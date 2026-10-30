package com.ilian.xtreamplayer

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min

/**
 * Bulle d'information en verre, posée en bas de l'écran (remplace le Toast du système, austère sur TV).
 * Elle glisse en fondu, reste le temps de lire, puis s'efface ; un nouveau message remplace l'ancien.
 */
object GlassToast {
    private val ease = DecelerateInterpolator(2f)

    fun show(act: Activity, text: String) {
        if (act.isFinishing || act.isDestroyed) return
        val root = act.window?.decorView as? ViewGroup
        if (root == null) {
            Toast.makeText(act, text, Toast.LENGTH_LONG).show()
            return
        }
        val d = act.resources.displayMetrics.density
        var tv = root.findViewById<TextView>(R.id.toast_view)
        if (tv == null) {
            tv = TextView(act).apply {
                id = R.id.toast_view
                setBackgroundResource(R.drawable.toast_bg)
                setTextColor(act.getColor(R.color.text))
                typeface = font(act, R.font.outfit_medium)
                textSize = 14f
                setLineSpacing(0f, 1.12f)
                maxWidth = (600 * d).toInt()
                gravity = Gravity.CENTER_VERTICAL
                setPadding((20 * d).toInt(), (12 * d).toInt(), (22 * d).toInt(), (12 * d).toInt())
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.toast_dot, 0, 0, 0)
                compoundDrawablePadding = (12 * d).toInt()
                elevation = 16 * d
                visibility = View.GONE
            }
            root.addView(tv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = (44 * d).toInt() })
        }
        val v: TextView = tv
        (v.getTag(R.id.toast_view) as? Runnable)?.let { v.removeCallbacks(it) }
        v.animate().cancel()
        v.text = text
        if (v.visibility != View.VISIBLE || v.alpha < 0.5f) {
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.translationY = 22 * d
            v.scaleX = 0.96f
            v.scaleY = 0.96f
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setInterpolator(ease).setDuration(280).start()
        } else {
            // Message suivant : petite impulsion au lieu d'une nouvelle entrée
            v.alpha = 1f
            v.translationY = 0f
            v.scaleX = 1.03f
            v.scaleY = 1.03f
            v.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2f)).setDuration(260).start()
        }
        val hide = Runnable {
            v.animate().alpha(0f).translationY(12 * d).setInterpolator(ease).setDuration(220)
                .withEndAction { v.visibility = View.GONE }.start()
        }
        v.setTag(R.id.toast_view, hide)
        v.postDelayed(hide, (2_300L + text.length * 38L).coerceAtMost(7_000L))
    }

    private val fonts = HashMap<Int, Typeface?>()
    fun font(act: Activity, id: Int): Typeface? = fonts.getOrPut(id) {
        try {
            act.resources.getFont(id)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Fenêtres de choix et de confirmation de l'appli : panneau de verre, lignes qui s'illuminent au focus,
 * coche sur le choix en cours (et le focus s'y pose directement), apparition avec un léger ressort.
 */
object Sheet {
    private const val CHECK = "✓  "

    private fun base(act: Activity, title: String, message: String?): Triple<Dialog, LinearLayout, LinearLayout> {
        val d = act.resources.displayMetrics.density
        val dlg = Dialog(act, R.style.SheetDialog)
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.sheet_bg)
            setPadding((22 * d).toInt(), (22 * d).toInt(), (22 * d).toInt(), (18 * d).toInt())
            clipToPadding = false
            clipChildren = false
        }
        panel.addView(TextView(act).apply {
            text = title
            setTextColor(act.getColor(R.color.white))
            typeface = GlassToast.font(act, R.font.outfit_semibold)
            textSize = 19f
            letterSpacing = -0.005f
            setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
        })
        if (!message.isNullOrBlank()) {
            panel.addView(TextView(act).apply {
                text = message
                setTextColor(act.getColor(R.color.text_dim))
                typeface = GlassToast.font(act, R.font.outfit_regular)
                textSize = 14f
                setLineSpacing(0f, 1.18f)
                setPadding((4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), 0)
            })
        }
        val list = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            clipChildren = false
            setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
        }
        dlg.setContentView(panel)
        dlg.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return Triple(dlg, panel, list)
    }

    private fun row(act: Activity, label: String, checked: Boolean, onClick: () -> Unit): TextView {
        val d = act.resources.displayMetrics.density
        return TextView(act).apply {
            text = label
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.sheet_row_bg)
            setTextColor(act.getColorStateList(R.color.sheet_row_text))
            typeface = GlassToast.font(act, R.font.outfit_medium)
            textSize = 15f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            minHeight = (46 * d).toInt()
            setPadding((18 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            if (checked) {
                val ic = act.getDrawable(R.drawable.ic_check_sheet)?.mutate()
                ic?.setTintList(act.getColorStateList(R.color.sheet_check))
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, ic, null)
                compoundDrawablePadding = (12 * d).toInt()
            }
            setOnFocusChangeListener { v, has -> Fx.focus(v, has, 1.025f) }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (6 * d).toInt() }
        }
    }

    /**
     * Liste de choix. Les libellés qui commencent par « ✓ » sont affichés cochés, et le focus s'y pose.
     * [onLong] : appui long sur une ligne (par exemple « catégorie par défaut »). [hint] : aide en bas.
     */
    fun choose(
        act: Activity,
        title: String,
        options: List<String>,
        onLong: ((Int) -> Unit)? = null,
        hint: String = "Retour pour annuler",
        onPick: (Int) -> Unit
    ) {
        if (act.isFinishing || act.isDestroyed) return
        val d = act.resources.displayMetrics.density
        val (dlg, panel, list) = base(act, title, null)
        var focusRow: View? = null
        options.forEachIndexed { i, raw ->
            val checked = raw.startsWith(CHECK)
            val label = if (checked) raw.removePrefix(CHECK) else raw
            val r = row(act, label, checked) {
                dlg.dismiss()
                onPick(i)
            }
            if (onLong != null) r.setOnLongClickListener {
                dlg.dismiss()
                onLong(i)
                true
            }
            if (checked && focusRow == null) focusRow = r
            list.addView(r)
        }
        val scroll = ScrollView(act).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            clipToPadding = false
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            addView(list)
        }
        val screenH = act.resources.displayMetrics.heightPixels
        val maxH = (screenH * 0.66f).toInt()
        val want = options.size * (52 * d).toInt() + (16 * d).toInt()
        panel.addView(scroll, LinearLayout.LayoutParams((440 * d).toInt(), min(want, maxH)).apply { topMargin = (6 * d).toInt() })
        panel.addView(hint(act, hint))
        dlg.show()
        (focusRow ?: list.getChildAt(0))?.let { v -> v.post { v.requestFocus() } }
    }

    /**
     * Choix multiple : chaque ligne se coche / décoche avec OK (case animée), puis « Enregistrer ».
     * [neutral] : bouton supplémentaire à gauche (par exemple « Tout afficher »).
     */
    fun multi(
        act: Activity,
        title: String,
        labels: List<String>,
        checked: BooleanArray,
        apply: String = "Enregistrer",
        neutral: Pair<String, () -> Unit>? = null,
        onApply: (BooleanArray) -> Unit
    ) {
        if (act.isFinishing || act.isDestroyed) return
        val d = act.resources.displayMetrics.density
        val (dlg, panel, list) = base(act, title, null)
        val boxTint = act.getColorStateList(R.color.sheet_box_tint)
        fun paint(r: TextView, on: Boolean) {
            val ic = act.getDrawable(if (on) R.drawable.sheet_box_on else R.drawable.sheet_box_off)?.mutate()
            if (!on) ic?.setTintList(boxTint)
            r.setCompoundDrawablesRelativeWithIntrinsicBounds(ic, null, null, null)
        }
        labels.forEachIndexed { i, label ->
            lateinit var r: TextView
            r = row(act, label, false) {
                checked[i] = !checked[i]
                paint(r, checked[i])
                // Petit battement de la ligne quand on coche
                r.animate().cancel()
                r.scaleX = 0.97f
                r.scaleY = 0.97f
                r.animate().scaleX(1.025f).scaleY(1.025f).setInterpolator(OvershootInterpolator(3f)).setDuration(220).start()
            }
            r.compoundDrawablePadding = (14 * d).toInt()
            paint(r, checked[i])
            list.addView(r)
        }
        val scroll = ScrollView(act).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            addView(list)
        }
        val maxH = (act.resources.displayMetrics.heightPixels * 0.56f).toInt()
        val want = labels.size * (52 * d).toInt() + (16 * d).toInt()
        panel.addView(scroll, LinearLayout.LayoutParams((460 * d).toInt(), min(want, maxH)).apply { topMargin = (6 * d).toInt() })
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, (14 * d).toInt(), 0, (2 * d).toInt())
        }
        if (neutral != null) {
            bar.addView(pill(act, neutral.first, false, false) {
                dlg.dismiss()
                neutral.second()
            })
        }
        bar.addView(pill(act, "Annuler", false, false) { dlg.dismiss() })
        bar.addView(pill(act, apply, true, false) {
            dlg.dismiss()
            onApply(checked)
        })
        panel.addView(bar, LinearLayout.LayoutParams((460 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        dlg.show()
        list.getChildAt(0)?.let { v -> v.post { v.requestFocus() } }
    }

    /** Saisie d'un texte (clavier de la télécommande), avec un bouton optionnel « Depuis le téléphone ». */
    fun input(act: Activity, title: String, value: String, hint: String, onPhone: (() -> Unit)?, onDone: (String) -> Unit) {
        if (act.isFinishing || act.isDestroyed) return
        val d = act.resources.displayMetrics.density
        val (dlg, panel, _) = base(act, title, null)
        val et = android.widget.EditText(act).apply {
            setText(value)
            setSelection(text.length)
            this.hint = hint
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setBackgroundResource(R.drawable.sheet_field_bg)
            setTextColor(act.getColor(R.color.text))
            setHintTextColor(act.getColor(R.color.text_faint))
            typeface = GlassToast.font(act, R.font.outfit_medium)
            textSize = 16f
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
        }
        panel.addView(et, LinearLayout.LayoutParams((460 * d).toInt(), (50 * d).toInt()).apply { topMargin = (16 * d).toInt() })
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, (16 * d).toInt(), 0, (2 * d).toInt())
        }
        if (onPhone != null) bar.addView(pill(act, "Depuis le téléphone", false, false) {
            dlg.dismiss()
            onPhone()
        })
        bar.addView(pill(act, "Annuler", false, false) { dlg.dismiss() })
        val ok = pill(act, "Valider", true, false) {
            dlg.dismiss()
            onDone(et.text.toString().trim())
        }
        bar.addView(ok)
        et.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                ok.requestFocus()
                true
            } else false
        }
        panel.addView(bar, LinearLayout.LayoutParams((460 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        dlg.show()
        et.post { et.requestFocus() }
    }

    /** Confirmation : deux boutons (le bouton rouge pour une action qui supprime). */
    fun confirm(act: Activity, title: String, msg: String, yes: String, onYes: () -> Unit) {
        if (act.isFinishing || act.isDestroyed) return
        val d = act.resources.displayMetrics.density
        val (dlg, panel, _) = base(act, title, msg)
        val danger = DANGER.any { yes.contains(it, ignoreCase = true) }
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
            clipToPadding = false
            setPadding(0, (20 * d).toInt(), 0, (2 * d).toInt())
        }
        val cancel = pill(act, "Annuler", false, false) { dlg.dismiss() }
        val ok = pill(act, yes, true, danger) {
            dlg.dismiss()
            onYes()
        }
        bar.addView(cancel)
        bar.addView(ok)
        panel.addView(bar, LinearLayout.LayoutParams((460 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        dlg.setOnKeyListener { _, code, e -> code == KeyEvent.KEYCODE_DPAD_UP && e.action == KeyEvent.ACTION_DOWN }
        dlg.show()
        // Action qui supprime : le focus part sur « Annuler », par sécurité
        (if (danger) cancel else ok).let { v -> v.post { v.requestFocus() } }
    }

    /**
     * Fenêtre avec un texte (qui défile s'il est long) et jusqu'à deux boutons.
     * [primary] reçoit le focus, sauf si c'est une action qui supprime.
     */
    fun message(
        act: Activity,
        title: String,
        message: CharSequence,
        primary: String,
        onPrimary: () -> Unit,
        secondary: String? = "Annuler",
        onSecondary: () -> Unit = {},
        cancelable: Boolean = true,
        focusSecondary: Boolean = false
    ): Dialog? {
        if (act.isFinishing || act.isDestroyed) return null
        val d = act.resources.displayMetrics.density
        val (dlg, panel, _) = base(act, title, null)
        dlg.setCancelable(cancelable)
        val body = TextView(act).apply {
            text = message
            setTextColor(act.getColor(R.color.text_dim))
            typeface = GlassToast.font(act, R.font.outfit_regular)
            textSize = 14f
            setLineSpacing(0f, 1.22f)
            setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
        }
        val scroll = ScrollView(act).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = true
            setBackgroundResource(R.drawable.sheet_scroll_bg)
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            addView(body)
        }
        val maxH = (act.resources.displayMetrics.heightPixels * 0.52f).toInt()
        body.measure(
            View.MeasureSpec.makeMeasureSpec((500 * d).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = min(body.measuredHeight + (16 * d).toInt(), maxH)
        scroll.isFocusable = body.measuredHeight + (16 * d).toInt() > maxH
        panel.addView(scroll, LinearLayout.LayoutParams((516 * d).toInt(), h).apply { topMargin = (10 * d).toInt() })
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
            clipToPadding = false
            setPadding(0, (18 * d).toInt(), 0, (2 * d).toInt())
        }
        val danger = DANGER.any { primary.contains(it, ignoreCase = true) }
        var first: View? = null
        if (secondary != null) {
            bar.addView(pill(act, secondary, false, false) {
                dlg.dismiss()
                onSecondary()
            }.also { if (danger || focusSecondary) first = it })
        }
        val ok = pill(act, primary, true, danger) {
            dlg.dismiss()
            onPrimary()
        }
        bar.addView(ok)
        panel.addView(bar, LinearLayout.LayoutParams((516 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        dlg.show()
        (first ?: ok).let { v -> v.post { v.requestFocus() } }
        return dlg
    }

    /** Fenêtre d'attente avec barre de progression et bouton d'annulation. */
    class Progress(val dialog: Dialog, val label: TextView, val bar: android.widget.ProgressBar)

    fun progress(act: Activity, title: String, label: String, onCancel: (() -> Unit)?): Progress {
        val d = act.resources.displayMetrics.density
        val (dlg, panel, _) = base(act, title, null)
        dlg.setCancelable(false)
        val tv = TextView(act).apply {
            text = label
            setTextColor(act.getColor(R.color.text_dim))
            typeface = GlassToast.font(act, R.font.outfit_medium)
            textSize = 13.5f
            setPadding((4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt(), 0)
        }
        val bar = android.widget.ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = true
            progressDrawable = act.getDrawable(R.drawable.slider_track)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(act.getColor(R.color.accent))
        }
        panel.addView(tv)
        panel.addView(bar, LinearLayout.LayoutParams((440 * d).toInt(), (8 * d).toInt()).apply { topMargin = (14 * d).toInt() })
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
            clipToPadding = false
            setPadding(0, (18 * d).toInt(), 0, (2 * d).toInt())
        }
        if (onCancel != null) {
            val cancel = pill(act, "Annuler", false, false) {
                dlg.dismiss()
                onCancel()
            }
            row.addView(cancel)
            panel.addView(row, LinearLayout.LayoutParams((440 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            dlg.show()
            cancel.post { cancel.requestFocus() }
        } else {
            panel.setPadding(panel.paddingLeft, panel.paddingTop, panel.paddingRight, (24 * d).toInt())
            dlg.show()
        }
        return Progress(dlg, tv, bar)
    }

    private fun pill(act: Activity, label: String, primary: Boolean, danger: Boolean, click: () -> Unit) = TextView(act).apply {
        val d = act.resources.displayMetrics.density
        text = label
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER
        typeface = GlassToast.font(act, R.font.outfit_semibold)
        textSize = 14f
        minWidth = (120 * d).toInt()
        minHeight = (42 * d).toInt()
        setPadding((22 * d).toInt(), 0, (22 * d).toInt(), 0)
        if (danger) {
            setBackgroundResource(R.drawable.sheet_btn_danger_bg)
            setTextColor(act.getColorStateList(R.color.sheet_danger_text))
        } else {
            setBackgroundResource(if (primary) R.drawable.sheet_btn_primary_bg else R.drawable.sheet_btn_bg)
            setTextColor(act.getColorStateList(if (primary) R.color.sheet_primary_text else R.color.sheet_row_text))
        }
        setOnFocusChangeListener { v, has -> Fx.focus(v, has, 1.05f) }
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = (10 * d).toInt() }
    }

    private fun hint(act: Activity, text: String) = TextView(act).apply {
        val d = act.resources.displayMetrics.density
        this.text = text
        setTextColor(act.getColor(R.color.text_faint))
        typeface = GlassToast.font(act, R.font.outfit_regular)
        textSize = 11f
        gravity = Gravity.CENTER
        setPadding(0, (10 * d).toInt(), 0, 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private val DANGER = listOf("Supprimer", "Retirer", "Effacer", "Réinitialiser", "Déconnecter")
}
