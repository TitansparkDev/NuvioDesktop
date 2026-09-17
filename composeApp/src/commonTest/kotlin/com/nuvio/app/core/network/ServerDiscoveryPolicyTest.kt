package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Discovery decides which host the app will send account credentials to, so the rejections matter
 * as much as the happy path.
 */
class ServerDiscoveryPolicyTest {

    private fun document(
        version: Int = 1,
        service: String = "nuvio",
        selfHosted: Boolean = true,
        backendUrl: String = "https://backend.example.com",
        publishableKey: String = "test-key",
        emailPasswordAuth: Boolean = true,
        tvLogin: Boolean = false,
    ) = """
        {
          "version": $version,
          "service": "$service",
          "self_hosted": $selfHosted,
          "backend_url": "$backendUrl",
          "publishable_key": "$publishableKey",
          "capabilities": {
            "email_password_auth": $emailPasswordAuth,
            "tv_login": $tvLogin
          }
        }
    """.trimIndent()

    private fun failureOf(block: () -> Unit): ServerDiscoveryFailure =
        assertFailsWith<ServerDiscoveryException> { block() }.failure

    @Test
    fun `bare host is assumed https and gets the well-known path`() {
        assertEquals(
            "https://backend.example.com/.well-known/nuvio",
            ServerDiscoveryPolicy.discoveryUrl("backend.example.com"),
        )
    }

    @Test
    fun `an explicit http scheme is preserved`() {
        assertEquals(
            "http://localhost:8081/.well-known/nuvio",
            ServerDiscoveryPolicy.discoveryUrl("http://localhost:8081"),
        )
    }

    @Test
    fun `a base path is kept and the well-known suffix is not doubled`() {
        assertEquals(
            "https://example.com/nuvio/.well-known/nuvio",
            ServerDiscoveryPolicy.discoveryUrl("https://example.com/nuvio/"),
        )
        assertEquals(
            "https://example.com/.well-known/nuvio",
            ServerDiscoveryPolicy.discoveryUrl("https://example.com/.well-known/nuvio"),
        )
    }

    @Test
    fun `credentials in the authority are rejected`() {
        assertEquals(
            ServerDiscoveryFailure.InvalidUrl,
            failureOf { ServerDiscoveryPolicy.discoveryUrl("https://user:pass@example.com") },
        )
    }

    @Test
    fun `blank and non-http inputs are rejected`() {
        assertEquals(
            ServerDiscoveryFailure.InvalidUrl,
            failureOf { ServerDiscoveryPolicy.discoveryUrl("   ") },
        )
        assertEquals(
            ServerDiscoveryFailure.InvalidUrl,
            failureOf { ServerDiscoveryPolicy.discoveryUrl("ftp://example.com") },
        )
    }

    @Test
    fun `a valid document produces a custom configuration`() {
        val url = "https://backend.example.com/.well-known/nuvio"
        val server = ServerDiscoveryPolicy.parse(url, document(tvLogin = true))

        assertEquals("https://backend.example.com", server.backendUrl)
        assertEquals("test-key", server.publishableKey)
        assertTrue(server.isCustom)
        assertTrue(server.capabilities.tvLogin)
        assertEquals(url, server.discoveryUrl)
        assertTrue(server.isSecure)
        assertTrue(server.isPublicHost)
    }

    @Test
    fun `documents that are not a self-hosted nuvio v1 are rejected`() {
        val url = "https://backend.example.com/.well-known/nuvio"
        assertEquals(
            ServerDiscoveryFailure.UnsupportedVersion,
            failureOf { ServerDiscoveryPolicy.parse(url, document(version = 2)) },
        )
        assertEquals(
            ServerDiscoveryFailure.WrongService,
            failureOf { ServerDiscoveryPolicy.parse(url, document(service = "something-else")) },
        )
        assertEquals(
            ServerDiscoveryFailure.NotSelfHosted,
            failureOf { ServerDiscoveryPolicy.parse(url, document(selfHosted = false)) },
        )
        assertEquals(
            ServerDiscoveryFailure.InvalidDocument,
            failureOf { ServerDiscoveryPolicy.parse(url, "not json") },
        )
    }

    @Test
    fun `a server without email and password auth is rejected`() {
        assertEquals(
            ServerDiscoveryFailure.UnsupportedAuthentication,
            failureOf {
                ServerDiscoveryPolicy.parse(
                    "https://backend.example.com/.well-known/nuvio",
                    document(emailPasswordAuth = false),
                )
            },
        )
    }

    @Test
    fun `a blank or unusable backend url is rejected`() {
        val url = "https://backend.example.com/.well-known/nuvio"
        assertEquals(
            ServerDiscoveryFailure.MissingConfiguration,
            failureOf { ServerDiscoveryPolicy.parse(url, document(publishableKey = " ")) },
        )
        assertEquals(
            ServerDiscoveryFailure.MissingConfiguration,
            failureOf { ServerDiscoveryPolicy.parse(url, document(backendUrl = "https://a@b.com")) },
        )
        assertEquals(
            ServerDiscoveryFailure.MissingConfiguration,
            failureOf { ServerDiscoveryPolicy.parse(url, document(backendUrl = "https://b.com/?x=1")) },
        )
    }

    @Test
    fun `loopback and private hosts are not treated as public`() {
        listOf(
            "http://localhost:8081",
            "http://127.0.0.1:8000",
            "http://192.168.1.10",
            "http://10.0.0.5",
            "http://172.16.4.1",
            "http://nas.local",
        ).forEach { url ->
            assertTrue(!isPublicServerHost(url), "$url should not be public")
        }

        listOf("https://backend.example.com", "http://172.32.0.1").forEach { url ->
            assertTrue(isPublicServerHost(url), "$url should be public")
        }
    }
}
