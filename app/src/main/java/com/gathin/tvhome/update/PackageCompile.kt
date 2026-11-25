package com.gathin.tvhome.update

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver

internal object PackageCompile {
    private const val SHELL_COMMAND = ('_'.code shl 24) or ('C'.code shl 16) or ('M'.code shl 8) or 'D'.code
    private const val COMPILE_TIMEOUT_MS = 120_000L

    fun compile(context: Context, filter: String): String {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "package") as IBinder
        val input = ParcelFileDescriptor.createPipe()
        val out = ParcelFileDescriptor.createPipe()
        val err = ParcelFileDescriptor.createPipe()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        var result: String? = null
        var failure: Throwable? = null
        val worker = Thread({
            try {
                input[1].close()
                data.writeFileDescriptor(input[0].fileDescriptor)
                data.writeFileDescriptor(out[1].fileDescriptor)
                data.writeFileDescriptor(err[1].fileDescriptor)
                data.writeStringArray(arrayOf("compile", "-m", filter, "-f", context.packageName))
                if (Build.VERSION.SDK_INT >= 26) data.writeStrongBinder(null)
                ResultReceiver(null).writeToParcel(data, 0)
                binder.transact(SHELL_COMMAND, data, reply, 0)
                reply.readException()
                result = ParcelFileDescriptor.AutoCloseInputStream(out[0]).bufferedReader().use { it.readText() } +
                    ParcelFileDescriptor.AutoCloseInputStream(err[0]).bufferedReader().use { it.readText() }
            } catch (t: Throwable) {
                failure = t
            } finally {
                data.recycle()
                reply.recycle()
                runCatching { input[0].close() }
                runCatching { out[1].close() }
                runCatching { err[1].close() }
            }
        }, "package-compile")
        worker.start()
        worker.join(COMPILE_TIMEOUT_MS)
        if (worker.isAlive) {
            runCatching { out[0].close() }
            runCatching { err[0].close() }
            return "compile timed out"
        }
        failure?.let { throw it }
        return result?.trim() ?: ""
    }
}
