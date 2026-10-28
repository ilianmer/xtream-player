package com.ilian.xtreamplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/** Chargeur d'images très simple avec cache mémoire (logos, affiches). */
object Img {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4)

    fun load(iv: ImageView, url: String?, placeholder: Int, maxPx: Int = 360) {
        iv.tag = url
        if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) {
            iv.setImageResource(placeholder)
            return
        }
        val hit = cache.get(url)
        if (hit != null) {
            iv.setImageBitmap(hit)
            return
        }
        iv.setImageResource(placeholder)
        pool.execute {
            // La vue a peut-être été recyclée entre-temps : inutile de télécharger.
            if (iv.tag != url) return@execute
            val bmp = try {
                decode(Http.getBytes(url), maxPx)
            } catch (_: Throwable) {
                null
            }
            if (bmp != null) {
                cache.put(url, bmp)
                Bg.main.post { if (iv.tag == url) iv.setImageBitmap(bmp) }
            }
        }
    }

    private fun decode(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
