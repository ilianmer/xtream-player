package com.ilian.xtreamplayer

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
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
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Onglet Direct : en haut la chaîne survolée (aperçu vidéo et fiche du programme à hauteur fixe),
 * puis les catégories en pastilles (la pastille glisse comme sur les onglets), puis les chaînes :
 * en Liste (programme en cours et suite, le plus lisible) ou en Guide (grille des programmes).
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
    private val infoBar: ProgressBar = root.findViewById(R.id.infoBar)
    private val infoRemain: TextView = root.findViewById(R.id.infoRemain)
    private val infoDesc: TextView = root.findViewById(R.id.infoDesc)
    private val infoNext: TextView = root.findViewById(R.id.infoNext)
    private val btnWatch: Button = root.findViewById(R.id.btnWatch)
    private val btnChanFav: Button = root.findViewById(R.id.btnChanFav)
    private val clock: TextView = root.findViewById(R.id.clock)
    private val clockDate: TextView = root.findViewById(R.id.clockDate)
    private val chipScroll: HorizontalScrollView = root.findViewById(R.id.chipScroll)
    private val chips: LinearLayout = root.findViewById(R.id.chips)
    private val btnTonight: TextView = root.findViewById(R.id.btnTonight)
    private val modeBar: LinearLayout = root.findViewById(R.id.modeBar)
    private val modeList: TextView = root.findViewById(R.id.modeList)
    private val modeGuide: TextView = root.findViewById(R.id.modeGuide)
    private val guideHead: View = root.findViewById(R.id.guideHead)
    private val guideDay: TextView = root.findViewById(R.id.guideDay)
    private val ruler: TimeRulerView = root.findViewById(R.id.ruler)
    private val rv: RecyclerView = root.findViewById(R.id.rvGuide)
    private val rvList: RecyclerView = root.findViewById(R.id.rvList)
    private val status: TextView = root.findViewById(R.id.guideStatus)

    private val chipPill = TabPill(chips, 15f)
    private val modePill = TabPill(modeBar, 15f)

    private var groups: List<Group> = emptyList()
    private var groupIndex = 0
    private var query: String? = null
    private var list: ItemList = ItemList.EMPTY
    private val adapter = GuideAdapter()
    private val listAdapter = ListAdapter()

    /** « list » (Liste) ou « guide » (Guide), retenu par profil. */
    private var mode = Db.kvGet(Db.pkey("live_mode")) ?: "list"
    private val isGuide get() = mode == "guide"

    /** Ligne active (chaîne) et programme sélectionné dedans. */
    private var current = -1
    private var currentEntry: EpgEntry? = null
    private var player: ExoPlayer? = null
    private var previewing: String? = null
    private var visible = false
    private var digits = ""
    private var shiftAnim: ValueAnimator? = null
    private val ease = android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)

    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            paintClock()
            redrawGuide()
            if (!isGuide) rvList.adapter?.notifyItemRangeChanged(0, list.size, PROGRESS)
            paintInfo()
            Bg.main.postDelayed(this, 30_000)
        }
    }
    private val startPreview = Runnable { preview() }
    private val applyDigits = Runnable { jumpToNumber() }
    private var pendingGroup = -1
    private val switchGroup = Runnable {
        val g = pendingGroup
        if (g >= 0 && g != groupIndex) selectGroup(g, focusList = false)
    }

    init {
        previewVideo.useController = false
        previewVideo.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        root.findViewById<View>(R.id.preview).clipToOutline = true
        ruler.state = state
        rv.layoutManager = LinearLayoutManager(act)
        rv.adapter = adapter
        rv.itemAnimator = null
        rvList.layoutManager = LinearLayoutManager(act)
        rvList.adapter = listAdapter
        rvList.itemAnimator = null
        rvList.setItemViewCacheSize(8)
        // Trait vertical de l'heure actuelle sur toute la hauteur du guide
        rv.addItemDecoration(object : RecyclerView.ItemDecoration() {
            // Trait fin et translucide : repère discret qui ne gêne pas la lecture des titres
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x8CF5C451.toInt()
                strokeWidth = act.resources.displayMetrics.density * 1.5f
            }

            override fun onDrawOver(c: Canvas, parent: RecyclerView, s: RecyclerView.State) {
                val child = parent.getChildAt(0) ?: return
                val strip = child.findViewById<View>(R.id.strip) ?: return
                val now = GuideState.nowSec()
                val vs = state.viewStart
                if (now < vs || now >= vs + state.span) return
                val x = child.left + strip.left + ((now - vs) / state.span * strip.width).toFloat()
                c.drawLine(x, 0f, x, parent.height.toFloat(), p)
            }
        })
        btnWatch.setOnClickListener { if (current >= 0) watch(current) }
        btnChanFav.setOnClickListener { toggleFav(current) }
        btnTonight.setOnClickListener { tonight() }
        btnTonight.setOnFocusChangeListener { v, has -> Fx.focus(v, has, 1.05f) }
        for ((v, m) in listOf(modeList to "list", modeGuide to "guide")) {
            modePill.adopt(v)
            v.setOnClickListener { setMode(m, focusList = true) }
            // Comme les onglets du haut : on change en passant dessus
            v.setOnFocusChangeListener { _, has -> if (has && m != mode) v.postDelayed({ if (v.hasFocus()) setMode(m, focusList = false) }, 250) }
        }
        groups = buildGroups()
        // Catégorie choisie « par défaut » pour ce profil ; sinon les dernières chaînes regardées ; sinon la première catégorie
        val def = LiveDefault.get()
        groupIndex = groups.indexOfFirst { it.key == def }.takeIf { it >= 0 }
            ?: groups.indexOfFirst { it.key == LiveRecent.KEY }.takeIf { it >= 0 }
            ?: groups.indexOfFirst { it.key.startsWith("c:") || it.key.startsWith("m:") }.coerceAtLeast(0)
        buildChips()
        applyMode()
    }

    private fun buildGroups(): List<Group> = LiveDefault.groups(src.id)

    // ---------- Cycle de vie ----------

    fun show() {
        visible = true
        if (list.size == 0) reload()
        state.reset()
        paintTonight()
        redrawGuide()
        adapter.notifyDataSetChanged()
        listAdapter.notifyDataSetChanged()
        paintClock()
        Bg.main.removeCallbacks(tick)
        Bg.main.postDelayed(tick, 30_000)
    }

    fun hide() {
        visible = false
        Bg.main.removeCallbacks(tick)
        Bg.main.removeCallbacks(startPreview)
        Bg.main.removeCallbacks(applyDigits)
        Bg.main.removeCallbacks(switchGroup)
        stopPreview()
    }

    /** Retour sur l'onglet : favoris, chaînes récentes et masquage ont pu changer. */
    fun refresh() {
        val key = groups.getOrNull(groupIndex)?.key
        groups = buildGroups()
        groupIndex = groups.indexOfFirst { it.key == key }.coerceAtLeast(0)
        buildChips()
        reload(keepPosition = true)
    }

    fun focusDefault() {
        val target = if (isGuide) rv else rvList
        target.post {
            val lm = target.layoutManager as LinearLayoutManager
            val pos = if (current in 0 until list.size) current else 0
            (lm.findViewByPosition(pos) ?: target.getChildAt(0))?.requestFocus()
        }
    }

    fun setQuery(q: String?) {
        if (q == query) return
        query = q
        reload()
    }

    // ---------- Catégories en pastilles ----------

    private fun buildChips() {
        chips.removeAllViews()
        val def = LiveDefault.get()
        for ((i, g) in groups.withIndex()) {
            val chip = TextView(act, null, 0, R.style.Tab).apply {
                text = (if (g.key == def) "★ " else "") + g.name
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (30 * act.resources.displayMetrics.density).toInt()).apply {
                    marginEnd = (2 * act.resources.displayMetrics.density).toInt()
                }
                isSelected = i == groupIndex
            }
            chipPill.adopt(chip)
            chip.setOnFocusChangeListener { _, has ->
                Bg.main.removeCallbacks(switchGroup)
                if (has && i != groupIndex) {
                    pendingGroup = i
                    Bg.main.postDelayed(switchGroup, 380)
                }
            }
            chip.setOnClickListener {
                Bg.main.removeCallbacks(switchGroup)
                selectGroup(i, focusList = true)
            }
            // Appui long : catégorie affichée à l'ouverture du Direct (★)
            chip.setOnLongClickListener {
                if (g.key == LiveDefault.get()) {
                    LiveDefault.set(null)
                    Ui.toast(act, "Plus de catégorie par défaut.")
                } else {
                    LiveDefault.set(g.key)
                    Ui.toast(act, "« ${g.name} » s'affichera à chaque ouverture du Direct.")
                }
                buildChips()
                chips.getChildAt(i)?.requestFocus()
                true
            }
            chips.addView(chip)
        }
        chips.post {
            val sel = chips.getChildAt(groupIndex) ?: return@post
            chipPill.select(sel)
            chipScroll.smoothScrollTo((sel.left - chipScroll.width / 3).coerceAtLeast(0), 0)
        }
    }

    private fun selectGroup(i: Int, focusList: Boolean) {
        groupIndex = i
        for (k in 0 until chips.childCount) chips.getChildAt(k).isSelected = k == i
        chips.getChildAt(i)?.let { chipPill.select(it) }
        reload()
        if (focusList) focusDefault()
    }

    private fun focusChip() {
        chips.getChildAt(groupIndex)?.requestFocus()
    }

    // ---------- Liste ou Guide ----------

    private fun setMode(m: String, focusList: Boolean) {
        if (m == mode) {
            if (focusList) focusDefault()
            return
        }
        mode = m
        Db.kvPut(Db.pkey("live_mode"), m)
        applyMode()
        if (focusList) focusDefault()
    }

    private fun applyMode() {
        modeList.isSelected = !isGuide
        modeGuide.isSelected = isGuide
        modePill.select(if (isGuide) modeGuide else modeList)
        guideHead.visibility = if (isGuide) View.VISIBLE else View.GONE
        val show = if (isGuide) rv else rvList
        val hide = if (isGuide) rvList else rv
        hide.visibility = View.GONE
        show.visibility = View.VISIBLE
        show.alpha = 0f
        show.animate().alpha(1f).setDuration(220).start()
        // Les deux affichages se partagent la liste : chacun ne montre ses lignes que s'il est actif
        adapter.notifyDataSetChanged()
        listAdapter.notifyDataSetChanged()
        (show.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(current.coerceAtLeast(0), 0)
        paintTonight()
    }

    /** « Ce soir » : le guide se place sur la soirée (21 h) ; un nouvel appui revient à maintenant. */
    private fun tonight() {
        if (state.start > GuideState.floorHalfHour(GuideState.nowSec())) {
            state.reset()
            animateShift()
            paintTonight()
            refreshSelection()
            return
        }
        if (!isGuide) setMode("guide", focusList = false)
        state.jumpTo(eveningTarget())
        animateShift()
        paintTonight()
        refreshSelection()
        focusDefault()
    }

    private fun eveningTarget(): Long {
        val c = Calendar.getInstance()
        // Passé 21 h 30, c'est la soirée du lendemain
        if (c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) > 21 * 60 + 30) c.add(Calendar.DAY_OF_YEAR, 1)
        c.set(Calendar.HOUR_OF_DAY, 21)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        return c.timeInMillis / 1000
    }

    private fun paintTonight() {
        val later = state.start > GuideState.floorHalfHour(GuideState.nowSec())
        btnTonight.isSelected = later
        val c = Calendar.getInstance()
        val tomorrow = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) > 21 * 60 + 30
        btnTonight.text = if (later) "Maintenant" else if (tomorrow) "Demain soir" else "Ce soir"
        guideDay.text = when {
            !later -> "Maintenant"
            dayPrefix(state.start).isEmpty() -> "Ce soir"
            else -> SimpleDateFormat("EEEE d", Locale.FRANCE).format(Date(state.start * 1000)).replaceFirstChar { it.uppercase() }
        }
    }

    /** Le guide glisse jusqu'à sa nouvelle fenêtre de temps (au lieu de sauter). */
    private fun animateShift() {
        shiftAnim?.cancel()
        val from = state.shift
        if (from == 0f) {
            redrawGuide()
            return
        }
        shiftAnim = ValueAnimator.ofFloat(from, 0f).apply {
            duration = 380
            interpolator = ease
            addUpdateListener {
                state.shift = it.animatedValue as Float
                redrawGuide()
            }
            start()
        }
    }

    private fun redrawGuide() {
        ruler.invalidate()
        for (i in 0 until rv.childCount) rv.getChildAt(i).findViewById<View>(R.id.strip)?.invalidate()
        rv.invalidate()
    }

    /** Après un changement de fenêtre : la ligne active resélectionne le programme à l'heure visée. */
    private fun refreshSelection() {
        val strip = stripAt(current) ?: return
        strip.selected = strip.indexAt(maxOf(state.selTime, GuideState.nowSec()))
        currentEntry = strip.visible().getOrNull(strip.selected)
        paintInfo()
    }

    // ---------- Liste des chaînes ----------

    private fun reload(keepPosition: Boolean = false) {
        val g = groups.getOrNull(groupIndex) ?: return
        val q = query
        fun filter(l: List<Item>) = if (q == null) l else l.filter { normalize(it.name).contains(normalize(q)) || it.num?.toString() == q }
        list = when (g.key) {
            MainActivity.FAV -> ItemList.of(filter(Db.favorites(src.id, Kind.LIVE)))
            LiveRecent.KEY -> ItemList.of(filter(LiveRecent.items(src.id)))
            MainActivity.ALL -> ItemList.query(src.id, Kind.LIVE, null, q)
            else -> ItemList.query(src.id, Kind.LIVE, g.catIds, q)
        }
        adapter.notifyDataSetChanged()
        listAdapter.notifyDataSetChanged()
        if (!keepPosition) {
            current = -1
            rv.scrollToPosition(0)
            rvList.scrollToPosition(0)
        }
        status.visibility = if (list.size == 0) View.VISIBLE else View.GONE
        status.text = when {
            list.size > 0 -> ""
            q != null -> "Aucune chaîne pour « $q »"
            g.key == MainActivity.FAV -> "Aucune chaîne favorite.\nAppui long sur OK sur une chaîne pour l'ajouter."
            g.key == LiveRecent.KEY -> "Les chaînes que tu regardes apparaîtront ici."
            else -> "Aucune chaîne dans cette catégorie."
        }
        if (list.size == 0) clearInfo() else primeInfo()
    }

    /**
     * La fiche du haut n'est jamais vide : tant qu'aucune chaîne n'est survolée, elle montre la première
     * de la catégorie (sans lancer l'aperçu vidéo).
     */
    private fun primeInfo() {
        if (current !in 0 until list.size) current = 0
        val ch = item(current) ?: return
        val l = Epg.cached(ch.id)
        currentEntry = l?.let { Epg.nowAndNext(it).first }
        paintInfo()
        if (l == null && Prefs.epgEnabled) Epg.prefetch(api, ch.id) { got ->
            if (item(current)?.id == ch.id && currentEntry == null) {
                currentEntry = Epg.nowAndNext(got).first
                paintInfo()
            }
        }
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
        schedulePreview()
    }

    private fun onListFocus(pos: Int) {
        current = pos
        val ch = item(pos)
        currentEntry = ch?.let { Epg.cached(it.id) }?.let { Epg.nowAndNext(it).first }
        paintInfo()
        schedulePreview()
    }

    private fun schedulePreview() {
        Bg.main.removeCallbacks(startPreview)
        if (Prefs.guidePreview) Bg.main.postDelayed(startPreview, 900)
    }

    /** Gauche / droite : programme précédent ou suivant sur la même chaîne (au bout à gauche : les catégories). */
    private fun move(strip: EpgRowView, step: Int): Boolean {
        val v = strip.visible()
        if (v.isEmpty()) {
            if (step < 0) focusChip()
            return true
        }
        val next = (strip.selected + step).coerceIn(0, v.size - 1)
        if (next == strip.selected) {
            if (step < 0) focusChip()
            return true
        }
        strip.selected = next
        val e = v[next]
        state.selTime = maxOf(e.start, GuideState.nowSec())
        if (state.reveal(state.selTime)) {
            animateShift()
            paintTonight()
        }
        currentEntry = e
        paintInfo()
        return true
    }

    private fun item(pos: Int): Item? = list[pos]

    private fun paintInfo() {
        val ch = item(current) ?: return
        infoChan.text = "${ch.num ?: (current + 1)} · ${ch.name}"
        infoLogo.visibility = View.VISIBLE
        Img.load(infoLogo, ch.icon, R.drawable.ph_live)
        previewNum.text = (ch.num ?: (current + 1)).toString()
        if (previewing != ch.id) Img.load(previewLogo, ch.icon, R.drawable.ic_play)
        val fav = Db.isFav(src.id, ch)
        btnChanFav.text = if (fav) "Favori" else "Ajouter"
        btnChanFav.setCompoundDrawablesRelativeWithIntrinsicBounds(if (fav) R.drawable.ic_star_small else R.drawable.ic_star_outline_small, 0, 0, 0)

        val e = currentEntry
        if (e == null) {
            infoTitle.text = if (Epg.cached(ch.id) == null && Prefs.epgEnabled) "Chargement du guide…" else ch.name
            infoTime.text = if (Prefs.epgEnabled && Epg.cached(ch.id) != null) "Pas de guide pour cette chaîne" else ""
            infoDesc.text = ""
            infoNext.text = ""
            infoBar.visibility = View.INVISIBLE
            infoRemain.text = ""
            return
        }
        val now = GuideState.nowSec()
        infoTitle.text = e.title
        infoTime.text = "${dayPrefix(e.start)}${GuideState.hm(e.start)} – ${GuideState.hm(e.end)} · ${Meta.minutes(((e.end - e.start) / 60).toInt()) ?: ""}"
        infoDesc.text = e.desc
        if (e.start <= now && now < e.end) {
            infoBar.visibility = View.VISIBLE
            infoBar.progress = ((now - e.start) * 1000 / (e.end - e.start)).toInt().coerceIn(0, 1000)
            infoRemain.text = "Il reste " + (Meta.minutes(((e.end - now) / 60).toInt().coerceAtLeast(1)) ?: "")
        } else {
            infoBar.visibility = View.INVISIBLE
            infoRemain.text = "Dans " + (Meta.minutes(((e.start - now) / 60).toInt().coerceAtLeast(1)) ?: "")
        }
        // Ce qui suit, sur la même chaîne
        val all = Epg.cached(ch.id).orEmpty()
        val after = all.firstOrNull { it.start >= e.end && it.end > it.start }
        infoNext.text = after?.let { "Ensuite  ·  ${GuideState.hm(it.start)}  ${it.title}" } ?: ""
    }

    private fun dayPrefix(sec: Long): String {
        val f = SimpleDateFormat("yyyyMMdd", Locale.FRANCE)
        val d = f.format(Date(sec * 1000))
        val today = f.format(Date())
        return if (d == today) "" else SimpleDateFormat("EEE d", Locale.FRANCE).format(Date(sec * 1000)) + " · "
    }

    private fun clearInfo() {
        infoLogo.visibility = View.INVISIBLE
        infoChan.text = ""
        infoTitle.text = ""
        infoTime.text = ""
        infoDesc.text = ""
        infoNext.text = ""
        infoRemain.text = ""
        infoBar.visibility = View.INVISIBLE
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
                        if (s == Player.STATE_READY && previewVideo.visibility != View.VISIBLE) {
                            // L'image arrive en fondu par-dessus le logo
                            previewVideo.alpha = 0f
                            previewVideo.visibility = View.VISIBLE
                            previewVideo.animate().alpha(1f).setDuration(300).start()
                        }
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
        else if (isGuide) adapter.notifyItemChanged(pos) else listAdapter.notifyItemChanged(pos)
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
                query = null
                selectGroup(all, focusList = false)
                idx = list.indexOfNum(n)
            }
        }
        if (idx == null) {
            Ui.toast(act, "Aucune chaîne n° $n")
            return
        }
        val target = idx
        val r = if (isGuide) rv else rvList
        (r.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(target, 0)
        r.post { (r.layoutManager as LinearLayoutManager).findViewByPosition(target)?.requestFocus() }
    }

    // ---------- Adaptateurs ----------

    /** Affichage Guide : une ligne par chaîne, programmes en blocs sur l'axe du temps. */
    private inner class GuideAdapter : RecyclerView.Adapter<GuideAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val num: TextView = v.findViewById(R.id.num)
            val img: ImageView = v.findViewById(R.id.img)
            val name: TextView = v.findViewById(R.id.name)
            val star: View = v.findViewById(R.id.star)
            val strip: EpgRowView = v.findViewById(R.id.strip)
        }

        override fun getItemCount() = if (isGuide) list.size else 0

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

    /** Affichage Liste : une ligne par chaîne, programme en cours (avec sa progression) et la suite. */
    private inner class ListAdapter : RecyclerView.Adapter<ListAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val num: TextView = v.findViewById(R.id.num)
            val img: ImageView = v.findViewById(R.id.img)
            val name: TextView = v.findViewById(R.id.name)
            val star: View = v.findViewById(R.id.star)
            val now: TextView = v.findViewById(R.id.now)
            val bar: ProgressBar = v.findViewById(R.id.bar)
            val remain: TextView = v.findViewById(R.id.remain)
            val next: TextView = v.findViewById(R.id.next)
        }

        override fun getItemCount() = if (isGuide) 0 else list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_live_row, parent, false)
            val h = VH(v)
            v.setOnFocusChangeListener { view, has ->
                Fx.focus(view, has, 1.015f)
                val p = h.bindingAdapterPosition
                if (has && p != RecyclerView.NO_POSITION) onListFocus(p)
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
            // Gauche : retour aux catégories
            v.setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN) {
                    focusChip()
                    true
                } else false
            }
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PROGRESS)) {
                list[position]?.let { ch -> paintNow(h, Epg.cached(ch.id)) }
                return
            }
            super.onBindViewHolder(h, position, payloads)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ch = list[position]
            if (ch == null) {
                h.name.text = ""
                h.num.text = ""
                h.now.text = ""
                h.next.text = ""
                h.remain.text = ""
                h.bar.visibility = View.INVISIBLE
                return
            }
            h.num.text = (ch.num ?: (position + 1)).toString()
            h.name.text = ch.name
            h.star.visibility = if (Db.isFav(src.id, ch)) View.VISIBLE else View.GONE
            Img.load(h.img, ch.icon, R.drawable.ph_live)
            h.itemView.tag = ch.id
            if (!Prefs.epgEnabled) {
                paintNow(h, emptyList())
                return
            }
            val cached = Epg.cached(ch.id)
            paintNow(h, cached)
            if (cached == null) Epg.prefetch(api, ch.id) { l ->
                if (h.itemView.tag != ch.id) return@prefetch
                paintNow(h, l)
                if (h.bindingAdapterPosition == current && h.itemView.hasFocus()) onListFocus(current)
            }
        }

        fun paintNow(h: VH, l: List<EpgEntry>?) {
            if (l == null) {
                h.now.text = "Chargement du guide…"
                h.next.text = ""
                h.remain.text = ""
                h.bar.visibility = View.INVISIBLE
                return
            }
            val (now, next) = Epg.nowAndNext(l)
            val t = GuideState.nowSec()
            val airing = now != null && now.start <= t && t < now.end
            h.now.text = now?.title ?: if (Prefs.epgEnabled) "Pas de guide pour cette chaîne" else ""
            if (airing && now != null) {
                h.bar.visibility = View.VISIBLE
                h.bar.progress = ((t - now.start) * 1000 / (now.end - now.start)).toInt().coerceIn(0, 1000)
                h.remain.text = Meta.minutes(((now.end - t) / 60).toInt().coerceAtLeast(1))?.let { "$it" } ?: ""
            } else {
                h.bar.visibility = View.INVISIBLE
                h.remain.text = ""
            }
            h.next.text = next?.let { "${GuideState.hm(it.start)}  ${it.title}" } ?: ""
        }
    }

    private companion object {
        const val PROGRESS = "progress"
    }
}

