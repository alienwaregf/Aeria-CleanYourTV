package com.gathin.tvhome.guard

internal object GamepadDevices {
    private const val AZFP0_EVENTSERVER_NAME = "eventserver-Joystick"
    private const val AZFP0_EVENTSERVER_VENDOR_ID = 0xdead
    private const val AZFP0_EVENTSERVER_PRODUCT_ID = 0xbeef

    private const val BUILT_IN_VENDOR_ID = 0x0001
    private const val BUILT_IN_PRODUCT_ID = 0x0001

    private const val REDMI_PROJECTOR_REMOTE_NAME = "小米蓝牙语音遥控器"
    private const val XIAOMI_VENDOR_ID = 0x2717
    private const val REDMI_PROJECTOR_REMOTE_PRODUCT_ID = 0x32b8

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
        if (vendorId == BUILT_IN_VENDOR_ID && productId == BUILT_IN_PRODUCT_ID) return false
        if (name == REDMI_PROJECTOR_REMOTE_NAME &&
            vendorId == XIAOMI_VENDOR_ID && productId == REDMI_PROJECTOR_REMOTE_PRODUCT_ID
        ) return false

        return true
    }

    fun isExternalMouse(isExternal: Boolean, isVirtual: Boolean, supportsMouse: Boolean): Boolean =
        isExternal && !isVirtual && supportsMouse
}
