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
}
