package com.banknotify

import android.content.SharedPreferences
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object SettingsImport {
    suspend fun run(
        read: suspend () -> ByteArray,
        password: CharArray,
        preferences: () -> SharedPreferences,
        configure: suspend () -> Unit
    ) {
        val settings = SettingsBackup.decode(read(), password)
        // Reads and PBKDF2 may finish after the owner is destroyed. No mutation in that case.
        currentCoroutineContext().ensureActive()
        // Once replacement starts, its scheduling must finish even if the owner goes away.
        withContext(NonCancellable) {
            val prefs = preferences()
            val edit = prefs.edit()
            prefs.all.keys.filter(SettingsBackup::isSetting).forEach(edit::remove)
            for ((name, value) in settings) {
                when (value) {
                    is String -> edit.putString(name, value)
                    is Boolean -> edit.putBoolean(name, value)
                }
            }
            check(edit.commit())
            configure()
        }
    }
}
