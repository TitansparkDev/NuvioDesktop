package com.nuvio.app.features.lights

/** The four moments the room reacts to. */
internal enum class LightsEvent {
    Start,
    Pause,
    Resume,
    End,
}

/**
 * One way of reaching the lights. The controller owns *when* an event fires and serialises them;
 * a provider owns *what* the event means for its lights, and must never throw — a bulb that is
 * unreachable is logged and skipped so playback is never held up by the room.
 */
internal interface LightsProvider {
    val id: String

    /** True when the settings carry enough for this provider to do anything at all. */
    fun isConfigured(settings: LightsSettings): Boolean

    suspend fun handle(event: LightsEvent, settings: LightsSettings)
}
