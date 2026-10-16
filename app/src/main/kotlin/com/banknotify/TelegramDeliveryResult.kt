package com.banknotify

import com.google.gson.JsonParser

internal data class TelegramDeliveryResult(
    val delivered: Boolean,
    val error: String? = null,
    val retryAfterMillis: Long = 0
) {
    companion object {
        fun parse(status: Int, body: String): TelegramDeliveryResult {
            return try {
                val json = JsonParser.parseString(body).asJsonObject
                val ok = json.get("ok")?.asBoolean == true
                val confirmed = json.getAsJsonObject("result")?.get("message_id")?.asLong != null
                if (status in 200..299 && ok && confirmed) {
                    TelegramDeliveryResult(true)
                } else {
                    val retrySeconds = json.getAsJsonObject("parameters")?.get("retry_after")?.asLong ?: 0L
                    TelegramDeliveryResult(false, "Telegram HTTP $status", retrySeconds.coerceIn(0, 604_800) * 1000)
                }
            } catch (_: RuntimeException) {
                TelegramDeliveryResult(false, "Некорректный ответ Telegram (HTTP $status)")
            }
        }
    }
}
