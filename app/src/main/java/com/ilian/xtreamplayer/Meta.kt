package com.ilian.xtreamplayer

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import java.util.Locale

/** Ligne « 2025 · 2 h 01 · ★ 7,8 · Thriller » avec la note en couleur d'accent. */
object Meta {
    private const val SEP = "  ·  "

    fun line(parts: List<String?>, accent: Int): CharSequence {
        val sb = SpannableStringBuilder()
        for (p in parts) {
            if (p.isNullOrBlank()) continue
            if (sb.isNotEmpty()) sb.append(SEP)
            val start = sb.length
            sb.append(p)
            if (p.startsWith("★")) sb.setSpan(ForegroundColorSpan(accent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    fun rating(r: String?): String? {
        val v = r?.trim()?.takeIf { it.isNotEmpty() && it != "0" && it != "0.0" } ?: return null
        val d = v.replace(',', '.').toDoubleOrNull() ?: return "★ $v"
        if (d <= 0) return null
        return "★ " + String.format(Locale.FRANCE, "%.1f", d)
    }

    /** "01:44:00" -> "1 h 44", "00:42:00" -> "42 min". */
    fun duration(d: String?): String? {
        if (d.isNullOrBlank() || d == "00:00:00") return null
        val parts = d.split(':').mapNotNull { it.trim().toIntOrNull() }
        if (parts.size == 3) {
            val h = parts[0]
            val m = parts[1]
            return if (h > 0) "$h h ${String.format(Locale.FRANCE, "%02d", m)}" else if (m > 0) "$m min" else null
        }
        return d
    }

    fun minutes(min: Int?): String? {
        if (min == null || min <= 0) return null
        return if (min >= 60) "${min / 60} h ${String.format(Locale.FRANCE, "%02d", min % 60)}" else "$min min"
    }

    fun firstGenre(g: String?): String? = g?.split(',', '/')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
