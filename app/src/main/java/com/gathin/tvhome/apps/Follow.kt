package com.gathin.tvhome.apps

import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.exp

class Follow internal constructor(
    val host: View,
    private val name: String?,
    val omega: Float,
    val eased: Boolean,
    val apply: (Float) -> Unit,
) : Runnable {

    private val tick = Choreographer.FrameCallback { frameTimeNanos ->
        frameNanos = frameTimeNanos
        run()
    }

    private var frameNanos: Long = 0L
    internal var goal: Float = 0.0f
    internal var value: Float = 0.0f
    internal var velocity: Float = 0.0f
    private var last: Long = 0L
    internal var running: Boolean = false

    fun to(target: Float, from: Float) {
        val specName = name
        if (specName != null && !Motion.moves(specName)) {
            cancel()
            goal = target
            value = target
            velocity = 0.0f
            apply(target)
            return
        }
        goal = target
        if (running) {
            return
        }
        value = from
        velocity = 0.0f
        running = true
        last = 0L
        next()
    }

    fun cancel() {
        running = false
        host.removeCallbacks(this)
        Choreographer.getInstance().removeFrameCallback(tick)
    }

    private fun next() {
        if (host.isAttachedToWindow) {
            Choreographer.getInstance().postFrameCallback(tick)
        } else {
            host.postOnAnimation(this)
        }
    }

    override fun run() {
        var frameTime = frameNanos
        if (frameTime == 0L) {
            frameTime = System.nanoTime()
        }
        frameNanos = 0L
        if (running) {
            val prev = last
            val dt = if (prev == 0L) 0.016666668f else minOf(0.25f, (frameTime - prev) / 1.0e9f)
            last = frameTime
            val dist = value - goal
            val decay = exp((-omega * dt).toDouble()).toFloat()
            val vel = velocity
            val w = omega
            val combined = (w * dist) + vel
            val target = goal
            val newValue: Float
            if (eased) {
                newValue = (dist * decay) + target
                value = newValue
                velocity = (-w) * (newValue - target)
            } else {
                newValue = ((dist + (combined * dt)) * decay) + target
                value = newValue
                velocity = (vel - ((w * combined) * dt)) * decay
            }
            if (abs(newValue - target) < 0.5f && abs(velocity) < 20.0f) {
                value = goal
                running = false
            }
            apply(value)
            if (running) {
                next()
            }
        }
    }
}
