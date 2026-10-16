package com.banknotify

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Single-process journal. All disk work uses the serial executor; callers only update memory. */
internal class DiagnosticJournal(
    private val executor: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diagnostic-journal").apply { isDaemon = true }
    },
    private val maxEntries: Int = 500,
    private val maxBytes: Int = 256 * 1024
) {
    private data class Record(val entry: AppLog.LogEntry, val line: String)
    private val records = ArrayDeque<Record>()
    private var bytes = 0
    private var file: File? = null
    private var loaded = false
    private var cleared = false
    private var writeQueued = false

    init {
        require(maxEntries > 0 && maxBytes >= 128)
    }

    @Synchronized
    fun initialize(target: File) {
        if (file != null) return
        file = target
        executor.execute {
            val previous = try { read(target) } catch (_: Exception) { emptyList() }
            synchronized(this) {
                // Entries created before/during load must follow previous-process entries.
                // A clear at any point before this merge invalidates all previous history.
                if (!cleared) {
                    val current = records.toList()
                    records.clear()
                    bytes = 0
                    (previous + current).forEach(::addRecord)
                }
                loaded = true
                queueWrite()
            }
        }
    }

    @Synchronized
    fun append(entry: AppLog.LogEntry) {
        var bounded = entry.copy(tag = entry.tag.take(64), message = entry.message.take(2048))
        var line = encode(bounded)
        while (line.length > maxBytes) {
            bounded = bounded.copy(message = bounded.message.take(bounded.message.length / 2),
                tag = bounded.tag.take(bounded.tag.length / 2))
            line = encode(bounded)
        }
        addRecord(Record(bounded, line))
        queueWrite()
    }

    @Synchronized
    fun snapshot(): List<AppLog.LogEntry> = records.map { it.entry }

    @Synchronized
    fun clear() {
        cleared = true
        records.clear()
        bytes = 0
        queueWrite()
    }

    private fun addRecord(record: Record) {
        records.addLast(record)
        bytes += record.line.length
        while (records.size > maxEntries || bytes > maxBytes) {
            bytes -= records.removeFirst().line.length
        }
    }

    // Called with this monitor held. Coalesce bursts without queuing an unbounded number of writes.
    private fun queueWrite() {
        if (!loaded || writeQueued) return
        writeQueued = true
        executor.execute {
            val text = synchronized(this) {
                writeQueued = false
                records.joinToString("") { it.line }
            }
            try { write(file!!, text) } catch (_: Exception) {
                // Storage can be full/unavailable. Keep the live journal and retry on the next event.
            }
        }
    }

    private fun read(target: File): List<Record> {
        if (!target.isFile) return emptyList()
        val (offset, data) = RandomAccessFile(target, "r").use { input ->
            val size = minOf(input.length(), maxBytes.toLong()).toInt()
            val start = input.length() - size
            input.seek(start)
            start to ByteArray(size).also(input::readFully)
        }
        // Only newline-terminated records are committed. Ignore a partial first/last record.
        val lines = data.toString(Charsets.US_ASCII).split('\n').dropLast(1)
        return (if (offset > 0) lines.drop(1) else lines).mapNotNull { line ->
            try {
                val fields = line.split('\t')
                if (fields.size != 4 || fields[1] !in setOf("D", "I", "W", "E")) return@mapNotNull null
                val timestamp = fields[0].toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
                val tag = decode(fields[2])
                val message = decode(fields[3])
                if (tag.length > 64 || message.length > 2048) return@mapNotNull null
                if (encode(tag) != fields[2] || encode(message) != fields[3]) return@mapNotNull null
                val entry = AppLog.LogEntry(timestamp, fields[1], tag, message)
                Record(entry, encode(entry))
            } catch (_: IllegalArgumentException) { null }
        }
    }

    private fun write(target: File, text: String) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(text.toByteArray(Charsets.US_ASCII))
                stream.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun encode(entry: AppLog.LogEntry): String =
        "${entry.timestamp}\t${entry.level}\t${encode(entry.tag)}\t${encode(entry.message)}\n"

    private fun encode(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String) = Base64.getDecoder().decode(value).toString(Charsets.UTF_8)
}
