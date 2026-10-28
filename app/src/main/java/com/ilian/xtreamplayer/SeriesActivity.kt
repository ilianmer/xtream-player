package com.ilian.xtreamplayer

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class SeriesActivity : Activity() {

    private lateinit var src: Source
    private lateinit var api: XtreamApi
    private lateinit var series: Item
    private lateinit var rvSeasons: RecyclerView
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var tvStatus: TextView
    private lateinit var tvPlot: TextView
    private lateinit var ivCover: ImageView
    private lateinit var btnFav: TextView

    private val seasonAdapter = CategoryAdapter(R.layout.item_chip, ::selectSeason, ::onSeasonClick)
    private val episodeAdapter = EpisodeAdapter(::playEpisode, ::episodeMenu)
    private var info: SeriesInfo? = null
    private var episodes: List<Item> = emptyList()

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

        ivCover = findViewById(R.id.ivCover)
        tvPlot = findViewById(R.id.tvPlot)
        tvStatus = findViewById(R.id.tvStatus)
        rvSeasons = findViewById(R.id.rvSeasons)
        rvEpisodes = findViewById(R.id.rvEpisodes)
        btnFav = findViewById(R.id.btnFav)

        findViewById<TextView>(R.id.tvTitle).text = series.name
        tvPlot.text = series.plot ?: ""
        Img.load(ivCover, series.icon, R.drawable.ph_poster, 600)
        paintFav()
        btnFav.setOnClickListener {
            val added = Db.toggleFav(src.id, series)
            Ui.toast(this, if (added) "Ajoutée aux favoris" else "Retirée des favoris")
            paintFav()
        }

        rvSeasons.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvSeasons.adapter = seasonAdapter
        rvSeasons.itemAnimator = null
        rvEpisodes.layoutManager = LinearLayoutManager(this)
        rvEpisodes.adapter = episodeAdapter
        rvEpisodes.itemAnimator = null

        tvStatus.text = "Chargement des épisodes…"
        Bg.task(this, { api.seriesInfo(series.id) }) { r ->
            r.onSuccess { show(it) }.onFailure { tvStatus.text = Bg.err(it) }
        }
    }

    override fun onRestart() {
        super.onRestart()
        refreshProgress()
    }

    private fun paintFav() {
        btnFav.text = if (Db.isFav(src.id, series)) "★  Retirer des favoris" else "☆  Ajouter aux favoris"
    }

    private fun show(si: SeriesInfo) {
        info = si
        if (!si.plot.isNullOrBlank()) tvPlot.text = si.plot
        if (series.icon.isNullOrBlank() && si.cover != null) Img.load(ivCover, si.cover, R.drawable.ph_poster, 600)
        if ((tvPlot.text.isNullOrBlank() || (series.icon.isNullOrBlank() && si.cover == null)) && Tmdb.enabled) {
            val forTmdb = series.copy(tmdb = si.tmdb ?: series.tmdb)
            Bg.task(this, { Tmdb.info(forTmdb) }) { t ->
                val ti = t.getOrNull() ?: return@task
                if (tvPlot.text.isNullOrBlank() && ti.overview != null) tvPlot.text = ti.overview
                if (series.icon.isNullOrBlank() && si.cover == null && ti.poster != null) Img.load(ivCover, ti.poster, R.drawable.ph_poster, 600)
            }
        }
        if (si.seasons.isEmpty()) {
            tvStatus.text = "Aucun épisode disponible."
            return
        }
        tvStatus.visibility = View.GONE
        seasonAdapter.items = si.seasons.map { Category(it.number.toString(), "Saison ${it.number}") }
        selectSeason(0)
        rvSeasons.post { rvSeasons.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun selectSeason(position: Int) {
        val season = info?.seasons?.getOrNull(position) ?: return
        if (seasonAdapter.selected == position) return
        seasonAdapter.selected = position
        episodes = season.episodes
        episodeAdapter.set(episodes, Db.progressMap(src.id, Kind.EPISODE, episodes.map { it.id }))
        rvEpisodes.scrollToPosition(0)
    }

    private fun refreshProgress() {
        if (episodes.isEmpty()) return
        episodeAdapter.set(episodes, Db.progressMap(src.id, Kind.EPISODE, episodes.map { it.id }))
    }

    private fun onSeasonClick(position: Int) {
        selectSeason(position)
        rvEpisodes.post { rvEpisodes.getChildAt(0)?.requestFocus() }
    }

    /** La liste de lecture dépend du réglage « Enchaînement des épisodes ». */
    private fun playEpisode(position: Int) {
        if (position !in episodes.indices) return
        val ep = episodes[position]
        val local = Downloads.find(this, src.id, ep)?.playable()
        if (local != null) {
            Ui.play(this, ItemList.of(listOf(local)), 0)
            return
        }
        when (Prefs.autoplay) {
            0 -> Ui.play(this, ItemList.of(listOf(ep)), 0)
            1 -> Ui.play(this, ItemList.of(episodes), position)
            else -> {
                val all = info?.seasons?.flatMap { it.episodes } ?: episodes
                Ui.play(this, ItemList.of(all), all.indexOf(ep).coerceAtLeast(0))
            }
        }
    }

    private fun episodeMenu(position: Int) {
        if (position !in episodes.indices) return
        val ep = episodes[position]
        val dl = Downloads.find(this, src.id, ep)
        val options = listOf(
            "Lire depuis le début",
            when {
                dl == null -> "Télécharger l'épisode"
                dl.finished -> "Supprimer le téléchargement"
                else -> "Annuler le téléchargement"
            },
            "Marquer comme vu"
        )
        Ui.choose(this, ep.name, options) { w ->
            when (w) {
                0 -> {
                    Db.saveProgress(src.id, ep, 0, 0, false)
                    playEpisode(position)
                }
                1 -> if (dl == null) {
                    val err = Downloads.start(this, src.id, ep, api.streamUrl(ep, Prefs.liveFormat))
                    Ui.toast(this, err ?: "Téléchargement lancé. Suis-le dans Réglages > Téléchargements.")
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
}
