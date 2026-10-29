package com.ilian.xtreamplayer

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Infos complémentaires sans clé ni compte, quand le fournisseur et TMDB n'ont rien :
 * - TVmaze pour les séries (affiche, image de fond, résumé, note) ;
 * - iTunes (catalogue français) pour les films (affiche, résumé, genre).
 * Réponses gardées 30 jours dans la base locale.
 */
object FreeMeta {
    data class Info(val poster: String?, val backdrop: String?, val plot: String?, val genre: String?, val rating: String?, val year: String?)

    private const val MONTH = 30L * 86_400_000L

    private fun cached(key: String, fetch: () -> JSONObject?): JSONObject? {
        Db.tmdbGet(key)?.let { (json, at) ->
            if (System.currentTimeMillis() - at < MONTH) return try {
                JSONObject(json).takeIf { it.length() > 0 }
            } catch (_: Exception) {
                null
            }
        }
        val o = try {
            fetch()
        } catch (_: Exception) {
            null
        }
        Db.tmdbPut(key, (o ?: JSONObject()).toString())
        return o
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun stripHtml(s: String?): String? = s?.replace(Rx.HTML_TAG, "")?.replace("&amp;", "&")?.trim()?.ifBlank { null }

    /** Série : TVmaze. Bloquant. */
    fun series(item: Item): Info? {
        val (title, _) = Tmdb.cleanTitle(item.name)
        val o = cached("tvmaze:" + normalize(title)) {
            val show = JSONObject(Http.getText("https://api.tvmaze.com/singlesearch/shows?q=${enc(title)}"))
            val id = show.optInt("id", 0)
            if (id > 0) {
                try {
                    val imgs = JSONArray(Http.getText("https://api.tvmaze.com/shows/$id/images"))
                    for (i in 0 until imgs.length()) {
                        val im = imgs.optJSONObject(i) ?: continue
                        if (im.str("type") == "background") {
                            im.optJSONObject("resolutions")?.optJSONObject("original")?.str("url")?.let { show.put("_backdrop", it) }
                            break
                        }
                    }
                } catch (_: Exception) {
                }
            }
            show
        } ?: return null
        val rating = o.optJSONObject("rating")?.optDouble("average", Double.NaN)?.takeIf { !it.isNaN() }
        return Info(
            poster = o.optJSONObject("image")?.str("original") ?: o.optJSONObject("image")?.str("medium"),
            backdrop = o.str("_backdrop"),
            plot = stripHtml(o.str("summary")),
            genre = o.optJSONArray("genres")?.optString(0)?.ifBlank { null },
            rating = rating?.toString(),
            year = o.str("premiered")?.take(4)
        )
    }

    /** Film : iTunes France (résumé en français). Bloquant. */
    fun movie(item: Item): Info? {
        val (title, year) = Tmdb.cleanTitle(item.name)
        val o = cached("itunes:" + normalize(title) + ":" + (year ?: "")) {
            val r = JSONObject(
                Http.getText("https://itunes.apple.com/search?term=${enc(title)}&country=fr&media=movie&entity=movie&limit=5&lang=fr_fr")
            ).optJSONArray("results") ?: return@cached null
            var best: JSONObject? = null
            for (i in 0 until r.length()) {
                val x = r.optJSONObject(i) ?: continue
                if (best == null) best = x
                if (year != null && x.str("releaseDate")?.startsWith(year) == true) {
                    best = x
                    break
                }
            }
            best
        } ?: return null
        val art = o.str("artworkUrl100")?.replace("100x100bb", "600x900bb")
        return Info(
            poster = art, backdrop = null,
            plot = o.str("longDescription") ?: o.str("shortDescription"),
            genre = o.str("primaryGenreName"), rating = null, year = o.str("releaseDate")?.take(4)
        )
    }

    /** Image de fond quand rien d'autre n'en donne (séries seulement). Bloquant. */
    fun backdrop(item: Item): String? = if (item.kind == Kind.SERIES) series(item)?.backdrop else null
}
