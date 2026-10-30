package com.ilian.xtreamplayer

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * « Regarder le direct » : trouve tout seul la chaîne qui diffuse un match, dans la bonne qualité.
 *
 * Indices utilisés, du plus sûr au moins sûr :
 * 1. Ton choix pour CE match (ou la chaîne sur laquelle tu es resté après avoir zappé) — jamais pour les autres matchs.
 * 2. Le catalogue : les chaînes « événement » dont le nom cite le match (« PSG vs OM 21:00 », « UFC 310 »…).
 * 3. Le guide TV (externe et fournisseur) : titre, sous-titre, résumé et genre du programme en cours
 *    (les deux équipes, une équipe sur une chaîne de sport, la compétition ou le sport chez le diffuseur officiel).
 * 4. Le diffuseur officiel en France (beIN, Canal+, Eurosport…), en dernier recours.
 * Plusieurs matchs en même temps ne reçoivent pas la même chaîne sans preuve : une chaîne déjà prouvée
 * pour un match passe derrière pour les autres.
 * Puis la version de la chaîne adaptée à l'écran (FHD sur un écran HD, 4K sur un écran 4K, jamais « +1 »),
 * dans la langue du profil.
 */
object MatchFinder {
    class Found(val list: List<Item>, val sure: Boolean, val programme: String?, val reason: String, val score: Int)

    private class Cand(val item: Item, var score: Int, var why: String?)

    private val cache = ConcurrentHashMap<String, Pair<Long, Found>>()

    fun cached(m: Sport.Match): Found? {
        val (at, f) = cache[m.key] ?: return null
        return if (System.currentTimeMillis() - at < 4 * 60_000L) f else null
    }

    fun forget(m: Sport.Match) {
        cache.remove(m.key)
    }

    // ---------- Qualité ----------

    @Volatile
    private var uhdScreen: Boolean? = null

    /** Écran 4K branché sur le stick ? */
    fun uhd(ctx: Context): Boolean {
        uhdScreen?.let { return it }
        val r = try {
            val d = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            d.supportedModes.maxOf { maxOf(it.physicalWidth, it.physicalHeight) } >= 3000
        } catch (_: Exception) {
            false
        }
        uhdScreen = r
        return r
    }

