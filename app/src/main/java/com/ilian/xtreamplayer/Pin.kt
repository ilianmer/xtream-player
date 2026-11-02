package com.ilian.xtreamplayer

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Code à 4 chiffres par profil : chacun peut protéger son profil (ouverture, modification).
 * Le code n'est jamais gardé en clair : seule une empreinte salée est enregistrée sur l'appareil.
 * Il est redemandé au lancement de l'appli et après 10 minutes passées hors de l'appli.
 * Code oublié : le mot de passe de la source permet de le retirer.
 */
object Pin {
    private fun key(id: Long) = "pin:$id"

    fun isSet(profileId: Long): Boolean = try {
        Db.kvGet(key(profileId)) != null
    } catch (_: Exception) {
        false
    }

    private fun hash(profileId: Long, salt: String, code: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("xp|$profileId|$salt|$code".toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    fun set(profileId: Long, code: String?) {
        val value = code?.let {
            val b = ByteArray(8).also { a -> SecureRandom().nextBytes(a) }
            val salt = b.joinToString("") { x -> "%02x".format(x) }
            "$salt:${hash(profileId, salt, it)}"
        }
        try {
            Db.kvPut(key(profileId), value)
        } catch (_: Exception) {
        }
        if (code != null) unlocked.add(profileId) else unlocked.remove(profileId)
    }

    fun check(profileId: Long, code: String): Boolean {
        val v = try {
            Db.kvGet(key(profileId))
        } catch (_: Exception) {
            null
        } ?: return true
        val salt = v.substringBefore(':')
        return v.substringAfter(':') == hash(profileId, salt, code)
    }

    // ---------- Verrouillage pendant l'utilisation ----------

    /** Profils déverrouillés depuis le lancement (le code a été tapé). */
    private val unlocked = HashSet<Long>()
    private var started = 0
    private var hiddenAt = 0L
    private const val RELOCK_MS = 10 * 60_000L

    fun isUnlocked(profileId: Long) = profileId in unlocked

    /** Écrans par où l'on passe avant d'entrer dans un profil : jamais verrouillés. */
    private fun exempt(act: Activity) = act is StartActivity || act is ProfilesActivity || act is ProfileEditActivity ||
        act is AvatarPickerActivity || act is LegalActivity || act is SourceActivity

    /**
     * Appelé à l'ouverture de chaque écran. Retour dans l'appli après 10 minutes ailleurs (ou appli relancée) :
     * un profil protégé redemande son code, en repassant par « Qui regarde ? ».
     */
    fun onStart(act: Activity) {
        if (started++ == 0 && hiddenAt > 0 && SystemClock.elapsedRealtime() - hiddenAt > RELOCK_MS) unlocked.clear()
        hiddenAt = 0L
        if (exempt(act) || act.isFinishing) return
        val p = try {
            Session.profile()
        } catch (_: Exception) {
            null
        } ?: return
        if (!isSet(p.id) || p.id in unlocked) return
        act.startActivity(Intent(act, ProfilesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        act.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        act.finish()
    }

    fun onStop() {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) hiddenAt = SystemClock.elapsedRealtime()
    }

    // ---------- Essais ----------

    /** Essais ratés : après 5, on attend 30 secondes. */
    private val fails = HashMap<Long, Pair<Int, Long>>()

    private fun lockedFor(profileId: Long): Long {
        val (n, at) = fails[profileId] ?: return 0L
        if (n < 5) return 0L
        val left = 30_000L - (SystemClock.elapsedRealtime() - at)
        if (left <= 0) fails.remove(profileId)
        return left.coerceAtLeast(0L)
    }

    private fun noteFail(profileId: Long) {
        val (n, _) = fails[profileId] ?: (0 to 0L)
        fails[profileId] = (n + 1) to SystemClock.elapsedRealtime()
    }

    private fun waitMsg(ms: Long) = "Trop d'essais. Réessaie dans ${(ms + 999) / 1000} s."

    // ---------- Écrans ----------

    /** Profil protégé : demande son code, puis [ok]. Sans code : [ok] tout de suite. */
    fun guard(act: Activity, p: Profile, ok: () -> Unit) {
        if (!isSet(p.id)) {
            ok()
            return
        }
        PinPad(
            act, "Profil protégé", "Tape le code de « ${p.name} »", Profiles.avatarDrawable(p, ring = true),
            forgot = { recover(act, p, ok) },
            check = { code ->
                val wait = lockedFor(p.id)
                when {
                    wait > 0 -> PinPad.Result.Wrong(waitMsg(wait))
                    check(p.id, code) -> {
                        fails.remove(p.id)
                        unlocked.add(p.id)
                        PinPad.Result.Ok
                    }
                    else -> {
                        noteFail(p.id)
                        val w = lockedFor(p.id)
                        PinPad.Result.Wrong(if (w > 0) waitMsg(w) else "Code incorrect")
                    }
                }
            },
            done = ok
        ).show()
    }

    /** Code oublié : le mot de passe de la source (connu du titulaire de l'abonnement) le retire. */
    private fun recover(act: Activity, p: Profile, ok: () -> Unit) {
        val title = "Code oublié"
        val hint = "Mot de passe de ta source IPTV"
        val verify = { pwd: String ->
            val pass = try {
                Db.source(p.sourceId)?.pass
            } catch (_: Exception) {
                null
            }
            if (!pass.isNullOrEmpty() && (pwd == pass || pwd == pass.trim())) {
                set(p.id, null)
                fails.remove(p.id)
                Ui.toast(act, "Code retiré. Tu peux en choisir un nouveau dans Gérer les profils.")
                ok()
            } else {
                Ui.toast(act, "Mot de passe incorrect.")
            }
        }
        Sheet.input(act, title, "", hint, onPhone = {
            Ui.phoneInput(act, title, listOf(PhoneInput.Field("v", "Mot de passe IPTV", "", password = true))) { m -> verify(m["v"].orEmpty().trim()) }
        }, password = true) { verify(it) }
    }

    /** Nouveau code : on le tape deux fois. */
    fun create(act: Activity, p: Profile, done: () -> Unit) {
        PinPad(
            act, "Nouveau code", "Choisis 4 chiffres pour protéger « ${p.name} »", Profiles.avatarDrawable(p, ring = true),
            check = { PinPad.Result.Ok },
            done = {},
            onAccepted = { first ->
                PinPad(
                    act, "Confirme le code", "Tape à nouveau les 4 chiffres", Profiles.avatarDrawable(p, ring = true),
                    check = { second -> if (second == first) PinPad.Result.Ok else PinPad.Result.Wrong("Les deux codes ne correspondent pas") },
                    done = {
                        set(p.id, first)
                        Ui.toast(act, "« ${p.name} » est protégé par un code.")
                        done()
                    }
                ).show()
            }
        ).show()
    }

    /** Réglage du code depuis la fiche du profil (déjà déverrouillée) : créer, changer ou retirer. */
    fun manage(act: Activity, p: Profile, after: () -> Unit) {
        if (!isSet(p.id)) {
            create(act, p, after)
            return
        }
        Ui.choose(act, "Code de ${p.name}", listOf("Changer le code", "Retirer le code")) { w ->
            if (w == 0) create(act, p, after) else {
                Ui.confirm(act, "Retirer le code ?", "« ${p.name} » s'ouvrira sans code.", "Retirer le code") {
                    set(p.id, null)
                    Ui.toast(act, "Code retiré : « ${p.name} » s'ouvre librement.")
                    after()
                }
            }
        }
    }
}

/**
 * Pavé de code en verre, pensé pour la télécommande : à gauche l'avatar, le titre et les quatre points
 * qui se remplissent ; à droite les touches rondes 1 à 9, 0, effacer et annuler.
 * Les chiffres d'une télécommande ou d'un clavier marchent aussi. Mauvais code : les points tremblent.
 */
class PinPad(
    private val act: Activity,
    private val title: String,
    private val subtitle: String,
    private val avatar: Drawable?,
    private val forgot: (() -> Unit)? = null,
    private val check: (String) -> Result,
    private val done: () -> Unit,
    /** Appelé avec le code accepté (création : il sert à la confirmation). */
    private val onAccepted: ((String) -> Unit)? = null
) {
    sealed class Result {
        object Ok : Result()
        class Wrong(val msg: String) : Result()
    }

    private val d = act.resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()
    private var code = ""
    private lateinit var dots: LinearLayout
    private lateinit var sub: TextView
    private var dialog: Dialog? = null
    /** Un message d'erreur est affiché (il s'efface au chiffre suivant). */
    private var err = false

    fun show() {
        if (act.isFinishing || act.isDestroyed) return
        val dlg = Dialog(act, R.style.SheetDialog)
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.sheet_bg)
            setPadding(px(28), px(20), px(26), px(20))
            clipChildren = false
            clipToPadding = false
        }
        // Colonne de gauche : qui, quoi, où en est la saisie
        val left = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
        }
        if (avatar != null) {
            left.addView(View(act).apply {
                background = avatar
                outlineProvider = Profiles.OVAL
                elevation = 6 * d
            }, LinearLayout.LayoutParams(px(64), px(64)))
        }
        left.addView(TextView(act, null, 0, R.style.Semi).apply {
            text = title
            textSize = 19f
            gravity = Gravity.CENTER
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12) })
        // Deux lignes réservées : le message d'erreur ne fait rien bouger
        sub = TextView(act, null, 0, R.style.Txt).apply {
            text = subtitle
            textSize = 13f
            gravity = Gravity.CENTER
            setLines(2)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
            setTextColor(act.getColor(R.color.text_dim))
        }
        left.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) })
        dots = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(4) {
            dots.addView(View(act).apply { setBackgroundResource(R.drawable.pin_dot) }, LinearLayout.LayoutParams(px(14), px(14)).apply {
                marginStart = px(9)
                marginEnd = px(9)
            })
        }
        left.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(20)).apply { topMargin = px(14) })
        if (forgot != null) {
            left.addView(TextView(act, null, 0, R.style.Medium).apply {
                text = "Code oublié ?"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(act.getColorStateList(R.color.row_value_text))
                setBackgroundResource(R.drawable.row_bg)
                setPadding(px(14), px(6), px(14), px(6))
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { v, has -> Fx.focus(v, has, 1.05f) }
                setOnClickListener {
                    dlg.dismiss()
                    forgot.invoke()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14) })
        }
        panel.addView(left, LinearLayout.LayoutParams(px(224), ViewGroup.LayoutParams.WRAP_CONTENT))
        // Colonne de droite : 1 2 3 / 4 5 6 / 7 8 9 / ✕ 0 ⌫
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "✕", "0", "⌫")
        var first: View? = null
        val grid = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        for (r in 0 until 4) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            for (c in 0 until 3) {
                val k = keys[r * 3 + c]
                val digit = k[0].isDigit()
                val key = TextView(act).apply {
                    text = k
                    gravity = Gravity.CENTER
                    textSize = if (digit) 21f else 17f
                    includeFontPadding = false
                    typeface = GlassToast.font(act, if (digit) R.font.outfit_semibold else R.font.outfit_medium)
                    setTextColor(act.getColorStateList(R.color.row_title_text))
                    setBackgroundResource(R.drawable.pin_key_bg)
                    contentDescription = when (k) {
                        "✕" -> "Annuler"
                        "⌫" -> "Effacer"
                        else -> k
                    }
                    isFocusable = true
                    isClickable = true
                    setOnFocusChangeListener { v, has -> Fx.focus(v, has, 1.1f) }
                    setOnClickListener {
                        when (k) {
                            "✕" -> dlg.dismiss()
                            "⌫" -> back()
                            else -> type(k)
                        }
                    }
                }
                row.addView(key, LinearLayout.LayoutParams(px(50), px(50)).apply {
                    marginStart = px(6)
                    marginEnd = px(6)
                    topMargin = px(5)
                    bottomMargin = px(5)
                })
                if (k == "5") first = key
            }
            grid.addView(row)
        }
        panel.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(22) })
        // Chiffres et effacement de la télécommande (ou d'un clavier)
        dlg.setOnKeyListener { _, kc, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (kc) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    type((kc - KeyEvent.KEYCODE_0).toString())
                    true
                }
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                    type((kc - KeyEvent.KEYCODE_NUMPAD_0).toString())
                    true
                }
                KeyEvent.KEYCODE_DEL -> {
                    back()
                    true
                }
                else -> false
            }
        }
        dlg.setContentView(panel)
        dlg.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog = dlg
        dlg.show()
        first?.let { f -> f.post { f.requestFocus() } }
        // Arrivée : le panneau éclot doucement
        panel.alpha = 0f
        panel.scaleX = 0.94f
        panel.scaleY = 0.94f
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)).start()
    }

    private fun paintDots() {
        for (i in 0 until dots.childCount) {
            val v = dots.getChildAt(i)
            val on = i < code.length
            if (v.isSelected != on) {
                v.isSelected = on
                if (on) {
                    v.scaleX = 0.6f
                    v.scaleY = 0.6f
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
                }
            }
        }
    }

    private fun type(digit: String) {
        if (code.length >= 4) return
        if (err) {
            err = false
            sub.text = subtitle
            sub.setTextColor(act.getColor(R.color.text_dim))
        }
        code += digit
        paintDots()
        if (code.length == 4) dots.postDelayed({ submit() }, 160)
    }

    private fun back() {
        if (code.isEmpty() || code.length >= 4) return
        code = code.dropLast(1)
        paintDots()
    }

    private fun submit() {
        if (dialog?.isShowing != true || code.length < 4) return
        val c = code
        when (val r = check(c)) {
            is Result.Ok -> {
                dialog?.dismiss()
                onAccepted?.invoke(c)
                done()
            }
            is Result.Wrong -> refuse(r.msg)
        }
    }

    /** Mauvais code : les points tremblent, puis se vident. */
    private fun refuse(msg: String) {
        err = true
        sub.text = msg
        sub.setTextColor(act.getColor(R.color.danger))
        val k = 10 * d
        android.animation.ObjectAnimator.ofFloat(dots, View.TRANSLATION_X, 0f, -k, k, -k * 0.7f, k * 0.7f, -k * 0.35f, 0f).apply {
            duration = 420
            start()
        }
        dots.postDelayed({
            code = ""
            paintDots()
        }, 400)
    }
}
