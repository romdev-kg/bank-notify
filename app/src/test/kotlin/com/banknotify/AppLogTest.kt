package com.banknotify

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class AppLogTest {
    @Test fun `exception details cannot leak credentials into diagnostic logs`() {
        AppLog.clear()
        AppLog.e("Delivery", "Отправка не удалась", IllegalStateException("https://api.telegram.org/botSECRET_TOKEN/sendMessage"))
        assertFalse(AppLog.getLogsAsText().contains("SECRET_TOKEN"))
        assertTrue(AppLog.getLogsAsText().contains("IllegalStateException"))
    }

    @Test fun `skips are aggregated and cannot evict delivery errors`() {
        AppLog.clear()
        AppLog.e("Delivery", "Отправка не удалась")
        repeat(10000) { AppLog.skipped("Приложение не выбрано") }
        assertEquals(1, AppLog.getLogs().size)
        assertEquals("E", AppLog.getLogs().single().level)
        assertTrue(AppLog.skippedSummary().contains("10000"))
    }
    @Test fun `log remains bounded and clearing resets counters`() {
        AppLog.clear()
        repeat(600) { AppLog.i("Test", "$it") }
        AppLog.skipped("Причина")
        assertEquals(500, AppLog.getLogs().size)
        assertEquals("599", AppLog.getLogs().first().message)
        AppLog.clear()
        assertTrue(AppLog.getLogs().isEmpty())
        assertEquals("", AppLog.skippedSummary())
    }
}
