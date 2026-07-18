package com.aynthor.taskswap.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThorKeyMapperTest {

    @Test
    fun back_alwaysMapsToBack() {
        assertEquals(ButtonGestures.Key.BACK, ThorKeyMapper.map(ThorKeyMapper.KEYCODE_BACK, 0))
        assertEquals(
            ButtonGestures.Key.BACK,
            ThorKeyMapper.map(ThorKeyMapper.KEYCODE_BACK, ThorKeyMapper.SOURCE_GAMEPAD)
        )
    }

    @Test
    fun f24_gpioKeys_isAyn_notHome() {
        // getevent: /dev/input/event0 gpio-keys KEY_F24 = chin AYN
        assertEquals(
            ButtonGestures.Key.AYN,
            ThorKeyMapper.map(ThorKeyMapper.KEYCODE_F24, 0x101, "gpio-keys")
        )
    }

    @Test
    fun f24_odinController_isHome() {
        assertEquals(
            ButtonGestures.Key.HOME,
            ThorKeyMapper.map(ThorKeyMapper.KEYCODE_F24, 0x501, "Odin Controller")
        )
    }

    @Test
    fun f24_withoutDeviceName_isAyn_notHome() {
        // Unknown device: do NOT treat as Home (would steal AYN)
        assertEquals(ButtonGestures.Key.AYN, ThorKeyMapper.map(ThorKeyMapper.KEYCODE_F24, 0))
    }

    @Test
    fun buttonMode_mapsToPhysicalHome() {
        assertEquals(ButtonGestures.Key.HOME, ThorKeyMapper.map(ThorKeyMapper.KEYCODE_BUTTON_MODE, 0))
    }

    @Test
    fun keycodeHome_gpioKeys_isAyn_fromDeviceLog() {
        assertEquals(
            ButtonGestures.Key.AYN,
            ThorKeyMapper.map(ThorKeyMapper.KEYCODE_HOME, 0x101, "gpio-keys")
        )
    }

    @Test
    fun keycodeHome_odinController_isHome_fromDeviceLog() {
        assertEquals(
            ButtonGestures.Key.HOME,
            ThorKeyMapper.map(ThorKeyMapper.KEYCODE_HOME, 0x501, "Odin Controller")
        )
    }

    @Test
    fun keycodeHome_gamepadSourceWithoutName_isAyn_notHome() {
        assertEquals(
            ButtonGestures.Key.AYN,
            ThorKeyMapper.map(ThorKeyMapper.KEYCODE_HOME, ThorKeyMapper.SOURCE_GAMEPAD)
        )
    }

    @Test
    fun unknownKey_returnsNull() {
        assertNull(ThorKeyMapper.map(0, 0))
        assertNull(ThorKeyMapper.map(26, 0))
    }
}
