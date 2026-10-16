package com.banknotify

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

object ListenerRecovery {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: ListenerReconnectLoop? = null

    fun hasAccess(context: Context): Boolean {
        val component = ComponentName(context, BankNotificationListener::class.java)
        return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')?.mapNotNull(ComponentName::unflattenFromString)?.contains(component) == true
    }

    fun ensureConnected(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (BankNotificationListener.isConnected || !hasAccess(app)) {
                loop?.stop()
                return@launch
            }
            if (loop == null) {
                loop = ListenerReconnectLoop(scope, { hasAccess(app) && !BankNotificationListener.isConnected }) {
                    try {
                        NotificationListenerService.requestRebind(ComponentName(app, BankNotificationListener::class.java))
                        AppLog.i("Listener", "Запрошено переподключение к системе")
                    } catch (_: Exception) {
                        AppLog.w("Listener", "Не удалось запросить подключение; будет повтор")
                    }
                }
            }
            loop?.start()
        }
    }

    fun connected() { scope.launch { loop?.stop() } }

    fun schedule(context: Context) {
        ensureConnected(context)
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork("listener-recovery", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ListenerRecoveryWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build())
    }
}

class ListenerRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!ListenerRecovery.hasAccess(applicationContext) || BankNotificationListener.isConnected) return Result.success()
        withContext(Dispatchers.Main.immediate) { ListenerRecovery.ensureConnected(applicationContext) }
        val delay = ListenerReconnectLoop.nextDelay(inputData.getLong("previous_delay", 0))
        val next = OneTimeWorkRequestBuilder<ListenerRecoveryWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("previous_delay" to delay))
            .build()
        // Append a persisted successor before finishing. WorkManager's built-in exponential
        // retry grows to five HOURS, so it cannot enforce our five-minute reconnect cap.
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork("listener-recovery", ExistingWorkPolicy.APPEND_OR_REPLACE, next).result.get()
        }
        return Result.success()
    }
}
