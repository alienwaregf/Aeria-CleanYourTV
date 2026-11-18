package com.gathin.tvhome.apps

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Property
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import java.util.WeakHashMap
import kotlin.math.abs

object Motion {


    const val APP_GROW = "app.grow"
    const val APP_LAND = "app.land"
    const val APP_OPEN = "app.open"
    const val APP_RECEDE = "app.recede"
    const val APP_RETURN = "app.return"
    const val APP_SETTLE = "app.settle"
    const val BACKDROP_DARK = "backdrop.dark"
    const val BACKDROP_DIP = "backdrop.dip"
    const val BACKDROP_LAND = "backdrop.land"
    const val BACKDROP_LIVE = "backdrop.live"
    const val BACKDROP_RISE = "backdrop.rise"
    const val BACKDROP_SHARPEN = "backdrop.sharpen"
    const val CARD_SOFTEN = "card.soften"
    const val CLOCK_CARRY = "clock.carry"
    const val CLOCK_RETURN = "clock.return"
    const val CLOCK_REVEAL = "clock.reveal"
    const val CONTROLS_SHRINK = "controls.shrink"
    const val DIALOG_FOCUS = "dialog.focus"
    const val DIALOG_GROW = "dialog.grow"
    const val DIALOG_IN = "dialog.in"
    const val DOCK_PAGE = "dock.page"
    const val FOCUS_POP = "focus.pop"
    const val FOLDER_GROW = "folder.grow"
    const val FOLDER_LAND = "folder.land"
    const val FOLDER_PAGE = "folder.page"
    const val FOLDER_SHRINK = "folder.shrink"
    const val FULL_HINT = "full.hint"
    const val FULL_SCREEN = "full.screen"
    const val GLASS_FOCUS = "glass.focus"
    const val GRID = "grid"
    const val GRID_SCROLL = "grid.scroll"
    const val HEADER_FADE = "header.fade"
    const val KEYBOARD_IN = "keyboard.in"
    const val KEY_GLIDE = "key.glide"
    const val KEY_PULSE = "key.pulse"
    const val LABEL_IN = "label.in"
    const val LIST_FOCUS = "list.focus"
    const val LIST_LAND = "list.land"
    const val LIST_LEAVE = "list.leave"
    const val LIST_PAGE = "list.page"
    const val LIST_PRESS = "list.press"
    const val LIST_SCROLL = "list.scroll"
    const val MENU_GROW = "menu.grow"
    const val MENU_SHRINK = "menu.shrink"
    const val PICKER_FADE = "picker.fade"
    const val PICKER_LIFT = "picker.lift"
    const val PICTURE_FADE = "picture.fade"
    const val RECENTS_DISMISS = "recents.dismiss"
    const val RECENTS_FADE = "recents.fade"
    const val RECENTS_HANDOVER = "recents.handover"
    const val RECENTS_HOME = "recents.home"
    const val RECENTS_IN = "recents.in"
    const val RECENTS_OUT = "recents.out"
    const val RECENTS_STEP = "recents.step"
    const val ROWS_LIFT = "rows.lift"
    const val ROW_GLIDE = "row.glide"
    const val SHEET_CLOSE = "sheet.close"
    const val SHEET_GROW = "sheet.grow"
    const val SHEET_OPEN = "sheet.open"
    const val SHEET_SHRINK = "sheet.shrink"
    const val SHEET_TAB = "sheet.tab"
    const val SHEET_TAB_OUT = "sheet.tab.out"
    const val SHELF_SWAP = "shelf.swap"
    const val TILE_FOCUS = "tile.focus"
    const val TILE_GLOSS = "tile.gloss"
    const val TILE_MOVE = "tile.move"
    const val TOAST_DIP = "toast.dip"
    const val TOAST_IN = "toast.in"
    const val TOAST_OUT = "toast.out"

