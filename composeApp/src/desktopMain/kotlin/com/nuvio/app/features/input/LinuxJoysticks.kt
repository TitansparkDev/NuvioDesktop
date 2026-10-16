package com.nuvio.app.features.input

import co.touchlab.kermit.Logger
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.math.BigInteger

/**
 * Linux gamepad source for [GamepadInput]: reads the kernel joystick API (`/dev/input/js*`) and
 * hands back the same XInput-shaped slots the Windows bridge's `pollGamepads` fills, so everything
 * downstream (bindings, repeat, on-screen keyboard) is shared.
 *
 * On a Steam Deck in Game Mode — or any controller routed through Steam Input — the pad shows up as
 * Steam's virtual Xbox 360 controller, which is exactly the xpad layout mapped here. Plain xpad,
 * Xbox, DualShock/DualSense and 8BitDo pads work the same way.
 *
 * joydev numbers buttons and axes by the evdev codes a device has, so the mapping from the stable
 * evdev codes to joydev's indices is rebuilt per device from `/proc/bus/input/devices`. joydev also
 * normalises every axis to ±32767, which is what makes triggers and sticks comparable across pads
 * without the EVIOCGABS ioctl (unreachable from the JVM).
 *
 * One blocking reader thread per open device; [poll] only copies the latest state, so the shared
 * poll loop keeps its timing and never blocks on a read.
 */
internal object LinuxJoysticks {
    private val log = Logger.withTag("GamepadLinux")

    private const val SLOT_COUNT = 8
    private const val SLOT_STRIDE = 8

    // XInput wButtons bits, mirrored from GamepadInput.
    private const val BIT_DPAD_UP = 0x0001
    private const val BIT_DPAD_DOWN = 0x0002
    private const val BIT_DPAD_LEFT = 0x0004
    private const val BIT_DPAD_RIGHT = 0x0008
    private const val BIT_START = 0x0010
    private const val BIT_BACK = 0x0020
    private const val BIT_LEFT_THUMB = 0x0040
    private const val BIT_RIGHT_THUMB = 0x0080
    private const val BIT_LEFT_SHOULDER = 0x0100
    private const val BIT_RIGHT_SHOULDER = 0x0200
    private const val BIT_A = 0x1000
    private const val BIT_B = 0x2000
    private const val BIT_X = 0x4000
    private const val BIT_Y = 0x8000

    // evdev key codes (input-event-codes.h).
    private const val BTN_MISC = 0x100
    private const val BTN_JOYSTICK = 0x120
    private const val BTN_SOUTH = 0x130
    private const val BTN_EAST = 0x131
    private const val BTN_NORTH = 0x133
    private const val BTN_WEST = 0x134
    private const val BTN_TL = 0x136
    private const val BTN_TR = 0x137
    private const val BTN_TL2 = 0x138
    private const val BTN_TR2 = 0x139
    private const val BTN_SELECT = 0x13a
    private const val BTN_START = 0x13b
    private const val BTN_THUMBL = 0x13d
    private const val BTN_THUMBR = 0x13e
    private const val BTN_DPAD_UP = 0x220
    private const val BTN_DPAD_DOWN = 0x221
    private const val BTN_DPAD_LEFT = 0x222
    private const val BTN_DPAD_RIGHT = 0x223
    private const val BTN_TRIGGER_HAPPY1 = 0x2c0
    private const val KEY_MAX = 0x2ff

    // evdev axis codes.
    private const val ABS_X = 0x00
    private const val ABS_Y = 0x01
    private const val ABS_Z = 0x02
    private const val ABS_RX = 0x03
    private const val ABS_RY = 0x04
    private const val ABS_RZ = 0x05
    private const val ABS_GAS = 0x09
    private const val ABS_BRAKE = 0x0a
    private const val ABS_HAT0X = 0x10
    private const val ABS_HAT0Y = 0x11
    private const val ABS_HAT2X = 0x14
    private const val ABS_HAT2Y = 0x15
    private const val ABS_MAX = 0x3f

