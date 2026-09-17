package com.nuvio.app.features.p2p

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class P2pStreamingDefaultsTest {
    @Test
    fun `performance defaults override TorrServer stock values`() {
        val original = buildJsonObject {
            put("ConnectionsLimit", 25)
            put("TorrentDisconnectTimeout", 30)
            put("CacheSize", 64 * 1024 * 1024)
            put("DisableUTP", false)
            put("DisableUpload", false)
            put("ReaderReadAHead", 95)
        }

        val configured = original.withNuvioP2pPerformanceDefaults(enableUpload = true)

        assertEquals(NUVIO_TORRENT_CONNECTION_LIMIT, configured.getValue("ConnectionsLimit").jsonPrimitive.int)
        assertEquals(
            NUVIO_TORRENT_RETENTION_SECONDS,
            configured.getValue("TorrentDisconnectTimeout").jsonPrimitive.int,
        )
        assertEquals(NUVIO_TORRENT_CACHE_BYTES, configured.getValue("CacheSize").jsonPrimitive.long)
        assertTrue(configured.getValue("DisableUTP").jsonPrimitive.boolean)
        assertFalse(configured.getValue("DisableUpload").jsonPrimitive.boolean)
        // Keys we do not own pass through untouched.
        assertEquals(95, configured.getValue("ReaderReadAHead").jsonPrimitive.int)
    }

    @Test
    fun `upload toggle maps onto DisableUpload`() {
        val configured = buildJsonObject {}.withNuvioP2pPerformanceDefaults(enableUpload = false)

        assertTrue(configured.getValue("DisableUpload").jsonPrimitive.boolean)
    }

    @Test
    fun `performance defaults are idempotent`() {
        val original = buildJsonObject {
            put("ConnectionsLimit", NUVIO_TORRENT_CONNECTION_LIMIT)
            put("TorrentDisconnectTimeout", NUVIO_TORRENT_RETENTION_SECONDS)
            put("CacheSize", NUVIO_TORRENT_CACHE_BYTES)
            put("DisableUTP", true)
            put("DisableUpload", false)
        }

        assertEquals(original, original.withNuvioP2pPerformanceDefaults(enableUpload = true))
    }
}
