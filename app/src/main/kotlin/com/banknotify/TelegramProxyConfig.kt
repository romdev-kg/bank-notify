package com.banknotify

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object TelegramProxyConfig {
    fun parse(rawValue: String): ProxyConfig? {
        val value = rawValue.trim()
        if (value.isEmpty()) return null

        return runCatching {
            val uri = URI(if (value.contains("://")) value else "http://$value")
            val host = uri.host ?: return null
            val port = if (uri.port in 1..65535) uri.port else return null
            val type = when (uri.scheme?.lowercase(Locale.US)) {
                "socks", "socks4", "socks5" -> Proxy.Type.SOCKS
                "http" -> Proxy.Type.HTTP
                else -> return null
            }
            val credentials = uri.rawUserInfo?.split(":", limit = 2)

            ProxyConfig(
                proxy = Proxy(type, InetSocketAddress(host, port)),
                username = credentials?.getOrNull(0)?.urlDecode(),
                password = credentials?.getOrNull(1)?.urlDecode()
            )
        }.getOrNull()
    }

    // URL credentials use URI escaping: a literal plus is not a form-encoded space.
    private fun String.urlDecode(): String =
        URLDecoder.decode(replace("+", "%2B"), StandardCharsets.UTF_8.name())

    data class ProxyConfig(
        val proxy: Proxy,
        val username: String?,
        val password: String?
    )
}
