package com.gathin.tvhome.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderNode
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.gathin.tvhome.system.DiagLog
import kotlin.math.roundToInt

object UhdCanvas {
    @Volatile private var known: Float? = null

    var why = "not asked"; private set

    fun scale(context: Context): Float = known ?: compute(context).also { known = it }

    fun choose(context: Context, on: Boolean?) {
        prefs(context).edit().apply { if (on == null) remove(FORCED) else putBoolean(FORCED, on) }.remove(SLOW).apply()
    }

    private fun compute(context: Context): Float {
        val p = prefs(context)
        val forced = if (p.contains(FORCED)) p.getBoolean(FORCED, false) else null
        fun no(reason: String) = 1f.also { why = reason }
        if (Build.VERSION.SDK_INT < 29) return no("before Android 10")
        if (forced == false) return no("forced off")
        if (forced == null && p.getBoolean(SLOW, false)) return no("off: ${p.getString(SLOW_WHY, "too slow")}")
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return no("no display")
        val ui = context.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
        val mode = maxOf(display.mode.physicalWidth, display.mode.physicalHeight)
        if (forced == null && !chipHas4kUi()) return no("auto: no 4K UI plane (mode $mode for a $ui UI)")
        val panel = maxOf(mode, tclPanel())
        if (forced == null) return no("auto: off until a 4K UI plane is seen to show it ($panel for a $ui UI)")
        if (ui <= 0 || panel < ui * 2) return no("mode $mode for a $ui UI")
        why = "${if (forced == true) "forced" else "auto"}: $panel for a $ui UI"
        return 2f
    }

    private fun tooSlow(context: Context, reason: String) {
        DiagLog.i("uhd canvas: too slow ($reason), the next start draws at the UI's size")
        prefs(context).edit().putBoolean(SLOW, true).putString(SLOW_WHY, reason).apply()
    }

    private fun forced(context: Context) = prefs(context).contains(FORCED)

    private fun prefs(context: Context) = context.getSharedPreferences("uhd_canvas", Context.MODE_PRIVATE)
    private const val FORCED = "forced"
    private const val SLOW = "slow"
    private const val SLOW_WHY = "slow_why"

    private fun chipHas4kUi(): Boolean = systemProperty("ro.feature.graphics_4kui") == "true"

    private fun tclPanel(): Int = systemProperty("ro.odm.tcl.panel_resolution")?.substringBefore('x')?.toIntOrNull() ?: 0

    private fun systemProperty(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()

    @RequiresApi(29)
    fun host(context: Context, content: View, scale: Float): View = Host(context, content, scale)

    @RequiresApi(29)
    internal class Host(context: Context, content: View, private val scale: Float) : FrameLayout(context) {
        val films = FrameLayout(context)
        private val surface = SurfaceView(context)
        private val stage = Stage(context)

        init {
            addView(films, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            surface.setZOrderMediaOverlay(true)
            surface.holder.setFormat(PixelFormat.TRANSLUCENT)
            surface.holder.addCallback(stage)
            addView(surface, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            stage.addView(content, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(stage, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0) post { surface.holder.setFixedSize((w * scale).roundToInt(), (h * scale).roundToInt()) }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            stage.release()
        }
    }

    @RequiresApi(29)
    private class Stage(context: Context) : FrameLayout(context), SurfaceHolder.Callback {
        private val root = RenderNode("AerialUhd")
        private val renderer = HardwareRenderer().apply {
            setContentRoot(root)
            isOpaque = false
            setLightSourceAlpha(0.039f, 0.19f)
        }
        private var ready = false
        private var drawing = false
        private var frames = 0
        private val pace = Pace(context)

        override fun surfaceCreated(holder: SurfaceHolder) = renderer.setSurface(holder.surface)

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            ready = width > 0 && w > width
            DiagLog.i("uhd canvas: surface ${w}x$h for a ${width}x$height window, ready $ready")
            if (!ready) return
            root.setPosition(0, 0, w, h)
            val px = resources.displayMetrics.density * w / width
            renderer.setLightSourceGeometry(w / 2f, 0f, 600 * px, 800 * px)
            invalidate()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            ready = false
            renderer.setSurface(null)
            invalidate()
        }

        fun release() {
            ready = false
            renderer.destroy()
        }

        override fun onDescendantInvalidated(child: View, target: View) {
            super.onDescendantInvalidated(child, target)
            if (ready && !drawing) invalidate()
        }

        override fun dispatchDraw(canvas: Canvas) {
            if (!ready || !canvas.isHardwareAccelerated) {
                super.dispatchDraw(canvas)
                return
            }
            drawing = true
            try {
                val rc = root.beginRecording()
                rc.scale(root.width.toFloat() / width, root.height.toFloat() / height)
                super.dispatchDraw(rc)
                root.endRecording()
            } finally {
                drawing = false
            }
            val before = System.nanoTime()
            renderer.createRenderRequest().setVsyncTime(before).syncAndDraw()
            pace.frame(before, System.nanoTime())
            if (++frames == 1) DiagLog.i("uhd canvas: first frame at ${root.width}x${root.height}")
        }
    }

    @RequiresApi(29)
    private class Pace(private val context: Context) {
        private val period = 1e9 / (context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate?.takeIf { it > 0f } ?: 60f)
        private val from = System.nanoTime() + SKIP_START_NS
        private val on = !forced(context)
        private var last = 0L
        private var moving = 0
        private var late = 0
        private var done = false

        fun frame(start: Long, end: Long) {
            val gap = start - last
            last = start
            if (!on || done || start < from || gap > STILL_NS) return
            moving++
            if (gap > period * 1.5 && end - start > period * 0.25) late++
            if (moving < WINDOW) return
            val reason = "$late of $moving moving frames late at ${"%.0f".format(1e9 / period)} Hz"
            if (late * 3 >= moving) {
                done = true
                tooSlow(context, reason)
            } else {
                DiagLog.i("uhd canvas: keeps up, $reason")
                moving = 0
                late = 0
            }
        }
    }

    private const val WINDOW = 120
    private const val SKIP_START_NS = 10_000_000_000L
    private const val STILL_NS = 100_000_000L
}

class FilmSlot(context: Context) : FrameLayout(context) {
    val surface = SurfaceView(context)
    private var host: UhdCanvas.Host? = null
    private val at = IntArray(2)
    private val follow = ViewTreeObserver.OnPreDrawListener { place(); true }
    private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    init {
        addView(surface, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT < 29) return
        var p = parent
        while (p != null && p !is UhdCanvas.Host) p = p.parent
        val h = p as? UhdCanvas.Host ?: return
        host = h
        removeView(surface)
        h.films.addView(surface, LayoutParams(0, 0))
        setWillNotDraw(false)
        viewTreeObserver.addOnPreDrawListener(follow)
    }

    @SuppressLint("NewApi")
    override fun onDetachedFromWindow() {
        host?.let { h ->
            viewTreeObserver.removeOnPreDrawListener(follow)
            h.films.removeView(surface)
            addView(surface, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            setWillNotDraw(true)
            host = null
        }
        super.onDetachedFromWindow()
    }

    private fun place() {
        getLocationInWindow(at)
        val lp = surface.layoutParams as LayoutParams
        if (lp.width == width && lp.height == height && lp.leftMargin == at[0] && lp.topMargin == at[1]) return
        lp.width = width
        lp.height = height
        lp.leftMargin = at[0]
        lp.topMargin = at[1]
        surface.layoutParams = lp
    }

    override fun onDraw(canvas: Canvas) {
        if (host != null) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), clear)
    }
}
