package com.banknotify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.*
import com.banknotify.db.BankNotificationsDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

internal object HealthcheckPolicy {
    fun validUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.fragment == null
    }

    fun healthy(access: Boolean, connected: Boolean, smsRequired: Boolean, smsAllowed: Boolean,
                configured: Boolean, staleOrFailed: Boolean): Boolean =
        access && connected && (!smsRequired || smsAllowed) && configured && !staleOrFailed
}

class HealthcheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = AppSettings.available(applicationContext) ?: return@withContext Result.retry()
        if (!prefs.getBoolean("healthcheck_enabled", false)) return@withContext Result.success()
        val url = prefs.getString("healthcheck_url", "").orEmpty()
        if (!HealthcheckPolicy.validUrl(url)) return@withContext Result.success()
        try {
            val dao = BankNotificationsDatabase.getDatabase(applicationContext).outboxDao()
            val now = System.currentTimeMillis()
            val hasProblem = dao.hasDeliveryProblems(now - TimeUnit.MINUTES.toMillis(30))
            val healthy = HealthcheckPolicy.healthy(
                ListenerRecovery.hasAccess(applicationContext), BankNotificationListener.isConnected,
                prefs.getBoolean("sms_enabled", false),
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED,
                !prefs.getString("telegram_token", "").isNullOrBlank() && !prefs.getString("telegram_chat_id", "").isNullOrBlank(),
                hasProblem)
            if (!healthy) {
                AppLog.w("Healthcheck", "Пинг пропущен: сервис или доставка требуют внимания")
                prefs.edit().putString("healthcheck_status", "Пинг пропущен: обнаружена проблема").apply()
                return@withContext Result.success()
            }
            client.newCall(Request.Builder().url(url).get().build()).awaitResponse { response ->
                val success = response.isSuccessful
                prefs.edit().putString("healthcheck_status", if (success) "Успешный пинг" else "Ошибка HTTP ${response.code}")
                    .putLong("healthcheck_time", now).apply()
                if (success) Result.success() else Result.retry()
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            prefs.edit().putString("healthcheck_status", "Не удалось отправить пинг").apply()
            Result.retry()
        }
    }

    companion object {
        private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
        fun configure(context: Context) {
            val prefs = AppSettings.available(context) ?: return
            val manager = WorkManager.getInstance(context)
            if (prefs.getBoolean("healthcheck_enabled", false)) {
                manager.enqueueUniquePeriodicWork("external-healthcheck", ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<HealthcheckWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            } else manager.cancelUniqueWork("external-healthcheck")
        }
    }
}
