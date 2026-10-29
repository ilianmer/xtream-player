package com.ilian.xtreamplayer

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Onglet Sport : matchs en direct, à venir et résultats, avec un raccourci vers la chaîne qui les diffuse. */
class SportScreen(
    private val act: Activity,
    root: View,
    private val src: Source,
    private val api: XtreamApi,
    /** Fond d'écran du sport choisi (football, basket…). */
    private val onTheme: (Int) -> Unit
) {
    private val chips: LinearLayout = root.findViewById(R.id.sportChips)
    private val updated: TextView = root.findViewById(R.id.sportUpdated)
    private val liveCount: TextView = root.findViewById(R.id.liveCount)
    private val rvLive: RecyclerView = root.findViewById(R.id.rvLive)
    private val rvUpcoming: RecyclerView = root.findViewById(R.id.rvUpcoming)
    private val rvResults: RecyclerView = root.findViewById(R.id.rvResults)
    private val liveEmpty: TextView = root.findViewById(R.id.liveEmpty)
    private val upcomingEmpty: TextView = root.findViewById(R.id.upcomingEmpty)
    private val resultsEmpty: TextView = root.findViewById(R.id.resultsEmpty)
    private val btnTeams: TextView = root.findViewById(R.id.btnTeams)

    private var all: List<Sport.Match> = emptyList()
    private var filter = "Tout"
    private var loading = false
    private var visible = false
    private var pendingSport: String? = null
    private val switchSport = Runnable {
        val g = pendingSport ?: return@Runnable
        if (visible && g != filter && chips.findViewWithTag<View>(g)?.hasFocus() == true) setFilter(g)
    }

    private var chans: List<Item> = emptyList()
    private var guideReady = false
    private val picks = HashMap<String, SportChannels.Pick?>()

    private val liveAdapter = MatchAdapter(R.layout.item_match_live)
    private val upcomingAdapter = MatchAdapter(R.layout.item_match_row)
    private val resultAdapter = MatchAdapter(R.layout.item_match_result)

    private val refresh = object : Runnable {
        override fun run() {
            if (!visible) return
            // Toutes les minutes s'il y a des matchs en cours, sinon toutes les 5 minutes.
            val every = if (all.any { it.live }) 60_000L else 300_000L
            if (Sport.cachedAge() >= every - 1_000) load()
            paintUpdated()
            Bg.main.postDelayed(this, 20_000)
        }
    }

    init {
        rvLive.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        rvLive.adapter = liveAdapter
        rvLive.itemAnimator = null
        Fx.lockRow(rvLive, escapeLeft = false)
        rvUpcoming.layoutManager = LinearLayoutManager(act)
        rvUpcoming.adapter = upcomingAdapter
        rvUpcoming.itemAnimator = null
        rvResults.layoutManager = LinearLayoutManager(act)
        rvResults.adapter = resultAdapter
        rvResults.itemAnimator = null
        resultsEmpty.text = "Aucun résultat pour l'instant."
        for (g in Sport.GROUPS) {
            val tv = act.layoutInflater.inflate(R.layout.item_sport_tab, chips, false) as TextView
            tv.text = g
            tv.tag = g
            tv.isSelected = g == filter
            // Comme les onglets du haut : on change de sport en passant dessus, OK descend dans la liste.
            tv.setOnFocusChangeListener { _, has ->
                Bg.main.removeCallbacks(switchSport)
                if (has && g != filter) {
                    pendingSport = g
                    Bg.main.postDelayed(switchSport, 450)
                }
            }
            tv.setOnClickListener {
                Bg.main.removeCallbacks(switchSport)
                if (g == "Mes équipes" && Sport.followed().isEmpty()) {
                    act.startActivity(Intent(act, TeamsActivity::class.java))
                    return@setOnClickListener
                }
                if (g != filter) setFilter(g)
                (rvLive.getChildAt(0) ?: rvUpcoming.getChildAt(0))?.requestFocus()
            }
            chips.addView(tv)
        }
        btnTeams.setOnClickListener { act.startActivity(Intent(act, TeamsActivity::class.java)) }
        // En arrivant sur la barre des sports depuis ailleurs, on se pose sur le sport en cours.
        root.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            if (new != null && new.parent === chips && (old == null || old.parent !== chips) && new.tag != filter) {
                chips.findViewWithTag<View>(filter)?.requestFocus()
            }
        }
    }

    private fun setFilter(g: String) {
        filter = g
        for (i in 0 until chips.childCount) chips.getChildAt(i).let { c -> c.isSelected = c.tag == g }
        onTheme(theme())
        paint()
    }

    fun theme(): Int = when (filter) {
        "Football" -> R.drawable.bg_sport_foot
        "Basket" -> R.drawable.bg_sport_basket
        "Rugby" -> R.drawable.bg_sport_rugby
        "Tennis" -> R.drawable.bg_sport_tennis
        "Combat" -> R.drawable.bg_sport_combat
        else -> R.drawable.bg_sport_all
    }

    fun show() {
        visible = true
        onTheme(theme())
        all = Sport.cached()
        if (all.isNotEmpty()) {
            computePicks()
            paint()
        }
        if (Sport.cachedAge() > 60_000) load()
        Bg.main.removeCallbacks(refresh)
        Bg.main.postDelayed(refresh, 20_000)
    }

    fun hide() {
        visible = false
        Bg.main.removeCallbacks(refresh)
        Bg.main.removeCallbacks(switchSport)
    }

    fun focusDefault() {
        val target = rvLive.getChildAt(0) ?: rvUpcoming.getChildAt(0) ?: chips.getChildAt(0)
        target?.requestFocus()
    }

    private fun load() {
        if (loading) return
        loading = true
        if (all.isEmpty()) {
            liveEmpty.visibility = View.VISIBLE
            liveEmpty.text = "Chargement des scores…"
        }
        Bg.task(act, {
            val matches = Sport.get(30_000L)
            val c = SportChannels.candidates(src.id)
            matches to c
        }) { r ->
            loading = false
            r.onSuccess { (matches, c) ->
                all = matches
                chans = c
                computePicks()
                paint()
                // Le guide TV des chaînes de sport permet de trouver qui diffuse quoi.
                if (Prefs.epgEnabled && c.isNotEmpty()) {
                    SportChannels.warmUp(api, matches, c) {
                        guideReady = true
                        if (!act.isDestroyed && visible) {
                            computePicks()
                            paint()
                        }
                    }
                }
            }.onFailure {
                if (all.isEmpty()) liveEmpty.text = "Scores indisponibles : ${Bg.err(it)}"
            }
        }
    }

    private fun computePicks() {
        picks.clear()
        for (m in all) if (!m.finished) picks[m.key] = SportChannels.pick(src.id, m, chans)
    }

    private fun matches(): List<Sport.Match> = when (filter) {
        "Tout" -> all
        "Mes équipes" -> all.filter { Sport.involvesFollowed(it) }
        else -> all.filter { it.league.group == filter }
    }

    private fun paint() {
        val list = matches()
        val live = list.filter { it.live }.sortedByDescending { Sport.trend(it) }
        val upcoming = list.filter { it.state == "pre" }.sortedWith(compareByDescending<Sport.Match> { Sport.involvesFollowed(it) }.thenBy { it.start })
        val results = list.filter { it.finished }.sortedByDescending { it.start }
        liveAdapter.set(live)
        upcomingAdapter.set(upcoming)
        resultAdapter.set(results)
        liveCount.text = when (live.size) {
            0 -> ""
            1 -> "1 match"
            else -> "${live.size} matchs"
        }
        liveEmpty.visibility = if (live.isEmpty()) View.VISIBLE else View.GONE
        if (live.isEmpty() && !loading) liveEmpty.text = when {
            filter == "Mes équipes" && Sport.followed().isEmpty() -> "Aucune équipe suivie.\nChoisis tes équipes avec le bouton « Choisir mes équipes »."
            filter == "Mes équipes" -> "Aucune de tes équipes ne joue en ce moment."
            else -> "Aucun match en direct pour l'instant."
        }
        upcomingEmpty.visibility = if (upcoming.isEmpty()) View.VISIBLE else View.GONE
        upcomingEmpty.text = "Aucun match prévu d'ici demain soir."
        resultsEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
        paintUpdated()
    }

    private fun paintUpdated() {
        val age = Sport.cachedAge()
        if (age == Long.MAX_VALUE) {
            updated.text = ""
            return
        }
        val s = age / 1000
        updated.text = when {
            s < 60 -> "Scores à jour"
            s < 3600 -> "Mis à jour il y a ${s / 60} min"
            else -> "Mis à jour à " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(System.currentTimeMillis() - age))
        }
    }

    // ---------- Actions ----------

    private fun watch(m: Sport.Match) {
        val ch = picks[m.key]?.channel
        if (ch != null) play(ch) else chooseChannel(m)
    }

    private fun play(ch: Item) {
        Ui.play(act, ItemList.of(listOf(ch)), 0)
    }

    private fun options(m: Sport.Match) {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (!m.finished) {
            labels.add("Choisir la chaîne")
            acts.add { chooseChannel(m) }
        }
        val books = m.odds?.books.orEmpty()
        if (books.isNotEmpty()) {
            labels.add("Voir les cotes des bookmakers")
            acts.add { showOdds(m) }
        }
        for (s in listOf(m.home, m.away)) {
            labels.add(if (Sport.isFollowed(s)) "Ne plus suivre ${s.name}" else "Suivre ${s.name}")
            acts.add {
                val added = Sport.toggleFollow(s)
                Ui.toast(act, if (added) "${s.name} ajouté à Mes équipes" else "${s.name} retiré de Mes équipes")
                paint()
            }
        }
        Ui.choose(act, m.title, labels) { w -> acts[w]() }
    }

    private fun showOdds(m: Sport.Match) {
        val books = m.odds?.books.orEmpty()
        val lines = books.map { b -> "${b.name}   1 ${Odds.fmt(b.home)} · N ${Odds.fmt(b.draw)} · 2 ${Odds.fmt(b.away)}" }
        Ui.choose(act, "Cotes · ${m.title}", lines) { }
    }

    /** Liste des chaînes : celles du diffuseur officiel et celles qui annoncent le match d'abord. */
    private fun chooseChannel(m: Sport.Match) {
        if (chans.isEmpty()) {
            Ui.toast(act, "Aucune chaîne de sport trouvée dans ta liste.")
            return
        }
        val startSec = if (m.live) System.currentTimeMillis() / 1000 else m.start / 1000
        fun programme(ch: Item): String? = Epg.cached(ch.id)?.firstOrNull { e -> startSec in (e.start - 1800) until e.end }?.title
        val rights = SportChannels.rightsChannels(m.league, chans).toSet()
        val sorted = chans.sortedWith(
            compareByDescending<Item> { if (it in rights) 1 else 0 }.thenByDescending { if (programme(it) != null) 1 else 0 }
        ).take(90)
        val labels = listOf("Chercher une chaîne…") + sorted.map { ch ->
            val num = ch.num?.let { "$it · " } ?: ""
            val prog = programme(ch)
            if (prog != null) "$num${ch.name}  —  $prog" else "$num${ch.name}"
        }
        val who = Sport.RIGHTS[m.league.path]?.second?.let { " (diffuseur : $it)" } ?: ""
        Ui.choose(act, "Chaîne pour ${m.title}$who", labels) { w ->
            if (w == 0) {
                Ui.textInput(act, "Chercher une chaîne", "", "Nom ou numéro") { q ->
                    if (q.isBlank()) return@textInput
                    val found = Db.page(src.id, Kind.LIVE, null, q, 0, 60)
                    if (found.isEmpty()) {
                        Ui.toast(act, "Aucune chaîne pour « $q »")
                        return@textInput
                    }
                    Ui.choose(act, "Chaîne pour ${m.title}", found.map { (it.num?.let { n -> "$n · " } ?: "") + it.name }) { i ->
                        pickChannel(m, found[i])
                    }
                }
            } else {
                pickChannel(m, sorted[w - 1])
            }
        }
    }

    private fun pickChannel(m: Sport.Match, ch: Item) {
        SportChannels.remember(m, ch)
        picks[m.key] = SportChannels.Pick(ch, true, null)
        paint()
        if (m.live || m.start - System.currentTimeMillis() < 20 * 60_000L) play(ch)
        else Ui.toast(act, "${ch.name} mémorisée pour ce match.")
    }

    // ---------- Affichage ----------

    private fun crest(v: View, s: Sport.Side) {
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(s.color ?: 0xFF3A3F55.toInt())
        val text = v.findViewById<TextView>(R.id.crestText)
        val img = v.findViewById<ImageView>(R.id.crestImg)
        val tag = s.logo ?: s.abbr
        text.background = bg
        text.text = s.abbr.take(3)
        img.tag = tag
        img.setImageDrawable(null)
        val logo = s.logo ?: return
        Img.fetch(logo, 96) { bmp ->
            if (bmp != null && img.tag == tag) {
                img.setImageBitmap(bmp)
                text.text = ""
                bg.setColor(0x26FFFFFF)
            }
        }
    }

    private fun dayLabel(ms: Long): String {
        val c = Calendar.getInstance()
        val today = c.get(Calendar.DAY_OF_YEAR)
        c.timeInMillis = ms
        val d = c.get(Calendar.DAY_OF_YEAR)
        val hour = c.get(Calendar.HOUR_OF_DAY)
        return when (d - today) {
            0 -> if (hour >= 18) "Ce soir" else "Aujourd'hui"
            1 -> "Demain"
            -1 -> "Hier"
            else -> SimpleDateFormat("EEE d", Locale.FRANCE).format(Date(ms))
        }
    }

    private fun compLabel(m: Sport.Match): String = listOfNotNull(m.league.label, m.round).joinToString(" · ")

    private inner class MatchAdapter(private val layout: Int) : RecyclerView.Adapter<MatchAdapter.VH>() {
        private var items: List<Sport.Match> = emptyList()

        fun set(l: List<Sport.Match>) {
            items = l
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            if (layout == R.layout.item_match_live) v.setOnFocusChangeListener { view, has -> Fx.focus(view, has, 1.03f) }
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val m = items[position]
            val v = h.itemView
            when (layout) {
                R.layout.item_match_live -> bindLive(v, m)
                R.layout.item_match_row -> bindRow(v, m)
                else -> bindResult(v, m)
            }
            if (layout != R.layout.item_match_result) {
                v.setOnClickListener { watch(m) }
                v.setOnLongClickListener {
                    options(m)
                    true
                }
            }
        }
    }

    private fun bindWatch(v: View, m: Sport.Match, short: Boolean) {
        val p = picks[m.key]
        val name = v.findViewById<TextView>(R.id.chName)
        val logo = v.findViewById<ImageView>(R.id.chLogo)
        val num: TextView? = v.findViewById(R.id.chNum)
        val ch = p?.channel
        val sure = p?.sure == true
        val brand = p?.broadcaster
        when {
            ch != null -> {
                val label = if (short) ch.name else "Regarder sur ${ch.name}"
                name.text = if (sure) label else "$label ?"
                Img.load(logo, ch.icon, R.drawable.ph_live)
                num?.text = ch.num?.let { "ch. $it" } ?: ""
            }
            brand != null -> {
                name.text = if (short) brand else "Sur $brand · choisir la chaîne"
                logo.setImageResource(R.drawable.ic_search_small)
                num?.text = ""
            }
            else -> {
                name.text = if (!guideReady && Prefs.epgEnabled && chans.isNotEmpty()) "Recherche de la chaîne…" else "Choisir la chaîne"
                logo.setImageResource(R.drawable.ic_search_small)
                num?.text = ""
            }
        }
    }

    private fun bindLive(v: View, m: Sport.Match) {
        v.findViewById<TextView>(R.id.comp).text = compLabel(m)
        v.findViewById<TextView>(R.id.status).text = m.status
        v.findViewById<TextView>(R.id.teamA).text = m.home.short
        v.findViewById<TextView>(R.id.teamB).text = m.away.short
        val a = v.findViewById<TextView>(R.id.scoreA)
        val b = v.findViewById<TextView>(R.id.scoreB)
        a.text = m.home.score ?: ""
        b.text = m.away.score ?: ""
        val sa = m.home.score?.toIntOrNull()
        val sb = m.away.score?.toIntOrNull()
        val dim = 0xFFC9CDD4.toInt()
        val bright = act.getColor(R.color.text)
        a.setTextColor(if (sa != null && sb != null && sa < sb) dim else bright)
        b.setTextColor(if (sa != null && sb != null && sb < sa) dim else bright)
        v.findViewById<TextView>(R.id.detail).text = m.detail ?: ""
        val odds = v.findViewById<TextView>(R.id.odds)
        val o = m.odds
        if (Prefs.oddsEnabled && o != null) {
            odds.visibility = View.VISIBLE
            odds.text = Odds.line(o)
        } else {
            odds.visibility = View.GONE
        }
        crest(v.findViewById(R.id.crestA), m.home)
        crest(v.findViewById(R.id.crestB), m.away)
        bindWatch(v, m, short = false)
    }

    private fun bindRow(v: View, m: Sport.Match) {
        v.findViewById<TextView>(R.id.time).text = m.status
        v.findViewById<TextView>(R.id.day).text = dayLabel(m.start)
        v.findViewById<TextView>(R.id.comp).text = compLabel(m)
        v.findViewById<TextView>(R.id.teamA).text = m.home.short
        v.findViewById<TextView>(R.id.teamB).text = m.away.short
        crest(v.findViewById(R.id.crestA), m.home)
        crest(v.findViewById(R.id.crestB), m.away)
        bindWatch(v, m, short = true)
    }

    private fun bindResult(v: View, m: Sport.Match) {
        v.findViewById<TextView>(R.id.comp).text = compLabel(m)
        v.findViewById<TextView>(R.id.status).text = m.status
        val ta = v.findViewById<TextView>(R.id.teamA)
        val tb = v.findViewById<TextView>(R.id.teamB)
        val sa = v.findViewById<TextView>(R.id.scoreA)
        val sb = v.findViewById<TextView>(R.id.scoreB)
        ta.text = m.home.short
        tb.text = m.away.short
        sa.text = m.home.score ?: "–"
        sb.text = m.away.score ?: "–"
        val a = m.home.score?.toIntOrNull()
        val b = m.away.score?.toIntOrNull()
        val win = act.getColor(R.color.text)
        val lose = act.getColor(R.color.text_faint)
        val aWins = m.home.winner || (a != null && b != null && a >= b)
        val bWins = m.away.winner || (a != null && b != null && b >= a)
        ta.setTextColor(if (aWins) win else lose)
        sa.setTextColor(if (aWins) win else lose)
        tb.setTextColor(if (bWins) win else lose)
        sb.setTextColor(if (bWins) win else lose)
    }
}

/** Mise en forme des cotes : « 1 2,10 · N 3,40 · 2 3,25 · Unibet ». */
object Odds {
    fun fmt(d: Double?): String = if (d == null) "–" else String.format(Locale.FRANCE, "%.2f", d)

    fun line(o: Sport.Odds): String {
        val parts = ArrayList<String>()
        parts.add("1 ${fmt(o.home)}")
        if (o.draw != null) parts.add("N ${fmt(o.draw)}")
        parts.add("2 ${fmt(o.away)}")
        return parts.joinToString("  ·  ") + "   " + (if (o.books.size > 1) "meilleures cotes, ${o.source}" else o.source)
    }
}
