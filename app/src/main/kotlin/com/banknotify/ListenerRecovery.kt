package com.banknotify

import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

object ListenerRecovery {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: ListenerReconnectLoop? = null

    private val connectionState = ListenerConnectionState()

    internal fun status(context: Context): ListenerStatus = connectionState.update(
        hasAccess(context), BankNotificationListener.isConnected, SystemClock.elapsedRealtime())

    fun hasAccess(context: Context): Boolean {
        val component = ComponentName(context, BankNotificationListener::class.java)
        if (Build.VERSION.SDK_INT >= 27) {
            return context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        }
        return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')?.mapNotNull(ComponentName::unflattenFromString)?.contains(component) == true
    }

    fun ensureConnected(context: Context) {
        val app = context.applicationContext
        scope.launch {
            status(app)
            if (BankNotificationListener.isConnected || !hasAccess(app)) {
                loop?.stop()
                return@launch
            }
            if (loop == null) {
                loop = ListenerReconnectLoop(scope, { hasAccess(app) && !BankNotificationListener.isConnected }) {
                    try {
                        NotificationListenerService.requestRebind(ComponentName(app, BankNotificationListener::class.java))
                        AppLog.i("Listener", "Вызван requestRebind; подтверждения системы пока нет")
                    } catch (_: Exception) {
                        AppLog.w("Listener", "Не удалось запросить подключение; будет повтор")
                    }
                    status(app)
                    toggleIfStalled(app)
                }
            }
            loop?.start()
        }
    }

    private fun toggleIfStalled(app: Context) {
        val now = SystemClock.elapsedRealtime()
        val prefs = app.getSharedPreferences("listener_recovery", Context.MODE_PRIVATE)
        val last = prefs.getLong("toggle_at", -1L).takeIf { it >= 0 }
        if (!ListenerTogglePolicy.shouldToggle(connectionState.disconnectedFor(now), last, now)) return
        prefs.edit().putLong("toggle_at", now).apply()
        // Flip a dummy component, never the listener itself: a PACKAGE_CHANGED handled while the
        // listener is disabled would make ManagedServices drop the notification-access grant.
        val trigger = ComponentName(app, ListenerRebindTrigger::class.java)
        val pm = app.packageManager
        try {
            val next = if (pm.getComponentEnabledSetting(trigger) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            pm.setComponentEnabledSetting(trigger, next, PackageManager.DONT_KILL_APP)
            AppLog.i("Listener", "Запрошена повторная привязка через изменение пакета")
        } catch (_: Exception) {
            AppLog.w("Listener", "Не удалось запросить повторную привязку через изменение пакета")
        }
    }

    /** Repairs a listener left disabled by the 1.5.x component toggle. */
    fun ensureComponentEnabled(context: Context) {
        val component = ComponentName(context, BankNotificationListener::class.java)
        val pm = context.packageManager
        try {
            val state = pm.getComponentEnabledSetting(component)
            if (state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
            }
        } catch (_: Exception) {
            AppLog.w("Listener", "Не удалось включить компонент слушателя")
        }
    }

    fun connected() { scope.launch { connectionState.update(true, true, SystemClock.elapsedRealtime()); loop?.stop() } }

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
