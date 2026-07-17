package com.aynthor.taskswap.input

/**
 * Maps raw Android keyCode (+ optional device name) to logical Thor buttons.
 *
 * Both physical Home and AYN can arrive as KEYCODE_HOME on Thor.
 * Distinguish by device name / source — never treat every KEYCODE_HOME the same.
 */
object ThorKeyMapper {

    const val KEYCODE_BACK = 4
    const val KEYCODE_HOME = 3
    const val KEYCODE_F24 = 337
    const val KEYCODE_BUTTON_MODE = 110

    const val SOURCE_GAMEPAD = 0x00000401
    const val SOURCE_JOYSTICK = 0x01000010
    const val SOURCE_DPAD = 0x00000201

    const val GAMEPAD_SOURCES = SOURCE_GAMEPAD or SOURCE_JOYSTICK or SOURCE_DPAD

    @JvmOverloads
    fun map(keyCode: Int, source: Int = 0, deviceName: String? = null): ButtonGestures.Key? {
        return when {
            keyCode == KEYCODE_BACK -> ButtonGestures.Key.BACK
            keyCode == KEYCODE_F24 -> ButtonGestures.Key.HOME
            keyCode == KEYCODE_BUTTON_MODE -> ButtonGestures.Key.HOME
            keyCode == KEYCODE_HOME -> mapKeycodeHome(source, deviceName)
            else -> null
        }
    }

    /**
     * Chin AYN button is usually gpio / non-controller.
     * Gamepad Home is usually Odin/controller device, often with gamepad source.
     */
    private fun mapKeycodeHome(source: Int, deviceName: String?): ButtonGestures.Key {
        val n = deviceName?.lowercase().orEmpty()
        // Controller name wins over generic "ayn" substring in product strings.
        if (isControllerDeviceName(n)) return ButtonGestures.Key.HOME
        if (isAynDeviceName(n)) return ButtonGestures.Key.AYN
        return if ((source and GAMEPAD_SOURCES) != 0) {
            ButtonGestures.Key.HOME
        } else {
            ButtonGestures.Key.AYN
        }
    }

    private fun isAynDeviceName(n: String): Boolean {
        if (n.isEmpty()) return false
        return n.contains("gpio") ||
            n.contains("pmic") ||
            n.contains("hall") ||
            n.contains("qpnp") ||
            n.contains("pm8") ||
            n.contains("sec_key") ||
            n.contains("chin")
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
