package com.nuvio.app.features.recap

import com.nuvio.app.core.storage.DesktopStorage

internal actual object RecapCacheStorage {
    private const val RECAPS_KEY = "recaps"
    private val store = DesktopStorage.store("nuvio_recap_cache")

    actual fun load(): String? = store.getString(RECAPS_KEY)

    actual fun save(json: String?) {
        store.putString(RECAPS_KEY, json)
    }
}
