package com.ilian.xtreamplayer

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.KeyEvent
import android.view.View
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
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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

    /** Zoom doux et ombre quand un élément prend le focus. */
    fun focus(v: View, has: Boolean, scale: Float = 1.07f) {
        v.animate().cancel()
        v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f)
            .translationZ(if (has) v.resources.displayMetrics.density * 10 else 0f)
            .setInterpolator(if (has) grow else shrink)
            .setDuration(if (has) 220 else 160).start()
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
                    if (target < 0) return@setOnKeyListener !escapeLeft
                    if (target >= count) return@setOnKeyListener !escapeRight
                    focusAt(rv, target)
                    true
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
