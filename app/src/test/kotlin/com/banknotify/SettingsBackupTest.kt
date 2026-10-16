package com.banknotify

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SettingsBackupTest {
    private val password = "long password".toCharArray()
    private val settings = mapOf<String, Any>("telegram_token" to "secret-token", "telegram_chat_id" to "-100123",
        "telegram_proxy" to "http://user:pass@proxy:8080\nsocks5://backup:1080", "sms_enabled" to true,
        "custom_apps" to "com.bank", "app_com.bank" to false, "healthcheck_enabled" to false)

    @Test fun `settings including secrets round trip in an authenticated encrypted file`() {
        val bytes = SettingsBackup.encode(settings, password)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("secret-token"))
        assertEquals(settings, SettingsBackup.decode(bytes, password))
    }
    @Test fun `wrong password and tampering are rejected`() {
        val bytes = SettingsBackup.encode(settings, password)
        assertThrows(Exception::class.java) { SettingsBackup.decode(bytes, "wrong password".toCharArray()) }
        val json = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        val data = json.get("data").asString
        json.addProperty("data", (if (data.first() == 'A') "B" else "A") + data.drop(1))
        assertThrows(Exception::class.java) { SettingsBackup.decode(json.toString().toByteArray(), password) }
    }
    @Test fun `unknown fields and wrong preference types are rejected before applying settings`() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackup.encode(mapOf("sms_enabled" to "yes"), password)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackup.encode(mapOf("unknown" to "value"), password)
        }
    }
}
