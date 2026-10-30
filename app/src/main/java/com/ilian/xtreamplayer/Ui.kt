package com.ilian.xtreamplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Ui {
    val DIALOG = R.style.GlassDialog

    fun toast(ctx: Context, s: String) {
        if (ctx is Activity) GlassToast.show(ctx, s) else Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
    }

    fun date(ms: Long): String = SimpleDateFormat("d MMMM yyyy", Locale.FRANCE).format(Date(ms))
    fun dateTime(ms: Long): String = SimpleDateFormat("d MMMM yyyy 'à' HH:mm", Locale.FRANCE).format(Date(ms))

    fun time(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.FRANCE, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.FRANCE, "%d:%02d", m, sec)
    }

    fun confirm(act: Activity, title: String, msg: String, yes: String, onYes: () -> Unit) =
        Sheet.confirm(act, title, msg, yes, onYes)

    fun choose(act: Activity, title: String, options: List<String>, onPick: (Int) -> Unit) =
        Sheet.choose(act, title, options, onPick = onPick)

    /** Saisie d'un texte, à la télécommande ou depuis le téléphone. */
    fun textInput(act: Activity, title: String, value: String, hint: String, onDone: (String) -> Unit) {
        Sheet.input(act, title, value, hint, onPhone = {
            phoneInput(act, title, listOf(PhoneInput.Field("v", title, value, hint = hint))) { m -> onDone(m["v"].orEmpty().trim()) }
        }, onDone = onDone)
    }

    /** Affiche l'adresse et le QR code de la page de saisie sur téléphone. */
    fun phoneInput(act: Activity, title: String, fields: List<PhoneInput.Field>, onSubmit: (Map<String, String>) -> Unit) {
        var dlg: AlertDialog? = null
        val pi = PhoneInput(title, fields) { values ->
            dlg?.dismiss()
            onSubmit(values)
        }
        if (!pi.start()) {
            toast(act, "Aucun réseau local détecté. Vérifie que le stick est connecté au Wi-Fi.")
            return
        }
        val v = act.layoutInflater.inflate(R.layout.dialog_phone, null)
        v.findViewById<ImageView>(R.id.qr).setImageBitmap(PhoneInput.qr(pi.url, 360))
        v.findViewById<TextView>(R.id.url).text = pi.url
        v.findViewById<TextView>(R.id.pin).text = pi.pin
        dlg = AlertDialog.Builder(act, DIALOG).setTitle(title).setView(v)
            .setNegativeButton("Fermer", null)
            .setOnDismissListener { pi.stop() }
            .show()
    }

    /**
     * Lance la lecture. Si la protection multi-écran est active, demande d'abord au serveur
     * combien d'écrans utilisent le compte (toutes applis et tous appareils confondus).
     */
    fun play(act: Activity, list: ItemList, index: Int, autoFallback: Boolean = false) {
        val item = list[index] ?: return
        val go = {
            Session.playlist = list
            Session.index = index
            Session.autoFallback = autoFallback
            if (!autoFallback) Session.sportMatchKey = null
            act.startActivity(Intent(act, PlayerActivity::class.java))
        }
        // Juste après une lecture, le serveur compte encore l'ancienne connexion : on ne bloque pas.
        val recent = System.currentTimeMillis() - Session.lastStopAt < 90_000
        if (!Prefs.screenProtection || item.url != null || recent) {
            go()
            return
        }
        val api = Session.api()
        if (api == null) {
            go()
            return
        }
        Bg.task(act, { api.account() }) { r ->
            val a = r.getOrNull()
            if (a == null || a.maxConn <= 0 || a.activeCons < a.maxConn) {
                go()
            } else {
                Sheet.message(
                    act, "Écran déjà utilisé",
                    "Ton compte est déjà en lecture sur ${a.activeCons} écran(s) pour ${a.maxConn} autorisé(s), " +
                        "sur un autre appareil ou dans une autre appli IPTV.\n\nSi tu lances quand même, l'autre lecture risque d'être coupée.",
                    primary = "Lancer quand même", onPrimary = { go() },
                    secondary = "Annuler", focusSecondary = true
                )
            }
        }
    }

    /** Redémarre l'appli sur l'écran de chargement (après un changement de source). */
    fun restart(act: Activity, forceSync: Boolean) {
        Session.reset()
        act.startActivity(
            Intent(act, StartActivity::class.java)
                .putExtra(StartActivity.EXTRA_FORCE, forceSync)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        act.finish()
    }
}

/** Construit les lignes d'un écran de réglages (interrupteurs, choix, curseurs, actions). */
class Rows(private val act: Activity, private val box: LinearLayout) {
    private val inf = act.layoutInflater

    fun clear() = box.removeAllViews()

    fun header(text: String) {
        val tv = inf.inflate(R.layout.row_header, box, false) as TextView
        tv.text = text
        box.addView(tv)
    }