    private val FLOW: Map<String, Spec> = mapOf(
        TILE_FOCUS to Spec(38.0f, 0.65f, 26.0f, 1.0f),
        GRID to Spec.both(10.5f, 1.0f),
        GRID_SCROLL to Spec.both(10.5f, 1.0f),
        DOCK_PAGE to Spec.both(12.0f, 1.0f),
        FOLDER_PAGE to Spec.both(10.5f, 1.0f),
        TILE_MOVE to Spec.both(18.0f, 1.0f),
        SHEET_OPEN to Spec.both(22.0f, 1.0f),
        SHEET_CLOSE to Spec.both(34.0f, 1.0f),
        SHEET_GROW to Spec.both(14.0f, 1.0f),
        SHEET_SHRINK to Spec.both(26.0f, 1.0f),
        CLOCK_CARRY to Spec.both(22.0f, 1.0f),
        CONTROLS_SHRINK to Spec.both(32.0f, 1.0f),
        FOLDER_GROW to Spec.both(13.0f, 0.9f),
        FOLDER_SHRINK to Spec.both(10.0f, 1.0f),
        FOLDER_LAND to Spec.both(20.0f, 1.0f),
        MENU_GROW to Spec.both(26.0f, 1.0f),
        MENU_SHRINK to Spec.both(38.0f, 1.0f),
        RECENTS_IN to Spec.both(24.0f, 1.0f),
        RECENTS_STEP to Spec.both(10.0f, 1.0f),
        RECENTS_OUT to Spec.both(20.0f, 1.0f),
        RECENTS_FADE to Spec.both(20.0f, 1.0f),
        RECENTS_DISMISS to Spec.both(14.0f, 1.0f),
        RECENTS_HOME to Spec.both(16.0f, 1.0f),
        RECENTS_HANDOVER to Spec.both(20.0f, 1.0f),
        CARD_SOFTEN to Spec.both(16.0f, 1.0f),
        CLOCK_RETURN to Spec.both(20.0f, 1.0f),
        CLOCK_REVEAL to Spec(28.0f, 1.0f, 18.0f, 1.0f),
        LABEL_IN to Spec.both(20.0f, 1.0f),
        LIST_PAGE to Spec.both(15.0f, 1.0f),
        LIST_FOCUS to Spec.both(80.0f, 1.0f),
        LIST_PRESS to Spec(80.0f, 1.0f, 45.0f, 1.0f),
        LIST_LAND to Spec.both(36.0f, 1.0f),
        LIST_LEAVE to Spec.both(66.0f, 1.0f),
        LIST_SCROLL to Spec.both(10.0f, 1.0f),
        PICKER_FADE to Spec(45.0f, 1.0f, 61.0f, 1.0f),
        PICKER_LIFT to Spec.both(56.0f, 1.0f),
        ROW_GLIDE to Spec.both(17.0f, 1.0f),
        ROWS_LIFT to Spec.both(16.5f, 1.0f),
        PICTURE_FADE to Spec.both(12.7f, 1.0f),
        TILE_GLOSS to Spec(28.0f, 1.0f, 40.0f, 1.0f),
        SHELF_SWAP to Spec.both(19.5f, 1.0f),
        SHEET_TAB to Spec.both(30.0f, 1.0f),
        SHEET_TAB_OUT to Spec.both(45.0f, 1.0f),
        KEY_GLIDE to Spec.both(30.0f, 1.0f),
        KEY_PULSE to Spec.both(22.0f, 0.55f),
        KEYBOARD_IN to Spec.both(16.0f, 1.0f),
        GLASS_FOCUS to Spec(80.0f, 1.0f, 30.0f, 1.0f),
        FOCUS_POP to Spec(70.0f, 1.0f, 24.0f, 1.0f),
        DIALOG_IN to Spec.both(27.0f, 1.0f),
        DIALOG_GROW to Spec.both(33.0f, 1.0f),
        DIALOG_FOCUS to Spec.both(56.0f, 1.0f),
        TOAST_IN to Spec.both(25.0f, 1.0f),
        TOAST_DIP to Spec(30.0f, 1.0f, 54.0f, 1.0f),
        TOAST_OUT to Spec.both(16.0f, 1.0f),
        APP_OPEN to Spec.both(24.0f, 1.0f),
        APP_RETURN to Spec.both(26.0f, 1.0f),
        APP_SETTLE to Spec.both(8.0f, 1.0f),
        APP_GROW to Spec.both(12.0f, 1.0f),
        APP_RECEDE to Spec.both(12.0f, 1.0f),
        APP_LAND to Spec.both(12.0f, 1.0f),
        HEADER_FADE to Spec.both(10.0f, 1.0f),
        BACKDROP_DIP to Spec.both(9.0f, 1.0f),
        BACKDROP_DARK to Spec.both(60.0f, 1.0f),
        BACKDROP_RISE to Spec.both(7.0f, 1.0f),
        BACKDROP_LAND to Spec.both(12.0f, 1.0f),
        BACKDROP_SHARPEN to Spec.both(16.0f, 1.0f),
        BACKDROP_LIVE to Spec.both(9.0f, 1.0f),
        FULL_SCREEN to Spec.both(12.0f, 1.0f),
        FULL_HINT to Spec(13.0f, 1.0f, 10.0f, 1.0f),
    )


