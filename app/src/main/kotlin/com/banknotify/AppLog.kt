package com.banknotify

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object AppLog {
    private const val TAG = "BankNotify"
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diagnostic-journal").apply { isDaemon = true }
    }
    private val journal = DiagnosticJournal(executor)
    private val skippedCounts = linkedMapOf<String, Long>()
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        executor.execute {
            try {
                journal.initialize(File(appContext.noBackupFilesDir, "diagnostics.log"))
            } catch (_: Exception) {
                w("Process", "Постоянный журнал недоступен; события доступны за текущий сеанс")
            }
            i("Process", "Запуск PID=${Process.myPid()}, версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val manager = appContext.getSystemService(ActivityManager::class.java)
                    manager?.getHistoricalProcessExitReasons(appContext.packageName, 0, 1)?.firstOrNull()?.let { exit ->
                        // Descriptions and traces may contain notification bodies or credentials.
                        i("Process", "Предыдущее завершение: PID=${exit.pid}, reason=${exit.reason}, time=${exit.timestamp}")
                    }
                } catch (_: Exception) {
                    w("Process", "Сведения о предыдущем завершении процесса недоступны")
                }
            }
        }
    }

    @Synchronized
    fun skipped(reason: String) {
        skippedCounts[reason] = (skippedCounts[reason] ?: 0L) + 1
    }

    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    ) {
        fun format(): String {
            val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
            return "[$time] [$level] $tag: $message"
        }
    }

    fun d(tag: String, message: String) {
        Log.d(TAG, "[$tag] $message")
        addLog("D", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(TAG, "[$tag] $message")
        addLog("I", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(TAG, "[$tag] $message")
        addLog("W", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val safeMessage = if (throwable == null) message else "$message (${throwable.javaClass.simpleName})"
        Log.e(TAG, "[$tag] $safeMessage")
        addLog("E", tag, safeMessage)
    }

    @Synchronized
    private fun addLog(level: String, tag: String, message: String) {
        journal.append(LogEntry(System.currentTimeMillis(), level, tag, message))
    }

    @Synchronized
    fun getLogs(): List<LogEntry> = journal.snapshot().reversed()

    @Synchronized
    fun skippedSummary(): String = skippedCounts.entries.joinToString("; ") { "${it.key}: ${it.value}" }

    fun getLogsAsText(): String {
        val summary = skippedSummary()
        return (if (summary.isEmpty()) "" else "Пропущено за сеанс: $summary\n\n") +
            getLogs().joinToString("\n") { it.format() }
    }

    @Synchronized
    fun clear() {
        skippedCounts.clear()
        journal.clear()
    }
}
