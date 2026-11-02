package com.ilian.xtreamplayer

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import java.util.WeakHashMap

/**
 * Barre de pastilles qui ne montre que des pastilles entières, jamais un bout coupé sur les bords :
 * elle avance d'une pastille à la fois, celles qui ne tiennent pas en entier s'effacent, et [onEdges]
 * dit s'il en reste à gauche ou à droite (pour les petites flèches).
 */
class ChipScrollView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : HorizontalScrollView(ctx, attrs) {
    /** (il en reste à gauche, il en reste à droite) */
    var onEdges: ((Boolean, Boolean) -> Unit)? = null

    /** Première pastille de la fenêtre affichée. */
    private var first = 0
    /** Pastille à garder visible : celle qui a le focus, ou la catégorie choisie. */
    private var anchor = 0
    /** Fenêtre à poser dès que les pastilles auront leur place (après une reconstruction). */
    private var pending = false
    private val target = WeakHashMap<View, Float>()
    private var edges = -1

    init {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = false
    }

    private val row: ViewGroup? get() = getChildAt(0) as? ViewGroup
    private val count: Int get() = row?.childCount ?: 0
    private fun room() = width - paddingLeft - paddingRight
    private fun left(i: Int) = row!!.getChildAt(i).left
    private fun right(i: Int) = row!!.getChildAt(i).right

    /** Dernière pastille entière d'une fenêtre qui commence à [s]. */
    private fun end(s: Int): Int {
        val w = room()
        var e = s
        while (e + 1 < count && right(e + 1) - left(s) <= w) e++
        return e
    }

    /** Fenêtre (sa première pastille) qui montre [f] en bougeant le moins possible. */
    private fun startFor(f: Int): Int {
        val n = count
        if (n == 0) return 0
        val w = room()
        var s = first.coerceIn(0, n - 1)
        if (f < s) {
            s = f
        } else if (f > end(s)) {
            s = f
            while (s > 0 && right(f) - left(s - 1) <= w) s--
        }
        // Au bout de la barre, la fenêtre se remplit : pas de vide à droite
        if (end(s) == n - 1) while (s > 0 && right(n - 1) - left(s - 1) <= w) s--
        return s
    }

    private fun xFor(s: Int) = (row?.left ?: 0) + left(s) - paddingLeft

    /** Nouvelles pastilles : la fenêtre repart du début et montre [index] dès qu'elles sont posées. */
    fun reset(index: Int) {
        first = 0
        anchor = index.coerceAtLeast(0)
        target.clear()
        edges = -1
        pending = true
        requestLayout()
    }

    /** Montre la pastille [index] (en glissant si [animate]). */
    fun show(index: Int, animate: Boolean) {
        anchor = index.coerceAtLeast(0)
        if (count == 0 || width == 0) {
            pending = true
            return
        }
        anchor = anchor.coerceAtMost(count - 1)
        val s = startFor(anchor)
        first = s
        val x = xFor(s)
        if (animate) smoothScrollTo(x, 0) else scrollTo(x, 0)
        paint(s, end(s), animate)
    }

    private fun paint(s: Int, e: Int, animate: Boolean) {
        val r = row ?: return
        for (i in 0 until r.childCount) {
            val c = r.getChildAt(i)
            val a = if (i in s..e) 1f else 0f
            if (target[c] == a && (animate || c.alpha == a)) continue
            target[c] = a
            c.animate().cancel()
            if (animate) c.animate().alpha(a).setDuration(if (a > 0f) 220L else 140L).start() else c.alpha = a
        }
        val k = (if (s > 0) 1 else 0) or (if (e < r.childCount - 1) 2 else 0)
        if (k != edges) {
            edges = k
            onEdges?.invoke(s > 0, e < r.childCount - 1)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Marge vide après la dernière pastille : la fenêtre peut toujours commencer pile sur une pastille
        val r = row
        val w = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        if (r != null && w > 0 && r.paddingRight != w) r.setPadding(r.paddingLeft, r.paddingTop, w, r.paddingBottom)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if ((pending || changed) && count > 0 && width > 0) {
            pending = false
            show(anchor, false)
        }
    }

    /** Le focus arrive sur une pastille : la fenêtre glisse pour la montrer en entier. */
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val r = row
        if (r == null || r.childCount == 0 || width == 0) return super.computeScrollDeltaToGetChildRectOnScreen(rect)
        val cx = rect.centerX() - r.left
        var f = -1
        for (i in 0 until r.childCount) {
            val c = r.getChildAt(i)
            if (cx >= c.left && cx <= c.right) {
                f = i
                break
            }
        }
        if (f < 0) return 0
        anchor = f
        val s = startFor(f)
        first = s
        paint(s, end(s), true)
        return xFor(s) - scrollX
    }

    /** Au bout de la barre, on ne défile pas dans le vide : le focus part vers le voisin. */
    override fun arrowScroll(direction: Int): Boolean {
        val cur = findFocus()
        if (FocusFinder.getInstance().findNextFocus(this, if (cur === this) null else cur, direction) == null) return false
        return super.arrowScroll(direction)
    }
}
