package com.banknotify

import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `restart restores persisted events in order including events before load`() {
        val file = File(temporary.root, "journal")
        val firstExecutor = DeferredExecutor()
        val first = DiagnosticJournal(executor = firstExecutor)
        first.initialize(file)
        first.append(event("old"))
        firstExecutor.drain()
        val secondExecutor = DeferredExecutor()
        val second = DiagnosticJournal(executor = secondExecutor)
        second.append(event("before initialize"))
        second.initialize(file)
        second.append(event("during load"))
        secondExecutor.drain()
        assertEquals(listOf("old", "before initialize", "during load"), second.snapshot().map { it.message })
        val restarted = DiagnosticJournal(executor = Executor { it.run() })
        restarted.initialize(file)
        assertEquals(second.snapshot(), restarted.snapshot())
    }

    @Test fun `clear during pending initialization never resurrects disk events`() {
        val file = seededFile()
        val executor = DeferredExecutor()
        val journal = DiagnosticJournal(executor = executor)
        journal.initialize(file)
        journal.append(event("before clear"))
        journal.clear()
        journal.append(event("after clear"))
        executor.drain()
        val restarted = DiagnosticJournal(executor = Executor { it.run() })
        restarted.initialize(file)
        assertEquals(listOf("after clear"), restarted.snapshot().map { it.message })
    }

    @Test fun `clear before initialization also erases previous process history`() {
        val file = seededFile()
        val journal = DiagnosticJournal(executor = Executor { it.run() })
        journal.clear()
        journal.initialize(file)
        assertTrue(journal.snapshot().isEmpty())
        assertEquals(0, file.length())
    }

    @Test fun `queued writes cannot restore events after clear`() {
        val file = seededFile()
        val executor = DeferredExecutor()
        val journal = DiagnosticJournal(executor = executor)
        journal.initialize(file)
        executor.drain()
        journal.append(event("queued"))
        journal.clear()
        assertTrue(journal.snapshot().isEmpty())
        executor.drain()
        val restarted = DiagnosticJournal(executor = Executor { it.run() })
        restarted.initialize(file)
        assertTrue(restarted.snapshot().isEmpty())
    }

    @Test fun `disk and memory retain newest events within entry and byte bounds`() {
        val file = File(temporary.root, "journal")
        val journal = DiagnosticJournal(executor = Executor { it.run() }, maxEntries = 5, maxBytes = 1024)
        journal.initialize(file)
        repeat(100) { journal.append(event("$it: " + "я".repeat(70))) }
        assertTrue(file.length() <= 1024)
        assertTrue(journal.snapshot().size in 1..5)
        assertTrue(journal.snapshot().last().message.startsWith("99:"))
        val restarted = DiagnosticJournal(executor = Executor { it.run() }, maxEntries = 5, maxBytes = 1024)
        restarted.initialize(file)
        assertEquals(journal.snapshot(), restarted.snapshot())
    }

    @Test fun `malformed records and truncated tail do not discard complete events`() {
        val file = seededFile()
        val validLine = file.readText()
        file.writeText("not a record\n" + validLine + "42\tE\tbroken\tbroken\n" + validLine.trimEnd())
        val journal = DiagnosticJournal(executor = Executor { it.run() })
        journal.initialize(file)
        assertEquals(listOf("old"), journal.snapshot().map { it.message })
    }

    @Test fun `oversized existing journal is loaded from a bounded tail`() {
        val file = seededFile()
        val validLine = file.readText()
        file.writeText("x".repeat(10000) + "\n" + validLine)
        val journal = DiagnosticJournal(executor = Executor { it.run() }, maxBytes = 1024)
        journal.initialize(file)
        assertEquals(listOf("old"), journal.snapshot().map { it.message })
        assertTrue(file.length() <= 1024)
    }

    @Test fun `disk failures preserve current session diagnostics`() {
        val directory = temporary.newFolder()
        val journal = DiagnosticJournal(executor = Executor { it.run() })
        journal.initialize(directory)
        journal.append(event("still visible"))
        assertEquals(listOf("still visible"), journal.snapshot().map { it.message })
        journal.clear()
        assertTrue(journal.snapshot().isEmpty())
    }

    private fun seededFile(): File {
        val file = File(temporary.root, "journal")
        val journal = DiagnosticJournal(executor = Executor { it.run() })
        journal.initialize(file)
        journal.append(event("old"))
        return file
    }

    private fun event(message: String) = AppLog.LogEntry(123456789, "I", "Listener", message)

    private class DeferredExecutor : Executor {
        private val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.add(command) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
}
