package com.gathin.tvhome.apps

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.util.Property
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import java.util.WeakHashMap
import kotlin.math.abs

object Motion {


    const val ROW_GLIDE = "row.glide"
    const val SHELF_SWAP = "shelf.swap"
    const val TILE_FOCUS = "tile.focus"

    private val FLOW: Map<String, Spec> = mapOf(
        TILE_FOCUS to Spec(38.0f, 0.65f, 26.0f, 1.0f),
        ROW_GLIDE to Spec.both(17.0f, 1.0f),
        SHELF_SWAP to Spec.both(19.5f, 1.0f),
    )


    private val EASED: Set<String> = setOf()

    private val CUT_IN_PERFORMANCE: Set<String> = setOf(SHELF_SWAP)

    private val STEP_TRAVEL: Map<String, FloatArray> = mapOf(
        SHELF_SWAP to floatArrayOf(30.0f, 12.0f),
    )


    private val PRECISION: FloatArray = floatArrayOf(0.001f, 0.001f, 0.5f, 0.5f, 0.004f)

    private val PROPS: Array<Property<View, Float>> = arrayOf(
        View.SCALE_X, View.SCALE_Y, View.TRANSLATION_X, View.TRANSLATION_Y, View.ALPHA,
    )

    private val PROGRESS: WeakHashMap<Any, Spring> = WeakHashMap()

    private val movingViews: WeakHashMap<View, Moving> = WeakHashMap()

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

    fun change(name: String, view: View, run: () -> Unit, onDone: (() -> Unit)? = null) {
        val flowSpec = spec(name)
        if (flowSpec == null || !view.isAttachedToWindow) {
            run()
            onDone?.invoke()
            return
        }
        val arrival = Arrival(onDone)
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
        if (from == to) {
            onUpdate(to)
            onDone?.invoke()
            return
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
        if (!spring.isRunning() && PROGRESS[key] === spring) {
            PROGRESS.remove(key)
            onDone?.invoke()
        }
    }

    private class Arrival(
        private val done: (() -> Unit)?,
    ) {
        private var pending: Int = 0
        private var ran: Boolean = false
        private var sealed: Boolean = false

        fun add() {
            pending++
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
                prevArrival.arrived()
            } else {
                val wasRunning = running
                running = wasRunning + 1
            }
            arrivals[index] = arrival
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
            if (spring.isRunning()) {
                return
            }
            settled(index)
        }

        private fun settled(index: Int) {
            val arrival = arrivals[index] ?: return
            arrivals[index] = null
            running -= 1
            arrival.arrived()
        }
    }
}