/** Dernières chaînes regardées (par profil), pour la pastille « Récentes ». */
object LiveRecent {
    const val KEY = "__recent"
    private const val MAX = 20

    private fun key(src: Long) = Db.pkey("live_recent:$src")

    fun ids(src: Long): List<String> = try {
        Db.kvGet(key(src))?.let { s ->
            val a = JSONArray(s)
            (0 until a.length()).map { a.getString(it) }
        }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    fun note(src: Long, item: Item) {
        try {
            val l = (listOf(item.id) + ids(src).filter { it != item.id }).take(MAX)
            Db.kvPut(key(src), JSONArray(l).toString())
        } catch (_: Exception) {
        }
    }

    fun items(src: Long): List<Item> {
        val ids = ids(src)
        if (ids.isEmpty()) return emptyList()
        val found = Db.findMany(src, Kind.LIVE, ids).associateBy { it.id }
        return ids.mapNotNull { found[it] }
    }
}

/** Catégorie du Direct affichée à l'ouverture, propre à chaque profil. */
object LiveDefault {
    fun get(): String? = Db.kvGet(Db.pkey("live_default"))
    fun set(key: String?) = Db.kvPut(Db.pkey("live_default"), key)

    fun groups(src: Long): List<Group> {
        val g = ArrayList<Group>()
        if (LiveRecent.ids(src).isNotEmpty()) g.add(Group(LiveRecent.KEY, "Récentes", emptyList()))
        g.add(Group(MainActivity.FAV, "Favoris", emptyList()))
        g.add(Group(MainActivity.ALL, "Toutes", emptyList()))
        for (c in Catalog.groups(src, Kind.LIVE)) g.add(Group(c.key, Catalog.cleanName(c.name), c.catIds))
        return g
    }

    fun label(src: Long): String {
        val k = get() ?: return "Première catégorie"
        return groups(src).firstOrNull { it.key == k }?.name ?: "Première catégorie"
    }
}
