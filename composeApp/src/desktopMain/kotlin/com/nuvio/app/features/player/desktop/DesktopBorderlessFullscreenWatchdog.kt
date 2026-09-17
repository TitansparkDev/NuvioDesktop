package com.nuvio.app.features.player.desktop

import java.awt.Frame
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeListener
import javax.swing.Timer

/**
 * Keeps borderless fullscreen actually covering the monitor after something outside the app
 * rearranges the desktop.
 *
 * [applyNativeBorderlessFullscreen] is a one-shot style change plus a `SetWindowPos` to the
 * monitor rect; nothing re-asserts it afterwards. That holds for as long as the display topology
 * does, which on an ordinary monitor is forever — but not on an HTPC feeding a projector through
 * an AV receiver, where an external player (MPC-HC/madVR changing the fullscreen display mode, or
 * simply an HDMI re-handshake when the receiver re-negotiates on stop) makes Windows tear the
 * desktop down and put it back. Windows restores each top-level window to the rect it remembers
 * for the new arrangement, which for Nuvio is the *windowed* rect: the app is still flagged
 * fullscreen and still has no caption, but it no longer covers the screen. Reported by a user
 * whose desktop runs at 3440x1440 on a 4K projector — exactly the setup where stopping playback in
 * an external player changes the mode.
 *
 * The correction reuses [suspendNativeBorderlessFullscreen] with `suspended = false`: it re-applies
 * the borderless style and the current monitor's rect without touching the saved windowed rect that
 * exiting fullscreen has to restore, so re-asserting is idempotent and cannot strand the window.
 *
 * Checks are event-driven (display change, window resize/move, regaining activation as the external
 * player closes) with a settle delay, plus a slow poll for the changes that arrive without any AWT
 * event at all — a mode change while Nuvio is in the background produces none until it is touched.
 */
internal object DesktopBorderlessFullscreenWatchdog {

    // How long the desktop is given to settle before its rect is trusted. An HDMI re-handshake
    // reports its new layout in several steps; correcting into an intermediate one would only have
    // to be corrected again a moment later.
    private const val SettleDelayMs = 500

    // Backstop for changes that produce no AWT event while the app is unfocused. Slow enough to
    // cost nothing, fast enough that the window is never left windowed for long once it is looked
    // at.
    private const val PollIntervalMs = 2_000

    // Edges are compared in AWT user space, where a stale DPI scale can put the numbers a pixel or
    // two out without the window actually having lost fullscreen.
    private const val EdgeToleranceUserPx = 2

    // A window that will not stay at the monitor rect must not be fought over forever: a projector
    // mid-handshake can report a layout no window can satisfy. Give up after this many corrections
    // that did not stick and let F11 be the escape hatch.
    private const val MaxConsecutiveCorrections = 5

    fun install(window: Window, isFullscreenActive: () -> Boolean): () -> Unit {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return {}

        var consecutiveCorrections = 0
        var lastCorrectedRect: Rectangle? = null

        val check = {
            val expected = correctableFullscreenBounds(window)
            when {
                !isFullscreenActive() -> {
                    consecutiveCorrections = 0
                    lastCorrectedRect = null
                }
                expected == null -> Unit
                covers(window.bounds, expected) -> {
                    consecutiveCorrections = 0
                    lastCorrectedRect = null
                }
                // The previous correction produced this same wrong rect, repeatedly: the window is
                // not going to reach the monitor rect, so stop asking.
                consecutiveCorrections >= MaxConsecutiveCorrections -> Unit
                else -> {
                    val repeated = lastCorrectedRect == window.bounds
                    consecutiveCorrections = if (repeated) consecutiveCorrections + 1 else 1
                    lastCorrectedRect = Rectangle(window.bounds)
                    System.out.println(
                        "Info: (WindowGeometry) borderless fullscreen lost - window is " +
                            "${window.bounds.width}x${window.bounds.height}" +
                            "@${window.bounds.x},${window.bounds.y} but monitor is " +
                            "${expected.width}x${expected.height}@${expected.x},${expected.y}; " +
                            "re-applying (attempt $consecutiveCorrections)"
                    )
                    suspendNativeBorderlessFullscreen(window, false)
                    if (consecutiveCorrections >= MaxConsecutiveCorrections) {
                        System.out.println(
                            "Info: (WindowGeometry) borderless fullscreen would not stick after " +
                                "$MaxConsecutiveCorrections attempts; leaving the window alone"
                        )
                    }
                }
            }
        }

        // Swing timers fire on the EDT, which is where every native window call here has to happen
        // anyway and — unlike a Compose effect — is not inside a render pass. See the re-entrancy
        // note on DesktopBorderlessRootPaneFix for why that distinction matters.
        val settleTimer = Timer(SettleDelayMs) { check() }.apply { isRepeats = false }
        val pollTimer = Timer(PollIntervalMs) { check() }.apply { isRepeats = true }

        val scheduleCheck = { settleTimer.restart() }

        val componentListener = object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = scheduleCheck()
            override fun componentMoved(event: ComponentEvent) = scheduleCheck()
            override fun componentShown(event: ComponentEvent) = scheduleCheck()
        }
        // Regaining activation is the moment the external player closed and handed the screen back.
        val windowListener = object : WindowAdapter() {
            override fun windowActivated(event: WindowEvent) = scheduleCheck()
            override fun windowDeiconified(event: WindowEvent) = scheduleCheck()
        }
        // Logged unconditionally, not just when a correction follows: a report of "it drops out of
        // fullscreen when I stop the external player" is only actionable once the log says whether
        // the desktop was rebuilt underneath the app at that moment, and at what size.
        val displayChangeListener = PropertyChangeListener {
            System.out.println(
                "Info: (WindowGeometry) display change - monitor is now " +
                    "${window.graphicsConfiguration?.bounds}, window is ${window.bounds}"
            )
            scheduleCheck()
        }

        window.addComponentListener(componentListener)
        window.addWindowListener(windowListener)
        Toolkit.getDefaultToolkit().addPropertyChangeListener("displayChange", displayChangeListener)
        pollTimer.start()

        return {
            pollTimer.stop()
            settleTimer.stop()
            window.removeComponentListener(componentListener)
            window.removeWindowListener(windowListener)
            Toolkit.getDefaultToolkit()
                .removePropertyChangeListener("displayChange", displayChangeListener)
        }
    }

    /** The rect this window should occupy, or null while it must not be judged or corrected. */
    private fun correctableFullscreenBounds(window: Window): Rectangle? {
        if (!window.isShowing) return null
        // An iconified window reports an off-screen origin on Windows; nothing about its rect is
        // meaningful until it is restored, and restoring fires the listener above.
        val extendedState = (window as? Frame)?.extendedState ?: 0
        if (extendedState and Frame.ICONIFIED != 0) return null
        // Only correct the window the user is actually looking at. The re-apply passes HWND_TOP,
        // so doing this while an external player owns the foreground would yank Nuvio out in front
        // of the film that is playing. Waiting costs nothing: the player closing makes Nuvio the
        // active window again, and windowActivated schedules the check that fixes it.
        if (!window.isActive) return null
        return window.graphicsConfiguration?.bounds
    }

    private fun covers(bounds: Rectangle, monitor: Rectangle): Boolean =
        bounds.x <= monitor.x + EdgeToleranceUserPx &&
            bounds.y <= monitor.y + EdgeToleranceUserPx &&
            bounds.x + bounds.width >= monitor.x + monitor.width - EdgeToleranceUserPx &&
            bounds.y + bounds.height >= monitor.y + monitor.height - EdgeToleranceUserPx
}
