package com.ilian.xtreamplayer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Guide TV externe au format XMLTV (programme de toutes les chaînes, même quand le fournisseur n'en donne pas).
 * - France : xmltvfr.fr (TNT, Canal+, beIN, RMC, Eurosport…)
 * - Guide complet du fournisseur (xmltv.php)
 * - Ou une adresse au choix
 * Les chaînes sont reconnues par l'identifiant de guide du fournisseur (« TF1.fr ») ou, à défaut, par leur nom.
 */
object XmlTv {
    const val OFF = 0
    const val FRANCE = 1
    const val PROVIDER = 2
    const val BOTH = 3
    const val CUSTOM = 4

    val CHOICES = listOf(
        FRANCE to "France (xmltvfr.fr)",
        BOTH to "France + guide du fournisseur",
        PROVIDER to "Guide complet du fournisseur",
        CUSTOM to "Adresse personnalisée",
        OFF to "Désactivé (guide du fournisseur seulement)"
    )

    private val FRANCE_URLS = listOf(
        "https://xmltvfr.fr/xmltv/xmltv.xml.gz",
        "https://xmltvfr.fr/xmltv/xmltv.zip",
        "https://xmltvfr.fr/xmltv/xmltv.xml"
    )

    /** On garde les programmes de 3 h en arrière à 2 jours en avant. */
    private const val BACK_SEC = 3 * 3600L
    private const val AHEAD_SEC = 48 * 3600L

    @Volatile
    var running = false
        private set

    class Result(val channels: Int, val programmes: Int)

    fun lastLabel(src: Long): String {
        val at = Db.kvGet("epg_at_$src")?.toLongOrNull() ?: return "Jamais mis à jour"
        val n = Db.kvGet("epg_n_$src") ?: "0"
        return "${Ui.dateTime(at)} · $n chaînes reconnues"
    }

    /** Au démarrage : mise à jour en arrière-plan si le guide a plus de 12 h. */
    fun refreshIfNeeded(src: Source, api: XtreamApi) {
        if (Prefs.epgSource == OFF || !Prefs.epgEnabled || running) return
        val at = Db.kvGet("epg_at_${src.id}")?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - at < 12 * 3_600_000L) return
        try {
            import(src, api) {}
        } catch (_: Exception) {
        }
    }

    /** Programmes d'une chaîne depuis le guide externe (vide si la chaîne n'y est pas). */
    fun entries(streamId: String): List<EpgEntry> {
        if (Prefs.epgSource == OFF) return emptyList()
        val src = Prefs.activeSourceId.takeIf { it > 0 } ?: return emptyList()
        return try {
            Db.epgFor(src, streamId, System.currentTimeMillis() / 1000 - 3600)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun urls(api: XtreamApi): List<List<String>> = when (Prefs.epgSource) {
        FRANCE -> listOf(FRANCE_URLS)
        PROVIDER -> listOf(listOf(api.xmltvUrl()))
        BOTH -> listOf(FRANCE_URLS, listOf(api.xmltvUrl()))
        CUSTOM -> Prefs.epgCustomUrl.trim().takeIf { it.startsWith("http") }?.let { listOf(listOf(it)) } ?: emptyList()
        else -> emptyList()
    }

    // ---------- Reconnaissance des chaînes ----------

    private val PREFIX = Regex("""^[^a-z0-9]*[a-z]{2,3}\s*[|:]\s*""")
    private val BRACKETS = Regex("""[\[(][^\])]*[\])]""")
    private val NOISE = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "x265", "raw", "vip", "backup", "low", "high",
        "60fps", "50fps", "25fps", "fps", "multi", "hdr", "dolby", "1080p", "720p", "2160p", "fr", "tv"
    )

    /** « FR| beIN SPORTS 1 FHD ᴿᴬᵂ » -> « beinsports1 » ; « Canal+ Sport » -> « canalplussport ». */
    fun key(name: String): String {
        var s = normalize(name)
        s = PREFIX.replace(s, "")
        s = BRACKETS.replace(s, " ")
        s = s.replace("+", " plus ").replace("&", " et ")
        val words = s.split(Regex("""[^a-z0-9]+""")).filter { it.isNotEmpty() && it !in NOISE }
        return words.joinToString("")
    }

    /** « TF1.fr » -> « tf1 », « CanalPlusSport.fr » -> « canalplussport ». */
    private fun idKey(id: String): String =
        normalize(id.substringBeforeLast('.').ifBlank { id }).replace("+", "plus").filter { it.isLetterOrDigit() }

    private class Stream(val id: String, val nameKey: String, val epgId: String?)

    // ---------- Import ----------

    /** Bloquant : télécharge, lit et enregistre le guide. [progress] reçoit des messages à afficher. */
    @Synchronized
    fun import(src: Source, api: XtreamApi, progress: (String) -> Unit): Result {
        running = true
        val gen = Db.epgGen(src.id) + 1
        try {
            val lists = urls(api)
            if (lists.isEmpty()) throw IOException("aucune adresse de guide")
            progress("Liste des chaînes…")
            val streams = ArrayList<Stream>()
            api.streamItems(Kind.LIVE) { streams.add(Stream(it.id, key(it.name), it.tmdb?.lowercase())) }
            val mapped = HashMap<String, String>()
            var programmes = 0
            var lastError: Exception? = null
            for ((i, candidates) in lists.withIndex()) {
                for (url in candidates) {
                    try {
                        programmes += readOne(src.id, gen, url, streams, mapped, i + 1, lists.size, progress)
                        lastError = null
                        break
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
            }
            if (mapped.isEmpty()) {
                Db.epgDrop(src.id, gen)
                throw lastError ?: IOException("aucune chaîne de ta liste n'a été trouvée dans ce guide")
            }
            Db.epgMap(src.id, gen, mapped)
            Db.epgCommit(src.id, gen)
            val channels = mapped.values.toSet().size
            Db.kvPut("epg_at_${src.id}", System.currentTimeMillis().toString())
            Db.kvPut("epg_n_${src.id}", mapped.size.toString())
            Epg.clear()
            return Result(mapped.size.coerceAtLeast(channels), programmes)
        } catch (e: Exception) {
            Db.epgDrop(src.id, gen)
            throw e
        } finally {
            running = false
        }
    }

    /** Lit un fichier XMLTV (xml, gz ou zip) et enregistre les programmes des chaînes reconnues. */
    private fun readOne(
        src: Long, gen: Int, url: String, streams: List<Stream>, mapped: HashMap<String, String>,
        step: Int, steps: Int, progress: (String) -> Unit
    ): Int {
        val tag = if (steps > 1) " ($step/$steps)" else ""
        progress("Téléchargement du guide$tag…")
        val c = Http.open(url, emptyMap(), 120_000)
        try {
            val counting = CountingStream(BufferedInputStream(c.inputStream, 64 * 1024)) { bytes ->
                progress("Téléchargement du guide$tag… ${bytes / 1_048_576} Mo")
            }
            val input = unpack(counting)
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)

            // Chaînes du fichier : identifiant -> clés de nom
            val byId = HashMap<String, String>()      // identifiant en minuscules -> identifiant
            val byIdKey = HashMap<String, String>()   // clé d'identifiant -> identifiant
            val byName = HashMap<String, String>()    // clé de nom -> identifiant
            var mappingDone = false
            val wanted = HashSet<String>()
            val batch = ArrayList<Array<Any>>(2_000)
            var count = 0
            val now = System.currentTimeMillis() / 1000
            val from = now - BACK_SEC
            val to = now + AHEAD_SEC

            var chId: String? = null
            var progCh: String? = null
            var progStart = 0L
            var progStop = 0L
            var title: String? = null
            var desc: String? = null

            // Les identifiants sont préfixés par le numéro du fichier : deux guides ne se mélangent pas
            val ns = "$step|"

            fun buildMapping() {
                mappingDone = true
                for (s in streams) {
                    if (mapped.containsKey(s.id)) continue
                    val ch = s.epgId?.let { byId[it] ?: byIdKey[idKey(it)] }
                        ?: byName[s.nameKey]?.takeIf { s.nameKey.length >= 2 }
                        ?: byIdKey[s.nameKey]?.takeIf { s.nameKey.length >= 2 }
                    if (ch != null) {
                        mapped[s.id] = ns + ch
                        wanted.add(ch)
                    }
                }
            }

            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "channel" -> chId = p.getAttributeValue(null, "id")
                        "display-name" -> {
                            val id = chId
                            if (id != null) {
                                val k = key(p.nextText())
                                if (k.isNotEmpty()) byName.putIfAbsent(k, id)
                            }
                        }
                        "programme" -> {
                            if (!mappingDone) {
                                buildMapping()
                                progress("Lecture du guide$tag… ${wanted.size} chaînes reconnues")
                            }
                            val ch = p.getAttributeValue(null, "channel")
                            progCh = if (ch != null && ch in wanted) ch else null
                            if (progCh != null) {
                                progStart = time(p.getAttributeValue(null, "start"))
                                progStop = time(p.getAttributeValue(null, "stop"))
                                if (progStop <= from || progStart >= to || progStart <= 0) progCh = null
                            }
                            title = null
                            desc = null
                        }
                        "title" -> if (progCh != null && title == null) title = p.nextText().trim()
                        "desc" -> if (progCh != null && desc == null) desc = p.nextText().trim().take(400)
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "channel" -> {
                            chId?.let { id ->
                                byId.putIfAbsent(id.lowercase(), id)
                                byIdKey.putIfAbsent(idKey(id), id)
                            }
                            chId = null
                        }
                        "programme" -> {
                            val ch = progCh
                            if (ch != null) {
                                val stop = if (progStop > progStart) progStop else progStart + 1800
                                batch.add(arrayOf(ns + ch, progStart, stop, title ?: "", desc ?: ""))
                                count++
                                if (batch.size >= 2_000) {
                                    Db.epgInsert(src, gen, batch)
                                    batch.clear()
                                    progress("Lecture du guide$tag… $count programmes")
                                }
                            }
                            progCh = null
                        }
                    }
                }
                ev = p.next()
            }
            if (!mappingDone) buildMapping()
            if (batch.isNotEmpty()) Db.epgInsert(src, gen, batch)
            return count
        } finally {
            c.disconnect()
        }
    }

    /** Détecte la compression : gzip (1F 8B), zip (PK) ou XML brut. */
    private fun unpack(raw: InputStream): InputStream {
        val b = BufferedInputStream(raw, 64 * 1024)
        b.mark(4)
        val b0 = b.read()
        val b1 = b.read()
        b.reset()
        return when {
            b0 == 0x1F && b1 == 0x8B -> BufferedInputStream(GZIPInputStream(b, 64 * 1024), 64 * 1024)
            b0 == 'P'.code && b1 == 'K'.code -> {
                val z = ZipInputStream(b)
                var e = z.nextEntry
                while (e != null && (e.isDirectory || !e.name.endsWith(".xml", ignoreCase = true))) e = z.nextEntry
                if (e == null) throw IOException("archive sans fichier XML")
                BufferedInputStream(z, 64 * 1024)
            }
            else -> b
        }
    }

    /** « 20261029203500 +0100 » -> secondes. */
    fun time(s: String?): Long {
        if (s == null || s.length < 12) return 0
        return try {
            val y = s.substring(0, 4).toInt()
            val mo = s.substring(4, 6).toInt()
            val d = s.substring(6, 8).toInt()
            val h = s.substring(8, 10).toInt()
            val mi = s.substring(10, 12).toInt()
            val se = if (s.length >= 14 && s[12].isDigit()) s.substring(12, 14).toInt() else 0
            val tz = s.substringAfter(' ', "").trim()
            val offset = if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
                val sign = if (tz[0] == '-') -1 else 1
                ZoneOffset.ofHoursMinutes(sign * tz.substring(1, 3).toInt(), sign * tz.substring(3, 5).toInt())
            } else ZoneOffset.UTC
            LocalDateTime.of(y, mo, d, h, mi, se).toEpochSecond(offset)
        } catch (_: Exception) {
            0
        }
    }

    /** Compte les octets lus pour afficher l'avancement du téléchargement (au plus deux fois par seconde). */
    private class CountingStream(private val inner: InputStream, private val onBytes: (Long) -> Unit) : InputStream() {
        private var n = 0L
        private var last = 0L

        private fun tick(k: Int) {
            if (k <= 0) return
            n += k
            val now = System.currentTimeMillis()
            if (now - last > 500) {
                last = now
                onBytes(n)
            }
        }

        override fun read(): Int = inner.read().also { if (it >= 0) tick(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { tick(it) }
        override fun available(): Int = inner.available()
        override fun close() = inner.close()
    }
}
