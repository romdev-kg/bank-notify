package com.banknotify

import com.banknotify.db.BankNotification
import java.math.BigDecimal

/** Shared payment interpretation for push notifications and bank SMS. */
object BankNotificationParser {
    data class Payment(val amount: String, val sender: String?, val operationType: String)

    private val income = Regex(
        """(?<![\p{L}\p{N}_])(?:зачисление|зачислено|поступление|поступило|перевод|пополнение|пополнено|возврат|кэшбэк|кешбэк|начисление|начислено|получено|получен)(?![\p{L}\p{N}_])""",
        RegexOption.IGNORE_CASE
    )
    private val outgoing = Regex(
        """(?<![\p{L}\p{N}_])(?:покупка|списание|списано|списаны|исходящий|отправлено|отправлен|переведено|получателю)(?![\p{L}\p{N}_])""",
        RegexOption.IGNORE_CASE
    )
    private val balance = Regex("""(?<![\p{L}\p{N}_])(?:баланс|остаток|доступно)(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE)
    // Capture the whole numeric token, including malformed separators, so a suffix is never a payment.
    private val number = Regex("""(?<![\p{L}\d.,])([+＋\-−]? *[0-9][0-9., ]*)""")
    private const val RUBLES = """(?:₽|руб(?:лей|ля|ль)?\.?|RUB|р\.?)(?!\p{L})"""
    private val currencyAfter = Regex("""^\s*$RUBLES""", RegexOption.IGNORE_CASE)
    private val currencyBefore = Regex("""(?<!\p{L})$RUBLES\s*$""", RegexOption.IGNORE_CASE)
    private val foreignCurrency = Regex("""^\s*(?:EUR|USD|GBP|CNY|KZT|BYN|€|\$|£)(?!\p{L})""", RegexOption.IGNORE_CASE)
    private val account = Regex("""(?:MIR|VISA|MC|MAESTRO)-(\d{4})|\*(\d{4})""", RegexOption.IGNORE_CASE)
    private val sender = Regex(
        """(?<!\p{L})(?:от\s+|СБП[.\s]+)([А-ЯЁа-яё]+(?:\s+(?!из(?!\p{L}))[А-ЯЁа-яё]+\.?){1,2})""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String, title: String = ""): Payment? {
        val fullText = normalizeSpaces("$title $text").trim()
        if (outgoing.containsMatchIn(fullText)) return null
        val operation = income.find(fullText)
        // Discard the balance section, but allow an operation appearing after an earlier balance.
        val start = operation?.range?.first ?: 0
        val end = balance.find(fullText, start)?.range?.first ?: fullText.length
        val operationText = fullText.substring(start, end)
        // Some banks put only the amount in the title and the operation/sender in the body.
        val titleAmount = normalizeSpaces(title).takeIf { it.isNotBlank() && !balance.containsMatchIn(it) }
        // An amount can also immediately precede the operation: "500 ₽ — перевод от ...".
        // Only use that adjacent amount, never an earlier balance or an unrelated earlier number.
        val prefix = fullText.substring(0, start)
        val precedingAmount = if (balance.containsMatchIn(prefix)) null else number.findAll(prefix).lastOrNull()?.let {
            val suffix = prefix.substring(it.range.last + 1)
            if (suffix.matches(Regex("""\s*$RUBLES\s*[—–\-:;.]*\s*""", RegexOption.IGNORE_CASE))) {
                prefix.substring(it.range.first)
            } else null
        }
        val scopes = listOfNotNull(operationText, precedingAmount, titleAmount).distinct()
        for (scope in scopes) for (match in number.findAll(scope)) {
            val raw = match.value.trim()
            val before = scope.substring(0, match.range.first)
            val after = scope.substring(match.range.last + 1)
            if (foreignCurrency.containsMatchIn(after)) return null
            val hasCurrency = currencyAfter.containsMatchIn(after) || currencyBefore.containsMatchIn(before)
            val positiveSign = raw.startsWith('+') || raw.startsWith('＋')
            val remainder = after.trimStart()
            if (positiveSign && (remainder.startsWith('-') || remainder.startsWith('('))) continue
            val followsOperation = operation != null && before.contains(operation.value) &&
                before.substringAfter(operation.value).trim().matches(Regex("""(?i)(?:на|сумма:?)?"""))
            // An incidental plus inside sender/phone details is not evidence of a payment amount.
            val signedPayment = positiveSign && (hasCurrency || before.isBlank() || followsOperation ||
                before.trim() == normalizeSpaces(title).trim())
            if (operation == null && !signedPayment) continue
            if (!hasCurrency && !signedPayment && !followsOperation) continue
            // A recognized payment position with an invalid amount must not fall through to another number.
            val amount = formatAmount(raw) ?: return null
            return Payment(amount, sender.find(fullText)?.groupValues?.get(1)?.trim(), operation?.value ?: "Зачисление")
        }
        return null
    }

    fun card(text: String): String = account.find(text)?.value ?: "????"

    private fun normalizeSpaces(text: String): String = buildString(text.length) {
        for (char in text) append(if (char.isWhitespace() || Character.isSpaceChar(char)) ' ' else char)
    }

    private fun formatAmount(raw: String): String? {
        if (raw.startsWith('-') || raw.startsWith('−')) return null
        var token = raw.removePrefix("+").removePrefix("＋").trim()
        // A single trailing period belongs to the sentence, rather than to the amount.
        if (token.endsWith('.') && !token.endsWith("..")) token = token.dropLast(1).trimEnd()
        if (token.isEmpty()) return null
        val comma = token.lastIndexOf(',')
        val dot = token.lastIndexOf('.')
        val decimal = when {
            comma >= 0 && dot >= 0 -> maxOf(comma, dot)
            maxOf(comma, dot) >= 0 && token.length - maxOf(comma, dot) - 1 in 1..2 -> maxOf(comma, dot)
            else -> -1
        }
        val integerPart = if (decimal >= 0) token.substring(0, decimal) else token
        val fraction = if (decimal >= 0) token.substring(decimal + 1) else ""
        if (decimal >= 0 && (!fraction.matches(Regex("[0-9]{1,2}")))) return null
        val separators = integerPart.filter { it == ',' || it == '.' || it == ' ' }.toSet()
        if (separators.size > 1) return null
        if (decimal >= 0 && token[decimal] in separators) return null
        if (separators.isNotEmpty()) {
            val sep = Regex.escape(separators.single().toString())
            if (!integerPart.matches(Regex("[0-9]{1,3}(?:$sep[0-9]{3})+"))) return null
        } else if (!integerPart.matches(Regex("[0-9]+"))) return null
        val digits = integerPart.filter { it in '0'..'9' }
        val value = (digits + if (fraction.isNotEmpty()) ".$fraction" else "").toBigDecimalOrNull() ?: return null
        if (value <= BigDecimal.ZERO) return null
        return if (value.stripTrailingZeros().scale() <= 0) value.toBigIntegerExact().toString()
        else value.setScale(2).toPlainString().replace('.', ',')
    }

    fun parseSber(text: String, title: String): BankNotification? = parseBank(text, title, "Сбербанк")
    fun parseRshb(text: String, title: String): BankNotification? = parseBank(text, title, "Россельхозбанк")

    private fun parseBank(text: String, title: String, bankName: String): BankNotification? {
        val payment = parse(text, title) ?: return null
        val accountMatch = account.find("$title $text")
        return BankNotification(
            bankName = bankName,
            amount = payment.amount,
            operationType = payment.operationType,
            accountNumber = accountMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: "????"
        )
    }
}