    private const val JS_EVENT_BUTTON = 0x01
    private const val JS_EVENT_AXIS = 0x02
    private const val JS_EVENT_INIT = 0x80

    /** Codes the default layout assumes when `/proc` cannot be read: joydev's xpad numbering. */
    private val xpadButtonCodes = intArrayOf(
        BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_TL, BTN_TR, BTN_SELECT, BTN_START, 0x13c,
        BTN_THUMBL, BTN_THUMBR,
    )
    private val xpadAxisCodes = intArrayOf(ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ, ABS_HAT0X, ABS_HAT0Y)

    private class Device(
        val path: String,
        val slot: Int,
        val buttonCodes: IntArray,
        val axisCodes: IntArray,
        val faceButtonsFollowSpec: Boolean,
    ) {
        val buttons = BooleanArray(buttonCodes.size)
        // Readers publish the device before joydev's INIT events arrive. Triggers must start
        // released, otherwise the first poll sees half-pressed triggers and fires shortcuts.
        val axes = IntArray(axisCodes.size) { index ->
            when (axisCodes[index]) {
                ABS_Z, ABS_RZ, ABS_GAS, ABS_BRAKE, ABS_HAT2X, ABS_HAT2Y -> -32_767
                else -> 0
            }
        }

        @Volatile
        var alive = true
        var stream: FileInputStream? = null
    }

    private val lock = Any()
    private val devices = arrayOfNulls<Device>(SLOT_COUNT)

    /**
     * Opens any newly attached joystick when [slotMask] asks for a full rescan, then copies the
     * current state of the requested slots into [out]. Returns the mask of connected slots.
     */
    fun poll(slotMask: Int, out: IntArray): Int {
        if (slotMask == ALL_SLOTS) rescan()
        var connected = 0
        synchronized(lock) {
            for (slot in 0 until SLOT_COUNT) {
                if (slotMask and (1 shl slot) == 0) continue
                val device = devices[slot] ?: continue
                if (!device.alive) {
                    devices[slot] = null
                    continue
                }
                connected = connected or (1 shl slot)
                fillSlot(device, out, slot * SLOT_STRIDE)
            }
        }
        return connected
    }

    /** Closes every device; reader threads end on the resulting read failure. */
    fun closeAll() {
        synchronized(lock) {
            devices.forEachIndexed { index, device ->
                device?.alive = false
                runCatching { device?.stream?.close() }
                devices[index] = null
            }
        }
    }

    private const val ALL_SLOTS = 0xFF

    private fun rescan() {
        val paths = File("/dev/input").listFiles { file -> file.name.matches(Regex("js\\d+")) }
            ?.map(File::getAbsolutePath)
            ?.sorted()
            .orEmpty()
        if (paths.isEmpty()) return
        val open = synchronized(lock) { devices.filterNotNull().map(Device::path).toSet() }
        val pending = paths.filter { it !in open }
        if (pending.isEmpty()) return
        val info = readProcDevices()
        pending.forEach { path ->
            val device = info[File(path).name]
            if (device != null && !device.isGamepad) {
                // A DualSense/Steam Deck motion sensor would read as a stick held off-centre.
                // Recheck next scan: hotplug can reuse this path for a real controller.
            } else {
                openDevice(path, device)
            }
        }
    }

