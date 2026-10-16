package com.nuvio.app.features.player.desktop

import java.awt.Canvas
import java.awt.Color
import java.awt.Graphics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

internal class NativePlayerHost : Canvas() {
    var onPeerReady: (() -> Unit)? = null
    var onDisplayableChanged: ((Boolean) -> Unit)? = null
    var onFirstPaint: (() -> Unit)? = null
    var onFirstFullSizePaint: (() -> Unit)? = null
    private var firstPaintNotified = false
    private var firstFullSizePaintNotified = false

    init {
        background = Color.BLACK
        ignoreRepaint = false
        // On Linux/XWayland a heavyweight Canvas embedded in a Compose SwingPanel is not
        // guaranteed an expose-driven paint() when it is first laid out, so the first-full-size
        // paint signal (which unlocks the native attach) could never fire and playback would
        // silently never start. componentResized fires reliably on layout.
        if (DesktopHostOs.current == DesktopHostOs.LINUX) {
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    repaint()
                    notifyFirstPaints()
                }

                override fun componentShown(event: ComponentEvent) {
                    repaint()
                    notifyFirstPaints()
                }
            })
        }
    }

    private fun notifyFirstPaints() {
        if (!firstPaintNotified) {
            firstPaintNotified = true
            onFirstPaint?.invoke()
        }
        if (!firstFullSizePaintNotified && width > 1 && height > 1) {
            firstFullSizePaintNotified = true
            onFirstFullSizePaint?.invoke()
        }
    }

    override fun update(graphics: Graphics) {
        paint(graphics)
    }

    override fun paint(graphics: Graphics) {
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, width, height)
        notifyFirstPaints()
    }

    override fun addNotify() {
        super.addNotify()
        onDisplayableChanged?.invoke(true)
        repaint()
        onPeerReady?.invoke()
    }

    override fun removeNotify() {
        onDisplayableChanged?.invoke(false)
        firstPaintNotified = false
        firstFullSizePaintNotified = false
        onPeerReady = null
        onFirstPaint = null
        onFirstFullSizePaint = null
        super.removeNotify()
    }
}
