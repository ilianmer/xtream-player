package com.ilian.xtreamplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Réglages : sources, IPTV, interface, protection multi-écran, lecteur, téléchargements. */
class SettingsActivity : BaseActivity() {

    private lateinit var menu: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var rows: Rows
    private val menuViews = ArrayList<TextView>()
    private var section = -1
    private var firstResume = true
    private val refreshDownloads = Runnable { updateDownloads() }
    private val dlValues = HashMap<Long, TextView>()

    private val sections = listOf(
        "Sources IPTV", "Réglages IPTV", "Interface", "Sport", "Protection multi-écran", "Lecteur vidéo", "Téléchargements"
    )
    private val icons = listOf(
        R.drawable.ic_menu_sources, R.drawable.ic_menu_iptv, R.drawable.ic_menu_ui, R.drawable.ic_menu_sport,
        R.drawable.ic_menu_shield, R.drawable.ic_menu_player, R.drawable.ic_download
    )
    private lateinit var tvSection: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        menu = findViewById(R.id.menu)
        content = findViewById(R.id.content)
        scroll = findViewById(R.id.scroll)
        tvSection = findViewById(R.id.tvSection)
        rows = Rows(this, content)
        findViewById<TextView>(R.id.tvVersion).text = try {
            "Xtream Player " + Updater.installedLabel(this)
        } catch (_: Exception) {
            "Xtream Player"
        }

