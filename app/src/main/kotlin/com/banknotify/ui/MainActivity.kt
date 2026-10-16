package com.banknotify.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.banknotify.RecoveryWork
import com.banknotify.AppSettings
import com.banknotify.DeliveryQueue
import com.banknotify.TelegramProxyConfig
import com.banknotify.ListenerRecovery
import com.banknotify.ListenerStatus
import kotlinx.coroutines.CancellationException
import com.banknotify.AppLog
import com.banknotify.BankAppsManager
import com.banknotify.BankNotificationListener
import com.banknotify.BuildConfig
import com.banknotify.R
import com.banknotify.TelegramSender
import com.banknotify.db.BankNotificationsDatabase
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val settingsTransfer = SettingsTransfer(this)

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var appsContainer: LinearLayout
    private lateinit var tvNoApps: TextView
    private lateinit var switchSms: SwitchMaterial
    private var settingsReady = false
    private var updatingSms = false

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!settingsReady) return@registerForActivityResult
        if (granted) {
            setSmsEnabled(true)
            updateSmsSwitch(true)
            AppLog.i("MainActivity", "SMS разрешение получено")
        } else {
            AppLog.w("MainActivity", "SMS разрешение отклонено")
            Toast.makeText(this, "SMS разрешение отклонено", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemInsets()

        val btnStatus = findViewById<Button>(R.id.btn_status)
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val statusIndicator = findViewById<View>(R.id.status_indicator)
        val btnPermissions = findViewById<Button>(R.id.btn_permissions)
        val btnSave = findViewById<Button>(R.id.btn_save_settings)
        val btnTest = findViewById<Button>(R.id.btn_test)
        val etToken = findViewById<TextInputEditText>(R.id.et_telegram_token)
        val etChatId = findViewById<TextInputEditText>(R.id.et_chat_id)
        val etProxy = findViewById<TextInputEditText>(R.id.et_telegram_proxy)
        val btnAddApp = findViewById<Button>(R.id.btn_add_app)
        appsContainer = findViewById(R.id.apps_container)
        tvNoApps = findViewById(R.id.tv_no_apps)

        val prefs = AppSettings.available(this)
        if (prefs == null) {
            showSettingsUnavailable()
            return
        }
        settingsReady = true

        // Загружаем сохранённые или дефолтные значения
        val savedToken = prefs.getString("telegram_token", "")
        val savedChatId = prefs.getString("telegram_chat_id", "")
        val savedProxy = prefs.getString(TelegramSender.PREF_TELEGRAM_PROXY, "")

        etToken.setText(savedToken)
        etChatId.setText(savedChatId)
        etProxy.setText(savedProxy)
        etProxy.error = TelegramProxyConfig.validationError(savedProxy.orEmpty())

        // Загружаем выбранные приложения
        refreshAppsList()

        // Кнопка добавления приложения
        btnAddApp.setOnClickListener {
            AppPickerDialog(this) { app ->
                BankAppsManager.addApp(this, app.packageName)
                refreshAppsList()
            }.show()
        }

        // SMS toggle
        switchSms = findViewById(R.id.switch_sms)
        switchSms.isChecked = isSmsEnabled()
        switchSms.setOnCheckedChangeListener { _, isChecked ->
            if (updatingSms) return@setOnCheckedChangeListener
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    setSmsEnabled(true)
                } else {
                    updateSmsSwitch(false)
                    smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
                }
            } else {
                setSmsEnabled(false)
            }
        }

        updateStatus(tvStatus, statusIndicator)

        btnStatus.setOnClickListener {
            updateStatus(tvStatus, statusIndicator)
        }

        btnPermissions.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btn_export_settings).setOnClickListener { settingsTransfer.export() }
        findViewById<Button>(R.id.btn_import_settings).setOnClickListener { settingsTransfer.import() }
        findViewById<Button>(R.id.btn_diagnostics).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        val btnLogs = findViewById<Button>(R.id.btn_logs)
        btnLogs.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        findViewById<TextView>(R.id.tv_version).text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_DATE})"

        AppLog.i("MainActivity", "Приложение запущено")
        ensureListenerConnected()

        btnSave.setOnClickListener {
            val token = etToken.text.toString().trim()
            val chatId = etChatId.text.toString().trim()
            val proxy = etProxy.text.toString().trim()
            TelegramProxyConfig.validationError(proxy)?.let {
                etProxy.error = it
                return@setOnClickListener
            }
            etProxy.error = null

            if (token.isNotEmpty() && chatId.isNotEmpty()) {
                prefs.edit()
                    .putString("telegram_token", token)
                    .putString("telegram_chat_id", chatId)
                    .putString(TelegramSender.PREF_TELEGRAM_PROXY, proxy)
                    .apply()
                wakeDelivery()
                Toast.makeText(this, "Сохранено!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Заполните все поля!", Toast.LENGTH_SHORT).show()
            }
        }

        btnTest.setOnClickListener {
            val token = etToken.text.toString().trim()
            val chatId = etChatId.text.toString().trim()
            val proxy = etProxy.text.toString().trim()
            TelegramProxyConfig.validationError(proxy)?.let {
                etProxy.error = it
                return@setOnClickListener
            }
            etProxy.error = null

            if (token.isEmpty() || chatId.isEmpty()) {
                Toast.makeText(this, "Сначала заполните и сохраните настройки", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("telegram_token", token)
                .putString("telegram_chat_id", chatId)
                .putString(TelegramSender.PREF_TELEGRAM_PROXY, proxy)
                .apply()

            btnTest.isEnabled = false
            scope.launch {
                try {
                    val success = withContext(Dispatchers.IO) {
                        TelegramSender(applicationContext, BankNotificationsDatabase.getDatabase(applicationContext).notificationDao()).sendTest()
                    }
                    if (success) wakeDelivery()
                    Toast.makeText(this@MainActivity, if (success) "Тестовое сообщение отправлено!"
                        else "Ошибка отправки. Проверьте токен, Chat ID и прокси", Toast.LENGTH_LONG).show()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    AppLog.w("Test", "Не удалось выполнить тест; проверьте настройки")
                    Toast.makeText(this@MainActivity, "Не удалось выполнить тест. Откройте диагностику.", Toast.LENGTH_LONG).show()
                } finally { btnTest.isEnabled = true }
            }
        }
    }

    private fun refreshAppsList() {
        appsContainer.removeAllViews()
        val selectedApps = BankAppsManager.getSelectedApps(this)

        if (selectedApps.isEmpty()) {
            tvNoApps.visibility = View.VISIBLE
        } else {
            tvNoApps.visibility = View.GONE
            for (app in selectedApps) {
                val itemView = LayoutInflater.from(this)
                    .inflate(R.layout.item_selected_app, appsContainer, false)

                val cbEnabled = itemView.findViewById<MaterialCheckBox>(R.id.cb_app_enabled)
                val tvAppName = itemView.findViewById<TextView>(R.id.tv_app_name)
                val btnRemove = itemView.findViewById<ImageButton>(R.id.btn_remove)

                tvAppName.text = app.displayName
                cbEnabled.isChecked = BankAppsManager.isAppEnabled(this, app.packageName)

                cbEnabled.setOnCheckedChangeListener { _, isChecked ->
                    BankAppsManager.setAppEnabled(this, app.packageName, isChecked)
                }

                btnRemove.setOnClickListener {
                    BankAppsManager.removeApp(this, app.packageName)
                    refreshAppsList()
                }

                appsContainer.addView(itemView)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!settingsReady) return
        statusHandler.removeCallbacks(statusRefresh)
        statusHandler.post(statusRefresh)
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val statusIndicator = findViewById<View>(R.id.status_indicator)
        updateStatus(tvStatus, statusIndicator)
        ensureListenerConnected()
        // Обновить состояние SMS switch (разрешение могло измениться в настройках)
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        updateSmsSwitch(hasPermission && isSmsEnabled())
    }

    private fun ensureListenerConnected() {
        com.banknotify.ListenerRecovery.schedule(applicationContext)
    }

    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRefresh = object : Runnable {
        override fun run() {
            updateStatus(findViewById(R.id.tv_status), findViewById(R.id.status_indicator))
            statusHandler.postDelayed(this, 2000)
        }
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusRefresh)
        super.onPause()
    }

    private fun updateStatus(tvStatus: TextView, statusIndicator: View) {
        val state = ListenerRecovery.status(this)
        tvStatus.text = state.label
        statusIndicator.setBackgroundResource(if (state == ListenerStatus.CONNECTED)
            R.drawable.status_indicator_on else R.drawable.status_indicator_off)
    }

    private fun isSmsEnabled(): Boolean = AppSettings.preferences(this).getBoolean("sms_enabled", false)

    private fun setSmsEnabled(enabled: Boolean) {
        AppSettings.preferences(this).edit().putBoolean("sms_enabled", enabled).apply()
    }

    private fun updateSmsSwitch(checked: Boolean) {
        updatingSms = true
        try { switchSms.isChecked = checked } finally { updatingSms = false }
    }

    private fun wakeDelivery() = RecoveryWork.wakeDelivery(applicationContext)

    private fun showSettingsUnavailable() {
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@MainActivity).apply {
                text = "Не удалось открыть защищённые настройки. Данные не удалены. Разблокируйте телефон и повторите попытку. Если ошибка остаётся, откройте журнал."
                textSize = 18f
            })
            addView(Button(this@MainActivity).apply { text = "Повторить"; setOnClickListener { recreate() } })
            addView(Button(this@MainActivity).apply {
                text = "Открыть журнал"
                setOnClickListener { startActivity(Intent(this@MainActivity, LogsActivity::class.java)) }
            })
        })
        applySystemInsets()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
