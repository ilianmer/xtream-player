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

    /** « Plus tard » sur la proposition de mise à jour : on ne redemande pas avant cette date. */
    var updateSnoozeUntil: Long
        get() = sp.getLong("update_snooze", 0L)
        set(v) = sp.edit().putLong("update_snooze", v).apply()

    /** Téléchargements retirés : anciens fichiers effacés une fois. */
    var downloadsPurged: Boolean
        get() = bool("downloads_purged_v1", false)
        set(v) = putBool("downloads_purged_v1", v)

    /** Préréglages de langue passés en mode strict (migration faite une fois). */
    var langsStrict: Boolean
        get() = bool("langs_strict_v1", false)
        set(v) = putBool("langs_strict_v1", v)

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

    /** Guide TV externe : voir XmlTv (France par défaut). */
    var epgSource: Int
        get() = int("epg_source", XmlTv.FRANCE)
        set(v) = putInt("epg_source", v)

    var epgCustomUrl: String
        get() = str("epg_custom_url", "")
        set(v) = putStr("epg_custom_url", v)

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

    /** Aperçu vidéo de la chaîne sélectionnée dans le guide du Direct. */
    var guidePreview: Boolean
        get() = bool("guide_preview", true)
        set(v) = putBool("guide_preview", v)

    /** Dernier profil utilisé pour une source. */
    fun profileFor(src: Long): Long = sp.getLong("profile_$src", -1L)

    fun setProfileFor(src: Long, id: Long) = sp.edit().putLong("profile_$src", id).apply()

    /** Son de navigation : 0 aucun, 1 doux, 2 plus marqué. */
    var navSound: Int
        get() = int("nav_sound", 1)
        set(v) = putInt("nav_sound", v)

    /** Cotes des bookmakers sur les matchs. */
    var oddsEnabled: Boolean
        get() = bool("odds", false)
        set(v) = putBool("odds", v)

    /** Clé The Odds API (facultative) pour comparer plusieurs bookmakers. */
    var oddsKey: String
        get() = str("odds_key", "")
        set(v) = putStr("odds_key", v)

    /** Scores de mes équipes et des matchs tendance pendant la lecture d'une chaîne. */
    var sportTicker: Boolean
        get() = bool("sport_ticker", true)
        set(v) = putBool("sport_ticker", v)

    /** Onglet Sport affiché dans la barre du haut. */
    var showSport: Boolean
        get() = bool("show_sport", true)
        set(v) = putBool("show_sport", v)
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

    /** Profil en cours de la source active (créé s'il n'existe pas). Met aussi la base sur ce profil. */
    fun profile(): Profile? {
        val s = source() ?: return null
        val id = Prefs.profileFor(s.id)
        val p = (if (id > 0) Db.profileById(id)?.takeIf { it.sourceId == s.id } else null) ?: Db.ensureProfile(s.id)
        Db.profile = p.id
        return p
    }

    fun selectProfile(p: Profile) {
        Prefs.setProfileFor(p.sourceId, p.id)
        Db.profile = p.id
    }

    fun reset() {
        cachedApi = null
        cachedKey = ""
        playlist = ItemList.EMPTY
        index = 0
    }
}
