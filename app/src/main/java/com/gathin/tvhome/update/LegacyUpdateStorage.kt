package com.gathin.tvhome.update

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.core.app.ActivityCompat


internal object LegacyUpdateStorage {
    private const val REQUEST_STORAGE = 2101
    private val permissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    fun needsPermission(context: Context): Boolean = false

    fun requestPermission(context: Context) {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) {
                ActivityCompat.requestPermissions(current, permissions, REQUEST_STORAGE)
                return
            }
            val base = current.baseContext
            if (base === current) break
            current = base
        }
        Log.w("AerialUpdates", "no activity to request legacy update storage permission")
    }
}
