package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio

/**
 * Opens the platform's colour dialog seeded with [initialHex] and returns the chosen colour as
 * `#RRGGBB`, or null when the dialog was cancelled. Blocks until the dialog closes.
 */
internal expect fun pickHexColor(initialHex: String, title: String): String?

/**
 * A circular swatch that sits inside a hex text field and opens [pickHexColor]. It is painted with
 * whatever [currentHex] parses to (an empty ring while the draft is not a colour yet), so the field
 * doubles as a live preview of what is being typed. Sized to match [com.nuvio.app.core.ui.NuvioFieldIconButton].
 *
 * Accepts `#RRGGBB` and `#AARRGGBB`; the picker itself only deals in RGB, so callers that carry an
 * alpha need to splice it back onto the result themselves.
 */
@Composable
internal fun HexColorPickerSwatch(
    currentHex: String,
    dialogTitle: String,
    onPicked: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val parsed = currentHex.hexColorOrNull()
    Box(
        modifier = Modifier
            .size(SwatchButtonSize)
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(if (hovered) tokens.colors.overlayHover else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = { pickHexColor(currentHex, dialogTitle)?.let(onPicked) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(SwatchSize)
                .clip(CircleShape)
                .background(parsed ?: Color.Transparent)
                .border(
                    width = 1.dp,
                    color = if (hovered) tokens.colors.textPrimary else tokens.colors.borderStrong,
                    shape = CircleShape,
                ),
        )
    }
}

private fun String.hexColorOrNull(): Color? {
    val cleaned = trim().removePrefix("#")
    if (cleaned.length != 6 && cleaned.length != 8) return null
    if (!cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val argb = if (cleaned.length == 6) "FF$cleaned" else cleaned
    return argb.toLongOrNull(16)?.let { Color(it) }
}

private val SwatchButtonSize = 32.dp
private val SwatchSize = 18.dp
