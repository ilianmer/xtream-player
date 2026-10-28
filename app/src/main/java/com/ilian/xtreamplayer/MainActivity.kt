package com.ilian.xtreamplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : Activity() {

    private lateinit var src: Source
    private lateinit var api: XtreamApi
    private lateinit var tabs: List<TextView>
    private lateinit var rvCategories: RecyclerView
    private lateinit var rvItems: RecyclerView
    private lateinit var tvStatus: TextView
    private lateinit var etSearch: EditText

    private val catAdapter = CategoryAdapter(R.layout.item_category, ::onCategoryFocus, ::onCategoryClick)
    private val itemAdapter = ItemAdapter(::onItemClick, ::onItemLongClick)

    private var kind = Kind.LIVE
    private var groups: List<Group> = emptyList()
    private var focusedCategory = -1
    private val selectFocusedCategory = Runnable {
        if (focusedCategory != catAdapter.selected) selectCategory(focusedCategory)
    }
    private val applySearch = Runnable { reloadItems(resetScroll = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Session.source()
        val a = Session.api()
        if (s == null || a == null) {
            Ui.restart(this, forceSync = false)
            return
        }
        src = s
        api = a
        setContentView(R.layout.activity_main)

        tabs = listOf(findViewById(R.id.tabLive), findViewById(R.id.tabMovies), findViewById(R.id.tabSeries))
        rvCategories = findViewById(R.id.rvCategories)
        rvItems = findViewById(R.id.rvItems)
        tvStatus = findViewById(R.id.tvStatus)
        etSearch = findViewById(R.id.etSearch)
        findViewById<TextView>(R.id.tvSource).text = src.name

        tabs[0].setOnClickListener { switchKind(Kind.LIVE) }
        tabs[1].setOnClickListener { switchKind(Kind.MOVIE) }
        tabs[2].setOnClickListener { switchKind(Kind.SERIES) }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        rvCategories.layoutManager = LinearLayoutManager(this)
        rvCategories.adapter = catAdapter
        rvCategories.itemAnimator = null

        itemAdapter.sourceId = src.id
        rvItems.adapter = itemAdapter
        rvItems.itemAnimator = null

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                Bg.main.removeCallbacks(applySearch)
                Bg.main.postDelayed(applySearch, 400)
            }
        })

        switchKind(Kind.LIVE)
    }

    override fun onRestart() {
        super.onRestart()
        // Retour du lecteur ou des réglages : listes spéciales, masquage et fusion ont pu changer.
        val key = groups.getOrNull(catAdapter.selected)?.key
        groups = buildGroups()
        catAdapter.items = groups.map { Category(it.key, it.name) }
        val i = groups.indexOfFirst { it.key == key }.let { if (it >= 0) it else defaultIndex() }
        catAdapter.selected = i
        itemAdapter.epgApi = if (kind == Kind.LIVE && Prefs.epgEnabled && Prefs.epgPreload) api else null
        reloadItems(resetScroll = false)
    }

    private fun defaultIndex(): Int {
        val i = groups.indexOfFirst { it.key.startsWith("c:") || it.key.startsWith("m:") }
        return if (i >= 0) i else groups.indexOfFirst { it.key == ALL }
    }

    private fun buildGroups(): List<Group> {
        val g = ArrayList<Group>()
        g.add(Group(FAV, "★  Favoris", emptyList()))
        if (kind == Kind.MOVIE) {
            if (Db.progressList(src.id, Kind.MOVIE, false).isNotEmpty()) g.add(Group(RESUME, "Reprendre", emptyList()))
            if (Prefs.showRewatch && Db.progressList(src.id, Kind.MOVIE, true).isNotEmpty()) g.add(Group(REWATCH, "Revoir", emptyList()))
        }
        g.add(Group(ALL, "Tout", emptyList()))
        g.addAll(Catalog.groups(src.id, kind))
        return g
    }

    private fun switchKind(k: Kind) {
        kind = k
        tabs.forEachIndexed { i, t -> t.isSelected = i == k.ordinal }
        rvItems.layoutManager = GridLayoutManager(this, if (k == Kind.LIVE) 3 else 6)
        itemAdapter.viewKind = k
        itemAdapter.epgApi = if (k == Kind.LIVE && Prefs.epgEnabled && Prefs.epgPreload) api else null
        if (etSearch.text.isNotEmpty()) etSearch.setText("")
        Bg.main.removeCallbacks(applySearch)

        groups = buildGroups()
        catAdapter.items = groups.map { Category(it.key, it.name) }
        val start = defaultIndex()
        selectCategory(start)
        rvCategories.scrollToPosition(start)
        rvCategories.post { rvCategories.findViewHolderForAdapterPosition(start)?.itemView?.requestFocus() }
    }

    private fun onCategoryFocus(position: Int) {
        // On attend un court instant pour ne pas charger chaque catégorie survolée.
        focusedCategory = position
        Bg.main.removeCallbacks(selectFocusedCategory)
        Bg.main.postDelayed(selectFocusedCategory, 350)
    }

    private fun onCategoryClick(position: Int) {
        Bg.main.removeCallbacks(selectFocusedCategory)
        if (position != catAdapter.selected) {
            selectCategory(position)
        } else if (itemAdapter.itemCount > 0) {
            rvItems.post { (rvItems.layoutManager?.findViewByPosition(0) ?: rvItems.getChildAt(0))?.requestFocus() }
        }
    }

    private fun selectCategory(position: Int) {
        if (position !in groups.indices) return
        catAdapter.selected = position
        reloadItems(resetScroll = true)
    }

    private fun reloadItems(resetScroll: Boolean) {
        val g = groups.getOrNull(catAdapter.selected) ?: return
        val q = etSearch.text.toString().trim().ifBlank { null }
        val s = src.id
        val k = kind
        fun filtered(l: List<Item>): ItemList {
            if (q == null) return ItemList.of(l)
            val n = normalize(q)
            return ItemList.of(l.filter { normalize(it.name).contains(n) })
        }
        val list = when (g.key) {
            FAV -> filtered(Db.favorites(s, k))
            RESUME -> filtered(Db.progressList(s, k, false))
            REWATCH -> filtered(Db.progressList(s, k, true))
            ALL -> ItemList(Db.count(s, k, null, q)) { o, l -> Db.page(s, k, null, q, o, l) }
            else -> ItemList(Db.count(s, k, g.catIds, q)) { o, l -> Db.page(s, k, g.catIds, q, o, l) }
        }
        itemAdapter.list = list
        if (resetScroll) rvItems.scrollToPosition(0)
        setStatus(
            when {
                list.size > 0 -> null
                q != null -> "Aucun résultat pour « $q »"
                g.key == FAV -> "Aucun favori ici.\nFais un appui long sur OK sur un élément pour l'ajouter."
                Db.countAll(s, k) == 0 -> "Aucun contenu dans cette source.\nVa dans Réglages > Sources IPTV > Rafraîchir."
                else -> "Rien dans cette catégorie."
            }
        )
    }

    private fun setStatus(text: String?) {
        tvStatus.text = text ?: ""
        tvStatus.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun onItemClick(position: Int) {
        val item = itemAdapter.list[position] ?: return
        when (item.kind) {
            Kind.LIVE -> Ui.play(this, itemAdapter.list, position)
            Kind.MOVIE -> startActivity(Intent(this, MovieActivity::class.java).putExtra(EXTRA_ITEM, item.toJson().toString()))
            Kind.SERIES -> startActivity(Intent(this, SeriesActivity::class.java).putExtra(EXTRA_ITEM, item.toJson().toString()))
            Kind.EPISODE -> Unit
        }
    }

    private fun onItemLongClick(position: Int) {
        val item = itemAdapter.list[position] ?: return
        val added = Db.toggleFav(src.id, item)
        Ui.toast(this, if (added) "Ajouté aux favoris" else "Retiré des favoris")
        if (groups.getOrNull(catAdapter.selected)?.key == FAV) reloadItems(resetScroll = false)
        else itemAdapter.notifyItemChanged(position)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Retour depuis la grille : on revient sur les catégories au lieu de quitter.
        if (rvItems.hasFocus()) {
            val v = rvCategories.findViewHolderForAdapterPosition(catAdapter.selected)?.itemView
            if (v != null) v.requestFocus() else rvCategories.requestFocus()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        Bg.main.removeCallbacks(selectFocusedCategory)
        Bg.main.removeCallbacks(applySearch)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ITEM = "item"
        private const val FAV = "__fav"
        private const val RESUME = "__resume"
        private const val REWATCH = "__rewatch"
        private const val ALL = "__all"
    }
}
