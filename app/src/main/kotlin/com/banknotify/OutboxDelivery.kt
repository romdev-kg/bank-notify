package com.banknotify

import com.banknotify.db.OutboxDao
import com.banknotify.db.OutboxMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val MAX_DELIVERY_RETRY_DELAY_MILLIS = 300_000L

/** Shared by normal, legacy and explicit wake workers, including separate delivery instances. */
internal object OutboxAccess {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<Long, Entry>()

    suspend fun pending(dao: OutboxDao, id: Long): OutboxMessage? = withMessage(id) {
        normalizeDeadline(dao, dao.get(id), System.currentTimeMillis())
    }

    suspend fun normalizeDeadline(dao: OutboxDao, item: OutboxMessage?, now: Long): OutboxMessage? {
        if (item == null || item.sent || item.retryAfterAt > now ||
            item.nextAttemptAt - now <= MAX_DELIVERY_RETRY_DELAY_MILLIS) return item
        // Ordinary deadlines beyond the maximum can occur after a wall-clock rollback.
        // Persist the repair once, without changing attempts or ever shortening a Telegram deadline.
        val deadline = now + MAX_DELIVERY_RETRY_DELAY_MILLIS
        dao.reschedule(item.id, deadline)
        return item.copy(nextAttemptAt = deadline)
    }

    suspend fun <T> withMessage(id: Long, action: suspend () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(id) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { action() }
        } finally {
            synchronized(entries) {
                if (--entry.users == 0) entries.remove(id)
            }
        }
    }
}

internal class OutboxDelivery(
    private val dao: OutboxDao,
    private val send: suspend (String) -> TelegramDeliveryResult,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun deliver(id: Long, now: Long? = null, wake: Boolean = false): Boolean = OutboxAccess.withMessage(id) {
        // Read both state and time after acquiring the lock: another worker may just have sent it.
        val attemptTime = now ?: clock()
        val item = OutboxAccess.normalizeDeadline(dao, dao.get(id), attemptTime) ?: return@withMessage true
        if (item.sent) return@withMessage true
        if (item.retryAfterAt > attemptTime || (!wake && item.nextAttemptAt > attemptTime)) return@withMessage false
        val result = try {
            send(item.message)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Exception text may contain credentials or the notification body.
            TelegramDeliveryResult(false, "Не удалось прочитать настройки или выполнить отправку")
        }
        if (result.delivered) {
            dao.markSent(id)
            AppLog.i("Delivery", "Сообщение #$id доставлено в Telegram")
        } else {
            val responseTime = clock()
            val serverDeadline = if (result.retryAfterMillis > 0) responseTime + result.retryAfterMillis else 0L
            val ordinaryDelay = (30_000L shl item.attempts.coerceIn(0, 4)).coerceAtMost(MAX_DELIVERY_RETRY_DELAY_MILLIS)
            dao.failed(id, result.error ?: "Нет подтверждения Telegram",
                maxOf(responseTime + ordinaryDelay, serverDeadline), serverDeadline)
            AppLog.w("Delivery", "Сообщение #$id сохранено для повтора: ${result.error}")
        }
        result.delivered
    }
}
