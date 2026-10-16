package com.banknotify

import android.content.Context
import androidx.work.*
import com.banknotify.db.BankNotificationsDatabase
import com.banknotify.db.OutboxMessage
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
            .insert(OutboxMessage(eventKey = eventKey, message = message))
        if (id != -1L) {
            AppLog.i("Queue", "Сообщение #$id сохранено для доставки")
            schedule(context, id)
        }
    }

    suspend fun recover(context: Context) {
        BankNotificationsDatabase.getDatabase(context).outboxDao().pendingIds()
            .forEach { schedule(context, it) }
    }

    private suspend fun schedule(context: Context, id: Long) = withContext(Dispatchers.IO) {
        val work = OneTimeWorkRequestBuilder<TelegramDeliveryWorker>()
            .setInputData(workDataOf("message_id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Wait for WorkManager's database commit before releasing the SMS receiver.
        WorkManager.getInstance(context).enqueueUniqueWork("telegram-$id", ExistingWorkPolicy.KEEP, work)
            .result.get()
    }
}

class TelegramDeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getLong("message_id", -1)
        if (id < 0) return@withContext Result.failure()
        try {
            val db = BankNotificationsDatabase.getDatabase(applicationContext)
            val sender = TelegramSender(applicationContext, db.notificationDao())
            if (OutboxDelivery(db.outboxDao(), sender::deliver).deliver(id, System.currentTimeMillis())) {
                Result.success()
            } else Result.retry()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            AppLog.w("Delivery", "Не удалось обработать сообщение #$id; запланирован повтор")
            Result.retry()
        }
    }
}
