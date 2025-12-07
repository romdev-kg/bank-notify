package com.banknotify

import com.banknotify.db.BankNotification

object BankNotificationParser {

    private val AMOUNT_PATTERN = Regex("([\\d\\s]+[.,]?\\d*)\\s*[₽руб]", RegexOption.IGNORE_CASE)
    private val ACCOUNT_PATTERN = Regex("\\*?(\\d{4})\\)?")
    private val SBER_TYPE_PATTERN = Regex("(Зачисление|Платёж|Платеж|Поступление|Списание|Перевод|Покупка)", RegexOption.IGNORE_CASE)

    fun parseSber(text: String, title: String): BankNotification? {
        val fullText = "$title $text"
        val amount = AMOUNT_PATTERN.find(fullText)?.groupValues?.get(1)
            ?.replace(" ", "")
            ?.replace(",", ".")
            ?: return null

        val operationType = SBER_TYPE_PATTERN.find(fullText)?.groupValues?.get(1) ?: "Операция"
        val account = ACCOUNT_PATTERN.find(fullText)?.groupValues?.get(1) ?: "????"

        return BankNotification(
            bankName = "Сбербанк",
            amount = amount,
            operationType = operationType,
            accountNumber = account
        )
    }

    fun parseRshb(text: String, title: String): BankNotification? {
        val fullText = "$title $text"
        val amount = AMOUNT_PATTERN.find(fullText)?.groupValues?.get(1)
            ?.replace(" ", "")
            ?.replace(",", ".")
            ?: return null

        val account = ACCOUNT_PATTERN.find(fullText)?.groupValues?.get(1) ?: "????"
        val operationType = SBER_TYPE_PATTERN.find(fullText)?.groupValues?.get(1) ?: "Зачисление"

        return BankNotification(
            bankName = "Россельхозбанк",
            amount = amount,
            operationType = operationType,
            accountNumber = account
        )
    }
}
