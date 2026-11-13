package com.gathin.tvhome.system

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi

/**
 * How long Home's last frames took, for the diagnostics report: a report that says "it stutters"
 * then comes with numbers from the reporter's own TV. Android 7+ (FrameMetrics); the system hands
 * over each frame's times after it is drawn, on a thread of its own. Nothing runs while nothing is
 * drawn, so it costs nothing when Home is still.
 */
object FrameStats {
    private const val KEEP = 600
    private const val SKIP_START_MS = 5_000L
    private const val BUDGET_NS = 16_700_000L
    private val total = LongArray(KEEP)
    /** The GPU's part of the same frames (Android 12+): says whether a slow TV waits on its GPU. */
    private val gpu = LongArray(KEEP)
    private var count = 0L
    private var over = 0L
    private var thread: HandlerThread? = null

    fun attach(window: Window) {
        if (Build.VERSION.SDK_INT < 24) return
        listen(window)
    }

    @RequiresApi(24)
    private fun listen(window: Window) {
        val t = thread ?: HandlerThread("AerialFrames").also { it.start(); thread = it }
        val from = SystemClock.uptimeMillis() + SKIP_START_MS
        window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
            // Home's first seconds are its start (up to a second a frame on TCL MT5879), not the
            // TV's pace while it is used; the start has its own lines in DiagLog.
            if (SystemClock.uptimeMillis() < from || metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) {
                return@addOnFrameMetricsAvailableListener
            }
            val ns = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val gpuNs = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.GPU_DURATION) else 0L
            synchronized(this) {
                total[(count % KEEP).toInt()] = ns
                gpu[(count % KEEP).toInt()] = gpuNs
                count++
                if (ns > BUDGET_NS) over++
            }
        }, Handler(t.looper))
    }

    /** One line: how many frames, the median, 90th and 99th percentiles and the slowest of the last [KEEP]. */
    fun summary(): String {
        if (Build.VERSION.SDK_INT < 24) return "not measured before Android 7"
        val (n, all, slow, sorted, gpuSorted) = synchronized(this) {
            val n = minOf(count, KEEP.toLong()).toInt()
            Snapshot(n, count, over, total.copyOf(n).also { it.sort() }, gpu.copyOf(n).also { it.sort() })
        }
        if (n == 0) return "no frames yet"
        fun ms(a: LongArray, p: Double) = "%.1f".format(a[((n - 1) * p).toInt()] / 1e6)
        val gpuPart = if (Build.VERSION.SDK_INT >= 31) "; GPU median ${ms(gpuSorted, 0.5)} ms, p90 ${ms(gpuSorted, 0.9)} ms" else ""
        return "last $n frames: median ${ms(sorted, 0.5)} ms, p90 ${ms(sorted, 0.9)} ms, p99 ${ms(sorted, 0.99)} ms, slowest ${ms(sorted, 1.0)} ms$gpuPart; " +
            "over 16.7 ms: $slow of $all since 5 s after start"
    }

    private data class Snapshot(val n: Int, val all: Long, val slow: Long, val sorted: LongArray, val gpuSorted: LongArray)
}
