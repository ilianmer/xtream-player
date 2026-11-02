package com.ilian.xtreamplayer

import android.graphics.Color
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Budget des cotes The Odds API : 500 requêtes gratuites par mois, remises à zéro le 1er.
 * Chaque jour, l'appli s'autorise (requêtes restantes ce matin) ÷ (jours restants dans le mois) :
 * ce qui n'est pas dépensé un jour reste pour les suivants. Le compteur officiel est lu
 * sur chaque réponse (en-têtes x-requests-remaining / x-requests-used).
 */
object OddsBudget {
    const val FREE = 500
    private val paris: TimeZone = TimeZone.getTimeZone("Europe/Paris")

    private fun fmt(p: String) = SimpleDateFormat(p, Locale.US).apply { timeZone = paris }
    private fun day(): String = fmt("yyyyMMdd").format(Date())
    private fun month(): String = fmt("yyyyMM").format(Date())

    /** restantes | utilisées | mois (aaaamm), tel que lu sur la dernière réponse de ce mois-ci. */
    private fun quota(): List<String>? = try {
        Db.kvGet("odds_quota")?.split('|')?.takeIf { it.size >= 3 && it[2] == month() }
    } catch (_: Exception) {
        null
    }

    /** Le compteur officiel a déjà été lu ce mois-ci. */
    fun known(): Boolean = quota() != null

    /** Requêtes restantes ce mois-ci (500 tant qu'aucune réponse n'est arrivée). */
    fun remaining(): Int = quota()?.get(0)?.toIntOrNull() ?: FREE

    /** Total du mois (500 avec la clé gratuite, plus avec une formule payante). */
    fun total(): Int = quota()?.let { q -> (q[0].toIntOrNull() ?: 0) + (q[1].toIntOrNull() ?: 0) }?.takeIf { it > 0 } ?: FREE

    fun spentToday(): Int = try {
        Db.kvGet("odds_spent")?.split('|')?.takeIf { it.size >= 2 && it[0] == day() }?.get(1)?.toIntOrNull() ?: 0
    } catch (_: Exception) {
        0
    }

    fun daysLeft(): Int {
        val c = Calendar.getInstance(paris)
        return (c.getActualMaximum(Calendar.DAY_OF_MONTH) - c.get(Calendar.DAY_OF_MONTH) + 1).coerceAtLeast(1)
    }

    /** Part du jour : ce qui restait ce matin, réparti sur les jours qui restent. */
    fun allowance(): Int {
        val morning = remaining() + spentToday()
        return if (morning <= 3) 0 else (morning / daysLeft()).coerceAtLeast(1)
    }

    fun leftToday(): Int = (allowance() - spentToday()).coerceAtLeast(0)

    fun canSpend(): Boolean = remaining() > 3 && leftToday() > 0

    fun exhaustedNow(): Boolean = known() && remaining() <= 3

    @Synchronized
    fun record(remaining: Int?, used: Int?, cost: Int) {
        try {
            if (remaining != null) Db.kvPut("odds_quota", "$remaining|${used ?: (FREE - remaining).coerceAtLeast(0)}|${month()}")
            if (cost > 0) Db.kvPut("odds_spent", "${day()}|${spentToday() + cost}")
        } catch (_: Exception) {
        }
    }

    /** Réponse « quota atteint » : plus aucune requête jusqu'au 1er. */
    fun exhausted() {
        val used = quota()?.get(1)?.toIntOrNull()
        record(0, used ?: FREE, 0)
    }

    fun nextMonthName(): String {
        val c = Calendar.getInstance(paris)
        c.add(Calendar.MONTH, 1)
        return SimpleDateFormat("MMMM", Locale.FRANCE).format(c.time)
    }

