package dev.kogler.a7iiiconnect.develop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.sin

/** Generated test photos (Robolectric draws them with the real Skia). */
object TestScenes {

    /** A lake-and-mountains scene at sunset, drawn at [outW] x [outH] (designed for 3:2). */
    fun landscape(outW: Int, outH: Int): Bitmap {
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Drawn in a 3000 x 2000 space, scaled to the requested size.
        c.scale(outW / 3000f, outH / 2000f)
        val w = 3000; val h = 2000
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val horizon = h * 0.58f

        p.shader = LinearGradient(0f, 0f, 0f, horizon, intArrayOf(0xFF1B3A6B.toInt(), 0xFF6C7FB0.toInt(), 0xFFF2A65A.toInt(), 0xFFFFD39A.toInt()), floatArrayOf(0f, 0.45f, 0.85f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), horizon, p)
        p.shader = RadialGradient(w * 0.68f, horizon - 120f, 520f, intArrayOf(0xFFFFF6D8.toInt(), 0x66FFC070, 0x00FFC070), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), horizon, p)
        p.shader = null

        fun ridge(base: Float, amp: Float, freq: Float, phase: Float, color: Int) {
            val path = Path()
            path.moveTo(0f, horizon)
            var x = 0f
            while (x <= w) {
                val y = base - amp * (0.6f * sin(x / w * freq + phase) + 0.3f * sin(x / w * freq * 2.7f + phase * 2f) + 0.1f * sin(x / w * freq * 7.1f))
                path.lineTo(x, y)
                x += 10f
            }
            path.lineTo(w.toFloat(), horizon)
            path.close()
            p.color = color
            c.drawPath(path, p)
        }
        ridge(horizon - 330f, 260f, 5f, 0.3f, 0xFF5B5F86.toInt())
        ridge(horizon - 170f, 200f, 7f, 1.7f, 0xFF3C4466.toInt())
        ridge(horizon - 40f, 110f, 11f, 2.9f, 0xFF223322.toInt())

        // Lake: the sky mirrored and darkened.
        p.shader = LinearGradient(0f, horizon, 0f, h * 0.86f, intArrayOf(0xFFE89A5A.toInt(), 0xFF4A5A80.toInt(), 0xFF14233F.toInt()), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, horizon, w.toFloat(), h * 0.86f, p)
        p.shader = null
        p.color = 0x55FFE0B0
        for (i in 0 until 14) {
            val y = horizon + 20f + i * 28f
            c.drawRect(w * 0.68f - 160f + i * 6f, y, w * 0.68f + 160f - i * 6f, y + 6f, p)
        }

        // Foreground shore with a few dark trees.
        p.color = 0xFF0F1A0E.toInt()
        val shore = Path()
        shore.moveTo(0f, h * 0.84f)
        shore.cubicTo(w * 0.3f, h * 0.80f, w * 0.6f, h * 0.90f, w.toFloat(), h * 0.83f)
        shore.lineTo(w.toFloat(), h.toFloat()); shore.lineTo(0f, h.toFloat()); shore.close()
        c.drawPath(shore, p)
        for ((tx, th) in listOf(220f to 520f, 330f to 420f, 2650f to 600f, 2780f to 470f)) {
            val tree = Path()
            tree.moveTo(tx, h * 0.84f - th); tree.lineTo(tx - th * 0.22f, h * 0.86f); tree.lineTo(tx + th * 0.22f, h * 0.86f); tree.close()
            c.drawPath(tree, p)
        }

        return bmp
    }
}
