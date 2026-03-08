package com.banknotify

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService

class BootCompleteReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompleteReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        val action = intent?.action ?: return

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {

            AppLog.i(TAG, "Boot completed, requesting rebind for notification listener")

            if (!isNotificationAccessGranted(context)) {
                AppLog.w(TAG, "Notification access not granted, skipping rebind")
                return
            }

            // Сразу пробуем requestRebind
            rebind(context)

            // Через 5 сек — toggle компонента + повторный rebind (надёжнее на проблемных устройствах)
            Handler(Looper.getMainLooper()).postDelayed({
                if (!BankNotificationListener.isConnected) {
                    AppLog.i(TAG, "Listener ещё не подключён, toggle компонента")
                    toggleComponent(context)
                }
            }, 5000)

            // Через 15 сек — ещё одна попытка
            Handler(Looper.getMainLooper()).postDelayed({
                if (!BankNotificationListener.isConnected) {
                    AppLog.i(TAG, "Listener ещё не подключён, повторный rebind")
                    rebind(context)
                }
            }, 15000)
        }
    }

    private fun rebind(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val cn = ComponentName(context, BankNotificationListener::class.java)
            NotificationListenerService.requestRebind(cn)
            AppLog.i(TAG, "requestRebind() called")
        }
    }

    private fun toggleComponent(context: Context) {
        try {
            val pm = context.packageManager
            val cn = ComponentName(context, BankNotificationListener::class.java)
            // Выключаем компонент
            pm.setComponentEnabledSetting(
                cn,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            // Включаем обратно — Android перебиндит listener
            pm.setComponentEnabledSetting(
                cn,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            AppLog.i(TAG, "Component toggled, requesting rebind")
            rebind(context)
        } catch (e: Exception) {
            AppLog.e(TAG, "Toggle failed: ${e.message}")
        }
    }

    private fun isNotificationAccessGranted(context: Context): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return enabledListeners?.contains(context.packageName) == true
    }
}
