package com.nuvio.app.features.lights

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What happens to the room when playback starts. */
enum class LightsStartAction {
    /** Cinema default: everything selected goes off. */
    TurnOff,

    /** Lights that are on drop to [LightsSettings.dimPercent]; lights already off stay off. */
    Dim,
}

/** What happens to the room while playback is paused. */
enum class LightsPauseBehavior {
    /** Nothing: the room stays as it was when playback started. */
    StayDark,

    /** Intermission: the captured state comes back, and the scene is re-applied on resume. */
    BringUp,
}

/** What happens to the room when playback ends or the player is left. */
enum class LightsEndBehavior {
    /** Put every light back exactly as it was captured — a light that was off stays off. */
    Restore,

    /** Every selected light comes on at its previous brightness (full, if it was off). */
    TurnOn,
}

enum class LightsWebhookMethod {
    Get,
    Post,
}

/** One Govee device as listed by the account, cached so the picker works without a fetch. */
@Serializable
data class GoveeDevice(
    val sku: String,
    val deviceId: String,
    val name: String,
)

sealed interface GoveeDeviceLoadState {
    data object Idle : GoveeDeviceLoadState
    data object Loading : GoveeDeviceLoadState
    data class Loaded(val count: Int) : GoveeDeviceLoadState
    data class Failed(val detail: String) : GoveeDeviceLoadState
}

sealed interface LightsTestState {
    data object Idle : LightsTestState
    data object Running : LightsTestState
    data object Done : LightsTestState
    data class Failed(val detail: String) : LightsTestState
}

data class LightsSettings(
    val enabled: Boolean = false,
    val startAction: LightsStartAction = LightsStartAction.TurnOff,
    /** Brightness, 1..100, used by [LightsStartAction.Dim]. */
    val dimPercent: Int = 20,
    val pauseBehavior: LightsPauseBehavior = LightsPauseBehavior.StayDark,
    val endBehavior: LightsEndBehavior = LightsEndBehavior.Restore,
    /** Also react when playback is handed to mpv / VLC / another external player. */
    val externalPlayers: Boolean = true,
    /** Only react inside [scheduleFromMinutes]..[scheduleUntilMinutes] (local clock). */
    val scheduleEnabled: Boolean = true,
    /** Minutes past local midnight; the window wraps when "until" is earlier than "from". */
    val scheduleFromMinutes: Int = DEFAULT_SCHEDULE_FROM_MINUTES,
    val scheduleUntilMinutes: Int = DEFAULT_SCHEDULE_UNTIL_MINUTES,
    val goveeApiKey: String = "",
    val goveeDevices: List<GoveeDevice> = emptyList(),
    val goveeSelectedDeviceIds: Set<String> = emptySet(),
    val goveeDeviceLoadState: GoveeDeviceLoadState = GoveeDeviceLoadState.Idle,
    val webhookMethod: LightsWebhookMethod = LightsWebhookMethod.Post,
    val webhookStartUrl: String = "",
    val webhookPauseUrl: String = "",
    val webhookResumeUrl: String = "",
    val webhookEndUrl: String = "",
    val testState: LightsTestState = LightsTestState.Idle,
) {
    val hasGoveeKey: Boolean get() = goveeApiKey.isNotBlank()

    /** Selected devices that are still in the cached list; a stale selection is ignored. */
    val goveeActiveDevices: List<GoveeDevice>
        get() = goveeDevices.filter { it.deviceId in goveeSelectedDeviceIds }

    val hasGovee: Boolean get() = hasGoveeKey && goveeActiveDevices.isNotEmpty()

    val hasWebhook: Boolean
        get() = listOf(webhookStartUrl, webhookPauseUrl, webhookResumeUrl, webhookEndUrl)
            .any { it.isNotBlank() }

    /** At least one provider is usable; the enable switch is meaningless before this. */
    val hasAnyProvider: Boolean get() = hasGovee || hasWebhook

    val isActive: Boolean get() = enabled && hasAnyProvider

    /** True when the schedule is off, or the local time (minutes past midnight) is inside it. */
    fun allowsAt(nowMinutes: Int): Boolean =
        !scheduleEnabled || isWithinDailyWindow(scheduleFromMinutes, scheduleUntilMinutes, nowMinutes)
}

