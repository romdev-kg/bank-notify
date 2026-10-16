package com.banknotify

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.banknotify.db.BankNotificationsDatabase
import com.banknotify.db.OutboxMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class OutboxTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: BankNotificationsDatabase
    private fun open() = Room.databaseBuilder(context, BankNotificationsDatabase::class.java, "outbox-test.db")
        .addMigrations(BankNotificationsDatabase.MIGRATION_1_2, BankNotificationsDatabase.MIGRATION_2_3).allowMainThreadQueries().build()

    @Before fun setUp() { context.deleteDatabase("outbox-test.db"); db = open() }
    @After fun tearDown() { db.close(); context.deleteDatabase("outbox-test.db") }

    @Test fun `failed payment survives reopen and is marked sent only after confirmation`() = runBlocking {
        val id = db.outboxDao().insert(OutboxMessage(eventKey = "push:1", message = "500 ₽"))
        OutboxDelivery(db.outboxDao(), { TelegramDeliveryResult(false, "Network error") }, { 1000 }).deliver(id, 1000)
        db.close()
        db = open()
        val pending = db.outboxDao().get(id)!!
        assertFalse(pending.sent)
        assertEquals(1, pending.attempts)
        assertEquals("500 ₽", pending.message)
        OutboxDelivery(db.outboxDao(), { TelegramDeliveryResult(true) }).deliver(id, 31_000)
        assertTrue(db.outboxDao().get(id)!!.sent)
        assertNull(db.outboxDao().get(id)!!.lastError)
    }

    @Test fun `replayed source event deduplicates but identical separate payments do not`() = runBlocking {
        val dao = db.outboxDao()
        val id = dao.insert(OutboxMessage(eventKey = "push:1", message = "500 ₽"))
        assertEquals(-1L, dao.insert(OutboxMessage(eventKey = "push:1", message = "500 ₽")))
        assertTrue(dao.insert(OutboxMessage(eventKey = "push:2", message = "500 ₽")) > id)
        assertEquals(2, dao.pendingIds().size)
    }

    @Test fun `server retry delay prevents early retry and completed rows are not resent`() = runBlocking {
        val id = db.outboxDao().insert(OutboxMessage(eventKey = "sms:1", message = "100 ₽"))
        OutboxDelivery(db.outboxDao(), { TelegramDeliveryResult(false, "HTTP 429", 120_000) }, { 1000 }).deliver(id, 1000)
        var calls = 0
        val delivery = OutboxDelivery(db.outboxDao(), { calls++; TelegramDeliveryResult(true) })
        assertFalse(delivery.deliver(id, 120_999))
        assertEquals(0, calls)
        assertTrue(delivery.deliver(id, 121_000))
        assertTrue(delivery.deliver(id, 122_000))
        assertEquals(1, calls)
    }

    @Test fun `retry after is measured from response after slow proxy failover`() = runBlocking {
        val id = db.outboxDao().insert(OutboxMessage(eventKey = "slow", message = "100 ₽"))
        var clock = 1_000L
        val delivery = OutboxDelivery(db.outboxDao(), {
            clock = 61_000L
            TelegramDeliveryResult(false, "HTTP 429", 120_000)
        }, { clock })
        delivery.deliver(id, clock)
        assertEquals(181_000L, db.outboxDao().get(id)!!.nextAttemptAt)
    }

    @Test fun `ordinary failures back off from thirty seconds and never exceed five minutes`() = runBlocking {
        val dao = db.outboxDao()
        val id = dao.insert(OutboxMessage(eventKey = "backoff", message = "100 ₽"))
        var now = 1_000L
        val delivery = OutboxDelivery(dao, { TelegramDeliveryResult(false, "Network error") }, { now })
        for (expectedDelay in listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L)) {
            assertFalse(delivery.deliver(id, now))
            val pending = dao.get(id)!!
            assertEquals(expectedDelay, pending.nextAttemptAt - now)
            now = pending.nextAttemptAt
        }
        assertEquals(6, dao.get(id)!!.attempts)
    }

    @Test fun `ordinary retry deadline survives reopening database`() = runBlocking {
        val id = db.outboxDao().insert(OutboxMessage(eventKey = "restart", message = "100 ₽"))
        OutboxDelivery(db.outboxDao(), { TelegramDeliveryResult(false, "Network error") }, { 1_000 }).deliver(id, 1_000)
        db.close()
        db = open()
        var calls = 0
        val delivery = OutboxDelivery(db.outboxDao(), { calls++; TelegramDeliveryResult(true) })
        assertFalse(delivery.deliver(id, 30_999))
        assertEquals(0, calls)
        assertTrue(delivery.deliver(id, 31_000))
        assertEquals(1, calls)
    }

    @Test fun `waiting for server deadline never counts as another failed attempt`() = runBlocking {
        val dao = db.outboxDao()
        val id = dao.insert(OutboxMessage(eventKey = "wait", message = "100 ₽"))
        val delivery = OutboxDelivery(dao, { TelegramDeliveryResult(false, "HTTP 429", 600_000) }, { 1_000 })
        delivery.deliver(id, 1_000)
        repeat(10) { assertFalse(delivery.deliver(id, 300_000)) }
        assertEquals(1, dao.get(id)!!.attempts)
        assertEquals(601_000L, dao.get(id)!!.nextAttemptAt)
    }

    @Test fun `simultaneous workers cannot send the same row twice`() = runBlocking {
        val dao = db.outboxDao()
        val id = dao.insert(OutboxMessage(eventKey = "concurrent", message = "100 ₽"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val send: suspend (String) -> TelegramDeliveryResult = {
            calls++
            started.complete(Unit)
            release.await()
            TelegramDeliveryResult(true)
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { OutboxDelivery(dao, send).deliver(id, 1_000) }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { OutboxDelivery(dao, send).deliver(id, 1_000) }
        release.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(1, calls)
    }

    @Test fun `settings failure retains row with bounded retry and sanitized error`() = runBlocking {
        val dao = db.outboxDao()
        val id = dao.insert(OutboxMessage(eventKey = "keystore", message = "100 ₽"))
        val delivery = OutboxDelivery(dao, { throw IllegalStateException("secret token") }, { 1_000 })
        assertFalse(delivery.deliver(id, 1_000))
        val item = dao.get(id)!!
        assertEquals(31_000L, item.nextAttemptAt)
        assertEquals(1, item.attempts)
        assertFalse(item.lastError.orEmpty().contains("secret"))
        assertFalse(item.sent)
    }

    @Test fun `wake bypasses ordinary delay but preserves server deadline after restart`() = runBlocking {
        val dao = db.outboxDao()
        val id = dao.insert(OutboxMessage(eventKey = "wake", message = "100 ₽"))
        OutboxDelivery(dao, { TelegramDeliveryResult(false, "Network error") }, { 1_000 }).deliver(id, 1_000)
        var calls = 0
        val wake = OutboxDelivery(dao, { calls++; TelegramDeliveryResult(false, "HTTP 429", 600_000) }, { 2_000 })
        assertFalse(wake.deliver(id, 2_000, wake = true))
        assertEquals(1, calls)
        db.close()
        db = open()
        val resumed = OutboxDelivery(db.outboxDao(), { calls++; TelegramDeliveryResult(true) })
        assertFalse(resumed.deliver(id, 601_999, wake = true))
        assertEquals(1, calls)
        assertEquals(2, db.outboxDao().get(id)!!.attempts)
        assertTrue(resumed.deliver(id, 602_000, wake = true))
        assertEquals(2, calls)
    }

    @Test fun `wake failure is followed by ordinary delay again`() = runBlocking {
        val dao = db.outboxDao()
        val id = dao.insert(OutboxMessage(eventKey = "wake-fail", message = "100 ₽"))
        OutboxDelivery(dao, { TelegramDeliveryResult(false, "Network error") }, { 1_000 }).deliver(id, 1_000)
        OutboxDelivery(dao, { TelegramDeliveryResult(false, "Network error") }, { 2_000 }).deliver(id, 2_000, wake = true)
        var calls = 0
        val normal = OutboxDelivery(dao, { calls++; TelegramDeliveryResult(true) })
        assertFalse(normal.deliver(id, 61_999))
        assertEquals(0, calls)
        assertTrue(normal.deliver(id, 62_000))
        assertEquals(1, calls)
    }

    @Test fun `clock rollback cannot strand an ordinary retry beyond its maximum delay`() = runBlocking {
        val dao = db.outboxDao()
        val id = dao.insert(OutboxMessage(eventKey = "rollback", message = "100 ₽"))
        OutboxDelivery(dao, { TelegramDeliveryResult(false, "Network error") }, { 3_601_000 })
            .deliver(id, 3_601_000)
        var calls = 0
        val resumed = OutboxDelivery(dao, { calls++; TelegramDeliveryResult(true) }, { 1_000 })
        assertFalse(resumed.deliver(id, 1_000))
        assertEquals(301_000L, dao.get(id)!!.nextAttemptAt)
        assertEquals(1, dao.get(id)!!.attempts)
        assertEquals(0, calls)
        assertTrue(resumed.deliver(id, 301_000))
        assertEquals(1, calls)
        assertTrue(dao.get(id)!!.sent)
    }

    @Test fun `clock rollback never bypasses a persisted Telegram deadline`() = runBlocking {
        val dao = db.outboxDao()
        val id = dao.insert(OutboxMessage(eventKey = "rollback-server", message = "100 ₽"))
        OutboxDelivery(dao, { TelegramDeliveryResult(false, "HTTP 429", 600_000) }, { 3_601_000 })
            .deliver(id, 3_601_000)
        var calls = 0
        val resumed = OutboxDelivery(dao, { calls++; TelegramDeliveryResult(true) }, { 1_000 })
        assertFalse(resumed.deliver(id, 1_000))
        assertFalse(resumed.deliver(id, 1_000, wake = true))
        assertEquals(0, calls)
        assertEquals(1, dao.get(id)!!.attempts)
    }

    @Test fun `migration from version two preserves Telegram retry after and dedup keys`() = runBlocking {
        db.close()
        context.deleteDatabase("outbox-test.db")
        context.openOrCreateDatabase("outbox-test.db", 0, null).use { old ->
            old.execSQL("CREATE TABLE bank_notifications (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bankName TEXT NOT NULL, amount TEXT NOT NULL, operationType TEXT NOT NULL, accountNumber TEXT NOT NULL, timestamp INTEGER NOT NULL, sentToTelegram INTEGER NOT NULL, errorMessage TEXT)")
            old.execSQL("CREATE TABLE telegram_outbox (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, eventKey TEXT NOT NULL, message TEXT NOT NULL, createdAt INTEGER NOT NULL, sent INTEGER NOT NULL, attempts INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL, lastError TEXT)")
            old.execSQL("CREATE UNIQUE INDEX index_telegram_outbox_eventKey ON telegram_outbox(eventKey)")
            old.execSQL("INSERT INTO telegram_outbox VALUES (1, 'old-event', '100 ₽', 1000, 0, 3, 601000, 'HTTP 429')")
            old.version = 2
        }
        db = open()
        val dao = db.outboxDao()
        assertEquals(601_000L, dao.get(1)!!.retryAfterAt)
        assertEquals(-1L, dao.insert(OutboxMessage(eventKey = "old-event", message = "100 ₽")))
        var calls = 0
        val delivery = OutboxDelivery(dao, { calls++; TelegramDeliveryResult(true) })
        assertFalse(delivery.deliver(1, 600_999, wake = true))
        assertEquals(0, calls)
        assertTrue(delivery.deliver(1, 601_000, wake = true))
        assertEquals(1, calls)
    }

    @Test fun `migration retains old history and creates durable outbox`() = runBlocking {
        db.close()
        context.deleteDatabase("outbox-test.db")
        context.openOrCreateDatabase("outbox-test.db", 0, null).use { old ->
            old.execSQL("CREATE TABLE bank_notifications (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bankName TEXT NOT NULL, amount TEXT NOT NULL, operationType TEXT NOT NULL, accountNumber TEXT NOT NULL, timestamp INTEGER NOT NULL, sentToTelegram INTEGER NOT NULL, errorMessage TEXT)")
            old.execSQL("INSERT INTO bank_notifications VALUES (7, 'Bank', '100', 'income', '1234', 123, 1, NULL)")
            old.version = 1
        }
        db = open()
        assertEquals("100", db.notificationDao().getRecentNotifications().single().amount)
        assertTrue(db.outboxDao().insert(OutboxMessage(eventKey = "new", message = "200 ₽")) > 0)
    }
}
