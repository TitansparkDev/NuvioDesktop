package com.nuvio.app.features.player

import androidx.compose.ui.unit.dp
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem

internal const val PlaybackProgressPersistIntervalMs = 60_000L
internal const val PlayerDoubleTapSeekResetDelayMs = 800L
internal const val PlayerLockedOverlayDurationMs = 2_000L
internal const val PlayerLeftGestureBoundary = 0.4f
internal const val PlayerRightGestureBoundary = 0.6f
internal const val PlayerVolumeStepFraction = 0.05f
internal const val PlayerSeekProgressSyncDebounceMs = 700L
// P2P initial-load progress (upstream 1b88c5ab): the bar is the max of an engine estimate
// (bytes downloaded, then bytes delivered to the player, each saturating around 5 MB) and the
// player's own buffered-ahead time, capped below 100% so it never claims done before mpv does.
internal const val P2pInitialByteProgressMidpoint = 5_242_880L
internal const val P2pInitialBufferTargetMs = 10_000L
internal const val P2pInitialNetworkStageWeight = 0.45f
internal const val P2pInitialDeliveryStageWeight = 0.30f
internal const val P2pInitialPlayerStageStart = 0.75f
internal const val P2pInitialLoadingMaximum = 0.95f

internal fun p2pInitialLoadingProgress(
    bufferedAheadMs: Long,
    downloadedBytes: Long,
    deliveredBytes: Long,
): Float {
    val networkProgress = saturatingProgress(downloadedBytes, P2pInitialByteProgressMidpoint) *
        P2pInitialNetworkStageWeight
    val deliveryProgress = saturatingProgress(deliveredBytes, P2pInitialByteProgressMidpoint) *
        P2pInitialDeliveryStageWeight
    val engineProgress = networkProgress + deliveryProgress
    val playerProgress = if (bufferedAheadMs > 0L) {
        P2pInitialPlayerStageStart +
            (bufferedAheadMs.toFloat() / P2pInitialBufferTargetMs.toFloat()).coerceIn(0f, 1f) *
            (P2pInitialLoadingMaximum - P2pInitialPlayerStageStart)
    } else {
        0f
    }
    return maxOf(engineProgress, playerProgress).coerceIn(0f, P2pInitialLoadingMaximum)
}

private fun saturatingProgress(value: Long, midpoint: Long): Float {
    val safeValue = value.coerceAtLeast(0L).toDouble()
    return (safeValue / (safeValue + midpoint.toDouble())).toFloat()
}
internal const val NEXT_EPISODE_HARD_TIMEOUT_MS = 120_000L

/**
 * User-configured jump distance for the seek buttons, the arrow-key shortcuts, and the double-tap
 * gesture, in milliseconds. Read from the settings snapshot the runtime already holds so every seek
 * entry point stays on one number.
 */
internal val PlayerScreenRuntime.seekStepMs: Long
    get() = playerSettingsUiState.seekStepSeconds.toLong() * 1_000L

internal val PlayerSliderOverlayGap = 12.dp
internal val PlayerTimeRowHeight = 36.dp
internal val PlayerActionRowHeight = 50.dp

internal fun sliderOverlayBottomPadding(metrics: PlayerLayoutMetrics) =
    metrics.sliderBottomOffset +
        metrics.sliderTouchHeight +
        PlayerTimeRowHeight +
        PlayerActionRowHeight +
        PlayerSliderOverlayGap

internal enum class PlayerSeekDirection {
    Backward,
    Forward,
}

internal data class PlayerAccumulatedSeekState(
    val direction: PlayerSeekDirection,
    val baselinePositionMs: Long,
    val amountMs: Long,
)

internal data class PendingPlayerP2pSwitch(
    val stream: StreamItem,
    val episode: MetaVideo?,
    val isAutoPlay: Boolean,
)
