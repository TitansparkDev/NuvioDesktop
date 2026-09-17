package com.nuvio.app.features.plugins

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLHandshakeException

/**
 * Short memory of hosts a plugin fetch could not reach at all.
 *
 * A stream search fans ~200 scrapers through a handful of runtime slots, and the same dead site
 * appears in several plugin repositories (and several scrapers of the same repository). Without
 * this, every copy pays the full connect timeout for a host that failed DNS or never answered a
 * SYN seconds earlier, each one pinning a slot the whole time. The cache only records failures
 * that describe the *host* rather than the request — a 5xx, a slow body, or a call-timeout on a
 * host that did connect is not evidence the next URL there will fail — and it forgets quickly,
 * so a site that was briefly down is retried on the next search rather than the next launch.
 */
internal object PluginHostFailureCache {
    private const val TTL_MS = 90_000L

    private class Failure(val atMs: Long, val reason: String)

    private val failures = ConcurrentHashMap<String, Failure>()

    /** Lower-cased host of [url], or null when it has none (a data: URL, a relative path). */
    fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** The recorded reason when [host] failed within the TTL, else null. */
    fun recentFailure(host: String, nowMs: Long = System.currentTimeMillis()): String? {
        val failure = failures[host] ?: return null
        if (nowMs - failure.atMs > TTL_MS) {
            failures.remove(host, failure)
            return null
        }
        return failure.reason
    }

    fun recordFailure(host: String, reason: String, nowMs: Long = System.currentTimeMillis()) {
        failures[host] = Failure(nowMs, reason)
    }

    /**
     * A one-line reason when [error] means the host itself is unreachable, else null. Read/call
     * timeouts and everything HTTP-level are deliberately not classified: they happened *after* a
     * connection, so the host is up.
     */
    fun unreachableReason(error: Throwable): String? = when (error) {
        is UnknownHostException -> "DNS lookup failed"
        is ConnectException -> "connection failed"
        is SocketTimeoutException ->
            if (error.message?.contains("connect", ignoreCase = true) == true) "connect timed out" else null
        is SSLHandshakeException -> "TLS handshake failed"
        else -> null
    }

    fun clear() = failures.clear()
}
