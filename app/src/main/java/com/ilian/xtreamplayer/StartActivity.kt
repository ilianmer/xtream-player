package com.ilian.xtreamplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Ouverture de l'appli : petite animation du logo (avec son), pendant laquelle tout se prépare
 * (sélection « À la une », images, sport). Met à jour le catalogue quand c'est nécessaire,
 * puis ouvre le choix du profil (s'il y en a plusieurs) ou directement l'accueil.
 */
class StartActivity : BaseActivity() {

    private lateinit var tvProgress: TextView
    private lateinit var tvError: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var actions: View
    private lateinit var btnContinue: View
    private lateinit var splash: View

    @Volatile
    private var preloaded = false
    private var introDone = false
    private var leaving = false
    private var needSync = false
    private val startedAt = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        splash = findViewById(R.id.splash)
        tvProgress = findViewById(R.id.tvProgress)
        tvError = findViewById(R.id.tvError)
        spinner = findViewById(R.id.spinner)
        actions = findViewById(R.id.actions)
        btnContinue = findViewById(R.id.btnContinue)
        findViewById<View>(R.id.btnRetry).setOnClickListener { runSync() }
        btnContinue.setOnClickListener { next() }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val src = Session.source()
        if (src != null) {
            Session.profile()
            findViewById<TextView>(R.id.tvSource).text = src.name
            needSync = intent.getBooleanExtra(EXTRA_FORCE, false) || Sync.needed(src)
            if (!needSync) {
                thread(isDaemon = true, name = "preload") {
                    Preload.run(src)
                    preloaded = true
                }
                thread(isDaemon = true, name = "preload-sport") { Preload.runSlow(src) }
            }
        }
        playIntro()
    }

    // ---------- Animation ----------

    private fun playIntro() {
        val d = resources.displayMetrics.density
        val tile = findViewById<View>(R.id.splashTile)
        val play = findViewById<View>(R.id.splashPlay)
        val glow = findViewById<View>(R.id.splashGlow)
        tile.scaleX = 0.6f
        tile.scaleY = 0.6f
        play.translationX = -40 * d
        play.scaleX = 0.8f
        play.scaleY = 0.8f
        glow.scaleX = 0.3f
        glow.scaleY = 0.3f
        tile.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(1.3f)).setDuration(560).start()
        play.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f).setStartDelay(280)
            .setInterpolator(DecelerateInterpolator(2f)).setDuration(560).start()
        glow.animate().alpha(1f).scaleX(1.6f).scaleY(1.6f).setStartDelay(460).setDuration(520).withEndAction {
            glow.animate().alpha(0.4f).scaleX(1.25f).scaleY(1.25f).setDuration(420).start()
        }.start()
        Bg.main.postDelayed({ if (!isFinishing) Sounds.intro(this) }, 260)
        Bg.main.postDelayed({
            introDone = true
            maybeLeave()
        }, 1_450)
    }

    /** On sort quand l'animation est finie et que l'essentiel est prêt (au plus 4 s au total). */
    private fun maybeLeave() {
        if (leaving || isFinishing) return
        val src = Session.source()
        val ready = src == null || needSync || preloaded || System.currentTimeMillis() - startedAt > 4_000
        if (!introDone || !ready) {
            Bg.main.postDelayed({ maybeLeave() }, 120)
            return
        }
        leaving = true
        // On « entre » dans le play jaune
        splash.animate().scaleX(12f).scaleY(12f).alpha(0f).setInterpolator(AccelerateInterpolator(1.7f)).setDuration(520)
            .withEndAction { afterIntro() }.start()
    }

    private fun afterIntro() {
        if (isFinishing) return
        val src = Session.source()
        if (src == null) {
            startActivity(Intent(this, SourceActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
            return
        }
        if (needSync) {
            splash.visibility = View.GONE
            findViewById<View>(R.id.syncBox).visibility = View.VISIBLE
            runSync()
        } else {
            next()
        }
    }

    // ---------- Mise à jour du catalogue ----------

    private fun runSync() {
        val src = Session.source() ?: return
        tvError.visibility = View.GONE
        actions.visibility = View.GONE
        spinner.visibility = View.VISIBLE
        tvProgress.text = "Connexion…"
        Bg.task(this, {
            Sync.run(src) { msg -> Bg.main.post { if (!isDestroyed) tvProgress.text = msg } }
            Preload.run(src)
        }) { r ->
            r.onSuccess {
                Session.reset()
                thread(isDaemon = true) { Preload.runSlow(src) }
                next()
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

    /** Plusieurs profils : « Qui regarde ? ». Sinon l'accueil directement. */
    private fun next() {
        val src = Session.source() ?: return
        val many = Db.profiles(src.id).size > 1
        startActivity(Intent(this, if (many) ProfilesActivity::class.java else MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    companion object {
        const val EXTRA_FORCE = "force"
    }
}
