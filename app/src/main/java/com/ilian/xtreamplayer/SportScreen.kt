package com.ilian.xtreamplayer

import android.app.Activity
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

/** Onglet Sport : matchs en direct, à venir et résultats, avec un raccourci vers la chaîne. */
class SportScreen(
    private val act: Activity,
    root: View,
    private val src: Source,
    private val api: XtreamApi
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

    private var all: List<Sport.Match> = emptyList()
    private var filter = "Tout"
    private var loadedAt = 0L
    private var loading = false
    private var visible = false
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
            if (System.currentTimeMillis() - loadedAt >= every - 1_000) load()
            paintUpdated()
            Bg.main.postDelayed(this, 20_000)
        }
    }

    init {
        rvLive.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        rvLive.adapter = liveAdapter
        rvLive.itemAnimator = null
        rvUpcoming.layoutManager = LinearLayoutManager(act)
        rvUpcoming.adapter = upcomingAdapter
        rvUpcoming.itemAnimator = null
        rvResults.layoutManager = LinearLayoutManager(act)
        rvResults.adapter = resultAdapter
        rvResults.itemAnimator = null
        resultsEmpty.text = "Aucun résultat pour l'instant."
        for (g in Sport.GROUPS) {
            val tv = act.layoutInflater.inflate(R.layout.item_sport_chip, chips, false) as TextView
            tv.text = g
            tv.tag = g
            tv.isSelected = g == filter
            tv.setOnClickListener {
                filter = g
                for (i in 0 until chips.childCount) chips.getChildAt(i).let { c -> c.isSelected = c.tag == g }
                paint()
            }
            chips.addView(tv)
        }
    }

    fun show() {
        visible = true
        if (System.currentTimeMillis() - loadedAt > 60_000) load() else paint()
        Bg.main.removeCallbacks(refresh)
        Bg.main.postDelayed(refresh, 20_000)
    }

    fun hide() {
        visible = false
        Bg.main.removeCallbacks(refresh)
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
            val matches = Sport.load()
            val c = SportChannels.candidates(src.id)
            matches to c
        }) { r ->
            loading = false
            r.onSuccess { (matches, c) ->
                all = matches
                chans = c
                loadedAt = System.currentTimeMillis()
                computePicks()
                paint()
                // Le guide TV des chaînes de sport permet de trouver qui diffuse quoi.
                if (!guideReady && Prefs.epgEnabled && c.isNotEmpty()) {
                    SportChannels.warmUp(api, c) {
                        guideReady = true
                        if (!act.isDestroyed) {
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
    }.sortedWith(compareByDescending<Sport.Match> { Sport.involvesFollowed(it) }.thenBy { it.start })

    private fun paint() {
        val list = matches()
        val live = list.filter { it.live }
        val upcoming = list.filter { it.state == "pre" }.sortedBy { it.start }
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
            filter == "Mes équipes" && Sport.followed().isEmpty() ->
                "Aucune équipe suivie.\nAppui long sur OK sur un match pour suivre une équipe."
            else -> "Aucun match en direct pour l'instant."
        }
        upcomingEmpty.visibility = if (upcoming.isEmpty()) View.VISIBLE else View.GONE
        upcomingEmpty.text = "Aucun match prévu d'ici demain soir."
        resultsEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
        paintUpdated()
    }

    private fun paintUpdated() {
        if (loadedAt == 0L) {
            updated.text = ""
            return
        }
        val s = (System.currentTimeMillis() - loadedAt) / 1000
        updated.text = when {
            s < 60 -> "Scores mis à jour à l'instant"
            s < 3600 -> "Scores mis à jour il y a ${s / 60} min"
            else -> "Scores mis à jour à " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(loadedAt))
        }
    }

    // ---------- Actions ----------

    private fun watch(m: Sport.Match) {
        val p = picks[m.key]
        if (p != null) {
            play(p.channel)
        } else {
            chooseChannel(m)
        }
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

    /** Liste des chaînes de sport : celles qui annoncent le match d'abord. */
    private fun chooseChannel(m: Sport.Match) {
        if (chans.isEmpty()) {
            Ui.toast(act, "Aucune chaîne de sport trouvée dans ta liste.")
            return
        }
        val startSec = m.start / 1000
        fun programme(ch: Item): String? = Epg.cached(ch.id)?.firstOrNull { e -> startSec in (e.start - 2700) until e.end }?.title
        val sorted = chans.sortedByDescending { if (programme(it) != null) 1 else 0 }.take(80)
        val labels = listOf("Chercher une chaîne…") + sorted.map { ch ->
            val num = ch.num?.let { "$it · " } ?: ""
            val prog = programme(ch)
            if (prog != null) "$num${ch.name}  —  $prog" else "$num${ch.name}"
        }
        Ui.choose(act, "Chaîne pour ${m.title}", labels) { w ->
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
        text.background = bg
        text.text = s.abbr.take(3)
        img.setImageDrawable(null)
        if (s.logo != null) {
            Img.fetch(s.logo, 96) { bmp ->
                if (bmp != null && text.text == s.abbr.take(3)) {
                    img.setImageBitmap(bmp)
                    text.text = ""
                    bg.setColor(0x26FFFFFF)
                }
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
            if (layout == R.layout.item_match_live) {
                v.setOnFocusChangeListener { view, has ->
                    val s = if (has) 1.03f else 1f
                    view.animate().scaleX(s).scaleY(s).setDuration(120).start()
                }
            }
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
        if (p == null) {
            name.text = if (!guideReady && Prefs.epgEnabled && chans.isNotEmpty()) "Recherche de la chaîne…" else "Choisir la chaîne"
            logo.setImageResource(R.drawable.ic_search_small)
            num?.text = ""
        } else {
            val label = if (short) p.channel.name else "Regarder sur ${p.channel.name}"
            name.text = if (p.sure) label else "$label ?"
            Img.load(logo, p.channel.icon, R.drawable.ph_live)
            num?.text = p.channel.num?.let { "ch. $it" } ?: ""
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
        crest(v.findViewById(R.id.crestA), m.home)
        crest(v.findViewById(R.id.crestB), m.away)
        bindWatch(v, m, short = false)
    }

    private fun bindRow(v: View, m: Sport.Match) {
        v.findViewById<TextView>(R.id.time).text = m.status
        v.findViewById<TextView>(R.id.day).text = dayLabel(m.start)
        v.findViewById<TextView>(R.id.comp).text = compLabel(m)
        v.findViewById<TextView>(R.id.teams).text = "${m.home.short}  –  ${m.away.short}"
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
