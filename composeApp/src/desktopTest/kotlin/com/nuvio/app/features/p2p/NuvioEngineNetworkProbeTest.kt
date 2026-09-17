package com.nuvio.app.features.p2p

import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.internal.NuvioEngineLibrary
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Opt-in live-swarm probe: `-Pnuvio.p2pProbeHash=<infohash>` (wired to the system property of the
 * same name) adds that magnet through the real engine, waits for metadata, prepares a stream and
 * prints per-second swarm telemetry. Never runs in a normal test pass — it needs the network and
 * a torrent that is actually seeded.
 */
class NuvioEngineNetworkProbeTest {
    @Test
    fun probeLiveSwarm() {
        val hash = System.getProperty("nuvio.p2pProbeHash")?.trim().orEmpty()
        if (hash.isEmpty() || !NuvioEngineLibrary.isAvailable) {
            println("probe skipped (no -Dnuvio.p2pProbeHash or no DLL)")
            return
        }
        val root = Files.createTempDirectory("nuvio-engine-probe").toFile()
        val engine = NuvioEngine.create(
            buildNuvioEngineConfig(
                stateDirectory = root.resolve("state").apply { mkdirs() },
                cacheDirectory = root.resolve("payload").apply { mkdirs() },
                uploadEnabled = true,
                torrentProfile = P2pTorrentProfile.BALANCED,
                diskCacheCapacityBytes = 0L,
            ),
        )
        val startedAt = System.nanoTime()
        fun t() = (System.nanoTime() - startedAt) / 1_000_000
        try {
            runBlocking {
                val sampler = launch {
                    while (true) {
                        val s = engine.stats.value
                        println(
                            "t=${t()}ms peers=${s.connectedPeers} seeds=${s.connectedSeeds} known=${s.knownPeers} " +
                                "trackerReplies=${s.trackerReplyEvents} trackerErrors=${s.trackerErrorEvents} " +
                                "trackerPeers=${s.trackerPeersReturned} dhtReplies=${s.dhtReplyEvents} dhtPeers=${s.dhtPeersReturned} " +
                                "connects=${s.peerConnectEvents} disconnects=${s.peerDisconnectEvents} " +
                                "connFail=${s.peerDisconnectConnectFailures} timeouts=${s.peerDisconnectTimeouts} downBps=${s.downloadRateBytesPerSecond} " +
                                "unchoked=${s.unchokedPeers} downloading=${s.downloadingPeers} snubbed=${s.snubbedPeers} " +
                                "pendingBlocks=${s.pendingBlockRequests} targetBlocks=${s.targetBlockRequests} timedOutBlocks=${s.timedOutBlockRequests} " +
                                "targetPeers=${s.targetPiecePeers}/${s.targetPieceDownloadingPeers} offTarget=${s.offTargetDownloadingPeers} payload=${s.totalPayloadDownloadBytes}",
                        )
                        delay(1_000)
                    }
                }
                val magnet = buildP2pMagnetUri(
                    hash,
                    listOf(
                        "udp://tracker.opentrackr.org:1337/announce",
                        "udp://open.stealth.si:80/announce",
                        "udp://exodus.desync.com:6969/announce",
                        "udp://tracker.torrent.eu.org:451/announce",
                    ),
                )
                val torrentId = withTimeout(120_000) { engine.addMagnet(magnet) }
                println("METADATA after ${t()}ms torrent=$torrentId files=${engine.files(torrentId).size}")
                val stream = withTimeout(60_000) { engine.prepareStream(torrentId) }
                println("STREAM after ${t()}ms url=${stream.url} file=${stream.fileIndex} size=${stream.fileSize}")
                val preloadMb = System.getProperty("nuvio.p2pProbePreloadMb")?.toLongOrNull()?.coerceIn(1L, 64L) ?: 4L
                val stats = engine.preloadStream(stream, preloadMb * 1024 * 1024)
                println("PRELOAD ${preloadMb}MB after ${t()}ms contiguous=${stats.contiguousReadyBytes}")
                sampler.cancel()
                assertTrue(stats.contiguousReadyBytes >= preloadMb * 1024 * 1024)
            }
        } finally {
            engine.close()
            root.deleteRecursively()
        }
    }
}
