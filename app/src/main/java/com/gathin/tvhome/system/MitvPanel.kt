package com.gathin.tvhome.system

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.Log

object MitvPanel {
    private const val TAG = "AerialMitvPanel"

    const val SETTING = "backLightForNormal"

    private const val ITV_SERVICE = "mitv.internal.ITvService"
    private const val ITV_SETTING_MANAGER = "mitv.impl.tv.ITvSettingManager"
    private const val ACCESSORY_NAME = "TvSettingService"
    private val TX_GET_ACCESSORY_SERVICE = intArrayOf(80, 81)
    private const val TX_GET_BACKLIGHT_VALUE = 43
    private const val TX_SET_BACKLIGHT_VALUE = 42

    private const val PROBE_STEP = 5

    private const val PROBE_TOLERANCE = 2


    private var verified: Boolean? = null

    fun isAvailable(context: Context): Boolean =
        Settings.System.getString(context.contentResolver, SETTING) != null

    fun usable(context: Context): Boolean {
        verified?.let { return it }
        if (isBox()) return false.also { verified = it }
        if (!probing.compareAndSet(false, true)) return false
        try {
            return probe(context)
        } finally {
            probing.set(false)
        }
    }

    private val probing = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun isBox(): Boolean = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "ro.build.characteristics") as String
    }.getOrNull().orEmpty().let { "stb" in it || "mbx" in it }

    private fun probe(context: Context): Boolean {
        val ok = run {
            if (DeviceProfile.current(context).ignoresPictureBacklight) return@run false
            if (!isAvailable(context)) return@run false
            val before = readSettled(context) ?: return@run false
            if (before !in 1..100) return@run false
            val step = if (before >= 50) -PROBE_STEP else PROBE_STEP
            setDelta(context, step)
            val moved = readSettled(context)
            setDelta(context, -step)
            val restored = readSettled(context)
            val ok = moved != null && restored != null &&
                kotlin.math.abs(moved - (before + step)) <= PROBE_TOLERANCE &&
                kotlin.math.abs(restored - before) <= PROBE_TOLERANCE
            if (!ok) Log.w(TAG, "backlight transactions unverified ($before -> $moved -> $restored)")
            ok
        }
        verified = ok
        return ok
    }

    fun level(context: Context): Int? = readBacklight(context) ?: settingLevel(context)

    private fun settingLevel(context: Context): Int? =
        runCatching { Settings.System.getInt(context.contentResolver, SETTING, 50) }.getOrNull()

    fun setBacklight(context: Context, from: Int, to: Int) {
        val value = to.coerceIn(1, 100)
        val current = from.coerceIn(1, 100)
        val delta = value - current
        if (delta != 0) setDelta(context, delta)
        runCatching { Settings.System.putInt(context.contentResolver, SETTING, value) }
            .onFailure { Log.d(TAG, "setting write refused: $it") }
    }

    fun readBacklight(context: Context): Int? {
        val settingService = settingService() ?: return null
        return runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ITV_SETTING_MANAGER)
                settingService.transact(TX_GET_BACKLIGHT_VALUE, data, reply, 0)
                reply.readException()
                reply.readInt()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }.onFailure { Log.w(TAG, "getBacklightStatus failed: $it") }.getOrNull()
    }

    private fun readSettled(context: Context): Int? {
        var last: Int? = null
        repeat(3) { attempt ->
            val value = readBacklight(context) ?: return last
            if (value == last) return value
            last = value
            if (attempt < 2) runCatching { Thread.sleep(30) }
        }
        return last
    }

    private fun setDelta(context: Context, delta: Int) {
        val settingService = settingService()
        if (settingService == null) {
            Log.w(TAG, "TvService binder missing")
            return
        }
        runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ITV_SETTING_MANAGER)
                data.writeInt(delta)
                settingService.transact(TX_SET_BACKLIGHT_VALUE, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
            Log.d(TAG, "setBacklightValue $delta")
        }.onFailure { Log.w(TAG, "setBacklightValue failed: $it") }
    }

    private fun settingService(): IBinder? {
        val tvService = runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            getService.invoke(null, "TvService") as? IBinder
        }.onFailure { Log.w(TAG, "TvService lookup failed: $it") }.getOrNull()
        if (tvService == null) {
            Log.w(TAG, "TvService binder missing")
            return null
        }
        accessorySlot?.let { return accessory(tvService, it) }
        for (slot in TX_GET_ACCESSORY_SERVICE) {
            val binder = accessory(tvService, slot) ?: continue
            val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
            if (descriptor == ITV_SETTING_MANAGER) {
                accessorySlot = slot
                return binder
            }
            Log.w(TAG, "getAccessoryService slot $slot returned $descriptor")
        }
        return null
    }

    @Volatile private var accessorySlot: Int? = null

    private fun accessory(tvService: IBinder, slot: Int): IBinder? = runCatching {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ITV_SERVICE)
            data.writeString(ACCESSORY_NAME)
            tvService.transact(slot, data, reply, 0)
            reply.readException()
            reply.readStrongBinder()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }.onFailure { Log.w(TAG, "accessory lookup ($slot) failed: $it") }.getOrNull()
}
