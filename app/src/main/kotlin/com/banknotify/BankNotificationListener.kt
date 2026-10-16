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

        internal fun paymentText(notification: Notification): String? {
            if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
            val extras = notification.extras
            return extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        }
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

        if (AppSettings.available(applicationContext) == null) return
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

        val text = paymentText(notification) ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""

        // Получаем имя приложения
        val appName = try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        val payment = BankNotificationParser.parse(text, title)
        if (payment == null) {
            AppLog.skipped("Не удалось распознать входящий платёж")
            return
        }
        val amount = payment.amount
        val sender = payment.sender

        AppLog.i(TAG, "Распознан входящий платёж")

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

}
