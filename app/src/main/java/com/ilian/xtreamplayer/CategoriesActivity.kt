package com.ilian.xtreamplayer

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * « Catégories affichées » du profil en cours : boutons Tout cocher / Tout décocher / Selon mes langues
 * toujours visibles à gauche, la liste à droite (deux colonnes). Chaque OK est enregistré aussitôt.
 */
class CategoriesActivity : BaseActivity() {

    private class Row(val cat: Category, var visible: Boolean, val count: Int)

    private lateinit var src: Source
    private lateinit var rv: RecyclerView
    private lateinit var tvCount: TextView
    private lateinit var tvKind: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tabs: Map<Kind, TextView>

    private var kind = Kind.LIVE
    private var rows: MutableList<Row> = ArrayList()
    private lateinit var tvHint: TextView
    private lateinit var btnOrder: TextView

    /** Rangement : OK prend une catégorie, les flèches la déplacent, OK la pose. */
    private var ordering = false
    private var tabPill: TabPill? = null
    private var held = -1
    private val adapter = RowAdapter()
    private var pendingKind = Kind.LIVE
    private val switchKind = Runnable { if (pendingKind != kind) load(pendingKind) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        src = Session.source() ?: run {
            finish()
            return
        }
        setContentView(R.layout.activity_categories)
        rv = findViewById(R.id.rvCats)
        tvCount = findViewById(R.id.tvCount)
        tvKind = findViewById(R.id.tvKind)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvHint = findViewById(R.id.tvHint)
        btnOrder = findViewById(R.id.btnOrder)
        val p = Session.profile()
        findViewById<TextView>(R.id.tvProfile).text = p?.let { "Profil ${it.name} · ${Profiles.langsLabel(it)}" } ?: ""

        tabs = mapOf(
            Kind.LIVE to findViewById(R.id.tabLive),
            Kind.MOVIE to findViewById(R.id.tabMovie),
            Kind.SERIES to findViewById(R.id.tabSeries)
        )
        val pill = TabPill(tabs.getValue(Kind.LIVE).parent as android.view.ViewGroup)
        tabPill = pill
        tabs.values.forEach { pill.adopt(it) }
        for ((k, v) in tabs) {
            // Même logique que la barre du haut : on change en passant dessus.
            v.setOnFocusChangeListener { _, has ->
                Bg.main.removeCallbacks(switchKind)
                if (has && k != kind) {
                    pendingKind = k
                    Bg.main.postDelayed(switchKind, 350)
                }
            }
            v.setOnClickListener {
                Bg.main.removeCallbacks(switchKind)
                if (k != kind) load(k)
                rv.post { focusList(0) }
            }
        }

        rv.layoutManager = GridLayoutManager(this, 2)
        rv.adapter = adapter
        rv.itemAnimator = null

        findViewById<View>(R.id.btnAll).setOnClickListener { setAll(true) }
        findViewById<View>(R.id.btnNone).setOnClickListener { setAll(false) }
        findViewById<View>(R.id.btnLang).setOnClickListener { byLanguage() }
        btnOrder.setOnClickListener {
            if (ordering) {
                stopOrdering()
                return@setOnClickListener
            }
            Ui.choose(this, "Ordre des catégories", listOf("Ranger moi-même", "Remettre l'ordre du fournisseur")) { w ->
                if (w == 0) startOrdering() else {
                    Db.setCatOrder(src.id, kind, null)
                    load(kind)
                    Ui.toast(this, "Ordre du fournisseur rétabli.")
                }
            }
        }
        findViewById<View>(R.id.btnDone).setOnClickListener {
            if (ordering) stopOrdering()
            Ui.toast(this, "Catégories enregistrées.")
            finish()
        }
        // Colonne de gauche : haut / bas restent dans la colonne (on ne passe plus par la liste de droite)
        val chain = intArrayOf(R.id.btnAll, R.id.btnNone, R.id.btnLang, R.id.btnOrder, R.id.btnDone).map { findViewById<View>(it) }
        chain.forEachIndexed { i, v ->
            if (i > 0) v.nextFocusUpId = chain[i - 1].id
            v.nextFocusDownId = if (i < chain.size - 1) chain[i + 1].id else v.id
        }
        for (t in tabs.values) t.nextFocusDownId = R.id.btnAll
        for (id in intArrayOf(R.id.btnAll, R.id.btnNone, R.id.btnLang, R.id.btnOrder, R.id.btnDone)) {
            findViewById<View>(id).setOnFocusChangeListener { v, has -> Fx.focus(v, has, 1.04f) }
        }

        load(Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: Kind.LIVE.name))
        tabs[kind]?.post { tabs[kind]?.requestFocus() }
    }

    private fun load(k: Kind) {
        if (ordering) stopOrdering(save = held >= 0)
        kind = k
        for ((kk, v) in tabs) v.isSelected = kk == k
        tabs[k]?.let { v -> v.post { tabPill?.select(v) } }
        tabs[k]?.let { findViewById<View>(R.id.btnAll).nextFocusUpId = it.id }
        tvKind.text = when (k) {
            Kind.LIVE -> "Chaînes en direct"
            Kind.MOVIE -> "Films"
            else -> "Séries"
        }
        val counts = Db.countsByCat(src.id, k)
        rows = Db.categories(src.id, k).map { (c, hidden) -> Row(c, !hidden, counts[c.id] ?: 0) }.toMutableList()
        adapter.notifyDataSetChanged()
        rv.scrollToPosition(0)
        tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        paintCount()
    }

    private fun paintCount() {
        val on = rows.count { it.visible }
        tvCount.text = if (rows.isEmpty()) "" else "$on affichée${if (on > 1) "s" else ""} sur ${rows.size}"
    }

    private fun setAll(visible: Boolean) {
        if (rows.isEmpty()) return
        Db.setHidden(src.id, kind, if (visible) emptySet() else rows.map { it.cat.id }.toSet())
        rows.forEach { it.visible = visible }
        adapter.notifyDataSetChanged()
        paintCount()
        Ui.toast(this, if (visible) "Toutes les catégories sont affichées." else "Tout est décoché : coche maintenant ce que tu veux voir.")
    }

    /** Applique le préréglage de langue du profil à cet onglet (ou propose d'en choisir un). */
    private fun byLanguage() {
        val p = Session.profile() ?: return
        if (p.langSet.isEmpty()) {
            Profiles.chooseLangs(this, p) {
                findViewById<TextView>(R.id.tvProfile).text = Session.profile()?.let { "Profil ${it.name} · ${Profiles.langsLabel(it)}" } ?: ""
                load(kind)
            }
            return
        }
        val hidden = rows.filter { !Langs.keep(it.cat.name, p.langSet) }.map { it.cat.id }.toSet()
        Db.setHidden(src.id, kind, hidden)
        load(kind)
        Ui.toast(this, "Seulement les catégories marquées : ${p.langSet.joinToString(", ") { Langs.label(it) }}")
    }

    // ---------- Rangement ----------

    private fun startOrdering() {
        if (rows.isEmpty()) return
        ordering = true
        held = -1
        btnOrder.text = "Terminer le rangement"
        tvHint.text = "OK : prendre / poser · flèches : déplacer"
        adapter.notifyDataSetChanged()
        Ui.toast(this, "OK sur une catégorie pour la prendre, flèches pour la déplacer, OK pour la poser.")
        rv.post { focusList(0) }
    }

    private fun stopOrdering(save: Boolean = true) {
        if (held >= 0 && save) saveOrder()
        ordering = false
        held = -1
        btnOrder.text = "Changer l'ordre"
        tvHint.text = "OK : afficher / masquer"
        adapter.notifyDataSetChanged()
    }

    private fun saveOrder() {
        Db.setCatOrder(src.id, kind, rows.map { it.cat.id })
    }

    private fun move(delta: Int) {
        val from = held
        if (from < 0) return
        val to = (from + delta).coerceIn(0, rows.size - 1)
        if (to == from) {
            rv.findViewHolderForAdapterPosition(from)?.itemView?.let { Fx.bump(it, if (delta < -1 || delta > 1) 0 else delta, if (delta < -1) -1 else if (delta > 1) 1 else 0) }
            return
        }
        rows.add(to, rows.removeAt(from))
        held = to
        adapter.notifyItemMoved(from, to)
        adapter.notifyItemRangeChanged(minOf(from, to), kotlin.math.abs(to - from) + 1)
        rv.scrollToPosition(to)
        rv.post { rv.findViewHolderForAdapterPosition(to)?.itemView?.requestFocus() }
    }

    private fun toggle(pos: Int) {
        if (ordering) {
            if (held < 0) {
                held = pos
                adapter.notifyItemChanged(pos)
            } else {
                val was = held
                held = -1
                saveOrder()
                adapter.notifyItemChanged(was)
                Ui.toast(this, "Ordre enregistré.")
            }
            return
        }
        val r = rows.getOrNull(pos) ?: return
        r.visible = !r.visible
        Db.setOneHidden(src.id, kind, r.cat.id, !r.visible)
        adapter.notifyItemChanged(pos)
        paintCount()
    }

    private fun focusList(pos: Int) {
        if (rows.isEmpty()) return
        Fx.focusAt(rv, pos.coerceIn(0, rows.size - 1))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ordering && held >= 0) {
            val delta = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> -2
                KeyEvent.KEYCODE_DPAD_DOWN -> 2
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> 0
            }
            if (delta != 0) {
                if (event.action == KeyEvent.ACTION_DOWN) move(delta)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) toggle(held)
                return true
            }
        }
        if (ordering && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                stopOrdering()
                btnOrder.requestFocus()
            }
            return true
        }
        // Retour depuis la liste : on remonte d'abord sur l'onglet en cours.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && rv.hasFocus()) {
            tabs[kind]?.requestFocus()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && rv.hasFocus()) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        Bg.main.removeCallbacks(switchKind)
        super.onDestroy()
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val check: View = v.findViewById(R.id.check)
            val name: TextView = v.findViewById(R.id.name)
            val count: TextView = v.findViewById(R.id.count)
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_cat_check, parent, false)
            val h = VH(v)
            v.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) toggle(p)
            }
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            h.name.text = r.cat.name
            h.count.text = if (r.count > 0) Sync.fmt(r.count) else ""
            h.check.setBackgroundResource(if (r.visible) R.drawable.check_badge else R.drawable.check_empty)
            h.name.alpha = if (r.visible) 1f else 0.55f
            if (ordering) {
                val isHeld = position == held
                h.count.text = "${position + 1}"
                h.name.text = if (isHeld) "↕  ${r.cat.name}" else r.cat.name
                h.name.setTextColor(getColor(if (isHeld) R.color.accent else R.color.text))
                h.itemView.alpha = if (held >= 0 && !isHeld) 0.7f else 1f
            } else {
                h.name.setTextColor(getColor(R.color.text))
                h.itemView.alpha = 1f
            }
        }
    }

    companion object {
        const val EXTRA_KIND = "kind"
    }
}
