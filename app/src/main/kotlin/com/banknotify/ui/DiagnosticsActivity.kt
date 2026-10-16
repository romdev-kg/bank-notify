package com.banknotify.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.banknotify.*
import com.banknotify.db.BankNotificationsDatabase
import kotlinx.coroutines.launch

class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Диагностика"
        val prefs = AppSettings.preferences(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        applySystemInsets()
        status = TextView(this).apply { textSize = 16f }
        layout.addView(status)
        layout.addView(Button(this).apply {
            text = "Обновить состояние"
            setOnClickListener { refresh() }
        })
        layout.addView(Button(this).apply {
            text = "Настройки доступа к уведомлениям"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        layout.addView(Button(this).apply {
            text = "Настройки экономии батареи"
            setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        })
        layout.addView(TextView(this).apply {
            text = "Внешний контроль работоспособности\nПри исправном сервисе отправляем GET-пинг примерно раз в 15 минут. При сбое пинг прекращается. В мониторинге задайте интервал 15 минут и запас не менее 30 минут: Android может задерживать фоновые задания."
        })
        val enabled = Switch(this).apply {
            text = "Включить healthcheck"
            isChecked = prefs.getBoolean("healthcheck_enabled", false)
        }
        val url = EditText(this).apply {
            hint = "https://…/ping/…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("healthcheck_url", ""))
        }
        layout.addView(enabled)
        layout.addView(url)
        layout.addView(Button(this).apply {
            text = "Сохранить healthcheck"
            setOnClickListener {
                val value = url.text.toString().trim()
                if ((enabled.isChecked || value.isNotEmpty()) && !HealthcheckPolicy.validUrl(value)) {
                    url.error = "Укажите корректный HTTPS URL без логина и пароля"
                } else {
                    prefs.edit().putBoolean("healthcheck_enabled", enabled.isChecked).putString("healthcheck_url", value).apply()
                    HealthcheckWorker.configure(this@DiagnosticsActivity)
                    Toast.makeText(this@DiagnosticsActivity, "Сохранено", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
        })
        layout.addView(Button(this).apply { text = "Назад"; setOnClickListener { finish() } })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        lifecycleScope.launch {
            val prefs = AppSettings.preferences(this@DiagnosticsActivity)
            val pending = BankNotificationsDatabase.getDatabase(this@DiagnosticsActivity).outboxDao().pendingCount()
            val sms = ContextCompat.checkSelfPermission(this@DiagnosticsActivity, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
            val power = getSystemService(PowerManager::class.java)
            status.text = buildString {
                appendLine("Доступ к уведомлениям: ${if (ListenerRecovery.hasAccess(this@DiagnosticsActivity)) "есть" else "нет"}")
                appendLine("Сервис: ${if (BankNotificationListener.isConnected) "подключён" else "не подключён"}")
                appendLine("Приём SMS: ${if (prefs.getBoolean("sms_enabled", false)) "включён" else "выключен"}")
                appendLine("Разрешение SMS: ${if (sms) "есть" else "нет"}")
                appendLine("Ограничения батареи: ${if (power.isIgnoringBatteryOptimizations(packageName)) "сняты" else "действуют"}")
                appendLine("Ожидают доставки: $pending")
                appendLine("Healthcheck: ${if (prefs.getBoolean("healthcheck_enabled", false)) prefs.getString("healthcheck_status", "ожидает запуска") else "выключен"}")
                appendLine("Пропущено за сеанс: ${AppLog.skippedSummary().ifEmpty { "0" }}")
            }
        }
    }
}
