package com.banknotify

import android.content.Context
import com.banknotify.db.BankNotificationDao
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class TelegramSender(
    context: Context,
    private val dao: BankNotificationDao
) {

    companion object {
        // Пустые значения по умолчанию - пользователь должен ввести свои
        const val DEFAULT_TOKEN = ""
        const val DEFAULT_CHAT_ID = ""
        const val PREF_TELEGRAM_PROXY = "telegram_proxy"
    }

    private val prefs = AppSettings.preferences(context)

    internal suspend fun deliver(message: String): TelegramDeliveryResult {
        val token = prefs.getString("telegram_token", "").orEmpty().trim()
        val chatId = prefs.getString("telegram_chat_id", "").orEmpty().trim()
        if (token.isEmpty() || chatId.isEmpty()) {
            return TelegramDeliveryResult(false, "Не задан токен или Chat ID")
        }
        val proxyText = prefs.getString(PREF_TELEGRAM_PROXY, "").orEmpty()
        // Older releases accepted invalid routes. Skip them individually so valid backups still work.
        val proxies = proxyText
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
            .ifEmpty { listOf("") }
        return ProxyFailover.deliver(proxies) { proxy ->
            val body = Gson().toJson(mapOf("chat_id" to chatId, "text" to message))
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendMessage")
                .post(body).build()
            TelegramHttpClients.forProxy(proxy).newCall(request).awaitResponse { response ->
                TelegramDeliveryResult.parse(response.code, response.body?.string().orEmpty())
            }
        }
    }

    suspend fun sendSimple(message: String): Boolean = deliver(message).delivered

    suspend fun sendTest(): Boolean = sendSimple("BankNotify: Тестовое сообщение\nПриложение настроено корректно!")
}