        val inf = LayoutInflater.from(this)
        val iconPx = (18 * resources.displayMetrics.density).toInt()
        sections.forEachIndexed { i, name ->
            val tv = inf.inflate(R.layout.item_category, menu, false) as TextView
            tv.text = name
            val pad = (11 * resources.displayMetrics.density).toInt()
            tv.setPadding(tv.paddingStart, pad, tv.paddingEnd, pad)
            getDrawable(icons[i])?.mutate()?.let { d ->
                d.setBounds(0, 0, iconPx, iconPx)
                tv.setCompoundDrawablesRelative(d, null, null, null)
                tv.compoundDrawablePadding = (10 * resources.displayMetrics.density).toInt()
                tv.compoundDrawableTintList = getColorStateList(R.color.cat_text)
            }
            tv.setOnFocusChangeListener { _, has -> if (has) show(i) }
            tv.setOnClickListener {
                show(i)
                firstRow()?.requestFocus()
            }
            menu.addView(tv)
            menuViews.add(tv)
        }
        show(intent.getIntExtra(EXTRA_SECTION, 0))
        menuViews[section].requestFocus()
    }

    override fun onResume() {
        super.onResume()
        Updater.resume(this)
        if (firstResume) {
            firstResume = false
            return
        }
        // Retour d'un autre écran (modification de source) : on rafraîchit la section.
        if (section >= 0) {
            val s = section
            section = -1
            show(s)
        }
    }

    override fun onPause() {
        Bg.main.removeCallbacks(refreshDownloads)
        super.onPause()
    }

    private fun firstRow(): View? {
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if (v.isFocusable) return v
        }
        return null
    }

    private fun show(i: Int) {
        if (i == section) return
        section = i
        tvSection.text = sections.getOrNull(i) ?: ""
        menuViews.forEachIndexed { j, tv -> tv.isSelected = j == i }
        Bg.main.removeCallbacks(refreshDownloads)
        rows.clear()
        scroll.scrollTo(0, 0)
        when (i) {
            0 -> sources()
            1 -> iptv()
            2 -> ui()
            3 -> sportSettings()
            4 -> protection()
            5 -> playerSettings()
            else -> downloads()
        }
    }

    private fun reshow() {
        val s = section
        section = -1
        show(s)
        menuViews.getOrNull(s)?.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Retour depuis la partie droite : on revient au menu.
        if (content.hasFocus()) {
            menuViews.getOrNull(section)?.requestFocus()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---------- Sources IPTV ----------

    private fun pct(src: Long, kind: Kind): String {
        val total = Db.countAll(src, kind)
        if (total == 0) return "—"
        return "${Db.countWithTmdb(src, kind) * 100 / total} %"
    }

    private fun sources() {
        val src = Session.source()
        if (src == null) {
            rows.note("Aucune source enregistrée.")
            rows.action("Ajouter une source") { editSource(-1) }
            return
        }
        rows.header("Source active")
        rows.info("Nom", src.name)
        rows.info("Serveur", src.server)
        val status = rows.info("Statut Xtream", src.status ?: "—")
        val exp = rows.info("Date d'expiration", src.expDate?.let { Ui.date(it * 1000) } ?: "Illimitée")
        val screens = rows.info("Écrans simultanés autorisés", if (src.maxConn > 0) src.maxConn.toString() else "—")
        val active = rows.info("Connexions en cours", "…")
        rows.info(
            "Chaînes · Films · Séries",
            "${Sync.fmt(Db.countAll(src.id, Kind.LIVE))} · ${Sync.fmt(Db.countAll(src.id, Kind.MOVIE))} · ${Sync.fmt(Db.countAll(src.id, Kind.SERIES))}"
        )
        rows.info("Intégration TMDB", "Films : ${pct(src.id, Kind.MOVIE)} · Séries : ${pct(src.id, Kind.SERIES)}")
        rows.info("Dernière mise à jour", if (src.lastSync > 0) Ui.dateTime(src.lastSync) else "Jamais")

        rows.action("Rafraîchir", "Recharge toutes les listes depuis le serveur.") { Ui.restart(this, forceSync = true) }
        rows.action("Modifier", "Nom, serveur, identifiant, mot de passe.") { editSource(src.id) }

        val prof = Session.profile()
        rows.header("Profil" + (prof?.let { " · ${it.name}" } ?: ""))
        if (prof != null) {
            rows.action(
                "Langue du contenu",
                "Préréglage : n'affiche que les chaînes, films et séries dans ces langues. On affine ensuite avec « Catégories affichées ».",
                Profiles.langsLabel(prof)
            ) { v ->
                val cur = Session.profile() ?: return@action
                Profiles.chooseLangs(this, cur) { Session.profile()?.let { rows.value(v).text = Profiles.langsLabel(it) } }
            }
        }
        rows.action("Catégories affichées", "Coche seulement les catégories que tu veux voir (Direct, Films, Séries).") {
            startActivity(Intent(this, CategoriesActivity::class.java))
        }
        rows.action("Profils", "Jusqu'à ${Profiles.MAX} profils sur cette source, chacun avec ses favoris, sa reprise, ses langues et ses équipes.", "${Db.profiles(src.id).size} / ${Profiles.MAX}") {
            startActivity(Intent(this, ProfilesActivity::class.java).putExtra(ProfilesActivity.EXTRA_MANAGE, true))
        }
        rows.header("Gestion de la source")
        rows.action("Supprimer", "Retire la source et ses listes de cet appareil.", danger = true) {
            Ui.confirm(this, "Supprimer « ${src.name} » ?", "Ses favoris et sa progression de lecture seront aussi supprimés.", "Supprimer") {
                deleteSource(src.id)
            }
        }

        val others = Db.sources().filter { it.id != src.id }
        rows.header("Autres sources")
        for (o in others) {
            rows.action(o.name, o.server) {
                Ui.choose(this, o.name, listOf("Utiliser cette source", "Modifier", "Supprimer")) { w ->
                    when (w) {
                        0 -> {
                            Prefs.activeSourceId = o.id
                            Ui.restart(this, forceSync = false)
                        }
                        1 -> editSource(o.id)
                        2 -> Ui.confirm(this, "Supprimer « ${o.name} » ?", "Ses listes, favoris et progression seront supprimés de cet appareil.", "Supprimer") {
                            Db.deleteSource(o.id)
                            reshow()
                        }
                    }
                }
            }
        }
        rows.action("Ajouter une source") { editSource(-1) }

        // Infos du compte en direct depuis le serveur
        val api = Session.api() ?: return
        Bg.task(this, { api.account() }) { r ->
            r.onSuccess { a ->
                Db.updateAccount(src.id, a)
                status.text = a.status ?: "—"
                exp.text = a.expDate?.let { Ui.date(it * 1000) } ?: "Illimitée"
                screens.text = if (a.maxConn > 0) a.maxConn.toString() else "—"
                active.text = a.activeCons.toString()
            }.onFailure { active.text = "Serveur injoignable" }
        }
    }

    private fun editSource(id: Long) {
        startActivity(Intent(this, SourceActivity::class.java).putExtra(SourceActivity.EXTRA_ID, id))
    }

    private fun deleteSource(id: Long) {
        Db.deleteSource(id)
        val next = Db.sources().firstOrNull()
        if (next == null) {
            Prefs.activeSourceId = -1
            Session.reset()
            startActivity(Intent(this, SourceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        } else {
            Prefs.activeSourceId = next.id
            Ui.restart(this, forceSync = false)
        }
    }

    // ---------- Guide TV externe ----------

    private fun epgSection() {
        val src = Session.source() ?: return
        rows.header("Guide TV externe")
        rows.note("Le programme de toutes les chaînes, même quand ton fournisseur n'en donne pas. Les chaînes sont reconnues automatiquement.")
        var status: TextView? = null
        rows.choice(
            "Source du guide", null, XmlTv.CHOICES,
            { Prefs.epgSource }, { Prefs.epgSource = it }
        ) {
            when {
                Prefs.epgSource == XmlTv.OFF -> Unit
                Prefs.epgSource == XmlTv.CUSTOM && Prefs.epgCustomUrl.isBlank() -> askEpgUrl { updateEpg(src, status) }
                else -> updateEpg(src, status)
            }
        }
        rows.action("Adresse personnalisée", "Lien vers un fichier XMLTV (.xml, .gz ou .zip).", Prefs.epgCustomUrl.ifBlank { "Aucune" }) { v ->
            askEpgUrl {
                rows.value(v).text = Prefs.epgCustomUrl.ifBlank { "Aucune" }
                if (Prefs.epgSource == XmlTv.CUSTOM) updateEpg(src, status)
            }
        }
        val up = rows.action("Mettre à jour le guide maintenant", "Se fait aussi tout seul au lancement, toutes les 12 h.", XmlTv.lastLabel(src.id)) {
            updateEpg(src, status)
        }
        status = rows.value(up)
    }

    private fun askEpgUrl(after: () -> Unit) {
        Ui.textInput(this, "Adresse du guide XMLTV", Prefs.epgCustomUrl, "https://…/guide.xml.gz") { url ->
            Prefs.epgCustomUrl = url.trim()
            after()
        }
    }

    /** Téléchargement du guide avec l'avancement affiché. */
    private fun updateEpg(src: Source, status: TextView?) {
        val api = Session.api() ?: return
        if (Prefs.epgSource == XmlTv.OFF) {
            Ui.toast(this, "Choisis d'abord une source de guide.")
            return
        }
        if (XmlTv.running) {
            Ui.toast(this, "Mise à jour du guide déjà en cours…")
            return
        }
        val d = resources.displayMetrics.density
        val label = TextView(this).apply {
            text = "Préparation…"
            setTextColor(getColor(R.color.text_dim))
            textSize = 13f
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), (4 * d).toInt())
        }
        val dlg = android.app.AlertDialog.Builder(this, Ui.DIALOG)
            .setTitle("Guide TV")
            .setView(label)
            .setCancelable(false)
            .show()
        Bg.task(this, {
            XmlTv.import(src, api) { msg -> Bg.main.post { label.text = msg } }
        }) { r ->
            dlg.dismiss()
            r.onSuccess { res ->
                status?.text = XmlTv.lastLabel(src.id)
                Ui.toast(this, "Guide à jour : ${res.channels} chaînes reconnues, ${Sync.fmt(res.programmes)} programmes.")
            }.onFailure { e ->
                Ui.toast(this, "Guide indisponible : ${Bg.err(e)}")
            }
        }
    }

    // ---------- Réglages IPTV ----------

    private fun iptv() {
        rows.choice(
            "Mise à jour du contenu", "Fréquence de rechargement automatique des listes au démarrage de l'appli.",
            listOf(0 to "Manuelle", 1 to "Chaque jour", 3 to "Tous les 3 jours", 7 to "Tous les 7 jours", 14 to "Tous les 14 jours"),
            { Prefs.updateDays }, { Prefs.updateDays = it }
        )
        val src = Session.source()
        if (src != null) {
            rows.action("Catégorie du Direct à l'ouverture", "Catégorie affichée en arrivant sur le Direct (aussi : appui long dans la liste des catégories).", LiveDefault.label(src.id)) { v ->
                val groups = listOf<Group?>(null) + LiveDefault.groups(src.id)
                val cur = LiveDefault.get()
                Ui.choose(this, "Catégorie du Direct", groups.map { g ->
                    val name = g?.name ?: "Première catégorie (par défaut)"
                    if ((g?.key) == cur) "★  $name" else name
                }) { w ->
                    LiveDefault.set(groups[w]?.key)
                    rows.value(v).text = LiveDefault.label(src.id)
                }
            }
        }
        rows.toggle("Activer l'EPG", { if (it) "Le guide TV est activé." else "Le guide TV est désactivé." }, { Prefs.epgEnabled }, { Prefs.epgEnabled = it })
        rows.toggle(
            "Précharger l'EPG",
            { "Affiche le programme en cours directement dans la liste des chaînes, sans attendre de lancer la chaîne." },
            { Prefs.epgPreload }, { Prefs.epgPreload = it }
        )
        epgSection()
        rows.toggle(
            "Fusion des catégories similaires",
            { "Exemple : FR TV (SD) | FR TV (HD) | FR TV (4K) sont regroupées en : FR TV" },
            { Prefs.mergeCategories }, { Prefs.mergeCategories = it }
        )
        rows.choice(
            "Format du direct", "TS marche avec la plupart des fournisseurs. Si une chaîne ne passe pas, l'appli essaie l'autre format toute seule.",
            listOf("ts" to "TS", "m3u8" to "HLS (m3u8)"), { Prefs.liveFormat }, { Prefs.liveFormat = it }
        )
        val ua = rows.choice(
            "User Agent", "Identifie l'appareil auprès des serveurs IPTV. Certains fournisseurs n'acceptent que certaines valeurs.",
            listOf("default" to "Xtream Player", "smarters" to Prefs.SMARTERS_UA, "custom" to "Personnalisé"),
            { Prefs.uaMode }, { Prefs.uaMode = it }
        ) {
            if (Prefs.uaMode == "custom") {
                Ui.textInput(this, "User Agent personnalisé", Prefs.uaCustom, "ex : VLC/3.0.20 LibVLC/3.0.20") {
                    Prefs.uaCustom = it
                    reshow()
                }
            }
        }
        if (Prefs.uaMode == "custom" && Prefs.uaCustom.isNotBlank()) rows.value(ua).text = Prefs.uaCustom.take(28)

        rows.header("Intégration TMDB")
        rows.action(
            "Clé API TMDB",
            "Complète les affiches et résumés manquants. Crée une clé gratuite sur themoviedb.org, rubrique Paramètres > API, puis colle-la ici (le plus simple : depuis le téléphone).",
            value = if (Prefs.tmdbKey.isBlank()) "Désactivée" else "Activée"
        ) {
            Ui.textInput(this, "Clé API TMDB", Prefs.tmdbKey, "Clé API (v3) ou jeton de lecture (v4)") { key ->
                Prefs.tmdbKey = key
                if (key.isBlank()) {
                    reshow()
                    return@textInput
                }
                Bg.task(this, { Tmdb.test() }) { r ->
                    val err = r.getOrNull()
                    Ui.toast(this, if (err == null) "Clé TMDB valide, enrichissement activé." else "La clé ne fonctionne pas : $err")
                    reshow()
                }
            }
        }
    }

    // ---------- Interface ----------

    private fun ui() {
        rows.toggle(
            "Liste Revoir",
            { if (it) "La liste des films terminés est visible dans Films." else "La liste des films terminés est masquée." },
            { Prefs.showRewatch }, { Prefs.showRewatch = it }
        )
        rows.note("La liste Reprendre (films commencés) apparaît automatiquement dans Films dès qu'un film est en cours.")
        rows.toggle(
            "Aperçu vidéo dans le guide",
            { if (it) "La chaîne sélectionnée dans le Direct se lance en petit, comme sur un décodeur. Elle utilise un écran de ton compte." else "Pas d'aperçu : seul le guide s'affiche." },
            { Prefs.guidePreview }, { Prefs.guidePreview = it }
        )
        rows.choice(
            "Son de navigation", "Petit son quand tu te déplaces avec les flèches de la télécommande.",
            listOf(1 to "Doux", 2 to "Plus marqué", 0 to "Aucun"),
            { Prefs.navSound }, { Prefs.navSound = it }
        )
        rows.header("Application")
        rows.info("Version installée", Updater.installedLabel(this))
        rows.action("Rechercher une mise à jour", "L'appli télécharge la nouvelle version ; il ne reste qu'à confirmer l'installation. Tes données sont conservées.") {
            Updater.checkNow(this)
        }
    }

    // ---------- Sport ----------

    private fun sportSettings() {
        rows.toggle(
            "Onglet Sport",
            { if (it) "Scores en direct et matchs à venir, avec la chaîne qui les diffuse." else "L'onglet Sport est masqué (effet au prochain retour à l'accueil)." },
            { Prefs.showSport }, { Prefs.showSport = it }
        )
        val n = Sport.followed().size
        rows.action("Mes équipes", if (n == 0) "Aucune équipe suivie" else "$n équipe(s) suivie(s) : leurs matchs passent en premier.") {
            startActivity(Intent(this, TeamsActivity::class.java))
        }
        rows.toggle(
            "Scores pendant le direct",
            { if (it) "En regardant une chaîne : scores de tes équipes et des grands matchs avec les infos, et une alerte à chaque but." else "Aucun score pendant la lecture." },
            { Prefs.sportTicker }, { Prefs.sportTicker = it }
        )
        rows.header("Cotes en direct")
        rows.toggle(
            "Afficher les cotes",
            { if (it) "Cotes 1 / N / 2 sur les matchs (source ESPN, ou plusieurs bookmakers avec une clé The Odds API)." else "Les cotes sont masquées." },
            { Prefs.oddsEnabled }, { Prefs.oddsEnabled = it }
        )
        rows.action(
            "Clé The Odds API",
            "Facultative et gratuite (500 requêtes par mois) sur the-odds-api.com : compare les cotes de plusieurs bookmakers.",
            value = if (Prefs.oddsKey.isBlank()) "Aucune" else "Enregistrée"
        ) {
            Ui.textInput(this, "Clé The Odds API", Prefs.oddsKey, "Colle ta clé ici") { key ->
                Prefs.oddsKey = key.trim()
                reshow()
            }
        }
        rows.note("Les scores viennent du site ESPN, sans compte. La chaîne de chaque match est trouvée dans le guide TV de ton fournisseur ; sinon l'appli affiche le diffuseur officiel et tu choisis la chaîne une fois.")
    }

    // ---------- Protection multi-écran ----------

    private fun protection() {
        rows.toggle(
            "État",
            { if (it) "La protection multi-écrans est activée sur cet appareil." else "La protection multi-écrans est désactivée sur cet appareil." },
            { Prefs.screenProtection }, { Prefs.screenProtection = it }
        )
        rows.note(
            "Avant chaque lecture, l'appli demande au serveur combien d'écrans utilisent ton compte : dans cette appli, " +
                "dans une autre appli IPTV ou sur un autre appareil. Si ta limite est atteinte, elle te prévient au lieu de lancer le flux. " +
                "Le serveur peut mettre jusqu'à une minute pour voir qu'une lecture s'est arrêtée."
        )
        val now = rows.info("Connexions en cours", "…")
        val check = {
            now.text = "…"
            val api = Session.api()
            if (api == null) {
                now.text = "—"
            } else {
                Bg.task(this, { api.account() }) { r ->
                    now.text = r.getOrNull()?.let { a -> "${a.activeCons} sur ${if (a.maxConn > 0) a.maxConn else "∞"}" } ?: "Serveur injoignable"
                }
            }
        }
        rows.action("Vérifier maintenant") { check() }
        check()
    }

    // ---------- Lecteur vidéo ----------

    private fun playerSettings() {
        rows.choice(
            "Taille des sous-titres", null,
            listOf(0 to "Petite", 1 to "Normale", 2 to "Grande", 3 to "Très grande"),
            { Prefs.subtitleSize }, { Prefs.subtitleSize = it }
        )
        rows.choice(
            "Enchaînement des épisodes", "Jusqu'où le lecteur enchaîne automatiquement les épisodes suivants.",
            listOf(0 to "Désactivé", 1 to "Jusqu'à la fin de la saison", 2 to "Toute la série"),
            { Prefs.autoplay }, { Prefs.autoplay = it }
        )
        rows.slider(
            "Durée du tampon",
            "Ce que le lecteur précharge avant de lancer la lecture. Plus c'est élevé, moins il y a de coupures, mais le démarrage est plus long et le direct a plus de retard.",
            1, 30, 1, { "$it seconde${if (it > 1) "s" else ""}" }, { Prefs.bufferSec }, { Prefs.bufferSec = it }
        )
        rows.slider(
            "Durée max du tampon",
            "Quantité maximale de vidéo gardée d'avance. Une valeur basse évite les soucis de mémoire sur le stick, une valeur haute réduit les pauses.",
            10, 120, 5, { "$it secondes" }, { Prefs.maxBufferSec }, { Prefs.maxBufferSec = it }
        )
        rows.note("Les réglages du tampon s'appliquent à la prochaine lecture.")
    }

    // ---------- Téléchargements ----------

    private fun downloads() {
        dlValues.clear()
        rows.info("Espace libre sur le stick", Downloads.size(Downloads.freeSpace(this)))
        val list = Downloads.statuses(this)
        if (list.isEmpty()) {
            rows.note("Aucun téléchargement. Ouvre un film et choisis Télécharger, ou maintiens OK sur un épisode.")
            return
        }
        for (s in list) {
            val v = rows.action(s.row.item.name, s.row.item.extra, value = s.label) {
                val current = Downloads.statuses(this).firstOrNull { it.row.dmId == s.row.dmId }
                val options = ArrayList<String>()
                val playable = current?.playable()
                if (playable != null) options.add("Lire")
                options.add(if (current?.finished == true) "Supprimer" else "Annuler et supprimer")
                Ui.choose(this, s.row.item.name, options) { w ->
                    if (options[w] == "Lire" && playable != null) {
                        Ui.play(this, ItemList.of(listOf(playable)), 0)
                    } else {
                        Downloads.remove(this, s.row.dmId)
                        reshow()
                    }
                }
            }
            dlValues[s.row.dmId] = rows.value(v)
        }
        Bg.main.postDelayed(refreshDownloads, 2_000)
    }

    private fun updateDownloads() {
        if (section != 5) return
        for (s in Downloads.statuses(this)) dlValues[s.row.dmId]?.text = s.label
        Bg.main.postDelayed(refreshDownloads, 2_000)
    }

    companion object {
        const val EXTRA_SECTION = "section"
    }
}
