package com.banknotify

import org.junit.Assert.*
import org.junit.Test

class ListenerTogglePolicyTest {
    @Test fun `toggle waits for thirty seconds of disconnection`() {
        assertFalse(ListenerTogglePolicy.shouldToggle(null, null, 1_000_000))
        assertFalse(ListenerTogglePolicy.shouldToggle(29_999, null, 1_000_000))
        assertTrue(ListenerTogglePolicy.shouldToggle(30_000, null, 1_000_000))
    }

    @Test fun `toggle is limited to once per ten minutes`() {
        assertFalse(ListenerTogglePolicy.shouldToggle(60_000, 1_000_000, 1_599_999))
        assertTrue(ListenerTogglePolicy.shouldToggle(60_000, 1_000_000, 1_600_000))
    }

    @Test fun `toggle time stored before reboot does not block recovery`() {
        assertTrue(ListenerTogglePolicy.shouldToggle(60_000, 5_000_000, 100_000))
    }

    @Test fun `disconnection duration follows connection state`() {
        val state = ListenerConnectionState()
        assertNull(state.disconnectedFor(0))
        state.update(true, false, 1_000)
        assertEquals(29_000L, state.disconnectedFor(30_000))
        state.update(true, true, 31_000)
        assertNull(state.disconnectedFor(40_000))
        state.update(false, false, 50_000)
        assertNull(state.disconnectedFor(60_000))
    }
}
