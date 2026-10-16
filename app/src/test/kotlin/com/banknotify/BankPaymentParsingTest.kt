package com.banknotify

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class BankPaymentParsingTest {
    private fun amount(text: String, title: String = "") =
        BankNotificationParser.parseSber(text, title)?.amount

    @Test fun `credit amount is selected instead of balance`() {
        assertEquals("500", amount("Зачисление +500. Баланс 10000 ₽"))
        assertEquals("500", amount("Баланс 10000 ₽. Зачисление 500 ₽"))
        assertNull(amount("Зачисление 500 EUR. Баланс 10000 ₽"))
    }

    @Test fun `whole numbers support decimal and thousands conventions`() {
        for (text in listOf("1,000.50", "1.000,50", "1 000,50", "1\u00a0000,50", "1\u202f000,50")) {
            assertEquals(text, "1000,50", amount("Зачисление $text ₽"))
        }
        assertEquals("1234567,89", amount("Зачисление 1,234,567.89 ₽"))
        assertEquals("9007199254740993", amount("Зачисление 9007199254740993 ₽"))
    }

    @Test fun `malformed and nonpositive amounts cannot become numeric suffixes`() {
        for (text in listOf("1,00.50", "1..50", "1,2345", "0", "-500", "−500")) {
            assertNull(text, amount("Зачисление $text ₽"))
        }
    }

    @Test fun `outgoing operations and incidental telephone plus are not income`() {
        for (text in listOf("Покупка 500 ₽. Телефон +7 900 123-45-67", "Списание 500 ₽", "Исходящий перевод 500 ₽", "Перевод 500р получателю Иван И.")) {
            assertNull(text, amount(text))
        }
        assertNull(amount("Телефон +7 900 123-45-67. Баланс 500 ₽"))
    }

    @Test fun `ambiguous transfers continue to be ordinary payments`() {
        assertEquals("500", amount("Перевод 500р"))
        assertEquals("500", amount("Перевод выполнен на 500 ₽"))
        assertEquals("500", amount("+500 ₽"))
    }

    @Test fun `income synonyms and sender before amount are supported`() {
        for (text in listOf("Зачислено 500р", "Поступило 500р", "Перевод от Иван И. 500р", "Получен перевод 500 ₽", "Пополнение *2923 на 500 р")) {
            assertEquals(text, "500", amount(text))
        }
    }

    @Test fun `amount in title is retained when income description is in body`() {
        assertEquals("500", amount("Перевод от Иван И.", "+500 ₽"))
        assertNull(amount("Перевод от Иван И.", "Баланс 10000 ₽"))
    }

    @Test fun `amount immediately before operation in body remains a payment`() {
        assertEquals("500", amount("500 ₽ зачислено на счёт *1234", "Сбербанк"))
        assertEquals("500", amount("500 ₽ — перевод от Иван И.", "Сбербанк"))
        assertNull(amount("Баланс 10000 ₽. Перевод от Иван И.", "Сбербанк"))
    }

    @Test fun `sender phone number cannot replace the actual transferred amount`() {
        assertEquals("500", amount("Перевод от +7 (900) 123-45-67 на 500 ₽"))
        assertEquals("500", amount("Перевод от +7 900 123-45-67 на 500 ₽"))
        assertNull(amount("Зачисление +7 (900) 123-45-67"))
    }

    @Test fun `sender case and full initials are preserved`() {
        assertEquals("Семён Дмитриевич П.", BankNotificationParser.parse("Перевод от Семён Дмитриевич П. 500 ₽")?.sender)
        assertEquals("Семен Дмитриевич П", BankNotificationParser.parse("Перевод 500 ₽. СБП. Семен Дмитриевич П из Сбербанк")?.sender)
    }

    @Test fun `ruble prefix cannot be the final letter of an ordinary word`() {
        assertEquals("500", amount("Пополнение номер 1234, на 500 ₽"))
        assertEquals("500", amount("Пополнение руб. 500"))
        assertEquals("500", amount("Пополнение ₽500"))
    }

    @Test fun `decimal rendering uses Russian separators regardless of device locale`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("ar"))) {
                Locale.setDefault(locale)
                assertEquals("500,50", amount("Зачисление 500.50 ₽"))
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `masked account is not inferred from payment amount`() {
        assertEquals("5678", BankNotificationParser.parseSber("Зачисление 1000 ₽ на *5678", "")?.accountNumber)
    }

    @Test fun `time is metadata rather than a payment amount`() {
        for (text in listOf(
            "Зачисление 14:05 500р",
            "Зачисление 14:05:32 500 ₽",
            "14:05 Зачисление 500 ₽",
            "Зачисление 14:05. Сумма: 500 ₽"
        )) assertEquals(text, "500", amount(text))
        assertNull(amount("Зачисление 14:05"))
        assertNull(amount("Зачисление 14:05. Баланс 500 ₽"))
    }

    @Test fun `account digits adjacent to an amount do not merge with it`() {
        for (text in listOf(
            "Пополнение MIR-2923 500р",
            "Пополнение VISA-2923 500р",
            "Зачисление на счёт *1234 500 ₽",
            "Пополнение MIR-2923 500р. Баланс 10000 ₽"
        )) assertEquals(text, "500", amount(text))
        assertEquals("1500,50", amount("Зачисление на счёт *1234 1\u202f500,50 ₽"))
        assertNull(amount("Пополнение MIR-2923. Баланс 500 ₽"))
        assertNull(amount("Зачисление на счёт *1234567 500 ₽"))
    }

    @Test fun `sent to you describes an incoming transfer`() {
        assertEquals("500", amount("Вам отправлен перевод 500 ₽"))
        assertEquals("500", amount("Вам отправлено переводом 500 ₽", "Перевод"))
        assertEquals("500", amount("Перевод 500 ₽ отправлен вам"))
        assertEquals("500", amount("Вам переведено 500 ₽", "Зачисление"))
        assertNull(amount("Вами отправлен перевод 500 ₽"))
        assertNull(amount("Отправлен перевод 500 ₽"))
        assertNull(amount("Вам отправлен перевод 500 ₽ получателю Иван И."))
    }

    @Test fun `sender comment does not control operation direction or amount`() {
        for (text in listOf(
            "Перевод 500 ₽ от Иван И. Сообщение: отправлено получателю",
            "Зачисление 500 ₽. Комментарий: покупка подарка",
            "Перевод 500 ₽ от Иван И. Сообщение: списано 1000 ₽",
            "Вам отправлен перевод 500 ₽. Сообщение: исходящий перевод"
        )) assertEquals(text, "500", amount(text))
        for (text in listOf(
            "Исходящий перевод 500 ₽. Сообщение: зачисление",
            "Перевод 500 ₽ получателю Иван И. Комментарий: подарок",
            "Сообщение: Зачисление 500 ₽",
            "Перевод от Иван И. Сообщение: зачисление 500 ₽",
            "Сообщение о переводе: списано 500 ₽"
        )) assertNull(text, amount(text))
        assertNull(amount("", "Сообщение: +500 ₽"))
    }

    @Test fun `metadata skipping does not rescue malformed or foreign payment amounts`() {
        for (text in listOf(
            "Зачисление 14:05 1 00 ₽",
            "Пополнение MIR-2923 1,00.50 ₽",
            "Зачисление на счёт *1234 500 EUR. Баланс 1000 ₽",
            "Зачисление 14:05 500 EUR. Баланс 1000 ₽"
        )) assertNull(text, amount(text))
    }
}
