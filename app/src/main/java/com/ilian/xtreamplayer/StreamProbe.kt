package com.ilian.xtreamplayer

import android.os.SystemClock
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Test express d'un flux en direct, avant de le lancer (comme le ferait une box qui choisit sa source) :
 * temps de réponse du serveur, flux vraiment vidéo, et surtout vitesse d'arrivée comparée au temps réel.
 *
 * - Flux TS : on lit ~1,5 s de données et on mesure, grâce à l'horloge du flux (PCR), combien de
 *   secondes de vidéo sont arrivées pendant ce temps. Au-dessus de 1, le flux arrive plus vite que
 *   la lecture : il sera fluide. En dessous, il saccadera.
 * - Flux HLS : on télécharge le dernier segment et on compare sa durée au temps de téléchargement.
 *
 * Les tests se font l'un après l'autre (beaucoup d'abonnements n'autorisent qu'une connexion à la fois).
 */
object StreamProbe {
    class Result(val item: Item, val ok: Boolean, val ttfbMs: Long, val speed: Float) {
        /** Note de fluidité : vitesse d'arrivée (plafonnée), un peu pénalisée par un serveur lent à répondre. */
        val grade: Float get() = if (!ok) -1f else speed.coerceAtMost(4f) * 10f - ttfbMs / 400f
        /** Assez d'avance pour ne pas hésiter à lancer cette chaîne tout de suite. */
        val comfortable: Boolean get() = ok && speed >= 1.3f && ttfbMs < 2_500L
    }

    private const val TS = 188

    /** Bloquant : teste [item] (jamais plus de ~4 s). */
    fun probe(api: XtreamApi, item: Item, format: String = Prefs.liveFormat): Result {
        val url = api.streamUrl(item, format)
        return try {
            if (format == "m3u8" || url.contains(".m3u8")) probeHls(item, url) else probeTs(item, url)
        } catch (_: Exception) {
            Result(item, false, 0L, 0f)
        }
    }

    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(5) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 2_500
            c.readTimeout = 2_500
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", Prefs.userAgent)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) throw java.io.IOException("redirection")
                current = URL(URL(current), loc).toString()
                return@repeat
            }
            if (code >= 400) {
                c.disconnect()
                throw java.io.IOException("http $code")
            }
            return c
        }
        throw java.io.IOException("redirections")
    }

    // ---------- TS ----------

    private fun probeTs(item: Item, url: String): Result {
        val t0 = SystemClock.elapsedRealtime()
        val c = open(url)
        try {
            val input = c.inputStream
            val buf = ByteArray(4 * 1024 * 1024)
            var n = 0
            var first = 0L
            val budget = 1_500L
            while (n < buf.size) {
                val r = input.read(buf, n, minOf(64 * 1024, buf.size - n))
                if (r < 0) break
                if (first == 0L) first = SystemClock.elapsedRealtime()
                n += r
                if (SystemClock.elapsedRealtime() - first >= budget) break
            }
            val end = SystemClock.elapsedRealtime()
            closeQuietly(input)
            if (first == 0L || n < TS * 20) return Result(item, false, end - t0, 0f)
            val ttfb = first - t0
            val wall = ((end - first).coerceAtLeast(200L)) / 1000f
            val sync = syncOffset(buf, n)
            if (sync < 0) return Result(item, false, ttfb, 0f)
            val media = pcrSpan(buf, n, sync)
            val speed = if (media > 0f) media / wall else {
                // Pas d'horloge lisible : on juge sur le débit (≈ 1,2 Mbit/s minimum pour une image correcte)
                if (n / wall >= 150_000f) 1f else 0.5f
            }
            return Result(item, speed >= 0.9f, ttfb, speed)
        } finally {
            c.disconnect()
        }
    }

    /** Début des paquets TS (octet 0x47 tous les 188 octets). */
    private fun syncOffset(b: ByteArray, n: Int): Int {
        for (o in 0 until minOf(TS, n - TS * 3)) {
            if (b[o] == 0x47.toByte() && b[o + TS] == 0x47.toByte() && b[o + 2 * TS] == 0x47.toByte()) return o
        }
        return -1
    }

    /** Secondes de vidéo contenues dans les données (écart entre la première et la dernière horloge PCR). */
    private fun pcrSpan(b: ByteArray, n: Int, start: Int): Float {
        val firstPcr = HashMap<Int, Long>()
        val lastPcr = HashMap<Int, Long>()
        val count = HashMap<Int, Int>()
        var o = start
        while (o + TS <= n) {
            if (b[o] != 0x47.toByte()) {
                o++
                continue
            }
            val pid = ((b[o + 1].toInt() and 0x1F) shl 8) or (b[o + 2].toInt() and 0xFF)
            val afc = (b[o + 3].toInt() shr 4) and 3
            if ((afc == 2 || afc == 3) && (b[o + 4].toInt() and 0xFF) >= 7 && (b[o + 5].toInt() and 0x10) != 0) {
                val base = ((b[o + 6].toLong() and 0xFF) shl 25) or
                    ((b[o + 7].toLong() and 0xFF) shl 17) or
                    ((b[o + 8].toLong() and 0xFF) shl 9) or
                    ((b[o + 9].toLong() and 0xFF) shl 1) or
                    ((b[o + 10].toLong() and 0xFF) shr 7)
                if (pid !in firstPcr) firstPcr[pid] = base
                lastPcr[pid] = base
                count[pid] = (count[pid] ?: 0) + 1
            }
            o += TS
        }
        val pid = count.maxByOrNull { it.value }?.key ?: return 0f
        if ((count[pid] ?: 0) < 3) return 0f
        val d = (lastPcr[pid] ?: 0L) - (firstPcr[pid] ?: 0L)
        // Horloge sur 33 bits, à 90 kHz ; un saut (boucle ou discontinuité) rend la mesure inutilisable
        if (d <= 0L || d > 90_000L * 120) return 0f
        return d / 90_000f
    }

    // ---------- HLS ----------

    private val EXTINF = Regex("""#EXTINF:([0-9.]+)""")

    private fun probeHls(item: Item, url: String): Result {
        val t0 = SystemClock.elapsedRealtime()
        var (base, text) = fetchText(url)
        val ttfb = SystemClock.elapsedRealtime() - t0
        if (!text.contains("#EXTM3U")) return Result(item, false, ttfb, 0f)
        if (text.contains("#EXT-X-STREAM-INF")) {
            // Liste de qualités : on teste la première (celle que le lecteur prendra au départ)
            val lines = text.lines()
            val i = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return Result(item, false, ttfb, 0f)
            val r = fetchText(URL(URL(base), uri.trim()).toString())
            base = r.first
            text = r.second
        }
        val lines = text.lines()
        var dur = 0f
        var seg: String? = null
        for ((i, l) in lines.withIndex()) {
            val m = EXTINF.find(l) ?: continue
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
            dur = m.groupValues[1].toFloatOrNull() ?: continue
            seg = uri.trim()
        }
        if (seg == null || dur <= 0f) return Result(item, false, ttfb, 0f)
        val s0 = SystemClock.elapsedRealtime()
        val c = open(URL(URL(base), seg).toString())
        var n = 0L
        try {
            val input = c.inputStream
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                n += r
                if (SystemClock.elapsedRealtime() - s0 > 4_000L) break
            }
            closeQuietly(input)
        } finally {
            c.disconnect()
        }
        val sec = ((SystemClock.elapsedRealtime() - s0).coerceAtLeast(50L)) / 1000f
        val speed = if (n > 1_000) dur / sec else 0f
        return Result(item, speed >= 1.1f, ttfb, speed)
    }

    private fun fetchText(url: String): Pair<String, String> {
        val c = open(url)
        try {
            val text = c.inputStream.use { it.readBytes().decodeToString() }
            return c.url.toString() to text
        } finally {
            c.disconnect()
        }
    }

    private fun closeQuietly(s: InputStream) {
        try {
            s.close()
        } catch (_: Exception) {
        }
    }
}
