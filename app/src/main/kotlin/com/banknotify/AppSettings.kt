package com.banknotify

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Cache only successfully opened and validated stores; never replace unreadable data. */
internal class SettingsCache<K : Any, V : Any>(private val load: (K) -> V) {
    private var owner: K? = null
    private var value: V? = null
    @Synchronized fun get(key: K): V {
        if (owner === key) value?.let { return it }
        val loaded = load(key)
        owner = key
        value = loaded
        return loaded
    }
}

object AppSettings {
    private val cache = SettingsCache<Context, SharedPreferences> { context ->
        EncryptedSharedPreferences.create(
            context, "bank_notify",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also { it.all } // Detect unreadable entries before any screen/worker starts using the store.
    }

    fun preferences(context: Context): SharedPreferences = cache.get(context.applicationContext)

    fun available(context: Context): SharedPreferences? = try {
        preferences(context)
    } catch (_: Exception) {
        AppLog.w("Settings", "Не удалось открыть защищённые настройки. Данные сохранены; открытие можно повторить.")
        null
    }
}
