package com.banknotify

import org.junit.Assert.*
import org.junit.Test

class HealthcheckPolicyTest {
    @Test fun `healthy ping is suppressed for every monitored failure`() {
        assertTrue(HealthcheckPolicy.healthy(true, true, true, true, true, false))
        assertFalse(HealthcheckPolicy.healthy(false, true, true, true, true, false))
        assertFalse(HealthcheckPolicy.healthy(true, false, true, true, true, false))
        assertFalse(HealthcheckPolicy.healthy(true, true, true, false, true, false))
        assertFalse(HealthcheckPolicy.healthy(true, true, true, true, false, false))
        assertFalse(HealthcheckPolicy.healthy(true, true, true, true, true, true))
        assertTrue(HealthcheckPolicy.healthy(true, true, false, false, true, false))
    }
    @Test fun `healthcheck requires https and does not accept credentials in URL authority`() {
        assertTrue(HealthcheckPolicy.validUrl("https://hc.example/ping/secret"))
        for (url in listOf("http://hc.example/ping", "file:///tmp/a", "https://user:pass@hc.example", "https://", "https://hc.example:99999/ping", "https://hc.example:0/ping")) {
            assertFalse(HealthcheckPolicy.validUrl(url))
        }
    }
}
