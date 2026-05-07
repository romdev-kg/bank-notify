package com.banknotify

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.banknotify.db.BankNotification
import com.banknotify.db.BankNotificationDao
import com.google.gson.Gson
import kotlinx.coroutines.delay
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
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

    private fun createHttpClient(): OkHttpClient {
        val rawProxy = prefs.getString(PREF_TELEGRAM_PROXY, "").orEmpty().trim()
        val proxyConfig = parseProxyConfig(rawProxy)
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
                    response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(proxyConfig.username, proxyConfig.password))
                        .build()
                }
            }
        }

        return builder.build()
    }

    suspend fun send(notification: BankNotification, id: Long) {
        val token = prefs.getString("telegram_token", "") ?: return
        val chatId = prefs.getString("telegram_chat_id", "") ?: return

        if (token.isEmpty() || chatId.isEmpty()) {
            dao.recordError(id, "Token or Chat ID not set")
            return
        }

        val message = formatMessage(notification)

        repeat(3) { attempt ->
            try {
                val requestBody = mapOf(
                    "chat_id" to chatId,
                    "text" to message,
                    "parse_mode" to "HTML"
                ).let {
                    Gson().toJson(it).toRequestBody("application/json".toMediaType())
                }

                val request = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendMessage")
                    .post(requestBody)
                    .build()

                createHttpClient().newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        dao.markAsSent(id)
                        Log.d("TelegramSender", "Message sent successfully")
                        return
                    } else {
                        Log.e("TelegramSender", "Error ${response.code}: ${response.message}")
                        if (attempt < 2) {
                            delay((1000L shl attempt))
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e("TelegramSender", "Network error on attempt $attempt", e)
                if (attempt == 2) {
                    dao.recordError(id, e.message ?: "Network error")
                } else {
                    delay((1000L shl attempt))
                }
            }
        }
    }

    suspend fun sendSimple(message: String): Boolean {
        val token = prefs.getString("telegram_token", "") ?: return false
        val chatId = prefs.getString("telegram_chat_id", "") ?: return false

        if (token.isEmpty() || chatId.isEmpty()) return false

        return try {
            val requestBody = mapOf(
                "chat_id" to chatId,
                "text" to message,
                "parse_mode" to "HTML"
            ).let {
                Gson().toJson(it).toRequestBody("application/json".toMediaType())
            }

            val request = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendMessage")
                .post(requestBody)
                .build()

            createHttpClient().newCall(request).execute().use { response ->
                Log.d("TelegramSender", "Response: ${response.code}")
                response.isSuccessful
            }
        } catch (e: IOException) {
            Log.e("TelegramSender", "Send failed", e)
            false
        }
    }

    suspend fun sendTest(): Boolean {
        val token = prefs.getString("telegram_token", "") ?: return false
        val chatId = prefs.getString("telegram_chat_id", "") ?: return false

        if (token.isEmpty() || chatId.isEmpty()) {
            return false
        }

        return try {
            val message = "BankNotify: Тестовое сообщение\nПриложение настроено корректно!"
            val requestBody = mapOf(
                "chat_id" to chatId,
                "text" to message,
                "parse_mode" to "HTML"
            ).let {
                Gson().toJson(it).toRequestBody("application/json".toMediaType())
            }

            val request = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendMessage")
                .post(requestBody)
                .build()

            createHttpClient().newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: IOException) {
            Log.e("TelegramSender", "Test message failed", e)
            false
        }
    }

    private fun formatMessage(notification: BankNotification): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale("ru", "RU")).format(notification.timestamp)
        return """
            <b>${notification.bankName}</b>

            <b>Тип:</b> ${notification.operationType}
            <b>Сумма:</b> ${notification.amount} руб.
            <b>Счёт:</b> ...${notification.accountNumber}
            <b>Время:</b> $time
        """.trimIndent()
    }

    private fun parseProxyConfig(rawValue: String): ProxyConfig? {
        val value = rawValue.trim()
        if (value.isEmpty()) return null

        return runCatching {
            val uri = URI(if (value.contains("://")) value else "http://$value")
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else return null
            val type = when (uri.scheme?.lowercase(Locale.US)) {
                "socks", "socks4", "socks5" -> Proxy.Type.SOCKS
                else -> Proxy.Type.HTTP
            }
            val credentials = uri.userInfo?.split(":", limit = 2)

            ProxyConfig(
                proxy = Proxy(type, InetSocketAddress(host, port)),
                username = credentials?.getOrNull(0)?.urlDecode(),
                password = credentials?.getOrNull(1)?.urlDecode()
            )
        }.getOrNull()
    }

    private fun String.urlDecode(): String =
        URLDecoder.decode(this, StandardCharsets.UTF_8.name())

    private data class ProxyConfig(
        val proxy: Proxy,
        val username: String?,
        val password: String?
    )
}
