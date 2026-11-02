package com.ilian.xtreamplayer

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Défilement du tableau de l'accueil : la rangée qui reçoit le focus glisse en douceur en haut de l'écran
 * (au lieu des sauts de la liste classique), et haut / bas passent directement d'un widget à l'autre.
 */
class BoardScrollView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ScrollView(ctx, attrs) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0

    override fun executeKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> false
        else -> super.executeKeyEvent(event)
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        focused?.let { reveal(it) }
    }

    /** Amène la rangée qui contient [v] en haut de l'écran (la première reste tout en haut). */
    fun reveal(v: View) {
        val board = getChildAt(0) as? ViewGroup ?: return
        var c: View = v
        var p = c.parent
        while (p != null && p !== board) {
            c = p as? View ?: return
            p = c.parent
        }
        if (p !== board) return
        val pad = (6 * resources.displayMetrics.density).toInt()
        val max = (board.height - height).coerceAtLeast(0)
        val target = if (board.indexOfChild(c) == 0) 0 else (c.top - pad).coerceIn(0, max)
        if (target != scrollY) smoothScrollTo(0, target)
    }
}

/**
 * Accueil en widgets, façon iPhone : tout se personnalise (ajouter, retirer, déplacer, petit / moyen / grand).
 * Les widgets se rangent sur une grille de trois colonnes ; une rangée prend la hauteur de son plus grand widget.
 * La disposition est retenue par profil.
 */
