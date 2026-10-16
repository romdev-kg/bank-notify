package com.banknotify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.launch

class BankNotificationListener : NotificationListenerService() {


    companion object {
        private const val TAG = "Listener"
        @Volatile
        var isConnected = false
            private set

        // Ключевые слова для зачислений
        private val INCOME_KEYWORDS = listOf(
            "зачисление", "поступление", "перевод от", "получен перевод",
            "входящий перевод", "пополнение", "возврат", "кэшбэк",
            "начисление", "зачислено", "поступило", "+", "получено"
        )

        // Регулярки для извлечения суммы (несколько паттернов для разных банков)
        private val AMOUNT_PATTERNS = listOf(
            // Стандартный: 1000 ₽, 1 000,50 руб, +500 RUB
            Regex("""[+＋]?\s*(\d[\d\s]*[.,]?\d*)\s*(?:₽|руб\.?|RUB|р\.?)""", RegexOption.IGNORE_CASE),
            // Валюта перед суммой: ₽1000, руб. 500
            Regex("""(?:₽|руб\.?|RUB|р\.?)\s*[+＋]?\s*(\d[\d\s]*[.,]?\d*)""", RegexOption.IGNORE_CASE),
            // Сумма с "сумма": сумма 1000, сумма: 500.00
            Regex("""сумм[аы]:?\s*[+＋]?\s*(\d[\d\s]*[.,]?\d*)""", RegexOption.IGNORE_CASE),
            // Просто число с плюсом в начале: +1000
            Regex("""[+＋]\s*(\d[\d\s]*[.,]?\d*)""")
        )

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
        ListenerRecovery.schedule(applicationContext)
        AppLog.i(TAG, "Сервис запущен")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        ListenerRecovery.connected()
        AppLog.i(TAG, "Listener подключён к системе")
        // Recover notifications still present after a disconnect; the durable event key deduplicates callbacks.
        try { activeNotifications?.forEach { onNotificationPosted(it) } }
        catch (_: Exception) { AppLog.w(TAG, "Не удалось прочитать оставшиеся уведомления") }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        AppLog.w(TAG, "Listener отключён от системы; запускаем повторы")
        ListenerRecovery.schedule(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val packageName = sbn.packageName

        // Проверяем есть ли приложение в списке отслеживаемых и включено ли оно
        if (!BankAppsManager.isAppTracked(applicationContext, packageName)) {
            AppLog.skipped("Приложение не выбрано")
            return
        }
        if (!BankAppsManager.isAppEnabled(applicationContext, packageName)) {
            AppLog.skipped("Приложение отключено")
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
            AppLog.skipped("Не относится к оплатам")
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

        RecoveryWork.intakeScope.launch {
            try {
                val message = buildString {
                    append("$amount ₽")
                    if (sender != null) {
                        append(" от $sender")
                    }
                    append(" ($appName)")
                }

                val key = DeliveryQueue.eventKey("push", sbn.key, sbn.postTime.toString(), title, text)
                DeliveryQueue.enqueue(applicationContext, key, message)
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
        isConnected = false
        ListenerRecovery.schedule(applicationContext)
        AppLog.i(TAG, "Сервис остановлен")
    }

    private fun extractAmount(text: String): String? {
        // Пробуем все паттерны по очереди
        for (pattern in AMOUNT_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                val rawAmount = match.groupValues[1]
                    .replace(" ", "")
                    .replace(",", ".")
                    .replace("\\s".toRegex(), "")
                    .trim()

                if (rawAmount.isEmpty()) continue

                // Форматируем сумму
                return try {
                    val number = rawAmount.toDouble()
                    if (number <= 0) continue // Пропускаем нулевые и отрицательные
                    if (number == number.toLong().toDouble()) {
                        number.toLong().toString()
                    } else {
                        String.format("%.2f", number)
                    }
                } catch (e: NumberFormatException) {
                    continue
                }
            }
        }
        return null
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
