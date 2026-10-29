package com.ilian.xtreamplayer

import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private fun smallPool(queue: Int, threads: Int = 2) = ThreadPoolExecutor(
    threads, threads, 30, TimeUnit.SECONDS, LinkedBlockingQueue(queue), ThreadPoolExecutor.DiscardOldestPolicy()
)

/** Guide TV : cache du programme en cours, chargé à la demande ou en avance (préchargement). */
object Epg {
    private class Entry(val at: Long, val list: List<EpgEntry>)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val pool = smallPool(80, 4)

    fun cached(id: String): List<EpgEntry>? {
        val e = cache[id] ?: return null
        val now = System.currentTimeMillis()
        if (now - e.at > 20 * 60_000) return null
        // Si le programme en cache est déjà terminé, on recharge.
        val first = e.list.firstOrNull()
        if (first != null && first.end > 0 && first.end * 1000 < now && e.list.size < 2) return null
        return e.list
    }

    /** Bloquant. */
    fun load(api: XtreamApi, id: String): List<EpgEntry> {
        cached(id)?.let { return it }
        val list = try {
            api.shortEpg(id)
        } catch (_: Exception) {
            emptyList()
        }
        cache[id] = Entry(System.currentTimeMillis(), list)
        return list
    }

    fun prefetch(api: XtreamApi, id: String, done: ((List<EpgEntry>) -> Unit)? = null) {
        cached(id)?.let { l ->
            done?.invoke(l)
            return
        }
        pool.execute {
            val l = load(api, id)
            if (done != null) Bg.main.post { done(l) }
        }
    }

    fun nowAndNext(list: List<EpgEntry>): Pair<EpgEntry?, EpgEntry?> {
        val t = System.currentTimeMillis() / 1000
        val i = list.indexOfFirst { it.start <= t && t < it.end }
        return if (i >= 0) list[i] to list.getOrNull(i + 1) else list.firstOrNull() to list.getOrNull(1)
    }

    fun line(e: EpgEntry): String {
        val f = SimpleDateFormat("HH:mm", Locale.FRANCE)
        return if (e.start > 0 && e.end > 0)
            "${f.format(Date(e.start * 1000))}–${f.format(Date(e.end * 1000))}  ${e.title}"
        else e.title
    }
}

/** Enrichissement via TMDB : affiches et résumés manquants. Nécessite une clé API gratuite. */
object Tmdb {
    data class Info(
        val poster: String?,
        val backdrop: String?,
        val overview: String?,
        val year: String?,
        val rating: String?,
        val genres: String?,
        val runtime: Int?
    )

    private const val IMG = "https://image.tmdb.org/t/p/"
    private const val MONTH = 30L * 86_400_000L
    private val pool = smallPool(40)
    private val posters = ConcurrentHashMap<String, String>()

