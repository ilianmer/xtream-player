package com.ilian.xtreamplayer

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Db.init(this)
    }
}
