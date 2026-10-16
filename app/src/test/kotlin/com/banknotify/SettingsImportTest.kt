package com.banknotify

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.banknotify.db.BankNotification
import com.banknotify.db.BankNotificationsDatabase
import com.banknotify.db.OutboxMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SettingsImportTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("settings-import-test", Context.MODE_PRIVATE)
    private val password = "test password".toCharArray()
    private val backup = SettingsBackup.encode(mapOf("telegram_token" to "new", "healthcheck_enabled" to false), password)

    @Before fun setUp() {
        prefs.edit().clear().putString("telegram_token", "old")
            .putBoolean("sms_enabled", true).putString("healthcheck_status", "previous status").commit()
    }

    @Test fun `cancellation during read leaves preferences and scheduling untouched`() = runBlocking {
        var configured = false
        val job = launch {
            SettingsImport.run({ currentCoroutineContext().cancel(); backup }, password, { prefs }) { configured = true }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals("old", prefs.getString("telegram_token", null))
        assertTrue(prefs.getBoolean("sms_enabled", false))
        assertFalse(configured)
    }

    @Test fun `configuration completes if owner is destroyed after commit`() = runBlocking {
        val committed = CompletableDeferred<Unit>()
        val finishConfiguration = CompletableDeferred<Unit>()
        var configured = false
        val job = launch {
            SettingsImport.run({ backup }, password, { prefs }) {
                committed.complete(Unit)
                finishConfiguration.await()
                configured = true
            }
        }
        committed.await()
        job.cancel()
        finishConfiguration.complete(Unit)
        job.join()
        assertTrue(configured)
        assertEquals("new", prefs.getString("telegram_token", null))
        assertFalse(prefs.contains("sms_enabled"))
        assertEquals("previous status", prefs.getString("healthcheck_status", null))
    }

    @Test fun `wrong password leaves all existing preferences untouched`() = runBlocking {
        val original = prefs.all
        var configured = false
        try {
            SettingsImport.run({ backup }, "wrong password".toCharArray(), { prefs }) { configured = true }
            fail("Wrong password must fail")
        } catch (_: javax.crypto.AEADBadTagException) { }
        assertEquals(original, prefs.all)
        assertFalse(configured)
    }

    @Test fun `successful replacement preserves history and queued payments`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, BankNotificationsDatabase::class.java).build()
        try {
            val historyId = db.notificationDao().insert(BankNotification(
                bankName = "Bank", amount = "100", operationType = "income", accountNumber = "1234"))
            val queuedId = db.outboxDao().insert(OutboxMessage(eventKey = "existing", message = "100 ₽"))
            SettingsImport.run({ backup }, password, { prefs }) { }
            assertEquals(historyId, db.notificationDao().getRecentNotifications().single().id)
            assertEquals(listOf(queuedId), db.outboxDao().pendingIds())
            assertEquals("100 ₽", db.outboxDao().get(queuedId)?.message)
        } finally {
            db.close()
        }
    }
}
