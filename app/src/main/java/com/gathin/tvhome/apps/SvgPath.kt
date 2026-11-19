package com.gathin.tvhome.apps

import android.graphics.Path

internal class SvgPath private constructor(private val s: String) {
    private var i = 0

    companion object {
        fun parse(d: String): Path = SvgPath(d).run()
    }

    private fun run(): Path {
        val p = Path()
        var x = 0f
        var y = 0f
        var startX = 0f
        var startY = 0f
        var lastCx = 0f
        var lastCy = 0f
        var cmd = 0.toChar()
        var prev = 0.toChar()
        while (true) {
            skip()
            if (i >= s.length) break
            val c = s[i]
            if (c.isLetter()) {
                cmd = c
                i++
            } else if (cmd.code == 0) {
                break
            }
            val rel = cmd.isLowerCase()
            val ox = if (rel) x else 0f
            val oy = if (rel) y else 0f
            when (cmd.uppercaseChar()) {
                'M' -> {
                    x = ox + num()
                    y = oy + num()
                    p.moveTo(x, y)
                    startX = x
                    startY = y
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> {
                    x = ox + num()
                    y = oy + num()
                    p.lineTo(x, y)
                }
                'H' -> {
                    x = ox + num()
                    p.lineTo(x, y)
                }
                'V' -> {
                    y = oy + num()
                    p.lineTo(x, y)
                }
                'C' -> {
                    val x1 = ox + num()
                    val y1 = oy + num()
                    val x2 = ox + num()
                    val y2 = oy + num()
                    x = ox + num()
                    y = oy + num()
                    p.cubicTo(x1, y1, x2, y2, x, y)
                    lastCx = x2
                    lastCy = y2
                }
                'S' -> {
                    val pu = prev.uppercaseChar()
                    val x1 = if (pu == 'C' || pu == 'S') 2 * x - lastCx else x
                    val y1 = if (pu == 'C' || pu == 'S') 2 * y - lastCy else y
                    val x2 = ox + num()
                    val y2 = oy + num()
                    x = ox + num()
                    y = oy + num()
                    p.cubicTo(x1, y1, x2, y2, x, y)
                    lastCx = x2
                    lastCy = y2
                }
                'Q' -> {
                    val x1 = ox + num()
                    val y1 = oy + num()
                    x = ox + num()
                    y = oy + num()
                    p.quadTo(x1, y1, x, y)
                    lastCx = x1
                    lastCy = y1
                }
                'T' -> {
                    val pu = prev.uppercaseChar()
                    val x1 = if (pu == 'Q' || pu == 'T') 2 * x - lastCx else x
                    val y1 = if (pu == 'Q' || pu == 'T') 2 * y - lastCy else y
                    x = ox + num()
                    y = oy + num()
                    p.quadTo(x1, y1, x, y)
                    lastCx = x1
                    lastCy = y1
                }
                'A' -> {
                    val rx = num()
                    val ry = num()
                    val rot = num()
                    val large = flag()
                    val sweep = flag()
                    val ex = ox + num()
                    val ey = oy + num()
                    arc(p, x, y, ex, ey, rx, ry, rot, large, sweep)
                    x = ex
                    y = ey
                }
                'Z' -> {
                    p.close()
                    x = startX
                    y = startY
                }
                else -> return p
            }
            prev = cmd
        }
        return p
    }

    private fun skip() {
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r') i++ else break
        }
    }

    private fun num(): Float {
        skip()
        val start = i
        if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
        var dot = false
        var digits = false
        while (i < s.length) {
            val c = s[i]
            if (c.isDigit()) {
                digits = true
                i++
            } else if (c == '.' && !dot) {
                dot = true
                i++
            } else if ((c == 'e' || c == 'E') && digits) {
                i++
                if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            } else {
                break
            }
        }
        if (start == i) throw IllegalArgumentException("bad number at $i")
        return s.substring(start, i).toFloat()
    }

    private fun flag(): Boolean {
        skip()
        val c = s[i++]
        return c == '1'
    }

    private fun arc(
        p: Path, x1: Float, y1: Float, x2: Float, y2: Float,
        rx0: Float, ry0: Float, angleDeg: Float, largeArc: Boolean, sweep: Boolean,
    ) {
        var rx = rx0
        var ry = ry0
        if (rx == 0f || ry == 0f) {
            p.lineTo(x2, y2)
            return
        }
        rx = kotlin.math.abs(rx)
        ry = kotlin.math.abs(ry)
        val phi = Math.toRadians(angleDeg.toDouble())
        val cos = kotlin.math.cos(phi)
        val sin = kotlin.math.sin(phi)
        val dx = (x1 - x2) / 2.0
        val dy = (y1 - y2) / 2.0
        val x1p = cos * dx + sin * dy
        val y1p = -sin * dx + cos * dy
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) {
            val sq = kotlin.math.sqrt(lambda)
            rx *= sq.toFloat()
            ry *= sq.toFloat()
        }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        if (den == 0.0) return
        val coef = (if (largeArc == sweep) -1 else 1) * kotlin.math.sqrt(kotlin.math.max(0.0, num / den))
        val cxp = coef * (rx * y1p / ry)
        val cyp = coef * -(ry * x1p / rx)
        val cx = cos * cxp - sin * cyp + (x1 + x2) / 2.0
        val cy = sin * cxp + cos * cyp + (y1 + y2) / 2.0
        val theta1 = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dTheta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dTheta > 0) dTheta -= 2 * Math.PI
        else if (sweep && dTheta < 0) dTheta += 2 * Math.PI
        val segs = kotlin.math.ceil(kotlin.math.abs(dTheta) / (Math.PI / 2)).toInt()
        val delta = dTheta / segs
        val t = 4.0 / 3.0 * kotlin.math.tan(delta / 4)
        var th = theta1
        for (k in 0 until segs) {
            val c1 = kotlin.math.cos(th)
            val s1 = kotlin.math.sin(th)
            val c2 = kotlin.math.cos(th + delta)
            val s2 = kotlin.math.sin(th + delta)
            val ex1 = c1 - t * s1
            val ey1 = s1 + t * c1
            val ex2 = c2 + t * s2
            val ey2 = s2 - t * c2
            p.cubicTo(
                (cx + rx * cos * ex1 - ry * sin * ey1).toFloat(),
                (cy + rx * sin * ex1 + ry * cos * ey1).toFloat(),
                (cx + rx * cos * ex2 - ry * sin * ey2).toFloat(),
                (cy + rx * sin * ex2 + ry * cos * ey2).toFloat(),
                (cx + rx * cos * c2 - ry * sin * s2).toFloat(),
                (cy + rx * sin * c2 + ry * cos * s2).toFloat(),
            )
            th += delta
        }
    }

    private fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val a = kotlin.math.atan2(uy, ux)
        val b = kotlin.math.atan2(vy, vx)
        var d = b - a
        while (d > Math.PI) d -= 2 * Math.PI
        while (d < -Math.PI) d += 2 * Math.PI
        return d
    }
}
