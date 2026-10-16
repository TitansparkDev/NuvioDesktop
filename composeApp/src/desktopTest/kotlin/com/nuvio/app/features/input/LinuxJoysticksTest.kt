package com.nuvio.app.features.input

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers how `/proc/bus/input/devices` becomes joydev's button/axis numbering. Getting this wrong
 * swaps buttons on a real pad, and there is no controller in CI to notice.
 */
class LinuxJoysticksTest {

    // Steam's virtual pad (what a Steam Deck exposes under Steam Input) and the Deck's motion
    // sensors, as the kernel prints them.
    private val procDevices = """
        I: Bus=0003 Vendor=28de Product=11ff Version=0001
        N: Name="Microsoft X-Box 360 pad 0"
        P: Phys=
        H: Handlers=event20 js0
        B: EV=20000b
        B: KEY=7cdb000000000000 0 0 0 0
        B: ABS=3003f

        I: Bus=0003 Vendor=28de Product=1205 Version=0111
        N: Name="Steam Deck Motion Sensors"
        H: Handlers=event21 js1
        B: EV=19
        B: ABS=3f
    """.trimIndent()

    @Test
    fun steamVirtualPadUsesXpadNumbering() {
        val pad = LinuxJoysticks.parseProcDevices(procDevices).getValue("js0")
        assertEquals("Microsoft X-Box 360 pad 0", pad.name)
        assertTrue(pad.isGamepad)
        // A, B, X, Y, LB, RB, Back, Start, Guide, LS, RS
        assertContentEquals(
            intArrayOf(0x130, 0x131, 0x133, 0x134, 0x136, 0x137, 0x13a, 0x13b, 0x13c, 0x13d, 0x13e),
            pad.buttonCodes,
        )
        // LX, LY, LT, RX, RY, RT, hat X, hat Y
        assertContentEquals(intArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x10, 0x11), pad.axisCodes)
    }

    @Test
    fun motionSensorsAreNotGamepads() {
        val sensors = LinuxJoysticks.parseProcDevices(procDevices).getValue("js1")
        assertFalse(sensors.isGamepad)
    }

    @Test
    fun triggerTravelMapsJoydevRangeToXInput() {
        assertEquals(0, LinuxJoysticks.triggerTravel(-32_767))
        assertEquals(127, LinuxJoysticks.triggerTravel(0))
        assertEquals(255, LinuxJoysticks.triggerTravel(32_767))
    }
}
