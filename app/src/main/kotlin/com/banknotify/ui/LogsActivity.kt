package com.banknotify.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.banknotify.AppLog
import com.banknotify.R

class LogsActivity : AppCompatActivity() {

    private lateinit var tvLogs: TextView
    private lateinit var scrollView: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var autoRefresh = true

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (autoRefresh) {
                updateLogs()
                handler.postDelayed(this, 2000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        tvLogs = findViewById(R.id.tv_logs)
        scrollView = findViewById(R.id.scroll_view)

        val btnRefresh = findViewById<Button>(R.id.btn_refresh)
        val btnClear = findViewById<Button>(R.id.btn_clear)
        val btnBack = findViewById<Button>(R.id.btn_back)

        btnRefresh.setOnClickListener {
            updateLogs()
            Toast.makeText(this, "Обновлено", Toast.LENGTH_SHORT).show()
        }

        btnClear.setOnClickListener {
            AppLog.clear()
            updateLogs()
            Toast.makeText(this, "Логи очищены", Toast.LENGTH_SHORT).show()
        }

        btnBack.setOnClickListener {
            finish()
        }

        updateLogs()
    }

    override fun onResume() {
        super.onResume()
        autoRefresh = true
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        autoRefresh = false
        handler.removeCallbacks(refreshRunnable)
    }

    private fun updateLogs() {
        val logs = AppLog.getLogsAsText()
        tvLogs.text = if (logs.isNotEmpty()) logs else "Логи пусты. Дождитесь уведомлений."
        scrollView.post {
            scrollView.scrollTo(0, 0)
        }
    }
}
