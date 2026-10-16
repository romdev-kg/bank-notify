package com.banknotify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import java.util.concurrent.TimeUnit
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.banknotify.db.BankNotificationsDatabase
import com.banknotify.db.OutboxMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DeliveryQueueTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var workManager: WorkManager
    private lateinit var db: BankNotificationsDatabase

    private suspend fun normalWorkName(id: Long): String {
        val item = db.outboxDao().get(id)!!
        return "telegram-v3-$id-${item.attempts}-${item.nextAttemptAt}"
    }

    @Before fun setUp() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
        db = BankNotificationsDatabase.getDatabase(context)
        withContext(Dispatchers.IO) { db.clearAllTables() }
    }

    @After fun tearDown() = runBlocking {
        workManager.cancelAllWork().result.get()
        withContext(Dispatchers.IO) { db.clearAllTables() }
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test fun `recover enqueues bounded ordinary delay and wake leaves delayed work intact`() = runBlocking {
        val now = System.currentTimeMillis()
        val id = db.outboxDao().insert(OutboxMessage(eventKey = "recover-delay", message = "100 ₽", attempts = 20))
        OutboxDelivery(db.outboxDao(), { TelegramDeliveryResult(false, "Network error") }, { now }).deliver(id, now)
        DeliveryQueue.recover(context)
        val before = workManager.getWorkInfosForUniqueWork(normalWorkName(id)).get().single()
        assertTrue(before.initialDelayMillis in 290_000L..300_000L)
        assertEquals(0, before.runAttemptCount)
        DeliveryQueue.wake(context)
        val after = workManager.getWorkInfosForUniqueWork(normalWorkName(id)).get().single()
        assertEquals(before.id, after.id)
        assertEquals(WorkInfo.State.ENQUEUED, after.state)
        val wake = workManager.getWorkInfosForUniqueWork("telegram-wake-v3-$id").get().single()
        assertEquals(0L, wake.initialDelayMillis)
        assertEquals(WorkInfo.State.ENQUEUED, wake.state)
    }

    @Test fun `new failure after wake does not sit behind obsolete server wait work`() = runBlocking {
        val now = System.currentTimeMillis()
        val id = db.outboxDao().insert(OutboxMessage(eventKey = "stale-server-work", message = "100 ₽"))
        OutboxDelivery(db.outboxDao(), { TelegramDeliveryResult(false, "HTTP 429", 600_000) }, { now }).deliver(id, now)
        DeliveryQueue.recover(context)
        // A later wake may run before an old delayed worker (for example after a wall clock change).
        val later = now + 600_000
        OutboxDelivery(db.outboxDao(), { TelegramDeliveryResult(false, "Network error") }, { later })
            .deliver(id, later, wake = true)
        DeliveryQueue.schedule(context, id)
        // The second attempt must have its own work, independent of the first attempt's chain.
        assertEquals(1, workManager.getWorkInfosForUniqueWork(normalWorkName(id)).get().size)
    }

    @Test fun `recover after clock rollback schedules ordinary retry within five minutes`() = runBlocking {
        val now = System.currentTimeMillis()
        val id = db.outboxDao().insert(OutboxMessage(eventKey = "recover-rollback", message = "100 ₽",
            attempts = 1, nextAttemptAt = now + 3_600_000))
        val obsolete = OneTimeWorkRequestBuilder<TelegramDeliveryWorker>()
            .setInitialDelay(3_600_000, TimeUnit.MILLISECONDS).build()
        workManager.enqueueUniqueWork(normalWorkName(id), ExistingWorkPolicy.KEEP, obsolete).result.get()
        DeliveryQueue.recover(context)
        assertEquals(1, db.outboxDao().get(id)!!.attempts)
        assertTrue(db.outboxDao().get(id)!!.nextAttemptAt in now..(now + 301_000))
        val scheduled = workManager.getWorkInfosForUniqueWork(normalWorkName(id)).get().single()
        assertNotEquals(obsolete.id, scheduled.id)
        assertTrue(scheduled.initialDelayMillis in 0L..300_000L)
    }

    @Test fun `recover preserves longer Telegram delay and waiting appends without WM backoff`() = runBlocking {
        val now = System.currentTimeMillis()
        val id = db.outboxDao().insert(OutboxMessage(eventKey = "server-delay", message = "100 ₽"))
        OutboxDelivery(db.outboxDao(), { TelegramDeliveryResult(false, "HTTP 429", 600_000) }, { now }).deliver(id, now)
        DeliveryQueue.recover(context)
        val initial = workManager.getWorkInfosForUniqueWork(normalWorkName(id)).get().single()
        assertTrue(initial.initialDelayMillis in 590_000L..600_000L)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(initial.id)
        driver.setInitialDelayMet(initial.id)
        // The test driver ran early, but the persisted server deadline prevents a send.
        withTimeout(5_000) {
            while (workManager.getWorkInfoById(initial.id).get()!!.state != WorkInfo.State.SUCCEEDED) delay(10)
        }
        val infos = workManager.getWorkInfosForUniqueWork(normalWorkName(id)).get()
        assertEquals(WorkInfo.State.SUCCEEDED, infos.single { it.id == initial.id }.state)
        val successor = infos.single { it.id != initial.id }
        assertTrue(successor.initialDelayMillis in 590_000L..600_000L)
        assertEquals(0, successor.runAttemptCount)
        assertEquals(1, db.outboxDao().get(id)!!.attempts)
    }
}
