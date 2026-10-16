package com.banknotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.launch

class BankSmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"

        private val TRACKED_SENDERS = listOf("900", "Alfa-Bank")

    }

    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        intent ?: return
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        if (!isSmsEnabled(context)) {
            AppLog.skipped("SMS обработка отключена")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Конкатенация multi-part SMS по отправителю
        val senderToBody = mutableMapOf<String, StringBuilder>()
        for (msg in messages) {
            val sender = msg.originatingAddress ?: continue
            senderToBody.getOrPut(sender) { StringBuilder() }.append(msg.messageBody ?: "")
        }

        val pendingResult = goAsync()
        RecoveryWork.intakeScope.launch {
            try {
                for ((sender, bodyBuilder) in senderToBody) {
                    val body = bodyBuilder.toString()
                    if (!TRACKED_SENDERS.any { it.equals(sender, ignoreCase = true) }) {
                        AppLog.skipped("SMS от другого отправителя")
                        continue
                    }
                    val parsed = when {
                        sender.equals("900", ignoreCase = true) -> parseSber(body)
                        sender.equals("Alfa-Bank", ignoreCase = true) -> parseAlfa(body)
                        else -> null
                    }
                    if (parsed == null) {
                        AppLog.skipped("SMS не распознано как входящий платёж")
                        continue
                    }
                    try {
                        val message = buildString {
                            append("${parsed.amount} ₽")
                            if (parsed.sender != null) append(" от ${parsed.sender}")
                            append(" (${parsed.bankName}, ${parsed.card}) [SMS]")
                        }
                        val timestamps = messages.filter { it.originatingAddress == sender }
                            .joinToString(",") { it.timestampMillis.toString() }
                        DeliveryQueue.enqueue(context.applicationContext,
                            DeliveryQueue.eventKey("sms", sender, timestamps, body), message)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) {
                        AppLog.e(TAG, "Не удалось сохранить SMS в очередь доставки")
                    }
                }
            } finally { pendingResult.finish() }
        }
    }

    private fun isSmsEnabled(context: Context): Boolean {
        return try {
            val prefs = EncryptedSharedPreferences.create(
                context,
                "bank_notify",
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.getBoolean("sms_enabled", false)
        } catch (e: Exception) {
            false
        }
    }

    data class ParsedSms(
        val bankName: String,
        val amount: String,
        val card: String,
        val type: String,
        val sender: String? = null
    )

    private fun parseSber(body: String): ParsedSms? = parseBank(body, "Сбербанк")

    private fun parseAlfa(body: String): ParsedSms? = parseBank(body, "Альфа-Банк")

    private fun parseBank(body: String, bankName: String): ParsedSms? {
        val payment = BankNotificationParser.parse(body) ?: return null
        return ParsedSms(bankName, payment.amount, BankNotificationParser.card(body), payment.operationType, payment.sender)
    }
}
