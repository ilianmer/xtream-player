package com.ilian.xtreamplayer

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView

/**
 * Onglets Films et Séries façon plateforme de streaming : grande image de fond,
 * titre mis en avant et rangées horizontales (Reprendre, Nouveautés, Tendances, catégories…).
 */
class StreamScreen(
    private val act: Activity,
    root: View,
    private val src: Source,
    private val api: XtreamApi,
    private val kind: Kind,
    private val bg: ImageView,
    /** Ouvre la grille classique sur une catégorie (null = tout). */
    private val openGrid: (String?) -> Unit
) {
    private class Rail(
        val key: String,
        val title: String,
        var items: List<Item>,
        val loader: (() -> List<Item>)? = null,
        var progress: Map<String, Float> = emptyMap(),
        var subs: Map<String, String> = emptyMap(),
        var badge: String? = null,
        var badgeIds: Set<String> = emptySet()
    )

    private val rvRails: RecyclerView = root.findViewById(R.id.rvRails)
    private val heroTitle: TextView = root.findViewById(R.id.sHeroTitle)
    private val heroBadge: TextView = root.findViewById(R.id.sHeroBadge)
    private val heroMeta: TextView = root.findViewById(R.id.sHeroMeta)
    private val heroPlot: TextView = root.findViewById(R.id.sHeroPlot)
    private val heroPlay: TextView = root.findViewById(R.id.sHeroPlay)
    private val heroMore: TextView = root.findViewById(R.id.sHeroMore)

    private val rails = ArrayList<Rail>()
    private val adapter = RailAdapter()
    private var hero: Item? = null
    private var heroRail: Rail? = null
    private var pending: Pair<Item, Rail>? = null
    private var built = false
    private var visible = false
    private val movieInfos = HashMap<String, MovieInfo>()
    private val seriesInfos = HashMap<String, SeriesInfo>()
    private val tmdbInfos = HashMap<String, Tmdb.Info>()
    private val railScroll = HashMap<String, Int>()
    private val applyHero = Runnable { pending?.let { (i, r) -> setHero(i, r) } }

    private val isSeries = kind == Kind.SERIES

    init {
        root.findViewById<TextView>(R.id.streamTitle).text = if (isSeries) "Séries" else "Films"
        root.findViewById<View>(R.id.btnGenres).setOnClickListener { chooseCategory() }
        rvRails.layoutManager = LinearLayoutManager(act)
        rvRails.adapter = adapter
        rvRails.itemAnimator = null
        heroPlay.setOnClickListener { hero?.let { primary(it, heroRail) } }
        heroMore.setOnClickListener { hero?.let { open(it) } }
    }

    fun show() {
        visible = true
        if (!built) build() else refresh()
        hero?.let { paintBackground(it) }
    }

    fun hide() {
        visible = false
        Bg.main.removeCallbacks(applyHero)
    }

    fun focusDefault() {
        rvRails.post {
            val first = rvRails.getChildAt(0)?.findViewById<RecyclerView>(R.id.railList)?.getChildAt(0)
            (first ?: heroPlay).requestFocus()
        }
    }

    // ---------- Rangées ----------

    private fun build() {
        built = true
        rails.clear()
        resumeRail()?.let { rails.add(it) }
        val latest = Db.latest(src.id, kind, 40)
        if (latest.isNotEmpty()) {
            val recent = System.currentTimeMillis() / 1000 - (if (isSeries) 7 else 14) * 86_400L
            rails.add(
                Rail(
                    "latest", if (isSeries) "Nouveaux épisodes" else "Nouveautés", latest,
                    badge = if (isSeries) "Nouvel épisode" else "Nouveau",
                    badgeIds = latest.filter { it.added >= recent }.map { it.id }.toSet()
                )
            )
        }
        favRail()?.let { rails.add(it) }
        for (g in Catalog.groups(src.id, kind)) {
            rails.add(Rail(g.key, Catalog.cleanName(g.name), emptyList(), loader = { Db.page(src.id, kind, g.catIds, null, 0, 30) }))
        }
        adapter.notifyDataSetChanged()
        val first = rails.firstOrNull { it.key == "latest" } ?: rails.firstOrNull()
        val item = first?.let { r -> r.items.firstOrNull() ?: r.loader?.invoke()?.also { r.items = it }?.firstOrNull() }
        if (item != null && first != null) setHero(item, first) else {
            heroTitle.text = if (isSeries) "Aucune série" else "Aucun film"
            heroPlot.text = "Cette source ne contient rien dans cette rubrique."
        }
        loadTrending()
    }

    /** Retour sur l'onglet : la reprise et les favoris ont pu changer. */
    fun refresh() {
        val newResume = resumeRail()
        val iResume = rails.indexOfFirst { it.key == "resume" }
        when {
            newResume != null && iResume >= 0 -> {
                rails[iResume] = newResume
                adapter.notifyItemChanged(iResume)
            }
            newResume != null -> {
                rails.add(0, newResume)
                adapter.notifyItemInserted(0)
            }
            iResume >= 0 -> {
                rails.removeAt(iResume)
                adapter.notifyItemRemoved(iResume)
            }
        }
        val newFav = favRail()
        val iFav = rails.indexOfFirst { it.key == "fav" }
        when {
            newFav != null && iFav >= 0 -> {
                rails[iFav] = newFav
                adapter.notifyItemChanged(iFav)
            }
            newFav != null -> {
                val at = rails.indexOfFirst { it.key.startsWith("c:") || it.key.startsWith("m:") }.let { if (it < 0) rails.size else it }
                rails.add(at, newFav)
                adapter.notifyItemInserted(at)
            }
            iFav >= 0 -> {
                rails.removeAt(iFav)
                adapter.notifyItemRemoved(iFav)
            }
        }
    }

    private fun resumeRail(): Rail? {
        if (!isSeries) {
            val list = Db.resume(src.id, 20, Kind.MOVIE)
            if (list.isEmpty()) return null
            return Rail(
                "resume", "Reprendre", list.map { r -> Db.find(src.id, Kind.MOVIE, r.item.id) ?: r.item.copy(kind = Kind.MOVIE) },
                progress = list.filter { it.dur > 0 }.associate { it.item.id to it.pos.toFloat() / it.dur },
                subs = list.filter { it.dur > 0 }.associate { it.item.id to remaining(it.dur - it.pos) }
            )
        }
        val list = Db.seriesActivity(src.id, 20)
        val items = ArrayList<Item>()
        val progress = HashMap<String, Float>()
        val subs = HashMap<String, String>()
        for ((sid, r) in list) {
            val s = Db.find(src.id, Kind.SERIES, sid) ?: continue
            items.add(s)
            val label = SE.find(r.item.extra ?: "")?.let { "S${it.groupValues[1]} · É${it.groupValues[2]}" } ?: ""
            if (r.pos >= 0 && r.dur > 0) {
                progress[sid] = r.pos.toFloat() / r.dur
                subs[sid] = listOf(label, remaining(r.dur - r.pos)).filter { it.isNotEmpty() }.joinToString(" · ")
            } else {
                subs[sid] = if (label.isNotEmpty()) "$label vu" else "Épisode suivant"
            }
        }
        if (items.isEmpty()) return null
        return Rail("resume", "Reprendre", items, progress = progress, subs = subs)
    }

    private fun favRail(): Rail? {
        val favs = Db.favorites(src.id, kind)
        return if (favs.isEmpty()) null else Rail("fav", "Ma liste", favs)
    }

    private fun loadTrending() {
        if (!Tmdb.enabled) return
        Bg.task(act, {
            val ids = Tmdb.trending(isSeries)
            Db.byTmdb(src.id, kind, ids)
        }) { r ->
            val list = r.getOrNull() ?: return@task
            if (list.size < 4) return@task
            val at = rails.indexOfFirst { it.key == "latest" }.let { if (it < 0) 0 else it + 1 }
            rails.add(at, Rail("trending", "Tendances de la semaine", list.take(30)))
            adapter.notifyItemInserted(at)
        }
    }

    private fun remaining(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(1)
        return if (min >= 60) "${min / 60} h ${String.format(java.util.Locale.FRANCE, "%02d", min % 60)}" else "$min min"
    }

    private fun chooseCategory() {
        val groups = Catalog.groups(src.id, kind)
        val labels = listOf("Tout le catalogue", "Favoris") + groups.map { it.name }
        Ui.choose(act, if (isSeries) "Catégories de séries" else "Catégories de films", labels) { w ->
            when (w) {
                0 -> openGrid(MainActivity.ALL)
                1 -> openGrid(MainActivity.FAV)
                else -> openGrid(groups[w - 2].key)
            }
        }
    }

    // ---------- Mise en avant ----------

    private fun onCardFocus(item: Item, rail: Rail) {
        pending = item to rail
        Bg.main.removeCallbacks(applyHero)
        Bg.main.postDelayed(applyHero, 350)
    }

    private fun setHero(item: Item, rail: Rail) {
        hero = item
        heroRail = rail
        heroTitle.text = Tmdb.cleanTitle(item.name).first
        heroBadge.visibility = if (rail.badge != null && item.id in rail.badgeIds) View.VISIBLE else View.GONE
        heroBadge.text = rail.badge ?: ""
        heroPlot.text = item.plot ?: ""
        heroMeta.text = Meta.line(listOf(Meta.rating(item.rating)), act.getColor(R.color.accent))
        heroPlay.text = when {
            rail.key == "resume" && !isSeries -> "Reprendre"
            isSeries -> "Épisodes"
            else -> "Lecture"
        }
        paintBackground(item)
        if (isSeries) {
            seriesInfos[item.id]?.let {
                paintSeries(item, it)
                return
            }
            Bg.task(act, { api.seriesInfo(item.id) }) { r ->
                val si = r.getOrNull() ?: return@task
                seriesInfos[item.id] = si
                if (hero?.id == item.id) paintSeries(item, si)
            }
        } else {
            movieInfos[item.id]?.let {
                paintMovie(item, it)
                return
            }
            Bg.task(act, { api.movieInfo(item.id) }) { r ->
                val mi = r.getOrNull() ?: return@task
                movieInfos[item.id] = mi
                if (hero?.id == item.id) paintMovie(item, mi)
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
                if (n > 0) (if (n == 1) "1 saison" else "$n saisons") else null,
                Meta.firstGenre(si.genre), Meta.rating(si.rating) ?: Meta.rating(item.rating), si.year
            ),
            act.getColor(R.color.accent)
        )
        heroPlot.text = si.plot ?: item.plot ?: ""
        backdrop(item, si.backdrop, si.tmdb)
    }

    /** Fond d'écran : image de fond du fournisseur, sinon TMDB, sinon l'affiche floutée. */
    private fun backdrop(item: Item, url: String?, tmdbId: String?) {
        if (url != null) {
            showBackdrop(item, url)
            return
        }
        tmdbInfos[item.key]?.backdrop?.let {
            showBackdrop(item, it)
            return
        }
        if (!Tmdb.enabled) return
        Bg.task(act, { Tmdb.info(item.copy(tmdb = tmdbId ?: item.tmdb)) }) { r ->
            val t = r.getOrNull() ?: return@task
            tmdbInfos[item.key] = t
            if (hero?.id == item.id) {
                t.backdrop?.let { showBackdrop(item, it) }
                if (heroPlot.text.isNullOrBlank()) heroPlot.text = t.overview ?: ""
            }
        }
    }

    private fun showBackdrop(item: Item, url: String) {
        Img.fetch(url, 1280) { bmp ->
            if (bmp == null || hero?.id != item.id || act.isDestroyed || !visible) return@fetch
            bg.animate().cancel()
            bg.tag = "backdrop|$url"
            bg.setImageBitmap(bmp)
            bg.alpha = 0.4f
            bg.animate().alpha(1f).setDuration(300).start()
        }
    }

    private fun paintBackground(item: Item) {
        if (!visible) return
        bg.visibility = View.VISIBLE
        Img.blurred(bg, item.icon) { ok -> if (ok && visible && hero?.id == item.id) bg.animate().alpha(0.9f).setDuration(250).start() }
    }

    // ---------- Actions ----------

    private fun primary(item: Item, rail: Rail?) {
        if (!isSeries && rail?.key == "resume") {
            val d = Downloads.find(act, src.id, item)?.playable()
            Ui.play(act, ItemList.of(listOf(d ?: item)), 0)
        } else if (!isSeries) {
            val d = Downloads.find(act, src.id, item)?.playable()
            Ui.play(act, ItemList.of(listOf(d ?: item)), 0)
        } else {
            open(item)
        }
    }

    private fun open(item: Item) {
        val cls = if (isSeries) SeriesActivity::class.java else MovieActivity::class.java
        act.startActivity(Intent(act, cls).putExtra(MainActivity.EXTRA_ITEM, item.toJson().toString()))
    }

    private fun toggleFav(item: Item) {
        val added = Db.toggleFav(src.id, item)
        Ui.toast(act, if (added) "Ajouté à Ma liste" else "Retiré de Ma liste")
        refresh()
    }

    // ---------- Adaptateurs ----------

    private inner class RailAdapter : RecyclerView.Adapter<RailAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.railTitle)
            val list: RecyclerView = v.findViewById(R.id.railList)
            val cards = CardAdapter()
            var key: String? = null
        }

        override fun getItemCount() = rails.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_rail, parent, false)
            val h = VH(v)
            h.list.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
            h.list.adapter = h.cards
            h.list.itemAnimator = null
            h.list.setHasFixedSize(true)
            h.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val k = h.key ?: return
                    railScroll[k] = (rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                }
            })
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val rail = rails[position]
            if (rail.items.isEmpty() && rail.loader != null) rail.items = rail.loader.invoke()
            h.key = rail.key
            h.title.text = rail.title
            h.cards.rail = rail
            h.cards.railPos = { h.bindingAdapterPosition }
            h.cards.notifyDataSetChanged()
            h.list.scrollToPosition(railScroll[rail.key] ?: 0)
            h.itemView.visibility = if (rail.items.isEmpty()) View.GONE else View.VISIBLE
            h.itemView.layoutParams = h.itemView.layoutParams.apply {
                height = if (rail.items.isEmpty()) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    /** Fait remonter la rangée active en haut de la zone des rangées. */
    private fun scrollRailToTop(pos: Int) {
        if (pos < 0) return
        val s = object : LinearSmoothScroller(act) {
            override fun getVerticalSnapPreference() = SNAP_TO_START
        }
        s.targetPosition = pos
        rvRails.layoutManager?.startSmoothScroll(s)
    }

    private inner class CardAdapter : RecyclerView.Adapter<CardAdapter.VH>() {
        var rail: Rail? = null
        var railPos: () -> Int = { -1 }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.img)
            val name: TextView = v.findViewById(R.id.name)
            val sub: TextView = v.findViewById(R.id.sub)
            val bar: ProgressBar = v.findViewById(R.id.bar)
            val badge: TextView = v.findViewById(R.id.badge)
            val overlay: View = v.findViewById(R.id.overlay)
        }

        override fun getItemCount() = rail?.items?.size ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
            v.clipToOutline = true
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rail ?: return
            val item = r.items[position]
            val noImage = item.icon.isNullOrBlank()
            h.name.text = Tmdb.cleanTitle(item.name).first
            h.name.visibility = if (noImage) View.VISIBLE else View.GONE
            val sub = r.subs[item.id]
            h.sub.text = sub ?: ""
            h.sub.visibility = if (sub.isNullOrBlank()) View.GONE else View.VISIBLE
            val p = r.progress[item.id]
            h.bar.visibility = if (p != null && p > 0f) View.VISIBLE else View.GONE
            if (p != null) h.bar.progress = (p * 1000).toInt().coerceIn(0, 1000)
            h.overlay.visibility = if (noImage || !sub.isNullOrBlank() || (p != null && p > 0f)) View.VISIBLE else View.GONE
            val showBadge = r.badge != null && item.id in r.badgeIds
            h.badge.visibility = if (showBadge) View.VISIBLE else View.GONE
            h.badge.text = r.badge ?: ""
            if (noImage && Tmdb.enabled) {
                val tag = "tmdb:" + item.key
                h.img.tag = tag
                h.img.setImageResource(R.drawable.ph_poster)
                Tmdb.posterAsync(item) { url ->
                    if (h.img.tag == tag) {
                        Img.load(h.img, url, R.drawable.ph_poster, 300)
                        h.name.visibility = View.GONE
                    }
                }
            } else {
                Img.load(h.img, item.icon, R.drawable.ph_poster, 300)
            }
            h.itemView.setOnClickListener {
                if (r.key == "resume" && !isSeries) primary(item, r) else open(item)
            }
            h.itemView.setOnLongClickListener {
                toggleFav(item)
                true
            }
            h.itemView.setOnFocusChangeListener { view, has ->
                val s = if (has) 1.08f else 1f
                view.animate().scaleX(s).scaleY(s).setDuration(120).start()
                view.elevation = if (has) 8f else 0f
                if (has) {
                    onCardFocus(item, r)
                    scrollRailToTop(railPos())
                }
            }
        }
    }

    companion object {
        private val SE = Regex("""Saison (\d+) · Épisode (\d+)""")
    }
}