    private val EASED: Set<String> = setOf(LIST_SCROLL)

    private val CUT_IN_PERFORMANCE: Set<String> = setOf(SHELF_SWAP, RECENTS_FADE, RECENTS_HANDOVER)

    private val STEP_TRAVEL: Map<String, FloatArray> = mapOf(
        SHELF_SWAP to floatArrayOf(30.0f, 12.0f),
        SHEET_TAB to floatArrayOf(48.0f, 0.0f),
    )


    private const val GATE_LIMIT_MS: Long = 2000L

    private val PRECISION: FloatArray = floatArrayOf(0.001f, 0.001f, 0.5f, 0.5f, 0.004f)

    private val PROPS: Array<Property<View, Float>> = arrayOf(
        View.SCALE_X, View.SCALE_Y, View.TRANSLATION_X, View.TRANSLATION_Y, View.ALPHA,
    )

    private val MAIN: Handler = Handler(Looper.getMainLooper())
    private val PROGRESS: WeakHashMap<Any, Spring> = WeakHashMap()
    private val MOVING: MutableList<(Boolean) -> Unit> = ArrayList()

    private val movingViews: WeakHashMap<View, Moving> = WeakHashMap()

    private var gate: Int = 0
    private var gateName: String? = null
    private var gateReverse: IntArray = intArrayOf()
    private var gated: Boolean = false
    private var held: Int = 0
    private var nearNext: (() -> Unit)? = null
    private var replay: ((Int) -> Unit)? = null

    class Spec(
        val omegaUp: Float,
        val zetaUp: Float,
        val omegaDown: Float,
        val zetaDown: Float,
    ) {
        companion object {
            fun both(omega: Float, zeta: Float): Spec = Spec(omega, zeta, omega, zeta)
        }
    }

    private fun animatorsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    private fun spec(name: String): Spec? {
        if (!animatorsEnabled()) return null
        if (CUT_IN_PERFORMANCE.contains(name)) return null
        return FLOW[name]
    }

    fun moves(name: String): Boolean = spec(name) != null

    fun follow(name: String, view: View, onUpdate: (Float) -> Unit): Follow {
        val flowSpec = FLOW[name]
        return Follow(view, name, flowSpec?.omegaUp ?: 30.0f, EASED.contains(name), onUpdate)
    }

    fun spring(name: String, start: Float, listener: Spring.Listener): Spring {
        val flowSpec = FLOW[name]
        return Spring(flowSpec?.omegaUp ?: 30.0f, flowSpec?.zetaUp ?: 1.0f, start, listener)
    }

    fun step(
        name: String,
        context: Context,
        dir: Int,
        key: Any,
        onIn: (Float) -> Unit,
        key2: Any,
        from: Float,
        onOut: (Float) -> Unit,
    ) {
        val travel = STEP_TRAVEL[name]
        if (dir == 0 || travel == null || spec(name) == null) {
            finish(key)
            onIn(0.0f)
        } else {
            progress(name, key, (Dimens.px(context, travel[0]) * dir).toFloat(), 0.0f, onIn, null)
            progress(name, key2, from, (-Dimens.px(context, travel[1]) * dir).toFloat(), onOut, null)
        }
    }

    fun realMs(ms: Long): Long {
        if (Build.VERSION.SDK_INT < 33) {
            return ms
        }
        val scale = ValueAnimator.getDurationScale()
        return if (scale > 0.0f) (ms / scale).toLong() else ms
    }

