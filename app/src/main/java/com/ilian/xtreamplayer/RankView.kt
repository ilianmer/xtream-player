package com.ilian.xtreamplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat

/**
 * Grand chiffre du Top 10, comme sur Netflix : chiffre sombre cerclé d'un fin contour clair,
 * posé derrière l'affiche qui le recouvre en partie. Dessiné une fois (aucune image).
 */
class RankView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val density = resources.displayMetrics.density
    private val font = try {
        ResourcesCompat.getFont(ctx, R.font.bebas_neue)
    } catch (_: Exception) {
        null
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font
        style = Paint.Style.FILL
        textAlign = Paint.Align.RIGHT
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeJoin = Paint.Join.ROUND
        color = 0xFF9AA0AA.toInt()
        textAlign = Paint.Align.RIGHT
    }

    var rank: Int = 1
        set(v) {
            field = v
            invalidate()
        }

    /** Chiffre allumé (focus sur l'affiche) : contour doré. */
    var lit: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            stroke.color = if (v) 0xFFF5C451.toInt() else 0xFF9AA0AA.toInt()
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fill.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF2A2F38.toInt(), 0xFF0B0D11.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val text = rank.toString()
        val h = height.toFloat()
        // Le chiffre occupe toute la hauteur ; « 10 » est resserré pour tenir dans la place
        fill.letterSpacing = if (text.length > 1) -0.06f else 0f
        stroke.letterSpacing = fill.letterSpacing
        var size = h * 1.12f
        fill.textSize = size
        val maxW = width * 0.98f
        val w = fill.measureText(text)
        if (w > maxW) size *= maxW / w
        fill.textSize = size
        stroke.textSize = size
        val x = width.toFloat() - 2f * density
        val y = h - 2f * density
        canvas.drawText(text, x, y, fill)
        canvas.drawText(text, x, y, stroke)
    }
}
