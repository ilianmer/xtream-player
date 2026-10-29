package com.ilian.xtreamplayer

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Scores en direct et matchs à venir, depuis les tableaux de scores publics d'ESPN (sans clé).
 * Chaque compétition est chargée séparément : si l'une ne répond pas, les autres s'affichent quand même.
 */
object Sport {

    data class League(val sport: String, val path: String, val label: String, val group: String)

    data class Side(
        val name: String,
        val short: String,
        val abbr: String,
        val logo: String?,
        val color: Int?,
        val score: String?,
        val winner: Boolean
    )

    data class Match(
        val id: String,
        val league: League,
        val start: Long,
        /** pre, in ou post */
        val state: String,
        /** Minute de jeu, mi-temps, heure de début ou « Terminé ». */
        val status: String,
        val home: Side,
        val away: Side,
        /** Dernier but, sets d'un match de tennis… */
        val detail: String?,
        /** Journée, tour, nom de la soirée de combat… */
        val round: String?,
        /** Chaînes annoncées par ESPN (souvent américaines, utilisées comme indice). */
        val broadcasts: List<String> = emptyList(),
        val odds: Odds? = null
    ) {
        val live: Boolean get() = state == "in"
        val finished: Boolean get() = state == "post"
        val key: String get() = "${league.path}:$id"
        val title: String get() = "${home.short} – ${away.short}"
    }

    /** Cotes 1 / N / 2 en décimal. [books] : détail par bookmaker quand on en a plusieurs. */
    data class Odds(
        val home: Double?,
        val draw: Double?,
        val away: Double?,
        val source: String,
        val books: List<Book> = emptyList()
    )

    data class Book(val name: String, val home: Double?, val draw: Double?, val away: Double?)

    val GROUPS = listOf("Tout", "Football", "Basket", "Rugby", "Tennis", "Combat", "Mes équipes")

    /**
     * Qui diffuse quoi en France (saison 2026-2027) : mots à chercher dans les noms de chaînes,
     * et le nom du diffuseur à afficher.
     */
    val RIGHTS: Map<String, Pair<List<String>, String>> = mapOf(
        "fra.1" to (listOf("ligue 1+", "ligue1+", "ligue 1 +", "l1+", "bein") to "Ligue 1+ / beIN Sports"),
        "fra.2" to (listOf("bein") to "beIN Sports"),
        "uefa.champions" to (listOf("canal", "bein") to "Canal+"),
        "uefa.europa" to (listOf("canal") to "Canal+"),
        "uefa.europa.conf" to (listOf("canal") to "Canal+"),
        "eng.1" to (listOf("canal") to "Canal+"),
        "esp.1" to (listOf("dazn", "disney", "espn") to "DAZN / Disney+"),
        "ita.1" to (listOf("dazn") to "DAZN"),
        "ger.1" to (listOf("bein") to "beIN Sports"),
        "fra.coupe_de_france" to (listOf("bein", "france 2", "france 3", "france tv") to "beIN Sports"),
        "mar.1" to (listOf("arryadia", "aoula", "maroc", "snrt", "2m") to "Arryadia"),
        "caf.nations" to (listOf("bein", "arryadia") to "beIN Sports"),
        "uefa.nations" to (listOf("tf1", "m6", "equipe") to "TF1 / L'Équipe"),
        "fifa.worldq.uefa" to (listOf("tf1", "m6", "equipe") to "TF1 / L'Équipe"),
        "fifa.friendly" to (listOf("tf1", "m6", "equipe") to "TF1 / L'Équipe"),
        "nba" to (listOf("bein", "nba", "prime") to "beIN Sports"),
        "270559" to (listOf("canal", "rugby") to "Canal+"),
        "271937" to (listOf("bein", "france 2", "france 3", "france tv") to "beIN Sports / France TV"),
        "180659" to (listOf("france 2", "france tv") to "France 2"),
        "atp" to (listOf("eurosport", "bein", "tennis") to "Eurosport"),
        "wta" to (listOf("eurosport", "bein", "tennis") to "Eurosport"),
        "ufc" to (listOf("rmc", "ufc", "dazn", "fight") to "RMC Sport")
    )