    /** Nom ramené à du texte simple (les « ᴴᴰ », « ʀᴀᴡ »… deviennent « hd », « raw »). */
    private fun plain(s: String): String =
        Rx.MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT)

    private val R4K = Regex("""(^|[^a-z0-9])(4k|uhd|2160p?)([^a-z0-9]|$)""")
    private val RFHD = Regex("""(^|[^a-z0-9])(fhd|1080[pi]?|full ?hd)([^a-z0-9]|$)""")
    private val RHD = Regex("""(^|[^a-z0-9])(hd|720p?)([^a-z0-9]|$)""")
    private val RSD = Regex("""(^|[^a-z0-9])(sd|480p?|576p?|low|lq)([^a-z0-9]|$)""")
    private val RHEVC = Regex("""(^|[^a-z0-9])(hevc|h265|h\.265|x265)([^a-z0-9]|$)""")
    private val RFPS = Regex("""(50|60) ?fps""")
    private val RBAD = Regex("""(^|[^a-z0-9])(backup|bkp|test|alt|old)([^a-z0-9]|$)""")
    private val RPLUS1 = Regex("""(\+ ?1|plus ?1)([^0-9]|$)""")

    /** Note de qualité d'une version de chaîne, adaptée à l'écran. */
    fun quality(name: String, uhd: Boolean): Int {
        val n = plain(name)
        var s = when {
            R4K.containsMatchIn(n) -> if (uhd) 14 else -4
            RFHD.containsMatchIn(n) -> 12
            RHD.containsMatchIn(n) -> 9
            RSD.containsMatchIn(n) -> -6
            else -> 5
        }
        if (RHEVC.containsMatchIn(n)) s += 1
        if (RFPS.containsMatchIn(n)) s += 2
        if (RBAD.containsMatchIn(n)) s -= 8
        if (RPLUS1.containsMatchIn(n)) s -= 40
        return s
    }

    /** Palier de qualité annoncé dans le nom : 4 = 4K, 3 = Full HD, 2 = HD, 1 = SD, 0 = rien d'indiqué. */
    fun tier(name: String): Int {
        val n = plain(name)
        return when {
            R4K.containsMatchIn(n) -> 4
            RFHD.containsMatchIn(n) -> 3
            RHD.containsMatchIn(n) -> 2
            RSD.containsMatchIn(n) -> 1
            else -> 0
        }
    }

    fun isPlus1(name: String): Boolean = RPLUS1.containsMatchIn(plain(name))

    // ---------- Textes ----------

    private fun clean(s: String): String =
        " " + normalize(s).replace(Rx.NON_ALNUM_PLUS_SPACE, " ").replace(Rx.SPACES, " ").trim() + " "

    private fun has(text: String, words: Collection<String>): Boolean =
        words.any { w -> text.contains(" $w ") || (w.length >= 7 && text.contains(w)) }

    private val STOP = setOf(
        "fc", "cf", "sc", "ac", "as", "ss", "afc", "club", "de", "du", "la", "le", "les", "real", "united", "city",
        "sporting", "stade", "olympique", "racing", "rc", "us", "ogc", "aj", "sco", "esp", "the", "and", "et", "cd", "sd", "ud"
    )

    /** Façons d'écrire une équipe ou un joueur dans les guides et les noms de chaînes. */
    private fun names(s: Sport.Side, individual: Boolean): Set<String> {
        val out = LinkedHashSet<String>()
        for (n in listOf(s.name, s.short)) {
            val c = clean(n).trim()
            if (c.isEmpty()) continue
            val words = c.split(' ').filter { it.isNotEmpty() }
            val core = words.filter { it !in STOP }
            if (core.joinToString(" ").length >= 4) out.add(core.joinToString(" "))
            core.filter { it.length >= 5 }.maxByOrNull { it.length }?.let { out.add(it) }
            // Joueurs (tennis, combat) : le nom de famille suffit (« Fils », « Sinner »)
            if (individual) core.lastOrNull()?.takeIf { it.length >= 3 }?.let { out.add(it) }
        }
        // Doubles « A / B » : chaque joueur
        if (individual && s.name.contains('/')) {
            for (p in s.name.split('/')) clean(p).trim().split(' ').lastOrNull()?.takeIf { it.length >= 3 }?.let { out.add(it) }
        }
        val n = normalize(s.name)
        val alias = mapOf(
            "paris saint" to listOf("psg", "paris sg", "paris saint germain"), "marseille" to listOf("om"), "lyon" to listOf("ol"),
            "lille" to listOf("losc"), "manchester city" to listOf("man city"), "manchester united" to listOf("man united", "man utd"),
            "bayern" to listOf("bayern munich"), "internazionale" to listOf("inter", "inter milan"), "inter milan" to listOf("inter"),
            "ac milan" to listOf("milan"), "atletico" to listOf("atletico madrid", "atl madrid"), "tottenham" to listOf("spurs"),
            "wolverhampton" to listOf("wolves"), "borussia dortmund" to listOf("dortmund", "bvb"), "rennais" to listOf("rennes"),
            "brestois" to listOf("brest"), "havre" to listOf("le havre"), "raja" to listOf("raja casablanca"), "wydad" to listOf("wydad casablanca", "wac")
        )
        for ((k, v) in alias) if (n.contains(k)) out.addAll(v)
        return out.filter { it.length >= 2 }.toSet()
    }

    /** Villes des tournois (anglais ESPN -> français des guides). */
    private val CITY = mapOf(
        "basel" to "bale", "vienna" to "vienne", "geneva" to "geneve", "beijing" to "pekin", "munich" to "munich",
        "cologne" to "cologne", "brussels" to "bruxelles", "rome" to "rome", "monte carlo" to "monte carlo",
        "london" to "londres", "turin" to "turin", "seville" to "seville", "warsaw" to "varsovie", "athens" to "athenes"
    )

    private fun sportWords(m: Sport.Match): List<String> = when (m.league.sport) {
        "soccer" -> listOf("football", "foot", "ligue", "coupe", "championnat", "premier league", "liga", "serie a", "bundesliga", "multiplex", "match")
        "basketball" -> listOf("basket", "nba", "basketball")
        "rugby" -> listOf("rugby", "top 14", "six nations", "champions cup", "tournoi des 6", "xv de france")
        "tennis" -> listOf("tennis", "atp", "wta", "masters")
        "mma" -> listOf("ufc", "mma", "combat", "arts martiaux")
        else -> emptyList()
    }

    private fun compWords(m: Sport.Match): List<String> {
        val out = ArrayList<String>()
        out.add(clean(m.league.label).trim())
        val r = clean(m.round ?: "")
        for ((en, fr) in CITY) if (r.contains(" $en ")) { out.add(en); out.add(fr) }
        r.trim().split(' ').filter { it.length >= 6 && it !in setOf("quarterfinal", "semifinal", "qualifying", "round", "matchday") }
            .forEach { out.add(it) }
        return out.filter { it.length >= 3 }
    }

    private val SPORTY = listOf(
        "sport", "bein", "canal+", "canal +", "rmc", "eurosport", "equipe", "dazn", "ligue 1", "ligue1", "foot", "tennis", "rugby", "nba",
        "ufc", "golf", "infosport", "ssc", "arryadia", "espn", "sky", "tnt", "prime", "eleven", "event", "ppv", "match", "arena", "premier", "fight"
    )

    private fun sportyName(name: String): Boolean {
        val n = normalize(name)
        return SPORTY.any { n.contains(it) }
    }

    /** Nom de chaîne qui ressemble à un événement : « A vs B », « A - B », heure… */
    private val EVENT = Regex("""( vs\.? | v | x | - |@|\d{1,2}[:h]\d{2})""")

    // ---------- Guide en cours (calculé une fois pour tous les matchs) ----------

    private class ChanProg(val ch: String, val title: String, val text: String, val streams: List<Item>, val sporty: Boolean)

    private val snaps = ConcurrentHashMap<String, Pair<Long, List<ChanProg>>>()

    private fun snapshot(src: Long, t: Long, ahead: Long): List<ChanProg> {
        val key = "$src:${t / 600}:$ahead"
        snaps[key]?.let { (at, l) -> if (System.currentTimeMillis() - at < 5 * 60_000L) return l }
        val byCh = LinkedHashMap<String, Pair<StringBuilder, String>>()
        try {
            for ((ch, title, desc) in Db.epgAt(src, t, ahead)) {
                val e = byCh.getOrPut(ch) { StringBuilder() to title }
                e.first.append(' ').append(title).append(' ').append(desc)
            }
        } catch (_: Exception) {
        }
        val out = ArrayList<ChanProg>()
        for ((ch, v) in byCh) {
            val streams = Db.epgStreams(src, ch).mapNotNull { Db.find(src, Kind.LIVE, it) }
            if (streams.isEmpty()) continue
            val text = clean(v.first.toString())
            out.add(ChanProg(ch, v.second, text, streams, streams.any { sportyName(it.name) } || has(text, listOf("football", "tennis", "rugby", "basket", "sport", "match", "ligue"))))
        }
        snaps[key] = System.currentTimeMillis() to out
        return out
    }

    // ---------- Recherche ----------

    /** Bloquant : candidats notés pour ce match (sans le tri final). */
    private fun candidates(ctx: Context, src: Long, m: Sport.Match, sportChans: List<Item>): Map<String, Cand> {
        val individual = m.league.sport == "tennis" || m.league.sport == "mma"
        val na = names(m.home, individual)
        val nb = names(m.away, individual)
        val sw = sportWords(m)
        val cw = compWords(m)
        val rightsKeys = Sport.RIGHTS[m.league.path]?.first.orEmpty()
        fun isRights(name: String) = rightsKeys.any { normalize(name).contains(it) }
        val out = HashMap<String, Cand>()
        fun offer(i: Item, score: Int, why: String?) {
            if (score <= 0) return
            val c = out[i.id]
            if (c == null) out[i.id] = Cand(i, score, why)
            else if (score > c.score) {
                c.score = score
                c.why = why
            }
        }

        /** Le programme décrit-il ce match ? */
        fun guideScore(text: String, rights: Boolean, sporty: Boolean): Int {
            val a = has(text, na)
            val b = has(text, nb)
            return when {
                a && b -> 110
                (a || b) && rights -> 80
                (a || b) && sporty -> 68
                has(text, cw) && rights -> 46
                has(text, sw) && rights -> 38
                has(text, cw) && sporty -> 30
                has(text, sw) && sporty -> 20
                else -> 0
            }
        }

        // 1. Choix pour ce match
        Db.kvGet("sport:m:${m.key}")?.let { id -> Db.find(src, Kind.LIVE, id)?.let { offer(it, 1_000, null) } }

        // 2. Chaînes « événement » du catalogue : le nom cite le match
        val probes = (na + nb).filter { it.length >= 4 }.sortedByDescending { it.length }.take(6)
        for (q in probes) {
            for (ch in Db.liveByName(src, q, 80)) {
                val n = clean(ch.name)
                val a = has(n, na)
                val b = has(n, nb)
                when {
                    a && b -> offer(ch, 130, ch.name)
                    (a || b) && EVENT.containsMatchIn(n) && !n.contains(" tv ") -> offer(ch, 85, ch.name)
                }
            }
        }
        if (m.league.sport == "mma") {
            // Soirées UFC : « UFC 310 », « UFC Fight Night »
            val ev = clean(m.round ?: "").trim()
            Rx.UFC_NUM.find(ev)?.let { r ->
                for (ch in Db.liveByName(src, "ufc ${r.groupValues[1]}", 30) + Db.liveByName(src, "ufc${r.groupValues[1]}", 30)) offer(ch, 120, ch.name)
            }
        }

        // 3. Guide externe : tous les programmes en cours (ou autour du coup d'envoi)
        val now = System.currentTimeMillis() / 1000
        val t = if (m.live) now else m.start / 1000
        for (p in snapshot(src, t, if (m.live) 0 else 1_800)) {
            val rights = p.streams.any { isRights(it.name) }
            val sc = guideScore(p.text, rights, p.sporty)
            if (sc > 0) for (s in p.streams) offer(s, sc, p.title)
        }

        // 4. Guide du fournisseur (chaînes de sport déjà chargées)
        for (ch in sportChans) {
            val l = Epg.cached(ch.id) ?: continue
            for (e in l) {
                if (e.start <= 0 || e.end <= e.start) continue
                if (t < e.start - (if (m.live) 0 else 1_800) || t >= e.end) continue
                offer(ch, guideScore(clean(e.title + " " + e.desc), isRights(ch.name), true), e.title)
            }
        }

        // 5. Diffuseur officiel, faute de mieux
        for (k in rightsKeys) for (ch in Db.page(src, Kind.LIVE, null, k, 0, 80)) offer(ch, 12, null)
        for (b in m.broadcasts.take(3)) for (ch in Db.page(src, Kind.LIVE, null, b, 0, 30)) offer(ch, 8, null)

        // Autres versions (qualités) des meilleures chaînes : mêmes indices
        val best = out.values.sortedByDescending { it.score }.take(6)
        for (c in best) {
            val base = XmlTv.key(c.item.name)
            if (base.length < 3) continue
            for (v in Db.liveByName(src, baseQuery(c.item.name), 60)) {
                if (XmlTv.key(v.name) == base) offer(v, c.score, c.why)
            }
        }
        return out
    }

    private fun rank(ctx: Context, src: Long, m: Sport.Match, cands: Map<String, Cand>, penalty: Map<String, Int>): Found? {
        val uhd = uhd(ctx)
        val langs = Session.profile()?.langSet.orEmpty()
        val hidden = Db.categories(src, Kind.LIVE).filter { it.second }.map { it.first.id }.toSet()
        fun extra(i: Item): Int {
            var s = quality(i.name, uhd)
            val d = Langs.detect(i.name)
            if (langs.isNotEmpty()) {
                if (d.any { it in langs }) s += 6 else if (d.isNotEmpty()) s -= 8
            }
            if (i.catId != null && i.catId in hidden) s -= 4 else s += 3
            return s
        }
        fun eff(c: Cand) = c.score - (penalty[XmlTv.key(c.item.name)] ?: 0)
        val ranked = cands.values
            .filter { quality(it.item.name, uhd) > -30 } // jamais le « +1 » pour un direct
            .sortedWith(compareByDescending<Cand> { eff(it) }.thenByDescending { extra(it.item) })
            .take(20)
        if (ranked.isEmpty()) return null
        val top = ranked[0]
        val s = eff(top)
        val reason = when {
            top.score >= 1_000 -> "chaîne choisie pour ce match"
            top.score >= 120 -> "la chaîne porte le nom du match"
            top.score >= 110 -> "le guide annonce ce match"
            top.score >= 68 -> "le guide annonce une des équipes"
            top.score >= 20 -> "le guide annonce ${m.league.label}"
            else -> "diffuseur habituel de ${m.league.label}"
        }
        return Found(ranked.map { it.item }, s >= 68, top.why, reason, s)
    }

    /** Bloquant : liste des chaînes pour ce match, la meilleure en premier (null si rien de plausible). */
    fun find(ctx: Context, src: Long, m: Sport.Match, sportChans: List<Item>): Found? {
        cached(m)?.let { return it }
        val f = rank(ctx, src, m, candidates(ctx, src, m, sportChans), emptyMap()) ?: return null
        cache[m.key] = System.currentTimeMillis() to f
        return f
    }

    /** Mot le plus parlant du nom d'une chaîne, pour retrouver ses autres versions. */
    fun baseQuery(name: String): String {
        val words = plain(name).replace(Rx.LANG_PREFIX, "")
            .split(Rx.NON_ALNUM_PLUS_RUN).filter { it.length >= 2 && it !in setOf("hd", "fhd", "uhd", "sd", "4k", "raw", "vip", "hevc", "fr") }
        return words.take(2).joinToString(" ").ifBlank { name }
    }

    /**
     * Tous les matchs en cours et imminents d'un coup (bloquant), pour que deux matchs simultanés
     * n'héritent pas de la même chaîne sans preuve : les matchs les mieux prouvés choisissent d'abord.
     */
    fun prepare(ctx: Context, src: Long, matches: List<Sport.Match>, sportChans: List<Item>) {
        val soon = System.currentTimeMillis() + 3 * 3_600_000L
        val todo = matches.filter { it.live || (it.state == "pre" && it.start < soon) }.take(30)
        val all = todo.mapNotNull { m ->
            try {
                m to candidates(ctx, src, m, sportChans)
            } catch (_: Exception) {
                null
            }
        }
        // Les mieux prouvés d'abord
        val order = all.sortedByDescending { (_, c) -> c.values.maxOfOrNull { it.score } ?: 0 }
        // chaîne (sans la qualité) -> créneau horaire déjà pris avec une preuve solide
        val claimed = HashMap<String, MutableList<Long>>()
        for ((m, cands) in order) {
            val t = if (m.live) System.currentTimeMillis() / 1000 else m.start / 1000
            val slot = t / 5_400 // tranches d'1 h 30
            val penalty = HashMap<String, Int>()
            for ((base, slots) in claimed) if (slot in slots) penalty[base] = 60
            // Une preuve directe pour ce match n'est jamais pénalisée
            val strong = cands.values.filter { it.score >= 68 }.map { XmlTv.key(it.item.name) }.toSet()
            for (b in strong) penalty.remove(b)
            val f = rank(ctx, src, m, cands, penalty) ?: continue
            cache[m.key] = System.currentTimeMillis() to f
            if (f.score >= 68) f.list.firstOrNull()?.let { claimed.getOrPut(XmlTv.key(it.name)) { ArrayList() }.add(slot) }
        }
    }

    // ---------- Lancement ----------

    /**
     * « Regarder le direct » : cherche (si besoin) puis lance la meilleure chaîne.
     * Match pas encore commencé : indique où et quand il passera (et le lance s'il commence dans 20 min).
     */
    fun watch(act: Activity, src: Long, m: Sport.Match, sportChans: List<Item>, onNothing: () -> Unit) {
        if (cached(m) == null) Ui.toast(act, "Recherche du direct…")
        Bg.task(act, { find(act, src, m, sportChans) }) { r ->
            val f = r.getOrNull()
            val first = f?.list?.firstOrNull()
            if (f == null || first == null) {
                Ui.toast(act, "Aucune chaîne de ta liste ne semble diffuser ce match.")
                onNothing()
                return@task
            }
            val soon = m.live || m.start - System.currentTimeMillis() < 20 * 60_000L
            if (!soon) {
                val at = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(m.start))
                Ui.toast(act, "${m.title} : sur ${first.name} à $at (${f.reason}).")
                return@task
            }
            Session.sportMatchKey = m.key
            SportPrefs.noteWatched(m, 1f)
            Ui.play(act, ItemList.of(f.list), 0, autoFallback = true)
            val more = if (f.list.size > 1) " · haut / bas : autres chaînes" else ""
            Ui.toast(act, (if (f.sure) first.name else "${first.name} (le plus probable)") + more)
        }
    }

    /** Dans le lecteur : la chaîne sur laquelle on est resté pour ce match devient la sienne (pour ce match seulement). */
    fun learn(matchKey: String, ch: Item) {
        Db.kvPut("sport:m:$matchKey", ch.id)
        cache.remove(matchKey)
    }
}