    /** Ligne des Réglages : « 432 restantes sur 500 · environ 15 par jour jusqu'au 1er décembre ». */
    fun label(): String = when {
        !known() -> "Compteur lu à la prochaine mise à jour des cotes · ${FREE} par mois, remis à zéro le 1er"
        remaining() <= 3 -> "Quota du mois atteint : les cotes reviennent le 1er ${nextMonthName()}"
        else -> "${remaining()} restantes sur ${total()} · environ ${allowance()} par jour jusqu'au 1er ${nextMonthName()}"
    }
}

/**
 * Cotes des bookmakers français (Winamax, Betclic, Unibet, PMU, NetBet) via The Odds API.
 * Une requête donne les cotes de tous les matchs d'un championnat. Elles sont gardées sur l'appareil
 * (elles survivent à un redémarrage) et rechargées selon le moment : toutes les 6 h avant le jour du match,
 * toutes les heures dans les 2 h qui précèdent, toutes les 15 à 30 min pendant le match selon le budget.
 * Le réseau n'est utilisé que depuis l'onglet Sport ou le widget « Cotes en direct », quand ils sont à l'écran.
 */
object OddsApi {
    private val KEYS = mapOf(
        "fra.1" to "soccer_france_ligue_one", "fra.2" to "soccer_france_ligue_two",
        "fra.coupe_de_france" to "soccer_france_coupe_de_france",
        "uefa.champions" to "soccer_uefa_champs_league", "uefa.europa" to "soccer_uefa_europa_league",
        "uefa.europa.conf" to "soccer_uefa_europa_conference_league", "eng.1" to "soccer_epl",
        "esp.1" to "soccer_spain_la_liga", "ita.1" to "soccer_italy_serie_a", "ger.1" to "soccer_germany_bundesliga",
        "uefa.nations" to "soccer_uefa_nations_league", "fifa.worldq.uefa" to "soccer_fifa_world_cup_qualifiers_europe",
        "caf.nations" to "soccer_africa_cup_of_nations",
        "nba" to "basketball_nba", "180659" to "rugbyunion_six_nations", "ufc" to "mma_mixed_martial_arts"
    )

    private class Ev(val home: String, val away: String, val t: Long, val books: List<Sport.Book>)
    private class Data(val at: Long, val evs: List<Ev>)

    private val EMPTY = Data(0L, emptyList())
    private val mem = ConcurrentHashMap<String, Data>()

    /** Dernier souci : « key » (clé refusée), « quota » (quota du mois atteint), sinon null. */
    @Volatile
    var problem: String? = null
        private set

    /** Nouvelle clé : on oublie le refus de l'ancienne. */
    fun keyChanged() {
        problem = null
    }

    private fun data(k: String): Data? {
        val d = mem.getOrPut(k) { readStore(k) ?: EMPTY }
        return if (d === EMPTY) null else d
    }

    private fun writeStore(k: String, d: Data) {
        val a = JSONArray()
        for (e in d.evs) {
            val bs = JSONArray()
            for (b in e.books) bs.put(JSONArray().put(b.name).put(b.home ?: 0.0).put(b.draw ?: 0.0).put(b.away ?: 0.0))
            a.put(JSONArray().put(e.home).put(e.away).put(e.t).put(bs))
        }
        try {
            Db.kvPut("odds:k:$k", "${d.at}|$a")
        } catch (_: Exception) {
        }
    }

    private fun readStore(k: String): Data? {
        return try {
            val raw = Db.kvGet("odds:k:$k") ?: return null
            val at = raw.substringBefore('|').toLongOrNull() ?: return null
            val a = JSONArray(raw.substringAfter('|'))
            val evs = ArrayList<Ev>()
            for (i in 0 until a.length()) {
                val e = a.optJSONArray(i) ?: continue
                val bs = e.optJSONArray(3) ?: continue
                val books = ArrayList<Sport.Book>()
                for (j in 0 until bs.length()) {
                    val b = bs.optJSONArray(j) ?: continue
                    fun v(x: Int) = b.optDouble(x, 0.0).takeIf { it > 1.0 }
                    books.add(Sport.Book(b.optString(0), v(1), v(2), v(3)))
                }
                evs.add(Ev(e.optString(0), e.optString(1), e.optLong(2), books))
            }
            Data(at, evs)
        } catch (_: Exception) {
            null
        }
    }

