package com.banknotify

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal object ProxyFailover {
    private const val DELIVERY_TIMEOUT_MILLIS = 480_000L

    suspend fun deliver(proxies: List<String>, send: suspend (String) -> TelegramDeliveryResult): TelegramDeliveryResult = withTimeoutOrNull(DELIVERY_TIMEOUT_MILLIS) {
        // Leave time for the worker to persist its result before WorkManager's execution limit.
        // Long lists share the budget: later routes still get a turn, with a shorter timeout.
        val routeTimeoutMillis = minOf(30_000L, DELIVERY_TIMEOUT_MILLIS / proxies.size.coerceAtLeast(1)).coerceAtLeast(1L)
        var last = TelegramDeliveryResult(false, "Прокси недоступны")
        for ((index, proxy) in proxies.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                last = withTimeoutOrNull(routeTimeoutMillis) {
                    try {
                        val result = send(proxy)
                        currentCoroutineContext().ensureActive()
                        result
                    } catch (e: IOException) {
                        // A cancelled route may report an I/O error while its socket closes.
                        // Check this child scope before the error leaves the timeout boundary.
                        currentCoroutineContext().ensureActive()
                        throw e
                    } catch (e: IllegalArgumentException) {
                        currentCoroutineContext().ensureActive()
                        throw e
                    }
                }
                    ?: TelegramDeliveryResult(false, "Превышено время ожидания: маршрут ${index + 1}")
                currentCoroutineContext().ensureActive()
                // Telegram application errors and rate limits are independent of the proxy.
                if (last.delivered || last.retryAfterMillis > 0 || (last.error?.startsWith("Telegram HTTP 4") == true && last.error != "Telegram HTTP 407")) return@withTimeoutOrNull last
            } catch (_: IOException) {
                last = TelegramDeliveryResult(false, "Ошибка сети: маршрут ${index + 1}")
            } catch (_: IllegalArgumentException) {
                last = TelegramDeliveryResult(false, "Некорректные настройки: маршрут ${index + 1}")
            }
        }
        currentCoroutineContext().ensureActive()
        last
    } ?: TelegramDeliveryResult(false, "Превышено общее время попытки доставки")
}
