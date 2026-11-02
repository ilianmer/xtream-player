package com.ilian.xtreamplayer

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Barre de pronostic : trois segments (domicile, nul, extérieur) aux couleurs des équipes,
 * proportionnels aux chances tirées des cotes, avec les pourcentages dessous (le favori en blanc).
 */
class OddsBarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val dn = ctx.resources.displayMetrics.density
    private val sp = ctx.resources.displayMetrics.scaledDensity

    /** Épaisseur de la barre (px). */
    var barH = 4f * dn
        set(v) {
            field = v
            requestLayout()
        }

    /** Pourcentages sous la barre. */
    var labels = true
        set(v) {
            field = v
            requestLayout()
        }

    /** Taille du texte des pourcentages (sp). */
    var textSp = 9.5f
        set(v) {
            field = v
            label.textSize = v * sp
            strong.textSize = v * sp
            requestLayout()
        }

    private var probs: OddsMath.Probs? = null
    private var names = arrayOfNulls<String>(3)
    private val colors = intArrayOf(0xFF5AA9FF.toInt(), 0x59FFFFFF, 0xFFFF8A65.toInt())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun font(id: Int, fallback: Typeface): Typeface = try {
        resources.getFont(id)
    } catch (_: Exception) {
        fallback
    }

    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * sp
        color = 0xFFB9BEC7.toInt()
        typeface = font(R.font.outfit_medium, Typeface.DEFAULT)
    }
    private val strong = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * sp
        color = 0xFFFFFFFF.toInt()
        typeface = font(R.font.outfit_semibold, Typeface.DEFAULT_BOLD)
    }
    private val r = RectF()
    private val gap = 2f * dn

    /** Chances + couleurs des équipes ; avec [homeName] / [awayName], les noms précèdent les pourcentages. */
    fun set(p: OddsMath.Probs?, home: Int, away: Int, homeName: String? = null, awayName: String? = null) {
        probs = p
        colors[0] = home
        colors[2] = away
        names[0] = homeName?.let { cut(it) }
        names[1] = if (homeName != null) "Nul" else null
        names[2] = awayName?.let { cut(it) }
        invalidate()
    }

    private fun cut(s: String) = if (s.length > 14) s.take(13).trimEnd() + "…" else s

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = label.fontMetrics
        val textH = if (labels) (fm.descent - fm.ascent) + 3 * dn else 0f
        val want = (paddingTop + paddingBottom + barH + textH).toInt() + 1
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(want, heightMeasureSpec))
    }

    private fun text(i: Int, v: Double): String {
        val n = names[i]
        return if (n == null) OddsMath.pct(v) else "$n ${OddsMath.pct(v)}"
    }

    override fun onDraw(c: Canvas) {
        val p = probs ?: return
        val x0 = paddingLeft.toFloat()
        val w = (width - paddingLeft - paddingRight).toFloat()
        if (w <= 0f) return
        val dr = p.draw
        val parts = floatArrayOf(p.home.toFloat(), (dr ?: 0.0).toFloat(), p.away.toFloat())
        val count = parts.count { it > 0f }
        val total = parts.sum()
        if (count == 0 || total <= 0f) return
        val avail = w - gap * (count - 1)
        // Chaque issue garde un segment visible, même à 3 %
        val ws = FloatArray(3) { i -> if (parts[i] > 0f) maxOf(barH * 1.5f, avail * parts[i] / total) else 0f }
        val sum = ws.sum()
        for (i in 0..2) ws[i] = ws[i] * avail / sum
        val top = paddingTop.toFloat()
        val rad = barH / 2f
        val starts = FloatArray(3)
        val ends = FloatArray(3)
        var x = x0
        for (i in 0..2) {
            starts[i] = x
            ends[i] = x
            if (ws[i] <= 0f) continue
            r.set(x, top, x + ws[i], top + barH)
            fill.color = colors[i]
            c.drawRoundRect(r, rad, rad, fill)
            ends[i] = x + ws[i]
            x += ws[i] + gap
        }
        if (!labels) return
        val base = top + barH + 3 * dn - label.fontMetrics.ascent
        val homeFav = p.home >= p.away
        val left = text(0, p.home)
        val right = text(2, p.away)
        val pl = if (homeFav) strong else label
        val pr = if (homeFav) label else strong
        val lw = pl.measureText(left)
        val rw = pr.measureText(right)
        c.drawText(left, x0, base, pl)
        c.drawText(right, x0 + w - rw, base, pr)
        if (dr != null) {
            val mid = text(1, dr)
            val mw = label.measureText(mid)
            val lo = x0 + lw + 8 * dn
            val hi = x0 + w - rw - 8 * dn - mw
            if (lo <= hi) c.drawText(mid, ((starts[1] + ends[1]) / 2f - mw / 2f).coerceIn(lo, hi), base, label)
        }
    }
}

