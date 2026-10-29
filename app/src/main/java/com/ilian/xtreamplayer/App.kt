package com.ilian.xtreamplayer

import android.app.Application

class App : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // L'appli passe en arrière-plan ou Android manque de mémoire : on rend de la place
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            Img.trim(level)
            Backgrounds.trim()
            Epg.trim()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Img.trim(TRIM_MEMORY_COMPLETE)
        Backgrounds.trim()
        Epg.trim()
    }

    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
        Prefs.init(this)
        Db.init(this)
        Img.init(this)
        // Le bon profil dès le démarrage (même si Android relance l'appli directement sur un écran)
        try {
            Session.profile()
            if (!Features.DOWNLOADS && !Prefs.downloadsPurged) {
                Downloads.purgeAll(this)
                Prefs.downloadsPurged = true
            }
            if (!Prefs.langsStrict) {
                Langs.tightenAll()
                Prefs.langsStrict = true
            }
        } catch (_: Exception) {
        }
        Thread {
            Db.housekeeping()
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }
}
