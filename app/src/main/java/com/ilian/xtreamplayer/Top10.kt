package com.ilian.xtreamplayer

import org.json.JSONArray
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp

/**
 * Top 10 du jour (films ou séries), façon Netflix, calculé sur TON catalogue :
 * - l'actualité : tendances de la semaine (TMDB), en tête de classement ce qui est le plus regardé partout ;
 * - la nouveauté : ajouté récemment chez ton fournisseur, ou sorti cette année ;
 * - la qualité : note (TMDB ou fournisseur) et classement des mieux notés ;
 * - tes goûts, un peu : les catégories que tu regardes ; ce que tu as déjà fini recule.
 * Une seule version par titre (4K, VF, VOSTFR… d'un même film). Le classement est figé pour la
 * journée (comme un vrai Top 10), puis recalculé le lendemain.
 */
object Top10 {
    private const val SIZE = 10
    private val mem = ConcurrentHashMap<String, Pair<String, List<Item>>>()

    private fun key(src: Long, kind: Kind) = Db.pkey("top10:$src:${kind.name}")

    private fun today(): String {
        val c = Calendar.getInstance()
        return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
    }

    /** Rapide : classement du jour déjà calculé (mémoire, sinon base locale), ou null. */
    fun cached(src: Long, kind: Kind): List<Item>? {
        val k = key(src, kind)
        val day = today()
        mem[k]?.let { (d, l) -> if (d == day) return l }
        val saved = try {
            Db.kvGet(k)
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            val a = JSONArray(saved)
            if (a.optString(0) != day) return null
            val ids = (1 until a.length()).map { a.getString(it) }
            val found = Db.findMany(src, kind, ids).associateBy { it.id }
            val list = ids.mapNotNull { found[it] }
            if (list.size < 3) null else list.also { mem[k] = day to it }
        } catch (_: Exception) {
            null
        }
    }

    /** Bloquant (réseau possible) : classement du jour, calculé une fois par jour. */
    fun get(src: Long, kind: Kind): List<Item> {
        cached(src, kind)?.let { return it }
        val list = compute(src, kind)
        val k = key(src, kind)
        val day = today()
        mem[k] = day to list
        if (list.size >= 3) {
            try {
                val a = JSONArray().put(day)
                for (i in list) a.put(i.id)
                Db.kvPut(k, a.toString())
            } catch (_: Exception) {
            }
        }
        return list
    }

    private val YEAR = Regex("""\b(19|20)\d\d\b""")

    private fun compute(src: Long, kind: Kind): List<Item> {
        val series = kind == Kind.SERIES
        val now = System.currentTimeMillis()
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)

        // Actualité : tendances de la semaine retrouvées dans le catalogue
        val trendIds = try {
            Tmdb.trending(series)
        } catch (_: Exception) {
            emptyList()
        }
        val trendRank = HashMap<String, Int>()
        val trendItems = try {
            Db.byTmdbVisible(src, kind, trendIds)
        } catch (_: Exception) {
            emptyMap()
        }
        for ((t, item) in trendItems) trendRank[item.key] = trendIds.indexOf(t).coerceAtLeast(0)

        // Nouveautés et mieux notés
        val latest = try {
            Db.latest(src, kind, 150)
        } catch (_: Exception) {
            emptyList()
        }
        val top = try {
            TopRated.items(src, kind)
        } catch (_: Exception) {
            emptyList()
        }
        val topRank = top.withIndex().associate { (i, it) -> it.key to i }

        // Goûts : catégories des titres déjà regardés
        val watched = try {
            Db.watchedIds(src, kind)
        } catch (_: Exception) {
            emptySet()
        }
        val likedCats = try {
            Db.findMany(src, kind, watched.take(200).toList()).mapNotNull { it.catId }.groupingBy { it }.eachCount()
        } catch (_: Exception) {
            emptyMap()
        }

        val pool = LinkedHashMap<String, Item>()
        for (i in trendItems.values) pool[i.key] = i
        for (i in latest) pool.putIfAbsent(i.key, i)
        for (i in top) pool.putIfAbsent(i.key, i)
        if (pool.isEmpty()) return emptyList()
        val nTrend = trendIds.size.coerceAtLeast(1)
        val nTop = top.size.coerceAtLeast(1)

        fun score(i: Item): Float {
            var s = 0f
            trendRank[i.key]?.let { r -> s += 15f + 45f * (1f - r.toFloat() / nTrend) }
            if (i.added > 0) {
                val days = ((now / 1000 - i.added) / 86_400f).coerceAtLeast(0f)
                s += 30f * exp(-days / 20f)
            }
            YEAR.findAll(i.name).lastOrNull()?.value?.toIntOrNull()?.let { y ->
                if (y >= thisYear) s += 12f else if (y == thisYear - 1) s += 6f
            }
            rating(i.rating)?.let { r -> s += ((r - 5.5f).coerceAtLeast(0f)) * 6f }
            topRank[i.key]?.let { r -> s += 12f * (1f - r.toFloat() / nTop) }
            i.catId?.let { c -> likedCats[c]?.let { n -> s += minOf(8f, 2f + n) } }
            if (i.id in watched) s -= 10f
            return s
        }

        val seen = HashSet<String>()
        return pool.values
            .map { it to score(it) }
            .sortedByDescending { it.second }
            .map { it.first }
            .filter { seen.add(it.tmdb?.takeIf { t -> t.isNotBlank() && t != "0" } ?: normalize(Tmdb.cleanTitle(it.name).first)) }
            .take(SIZE)
    }

    /** Note sur 10 (le fournisseur la donne parfois sur 100). */
    private fun rating(r: String?): Float? {
        val v = r?.replace(',', '.')?.toFloatOrNull() ?: return null
        return when {
            v <= 0f -> null
            v <= 10f -> v
            v <= 100f -> v / 10f
            else -> null
        }
    }
}