    private fun openDevice(path: String, info: ProcDevice?) {
        val slot = synchronized(lock) { devices.indexOfFirst { it == null } }
        if (slot < 0) return
        val stream = try {
            FileInputStream(path)
        } catch (error: IOException) {
            // Usually permissions: the node exists but this user has no ACL on it.
            log.w { "cannot open $path: ${error.message}" }
            return
        }
        val name = info?.name.orEmpty()
        val device = Device(
            path = path,
            slot = slot,
            buttonCodes = info?.buttonCodes ?: xpadButtonCodes,
            axisCodes = info?.axisCodes ?: xpadAxisCodes,
            // Sony's drivers follow the kernel gamepad spec (BTN_NORTH = top button); xpad and
            // Steam's virtual pad put the Xbox X/Y buttons on BTN_X/BTN_Y as named.
            faceButtonsFollowSpec = SPEC_FACE_BUTTON_NAMES.any { name.contains(it, ignoreCase = true) },
        )
        device.stream = stream
        synchronized(lock) { devices[slot] = device }
        log.i { "gamepad connected slot=$slot path=$path name=\"$name\"" }
        Thread({ readLoop(device, stream) }, "nuvio-gamepad-${File(path).name}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    private val SPEC_FACE_BUTTON_NAMES = listOf("Sony", "DualSense", "DualShock", "Wireless Controller")

    private fun readLoop(device: Device, stream: FileInputStream) {
        val event = ByteArray(8)
        try {
            while (device.alive) {
                var read = 0
                while (read < event.size) {
                    val n = stream.read(event, read, event.size - read)
                    if (n < 0) throw IOException("end of stream")
                    read += n
                }
                // struct js_event { u32 time; s16 value; u8 type; u8 number; }, little-endian.
                val value = ((event[4].toInt() and 0xFF) or (event[5].toInt() shl 8)).toShort().toInt()
                val type = (event[6].toInt() and 0xFF) and JS_EVENT_INIT.inv()
                val number = event[7].toInt() and 0xFF
                synchronized(lock) {
                    when (type) {
                        JS_EVENT_BUTTON -> if (number < device.buttons.size) device.buttons[number] = value != 0
                        JS_EVENT_AXIS -> if (number < device.axes.size) device.axes[number] = value
                    }
                }
            }
        } catch (_: IOException) {
            // Unplugged, or closed by closeAll(); the next poll frees the slot.
        } finally {
            device.alive = false
            runCatching { stream.close() }
            log.i { "gamepad disconnected slot=${device.slot} path=${device.path}" }
        }
    }

    private fun fillSlot(device: Device, out: IntArray, base: Int) {
        var buttons = 0
        var leftTrigger = 0
        var rightTrigger = 0
        var lx = 0
        var ly = 0
        var rx = 0
        var ry = 0
        device.buttonCodes.forEachIndexed { index, code ->
            if (!device.buttons[index]) return@forEachIndexed
            buttons = buttons or when (code) {
                BTN_SOUTH -> BIT_A
                BTN_EAST -> BIT_B
                BTN_NORTH -> if (device.faceButtonsFollowSpec) BIT_Y else BIT_X
                BTN_WEST -> if (device.faceButtonsFollowSpec) BIT_X else BIT_Y
                BTN_TL -> BIT_LEFT_SHOULDER
                BTN_TR -> BIT_RIGHT_SHOULDER
                BTN_SELECT -> BIT_BACK
                BTN_START -> BIT_START
                BTN_THUMBL -> BIT_LEFT_THUMB
                BTN_THUMBR -> BIT_RIGHT_THUMB
                BTN_DPAD_UP, BTN_TRIGGER_HAPPY1 + 2 -> BIT_DPAD_UP
                BTN_DPAD_DOWN, BTN_TRIGGER_HAPPY1 + 3 -> BIT_DPAD_DOWN
                BTN_DPAD_LEFT, BTN_TRIGGER_HAPPY1 -> BIT_DPAD_LEFT
                BTN_DPAD_RIGHT, BTN_TRIGGER_HAPPY1 + 1 -> BIT_DPAD_RIGHT
                else -> 0
            }
            if (code == BTN_TL2) leftTrigger = 255
            if (code == BTN_TR2) rightTrigger = 255
        }
        device.axisCodes.forEachIndexed { index, code ->
            val value = device.axes[index]
            when (code) {
                ABS_X -> lx = value
                // joydev's Y grows downward; XInput's grows upward.
                ABS_Y -> ly = -value
                ABS_RX -> rx = value
                ABS_RY -> ry = -value
                ABS_Z, ABS_BRAKE, ABS_HAT2Y -> leftTrigger = maxOf(leftTrigger, triggerTravel(value))
                ABS_RZ, ABS_GAS, ABS_HAT2X -> rightTrigger = maxOf(rightTrigger, triggerTravel(value))
                ABS_HAT0X -> buttons = buttons or when {
                    value < -16_384 -> BIT_DPAD_LEFT
                    value > 16_384 -> BIT_DPAD_RIGHT
                    else -> 0
                }
                ABS_HAT0Y -> buttons = buttons or when {
                    value < -16_384 -> BIT_DPAD_UP
                    value > 16_384 -> BIT_DPAD_DOWN
                    else -> 0
                }
            }
        }
        out[base] = buttons
        out[base + 1] = leftTrigger
        out[base + 2] = rightTrigger
        out[base + 3] = lx.coerceIn(-32_767, 32_767)
        out[base + 4] = ly.coerceIn(-32_767, 32_767)
        out[base + 5] = rx.coerceIn(-32_767, 32_767)
        out[base + 6] = ry.coerceIn(-32_767, 32_767)
        out[base + 7] = 0
    }

    /** joydev rescales a 0..max trigger to -32767..32767; XInput reports 0..255. */
    internal fun triggerTravel(value: Int): Int = ((value + 32_767L) * 255L / 65_534L).toInt().coerceIn(0, 255)

    internal class ProcDevice(val name: String, val buttonCodes: IntArray, val axisCodes: IntArray) {
        /** Motion-sensor and other non-pad nodes also get a jsN handler but have no face buttons. */
        val isGamepad: Boolean get() = BTN_SOUTH in buttonCodes
    }

    /** Parses `/proc/bus/input/devices` into joydev's button/axis numbering, keyed by `jsN`. */
    private fun readProcDevices(): Map<String, ProcDevice> = runCatching {
        parseProcDevices(File("/proc/bus/input/devices").readText())
    }.onFailure { log.w { "cannot read /proc/bus/input/devices: ${it.message}" } }
        .getOrDefault(emptyMap())

    internal fun parseProcDevices(text: String): Map<String, ProcDevice> {
        val result = HashMap<String, ProcDevice>()
        text.split(Regex("\\n\\s*\\n")).forEach { block ->
            var name = ""
            var js: String? = null
            var keyBits = BigInteger.ZERO
            var absBits = BigInteger.ZERO
            block.lineSequence().forEach { line ->
                when {
                    line.startsWith("N: Name=") -> name = line.removePrefix("N: Name=").trim('"')
                    line.startsWith("H: Handlers=") -> js = Regex("\\bjs\\d+\\b").find(line)?.value
                    line.startsWith("B: KEY=") -> keyBits = parseBitmap(line.removePrefix("B: KEY="))
                    line.startsWith("B: ABS=") -> absBits = parseBitmap(line.removePrefix("B: ABS="))
                }
            }
            val handler = js ?: return@forEach
            result[handler] = ProcDevice(name, joydevButtonCodes(keyBits), joydevAxisCodes(absBits))
        }
        return result
    }

    /** Space-separated hex words, most significant first, each one `unsigned long` wide (64-bit). */
    private fun parseBitmap(words: String): BigInteger {
        var bits = BigInteger.ZERO
        words.trim().split(Regex("\\s+")).filter(String::isNotEmpty).forEach { word ->
            bits = bits.shiftLeft(64).or(BigInteger(word, 16))
        }
        return bits
    }

    /** joydev's order: codes from BTN_JOYSTICK to KEY_MAX first, then BTN_MISC..BTN_JOYSTICK-1. */
    private fun joydevButtonCodes(keyBits: BigInteger): IntArray = buildList {
        for (code in BTN_JOYSTICK..KEY_MAX) if (keyBits.testBit(code)) add(code)
        for (code in BTN_MISC until BTN_JOYSTICK) if (keyBits.testBit(code)) add(code)
    }.toIntArray()

    private fun joydevAxisCodes(absBits: BigInteger): IntArray = buildList {
        for (code in 0..ABS_MAX) if (absBits.testBit(code)) add(code)
    }.toIntArray()
}
