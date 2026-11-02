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
        // Le texte défile en douceur avec haut / bas (en continu si on reste appuyé) ; gauche revient au menu
        scroll.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    views.getOrNull(shown)?.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                    val down = code == KeyEvent.KEYCODE_DPAD_DOWN
                    if (scroll.canScrollVertically(if (down) 1 else -1)) {
                        val step = (scroll.height * (if (e.repeatCount > 0) 0.35f else 0.7f)).toInt()
                        scroll.smoothScrollBy(0, if (down) step else -step)
                    } else if (e.repeatCount % 3 == 0) Fx.bump(scroll, 0, if (down) 1 else -1)
                    true
                }
                else -> false
            }
        }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> paintThumb() }
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
            scroll.post { paintThumb() }
            text.alpha = 0f
            text.translationY = 8 * resources.displayMetrics.density
            text.animate().alpha(1f).translationY(0f).setDuration(260).start()
        }
        if (enter) scroll.requestFocus()
    }

    /** Barre de progression : position dans le texte, et longueur du texte par rapport à l'écran. */
    private fun paintThumb() {
        val track = findViewById<View>(R.id.legalTrack)
        val thumb = findViewById<View>(R.id.legalThumb)
        val content = scroll.getChildAt(0)?.height ?: 0
        val view = scroll.height - scroll.paddingTop - scroll.paddingBottom
        if (content <= view || track.height <= 0) {
            track.visibility = View.INVISIBLE
            thumb.visibility = View.INVISIBLE
            return
        }
        track.visibility = View.VISIBLE
        thumb.visibility = View.VISIBLE
        val h = (track.height * view / content.toFloat()).toInt().coerceAtLeast((24 * resources.displayMetrics.density).toInt())
        if (thumb.layoutParams.height != h) thumb.layoutParams = thumb.layoutParams.apply { height = h }
        val range = (content - view).coerceAtLeast(1)
        thumb.translationY = (track.height - h) * (scroll.scrollY.toFloat() / range).coerceIn(0f, 1f)
    }

    companion object {
        const val EXTRA_DOC = "doc"
    }
}