/** Petite étiquette dorée (« Match serré », « Exploit en cours »…). */
object OddsTag {
    fun bind(v: TextView, t: OddsMath.Tag?, short: Boolean) {
        if (t == null) {
            v.visibility = View.GONE
            return
        }
        v.visibility = View.VISIBLE
        v.text = if (short) t.short else t.label
    }

    fun view(act: Activity, t: OddsMath.Tag, short: Boolean = false): TextView = TextView(act, null, 0, R.style.Semi).apply {
        val d = act.resources.displayMetrics.density
        text = if (short) t.short else t.label
        textSize = 10f
        setTextColor(act.getColor(R.color.accent))
        setBackgroundResource(R.drawable.tag_pill_bg)
        setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
        setSingleLine()
    }
}

/**
 * Fiche des cotes d'un match : pronostic, tableau des bookmakers (meilleure cote de chaque colonne en doré,
 * flèche si elle a bougé depuis la première fois), et « Regarder » pour aller droit au match.
 */
object OddsSheet {
    fun show(act: Activity, m: Sport.Match, onWatch: (() -> Unit)?) {
        if (act.isFinishing || act.isDestroyed) return
        val o = m.odds
        val p = OddsMath.probs(o)
        if (o == null || p == null) {
            Ui.toast(act, "Pas encore de cotes pour ce match.")
            return
        }
        val d = act.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val w = px(500)
        val dlg = Dialog(act, R.style.SheetDialog)
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.sheet_bg)
            setPadding(px(26), px(20), px(26), px(16))
            clipChildren = false
            clipToPadding = false
        }
        // Titre et moment du match
        panel.addView(TextView(act, null, 0, R.style.Semi).apply {
            text = "${m.home.short} – ${m.away.short}"
            textSize = 20f
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(TextView(act, null, 0, R.style.Txt).apply {
            text = moment(m)
            textSize = 12f
            setTextColor(act.getColor(R.color.text_dim))
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(2) })
        // Pronostic
        val (ch, ca) = OddsMath.colors(m)
        panel.addView(OddsBarView(act).apply {
            barH = 8 * d
            textSp = 12.5f
            set(p, ch, ca, m.home.short, m.away.short)
        }, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16) })
        OddsMath.tag(m)?.let { t ->
            panel.addView(OddsTag.view(act, t), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(10)
            })
        }
        // Tableau des bookmakers
        val books = OddsMath.books(o)
        val hasDraw = books.any { it.draw != null }
        val (bh, bd, ba) = OddsMath.best(o)
        val first = OddsMath.firstBest(m)
        val table = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.odds_table_bg)
            setPadding(px(14), px(8), px(14), px(8))
        }
        table.addView(line(act, "Bookmaker", listOfNotNull("1", if (hasDraw) "N" else null, "2").map { Cell(it, false, null) }, header = true))
        for (b in books) {
            val cells = ArrayList<Cell>()
            cells.add(cell(b.home, bh, first?.first))
            if (hasDraw) cells.add(cell(b.draw, bd, first?.second))
            cells.add(cell(b.away, ba, first?.third))
            table.addView(line(act, b.name, cells, header = false))
        }
        panel.addView(table, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14) })
        // Note
        val parts = ArrayList<String>()
        if (books.size > 1) parts.add("En doré : la meilleure cote.")
        OddsMath.age(o.at).takeIf { it.isNotEmpty() }?.let { parts.add("Cotes mises à jour $it.") }
        parts.add("Indicatif : l'appli ne propose aucun pari.")
        if (o.books.isEmpty() && Prefs.oddsKey.isBlank()) parts.add("Ajoute une clé The Odds API (Réglages, Sport) pour comparer Winamax, Betclic, Unibet, PMU et NetBet.")
        panel.addView(TextView(act, null, 0, R.style.Txt).apply {
            text = parts.joinToString(" ")
            textSize = 11f
            setTextColor(act.getColor(R.color.text_faint))
            setLineSpacing(0f, 1.12f)
        }, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })
        // Boutons
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
            clipToPadding = false
            setPadding(0, px(16), 0, px(2))
        }
        bar.addView(Sheet.pill(act, "Fermer", false, false) { dlg.dismiss() })
        var main: View? = null
        if (onWatch != null && !m.finished) {
            val watch = Sheet.pill(act, if (m.live) "Regarder le match" else "Chaîne du match", true, false) {
                dlg.dismiss()
                onWatch()
            }
            bar.addView(watch)
            main = watch
        }
        panel.addView(bar, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        dlg.setContentView(panel)
        dlg.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
        (main ?: bar.getChildAt(0))?.let { v -> v.post { v.requestFocus() } }
    }

    private class Cell(val text: String, val best: Boolean, val arrow: String?)

    /** Une cote du tableau : dorée si c'est la meilleure, avec une flèche si elle a bougé depuis la première fois. */
    private fun cell(v: Double?, best: Double?, first: Double?): Cell {
        if (v == null) return Cell("–", false, null)
        val isBest = best != null && v >= best - 1e-9
        val arrow = if (isBest && best != null && first != null) when {
            best > first * 1.03 -> "↑"
            best < first / 1.03 -> "↓"
            else -> null
        } else null
        return Cell(OddsMath.fmt(v), isBest, arrow)
    }

    private fun line(act: Activity, name: String, cells: List<Cell>, header: Boolean): LinearLayout {
        val d = act.resources.displayMetrics.density
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ((if (header) 24 else 32) * d).toInt()
        }
        row.addView(TextView(act, null, 0, if (header) R.style.Txt else R.style.Medium).apply {
            text = name
            textSize = if (header) 11f else 14f
            setTextColor(act.getColor(if (header) R.color.text_faint else R.color.text))
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for (c in cells) {
            row.addView(TextView(act, null, 0, if (c.best) R.style.Semi else R.style.Txt).apply {
                text = if (c.arrow != null) "${c.text} ${c.arrow}" else c.text
                textSize = if (header) 11f else 14f
                gravity = Gravity.END
                fontFeatureSettings = "tnum"
                setTextColor(act.getColor(if (header) R.color.text_faint else if (c.best) R.color.accent else R.color.text))
            }, LinearLayout.LayoutParams((84 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return row
    }

    /** « Ligue 1 · ce soir à 21:00 », « Ligue 1 · 62' · 1 – 0 », « Ligue 1 · terminé · 2 – 1 ». */
    private fun moment(m: Sport.Match): String {
        val score = "${m.home.score ?: 0} – ${m.away.score ?: 0}"
        val whenText = when {
            m.live -> "${m.status} · $score"
            m.finished -> "Terminé · $score"
            else -> {
                val c = Calendar.getInstance()
                val today = c.get(Calendar.DAY_OF_YEAR)
                c.timeInMillis = m.start
                val hm = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(m.start))
                when (c.get(Calendar.DAY_OF_YEAR) - today) {
                    0 -> (if (c.get(Calendar.HOUR_OF_DAY) >= 18) "ce soir" else "aujourd'hui") + " à $hm"
                    1 -> "demain à $hm"
                    else -> SimpleDateFormat("EEEE d 'à' HH:mm", Locale.FRANCE).format(Date(m.start))
                }
            }
        }
        return listOfNotNull(m.league.label, m.round?.takeIf { it.isNotBlank() }, whenText).joinToString(" · ")
    }
}
