package com.banknotify

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.ArrayDeque

object AppLog {
    private const val TAG = "BankNotify"
    private const val MAX_LOGS = 500

    private val logs = ArrayDeque<LogEntry>()
    private val skippedCounts = linkedMapOf<String, Long>()

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
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
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
        if (throwable != null) {
            Log.e(TAG, "[$tag] $message", throwable)
            addLog("E", tag, "$message: ${throwable.message}")
        } else {
            Log.e(TAG, "[$tag] $message")
            addLog("E", tag, message)
        }
    }

    @Synchronized
    private fun addLog(level: String, tag: String, message: String) {
        logs.add(LogEntry(System.currentTimeMillis(), level, tag, message))
        while (logs.size > MAX_LOGS) {
            logs.removeFirst()
        }
    }

    @Synchronized
    fun getLogs(): List<LogEntry> = logs.toList().reversed()

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
        logs.clear()
    }
}
