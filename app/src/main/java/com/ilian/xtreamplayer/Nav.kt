package com.ilian.xtreamplayer

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Base de tous les écrans : son de navigation doux (au lieu du clic système),
 * avec un léger délai entre deux sons quand on maintient une flèche.
 */
open class BaseActivity : Activity() {
    /** Rebond en bout de liste (désactivé dans le lecteur, qui gère ses flèches lui-même). */
    protected open val edgeBump = true

    /** Le zoom de sélection déborde un peu : les panneaux ne le coupent plus dans leur marge intérieure. */
    override fun onResume() {
        super.onResume()
        // Écran resté dans la cadence de la dernière lecture (évite un écran noir à chaque sortie du lecteur)
        DisplayRate.keep(this)
    }

    override fun onContentChanged() {
        super.onContentChanged()
        (findViewById<View>(android.R.id.content) as? ViewGroup)?.let { content ->
            Fx.relaxClipping(content)
            val root = content.getChildAt(0)
            if (root?.background != null) {
                // L'écran peint déjà tout son fond (opaque) : inutile que la fenêtre en dessine un dessous
                window.setBackgroundDrawable(null)
                Backgrounds.bake(root)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // OK : l'élément s'enfonce légèrement puis rebondit (retour visuel de l'appui, comme sur Apple TV)
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            val v = currentFocus
            if (v != null && v.isClickable && v !is android.widget.EditText) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) Fx.press(v, true)
                else if (event.action == KeyEvent.ACTION_UP) Fx.press(v, false)
            }
        }
        // Bas sur la dernière ligne d'une liste : on reste dedans, avec un petit rebond (comme les plateformes de streaming)
        if (edgeBump && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && Fx.atListBottom(currentFocus)) {
            if (event.repeatCount % 3 == 0) Fx.bump(Fx.listItemOf(currentFocus) ?: currentFocus, 0, 1)
            return true
        }
        // Ailleurs : bas (ou haut) qui ferait sauter le focus dans l'autre sens, ou nulle part -> rebond sur place
        if (edgeBump && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP) &&
            Fx.wrongWay(currentFocus, event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            if (event.repeatCount % 3 == 0) Fx.bump(currentFocus, 0, if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
            return true
        }
        val nav = event.action == KeyEvent.ACTION_DOWN && event.keyCode in NAV_KEYS
        val before = if (nav) currentFocus else null
        val handled = super.dispatchKeyEvent(event)
        if (nav && currentFocus !== before && currentFocus != null) Sounds.move(this)
        return handled
    }

    companion object {
        private val NAV_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
        )
    }
}

/** Petit son « tic » synthétisé (doux, court), joué à chaque déplacement du focus. */
object Sounds {
    private var pool: SoundPool? = null
    private var tick = 0
    private var last = 0L

    private fun ensure(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            .build()
        val f = File(ctx.cacheDir, "tick_v2.wav")
        if (!f.exists()) writeTick(f)
        tick = p.load(f.path, 1)
        pool = p
    }

    fun move(ctx: Context) {
        val level = Prefs.navSound
        if (level == 0) return
        val now = System.currentTimeMillis()
        if (now - last < 70) return
        last = now
        try {
            ensure(ctx)
            val v = if (level == 1) 0.22f else 0.5f
            pool?.play(tick, v, v, 1, 0, 1f)
        } catch (_: Exception) {
        }
    }

    /** Son d'ouverture : deux notes graves et rondes (« ta-dum »), joué une fois au lancement. */
    fun intro(ctx: Context) {
        if (Prefs.navSound == 0) return
        try {
            val f = File(ctx.cacheDir, "intro_v1.wav")
            if (!f.exists()) writeIntro(f)
            val mp = android.media.MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            mp.setDataSource(f.path)
            mp.setVolume(0.55f, 0.55f)
            mp.setOnCompletionListener { it.release() }
            mp.prepare()
            mp.start()
        } catch (_: Exception) {
        }
    }

