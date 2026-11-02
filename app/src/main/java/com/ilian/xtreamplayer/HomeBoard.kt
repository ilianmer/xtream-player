package com.ilian.xtreamplayer

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.Rect
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Défilement du tableau de l'accueil, page par page : chaque page a la hauteur exacte de l'écran,
 * et l'arrivée du focus dans une autre page la fait glisser en entier, d'un seul mouvement.
 * Le contenu est coupé net à la limite de la zone (il ne passe jamais sous la barre du haut).
 */
class BoardScrollView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ScrollView(ctx, attrs) {
    /** Hauteur visible changée (premier affichage, barre de personnalisation…). */
    var onHeight: ((Int) -> Unit)? = null
    /** Page affichée, pour l'indicateur. */
    var onPage: ((Int) -> Unit)? = null
    private var anim: ValueAnimator? = null
    private var target = -1
    private val ease = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0

    override fun executeKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> false
        else -> super.executeKeyEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (h != oh && h > 0) post { onHeight?.invoke(h) }
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        focused?.let { reveal(it, true) }
    }

    /** Page (enfant direct du tableau) qui contient [v]. */
    private fun pageOf(v: View): View? {
        val board = getChildAt(0) as? ViewGroup ?: return null
        var c: View = v
        var p = c.parent
        while (p != null && p !== board) {
            c = p as? View ?: return null
            p = c.parent
        }
        return if (p === board) c else null
    }

    fun reveal(v: View, animate: Boolean) {
        val page = pageOf(v) ?: return
        goTo(page.top, animate)
        (getChildAt(0) as? ViewGroup)?.let { onPage?.invoke(it.indexOfChild(page)) }
    }

    fun goTo(y: Int, animate: Boolean) {
        if (y == target && anim?.isRunning == true) return
        target = y
        anim?.cancel()
        if (!animate || y == scrollY) {
            scrollTo(0, y)
            return
        }
        anim = ValueAnimator.ofInt(scrollY, y).apply {
            duration = 480
            interpolator = ease
            addUpdateListener { scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }
}

/**
 * Case d'un widget : son contenu est dessiné à sa taille idéale puis réduit (jusqu'à 80 %) pour tenir
 * exactement dans la case, comme un widget d'iPhone ; tout ce qui dépasserait est coupé au bord arrondi.
 */
class FitFrame(ctx: Context) : FrameLayout(ctx) {
    /** Hauteur idéale du contenu (px). */
    var designH = 0
    var fit = 1f
        private set
    private val radius = 16f * resources.displayMetrics.density

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        fit = if (designH > 0 && h > 0) (h.toFloat() / designH).coerceIn(MIN_FIT, 1f) else 1f
        val cw = MeasureSpec.makeMeasureSpec((w / fit).roundToInt(), MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec((h / fit).roundToInt(), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.layout(0, 0, c.measuredWidth, c.measuredHeight)
            c.pivotX = 0f
            c.pivotY = 0f
            c.scaleX = fit
            c.scaleY = fit
        }
    }

    companion object {
        const val MIN_FIT = 0.8f
    }
}

/**
 * Accueil en widgets, façon iPhone : tout se personnalise (ajouter, retirer, déplacer, petit / moyen / grand).
 * Les widgets se rangent sur une grille de trois colonnes, puis en pages de la hauteur de l'écran :
 * une page prend autant de rangées entières qu'il en tient, ajustées pour remplir l'écran sans rien couper.
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
    private val editBar: View = root.findViewById(R.id.editBar)
    private val pageDots: LinearLayout = root.findViewById(R.id.pageDots)
    private val inf = LayoutInflater.from(act)
    private val dp = act.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private val ease = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    class Spec(val type: String, var size: Int)

    /** Type de widget : nom, tailles possibles (1 petit, 2 moyen, 3 grand), taille par défaut. */
    private class Type(val id: String, val label: String, val sizes: List<Int>, val size: Int)

    private val types = listOf(
        Type("hero", "À la une", listOf(2, 3), 2),
        Type("sport", "Scores en direct", listOf(1, 2, 3), 1),
        Type("resume", "Reprendre la lecture", listOf(2, 3), 3),
        Type("top10:MOVIE", "Top 10 des films", listOf(1, 2, 3), 3),
        Type("top10:SERIES", "Top 10 des séries", listOf(1, 2, 3), 3),
        Type("channels", "Chaînes favorites", listOf(1, 2, 3), 2),
        Type("tonight", "Ce soir à la TV", listOf(1, 2, 3), 2),
        Type("sportday", "Matchs du jour", listOf(1, 2, 3), 1),
        Type("odds", "Cotes en direct", listOf(1, 2, 3), 2),
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
        private const val GAP = 12
        /** Vitesse du ruban du Top 10 (dp par seconde) : lente et régulière. */
        private const val GLIDE = 30f
    }

    private val items = ArrayList<Widget>()
    private var editing = false
    private var moving: Widget? = null
    private var running = false
    private val jiggles = ArrayList<ValueAnimator>()
    private var laidOut = false
    private var pendingFocus = false
    private var pageCount = 0
    private var page = 0

    init {
        scroll.onHeight = { relayout() }
        scroll.onPage = { p ->
            page = p
            paintDots()
        }
        val add = root.findViewById<View>(R.id.btnAddWidget)
        val reset = root.findViewById<View>(R.id.btnResetWidgets)
        val done = root.findViewById<View>(R.id.btnDoneWidgets)
        add.setOnClickListener { addWidget() }
        reset.setOnClickListener { reset() }
        done.setOnClickListener { exitEdit() }
        for (b in listOf(add, reset, done)) {
            b.setOnFocusChangeListener { v, has -> Fx.focus(v, has, 1.05f) }
            b.setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_BACK && editing) {
                    if (e.action == KeyEvent.ACTION_UP) exitEdit()
                    true
                } else false
            }
        }
    }

    // ---------- Enregistrement ----------

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

    // ---------- Cycle de vie ----------

    fun start() {
        for (s in load()) create(s)?.let { items.add(it) }
        layout()
        for (w in items) w.start()
        if (Db.kvGet(Db.pkey("home_widgets_hint2")) == null) {
            Db.kvPut(Db.pkey("home_widgets_hint2"), "1")
            Bg.main.postDelayed({
                if (!act.isFinishing) Ui.toast(act, "Personnalise ton accueil avec l'icône en haut à droite, à côté des Réglages.")
            }, 2_500)
        }
    }

    /** Moment où l'on a quitté l'accueil : après plus de 3 minutes, il revient à sa première page. */
    private var pausedAt = 0L

    fun resume() {
        val stale = !running && pausedAt > 0L && android.os.SystemClock.elapsedRealtime() - pausedAt > 180_000L
        running = true
        for (w in items) w.resume()
        if (stale && laidOut && !editing) {
            page = 0
            paintDots()
            scroll.goTo(0, false)
            board.post {
                val f = act.currentFocus
                if (f == null || board.hasFocus()) focusDefault()
            }
        }
    }

    fun pause() {
        if (running) pausedAt = android.os.SystemClock.elapsedRealtime()
        running = false
        for (w in items) w.pause()
    }

    fun refresh() {
        for (w in items) w.refresh()
    }

    fun focusDefault() {
        if (!laidOut) {
            pendingFocus = true
            return
        }
        scroll.goTo(0, false)
        if (items.any { it.spec.type == "hero" } && items.firstOrNull { it.spec.type == "hero" }?.let { pageOfWidget(it) } == 0) {
            home.focusDefault()
        } else {
            (board.getChildAt(0) ?: board).requestFocus()
        }
    }

    private fun pageOfWidget(w: Widget): Int {
        var c: View = w.cell
        var p = c.parent
        while (p != null && p !== board) {
            c = p as? View ?: return -1
            p = c.parent
        }
        return if (p === board) board.indexOfChild(c) else -1
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

    // ---------- Pages ----------

    private class Row(val ws: List<Widget>) {
        var pref = 0
        var min = 0
        var h = 0
    }

    /** Nouvelle mise en page en gardant le focus là où il était. */
    private fun relayout() {
        val f = act.currentFocus
        layout()
        if (f != null && f.isAttachedToWindow && f.isShown) f.requestFocus()
        if (pendingFocus && laidOut) {
            pendingFocus = false
            focusDefault()
        }
    }

    private fun layout() {
        val pageH = scroll.height - scroll.paddingTop - scroll.paddingBottom
        stopJiggle()
        // Les blocs historiques non posés retournent dans leur réserve (toujours attachés à l'écran)
        for (v in listOf(home.heroView, home.sportView, home.rowView)) {
            if (items.none { it.view === v } && v.parent !== stash) {
                (v.parent as? ViewGroup)?.removeView(v)
                stash.addView(v)
            }
        }
        if (pageH <= 0) return // la hauteur de l'écran arrive juste après : onHeight relance la mise en page
        laidOut = true
        for (w in items) (w.cell.parent as? ViewGroup)?.removeView(w.cell)
        board.removeAllViews()

        // 1. Rangées de trois colonnes
        val rows = ArrayList<Row>()
        var cur = ArrayList<Widget>()
        var used = 0
        for (w in items) {
            val t = typeOf(w.spec) ?: continue
            if (w.spec.size !in t.sizes) w.spec.size = t.size
            val s = w.spec.size
            if (cur.isNotEmpty() && used + s > 3) {
                rows.add(Row(cur))
                cur = ArrayList()
                used = 0
            }
            cur.add(w)
            used += s
        }
        if (cur.isNotEmpty()) rows.add(Row(cur))
        val gap = px(GAP)
        for (r in rows) {
            r.pref = px(r.ws.maxOf { it.design(it.spec.size) })
            r.min = (r.pref * FitFrame.MIN_FIT).toInt()
        }

        // 2. Pages : autant de rangées entières qu'il en tient à l'écran
        val pages = ArrayList<List<Row>>()
        var pg = ArrayList<Row>()
        var minSum = 0
        for (r in rows) {
            if (pg.isNotEmpty() && minSum + r.min + gap * (pg.size + 1) > pageH) {
                pages.add(pg)
                pg = ArrayList()
                minSum = 0
            }
            pg.add(r)
            minSum += r.min
        }
        if (pg.isNotEmpty()) pages.add(pg)

        // 3. Hauteurs : on resserre ou on étire un peu les rangées pour remplir l'écran exactement
        for (p in pages) {
            val avail = pageH - gap * p.size
            val sumPref = p.sumOf { it.pref }
            val sumMin = p.sumOf { it.min }
            if (sumPref >= avail) {
                val t = if (sumPref <= sumMin) 1f else ((sumPref - avail).toFloat() / (sumPref - sumMin)).coerceIn(0f, 1f)
                for (r in p) r.h = (r.pref - t * (r.pref - r.min)).toInt()
            } else {
                val f = (avail.toFloat() / sumPref).coerceAtMost(1.15f)
                for (r in p) r.h = (r.pref * f).toInt()
            }
            for (r in p) r.h = r.h.coerceAtMost(avail)
        }

        // 4. Vues
        for (p in pages) {
            val pv = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            for (r in p) {
                val rv = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = 3f
                    clipChildren = false
                    clipToPadding = false
                }
                for (w in r.ws) {
                    val design = px(w.design(w.spec.size))
                    w.cell.designH = design
                    rv.addView(w.cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w.spec.size.toFloat()).apply {
                        marginStart = px(6)
                        marginEnd = px(6)
                    })
                    val fit = (r.h.toFloat() / design).coerceIn(FitFrame.MIN_FIT, 1f)
                    w.sized(w.spec.size, (r.h / fit / dp).toInt())
                    styleCell(w)
                }
                pv.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, r.h).apply { bottomMargin = gap })
            }
            board.addView(pv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pageH))
        }
        pageCount = pages.size
        page = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        paintDots()
        if (editing) startJiggle()
        // On reste aligné sur une page (celle du focus)
        scroll.post {
            val f = act.currentFocus
            if (f != null && board.hasFocus()) scroll.reveal(f, false) else scroll.goTo(page * pageH, false)
        }
    }

    /** Points de page à droite (seulement s'il y a plusieurs pages). */
    private fun paintDots() {
        if (pageCount <= 1) {
            pageDots.visibility = View.GONE
            return
        }
        pageDots.visibility = View.VISIBLE
        if (pageDots.childCount != pageCount) {
            pageDots.removeAllViews()
            repeat(pageCount) {
                pageDots.addView(View(act), LinearLayout.LayoutParams(px(4), px(4)).apply {
                    topMargin = px(3)
                    bottomMargin = px(3)
                })
            }
        }
        for (i in 0 until pageDots.childCount) {
            val d = pageDots.getChildAt(i)
            d.setBackgroundResource(if (i == page) R.drawable.dot_on else R.drawable.dot_off)
            val target = px(if (i == page) 14 else 4)
            val from = d.layoutParams.height
            if (from != target) {
                ValueAnimator.ofInt(from, target).apply {
                    duration = 320
                    interpolator = ease
                    addUpdateListener { a -> d.layoutParams = d.layoutParams.apply { height = a.animatedValue as Int } }
                    start()
                }
            }
        }
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
            c.translationZ = 0f
        }
    }

    // ---------- Mode personnalisation (les widgets « tremblent » comme sur iPhone) ----------

    /** Barre du haut (onglets) : hors d'atteinte pendant la personnalisation, pour ne pas changer d'onglet par erreur. */
    private var topFocus = ViewGroup.FOCUS_BEFORE_DESCENDANTS

    private fun lockTopBar(lock: Boolean) {
        val bar = act.findViewById<ViewGroup>(R.id.topBar) ?: return
        if (lock) {
            topFocus = bar.descendantFocusability
            bar.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } else {
            bar.descendantFocusability = topFocus
        }
    }

    fun enterEdit() {
        if (editing) return
        editing = true
        lockTopBar(true)
        editBar.visibility = View.VISIBLE
        editBar.alpha = 0f
        editBar.translationY = -8 * dp
        editBar.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(ease).start()
        layout()
        focusFirstCell()
    }

    private fun exitEdit() {
        moving = null
        editing = false
        lockTopBar(false)
        save()
        editBar.visibility = View.GONE
        layout()
        board.post { focusDefault() }
    }

    private fun reset() {
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
    }

    private fun focusFirstCell() {
        board.post { items.firstOrNull()?.cell?.requestFocus() ?: board.requestFocus() }
    }

    private fun startJiggle() {
        stopJiggle()
        for ((i, w) in items.withIndex()) {
            val c = w.cell
            c.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            jiggles.add(ObjectAnimator.ofFloat(c, View.ROTATION, -0.3f, 0.3f).apply {
                duration = 150L + (i % 3) * 18L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                startDelay = (i * 37L) % 120L
                start()
            })
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
                w.cell.post {
                    w.cell.requestFocus()
                    lift(w)
                }
            } else Fx.bump(w.cell, if (to < 0) -1 else 1, 0)
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) exitEdit()
            return true
        }
        return false
    }

    private fun lift(w: Widget) {
        w.cell.animate().scaleX(1.03f).scaleY(1.03f).translationZ(12 * dp).setDuration(180).start()
    }

    private fun drop() {
        val w = moving ?: return
        moving = null
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
                    w.cell.post {
                        w.cell.requestFocus()
                        lift(w)
                    }
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
                    val next = items.getOrNull(i) ?: items.lastOrNull()
                    next?.cell?.post { next.cell.requestFocus() }
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
        spec.type == "sport" -> Core(spec, "Scores en direct", home.sportView) { s -> if (s >= 2) 158 else 260 }
        spec.type == "resume" -> Core(spec, "Reprendre la lecture", home.rowView) { 200 }
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
        spec.type == "odds" -> OddsWidget(spec)
        spec.type == "clock" -> Clock(spec)
        else -> null
    }

    private abstract inner class Widget(val spec: Spec, val title: String) {
        abstract val view: View
        val cell: FitFrame by lazy {
            FitFrame(act).apply {
                (view.parent as? ViewGroup)?.removeView(view)
                addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }

        /** Hauteur idéale (dp) pour cette taille. */
        abstract fun design(size: Int): Int

        /** Taille et hauteur réelle du contenu (dp) : le widget adapte ce qu'il montre. */
        open fun sized(size: Int, contentH: Int) {}
        open fun start() {}
        open fun refresh() {}
        open fun resume() {}
        open fun pause() {}
    }

    /** Blocs historiques (À la une, sport, reprise) : leur logique reste dans [HomeScreen]. */
    private inner class Core(spec: Spec, title: String, override val view: View, val h: (Int) -> Int) : Widget(spec, title) {
        override fun design(size: Int) = h(size)
        override fun sized(size: Int, contentH: Int) {
            if (view !== home.sportView) return
            if (size >= 2) home.setSportLayout(true, if (size == 3) 4 else 3)
            else home.setSportLayout(false, ((contentH - 50) / 104).coerceIn(1, 3))
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

    private fun toggleFav(item: Item) {
        val added = Db.toggleFav(src.id, item)
        Ui.toast(act, if (added) "Ajouté à Ma liste" else "Retiré de Ma liste")
    }

    private fun horizontal(rv: RecyclerView) {
        rv.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.clipChildren = false
        rv.overScrollMode = View.OVER_SCROLL_NEVER
        rv.setPadding(px(4), px(4), px(12), px(4))
        Fx.lockRow(rv, escapeLeft = true, escapeRight = true)
    }

    /** Affiche, titre (sans image) et grand chiffre (Top 10) d'une carte. */
    private fun bindCard(v: View, item: Item, position: Int) {
        v.findViewById<RankView?>(R.id.rank)?.rank = position + 1
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
    }

    /**
     * Rangée d'affiches (Top 10 avec ses grands chiffres, nouveautés, Ma liste, catégorie).
     * Top 10 en moyen et en grand : la rangée glisse en continu, comme un ruban, et enchaîne
     * le n° 1 après le n° 10 (sans retour en arrière). En petit : les affiches glissent l'une
     * après l'autre, l'ancienne sort à gauche pendant que la nouvelle entre par la droite.
     * Dès que le focus est dessus, tout ralentit et s'arrête en douceur ; ça repart quand on part.
     */
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
        /** 0 : rangée, 1 : carrousel (Top 10 en petit). */
        var mode = -1
        /** Rangée sans fin (Top 10) : on démarre loin du début pour pouvoir glisser indéfiniment. */
        val endless get() = top10 && mode == 0 && list.size > 1
        val loops = 2_000

        // ----- Ruban continu (Top 10 moyen / grand) -----
        var speed = 0f
        var carry = 0f
        var lastFrame = 0L
        var gliding = false
        val seen = Rect()
        val frame = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(now: Long) {
                if (!gliding) return
                val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
                lastFrame = now
                // Vitesse visée : environ un point d'écran par image ; zéro si on regarde la rangée,
                // si l'accueil est en pause ou en personnalisation, ou si le widget n'est pas à l'écran
                val visible = cell.isShown && cell.getGlobalVisibleRect(seen)
                val target = if (running && !editing && !rv.hasFocus() && visible && endless) GLIDE * dp else 0f
                // Accélère doucement, freine plus vite
                val k = if (target > speed) 1.6f else 7f
                speed += (target - speed) * (k * dt).coerceAtMost(1f)
                if (speed < 0.5f && target == 0f) {
                    speed = 0f
                    carry = 0f
                    gliding = false
                    lastFrame = 0L
                    return
                }
                carry += speed * dt
                val step = carry.toInt()
                if (step > 0) {
                    carry -= step
                    rv.scrollBy(step, 0)
                }
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }

        fun glide() {
            if (gliding || !endless || !running) return
            gliding = true
            lastFrame = 0L
            android.view.Choreographer.getInstance().postFrameCallback(frame)
        }

        // ----- Carrousel (Top 10 petit) -----
        var carBox: FrameLayout? = null
        var carA: View? = null
        var carB: View? = null
        var carTitle: TextView? = null
        var carDots: LinearLayout? = null
        var carIndex = 0
        var sliding = false

        /** Relance : glissement continu, ou affiche suivante du carrousel toutes les 4,5 s. */
        val auto = object : Runnable {
            override fun run() {
                if (!running) return
                if (mode == 1) {
                    Bg.main.postDelayed(this, 4_500)
                    if (!editing && list.size > 1 && carBox?.hasFocus() == false) slide(carIndex + 1, 1)
                } else {
                    // Le ruban vérifie de temps en temps s'il peut repartir (focus parti, page revenue…)
                    Bg.main.postDelayed(this, 700)
                    glide()
                }
            }
        }

        init {
            horizontal(rv)
            rv.adapter = Posters()
            rv.setItemViewCacheSize(6)
        }

        override fun design(size: Int) = if (top10 && size == 1) 236 else 192

        override fun sized(size: Int, contentH: Int) {
            val m = if (top10 && size == 1) 1 else 0
            if (m == mode) return
            mode = m
            body.removeAllViews()
            if (m == 0) {
                body.addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            } else {
                body.addView(carousel(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            paint()
        }

        override fun start() = load()
        override fun refresh() {
            if (refreshes || top10) load()
        }

        override fun resume() {
            if (!top10) return
            Bg.main.removeCallbacks(auto)
            Bg.main.postDelayed(auto, if (mode == 1) 4_500 else 900)
        }

        override fun pause() {
            Bg.main.removeCallbacks(auto)
            gliding = false
            speed = 0f
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
                // Toutes les affiches sont préparées d'avance : rien n'arrive en retard pendant le défilement
                if (top10) for (i in l) Img.prefetch(i.icon, 300)
                paint()
            }
        }

        fun paint() {
            if (mode == 1) {
                carIndex = carIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
                carBox?.visibility = if (list.isEmpty()) View.INVISIBLE else View.VISIBLE
                bindCarousel()
            } else {
                rv.adapter?.notifyDataSetChanged()
                // Ruban sans fin : on part du milieu, aligné sur le n° 1
                if (endless) (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(list.size * (loops / 2), 0)
            }
            if (loaded) showEmpty(if (list.isEmpty()) empty else null)
        }

        // ----- Carrousel -----

        fun card(parent: ViewGroup): View {
            val card = inf.inflate(R.layout.item_top10, parent, false)
            card.isFocusable = false
            card.isClickable = false
            card.isDuplicateParentStateEnabled = true
            card.findViewById<View>(R.id.frame).let {
                it.clipToOutline = true
                Fx.shine(it)
            }
            return card
        }

        fun carousel(): View {
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
            }
            // Le cadre garde le focus ; les deux affiches glissent à l'intérieur
            val box = FrameLayout(act).apply {
                isFocusable = true
                isClickable = true
                clipChildren = false
            }
            val a = card(box)
            val b = card(box)
            box.addView(b, FrameLayout.LayoutParams(px(160), px(144)))
            box.addView(a, FrameLayout.LayoutParams(px(160), px(144)))
            b.visibility = View.INVISIBLE
            col.addView(box, LinearLayout.LayoutParams(px(160), px(144)))
            val t = TextView(act, null, 0, R.style.Medium).apply {
                textSize = 11f
                gravity = Gravity.CENTER
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(act.getColor(R.color.text))
            }
            col.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(6)
                marginStart = px(8)
                marginEnd = px(8)
            })
            val dots = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            col.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(4)).apply { topMargin = px(7) })
            box.setOnFocusChangeListener { v, has ->
                Fx.focus(v, has, 1.05f)
                carA?.findViewById<RankView?>(R.id.rank)?.lit = has
            }
            box.setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    // Aux deux bouts du classement, gauche / droite laissent partir vers les widgets voisins
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (carIndex > 0) {
                        slide(carIndex - 1, -1)
                        true
                    } else false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (carIndex < list.size - 1) {
                        slide(carIndex + 1, 1)
                        true
                    } else false
                    else -> false
                }
            }
            box.setOnClickListener { list.getOrNull(carIndex)?.let { open(it) } }
            box.setOnLongClickListener {
                list.getOrNull(carIndex)?.let { toggleFav(it) }
                true
            }
            carBox = box
            carA = a
            carB = b
            carTitle = t
            carDots = dots
            return col
        }

        /** L'affiche en place sort d'un côté pendant que la suivante entre de l'autre, d'un seul mouvement. */
        fun slide(i: Int, dir: Int) {
            val a = carA ?: return
            val b = carB ?: return
            if (list.size < 2 || sliding) return
            val next = Math.floorMod(i, list.size)
            sliding = true
            val w = px(176).toFloat()
            bindCard(b, list[next], next)
            b.findViewById<RankView?>(R.id.rank)?.lit = carBox?.hasFocus() == true
            b.translationX = w * dir
            b.alpha = 1f
            b.visibility = View.VISIBLE
            val curve = PathInterpolator(0.45f, 0f, 0.2f, 1f)
            a.animate().translationX(-w * dir).alpha(0.4f).setDuration(620).setInterpolator(curve).withLayer().start()
            b.animate().translationX(0f).setDuration(620).setInterpolator(curve).withLayer().withEndAction {
                a.visibility = View.INVISIBLE
                a.translationX = 0f
                a.alpha = 1f
                // Les rôles s'échangent : la nouvelle affiche passe devant
                b.bringToFront()
                carA = b
                carB = a
                carIndex = next
                sliding = false
                paintCaption()
            }.start()
            // La légende et les points suivent en même temps
            carTitle?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
                carTitle?.text = Tmdb.cleanTitle(list[next].name).first
                carTitle?.animate()?.alpha(1f)?.setDuration(260)?.start()
            }?.start()
            paintDots(next)
            // L'affiche d'après est déjà prête
            list.getOrNull(Math.floorMod(next + dir, list.size))?.let { Img.prefetch(it.icon, 300) }
        }

        fun bindCarousel() {
            val a = carA ?: return
            val item = list.getOrNull(carIndex) ?: return
            bindCard(a, item, carIndex)
            paintCaption()
        }

        fun paintCaption() {
            list.getOrNull(carIndex)?.let { carTitle?.text = Tmdb.cleanTitle(it.name).first }
            paintDots(carIndex)
        }

        fun paintDots(cur: Int) {
            val dots = carDots ?: return
            if (dots.childCount != list.size) {
                dots.removeAllViews()
                repeat(list.size) {
                    dots.addView(View(act), LinearLayout.LayoutParams(px(4), px(4)).apply {
                        marginStart = px(2)
                        marginEnd = px(2)
                    })
                }
            }
            for (k in 0 until dots.childCount) {
                val d = dots.getChildAt(k)
                d.setBackgroundResource(if (k == cur) R.drawable.dot_on else R.drawable.dot_off)
                val target = px(if (k == cur) 12 else 4)
                val from = d.layoutParams.width
                if (from == target) continue
                ValueAnimator.ofInt(from, target).apply {
                    duration = 420
                    interpolator = ease
                    addUpdateListener { an -> d.layoutParams = d.layoutParams.apply { width = an.animatedValue as Int } }
                    start()
                }
            }
        }

        inner class Posters : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = if (endless) list.size * loops else list.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = inf.inflate(if (top10) R.layout.item_top10 else R.layout.item_card, parent, false)
                val frame = if (top10) v.findViewById<View>(R.id.frame) else v
                frame.clipToOutline = true
                Fx.shine(frame)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                val idx = if (list.isEmpty()) return else position % list.size
                val item = list[idx]
                val v = h.itemView
                bindCard(v, item, idx)
                val rank = v.findViewById<RankView?>(R.id.rank)
                v.setOnFocusChangeListener { view, has ->
                    Fx.focus(view, has, if (top10) 1.05f else 1.08f)
                    rank?.lit = has
                }
                v.setOnClickListener { open(item) }
                v.setOnLongClickListener {
                    toggleFav(item)
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
            body.addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        }

        override fun design(size: Int) = 146
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
        /** Lignes qui tiennent dans la hauteur du widget (aucune coupée). */
        var maxRows = 4

        init {
            body.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        override fun design(size: Int) = 226

        override fun sized(size: Int, contentH: Int) {
            val n = ((contentH - 41) / 46).coerceIn(1, 6)
            if (n != maxRows) {
                maxRows = n
                repaint()
            }
        }

        abstract fun repaint()

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
        var chans: List<Item> = emptyList()
        val found = HashMap<String, EpgEntry>()

        init {
            hint.text = "vers 21 h"
        }

        override fun start() = load()
        override fun refresh() = load()
        override fun repaint() {
            stamp = ""
            paint()
        }

        fun load() {
            if (!Prefs.epgEnabled) {
                showEmpty("Guide TV désactivé dans les réglages.")
                return
            }
            Bg.task(act, {
                val favs = Db.favorites(src.id, Kind.LIVE)
                (if (favs.size >= 3) favs else (favs + Db.page(src.id, Kind.LIVE, null, null, 0, 14))).distinctBy { it.id }.take(14)
            }) { r ->
                val list = r.getOrNull() ?: return@task
                chans = list
                if (list.isEmpty()) {
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
                var pending = list.size
                for (ch in list) Epg.prefetch(api, ch.id) { l ->
                    val p = l.filter { it.start in (t - 25 * 60)..(t + 30 * 60) }.minByOrNull { kotlin.math.abs(it.start - t) }
                        ?: l.firstOrNull { it.start <= t && t < it.end }
                    if (p != null) found[ch.id] = p
                    pending--
                    if (pending % 4 == 0 || pending == 0) paint()
                }
            }
        }

        fun paint() {
            if (chans.isEmpty()) return
            val list = chans.filter { found.containsKey(it.id) }.take(maxRows)
            val key = list.joinToString { it.id + found[it.id]?.title }
            if (key == stamp) return
            stamp = key
            box.removeAllViews()
            showEmpty(if (list.isEmpty()) "Pas encore de programme du soir dans le guide." else null)
            val fmt = SimpleDateFormat("HH:mm", Locale.FRANCE)
            for (ch in list) {
                val p = found[ch.id] ?: continue
                row(p.title, ch.name, fmt.format(Date(p.start * 1000)), ch.icon) {
                    Ui.play(act, ItemList.of(chans), chans.indexOf(ch).coerceAtLeast(0))
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
        override fun repaint() {
            stamp = ""
            paint(Sport.cached())
        }

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
            val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            val today = all.filter { m ->
                m.live || (Calendar.getInstance().apply { timeInMillis = m.start }.get(Calendar.DAY_OF_YEAR) == day)
            }
            val list = (today.filter { it.live }.sortedByDescending { Sport.trend(it) } +
                today.filter { !it.live && !it.finished }.sortedBy { it.start } +
                today.filter { it.finished }.sortedByDescending { it.start }).take(maxRows)
            dot.visibility = if (list.any { it.live }) View.VISIBLE else View.GONE
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

    /**
     * Cotes en direct : les matchs du moment qui valent le coup (exploits en cours, matchs serrés, tes équipes)
     * avec leur barre de pronostic. OK ouvre la fiche des cotes, d'où l'on lance le match.
     * Les cotes ne sont redemandées que si le widget est sur la page affichée (budget du mois).
     */
    private inner class OddsWidget(spec: Spec) : Panel(spec, "Cotes en direct") {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var maxRows = 3
        var wide = false
        var big = false
        var chans: List<Item> = emptyList()
        var shown: List<Sport.Match> = emptyList()
        private val rows = ArrayList<OddsRow>()
        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                load()
                Bg.main.postDelayed(this, 60_000)
            }
        }

        init {
            body.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        override fun design(size: Int) = 226

        override fun sized(size: Int, contentH: Int) {
            val w = size >= 2
            val n = ((contentH - 41) / (if (w) 50 else 58)).coerceIn(1, 6)
            if (n != maxRows || w != wide || (size == 3) != big) {
                maxRows = n
                wide = w
                big = size == 3
                rows.clear()
                box.removeAllViews()
                paint(Sport.cached())
            }
        }

        override fun start() = paint(Sport.cached())
        override fun refresh() = load()
        override fun resume() {
            Bg.main.removeCallbacks(tick)
            Bg.main.post(tick)
        }

        override fun pause() {
            Bg.main.removeCallbacks(tick)
        }

        fun load() {
            val onScreen = !editing && pageOfWidget(this) == page
            Bg.task(act, {
                if (chans.isEmpty()) chans = SportChannels.candidates(src.id)
                val all = Sport.get(60_000L)
                if (onScreen && OddsApi.refresh(all)) Sport.reattachOdds() else all
            }) { r -> r.getOrNull()?.let { paint(it) } }
        }

        fun pick(all: List<Sport.Match>): List<Sport.Match> {
            val now = System.currentTimeMillis()
            val withOdds = all.filter { !it.finished && OddsMath.probs(it.odds) != null && (it.live || it.start - now < 24 * 3_600_000L) }
            val live = withOdds.filter { it.live }
                .sortedWith(compareByDescending<Sport.Match> { OddsMath.tag(it) == OddsMath.Tag.UPSET }.thenByDescending { Sport.trend(it) })
            val soon = withOdds.filter { !it.live }
                .sortedWith(compareByDescending<Sport.Match> { Sport.involvesFollowed(it) }.thenBy { it.start })
            return (live + soon).take(maxRows)
        }

        fun emptyMsg(all: List<Sport.Match>): String = when {
            all.isEmpty() -> "Chargement des matchs…"
            Prefs.oddsKey.isBlank() -> "Pas de cotes pour l'instant. Ajoute ta clé The Odds API (Réglages, Sport) pour avoir celles de Winamax, Betclic, Unibet, PMU et NetBet."
            OddsApi.problem == "key" -> "Clé The Odds API refusée : vérifie-la dans Réglages, Sport."
            OddsBudget.exhaustedNow() -> "Quota de cotes du mois atteint : elles reviennent le 1er ${OddsBudget.nextMonthName()}."
            else -> "Pas de match avec des cotes d'ici demain."
        }

        fun paint(all: List<Sport.Match>) {
            if (!Prefs.oddsEnabled) {
                for (r in rows) r.view.visibility = View.GONE
                dot.visibility = View.GONE
                hint.text = ""
                showEmpty("Active les cotes dans Réglages, rubrique Sport.")
                return
            }
            val list = pick(all)
            shown = list
            dot.visibility = if (list.any { it.live }) View.VISIBLE else View.GONE
            val at = list.mapNotNull { it.odds?.at?.takeIf { t -> t > 0L } }.maxOrNull()
            hint.text = at?.let { OddsMath.age(it) } ?: ""
            showEmpty(if (list.isEmpty()) emptyMsg(all) else null)
            // Les lignes sont réutilisées : celle qui a le focus le garde pendant les mises à jour
            while (rows.size < list.size) rows.add(OddsRow().also { box.addView(it.view) })
            for ((i, r) in rows.withIndex()) {
                val m = list.getOrNull(i)
                if (m == null) {
                    if (r.view.hasFocus()) rows.firstOrNull()?.view?.takeIf { it !== r.view && it.visibility == View.VISIBLE }?.requestFocus()
                    r.view.visibility = View.GONE
                } else {
                    r.view.visibility = View.VISIBLE
                    r.bind(m)
                }
            }
        }

        /** Une ligne : équipes, score ou heure, étiquette, barre de pronostic (et meilleures cotes en grand). */
        inner class OddsRow {
            val view: LinearLayout
            val teams: TextView
            val time: TextView
            val sub: TextView?
            val tag: TextView
            val bar: OddsBarView
            val cells: List<TextView>
            var match: Sport.Match? = null

            init {
                val wideRow = wide
                view = LinearLayout(act).apply {
                    orientation = if (wideRow) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.list_row_bg)
                    isFocusable = true
                    isClickable = true
                    setPadding(px(10), px(5), px(10), px(5))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(if (wideRow) 44 else 52)).apply { topMargin = px(6) }
                    setOnFocusChangeListener { v, has -> Fx.focus(v, has, 1.03f) }
                    setOnClickListener { match?.let { m -> openOdds(m) } }
                }
                teams = TextView(act, null, 0, R.style.Medium).apply {
                    textSize = 12f
                    setSingleLine()
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                time = TextView(act, null, 0, R.style.Txt).apply {
                    textSize = 11f
                    fontFeatureSettings = "tnum"
                    setSingleLine()
                    setTextColor(act.getColor(R.color.text_dim))
                }
                tag = TextView(act, null, 0, R.style.Semi).apply {
                    textSize = 9f
                    setTextColor(act.getColor(R.color.accent))
                    setBackgroundResource(R.drawable.tag_pill_bg)
                    setPadding(px(6), px(1), px(6), px(1))
                    setSingleLine()
                    visibility = View.GONE
                }
                bar = OddsBarView(act).apply { textSp = 9f }
                if (wideRow) {
                    view.addView(time, LinearLayout.LayoutParams(px(58), ViewGroup.LayoutParams.WRAP_CONTENT))
                    val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                    col.addView(teams)
                    val league = TextView(act, null, 0, R.style.Txt).apply {
                        textSize = 10f
                        setSingleLine()
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(act.getColor(R.color.text_faint))
                    }
                    col.addView(league)
                    sub = league
                    view.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    view.addView(tag, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = px(6)
                        marginEnd = px(10)
                    })
                    view.addView(bar, LinearLayout.LayoutParams(px(if (big) 190 else 150), ViewGroup.LayoutParams.WRAP_CONTENT))
                    val cs = ArrayList<TextView>()
                    if (big) {
                        for (k in 0 until 3) {
                            val c = TextView(act, null, 0, R.style.Txt).apply {
                                textSize = 11f
                                gravity = Gravity.END
                                fontFeatureSettings = "tnum"
                                setSingleLine()
                            }
                            view.addView(c, LinearLayout.LayoutParams(px(44), ViewGroup.LayoutParams.WRAP_CONTENT))
                            cs.add(c)
                        }
                    }
                    cells = cs
                } else {
                    sub = null
                    val top = LinearLayout(act).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    top.addView(teams, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    top.addView(tag, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = px(5)
                    })
                    top.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = px(6)
                    })
                    view.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    view.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = px(4)
                    })
                    cells = emptyList()
                }
            }

            fun bind(m: Sport.Match) {
                match = m
                val score = "${m.home.score ?: 0} – ${m.away.score ?: 0}"
                teams.text = if (m.live) "${m.home.short} $score ${m.away.short}" else "${m.home.short} – ${m.away.short}"
                time.text = when {
                    m.live -> m.status
                    else -> SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(m.start))
                }
                time.setTextColor(act.getColor(if (m.live) R.color.danger else R.color.text_dim))
                sub?.text = m.league.label
                OddsTag.bind(tag, OddsMath.tag(m), short = true)
                val p = OddsMath.probs(m.odds)
                val (ch, ca) = OddsMath.colors(m)
                bar.set(p, ch, ca, m.home.abbr, m.away.abbr)
                val o = m.odds
                if (cells.isNotEmpty() && o != null && p != null) {
                    val (bh, bd, ba) = OddsMath.best(o)
                    val v = listOf(bh, bd, ba)
                    val fav = if (p.home >= p.away) 0 else 2
                    for ((k, c) in cells.withIndex()) {
                        c.text = if (k == 1 && v[1] == null) "" else OddsMath.fmt(v[k])
                        c.setTextColor(act.getColor(if (k == fav) R.color.accent else R.color.text))
                    }
                }
            }
        }

        fun openOdds(m: Sport.Match) {
            OddsSheet.show(act, m, if (m.finished) null else ({ MatchFinder.watch(act, src.id, m, chans) { openSport() } }))
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

        override fun design(size: Int) = 146
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
