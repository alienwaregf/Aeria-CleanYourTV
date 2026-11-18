package com.gathin.tvhome.system

import android.content.ClipboardManager

object MissingClipboard {
    val standIn: ClipboardManager? by lazy {
        runCatching {
            val unsafe = Class.forName("sun.misc.Unsafe")
            val instance = unsafe.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            unsafe.getMethod("allocateInstance", Class::class.java).invoke(instance, ClipboardManager::class.java) as ClipboardManager
        }.getOrNull()
    }
}
