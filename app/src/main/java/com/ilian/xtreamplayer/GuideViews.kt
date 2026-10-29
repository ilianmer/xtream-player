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
        private val hmCache = object : LinkedHashMap<Long, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?) = size > 2_000
        }

        /** « 21:35 » (mis en cache par minute : le guide en affiche des centaines à chaque image). */
        fun hm(sec: Long): String {
            val minute = Math.floorDiv(sec, 60L)
            return synchronized(hmCache) {
                hmCache[minute] ?: HM.format(Date(minute * 60_000L)).also { hmCache[minute] = it }
            }
        }
    }
}

private fun Context.dp(v: Float) = v * resources.displayMetrics.density
private fun Context.sp(v: Float) = v * resources.displayMetrics.scaledDensity

/** Une ligne du guide : les programmes d'une chaîne, en blocs posés sur l'axe du temps. */
class EpgRowView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var state: GuideState? = null
    var entries: List<EpgEntry>? = null
        set(v) {
            if (field === v) return
            field = v
            visFor = null
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
    private val px1 = ctx.dp(1f)
    private val px2 = ctx.dp(2f)
    private val px14 = ctx.dp(14f)

    // Calculs mis de côté entre deux images : la liste visible, les horaires et les textes raccourcis.
    private var visFor: List<EpgEntry>? = null
    private var visMinute = -1L
    private var vis: List<EpgEntry> = emptyList()
    private var times: Array<String> = emptyArray()
    private var titleRoom = FloatArray(0)
    private var titleCut: Array<CharSequence?> = emptyArray()
    private var timeRoom = FloatArray(0)
    private var timeCut: Array<CharSequence?> = emptyArray()

    /** Programmes encore à venir ou en cours (les programmes finis ne s'affichent plus). */
    fun visible(): List<EpgEntry> {
        val now = GuideState.nowSec()
        val minute = now / 60
        val src = entries
        if (src !== visFor || minute != visMinute || visFor == null) {
            vis = src.orEmpty().filter { it.end > now && it.end > it.start }
            times = Array(vis.size) { i -> "${GuideState.hm(vis[i].start)} – ${GuideState.hm(vis[i].end)}" }
            titleRoom = FloatArray(vis.size) { -1f }
            titleCut = arrayOfNulls(vis.size)
            timeRoom = FloatArray(vis.size) { -1f }
            timeCut = arrayOfNulls(vis.size)
            visFor = src ?: emptyList()
            visMinute = minute
        }
        return vis
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
        for (i in v.indices) {
            val e = v[i]
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
                c.drawRect(r.left + radius / 2, h - px2, r.left + radius / 2 + (r.width() - radius) * p, h, fill)
            }
            val room = r.width() - pad * 2
            if (room < px14) continue
            titlePaint.color = if (focus) 0xFF0B0D12.toInt() else 0xFFF4F5F7.toInt()
            timePaint.color = if (focus) 0xFF3A3F4A.toInt() else 0xFFA9AEB8.toInt()
            // Texte raccourci recalculé seulement si la place disponible change
            val title = if (titleRoom[i] == room) titleCut[i]!! else
                TextUtils.ellipsize(e.title, titlePaint, room, TextUtils.TruncateAt.END).also { titleCut[i] = it; titleRoom[i] = room }
            val time = if (timeRoom[i] == room) timeCut[i]!! else
                TextUtils.ellipsize(times[i], timePaint, room, TextUtils.TruncateAt.END).also { timeCut[i] = it; timeRoom[i] = room }
            val x = r.left + pad
            c.drawText(title, 0, title.length, x, h / 2 - px1, titlePaint)
            c.drawText(time, 0, time.length, x, h / 2 + timePaint.textSize + px1, timePaint)
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
    private val d4 = ctx.dp(4f)
    private val d6 = ctx.dp(6f)
    private val d7 = ctx.dp(7f)
    private val d9 = ctx.dp(9f)
    private val d12 = ctx.dp(12f)

    override fun onDraw(c: Canvas) {
        val st = state ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        var t = st.start
        while (t < st.end) {
            val x = (t - st.start).toFloat() / st.span * w
            c.drawText(GuideState.hm(t), x + d4, h / 2 + text.textSize / 3, text)
            t += 1_800
        }
        val now = GuideState.nowSec()
        if (now in st.start until st.end) {
            val x = (now - st.start).toFloat() / st.span * w
            val label = GuideState.hm(now)
            val tw = badgeText.measureText(label) + d12
            val left = (x - tw / 2).coerceIn(0f, w - tw)
            r.set(left, h / 2 - d9, left + tw, h / 2 + d9)
            c.drawRoundRect(r, d7, d7, fill)
            c.drawText(label, left + d6, h / 2 + badgeText.textSize / 3, badgeText)
        }
    }
}