    /** Grandes équipes : un match qui les concerne est « tendance ». */
    private val BIG = listOf(
        "paris saint", "marseille", "lyon", "monaco", "lille", "lens", "nice", "rennes",
        "real madrid", "barcelona", "atletico", "manchester city", "manchester united", "liverpool", "arsenal", "chelsea", "tottenham",
        "bayern", "dortmund", "inter", "milan", "juventus", "napoli", "roma", "raja", "wydad", "far rabat",
        "lakers", "celtics", "warriors", "toulouse", "la rochelle", "racing 92", "france", "maroc", "morocco"
    )
    private val LEAGUE_WEIGHT = mapOf(
        "uefa.champions" to 10, "fra.1" to 8, "eng.1" to 7, "esp.1" to 7, "caf.nations" to 7, "uefa.nations" to 6,
        "ita.1" to 6, "ger.1" to 6, "uefa.europa" to 5, "nba" to 5, "270559" to 5, "180659" to 8, "fifa.worldq.uefa" to 7,
        "mar.1" to 4, "ufc" to 4, "atp" to 3, "wta" to 3, "fra.coupe_de_france" to 4
    )

    /** Note de « tendance » d'un match (compétition, grandes équipes, suspense). */
    fun trend(m: Match): Int {
        var s = LEAGUE_WEIGHT[m.league.path] ?: 2
        val names = normalize(m.home.name + " " + m.away.name)
        s += BIG.count { names.contains(it) } * 4
        val a = m.home.score?.toIntOrNull()
        val b = m.away.score?.toIntOrNull()
        if (m.live && a != null && b != null && m.league.sport == "soccer" && kotlin.math.abs(a - b) <= 1) s += 2
        if (involvesFollowed(m)) s += 20
        return s
    }

    // ---------- Cache partagé (Accueil, Sport, lecteur) ----------

    @Volatile
    private var cache: List<Match> = emptyList()
    @Volatile
    private var cachedAt = 0L

    /** Derniers matchs connus sans attendre le réseau. */
    fun cached(): List<Match> = cache

    fun cachedAge(): Long = if (cachedAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - cachedAt

    @Volatile
    private var fullAt = 0L

    /**
     * Matchs à jour : réutilise le cache s'il a moins de [maxAgeMs]. Bloquant.
     * Entre deux chargements complets (toutes les 10 minutes), seules les compétitions
     * qui ont un match en cours ou qui commence bientôt sont rechargées.
     */
    @Synchronized
    fun get(maxAgeMs: Long = 60_000L): List<Match> {
        if (cachedAge() < maxAgeMs) return cache
        val now = System.currentTimeMillis()
        val fresh = if (cache.isEmpty() || now - fullAt > 10 * 60_000L) {
            fullAt = now
            load()
        } else {
            val hot = cache.filter { it.live || (it.state == "pre" && it.start - now < 2 * 3_600_000L) }.map { it.league.path }.toSet()
            if (hot.isEmpty()) cache else {
                val updated = load(LEAGUES.filter { it.path in hot })
                val byKey = updated.associateBy { it.key }
                (cache.filter { it.league.path !in hot } + cache.filter { it.league.path in hot }.map { byKey[it.key] ?: it } +
                    updated.filter { u -> cache.none { it.key == u.key } }).sortedBy { it.start }
            }
        }
        val withOdds = if (Prefs.oddsEnabled) OddsApi.attach(fresh) else fresh
        cache = withOdds
        cachedAt = System.currentTimeMillis()
        return withOdds
    }

    val LEAGUES = listOf(
        League("soccer", "fra.1", "Ligue 1", "Football"),
        League("soccer", "uefa.champions", "Ligue des champions", "Football"),
        League("soccer", "uefa.europa", "Ligue Europa", "Football"),
        League("soccer", "uefa.europa.conf", "Ligue Conférence", "Football"),
        League("soccer", "eng.1", "Premier League", "Football"),
        League("soccer", "esp.1", "LaLiga", "Football"),
        League("soccer", "ita.1", "Serie A", "Football"),
        League("soccer", "ger.1", "Bundesliga", "Football"),
        League("soccer", "fra.2", "Ligue 2", "Football"),
        League("soccer", "fra.coupe_de_france", "Coupe de France", "Football"),
        League("soccer", "mar.1", "Botola Pro", "Football"),
        League("soccer", "caf.nations", "Coupe d'Afrique", "Football"),
        League("soccer", "uefa.nations", "Ligue des nations", "Football"),
        League("soccer", "fifa.worldq.uefa", "Qualif. Coupe du monde", "Football"),
        League("soccer", "fifa.friendly", "Match amical", "Football"),
        League("basketball", "nba", "NBA", "Basket"),
        League("rugby", "270559", "Top 14", "Rugby"),
        League("rugby", "271937", "Champions Cup", "Rugby"),
        League("rugby", "180659", "Six Nations", "Rugby"),
        League("tennis", "atp", "ATP", "Tennis"),
        League("tennis", "wta", "WTA", "Tennis"),
        League("mma", "ufc", "UFC", "Combat")
    )

    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports"
    private val pool = Executors.newFixedThreadPool(6)

    /** Matchs d'hier soir à demain, toutes compétitions (ou seulement [leagues]). Bloquant. */
    fun load(leagues: List<League> = LEAGUES): List<Match> {
        val paris = TimeZone.getTimeZone("Europe/Paris")
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = paris }
        val cal = Calendar.getInstance(paris)
        val today = f.format(cal.time)
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val tomorrow = f.format(cal.time)
        cal.add(Calendar.DAY_OF_MONTH, -2)
        val yesterday = f.format(cal.time)

        val jobs = ArrayList<Callable<List<Match>>>()
        for (l in leagues) {
            val days = when (l.sport) {
                "tennis", "mma" -> listOf<String?>(null)
                // Les matchs américains de la nuit sont rangés à la date de la veille, heure de New York.
                "basketball" -> listOf(yesterday, today, tomorrow)
                else -> listOf(today, tomorrow)
            }
            for (d in days) jobs.add(Callable { fetch(l, d) })
        }
        val out = ArrayList<Match>()
        val futures = pool.invokeAll(jobs, 25, TimeUnit.SECONDS)
        for (fu in futures) {
            try {
                out.addAll(fu.get())
            } catch (_: Exception) {
            }
        }
        val now = System.currentTimeMillis()
        return out.distinctBy { it.key }
            .filter { m ->
                when {
                    m.live -> true
                    m.finished -> now - m.start < 20 * 3_600_000L
                    else -> m.start - now < 36 * 3_600_000L && now - m.start < 6 * 3_600_000L
                }
            }
            .sortedBy { it.start }
    }

