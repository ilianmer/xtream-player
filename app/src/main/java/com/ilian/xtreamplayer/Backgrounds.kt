package com.ilian.xtreamplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import java.util.concurrent.Executors

/**
 * Fonds d'écran (dégradés superposés, motifs) pré-dessinés une fois dans une image identique :
 * à chaque image affichée, la carte graphique n'a plus qu'une texture à poser au lieu de recalculer
 * 3 ou 4 dégradés plein écran. Le rendu est le même, en plus léger.
 */
object Backgrounds {
    private val cache = object : LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 3
    }
    private val worker = Pools.single("fonds")

    /** Pose le fond [resId] : tout de suite tel quel, puis sa version pré-dessinée dès qu'elle est prête. */
    fun set(v: View, resId: Int) {
        val prev = v.getTag(R.id.bg_res)
        v.setTag(R.id.bg_res, resId)
        val w = v.width
        val h = v.height
        if (w <= 0 || h <= 0) {
            v.setBackgroundResource(resId)
            v.post { if (v.getTag(R.id.bg_res) == resId && v.width > 0 && v.height > 0) set(v, resId) }
            return
        }
        val key = "$resId:$w:$h"
        synchronized(cache) { cache[key] }?.let {
            v.background = BitmapDrawable(v.resources, it)
            return
        }
        if (prev != resId || v.background !is BitmapDrawable) v.setBackgroundResource(resId)
        val ctx = v.context.applicationContext
        worker.execute {
            val bmp = render(ctx.getDrawable(resId), w, h) ?: return@execute
            synchronized(cache) { cache[key] = bmp }
            Bg.main.post {
                if (v.getTag(R.id.bg_res) == resId && v.width == w && v.height == h) v.background = BitmapDrawable(v.resources, bmp)
            }
        }
    }

    /** Fond d'origine d'un écran (posé par la mise en page) remplacé par sa version pré-dessinée. */
    fun bake(v: View) {
        val bg = v.background ?: return
        if (bg is BitmapDrawable || bg is ColorDrawable) return
        val w = v.width
        val h = v.height
        if (w <= 0 || h <= 0) {
            v.post { if (v.width > 0 && v.height > 0) bake(v) }
            return
        }
        val copy = bg.constantState?.newDrawable(v.resources) ?: return
        worker.execute {
            val bmp = render(copy, w, h) ?: return@execute
            Bg.main.post {
                // Seulement si personne n'a changé le fond entre-temps
                if (v.background === bg && v.width == w && v.height == h) v.background = BitmapDrawable(v.resources, bmp)
            }
        }
    }

    private fun render(d: Drawable?, w: Int, h: Int): Bitmap? = try {
        d ?: throw IllegalStateException()
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(b))
        }
    } catch (_: Throwable) {
        null
    }

    fun trim() {
        synchronized(cache) { cache.clear() }
    }
}
