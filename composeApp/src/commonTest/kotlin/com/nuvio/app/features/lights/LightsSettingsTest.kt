package com.nuvio.app.features.lights

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LightsSettingsTest {
    @Test
    fun webhookUrlKeepsPathAndOnlyFillsInScheme() {
        assertEquals(
            "http://homeassistant.local:8123/api/webhook/nuvio-start",
            normalizeWebhookUrl("  homeassistant.local:8123/api/webhook/nuvio-start "),
        )
        assertEquals(
            "https://maker.ifttt.com/trigger/lights/with/key/abc",
            normalizeWebhookUrl("https://maker.ifttt.com/trigger/lights/with/key/abc"),
        )
        assertEquals("", normalizeWebhookUrl("   "))
    }

    @Test
    fun storedSettingsRoundTripAndTolerateGarbage() {
        val stored = LightsStoredSettings(
            enabled = true,
            startAction = LightsStartAction.Dim,
            dimPercent = 15,
            goveeDevices = listOf(GoveeDevice(sku = "H6008", deviceId = "AA:BB", name = "Lamp")),
            goveeSelectedDeviceIds = listOf("AA:BB"),
            webhookEndUrl = "http://x/end",
        )
        assertEquals(stored, decodeLightsSettingsPayload(encodeLightsSettingsPayload(stored)))
        assertEquals(LightsStoredSettings(), decodeLightsSettingsPayload("not json"))
        assertEquals(LightsStoredSettings(), decodeLightsSettingsPayload(""))
    }

    @Test
    fun activeNeedsAProviderNotJustTheSwitch() {
        val bare = LightsSettings(enabled = true)
        assertFalse(bare.isActive)

        val keyOnly = bare.copy(goveeApiKey = "key")
        assertFalse(keyOnly.isActive)

        val device = GoveeDevice(sku = "H6008", deviceId = "AA:BB", name = "Lamp")
        val staleSelection = keyOnly.copy(goveeSelectedDeviceIds = setOf("gone"), goveeDevices = listOf(device))
        assertFalse(staleSelection.isActive)

        val govee = keyOnly.copy(goveeDevices = listOf(device), goveeSelectedDeviceIds = setOf("AA:BB"))
        assertTrue(govee.isActive)

        val webhook = bare.copy(webhookStartUrl = "http://x/start")
        assertTrue(webhook.isActive)
    }

    @Test
    fun dailyWindowWrapsPastMidnight() {
        val from = 20 * 60
        val until = 8 * 60
        assertTrue(isWithinDailyWindow(from, until, 20 * 60))
        assertTrue(isWithinDailyWindow(from, until, 23 * 60 + 59))
        assertTrue(isWithinDailyWindow(from, until, 0))
        assertTrue(isWithinDailyWindow(from, until, 3 * 60))
        assertFalse(isWithinDailyWindow(from, until, 8 * 60))
        assertFalse(isWithinDailyWindow(from, until, 12 * 60))
        assertFalse(isWithinDailyWindow(from, until, 19 * 60 + 59))

        // A same-day window, and the degenerate whole-day one.
        assertTrue(isWithinDailyWindow(9 * 60, 17 * 60, 12 * 60))
        assertFalse(isWithinDailyWindow(9 * 60, 17 * 60, 17 * 60))
        assertTrue(isWithinDailyWindow(6 * 60, 6 * 60, 12 * 60))

        val settings = LightsSettings(scheduleEnabled = false)
        assertTrue(settings.allowsAt(12 * 60))
        assertFalse(settings.copy(scheduleEnabled = true).allowsAt(12 * 60))
    }
}
