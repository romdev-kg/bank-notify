package com.banknotify

import java.io.IOException

internal object ProxyFailover {
    suspend fun deliver(proxies: List<String>, send: suspend (String) -> TelegramDeliveryResult): TelegramDeliveryResult {
        var last = TelegramDeliveryResult(false, "Прокси недоступны")
        for ((index, proxy) in proxies.withIndex()) {
            try {
                last = send(proxy)
                // Telegram application errors and rate limits are independent of the proxy.
                if (last.delivered || last.retryAfterMillis > 0 || (last.error?.startsWith("Telegram HTTP 4") == true && last.error != "Telegram HTTP 407")) return last
            } catch (_: IOException) {
                last = TelegramDeliveryResult(false, "Ошибка сети: маршрут ${index + 1}")
            } catch (_: IllegalArgumentException) {
                last = TelegramDeliveryResult(false, "Некорректные настройки: маршрут ${index + 1}")
            }
        }
        return last
    }
}
