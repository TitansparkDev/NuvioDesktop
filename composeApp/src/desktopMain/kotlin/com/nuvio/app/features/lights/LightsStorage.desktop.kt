package com.nuvio.app.features.lights

import com.nuvio.app.core.storage.DesktopStorage

internal actual object LightsSettingsStorage {
    private val store = DesktopStorage.store("nuvio_lights")
    private const val payloadKey = "settings"
    private const val goveeSnapshotKey = "govee_snapshot"
    private const val goveeApiKeyKey = "govee_api_key"

    actual fun loadPayload(): String? = store.getString(payloadKey)

    actual fun savePayload(payload: String) {
        store.putString(payloadKey, payload)
    }

    actual fun loadGoveeApiKey(): String = store.getString(goveeApiKeyKey).orEmpty()

    actual fun saveGoveeApiKey(value: String) {
        store.putString(goveeApiKeyKey, value.takeIf { it.isNotBlank() })
    }

    actual fun loadGoveeSnapshot(): String? = store.getString(goveeSnapshotKey)

    actual fun saveGoveeSnapshot(payload: String?) {
        store.putString(goveeSnapshotKey, payload)
    }
}
