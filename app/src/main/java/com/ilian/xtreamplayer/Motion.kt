package com.ilian.xtreamplayer

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/**
 * Pastille qui glisse d'un onglet à l'autre, comme la barre du haut d'Apple TV :
 * blanche sous l'onglet qui a le focus, en verre sous l'onglet choisi quand le focus est ailleurs.
 * Elle est dessinée dans le fond de la barre (aucune vue en plus, rien à remesurer).
 */
class TabPill(private val bar: ViewGroup, radiusDp: Float = 15f) {
    private val pill = PillDrawable(bar.resources.displayMetrics.density * radiusDp)
    private var selected: View? = null
    private var focused: View? = null

    init {
        val base = bar.background ?: ColorDrawable(0)
        bar.background = LayerDrawable(arrayOf(base, pill))
        bar.viewTreeObserver.addOnGlobalFocusChangeListener { _, new ->
            if (new != null && new.parent === bar && new.visibility == View.VISIBLE) {
                focused = new
                moveTo(new, true)
            } else if (focused != null) {
                focused = null
                moveTo(selected, false)
            }
        }
        bar.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) bar.post { snap() }
        }
    }

    /** Les onglets n'ont plus de fond propre : la pastille le dessine. */
    fun adopt(tab: View) {
        tab.background = null
    }

    /** Onglet choisi (l'écran affiché). */
    fun select(tab: View?) {
        selected = tab
        if (focused == null) moveTo(tab, false)
    }

    private fun snap() {
        val v = focused ?: selected ?: return
        pill.jump(rectOf(v), if (focused != null) FOCUS else REST)
    }

    private fun rectOf(v: View) = RectF(v.left.toFloat(), v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat())

    private fun moveTo(v: View?, focus: Boolean) {
        if (v == null || v.width == 0) {
            pill.fadeOut()
            return
        }
        pill.animateTo(rectOf(v), if (focus) FOCUS else REST)
    }

    companion object {
        const val FOCUS = 0xFFFFFFFF.toInt()
        const val REST = 0x2EFFFFFF
    }
}

/** Rectangle arrondi animé (position, taille et couleur), dessiné en fond. */
class PillDrawable(private val radius: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cur = RectF()
    private var color = 0
    private var anim: ValueAnimator? = null
    private var shown = false

    fun jump(r: RectF, c: Int) {
        anim?.cancel()
        cur.set(r)
        color = c
        shown = true
        invalidateSelf()
    }

    fun animateTo(r: RectF, c: Int) {
        if (!shown) {
            // Première apparition : la pastille éclot sur place
            cur.set(r.centerX(), r.top, r.centerX(), r.bottom)
            color = c and 0x00FFFFFF
            shown = true
        }
        anim?.cancel()
        val from = RectF(cur)
        val c0 = color
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = EASE
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                cur.set(lerp(from.left, r.left, f), lerp(from.top, r.top, f), lerp(from.right, r.right, f), lerp(from.bottom, r.bottom, f))
                color = blend(c0, c, (f * 1.4f).coerceAtMost(1f))
                invalidateSelf()
            }
            start()
        }
    }

    fun fadeOut() {
        if (!shown) return
        anim?.cancel()
        val c0 = color
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            addUpdateListener { a ->
                color = blend(c0, c0 and 0x00FFFFFF, a.animatedValue as Float)
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        if (!shown || (color ushr 24) == 0) return
        paint.color = color
        canvas.drawRoundRect(cur, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** Départ vif, arrivée très douce (courbe « ressort » des interfaces Apple). */
        val EASE = PathInterpolator(0.22f, 1f, 0.36f, 1f)

        fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

        fun blend(a: Int, b: Int, f: Float): Int {
            fun ch(s: Int) = ((a ushr s and 255) + (((b ushr s and 255) - (a ushr s and 255)) * f)).toInt().coerceIn(0, 255)
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
