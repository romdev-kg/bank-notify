package com.banknotify

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.banknotify.db.BankNotification
import com.banknotify.db.BankNotificationDao
import com.google.gson.Gson
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class TelegramSender(
    context: Context,
    private val dao: BankNotificationDao
) {

    companion object {
        // Дефолтные значения - можно изменить в приложении
        const val DEFAULT_TOKEN = "8586959260:AAHNmCun_o_P4QJaC0mK3nG5LisL_K_gsPg"
        const val DEFAULT_CHAT_ID = "-1003459500291"
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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

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

                httpClient.newCall(request).execute().use { response ->
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

            httpClient.newCall(request).execute().use { response ->
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

            httpClient.newCall(request).execute().use { response ->
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
}