    private val SUFFIX = Regex("""\s*\((fr|FR|Fr)\)\s*$""")

    private fun parse(arr: JSONArray): List<Ev> {
        val out = ArrayList<Ev>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val home = e.str("home_team") ?: continue
            val away = e.str("away_team") ?: continue
            val t = e.optLong("commence_time", 0L)
            val books = ArrayList<Sport.Book>()
            val bs = e.optJSONArray("bookmakers") ?: JSONArray()
            for (j in 0 until bs.length()) {
                val b = bs.optJSONObject(j) ?: continue
                val markets = b.optJSONArray("markets") ?: continue
                var outcomes: JSONArray? = null
                for (x in 0 until markets.length()) {
                    val mk = markets.optJSONObject(x) ?: continue
                    if (mk.optString("key") == "h2h") {
                        outcomes = mk.optJSONArray("outcomes")
                        break
                    }
                }
                val oc = outcomes ?: continue
                var h: Double? = null
                var a: Double? = null
                var d: Double? = null
                for (o in 0 until oc.length()) {
                    val x = oc.optJSONObject(o) ?: continue
                    val price = x.optDouble("price", Double.NaN).takeIf { !it.isNaN() && it > 1.0 }
                    when (x.str("name")) {
                        home -> h = price
                        away -> a = price
                        else -> d = price
                    }
                }
                if (h == null || a == null) continue
                val name = (b.str("title") ?: b.str("key") ?: "?").replace(SUFFIX, "")
                books.add(Sport.Book(name, h, d, a))
            }
            if (books.isNotEmpty()) out.add(Ev(home, away, t, books.sortedBy { it.name }))
        }
        return out
    }

    /** Une requête (un championnat). Renvoie true si de nouvelles cotes sont arrivées. */
    private fun fetch(k: String): Boolean {
        val key = Prefs.oddsKey.trim()
        if (key.isEmpty()) return false
        val url = "https://api.the-odds-api.com/v4/sports/$k/odds?apiKey=${URLEncoder.encode(key, "UTF-8")}" +
            "&regions=fr&markets=h2h&oddsFormat=decimal&dateFormat=unix"
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "XtreamPlayer")
            val code = c.responseCode
            fun header(n: String) = c.getHeaderField(n)?.trim()?.toDoubleOrNull()?.toInt()
            val rem = header("x-requests-remaining")
            val used = header("x-requests-used")
            val last = header("x-requests-last")
            if (code >= 400) {
                val msg = (try {
                    c.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (_: Exception) {
                    null
                }).orEmpty()
                OddsBudget.record(rem, used, 0)
                if (msg.contains("quota", true) || msg.contains("credits", true) || msg.contains("usage", true)) {
                    OddsBudget.exhausted()
                    problem = "quota"
                } else if (code == 401) {
                    problem = "key"
                }
                return false
            }
            val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            OddsBudget.record(rem, used, last ?: 1)
            val d = Data(System.currentTimeMillis(), parse(JSONArray(body)))
            mem[k] = d
            writeStore(k, d)
            problem = null
            return true
        } finally {
            c.disconnect()
        }
    }

    /** Pendant les matchs : toutes les 15 min si la part du jour est large, jusqu'à 30 min quand elle se resserre. */
    private fun liveEvery(): Long = when (OddsBudget.leftToday()) {
        in 8..Int.MAX_VALUE -> 15 * 60_000L
        in 4..7 -> 20 * 60_000L
        else -> 30 * 60_000L
    }

    /**
     * Met à jour ce qui en a besoin, dans la limite du budget du jour : d'abord les championnats
     * sans aucune cote, puis ceux de tes équipes et des grandes affiches. Bloquant (réseau).
     * Renvoie true si de nouvelles cotes sont arrivées.
     */
    @Synchronized
    fun refresh(matches: List<Sport.Match>): Boolean {
        if (!Prefs.oddsEnabled || Prefs.oddsKey.isBlank()) return false
        if (OddsBudget.exhaustedNow()) return false
        val now = System.currentTimeMillis()
        val byKey = HashMap<String, MutableList<Sport.Match>>()
        for (m in matches) {
            val k = KEYS[m.league.path] ?: continue
            if (m.finished) continue
            if (!m.live && m.start - now > 24 * 3_600_000L) continue
            byKey.getOrPut(k) { ArrayList() }.add(m)
        }
        val due = ArrayList<Pair<String, Int>>()
        for ((k, ms) in byKey) {
            val d = data(k)
            val age = if (d == null) Long.MAX_VALUE else now - d.at
            val live = ms.any { it.live }
            val next = ms.filter { !it.live }.minOfOrNull { it.start } ?: Long.MAX_VALUE
            val every = when {
                live -> liveEvery()
                next - now < 2 * 3_600_000L -> 60 * 60_000L
                else -> 6 * 3_600_000L
            }
            if (age < every) continue
            val prio = (if (d == null) 1_000 else 0) +
                ms.maxOf { (if (Sport.involvesFollowed(it)) 50 else 0) + Sport.trend(it) } + (if (live) 10 else 0)
            due.add(k to prio)
        }
        var got = false
        for ((k, _) in due.sortedByDescending { it.second }) {
            if (!OddsBudget.canSpend()) break
            try {
                if (fetch(k)) got = true
            } catch (_: Exception) {
            }
            if (problem != null) break
        }
        return got
    }

    // ---------- Rapprochement des équipes (ESPN d'un côté, bookmakers de l'autre) ----------

    private val ALIAS: Map<String, String> by lazy {
        mapOf(
            "internazionale" to "inter", "inter milan" to "inter", "fc internazionale milano" to "inter",
            "stade rennais" to "rennes", "stade rennais fc" to "rennes", "paris saint germain" to "psg", "paris sg" to "psg",
            "bayern munchen" to "bayern munich", "fc bayern munchen" to "bayern munich",
            "man city" to "manchester city", "man utd" to "manchester united", "manchester utd" to "manchester united",
            "wolves" to "wolverhampton wanderers", "wolverhampton" to "wolverhampton wanderers", "spurs" to "tottenham",
            "tottenham hotspur" to "tottenham", "nottm forest" to "nottingham forest", "athletic club" to "athletic bilbao",
            "olympique lyonnais" to "lyon", "olympique de marseille" to "marseille", "olympique marseille" to "marseille",
            "as monaco" to "monaco", "ogc nice" to "nice", "rc lens" to "lens", "losc lille" to "lille", "lille osc" to "lille",
            "rc strasbourg alsace" to "strasbourg", "rc strasbourg" to "strasbourg", "stade brestois 29" to "brest",
            "stade brestois" to "brest", "fc nantes" to "nantes", "le havre ac" to "le havre", "aj auxerre" to "auxerre",
            "angers sco" to "angers", "fc lorient" to "lorient", "fc metz" to "metz", "toulouse fc" to "toulouse",
            "atletico de madrid" to "atletico madrid", "fc barcelona" to "barcelona", "real betis balompie" to "real betis",
            "borussia monchengladbach" to "monchengladbach", "borussia dortmund" to "dortmund", "bayer leverkusen" to "leverkusen",
            "bayer 04 leverkusen" to "leverkusen", "ac milan" to "milan", "ssc napoli" to "napoli", "as roma" to "roma",
            "juventus fc" to "juventus", "brighton hove albion" to "brighton", "west ham united" to "west ham",
            "newcastle united" to "newcastle", "leeds united" to "leeds"
        )
    }

    /** Mots trop courants pour reconnaître une équipe à eux seuls (« Paris » FC ou Saint-Germain…). */
    private val GENERIC = setOf(
        "club", "football", "calcio", "real", "united", "city", "saint", "paris", "olympique", "athletic", "atletico",
        "sporting", "stade", "racing", "union", "town", "county", "rovers", "wanderers", "albion", "hotspur", "borussia",
        "milan", "inter", "madrid", "manchester", "london", "sport", "sports", "team"
    )

    private fun canon(n: String): String =
        Rx.NON_ALNUM_RUN.replace(normalize(n), " ").trim().split(' ').filter { it.isNotEmpty() && it != "and" }.joinToString(" ")

    private val teamKeys = ConcurrentHashMap<String, String>()

    private fun teamKey(n: String): String = teamKeys.getOrPut(n) {
        val c = canon(n)
        ALIAS[c] ?: c
    }

    private fun sameName(a: String, b: String): Boolean {
        val x = teamKey(a)
        val y = teamKey(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val cx = x.replace(" ", "")
        val cy = y.replace(" ", "")
        val s = if (cx.length <= cy.length) cx else cy
        val l = if (cx.length <= cy.length) cy else cx
        if (s.length >= 4 && s !in GENERIC && l.contains(s)) return true
        val ta = x.split(' ').filter { it.length >= 4 && it !in GENERIC }
        val tb = y.split(' ').filter { it.length >= 4 && it !in GENERIC }
        return ta.any { w -> tb.any { v -> v == w || (minOf(v.length, w.length) >= 5 && (v.startsWith(w) || w.startsWith(v))) } }
    }

    private fun sameTeam(n: String, s: Sport.Side) = sameName(n, s.name) || sameName(n, s.short)

    /** Le match des bookmakers qui correspond : les deux équipes, ou une seule avec la même heure de coup d'envoi. */
    private fun find(evs: List<Ev>, m: Sport.Match): Ev? {
        var best: Ev? = null
        var bestScore = 0
        for (e in evs) {
            val dt = if (e.t > 0 && m.start > 0) abs(e.t * 1000 - m.start) else 0L
            if (dt > 3 * 3_600_000L) continue
            val direct = (if (sameTeam(e.home, m.home)) 1 else 0) + (if (sameTeam(e.away, m.away)) 1 else 0)
            val swap = (if (sameTeam(e.home, m.away)) 1 else 0) + (if (sameTeam(e.away, m.home)) 1 else 0)
            val teams = maxOf(direct, swap)
            if (teams == 0) continue
            if (teams == 1 && dt > 20 * 60_000L) continue
            val score = teams * 100 - (dt / 60_000L).toInt()
            if (score > bestScore) {
                bestScore = score
                best = e
            }
        }
        return best
    }

    /** Pose les cotes déjà connues sur les matchs (sans réseau). */
    fun attachCached(matches: List<Sport.Match>): List<Sport.Match> {
        if (!Prefs.oddsEnabled) return matches
        val keyed = Prefs.oddsKey.isNotBlank()
        return matches.map { m ->
            val d = if (keyed) KEYS[m.league.path]?.let { data(it) } else null
            val ev = d?.let { find(it.evs, m) }
            val out = if (d != null && ev != null) {
                val swapped = !sameTeam(ev.home, m.home) && sameTeam(ev.home, m.away)
                val books = ev.books.map { b -> if (swapped) b.copy(home = b.away, away = b.home) else b }
                if (books.isEmpty()) m else m.copy(
                    odds = Sport.Odds(
                        books.mapNotNull { it.home }.maxOrNull(), books.mapNotNull { it.draw }.maxOrNull(),
                        books.mapNotNull { it.away }.maxOrNull(), "${books.size} bookmakers", books, d.at
                    )
                )
            } else m
            OddsMath.note(out)
            out
        }
    }
}

