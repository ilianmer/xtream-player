package com.ilian.xtreamplayer

import org.json.JSONObject
import java.text.Normalizer

enum class Kind { LIVE, MOVIE, SERIES, EPISODE }

data class Category(val id: String, val name: String)

/** Entrée de la colonne de gauche : une ou plusieurs catégories fusionnées, ou une liste spéciale. */
data class Group(val key: String, val name: String, val catIds: List<String>)

data class Item(
    val kind: Kind,
    val id: String,
    val name: String,
    val icon: String? = null,
    val ext: String? = null,
    val plot: String? = null,
    val extra: String? = null,
    val catId: String? = null,
    val rating: String? = null,
    val tmdb: String? = null,
    /** Adresse directe (fichier téléchargé). Si null, l'adresse est construite depuis la source. */
    val url: String? = null,
    /** Numéro de chaîne donné par le fournisseur. */
    val num: Int? = null,
    /** Date d'ajout (ou de dernière mise à jour pour une série), en secondes. */
    val added: Long = 0L,
    /** Pour un épisode : identifiant de la série. */
    val series: String? = null
) {
    val key: String get() = "${kind.name}:$id"

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind.name)
        put("id", id)
        put("name", name)
        put("icon", icon ?: "")
        put("ext", ext ?: "")
        put("plot", plot ?: "")
        put("extra", extra ?: "")
        put("cat", catId ?: "")
        put("rating", rating ?: "")
        put("tmdb", tmdb ?: "")
        put("url", url ?: "")
        put("num", num ?: 0)
        put("added", added)
        put("series", series ?: "")
    }

    companion object {
        fun fromJson(o: JSONObject) = Item(
            kind = Kind.valueOf(o.getString("kind")),
            id = o.getString("id"),
            name = o.getString("name"),
            icon = o.optString("icon").ifBlank { null },
            ext = o.optString("ext").ifBlank { null },
            plot = o.optString("plot").ifBlank { null },
            extra = o.optString("extra").ifBlank { null },
            catId = o.optString("cat").ifBlank { null },
            rating = o.optString("rating").ifBlank { null },
            tmdb = o.optString("tmdb").ifBlank { null },
            url = o.optString("url").ifBlank { null },
            num = o.optInt("num", 0).takeIf { it > 0 },
            added = o.optLong("added", 0L),
            series = o.optString("series").ifBlank { null }
        )
    }
}

data class EpgEntry(val title: String, val start: Long, val end: Long, val desc: String = "")

data class Season(val number: Int, val episodes: List<Item>)

data class SeriesInfo(
    val name: String,
    val cover: String?,
    val plot: String?,
    val tmdb: String?,
    val seasons: List<Season>,
    val backdrop: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val cast: String? = null
)

data class MovieInfo(
    val plot: String?,
    val genre: String?,
    val duration: String?,
    val year: String?,
    val rating: String?,
    val cover: String?,
    val tmdb: String?,
    val director: String?,
    val cast: String?,
    val backdrop: String? = null
)

data class AccountInfo(
    val auth: Boolean,
    val status: String?,
    /** Date d'expiration en secondes (null = illimité). */
    val expDate: Long?,
    val maxConn: Int,
    val activeCons: Int
)

data class Source(
    val id: Long,
    val name: String,
    val server: String,
    val user: String,
    val pass: String,
    val lastSync: Long,
    val status: String?,
    val expDate: Long?,
    val maxConn: Int
)

/** Profil de visionnage (jusqu'à 5 par source), avec ses langues préférées (« fr,ar »). */
data class Profile(val id: Long, val sourceId: Long, val name: String, val color: Int, val langs: String) {
    val langSet: Set<String> get() = langs.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

/** Lit un champ texte en ignorant les null JSON et les chaînes vides. */
fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").trim().ifBlank { null }
}

private val MARKS = Regex("\\p{Mn}+")

/** Minuscules sans accents, pour la recherche. */
fun normalize(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()