    val enabled: Boolean get() = Prefs.tmdbKey.isNotBlank()

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val key = Prefs.tmdbKey.trim()
        val sb = StringBuilder("https://api.themoviedb.org/3$path?language=fr-FR")
        for ((k, v) in params) sb.append('&').append(k).append('=').append(URLEncoder.encode(v, "UTF-8"))
        val headers = HashMap<String, String>()
        headers["Accept"] = "application/json"
        // Jeton de lecture (v4, commence par eyJ) ou clé API classique (v3)
        if (key.startsWith("eyJ")) headers["Authorization"] = "Bearer $key"
        else sb.append("&api_key=").append(URLEncoder.encode(key, "UTF-8"))
        return JSONObject(Http.getText(sb.toString(), headers))
    }

    /** Vérifie la clé. Renvoie null si elle fonctionne, sinon le message d'erreur. */
    fun test(): String? = try {
        get("/configuration")
        null
    } catch (e: Exception) {
        Bg.err(e)
    }

    /** Fiche TMDB d'un film ou d'une série. Bloquant, avec cache local de 30 jours. */
    fun info(item: Item): Info? {
        if (!enabled) return null
        val tv = item.kind == Kind.SERIES
        val type = if (tv) "tv" else "movie"
        val id = item.tmdb?.takeIf { it.isNotBlank() && it != "0" }
        val cacheKey = if (id != null) "$type:$id" else "$type:q:${normalize(item.name)}"
        Db.tmdbGet(cacheKey)?.let { (json, at) ->
            if (System.currentTimeMillis() - at < MONTH) return parse(JSONObject(json), tv)
        }
        var obj: JSONObject? = null
        if (id != null) obj = try {
            get("/$type/$id")
        } catch (_: Exception) {
            null
        }
        if (obj == null) obj = try {
            search(item.name, tv)
        } catch (_: Exception) {
            null
        }
        Db.tmdbPut(cacheKey, (obj ?: JSONObject()).toString())
        return obj?.let { parse(it, tv) }
    }

    /** Identifiants TMDB des films ou séries les plus vus de la semaine (cache de 12 h). Bloquant. */
    fun trending(tv: Boolean): List<String> {
        if (!enabled) return emptyList()
        val key = "trending:" + (if (tv) "tv" else "movie")
        Db.tmdbGet(key)?.let { (json, at) ->
            if (System.currentTimeMillis() - at < 12 * 3_600_000L) return idsOf(json)
        }
        val ids = ArrayList<String>()
        for (page in 1..2) {
            val res = get("/trending/${if (tv) "tv" else "movie"}/week", mapOf("page" to page.toString())).optJSONArray("results") ?: break
            for (i in 0 until res.length()) res.optJSONObject(i)?.optInt("id", 0)?.takeIf { it > 0 }?.let { ids.add(it.toString()) }
        }
        Db.tmdbPut(key, org.json.JSONArray(ids).toString())
        return ids
    }

    private fun idsOf(json: String): List<String> = try {
        val a = org.json.JSONArray(json)
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    /** Charge l'affiche en arrière-plan quand le fournisseur n'en a pas. */
    fun posterAsync(item: Item, done: (String) -> Unit) {
        if (!enabled) return
        posters[item.key]?.let {
            if (it.isNotEmpty()) done(it)
            return
        }
        pool.execute {
            val url = try {
                info(item)?.poster
            } catch (_: Exception) {
                null
            } ?: ""
            posters[item.key] = url
            if (url.isNotEmpty()) Bg.main.post { done(url) }
        }
    }

    private fun search(name: String, tv: Boolean): JSONObject? {
        val (title, year) = cleanTitle(name)
        val type = if (tv) "tv" else "movie"
        val p = HashMap<String, String>()
        p["query"] = title
        if (year != null) p[if (tv) "first_air_date_year" else "year"] = year
        var res = get("/search/$type", p).optJSONArray("results")
        if ((res == null || res.length() == 0) && year != null) {
            p.remove(if (tv) "first_air_date_year" else "year")
            res = get("/search/$type", p).optJSONArray("results")
        }
        val first = res?.optJSONObject(0) ?: return null
        val id = first.optInt("id", 0)
        if (id == 0) return null
        return try {
            get("/$type/$id")
        } catch (_: Exception) {
            first
        }
    }

    // Préfixes de langue ou de fournisseur : « FR - », « -FR- - », « [FR] », « |FR| », « (FR) », « FR: », tirets restants
    private val PREFIX = Regex("""^\s*(\[[^\]]*]|\|[^|]*\||-\s*[A-Za-z]{2,3}\s*-|\([A-Za-z]{2,3}\)|[A-Za-z]{2,3}\s*[-:|]\s+|[A-Za-z]{2,3}\|\s*|[A-Za-z]{2}:(?=\S)|[-–|:•★*]+\s*)""")
    private val YEAR = Regex("""\b(19|20)\d{2}\b""")
    private val NOISE = Regex("""(?i)\b(4k|uhd|fhd|hd|sd|vf|vff|vfq|vostfr|vo|multi|truefrench|french|hevc|x265|x264|1080p|720p|2160p|hdr)\b""")

    /** "FR - Le Film (2021) 4K" -> ("Le Film", "2021") */
    fun cleanTitle(raw: String): Pair<String, String?> {
        var s = raw
        repeat(4) { s = s.replace(PREFIX, "") }
        val year = YEAR.findAll(s).lastOrNull()?.value
        if (year != null) s = s.replace(year, " ")
        s = NOISE.replace(s, " ")
        s = s.replace(Regex("""[\[\]()|_.]+"""), " ").replace(Regex("""\s{2,}"""), " ").trim().trimEnd('-', ':').trim()
        return (s.ifBlank { raw.trim() }) to year
    }

    private fun parse(o: JSONObject, tv: Boolean): Info? {
        if (o.length() == 0) return null
        val genres = o.optJSONArray("genres")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.str("name") }.joinToString(", ")
        }?.ifBlank { null }
        val date = o.str(if (tv) "first_air_date" else "release_date")
        val vote = o.optDouble("vote_average", 0.0)
        val runtime = if (tv) {
            o.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }
        } else {
            o.optInt("runtime", 0).takeIf { it > 0 }
        }
        return Info(
            poster = o.str("poster_path")?.let { IMG + "w342" + it },
            backdrop = o.str("backdrop_path")?.let { IMG + "w780" + it },
            overview = o.str("overview"),
            year = date?.take(4),
            rating = if (vote > 0) String.format(Locale.FRANCE, "%.1f", vote) else null,
            genres = genres,
            runtime = runtime
        )
    }
}
