package com.nuvio.app.features.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.ServerDiscoveryException
import com.nuvio.app.core.network.ServerDiscoveryFailure
import com.nuvio.app.core.network.ServerDiscoveryService
import com.nuvio.app.core.network.SupabaseProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ServerSwitchFailure {
    SessionClear,
    Save,
    Restart,
}

data class ServerConnectionUiState(
    val activeServer: ServerConfiguration = ServerConfigurationRepository.active.value,
    val isDiscovering: Boolean = false,
    val isSwitching: Boolean = false,
    val discoveredServer: ServerConfiguration? = null,
    val failure: ServerDiscoveryFailure? = null,
    val statusCode: Int? = null,
    val switchFailure: ServerSwitchFailure? = null,
)

object ServerConnectionController {
    private val log = Logger.withTag("ServerConnection")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ServerConnectionUiState())
    val state: StateFlow<ServerConnectionUiState> = _state.asStateFlow()
    private var discoveryJob: Job? = null

    fun discover(url: String) {
        if (_state.value.isDiscovering || _state.value.isSwitching) return
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            _state.update {
                it.copy(
                    isDiscovering = true,
                    discoveredServer = null,
                    failure = null,
                    statusCode = null,
                    switchFailure = null,
                )
            }
            ServerDiscoveryService.discover(url).fold(
                onSuccess = { server ->
                    _state.update { it.copy(isDiscovering = false, discoveredServer = server) }
                },
                onFailure = { error ->
                    val discoveryError = error as? ServerDiscoveryException
                    _state.update {
                        it.copy(
                            isDiscovering = false,
                            failure = discoveryError?.failure ?: ServerDiscoveryFailure.ConnectionFailed,
                            statusCode = discoveryError?.statusCode,
                        )
                    }
                },
            )
        }
    }

    fun connectDiscovered() {
        val server = _state.value.discoveredServer
        if (server == null) {
            log.w { "Trust confirmed but no discovered server is held in state" }
            return
        }
        log.i { "Trust confirmed for ${server.backendUrl}" }
        switchServer { ServerConfigurationRepository.saveCustom(server) }
    }

    fun useOfficial() {
        if (!_state.value.activeServer.isCustom) return
        switchServer(ServerConfigurationRepository::useOfficial)
    }

    fun resetDiscovery() {
        if (_state.value.isSwitching) return
        discoveryJob?.cancel()
        discoveryJob = null
        _state.update {
            it.copy(
                isDiscovering = false,
                discoveredServer = null,
                failure = null,
                statusCode = null,
                switchFailure = null,
            )
        }
    }

    private fun switchServer(save: () -> Boolean) {
        if (_state.value.isSwitching) {
            log.w { "Ignoring server switch: one is already in progress" }
            return
        }
        scope.launch {
            _state.update { it.copy(isSwitching = true, failure = null, switchFailure = null) }
            try {
                log.i { "Server switch started" }
                if (AuthRepository.prepareForServerSwitch().isFailure) {
                    log.e { "Server switch aborted: could not clear the local session" }
                    _state.update {
                        it.copy(isSwitching = false, switchFailure = ServerSwitchFailure.SessionClear)
                    }
                    return@launch
                }
                if (!save()) {
                    log.e { "Server switch aborted: the configuration could not be saved" }
                    _state.update { it.copy(isSwitching = false, switchFailure = ServerSwitchFailure.Save) }
                    return@launch
                }
                log.i { "Configuration saved, rebuilding the backend client" }
                SupabaseProvider.reset()
                AuthRepository.reinitialize()
                NetworkStatusRepository.requestRefresh(force = true)
                _state.value = ServerConnectionUiState(
                    activeServer = ServerConfigurationRepository.active.value,
                )
                log.i { "Server switch finished: now on ${ServerConfigurationRepository.active.value.backendUrl}" }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.e(error) { "Server switch failed" }
                _state.update { it.copy(isSwitching = false, switchFailure = ServerSwitchFailure.Restart) }
            }
        }
    }
}
