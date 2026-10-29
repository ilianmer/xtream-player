package com.ilian.xtreamplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Chargeur d'images très simple avec cache mémoire (logos, affiches). */
object Img {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Pools.fixed(4, "images")

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
                decode(bytes(url), maxPx)
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
                decode(bytes(url), maxPx)
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
                decode(bytes(url), 200)?.let { Blur.apply(it, 72, 3) }
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
            decode(bytes(url), 200)?.let { Blur.apply(it, 72, 3) }?.let { cache.put(key, it) }
        } catch (_: Throwable) {
        }
    }

    /** Télécharge et garde en mémoire une image (pendant l'animation d'ouverture). Bloquant. */
    fun warm(url: String?, maxPx: Int) {
        if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) return
        val key = "$maxPx|$url"
        if (cache.get(key) != null) return
        try {
            decode(bytes(url), maxPx)?.let { cache.put(key, it) }
        } catch (_: Throwable) {
        }
    }

    // ---------- Cache disque ----------
    // Les affiches et logos déjà vus sont gardés sur le stick : l'appli ne les retélécharge pas à chaque ouverture.

    @Volatile
    private var dir: File? = null
    private const val DISK_MAX = 160L * 1024 * 1024
    private const val DISK_TARGET = 120L * 1024 * 1024

    fun init(ctx: Context) {
        dir = File(ctx.cacheDir, "img").apply { mkdirs() }
        // Ménage en arrière-plan : on garde les images les plus récemment utilisées
        Thread {
            try {
                val files = dir?.listFiles() ?: return@Thread
                var total = files.sumOf { it.length() }
                if (total <= DISK_MAX) return@Thread
                for (f in files.sortedBy { it.lastModified() }) {
                    if (total <= DISK_TARGET) break
                    total -= f.length()
                    f.delete()
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    private fun fileFor(url: String): File? {
        val d = dir ?: return null
        val md = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val name = StringBuilder(40)
        for (b in md) name.append(String.format("%02x", b))
        return File(d, name.toString())
    }

    /** Octets d'une image : depuis le stick si on l'a déjà, sinon téléchargée puis gardée. */
    private fun bytes(url: String): ByteArray {
        val f = fileFor(url)
        if (f != null && f.isFile && f.length() > 0) {
            try {
                val b = f.readBytes()
                f.setLastModified(System.currentTimeMillis())
                return b
            } catch (_: Exception) {
            }
        }
        val b = Http.getBytes(url)
        if (f != null && b.size in 64..4_000_000) {
            try {
                val tmp = File(f.path + ".tmp")
                tmp.writeBytes(b)
                tmp.renameTo(f)
            } catch (_: Exception) {
            }
        }
        return b
    }

    /** Android manque de mémoire : on libère les images gardées en mémoire (elles restent sur le disque). */
    fun trim(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level == android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) cache.evictAll()
        else cache.trimToSize(cache.maxSize() / 2)
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
