package com.banknotify

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object SettingsBackup {
    const val MAX_BYTES = 1_048_576
    private val stringKeys = setOf("telegram_token", "telegram_chat_id", "telegram_proxy", "custom_apps", "healthcheck_url")
    private val booleanKeys = setOf("sms_enabled", "healthcheck_enabled")
    private val appKey = Regex("app_[a-zA-Z0-9_.]+")
    private val aad = "BankNotify-settings-v1".toByteArray()

    fun isSetting(key: String): Boolean = key in stringKeys || key in booleanKeys || appKey.matches(key)

    private fun validate(settings: Map<String, Any>) {
        require(settings.size <= 1000) { "Слишком много настроек" }
        for ((key, value) in settings) {
            require(isSetting(key)) { "Неизвестная настройка" }
            require(if (key in stringKeys) value is String else value is Boolean) { "Неверный тип настройки" }
            if (value is String) require(value.length <= 65_536) { "Слишком длинная настройка" }
        }
        val url = settings["healthcheck_url"] as? String ?: ""
        require((url.isEmpty() && settings["healthcheck_enabled"] != true) || HealthcheckPolicy.validUrl(url)) {
            "Некорректный healthcheck URL"
        }
    }

    fun encode(settings: Map<String, Any>, password: CharArray): ByteArray {
        require(password.size >= 8) { "Пароль должен содержать не менее 8 символов" }
        validate(settings)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val plaintext = Gson().toJson(settings).toByteArray(Charsets.UTF_8)
        require(plaintext.size < MAX_BYTES / 2) { "Слишком большой файл настроек" }
        val ciphertext = cipher.doFinal(plaintext)
        val encoder = Base64.getEncoder()
        return Gson().toJson(mapOf("format" to "BankNotify", "version" to 1,
            "salt" to encoder.encodeToString(salt), "iv" to encoder.encodeToString(iv),
            "data" to encoder.encodeToString(ciphertext))).toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray, password: CharArray): Map<String, Any> {
        require(bytes.size <= MAX_BYTES) { "Слишком большой файл" }
        val envelope = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        require(envelope.get("format")?.asString == "BankNotify" && envelope.get("version")?.asInt == 1) {
            "Неподдерживаемый формат"
        }
        val decoder = Base64.getDecoder()
        val salt = decoder.decode(envelope.get("salt").asString)
        val iv = decoder.decode(envelope.get("iv").asString)
        require(salt.size == 16 && iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(decoder.decode(envelope.get("data").asString))
        val json = JsonParser.parseString(plaintext.toString(Charsets.UTF_8)).asJsonObject
        val settings = json.entrySet().associate { (name, element) ->
            require(element.isJsonPrimitive) { "Неверный тип настройки" }
            val value = element.asJsonPrimitive
            name to when {
                value.isBoolean -> value.asBoolean
                value.isString -> value.asString
                else -> throw IllegalArgumentException("Неверный тип настройки")
            }
        }
        validate(settings)
        return settings
    }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 210_000, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }
}