    private fun fetch(l: League, day: String?): List<Match> {
        val url = "$BASE/${l.sport}/${l.path}/scoreboard" + (if (day != null) "?dates=$day" else "")
        val o = try {
            JSONObject(Http.getText(url))
        } catch (_: Exception) {
            return emptyList()
        }
        val events = o.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<Match>()
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            try {
                when (l.sport) {
                    "tennis" -> out.addAll(tennis(l, e))
                    "mma" -> mma(l, e)?.let { out.add(it) }
                    else -> match(l, e)?.let { out.add(it) }
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    // ---------- Lecture du JSON ----------

    private val ISO = listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

    private fun parseDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        for (p in ISO) {
            try {
                return SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)?.time ?: continue
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    private fun color(hex: String?): Int? = try {
        if (hex.isNullOrBlank()) null else Color.parseColor("#" + hex.trim().removePrefix("#"))
    } catch (_: Exception) {
        null
    }

    private fun side(c: JSONObject): Side {
        val t = c.optJSONObject("team")
        val a = c.optJSONObject("athlete")
        val name = t?.str("displayName") ?: a?.str("displayName") ?: c.optJSONObject("roster")?.str("displayName") ?: "?"
        val short = t?.str("shortDisplayName") ?: a?.str("shortName") ?: name
        val abbr = t?.str("abbreviation") ?: initials(name)
        val logo = t?.str("logo") ?: t?.optJSONArray("logos")?.optJSONObject(0)?.str("href") ?: a?.optJSONObject("flag")?.str("href")
        return Side(name, short, abbr, logo, color(t?.str("color")), c.str("score"), c.optBoolean("winner", false))
    }

    fun initials(name: String): String {
        // Doubles « A / B » : initiales du premier joueur ; jamais de « / » dans la pastille
        val words = name.substringBefore('/').split(' ', '-', '.').map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(3).uppercase()
            else -> words.take(3).joinToString("") { it.take(1) }.uppercase()
        }
    }

    private fun status(l: League, st: JSONObject?, start: Long): Pair<String, String> {
        val type = st?.optJSONObject("type")
        val state = type?.str("state") ?: "pre"
        val name = type?.str("name") ?: ""
        val text = when (state) {
            "pre" -> if (name.contains("POSTPONED")) "Reporté" else if (name.contains("CANCELED")) "Annulé" else hhmm(start)
            "post" -> if (name.contains("POSTPONED")) "Reporté" else if (name.contains("CANCELED")) "Annulé" else "Terminé"
            else -> when {
                name.contains("HALFTIME") -> "Mi-temps"
                l.sport == "basketball" -> {
                    val p = st?.optInt("period", 0) ?: 0
                    val clock = st?.str("displayClock") ?: ""
                    if (p > 4) "Prol. · $clock" else "QT$p · $clock"
                }
                l.sport == "soccer" || l.sport == "rugby" -> st?.str("displayClock") ?: "En cours"
                else -> type?.str("shortDetail") ?: "En cours"
            }
        }
        return state to text
    }

    private fun hhmm(ms: Long): String =
        if (ms <= 0) "—" else SimpleDateFormat("HH:mm", Locale.FRANCE).format(java.util.Date(ms))

    private fun match(l: League, e: JSONObject): Match? {
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val cs = comp.optJSONArray("competitors") ?: return null
        var home: Side? = null
        var away: Side? = null
        for (i in 0 until cs.length()) {
            val c = cs.optJSONObject(i) ?: continue
            if (c.str("homeAway") == "away") away = side(c) else if (home == null) home = side(c) else away = side(c)
        }
        if (home == null || away == null) return null
        val start = parseDate(comp.str("date") ?: e.str("date"))
        val (state, text) = status(l, comp.optJSONObject("status") ?: e.optJSONObject("status"), start)
        return Match(
            e.str("id") ?: return null, l, start, state, text, home, away,
            if (state == "in") lastScore(comp) else null,
            roundOf(e, comp),
            broadcasts(comp),
            espnOdds(comp)
        )
    }

    private fun broadcasts(comp: JSONObject): List<String> {
        val out = LinkedHashSet<String>()
        comp.optJSONArray("broadcasts")?.let { a ->
            for (i in 0 until a.length()) {
                val names = a.optJSONObject(i)?.optJSONArray("names") ?: continue
                for (j in 0 until names.length()) names.optString(j).trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
            }
        }
        comp.optJSONArray("geoBroadcasts")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.optJSONObject("media")?.str("shortName")?.let { out.add(it) }
        }
        return out.toList()
    }

    /** Cote américaine (+150 / -120) vers cote décimale (2,50 / 1,83). */
    fun decimal(moneyLine: Double): Double? = when {
        moneyLine.isNaN() || moneyLine == 0.0 -> null
        moneyLine > 0 -> 1 + moneyLine / 100
        else -> 1 + 100 / -moneyLine
    }

    private fun espnOdds(comp: JSONObject): Odds? {
        val o = comp.optJSONArray("odds")?.optJSONObject(0) ?: return null
        fun ml(key: String): Double? {
            val x = o.optJSONObject(key) ?: return null
            val v = x.optDouble("moneyLine", Double.NaN)
            return decimal(v)
        }
        val h = ml("homeTeamOdds")
        val a = ml("awayTeamOdds")
        val d = ml("drawOdds")
        if (h == null && a == null) return null
        return Odds(h, d, a, o.optJSONObject("provider")?.str("name") ?: "ESPN")
    }

    /** Équipes d'une compétition (pour la liste « Mes équipes »). Bloquant. */
    fun teams(l: League): List<Side> {
        val o = JSONObject(Http.getText("$BASE/${l.sport}/${l.path}/teams"))
        val arr = o.optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("teams")
            ?: return emptyList()
        val out = ArrayList<Side>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i)?.optJSONObject("team") ?: continue
            val name = t.str("displayName") ?: continue
            out.add(
                Side(
                    name, t.str("shortDisplayName") ?: name, t.str("abbreviation") ?: initials(name),
                    t.optJSONArray("logos")?.optJSONObject(0)?.str("href"), color(t.str("color")), null, false
                )
            )
        }
        return out.sortedBy { normalize(it.name) }
    }

