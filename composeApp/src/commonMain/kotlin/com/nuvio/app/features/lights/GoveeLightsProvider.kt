package com.nuvio.app.features.lights

import co.touchlab.kermit.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One bulb as it was the moment playback started — what "restore" puts back. */
@Serializable
internal data class GoveeCapturedDevice(
    val sku: String,
    val deviceId: String,
    val name: String,
    val powerOn: Boolean,
    val brightness: Int?,
)

@Serializable
internal data class GoveeCapturedScene(
    val capturedAtMs: Long,
    val devices: List<GoveeCapturedDevice>,
)

/**
 * Govee bulbs through the cloud API.
 *
 * The scene is captured once per playback session and held both in memory and on disk. On disk
 * because a crash mid-film — the app's, or the machine's — would otherwise leave the room dark with
 * nothing that remembers how it was; [restoreAfterCrash] runs at the next launch. Held for the
 * whole session (not re-captured on Resume) because a paused intermission has already put the
 * lights back, and capturing *that* would just be capturing our own restore.
 */
internal object GoveeLightsProvider : LightsProvider {
    override val id: String = "govee"

    private val log = Logger.withTag("Lights/Govee")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** A crash snapshot older than this is not restored: the room has long since moved on. */
    private const val CRASH_RESTORE_MAX_AGE_MS = 3 * 60 * 60 * 1_000L

    /**
     * Govee's per-device limit is 2 requests/s; power then brightness on the same bulb are spaced
     * so a restore never trips it.
     */
    private const val SAME_DEVICE_GAP_MS = 550L

    private var scene: GoveeCapturedScene? = null

    override fun isConfigured(settings: LightsSettings): Boolean = settings.hasGovee

    override suspend fun handle(event: LightsEvent, settings: LightsSettings) {
        when (event) {
            LightsEvent.Start -> {
                if (scene == null) capture(settings)
                applyScene(settings)
            }
            LightsEvent.Pause -> if (settings.pauseBehavior == LightsPauseBehavior.BringUp) {
                scene?.let { restore(settings, it, LightsEndBehavior.Restore) }
            }
            LightsEvent.Resume -> if (settings.pauseBehavior == LightsPauseBehavior.BringUp) {
                applyScene(settings)
            }
            LightsEvent.End -> {
                val captured = scene
                scene = null
                LightsSettingsStorage.saveGoveeSnapshot(null)
                if (captured != null) restore(settings, captured, settings.endBehavior)
            }
        }
    }

    /** Called once at startup; a no-op unless the previous run died with the lights down. */
    suspend fun restoreAfterCrash(settings: LightsSettings) {
        val stored = LightsSettingsStorage.loadGoveeSnapshot() ?: return
        LightsSettingsStorage.saveGoveeSnapshot(null)
        val captured = runCatching { json.decodeFromString(GoveeCapturedScene.serializer(), stored) }
            .getOrNull() ?: return
        val age = System.currentTimeMillis() - captured.capturedAtMs
        if (age !in 0..CRASH_RESTORE_MAX_AGE_MS) {
            log.i { "Discarding a ${age / 60_000}-minute-old lights snapshot; too old to restore" }
            return
        }
        if (settings.goveeApiKey.isBlank()) return
        log.i { "Restoring ${captured.devices.size} light(s) left down by the previous run" }
        restore(settings, captured, settings.endBehavior)
    }

    private suspend fun capture(settings: LightsSettings) {
        val apiKey = settings.goveeApiKey
        val devices = settings.goveeActiveDevices
        val captured = coroutineScope {
            devices.map { device ->
                async {
                    when (val result = GoveeClient.readState(apiKey, device)) {
                        is GoveeResult.Ok -> {
                            if (!result.value.online) {
                                log.w { "${device.name} is offline; leaving it out of this session" }
                                null
                            } else {
                                GoveeCapturedDevice(
                                    sku = device.sku,
                                    deviceId = device.deviceId,
                                    name = device.name,
                                    powerOn = result.value.powerOn,
                                    brightness = result.value.brightness,
                                )
                            }
                        }
                        is GoveeResult.Unauthorized -> {
                            log.w { "Govee rejected the API key while reading ${device.name}: ${result.detail}" }
                            null
                        }
                        is GoveeResult.Failed -> {
                            log.w { "Could not read ${device.name}: ${result.detail}" }
                            null
                        }
                    }
                }
            }.mapNotNull { it.await() }
        }
        val next = GoveeCapturedScene(capturedAtMs = System.currentTimeMillis(), devices = captured)
        scene = next
        LightsSettingsStorage.saveGoveeSnapshot(json.encodeToString(GoveeCapturedScene.serializer(), next))
        log.i {
            "Captured ${captured.size}/${devices.size} light(s): " +
                captured.joinToString { "${it.name}=${if (it.powerOn) "on@${it.brightness ?: "?"}" else "off"}" }
        }
    }

    private suspend fun applyScene(settings: LightsSettings) {
        val captured = scene ?: return
        val apiKey = settings.goveeApiKey
        coroutineScope {
            captured.devices.map { device ->
                async {
                    val target = device.toDevice()
                    when (settings.startAction) {
                        LightsStartAction.TurnOff -> {
                            if (device.powerOn) GoveeClient.setPower(apiKey, target, on = false).logFailure("off", device)
                        }
                        LightsStartAction.Dim -> {
                            // A bulb that was off stays off — dimming it would switch it on.
                            if (device.powerOn) {
                                GoveeClient.setBrightness(apiKey, target, settings.dimPercent).logFailure("dim", device)
                            }
                        }
                    }
                }
            }.forEach { it.await() }
        }
    }

    private suspend fun restore(
        settings: LightsSettings,
        captured: GoveeCapturedScene,
        behavior: LightsEndBehavior,
    ) {
        val apiKey = settings.goveeApiKey
        coroutineScope {
            captured.devices.map { device ->
                async {
                    val target = device.toDevice()
                    val wantOn = device.powerOn || behavior == LightsEndBehavior.TurnOn
                    if (!wantOn) return@async
                    // Brightness first, so a bulb that was switched off comes back at its old level
                    // rather than flashing full before settling. (Some Govee firmware treats a
                    // brightness command as an implicit power-on; the explicit one after it is
                    // then a harmless no-op.)
                    val brightness = device.brightness ?: if (device.powerOn) null else 100
                    if (brightness != null) {
                        GoveeClient.setBrightness(apiKey, target, brightness).logFailure("brightness", device)
                        delay(SAME_DEVICE_GAP_MS)
                    }
                    GoveeClient.setPower(apiKey, target, on = true).logFailure("on", device)
                }
            }.forEach { it.await() }
        }
    }

    private fun GoveeCapturedDevice.toDevice() = GoveeDevice(sku = sku, deviceId = deviceId, name = name)

    private fun GoveeResult<Unit>.logFailure(what: String, device: GoveeCapturedDevice) {
        when (this) {
            is GoveeResult.Ok -> Unit
            is GoveeResult.Unauthorized -> log.w { "Govee rejected the API key ($what ${device.name}): $detail" }
            is GoveeResult.Failed -> log.w { "Could not set $what on ${device.name}: $detail" }
        }
    }
}
