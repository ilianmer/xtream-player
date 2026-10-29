package com.ilian.xtreamplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import kotlin.random.Random

/** Fiche d'un film : lecture ou reprise, favori, téléchargement, suggestions. */
class MovieActivity : BaseActivity() {

    private lateinit var src: Source
    private lateinit var api: XtreamApi
    private lateinit var item: Item
    private lateinit var ivBg: ImageView
    private lateinit var ivBackdrop: ImageView
    private lateinit var ivPoster: ImageView
    private lateinit var tvMeta: TextView
    private lateinit var tvPlot: TextView
    private lateinit var tvCredits: TextView
    private lateinit var tvPlay: TextView
    private lateinit var playBar: ProgressBar
    private lateinit var btnPlay: View
    private lateinit var btnRestart: View
    private lateinit var btnFav: ImageView
    private lateinit var btnDownload: ImageView
    private lateinit var tvDl: TextView
    private lateinit var tvSimilar: TextView

    private var info: MovieInfo? = null
    private var tmdb: Tmdb.Info? = null
    private var backdropShown = false

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

        ivBg = findViewById(R.id.ivBg)
        ivBackdrop = findViewById(R.id.ivBackdrop)
        ivPoster = findViewById(R.id.ivPoster)
        ivPoster.clipToOutline = true
        tvMeta = findViewById(R.id.tvMeta)
        tvPlot = findViewById(R.id.tvPlot)
        tvCredits = findViewById(R.id.tvCredits)
        tvPlay = findViewById(R.id.tvPlay)
        playBar = findViewById(R.id.playBar)
        btnPlay = findViewById(R.id.btnPlay)
        btnRestart = findViewById(R.id.btnRestart)
        btnFav = findViewById(R.id.btnFav)
        btnDownload = findViewById(R.id.btnDownload)
        tvDl = findViewById(R.id.tvDl)
        tvSimilar = findViewById(R.id.tvSimilar)

        findViewById<TextView>(R.id.tvTitle).text = Tmdb.cleanTitle(item.name).first
        val back = findViewById<TextView>(R.id.btnBack)
        back.text = categoryLabel()
        back.setOnClickListener { finish() }

        Img.blurred(ivBg, item.icon)
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
        if (!Features.DOWNLOADS) btnDownload.visibility = View.GONE
        btnPlay.requestFocus()
        fillSimilar()

