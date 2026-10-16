package com.banknotify

import android.app.Notification
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class BankNotificationTextTest {
    private fun notification(text: String?, expanded: String?, summary: Boolean = false) = Notification().apply {
        extras = Bundle().apply {
            if (text != null) putCharSequence(Notification.EXTRA_TEXT, text)
            if (expanded != null) putCharSequence(Notification.EXTRA_BIG_TEXT, expanded)
        }
        if (summary) flags = flags or Notification.FLAG_GROUP_SUMMARY
    }

    @Test fun `expanded payment survives absent or abbreviated basic text`() {
        for (basic in listOf(null, "Новое поступление")) {
            val selected = BankNotificationListener.paymentText(notification(basic, "Зачисление 500 ₽"))
            assertEquals("500", BankNotificationParser.parse(selected!!)?.amount)
        }
    }

    @Test fun `basic payment is used when expanded text is absent or blank`() {
        for (expanded in listOf(null, "", "  ")) {
            val selected = BankNotificationListener.paymentText(notification("Зачисление 500 ₽", expanded))
            assertEquals("500", BankNotificationParser.parse(selected!!)?.amount)
        }
    }

    @Test fun `group summary cannot become an additional payment`() {
        assertNull(BankNotificationListener.paymentText(notification("Зачисление 500 ₽", null, summary = true)))
    }

    @Test fun `SMS entry points share synonyms grouping and sender handling`() {
        val receiver = BankSmsReceiver()
        for ((methodName, body) in listOf(
            "parseSber" to "Зачислено 13\u00a0500,50р",
            "parseSber" to "Поступило 13\u202f500.50 ₽",
            "parseAlfa" to "Пополнение *2923 на 13 500,50 р"
        )) {
            val method = BankSmsReceiver::class.java.getDeclaredMethod(methodName, String::class.java).apply { isAccessible = true }
            val parsed = method.invoke(receiver, body) as BankSmsReceiver.ParsedSms
            assertEquals("13500,50", parsed.amount)
        }
    }
}
