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
        private const val SBER_PACKAGE = "ru.sberbankmobile"
        private const val RSHB_PACKAGE = "ru.rshb.mbank"
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
        val notification = sbn.notification
        val extras = notification.extras

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""

        Log.d(TAG, "Notification from $packageName: $title - $text")

        scope.launch {
            try {
                val bankNotif = when (packageName) {
                    SBER_PACKAGE -> BankNotificationParser.parseSber(text, title)
                    RSHB_PACKAGE -> BankNotificationParser.parseRshb(text, title)
                    else -> null
                }

                if (bankNotif != null) {
                    val id = database.notificationDao().insert(bankNotif)
                    telegramSender.send(bankNotif, id)
                    Log.d(TAG, "Notification processed and sent: $bankNotif")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing notification", e)
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
