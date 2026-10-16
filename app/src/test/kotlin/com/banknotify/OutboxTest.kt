package com.banknotify

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.banknotify.db.BankNotificationsDatabase
import com.banknotify.db.OutboxMessage
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
        .addMigrations(BankNotificationsDatabase.MIGRATION_1_2).allowMainThreadQueries().build()

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
        OutboxDelivery(db.outboxDao(), { TelegramDeliveryResult(true) }).deliver(id, 2000)
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
