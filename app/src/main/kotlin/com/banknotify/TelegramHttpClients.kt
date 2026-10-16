package com.banknotify

import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** A bounded cache; derived clients share the dispatcher's threads and connection pool. */
internal object TelegramHttpClients {
    private val base = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).proxy(Proxy.NO_PROXY).build()
    private val clients = object : LinkedHashMap<String, OkHttpClient>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OkHttpClient>?): Boolean = size > 8
    }

    @Synchronized fun forProxy(rawProxy: String): OkHttpClient {
        if (rawProxy.isEmpty()) return base
        return clients.getOrPut(rawProxy) {
            val config = requireNotNull(TelegramProxyConfig.parse(rawProxy)) { "Некорректный прокси" }
            base.newBuilder().proxy(config.proxy).apply {
                if (config.proxy.type() == Proxy.Type.HTTP && config.username != null && config.password != null) {
                    proxyAuthenticator { _, response ->
                        if (response.request.header("Proxy-Authorization") != null) null
                        else response.request.newBuilder().header("Proxy-Authorization",
                            Credentials.basic(config.username, config.password)).build()
                    }
                }
            }.build()
        }
    }
}
