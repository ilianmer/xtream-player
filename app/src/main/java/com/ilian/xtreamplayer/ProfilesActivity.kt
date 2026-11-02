package com.ilian.xtreamplayer

import android.app.Activity
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

    /** Avatar rond du profil ([ring] : anneau à sa couleur). */
    fun avatarDrawable(p: Profile, ring: Boolean = false) = AvatarDrawable(Avatars.of(p), if (ring) color(p) else null, Avatars.bgOf(p))

    /** Couleur principale de l'avatar du profil (pour le halo). */
    fun glowColor(p: Profile): Int = Avatars.bgOf(p)?.top ?: Avatars.of(p).top

    /** Ombre ronde sous un avatar. */
    val OVAL = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    /** Halo de couleur (fond radial) qui se pose derrière l'avatar choisi. */
    fun glow(view: View, color: Int) {
        val c = (color and 0x00FFFFFF) or 0x8C000000.toInt()
        view.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = view.width / 2f
            colors = intArrayOf(c, color and 0x00FFFFFF)
        }
    }

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
        val labels = langs.map { "${it.label}  (${stats[it.code]} catégories)" }
        val checked = langs.map { it.code in p.langSet }.toBooleanArray()
        Sheet.multi(
            act, "Langue du contenu · ${p.name}", labels, checked, apply = "Appliquer",
            neutral = "Tout afficher" to {
                Db.setProfileLangs(p.id, "")
                Langs.apply(p.sourceId, p.id, emptySet())
                Ui.toast(act, "Toutes les langues sont affichées.")
                after()
            }
        ) { c ->
            val set = langs.filterIndexed { i, _ -> c[i] }.map { it.code }.toSet()
            Db.setProfileLangs(p.id, set.joinToString(","))
            Langs.apply(p.sourceId, p.id, set)
            Ui.toast(act, if (set.isEmpty()) "Toutes les langues sont affichées." else "Préréglage appliqué : ${set.joinToString(", ") { Langs.label(it) }}")
            after()
        }
    }
}

/**
 * « Qui regarde ? » : jusqu'à 5 profils par source, chacun avec son avatar, ses favoris, sa reprise et ses réglages.
 * Comme sur les plateformes de streaming : grands avatars ronds, celui qui a le focus s'avance dans un halo
 * de sa couleur, les autres s'estompent.
 */
class ProfilesActivity : BaseActivity() {

