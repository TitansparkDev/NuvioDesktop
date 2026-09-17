package com.nuvio.app.core.sync

import com.nuvio.app.core.storage.DesktopStorage

internal actual object SynchronizationPreferencesStorage {
    private const val PayloadKey = "payload"
    private const val PendingPushMarkerKey = "pending_push"
    private val store = DesktopStorage.store("nuvio_synchronization_preferences")

    actual fun loadPayload(): String? = store.getString(PayloadKey)

    actual fun savePayload(payload: String) {
        store.putString(PayloadKey, payload)
    }

    actual fun loadPendingPushMarker(): String? = store.getString(PendingPushMarkerKey)

    actual fun savePendingPushMarker(marker: String?) {
        store.putString(PendingPushMarkerKey, marker)
    }
}
