package com.nuvio.app.features.plugins

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginHostFailureCacheTest {

    @BeforeTest
    fun reset() = PluginHostFailureCache.clear()

    @AfterTest
    fun cleanup() = PluginHostFailureCache.clear()

    @Test
    fun `a recorded host is reported until the ttl passes`() {
        PluginHostFailureCache.recordFailure("dead.example", "DNS lookup failed", nowMs = 1_000)
        assertEquals("DNS lookup failed", PluginHostFailureCache.recentFailure("dead.example", nowMs = 60_000))
        assertNull(PluginHostFailureCache.recentFailure("dead.example", nowMs = 1_000 + 90_001))
        // Expiry evicts, so a later probe inside a fresh window does not resurrect it.
        assertNull(PluginHostFailureCache.recentFailure("dead.example", nowMs = 2_000))
    }

    @Test
    fun `hosts are keyed case-insensitively by url host only`() {
        assertEquals("dead.example", PluginHostFailureCache.hostOf("https://Dead.Example/path?q=1"))
        assertEquals("dead.example", PluginHostFailureCache.hostOf("http://dead.example:8080/other"))
        assertNull(PluginHostFailureCache.hostOf("data:text/plain,hello"))
        assertNull(PluginHostFailureCache.hostOf("not a url"))
    }

    @Test
    fun `only host-level failures are classified`() {
        assertEquals("DNS lookup failed", PluginHostFailureCache.unreachableReason(UnknownHostException("x")))
        assertEquals("connection failed", PluginHostFailureCache.unreachableReason(ConnectException("Connection timed out: getsockopt")))
        assertEquals("connect timed out", PluginHostFailureCache.unreachableReason(SocketTimeoutException("connect timed out")))
        assertEquals("TLS handshake failed", PluginHostFailureCache.unreachableReason(SSLHandshakeException("Remote host terminated the handshake")))
        // Happened after a connection: the host is up, the request was slow.
        assertNull(PluginHostFailureCache.unreachableReason(SocketTimeoutException("Read timed out")))
        assertNull(PluginHostFailureCache.unreachableReason(InterruptedIOException("timeout")))
        assertNull(PluginHostFailureCache.unreachableReason(IllegalStateException("boom")))
    }
}
