package com.ilian.xtreamplayer

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : BaseActivity() {

    enum class Tab { HOME, LIVE, SPORT, MOVIE, SERIES, FAV, DL }

    private lateinit var src: Source
    private lateinit var api: XtreamApi
    private lateinit var tabViews: Map<Tab, TextView>
    private lateinit var root: View
    private lateinit var topBar: View
    private lateinit var bgImage: ImageView
    private lateinit var bgScrim: View
    private lateinit var homeView: View
    private lateinit var directView: View
    private lateinit var sportView: View
    private lateinit var moviesView: View
    private lateinit var seriesView: View
    private lateinit var catalogView: View
    private lateinit var catPanel: View
    private lateinit var gridTitle: TextView
    private lateinit var rvCategories: RecyclerView
    private lateinit var rvItems: RecyclerView
    private lateinit var tvStatus: TextView
    private lateinit var grid: GridLayoutManager

    private lateinit var home: HomeScreen
    private lateinit var direct: DirectScreen
    private lateinit var sport: SportScreen
    private lateinit var movies: StreamScreen
    private lateinit var series: StreamScreen

    private val catAdapter = CategoryAdapter(R.layout.item_category, ::onCategoryFocus, ::onCategoryClick)
    private val itemAdapter = ItemAdapter(::onItemClick, ::onItemLongClick)

    private var tab = Tab.HOME
    /** Films et Séries : une catégorie ouverte en pleine page (« Voir tout »). */
    private var gridMode = false
    private var groups: List<Group> = emptyList()
    private var dl: List<Downloads.Status> = emptyList()
    private var focusedCategory = -1
    private var ready = false
    /** Profil et catégories masquées au moment où l'écran a été construit. */
    private var seenProfile = 0L
    private var seenHidden = 0
    private var seenSport = 0
    private var pendingTab: Tab? = null
    private val tabSwitch = Runnable {
        val t = pendingTab ?: return@Runnable
        if (tabViews[t]?.hasFocus() == true && (t != tab || gridMode)) showTab(t)
    }
    private val selectFocusedCategory = Runnable {
        if (focusedCategory != catAdapter.selected) selectCategory(focusedCategory)
    }
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
        topBar = findViewById(R.id.topBar)
        bgImage = findViewById(R.id.bgImage)
        bgScrim = findViewById(R.id.bgScrim)
        homeView = findViewById(R.id.homeView)
        directView = findViewById(R.id.directView)
        sportView = findViewById(R.id.sportView)
        moviesView = findViewById(R.id.moviesView)
        seriesView = findViewById(R.id.seriesView)
        catalogView = findViewById(R.id.catalogView)
        catPanel = findViewById(R.id.catPanel)
        gridTitle = findViewById(R.id.gridTitle)
        rvCategories = findViewById(R.id.rvCategories)
        rvItems = findViewById(R.id.rvItems)
        tvStatus = findViewById(R.id.tvStatus)
        paintProfile()

        tabViews = mapOf(
            Tab.HOME to findViewById(R.id.tabHome),
            Tab.LIVE to findViewById(R.id.tabLive),
            Tab.SPORT to findViewById(R.id.tabSport),
            Tab.MOVIE to findViewById(R.id.tabMovies),
            Tab.SERIES to findViewById(R.id.tabSeries),
            Tab.FAV to findViewById(R.id.tabFav),
            Tab.DL to findViewById(R.id.tabDl)
        )
        for ((t, v) in tabViews) {
            v.setOnClickListener { if (t != tab || gridMode) showTab(t) }
            // Comme sur les grandes plateformes : on change d'onglet en passant dessus (après un court instant).
            v.setOnFocusChangeListener { _, has ->
                Bg.main.removeCallbacks(tabSwitch)
                if (has && (t != tab || gridMode)) {
                    pendingTab = t
                    Bg.main.postDelayed(tabSwitch, 450)
                }
            }
        }
        // En remontant du contenu vers la barre d'onglets, on arrive toujours sur l'onglet en cours
        // (et pas sur l'onglet voisin, qui changerait l'écran tout seul).
        val tabsBar = findViewById<View>(R.id.tabsBar)
        root.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            if (ready && new != null && new.parent === tabsBar && old != null && !isInside(old, topBar)) {
                val cur = tabViews[tab]
                if (cur != null && cur !== new && cur.visibility == View.VISIBLE && !gridMode) cur.requestFocus()
            }
        }
        if (!Features.DOWNLOADS) {
            tabViews[Tab.DL]?.visibility = View.GONE
            findViewById<View>(R.id.tabDlGap).visibility = View.GONE
        }
        if (!Prefs.showSport) {
            tabViews[Tab.SPORT]?.visibility = View.GONE
            findViewById<View>(R.id.tabSportGap).visibility = View.GONE
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnSearch).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<View>(R.id.sourceChip).setOnClickListener { profileMenu() }

        rvCategories.layoutManager = LinearLayoutManager(this)
        rvCategories.adapter = catAdapter
        rvCategories.itemAnimator = null

        grid = GridLayoutManager(this, 3)
        rvItems.layoutManager = grid
        itemAdapter.sourceId = src.id
        rvItems.adapter = itemAdapter
        rvItems.itemAnimator = null

        home = HomeScreen(this, homeView, src, api, bgImage) { showTab(Tab.SPORT) }
        direct = DirectScreen(this, directView, src, api)
        sport = SportScreen(this, sportView, src, api) { res -> if (tab == Tab.SPORT) Backgrounds.set(root, res) }
        movies = StreamScreen(this, moviesView, src, api, Kind.MOVIE, bgImage) { key, title -> openGrid(Tab.MOVIE, key, title) }
        series = StreamScreen(this, seriesView, src, api, Kind.SERIES, bgImage) { key, title -> openGrid(Tab.SERIES, key, title) }
        ready = true

        seenProfile = Db.profile
        seenHidden = Db.hiddenVersion
        seenSport = SportPrefs.version
        home.start()
        val start = savedInstanceState?.getString(KEY_TAB)?.let { n -> Tab.values().firstOrNull { it.name == n } }
            ?.takeIf { tabViews[it]?.visibility == View.VISIBLE } ?: Tab.HOME
        showTab(start)
        if (start == Tab.HOME) homeView.post { home.focusDefault() } else tabViews[start]?.post { tabViews[start]?.requestFocus() }
        // Nouvelle version publiée ? (une fois par lancement, après l'arrivée sur l'accueil)
        Bg.main.postDelayed({ if (!isFinishing && !isDestroyed) Updater.checkOnLaunch(this) }, 1_800)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, tab.name)
    }

    override fun onResume() {
        super.onResume()
        Updater.resume(this)
        if (!ready) return
        when (tab) {
            Tab.HOME -> home.resume()
            Tab.LIVE -> direct.show()
            Tab.SPORT -> sport.show()
            Tab.DL -> {
                Bg.main.removeCallbacks(refreshDownloads)
                Bg.main.postDelayed(refreshDownloads, 3_000)
            }
            else -> if (!gridMode) screenFor(tab)?.show()
        }
    }

    override fun onPause() {
        if (ready) {
            home.pause()
            direct.hide()
            sport.hide()
            movies.hide()
            series.hide()
        }
        Bg.main.removeCallbacks(refreshDownloads)
        super.onPause()
    }

    override fun onRestart() {
        super.onRestart()
        // Changement de profil ou de catégories affichées : on reconstruit tout l'écran (même onglet).
        if (ready && (Db.profile != seenProfile || Db.hiddenVersion != seenHidden || SportPrefs.version != seenSport)) {
            recreate()
            return
        }
        if (ready) paintProfile()
        // Retour du lecteur, d'une fiche ou des réglages : favoris, reprise et masquage ont pu changer.
        when {
            tab == Tab.HOME -> home.refresh()
            tab == Tab.LIVE -> direct.refresh()
            tab == Tab.SPORT -> Unit
            (tab == Tab.MOVIE || tab == Tab.SERIES) && !gridMode -> Unit
            else -> {
                val key = groups.getOrNull(catAdapter.selected)?.key
                groups = buildGroups()
                catAdapter.items = groups.map { Category(it.key, it.name) }
                val i = groups.indexOfFirst { it.key == key }.let { if (it >= 0) it else defaultIndex() }
                catAdapter.selected = i
                reloadItems(resetScroll = false)
            }
        }
    }

    private fun screenFor(t: Tab): StreamScreen? = when (t) {
        Tab.MOVIE -> movies
        Tab.SERIES -> series
        else -> null
    }

    // ---------- Onglets ----------

    private fun showOnly(v: View?) {
        for (x in listOf(homeView, directView, sportView, moviesView, seriesView, catalogView)) {
            x.visibility = if (x === v) View.VISIBLE else View.GONE
        }
    }

    /** Quitte l'onglet affiché (arrête ce qui tourne en arrière-plan). */
    private fun leaveCurrent() {
        home.pause()
        direct.hide()
        sport.hide()
        movies.hide()
        series.hide()
        Bg.main.removeCallbacks(refreshDownloads)
        bgImage.animate().cancel()
    }

    private fun showTab(t: Tab) {
        leaveCurrent()
        tab = t
        gridMode = false
        tabViews.forEach { (k, v) -> v.isSelected = k == t }
        when (t) {
            Tab.HOME -> {
                showOnly(homeView)
                Backgrounds.set(root, R.drawable.bg_home)
                bgScrim.setBackgroundResource(R.drawable.scrim_home)
                bgImage.visibility = View.VISIBLE
                bgImage.alpha = 0f
                home.refresh()
                home.resume()
            }
            Tab.LIVE -> {
                showOnly(directView)
                Backgrounds.set(root, R.drawable.bg_live)
                bgScrim.setBackgroundResource(R.drawable.scrim_home)
                bgImage.visibility = View.GONE
                direct.show()
            }
            Tab.SPORT -> {
                showOnly(sportView)
                Backgrounds.set(root, sport.theme())
                bgScrim.setBackgroundResource(R.drawable.scrim_home)
                bgImage.visibility = View.GONE
                sport.show()
            }
            Tab.MOVIE, Tab.SERIES -> {
                showOnly(if (t == Tab.MOVIE) moviesView else seriesView)
                Backgrounds.set(root, R.drawable.bg_catalog)
                bgScrim.setBackgroundResource(R.drawable.scrim_stream)
                bgImage.visibility = View.VISIBLE
                bgImage.alpha = 0f
                screenFor(t)?.show()
            }
            Tab.FAV, Tab.DL -> showGrid(null, null)
        }
    }

    /** « Voir tout » d'une rangée : la catégorie en pleine page. */
    private fun openGrid(t: Tab, key: String, title: String) {
        leaveCurrent()
        tab = t
        gridMode = true
        tabViews.forEach { (k, v) -> v.isSelected = k == t }
        showGrid(key, title)
        rvItems.post { focusFirstItem() }
    }

    private fun showGrid(groupKey: String?, title: String?) {
        showOnly(catalogView)
        bgImage.visibility = View.GONE
        bgScrim.setBackgroundResource(R.drawable.scrim_home)
        Backgrounds.set(root, R.drawable.bg_catalog)
        // Pleine page : pas de colonne de catégories, juste le titre et toutes les affiches.
        val full = gridMode
        catPanel.visibility = if (full) View.GONE else View.VISIBLE
        gridTitle.visibility = if (full) View.VISIBLE else View.GONE
        gridTitle.text = title ?: ""
        groups = buildGroups()
        catAdapter.items = groups.map { Category(it.key, it.name) }
        val start = groupKey?.let { k -> groups.indexOfFirst { it.key == k }.takeIf { it >= 0 } } ?: defaultIndex()
        selectCategory(start, force = true)
        rvCategories.scrollToPosition(start)
        if (tab == Tab.DL) Bg.main.postDelayed(refreshDownloads, 3_000)
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
            Tab.HOME, Tab.SPORT, Tab.LIVE -> Unit
            else -> {
                val kind = kindOfTab()
                g.add(Group(FAV, "Ma liste", emptyList()))
                g.add(Group(RESUME, "Reprendre", emptyList()))
                if (Prefs.showRewatch) g.add(Group(REWATCH, "Revoir", emptyList()))
                g.add(Group(LATEST, "Nouveautés", emptyList()))
                g.add(Group(ALL, "Tout", emptyList()))
                g.addAll(Catalog.groups(src.id, kind))
            }
        }
        return g
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: Any? = v
        while (p is View) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    /** Chip du haut : avatar et nom du profil en cours (et la source s'il y en a plusieurs). */
    private fun paintProfile() {
        val p = Session.profile()
        val av = findViewById<TextView>(R.id.profileAvatar)
        val tv = findViewById<TextView>(R.id.tvSource)
        if (p == null) {
            tv.text = src.name
            av.visibility = View.GONE
            return
        }
        av.background = Profiles.avatar(p, 8f, resources.displayMetrics.density)
        av.text = Profiles.initial(p)
        tv.text = if (Db.sources().size > 1) "${p.name} · ${src.name}" else p.name
    }

    private fun profileMenu() {
        val many = Db.profiles(src.id).size > 1
        val opts = arrayListOf(if (many) "Changer de profil" else "Ajouter un profil", "Gérer les profils", "Changer de source")
        Ui.choose(this, Session.profile()?.name ?: src.name, opts) { w ->
            when (w) {
                0 -> startActivity(Intent(this, ProfilesActivity::class.java))
                1 -> startActivity(Intent(this, ProfilesActivity::class.java).putExtra(ProfilesActivity.EXTRA_MANAGE, true))
                2 -> chooseSource()
            }
        }
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
        val s = src.id
        val k = kindOfTab()

        val vk = viewKindOf(g.key)
        itemAdapter.viewKind = vk
        grid.spanCount = if (vk == Kind.LIVE) 3 else if (gridMode) 7 else 6
        itemAdapter.epgApi = if (vk == Kind.LIVE && Prefs.epgEnabled && Prefs.epgPreload) api else null
        itemAdapter.subtitle = null
        itemAdapter.progress = null

        val list = when (g.key) {
            FAV -> ItemList.of(Db.favorites(s, k))
            FAV_LIVE -> ItemList.of(Db.favorites(s, Kind.LIVE))
            FAV_MOVIE -> ItemList.of(Db.favorites(s, Kind.MOVIE))
            FAV_SERIES -> ItemList.of(Db.favorites(s, Kind.SERIES))
            RESUME -> {
                if (k == Kind.SERIES) {
                    val l = Db.seriesActivity(s, 200).mapNotNull { (sid, _) -> Db.find(s, Kind.SERIES, sid) }
                    ItemList.of(l)
                } else {
                    val l = Db.progressList(s, k, false)
                    val map = Db.progressMap(s, k, l.map { it.id })
                    itemAdapter.progress = { item -> map[item.id]?.let { p -> if (p.dur > 0) p.pos.toFloat() / p.dur else null } }
                    ItemList.of(l)
                }
            }
            REWATCH -> ItemList.of(Db.progressList(s, k, true))
            LATEST -> ItemList.of(Db.latest(s, k, 300))
            DL -> {
                dl = Downloads.statuses(this)
                val byKey = dl.associateBy { it.row.item.key }
                itemAdapter.subtitle = { item -> byKey[item.key]?.label }
                ItemList.of(dl.map { it.row.item })
            }
            ALL -> ItemList.query(s, k, null, null)
            else -> ItemList.query(s, k, g.catIds, null)
        }
        itemAdapter.list = list
        if (resetScroll) rvItems.scrollToPosition(0)
        if (gridMode) gridTitle.text = "${gridTitle.text.toString().substringBefore("  ·  ")}  ·  ${list.size}"
        setStatus(
            when {
                list.size > 0 -> null
                g.key == DL -> "Aucun téléchargement.\nSur la fiche d'un film ou d'un épisode, choisis Télécharger pour le regarder hors ligne."
                g.key == FAV || g.key.startsWith("__fav") -> "Rien ici pour l'instant.\nAppui long sur OK sur un élément pour l'ajouter."
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
        Ui.toast(this, if (added) "Ajouté à Ma liste" else "Retiré de Ma liste")
        val key = groups.getOrNull(catAdapter.selected)?.key
        if (key == FAV || key?.startsWith("__fav") == true) reloadItems(resetScroll = false)
        else itemAdapter.notifyItemChanged(position)
    }

    // ---------- Télécommande ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Chiffres de la télécommande dans le Direct : numéro de chaîne, comme sur un décodeur.
        if (ready && tab == Tab.LIVE && event.action == KeyEvent.ACTION_DOWN) {
            val c = event.keyCode
            if (c in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) return direct.typeDigit(c - KeyEvent.KEYCODE_0)
            if (c in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) return direct.typeDigit(c - KeyEvent.KEYCODE_NUMPAD_0)
        }
        // Touche Recherche de la télécommande
        if (ready && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            startActivity(Intent(this, SearchActivity::class.java))
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Retour, toujours dans le même ordre :
     * grille -> catégories ; catégorie pleine page -> rangées ; contenu -> barre d'onglets ;
     * onglet -> Accueil ; Accueil -> confirmation avant de quitter.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val tabView = tabViews[tab]
        val inTopBar = topBar.hasFocus()
        when {
            catalogView.visibility == View.VISIBLE && rvItems.hasFocus() && catPanel.visibility == View.VISIBLE -> {
                val v = rvCategories.findViewHolderForAdapterPosition(catAdapter.selected)?.itemView
                if (v != null) v.requestFocus() else rvCategories.requestFocus()
            }
            gridMode && !inTopBar -> {
                showTab(tab)
                screenFor(tab)?.focusDefault()
            }
            tabView != null && !inTopBar -> tabView.requestFocus()
            tab != Tab.HOME -> {
                showTab(Tab.HOME)
                tabViews[Tab.HOME]?.requestFocus()
            }
            else -> confirmExit()
        }
    }

    private fun confirmExit() {
        val dlg = android.app.AlertDialog.Builder(this, Ui.DIALOG)
            .setTitle("Quitter Xtream Player ?")
            .setMessage("Tu pourras revenir quand tu veux depuis l'écran d'accueil du stick.")
            .setPositiveButton("Quitter") { _, _ -> finish() }
            .setNegativeButton("Rester", null)
            .create()
        dlg.setOnShowListener { dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.requestFocus() }
        dlg.show()
    }

    override fun onDestroy() {
        Bg.main.removeCallbacks(tabSwitch)
        Bg.main.removeCallbacks(selectFocusedCategory)
        Bg.main.removeCallbacks(refreshDownloads)
        if (ready) {
            home.pause()
            direct.hide()
            sport.hide()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ITEM = "item"
        private const val KEY_TAB = "tab"
        const val FAV = "__fav"
        const val FAV_LIVE = "__fav_live"
        const val FAV_MOVIE = "__fav_movie"
        const val FAV_SERIES = "__fav_series"
        const val RESUME = "__resume"
        const val REWATCH = "__rewatch"
        const val LATEST = "__latest"
        const val ALL = "__all"
        const val DL = "__dl"
    }
}