/** Calculs sur les cotes : chances de chaque issue, meilleures cotes, étiquettes, couleurs des équipes. */
object OddsMath {
    /** Chances (0 à 1) de victoire à domicile, de nul (null sans nul possible) et de victoire à l'extérieur. */
    class Probs(val home: Double, val draw: Double?, val away: Double)

    enum class Tag(val label: String, val short: String) {
        TIGHT("Match serré", "Serré"),
        FAVORITE("Grand favori", "Gros favori"),
        UPSET("Exploit en cours", "Exploit"),
        SURPRISE("Surprise", "Surprise")
    }

    fun fmt(d: Double?): String = if (d == null) "–" else String.format(Locale.FRANCE, "%.2f", d)

    fun pct(p: Double): String = "${Math.round(p * 100)} %"

    fun books(o: Sport.Odds): List<Sport.Book> = o.books.ifEmpty { listOf(Sport.Book(o.source, o.home, o.draw, o.away)) }

    /** Chances « justes » : la marge de chaque bookmaker est retirée, puis on fait la moyenne. */
    fun probs(o: Sport.Odds?): Probs? {
        o ?: return null
        var n = 0
        var sh = 0.0
        var sd = 0.0
        var sa = 0.0
        var anyDraw = false
        for (b in books(o)) {
            val h = b.home ?: continue
            val a = b.away ?: continue
            if (h <= 1.0 || a <= 1.0) continue
            val d = b.draw?.takeIf { it > 1.0 }
            val ih = 1 / h
            val ia = 1 / a
            val id = if (d != null) 1 / d else 0.0
            val s = ih + ia + id
            sh += ih / s
            sa += ia / s
            sd += id / s
            if (d != null) anyDraw = true
            n++
        }
        if (n == 0) return null
        return Probs(sh / n, if (anyDraw) sd / n else null, sa / n)
    }

