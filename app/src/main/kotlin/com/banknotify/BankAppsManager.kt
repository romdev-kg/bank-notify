package com.banknotify

import android.content.Context
import android.content.pm.PackageManager

data class BankApp(
    val packageName: String,
    val displayName: String,
    val prefKey: String
)

object BankAppsManager {

    // Список поддерживаемых банков
    private val SUPPORTED_BANKS = listOf(
        BankApp("ru.sberbankmobile", "Сбербанк", "bank_sberbank"),
        BankApp("ru.rshb.dbo", "Россельхозбанк", "bank_rshb")
    )

    /**
     * Возвращает список установленных банковских приложений
     */
    fun getInstalledBanks(context: Context): List<BankApp> {
        val pm = context.packageManager
        return SUPPORTED_BANKS.filter { bank ->
            isAppInstalled(pm, bank.packageName)
        }
    }

    /**
     * Возвращает все поддерживаемые банки (для отображения в UI)
     */
    fun getAllSupportedBanks(): List<BankApp> = SUPPORTED_BANKS

    /**
     * Проверяет установлено ли приложение
     */
    private fun isAppInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Получает BankApp по package name
     */
    fun getBankByPackage(packageName: String): BankApp? {
        return SUPPORTED_BANKS.find { it.packageName == packageName }
    }
}
