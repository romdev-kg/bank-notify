package com.banknotify.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.banknotify.R
import com.banknotify.TelegramSender
import com.banknotify.db.BankNotificationsDatabase
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStatus = findViewById<Button>(R.id.btn_status)
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val statusIndicator = findViewById<View>(R.id.status_indicator)
        val btnPermissions = findViewById<Button>(R.id.btn_permissions)
        val btnSave = findViewById<Button>(R.id.btn_save_settings)
        val btnTest = findViewById<Button>(R.id.btn_test)
        val etToken = findViewById<TextInputEditText>(R.id.et_telegram_token)
        val etChatId = findViewById<TextInputEditText>(R.id.et_chat_id)

        val prefs = EncryptedSharedPreferences.create(
            this,
            "bank_notify",
            MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        etToken.setText(prefs.getString("telegram_token", ""))
        etChatId.setText(prefs.getString("telegram_chat_id", ""))

        updateStatus(tvStatus, statusIndicator)

        btnStatus.setOnClickListener {
            updateStatus(tvStatus, statusIndicator)
        }

        btnPermissions.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnSave.setOnClickListener {
            val token = etToken.text.toString().trim()
            val chatId = etChatId.text.toString().trim()

            if (token.isNotEmpty() && chatId.isNotEmpty()) {
                prefs.edit()
                    .putString("telegram_token", token)
                    .putString("telegram_chat_id", chatId)
                    .apply()
                Toast.makeText(this, "Сохранено!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Заполните все поля!", Toast.LENGTH_SHORT).show()
            }
        }

        btnTest.setOnClickListener {
            val token = etToken.text.toString().trim()
            val chatId = etChatId.text.toString().trim()

            if (token.isEmpty() || chatId.isEmpty()) {
                Toast.makeText(this, "Сначала заполните и сохраните настройки", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnTest.isEnabled = false
            val database = BankNotificationsDatabase.getDatabase(this)
            val sender = TelegramSender(this, database.notificationDao())

            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    sender.sendTest()
                }

                btnTest.isEnabled = true
                if (success) {
                    Toast.makeText(this@MainActivity, "Тестовое сообщение отправлено!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Ошибка отправки. Проверьте токен и Chat ID", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val statusIndicator = findViewById<View>(R.id.status_indicator)
        updateStatus(tvStatus, statusIndicator)
    }

    private fun updateStatus(tvStatus: TextView, statusIndicator: View) {
        if (hasNotificationAccess()) {
            tvStatus.text = "Активен"
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_on)
        } else {
            tvStatus.text = "Не активен"
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_off)
        }
    }

    private fun hasNotificationAccess(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        return enabledListeners?.contains(packageName) == true
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