    fun note(text: String) {
        val tv = inf.inflate(R.layout.row_note, box, false) as TextView
        tv.text = text
        box.addView(tv)
    }

    /** Ligne d'info non sélectionnable. Renvoie le champ valeur pour le mettre à jour plus tard. */
    fun info(label: String, value: String): TextView {
        val v = inf.inflate(R.layout.row_info, box, false)
        v.findViewById<TextView>(R.id.label).text = label
        val tv = v.findViewById<TextView>(R.id.value)
        tv.text = value
        box.addView(v)
        return tv
    }

    private fun row(title: String, desc: String?): View {
        val v = inf.inflate(R.layout.row_setting, box, false)
        v.findViewById<TextView>(R.id.title).text = title
        val d = v.findViewById<TextView>(R.id.desc)
        d.text = desc ?: ""
        d.visibility = if (desc.isNullOrBlank()) View.GONE else View.VISIBLE
        box.addView(v)
        return v
    }

    fun value(v: View): TextView = v.findViewById(R.id.value)

    fun action(title: String, desc: String? = null, value: String? = null, danger: Boolean = false, onClick: (View) -> Unit): View {
        val v = row(title, desc)
        val tv = value(v)
        if (value != null) {
            tv.text = value
            tv.visibility = View.VISIBLE
        } else {
            v.findViewById<View>(R.id.chevron).visibility = View.VISIBLE
        }
        if (danger) v.findViewById<TextView>(R.id.title).setTextColor(act.getColorStateList(R.color.row_danger_text))
        v.setOnClickListener { onClick(v) }
        return v
    }

    fun toggle(title: String, desc: (Boolean) -> String?, get: () -> Boolean, set: (Boolean) -> Unit): View {
        val v = row(title, desc(get()))
        val sw = v.findViewById<View>(R.id.sw)
        val knob = v.findViewById<View>(R.id.knob)
        val d = v.findViewById<TextView>(R.id.desc)
        sw.visibility = View.VISIBLE
        // Interrupteur animé : le fond passe du gris au vert en fondu, la pastille glisse avec un léger ressort
        val track = android.graphics.drawable.TransitionDrawable(
            arrayOf(act.getDrawable(R.drawable.switch_off), act.getDrawable(R.drawable.switch_on))
        ).apply { isCrossFadeEnabled = true }
        sw.background = track
        val dp = act.resources.displayMetrics.density
        val travel = (46 - 21 - 6) * dp
        fun paint(animate: Boolean) {
            val on = get()
            if (animate) {
                if (on) track.startTransition(180) else track.reverseTransition(180)
                knob.animate().translationX(if (on) travel else 0f)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).setDuration(260).start()
            } else {
                if (on) track.startTransition(0) else track.resetTransition()
                knob.translationX = if (on) travel else 0f
            }
            val text = desc(on)
            d.text = text ?: ""
            d.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        paint(false)
        v.setOnClickListener {
            set(!get())
            paint(true)
        }
        return v
    }

    fun <T> choice(
        title: String,
        desc: String?,
        options: List<Pair<T, String>>,
        get: () -> T,
        set: (T) -> Unit,
        after: (() -> Unit)? = null
    ): View {
        val v = row(title, desc)
        val tv = value(v)
        tv.visibility = View.VISIBLE
        fun paint() {
            tv.text = options.firstOrNull { it.first == get() }?.second ?: ""
        }
        paint()
        v.setOnClickListener {
            val cur = get()
            Sheet.choose(act, title, options.map { (if (it.first == cur) "✓  " else "") + it.second }) { which ->
                set(options[which].first)
                paint()
                after?.invoke()
            }
        }
        return v
    }

    /** Curseur réglé avec gauche / droite de la télécommande. */
    fun slider(title: String, desc: String, min: Int, max: Int, step: Int, label: (Int) -> String, get: () -> Int, set: (Int) -> Unit): View {
        val v = row(title, desc)
        val tv = value(v)
        val bar = v.findViewById<ProgressBar>(R.id.bar)
        tv.visibility = View.VISIBLE
        bar.visibility = View.VISIBLE
        bar.max = max - min
        fun paint(animate: Boolean = false) {
            val x = get()
            tv.text = label(x)
            bar.setProgress(x - min, animate)
        }
        paint()
        v.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val d = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -step
                KeyEvent.KEYCODE_DPAD_RIGHT -> step
                else -> 0
            }
            if (d == 0) {
                false
            } else {
                val before = get()
                set((before + d).coerceIn(min, max))
                if (get() == before) Fx.bump(bar, if (d < 0) -1 else 1, 0) else paint(true)
                true
            }
        }
        v.setOnClickListener { Ui.toast(act, "Règle avec les flèches gauche et droite") }
        return v
    }
}
