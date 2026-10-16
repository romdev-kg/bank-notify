package com.banknotify

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProxyFailoverTest {
    @Test fun `unavailable proxy falls through and success stops the chain`() = runBlocking {
        val attempted = mutableListOf<String>()
        val result = ProxyFailover.deliver(listOf("first", "second", "third")) {
            attempted += it
            if (it == "first") throw IOException("offline")
            TelegramDeliveryResult(true)
        }
        assertTrue(result.delivered)
        assertEquals(listOf("first", "second"), attempted)
    }
    @Test fun `all failed proxies retain failure and never try direct connection`() = runBlocking {
        val attempted = mutableListOf<String>()
        val result = ProxyFailover.deliver(listOf("first", "second")) {
            attempted += it
            throw IOException("offline")
        }
        assertFalse(result.delivered)
        assertEquals(listOf("first", "second"), attempted)
    }
    @Test fun `proxy authentication error tries the next configured proxy`() = runBlocking {
        val attempted = mutableListOf<String>()
        val result = ProxyFailover.deliver(listOf("first", "second")) {
            attempted += it
            if (it == "first") TelegramDeliveryResult(false, "Telegram HTTP 407") else TelegramDeliveryResult(true)
        }
        assertTrue(result.delivered)
        assertEquals(listOf("first", "second"), attempted)
    }
    @Test fun `rate limit is not bypassed using another proxy`() = runBlocking {
        var calls = 0
        val result = ProxyFailover.deliver(listOf("first", "second")) {
            calls++
            TelegramDeliveryResult(false, "Telegram HTTP 429", 60_000)
        }
        assertEquals(1, calls)
        assertEquals(60_000, result.retryAfterMillis)
    }
}
