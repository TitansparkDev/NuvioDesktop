package com.nuvio.app.features.games

import androidx.compose.runtime.Composable

/**
 * The "Rows" block of the games settings page: the shelves the library shows, in order, with
 * add / rename / recolour / reorder / remove — the same controls the local library offers for its
 * catalogs. Lives behind an expect because the row store is the desktop-only game library.
 */
@Composable
internal expect fun GameRowsSettingsSection(isTablet: Boolean, title: String)
