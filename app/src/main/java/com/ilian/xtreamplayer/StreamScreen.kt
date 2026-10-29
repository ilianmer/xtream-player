package com.ilian.xtreamplayer

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
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
 * titre mis en avant et rangées horizontales (Reprendre, Nouveautés, Tendances, Ma liste, catégories).
 * Au bout de chaque rangée, « Voir tout » ouvre la catégorie en pleine page.
 */
class StreamScreen(
    private val act: Activity,
    root: View,
    private val src: Source,
    private val api: XtreamApi,
    private val kind: Kind,
    private val bg: ImageView,
    /** Ouvre une catégorie en pleine page : clé du groupe et titre. */
    private val openGrid: (String, String) -> Unit
) {
    private class Rail(
        val key: String,
        val title: String,
        var items: List<Item>,
        val loader: (() -> List<Item>)? = null,
        var progress: Map<String, Float> = emptyMap(),
        var subs: Map<String, String> = emptyMap(),
        var badge: String? = null,
        var badgeIds: Set<String> = emptySet(),
        /** Groupe ouvert par « Voir tout » (null : pas de carte Voir tout). */
        val seeAll: String? = null
    )

    private val rvRails: RecyclerView = root.findViewById(R.id.rvRails)
    private val heroTitle: TextView = root.findViewById(R.id.sHeroTitle)
    private val heroBadge: TextView = root.findViewById(R.id.sHeroBadge)
    private val heroMeta: TextView = root.findViewById(R.id.sHeroMeta)
    private val heroPlot: TextView = root.findViewById(R.id.sHeroPlot)

    private val rails = ArrayList<Rail>()
    private val adapter = RailAdapter()
    private var hero: Item? = null
    private var heroRail: Rail? = null
    private var pending: Pair<Item, Rail>? = null
    private var built = false
    private var visible = false
    private val movieInfos = HashMap<String, MovieInfo>()
    private val seriesInfos = HashMap<String, SeriesInfo>()
    private val extraBackdrops = HashMap<String, String?>()
    /** Position mémorisée dans chaque rangée (comme Netflix : chaque rangée garde sa place). */
    private val railFocus = HashMap<String, Int>()
    private var activeRail = -1
    private val applyHero = Runnable { pending?.let { (i, r) -> setHero(i, r) } }

    private val isSeries = kind == Kind.SERIES

    init {
        root.findViewById<TextView>(R.id.streamTitle).text = if (isSeries) "Séries" else "Films"
        rvRails.layoutManager = LinearLayoutManager(act)
        rvRails.adapter = adapter
        rvRails.itemAnimator = null
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
        rvRails.post { if (rails.isNotEmpty()) focusRail(if (activeRail >= 0) activeRail else firstRail(), 0) else focusTab() }
    }

    /** Onglet Films ou Séries de la barre du haut. */
    private fun focusTab() {
        act.findViewById<View>(if (isSeries) R.id.tabSeries else R.id.tabMovies)?.requestFocus()
    }

    private fun firstRail(): Int = rails.indexOfFirst { it.items.isNotEmpty() || it.loader != null }.coerceAtLeast(0)

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
                    badgeIds = latest.filter { it.added >= recent }.map { it.id }.toSet(),
                    seeAll = MainActivity.LATEST
                )
            )
        }
        favRail()?.let { rails.add(it) }
        for (g in Catalog.groups(src.id, kind)) {
            rails.add(Rail(g.key, Catalog.cleanName(g.name), emptyList(), loader = { Db.page(src.id, kind, g.catIds, null, 0, 30) }, seeAll = g.key))
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

    /** Retour sur l'onglet : la reprise et Ma liste ont pu changer. */
    fun refresh() {
        replaceRail("resume", resumeRail(), 0)
        replaceRail("fav", favRail(), rails.indexOfFirst { it.key.startsWith("c:") || it.key.startsWith("m:") }.let { if (it < 0) rails.size else it })
    }

    private fun replaceRail(key: String, new: Rail?, insertAt: Int) {
        val i = rails.indexOfFirst { it.key == key }
        when {
            new != null && i >= 0 -> {
                rails[i] = new
                adapter.notifyItemChanged(i)
            }
            new != null -> {
                val at = insertAt.coerceIn(0, rails.size)
                rails.add(at, new)
                adapter.notifyItemInserted(at)
                if (activeRail >= at) activeRail++
            }
            i >= 0 -> {
                rails.removeAt(i)
                adapter.notifyItemRemoved(i)
                if (activeRail > i) activeRail-- else if (activeRail == i) activeRail = -1
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
                subs = list.filter { it.dur > 0 }.associate { it.item.id to remaining(it.dur - it.pos) },
                seeAll = MainActivity.RESUME
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
                subs[sid] = "Épisode suivant"
            }
        }
        if (items.isEmpty()) return null
        return Rail("resume", "Reprendre", items, progress = progress, subs = subs, seeAll = MainActivity.RESUME)
    }

    private fun favRail(): Rail? {
        val favs = Db.favorites(src.id, kind)
        return if (favs.isEmpty()) null else Rail("fav", "Ma liste", favs, seeAll = MainActivity.FAV)
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
            if (activeRail >= at) activeRail++
        }
    }

    private fun remaining(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(1)
        return if (min >= 60) "${min / 60} h ${String.format(java.util.Locale.FRANCE, "%02d", min % 60)}" else "$min min"
    }

    // ---------- Navigation verticale ----------

    /**
     * Haut / bas : rangée précédente ou suivante, sur l'élément où l'on s'était arrêté dans cette
     * rangée (le focus ne part plus de l'autre côté de l'écran).
     */
    private fun focusRail(target: Int, dir: Int) {
        var t = target
        // On saute les rangées vides (catégorie sans titre)
        while (t in rails.indices) {
            val r = rails[t]
            if (r.items.isEmpty() && r.loader != null) r.items = r.loader.invoke()
            if (r.items.isNotEmpty()) break
            t += if (dir == 0) 1 else dir
        }
        if (t !in rails.indices) return
        val rail = rails[t]
        val idx = (railFocus[rail.key] ?: 0).coerceIn(0, cardCount(rail) - 1)
        activeRail = t
        scrollRailToTop(t)
        val lm = rvRails.layoutManager as LinearLayoutManager
        val attempt = {
            val holder = rvRails.findViewHolderForAdapterPosition(t) as? RailAdapter.VH
            if (holder != null) Fx.focusAt(holder.list, idx)
            holder != null
        }
        if (!attempt()) {
            lm.scrollToPositionWithOffset(t, 0)
            rvRails.post { attempt() }
        }
    }

    private fun cardCount(r: Rail): Int = r.items.size + (if (r.seeAll != null && r.items.size >= 6) 1 else 0)

    /** Fait remonter la rangée active en haut de la zone des rangées, et estompe les autres. */
    private fun scrollRailToTop(pos: Int) {
        if (pos < 0) return
        val s = object : LinearSmoothScroller(act) {
            override fun getVerticalSnapPreference() = SNAP_TO_START
        }
        s.targetPosition = pos
        rvRails.layoutManager?.startSmoothScroll(s)
        for (i in 0 until rvRails.childCount) {
            val c = rvRails.getChildAt(i)
            val p = rvRails.getChildAdapterPosition(c)
            c.animate().alpha(if (p == pos || p < 0) 1f else 0.55f).setDuration(200).start()
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

    /** Fond d'écran : fournisseur, puis TMDB, puis TVmaze ; sinon l'affiche floutée. */
    private fun backdrop(item: Item, url: String?, tmdbId: String?) {
        if (url != null) {
            showBackdrop(item, url)
            return
        }
        if (extraBackdrops.containsKey(item.key)) {
            extraBackdrops[item.key]?.let { showBackdrop(item, it) }
            return
        }
        Bg.task(act, {
            val t = if (Tmdb.enabled) Tmdb.info(item.copy(tmdb = tmdbId ?: item.tmdb)) else null
            (t?.backdrop ?: FreeMeta.backdrop(item)) to t?.overview
        }) { r ->
            val (b, overview) = r.getOrNull() ?: (null to null)
            extraBackdrops[item.key] = b
            if (hero?.id != item.id) return@task
            if (heroPlot.text.isNullOrBlank() && overview != null) heroPlot.text = overview
            b?.let { showBackdrop(item, it) }
        }
    }

    private fun showBackdrop(item: Item, url: String) {
        Img.fetch(url, 1280) { bmp ->
            if (bmp == null || hero?.id != item.id || act.isDestroyed || !visible) return@fetch
            bg.animate().cancel()
            bg.tag = "backdrop|$url"
            bg.setImageBitmap(bmp)
            bg.alpha = 0.35f
            bg.scaleX = 1f
            bg.scaleY = 1f
            // Fondu puis léger zoom lent
            bg.animate().alpha(1f).setDuration(350).withEndAction {
                bg.animate().scaleX(1.05f).scaleY(1.05f).setDuration(12_000).start()
            }.start()
        }
    }

    private fun paintBackground(item: Item) {
        if (!visible) return
        bg.visibility = View.VISIBLE
        bg.scaleX = 1f
        bg.scaleY = 1f
        Img.blurred(bg, item.icon) { ok -> if (ok && visible && hero?.id == item.id) bg.animate().alpha(0.9f).setDuration(250).start() }
    }

    // ---------- Actions ----------

    private fun primary(item: Item, rail: Rail?) {
        if (isSeries) {
            open(item)
            return
        }
        val d = Downloads.find(act, src.id, item)?.playable()
        Ui.play(act, ItemList.of(listOf(d ?: item)), 0)
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
        }

        override fun getItemCount() = rails.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_rail, parent, false)
            val h = VH(v)
            h.list.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
            h.list.adapter = h.cards
            h.list.itemAnimator = null
            h.list.setHasFixedSize(true)
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val rail = rails[position]
            if (rail.items.isEmpty() && rail.loader != null) rail.items = rail.loader.invoke()
            h.title.text = rail.title
            h.cards.rail = rail
            h.cards.railPos = { h.bindingAdapterPosition }
            h.cards.notifyDataSetChanged()
            val keep = railFocus[rail.key] ?: 0
            (h.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(keep, h.list.paddingStart)
            h.itemView.alpha = if (activeRail < 0 || activeRail == position) 1f else 0.55f
            h.itemView.visibility = if (rail.items.isEmpty()) View.GONE else View.VISIBLE
            h.itemView.layoutParams = h.itemView.layoutParams.apply {
                height = if (rail.items.isEmpty()) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    private inner class CardAdapter : RecyclerView.Adapter<CardAdapter.VH>() {
        var rail: Rail? = null
        var railPos: () -> Int = { -1 }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView? = v.findViewById(R.id.img)
            val name: TextView? = v.findViewById(R.id.name)
            val sub: TextView? = v.findViewById(R.id.sub)
            val bar: ProgressBar? = v.findViewById(R.id.bar)
            val badge: TextView? = v.findViewById(R.id.badge)
            val overlay: View? = v.findViewById(R.id.overlay)
        }

        override fun getItemCount() = rail?.let { cardCount(it) } ?: 0

        override fun getItemViewType(position: Int) = if (position >= (rail?.items?.size ?: 0)) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = if (viewType == 1) R.layout.item_card_more else R.layout.item_card
            val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            v.clipToOutline = true
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rail ?: return
            val count = cardCount(r)
            h.itemView.setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val p = railPos()
                        val prev = (p - 1 downTo 0).firstOrNull { i -> rails[i].items.isNotEmpty() || rails[i].loader != null }
                        if (prev == null) {
                            // Tout en haut : retour à la barre d'onglets (plus de boutons sur la mise en avant)
                            activeRail = -1
                            rvRails.smoothScrollToPosition(0)
                            focusTab()
                        } else focusRail(prev, -1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val p = railPos()
                        if (p + 1 < rails.size) focusRail(p + 1, 1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val rv = h.itemView.parent as? RecyclerView
                        if (rv != null && position + 1 < count) Fx.focusAt(rv, position + 1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val rv = h.itemView.parent as? RecyclerView
                        if (rv != null && position > 0) Fx.focusAt(rv, position - 1)
                        true
                    }
                    else -> false
                }
            }
            h.itemView.setOnFocusChangeListener { view, has ->
                Fx.focus(view, has, 1.08f)
                if (has) {
                    railFocus[r.key] = position
                    val p = railPos()
                    if (p != activeRail) {
                        activeRail = p
                        scrollRailToTop(p)
                    }
                    r.items.getOrNull(position)?.let { onCardFocus(it, r) }
                }
            }
            if (position >= r.items.size) {
                // Carte « Voir tout »
                h.itemView.setOnClickListener { r.seeAll?.let { openGrid(it, r.title) } }
                h.itemView.setOnLongClickListener(null)
                return
            }
            val item = r.items[position]
            val noImage = item.icon.isNullOrBlank()
            h.name?.text = Tmdb.cleanTitle(item.name).first
            h.name?.visibility = if (noImage) View.VISIBLE else View.GONE
            val sub = r.subs[item.id]
            h.sub?.text = sub ?: ""
            h.sub?.visibility = if (sub.isNullOrBlank()) View.GONE else View.VISIBLE
            val p = r.progress[item.id]
            h.bar?.visibility = if (p != null && p > 0f) View.VISIBLE else View.GONE
            if (p != null) h.bar?.progress = (p * 1000).toInt().coerceIn(0, 1000)
            h.overlay?.visibility = if (noImage || !sub.isNullOrBlank() || (p != null && p > 0f)) View.VISIBLE else View.GONE
            val showBadge = r.badge != null && item.id in r.badgeIds
            h.badge?.visibility = if (showBadge) View.VISIBLE else View.GONE
            h.badge?.text = r.badge ?: ""
            val img = h.img
            if (img != null) {
                if (noImage && Tmdb.enabled) {
                    val tag = "tmdb:" + item.key
                    img.tag = tag
                    img.setImageResource(R.drawable.ph_poster)
                    Tmdb.posterAsync(item) { url ->
                        if (img.tag == tag) {
                            Img.load(img, url, R.drawable.ph_poster, 300)
                            h.name?.visibility = View.GONE
                        }
                    }
                } else {
                    Img.load(img, item.icon, R.drawable.ph_poster, 300)
                }
            }
            h.itemView.setOnClickListener {
                if (r.key == "resume" && !isSeries) primary(item, r) else open(item)
            }
            h.itemView.setOnLongClickListener {
                toggleFav(item)
                true
            }
        }
    }

    companion object {
        private val SE = Regex("""Saison (\d+) · Épisode (\d+)""")
    }
}
