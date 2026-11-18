package com.gathin.tvhome.apps

import android.view.Choreographer
import kotlin.math.abs

class Spring(
    omega: Float,
    zeta: Float,
    start: Float,
    private val listener: Listener,
) : Choreographer.FrameCallback {

    fun interface Listener {
        fun onSpringUpdate(value: Float)
    }

    private var omega: Float = omega
    private var zeta: Float = zeta
    private var precision: Float = 0.0015f
    private var running: Boolean = false
    private var curTarget: Float = start
    private var curValue: Float = start
    private var curVelocity: Float = 0.0f
    private var lastNanos: Long = 0L

    fun setPrecision(precision: Float) {
        this.precision = precision
    }

    fun setParams(omega: Float, zeta: Float) {
        this.omega = omega
        this.zeta = zeta
    }

    fun value(): Float = curValue

    fun target(): Float = curTarget

    fun isRunning(): Boolean = running

    fun animateTo(target: Float) {
        curTarget = target
        if (running) {
            return
        }
        if (abs(curValue - target) >= 1.0e-4f || abs(curVelocity) >= 0.001f) {
            running = true
            lastNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun shift(delta: Float, target: Float) {
        val newValue = curValue + delta
        curValue = newValue
        curTarget = target
        listener.onSpringUpdate(newValue)
        if (running) {
            return
        }
        if (abs(curValue - target) >= 1.0e-4f || abs(curVelocity) >= 0.001f) {
            running = true
            lastNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun snapTo(target: Float) {
        curTarget = target
        curValue = target
        curVelocity = 0.0f
        if (running) {
            Choreographer.getInstance().removeFrameCallback(this)
            running = false
        }
        listener.onSpringUpdate(curValue)
    }

    fun stepNow() {
        if (running) {
            integrate(0.016666668f)
            listener.onSpringUpdate(curValue)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (running) {
            val prevNanos = lastNanos
            val dt = if (prevNanos == 0L) 0.016666668f else minOf(0.034f, (frameTimeNanos - prevNanos) / 1.0e9f)
            lastNanos = frameTimeNanos
            integrate(dt)
            listener.onSpringUpdate(curValue)
            if (running) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private fun integrate(dt: Float) {
        val sub = dt / 4.0f
        for (i in 0 until 4) {
            val w = omega
            val v0 = curValue
            val accel = (-w) * w * (v0 - curTarget)
            val damp = zeta * 2.0f * w
            val vel0 = curVelocity
            val vel1 = vel0 + ((accel - (damp * vel0)) * sub)
            curVelocity = vel1
            curValue = v0 + (vel1 * sub)
        }
        if (abs(curValue - curTarget) >= precision || abs(curVelocity) >= precision * 7.0f) {
            return
        }
        curValue = curTarget
        curVelocity = 0.0f
        running = false
    }
}
