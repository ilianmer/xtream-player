package com.ilian.xtreamplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Outils communs aux profils : couleurs, avatar, choix des langues. */
object Profiles {
    const val MAX = 5
    val COLORS = intArrayOf(
        0xFFE5484D.toInt(), 0xFFF5C451.toInt(), 0xFF3E8EF7.toInt(), 0xFF30A46C.toInt(),
        0xFF8E4EC6.toInt(), 0xFFF76B15.toInt(), 0xFF0EA5A5.toInt(), 0xFFE93D82.toInt()
    )
    val COLOR_NAMES = listOf("Rouge", "Jaune", "Bleu", "Vert", "Violet", "Orange", "Turquoise", "Rose")

    fun color(p: Profile): Int = COLORS[Math.floorMod(p.color, COLORS.size)]

    /** Avatar : carré arrondi à la couleur du profil, avec un léger dégradé. */
    fun avatar(p: Profile, radiusDp: Float, density: Float): GradientDrawable {
        val c = color(p)
        val dark = android.graphics.Color.rgb(
            (android.graphics.Color.red(c) * 0.55).toInt(), (android.graphics.Color.green(c) * 0.55).toInt(), (android.graphics.Color.blue(c) * 0.55).toInt()
        )
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c, dark)).apply { cornerRadius = radiusDp * density }
    }

    fun initial(p: Profile): String = p.name.trim().take(1).uppercase().ifBlank { "?" }

    fun langsLabel(p: Profile): String =
        if (p.langSet.isEmpty()) "Toutes les langues" else p.langSet.joinToString(" · ") { Langs.label(it) }

    /**
     * Préréglage de langue : on coche une ou plusieurs langues, l'appli n'affiche plus que
     * les chaînes, films et séries dans ces langues (on affine ensuite dans « Catégories affichées »).
     */
    fun chooseLangs(act: Activity, p: Profile, after: () -> Unit) {
        val stats = Langs.stats(p.sourceId)
        val langs = Langs.ALL.filter { (stats[it.code] ?: 0) > 0 }.sortedByDescending { stats[it.code] ?: 0 }
        if (langs.isEmpty()) {
            Ui.toast(act, "Impossible de reconnaître les langues des catégories de cette source.")
            return
        }
        val labels = langs.map { "${it.label}  (${stats[it.code]} catégories)" }.toTypedArray()
        val checked = langs.map { it.code in p.langSet }.toBooleanArray()
        AlertDialog.Builder(act, Ui.DIALOG)
            .setTitle("Langue du contenu · ${p.name}")
            .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Appliquer") { _, _ ->
                val set = langs.filterIndexed { i, _ -> checked[i] }.map { it.code }.toSet()
                Db.setProfileLangs(p.id, set.joinToString(","))
                Langs.apply(p.sourceId, p.id, set)
                Ui.toast(act, if (set.isEmpty()) "Toutes les langues sont affichées." else "Préréglage appliqué : ${set.joinToString(", ") { Langs.label(it) }}")
                after()
            }
            .setNeutralButton("Tout afficher") { _, _ ->
                Db.setProfileLangs(p.id, "")
                Langs.apply(p.sourceId, p.id, emptySet())
                Ui.toast(act, "Toutes les langues sont affichées.")
                after()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}

/** « Qui regarde ? » : jusqu'à 5 profils par source, chacun avec ses favoris, sa reprise et ses réglages. */
class ProfilesActivity : BaseActivity() {

