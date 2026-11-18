package com.gathin.tvhome.guard

internal object UsageFront {
    const val MOVE_TO_FOREGROUND = 1
    const val MOVE_TO_BACKGROUND = 2

    data class Front(val pkg: String, val cls: String, val at: Long, val closed: Boolean)

    class Picker {
        private var front: Front? = null

        fun add(type: Int, pkg: String, cls: String?, at: Long) {
            if (cls == null) return
            val f = front
            when (type) {
                MOVE_TO_FOREGROUND -> front = Front(pkg, cls, at, closed = false)
                MOVE_TO_BACKGROUND -> if (f != null && f.pkg == pkg && f.cls == cls && at >= f.at) front = f.copy(closed = true)
            }
        }

        fun result(): Front? = front
    }
}
