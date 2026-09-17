package com.nuvio.app.features.screensaver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Every delay comes in a browsing and a playback flavour: ten idle minutes on the home screen is
// someone who walked off, ten idle minutes in the player is someone watching a film. The
// 1-minute entry in each list exists for testing.

/** Idle time before dimming while browsing, in minutes. */
val SCREENSAVER_DIM_DELAY_OPTIONS_MINUTES: List<Int> = listOf(1, 2, 5, 10, 15, 20, 30, 45, 60)

/** Idle time before dimming while the player is open, in minutes. */
val SCREENSAVER_PLAYBACK_DIM_DELAY_OPTIONS_MINUTES: List<Int> = listOf(1, 15, 30, 45, 60, 90, 120, 180)

/** Idle time before the shutdown countdown while browsing, in minutes. */
val SCREENSAVER_SHUTDOWN_DELAY_OPTIONS_MINUTES: List<Int> = listOf(1, 15, 30, 45, 60, 90, 120, 180, 240)

/** Idle time before the shutdown countdown while the player is open, in minutes. */
val SCREENSAVER_PLAYBACK_SHUTDOWN_DELAY_OPTIONS_MINUTES: List<Int> = listOf(1, 30, 60, 90, 120, 180, 240, 300, 360)

const val SCREENSAVER_DEFAULT_DIM_DELAY_MINUTES = 10
const val SCREENSAVER_DEFAULT_PLAYBACK_DIM_DELAY_MINUTES = 60
const val SCREENSAVER_DEFAULT_DIM_PERCENT = 100
const val SCREENSAVER_DEFAULT_SHUTDOWN_DELAY_MINUTES = 60
const val SCREENSAVER_DEFAULT_PLAYBACK_SHUTDOWN_DELAY_MINUTES = 180

/** Seconds the shutdown countdown stays on screen before the PC is actually turned off. */
const val SCREENSAVER_SHUTDOWN_COUNTDOWN_SECONDS = 30

data class ScreensaverSettings(
    val enabled: Boolean = false,
    val dimDelayMinutes: Int = SCREENSAVER_DEFAULT_DIM_DELAY_MINUTES,
    val playbackDimDelayMinutes: Int = SCREENSAVER_DEFAULT_PLAYBACK_DIM_DELAY_MINUTES,
    /** 100 is fully black; anything lower leaves the screen partly visible through the shade. */
    val dimPercent: Int = SCREENSAVER_DEFAULT_DIM_PERCENT,
    /**
     * Whether idling while the player is open counts at all. Off is for people who never want a
     * film dimmed; on is for people who fall asleep with something playing, and then the
     * playback delays apply. Gates the shutdown too.
     */
    val activeDuringPlayback: Boolean = false,
    val shutdownEnabled: Boolean = false,
    val shutdownDelayMinutes: Int = SCREENSAVER_DEFAULT_SHUTDOWN_DELAY_MINUTES,
    val playbackShutdownDelayMinutes: Int = SCREENSAVER_DEFAULT_PLAYBACK_SHUTDOWN_DELAY_MINUTES,
) {
    /** True when the idle timer has anything at all to do. */
    val anyActionEnabled: Boolean get() = enabled || shutdownEnabled

    fun dimDelayMinutes(playerActive: Boolean): Int =
        if (playerActive) playbackDimDelayMinutes else dimDelayMinutes

    fun shutdownDelayMinutes(playerActive: Boolean): Int =
        if (playerActive) playbackShutdownDelayMinutes else shutdownDelayMinutes
}

