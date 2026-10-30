package com.ilian.xtreamplayer

import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.min

/**
 * Sport à la carte (par profil) : sports et compétitions affichés, et habitudes de visionnage
 * (compétitions et équipes regardées) pour mettre en avant ce que tu regardes vraiment.
 */
object SportPrefs {
    val SPORTS = listOf("Football", "Basket", "Rugby", "Tennis", "Combat")

    private fun key(n: String) = Db.pkey("sport:$n")

    @Volatile
    private var cacheKey = ""
    @Volatile
    private var enabledPaths: Set<String> = emptySet()
    @Volatile
    private var habits: Habits? = null
    @Volatile
    var version = 0
        private set

    private fun read(n: String): Set<String>? = try {
        Db.kvGet(key(n))?.split('|')?.filter { it.isNotEmpty() }?.toSet()
    } catch (_: Exception) {
        null
    }

    /** Sports affichés (tous par défaut). */
    fun sports(): Set<String> = read("groups")?.filter { it in SPORTS }?.toSet()?.takeIf { it.isNotEmpty() } ?: SPORTS.toSet()

    /** Compétitions retirées par l'utilisateur. */
    fun offLeagues(): Set<String> = read("leagues_off").orEmpty()

    fun setSports(s: Set<String>) {
        Db.kvPut(key("groups"), if (s.isEmpty() || s.size == SPORTS.size) null else s.joinToString("|"))
        changed()
    }

    fun setOffLeagues(s: Set<String>) {
        Db.kvPut(key("leagues_off"), if (s.isEmpty()) null else s.joinToString("|"))
        changed()
    }

    private fun changed() {
        version++
        cacheKey = ""
        Sport.invalidate()
    }

    /** Compétitions à charger et afficher. */
    fun leagues(): List<Sport.League> {
        val on = sports()
        val off = offLeagues()
        val l = Sport.LEAGUES.filter { it.group in on && it.path !in off }
        return l.ifEmpty { Sport.LEAGUES.filter { it.group in on } }
    }

    fun enabled(l: Sport.League): Boolean {
        val k = "${Db.profile}:$version"
        if (k != cacheKey) {
            enabledPaths = leagues().map { it.path }.toSet()
            cacheKey = k
            habits = null
        }
        return l.path in enabledPaths
    }

    /** Onglets de l'écran Sport : « Tout » seulement s'il y a plusieurs sports. */
    fun tabs(): List<String> {
        val on = SPORTS.filter { it in sports() }
        return (if (on.size > 1) listOf("Tout") else emptyList()) + on + "Mes équipes"
    }

    // ---------- Habitudes ----------

    private class Habits(val leagues: HashMap<String, Float>, val teams: HashMap<String, Float>)

    @Volatile
    private var habitsProfile = Long.MIN_VALUE

    private fun habits(): Habits {
        if (habitsProfile != Db.profile.toLong()) {
            habits = null
            habitsProfile = Db.profile.toLong()
        }
        habits?.let { return it }
        val h = Habits(HashMap(), HashMap())
        try {
            Db.kvGet(key("habits"))?.let { s ->
                val o = JSONObject(s)
                o.optJSONObject("l")?.let { l -> l.keys().forEach { k -> h.leagues[k] = l.optDouble(k).toFloat() } }
                o.optJSONObject("t")?.let { t -> t.keys().forEach { k -> h.teams[k] = t.optDouble(k).toFloat() } }
            }
        } catch (_: Exception) {
        }
        habits = h
        return h
    }

    private fun team(s: Sport.Side) = normalize(s.name).trim()

    /** Match regardé (poids 1 au lancement, 2 après une vraie séance devant). */
    fun noteWatched(m: Sport.Match, weight: Float = 1f) {
        try {
            val h = habits()
            h.leagues[m.league.path] = (h.leagues[m.league.path] ?: 0f) + weight
            for (s in listOf(m.home, m.away)) team(s).takeIf { it.isNotEmpty() }?.let { h.teams[it] = (h.teams[it] ?: 0f) + weight }
            // Les vieilles habitudes s'effacent peu à peu ; on garde les 60 équipes les plus regardées
            if (h.leagues.values.sum() > 300f) {
                for (k in h.leagues.keys) h.leagues[k] = h.leagues[k]!! * 0.7f
                for (k in h.teams.keys) h.teams[k] = h.teams[k]!! * 0.7f
            }
            val keep = h.teams.entries.sortedByDescending { it.value }.take(60).associate { it.key to it.value }
            h.teams.clear()
            h.teams.putAll(keep)
            val o = JSONObject()
            o.put("l", JSONObject(h.leagues.mapValues { it.value.toDouble() }))
            o.put("t", JSONObject(h.teams.mapValues { it.value.toDouble() }))
            Db.kvPut(key("habits"), o.toString())
        } catch (_: Exception) {
        }
    }

    /** Bonus de « tendance » selon ce que tu regardes d'habitude (0 si aucune habitude). */
    fun affinity(m: Sport.Match): Int {
        val h = try {
            habits()
        } catch (_: Exception) {
            return 0
        }
        if (h.leagues.isEmpty()) return 0
        fun w(x: Float) = ln(1f + x)
        val l = w(h.leagues[m.league.path] ?: 0f) * 3f
        val t = w((h.teams[team(m.home)] ?: 0f) + (h.teams[team(m.away)] ?: 0f)) * 4f
        // Sport préféré : celui des compétitions les plus regardées
        val bySport = HashMap<String, Float>()
        for ((p, v) in h.leagues) Sport.LEAGUES.firstOrNull { it.path == p }?.let { bySport[it.group] = (bySport[it.group] ?: 0f) + v }
        val total = bySport.values.sum().coerceAtLeast(1f)
        val s = (bySport[m.league.group] ?: 0f) / total * 4f
        return (min(l, 9f) + min(t, 14f) + s).toInt()
    }
}
