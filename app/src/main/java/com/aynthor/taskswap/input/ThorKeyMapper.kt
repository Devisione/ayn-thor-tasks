package com.aynthor.taskswap.input

/**
 * Thor button map — confirmed on device 2026-07-18:
 *
 * | Button | KeyEvent | Device | scan |
 * |--------|----------|--------|------|
 * | Back | KEYCODE_BACK (4) | Odin Controller | 158 |
 * | Home | KEYCODE_HOME (3) | Odin Controller | 102 |
 * | AYN  | KEYCODE_HOME (3) | gpio-keys | 194 (linux KEY_F24) |
 *
 * Never map KEYCODE_HOME → Home without checking device name.
 * Never map all F24 → Home — gpio F24/HOME family is AYN.
 */
object ThorKeyMapper {

    const val KEYCODE_BACK = 4
    const val KEYCODE_HOME = 3
    const val KEYCODE_F24 = 337
    const val KEYCODE_BUTTON_MODE = 110

    /** Linux scan codes from Thor getevent / KeyEvent.scanCode. */
    const val SCAN_HOME = 102
    const val SCAN_AYN_F24 = 194
    const val SCAN_BACK = 158

    const val SOURCE_GAMEPAD = 0x00000401
    const val SOURCE_JOYSTICK = 0x01000010
    const val SOURCE_DPAD = 0x00000201

    const val GAMEPAD_SOURCES = SOURCE_GAMEPAD or SOURCE_JOYSTICK or SOURCE_DPAD

    @JvmOverloads
    fun map(keyCode: Int, source: Int = 0, deviceName: String? = null): ButtonGestures.Key? {
        return when {
            keyCode == KEYCODE_BACK -> ButtonGestures.Key.BACK
            keyCode == KEYCODE_BUTTON_MODE -> ButtonGestures.Key.HOME
            keyCode == KEYCODE_F24 -> mapByDevice(deviceName, controllerDefault = ButtonGestures.Key.HOME)
            keyCode == KEYCODE_HOME -> mapByDevice(deviceName, controllerDefault = ButtonGestures.Key.HOME)
            else -> null
        }
    }

    private fun mapByDevice(
        deviceName: String?,
        controllerDefault: ButtonGestures.Key
    ): ButtonGestures.Key {
        val n = deviceName?.lowercase().orEmpty()
        if (isControllerDeviceName(n)) return controllerDefault
        // gpio-keys (and any non-controller) → AYN
        return ButtonGestures.Key.AYN
    }

    private fun isControllerDeviceName(n: String): Boolean {
        if (n.isEmpty()) return false
        return n.contains("odin") ||
            n.contains("controller") ||
            n.contains("gamepad") ||
            n.contains("joypad") ||
            n.contains("retroid") ||
            n.contains("input-plumb")
    }
}
