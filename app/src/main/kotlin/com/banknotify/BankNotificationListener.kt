package com.banknotify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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

        // Ключевые слова для зачислений
        private val INCOME_KEYWORDS = listOf(
            "зачисление", "поступление", "перевод от", "получен перевод",
            "входящий перевод", "пополнение", "возврат", "кэшбэк",
            "начисление", "зачислено", "поступило", "+", "получено"
        )

        // Регулярка для извлечения суммы
        private val AMOUNT_REGEX = Regex("""[+＋]?\s*(\d[\d\s]*[.,]?\d*)\s*(?:₽|руб|RUB|р)(?:\s|$|\.)""", RegexOption.IGNORE_CASE)

        // Регулярки для извлечения отправителя
        private val SENDER_PATTERNS = listOf(
            // Сбер: "Перевод от Семён Дмитриевич П."
            Regex("""перевод от\s+([А-ЯЁа-яё]+\s+[А-ЯЁа-яё]+\.?\s*[А-ЯЁа-яё]?\.?)""", RegexOption.IGNORE_CASE),
            // РСХБ: "Семен Дмитриевич П из Сбербанк"
            Regex("""СБП[.\s]+([А-ЯЁа-яё]+\s+[А-ЯЁа-яё]+\.?\s*[А-ЯЁа-яё]?\.?)\s+из""", RegexOption.IGNORE_CASE),
            // Общий: "от Имя Фамилия"
            Regex("""от\s+([А-ЯЁа-яё]+\s+[А-ЯЁа-яё]+\.?\s*[А-ЯЁа-яё]?\.?)""", RegexOption.IGNORE_CASE)
        )
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            applicationContext,
            "bank_notify",
            MasterKey.Builder(applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
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

        // Проверяем есть ли приложение в списке отслеживаемых и включено ли оно
        if (!BankAppsManager.isAppTracked(applicationContext, packageName)) return
        if (!BankAppsManager.isAppEnabled(applicationContext, packageName)) return

        val notification = sbn.notification
        val extras = notification.extras

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val fullText = "$title $text".lowercase()

        // Получаем имя приложения
        val appName = try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        Log.d(TAG, "Notification from $appName: $title - $text")

        // Проверяем что это зачисление
        val isIncome = INCOME_KEYWORDS.any { keyword -> fullText.contains(keyword) }
        if (!isIncome) {
            Log.d(TAG, "Skipping non-income notification")
            return
        }

        // Извлекаем сумму
        val amount = extractAmount(fullText)
        if (amount == null) {
            Log.d(TAG, "Could not extract amount from: $fullText")
            return
        }

        // Извлекаем отправителя
        val sender = extractSender(fullText)

        scope.launch {
            try {
                val message = buildString {
                    append("$amount ₽")
                    if (sender != null) {
                        append(" от $sender")
                    }
                    append(" ($appName)")
                }

                telegramSender.sendSimple(message)
                Log.d(TAG, "Income notification forwarded: $amount from $appName, sender: $sender")
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

    private fun extractAmount(text: String): String? {
        val match = AMOUNT_REGEX.find(text) ?: return null
        val rawAmount = match.groupValues[1]
            .replace(" ", "")
            .replace(",", ".")
            .trim()

        // Форматируем сумму
        return try {
            val number = rawAmount.toDouble()
            if (number == number.toLong().toDouble()) {
                number.toLong().toString()
            } else {
                String.format("%.2f", number)
            }
        } catch (e: NumberFormatException) {
            rawAmount
        }
    }

    private fun extractSender(text: String): String? {
        for (pattern in SENDER_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return null
    }
}
