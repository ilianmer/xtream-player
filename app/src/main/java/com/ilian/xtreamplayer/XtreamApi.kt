package com.ilian.xtreamplayer

import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.URLEncoder

/** Client de l'API Xtream Codes (player_api.php). */
class XtreamApi(server: String, val user: String, val pass: String) {

    val base: String = normalizeServer(server)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun url(action: String?, params: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder("$base/player_api.php?username=${enc(user)}&password=${enc(pass)}")
        if (action != null) sb.append("&action=").append(action)
        for ((k, v) in params) sb.append('&').append(k).append('=').append(enc(v))
        return sb.toString()
    }

    private fun call(action: String?, params: Map<String, String> = emptyMap()): String = Http.getText(url(action, params))

    /** Infos du compte : statut, expiration, écrans autorisés et connexions en cours. */
    fun account(): AccountInfo {
        val o = JSONObject(call(null))
        val ui = o.optJSONObject("user_info") ?: throw IOException("Réponse inattendue du serveur")
        return AccountInfo(
            auth = ui.optInt("auth", 0) == 1,
            status = ui.str("status"),
            expDate = ui.str("exp_date")?.toLongOrNull(),
            maxConn = ui.optInt("max_connections", 0),
            activeCons = ui.optInt("active_cons", 0)
        )
    }

