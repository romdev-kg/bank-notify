package com.banknotify

import com.banknotify.db.OutboxDao

internal class OutboxDelivery(
    private val dao: OutboxDao,
    private val send: suspend (String) -> TelegramDeliveryResult,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun deliver(id: Long, now: Long = clock()): Boolean {
        val item = dao.get(id) ?: return true
        if (item.sent) return true
        if (item.nextAttemptAt > now) return false
        val result = send(item.message)
        if (result.delivered) {
            dao.markSent(id)
            AppLog.i("Delivery", "Сообщение #$id доставлено в Telegram")
        } else {
            dao.failed(id, result.error ?: "Нет подтверждения Telegram", clock() + result.retryAfterMillis)
            AppLog.w("Delivery", "Сообщение #$id сохранено для повтора: ${result.error}")
        }
        return result.delivered
    }
}
