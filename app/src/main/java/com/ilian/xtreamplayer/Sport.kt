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
        val round: String?
    ) {
        val live: Boolean get() = state == "in"
        val finished: Boolean get() = state == "post"
        val key: String get() = "${league.path}:$id"
        val title: String get() = "${home.short} – ${away.short}"
    }

    val GROUPS = listOf("Tout", "Football", "Basket", "Rugby", "Tennis", "Combat", "Mes équipes")

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

    /** Matchs d'hier soir à demain, toutes compétitions. Bloquant. */
    fun load(): List<Match> {
        val paris = TimeZone.getTimeZone("Europe/Paris")
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = paris }
        val cal = Calendar.getInstance(paris)
        val today = f.format(cal.time)
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val tomorrow = f.format(cal.time)
        cal.add(Calendar.DAY_OF_MONTH, -2)
        val yesterday = f.format(cal.time)

        val jobs = ArrayList<Callable<List<Match>>>()
        for (l in LEAGUES) {
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
        val words = name.split(' ', '-', '.').filter { it.isNotBlank() }
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
            roundOf(e, comp)
        )
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

    fun followed(): Set<String> = Db.kvGet("sport:teams")?.let { s ->
        try {
            val a = JSONArray(s)
            (0 until a.length()).map { a.getString(it) }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    } ?: emptySet()

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
        Db.kvPut("sport:teams", JSONArray(set.toList()).toString())
        return added
    }

    fun involvesFollowed(m: Match): Boolean {
        val f = followed()
        return normalize(m.home.name) in f || normalize(m.away.name) in f
    }
}

/**
 * Trouve la chaîne qui diffuse un match : choix mémorisé, sinon recherche des noms d'équipes
 * dans le guide TV des chaînes de sport du fournisseur.
 */
object SportChannels {
    data class Pick(val channel: Item, val sure: Boolean, val programme: String?)

    private val pool = Executors.newFixedThreadPool(4)
    private val HINTS = listOf(
        "bein", "canal", "rmc", "dazn", "eurosport", "equipe", "prime", "foot", "ligue", "sport", "arryadia", "ssc",
        "espn", "sky", "nba", "ufc", "rugby", "tennis", "match", "arena", "movistar", "la liga", "premier"
    )
    private val STRONG = listOf("bein", "canal", "rmc", "dazn", "eurosport", "equipe", "prime", "arryadia", "ssc", "foot")

    @Volatile
    private var cachedFor = -1L
    @Volatile
    private var cached: List<Item> = emptyList()

    /** Chaînes de sport du fournisseur, les plus probables d'abord (au plus [limit]). Bloquant. */
    fun candidates(src: Long, limit: Int = 120): List<Item> {
        if (cachedFor == src && cached.isNotEmpty()) return cached
        val seen = HashSet<String>()
        val all = ArrayList<Item>()
        for (h in HINTS) {
            for (it in Db.page(src, Kind.LIVE, null, h, 0, 200)) if (seen.add(it.id)) all.add(it)
        }
        fun score(i: Item): Int {
            val n = normalize(i.name)
            var s = 0
            if (STRONG.any { n.contains(it) }) s += 10
            if (Regex("""\b(fr|france)\b""").containsMatchIn(n)) s += 4
            if (n.contains("hd") || n.contains("fhd")) s += 1
            if (Regex("""\b(4k|uhd|backup|test|vip|24/7|replay)\b""").containsMatchIn(n)) s -= 3
            return s
        }
        val list = all.sortedByDescending { score(it) }.take(limit)
        cached = list
        cachedFor = src
        return list
    }

    /** Charge le guide des chaînes candidates en arrière-plan ; [progress] reçoit le nombre chargé. */
    fun warmUp(api: XtreamApi, chans: List<Item>, done: () -> Unit) {
        pool.execute {
            val tasks = chans.map { ch -> Callable { Epg.load(api, ch.id) } }
            try {
                ScopedPool.run(tasks)
            } catch (_: Exception) {
            }
            Bg.main.post(done)
        }
    }

    private object ScopedPool {
        private val workers = Executors.newFixedThreadPool(4)
        fun run(tasks: List<Callable<List<EpgEntry>>>) {
            workers.invokeAll(tasks, 60, TimeUnit.SECONDS)
        }
    }

    private fun tokens(s: Sport.Side): List<String> {
        val out = LinkedHashSet<String>()
        for (n in listOf(s.name, s.short)) {
            val t = normalize(n).replace(Regex("""\b(fc|cf|sc|ac|as|ss|afc|club|de|du|la|le|les|olympique|real|united|city)\b"""), " ")
                .replace(Regex("""[^a-z0-9 ]"""), " ").replace(Regex("""\s+"""), " ").trim()
            if (t.length >= 4) out.add(t)
            // Mot le plus distinctif : « marseille », « barcelona »…
            t.split(' ').filter { it.length >= 5 }.maxByOrNull { it.length }?.let { out.add(it) }
        }
        val abbr = s.abbr.lowercase()
        if (abbr.length >= 3 && abbr in listOf("psg", "om", "ol", "losc", "ogcn", "asse")) out.add(abbr)
        if (normalize(s.name).contains("paris saint")) out.add("psg")
        return out.toList()
    }

    private fun hit(title: String, toks: List<String>): Boolean {
        val t = " " + normalize(title).replace(Regex("""[^a-z0-9 ]"""), " ") + " "
        return toks.any { k -> t.contains(" $k ") || (k.length >= 6 && t.contains(k)) }
    }

    /** Meilleure chaîne pour ce match d'après le guide déjà chargé (ou le choix mémorisé). */
    fun pick(src: Long, m: Sport.Match, chans: List<Item>): Pick? {
        Db.kvGet("sport:m:${m.key}")?.let { id -> Db.find(src, Kind.LIVE, id)?.let { return Pick(it, true, null) } }
        val ta = tokens(m.home)
        val tb = tokens(m.away)
        val startSec = m.start / 1000
        var best: Pick? = null
        var bestScore = 0
        for (ch in chans) {
            val l = Epg.cached(ch.id) ?: continue
            for (e in l) {
                if (e.end <= 0 || e.start <= 0) continue
                if (startSec < e.start - 45 * 60 || startSec > e.end) continue
                val a = hit(e.title, ta)
                val b = hit(e.title, tb)
                val sc = (if (a) 2 else 0) + (if (b) 2 else 0)
                if (sc > bestScore) {
                    bestScore = sc
                    best = Pick(ch, sc >= 4, e.title)
                }
            }
        }
        if (best != null) return best
        Db.kvGet("sport:l:${m.league.path}")?.let { id -> Db.find(src, Kind.LIVE, id)?.let { return Pick(it, false, null) } }
        return null
    }

    fun remember(m: Sport.Match, ch: Item) {
        Db.kvPut("sport:m:${m.key}", ch.id)
        Db.kvPut("sport:l:${m.league.path}", ch.id)
    }
}
