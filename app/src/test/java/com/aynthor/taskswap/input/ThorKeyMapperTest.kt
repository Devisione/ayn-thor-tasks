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
    fun f24_alwaysMapsToPhysicalHome() {
        assertEquals(ButtonGestures.Key.HOME, ThorKeyMapper.map(ThorKeyMapper.KEYCODE_F24, 0))
        assertEquals(
            ButtonGestures.Key.HOME,
            ThorKeyMapper.map(ThorKeyMapper.KEYCODE_F24, ThorKeyMapper.SOURCE_GAMEPAD)
        )
    }

    @Test
    fun buttonMode_mapsToPhysicalHome() {
        assertEquals(ButtonGestures.Key.HOME, ThorKeyMapper.map(ThorKeyMapper.KEYCODE_BUTTON_MODE, 0))
    }

    @Test
    fun keycodeHome_gpioDevice_isAyn() {
        assertEquals(
            ButtonGestures.Key.AYN,
            ThorKeyMapper.map(ThorKeyMapper.KEYCODE_HOME, 0, "gpio-keys")
        )
        assertEquals(
            ButtonGestures.Key.AYN,
            ThorKeyMapper.map(ThorKeyMapper.KEYCODE_HOME, ThorKeyMapper.SOURCE_GAMEPAD, "gpio-keys")
        )
    }

    @Test
    fun keycodeHome_controllerDevice_isHome() {
        assertEquals(
            ButtonGestures.Key.HOME,
            ThorKeyMapper.map(ThorKeyMapper.KEYCODE_HOME, ThorKeyMapper.SOURCE_GAMEPAD, "Odin Controller")
        )
        assertEquals(
            ButtonGestures.Key.HOME,
            ThorKeyMapper.map(ThorKeyMapper.KEYCODE_HOME, 0, "AYN Gamepad")
        )
    }

    @Test
    fun keycodeHome_gamepadSourceWithoutName_isHome() {
        assertEquals(
            ButtonGestures.Key.HOME,
            ThorKeyMapper.map(ThorKeyMapper.KEYCODE_HOME, ThorKeyMapper.SOURCE_GAMEPAD)
        )
    }

    @Test
    fun keycodeHome_plainSource_isAyn() {
        assertEquals(ButtonGestures.Key.AYN, ThorKeyMapper.map(ThorKeyMapper.KEYCODE_HOME, 0))
        assertEquals(
            ButtonGestures.Key.AYN,
            ThorKeyMapper.map(ThorKeyMapper.KEYCODE_HOME, 0x00001000)
        )
    }

    @Test
    fun unknownKey_returnsNull() {
        assertNull(ThorKeyMapper.map(0, 0))
        assertNull(ThorKeyMapper.map(26, 0))
    }
}