    /** Meilleure cote de chaque issue (1, N, 2). */
    fun best(o: Sport.Odds): Triple<Double?, Double?, Double?> {
        val b = books(o)
        return Triple(b.mapNotNull { it.home }.maxOrNull(), b.mapNotNull { it.draw }.maxOrNull(), b.mapNotNull { it.away }.maxOrNull())
    }

    // ---------- Instantanés d'avant-match ----------

    /** Première cote vue (pour les flèches ↑ ↓) et dernière avant le coup d'envoi (pour exploit et surprise). */
    private class Snap(val firstAt: Long, val first: DoubleArray, val last: DoubleArray)

    private val NONE = Snap(0L, DoubleArray(0), DoubleArray(0))
    private val snaps = ConcurrentHashMap<String, Snap>()

    private fun snap(key: String): Snap? {
        val s = snaps.getOrPut(key) { read(key) ?: NONE }
        return if (s === NONE || s.last.size < 6) null else s
    }

    private fun read(key: String): Snap? = try {
        Db.kvGet("odds:m:$key")?.let { raw ->
            val at = raw.substringBefore('|').toLong()
            val parts = raw.substringAfter('|').split(';')
            fun arr(s: String) = s.split(',').map { it.toDouble() }.toDoubleArray()
            Snap(at, arr(parts[0]), arr(parts[1]))
        }
    } catch (_: Exception) {
        null
    }