    fun categories(kind: Kind): List<Category> {
        val action = when (kind) {
            Kind.LIVE -> "get_live_categories"
            Kind.MOVIE -> "get_vod_categories"
            else -> "get_series_categories"
        }
        val txt = call(action).trim()
        if (!txt.startsWith("[")) return emptyList()
        val arr = JSONArray(txt)
        val out = ArrayList<Category>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.str("category_id") ?: continue
            out.add(Category(id, o.str("category_name") ?: "Sans nom"))
        }
        return out
    }

    /**
     * Lit la liste complète d'un type en flux, élément par élément, sans tout charger en mémoire
     * (indispensable avec des centaines de milliers de films sur un stick à 1 Go de RAM).
     */
    fun streamItems(kind: Kind, onItem: (Item) -> Unit) {
        val action = when (kind) {
            Kind.LIVE -> "get_live_streams"
            Kind.MOVIE -> "get_vod_streams"
            else -> "get_series"
        }
        val c = Http.open(url(action), emptyMap(), 120_000)
        try {
            JsonReader(InputStreamReader(BufferedInputStream(c.inputStream, 64 * 1024), Charsets.UTF_8)).use { r ->
                r.isLenient = true
                if (r.peek() != JsonToken.BEGIN_ARRAY) {
                    r.skipValue()
                    return
                }
                r.beginArray()
                val m = HashMap<String, String>()
                while (r.hasNext()) {
                    if (r.peek() != JsonToken.BEGIN_OBJECT) {
                        r.skipValue()
                        continue
                    }
                    m.clear()
                    r.beginObject()
                    while (r.hasNext()) {
                        val k = r.nextName()
                        if (k in WANTED) {
                            val v = scalar(r)
                            if (v != null) m[k] = v
                        } else {
                            r.skipValue()
                        }
                    }
                    r.endObject()
                    toItem(kind, m)?.let(onItem)
                }
                r.endArray()
            }
        } finally {
            c.disconnect()
        }
    }

    private fun scalar(r: JsonReader): String? = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString().trim().ifBlank { null }
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        JsonToken.NULL -> {
            r.nextNull()
            null
        }
        else -> {
            r.skipValue()
            null
        }
    }

    private fun toItem(kind: Kind, m: Map<String, String>): Item? = when (kind) {
        Kind.LIVE -> m["stream_id"]?.let {
            Item(Kind.LIVE, it, m["name"] ?: "?", m["stream_icon"], null, catId = m["category_id"])
        }
        Kind.MOVIE -> m["stream_id"]?.let {
            Item(
                Kind.MOVIE, it, m["name"] ?: "?", m["stream_icon"], m["container_extension"] ?: "mp4",
                catId = m["category_id"], rating = m["rating"], tmdb = m["tmdb"] ?: m["tmdb_id"]
            )
        }
        else -> m["series_id"]?.let {
            Item(
                Kind.SERIES, it, m["name"] ?: "?", m["cover"], null, plot = m["plot"],
                catId = m["category_id"], rating = m["rating"], tmdb = m["tmdb"] ?: m["tmdb_id"]
            )
        }
    }

    fun movieInfo(id: String): MovieInfo {
        val o = JSONObject(call("get_vod_info", mapOf("vod_id" to id)))
        val i = o.optJSONObject("info")
        return MovieInfo(
            plot = i?.str("plot") ?: i?.str("description"),
            genre = i?.str("genre"),
            duration = i?.str("duration"),
            year = i?.str("releasedate")?.take(4) ?: i?.str("year"),
            rating = i?.str("rating"),
            cover = i?.str("movie_image") ?: i?.str("cover_big"),
            tmdb = i?.str("tmdb_id"),
            director = i?.str("director"),
            cast = i?.str("cast") ?: i?.str("actors"),
            backdrop = backdrop(i)
        )
    }

    fun seriesInfo(seriesId: String): SeriesInfo {
        val o = JSONObject(call("get_series_info", mapOf("series_id" to seriesId)))
        val info = o.optJSONObject("info")
        val cover = info?.str("cover")
        val bySeason = sortedMapOf<Int, MutableList<Pair<Int, Item>>>()

        fun addEpisode(seasonHint: Int, e: JSONObject) {
            val id = e.str("id") ?: return
            val num = e.optInt("episode_num", 0)
            val season = e.optInt("season", seasonHint)
            val ei = e.optJSONObject("info")
            val duration = ei?.str("duration")
            val extra = buildString {
                append("Saison ").append(season).append(" · Épisode ").append(num)
                if (duration != null) append(" · ").append(duration)
            }
            val item = Item(
                Kind.EPISODE, id, e.str("title") ?: "Épisode $num",
                ei?.str("movie_image") ?: cover,
                e.str("container_extension") ?: "mp4",
                ei?.str("plot"), extra
            )
            bySeason.getOrPut(season) { mutableListOf() }.add(num to item)
        }

        when (val eps = o.opt("episodes")) {
            is JSONObject -> {
                val keys = eps.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val a = eps.optJSONArray(k) ?: continue
                    for (i in 0 until a.length()) a.optJSONObject(i)?.let { addEpisode(k.toIntOrNull() ?: 0, it) }
                }
            }
            is JSONArray -> {
                for (i in 0 until eps.length()) {
                    val a = eps.optJSONArray(i) ?: continue
                    for (j in 0 until a.length()) a.optJSONObject(j)?.let { addEpisode(i + 1, it) }
                }
            }
        }

        val seasons = bySeason.map { (n, list) -> Season(n, list.sortedBy { it.first }.map { it.second }) }
        return SeriesInfo(
            info?.str("name") ?: "", cover, info?.str("plot"), info?.str("tmdb") ?: info?.str("tmdb_id"), seasons,
            backdrop = backdrop(info),
            genre = info?.str("genre"),
            year = (info?.str("releaseDate") ?: info?.str("release_date") ?: info?.str("year"))?.take(4),
            rating = info?.str("rating")?.takeIf { it != "0" },
            cast = info?.str("cast")
        )
    }

    /** Image de fond : souvent une liste d'adresses, parfois une seule chaîne. */
    private fun backdrop(o: JSONObject?): String? {
        if (o == null) return null
        return when (val b = o.opt("backdrop_path")) {
            is JSONArray -> (0 until b.length()).mapNotNull { b.optString(it).trim().ifBlank { null } }.firstOrNull { it.startsWith("http") }
            is String -> b.trim().takeIf { it.startsWith("http") }
            else -> null
        }
    }

    /** Programme en cours et suivant d'une chaîne (titres encodés en base64 par l'API). */
    fun shortEpg(streamId: String): List<EpgEntry> {
        val o = JSONObject(call("get_short_epg", mapOf("stream_id" to streamId, "limit" to "3")))
        val arr = o.optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<EpgEntry>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            out.add(EpgEntry(b64(e.str("title") ?: ""), e.optLong("start_timestamp", 0), e.optLong("stop_timestamp", 0)))
        }
        return out
    }

    fun streamUrl(item: Item, liveFormat: String): String {
        item.url?.let { return it }
        val u = Uri.encode(user)
        val p = Uri.encode(pass)
        return when (item.kind) {
            Kind.LIVE -> "$base/live/$u/$p/${item.id}.$liveFormat"
            Kind.MOVIE -> "$base/movie/$u/$p/${item.id}.${item.ext ?: "mp4"}"
            Kind.EPISODE -> "$base/series/$u/$p/${item.id}.${item.ext ?: "mp4"}"
            Kind.SERIES -> ""
        }
    }

    private fun b64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (_: Exception) {
        s
    }

    companion object {
        private val WANTED = setOf(
            "stream_id", "series_id", "name", "stream_icon", "cover", "category_id",
            "container_extension", "rating", "tmdb", "tmdb_id", "plot"
        )

        /** Garde uniquement schéma + hôte + port : "exemple.com:8080/" -> "http://exemple.com:8080". */
        fun normalizeServer(input: String): String {
            var t = input.trim()
            if (!t.startsWith("http://", true) && !t.startsWith("https://", true)) t = "http://$t"
            val uri = Uri.parse(t)
            val host = uri.host
            require(!host.isNullOrBlank()) { "Adresse du serveur invalide" }
            val port = if (uri.port > 0) ":${uri.port}" else ""
            return "${uri.scheme?.lowercase()}://$host$port"
        }

        /** Accepte un lien M3U complet : http://serveur:port/get.php?username=X&password=Y&... */
        fun parseM3uLink(input: String): Triple<String, String, String>? {
            val s = input.trim()
            if (!s.contains("username=")) return null
            val t = if (s.startsWith("http", true)) s else "http://$s"
            val uri = Uri.parse(t)
            val u = uri.getQueryParameter("username") ?: return null
            val p = uri.getQueryParameter("password") ?: return null
            return Triple(normalizeServer(t), u, p)
        }
    }
}
