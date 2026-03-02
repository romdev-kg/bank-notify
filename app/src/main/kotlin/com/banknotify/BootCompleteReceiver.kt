package com.banknotify

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
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

            if (isNotificationAccessGranted(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val cn = ComponentName(context, BankNotificationListener::class.java)
                    NotificationListenerService.requestRebind(cn)
                    AppLog.i(TAG, "requestRebind() called successfully")
                }
            } else {
                AppLog.w(TAG, "Notification access not granted, skipping rebind")
            }
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
