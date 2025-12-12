package com.banknotify

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class BankApp(
    val packageName: String,
    val displayName: String,
    val prefKey: String
)

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val icon: Drawable?
)

object BankAppsManager {

    private const val CUSTOM_APPS_KEY = "custom_apps"

    private fun getPrefs(context: Context): SharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            "bank_notify",
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Возвращает список выбранных пользователем приложений
     */
    fun getSelectedApps(context: Context): List<BankApp> {
        val prefs = getPrefs(context)
        val customAppsString = prefs.getString(CUSTOM_APPS_KEY, "") ?: ""
        if (customAppsString.isEmpty()) return emptyList()

        val pm = context.packageManager
        return customAppsString.split(",").mapNotNull { packageName ->
            try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val appName = pm.getApplicationLabel(appInfo).toString()
                BankApp(packageName, appName, "app_$packageName")
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    /**
     * Добавляет приложение в список отслеживаемых
     */
    fun addApp(context: Context, packageName: String) {
        val prefs = getPrefs(context)
        val currentApps = prefs.getString(CUSTOM_APPS_KEY, "") ?: ""
        val appsList = if (currentApps.isEmpty()) {
            mutableListOf()
        } else {
            currentApps.split(",").toMutableList()
        }

        if (!appsList.contains(packageName)) {
            appsList.add(packageName)
            prefs.edit()
                .putString(CUSTOM_APPS_KEY, appsList.joinToString(","))
                .putBoolean("app_$packageName", true)
                .apply()
        }
    }

    /**
     * Удаляет приложение из списка отслеживаемых
     */
    fun removeApp(context: Context, packageName: String) {
        val prefs = getPrefs(context)
        val currentApps = prefs.getString(CUSTOM_APPS_KEY, "") ?: ""
        val appsList = currentApps.split(",").toMutableList()
        appsList.remove(packageName)
        prefs.edit()
            .putString(CUSTOM_APPS_KEY, appsList.joinToString(","))
            .remove("app_$packageName")
            .apply()
    }

    /**
     * Проверяет включено ли приложение
     */
    fun isAppEnabled(context: Context, packageName: String): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean("app_$packageName", true)
    }

    /**
     * Включает/выключает приложение
     */
    fun setAppEnabled(context: Context, packageName: String, enabled: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit().putBoolean("app_$packageName", enabled).apply()
    }

    /**
     * Проверяет есть ли приложение в списке отслеживаемых
     */
    fun isAppTracked(context: Context, packageName: String): Boolean {
        val prefs = getPrefs(context)
        val customApps = prefs.getString(CUSTOM_APPS_KEY, "") ?: ""
        return customApps.split(",").contains(packageName)
    }

    /**
     * Возвращает список всех установленных приложений
     */
    fun getAllInstalledApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        return pm.queryIntentActivities(intent, 0)
            .map { resolveInfo ->
                InstalledApp(
                    packageName = resolveInfo.activityInfo.packageName,
                    appName = resolveInfo.loadLabel(pm).toString(),
                    icon = resolveInfo.loadIcon(pm)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }
}
