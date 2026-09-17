package com.nuvio.app.features.screensaver

import com.nuvio.app.core.storage.DesktopStorage

internal actual object ScreensaverSettingsStorage {
    private val store = DesktopStorage.store("nuvio_screensaver")
    private const val payloadKey = "settings"

    actual fun loadPayload(): String? = store.getString(payloadKey)

    actual fun savePayload(payload: String) {
        store.putString(payloadKey, payload)
    }
}