    private fun writeIntro(f: File) {
        val rate = 44_100
        val n = rate * 1300 / 1000
        val pcm = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        fun hit(t: Double, start: Double, freq: Double, decay: Double): Double {
            if (t < start) return 0.0
            val x = t - start
            val env = (1 - exp(-x * 220)) * exp(-x * decay)
            return env * (sin(2 * PI * freq * x) * 0.75 + sin(2 * PI * freq * 2 * x) * 0.22 + sin(2 * PI * freq * 3 * x) * 0.06)
        }
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val s = hit(t, 0.0, 98.0, 9.0) * 0.8 + hit(t, 0.24, 146.8, 3.2)
            pcm.putShort((s.coerceIn(-1.0, 1.0) * 0.6 * Short.MAX_VALUE).toInt().toShort())
        }
        writeWav(f, pcm.array(), rate)
    }

    private fun writeWav(f: File, data: ByteArray, rate: Int) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.size)
        FileOutputStream(f).use {
            it.write(h.array())
            it.write(data)
        }
    }

    /** 28 ms, 1 400 Hz qui s'éteint vite : un « tic » feutré plutôt qu'un clic sec. */
    private fun writeTick(f: File) {
        val rate = 44_100
        val n = rate * 28 / 1000
        val pcm = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = exp(-t * 170) * (1 - exp(-t * 4000))
            val s = (sin(2 * PI * 1400 * t) * 0.7 + sin(2 * PI * 2800 * t) * 0.15) * env
            pcm.putShort((s * 0.55 * Short.MAX_VALUE).toInt().toShort())
        }
        val data = pcm.array()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.size)
        FileOutputStream(f).use {
            it.write(h.array())
            it.write(data)
        }
    }
}

/** Animations et règles de focus communes. */
object Fx {
    private val grow = OvershootInterpolator(0.9f)
    private val shrink = DecelerateInterpolator()

    private fun isScroller(v: View) =
        v is RecyclerView || v is ScrollView || v is HorizontalScrollView || v is AbsListView

    /**
     * Les conteneurs ne coupent plus leurs enfants dans leur marge intérieure (sauf les listes qui défilent),
     * pour que le contour et le zoom de l'élément sélectionné restent entiers.
     */
    fun relaxClipping(root: ViewGroup) {
        if (!isScroller(root)) root.clipToPadding = false
        if (root is RecyclerView) {
            // Liste de taille fixe à l'écran : son contenu qui change ne force pas à remesurer tout l'écran
            val lp = root.layoutParams
            if (lp != null && lp.width != ViewGroup.LayoutParams.WRAP_CONTENT && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) root.setHasFixedSize(true)
            return
        }
        for (i in 0 until root.childCount) (root.getChildAt(i) as? ViewGroup)?.let { relaxClipping(it) }
    }

    private var dens = 0f
    private fun density(v: View): Float {
        if (dens == 0f) dens = v.resources.displayMetrics.density
        return dens
    }

