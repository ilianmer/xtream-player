package com.ilian.xtreamplayer

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

/** Fiche d'une série : saisons, épisodes, reprise. */
class SeriesActivity : BaseActivity() {

    private lateinit var src: Source
    private lateinit var api: XtreamApi
    private lateinit var series: Item
    private lateinit var btnSeason: Button
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var tvStatus: TextView
    private lateinit var tvPlot: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvCount: TextView
    private lateinit var ivBg: ImageView
    private lateinit var ivBackdrop: ImageView
    private lateinit var ivCover: ImageView
    private lateinit var btnFav: Button
    private lateinit var btnResume: Button

    private var season = -1
    private val episodeAdapter = EpisodeAdapter(::playEpisode, ::episodeMenu)
    private var info: SeriesInfo? = null
    private var tmdb: Tmdb.Info? = null
    private var episodes: List<Item> = emptyList()
    private var resumeTarget: Item? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Session.source()
        val a = Session.api()
        val raw = intent.getStringExtra(MainActivity.EXTRA_ITEM)
        if (s == null || a == null || raw == null) {
            finish()
            return
        }
        src = s
        api = a
        series = Item.fromJson(JSONObject(raw))
        setContentView(R.layout.activity_series)

        ivBg = findViewById(R.id.ivBg)
        ivBackdrop = findViewById(R.id.ivBackdrop)
        ivCover = findViewById(R.id.ivCover)
        ivCover.clipToOutline = true
        tvPlot = findViewById(R.id.tvPlot)
        tvMeta = findViewById(R.id.tvMeta)
        tvCount = findViewById(R.id.tvCount)
        tvStatus = findViewById(R.id.tvStatus)
        btnSeason = findViewById(R.id.btnSeason)
        btnSeason.setOnClickListener { chooseSeason() }
        rvEpisodes = findViewById(R.id.rvEpisodes)
        btnFav = findViewById(R.id.btnFav)
        btnResume = findViewById(R.id.btnResume)

        findViewById<TextView>(R.id.tvTitle).text = Tmdb.cleanTitle(series.name).first
        val back = findViewById<TextView>(R.id.btnBack)
        back.text = categoryLabel()
        back.setOnClickListener { finish() }
        tvPlot.text = series.plot ?: ""
        Img.load(ivCover, series.icon, R.drawable.ph_poster, 400)
        Img.blurred(ivBg, series.icon)
        paintFav()
        btnFav.setOnClickListener {
            val added = Db.toggleFav(src.id, series)
            Ui.toast(this, if (added) "Ajoutée aux favoris" else "Retirée des favoris")
            paintFav()
        }
        btnResume.setOnClickListener {
            val target = resumeTarget ?: return@setOnClickListener
            val all = allEpisodes()
            playFrom(all, all.indexOfFirst { it.id == target.id }.coerceAtLeast(0))
        }

