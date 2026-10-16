package com.banknotify

import org.junit.Assert.*
import org.junit.Test

class RecoveryStateTest {
    @Test fun `connection timeout is reset by grant changes and actual connection`() {
        val state = ListenerConnectionState()
        assertEquals(ListenerStatus.NO_ACCESS, state.update(false, false, 100))
        assertEquals(ListenerStatus.CONNECTING, state.update(true, false, 200))
        assertEquals(ListenerStatus.STALLED, state.update(true, false, 120_200))
        assertEquals(ListenerStatus.CONNECTED, state.update(true, true, 120_201))
        assertEquals(ListenerStatus.CONNECTING, state.update(true, false, 200_000))
        assertEquals(ListenerStatus.NO_ACCESS, state.update(false, false, 400_000))
        assertEquals(ListenerStatus.CONNECTING, state.update(true, false, 500_000))
    }

    @Test fun `failed settings load is retried and never caches a default value`() {
        var calls = 0
        val expected = Any()
        val cache = SettingsCache { _: Any ->
            calls++
            if (calls == 1) error("Keystore temporarily unavailable")
            expected
        }
        val owner = Any()
        assertThrows(IllegalStateException::class.java) { cache.get(owner) }
        assertSame(expected, cache.get(owner))
        assertSame(expected, cache.get(owner))
        assertEquals(2, calls)
        cache.get(Any())
        assertEquals(3, calls)
    }
}
