package com.ilian.xtreamplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : Activity() {

    enum class Tab { HOME, LIVE, MOVIE, SERIES, FAV, DL }

    private lateinit var src: Source
    private lateinit var api: XtreamApi
    private lateinit var tabViews: Map<Tab, TextView>
    private lateinit var root: View
    private lateinit var bgImage: ImageView
    private lateinit var homeView: View
    private lateinit var catalogView: View
    private lateinit var rvCategories: RecyclerView
    private lateinit var rvItems: RecyclerView
    private lateinit var tvStatus: TextView
    private lateinit var etSearch: EditText
    private lateinit var home: HomeScreen
    private lateinit var grid: GridLayoutManager

    private val catAdapter = CategoryAdapter(R.layout.item_category, ::onCategoryFocus, ::onCategoryClick)
    private val itemAdapter = ItemAdapter(::onItemClick, ::onItemLongClick)

    private var tab = Tab.HOME
    private var groups: List<Group> = emptyList()
    private var dl: List<Downloads.Status> = emptyList()
    private var focusedCategory = -1
    private var ignoreSearch = false
    private val selectFocusedCategory = Runnable {
        if (focusedCategory != catAdapter.selected) selectCategory(focusedCategory)
    }
    private val applySearch = Runnable { reloadItems(resetScroll = true) }
    private val refreshDownloads = object : Runnable {
        override fun run() {
            if (tab != Tab.DL || isDestroyed) return
            val now = Downloads.statuses(this@MainActivity)
            if (now.map { it.label } != dl.map { it.label }) reloadItems(resetScroll = false)
            Bg.main.postDelayed(this, 3_000)
        }
    }

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

        root = findViewById(R.id.root)
        bgImage = findViewById(R.id.bgImage)
        homeView = findViewById(R.id.homeView)
        catalogView = findViewById(R.id.catalogView)
        rvCategories = findViewById(R.id.rvCategories)
        rvItems = findViewById(R.id.rvItems)
        tvStatus = findViewById(R.id.tvStatus)
        etSearch = findViewById(R.id.etSearch)
        findViewById<TextView>(R.id.tvSource).text = src.name

        tabViews = mapOf(
            Tab.HOME to findViewById(R.id.tabHome),
            Tab.LIVE to findViewById(R.id.tabLive),
            Tab.MOVIE to findViewById(R.id.tabMovies),
            Tab.SERIES to findViewById(R.id.tabSeries),
            Tab.FAV to findViewById(R.id.tabFav),
            Tab.DL to findViewById(R.id.tabDl)
        )
        for ((t, v) in tabViews) v.setOnClickListener { showTab(t) }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.sourceChip).setOnClickListener { chooseSource() }

        rvCategories.layoutManager = LinearLayoutManager(this)
        rvCategories.adapter = catAdapter
        rvCategories.itemAnimator = null

        grid = GridLayoutManager(this, 3)
        rvItems.layoutManager = grid
        itemAdapter.sourceId = src.id
        rvItems.adapter = itemAdapter
        rvItems.itemAnimator = null

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (ignoreSearch) return
                // Une recherche depuis l'accueil, les favoris ou les téléchargements part dans tous les films.
                if (!s.isNullOrBlank() && (tab == Tab.HOME || tab == Tab.FAV || tab == Tab.DL)) {
                    showTab(Tab.MOVIE, groupKey = ALL, keepSearch = true)
                }
                Bg.main.removeCallbacks(applySearch)
                Bg.main.postDelayed(applySearch, 400)
            }
        })

        home = HomeScreen(this, homeView, src, api, bgImage) { kind, key ->
            showTab(
                when (kind) {
                    Kind.LIVE -> Tab.LIVE
                    Kind.SERIES -> Tab.SERIES
                    else -> Tab.MOVIE
                },
                groupKey = key
            )
        }
        home.start()
        showTab(Tab.HOME)
        homeView.post { home.focusDefault() }
    }

    override fun onResume() {
        super.onResume()
        if (!::home.isInitialized) return
        if (tab == Tab.HOME) home.resume()
        if (tab == Tab.DL) {
            Bg.main.removeCallbacks(refreshDownloads)
            Bg.main.postDelayed(refreshDownloads, 3_000)
        }
    }

    override fun onPause() {
        if (::home.isInitialized) home.pause()
        Bg.main.removeCallbacks(refreshDownloads)
        super.onPause()
    }

    override fun onRestart() {
        super.onRestart()
        // Retour du lecteur, d'une fiche ou des réglages : favoris, reprise et masquage ont pu changer.
        if (tab == Tab.HOME) {
            home.refresh()
            return
        }
        val key = groups.getOrNull(catAdapter.selected)?.key
        groups = buildGroups()
        catAdapter.items = groups.map { Category(it.key, it.name) }
        val i = groups.indexOfFirst { it.key == key }.let { if (it >= 0) it else defaultIndex() }
        catAdapter.selected = i
        reloadItems(resetScroll = false)
    }

    // ---------- Onglets ----------

    private fun showTab(t: Tab, groupKey: String? = null, keepSearch: Boolean = false) {
        val changed = t != tab || catalogView.visibility != View.VISIBLE
        tab = t
        tabViews.forEach { (k, v) -> v.isSelected = k == t }
        if (!keepSearch && etSearch.text.isNotEmpty()) {
            ignoreSearch = true
            etSearch.setText("")
            ignoreSearch = false
        }
        Bg.main.removeCallbacks(applySearch)
        Bg.main.removeCallbacks(refreshDownloads)

        if (t == Tab.HOME) {
            catalogView.visibility = View.GONE
            homeView.visibility = View.VISIBLE
            root.setBackgroundResource(R.drawable.bg_home)
            bgImage.visibility = View.VISIBLE
            home.refresh()
            home.resume()
            return
        }
        home.pause()
        homeView.visibility = View.GONE
        catalogView.visibility = View.VISIBLE
        bgImage.visibility = View.GONE
        root.setBackgroundResource(if (t == Tab.LIVE) R.drawable.bg_live else R.drawable.bg_catalog)

        if (changed || groupKey != null) {
            groups = buildGroups()
            catAdapter.items = groups.map { Category(it.key, it.name) }
        }
        val start = groupKey?.let { k -> groups.indexOfFirst { it.key == k }.takeIf { it >= 0 } } ?: defaultIndex()
        selectCategory(start, force = true)
        rvCategories.scrollToPosition(start)
        if (groupKey != null && !keepSearch) {
            // Arrivée depuis l'accueil : on va directement dans la grille.
            rvItems.post { focusFirstItem() }
        }
        if (t == Tab.DL) Bg.main.postDelayed(refreshDownloads, 3_000)
    }

    private fun kindOfTab(): Kind = when (tab) {
        Tab.LIVE -> Kind.LIVE
        Tab.SERIES -> Kind.SERIES
        else -> Kind.MOVIE
    }

    private fun defaultIndex(): Int {
        val i = groups.indexOfFirst { it.key.startsWith("c:") || it.key.startsWith("m:") }
        return when {
            i >= 0 -> i
            groups.any { it.key == ALL } -> groups.indexOfFirst { it.key == ALL }
            else -> 0
        }
    }

    private fun buildGroups(): List<Group> {
        val g = ArrayList<Group>()
        when (tab) {
            Tab.FAV -> {
                g.add(Group(FAV_LIVE, "Chaînes", emptyList()))
                g.add(Group(FAV_MOVIE, "Films", emptyList()))
                g.add(Group(FAV_SERIES, "Séries", emptyList()))
            }
            Tab.DL -> g.add(Group(DL, "Tous les téléchargements", emptyList()))
            Tab.HOME -> Unit
            else -> {
                val kind = kindOfTab()
                g.add(Group(FAV, "Favoris", emptyList()))
                if (kind == Kind.MOVIE) {
                    if (Db.progressList(src.id, Kind.MOVIE, false).isNotEmpty()) g.add(Group(RESUME, "Reprendre", emptyList()))
                    if (Prefs.showRewatch && Db.progressList(src.id, Kind.MOVIE, true).isNotEmpty()) g.add(Group(REWATCH, "Revoir", emptyList()))
                }
                g.add(Group(ALL, "Tout", emptyList()))
                g.addAll(Catalog.groups(src.id, kind))
            }
        }
        return g
    }

    private fun chooseSource() {
        val all = Db.sources()
        val labels = all.map { if (it.id == src.id) "${it.name}  (active)" else it.name } + "Ajouter une source"
        Ui.choose(this, "Source IPTV", labels) { w ->
            if (w == all.size) {
                startActivity(Intent(this, SourceActivity::class.java))
            } else if (all[w].id != src.id) {
                Prefs.activeSourceId = all[w].id
                Ui.restart(this, forceSync = false)
            }
        }
    }

    // ---------- Catégories et grille ----------

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
            rvItems.post { focusFirstItem() }
        }
    }

    private fun focusFirstItem() {
        (grid.findViewByPosition(0) ?: rvItems.getChildAt(0))?.requestFocus()
    }

    private fun selectCategory(position: Int, force: Boolean = false) {
        if (position !in groups.indices) return
        if (!force && position == catAdapter.selected) return
        catAdapter.selected = position
        reloadItems(resetScroll = true)
    }

    /** Type d'affichage d'une liste : chaînes en lignes, le reste en affiches. */
    private fun viewKindOf(key: String): Kind = when (key) {
        FAV_LIVE -> Kind.LIVE
        FAV_MOVIE, DL -> Kind.MOVIE
        FAV_SERIES -> Kind.SERIES
        else -> kindOfTab()
    }

    private fun reloadItems(resetScroll: Boolean) {
        val g = groups.getOrNull(catAdapter.selected) ?: return
        val q = etSearch.text.toString().trim().ifBlank { null }
        val s = src.id
        val k = kindOfTab()
        fun filtered(l: List<Item>): ItemList {
            if (q == null) return ItemList.of(l)
            val n = normalize(q)
            return ItemList.of(l.filter { normalize(it.name).contains(n) })
        }

        val vk = viewKindOf(g.key)
        itemAdapter.viewKind = vk
        grid.spanCount = if (vk == Kind.LIVE) 3 else 6
        itemAdapter.epgApi = if (vk == Kind.LIVE && Prefs.epgEnabled && Prefs.epgPreload) api else null
        itemAdapter.subtitle = null
        itemAdapter.progress = null

        val list = when (g.key) {
            FAV -> filtered(Db.favorites(s, k))
            FAV_LIVE -> filtered(Db.favorites(s, Kind.LIVE))
            FAV_MOVIE -> filtered(Db.favorites(s, Kind.MOVIE))
            FAV_SERIES -> filtered(Db.favorites(s, Kind.SERIES))
            RESUME -> {
                val l = Db.progressList(s, k, false)
                val map = Db.progressMap(s, k, l.map { it.id })
                itemAdapter.progress = { item -> map[item.id]?.let { p -> if (p.dur > 0) p.pos.toFloat() / p.dur else null } }
                filtered(l)
            }
            REWATCH -> filtered(Db.progressList(s, k, true))
            DL -> {
                dl = Downloads.statuses(this)
                val byKey = dl.associateBy { it.row.item.key }
                itemAdapter.subtitle = { item -> byKey[item.key]?.label }
                filtered(dl.map { it.row.item })
            }
            ALL -> ItemList(Db.count(s, k, null, q)) { o, l -> Db.page(s, k, null, q, o, l) }
            else -> ItemList(Db.count(s, k, g.catIds, q)) { o, l -> Db.page(s, k, g.catIds, q, o, l) }
        }
        itemAdapter.list = list
        if (resetScroll) rvItems.scrollToPosition(0)
        setStatus(
            when {
                list.size > 0 -> null
                q != null -> "Aucun résultat pour « $q »"
                g.key == DL -> "Aucun téléchargement.\nSur la fiche d'un film ou d'un épisode, choisis Télécharger pour le regarder hors ligne."
                g.key == FAV || g.key.startsWith("__fav") -> "Aucun favori ici.\nFais un appui long sur OK sur un élément pour l'ajouter."
                g.key == RESUME || g.key == REWATCH -> "Rien pour l'instant."
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
        if (tab == Tab.DL) {
            onDownloadClick(item)
            return
        }
        when (item.kind) {
            Kind.LIVE -> Ui.play(this, itemAdapter.list, position)
            Kind.MOVIE -> startActivity(Intent(this, MovieActivity::class.java).putExtra(EXTRA_ITEM, item.toJson().toString()))
            Kind.SERIES -> startActivity(Intent(this, SeriesActivity::class.java).putExtra(EXTRA_ITEM, item.toJson().toString()))
            Kind.EPISODE -> Ui.play(this, ItemList.of(listOf(item)), 0)
        }
    }

    private fun onDownloadClick(item: Item) {
        val st = dl.firstOrNull { it.row.item.key == item.key } ?: return
        val playable = st.playable()
        val options = ArrayList<String>()
        if (playable != null) options.add("Lire hors ligne")
        options.add(if (st.finished) "Supprimer le téléchargement" else "Annuler le téléchargement")
        Ui.choose(this, "${item.name}\n${st.label}", options) { w ->
            if (playable != null && w == 0) {
                Ui.play(this, ItemList.of(listOf(playable)), 0)
            } else {
                Ui.confirm(this, "Supprimer ?", "Le fichier sera effacé du stick.", "Supprimer") {
                    Downloads.remove(this, st.row.dmId)
                    reloadItems(resetScroll = false)
                }
            }
        }
    }

    private fun onItemLongClick(position: Int) {
        val item = itemAdapter.list[position] ?: return
        if (tab == Tab.DL) {
            onDownloadClick(item)
            return
        }
        val added = Db.toggleFav(src.id, item)
        Ui.toast(this, if (added) "Ajouté aux favoris" else "Retiré des favoris")
        val key = groups.getOrNull(catAdapter.selected)?.key
        if (key == FAV || key?.startsWith("__fav") == true) reloadItems(resetScroll = false)
        else itemAdapter.notifyItemChanged(position)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val tabView = tabViews[tab]
        when {
            // Grille -> catégories
            catalogView.visibility == View.VISIBLE && rvItems.hasFocus() -> {
                val v = rvCategories.findViewHolderForAdapterPosition(catAdapter.selected)?.itemView
                if (v != null) v.requestFocus() else rvCategories.requestFocus()
            }
            // Contenu -> barre d'onglets
            tabView != null && !findViewById<View>(R.id.topBar).hasFocus() -> tabView.requestFocus()
            // Onglet -> Accueil
            tab != Tab.HOME -> {
                showTab(Tab.HOME)
                tabViews[Tab.HOME]?.requestFocus()
            }
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        Bg.main.removeCallbacks(selectFocusedCategory)
        Bg.main.removeCallbacks(applySearch)
        Bg.main.removeCallbacks(refreshDownloads)
        if (::home.isInitialized) home.pause()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ITEM = "item"
        const val FAV = "__fav"
        const val FAV_LIVE = "__fav_live"
        const val FAV_MOVIE = "__fav_movie"
        const val FAV_SERIES = "__fav_series"
        const val RESUME = "__resume"
        const val REWATCH = "__rewatch"
        const val ALL = "__all"
        const val DL = "__dl"
    }
}
