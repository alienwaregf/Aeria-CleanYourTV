package com.gathin.tvhome.guard

internal object GamepadDevices {
    private const val AZFP0_EVENTSERVER_NAME = "eventserver-Joystick"
    private const val AZFP0_EVENTSERVER_VENDOR_ID = 0xdead
    private const val AZFP0_EVENTSERVER_PRODUCT_ID = 0xbeef

    private const val AMLOGIC_KEYPAD_NAME = "aml_keypad"
    private const val AMLOGIC_KEYPAD_VENDOR_ID = 0x0001
    private const val AMLOGIC_KEYPAD_PRODUCT_ID = 0x0001

    private const val REDMI_PROJECTOR_REMOTE_NAME = "小米蓝牙语音遥控器"
    private const val XIAOMI_VENDOR_ID = 0x2717
    private const val REDMI_PROJECTOR_REMOTE_PRODUCT_ID = 0x32b8
    private const val HILISILICON_PROJECTOR_KEYBOARD_NAME = "Hi keyboard"
    private const val HILISILICON_PROJECTOR_KEYBOARD_VENDOR_ID = 0x0001
    private const val HILISILICON_PROJECTOR_KEYBOARD_PRODUCT_ID = 0x0001

    fun isPhysicalGamepad(
        name: String?,
        isVirtual: Boolean,
        supportsGamepad: Boolean,
        vendorId: Int,
        productId: Int,
        alphabetic: Boolean = false,
    ): Boolean {
        if (isVirtual || name.orEmpty().contains("virtual", ignoreCase = true) || !supportsGamepad) return false
        if (alphabetic) return false

        if (name == AZFP0_EVENTSERVER_NAME &&
            vendorId == AZFP0_EVENTSERVER_VENDOR_ID && productId == AZFP0_EVENTSERVER_PRODUCT_ID
        ) return false
        if (name == AMLOGIC_KEYPAD_NAME &&
            vendorId == AMLOGIC_KEYPAD_VENDOR_ID && productId == AMLOGIC_KEYPAD_PRODUCT_ID
        ) return false
        if (name == REDMI_PROJECTOR_REMOTE_NAME &&
            vendorId == XIAOMI_VENDOR_ID && productId == REDMI_PROJECTOR_REMOTE_PRODUCT_ID
        ) return false
        if (name == HILISILICON_PROJECTOR_KEYBOARD_NAME &&
            vendorId == HILISILICON_PROJECTOR_KEYBOARD_VENDOR_ID &&
            productId == HILISILICON_PROJECTOR_KEYBOARD_PRODUCT_ID
        ) return false

        return true
    }

    fun isExternalMouse(isExternal: Boolean, isVirtual: Boolean, supportsMouse: Boolean): Boolean =
        isExternal && !isVirtual && supportsMouse
}
