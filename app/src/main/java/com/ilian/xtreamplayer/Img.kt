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

    /** Télécharge une image (avec cache) et la rend sur le fil principal, ou null en cas d'échec. */
    fun fetch(url: String?, maxPx: Int, cb: (Bitmap?) -> Unit) {
        if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) {
            cb(null)
            return
        }
        val key = "$maxPx|$url"
        cache.get(key)?.let {
            cb(it)
            return
        }
        pool.execute {
            val bmp = try {
                decode(Http.getBytes(url), maxPx)
            } catch (_: Throwable) {
                null
            }
            if (bmp != null) cache.put(key, bmp)
            Bg.main.post { cb(bmp) }
        }
    }

    /** Fond flouté : l'image est réduite puis floutée, l'agrandissement lisse le reste. */
    fun blurred(iv: ImageView, url: String?, onDone: ((Boolean) -> Unit)? = null) {
        iv.tag = "blur|$url"
        if (url.isNullOrBlank()) {
            onDone?.invoke(false)
            return
        }
        val key = "blur|$url"
        cache.get(key)?.let {
            iv.setImageBitmap(it)
            onDone?.invoke(true)
            return
        }
        pool.execute {
            val bmp = try {
                decode(Http.getBytes(url), 200)?.let { Blur.apply(it, 72, 3) }
            } catch (_: Throwable) {
                null
            }
            if (bmp != null) cache.put(key, bmp)
            Bg.main.post {
                if (iv.tag == key && bmp != null) iv.setImageBitmap(bmp)
                onDone?.invoke(bmp != null)
            }
        }
    }

    /** Prépare en avance le fond flouté d'une image (pendant l'animation d'ouverture). Bloquant. */
    fun warmBlur(url: String?) {
        if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) return
        val key = "blur|$url"
        if (cache.get(key) != null) return
        try {
            decode(Http.getBytes(url), 200)?.let { Blur.apply(it, 72, 3) }?.let { cache.put(key, it) }
        } catch (_: Throwable) {
        }
    }

    /** Télécharge et garde en mémoire une image (pendant l'animation d'ouverture). Bloquant. */
    fun warm(url: String?, maxPx: Int) {
        if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) return
        val key = "$maxPx|$url"
        if (cache.get(key) != null) return
        try {
            decode(Http.getBytes(url), maxPx)?.let { cache.put(key, it) }
        } catch (_: Throwable) {
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
