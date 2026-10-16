package com.banknotify

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenerReconnectLoopTest {
    @Test fun `disconnect retries with increasing delay and never exceeds five minutes`() = runTest {
        val times = mutableListOf<Long>()
        val loop = ListenerReconnectLoop(backgroundScope, { true }) { times += testScheduler.currentTime }
        loop.start()
        runCurrent()
        advanceTimeBy(620_001)
        assertEquals(listOf(0L, 5_000L, 15_000L, 35_000L, 75_000L, 155_000L, 315_000L, 615_000L), times)
    }

    @Test fun `repeated triggers do not reset backoff or create parallel retries`() = runTest {
        var calls = 0
        val loop = ListenerReconnectLoop(backgroundScope, { true }) { calls++ }
        loop.start()
        runCurrent()
        advanceTimeBy(2_000)
        repeat(10) { loop.start() }
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(3_001)
        assertEquals(2, calls)
    }

    @Test fun `connection cancels pending retries and next disconnect starts fresh`() = runTest {
        val times = mutableListOf<Long>()
        val loop = ListenerReconnectLoop(backgroundScope, { true }) { times += testScheduler.currentTime }
        loop.start()
        advanceTimeBy(16_000)
        loop.stop()
        advanceTimeBy(100_000)
        assertEquals(listOf(0L, 5_000L, 15_000L), times)
        loop.start()
        advanceTimeBy(5_001)
        assertEquals(listOf(0L, 5_000L, 15_000L, 116_000L, 121_000L), times)
    }

    @Test fun `revoking notification access stops retries`() = runTest {
        var allowed = true
        var calls = 0
        val loop = ListenerReconnectLoop(backgroundScope, { allowed }) { calls++ }
        loop.start()
        runCurrent()
        allowed = false
        advanceTimeBy(600_000)
        assertEquals(1, calls)
        allowed = true
        loop.start()
        runCurrent()
        assertEquals(2, calls)
    }
}
