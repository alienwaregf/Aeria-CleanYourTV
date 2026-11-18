package com.gathin.tvhome.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import kotlin.concurrent.thread

object FontWarmup {
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        thread(name = "AerialFonts", priority = Thread.NORM_PRIORITY) {
            runCatching {
                val resolver = createFontFamilyResolver(app)
                val faces = listOf(
                    InterText to FontWeight.W500, InterText to FontWeight.W600,
                    InterDisplay to FontWeight.W500, InterDisplay to FontWeight.W600,
                ).mapNotNull { (family, weight) -> resolver.resolve(family, weight).value as? Typeface }
                val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 28f }
                for (face in faces.ifEmpty { listOf(Typeface.DEFAULT) }) {
                    paint.typeface = face
                    paint.measureText(SAMPLE)
                }
            }
        }
    }

    private const val SAMPLE = "Aerial 0123456789:° 设置天气相册应用 Настройки"
}