/** 20:00 → 08:00, the evening a cinema keeps. */
const val DEFAULT_SCHEDULE_FROM_MINUTES: Int = 20 * 60
const val DEFAULT_SCHEDULE_UNTIL_MINUTES: Int = 8 * 60
const val MINUTES_PER_DAY: Int = 24 * 60

/**
 * A daily window that may cross midnight: 20:00–08:00 contains 23:00 and 03:00 but not 12:00.
 * Equal ends mean the whole day — the only sensible reading of a window with no width.
 */
internal fun isWithinDailyWindow(fromMinutes: Int, untilMinutes: Int, nowMinutes: Int): Boolean {
    val from = fromMinutes.mod(MINUTES_PER_DAY)
    val until = untilMinutes.mod(MINUTES_PER_DAY)
    val now = nowMinutes.mod(MINUTES_PER_DAY)
    return when {
        from == until -> true
        from < until -> now in from until until
        else -> now >= from || now < until
    }
}

@Serializable
internal data class LightsStoredSettings(
    val enabled: Boolean = false,
    val startAction: LightsStartAction = LightsStartAction.TurnOff,
    val dimPercent: Int = 20,
    val pauseBehavior: LightsPauseBehavior = LightsPauseBehavior.StayDark,
    val endBehavior: LightsEndBehavior = LightsEndBehavior.Restore,
    val externalPlayers: Boolean = true,
    val scheduleEnabled: Boolean = true,
    val scheduleFromMinutes: Int = DEFAULT_SCHEDULE_FROM_MINUTES,
    val scheduleUntilMinutes: Int = DEFAULT_SCHEDULE_UNTIL_MINUTES,
    val goveeDevices: List<GoveeDevice> = emptyList(),
    val goveeSelectedDeviceIds: List<String> = emptyList(),
    val webhookMethod: LightsWebhookMethod = LightsWebhookMethod.Post,
    val webhookStartUrl: String = "",
    val webhookPauseUrl: String = "",
    val webhookResumeUrl: String = "",
    val webhookEndUrl: String = "",
)

private val lightsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun decodeLightsSettingsPayload(payload: String): LightsStoredSettings {
    if (payload.isBlank()) return LightsStoredSettings()
    return runCatching {
        lightsJson.decodeFromString(LightsStoredSettings.serializer(), payload)
    }.getOrDefault(LightsStoredSettings())
}

internal fun encodeLightsSettingsPayload(settings: LightsStoredSettings): String =
    lightsJson.encodeToString(LightsStoredSettings.serializer(), settings)

internal expect object LightsSettingsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)

    /**
     * Kept out of the JSON payload and under its own key, so the settings backup's credential
     * scrub — which matches on property names — recognises it and leaves it out of shared exports.
     */
    fun loadGoveeApiKey(): String
    fun saveGoveeApiKey(value: String)

    /** The Govee state captured at playback start, kept on disk so a crash mid-film can be undone. */
    fun loadGoveeSnapshot(): String?
    fun saveGoveeSnapshot(payload: String?)
}

/**
 * "Lights out": dim or switch off the room's smart bulbs when playback starts and bring them back
 * when it ends. Machine-scoped rather than profile-scoped — the bulbs belong to the room, not to
 * whoever is signed in.
 */
