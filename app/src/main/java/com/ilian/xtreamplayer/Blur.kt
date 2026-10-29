package com.ilian.xtreamplayer

import android.graphics.Bitmap

/**
 * Flou rapide pour simuler l'effet verre (le vrai flou en direct n'existe qu'à partir d'Android 12).
 * L'image est réduite à [width] pixels de large puis floutée par passes successives de moyenne.
 */
object Blur {
    fun apply(src: Bitmap, width: Int, radius: Int): Bitmap {
        val w = width.coerceAtMost(src.width).coerceAtLeast(8)
        val h = (src.height * w / src.width.toFloat()).toInt().coerceAtLeast(8)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            pass(px, tmp, w, h, radius, true)
            pass(tmp, px, w, h, radius, false)
        }
        // Assombrit légèrement pour garder le texte lisible par-dessus.
        for (i in px.indices) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF) * 82 / 100
            val g = ((c shr 8) and 0xFF) * 82 / 100
            val b = (c and 0xFF) * 82 / 100
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        return out
    }

    private fun pass(input: IntArray, output: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = r * 2 + 1
        for (line in 0 until lines) {
            var sr = 0
            var sg = 0
            var sb = 0
            fun at(i: Int): Int {
                val k = i.coerceIn(0, len - 1)
                return if (horizontal) input[line * w + k] else input[k * w + line]
            }
            for (i in -r..r) {
                val c = at(i)
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF
            }
            for (i in 0 until len) {
                val idx = if (horizontal) line * w + i else i * w + line
                output[idx] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = at(i + r + 1)
                val rem = at(i - r)
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }
}