    /** « But · Mbappé 64' » ou « Essai · Dupont 49' ». */
    private fun lastScore(comp: JSONObject): String? {
        val d = comp.optJSONArray("details") ?: return null
        for (i in d.length() - 1 downTo 0) {
            val x = d.optJSONObject(i) ?: continue
            if (!x.optBoolean("scoringPlay", false)) continue
            val type = x.optJSONObject("type")?.str("text") ?: ""
            val label = when {
                type.contains("Own Goal", true) -> "But contre son camp"
                type.contains("Penalty", true) -> "But sur penalty"
                type.contains("Goal", true) -> "But"
                type.contains("Try", true) -> "Essai"
                else -> "Point"
            }
            val who = x.optJSONArray("athletesInvolved")?.optJSONObject(0)?.let { it.str("shortName") ?: it.str("displayName") }
            val clock = x.optJSONObject("clock")?.str("displayValue")
            return listOfNotNull(label, listOfNotNull(who, clock).joinToString(" ").ifBlank { null }).joinToString(" · ")
        }
        return null
    }

    private fun roundOf(e: JSONObject, comp: JSONObject): String? {
        comp.optJSONArray("notes")?.optJSONObject(0)?.str("headline")?.let { return it }
        e.optJSONObject("week")?.optInt("number", 0)?.takeIf { it > 0 }?.let { return "J$it" }
        return null
    }

