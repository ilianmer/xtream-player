package com.ilian.xtreamplayer

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** À propos et mentions légales : conditions, confidentialité, crédits et licences, marques. */
class LegalActivity : BaseActivity() {

    private lateinit var scroll: ScrollView
    private lateinit var title: TextView
    private lateinit var text: TextView
    private var shown = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_legal)
        scroll = findViewById(R.id.legalScroll)
        title = findViewById(R.id.legalTitle)
        text = findViewById(R.id.legalText)
        findViewById<TextView>(R.id.legalVersion).text = Updater.installedLabel(this)
        val rows = Rows(this, findViewById<LinearLayout>(R.id.legalMenu))
        val views = Legal.DOCS.mapIndexed { i, d ->
            rows.action(d.title) { show(i, enter = true) }.also { v ->
                v.setOnFocusChangeListener { _, has -> if (has) show(i, enter = false) }
            }
        }
        if (!Legal.accepted()) {
            rows.action("J'accepte les conditions", "Pour commencer à utiliser l'application.") {
                Legal.ask(this) { finish() }
            }
        }
        // Le texte défile avec haut / bas ; gauche revient au menu
        scroll.setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN) {
                views.getOrNull(shown)?.requestFocus()
                true
            } else false
        }
        show(intent.getIntExtra(EXTRA_DOC, 0), enter = false)
        views.getOrNull(intent.getIntExtra(EXTRA_DOC, 0))?.let { v -> v.post { v.requestFocus() } }
    }

    private fun show(i: Int, enter: Boolean) {
        val d = Legal.DOCS.getOrNull(i) ?: return
        if (i != shown) {
            shown = i
            title.text = d.title
            text.text = Legal.format(this, d.body)
            scroll.scrollTo(0, 0)
            text.alpha = 0f
            text.translationY = 8 * resources.displayMetrics.density
            text.animate().alpha(1f).translationY(0f).setDuration(260).start()
        }
        if (enter) scroll.requestFocus()
    }

    companion object {
        const val EXTRA_DOC = "doc"
    }
}
