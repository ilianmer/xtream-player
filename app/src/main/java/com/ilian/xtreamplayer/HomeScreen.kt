package com.ilian.xtreamplayer

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Onglet Accueil : film à la une, chaînes en direct, reprise de lecture et catégories.
 * [openGroup] ouvre un onglet de catalogue sur une catégorie (clé de groupe, ou null pour la première).
 */
class HomeScreen(
    private val act: Activity,
    root: View,
    private val src: Source,
    private val api: XtreamApi,
    private val bg: ImageView,
    private val openGroup: (Kind, String?) -> Unit
) {
    private val inf = act.layoutInflater

    private val liveBox: View = root.findViewById(R.id.liveBox)
    private val liveHint: TextView = root.findViewById(R.id.liveHint)
    private val liveTile: View = root.findViewById(R.id.liveTile)
    private val liveTileBg: ImageView = root.findViewById(R.id.liveTileBg)
    private val liveTileLogo: ImageView = root.findViewById(R.id.liveTileLogo)
    private val liveTileName: TextView = root.findViewById(R.id.liveTileName)
    private val liveTileProg: TextView = root.findViewById(R.id.liveTileProg)
    private val liveBar: ProgressBar = root.findViewById(R.id.liveBar)
    private val liveRows: LinearLayout = root.findViewById(R.id.liveRows)

    private val resumeRows: LinearLayout = root.findViewById(R.id.resumeRows)
    private val resumeEmpty: View = root.findViewById(R.id.resumeEmpty)
    private val btnResumeAll: View = root.findViewById(R.id.btnResumeAll)

    private val hero: View = root.findViewById(R.id.hero)
    private val heroBlur: ImageView = root.findViewById(R.id.heroBlur)
    private val heroBackdrop: ImageView = root.findViewById(R.id.heroBackdrop)
    private val heroPoster: ImageView = root.findViewById(R.id.heroPoster)
    private val heroTitle: TextView = root.findViewById(R.id.heroTitle)
    private val heroMeta: TextView = root.findViewById(R.id.heroMeta)
    private val heroPlot: TextView = root.findViewById(R.id.heroPlot)
    private val heroPlay: TextView = root.findViewById(R.id.heroPlay)
    private val heroInfo: ImageView = root.findViewById(R.id.heroInfo)
    private val heroFav: ImageView = root.findViewById(R.id.heroFav)
    private val heroDl: ImageView = root.findViewById(R.id.heroDl)
    private val heroDots: LinearLayout = root.findViewById(R.id.heroDots)
    private val heroEmpty: View = root.findViewById(R.id.heroEmpty)
    private val catTiles: LinearLayout = root.findViewById(R.id.catTiles)

    private var featured: List<Item> = emptyList()
    private var heroIndex = 0
    private val infos = HashMap<String, MovieInfo>()
    private val tmdbInfos = HashMap<String, Tmdb.Info>()
    private var live: List<Item> = emptyList()
    private var running = false

    private val rotate = object : Runnable {
        override fun run() {
            if (!running) return
            // On ne change pas le film pendant que la télécommande est dessus.
            if (featured.size > 1 && !hero.hasFocus()) showHero((heroIndex + 1) % featured.size)
            Bg.main.postDelayed(this, ROTATE_MS)
        }
    }

    init {
        hero.clipToOutline = true
        liveTile.clipToOutline = true
        heroPoster.clipToOutline = true
        heroPlay.setOnClickListener { current()?.let { playMovie(it) } }
        heroInfo.setOnClickListener { current()?.let { openItem(it) } }
        heroFav.setOnClickListener {
            val item = current() ?: return@setOnClickListener
            val added = Db.toggleFav(src.id, item)
            Ui.toast(act, if (added) "Ajouté aux favoris" else "Retiré des favoris")
            paintHeroButtons()
        }
        heroDl.setOnClickListener { current()?.let { download(it) } }
        btnResumeAll.setOnClickListener { openGroup(Kind.MOVIE, MainActivity.RESUME) }
        liveTile.setOnClickListener { playLive(0) }
    }

    fun start() {
        loadFeatured()
        fillLive()
        fillResume()
        fillCategories()
    }

    /** Retour sur l'accueil (après une lecture, une fiche, les réglages). */
    fun refresh() {
        fillResume()
        fillLive()
        paintHeroButtons()
    }

    fun resume() {
        if (running) return
        running = true
        Bg.main.removeCallbacks(rotate)
        Bg.main.postDelayed(rotate, ROTATE_MS)
        current()?.let { paintBg(it) }
    }

    /** Le fond de l'écran reprend l'affiche du film à la une, très floutée. */
    private fun paintBg(item: Item) {
        Img.blurred(bg, item.icon) { ok -> if (ok && running) bg.animate().alpha(0.55f).setDuration(400).start() }
    }

    fun pause() {
        running = false
        Bg.main.removeCallbacks(rotate)
    }

    fun focusDefault() {
        if (featured.isNotEmpty()) heroPlay.requestFocus() else liveTile.requestFocus()
    }

    // ---------- À la une ----------

    private fun current(): Item? = featured.getOrNull(heroIndex)

    private fun loadFeatured() {
        val pool = ArrayList<Item>()
        for (g in Catalog.groups(src.id, Kind.MOVIE).take(5)) {
            pool.addAll(Db.page(src.id, Kind.MOVIE, g.catIds, null, 0, 30).filter { !it.icon.isNullOrBlank() })
            if (pool.size >= 40) break
        }
        if (pool.isEmpty()) pool.addAll(Db.page(src.id, Kind.MOVIE, null, null, 0, 40))
        // Même sélection toute la journée, nouvelle sélection le lendemain.
        val day = System.currentTimeMillis() / 86_400_000L
        featured = pool.distinctBy { it.id }.shuffled(Random(day * 31 + src.id)).take(5)

        heroDots.removeAllViews()
        if (featured.isEmpty()) {
            heroEmpty.visibility = View.VISIBLE
            listOf(heroTitle, heroMeta, heroPlot).forEach { it.text = "" }
            (heroPlay.parent as View).visibility = View.GONE
            return
        }
        val dp = act.resources.displayMetrics.density
        if (featured.size > 1) for (i in featured.indices) {
            val d = View(act)
            val lp = LinearLayout.LayoutParams((6 * dp).toInt(), (4 * dp).toInt())
            lp.marginStart = (5 * dp).toInt()
            heroDots.addView(d, lp)
        }
        showHero(0)
    }

    private fun showHero(i: Int) {
        val item = featured.getOrNull(i) ?: return
        heroIndex = i
        val dp = act.resources.displayMetrics.density
        for (j in 0 until heroDots.childCount) {
            val d = heroDots.getChildAt(j)
            d.setBackgroundResource(if (j == i) R.drawable.dot_on else R.drawable.dot_off)
            d.layoutParams = d.layoutParams.apply { width = ((if (j == i) 16 else 6) * dp).toInt() }
        }
        heroTitle.text = Tmdb.cleanTitle(item.name).first
        heroPlot.text = item.plot ?: ""
        heroMeta.text = Meta.line(listOf(Meta.rating(item.rating)), act.getColor(R.color.accent))
        heroBackdrop.animate().cancel()
        heroBackdrop.alpha = 0f
        heroBackdrop.setImageDrawable(null)
        heroPoster.visibility = View.GONE
        Img.blurred(heroBlur, item.icon)
        if (running) paintBg(item)
        paintHeroButtons()

        val cachedInfo = infos[item.id]
        if (cachedInfo != null) {
            paintHeroInfo(item, cachedInfo)
            return
        }
        Bg.task(act, {
            val info = try {
                api.movieInfo(item.id)
            } catch (_: Exception) {
                null
            }
            val t = if (info?.backdrop == null && Tmdb.enabled) {
                try {
                    Tmdb.info(item.copy(tmdb = info?.tmdb ?: item.tmdb))
                } catch (_: Exception) {
                    null
                }
            } else null
            info to t
        }) { r ->
            val (info, t) = r.getOrNull() ?: (null to null)
            if (info != null) infos[item.id] = info
            if (t != null) tmdbInfos[item.id] = t
            if (current()?.id == item.id) paintHeroInfo(item, info)
        }
    }

    private fun paintHeroInfo(item: Item, info: MovieInfo?) {
        val t = tmdbInfos[item.id]
        heroMeta.text = Meta.line(
            listOf(
                info?.year ?: t?.year,
                Meta.duration(info?.duration) ?: Meta.minutes(t?.runtime),
                Meta.rating(info?.rating) ?: Meta.rating(item.rating) ?: Meta.rating(t?.rating),
                Meta.firstGenre(info?.genre ?: t?.genres)
            ),
            act.getColor(R.color.accent)
        )
        heroPlot.text = info?.plot ?: item.plot ?: t?.overview ?: ""

        val backdrop = info?.backdrop ?: t?.backdrop
        if (backdrop != null) {
            Img.fetch(backdrop, 900) { bmp ->
                if (current()?.id != item.id) return@fetch
                if (bmp != null) {
                    heroBackdrop.setImageBitmap(bmp)
                    heroBackdrop.animate().alpha(1f).setDuration(350).start()
                } else {
                    showPoster(item, info)
                }
            }
        } else {
            showPoster(item, info)
        }
    }

    /** Sans image de fond, on montre l'affiche nette à droite, sur le fond flouté. */
    private fun showPoster(item: Item, info: MovieInfo?) {
        val url = item.icon ?: info?.cover ?: tmdbInfos[item.id]?.poster ?: return
        heroPoster.visibility = View.VISIBLE
        Img.load(heroPoster, url, R.drawable.ph_poster, 420)
    }

    private fun paintHeroButtons() {
        val item = current() ?: return
        val fav = Db.isFav(src.id, item)
        heroFav.setImageResource(if (fav) R.drawable.ic_check else R.drawable.ic_plus)
        heroFav.contentDescription = if (fav) "Retirer des favoris" else "Ajouter aux favoris"
        val p = Db.progress(src.id, item)
        heroPlay.text = when {
            p != null && !p.finished && p.pos > 0 -> "Reprendre"
            p?.finished == true -> "Revoir"
            else -> "Regarder"
        }
    }

    // ---------- Direct ----------

    private fun fillLive() {
        val favs = Db.favorites(src.id, Kind.LIVE)
        live = if (favs.isNotEmpty()) {
            liveHint.text = "Chaînes favorites"
            favs
        } else {
            liveHint.text = "Suggestions"
            val g = Catalog.groups(src.id, Kind.LIVE).firstOrNull()
            if (g != null) Db.page(src.id, Kind.LIVE, g.catIds, null, 0, 3) else emptyList()
        }
        if (live.isEmpty()) {
            liveBox.visibility = View.GONE
            return
        }
        liveBox.visibility = View.VISIBLE
        val first = live[0]
        liveTileName.text = first.name
        Img.load(liveTileLogo, first.icon, R.drawable.ic_play)
        Img.blurred(liveTileBg, first.icon)
        epg(first) { now, _ ->
            liveTileProg.text = now?.title ?: ""
            liveTileProg.visibility = if (now == null) View.GONE else View.VISIBLE
            val pct = now?.let { progressOf(it) }
            liveBar.visibility = if (pct == null) View.GONE else View.VISIBLE
            if (pct != null) liveBar.progress = (pct * 1000).toInt()
        }

        liveRows.removeAllViews()
        for (i in 1 until minOf(live.size, 3)) {
            val ch = live[i]
            val v = inf.inflate(R.layout.item_home_channel, liveRows, false)
            v.findViewById<TextView>(R.id.name).text = ch.name
            Img.load(v.findViewById(R.id.img), ch.icon, R.drawable.ph_live)
            val sub = v.findViewById<TextView>(R.id.sub)
            val time = v.findViewById<TextView>(R.id.time)
            epg(ch) { now, _ ->
                sub.text = now?.title ?: ""
                sub.visibility = if (now == null) View.GONE else View.VISIBLE
                time.text = now?.takeIf { it.end > 0 }?.let { "→ " + hhmm(it.end) } ?: ""
            }
            v.setOnClickListener { playLive(i) }
            liveRows.addView(v)
        }
    }

    private fun epg(item: Item, done: (EpgEntry?, EpgEntry?) -> Unit) {
        if (!Prefs.epgEnabled) {
            done(null, null)
            return
        }
        Epg.prefetch(api, item.id) { l ->
            if (act.isDestroyed) return@prefetch
            val (now, next) = Epg.nowAndNext(l)
            done(now, next)
        }
    }

    private fun progressOf(e: EpgEntry): Float? {
        if (e.start <= 0 || e.end <= e.start) return null
        val t = System.currentTimeMillis() / 1000
        return ((t - e.start).toFloat() / (e.end - e.start)).coerceIn(0f, 1f)
    }

    private fun hhmm(sec: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(sec * 1000))

    private fun playLive(i: Int) {
        if (i !in live.indices) return
        Ui.play(act, ItemList.of(live), i)
    }

    // ---------- Reprendre ----------

    private fun fillResume() {
        val list = Db.resume(src.id, 3)
        resumeRows.removeAllViews()
        resumeEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        btnResumeAll.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        for (r in list) {
            val v = inf.inflate(R.layout.item_home_resume, resumeRows, false)
            val img = v.findViewById<ImageView>(R.id.img)
            img.clipToOutline = true
            Img.load(img, r.item.icon, R.drawable.ph_poster)
            v.findViewById<TextView>(R.id.name).text = r.item.name
            val bar = v.findViewById<ProgressBar>(R.id.bar)
            val sub = v.findViewById<TextView>(R.id.sub)
            if (r.dur > 0) {
                bar.progress = (r.pos * 1000 / r.dur).toInt().coerceIn(0, 1000)
                sub.text = remaining(r.dur - r.pos)
            } else {
                bar.progress = 0
                sub.text = Ui.time(r.pos)
            }
            v.setOnClickListener {
                val local = Downloads.find(act, src.id, r.item)?.playable()
                Ui.play(act, ItemList.of(listOf(local ?: r.item)), 0)
            }
            resumeRows.addView(v)
        }
    }

    private fun remaining(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(1)
        return if (min >= 60) "${min / 60} h ${String.format(Locale.FRANCE, "%02d", min % 60)}" else "$min min"
    }

    // ---------- Catégories ----------

    private fun fillCategories() {
        catTiles.removeAllViews()
        val groups = Catalog.groups(src.id, Kind.MOVIE).take(5)
        val dp = act.resources.displayMetrics.density
        groups.forEachIndexed { i, g ->
            val v = inf.inflate(R.layout.item_cat_tile, catTiles, false)
            v.setBackgroundResource(TILES[i % TILES.size])
            v.clipToOutline = true
            v.findViewById<TextView>(R.id.name).text = Catalog.cleanName(g.name)
            if (i > 0) (v.layoutParams as LinearLayout.LayoutParams).marginStart = (9 * dp).toInt()
            v.setOnFocusChangeListener { view, has ->
                val s = if (has) 1.04f else 1f
                view.animate().scaleX(s).scaleY(s).setDuration(120).start()
            }
            v.setOnClickListener { openGroup(Kind.MOVIE, g.key) }
            catTiles.addView(v)
        }
        (catTiles.parent as View).visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------- Actions ----------

    private fun playMovie(item: Item) {
        val d = Downloads.find(act, src.id, item)?.playable()
        Ui.play(act, ItemList.of(listOf(d ?: item)), 0)
    }

    private fun openItem(item: Item) {
        act.startActivity(Intent(act, MovieActivity::class.java).putExtra(MainActivity.EXTRA_ITEM, item.toJson().toString()))
    }

    private fun download(item: Item) {
        val d = Downloads.find(act, src.id, item)
        when {
            d == null -> {
                val err = Downloads.start(act, src.id, item, api.streamUrl(item, Prefs.liveFormat))
                Ui.toast(act, err ?: "Téléchargement lancé. Retrouve-le dans l'onglet Téléchargements.")
            }
            d.finished -> playMovie(item)
            else -> Ui.toast(act, "Déjà en cours : ${d.label}")
        }
    }

    companion object {
        private const val ROTATE_MS = 9_000L
        private val TILES = listOf(
            R.drawable.cat_tile_1, R.drawable.cat_tile_2, R.drawable.cat_tile_3,
            R.drawable.cat_tile_4, R.drawable.cat_tile_5, R.drawable.cat_tile_6
        )
    }
}
