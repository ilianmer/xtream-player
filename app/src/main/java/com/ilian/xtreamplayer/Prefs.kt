package com.ilian.xtreamplayer

import android.content.Context
import android.content.SharedPreferences

/** Réglages de l'appli, stockés sur l'appareil. */
object Prefs {
    const val DEFAULT_UA = "Dalvik/2.1.0 (Linux; U; Android 9; XtreamPlayer)"
    const val SMARTERS_UA = "IPTVSmartersPro"

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun putStr(k: String, v: String) = sp.edit().putString(k, v).apply()

    var activeSourceId: Long
        get() = sp.getLong("active_source", -1L)
        set(v) = sp.edit().putLong("active_source", v).apply()

    /** "ts" ou "m3u8" pour les chaînes en direct. */
    var liveFormat: String
        get() = str("live_format", "ts")
        set(v) = putStr("live_format", v)

    /** 0 = manuel, sinon nombre de jours entre deux mises à jour automatiques. */
    var updateDays: Int
        get() = int("update_days", 7)
        set(v) = putInt("update_days", v)

    var epgEnabled: Boolean
        get() = bool("epg", true)
        set(v) = putBool("epg", v)

    var epgPreload: Boolean
        get() = bool("epg_preload", true)
        set(v) = putBool("epg_preload", v)

    var mergeCategories: Boolean
        get() = bool("merge_cats", true)
        set(v) = putBool("merge_cats", v)

    /** "default", "smarters" ou "custom". */
    var uaMode: String
        get() = str("ua_mode", "default")
        set(v) = putStr("ua_mode", v)

    var uaCustom: String
        get() = str("ua_custom", "")
        set(v) = putStr("ua_custom", v)

    val userAgent: String
        get() = when (uaMode) {
            "smarters" -> SMARTERS_UA
            "custom" -> uaCustom.ifBlank { DEFAULT_UA }
            else -> DEFAULT_UA
        }

    var showRewatch: Boolean
        get() = bool("rewatch", true)
        set(v) = putBool("rewatch", v)

    var screenProtection: Boolean
        get() = bool("screen_protect", false)
        set(v) = putBool("screen_protect", v)

    /** 0 petite, 1 normale, 2 grande, 3 très grande. */
    var subtitleSize: Int
        get() = int("sub_size", 1)
        set(v) = putInt("sub_size", v)

    /** 0 désactivé, 1 jusqu'à la fin de la saison, 2 toute la série. */
    var autoplay: Int
        get() = int("autoplay", 1)
        set(v) = putInt("autoplay", v)

    var bufferSec: Int
        get() = int("buffer", 5)
        set(v) = putInt("buffer", v)

    var maxBufferSec: Int
        get() = int("max_buffer", 50)
        set(v) = putInt("max_buffer", v)

    var tmdbKey: String
        get() = str("tmdb_key", "")
        set(v) = putStr("tmdb_key", v)
}

/** État partagé entre les écrans pendant que l'appli tourne. */
object Session {
    private var cachedApi: XtreamApi? = null
    private var cachedKey = ""
    var playlist: ItemList = ItemList.EMPTY
    var index = 0
    /** Moment où la dernière lecture s'est arrêtée (pour la protection multi-écran). */
    var lastStopAt = 0L

    fun source(): Source? {
        Db.source(Prefs.activeSourceId)?.let { return it }
        val first = Db.sources().firstOrNull() ?: return null
        Prefs.activeSourceId = first.id
        return first
    }

    fun api(): XtreamApi? {
        val s = source() ?: return null
        val key = "${s.id}|${s.server}|${s.user}|${s.pass}"
        if (cachedApi == null || cachedKey != key) {
            cachedApi = try {
                XtreamApi(s.server, s.user, s.pass)
            } catch (_: Exception) {
                null
            }
            cachedKey = key
        }
        return cachedApi
    }

    fun reset() {
        cachedApi = null
        cachedKey = ""
        playlist = ItemList.EMPTY
        index = 0
    }
}
