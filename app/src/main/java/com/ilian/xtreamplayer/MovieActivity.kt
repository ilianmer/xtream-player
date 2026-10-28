package com.ilian.xtreamplayer

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject

/** Fiche d'un film : lecture ou reprise, favori, téléchargement. */
class MovieActivity : Activity() {

    private lateinit var src: Source
    private lateinit var api: XtreamApi
    private lateinit var item: Item
    private lateinit var ivPoster: ImageView
    private lateinit var tvMeta: TextView
    private lateinit var tvPlot: TextView
    private lateinit var tvCredits: TextView
    private lateinit var btnPlay: TextView
    private lateinit var btnRestart: TextView
    private lateinit var btnFav: TextView
    private lateinit var btnDownload: TextView

    private var info: MovieInfo? = null
    private var tmdb: Tmdb.Info? = null

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
        item = Item.fromJson(JSONObject(raw))
        setContentView(R.layout.activity_movie)

        ivPoster = findViewById(R.id.ivPoster)
        tvMeta = findViewById(R.id.tvMeta)
        tvPlot = findViewById(R.id.tvPlot)
        tvCredits = findViewById(R.id.tvCredits)
        btnPlay = findViewById(R.id.btnPlay)
        btnRestart = findViewById(R.id.btnRestart)
        btnFav = findViewById(R.id.btnFav)
        btnDownload = findViewById(R.id.btnDownload)

        findViewById<TextView>(R.id.tvTitle).text = item.name
        Img.load(ivPoster, item.icon, R.drawable.ph_poster, 600)
        tvPlot.text = item.plot ?: ""
        paintMeta()

        btnPlay.setOnClickListener { play() }
        btnRestart.setOnClickListener {
            Db.saveProgress(src.id, item, 0, 0, false)
            play()
        }
        btnFav.setOnClickListener {
            val added = Db.toggleFav(src.id, item)
            Ui.toast(this, if (added) "Ajouté aux favoris" else "Retiré des favoris")
            paintButtons()
        }
        btnDownload.setOnClickListener { download() }
        btnPlay.requestFocus()

        Bg.task(this, { api.movieInfo(item.id) }) { r ->
            r.getOrNull()?.let { info = it }
            paintMeta()
            val needTmdb = Tmdb.enabled && (info?.plot.isNullOrBlank() || (item.icon.isNullOrBlank() && info?.cover.isNullOrBlank()))
            if (needTmdb) {
                val forTmdb = item.copy(tmdb = info?.tmdb ?: item.tmdb)
                Bg.task(this, { Tmdb.info(forTmdb) }) { t ->
                    tmdb = t.getOrNull()
                    paintMeta()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        paintButtons()
    }

    private fun paintMeta() {
        val i = info
        val t = tmdb
        val parts = ArrayList<String>()
        (i?.year ?: t?.year)?.let { parts.add(it) }
        (i?.duration?.takeIf { it != "00:00:00" } ?: t?.runtime?.let { "${it / 60} h ${String.format("%02d", it % 60)}" })?.let { parts.add(it) }
        (i?.rating?.takeIf { it != "0" } ?: item.rating?.takeIf { it != "0" } ?: t?.rating)?.let { parts.add("★ $it") }
        (i?.genre ?: t?.genres)?.let { parts.add(it) }
        tvMeta.text = parts.joinToString("  ·  ")
        val plot = i?.plot ?: item.plot ?: t?.overview
        tvPlot.text = plot ?: "Pas de résumé disponible."
        tvCredits.text = buildString {
            i?.director?.let { append("Réalisation : ").append(it) }
            i?.cast?.let {
                if (isNotEmpty()) append("\n")
                append("Avec : ").append(it)
            }
        }
        tvCredits.visibility = if (tvCredits.text.isNullOrBlank()) View.GONE else View.VISIBLE
        if (item.icon.isNullOrBlank()) {
            val cover = i?.cover ?: t?.poster
            if (cover != null) Img.load(ivPoster, cover, R.drawable.ph_poster, 600)
        }
    }

    private fun paintButtons() {
        val p = Db.progress(src.id, item)
        if (p != null && !p.finished && p.pos > 0) {
            btnPlay.text = "Reprendre à ${Ui.time(p.pos)}"
            btnRestart.visibility = View.VISIBLE
        } else {
            btnPlay.text = if (p?.finished == true) "Revoir" else "Lecture"
            btnRestart.visibility = View.GONE
        }
        btnFav.text = if (Db.isFav(src.id, item)) "★  Retirer des favoris" else "☆  Ajouter aux favoris"
        val d = Downloads.find(this, src.id, item)
        btnDownload.text = when {
            d == null -> "Télécharger"
            d.finished -> "Téléchargé : lire hors ligne"
            else -> d.label
        }
    }

    private fun play() {
        val d = Downloads.find(this, src.id, item)?.playable()
        Ui.play(this, ItemList.of(listOf(d ?: item)), 0)
    }

    private fun download() {
        val d = Downloads.find(this, src.id, item)
        when {
            d == null -> {
                val err = Downloads.start(this, src.id, item, api.streamUrl(item, Prefs.liveFormat))
                Ui.toast(this, err ?: "Téléchargement lancé. Il occupe un écran de ton compte tant qu'il tourne.")
                paintButtons()
            }
            d.finished -> play()
            else -> Ui.toast(this, "Suis l'avancement dans Réglages > Téléchargements.")
        }
    }
}
