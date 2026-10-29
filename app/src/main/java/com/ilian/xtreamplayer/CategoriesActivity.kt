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
    private var rows: List<Row> = emptyList()
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
        val p = Session.profile()
        findViewById<TextView>(R.id.tvProfile).text = p?.let { "Profil ${it.name} · ${Profiles.langsLabel(it)}" } ?: ""

        tabs = mapOf(
            Kind.LIVE to findViewById(R.id.tabLive),
            Kind.MOVIE to findViewById(R.id.tabMovie),
            Kind.SERIES to findViewById(R.id.tabSeries)
        )
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
        findViewById<View>(R.id.btnDone).setOnClickListener { finish() }
        for (id in intArrayOf(R.id.btnAll, R.id.btnNone, R.id.btnLang, R.id.btnDone)) {
            findViewById<View>(id).setOnFocusChangeListener { v, has -> Fx.focus(v, has, 1.04f) }
        }

        load(Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: Kind.LIVE.name))
        tabs[kind]?.post { tabs[kind]?.requestFocus() }
    }

    private fun load(k: Kind) {
        kind = k
        for ((kk, v) in tabs) v.isSelected = kk == k
        tvKind.text = when (k) {
            Kind.LIVE -> "Chaînes en direct"
            Kind.MOVIE -> "Films"
            else -> "Séries"
        }
        val counts = Db.countsByCat(src.id, k)
        rows = Db.categories(src.id, k).map { (c, hidden) -> Row(c, !hidden, counts[c.id] ?: 0) }
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
        Ui.toast(this, "Seulement : ${p.langSet.joinToString(", ") { Langs.label(it) }} (+ catégories sans langue précise)")
    }

    private fun toggle(pos: Int) {
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
        }
    }

    companion object {
        const val EXTRA_KIND = "kind"
    }
}
