package com.banknotify

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class TelegramHttpClientsTest {
    @Test fun `proxy clients reuse resources and evict least recently used configuration`() {
        val direct = TelegramHttpClients.forProxy("")
        assertSame(direct, TelegramHttpClients.forProxy(""))
        val routes = (0..8).map { "http://cache-$it:fixture@127.0.0.1:${21000 + it}" }
        val initial = routes.take(8).map(TelegramHttpClients::forProxy)
        assertSame(initial[0], TelegramHttpClients.forProxy(routes[0]))
        TelegramHttpClients.forProxy(routes[8])
        assertSame("Recently used route must survive eviction", initial[0], TelegramHttpClients.forProxy(routes[0]))
        assertNotSame("Unused route must not stay cached indefinitely", initial[1], TelegramHttpClients.forProxy(routes[1]))
        for (client in initial) {
            assertSame(direct.connectionPool, client.connectionPool)
            assertSame(direct.dispatcher, client.dispatcher)
        }
    }

    @Test fun `HTTP proxy receives absolute target and Telegram response confirms delivery`() {
        LocalProxy { socket, _ ->
            val request = readRequest(socket)
            respond(socket, 200, SUCCESS)
            request
        }.use { proxy ->
            withClient("http://127.0.0.1:${proxy.port}") { client ->
                assertTrue(deliver(client).delivered)
            }
            val received = proxy.await().single()
            assertEquals("POST $TARGET HTTP/1.1", received.line)
            assertEquals(HOST, received.headers["host"])
            assertEquals("application/json; charset=utf-8", received.headers["content-type"])
            assertEquals(PAYLOAD, received.body)
            assertNull(received.headers["proxy-authorization"])
        }
    }

    @Test fun `HTTP proxy challenge retries with decoded credentials`() {
        LocalProxy(connections = 2) { socket, index ->
            val request = readRequest(socket)
            if (index == 0) {
                respond(socket, 407, "", "Proxy-Authenticate: Basic realm=\"fixture\"\r\n")
            } else {
                respond(socket, 200, SUCCESS)
            }
            request
        }.use { proxy ->
            withClient("http://name%2Btag:p%40ss@127.0.0.1:${proxy.port}") { client ->
                assertTrue(deliver(client).delivered)
            }
            val requests = proxy.await()
            assertNull(requests[0].headers["proxy-authorization"])
            assertEquals(Credentials.basic("name+tag", "p@ss"), requests[1].headers["proxy-authorization"])
            assertEquals(PAYLOAD, requests[1].body)
        }
    }

    @Test fun `SOCKS5 connects without authentication and sends target hostname to proxy`() {
        LocalProxy { socket, _ ->
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            assertEquals(5, input.readUnsignedByte())
            val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
            assertTrue("SOCKS client must offer unauthenticated mode", methods.contains(0.toByte()))
            output.write(byteArrayOf(5, 0))
            output.flush()
            assertEquals(5, input.readUnsignedByte())
            assertEquals("SOCKS command must be CONNECT", 1, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            assertEquals("Destination must use remote hostname resolution", 3, input.readUnsignedByte())
            val hostname = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.US_ASCII)
            val port = input.readUnsignedShort()
            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
            output.flush()
            val request = readRequest(socket)
            respond(socket, 200, SUCCESS)
            Triple(hostname, port, request)
        }.use { proxy ->
            withClient("socks5://127.0.0.1:${proxy.port}") { client ->
                assertTrue(deliver(client).delivered)
            }
            val (hostname, port, request) = proxy.await().single()
            assertEquals(HOST, hostname)
            assertEquals(80, port)
            assertEquals("POST /bot-fixture/sendMessage HTTP/1.1", request.line)
            assertEquals(PAYLOAD, request.body)
            assertNull(request.headers["proxy-authorization"])
        }
    }

    @Test fun `invalid legacy SOCKS credentials fall through to a working HTTP proxy`() = runBlocking {
        LocalProxy { socket, _ ->
            val request = readRequest(socket)
            respond(socket, 200, SUCCESS)
            request
        }.use { proxy ->
            val invalid = "socks5://legacy:password@127.0.0.1:${proxy.port}"
            val valid = "http://127.0.0.1:${proxy.port}"
            val attempted = mutableListOf<String>()
            val result = ProxyFailover.deliver(listOf(invalid, valid)) { route ->
                attempted += route
                withClient(route, ::deliver)
            }
            assertEquals(listOf(invalid, valid), attempted)
            assertTrue(result.delivered)
            assertEquals("POST $TARGET HTTP/1.1", proxy.await().single().line)
        }
    }

    @Test fun `Telegram rate limit from real HTTP route stops proxy failover`() = runBlocking {
        LocalProxy { socket, _ ->
            readRequest(socket)
            respond(socket, 429, """{"ok":false,"parameters":{"retry_after":9}}""")
        }.use { proxy ->
            val attempted = mutableListOf<String>()
            val route = "http://127.0.0.1:${proxy.port}"
            val result = ProxyFailover.deliver(listOf(route, "must-not-connect")) {
                attempted += it
                withClient(it, ::deliver)
            }
            assertEquals(listOf(route), attempted)
            assertFalse(result.delivered)
            assertEquals(9_000L, result.retryAfterMillis)
            proxy.await()
            Unit
        }
    }

    private fun deliver(client: OkHttpClient): TelegramDeliveryResult {
        val request = Request.Builder().url(TARGET)
            .post(PAYLOAD.toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute().use { response ->
            TelegramDeliveryResult.parse(response.code, response.body!!.string())
        }
    }

    private fun <T> withClient(route: String, action: (OkHttpClient) -> T): T {
        // Preserve production proxy/auth settings but own test network resources so cleanup cannot
        // shut down the process-wide client cache used by another test.
        val client = TelegramHttpClients.forProxy(route).newBuilder()
            .dispatcher(Dispatcher()).connectionPool(ConnectionPool())
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .dns(Dns { hostname ->
                check(hostname == "127.0.0.1") { "Unexpected local DNS lookup: $hostname" }
                listOf(LOOPBACK)
            }).build()
        return try {
            action(client)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            check(client.dispatcher.executorService.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private data class HttpRequest(val line: String, val headers: Map<String, String>, val body: String)

    private fun readRequest(socket: Socket): HttpRequest {
        val input = DataInputStream(socket.getInputStream())
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) throw EOFException("Proxy request ended before headers")
                if (next == '\n'.code) return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
                check(bytes.size() < 16_384) { "Unexpected oversized request header" }
                bytes.write(next)
            }
        }
        val requestLine = line()
        val headers = linkedMapOf<String, String>()
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val separator = header.indexOf(':')
            check(separator > 0) { "Malformed request header" }
            headers[header.substring(0, separator).lowercase()] = header.substring(separator + 1).trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        check(length in 0..16_384)
        val body = ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
        return HttpRequest(requestLine, headers, body)
    }

    private fun respond(socket: Socket, status: Int, body: String, extraHeaders: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n$extraHeaders\r\n"
        socket.getOutputStream().apply {
            write(headers.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private class LocalProxy<T>(connections: Int = 1, handle: (Socket, Int) -> T) : AutoCloseable {
        private val server = ServerSocket(0, 1, LOOPBACK).apply { soTimeout = 5_000 }
        private val active = AtomicReference<Socket?>()
        private val closing = AtomicBoolean()
        private val completed = CompletableFuture<List<T>>()
        val port = server.localPort
        private val worker = thread(name = "telegram-proxy-fixture", isDaemon = true) {
            try {
                val results = (0 until connections).map { index ->
                    server.accept().use { socket ->
                        active.set(socket)
                        if (closing.get()) socket.close()
                        socket.soTimeout = 3_000
                        handle(socket, index)
                    }.also { active.set(null) }
                }
                completed.complete(results)
            } catch (failure: Throwable) {
                completed.completeExceptionally(failure)
            }
        }

        fun await(): List<T> = completed.get(5, TimeUnit.SECONDS)

        override fun close() {
            closing.set(true)
            server.close()
            active.getAndSet(null)?.close()
            worker.join(4_000)
            check(!worker.isAlive) { "Proxy fixture worker did not terminate" }
        }
    }

    companion object {
        private val LOOPBACK = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private const val HOST = "telegram-fixture.invalid"
        private const val TARGET = "http://$HOST/bot-fixture/sendMessage"
        private const val PAYLOAD = """{"chat_id":"fixture","text":"payment"}"""
        private const val SUCCESS = """{"ok":true,"result":{"message_id":42}}"""
    }
}
