package com.nuvio.app.features.screensaver

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreensaverSettingsTest {

    @Test
    fun payloadRoundTrips() {
        val stored = ScreensaverStoredSettings(
            enabled = true,
            dimDelayMinutes = 1,
            playbackDimDelayMinutes = 45,
            dimPercent = 65,
            activeDuringPlayback = true,
            shutdownEnabled = true,
            shutdownDelayMinutes = 90,
            playbackShutdownDelayMinutes = 240,
        )

        assertEquals(stored, decodeScreensaverSettingsPayload(encodeScreensaverSettingsPayload(stored)))
    }

    @Test
    fun blankAndBrokenPayloadsFallBackToDefaults() {
        assertEquals(ScreensaverStoredSettings(), decodeScreensaverSettingsPayload(""))
        assertEquals(ScreensaverStoredSettings(), decodeScreensaverSettingsPayload("{not json"))
    }

    @Test
    fun unknownKeysAreIgnoredSoOlderBuildsCanReadNewerPayloads() {
        val payload = """{"enabled":true,"dimPercent":40,"someFutureKey":123}"""

        val decoded = decodeScreensaverSettingsPayload(payload)

        assertEquals(true, decoded.enabled)
        assertEquals(40, decoded.dimPercent)
        assertEquals(SCREENSAVER_DEFAULT_DIM_DELAY_MINUTES, decoded.dimDelayMinutes)
    }

    @Test
    fun playbackDelaysApplyOnlyWhileThePlayerIsOpen() {
        val settings = ScreensaverSettings(
            dimDelayMinutes = 10,
            playbackDimDelayMinutes = 60,
            shutdownDelayMinutes = 30,
            playbackShutdownDelayMinutes = 180,
        )

        assertEquals(10, settings.dimDelayMinutes(playerActive = false))
        assertEquals(60, settings.dimDelayMinutes(playerActive = true))
        assertEquals(30, settings.shutdownDelayMinutes(playerActive = false))
        assertEquals(180, settings.shutdownDelayMinutes(playerActive = true))
    }

    @Test
    fun defaultsAreOffAndNothingIsScheduled() {
        val settings = ScreensaverSettings()

        assertEquals(false, settings.enabled)
        assertEquals(false, settings.shutdownEnabled)
        assertEquals(false, settings.anyActionEnabled)
        assertEquals(SCREENSAVER_DEFAULT_DIM_PERCENT, settings.dimPercent)
    }
}
