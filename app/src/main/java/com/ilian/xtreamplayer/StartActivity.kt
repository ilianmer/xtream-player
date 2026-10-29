package com.ilian.xtreamplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView

/** Écran de démarrage : met à jour le catalogue quand c'est nécessaire, puis ouvre l'appli. */
class StartActivity : BaseActivity() {

    private lateinit var tvProgress: TextView
    private lateinit var tvError: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var actions: View
    private lateinit var btnContinue: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = Session.source()
        if (src == null) {
            startActivity(Intent(this, SourceActivity::class.java))
            finish()
            return
        }
        if (!intent.getBooleanExtra(EXTRA_FORCE, false) && !Sync.needed(src)) {
            openMain()
            return
        }

        setContentView(R.layout.activity_start)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        findViewById<TextView>(R.id.tvSource).text = src.name
        tvProgress = findViewById(R.id.tvProgress)
        tvError = findViewById(R.id.tvError)
        spinner = findViewById(R.id.spinner)
        actions = findViewById(R.id.actions)
        btnContinue = findViewById(R.id.btnContinue)

        findViewById<View>(R.id.btnRetry).setOnClickListener { runSync() }
        btnContinue.setOnClickListener { openMain() }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        runSync()
    }

    private fun runSync() {
        val src = Session.source() ?: return
        tvError.visibility = View.GONE
        actions.visibility = View.GONE
        spinner.visibility = View.VISIBLE
        tvProgress.text = "Connexion…"
        Bg.task(this, {
            Sync.run(src) { msg -> Bg.main.post { if (!isDestroyed) tvProgress.text = msg } }
        }) { r ->
            r.onSuccess {
                Session.reset()
                openMain()
            }.onFailure { e ->
                spinner.visibility = View.GONE
                tvProgress.text = ""
                tvError.text = "Mise à jour impossible : ${Bg.err(e)}"
                tvError.visibility = View.VISIBLE
                actions.visibility = View.VISIBLE
                val hasOld = (Db.source(src.id)?.lastSync ?: 0L) > 0L
                btnContinue.visibility = if (hasOld) View.VISIBLE else View.GONE
                findViewById<View>(R.id.btnRetry).requestFocus()
            }
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_FORCE = "force"
    }
}
