package com.banknotify

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.banknotify.db.BankNotificationDao
import com.google.gson.Gson
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

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

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "bank_notify",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    init {
        // Если настройки пустые - ставим дефолтные
        if (prefs.getString("telegram_token", "").isNullOrEmpty()) {
            prefs.edit()
                .putString("telegram_token", DEFAULT_TOKEN)
                .putString("telegram_chat_id", DEFAULT_CHAT_ID)
                .apply()
        }
    }

    private fun createHttpClient(rawProxy: String): OkHttpClient {
        val proxyConfig = TelegramProxyConfig.parse(rawProxy)
        if (rawProxy.isNotEmpty() && proxyConfig == null) {
            throw IOException("Invalid Telegram proxy format")
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)

        if (proxyConfig != null) {
            builder.proxy(proxyConfig.proxy)

            if (!proxyConfig.username.isNullOrEmpty() && proxyConfig.password != null) {
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) return@proxyAuthenticator null
                    response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(proxyConfig.username, proxyConfig.password))
                        .build()
                }
            }
        }

        return builder.build()
    }

    internal suspend fun deliver(message: String): TelegramDeliveryResult {
        val token = prefs.getString("telegram_token", "").orEmpty().trim()
        val chatId = prefs.getString("telegram_chat_id", "").orEmpty().trim()
        if (token.isEmpty() || chatId.isEmpty()) {
            return TelegramDeliveryResult(false, "Не задан токен или Chat ID")
        }
        val proxies = prefs.getString(PREF_TELEGRAM_PROXY, "").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
            .ifEmpty { listOf("") }
        return ProxyFailover.deliver(proxies) { proxy ->
            val body = Gson().toJson(mapOf("chat_id" to chatId, "text" to message))
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendMessage")
                .post(body).build()
            createHttpClient(proxy).newCall(request).awaitResponse { response ->
                TelegramDeliveryResult.parse(response.code, response.body?.string().orEmpty())
            }
        }
    }

    suspend fun sendSimple(message: String): Boolean = deliver(message).delivered

    suspend fun sendTest(): Boolean = sendSimple("BankNotify: Тестовое сообщение\nПриложение настроено корректно!")
}