    private lateinit var src: Source
    private lateinit var row: LinearLayout
    private lateinit var btnManage: TextView
    private lateinit var hint: TextView
    private lateinit var glow: View
    private var manage = false
    private var painted = false
    private var firstResume = true
    private var focusId = 0L
    /** Profil tout juste créé : on lui propose ensuite la langue du contenu. */
    private var askLangsFor = 0L

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
        glow = findViewById(R.id.glow)
        manage = intent.getBooleanExtra(EXTRA_MANAGE, false)
        focusId = Db.profile
        btnManage.setOnClickListener {
            manage = !manage
            paint(animate = false)
        }
        btnManage.setOnFocusChangeListener { _, has ->
            if (has) {
                for (i in 0 until row.childCount) row.getChildAt(i).animate().alpha(0.85f).setDuration(200).start()
                glow.animate().alpha(0f).setDuration(260).start()
            }
        }
        paint(animate = true)
    }

    override fun onResume() {
        super.onResume()
        // Retour de l'édition ou du choix d'avatar : on repeint sans l'animation d'arrivée
        if (firstResume) firstResume = false else if (painted) paint(animate = false)
        val id = askLangsFor
        if (id != 0L) {
            askLangsFor = 0L
            Db.profileById(id)?.let { p -> row.postDelayed({ Profiles.chooseLangs(this, p) { paint(animate = false) } }, 350) }
        }
    }

    private fun paint(animate: Boolean) {
        painted = true
        val d = resources.displayMetrics.density
        val list = Db.profiles(src.id).ifEmpty { listOf(Db.ensureProfile(src.id)) }
        findViewById<TextView>(R.id.tvTitle).text = if (manage) "Gérer les profils" else "Qui regarde ?"
        hint.text = if (manage) "Choisis un profil à modifier : avatar, nom, couleur, langues, code."
        else "Chaque profil a son avatar, ses favoris, sa reprise de lecture, ses langues et ses équipes."
        btnManage.text = if (manage) "Terminé" else "Gérer les profils"
        row.removeAllViews()
        var focus: View? = null
        for (p in list) {
            val v = layoutInflater.inflate(R.layout.item_profile, row, false)
            val avatar = v.findViewById<View>(R.id.avatar)
            avatar.background = Profiles.avatarDrawable(p)
            avatar.outlineProvider = Profiles.OVAL
            avatar.alpha = if (manage) 0.55f else 1f
            v.findViewById<View>(R.id.edit).visibility = if (manage) View.VISIBLE else View.GONE
            v.findViewById<View>(R.id.lock).visibility = if (Pin.isSet(p.id)) View.VISIBLE else View.GONE
            v.findViewById<TextView>(R.id.name).text = p.name
            v.findViewById<TextView>(R.id.langs).text = Profiles.langsLabel(p)
            val color = Profiles.glowColor(p)
            v.setOnFocusChangeListener { view, has ->
                Fx.focus(view, has, 1.1f)
                if (has) {
                    focusId = p.id
                    spotlight(view, color)
                }
            }
            v.setOnClickListener { if (manage) edit(p) else open(p) }
            row.addView(v)
            if (p.id == focusId || focus == null) focus = v
        }
        if (list.size < Profiles.MAX) {
            val v = layoutInflater.inflate(R.layout.item_profile, row, false)
            v.findViewById<View>(R.id.avatar).setBackgroundResource(R.drawable.avatar_add_circle)
            v.findViewById<TextView>(R.id.initial).apply {
                visibility = View.VISIBLE
                text = "+"
            }
            v.findViewById<TextView>(R.id.name).text = "Ajouter"
            v.findViewById<TextView>(R.id.langs).text = "${list.size} / ${Profiles.MAX}"
            v.setOnFocusChangeListener { view, has ->
                Fx.focus(view, has, 1.1f)
                if (has) {
                    focusId = -1L
                    spotlight(view, 0xFF8A93A6.toInt())
                }
            }
            v.setOnClickListener { add(list.size) }
            row.addView(v)
            if (focusId == -1L) focus = v
        }
        val f = focus
        f?.post { f.requestFocus() }
        if (!animate) return
        // Arrivée en cascade : le titre descend, puis chaque avatar éclot l'un après l'autre
        val title = findViewById<View>(R.id.tvTitle)
        title.alpha = 0f
        title.translationY = -10 * d
        title.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(EASE).start()
        hint.alpha = 0f
        hint.animate().alpha(1f).setStartDelay(120).setDuration(420).start()
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i).findViewById<View>(R.id.avatarBox) ?: continue
            c.alpha = 0f
            c.scaleX = 0.7f
            c.scaleY = 0.7f
            c.translationY = 14 * d
            c.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(160L + i * 80L).setDuration(520)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.3f)).withLayer().start()
        }
        btnManage.alpha = 0f
        btnManage.animate().alpha(1f).setStartDelay(280L + row.childCount * 80L).setDuration(380).start()
    }

    /** L'avatar qui a le focus reste net, les autres s'estompent ; le halo glisse derrière lui. */
    private fun spotlight(v: View, color: Int) {
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            c.animate().alpha(if (c === v) 1f else 0.5f).setDuration(220).start()
        }
        glow.post {
            Profiles.glow(glow, color)
            val a = IntArray(2)
            val b = IntArray(2)
            v.findViewById<View>(R.id.avatarBox).getLocationOnScreen(a)
            glow.getLocationOnScreen(b)
            val box = v.findViewById<View>(R.id.avatarBox)
            val dx = (a[0] + box.width / 2f) - (b[0] - glow.translationX + glow.width / 2f)
            val dy = (a[1] + box.height / 2f) - (b[1] - glow.translationY + glow.height / 2f)
            glow.animate().translationX(dx).translationY(dy).alpha(1f).setDuration(420).setInterpolator(EASE).start()
        }
    }

    private val EASE = android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)

    /** Profil protégé : son code est demandé avant de l'ouvrir. */
    private fun open(p: Profile) = Pin.guard(this, p) { enter(p) }

    private fun enter(p: Profile) {
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
            focusId = id
            askLangsFor = id
            // On choisit tout de suite son avatar, puis la langue du contenu
            startActivity(Intent(this, AvatarPickerActivity::class.java).putExtra(AvatarPickerActivity.EXTRA_PROFILE, id))
        }
    }

    /** Modifier un profil protégé demande aussi son code. */
    private fun edit(p: Profile) = Pin.guard(this, p) {
        startActivity(Intent(this, ProfileEditActivity::class.java).putExtra(ProfileEditActivity.EXTRA_PROFILE, p.id))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (manage && !intent.getBooleanExtra(EXTRA_MANAGE, false)) {
            manage = false
            paint(animate = false)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_MANAGE = "manage"
    }
}
