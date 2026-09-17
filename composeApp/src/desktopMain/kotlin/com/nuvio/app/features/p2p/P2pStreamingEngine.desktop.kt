package com.nuvio.app.features.p2p

import co.touchlab.kermit.Logger
import com.nuvio.engine.internal.NuvioEngineLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One desktop torrent engine. Both implementations are singletons that own their native state. */
internal interface DesktopP2pBackend {
    val state: StateFlow<P2pStreamingState>
    val cacheState: StateFlow<P2pCacheUiState>
    suspend fun startStream(request: P2pStreamRequest): String
    suspend fun clearCache(): P2pCacheClearResult
    fun stopStream()
    fun shutdown()
}

/**
 * Desktop P2P entry point. Chooses between [NuvioEngineP2pBackend] (in-process libtorrent, the
 * default) and [TorrServerP2pBackend] (external daemon) per the Settings > P2P "engine" choice,
 * re-evaluated on every stream start so a switch takes effect on the next play. Nuvio Engine
 * silently yields to TorrServer when its DLL is not on disk, so a build without the native
 * toolchain still plays torrents.
 *
 * `-Dnuvio.p2p.backend=torrserver|nuvio` overrides the setting for a single run.
 */
actual object P2pStreamingEngine {
    private val log = Logger.withTag("P2pStreamingEngine")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private val _state = MutableStateFlow<P2pStreamingState>(P2pStreamingState.Idle)
    actual val state: StateFlow<P2pStreamingState> = _state.asStateFlow()
    private val _cacheState = MutableStateFlow(P2pCacheUiState())
    actual val cacheState: StateFlow<P2pCacheUiState> = _cacheState.asStateFlow()

    private var active: DesktopP2pBackend? = null
    private var mirrorJobs: List<Job> = emptyList()

    actual suspend fun startStream(request: P2pStreamRequest): String =
        selectBackend().startStream(request)

    actual suspend fun clearCache(): P2pCacheClearResult = selectBackend().clearCache()

    actual fun stopStream() {
        synchronized(lock) { active }?.stopStream()
    }

    actual fun shutdown() {
        // Both are idempotent; shutting down the inactive one is a no-op that costs nothing.
        NuvioEngineP2pBackend.shutdown()
        TorrServerP2pBackend.shutdown()
    }

    /** Resolves the configured backend and rebinds the mirrored flows if it changed. */
    private fun selectBackend(): DesktopP2pBackend {
        P2pSettingsRepository.ensureLoaded()
        val wanted = resolveWantedBackend()
        val backend: DesktopP2pBackend = when (wanted) {
            P2pEngineBackend.NUVIO_ENGINE -> NuvioEngineP2pBackend
            P2pEngineBackend.TORRSERVER -> TorrServerP2pBackend
        }
        val previous = synchronized(lock) {
            if (active === backend) return backend
            val old = active
            active = backend
            mirrorJobs.forEach(Job::cancel)
            mirrorJobs = listOf(
                scope.launch { backend.state.collect { _state.value = it } },
                scope.launch { backend.cacheState.collect { _cacheState.value = it } },
            )
            old
        }
        previous?.stopStream()
        log.i { "P2P backend: $wanted${if (previous != null) " (switched from ${previous::class.simpleName})" else ""}" }
        return backend
    }

    private fun resolveWantedBackend(): P2pEngineBackend {
        val override = System.getProperty("nuvio.p2p.backend")?.trim()?.lowercase()
        val configured = when (override) {
            "torrserver" -> P2pEngineBackend.TORRSERVER
            "nuvio", "nuvio_engine", "nuvioengine" -> P2pEngineBackend.NUVIO_ENGINE
            else -> P2pSettingsRepository.uiState.value.engineBackend
        }
        if (configured == P2pEngineBackend.NUVIO_ENGINE && !NuvioEngineLibrary.isAvailable) {
            log.w { "Nuvio Engine selected but ${NuvioEngineLibrary.LIBRARY_FILE_NAME} is not available; using TorrServer" }
            return P2pEngineBackend.TORRSERVER
        }
        return configured
    }

    actual val nuvioEngineAvailable: Boolean
        get() = NuvioEngineLibrary.isAvailable
}