    fun change(name: String, view: View, run: () -> Unit, onDone: (() -> Unit)? = null) {
        val flowSpec = spec(name)
        if (flowSpec == null || !view.isAttachedToWindow) {
            run()
            onDone?.invoke()
            return
        }
        val arrival = Arrival(onDone, nearNext)
        val before = FloatArray(PROPS.size)
        for (i in PROPS.indices) {
            before[i] = PROPS[i].get(view)
        }
        val children: MutableMap<View, FloatArray>?
        if (view is ViewGroup) {
            val map = HashMap<View, FloatArray>()
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                map[child] = floatArrayOf(
                    child.left + child.translationX,
                    child.top + child.translationY,
                    child.alpha,
                    child.left.toFloat(),
                    child.top.toFloat(),
                )
            }
            children = map
        } else {
            children = null
        }
        run()
        for (i in PROPS.indices) {
            val after = PROPS[i].get(view)
            val old = before[i]
            if (after != old) {
                PROPS[i].set(view, old)
                Moving.of(view).go(i, before[i], after, flowSpec, arrival)
            }
        }
        if (children == null) {
            arrival.seal()
            return
        }
        for ((child, old) in children) {
            val oldAlpha = old[2]
            val newAlpha = child.alpha
            if (newAlpha != oldAlpha) {
                child.alpha = oldAlpha
                Moving.of(child).go(4, oldAlpha, newAlpha, flowSpec, arrival)
            }
        }
        val group = view as ViewGroup
        group.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (group.viewTreeObserver.isAlive) {
                    group.viewTreeObserver.removeOnPreDrawListener(this)
                }
                for ((child, old) in children) {
                    if (child.parent === group) {
                        if (child.left.toFloat() != old[3] || child.top.toFloat() != old[4]) {
                            val left = old[0] - child.left
                            val top = old[1] - child.top
                            val moving = Moving.of(child)
                            if (left != child.translationX) {
                                child.translationX = left
                                moving.go(2, left, 0.0f, flowSpec, arrival)
                            }
                            if (top != child.translationY) {
                                child.translationY = top
                                moving.go(3, top, 0.0f, flowSpec, arrival)
                            }
                        }
                    }
                }
                arrival.seal()
                return true
            }
        })
        group.invalidate()
    }

    fun change(name: String, view: View, run: () -> Unit, onNear: (() -> Unit)?, onDone: (() -> Unit)?) {
        nearNext = onNear
        try {
            change(name, view, run, onDone)
        } finally {
            nearNext = null
        }
    }

    fun now(view: View, run: () -> Unit) {
        movingViews[view]?.stop()
        run()
    }


    fun finish(key: Any) {
        val spring = PROGRESS.remove(key)
        if (spring != null) {
            spring.snapTo(spring.target())
        }
    }

    fun progress(
        name: String,
        key: Any,
        from: Float,
        to: Float,
        onUpdate: (Float) -> Unit,
        onDone: (() -> Unit)? = null,
    ) {
        val flowSpec = spec(name)
        val old = PROGRESS.remove(key)
        if (old != null) {
            old.snapTo(old.value())
        }
        if (flowSpec == null) {
            onUpdate(to)
            onDone?.invoke()
            return
        }
        val up = to > from
        lateinit var self: Spring
        val spring = Spring(
            if (up) flowSpec.omegaUp else flowSpec.omegaDown,
            if (up) flowSpec.zetaUp else flowSpec.zetaDown,
            from,
            Spring.Listener { v ->
                onUpdate(v)
                if (!self.isRunning()) {
                    if (PROGRESS[key] === self) {
                        PROGRESS.remove(key)
                        onDone?.invoke()
                    }
                }
            },
        )
        self = spring
        spring.setPrecision(maxOf(1.0f, abs(to - from)) * 0.001f)
        PROGRESS[key] = spring
        onUpdate(from)
        spring.animateTo(to)
        spring.stepNow()
    }

    fun moving(): Boolean = Moving.running > 0

    private fun stillness(moving: Boolean) {
        val snapshot = ArrayList(MOVING)
        for (consumer in snapshot) {
            consumer(moving)
        }
    }

    fun transition(
        name: String,
        view: View,
        run: () -> Unit,
        onDone: (() -> Unit)? = null,
        vararg reverse: Int,
    ) {
        transition(name, view, run, null, onDone, *reverse)
    }

    fun transition(
        name: String,
        view: View,
        run: () -> Unit,
        onNear: (() -> Unit)?,
        onDone: (() -> Unit)?,
        vararg reverse: Int,
    ) {
        val heldGate = hold(name, reverse)
        nearNext = {
            if (heldGate == gate) {
                release()
                onNear?.invoke()
            }
        }
        change(name, view, run) {
            if (heldGate == gate) {
                release()
            }
            onDone?.invoke()
        }
        nearNext = null
    }

    private fun hold(name: String, reverse: IntArray): Int {
        val id = gate + 1
        gate = id
        gated = true
        gateName = name
        gateReverse = reverse
        MAIN.postDelayed({
            if (id == gate && gated) {
                Log.w("FlowMotion", "gate $name never settled: keys let go after 2000 ms")
                release()
            }
        }, GATE_LIMIT_MS)
        return id
    }

    private fun release() {
        gated = false
        val heldKey = held
        held = 0
        if (heldKey != 0) {
            Log.d("FlowMotion", "replay key $heldKey after $gateName")
        }
        val replayFn = replay
        if (heldKey == 0 || replayFn == null) {
            return
        }
        replayFn(heldKey)
    }

    private class Arrival(
        private val done: (() -> Unit)?,
        private val near: (() -> Unit)?,
    ) {
        private var far: Int = 0
        private var pending: Int = 0
        private var ran: Boolean = false
        private var ranNear: Boolean = false
        private var sealed: Boolean = false

        fun add() {
            pending++
            far++
        }

        fun nearly() {
            far--
            check()
        }

        fun arrived() {
            pending--
            check()
        }

        fun seal() {
            sealed = true
            check()
        }

        private fun check() {
            if (sealed) {
                if (far <= 0 && !ranNear) {
                    ranNear = true
                    near?.invoke()
                }
                if (pending > 0 || ran) {
                    return
                }
                ran = true
                done?.invoke()
            }
        }
    }

    private class Moving private constructor(private val view: View) {
        companion object {
            var running: Int = 0

            fun of(view: View): Moving = Motion.movingViews.getOrPut(view) { Moving(view) }
        }

        private val springs: Array<Spring?> = arrayOfNulls(PROPS.size)
        private val arrivals: Array<Arrival?> = arrayOfNulls(PROPS.size)
        private val travel: FloatArray = FloatArray(PROPS.size)
        private val near: BooleanArray = BooleanArray(PROPS.size)

        fun go(index: Int, from: Float, to: Float, spec: Spec, arrival: Arrival) {
            val up = to > from
            val omega = if (up) spec.omegaUp else spec.omegaDown
            val zeta = if (up) spec.zetaUp else spec.zetaDown
            var spring = springs[index]
            if (spring == null) {
                spring = Spring(omega, zeta, from) { v -> onSpringUpdate(index, v) }
                springs[index] = spring
                spring.setPrecision(Motion.PRECISION[index])
            }
            spring.setParams(omega, zeta)
            if (!spring.isRunning() && spring.value() != from) {
                spring.snapTo(from)
            } else if (spring.isRunning() && spring.value() != from) {
                spring.shift(from - spring.value(), to)
            }
            val prevArrival = arrivals[index]
            if (prevArrival != null) {
                if (!near[index]) {
                    prevArrival.nearly()
                }
                prevArrival.arrived()
            } else {
                val wasRunning = running
                running = wasRunning + 1
                if (wasRunning == 0) {
                    Motion.stillness(true)
                }
            }
            arrivals[index] = arrival
            travel[index] = abs(to - from)
            near[index] = false
            arrival.add()
            val wasRunning = spring.isRunning()
            spring.animateTo(to)
            if (!wasRunning) {
                spring.stepNow()
            }
            if (spring.isRunning()) {
                return
            }
            settled(index)
        }

        private fun onSpringUpdate(index: Int, value: Float) {
            Motion.PROPS[index].set(view, value)
            val spring = springs[index] ?: return
            if (!near[index] && arrivals[index] != null &&
                abs(spring.target() - value) <= maxOf(Motion.PRECISION[index], travel[index] * 0.02f)
            ) {
                near[index] = true
                arrivals[index]?.nearly()
            }
            if (spring.isRunning()) {
                return
            }
            settled(index)
        }

        fun stop() {
            for (i in springs.indices) {
                val spring = springs[i]
                if (spring != null && spring.isRunning()) {
                    spring.snapTo(spring.target())
                }
                settled(i)
            }
        }

        private fun settled(index: Int) {
            val arrival = arrivals[index] ?: return
            arrivals[index] = null
            running -= 1
            if (running == 0) {
                Motion.stillness(false)
            }
            if (!near[index]) {
                near[index] = true
                arrival.nearly()
            }
            arrival.arrived()
        }
    }
}