@Serializable
internal data class ScreensaverStoredSettings(
    val enabled: Boolean = false,
    val dimDelayMinutes: Int = SCREENSAVER_DEFAULT_DIM_DELAY_MINUTES,
    val playbackDimDelayMinutes: Int = SCREENSAVER_DEFAULT_PLAYBACK_DIM_DELAY_MINUTES,
    val dimPercent: Int = SCREENSAVER_DEFAULT_DIM_PERCENT,
    val activeDuringPlayback: Boolean = false,
    val shutdownEnabled: Boolean = false,
    val shutdownDelayMinutes: Int = SCREENSAVER_DEFAULT_SHUTDOWN_DELAY_MINUTES,
    val playbackShutdownDelayMinutes: Int = SCREENSAVER_DEFAULT_PLAYBACK_SHUTDOWN_DELAY_MINUTES,
)

private val screensaverJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun decodeScreensaverSettingsPayload(payload: String): ScreensaverStoredSettings {
    if (payload.isBlank()) return ScreensaverStoredSettings()
    return runCatching {
        screensaverJson.decodeFromString(ScreensaverStoredSettings.serializer(), payload)
    }.getOrDefault(ScreensaverStoredSettings())
}

internal fun encodeScreensaverSettingsPayload(settings: ScreensaverStoredSettings): String =
    screensaverJson.encodeToString(ScreensaverStoredSettings.serializer(), settings)

internal expect object ScreensaverSettingsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}

/**
 * Screensaver: dim the window after a period of no input, and optionally shut the PC down after
 * a longer one. Machine-scoped rather than profile-scoped — it is about the display and the box
 * in the living room, not about whoever is signed in.
 *
 * Only the settings live here; the idle timer, the shade and the shutdown are desktop-side, in
 * `DesktopScreensaver`.
 */
object ScreensaverSettingsRepository {
    private val _uiState = MutableStateFlow(ScreensaverSettings())
    val uiState: StateFlow<ScreensaverSettings> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var stored = ScreensaverStoredSettings()

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        stored = decodeScreensaverSettingsPayload(ScreensaverSettingsStorage.loadPayload().orEmpty())
        publish()
    }

    fun snapshot(): ScreensaverSettings {
        ensureLoaded()
        return _uiState.value
    }

    fun setEnabled(value: Boolean) = update { copy(enabled = value) }
    fun setDimDelayMinutes(value: Int) = update { copy(dimDelayMinutes = value.coerceAtLeast(1)) }
    fun setPlaybackDimDelayMinutes(value: Int) = update { copy(playbackDimDelayMinutes = value.coerceAtLeast(1)) }
    fun setDimPercent(value: Int) = update { copy(dimPercent = value.coerceIn(1, 100)) }
    fun setActiveDuringPlayback(value: Boolean) = update { copy(activeDuringPlayback = value) }
    fun setShutdownEnabled(value: Boolean) = update { copy(shutdownEnabled = value) }
    fun setShutdownDelayMinutes(value: Int) = update { copy(shutdownDelayMinutes = value.coerceAtLeast(1)) }
    fun setPlaybackShutdownDelayMinutes(value: Int) = update { copy(playbackShutdownDelayMinutes = value.coerceAtLeast(1)) }

    private inline fun update(transform: ScreensaverStoredSettings.() -> ScreensaverStoredSettings) {
        ensureLoaded()
        val next = stored.transform()
        if (next == stored) return
        stored = next
        ScreensaverSettingsStorage.savePayload(encodeScreensaverSettingsPayload(stored))
        publish()
    }

    private fun publish() {
        _uiState.value = ScreensaverSettings(
            enabled = stored.enabled,
            dimDelayMinutes = stored.dimDelayMinutes.coerceAtLeast(1),
            playbackDimDelayMinutes = stored.playbackDimDelayMinutes.coerceAtLeast(1),
            dimPercent = stored.dimPercent.coerceIn(1, 100),
            activeDuringPlayback = stored.activeDuringPlayback,
            shutdownEnabled = stored.shutdownEnabled,
            shutdownDelayMinutes = stored.shutdownDelayMinutes.coerceAtLeast(1),
            playbackShutdownDelayMinutes = stored.playbackShutdownDelayMinutes.coerceAtLeast(1),
        )
    }
}
