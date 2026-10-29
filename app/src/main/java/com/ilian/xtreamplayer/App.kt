package com.ilian.xtreamplayer

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Db.init(this)
        // Le bon profil dès le démarrage (même si Android relance l'appli directement sur un écran)
        try {
            Session.profile()
        } catch (_: Exception) {
        }
    }
}
