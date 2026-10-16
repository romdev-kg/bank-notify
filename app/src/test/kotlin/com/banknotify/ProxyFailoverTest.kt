package com.banknotify

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyFailoverTest {
    @Test fun `long proxy chain shares budget so a late working backup is still tried`() = runTest {
        val attempted = mutableListOf<String>()
        var routeCancelled = false
        val result = ProxyFailover.deliver((1..20).map { "proxy-$it" } + "working-backup") { proxy ->
            attempted += proxy
            if (proxy == "working-backup") TelegramDeliveryResult(true) else {
                try {
                    delay(30_000)
                    throw IOException("offline")
                } catch (e: CancellationException) {
                    routeCancelled = true
                    throw e
                }
            }
        }
        assertTrue(result.delivered)
        assertTrue("Delivery must finish before WorkManager's limit", currentTime < 480_000L)
        assertTrue(routeCancelled)
        assertEquals("proxy-1", attempted.first())
        assertEquals("working-backup", attempted.last())
        assertEquals(21, attempted.size)
    }

    @Test fun `all failed routes return a retryable failure within eight minutes`() = runTest {
        val attempted = mutableListOf<String>()
        val result = ProxyFailover.deliver((1..20).map { "proxy-$it" }) {
            attempted += it
            delay(30_000)
            throw IOException("offline")
        }
        assertFalse(result.delivered)
        assertNotNull(result.error)
        assertEquals(0L, result.retryAfterMillis)
        assertTrue(currentTime <= 480_000L)
        assertEquals(20, attempted.size)
    }

    @Test fun `short proxy lists keep thirty seconds available to each route`() = runTest {
        val result = ProxyFailover.deliver(listOf("first", "second")) {
            delay(29_999)
            if (it == "first") throw IOException("offline")
            TelegramDeliveryResult(true)
        }
        assertTrue(result.delivered)
        assertEquals(59_998L, currentTime)
    }

    @Test fun `external cancellation remains cancellation rather than a delivery failure`() = runTest {
        val attempted = mutableListOf<String>()
        var returnedResult = false
        val delivery = async {
            ProxyFailover.deliver(listOf("first", "second")) {
                attempted += it
                delay(30_000)
                TelegramDeliveryResult(false, "offline")
            }
            returnedResult = true
        }
        runCurrent()
        advanceTimeBy(1_000)
        delivery.cancel()
        try {
            delivery.await()
            fail("External cancellation must propagate")
        } catch (_: CancellationException) {
            // WorkManager cancellation must not be converted into an ordinary delivery result.
        }
        assertFalse(returnedResult)
        assertEquals(listOf("first"), attempted)
        assertEquals(1_000L, currentTime)
    }

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
    @Test fun `cancellation during a failed route prevents trying the backup`() = runBlocking {
        val attempted = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            ProxyFailover.deliver(listOf("first", "second")) {
                attempted += it
                if (it == "first") {
                    currentCoroutineContext().cancel()
                    throw IOException("route stopped")
                }
                TelegramDeliveryResult(true)
            }
        }
        job.join()
        assertEquals(listOf("first"), attempted)
    }
}