    private lateinit var src: Source
    private lateinit var row: LinearLayout
    private lateinit var btnManage: TextView
    private lateinit var hint: TextView
    private var manage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        src = Session.source() ?: run {
            finish()
            return
        }
        setContentView(R.layout.activity_profiles)
        row = findViewById(R.id.row)
        btnManage = findViewById(R.id.btnManage)
        hint = findViewById(R.id.tvHint)
        manage = intent.getBooleanExtra(EXTRA_MANAGE, false)
        btnManage.setOnClickListener {
            manage = !manage
            paint()
        }
        paint()
    }

    private fun paint() {
        val d = resources.displayMetrics.density
        val list = Db.profiles(src.id).ifEmpty { listOf(Db.ensureProfile(src.id)) }
        findViewById<TextView>(R.id.tvTitle).text = if (manage) "Gérer les profils" else "Qui regarde ?"
        hint.text = if (manage) "Choisis un profil à modifier." else "Chaque profil a ses favoris, sa reprise de lecture, ses langues et ses équipes."
        btnManage.text = if (manage) "Terminé" else "Gérer les profils"
        row.removeAllViews()
        val current = Db.profile
        var focus: View? = null
        for (p in list) {
            val v = layoutInflater.inflate(R.layout.item_profile, row, false)
            val avatar = v.findViewById<View>(R.id.avatar)
            avatar.background = Profiles.avatar(p, 18f, d)
            avatar.clipToOutline = true
            v.findViewById<TextView>(R.id.initial).apply {
                text = Profiles.initial(p)
                alpha = if (manage) 0.35f else 1f
            }
            v.findViewById<TextView>(R.id.name).text = p.name
            v.findViewById<TextView>(R.id.langs).text = Profiles.langsLabel(p)
            v.findViewById<View>(R.id.edit).visibility = if (manage) View.VISIBLE else View.GONE
            v.setOnFocusChangeListener { view, has -> Fx.focus(view, has, 1.1f) }
            v.setOnClickListener { if (manage) edit(p) else open(p) }
            row.addView(v)
            if (p.id == current || focus == null) focus = v
        }
        if (list.size < Profiles.MAX) {
            val v = layoutInflater.inflate(R.layout.item_profile, row, false)
            val avatar = v.findViewById<View>(R.id.avatar)
            avatar.setBackgroundResource(R.drawable.avatar_add_bg)
            v.findViewById<TextView>(R.id.initial).text = "+"
            v.findViewById<TextView>(R.id.name).text = "Ajouter"
            v.findViewById<TextView>(R.id.langs).text = "${list.size} / ${Profiles.MAX}"
            v.setOnFocusChangeListener { view, has -> Fx.focus(view, has, 1.1f) }
            v.setOnClickListener { add(list.size) }
            row.addView(v)
        }
        val f = focus
        f?.post { f.requestFocus() }
    }

    private fun open(p: Profile) {
        Session.selectProfile(p)
        Preload.featured = null
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun add(count: Int) {
        Ui.textInput(this, "Nom du nouveau profil", "", "ex : Sarah") { name ->
            if (name.isBlank()) return@textInput
            val id = Db.addProfile(src.id, name.trim().take(20), count % Profiles.COLORS.size)
            val p = Db.profileById(id) ?: return@textInput
            paint()
            // On propose tout de suite la langue du contenu pour ce profil
            Profiles.chooseLangs(this, p) { paint() }
        }
    }

    private fun edit(p: Profile) {
        val count = Db.profiles(src.id).size
        val options = arrayListOf("Renommer", "Couleur", "Langue du contenu")
        if (count > 1) options.add("Supprimer le profil")
        Ui.choose(this, p.name, options) { w ->
            when (w) {
                0 -> Ui.textInput(this, "Nom du profil", p.name, "Nom") { name ->
                    if (name.isNotBlank()) Db.updateProfile(p.id, name.trim().take(20), p.color)
                    paint()
                }
                1 -> Ui.choose(this, "Couleur de ${p.name}", Profiles.COLOR_NAMES) { c ->
                    Db.updateProfile(p.id, p.name, c)
                    paint()
                }
                2 -> Profiles.chooseLangs(this, p) { paint() }
                3 -> Ui.confirm(this, "Supprimer « ${p.name} » ?", "Ses favoris, sa reprise de lecture et ses réglages seront effacés.", "Supprimer") {
                    Db.deleteProfile(p.id)
                    if (Db.profile == p.id) Db.profiles(src.id).firstOrNull()?.let { Session.selectProfile(it) }
                    paint()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (manage && !intent.getBooleanExtra(EXTRA_MANAGE, false)) {
            manage = false
            paint()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_MANAGE = "manage"
    }
}
