package com.banknotify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.banknotify.db.BankNotificationsDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DatabaseSingletonTest {
    @Test fun `concurrent first callers receive the same database instance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instance = BankNotificationsDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        val previous = instance.get(null)
        instance.set(null, null)
        val databases = Collections.synchronizedList(mutableListOf<BankNotificationsDatabase>())
        val entered = CountDownLatch(4)
        val threads = List(4) {
            Thread {
                entered.countDown()
                databases.add(BankNotificationsDatabase.getDatabase(context))
            }
        }
        try {
            synchronized(BankNotificationsDatabase.Companion) {
                threads.forEach { it.start() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (threads.any { it.state != Thread.State.BLOCKED } && System.nanoTime() < timeout) Thread.sleep(1)
                // All callers saw the absent instance before any caller may enter initialization.
                assertTrue(threads.all { it.state == Thread.State.BLOCKED })
            }
            threads.forEach { it.join(5_000) }
            assertTrue(threads.none { it.isAlive })
            assertEquals(4, databases.size)
            assertEquals(1, databases.toSet().size)
        } finally {
            threads.forEach { it.join(5_000) }
            databases.toSet().forEach { it.close() }
            instance.set(null, previous)
        }
    }
}
