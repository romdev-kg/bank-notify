package com.banknotify

import android.content.Context
import androidx.work.*
import com.banknotify.db.BankNotificationsDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object DeliveryQueue {
    fun eventKey(vararg parts: String): String {
        // Length prefixes prevent ambiguous concatenations; timestamps distinguish separate payments.
        val value = parts.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    suspend fun enqueue(context: Context, eventKey: String, message: String) {
        val id = BankNotificationsDatabase.getDatabase(context).outboxDao()
            .insert(com.banknotify.db.OutboxMessage(eventKey = eventKey, message = message))
        if (id != -1L) {
            AppLog.i("Queue", "Сообщение #$id сохранено для доставки")
            schedule(context, id)
        }
    }

    suspend fun recover(context: Context) {
        BankNotificationsDatabase.getDatabase(context).outboxDao().pendingIds()
            .forEach { schedule(context, it) }
    }

    /** Explicit settings recovery never cancels an in-flight HTTP request. */
    suspend fun wake(context: Context) {
        BankNotificationsDatabase.getDatabase(context).outboxDao().pendingIds()
            .forEach { schedule(context, it, wake = true) }
    }

    internal suspend fun schedule(context: Context, id: Long, wake: Boolean = false, previousAttempt: Int? = null, previousDeadline: Long? = null) {
        val dao = BankNotificationsDatabase.getDatabase(context).outboxDao()
        val item = OutboxAccess.pending(dao, id) ?: return
        if (item.sent) return
        val delay = if (wake) 0L else (item.nextAttemptAt - System.currentTimeMillis()).coerceAtLeast(0L)
        enqueueWork(context, id, delay, wake, item.attempts, item.nextAttemptAt,
            continuation = previousAttempt == item.attempts && previousDeadline == item.nextAttemptAt)
    }

    internal suspend fun enqueueWork(context: Context, id: Long, delay: Long, wake: Boolean, attempt: Int, deadline: Long, continuation: Boolean) = withContext(Dispatchers.IO) {
        val work = OneTimeWorkRequestBuilder<TelegramDeliveryWorker>()
            .setInputData(workDataOf("message_id" to id, "wake" to wake, "attempt" to attempt, "deadline" to deadline))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            // Used only if the scheduler/database itself fails; delivery retries use persisted deadlines.
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        // A new namespace avoids inheriting pre-v3 WorkManager exponential delays (up to five hours).
        // Each failed send or clock repair gets its own identity, so stale delayed successors cannot hold up
        // a new retry. Only an early worker waiting for the same deadline appends to its own chain.
        // Wake work is always immediate and never owns a delayed successor.
        val name = if (wake) "telegram-wake-v3-$id" else "telegram-v3-$id-$attempt-$deadline"
        val policy = if (wake || continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        // Commit the successor before this worker finishes. Process death on either side is recoverable:
        // WorkManager reruns the unfinished parent, and every worker rereads the locked outbox row.
        WorkManager.getInstance(context).enqueueUniqueWork(name, policy, work).result.get()
    }
}

class TelegramDeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getLong("message_id", -1)
        if (id < 0) return@withContext Result.failure()
        val wake = inputData.getBoolean("wake", false)
        val attempt = inputData.getInt("attempt", -1)
        val deadline = inputData.getLong("deadline", 0)
        try {
            val db = BankNotificationsDatabase.getDatabase(applicationContext)
            val delivered = OutboxDelivery(db.outboxDao(), { message ->
                // Settings/Keystore initialization failures are handled as ordinary persisted retries.
                TelegramSender(applicationContext, db.notificationDao()).deliver(message)
            }).deliver(id, wake = wake)
            if (!delivered) DeliveryQueue.schedule(applicationContext, id,
                previousAttempt = if (wake) null else attempt, previousDeadline = if (wake) null else deadline)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            AppLog.w("Delivery", "Не удалось обработать сообщение #$id; запланирован повтор")
            // An infrastructure failure must not leave a successfully finished worker without a successor.
            try {
                DeliveryQueue.enqueueWork(applicationContext, id, MAX_DELIVERY_RETRY_DELAY_MILLIS, wake = false, attempt = attempt, deadline = deadline, continuation = !wake)
                Result.success()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                Result.retry()
            }
        }
    }
}
