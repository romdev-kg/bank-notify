package com.banknotify.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.banknotify.R

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnHistory = findViewById<Button>(R.id.btn_history)
        val btnSettings = findViewById<Button>(R.id.btn_settings)
        val btnStatus = findViewById<Button>(R.id.btn_status)
        val tvStatus = findViewById<TextView>(R.id.tv_status)

        updateStatus(tvStatus)

        btnStatus.setOnClickListener {
            if (hasNotificationAccess()) {
                tvStatus.text = "Слушатель включен"
            } else {
                tvStatus.text = "Слушатель отключен"
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        btnHistory.setOnClickListener {
            loadFragment(HistoryFragment())
        }

        btnSettings.setOnClickListener {
            loadFragment(SettingsFragment())
        }
    }

    override fun onResume() {
        super.onResume()
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        updateStatus(tvStatus)
    }

    private fun updateStatus(tvStatus: TextView) {
        if (hasNotificationAccess()) {
            tvStatus.text = "Слушатель включен"
        } else {
            tvStatus.text = "Слушатель отключен"
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun hasNotificationAccess(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        return enabledListeners?.contains(packageName) == true
    }
}
