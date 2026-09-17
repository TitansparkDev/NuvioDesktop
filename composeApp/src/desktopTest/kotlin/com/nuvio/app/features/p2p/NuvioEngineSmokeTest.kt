package com.nuvio.app.features.p2p

import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.internal.NuvioEngineLibrary
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Loads the real nuvio_engine.dll through the JNI bridge and spins an engine up and down. This is
 * the check that the DLL exports what the Kotlin wrapper binds to and that libtorrent initialises
 * on this host. Skipped (not failed) when the DLL has not been built, so a box without the MSYS2
 * toolchain still gets a green desktopTest.
 */
class NuvioEngineSmokeTest {
    @Test
    fun engineCreatesAndReportsVersions() {
        if (!NuvioEngineLibrary.isAvailable) {
            println("NuvioEngineSmokeTest skipped: ${NuvioEngineLibrary.LIBRARY_FILE_NAME} not built")
            return
        }
        val root = Files.createTempDirectory("nuvio-engine-smoke").toFile()
        try {
            val engine = NuvioEngine.create(
                buildNuvioEngineConfig(
                    stateDirectory = root.resolve("state").apply { mkdirs() },
                    cacheDirectory = root.resolve("payload").apply { mkdirs() },
                    uploadEnabled = false,
                    torrentProfile = P2pTorrentProfile.BALANCED,
                    diskCacheCapacityBytes = 0L,
                ),
            )
            try {
                assertTrue(NuvioEngine.version.isNotBlank(), "engine version")
                // The backend reports libtorrent's own version string, e.g. "2.0.12.0".
                assertTrue(Regex("""\d+\.\d+\.\d+.*""").matches(NuvioEngine.protocolBackendVersion), NuvioEngine.protocolBackendVersion)
                // A request against an unknown torrent must round-trip through JNI as a status error, not a crash.
                val error = runCatching { runBlocking { engine.files("0".repeat(40)) } }.exceptionOrNull()
                assertTrue(error != null, "unknown torrent should fail")
                assertEquals(0, engine.stats.value.activeTorrents)
            } finally {
                engine.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