        rvEpisodes.layoutManager = LinearLayoutManager(this)
        rvEpisodes.adapter = episodeAdapter
        rvEpisodes.itemAnimator = null
        // Haut depuis le premier épisode : le choix de la saison
        rvEpisodes.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.setOnKeyListener { v, code, e ->
                    if (e.action == android.view.KeyEvent.ACTION_DOWN && code == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                        rvEpisodes.getChildAdapterPosition(v) == 0 && btnSeason.visibility == View.VISIBLE
                    ) {
                        btnSeason.requestFocus()
                        true
                    } else false
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })

        tvStatus.text = "Chargement des épisodes…"
        btnFav.requestFocus()
        Bg.task(this, { api.seriesInfo(series.id) }) { r ->
            r.onSuccess { show(it) }.onFailure { tvStatus.text = Bg.err(it) }
        }
    }

    override fun onRestart() {
        super.onRestart()
        refreshProgress()
    }

    private fun categoryLabel(): String {
        val catId = series.catId ?: Db.find(src.id, Kind.SERIES, series.id)?.catId
        val name = catId?.let { id -> Db.categories(src.id, Kind.SERIES).firstOrNull { it.first.id == id }?.first?.name }
        return if (name != null) "Séries · ${Catalog.cleanName(name)}" else "Séries"
    }

    private fun paintFav() {
        val fav = Db.isFav(src.id, series)
        btnFav.text = if (fav) "Dans tes favoris" else "Ajouter aux favoris"
        btnFav.setCompoundDrawablesRelativeWithIntrinsicBounds(if (fav) R.drawable.ic_check_small else R.drawable.ic_plus_small, 0, 0, 0)
    }

    private fun paintMeta() {
        val si = info
        val t = tmdb
        val n = si?.seasons?.size ?: 0
        tvMeta.text = Meta.line(
            listOf(
                if (n > 0) (if (n == 1) "1 saison" else "$n saisons") else null,
                Meta.firstGenre(si?.genre ?: t?.genres),
                Meta.rating(si?.rating) ?: Meta.rating(series.rating) ?: Meta.rating(t?.rating),
                si?.year ?: t?.year
            ),
            getColor(R.color.accent)
        )
    }

    private fun show(si: SeriesInfo) {
        info = si
        if (!si.plot.isNullOrBlank()) tvPlot.text = si.plot
        if (series.icon.isNullOrBlank() && si.cover != null) {
            Img.load(ivCover, si.cover, R.drawable.ph_poster, 400)
            Img.blurred(ivBg, si.cover)
        }
        paintMeta()
        si.backdrop?.let { showBackdrop(it) }
        if (!Tmdb.enabled && (si.backdrop == null || tvPlot.text.isNullOrBlank())) {
            Bg.task(this, { FreeMeta.series(series) }) { r ->
                val f = r.getOrNull() ?: return@task
                if (si.backdrop == null) f.backdrop?.let { showBackdrop(it) }
                if (tvPlot.text.isNullOrBlank()) f.plot?.let { tvPlot.text = it }
                if (series.icon.isNullOrBlank() && si.cover == null) f.poster?.let {
                    Img.load(ivCover, it, R.drawable.ph_poster, 400)
                    Img.blurred(ivBg, it)
                }
            }
        }
        val needTmdb = Tmdb.enabled && (tvPlot.text.isNullOrBlank() || si.backdrop == null || (series.icon.isNullOrBlank() && si.cover == null))
        if (needTmdb) {
            val forTmdb = series.copy(tmdb = si.tmdb ?: series.tmdb)
            Bg.task(this, { Tmdb.info(forTmdb) }) { t ->
                val ti = t.getOrNull() ?: return@task
                tmdb = ti
                paintMeta()
                if (tvPlot.text.isNullOrBlank() && ti.overview != null) tvPlot.text = ti.overview
                if (series.icon.isNullOrBlank() && si.cover == null && ti.poster != null) {
                    Img.load(ivCover, ti.poster, R.drawable.ph_poster, 400)
                    Img.blurred(ivBg, ti.poster)
                }
                if (si.backdrop == null) ti.backdrop?.let { showBackdrop(it) }
            }
        }
        if (si.seasons.isEmpty()) {
            tvStatus.text = "Aucun épisode disponible."
            return
        }
        tvStatus.visibility = View.GONE
        btnSeason.visibility = View.VISIBLE
        paintResume()
        // On ouvre la saison où l'on s'était arrêté.
        val start = resumeTarget?.let { t -> si.seasons.indexOfFirst { s -> s.episodes.any { it.id == t.id } } }?.takeIf { it >= 0 } ?: 0
        selectSeason(start)
        if (btnResume.visibility == View.VISIBLE) btnResume.requestFocus() else btnSeason.requestFocus()
    }

    private fun showBackdrop(url: String) {
        Img.fetch(url, 1280) { bmp ->
            if (bmp == null || isDestroyed) return@fetch
            ivBackdrop.setImageBitmap(bmp)
            ivBackdrop.animate().alpha(1f).setDuration(350).start()
        }
    }

    private fun allEpisodes(): List<Item> = info?.seasons?.flatMap { it.episodes } ?: episodes

    /** Bouton principal : reprendre l'épisode en cours, ou passer au suivant du dernier vu. */
    private fun paintResume() {
        val all = allEpisodes()
        if (all.isEmpty()) {
            btnResume.visibility = View.GONE
            return
        }
        val prog = Db.progressMap(src.id, Kind.EPISODE, all.map { it.id })
        val last = all.indexOfLast { prog[it.id]?.let { p -> p.finished || p.pos > 0 } == true }
        val (target, verb) = when {
            last < 0 -> all[0] to "Lecture"
            prog[all[last].id]?.finished == true -> {
                if (last + 1 < all.size) all[last + 1] to "Épisode suivant" else all[last] to "Revoir"
            }
            else -> all[last] to "Reprendre"
        }
        resumeTarget = target
        btnResume.text = "$verb  ${shortLabel(target)}"
        btnResume.visibility = View.VISIBLE
    }

    private fun shortLabel(e: Item): String {
        val m = SE.find(e.extra ?: "") ?: return ""
        return "S${m.groupValues[1]} · É${m.groupValues[2]}"
    }

    private fun seasonName(s: Season): String = if (s.number <= 0) "Épisodes spéciaux" else "Saison ${s.number}"

    /** Liste déroulante : toutes les saisons dans l'ordre, avec le nombre d'épisodes et ce qui a été vu. */
    private fun chooseSeason() {
        val list = info?.seasons ?: return
        val prog = Db.progressMap(src.id, Kind.EPISODE, list.flatMap { s -> s.episodes.map { it.id } })
        val labels = list.map { s ->
            val n = s.episodes.size
            val seen = s.episodes.count { prog[it.id]?.finished == true }
            buildString {
                append(seasonName(s)).append("  ·  ").append(if (n == 1) "1 épisode" else "$n épisodes")
                if (seen == n && n > 0) append("  ·  vue") else if (seen > 0) append("  ·  $seen/$n vus")
            }
        }.toTypedArray()
        android.app.AlertDialog.Builder(this, Ui.DIALOG)
            .setTitle(Tmdb.cleanTitle(series.name).first)
            .setSingleChoiceItems(labels, season.coerceAtLeast(0)) { d, w ->
                d.dismiss()
                selectSeason(w)
                rvEpisodes.post { rvEpisodes.getChildAt(0)?.requestFocus() }
            }
            .show()
    }

    private fun selectSeason(position: Int) {
        val s = info?.seasons?.getOrNull(position) ?: return
        if (season == position) return
        season = position
        btnSeason.text = seasonName(s)
        episodes = s.episodes
        tvCount.text = if (episodes.size == 1) "1 épisode" else "${episodes.size} épisodes"
        episodeAdapter.set(episodes, Db.progressMap(src.id, Kind.EPISODE, episodes.map { it.id }))
        rvEpisodes.scrollToPosition(0)
    }

    private fun refreshProgress() {
        if (episodes.isEmpty()) return
        episodeAdapter.set(episodes, Db.progressMap(src.id, Kind.EPISODE, episodes.map { it.id }))
        paintResume()
    }

    private fun playEpisode(position: Int) {
        if (position !in episodes.indices) return
        val ep = episodes[position]
        val all = allEpisodes()
        playFrom(all, all.indexOfFirst { it.id == ep.id }.coerceAtLeast(0))
    }

    /** La liste de lecture dépend du réglage « Enchaînement des épisodes ». */
    private fun playFrom(all: List<Item>, index: Int) {
        val ep = all.getOrNull(index) ?: return
        val local = Downloads.find(this, src.id, ep)?.playable()
        if (local != null) {
            Ui.play(this, ItemList.of(listOf(local)), 0)
            return
        }
        when (Prefs.autoplay) {
            0 -> Ui.play(this, ItemList.of(listOf(ep)), 0)
            1 -> {
                val season = info?.seasons?.firstOrNull { s -> s.episodes.any { it.id == ep.id } }?.episodes ?: listOf(ep)
                Ui.play(this, ItemList.of(season), season.indexOfFirst { it.id == ep.id }.coerceAtLeast(0))
            }
            else -> Ui.play(this, ItemList.of(all), index)
        }
    }

    private fun episodeMenu(position: Int) {
        if (position !in episodes.indices) return
        val ep = episodes[position]
        val dl = Downloads.find(this, src.id, ep)
        val options = arrayListOf("Lire depuis le début", "Marquer comme vu")
        if (Features.DOWNLOADS) options.add(
            when {
                dl == null -> "Télécharger l'épisode"
                dl.finished -> "Supprimer le téléchargement"
                else -> "Annuler le téléchargement"
            }
        )
        Ui.choose(this, ep.name, options) { picked ->
            // Ordre de la liste : 0 lire, 1 marquer vu, 2 téléchargement (si activé)
            val w = when (picked) {
                1 -> 2
                2 -> 1
                else -> picked
            }
            when (w) {
                0 -> {
                    Db.saveProgress(src.id, ep, 0, 0, false)
                    playEpisode(position)
                }
                1 -> if (dl == null) {
                    val err = Downloads.start(this, src.id, ep, api.streamUrl(ep, Prefs.liveFormat))
                    Ui.toast(this, err ?: "Téléchargement lancé. Retrouve-le dans l'onglet Téléchargements.")
                } else {
                    Downloads.remove(this, dl.row.dmId)
                    Ui.toast(this, "Téléchargement supprimé")
                }
                2 -> {
                    Db.saveProgress(src.id, ep, 0, 0, true)
                    refreshProgress()
                }
            }
        }
    }

    companion object {
        private val SE = Regex("""Saison (\d+) · Épisode (\d+)""")
    }
}
