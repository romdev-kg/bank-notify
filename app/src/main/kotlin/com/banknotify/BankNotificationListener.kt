package com.banknotify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.banknotify.db.BankNotificationsDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BankNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var database: BankNotificationsDatabase
    private lateinit var telegramSender: TelegramSender

    companion object {
        private const val TAG = "BankNotifyListener"
        private const val TELEGRAM_PACKAGE = "org.telegram.messenger"
    }

    override fun onCreate() {
        super.onCreate()
        database = BankNotificationsDatabase.getDatabase(applicationContext)
        telegramSender = TelegramSender(applicationContext, database.notificationDao())
        Log.d(TAG, "BankNotificationListener created")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val packageName = sbn.packageName

        // Только Telegram
        if (packageName != TELEGRAM_PACKAGE) return

        val notification = sbn.notification
        val extras = notification.extras

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""

        Log.d(TAG, "Telegram notification: $title - $text")

        scope.launch {
            try {
                telegramSender.sendSimple("📩 <b>$title</b>\n\n$text")
                Log.d(TAG, "Telegram notification forwarded")
            } catch (e: Exception) {
                Log.e(TAG, "Error forwarding notification", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Optional: log removed notifications
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Log.d(TAG, "BankNotificationListener destroyed")
    }
}