    private fun r4(v: Double) = Math.round(v * 10_000) / 10_000.0

    /** Garde l'instantané d'avant-match d'un match (seulement s'il a changé). */
    fun note(m: Sport.Match) {
        if (m.state != "pre") return
        val o = m.odds ?: return
        val p = probs(o) ?: return
        val (bh, bd, ba) = best(o)
        val arr = doubleArrayOf(r4(p.home), r4(p.draw ?: -1.0), r4(p.away), bh ?: -1.0, bd ?: -1.0, ba ?: -1.0)
        val old = snap(m.key)
        if (old != null && old.last.contentEquals(arr)) return
        val s = Snap(old?.firstAt ?: System.currentTimeMillis(), old?.first ?: arr, arr)
        snaps[m.key] = s
        try {
            Db.kvPut("odds:m:${m.key}", "${s.firstAt}|${s.first.joinToString(",")};${s.last.joinToString(",")}")
        } catch (_: Exception) {
        }
    }

    /** Chances d'avant-match (pour un match en cours ou terminé). */
    fun preMatch(m: Sport.Match): Probs? {
        val s = snap(m.key)
        if (s != null) return Probs(s.last[0], s.last[1].takeIf { it >= 0 }, s.last[2])
        // Cotes ESPN : ce sont celles d'avant le match
        val o = m.odds
        return if (o != null && o.books.isEmpty()) probs(o) else null
    }

