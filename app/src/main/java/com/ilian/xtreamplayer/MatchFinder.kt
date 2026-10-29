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
 * 1. Le guide TV (externe et fournisseur) : programme en cours qui cite les deux équipes, une équipe,
 *    ou le sport / la compétition sur la chaîne du diffuseur officiel.
 * 2. À défaut, le diffuseur officiel en France (beIN, Canal+, Eurosport…).
 * 3. Parmi les versions d'une même chaîne (SD, HD, FHD, 4K, HEVC, +1, backup…), celle qui convient au stick :
 *    FHD en priorité sur un écran HD, 4K seulement si l'écran est 4K ; jamais le décalé « +1 ».
 * 4. Les chaînes dans les langues du profil et dans les catégories affichées passent devant.
 *
 * La liste renvoyée est triée : la première est lancée, les suivantes servent de secours (flèches haut / bas
 * dans le lecteur, ou automatiquement si la chaîne ne marche pas).
 */
object MatchFinder {
    class Found(val list: List<Item>, val sure: Boolean, val programme: String?, val reason: String)

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
        Regex("\\p{M}+").replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT)

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

    // ---------- Mots du sport et de la compétition ----------

    private fun sportWords(m: Sport.Match): List<String> = when (m.league.sport) {
        "soccer" -> listOf("football", "foot", "ligue", "coupe", "championnat", "premier league", "liga", "serie a", "bundesliga", "multiplex")
        "basketball" -> listOf("basket", "nba", "basketball")
        "rugby" -> listOf("rugby", "top 14", "six nations", "champions cup", "tournoi des 6", "xv de france")
        "tennis" -> listOf("tennis", "atp", "wta", "masters", "open", "tournoi")
        "mma" -> listOf("ufc", "mma", "combat", "arts martiaux")
        else -> emptyList()
    }

    private fun compWords(m: Sport.Match): List<String> {
        val out = ArrayList<String>()
        out.add(normalize(m.league.label))
        m.round?.let { r -> normalize(r).split(Regex("[^a-z0-9]+")).filter { it.length >= 5 }.forEach { out.add(it) } }
        return out.filter { it.length >= 3 }
    }

    private fun contains(text: String, words: List<String>): Boolean {
        val t = " " + normalize(text).replace(Regex("[^a-z0-9+ ]"), " ").replace(Regex("\\s+"), " ") + " "
        return words.any { w -> t.contains(" $w ") || (w.length >= 6 && t.contains(w)) }
    }

    // ---------- Recherche ----------

    /** Bloquant : liste des chaînes pour ce match, la meilleure en premier (null si rien de plausible). */
    fun find(ctx: Context, src: Long, m: Sport.Match, sportChans: List<Item>): Found? {
        cached(m)?.let { return it }
        val uhd = uhd(ctx)
        val langs = Session.profile()?.langSet.orEmpty()
        val hidden = Db.categories(src, Kind.LIVE).filter { it.second }.map { it.first.id }.toSet()
        val now = System.currentTimeMillis() / 1000
        val t = if (m.live) now else m.start / 1000
        val ta = SportChannels.tokens(m.home)
        val tb = SportChannels.tokens(m.away)
        val sw = sportWords(m)
        val cw = compWords(m)
        val rightsKeys = Sport.RIGHTS[m.league.path]?.first.orEmpty()
        fun isRights(name: String) = rightsKeys.any { normalize(name).contains(it) }

        /** Note « ce programme montre bien ce match ». */
        fun matchScore(text: String, rights: Boolean): Int {
            val a = SportChannels.hit(text, ta)
            val b = SportChannels.hit(text, tb)
            return when {
                a && b -> 100
                (a || b) && rights -> 75
                a || b -> 55
                contains(text, cw) && rights -> 45
                contains(text, sw) && rights -> 38
                contains(text, cw) -> 30
                contains(text, sw) -> 22
                else -> 0
            }
        }

        // id du flux -> (note du match, programme)
        val scored = HashMap<String, Pair<Int, String?>>()
        fun offer(id: String, score: Int, prog: String?) {
            if (score <= 0) return
            val cur = scored[id]
            if (cur == null || score > cur.first) scored[id] = score to prog
        }

        // 0. Chaîne choisie à la main pour ce match
        Db.kvGet("sport:m:${m.key}")?.let { id -> offer(id, 1_000, null) }

        // 1. Guide externe : tous les programmes en cours (ou au coup d'envoi) de toutes les chaînes reconnues
        try {
            val progs = Db.epgAt(src, t, if (m.live) 0 else 1_800)
            val byCh = HashMap<String, Pair<Int, String>>()
            for ((ch, title, desc) in progs) {
                val streams = Db.epgStreams(src, ch)
                if (streams.isEmpty()) continue
                val rights = streams.firstNotNullOfOrNull { Db.find(src, Kind.LIVE, it) }?.let { isRights(it.name) } == true
                val sc = matchScore("$title $desc", rights)
                if (sc > (byCh[ch]?.first ?: 0)) byCh[ch] = sc to title
            }
            for ((ch, v) in byCh) for (id in Db.epgStreams(src, ch)) offer(id, v.first, v.second)
        } catch (_: Exception) {
        }

        // 2. Guide du fournisseur déjà chargé pour les chaînes de sport
        for (ch in sportChans) {
            val l = Epg.cached(ch.id) ?: continue
            for (e in l) {
                if (e.start <= 0 || e.end <= e.start) continue
                if (t < e.start - (if (m.live) 0 else 1_800) || t >= e.end) continue
                offer(ch.id, matchScore(e.title + " " + e.desc, isRights(ch.name)), e.title)
            }
        }

        // 3. Diffuseur officiel (et chaînes annoncées par ESPN), même sans guide
        val brandHits = ArrayList<Item>()
        for (k in rightsKeys) brandHits.addAll(Db.page(src, Kind.LIVE, null, k, 0, 80))
        for (b in brandHits) offer(b.id, 12, null)
        for (b in m.broadcasts.take(3)) for (it in Db.page(src, Kind.LIVE, null, b, 0, 30)) offer(it.id, 8, null)

        if (scored.isEmpty()) return null

        // Les autres versions d'une chaîne retenue (même nom sans la qualité) sont ajoutées comme secours
        val items = HashMap<String, Item>()
        for (id in scored.keys.toList()) Db.find(src, Kind.LIVE, id)?.let { items[id] = it }
        val bestIds = scored.entries.sortedByDescending { it.value.first }.take(6).map { it.key }
        for (id in bestIds) {
            val it = items[id] ?: continue
            val base = XmlTv.key(it.name)
            if (base.length < 3) continue
            for (v in Db.page(src, Kind.LIVE, null, baseQuery(it.name), 0, 40)) {
                if (XmlTv.key(v.name) == base && !scored.containsKey(v.id)) {
                    items[v.id] = v
                    scored[v.id] = scored[id]!!
                }
            }
        }

        fun extra(i: Item): Int {
            var s = quality(i.name, uhd)
            val d = Langs.detect(i.name)
            if (langs.isNotEmpty()) {
                if (d.any { it in langs }) s += 6 else if (d.isNotEmpty()) s -= 8
            }
            if (i.catId != null && i.catId in hidden) s -= 4 else s += 3
            return s
        }

        val ranked = items.values
            // Jamais les chaînes décalées (+1) pour un direct
            .filter { scored.containsKey(it.id) && quality(it.name, uhd) > -30 }
            .sortedWith(compareByDescending<Item> { scored[it.id]!!.first }.thenByDescending { extra(it) })
            .take(20)
        if (ranked.isEmpty()) return null
        val top = scored[ranked[0].id]!!
        val sure = top.first >= 55
        val reason = when {
            top.first >= 1_000 -> "chaîne choisie pour ce match"
            top.first >= 100 -> "le guide annonce ce match"
            top.first >= 55 -> "le guide annonce une des équipes"
            top.first >= 22 -> "le guide annonce ${m.league.label}"
            else -> "diffuseur habituel de ${m.league.label}"
        }
        val f = Found(ranked, sure, top.second, reason)
        cache[m.key] = System.currentTimeMillis() to f
        return f
    }

    /** Mot le plus parlant du nom d'une chaîne, pour retrouver ses autres versions. */
    private fun baseQuery(name: String): String {
        val words = plain(name).replace(Regex("""^[^a-z0-9]*[a-z]{2,3}\s*[|:]\s*"""), "")
            .split(Regex("[^a-z0-9+]+")).filter { it.length >= 2 && it !in setOf("hd", "fhd", "uhd", "sd", "4k", "raw", "vip", "hevc", "fr") }
        return words.take(2).joinToString(" ").ifBlank { name }
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
            Ui.play(act, ItemList.of(f.list), 0, autoFallback = true)
            val more = if (f.list.size > 1) " · haut / bas : autres chaînes" else ""
            Ui.toast(act, (if (f.sure) first.name else "${first.name} (le plus probable)") + more)
        }
    }

    /** Recherche en arrière-plan pour les matchs en cours et imminents (affichage des cartes). Bloquant. */
    fun prepare(ctx: Context, src: Long, matches: List<Sport.Match>, sportChans: List<Item>) {
        val soon = System.currentTimeMillis() + 3 * 3_600_000L
        for (m in matches.filter { it.live || (it.state == "pre" && it.start < soon) }.take(30)) {
            try {
                find(ctx, src, m, sportChans)
            } catch (_: Exception) {
            }
        }
    }
}
