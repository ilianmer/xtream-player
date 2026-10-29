package com.ilian.xtreamplayer

import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView

/**
 * Pastille d'une équipe ou d'un joueur (layout item_crest) :
 * - drapeau : remplit tout le rond, avec un fin liseré ;
 * - logo de club : centré avec une marge régulière sur un disque clair discret ;
 * - sinon : initiales sur la couleur de l'équipe.
 */
object Crest {
    private val OVAL = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    private val trimCache = object : android.util.LruCache<String, Bitmap>(64) {}

    /** Image sans ses marges transparentes (les drapeaux sont livrés au milieu d'un carré vide). */
    private fun trimmed(key: String, b: Bitmap): Bitmap {
        trimCache.get(key)?.let { return it }
        val out = try {
            val w = b.width
            val h = b.height
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            fun opaque(x: Int, y: Int) = (px[y * w + x] ushr 24) > 40
            var top = 0
            while (top < h && (0 until w).none { opaque(it, top) }) top++
            var bottom = h - 1
            while (bottom > top && (0 until w).none { opaque(it, bottom) }) bottom--
            var left = 0
            while (left < w && (top..bottom).none { opaque(left, it) }) left++
            var right = w - 1
            while (right > left && (top..bottom).none { opaque(right, it) }) right--
            // Petite marge rognée en plus : certains drapeaux ont un contour clair d'un pixel
            val inset = 1
            val l = (left + inset).coerceAtMost(right)
            val t = (top + inset).coerceAtMost(bottom)
            val cw = (right - inset - l + 1).coerceAtLeast(1)
            val ch = (bottom - inset - t + 1).coerceAtLeast(1)
            if (top >= h || (l == 0 && t == 0 && cw == w && ch == h)) b else Bitmap.createBitmap(b, l, t, cw, ch)
        } catch (_: Exception) {
            b
        }
        trimCache.put(key, out)
        return out
    }

    fun isFlag(url: String?): Boolean =
        url != null && (url.contains("/countries/") || url.contains("/flags/") || url.contains("flag", ignoreCase = true))

    /** Initiales lisibles : lettres et chiffres seulement (« EH/ » devient « EH »). */
    fun initials(abbr: String): String =
        abbr.filter { it.isLetterOrDigit() }.take(3).uppercase().ifBlank { "?" }

    fun bind(v: View, s: Sport.Side, maxPx: Int = 96) {
        val text = v.findViewById<TextView>(R.id.crestText)
        val img = v.findViewById<ImageView>(R.id.crestImg)
        val d = v.resources.displayMetrics.density
        val disc = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(s.color ?: 0xFF3A3F55.toInt())
        }
        text.background = disc
        text.text = initials(s.abbr)
        img.setImageDrawable(null)
        img.foreground = null
        img.outlineProvider = OVAL
        img.clipToOutline = true
        val logo = s.logo?.takeIf { it.isNotBlank() }
        img.tag = logo
        if (logo == null) return
        val flag = isFlag(logo)
        Img.fetch(logo, maxPx) { bmp ->
            if (bmp == null || img.tag != logo) return@fetch
            text.text = ""
            if (flag) {
                // Le drapeau remplit toute la bulle : on retire les bandes vides de l'image, puis recadrage en rond
                img.setPadding(0, 0, 0, 0)
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                disc.setColor(0x00000000)
                img.setImageBitmap(trimmed(logo, bmp))
                return@fetch
            } else {
                // Logo de club : marge proportionnelle pour qu'il respire dans le rond
                val pad = (img.width.takeIf { it > 0 } ?: v.width).let { (it * 0.14f).toInt() }.coerceAtLeast((2 * d).toInt())
                img.setPadding(pad, pad, pad, pad)
                img.scaleType = ImageView.ScaleType.FIT_CENTER
                disc.setColor(0x1FFFFFFF)
            }
            img.setImageBitmap(bmp)
        }
    }
}