object LightsSettingsRepository {
    private val _uiState = MutableStateFlow(LightsSettings())
    val uiState: StateFlow<LightsSettings> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var stored = LightsStoredSettings()
    private var goveeApiKey = ""
    private var goveeDeviceLoadState: GoveeDeviceLoadState = GoveeDeviceLoadState.Idle
    private var testState: LightsTestState = LightsTestState.Idle

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        stored = decodeLightsSettingsPayload(LightsSettingsStorage.loadPayload().orEmpty())
        goveeApiKey = LightsSettingsStorage.loadGoveeApiKey()
        publish()
    }

    fun snapshot(): LightsSettings {
        ensureLoaded()
        return _uiState.value
    }

    fun setEnabled(value: Boolean) = update { copy(enabled = value) }
    fun setStartAction(value: LightsStartAction) = update { copy(startAction = value) }
    fun setDimPercent(value: Int) = update { copy(dimPercent = value.coerceIn(1, 100)) }
    fun setPauseBehavior(value: LightsPauseBehavior) = update { copy(pauseBehavior = value) }
    fun setEndBehavior(value: LightsEndBehavior) = update { copy(endBehavior = value) }
    fun setExternalPlayers(value: Boolean) = update { copy(externalPlayers = value) }
    fun setScheduleEnabled(value: Boolean) = update { copy(scheduleEnabled = value) }
    fun setScheduleFromMinutes(value: Int) = update { copy(scheduleFromMinutes = value.mod(MINUTES_PER_DAY)) }
    fun setScheduleUntilMinutes(value: Int) = update { copy(scheduleUntilMinutes = value.mod(MINUTES_PER_DAY)) }

    fun setGoveeApiKey(value: String) {
        val trimmed = value.trim()
        ensureLoaded()
        if (goveeApiKey == trimmed) return
        goveeApiKey = trimmed
        LightsSettingsStorage.saveGoveeApiKey(trimmed)
        // A new key is a new account: the cached device list belongs to the old one.
        goveeDeviceLoadState = GoveeDeviceLoadState.Idle
        update { copy(goveeDevices = emptyList(), goveeSelectedDeviceIds = emptyList()) }
        publish()
    }

    fun toggleGoveeDevice(deviceId: String) = update {
        val selected = goveeSelectedDeviceIds.toMutableSet()
        if (!selected.remove(deviceId)) selected.add(deviceId)
        copy(goveeSelectedDeviceIds = selected.toList())
    }

    internal fun setGoveeDevices(devices: List<GoveeDevice>) = update {
        // Keep whatever selection still exists; drop ids the account no longer lists.
        val known = devices.map { it.deviceId }.toSet()
        copy(goveeDevices = devices, goveeSelectedDeviceIds = goveeSelectedDeviceIds.filter { it in known })
    }

    internal fun setGoveeDeviceLoadState(state: GoveeDeviceLoadState) {
        ensureLoaded()
        goveeDeviceLoadState = state
        publish()
    }

    internal fun setTestState(state: LightsTestState) {
        ensureLoaded()
        testState = state
        publish()
    }

    fun setWebhookMethod(value: LightsWebhookMethod) = update { copy(webhookMethod = value) }
    fun setWebhookStartUrl(value: String) = update { copy(webhookStartUrl = normalizeWebhookUrl(value)) }
    fun setWebhookPauseUrl(value: String) = update { copy(webhookPauseUrl = normalizeWebhookUrl(value)) }
    fun setWebhookResumeUrl(value: String) = update { copy(webhookResumeUrl = normalizeWebhookUrl(value)) }
    fun setWebhookEndUrl(value: String) = update { copy(webhookEndUrl = normalizeWebhookUrl(value)) }

    private inline fun update(transform: LightsStoredSettings.() -> LightsStoredSettings) {
        ensureLoaded()
        val next = stored.transform()
        if (next == stored) return
        stored = next
        LightsSettingsStorage.savePayload(encodeLightsSettingsPayload(stored))
        publish()
    }

    private fun publish() {
        _uiState.value = LightsSettings(
            enabled = stored.enabled,
            startAction = stored.startAction,
            dimPercent = stored.dimPercent.coerceIn(1, 100),
            pauseBehavior = stored.pauseBehavior,
            endBehavior = stored.endBehavior,
            externalPlayers = stored.externalPlayers,
            scheduleEnabled = stored.scheduleEnabled,
            scheduleFromMinutes = stored.scheduleFromMinutes.mod(MINUTES_PER_DAY),
            scheduleUntilMinutes = stored.scheduleUntilMinutes.mod(MINUTES_PER_DAY),
            goveeApiKey = goveeApiKey,
            goveeDevices = stored.goveeDevices,
            goveeSelectedDeviceIds = stored.goveeSelectedDeviceIds.toSet(),
            goveeDeviceLoadState = goveeDeviceLoadState,
            webhookMethod = stored.webhookMethod,
            webhookStartUrl = stored.webhookStartUrl,
            webhookPauseUrl = stored.webhookPauseUrl,
            webhookResumeUrl = stored.webhookResumeUrl,
            webhookEndUrl = stored.webhookEndUrl,
            testState = testState,
        )
    }
}

/**
 * A pasted webhook is taken as-is apart from whitespace: unlike a base URL the path *is* the
 * point (Home Assistant's `/api/webhook/<id>`), so nothing after the host may be trimmed. Only a
 * missing scheme is filled in, and as plain http — these are LAN targets far more often than not.
 */
internal fun normalizeWebhookUrl(value: String): String {
    val raw = value.trim()
    if (raw.isBlank()) return ""
    if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) return raw
    return "http://$raw"
}
