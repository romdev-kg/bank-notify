package com.banknotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.banknotify.db.BankNotificationsDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BankSmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"

        private val TRACKED_SENDERS = listOf("900", "Alfa-Bank")

        private val INCOME_KEYWORDS = listOf(
            "зачисление", "пополнение", "перевод от",
            "входящий перевод", "возврат", "кэшбэк",
            "зачислено", "поступило", "перевод"
        )

        // Sber: "Счёт карты MIR-7886 10:16 зачисление 13 500р"
        private val SBER_AMOUNT = Regex(
            """(?:зачисление|перевод|пополнение|возврат)\s+(\d[\d\s]*[.,]?\d*)\s*(?:₽|руб\.?|р\.?)""",
            RegexOption.IGNORE_CASE
        )
        private val SBER_CARD = Regex(
            """(?:MIR|VISA|MC|MAESTRO)-(\d{4})|[*](\d{4})""",
            RegexOption.IGNORE_CASE
        )
        private val SBER_SENDER = Regex(
            """от\s+([А-ЯЁа-яё]+\s+[А-ЯЁа-яё]\.?)""",
            RegexOption.IGNORE_CASE
        )
        private val SBER_TYPE = Regex(
            """(зачисление|перевод|пополнение|возврат)""",
            RegexOption.IGNORE_CASE
        )

        // Alfa: "Пополнение *2923 на 4500 р"
        private val ALFA_AMOUNT = Regex(
            """на\s+(\d[\d\s]*[.,]?\d*)\s*(?:₽|руб\.?|р\.?)""",
            RegexOption.IGNORE_CASE
        )
        private val ALFA_CARD = Regex("""[*]\d{4}""")
        private val ALFA_TYPE = Regex(
            """^(Пополнение|Зачисление|Возврат|Перевод)""",
            RegexOption.IGNORE_CASE
        )
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        intent ?: return
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        if (!isSmsEnabled(context)) {
            AppLog.d(TAG, "SMS обработка отключена")
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

        for ((sender, bodyBuilder) in senderToBody) {
            val body = bodyBuilder.toString()
            AppLog.d(TAG, "SMS от $sender: $body")

            if (!TRACKED_SENDERS.any { it.equals(sender, ignoreCase = true) }) {
                continue
            }

            val lowerBody = body.lowercase()
            if (INCOME_KEYWORDS.none { lowerBody.contains(it) }) {
                AppLog.d(TAG, "Пропуск SMS: не про зачисление")
                continue
            }

            val parsed = when {
                sender.equals("900", ignoreCase = true) -> parseSber(body)
                sender.equals("Alfa-Bank", ignoreCase = true) -> parseAlfa(body)
                else -> null
            }

            if (parsed == null) {
                AppLog.w(TAG, "Не удалось распарсить SMS от $sender")
                continue
            }

            AppLog.i(TAG, "SMS: ${parsed.type} ${parsed.amount}р карта ${parsed.card}")

            val database = BankNotificationsDatabase.getDatabase(context)
            val telegramSender = TelegramSender(context, database.notificationDao())

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val message = buildString {
                        append("${parsed.amount} ₽")
                        if (parsed.sender != null) append(" от ${parsed.sender}")
                        append(" (${parsed.bankName}, ${parsed.card}) [SMS]")
                    }
                    telegramSender.sendSimple(message)
                    AppLog.i(TAG, "Отправлено в Telegram: $message")
                } catch (e: Exception) {
                    AppLog.e(TAG, "Ошибка отправки в Telegram: ${e.message}")
                }
            }
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

    private fun parseSber(body: String): ParsedSms? {
        val amountMatch = SBER_AMOUNT.find(body) ?: return null
        val rawAmount = amountMatch.groupValues[1].replace(" ", "").replace(",", ".").trim()
        val amount = formatAmount(rawAmount) ?: return null

        val cardMatch = SBER_CARD.find(body)
        val card = cardMatch?.value ?: "????"

        val type = SBER_TYPE.find(body)?.groupValues?.get(1) ?: "зачисление"
        val sender = SBER_SENDER.find(body)?.groupValues?.get(1)?.trim()

        return ParsedSms("Сбербанк", amount, card, type, sender)
    }

    private fun parseAlfa(body: String): ParsedSms? {
        val amountMatch = ALFA_AMOUNT.find(body) ?: return null
        val rawAmount = amountMatch.groupValues[1].replace(" ", "").replace(",", ".").trim()
        val amount = formatAmount(rawAmount) ?: return null

        val card = ALFA_CARD.find(body)?.value ?: "????"
        val type = ALFA_TYPE.find(body)?.groupValues?.get(1) ?: "Пополнение"

        return ParsedSms("Альфа-Банк", amount, card, type)
    }

    private fun formatAmount(raw: String): String? {
        return try {
            val number = raw.toDouble()
            if (number <= 0) return null
            if (number == number.toLong().toDouble()) {
                number.toLong().toString()
            } else {
                String.format("%.2f", number)
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
