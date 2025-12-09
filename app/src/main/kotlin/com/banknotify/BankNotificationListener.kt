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

        // Сбербанк
        private const val SBER_PACKAGE = "ru.sberbankmobile"

        // Россельхозбанк
        private const val RSHB_PACKAGE = "ru.rshb.dbo"

        // Ключевые слова для зачислений
        private val INCOME_KEYWORDS = listOf(
            "зачисление", "поступление", "перевод от", "получен перевод",
            "входящий перевод", "пополнение", "возврат", "кэшбэк",
            "начисление", "зачислено", "поступило", "+", "получено"
        )
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

        // Только Сбербанк и Россельхозбанк
        val bankName = when (packageName) {
            SBER_PACKAGE -> "Сбербанк"
            RSHB_PACKAGE -> "Россельхозбанк"
            else -> return
        }

        val notification = sbn.notification
        val extras = notification.extras

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val fullText = "$title $text".lowercase()

        Log.d(TAG, "Bank notification from $bankName: $title - $text")

        // Проверяем что это зачисление
        val isIncome = INCOME_KEYWORDS.any { keyword -> fullText.contains(keyword) }
        if (!isIncome) {
            Log.d(TAG, "Skipping non-income notification")
            return
        }

        scope.launch {
            try {
                val message = """
                    💰 <b>$bankName</b>

                    $text
                """.trimIndent()

                telegramSender.sendSimple(message)
                Log.d(TAG, "Income notification forwarded from $bankName")
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