        Bg.task(this, { api.movieInfo(item.id) }) { r ->
            r.getOrNull()?.let { info = it }
            paintMeta()
            val i = info
            val needTmdb = Tmdb.enabled &&
                (i?.plot.isNullOrBlank() || i?.backdrop == null || (item.icon.isNullOrBlank() && i?.cover.isNullOrBlank()))
            if (!Tmdb.enabled && (i?.plot.isNullOrBlank() || (item.icon.isNullOrBlank() && i?.cover.isNullOrBlank()))) {
                // Sans clé TMDB : résumé et affiche du catalogue iTunes France
                Bg.task(this, { FreeMeta.movie(item) }) { f ->
                    val x = f.getOrNull() ?: return@task
                    if (i?.plot.isNullOrBlank() && item.plot.isNullOrBlank()) x.plot?.let { tvPlot.text = it }
                    if (item.icon.isNullOrBlank() && i?.cover.isNullOrBlank()) x.poster?.let { showPoster(it) }
                }
            }
            if (needTmdb) {
                val forTmdb = item.copy(tmdb = i?.tmdb ?: item.tmdb)
                Bg.task(this, { Tmdb.info(forTmdb) }) { t ->
                    tmdb = t.getOrNull()
                    paintMeta()
                    paintImages()
                }
            } else {
                paintImages()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        paintButtons()
    }

    private fun categoryLabel(): String {
        val catId = item.catId ?: Db.find(src.id, Kind.MOVIE, item.id)?.catId
        val name = catId?.let { id -> Db.categories(src.id, Kind.MOVIE).firstOrNull { it.first.id == id }?.first?.name }
        return if (name != null) "Films · ${Catalog.cleanName(name)}" else "Films"
    }

    private fun paintMeta() {
        val i = info
        val t = tmdb
        tvMeta.text = Meta.line(
            listOf(
                i?.year ?: t?.year,
                Meta.duration(i?.duration) ?: Meta.minutes(t?.runtime),
                Meta.rating(i?.rating) ?: Meta.rating(item.rating) ?: Meta.rating(t?.rating),
                Meta.firstGenre(i?.genre ?: t?.genres)
            ),
            getColor(R.color.accent)
        )
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
    }

    /** Image de fond nette si le fournisseur ou TMDB en a une, sinon affiche nette sur fond flouté. */
    private fun paintImages() {
        if (backdropShown) return
        val backdrop = info?.backdrop ?: tmdb?.backdrop
        val poster = item.icon ?: info?.cover ?: tmdb?.poster
        if (item.icon.isNullOrBlank() && poster != null) Img.blurred(ivBg, poster)
        if (backdrop != null) {
            Img.fetch(backdrop, 1280) { bmp ->
                if (bmp != null) {
                    backdropShown = true
                    ivPoster.visibility = View.GONE
                    ivBackdrop.setImageBitmap(bmp)
                    ivBackdrop.animate().alpha(1f).setDuration(350).start()
                } else {
                    showPoster(poster)
                }
            }
        } else {
            showPoster(poster)
        }
    }

    private fun showPoster(url: String?) {
        if (url == null) return
        ivPoster.visibility = View.VISIBLE
        Img.load(ivPoster, url, R.drawable.ph_poster, 600)
    }

    private fun paintButtons() {
        val p = Db.progress(src.id, item)
        if (p != null && !p.finished && p.pos > 0) {
            tvPlay.text = "Reprendre à ${Ui.time(p.pos)}"
            btnRestart.visibility = View.VISIBLE
            if (p.dur > 0) {
                playBar.visibility = View.VISIBLE
                playBar.progress = (p.pos * 1000 / p.dur).toInt().coerceIn(0, 1000)
            } else {
                playBar.visibility = View.GONE
            }
        } else {
            tvPlay.text = if (p?.finished == true) "Revoir" else "Lecture"
            btnRestart.visibility = View.GONE
            playBar.visibility = View.GONE
        }
        val fav = Db.isFav(src.id, item)
        btnFav.setImageResource(if (fav) R.drawable.ic_check else R.drawable.ic_plus)
        btnFav.contentDescription = if (fav) "Retirer des favoris" else "Ajouter aux favoris"
        val d = Downloads.find(this, src.id, item)
        btnDownload.setImageResource(if (d?.finished == true) R.drawable.ic_check else R.drawable.ic_download)
        tvDl.text = when {
            d == null -> ""
            d.finished -> "Téléchargé : se lit même sans connexion"
            else -> "Téléchargement : ${d.label}"
        }
        tvDl.visibility = if (d == null || !Features.DOWNLOADS) View.GONE else View.VISIBLE
    }

    private fun fillSimilar() {
        val catId = item.catId ?: Db.find(src.id, Kind.MOVIE, item.id)?.catId ?: return
        val list = Db.page(src.id, Kind.MOVIE, listOf(catId), null, 0, 60)
            .filter { it.id != item.id }
            .shuffled(Random(item.id.hashCode()))
            .take(14)
        if (list.isEmpty()) return
        findViewById<View>(R.id.similarBox).visibility = View.VISIBLE
        val rv = findViewById<RecyclerView>(R.id.rvSimilar)
        rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rv.itemAnimator = null
        Fx.lockRow(rv)
        rv.adapter = SmallPosterAdapter(
            onClick = { other ->
                startActivity(Intent(this, MovieActivity::class.java).putExtra(MainActivity.EXTRA_ITEM, other.toJson().toString()))
            },
            onFocus = { other ->
                tvSimilar.text = if (other == null) "Tu aimeras aussi" else "Tu aimeras aussi  ·  ${other.name}"
            }
        ).also { it.items = list }
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
            d.finished -> Ui.confirm(this, "Supprimer le téléchargement ?", "Le fichier sera effacé du stick.", "Supprimer") {
                Downloads.remove(this, d.row.dmId)
                paintButtons()
            }
            else -> Ui.confirm(this, "Annuler le téléchargement ?", d.label, "Annuler le téléchargement") {
                Downloads.remove(this, d.row.dmId)
                paintButtons()
            }
        }
    }
}
