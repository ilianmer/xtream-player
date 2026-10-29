package com.ilian.xtreamplayer

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
                // Le drapeau prend tout le rond
                img.setPadding(0, 0, 0, 0)
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.foreground = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke((1 * d).toInt().coerceAtLeast(1), 0x40FFFFFF)
                }
                disc.setColor(0x00000000)
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