    private fun tennis(l: League, e: JSONObject): List<Match> {
        val out = ArrayList<Match>()
        val tournament = e.str("shortName") ?: e.str("name")
        val comps = ArrayList<JSONObject>()
        e.optJSONArray("competitions")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { comps.add(it) } }
        e.optJSONArray("groupings")?.let { g ->
            for (i in 0 until g.length()) {
                val a = g.optJSONObject(i)?.optJSONArray("competitions") ?: continue
                for (j in 0 until a.length()) a.optJSONObject(j)?.let { comps.add(it) }
            }
        }
        for (comp in comps) {
            val cs = comp.optJSONArray("competitors") ?: continue
            if (cs.length() < 2) continue
            val a = cs.optJSONObject(0) ?: continue
            val b = cs.optJSONObject(1) ?: continue
            val start = parseDate(comp.str("date") ?: comp.str("startDate"))
            val (state, text) = status(l, comp.optJSONObject("status"), start)
            val setsA = sets(a)
            val setsB = sets(b)
            var wonA = 0
            var wonB = 0
            for (k in 0 until minOf(setsA.size, setsB.size)) {
                if (k == setsA.size - 1 && state == "in") break
                if (setsA[k] > setsB[k]) wonA++ else if (setsB[k] > setsA[k]) wonB++
            }
            val detail = if (setsA.isNotEmpty()) setsA.indices.joinToString("  ") { k -> "${setsA[k]}-${setsB.getOrElse(k) { 0 }}" } else null
            val sa = side(a).copy(score = if (state == "pre") null else wonA.toString())
            val sb = side(b).copy(score = if (state == "pre") null else wonB.toString())
            out.add(
                Match(
                    comp.str("id") ?: continue, l, start, state,
                    if (state == "in") "En cours" else text, sa, sb, detail,
                    listOfNotNull(tournament, comp.optJSONObject("round")?.str("displayName")).joinToString(" · ").ifBlank { null }
                )
            )
        }
        // Un tournoi compte des dizaines de matchs : on garde l'essentiel.
        val live = out.filter { it.live }
        val rest = out.filter { !it.live }.sortedBy { it.start }
        return live + rest.filter { it.finished }.takeLast(6) + rest.filter { it.state == "pre" }.take(6)
    }

    private fun sets(c: JSONObject): List<Int> {
        val a = c.optJSONArray("linescores") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optDouble("value", Double.NaN)?.takeIf { v -> !v.isNaN() }?.toInt() }
    }

    /** Une soirée de combat : on montre le combat principal (le dernier de la liste). */
    private fun mma(l: League, e: JSONObject): Match? {
        val comps = e.optJSONArray("competitions") ?: return null
        val main = comps.optJSONObject(comps.length() - 1) ?: return null
        val cs = main.optJSONArray("competitors") ?: return null
        if (cs.length() < 2) return null
        val start = parseDate(e.str("date") ?: main.str("date"))
        val (state, text) = status(l, e.optJSONObject("status") ?: main.optJSONObject("status"), start)
        return Match(
            e.str("id") ?: return null, l, start, state, text,
            side(cs.getJSONObject(0)).copy(score = null), side(cs.getJSONObject(1)).copy(score = null),
            null, e.str("shortName") ?: e.str("name")
        )
    }

    // ---------- Équipes suivies ----------

    fun followed(): Set<String> = Db.kvGet(Db.pkey("sport:teams"))?.let { s ->
        try {
            val a = JSONArray(s)
            (0 until a.length()).map { a.getString(it) }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    } ?: emptySet()

    /** Équipes suivies avec leur nom et leur logo, pour les afficher. */
    fun followedSides(): List<Side> {
        val info = try {
            JSONObject(Db.kvGet(Db.pkey("sport:teaminfo")) ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        return followed().map { k ->
            val o = info.optJSONObject(k)
            val name = o?.str("name") ?: k.replaceFirstChar { it.uppercase() }
            Side(name, o?.str("short") ?: name, o?.str("abbr") ?: initials(name), o?.str("logo"), null, null, false)
        }.sortedBy { normalize(it.name) }
    }

    fun isFollowed(side: Side): Boolean = normalize(side.name) in followed()

    fun toggleFollow(side: Side): Boolean {
        val set = followed().toMutableSet()
        val k = normalize(side.name)
        val added = if (k in set) {
            set.remove(k)
            false
        } else {
            set.add(k)
            true
        }
        Db.kvPut(Db.pkey("sport:teams"), JSONArray(set.toList()).toString())
        val info = try {
            JSONObject(Db.kvGet(Db.pkey("sport:teaminfo")) ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        if (added) info.put(k, JSONObject().put("name", side.name).put("short", side.short).put("abbr", side.abbr).put("logo", side.logo ?: ""))
        else info.remove(k)
        Db.kvPut(Db.pkey("sport:teaminfo"), info.toString())
        return added
    }

    fun involvesFollowed(m: Match): Boolean {
        val f = followed()
        return normalize(m.home.name) in f || normalize(m.away.name) in f
    }
}

/**
 * Trouve la chaîne qui diffuse un match : choix mémorisé, sinon le titre du match dans le guide TV
 * des chaînes du diffuseur officiel (droits TV en France), sinon le diffuseur seul.
 */
object SportChannels {
    /**
     * [sure] : le match a été trouvé dans le guide (ou choisi par toi).
     * [broadcaster] : seulement le nom du diffuseur, sans chaîne précise trouvée.
     */
    data class Pick(val channel: Item?, val sure: Boolean, val programme: String?, val broadcaster: String? = null)

    private val pool = Executors.newFixedThreadPool(2)
    private val workers = Executors.newFixedThreadPool(4)
    private val HINTS = listOf(
        "bein", "canal", "rmc", "dazn", "eurosport", "equipe", "prime", "foot", "ligue 1", "sport", "arryadia", "ssc",
        "espn", "nba", "ufc", "rugby", "tennis", "tf1", "m6", "france 2", "france 3", "disney", "aoula", "2m"
    )
    private val NOISE = Regex("""\b(4k|uhd|backup|test|vip|24/7|replay|radio|music|kids)\b""")

    @Volatile
    private var cachedFor = -1L
    @Volatile
    private var cached: List<Item> = emptyList()
    @Volatile
    private var warmedAt = 0L

    /** Chaînes de sport du fournisseur (au plus [limit]), les françaises et HD d'abord. Bloquant. */
    fun candidates(src: Long, limit: Int = 160): List<Item> {
        if (cachedFor == src && cached.isNotEmpty()) return cached
        val seen = HashSet<String>()
        val all = ArrayList<Item>()
        for (h in HINTS) {
            for (it in Db.page(src, Kind.LIVE, null, h, 0, 250)) if (seen.add(it.id)) all.add(it)
        }
        fun score(i: Item): Int {
            val n = normalize(i.name)
            var s = 0
            if (Regex("""\b(fr|france|french)\b""").containsMatchIn(n) || n.startsWith("fr")) s += 6
            if (n.contains("hd") || n.contains("fhd")) s += 1
            if (NOISE.containsMatchIn(n)) s -= 5
            return s
        }
        val list = all.filter { !NOISE.containsMatchIn(normalize(it.name)) || score(it) > 0 }.sortedByDescending { score(it) }.take(limit)
        cached = list
        cachedFor = src
        return list
    }

    /** Chaînes du diffuseur officiel d'une compétition parmi [chans]. */
    fun rightsChannels(league: Sport.League, chans: List<Item>): List<Item> {
        val keys = Sport.RIGHTS[league.path]?.first ?: return emptyList()
        return chans.filter { ch -> val n = normalize(ch.name); keys.any { n.contains(it) } }
    }

    /**
     * Charge le guide des chaînes utiles aux matchs du jour (diffuseurs officiels d'abord).
     * Au plus une fois toutes les 15 minutes.
     */
    fun warmUp(api: XtreamApi, matches: List<Sport.Match>, chans: List<Item>, done: () -> Unit) {
        if (System.currentTimeMillis() - warmedAt < 15 * 60_000L) {
            Bg.main.post(done)
            return
        }
        warmedAt = System.currentTimeMillis()
        pool.execute {
            val wanted = LinkedHashSet<Item>()
            for (m in matches.filter { !it.finished }) wanted.addAll(rightsChannels(m.league, chans))
            wanted.addAll(chans.take(60))
            val tasks = wanted.take(140).map { ch -> Callable { Epg.load(api, ch.id) } }
            try {
                workers.invokeAll(tasks, 90, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
            Bg.main.post(done)
        }
    }

    private val STOP = Regex("""\b(fc|cf|sc|ac|as|ss|afc|club|de|du|la|le|les|real|united|city|sporting|stade|olympique|racing)\b""")

    fun tokens(s: Sport.Side): List<String> {
        val out = LinkedHashSet<String>()
        for (n in listOf(s.name, s.short)) {
            val base = normalize(n).replace(Regex("""[^a-z0-9 ]"""), " ").replace(Regex("""\s+"""), " ").trim()
            val t = STOP.replace(base, " ").replace(Regex("""\s+"""), " ").trim()
            if (t.length >= 4) out.add(t)
            t.split(' ').filter { it.length >= 5 }.maxByOrNull { it.length }?.let { out.add(it) }
        }
        val n = normalize(s.name)
        if (n.contains("paris saint")) out.addAll(listOf("psg", "paris sg", "paris saint germain"))
        if (n.contains("marseille")) out.add("om")
        if (n.contains("lyon")) out.add("ol")
        if (n.contains("manchester city")) out.add("man city")
        if (n.contains("manchester united")) out.add("man united")
        return out.toList()
    }

    fun hit(title: String, toks: List<String>): Boolean {
        val t = " " + normalize(title).replace(Regex("""[^a-z0-9 ]"""), " ").replace(Regex("""\s+"""), " ") + " "
        return toks.any { k -> t.contains(" $k ") || (k.length >= 6 && t.contains(k)) }
    }

    /** Programme qui couvre le match (le direct pour un match en cours, le coup d'envoi sinon). */
    private fun covering(l: List<EpgEntry>, m: Sport.Match): List<EpgEntry> {
        val now = System.currentTimeMillis() / 1000
        val t = if (m.live) now else m.start / 1000
        return l.filter { e -> e.start > 0 && e.end > e.start && t >= e.start - 30 * 60 && t < e.end }
    }

    /** Meilleure chaîne pour ce match. Ne bloque pas : utilise seulement le guide déjà chargé. */
    fun pick(src: Long, m: Sport.Match, chans: List<Item>): Pick? {
        // Recherche complète déjà faite (guide externe, diffuseur, qualité) : on la reprend
        MatchFinder.cached(m)?.let { f -> f.list.firstOrNull()?.let { return Pick(it, f.sure, f.programme) } }
        Db.kvGet("sport:m:${m.key}")?.let { id -> Db.find(src, Kind.LIVE, id)?.let { return Pick(it, true, null) } }
        val ta = tokens(m.home)
        val tb = tokens(m.away)
        val rights = rightsChannels(m.league, chans)
        var best: Pick? = null
        var bestScore = 0
        // Les chaînes du diffuseur officiel passent en premier, à score égal.
        for ((rank, list) in listOf(rights, chans).withIndex()) {
            for (ch in list) {
                val l = Epg.cached(ch.id) ?: continue
                for (e in covering(l, m)) {
                    val a = hit(e.title + " " + e.desc, ta)
                    val b = hit(e.title + " " + e.desc, tb)
                    // Les deux équipes citées = certain ; une seule équipe sur la chaîne du diffuseur = probable.
                    val sc = when {
                        a && b -> 10
                        (a || b) && rank == 0 -> 6
                        else -> 0
                    }
                    if (sc > bestScore) {
                        bestScore = sc
                        best = Pick(ch, sc >= 10, e.title)
                    }
                }
            }
            if (bestScore >= 10) break
        }
        if (best != null) return best
        // Rien dans le guide : on indique au moins le diffuseur officiel, sans inventer de chaîne.
        val label = Sport.RIGHTS[m.league.path]?.second
        val esp = m.broadcasts.firstOrNull()
        return if (label != null || esp != null) Pick(null, false, null, label ?: esp) else null
    }

    fun remember(m: Sport.Match, ch: Item) {
        Db.kvPut("sport:m:${m.key}", ch.id)
    }
}

/** Cotes de plusieurs bookmakers via The Odds API (clé gratuite facultative), mises en cache 5 minutes. */
object OddsApi {
    private val KEYS = mapOf(
        "fra.1" to "soccer_france_ligue_one", "fra.2" to "soccer_france_ligue_two",
        "uefa.champions" to "soccer_uefa_champs_league", "uefa.europa" to "soccer_uefa_europa_league",
        "uefa.europa.conf" to "soccer_uefa_europa_conference_league", "eng.1" to "soccer_epl",
        "esp.1" to "soccer_spain_la_liga", "ita.1" to "soccer_italy_serie_a", "ger.1" to "soccer_germany_bundesliga",
        "nba" to "basketball_nba", "ufc" to "mma_mixed_martial_arts"
    )
    private class Ev(val home: String, val away: String, val books: List<Sport.Book>)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Ev>>>()

    private fun key(n: String) = normalize(n).replace(Regex("[^a-z0-9]"), "")

    private fun same(a: String, b: String): Boolean {
        val x = key(a)
        val y = key(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y || x.contains(y) || y.contains(x)) return true
        val ta = normalize(a).split(' ').filter { it.length >= 5 }.toSet()
        val tb = normalize(b).split(' ').filter { it.length >= 5 }.toSet()
        return ta.intersect(tb).isNotEmpty()
    }

    private fun events(sportKey: String): List<Ev> {
        cache[sportKey]?.let { (at, l) -> if (System.currentTimeMillis() - at < 5 * 60_000L) return l }
        val k = Prefs.oddsKey.trim()
        val url = "https://api.the-odds-api.com/v4/sports/$sportKey/odds?apiKey=${java.net.URLEncoder.encode(k, "UTF-8")}" +
            "&regions=eu&markets=h2h&oddsFormat=decimal"
        val arr = JSONArray(Http.getText(url))
        val out = ArrayList<Ev>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val home = e.str("home_team") ?: continue
            val away = e.str("away_team") ?: continue
            val books = ArrayList<Sport.Book>()
            val bs = e.optJSONArray("bookmakers") ?: JSONArray()
            for (j in 0 until bs.length()) {
                val b = bs.optJSONObject(j) ?: continue
                val outcomes = b.optJSONArray("markets")?.optJSONObject(0)?.optJSONArray("outcomes") ?: continue
                var h: Double? = null
                var a: Double? = null
                var d: Double? = null
                for (o in 0 until outcomes.length()) {
                    val x = outcomes.optJSONObject(o) ?: continue
                    val price = x.optDouble("price", Double.NaN).takeIf { !it.isNaN() }
                    when (x.str("name")) {
                        home -> h = price
                        away -> a = price
                        else -> d = price
                    }
                }
                books.add(Sport.Book(b.str("title") ?: "?", h, d, a))
            }
            out.add(Ev(home, away, books))
        }
        cache[sportKey] = System.currentTimeMillis() to out
        return out
    }

    /** Ajoute les cotes aux matchs en cours ou du jour (clé The Odds API si renseignée, sinon cotes ESPN). */
    fun attach(matches: List<Sport.Match>): List<Sport.Match> {
        if (Prefs.oddsKey.isBlank()) return matches
        val soon = System.currentTimeMillis() + 18 * 3_600_000L
        val needed = matches.filter { !it.finished && it.start < soon }.mapNotNull { KEYS[it.league.path] }.toSet()
        val byKey = HashMap<String, List<Ev>>()
        for (k in needed) {
            try {
                byKey[k] = events(k)
            } catch (_: Exception) {
            }
        }
        return matches.map { m ->
            val evs = byKey[KEYS[m.league.path] ?: ""] ?: return@map m
            val ev = evs.firstOrNull { same(it.home, m.home.name) && same(it.away, m.away.name) }
                ?: evs.firstOrNull { same(it.home, m.away.name) && same(it.away, m.home.name) }
                ?: return@map m
            val swapped = !same(ev.home, m.home.name)
            val books = ev.books.map { b -> if (swapped) b.copy(home = b.away, away = b.home) else b }
            if (books.isEmpty()) return@map m
            m.copy(
                odds = Sport.Odds(
                    books.mapNotNull { it.home }.maxOrNull(), books.mapNotNull { it.draw }.maxOrNull(),
                    books.mapNotNull { it.away }.maxOrNull(), "${books.size} bookmakers", books.sortedBy { it.name }
                )
            )
        }
    }
}
