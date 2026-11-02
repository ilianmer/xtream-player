package com.ilian.xtreamplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Modifier un profil : grand avatar (OK pour le changer), nom, couleur, langues, suppression. */
class ProfileEditActivity : BaseActivity() {

    private var id = 0L
    private lateinit var rows: Rows
    private lateinit var big: View
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getLongExtra(EXTRA_PROFILE, 0L)
        if (Db.profileById(id) == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_profile_edit)
        rows = Rows(this, findViewById<LinearLayout>(R.id.rows))
        big = findViewById(R.id.bigAvatar)
        big.setOnFocusChangeListener { v, has -> Fx.focus(v, has, 1.06f) }
        big.setOnClickListener { pickAvatar() }
        findViewById<View>(R.id.avatar).outlineProvider = Profiles.OVAL
        paint()
        big.post { big.requestFocus() }
        // Arrivée : l'avatar éclot, les réglages glissent
        val d = resources.displayMetrics.density
        big.scaleX = 0.8f
        big.scaleY = 0.8f
        big.alpha = 0f
        big.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(480)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
        val box = findViewById<View>(R.id.rows)
        box.alpha = 0f
        box.translationX = 18 * d
        box.animate().alpha(1f).translationX(0f).setStartDelay(120).setDuration(420)
            .setInterpolator(android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)).start()
    }

    override fun onResume() {
        super.onResume()
        // Retour du choix d'avatar
        if (firstResume) firstResume = false else paint()
    }

    private fun paint() {
        val p = Db.profileById(id) ?: return finish()
        val a = Avatars.of(p)
        findViewById<View>(R.id.avatar).background = Profiles.avatarDrawable(p, ring = true)
        findViewById<TextView>(R.id.pName).text = p.name
        findViewById<TextView>(R.id.pAvatar).text = "${a.name} · OK pour changer d'avatar"
        val glow = findViewById<View>(R.id.glow)
        glow.post { Profiles.glow(glow, a.top) }
        val focusedRow = (findViewById<LinearLayout>(R.id.rows)).let { box -> (0 until box.childCount).firstOrNull { box.getChildAt(it).hasFocus() } }
        rows.clear()
        rows.header("Profil")
        rows.action("Avatar", "Une trentaine de personnages, rangés par thème.", a.name) { pickAvatar() }
        rows.action("Nom", null, p.name) {
            Ui.textInput(this, "Nom du profil", p.name, "Nom") { name ->
                if (name.isNotBlank()) Db.updateProfile(p.id, name.trim().take(20), p.color)
                paint()
            }
        }
        rows.action("Couleur", "Anneau autour de l'avatar.", Profiles.COLOR_NAMES[Math.floorMod(p.color, Profiles.COLORS.size)]) {
            val cur = Math.floorMod(p.color, Profiles.COLORS.size)
            Ui.choose(this, "Couleur de ${p.name}", Profiles.COLOR_NAMES.mapIndexed { i, n -> (if (i == cur) "✓  " else "") + n }) { c ->
                Db.updateProfile(p.id, p.name, c)
                paint()
            }
        }
        rows.action("Langue du contenu", "Chaînes, films et séries affichés pour ce profil.", Profiles.langsLabel(p)) {
            Profiles.chooseLangs(this, p) { paint() }
        }
        if (Db.profiles(p.sourceId).size > 1) {
            rows.action("Supprimer le profil", "Ses favoris, sa reprise de lecture et ses réglages seront effacés.", danger = true) {
                Ui.confirm(this, "Supprimer « ${p.name} » ?", "Ses favoris, sa reprise de lecture et ses réglages seront effacés.", "Supprimer") {
                    Db.deleteProfile(p.id)
                    if (Db.profile == p.id) Db.profiles(p.sourceId).firstOrNull()?.let { Session.selectProfile(it) }
                    finish()
                }
            }
        }
        // Le focus reste sur la ligne où il était
        focusedRow?.let { i -> findViewById<LinearLayout>(R.id.rows).getChildAt(i)?.let { v -> v.post { v.requestFocus() } } }
    }

    private fun pickAvatar() {
        startActivity(Intent(this, AvatarPickerActivity::class.java).putExtra(AvatarPickerActivity.EXTRA_PROFILE, id))
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
