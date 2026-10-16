package com.banknotify

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class HttpCallTest {
    @Test fun `cancelling before response headers cancels the HTTP call`() = runBlocking {
        assertCancellation(stallBody = false)
    }

    @Test fun `cancelling while reading response body cancels the HTTP call`() = runBlocking {
        assertCancellation(stallBody = true)
    }

    @Test fun `complete response is consumed and returned`() = runBlocking {
        LocalServer("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok").use { server ->
            val client = client()
            try {
                val call = client.newCall(Request.Builder().url(server.url).build())
                val body = withTimeout(5_000) { call.awaitResponse { it.body!!.string() } }
                assertEquals("ok", body)
            } finally { close(client) }
        }
    }

    private suspend fun assertCancellation(stallBody: Boolean) = coroutineScope {
        val headers = if (stallBody) "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nx" else ""
        LocalServer(headers).use { server ->
            val client = client()
            val call = client.newCall(Request.Builder().url(server.url).build())
            val bodyStarted = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) {
                try {
                    call.awaitResponse {
                        bodyStarted.complete(Unit)
                        it.body!!.string()
                    }
                } catch (e: IOException) {
                    // Closing the fixture also wakes the old blocking implementation during RED.
                    if (currentCoroutineContext().isActive) throw e
                }
            }
            try {
                withTimeout(5_000) {
                    server.requestReceived.await()
                    if (stallBody) bodyStarted.await()
                }
                job.cancel()
                assertTrue("Coroutine cancellation must cancel the active socket call", call.isCanceled())
            } finally {
                server.close()
                job.cancelAndJoin()
                close(client)
            }
        }
    }

    private fun client() = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    private fun close(client: OkHttpClient) {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }

    private class LocalServer(private val response: String) : AutoCloseable {
        private val server = ServerSocket(0)
        private val connection = AtomicReference<Socket?>()
        val url = "http://127.0.0.1:${server.localPort}/"
        val requestReceived = CompletableDeferred<Unit>()
        private val worker = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    connection.set(socket)
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume request headers */ }
                    requestReceived.complete(Unit)
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().flush()
                    // Keep incomplete responses open until the caller cancels or closes the fixture.
                    socket.getInputStream().read()
                }
            } catch (_: IOException) { /* fixture shutdown closes the listening/connected socket */ }
        }

        override fun close() {
            server.close()
            connection.get()?.close()
            worker.join(2_000)
        }
    }
}
