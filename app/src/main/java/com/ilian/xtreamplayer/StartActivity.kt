package com.ilian.xtreamplayer

import android.content.Intent
import android.content.pm.ApplicationInfo
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

        demoSetup()
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

    /** Captures d'écran automatiques (version de test seulement, jamais dans l'appli publiée) : source de démonstration. */
    private fun demoSetup() {
        val server = intent.getStringExtra("demo_server") ?: return
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (Db.sources().isNotEmpty()) return
        val api = XtreamApi(server, "demo", "demo")
        Prefs.activeSourceId = Db.addSource("Démo", api.base, "demo", "demo")
        Session.reset()
    }

    // ---------- Animation ----------

    private fun playIntro() {
        val d = resources.displayMetrics.density
        val box = findViewById<View>(R.id.splashTileBox)
        val play = findViewById<android.widget.ImageView>(R.id.splashPlay)
        val shine = findViewById<View>(R.id.splashShine)
        val glow = findViewById<View>(R.id.splashGlow)
        val word = findViewById<View>(R.id.splashWord)
        val wordA = findViewById<TextView>(R.id.splashWordA)
        val wordB = findViewById<TextView>(R.id.splashWordB)
        val auraGold = findViewById<View>(R.id.auraGold)
        val auraViolet = findViewById<View>(R.id.auraViolet)
        val spring = android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)

        // Le reflet ne déborde pas de la tuile (coins arrondis)
        box.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.25f)
            }
        }
        box.clipToOutline = true

        // 1. Les halos s'allument et dérivent doucement
        auraGold.scaleX = 0.7f
        auraGold.scaleY = 0.7f
        auraViolet.scaleX = 0.7f
        auraViolet.scaleY = 0.7f
        auraGold.animate().alpha(1f).scaleX(1f).scaleY(1f).translationXBy(30 * d).setDuration(1_600).setInterpolator(DecelerateInterpolator()).start()
        auraViolet.animate().alpha(0.9f).scaleX(1f).scaleY(1f).translationXBy(-30 * d).setStartDelay(120).setDuration(1_700).setInterpolator(DecelerateInterpolator()).start()

        // 2. La tuile se pose
        box.scaleX = 0.82f
        box.scaleY = 0.82f
        box.translationY = 14 * d
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setInterpolator(spring).setDuration(640).withLayer().start()

        // 3. Le play doré se trace puis se remplit (animation vectorielle, dessinée hors du fil principal)
        (play.drawable as? android.graphics.drawable.Animatable)?.start()

        // 4. Halo derrière la tuile, au moment où le play se remplit
        glow.scaleX = 0.4f
        glow.scaleY = 0.4f
        glow.animate().alpha(1f).scaleX(1.55f).scaleY(1.55f).setStartDelay(640).setDuration(520).setInterpolator(DecelerateInterpolator()).withEndAction {
            glow.animate().alpha(0.45f).scaleX(1.25f).scaleY(1.25f).setDuration(480).start()
        }.start()

        // 5. Reflet qui balaie la tuile
        shine.translationX = -120 * d
        shine.animate().alpha(1f).translationX(120 * d).setStartDelay(900).setDuration(620)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).withEndAction { shine.alpha = 0f }.start()

        // 6. Le nom apparaît et ses lettres se resserrent
        word.translationY = 10 * d
        word.animate().alpha(1f).translationY(0f).setStartDelay(760).setDuration(560).setInterpolator(spring).start()
        android.animation.ValueAnimator.ofFloat(0.32f, 0.01f).apply {
            startDelay = 760
            duration = 760
            interpolator = spring
            addUpdateListener {
                val ls = it.animatedValue as Float
                wordA.letterSpacing = ls
                wordB.letterSpacing = ls
            }
            start()
        }

        Bg.main.postDelayed({ if (!isFinishing) Sounds.intro(this) }, 260)
        Bg.main.postDelayed({
            introDone = true
            maybeLeave()
        }, 1_650)
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
        // On « entre » dans le play doré : le nom s'efface, puis zoom centré sur le play
        val box = findViewById<View>(R.id.splashTileBox)
        val loc = IntArray(2)
        val rootLoc = IntArray(2)
        box.getLocationOnScreen(loc)
        splash.getLocationOnScreen(rootLoc)
        splash.pivotX = (loc[0] - rootLoc[0]) + box.width * 0.55f
        splash.pivotY = (loc[1] - rootLoc[1]) + box.height * 0.5f
        findViewById<View>(R.id.splashWord).animate().alpha(0f).translationY(8 * resources.displayMetrics.density).setDuration(160).start()
        splash.animate().scaleX(14f).scaleY(14f).alpha(0f).setStartDelay(90).setInterpolator(AccelerateInterpolator(1.9f)).setDuration(560)
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
        paintSteps("Connexion")
        Bg.task(this, {
            Sync.run(src) { msg ->
                Bg.main.post {
                    if (!isDestroyed) {
                        tvProgress.text = msg
                        paintSteps(msg)
                    }
                }
            }
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

    private var stepNow = -1

    /** Étapes cochées au fur et à mesure (le message de progression commence par « Chaînes », « Films »…). */
    private fun paintSteps(msg: String) {
        val steps = listOf(R.id.stepLive to "Chaînes", R.id.stepMovie to "Films", R.id.stepSeries to "Séries")
        val cur = steps.indexOfFirst { msg.startsWith(it.second) }.let { if (it < 0 && stepNow < 0) 0 else if (it < 0) stepNow else it }
        if (cur == stepNow) return
        stepNow = cur
        steps.forEachIndexed { i, (id, _) ->
            val tv = findViewById<TextView>(id) ?: return@forEachIndexed
            val icon = when {
                i < cur -> R.drawable.step_done
                i == cur -> R.drawable.step_now
                else -> R.drawable.step_todo
            }
            tv.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            tv.setTextColor(getColor(if (i <= cur) R.color.text else R.color.text_faint))
            tv.animate().cancel()
            tv.alpha = 1f
            if (i == cur) {
                tv.scaleX = 0.9f
                tv.scaleY = 0.9f
                tv.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2.5f)).setDuration(320).start()
            }
        }
    }

    /**
     * Captures automatiques (version de test seulement) : ouvre directement l'écran demandé
     * (« demo_go » = onglet, réglages, fiche, lecteur…), pour des captures toujours au même endroit.
     */
    private fun demoGo(src: Source): Boolean {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        val go = intent.getStringExtra("demo_go") ?: return false
        val main = Intent(this, MainActivity::class.java)
        val target: Intent? = when {
            go in listOf("HOME", "LIVE", "SPORT", "MOVIE", "SERIES", "FAV") -> main.putExtra("demo_tab", go)
            go.startsWith("settings") -> Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SECTION, go.substringAfter(':', "0").toIntOrNull() ?: 0)
            go == "categories" -> Intent(this, CategoriesActivity::class.java).putExtra(CategoriesActivity.EXTRA_KIND, Kind.MOVIE.name)
            go == "profiles" -> Intent(this, ProfilesActivity::class.java)
            go == "search" -> Intent(this, SearchActivity::class.java)
            go == "movie" -> Db.page(src.id, Kind.MOVIE, null, null, 3, 1).firstOrNull()?.let {
                Intent(this, MovieActivity::class.java).putExtra(MainActivity.EXTRA_ITEM, it.toJson().toString())
            }
            go == "serie" -> Db.page(src.id, Kind.SERIES, null, null, 0, 1).firstOrNull()?.let {
                Intent(this, SeriesActivity::class.java).putExtra(MainActivity.EXTRA_ITEM, it.toJson().toString())
            }
            go == "player" -> {
                startActivity(main)
                Ui.play(this, ItemList.query(src.id, Kind.LIVE, null, null), 0)
                null
            }
            else -> null
        }
        if (target != null) {
            if (target.component?.className != MainActivity::class.java.name) startActivity(main)
            startActivity(target)
        }
        finish()
        return true
    }

    /** Plusieurs profils : « Qui regarde ? ». Sinon l'accueil directement. */
    private fun next() {
        val src = Session.source() ?: return
        if (demoGo(src)) return
        val many = Db.profiles(src.id).size > 1
        startActivity(Intent(this, if (many) ProfilesActivity::class.java else MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    companion object {
        const val EXTRA_FORCE = "force"
    }
}
