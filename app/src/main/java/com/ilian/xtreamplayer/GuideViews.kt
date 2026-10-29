package com.ilian.xtreamplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fenêtre de temps affichée par le guide, partagée par toutes les lignes. */
class GuideState {
    /** Début de la fenêtre, en secondes. */
    var start: Long = floorHalfHour(nowSec())
    /** Durée affichée : 2 h 30. */
    val span: Long = 9_000L
    /** Heure « visée » par la sélection : conservée quand on monte ou descend d'une chaîne. */
    var selTime: Long = nowSec()

    val end: Long get() = start + span

    fun reset() {
        start = floorHalfHour(nowSec())
        selTime = nowSec()
    }

    /** Décale la fenêtre pour que [t] soit visible. Renvoie true si elle a bougé. */
    fun reveal(t: Long): Boolean {
        val old = start
        val min = floorHalfHour(nowSec())
        while (t >= start + span - 1_200) start += 1_800
        while (t < start && start > min) start -= 1_800
        if (start < min) start = min
        return start != old
    }

    companion object {
        fun nowSec(): Long = System.currentTimeMillis() / 1000
        fun floorHalfHour(t: Long): Long = t - Math.floorMod(t, 1_800L)
        private val HM = SimpleDateFormat("HH:mm", Locale.FRANCE)
        fun hm(sec: Long): String = synchronized(HM) { HM.format(Date(sec * 1000)) }
    }
}

private fun Context.dp(v: Float) = v * resources.displayMetrics.density
private fun Context.sp(v: Float) = v * resources.displayMetrics.scaledDensity

/** Une ligne du guide : les programmes d'une chaîne, en blocs posés sur l'axe du temps. */
class EpgRowView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var state: GuideState? = null
    var entries: List<EpgEntry>? = null
        set(v) {
            field = v
            invalidate()
        }
    /** Programme sélectionné (ligne active seulement), -1 sinon. */
    var selected = -1
        set(v) {
            field = v
            invalidate()
        }
    var active = false
        set(v) {
            field = v
            invalidate()
        }
    var emptyText = "Pas de programme"

    private val r = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.sp(12f)
        typeface = try {
            resources.getFont(R.font.outfit_semibold)
        } catch (_: Exception) {
            Typeface.DEFAULT_BOLD
        }
    }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.sp(10f)
        typeface = try {
            resources.getFont(R.font.outfit_regular)
        } catch (_: Exception) {
            Typeface.DEFAULT
        }
    }
    private val gap = ctx.dp(2f)
    private val radius = ctx.dp(8f)
    private val pad = ctx.dp(9f)

    /** Programmes encore à venir ou en cours (les programmes finis ne s'affichent plus). */
    fun visible(): List<EpgEntry> {
        val now = GuideState.nowSec()
        return entries.orEmpty().filter { it.end > now && it.end > it.start }
    }

    /** Index du programme qui couvre [t], sinon le suivant, sinon le dernier. */
    fun indexAt(t: Long): Int {
        val v = visible()
        if (v.isEmpty()) return -1
        v.indexOfFirst { it.start <= t && t < it.end }.takeIf { it >= 0 }?.let { return it }
        v.indexOfFirst { it.start >= t }.takeIf { it >= 0 }?.let { return it }
        return v.size - 1
    }

    override fun onDraw(c: Canvas) {
        val st = state ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val now = GuideState.nowSec()
        val v = visible()
        if (v.isEmpty()) {
            r.set(gap, 0f, w - gap, h)
            fill.color = if (active) Color.WHITE else 0x12FFFFFF
            c.drawRoundRect(r, radius, radius, fill)
            titlePaint.color = if (active) 0xFF0B0D12.toInt() else 0xFF8E939D.toInt()
            val label = if (entries == null) "Chargement du guide…" else emptyText
            c.drawText(label, pad, h / 2 + titlePaint.textSize / 3, titlePaint)
            return
        }
        for ((i, e) in v.withIndex()) {
            if (e.end <= st.start || e.start >= st.end) continue
            val a = ((maxOf(e.start, st.start) - st.start).toFloat() / st.span) * w
            val b = ((minOf(e.end, st.end) - st.start).toFloat() / st.span) * w
            if (b - a < gap * 3) continue
            r.set(a + gap, 0f, b - gap, h)
            val focus = active && i == selected
            val airing = e.start <= now && now < e.end
            fill.color = when {
                focus -> Color.WHITE
                airing -> 0x24FFFFFF
                else -> 0x13FFFFFF
            }
            c.drawRoundRect(r, radius, radius, fill)
            if (airing && !focus) {
                // Petite barre d'avancement en bas du programme en cours
                val p = ((now - e.start).toFloat() / (e.end - e.start)).coerceIn(0f, 1f)
                fill.color = 0xFFF5C451.toInt()
                c.drawRect(r.left + radius / 2, h - px(2f), r.left + radius / 2 + (r.width() - radius) * p, h, fill)
            }
            val room = r.width() - pad * 2
            if (room < px(14f)) continue
            titlePaint.color = if (focus) 0xFF0B0D12.toInt() else 0xFFF4F5F7.toInt()
            timePaint.color = if (focus) 0xFF3A3F4A.toInt() else 0xFFA9AEB8.toInt()
            val title = TextUtils.ellipsize(e.title, titlePaint, room, TextUtils.TruncateAt.END)
            val time = TextUtils.ellipsize("${GuideState.hm(e.start)} – ${GuideState.hm(e.end)}", timePaint, room, TextUtils.TruncateAt.END)
            val x = r.left + pad
            c.drawText(title, 0, title.length, x, h / 2 - px(1f), titlePaint)
            c.drawText(time, 0, time.length, x, h / 2 + timePaint.textSize + px(1f), timePaint)
        }
    }

    private fun px(v: Float) = context.dp(v)
}

/** Règle des heures au-dessus du guide, avec l'heure actuelle en pastille. */
class TimeRulerView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var state: GuideState? = null

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.sp(11f)
        color = 0xFFA9AEB8.toInt()
        typeface = try {
            resources.getFont(R.font.outfit_regular)
        } catch (_: Exception) {
            Typeface.DEFAULT
        }
    }
    private val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.sp(10f)
        color = 0xFF15161A.toInt()
        typeface = try {
            resources.getFont(R.font.outfit_bold)
        } catch (_: Exception) {
            Typeface.DEFAULT_BOLD
        }
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5C451.toInt() }
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val st = state ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        var t = st.start
        while (t < st.end) {
            val x = (t - st.start).toFloat() / st.span * w
            c.drawText(GuideState.hm(t), x + context.dp(4f), h / 2 + text.textSize / 3, text)
            t += 1_800
        }
        val now = GuideState.nowSec()
        if (now in st.start until st.end) {
            val x = (now - st.start).toFloat() / st.span * w
            val label = GuideState.hm(now)
            val tw = badgeText.measureText(label) + context.dp(12f)
            val left = (x - tw / 2).coerceIn(0f, w - tw)
            r.set(left, h / 2 - context.dp(9f), left + tw, h / 2 + context.dp(9f))
            c.drawRoundRect(r, context.dp(7f), context.dp(7f), fill)
            c.drawText(label, left + context.dp(6f), h / 2 + badgeText.textSize / 3, badgeText)
        }
    }
}
