package com.banknotify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
        private const val TAG = "Listener"

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

    override fun onCreate() {
        super.onCreate()
        database = BankNotificationsDatabase.getDatabase(applicationContext)
        telegramSender = TelegramSender(applicationContext, database.notificationDao())
        AppLog.i(TAG, "Сервис запущен")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val packageName = sbn.packageName

        // Проверяем есть ли приложение в списке отслеживаемых и включено ли оно
        if (!BankAppsManager.isAppTracked(applicationContext, packageName)) {
            AppLog.d(TAG, "Пропуск: $packageName не в списке")
            return
        }
        if (!BankAppsManager.isAppEnabled(applicationContext, packageName)) {
            AppLog.d(TAG, "Пропуск: $packageName отключено")
            return
        }

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

        AppLog.d(TAG, "Уведомление от $appName: $title | $text")

        // Проверяем что это зачисление
        val isIncome = INCOME_KEYWORDS.any { keyword -> fullText.contains(keyword) }
        if (!isIncome) {
            AppLog.d(TAG, "Пропуск: не про деньги")
            return
        }

        // Извлекаем сумму
        val amount = extractAmount(fullText)
        if (amount == null) {
            AppLog.w(TAG, "Не удалось извлечь сумму из: $fullText")
            return
        }

        // Извлекаем отправителя
        val sender = extractSender(fullText)

        AppLog.i(TAG, "Найдено: $amount руб. от ${sender ?: "неизвестно"} ($appName)")

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
                AppLog.i(TAG, "Отправлено в Telegram: $message")
            } catch (e: Exception) {
                AppLog.e(TAG, "Ошибка отправки в Telegram", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Optional: log removed notifications
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        AppLog.i(TAG, "Сервис остановлен")
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