    /** Meilleures cotes vues la première fois (1, N, 2), pour dire si elles ont bougé. */
    fun firstBest(m: Sport.Match): Triple<Double?, Double?, Double?>? {
        val s = snap(m.key) ?: return null
        return Triple(s.first[3].takeIf { it > 1 }, s.first[4].takeIf { it > 1 }, s.first[5].takeIf { it > 1 })
    }

    /** Écart minimal entre les scores pour parler d'exploit (un panier ou un essai ne suffit pas). */
    private fun margin(m: Sport.Match) = when (m.league.sport) {
        "basketball" -> 10
        "rugby" -> 7
        else -> 1
    }

    /** Étiquette qui aide à choisir quoi regarder. */
    fun tag(m: Sport.Match): Tag? {
        if (!Prefs.oddsEnabled) return null
        val sa = m.home.score?.toIntOrNull()
        val sb = m.away.score?.toIntOrNull()
        if (m.finished || m.live) {
            val p = preMatch(m) ?: return null
            if (sa == null || sb == null || abs(sa - sb) < margin(m)) return null
            val lead = if (sa > sb) p.home else p.away
            val other = if (sa > sb) p.away else p.home
            if (lead > 0.30 || lead >= other) return null
            return if (m.live) Tag.UPSET else Tag.SURPRISE
        }
        val p = probs(m.odds) ?: return null
        val top = maxOf(p.home, p.away)
        return when {
            top >= 0.75 -> Tag.FAVORITE
            abs(p.home - p.away) <= 0.08 || (p.draw != null && top <= 0.40) -> Tag.TIGHT
            else -> null
        }
    }

    /** « il y a 12 min » / « à 14:05 » : fraîcheur des cotes. */
    fun age(at: Long): String {
        if (at <= 0L) return ""
        val min = (System.currentTimeMillis() - at) / 60_000L
        return when {
            min < 1 -> "à l'instant"
            min < 60 -> "il y a $min min"
            else -> "à " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(at))
        }
    }

    // ---------- Couleurs ----------

    private const val HOME = 0xFF5AA9FF.toInt()
    private const val AWAY = 0xFFFF8A65.toInt()
    private const val ALT = 0xFF4FD1C5.toInt()

    /** Couleurs des deux équipes, lisibles sur le verre sombre et bien distinctes l'une de l'autre. */
    fun colors(m: Sport.Match): Pair<Int, Int> {
        val h = vivid(m.home.color) ?: HOME
        var a = vivid(m.away.color) ?: AWAY
        if (close(h, a)) a = if (close(h, AWAY)) ALT else AWAY
        return h to a
    }

    private fun vivid(c: Int?): Int? {
        c ?: return null
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        if (hsv[1] < 0.18f) return 0xFFE8EAEE.toInt()
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        hsv[2] = hsv[2].coerceAtLeast(0.9f)
        return Color.HSVToColor(hsv)
    }

    private fun close(x: Int, y: Int): Boolean {
        val a = FloatArray(3)
        val b = FloatArray(3)
        Color.colorToHSV(x, a)
        Color.colorToHSV(y, b)
        val greyA = a[1] < 0.2f
        val greyB = b[1] < 0.2f
        if (greyA || greyB) return greyA && greyB
        val dh = abs(a[0] - b[0]).let { minOf(it, 360f - it) }
        return dh < 30f
    }
}
