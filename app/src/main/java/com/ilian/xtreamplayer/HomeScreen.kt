package com.ilian.xtreamplayer

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/**
 * Onglet Accueil : sélection « À la une » qui défile toute seule (recommandations selon l'heure,
 * la saison, les nouveautés et tes goûts), sport en direct à gauche et rangée « Reprendre la lecture ».
 */
class HomeScreen(
    private val act: Activity,
    root: View,
    private val src: Source,
    private val api: XtreamApi,
    private val bg: ImageView,
    private val openSport: () -> Unit
) {
    private val inf = act.layoutInflater

    private val sportList: LinearLayout = root.findViewById(R.id.sportList)
    private val sportEmpty: TextView = root.findViewById(R.id.sportEmpty)
    private val sportTitle: TextView = root.findViewById(R.id.sportTitle)
    private val sportDot: View = root.findViewById(R.id.sportDot)

    private val hero: View = root.findViewById(R.id.hero)
    private val heroBlur: ImageView = root.findViewById(R.id.heroBlur)
    private val heroBacks: List<ImageView> = listOf(root.findViewById(R.id.heroBackA), root.findViewById(R.id.heroBackB))
    private val heroPoster: ImageView = root.findViewById(R.id.heroPoster)
    private val heroContent: View = root.findViewById(R.id.heroContent)
    private val heroTag: TextView = root.findViewById(R.id.heroTag)
    private val heroTitle: TextView = root.findViewById(R.id.heroTitle)
    private val heroMeta: TextView = root.findViewById(R.id.heroMeta)
    private val heroPlot: TextView = root.findViewById(R.id.heroPlot)
    private val heroPlay: TextView = root.findViewById(R.id.heroPlay)
    private val heroInfo: ImageView = root.findViewById(R.id.heroInfo)
    private val heroFav: ImageView = root.findViewById(R.id.heroFav)
    private val heroButtons: View = root.findViewById(R.id.heroButtons)
    private val heroDots: LinearLayout = root.findViewById(R.id.heroDots)
    private val heroEmpty: View = root.findViewById(R.id.heroEmpty)

    private val rowTitle: TextView = root.findViewById(R.id.rowTitle)
    private val rvResume: RecyclerView = root.findViewById(R.id.rvResume)
    private val resumeAdapter = ResumeAdapter()

    private var featured: List<Item> = emptyList()
    private var featuredSlot: Recommender.Slot? = null
    private var heroIndex = 0
    private var backIndex = 0
    private val movieInfos = HashMap<String, MovieInfo>()
    private val seriesInfos = HashMap<String, SeriesInfo>()
    private val tmdbInfos = HashMap<String, Tmdb.Info>()
    private var running = false
    private var lastTouch = 0L
    private var chans: List<Item> = emptyList()

    private val rotate = object : Runnable {
        override fun run() {
            if (!running) return
            // Défile tout seul ; une pause seulement juste après une action de la télécommande dessus.
            if (featured.size > 1 && System.currentTimeMillis() - lastTouch > PAUSE_MS) showHero((heroIndex + 1) % featured.size)
            Bg.main.postDelayed(this, ROTATE_MS)
        }
    }
    private val sportTick = object : Runnable {
        override fun run() {
            if (!running) return
            loadSport()
            Bg.main.postDelayed(this, 60_000)
        }
    }

    init {
        hero.clipToOutline = true
        heroPoster.clipToOutline = true
        heroPlay.setOnClickListener { current()?.let { primary(it) } }
        heroInfo.setOnClickListener { current()?.let { open(it) } }
        heroFav.setOnClickListener {
            val item = current() ?: return@setOnClickListener
            val added = Db.toggleFav(src.id, item)
            Ui.toast(act, if (added) "Ajouté à Ma liste" else "Retiré de Ma liste")
            paintHeroButtons()
        }
        for (b in listOf(heroPlay, heroInfo, heroFav)) {
            b.setOnKeyListener { _, code, e ->
                if (e.action == KeyEvent.ACTION_DOWN) {
                    lastTouch = System.currentTimeMillis()
                    // Gauche / droite sur les bords du bouton : titre précédent ou suivant.
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT && b === heroPlay && featured.size > 1) {
                        showHero((heroIndex - 1 + featured.size) % featured.size)
                        return@setOnKeyListener true
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_RIGHT && b === heroFav && featured.size > 1) {
                        showHero((heroIndex + 1) % featured.size)
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
        root.findViewById<View>(R.id.btnAllSport).setOnClickListener { openSport() }
        rvResume.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        rvResume.adapter = resumeAdapter
        rvResume.itemAnimator = null
        Fx.lockRow(rvResume, escapeLeft = true)
    }

    fun start() {
        loadFeatured()
        fillResume()
        paintSport(Sport.cached())
    }

    /** Retour sur l'accueil (après une lecture, une fiche, les réglages). */
    fun refresh() {
        fillResume()
        paintHeroButtons()
        // Nouveau créneau (le matin devient midi…) : nouvelle sélection.
        if (featuredSlot != Recommender.slot()) loadFeatured()
    }

    fun resume() {
        if (running) return
        running = true
        Bg.main.removeCallbacks(rotate)
        Bg.main.postDelayed(rotate, ROTATE_MS)
        Bg.main.removeCallbacks(sportTick)
        Bg.main.post(sportTick)
        current()?.let { paintBg(it) }
    }

    fun pause() {
        running = false
        Bg.main.removeCallbacks(rotate)
        Bg.main.removeCallbacks(sportTick)
    }

    fun focusDefault() {
        if (featured.isNotEmpty()) heroPlay.requestFocus() else rvResume.getChildAt(0)?.requestFocus()
    }

    // ---------- À la une ----------

    private fun current(): Item? = featured.getOrNull(heroIndex)

    private fun loadFeatured() {
        val slot = Recommender.slot()
        featuredSlot = slot
        Bg.task(act, { Recommender.featured(src.id, 8) }) { r ->
            val list = r.getOrNull().orEmpty()
            featured = list
            heroDots.removeAllViews()
            if (list.isEmpty()) {
                heroEmpty.visibility = View.VISIBLE
                (heroEmpty as TextView).text = "Aucun film ni série dans cette source."
                heroButtons.visibility = View.GONE
                return@task
            }
            heroEmpty.visibility = View.GONE
            heroButtons.visibility = View.VISIBLE
            val dp = act.resources.displayMetrics.density
            if (list.size > 1) for (i in list.indices) {
                val d = View(act)
                val lp = LinearLayout.LayoutParams((6 * dp).toInt(), (4 * dp).toInt())
                lp.marginStart = (5 * dp).toInt()
                heroDots.addView(d, lp)
            }
            heroTag.text = "À la une · ${slot.label.lowercase(Locale.FRANCE)}"
            showHero(0, animate = false)
        }
    }

    private fun showHero(i: Int, animate: Boolean = true) {
        val item = featured.getOrNull(i) ?: return
        heroIndex = i
        val dp = act.resources.displayMetrics.density
        for (j in 0 until heroDots.childCount) {
            val d = heroDots.getChildAt(j)
            d.setBackgroundResource(if (j == i) R.drawable.dot_on else R.drawable.dot_off)
            d.layoutParams = d.layoutParams.apply { width = ((if (j == i) 16 else 6) * dp).toInt() }
        }
        val apply = {
            heroTitle.text = Tmdb.cleanTitle(item.name).first
            heroPlot.text = item.plot ?: ""
            heroMeta.text = Meta.line(listOf(if (item.kind == Kind.SERIES) "Série" else "Film", Meta.rating(item.rating)), act.getColor(R.color.accent))
            paintHeroButtons()
            loadInfo(item)
        }
        if (animate) {
            heroContent.animate().cancel()
            heroContent.animate().alpha(0f).translationX(-12 * dp).setDuration(160).withEndAction {
                apply()
                heroContent.translationX = 12 * dp
                heroContent.animate().alpha(1f).translationX(0f).setDuration(260).start()
            }.start()
        } else {
            apply()
        }
        // Fond flouté immédiat, puis image de fond nette en fondu
        heroPoster.visibility = View.GONE
        for (b in heroBacks) b.animate().alpha(0f).setDuration(300).start()
        Img.blurred(heroBlur, item.icon)
        if (running) paintBg(item)
    }

    /** Le fond de l'écran reprend l'affiche du titre à la une, très floutée. */
    private fun paintBg(item: Item) {
        Img.blurred(bg, item.icon) { ok -> if (ok && running) bg.animate().alpha(0.55f).setDuration(400).start() }
    }

    private fun loadInfo(item: Item) {
        if (item.kind == Kind.SERIES) {
            seriesInfos[item.id]?.let {
                paintSeries(item, it)
                return
            }
            Bg.task(act, { api.seriesInfo(item.id) }) { r ->
                val si = r.getOrNull() ?: return@task
                seriesInfos[item.id] = si
                if (current()?.id == item.id) paintSeries(item, si)
            }
        } else {
            movieInfos[item.id]?.let {
                paintMovie(item, it)
                return
            }
            Bg.task(act, { api.movieInfo(item.id) }) { r ->
                val mi = r.getOrNull()
                if (mi != null) movieInfos[item.id] = mi
                if (current()?.id == item.id) {
                    if (mi != null) paintMovie(item, mi) else backdrop(item, null, null)
                }
            }
        }
    }

    private fun paintMovie(item: Item, i: MovieInfo) {
        heroMeta.text = Meta.line(
            listOf(i.year, Meta.duration(i.duration), Meta.rating(i.rating) ?: Meta.rating(item.rating), Meta.firstGenre(i.genre)),
            act.getColor(R.color.accent)
        )
        heroPlot.text = i.plot ?: item.plot ?: ""
        backdrop(item, i.backdrop, i.tmdb)
    }

    private fun paintSeries(item: Item, si: SeriesInfo) {
        val n = si.seasons.size
        heroMeta.text = Meta.line(
            listOf(
                "Série", if (n > 0) (if (n == 1) "1 saison" else "$n saisons") else null,
                Meta.firstGenre(si.genre), Meta.rating(si.rating) ?: Meta.rating(item.rating)
            ),
            act.getColor(R.color.accent)
        )
        heroPlot.text = si.plot ?: item.plot ?: ""
        backdrop(item, si.backdrop, si.tmdb)
    }

    /** Image de fond : fournisseur, puis TMDB, puis TVmaze ; sinon l'affiche nette sur fond flouté. */
    private fun backdrop(item: Item, url: String?, tmdbId: String?) {
        if (url != null) {
            showBackdrop(item, url)
            return
        }
        tmdbInfos[item.key]?.backdrop?.let {
            showBackdrop(item, it)
            return
        }
        Bg.task(act, {
            val t = if (Tmdb.enabled) Tmdb.info(item.copy(tmdb = tmdbId ?: item.tmdb)) else null
            t to (t?.backdrop ?: FreeMeta.backdrop(item))
        }) { r ->
            val (t, b) = r.getOrNull() ?: (null to null)
            if (t != null) tmdbInfos[item.key] = t
            if (current()?.id != item.id) return@task
            if (heroPlot.text.isNullOrBlank()) heroPlot.text = t?.overview ?: ""
            if (b != null) showBackdrop(item, b) else showPoster(item)
        }
    }

    private fun showBackdrop(item: Item, url: String) {
        Img.fetch(url, 1000) { bmp ->
            if (current()?.id != item.id) return@fetch
            if (bmp == null) {
                showPoster(item)
                return@fetch
            }
            // Fondu enchaîné entre deux images, puis léger zoom lent (effet Ken Burns)
            backIndex = 1 - backIndex
            val v = heroBacks[backIndex]
            val other = heroBacks[1 - backIndex]
            v.animate().cancel()
            v.setImageBitmap(bmp)
            v.scaleX = 1f
            v.scaleY = 1f
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(500).withEndAction {
                v.animate().scaleX(1.07f).scaleY(1.07f).setDuration(ROTATE_MS + 2_000).setInterpolator(LinearInterpolator()).start()
            }.start()
            other.animate().alpha(0f).setDuration(500).start()
        }
    }

    /** Sans image de fond, on montre l'affiche nette à droite, sur le fond flouté. */
    private fun showPoster(item: Item) {
        val url = item.icon ?: return
        heroPoster.visibility = View.VISIBLE
        heroPoster.alpha = 0f
        heroPoster.animate().alpha(1f).setDuration(300).start()
        Img.load(heroPoster, url, R.drawable.ph_poster, 420)
    }

    private fun paintHeroButtons() {
        val item = current() ?: return
        val fav = Db.isFav(src.id, item)
        heroFav.setImageResource(if (fav) R.drawable.ic_check else R.drawable.ic_plus)
        heroFav.contentDescription = if (fav) "Retirer de Ma liste" else "Ajouter à Ma liste"
        if (item.kind == Kind.SERIES) {
            heroPlay.text = "Épisodes"
            return
        }
        val p = Db.progress(src.id, item)
        heroPlay.text = when {
            p != null && !p.finished && p.pos > 0 -> "Reprendre"
            p?.finished == true -> "Revoir"
            else -> "Regarder"
        }
    }

    // ---------- Sport en direct ----------

    private fun loadSport() {
        Bg.task(act, {
            val m = Sport.get(60_000L)
            if (chans.isEmpty()) chans = SportChannels.candidates(src.id)
            m
        }) { r ->
            val all = r.getOrNull()
            if (all == null) {
                if (Sport.cached().isEmpty()) sportEmpty.text = "Scores indisponibles pour l'instant."
                return@task
            }
            paintSport(all)
            if (Prefs.epgEnabled && chans.isNotEmpty()) SportChannels.warmUp(api, all, chans) { if (running) paintSport(Sport.cached()) }
        }
    }

    private fun paintSport(all: List<Sport.Match>) {
        val live = all.filter { it.live }.sortedByDescending { Sport.trend(it) }
        val next = all.filter { it.state == "pre" }.sortedWith(compareByDescending<Sport.Match> { Sport.trend(it) >= 12 }.thenBy { it.start })
        val shown = (live + next).take(3)
        sportTitle.text = if (live.isNotEmpty()) "Sport en direct" else "Sport à venir"
        sportDot.visibility = if (live.isNotEmpty()) View.VISIBLE else View.GONE
        sportList.removeAllViews()
        if (shown.isEmpty()) {
            sportEmpty.visibility = View.VISIBLE
            if (all.isNotEmpty() || Sport.cachedAge() != Long.MAX_VALUE) sportEmpty.text = "Pas de match en cours ni prévu aujourd'hui."
            return
        }
        sportEmpty.visibility = View.GONE
        for (m in shown) {
            val v = inf.inflate(R.layout.item_home_match, sportList, false)
            v.findViewById<TextView>(R.id.comp).text = listOfNotNull(m.league.label, m.round).joinToString(" · ")
            val st = v.findViewById<TextView>(R.id.status)
            st.text = m.status
            if (!m.live) {
                st.setBackgroundResource(R.drawable.chip_small)
                st.setTextColor(act.getColor(R.color.text))
            }
            v.findViewById<TextView>(R.id.teamA).text = m.home.short
            v.findViewById<TextView>(R.id.teamB).text = m.away.short
            v.findViewById<TextView>(R.id.scoreA).text = if (m.live || m.finished) m.home.score ?: "" else ""
            v.findViewById<TextView>(R.id.scoreB).text = if (m.live || m.finished) m.away.score ?: "" else ""
            crest(v.findViewById(R.id.crestA), m.home)
            crest(v.findViewById(R.id.crestB), m.away)
            val pick = SportChannels.pick(src.id, m, chans)
            val ch = pick?.channel
            val sure = pick?.sure == true
            val brand = pick?.broadcaster
            v.findViewById<TextView>(R.id.channel).text = when {
                ch != null -> "${if (sure) "" else "Peut-être "}${ch.name}${ch.num?.let { " · ch. $it" } ?: ""}"
                brand != null -> "Sur $brand"
                else -> "Chaîne à choisir dans Sport"
            }
            v.setOnFocusChangeListener { view, has -> Fx.focus(view, has, 1.03f) }
            v.setOnClickListener { if (ch != null) Ui.play(act, ItemList.of(listOf(ch)), 0) else openSport() }
            sportList.addView(v)
        }
    }

    private fun crest(v: View, s: Sport.Side) {
        val bgd = GradientDrawable()
        bgd.shape = GradientDrawable.OVAL
        bgd.setColor(s.color ?: 0xFF3A3F55.toInt())
        val text = v.findViewById<TextView>(R.id.crestText)
        val img = v.findViewById<ImageView>(R.id.crestImg)
        text.background = bgd
        text.text = s.abbr.take(3)
        img.tag = s.logo
        val logo = s.logo ?: return
        Img.fetch(logo, 80) { bmp ->
            if (bmp != null && img.tag == logo) {
                img.setImageBitmap(bmp)
                text.text = ""
                bgd.setColor(0x00000000)
            }
        }
    }

    // ---------- Reprendre la lecture ----------

    /** Une carte de la rangée : film ou épisode en cours, ou série à poursuivre. */
    private class Entry(val item: Item, val title: String, val sub: String, val progress: Float?, val series: Item?, val updated: Long)

    private fun fillResume() {
        val out = ArrayList<Entry>()
        for (r in Db.resume(src.id, 20, Kind.MOVIE)) {
            val m = Db.find(src.id, Kind.MOVIE, r.item.id) ?: r.item
            out.add(Entry(m, Tmdb.cleanTitle(m.name).first, remaining(r.dur - r.pos), if (r.dur > 0) r.pos.toFloat() / r.dur else null, null, r.updated))
        }
        for ((sid, r) in Db.seriesActivity(src.id, 20)) {
            val s = Db.find(src.id, Kind.SERIES, sid) ?: continue
            val se = SE.find(r.item.extra ?: "")?.let { "S${it.groupValues[1]} · É${it.groupValues[2]}" } ?: ""
            if (r.pos >= 0) {
                out.add(Entry(r.item, Tmdb.cleanTitle(s.name).first, listOf(se, remaining(r.dur - r.pos)).filter { it.isNotEmpty() }.joinToString(" · "),
                    if (r.dur > 0) r.pos.toFloat() / r.dur else null, s, r.updated))
            } else {
                out.add(Entry(s, Tmdb.cleanTitle(s.name).first, "Épisode suivant", null, s, r.updated))
            }
        }
        out.sortByDescending { it.updated }
        if (out.isEmpty()) {
            // Rien en cours : on propose les nouveautés à la place.
            rowTitle.text = "À découvrir"
            for (m in Db.latest(src.id, Kind.MOVIE, 20)) out.add(Entry(m, Tmdb.cleanTitle(m.name).first, Meta.rating(m.rating) ?: "Nouveauté", null, null, 0))
        } else {
            rowTitle.text = "Reprendre la lecture"
        }
        resumeAdapter.set(out)
    }

    private fun remaining(ms: Long): String {
        if (ms <= 0) return ""
        val min = (ms / 60_000).coerceAtLeast(1)
        return if (min >= 60) "${min / 60} h ${String.format(Locale.FRANCE, "%02d", min % 60)} restantes" else "$min min restantes"
    }

    private fun backdropFor(e: Entry, iv: ImageView, thumb: ImageView) {
        thumb.visibility = View.GONE
        if (e.item.kind == Kind.EPISODE && !e.item.icon.isNullOrBlank()) {
            // Image de l'épisode (souvent une vraie image paysage)
            Img.load(iv, e.item.icon, R.drawable.ph_poster, 400)
            return
        }
        val poster = (e.series ?: e.item).icon
        Img.blurred(iv, poster)
        if (!poster.isNullOrBlank()) {
            thumb.visibility = View.VISIBLE
            Img.load(thumb, poster, R.drawable.ph_poster, 200)
        }
        val cached = movieInfos[e.item.id]?.backdrop
        if (e.item.kind == Kind.MOVIE && cached != null) {
            iv.tag = "bd|$cached"
            Img.fetch(cached, 420) { bmp ->
                if (bmp != null && iv.tag == "bd|$cached") {
                    iv.setImageBitmap(bmp)
                    thumb.visibility = View.GONE
                }
            }
        } else if (e.item.kind == Kind.MOVIE && !movieInfos.containsKey(e.item.id)) {
            Bg.task(act, { api.movieInfo(e.item.id) }) { r ->
                val mi = r.getOrNull() ?: return@task
                movieInfos[e.item.id] = mi
                val b = mi.backdrop ?: return@task
                iv.tag = "bd|$b"
                Img.fetch(b, 420) { bmp ->
                    if (bmp != null && iv.tag == "bd|$b") {
                        iv.setImageBitmap(bmp)
                        thumb.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun play(e: Entry) {
        when {
            e.item.kind == Kind.SERIES -> open(e.item)
            else -> {
                val local = Downloads.find(act, src.id, e.item)?.playable()
                Ui.play(act, ItemList.of(listOf(local ?: e.item)), 0)
            }
        }
    }

    private fun entryOptions(e: Entry) {
        val labels = arrayListOf("Plus d'infos")
        if (e.progress != null) labels.add("Retirer de Reprendre la lecture")
        Ui.choose(act, e.title, labels) { w ->
            if (w == 0) open(e.series ?: e.item)
            else {
                Db.saveProgress(src.id, e.item, 0, 0, true)
                fillResume()
            }
        }
    }

    private inner class ResumeAdapter : RecyclerView.Adapter<ResumeAdapter.VH>() {
        private var items: List<Entry> = emptyList()

        fun set(l: List<Entry>) {
            items = l
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.img)
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val name: TextView = v.findViewById(R.id.name)
            val sub: TextView = v.findViewById(R.id.sub)
            val bar: ProgressBar = v.findViewById(R.id.bar)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_resume_card, parent, false)
            v.findViewById<View>(R.id.frame).clipToOutline = true
            v.findViewById<View>(R.id.thumb).clipToOutline = true
            v.setOnFocusChangeListener { view, has -> Fx.focus(view, has, 1.06f) }
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            h.name.text = e.title
            h.sub.text = e.sub
            h.bar.visibility = if (e.progress != null) View.VISIBLE else View.GONE
            e.progress?.let { h.bar.progress = (it * 1000).toInt().coerceIn(0, 1000) }
            backdropFor(e, h.img, h.thumb)
            h.itemView.setOnClickListener { play(e) }
            h.itemView.setOnLongClickListener {
                entryOptions(e)
                true
            }
        }
    }

    // ---------- Actions ----------

    private fun primary(item: Item) {
        if (item.kind == Kind.SERIES) {
            open(item)
            return
        }
        val d = Downloads.find(act, src.id, item)?.playable()
        Ui.play(act, ItemList.of(listOf(d ?: item)), 0)
    }

    private fun open(item: Item) {
        val cls = if (item.kind == Kind.SERIES) SeriesActivity::class.java else MovieActivity::class.java
        act.startActivity(Intent(act, cls).putExtra(MainActivity.EXTRA_ITEM, item.toJson().toString()))
    }

    companion object {
        private const val ROTATE_MS = 7_000L
        private const val PAUSE_MS = 10_000L
        private val SE = Regex("""Saison (\d+) · Épisode (\d+)""")
    }
}
