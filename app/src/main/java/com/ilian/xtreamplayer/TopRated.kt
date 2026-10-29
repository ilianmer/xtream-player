package com.ilian.xtreamplayer

import org.json.JSONArray

/**
 * Rangée « Les mieux notés » de Films et Séries : le classement TMDB des mieux notés, retrouvé dans
 * ton catalogue (catégories affichées seulement). Sans clé TMDB : la note donnée par le fournisseur.
 * Le classement est recalculé en arrière-plan au plus une fois par jour ; l'affichage, lui, est instantané.
 */
object TopRated {
    private const val DAY = 24 * 3_600_000L
    private const val MAX = 40

    private fun key(src: Long, kind: Kind) = "toprated:$src:${kind.name}"

    /** Rapide (base locale uniquement) : peut être appelé pendant l'affichage. */
    fun items(src: Long, kind: Kind): List<Item> {
        val ids = Db.kvGet(key(src, kind))?.let { s ->
            try {
                val a = JSONArray(s)
                (0 until a.length()).map { a.getString(it) }
            } catch (_: Exception) {
                null
            }
        }
        val fromTmdb = ids?.let { Db.findMany(src, kind, it) }.orEmpty()
        if (fromTmdb.size >= 8) return dedupe(fromTmdb).take(MAX)
        return dedupe(Db.topByRating(src, kind, MAX)).take(MAX)
    }

    /** Une seule version par titre (4K, HD, VF… d'un même film). */
    private fun dedupe(list: List<Item>): List<Item> {
        val seen = HashSet<String>()
        return list.filter { seen.add(it.tmdb?.takeIf { t -> t.isNotBlank() && t != "0" } ?: normalize(Tmdb.cleanTitle(it.name).first)) }
    }

    fun stale(src: Long, kind: Kind): Boolean {
        val at = Db.kvGet(key(src, kind) + ":at")?.toLongOrNull() ?: 0L
        return System.currentTimeMillis() - at > DAY
    }

    /** Bloquant : télécharge le classement TMDB et le retrouve dans le catalogue. Renvoie true si la liste a changé. */
    fun refresh(src: Long, kind: Kind): Boolean {
        if (!Tmdb.enabled || !stale(src, kind)) return false
        val tv = kind == Kind.SERIES
        val top = Tmdb.topRated(tv)
        if (top.isEmpty()) return false
        // 1. Par identifiant TMDB fourni par le fournisseur
        val byId = Db.byTmdbVisible(src, kind, top.map { it.id })
        val found = LinkedHashMap<String, Item>()
        for (t in top) {
            val hit = byId[t.id] ?: matchByTitle(src, kind, t)
            if (hit != null && !found.containsKey(t.id)) found[t.id] = hit
        }
        val ids = found.values.map { it.id }
        val json = JSONArray(ids).toString()
        val changed = Db.kvGet(key(src, kind)) != json
        Db.kvPut(key(src, kind), json)
        Db.kvPut(key(src, kind) + ":at", System.currentTimeMillis().toString())
        return changed
    }

    /** 2. Sinon par le titre (français ou original), avec l'année quand on la connaît. */
    private fun matchByTitle(src: Long, kind: Kind, t: Tmdb.Top): Item? {
        val wanted = setOf(normalize(t.title), normalize(t.original)).filter { it.length >= 2 }
        for (q in wanted) {
            val cands = Db.page(src, kind, null, q, 0, 12)
            val exact = cands.filter { c ->
                val (name, year) = Tmdb.cleanTitle(c.name)
                normalize(name) in wanted && (year == null || t.year == null || year == t.year)
            }
            exact.firstOrNull()?.let { return it }
        }
        return null
    }
}
