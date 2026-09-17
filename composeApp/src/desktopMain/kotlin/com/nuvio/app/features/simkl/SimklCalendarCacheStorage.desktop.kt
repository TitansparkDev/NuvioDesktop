package com.nuvio.app.features.simkl

import com.nuvio.app.core.storage.DesktopStorage

internal actual object SimklCalendarCacheStorage {
    private const val ENTRY_KEY = "calendar"
    private val store = DesktopStorage.store("nuvio_simkl_calendar_cache")

    actual fun load(): String? = store.getString(ENTRY_KEY)

    actual fun save(json: String?) {
        store.putString(ENTRY_KEY, json)
    }
}