class HomeBoard(
    private val act: Activity,
    root: View,
    private val src: Source,
    private val api: XtreamApi,
    private val home: HomeScreen,
    private val openSport: () -> Unit
) {
    private val scroll: BoardScrollView = root.findViewById(R.id.boardScroll)
    private val board: LinearLayout = root.findViewById(R.id.board)
    private val stash: FrameLayout = root.findViewById(R.id.homeStash)
    private val banner: TextView = root.findViewById(R.id.editBanner)
    private val inf = LayoutInflater.from(act)
    private val dp = act.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    class Spec(val type: String, var size: Int)

    /** Type de widget : nom, tailles possibles (1 petit, 2 moyen, 3 grand), taille par défaut. */
    private class Type(val id: String, val label: String, val sizes: List<Int>, val size: Int)

    private val types = listOf(
        Type("hero", "À la une", listOf(2, 3), 2),
        Type("sport", "Scores en direct", listOf(1, 2, 3), 1),
        Type("resume", "Reprendre la lecture", listOf(2, 3), 3),
        Type("top10:MOVIE", "Top 10 des films", listOf(2, 3), 3),
        Type("top10:SERIES", "Top 10 des séries", listOf(2, 3), 3),
        Type("channels", "Chaînes favorites", listOf(1, 2, 3), 2),
        Type("tonight", "Ce soir à la TV", listOf(1, 2, 3), 2),
        Type("sportday", "Matchs du jour", listOf(1, 2, 3), 1),
        Type("latest", "Nouveautés", listOf(2, 3), 3),
        Type("mylist", "Ma liste", listOf(2, 3), 3),
        Type("clock", "Horloge", listOf(1, 2), 1),
        Type("cat", "Une catégorie au choix", listOf(2, 3), 3)
    )

    private fun typeOf(spec: Spec): Type? = types.firstOrNull { it.id == spec.type.substringBefore(':').let { b -> if (b == "top10") spec.type else b } }

    private fun defaults() = listOf(
        Spec("sport", 1), Spec("hero", 2),
        Spec("resume", 3),
        Spec("top10:MOVIE", 3),
        Spec("top10:SERIES", 3),
        Spec("channels", 2), Spec("clock", 1),
        Spec("tonight", 2), Spec("sportday", 1),
        Spec("latest", 3)
    )

    companion object {
        /** Demandé depuis les Réglages : l'accueil s'ouvre directement en mode personnalisation. */
        var editOnReturn = false
    }

    private val items = ArrayList<Widget>()
    private var editing = false
    private var moving: Widget? = null
    private var running = false
    private val jiggles = ArrayList<ValueAnimator>()

    // ---------- Disposition ----------

    private fun load(): List<Spec> {
        val raw = try {
            Db.kvGet(Db.pkey("home_widgets"))
        } catch (_: Exception) {
            null
        } ?: return defaults()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONArray(i)
                Spec(o.getString(0), o.getInt(1)).takeIf { typeOf(it) != null }
            }.ifEmpty { defaults() }
        } catch (_: Exception) {
            defaults()
        }
    }

    private fun save() {
        val a = JSONArray()
        for (w in items) a.put(JSONArray().put(w.spec.type).put(w.spec.size))
        try {
            Db.kvPut(Db.pkey("home_widgets"), a.toString())
        } catch (_: Exception) {
        }
    }

    fun start() {
        for (s in load()) create(s)?.let { items.add(it) }
        layout()
        for (w in items) w.start()
        if (Db.kvGet(Db.pkey("home_widgets_hint")) == null) {
            Db.kvPut(Db.pkey("home_widgets_hint"), "1")
            Bg.main.postDelayed({
                if (!act.isFinishing) Ui.toast(act, "Nouveau : ton accueil se personnalise. Tout en bas : « Personnaliser l'accueil ».")
            }, 2_500)
        }
    }

    fun resume() {
        running = true
        for (w in items) w.resume()
    }

    fun pause() {
        running = false
        for (w in items) w.pause()
    }

    fun refresh() {
        for (w in items) w.refresh()
    }

    fun focusDefault() {
        if (items.any { it.spec.type == "hero" }) home.focusDefault() else board.requestFocus()
    }

    /** Retour : sort du mode personnalisation (true si l'événement est consommé). */
    fun onBack(): Boolean {
        if (moving != null) {
            drop()
            return true
        }
        if (editing) {
            exitEdit()
            return true
        }
        return false
    }

    private fun layout() {
        stopJiggle()
        // Les blocs historiques non posés retournent dans leur réserve (toujours attachés à l'écran)
        for (v in listOf(home.heroView, home.sportView, home.rowView)) {
            if (items.none { it.view === v } && v.parent !== stash) {
                (v.parent as? ViewGroup)?.removeView(v)
                stash.addView(v)
            }
        }
        for (w in items) (w.cell.parent as? ViewGroup)?.removeView(w.cell)
        board.removeAllViews()
        var row: LinearLayout? = null
        var used = 0
        var rowH = 0
        fun close() {
            val r = row ?: return
            r.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(rowH)).apply { bottomMargin = px(12) }
            board.addView(r)
        }
        for (w in items) {
            val t = typeOf(w.spec) ?: continue
            if (w.spec.size !in t.sizes) w.spec.size = t.size
            val s = w.spec.size
            if (row == null || used + s > 3) {
                close()
                row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = 3f
                    clipChildren = false
                    clipToPadding = false
                }
                used = 0
                rowH = 0
            }
            row!!.addView(w.cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, s.toFloat()).apply {
                marginStart = px(6)
                marginEnd = px(6)
            })
            used += s
            rowH = maxOf(rowH, w.height(s))
            w.sized(s)
            styleCell(w)
        }
        close()
        board.addView(footer())
        if (editing) startJiggle()
    }

    private fun styleCell(w: Widget) {
        val c = w.cell
        if (editing) {
            c.isFocusable = true
            c.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            c.foreground = act.getDrawable(if (moving === w) R.drawable.widget_move_ring else R.drawable.widget_edit_ring)
            c.setOnClickListener { if (moving == null) options(w) }
            c.setOnKeyListener { _, code, e -> editKey(w, code, e) }
            c.setOnFocusChangeListener { v, has -> if (moving !== w) Fx.focus(v, has, 1.015f) }
        } else {
            c.isFocusable = false
            c.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            c.foreground = null
            c.setOnClickListener(null)
            c.isClickable = false
            c.setOnKeyListener(null)
            c.onFocusChangeListener = null
            c.rotation = 0f
            c.scaleX = 1f
            c.scaleY = 1f
        }
    }

    // ---------- Bas du tableau : personnaliser / ajouter / terminer ----------

    private fun pill(text: String, onClick: () -> Unit): TextView = TextView(act, null, 0, R.style.Btn).apply {
        this.text = text
        setOnClickListener { onClick() }
        setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_BACK && editing) {
                if (e.action == KeyEvent.ACTION_UP) exitEdit()
                true
            } else false
        }
        setOnFocusChangeListener { v, has -> Fx.focus(v, has, 1.05f) }
    }

    private fun footer(): View {
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            setPadding(0, px(4), 0, px(8))
        }
        fun add(v: View) = bar.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40)).apply {
            marginStart = px(6)
            marginEnd = px(6)
        })
        if (editing) {
            add(pill("+  Ajouter un widget") { addWidget() })
            add(pill("Disposition d'origine") {
                Ui.confirm(act, "Disposition d'origine", "Remettre l'accueil comme au départ ?", "Remettre") {
                    for (w in items) w.pause()
                    items.clear()
                    for (s in defaults()) create(s)?.let { items.add(it) }
                    layout()
                    for (w in items) {
                        w.start()
                        if (running) w.resume()
                    }
                    save()
                    focusFirstCell()
                }
            })
            add(pill("Terminé") { exitEdit() })
        } else {
            add(pill("Personnaliser l'accueil") { enterEdit() })
        }
        return bar
    }

    // ---------- Mode personnalisation (les widgets « tremblent » comme sur iPhone) ----------

    fun enterEdit() {
        if (editing) return
        editing = true
        banner.visibility = View.VISIBLE
        banner.alpha = 0f
        banner.animate().alpha(1f).setDuration(220).start()
        layout()
        focusFirstCell()
    }

    private fun exitEdit() {
        moving = null
        editing = false
        save()
        banner.animate().alpha(0f).setDuration(180).withEndAction { banner.visibility = View.GONE }.start()
        layout()
        scroll.smoothScrollTo(0, 0)
        board.post { focusDefault() }
    }

    private fun focusFirstCell() {
        board.post { items.firstOrNull()?.cell?.requestFocus() ?: board.requestFocus() }
    }

    private fun startJiggle() {
        stopJiggle()
        var i = 0
        for (w in items) {
            val c = w.cell
            c.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            val a = ObjectAnimator.ofFloat(c, View.ROTATION, -0.32f, 0.32f).apply {
                duration = 150L + (i % 3) * 18L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                startDelay = (i * 37L) % 120L
                start()
            }
            jiggles.add(a)
            i++
        }
    }

    private fun stopJiggle() {
        for (a in jiggles) a.cancel()
        jiggles.clear()
        for (w in items) {
            w.cell.rotation = 0f
            w.cell.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private fun editKey(w: Widget, code: Int, e: KeyEvent): Boolean {
        if (moving === w) {
            if (e.action != KeyEvent.ACTION_DOWN) return true
            val i = items.indexOf(w)
            val to = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> i - 1
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> i + 1
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                    drop()
                    return true
                }
                else -> return true
            }
            if (to in items.indices) {
                items.removeAt(i)
                items.add(to, w)
                layout()
                w.cell.post { w.cell.requestFocus() }
            } else Fx.bump(w.cell, if (to < 0) -1 else 1, 0)
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) exitEdit()
            return true
        }
        return false
    }

    private fun drop() {
        val w = moving ?: return
        moving = null
        w.cell.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(160).start()
        save()
        layout()
        w.cell.post { w.cell.requestFocus() }
    }

    private fun options(w: Widget) {
        val t = typeOf(w.spec) ?: return
        val names = mapOf(1 to "Petit", 2 to "Moyen", 3 to "Grand")
        val labels = ArrayList<String>()
        labels.add("Déplacer")
        for (s in t.sizes) labels.add((if (s == w.spec.size) "✓  " else "") + "Taille : " + names[s])
        labels.add("Retirer de l'accueil")
        Ui.choose(act, w.title, labels) { k ->
            when {
                k == 0 -> {
                    moving = w
                    layout()
                    w.cell.animate().scaleX(1.03f).scaleY(1.03f).translationZ(12 * dp).setDuration(180).start()
                    w.cell.post { w.cell.requestFocus() }
                    Ui.toast(act, "Flèches pour déplacer « ${w.title} », OK pour le poser.")
                }
                k <= t.sizes.size -> {
                    w.spec.size = t.sizes[k - 1]
                    save()
                    layout()
                    w.cell.post { w.cell.requestFocus() }
                }
                else -> {
                    val i = items.indexOf(w)
                    items.remove(w)
                    w.pause()
                    save()
                    layout()
                    (items.getOrNull(i) ?: items.lastOrNull())?.cell?.post { (items.getOrNull(i) ?: items.lastOrNull())?.cell?.requestFocus() }
                }
            }
        }
    }

    private fun addWidget() {
        val present = items.map { it.spec.type }.toSet()
        val avail = types.filter { it.id == "cat" || it.id !in present }
        if (avail.isEmpty()) {
            Ui.toast(act, "Tous les widgets sont déjà sur l'accueil.")
            return
        }
        Ui.choose(act, "Ajouter un widget", avail.map { it.label }) { k ->
            val t = avail[k]
            if (t.id == "cat") chooseCategory { type -> place(Spec(type, t.size)) } else place(Spec(t.id, t.size))
        }
    }

    private fun chooseCategory(done: (String) -> Unit) {
        Ui.choose(act, "Une catégorie de…", listOf("Films", "Séries")) { k ->
            val kind = if (k == 0) Kind.MOVIE else Kind.SERIES
            val groups = try {
                Catalog.groups(src.id, kind)
            } catch (_: Exception) {
                emptyList()
            }
            if (groups.isEmpty()) {
                Ui.toast(act, "Aucune catégorie disponible.")
                return@choose
            }
            Ui.choose(act, if (kind == Kind.MOVIE) "Catégorie de films" else "Catégorie de séries", groups.map { Catalog.cleanName(it.name) }) { g ->
                done("cat:${kind.name}:${groups[g].key}")
            }
        }
    }

    private fun place(spec: Spec) {
        val w = create(spec) ?: return
        items.add(w)
        save()
        layout()
        w.start()
        if (running) w.resume()
        w.cell.post { w.cell.requestFocus() }
    }

    // ---------- Widgets ----------

    private fun create(spec: Spec): Widget? = when {
        spec.type == "hero" -> Core(spec, "À la une", home.heroView) { 250 }
        spec.type == "sport" -> Core(spec, "Scores en direct", home.sportView) { s -> if (s >= 2) 170 else 250 }
        spec.type == "resume" -> Core(spec, "Reprendre la lecture", home.rowView) { 206 }
        spec.type == "top10:MOVIE" -> Rail(spec, "Top 10 des films aujourd'hui", top10 = true) { Top10.get(src.id, Kind.MOVIE) }
        spec.type == "top10:SERIES" -> Rail(spec, "Top 10 des séries aujourd'hui", top10 = true) { Top10.get(src.id, Kind.SERIES) }
        spec.type == "latest" -> Rail(spec, "Nouveautés", refreshes = true) {
            (Db.latest(src.id, Kind.MOVIE, 25) + Db.latest(src.id, Kind.SERIES, 25)).sortedByDescending { it.added }.take(30)
        }
        spec.type == "mylist" -> Rail(spec, "Ma liste", refreshes = true, empty = "Ajoute des films ou des séries à Ma liste (appui long) : ils apparaîtront ici.") {
            Db.favorites(src.id, Kind.MOVIE) + Db.favorites(src.id, Kind.SERIES)
        }
        spec.type.startsWith("cat:") -> {
            val parts = spec.type.split(':', limit = 3)
            val kind = if (parts.getOrNull(1) == Kind.SERIES.name) Kind.SERIES else Kind.MOVIE
            val key = parts.getOrNull(2).orEmpty()
            val g = try {
                Catalog.groups(src.id, kind).firstOrNull { it.key == key }
            } catch (_: Exception) {
                null
            }
            if (g == null) null else Rail(spec, Catalog.cleanName(g.name)) { Db.page(src.id, kind, g.catIds, null, 0, 30) }
        }
        spec.type == "channels" -> Channels(spec)
        spec.type == "tonight" -> Tonight(spec)
        spec.type == "sportday" -> SportDay(spec)
        spec.type == "clock" -> Clock(spec)
        else -> null
    }

    private abstract inner class Widget(val spec: Spec, val title: String) {
        abstract val view: View
        val cell: FrameLayout by lazy {
            FrameLayout(act).apply {
                clipChildren = false
                clipToPadding = false
                (view.parent as? ViewGroup)?.removeView(view)
                addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
        abstract fun height(size: Int): Int
        open fun sized(size: Int) {}
        open fun start() {}
        open fun refresh() {}
        open fun resume() {}
        open fun pause() {}
    }

    /** Blocs historiques (À la une, sport, reprise) : leur logique reste dans [HomeScreen]. */
    private inner class Core(spec: Spec, title: String, override val view: View, val h: (Int) -> Int) : Widget(spec, title) {
        override fun height(size: Int) = h(size)
        override fun sized(size: Int) {
            if (view === home.sportView) home.setSportWide(size >= 2)
        }
    }

    /** Cadre en verre avec un titre. */
    private abstract inner class Panel(spec: Spec, title: String) : Widget(spec, title) {
        final override val view: View = inf.inflate(R.layout.widget_panel, null, false)
        val head: TextView = view.findViewById(R.id.wTitle)
        val hint: TextView = view.findViewById(R.id.wHint)
        val dot: View = view.findViewById(R.id.wDot)
        val body: FrameLayout = view.findViewById(R.id.wBody)
        val emptyText: TextView = TextView(act, null, 0, R.style.Txt).apply {
            setTextColor(act.getColor(R.color.text_dim))
            textSize = 12f
            setLineSpacing(0f, 1.1f)
            visibility = View.GONE
        }

        init {
            head.text = title
        }

        fun showEmpty(msg: String?) {
            if (emptyText.parent == null) body.addView(emptyText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(8)
            })
            emptyText.text = msg ?: ""
            emptyText.visibility = if (msg == null) View.GONE else View.VISIBLE
        }
    }

    private fun open(item: Item) {
        val cls = if (item.kind == Kind.SERIES) SeriesActivity::class.java else MovieActivity::class.java
        act.startActivity(Intent(act, cls).putExtra(MainActivity.EXTRA_ITEM, item.toJson().toString()))
    }

    private fun horizontal(rv: RecyclerView) {
        rv.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.clipChildren = false
        rv.overScrollMode = View.OVER_SCROLL_NEVER
        rv.setPadding(px(2), px(2), px(12), 0)
        Fx.lockRow(rv, escapeLeft = true, escapeRight = true)
    }

    /** Rangée d'affiches (Top 10 avec ses grands chiffres, nouveautés, Ma liste, catégorie). */
    private inner class Rail(
        spec: Spec,
        title: String,
        val top10: Boolean = false,
        val refreshes: Boolean = false,
        val empty: String = "Rien à afficher pour l'instant.",
        val loader: () -> List<Item>
    ) : Panel(spec, title) {
        val rv = RecyclerView(act)
        var list: List<Item> = emptyList()
        var loading = false
        var loaded = false

        init {
            horizontal(rv)
            rv.adapter = Posters()
            body.addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (top10) hint.text = "nouveautés · actualité · mieux notés"
        }

        override fun height(size: Int) = 206
        override fun start() = load()
        override fun refresh() {
            if (refreshes || top10) load()
        }

        fun load() {
            if (loading) return
            loading = true
            Bg.task(act, loader) { r ->
                loading = false
                val l = r.getOrNull() ?: return@task
                if (loaded && l.map { it.key } == list.map { it.key }) return@task
                loaded = true
                list = l
                rv.adapter?.notifyDataSetChanged()
                showEmpty(if (l.isEmpty()) empty else null)
            }
        }

        inner class Posters : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = list.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = inf.inflate(if (top10) R.layout.item_top10 else R.layout.item_card, parent, false)
                val frame = if (top10) v.findViewById<View>(R.id.frame) else v
                frame.clipToOutline = true
                Fx.shine(frame)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                val item = list[position]
                val v = h.itemView
                val rank = v.findViewById<RankView?>(R.id.rank)
                rank?.rank = position + 1
                val noImage = item.icon.isNullOrBlank()
                v.findViewById<TextView?>(R.id.name)?.apply {
                    text = Tmdb.cleanTitle(item.name).first
                    visibility = if (noImage) View.VISIBLE else View.GONE
                }
                v.findViewById<View?>(R.id.overlay)?.visibility = if (noImage) View.VISIBLE else View.GONE
                v.findViewById<View?>(R.id.sub)?.visibility = View.GONE
                v.findViewById<View?>(R.id.bar)?.visibility = View.GONE
                v.findViewById<View?>(R.id.badge)?.visibility = View.GONE
                v.findViewById<ImageView?>(R.id.img)?.let { Img.load(it, item.icon, R.drawable.ph_poster, 300) }
                v.setOnFocusChangeListener { view, has ->
                    Fx.focus(view, has, if (top10) 1.05f else 1.08f)
                    rank?.lit = has
                }
                v.setOnClickListener { open(item) }
                v.setOnLongClickListener {
                    val added = Db.toggleFav(src.id, item)
                    Ui.toast(act, if (added) "Ajouté à Ma liste" else "Retiré de Ma liste")
                    true
                }
            }
        }
    }

    /** Chaînes favorites : logo, nom et programme en cours ; OK lance la chaîne. */
    private inner class Channels(spec: Spec) : Panel(spec, "Chaînes favorites") {
        val rv = RecyclerView(act)
        var list: List<Item> = emptyList()

        init {
            horizontal(rv)
            rv.adapter = Tiles()
            body.addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        override fun height(size: Int) = 156
        override fun start() = load()
        override fun refresh() = load()

        fun load() {
            Bg.task(act, { Db.favorites(src.id, Kind.LIVE) }) { r ->
                val l = r.getOrNull() ?: return@task
                if (l.map { it.key } != list.map { it.key }) {
                    list = l
                    rv.adapter?.notifyDataSetChanged()
                }
                showEmpty(if (l.isEmpty()) "Mets des chaînes en favori (appui long dans le Direct) : elles apparaîtront ici." else null)
            }
        }

        inner class Tiles : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = list.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = inf.inflate(R.layout.item_widget_channel, parent, false)
                v.clipToOutline = true
                Fx.shine(v)
                v.setOnFocusChangeListener { view, has -> Fx.focus(view, has, 1.07f) }
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                val ch = list[position]
                val v = h.itemView
                Img.load(v.findViewById(R.id.img), ch.icon, R.drawable.ph_live)
                v.findViewById<TextView>(R.id.name).text = ch.name
                val sub = v.findViewById<TextView>(R.id.sub)
                sub.tag = ch.id
                sub.text = Epg.cached(ch.id)?.let { Epg.nowAndNext(it).first?.title } ?: ""
                if (Prefs.epgEnabled && Epg.cached(ch.id) == null) Epg.prefetch(api, ch.id) { l ->
                    if (sub.tag == ch.id) sub.text = Epg.nowAndNext(l).first?.title ?: ""
                }
                v.setOnClickListener { Ui.play(act, ItemList.of(list), position) }
            }
        }
    }

    /** Lignes simples (logo, titre, détail, heure) : programmes du soir, matchs du jour. */
    private abstract inner class Rows(spec: Spec, title: String) : Panel(spec, title) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        init {
            body.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        override fun height(size: Int) = 226

        fun row(name: CharSequence, sub: String?, time: String, icon: String?, onClick: () -> Unit) {
            val v = inf.inflate(R.layout.item_home_channel, box, false)
            v.findViewById<TextView>(R.id.name).text = name
            v.findViewById<TextView>(R.id.sub).apply {
                text = sub ?: ""
                visibility = if (sub.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            v.findViewById<TextView>(R.id.time).text = time
            val img = v.findViewById<ImageView>(R.id.img)
            if (icon == null) img.visibility = View.GONE else Img.load(img, icon, R.drawable.ph_live)
            v.setOnFocusChangeListener { view, has -> Fx.focus(view, has, 1.03f) }
            v.setOnClickListener { onClick() }
            box.addView(v)
        }
    }

    /** Ce soir à la TV : ce qui commence vers 21 h sur tes chaînes favorites (ou les premières de ta liste). */
    private inner class Tonight(spec: Spec) : Rows(spec, "Ce soir à la TV") {
        var stamp = ""

        init {
            hint.text = "vers 21 h"
        }

        override fun start() = load()
        override fun refresh() = load()

        fun load() {
            if (!Prefs.epgEnabled) {
                showEmpty("Guide TV désactivé dans les réglages.")
                return
            }
            Bg.task(act, {
                val favs = Db.favorites(src.id, Kind.LIVE)
                (if (favs.size >= 3) favs else (favs + Db.page(src.id, Kind.LIVE, null, null, 0, 14))).distinctBy { it.id }.take(14)
            }) { r ->
                val chans = r.getOrNull() ?: return@task
                if (chans.isEmpty()) {
                    showEmpty("Aucune chaîne dans cette source.")
                    return@task
                }
                val cal = Calendar.getInstance()
                // Après 23 h, on montre la soirée du lendemain
                if (cal.get(Calendar.HOUR_OF_DAY) >= 23) cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 21)
                cal.set(Calendar.MINUTE, 5)
                cal.set(Calendar.SECOND, 0)
                val t = cal.timeInMillis / 1000
                val found = HashMap<String, EpgEntry>()
                var pending = chans.size
                for (ch in chans) Epg.prefetch(api, ch.id) { l ->
                    val p = l.filter { it.start in (t - 25 * 60)..(t + 30 * 60) }.minByOrNull { kotlin.math.abs(it.start - t) }
                        ?: l.firstOrNull { it.start <= t && t < it.end }
                    if (p != null) found[ch.id] = p
                    pending--
                    if (pending % 4 == 0 || pending == 0) paint(chans, found)
                }
            }
        }

        fun paint(chans: List<Item>, found: Map<String, EpgEntry>) {
            val list = chans.filter { found.containsKey(it.id) }.take(4)
            val key = list.joinToString { it.id + found[it.id]?.title }
            if (key == stamp) return
            stamp = key
            box.removeAllViews()
            showEmpty(if (list.isEmpty()) "Pas encore de programme du soir dans le guide." else null)
            val fmt = SimpleDateFormat("HH:mm", Locale.FRANCE)
            for (ch in list) {
                val p = found[ch.id] ?: continue
                row(p.title, ch.name, fmt.format(Date(p.start * 1000)), ch.icon) {
                    val i = chans.indexOf(ch)
                    Ui.play(act, ItemList.of(chans), i.coerceAtLeast(0))
                }
            }
        }
    }

    /** Matchs du jour de tes sports : en direct d'abord, puis à venir ; OK lance le direct. */
    private inner class SportDay(spec: Spec) : Rows(spec, "Matchs du jour") {
        var chans: List<Item> = emptyList()
        var stamp = ""
        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                load()
                Bg.main.postDelayed(this, 60_000)
            }
        }

        override fun start() = paint(Sport.cached())
        override fun resume() {
            Bg.main.removeCallbacks(tick)
            Bg.main.post(tick)
        }

        override fun pause() {
            Bg.main.removeCallbacks(tick)
        }

        fun load() {
            Bg.task(act, {
                if (chans.isEmpty()) chans = SportChannels.candidates(src.id)
                Sport.get(60_000L)
            }) { r -> r.getOrNull()?.let { paint(it) } }
        }

        fun paint(all: List<Sport.Match>) {
            val cal = Calendar.getInstance()
            val day = cal.get(Calendar.DAY_OF_YEAR)
            val today = all.filter { m ->
                m.live || (Calendar.getInstance().apply { timeInMillis = m.start }.get(Calendar.DAY_OF_YEAR) == day)
            }
            val list = (today.filter { it.live }.sortedByDescending { Sport.trend(it) } +
                today.filter { !it.live && !it.finished }.sortedBy { it.start } +
                today.filter { it.finished }.sortedByDescending { it.start }).take(4)
            val live = list.any { it.live }
            dot.visibility = if (live) View.VISIBLE else View.GONE
            val key = list.joinToString { it.key + it.status + it.home.score + it.away.score }
            if (key == stamp && box.childCount > 0) return
            stamp = key
            box.removeAllViews()
            showEmpty(if (list.isEmpty()) (if (all.isEmpty()) "Chargement des matchs…" else "Pas de match de tes sports aujourd'hui.") else null)
            val fmt = SimpleDateFormat("HH:mm", Locale.FRANCE)
            for (m in list) {
                val name = if (m.live || m.finished) "${m.home.short} ${m.home.score ?: 0} – ${m.away.score ?: 0} ${m.away.short}" else m.title
                val time = when {
                    m.live -> m.status
                    m.finished -> "Terminé"
                    else -> fmt.format(Date(m.start))
                }
                row(name, m.league.label, time, null) {
                    if (m.finished) openSport() else MatchFinder.watch(act, src.id, m, chans) { openSport() }
                }
            }
        }
    }

    /** Horloge : grande heure, date et salutation selon le moment de la journée. */
    private inner class Clock(spec: Spec) : Panel(spec, "Horloge") {
        val time = TextView(act, null, 0, R.style.Title).apply {
            textSize = 64f
            gravity = Gravity.CENTER
        }
        val date = TextView(act, null, 0, R.style.Medium).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(act.getColor(R.color.text))
        }
        val tick = object : Runnable {
            override fun run() {
                paint()
                if (running) Bg.main.postDelayed(this, 10_000)
            }
        }

        init {
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(time)
                addView(date)
            }
            body.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            paint()
        }

        override fun height(size: Int) = 156
        override fun resume() {
            Bg.main.removeCallbacks(tick)
            Bg.main.post(tick)
        }

        override fun pause() {
            Bg.main.removeCallbacks(tick)
        }

        fun paint() {
            val now = Date()
            time.text = SimpleDateFormat("HH:mm", Locale.FRANCE).format(now)
            date.text = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(now).replaceFirstChar { it.uppercase() }
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val name = Session.profile()?.name?.takeIf { it.isNotBlank() }
            head.text = (if (h in 5..17) "Bonjour" else "Bonsoir") + (name?.let { ", $it" } ?: "")
        }
    }
}
