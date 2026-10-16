package com.banknotify

import org.junit.Assert.*
import org.junit.Test

class TelegramDeliveryResultTest {
    @Test fun `only a confirmed Telegram message counts as delivered`() {
        assertTrue(TelegramDeliveryResult.parse(200, """{"ok":true,"result":{"message_id":42}}""").delivered)
        for ((status, body) in listOf(
            200 to """{"ok":false,"error_code":400}""",
            200 to """{"ok":true}""",
            200 to "<html>proxy error</html>",
            200 to "",
            500 to """{"ok":true,"result":{"message_id":42}}"""
        )) {
            assertFalse("$status $body", TelegramDeliveryResult.parse(status, body).delivered)
        }
    }

    @Test fun `rate limit preserves server requested delay`() {
        val result = TelegramDeliveryResult.parse(429, """{"ok":false,"parameters":{"retry_after":120},"error_code":429}""")
        assertFalse(result.delivered)
        assertEquals(120_000L, result.retryAfterMillis)
    }

    @Test fun `malformed fields remain retryable without crashing or leaking response data`() {
        for (body in listOf("null", "[]", """{"ok":[],"parameters":3}""", """{"ok":false,"description":"secret"}""")) {
            val result = TelegramDeliveryResult.parse(200, body)
            assertFalse(result.delivered)
            assertFalse(result.error.orEmpty().contains("secret"))
        }
    }
}
