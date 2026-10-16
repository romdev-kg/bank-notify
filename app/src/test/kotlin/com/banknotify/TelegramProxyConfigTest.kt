package com.banknotify

import org.junit.Assert.*
import org.junit.Test

class TelegramProxyConfigTest {
    @Test fun `proxy password preserves literal and escaped plus characters`() {
        for (password in listOf("p+ss", "p%2Bss")) {
            val parsed = TelegramProxyConfig.parse("http://alice:$password@127.0.0.1:8080")!!
            assertEquals("alice", parsed.username)
            assertEquals("p+ss", parsed.password)
        }
    }

    @Test fun `percent escapes in credentials are decoded exactly once`() {
        val parsed = TelegramProxyConfig.parse("http://alice:p%25ss%252B@127.0.0.1:8080")
        assertNotNull(parsed)
        assertEquals("p%ss%2B", parsed!!.password)
    }

    @Test fun `escaped username delimiter does not become a password separator`() {
        val parsed = TelegramProxyConfig.parse("http://alice%3Ateam:p%3Ass@127.0.0.1:8080")!!
        assertEquals("alice:team", parsed.username)
        assertEquals("p:ss", parsed.password)
    }
}
