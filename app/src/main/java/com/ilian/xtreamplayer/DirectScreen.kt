package com.ilian.xtreamplayer

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Onglet Direct façon décodeur : aperçu de la chaîne, fiche du programme, horloge
 * et guide des programmes (chaînes en lignes, heures en colonnes).
 */
@OptIn(UnstableApi::class)
class DirectScreen(
    private val act: Activity,
    root: View,
    private val src: Source,
    private val api: XtreamApi
) {
    private val state = GuideState()

    private val previewVideo: PlayerView = root.findViewById(R.id.previewVideo)
    private val previewLogo: ImageView = root.findViewById(R.id.previewLogo)
    private val previewNum: TextView = root.findViewById(R.id.previewNum)
    private val digitsView: TextView = root.findViewById(R.id.digits)
    private val infoLogo: ImageView = root.findViewById(R.id.infoLogo)
    private val infoChan: TextView = root.findViewById(R.id.infoChan)
    private val infoTitle: TextView = root.findViewById(R.id.infoTitle)
    private val infoTime: TextView = root.findViewById(R.id.infoTime)
    private val infoProgress: View = root.findViewById(R.id.infoProgress)
    private val infoBar: ProgressBar = root.findViewById(R.id.infoBar)
    private val infoRemain: TextView = root.findViewById(R.id.infoRemain)
    private val infoDesc: TextView = root.findViewById(R.id.infoDesc)
    private val btnWatch: Button = root.findViewById(R.id.btnWatch)
    private val btnChanFav: Button = root.findViewById(R.id.btnChanFav)
    private val clock: TextView = root.findViewById(R.id.clock)
    private val clockDate: TextView = root.findViewById(R.id.clockDate)
    private val btnGroup: TextView = root.findViewById(R.id.btnGroup)
    private val ruler: TimeRulerView = root.findViewById(R.id.ruler)
    private val rv: RecyclerView = root.findViewById(R.id.rvGuide)
    private val status: TextView = root.findViewById(R.id.guideStatus)

    private var groups: List<Group> = emptyList()
    private var groupIndex = 0
    private var query: String? = null
    private var list: ItemList = ItemList.EMPTY
    private val adapter = GuideAdapter()

    /** Ligne active (chaîne) et programme sélectionné dedans. */
    private var current = -1
    private var currentEntry: EpgEntry? = null
    private var player: ExoPlayer? = null
    private var previewing: String? = null
    private var visible = false
    private var digits = ""

    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            paintClock()
            ruler.invalidate()
            rv.invalidate()
            for (i in 0 until rv.childCount) rv.getChildAt(i).findViewById<View>(R.id.strip)?.invalidate()
            paintInfo()
            Bg.main.postDelayed(this, 30_000)
        }
    }
    private val startPreview = Runnable { preview() }
    private val applyDigits = Runnable { jumpToNumber() }

    init {
        previewVideo.useController = false
        previewVideo.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        root.findViewById<View>(R.id.preview).clipToOutline = true
        ruler.state = state
        rv.layoutManager = LinearLayoutManager(act)
        rv.adapter = adapter
        rv.itemAnimator = null
        // Trait vertical de l'heure actuelle sur toute la hauteur du guide
        rv.addItemDecoration(object : RecyclerView.ItemDecoration() {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFF5C451.toInt()
                strokeWidth = act.resources.displayMetrics.density * 2
            }

            override fun onDrawOver(c: Canvas, parent: RecyclerView, s: RecyclerView.State) {
                val child = parent.getChildAt(0) ?: return
                val strip = child.findViewById<View>(R.id.strip) ?: return
                val now = GuideState.nowSec()
                if (now !in state.start until state.end) return
                val x = child.left + strip.left + (now - state.start).toFloat() / state.span * strip.width
                c.drawLine(x, 0f, x, parent.height.toFloat(), p)
            }
        })
        btnGroup.setOnClickListener { chooseGroup() }
        btnWatch.setOnClickListener { if (current >= 0) watch(current) }
        btnChanFav.setOnClickListener { toggleFav(current) }
        groups = buildGroups()
        // Catégorie choisie « par défaut » pour ce profil, sinon la première catégorie
        val def = LiveDefault.get()
        groupIndex = groups.indexOfFirst { it.key == def }.takeIf { it >= 0 }
            ?: groups.indexOfFirst { it.key.startsWith("c:") || it.key.startsWith("m:") }.coerceAtLeast(0)
    }

    private fun buildGroups(): List<Group> = LiveDefault.groups(src.id)

    // ---------- Cycle de vie ----------

    fun show() {
        visible = true
        if (list.size == 0) reload()
        state.reset()
        ruler.invalidate()
        adapter.notifyDataSetChanged()
        paintClock()
        Bg.main.removeCallbacks(tick)
        Bg.main.postDelayed(tick, 30_000)
    }

    fun hide() {
        visible = false
        Bg.main.removeCallbacks(tick)
        Bg.main.removeCallbacks(startPreview)
        Bg.main.removeCallbacks(applyDigits)
        stopPreview()
    }

    /** Retour sur l'onglet : favoris et masquage ont pu changer. */
    fun refresh() {
        val key = groups.getOrNull(groupIndex)?.key
        groups = buildGroups()
        groupIndex = groups.indexOfFirst { it.key == key }.coerceAtLeast(0)
        reload(keepPosition = true)
    }

    fun focusDefault() {
        rv.post {
            val lm = rv.layoutManager as LinearLayoutManager
            val pos = if (current in 0 until list.size) current else 0
            (lm.findViewByPosition(pos) ?: rv.getChildAt(0))?.requestFocus()
        }
    }

    fun setQuery(q: String?) {
        if (q == query) return
        query = q
        reload()
    }

    // ---------- Liste des chaînes ----------

    private fun reload(keepPosition: Boolean = false) {
        val g = groups.getOrNull(groupIndex) ?: return
        btnGroup.text = g.name
        val q = query
        list = when (g.key) {
            MainActivity.FAV -> {
                val favs = Db.favorites(src.id, Kind.LIVE)
                ItemList.of(if (q == null) favs else favs.filter { normalize(it.name).contains(normalize(q)) || it.num?.toString() == q })
            }
            MainActivity.ALL -> ItemList.query(src.id, Kind.LIVE, null, q)
            else -> ItemList.query(src.id, Kind.LIVE, g.catIds, q)
        }
        adapter.notifyDataSetChanged()
        if (!keepPosition) {
            current = -1
            rv.scrollToPosition(0)
        }
        status.visibility = if (list.size == 0) View.VISIBLE else View.GONE
        status.text = when {
            list.size > 0 -> ""
            q != null -> "Aucune chaîne pour « $q »"
            g.key == MainActivity.FAV -> "Aucune chaîne favorite.\nAppui long sur OK sur une chaîne pour l'ajouter."
            else -> "Aucune chaîne dans cette catégorie."
        }
        if (list.size == 0) clearInfo()
    }

    /** Liste des catégories : OK pour ouvrir, appui long pour en faire la catégorie par défaut (★). */
    private fun chooseGroup() {
        val def = LiveDefault.get()
        val labels = groups.map { if (it.key == def) "★  ${it.name}" else it.name }
        val dlg = android.app.AlertDialog.Builder(act, Ui.DIALOG)
            .setTitle("Catégorie")
            .setItems(labels.toTypedArray()) { _, w ->
                groupIndex = w
                reload()
                focusDefault()
            }
            .setNegativeButton("Annuler", null)
            .create()
        dlg.setOnShowListener {
            val lv = dlg.listView ?: return@setOnShowListener
            lv.setSelection(groupIndex)
            lv.setOnItemLongClickListener { _, _, w, _ ->
                val g = groups.getOrNull(w) ?: return@setOnItemLongClickListener true
                dlg.dismiss()
                if (g.key == LiveDefault.get()) {
                    LiveDefault.set(null)
                    Ui.toast(act, "Plus de catégorie par défaut : le Direct s'ouvrira sur la première catégorie.")
                } else {
                    LiveDefault.set(g.key)
                    Ui.toast(act, "« ${g.name} » s'affichera à chaque ouverture du Direct.")
                }
                groupIndex = w
                reload()
                focusDefault()
                true
            }
        }
        dlg.show()
    }

    // ---------- Sélection ----------

    private fun rowAt(pos: Int): View? = (rv.layoutManager as LinearLayoutManager).findViewByPosition(pos)

    private fun stripAt(pos: Int): EpgRowView? = rowAt(pos)?.findViewById(R.id.strip)

    private fun onRowFocus(pos: Int, strip: EpgRowView) {
        if (current != pos) stripAt(current)?.active = false
        current = pos
        strip.active = true
        strip.selected = strip.indexAt(maxOf(state.selTime, GuideState.nowSec()))
        currentEntry = strip.visible().getOrNull(strip.selected)
        paintInfo()
        Bg.main.removeCallbacks(startPreview)
        if (Prefs.guidePreview) Bg.main.postDelayed(startPreview, 1_300)
    }

    /** Gauche / droite : programme précédent ou suivant sur la même chaîne. */
    private fun move(strip: EpgRowView, step: Int): Boolean {
        val v = strip.visible()
        if (v.isEmpty()) return true
        val next = (strip.selected + step).coerceIn(0, v.size - 1)
        if (next == strip.selected) return true
        strip.selected = next
        val e = v[next]
        state.selTime = maxOf(e.start, GuideState.nowSec())
        if (state.reveal(state.selTime)) {
            ruler.invalidate()
            for (i in 0 until rv.childCount) rv.getChildAt(i).findViewById<View>(R.id.strip)?.invalidate()
            rv.invalidate()
        }
        currentEntry = e
        paintInfo()
        return true
    }

    private fun item(pos: Int): Item? = list[pos]

    private fun paintInfo() {
        val ch = item(current) ?: return
        infoChan.text = "${ch.num ?: (current + 1)} · ${ch.name}"
        Img.load(infoLogo, ch.icon, R.drawable.ph_live)
        previewNum.text = (ch.num ?: (current + 1)).toString()
        if (previewing != ch.id) Img.load(previewLogo, ch.icon, R.drawable.ic_play)
        val fav = Db.isFav(src.id, ch)
        btnChanFav.text = if (fav) "Favorite" else "Ajouter"
        btnChanFav.setCompoundDrawablesRelativeWithIntrinsicBounds(if (fav) R.drawable.ic_star_small else R.drawable.ic_star_outline_small, 0, 0, 0)

        val e = currentEntry
        if (e == null) {
            infoTitle.text = if (Epg.cached(ch.id) == null && Prefs.epgEnabled) "Chargement du guide…" else "Pas de guide pour cette chaîne"
            infoTime.text = ""
            infoDesc.text = ""
            infoProgress.visibility = View.GONE
            return
        }
        val now = GuideState.nowSec()
        infoTitle.text = e.title
        infoTime.text = "${dayPrefix(e.start)}${GuideState.hm(e.start)} – ${GuideState.hm(e.end)} · ${Meta.minutes(((e.end - e.start) / 60).toInt()) ?: ""}"
        infoDesc.text = e.desc
        if (e.start <= now && now < e.end) {
            infoProgress.visibility = View.VISIBLE
            infoBar.visibility = View.VISIBLE
            infoBar.progress = ((now - e.start) * 1000 / (e.end - e.start)).toInt().coerceIn(0, 1000)
            infoRemain.text = "Il reste " + (Meta.minutes(((e.end - now) / 60).toInt().coerceAtLeast(1)) ?: "")
        } else {
            infoProgress.visibility = View.VISIBLE
            infoBar.visibility = View.GONE
            infoRemain.text = "Commence dans " + (Meta.minutes(((e.start - now) / 60).toInt().coerceAtLeast(1)) ?: "")
        }
    }

    private fun dayPrefix(sec: Long): String {
        val f = SimpleDateFormat("yyyyMMdd", Locale.FRANCE)
        val d = f.format(Date(sec * 1000))
        val today = f.format(Date())
        return if (d == today) "" else SimpleDateFormat("EEE d", Locale.FRANCE).format(Date(sec * 1000)) + " · "
    }

    private fun clearInfo() {
        infoChan.text = ""
        infoTitle.text = ""
        infoTime.text = ""
        infoDesc.text = ""
        infoProgress.visibility = View.GONE
        previewNum.text = ""
        previewLogo.setImageDrawable(null)
    }

    private fun paintClock() {
        val now = Date()
        clock.text = SimpleDateFormat("HH:mm", Locale.FRANCE).format(now)
        clockDate.text = SimpleDateFormat("EEE d MMMM", Locale.FRANCE).format(now).replaceFirstChar { it.uppercase() }
    }

    // ---------- Aperçu vidéo ----------

    private fun preview() {
        val ch = item(current) ?: return
        if (!visible || previewing == ch.id) return
        val p = player ?: ExoPlayer.Builder(act)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultHttpDataSource.Factory().setUserAgent(Prefs.userAgent).setAllowCrossProtocolRedirects(true)
                )
            )
            .build().also {
                player = it
                previewVideo.player = it
                it.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(s: Int) {
                        if (s == Player.STATE_READY) previewVideo.visibility = View.VISIBLE
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        previewVideo.visibility = View.INVISIBLE
                        previewing = null
                    }
                })
            }
        previewing = ch.id
        previewVideo.visibility = View.INVISIBLE
        val fmt = Prefs.liveFormat
        val b = MediaItem.Builder().setUri(api.streamUrl(ch, fmt))
        if (fmt == "m3u8") b.setMimeType(MimeTypes.APPLICATION_M3U8)
        p.setMediaItem(b.build())
        p.prepare()
        p.playWhenReady = true
    }

    private fun stopPreview() {
        if (player != null) Session.lastStopAt = System.currentTimeMillis()
        player?.release()
        player = null
        previewVideo.player = null
        previewVideo.visibility = View.INVISIBLE
        previewing = null
    }

    // ---------- Actions ----------

    private fun watch(pos: Int) {
        if (pos !in 0 until list.size) return
        Bg.main.removeCallbacks(startPreview)
        stopPreview()
        Ui.play(act, list, pos)
    }

    private fun toggleFav(pos: Int) {
        val ch = item(pos) ?: return
        val added = Db.toggleFav(src.id, ch)
        Ui.toast(act, if (added) "Chaîne ajoutée aux favoris" else "Chaîne retirée des favoris")
        if (groups.getOrNull(groupIndex)?.key == MainActivity.FAV) reload(keepPosition = true)
        else adapter.notifyItemChanged(pos)
        paintInfo()
    }

    /** Touches chiffres de la télécommande : on tape un numéro de chaîne. */
    fun typeDigit(d: Int): Boolean {
        if (digits.length >= 4) digits = ""
        digits += d
        digitsView.text = digits
        digitsView.visibility = View.VISIBLE
        Bg.main.removeCallbacks(applyDigits)
        Bg.main.postDelayed(applyDigits, 1_300)
        return true
    }

    private fun jumpToNumber() {
        val n = digits.toIntOrNull()
        digits = ""
        digitsView.visibility = View.GONE
        if (n == null) return
        var idx = list.indexOfNum(n)
        if (idx == null && groups.getOrNull(groupIndex)?.key != MainActivity.ALL) {
            // Pas dans cette catégorie : on cherche dans toutes les chaînes.
            val all = groups.indexOfFirst { it.key == MainActivity.ALL }
            if (all >= 0) {
                groupIndex = all
                query = null
                reload()
                idx = list.indexOfNum(n)
            }
        }
        if (idx == null) {
            Ui.toast(act, "Aucune chaîne n° $n")
            return
        }
        val target = idx
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(target, 0)
        rv.post { rowAt(target)?.requestFocus() }
    }

    // ---------- Adaptateur ----------

    private inner class GuideAdapter : RecyclerView.Adapter<GuideAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val num: TextView = v.findViewById(R.id.num)
            val img: ImageView = v.findViewById(R.id.img)
            val name: TextView = v.findViewById(R.id.name)
            val star: View = v.findViewById(R.id.star)
            val strip: EpgRowView = v.findViewById(R.id.strip)
        }

        override fun getItemCount() = list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_guide_row, parent, false)
            val h = VH(v)
            h.strip.state = state
            if (!Prefs.epgEnabled) h.strip.emptyText = "Guide TV désactivé dans les réglages"
            v.setOnFocusChangeListener { _, has ->
                val p = h.bindingAdapterPosition
                if (has && p != RecyclerView.NO_POSITION) onRowFocus(p, h.strip)
                else if (!has) h.strip.active = false
            }
            v.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) watch(p)
            }
            v.setOnLongClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) toggleFav(p)
                true
            }
            v.setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> move(h.strip, 1)
                    KeyEvent.KEYCODE_DPAD_LEFT -> move(h.strip, -1)
                    else -> false
                }
            }
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ch = list[position]
            h.strip.active = h.itemView.hasFocus()
            if (ch == null) {
                h.name.text = ""
                h.num.text = ""
                h.strip.entries = null
                return
            }
            h.num.text = (ch.num ?: (position + 1)).toString()
            h.name.text = ch.name
            h.star.visibility = if (Db.isFav(src.id, ch)) View.VISIBLE else View.GONE
            Img.load(h.img, ch.icon, R.drawable.ph_live)
            h.strip.tag = ch.id
            if (!Prefs.epgEnabled) {
                h.strip.entries = emptyList()
                return
            }
            val cached = Epg.cached(ch.id)
            h.strip.entries = cached
            if (cached == null) {
                Epg.prefetch(api, ch.id) { l ->
                    if (h.strip.tag != ch.id) return@prefetch
                    h.strip.entries = l
                    // Le guide arrive pour la ligne active : on met à jour la sélection et la fiche.
                    if (h.bindingAdapterPosition == current && h.itemView.hasFocus()) onRowFocus(current, h.strip)
                }
            }
        }
    }
}

/** Catégorie du Direct affichée à l'ouverture, propre à chaque profil. */
object LiveDefault {
    fun get(): String? = Db.kvGet(Db.pkey("live_default"))
    fun set(key: String?) = Db.kvPut(Db.pkey("live_default"), key)

    fun groups(src: Long): List<Group> {
        val g = ArrayList<Group>()
        g.add(Group(MainActivity.FAV, "Chaînes favorites", emptyList()))
        g.add(Group(MainActivity.ALL, "Toutes les chaînes", emptyList()))
        g.addAll(Catalog.groups(src, Kind.LIVE))
        return g
    }

    fun label(src: Long): String {
        val k = get() ?: return "Première catégorie"
        return groups(src).firstOrNull { it.key == k }?.name ?: "Première catégorie"
    }
}