    /** Zoom doux et ombre quand un élément prend le focus. */
    fun focus(v: View, has: Boolean, scale: Float = 1.07f) {
        if (has) {
            // Éléments ajoutés après coup : on libère aussi leurs conteneurs proches
            var p = v.parent
            var n = 0
            while (p is ViewGroup && n < 3 && !isScroller(p)) {
                p.clipToPadding = false
                p = p.parent
                n++
            }
        }
        v.animate().cancel()
        v.setTag(R.id.focus_scale, if (has) scale else 1f)
        // withLayer : pendant le zoom, la carte est dessinée une fois dans une texture puis agrandie
        // (au lieu d'être entièrement redessinée à chaque image)
        v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f)
            .translationZ(if (has) density(v) * 10 else 0f)
            .setInterpolator(if (has) grow else shrink)
            .setDuration(if (has) 220 else 160)
            .withLayer()
            .start()
    }

    /**
     * Rangée horizontale : gauche / droite passent d'un élément au suivant sans jamais
     * « s'échapper » ailleurs à l'écran. [escapeLeft] laisse sortir à gauche du premier élément.
     */
    fun lockRow(rv: RecyclerView, escapeLeft: Boolean = false, escapeRight: Boolean = false) {
        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.setOnKeyListener { v, code, e ->
                    if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val step = when (code) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        else -> return@setOnKeyListener false
                    }
                    val pos = rv.getChildAdapterPosition(v)
                    val count = rv.adapter?.itemCount ?: 0
                    if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
                    val target = pos + step
                    if (target < 0) {
                        if (!escapeLeft && e.repeatCount % 3 == 0) bump(v, -1, 0)
                        return@setOnKeyListener !escapeLeft
                    }
                    if (target >= count) {
                        if (!escapeRight && e.repeatCount % 3 == 0) bump(v, 1, 0)
                        return@setOnKeyListener !escapeRight
                    }
                    focusAt(rv, target)
                    true
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }

    /** Liste verticale qui contient [v] (en sautant les rangées horizontales), et l'élément de cette liste. */
    private fun verticalList(v: View?): Pair<RecyclerView, View>? {
        var child: View = v ?: return null
        var p = child.parent
        while (p is View) {
            if (p is RecyclerView) {
                val lm = p.layoutManager
                if (lm is LinearLayoutManager && lm.orientation == RecyclerView.VERTICAL) return p to child
            }
            child = p
            p = p.parent
        }
        return null
    }

    fun listItemOf(v: View?): View? = verticalList(v)?.second

    /** Vrai si [v] est sur la dernière ligne d'une liste verticale (ou d'une grille). */
    fun atListBottom(v: View?): Boolean {
        val (rv, item) = verticalList(v) ?: return false
        val count = rv.adapter?.itemCount ?: return false
        val pos = rv.getChildAdapterPosition(item)
        if (pos == RecyclerView.NO_POSITION || count == 0) return false
        val lm = rv.layoutManager
        return if (lm is androidx.recyclerview.widget.GridLayoutManager) {
            val span = lm.spanCount.coerceAtLeast(1)
            pos / span == (count - 1) / span
        } else pos == count - 1
    }

    /**
     * Hors des listes : vrai si la flèche ne mènerait nulle part, ou vers un élément situé de l'autre côté
     * (par exemple « bas » qui remonterait vers la recherche en haut de l'écran).
     */
    fun wrongWay(v: View?, down: Boolean): Boolean {
        v ?: return false
        if (verticalList(v) != null) return false
        val next = v.focusSearch(if (down) View.FOCUS_DOWN else View.FOCUS_UP) ?: return true
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationOnScreen(a)
        next.getLocationOnScreen(b)
        val curMid = a[1] + v.height / 2
        val nextMid = b[1] + next.height / 2
        return if (down) nextMid < curMid - 8 else nextMid > curMid + 8
    }

    /** Ajoute le reflet de focus (voir ShineDrawable) au premier plan d'une affiche. */
    fun shine(v: View?, radiusDp: Float = 9f) {
        v ?: return
        val fg = v.foreground
        if (fg is android.graphics.drawable.LayerDrawable && fg.numberOfLayers > 1 && fg.getDrawable(1) is ShineDrawable) return
        val s = ShineDrawable(radiusDp * density(v))
        v.foreground = if (fg == null) s else android.graphics.drawable.LayerDrawable(arrayOf(fg, s))
    }

    private val soft = android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)

    /** Textes qui changent (titre, résumé…) : ils réapparaissent en fondu en glissant de quelques points. */
    fun reveal(vararg views: View?) {
        for ((i, v) in views.withIndex()) {
            v ?: continue
            v.animate().cancel()
            v.alpha = 0f
            v.translationX = 8 * density(v)
            v.animate().alpha(1f).translationX(0f).setStartDelay(i * 40L).setDuration(320).setInterpolator(soft).start()
        }
    }

    private val pressIn = DecelerateInterpolator(1.6f)
    private val pressOut = OvershootInterpolator(2.4f)

    /** Appui sur OK : l'élément s'enfonce (94 %) puis revient à sa taille avec un petit rebond. */
    fun press(v: View, down: Boolean) {
        val rest = (v.getTag(R.id.focus_scale) as? Float) ?: 1f
        v.animate().cancel()
        if (down) {
            v.animate().scaleX(rest * 0.94f).scaleY(rest * 0.94f).setInterpolator(pressIn).setDuration(90).withLayer().start()
        } else {
            v.animate().scaleX(rest).scaleY(rest).setInterpolator(pressOut).setDuration(280).withLayer().start()
        }
    }

    /** Petit rebond : on ne peut pas aller plus loin dans cette direction. */
    fun bump(v: View?, dx: Int, dy: Int) {
        v ?: return
        val d = v.resources.displayMetrics.density * 10
        v.animate().translationX(dx * d).translationY(dy * d).setInterpolator(DecelerateInterpolator()).setDuration(70)
            .withEndAction {
                v.animate().translationX(0f).translationY(0f).setInterpolator(OvershootInterpolator(3f)).setDuration(220).start()
            }.start()
    }

    /**
     * Colonne : bas sur le dernier élément = rebond sur place (filet de sécurité en plus de la règle générale).
     */
    fun lockColumn(rv: RecyclerView) {
        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.setOnKeyListener { v, code, e ->
                    if (e.action != KeyEvent.ACTION_DOWN || code != KeyEvent.KEYCODE_DPAD_DOWN) return@setOnKeyListener false
                    val pos = rv.getChildAdapterPosition(v)
                    val count = rv.adapter?.itemCount ?: 0
                    if (pos != RecyclerView.NO_POSITION && pos >= count - 1) {
                        if (e.repeatCount % 3 == 0) bump(v, 0, 1)
                        true
                    } else false
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }

    /** Donne le focus à l'élément [pos] d'une liste, en la faisant défiler si besoin. */
    fun focusAt(rv: RecyclerView, pos: Int) {
        val vh = rv.findViewHolderForAdapterPosition(pos)
        if (vh != null) {
            vh.itemView.requestFocus()
            return
        }
        val lm = rv.layoutManager
        if (lm is LinearLayoutManager) lm.scrollToPositionWithOffset(pos, rv.paddingStart.coerceAtLeast(0)) else rv.scrollToPosition(pos)
        rv.post { rv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }
}
