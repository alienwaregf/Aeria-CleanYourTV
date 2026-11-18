package com.gathin.tvhome.apps

import android.content.Context

internal object Dimens {

    const val SHELF_MARK_Y = 190.0f

    val SHELF_LAYOUTS = arrayOf(
        floatArrayOf(391.0f, 220.0f, 24.0f, 205.0f),
        floatArrayOf(261.0f, 147.0f, 20.0f, 298.5f),
        floatArrayOf(176.0f, 99.0f, 16.0f, 322.5f),
        floatArrayOf(104.0f, 156.0f, 16.0f, 294.0f),
    )

    fun px(context: Context, dp: Float): Int =
        Math.round(dp * context.resources.displayMetrics.density)
}
