package com.ilian.xtreamplayer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.ui.AspectRatioFrameLayout
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicLong
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class PlayerActivity : BaseActivity() {

    override val edgeBump = false

    private lateinit var src: Source
    private lateinit var api: XtreamApi
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var ovTitle: TextView
    private lateinit var ovNow: TextView
    private lateinit var ovNext: TextView
    private lateinit var ovClock: TextView
    private lateinit var ovLogo: ImageView
    private lateinit var ovLive: View
    private lateinit var ovStar: View
    private lateinit var ovProgress: View
    private lateinit var ovStart: TextView
    private lateinit var ovEnd: TextView
    private lateinit var ovBar: ProgressBar
    private lateinit var ovAudio: TextView
    private lateinit var ovSubs: TextView
    private lateinit var tracksBox: View
    private lateinit var zapPanel: View
    private lateinit var zapRows: List<View>
    private lateinit var tvError: TextView
    private lateinit var nextBox: View
    private lateinit var nextTitle: TextView
    private lateinit var btnNext: TextView
    private lateinit var nextBar: ProgressBar

    // ---------- Épisode suivant pendant le générique ----------
    /** Bulle masquée avec Retour pour cet épisode : on laisse finir, l'enchaînement se fera à la toute fin. */
    private var nextDismissed = false
    /** Moment où la bulle est apparue (début du compte à rebours). */
    private var nextShownAt = 0L
    private val nextCountdownMs = 12_000L
    private val nextWatch = object : Runnable {
        override fun run() {
            if (isFinishing) return
            // Direct : rien à surveiller ici, on repasse rarement
            if (isLive) {
                Bg.main.postDelayed(this, 2_000)
                return
            }
            checkNextEpisode()
            Bg.main.postDelayed(this, 250)
        }
    }

    private var player: ExoPlayer? = null
    private var index = 0
    private var useAltFormat = false
    private var epgToken = 0

    /** Le bandeau s'efface en fondu (et non d'un coup). */
    private val hideOverlay = Runnable {
        overlay.animate().cancel()
        overlay.animate().alpha(0f).setDuration(280).withEndAction {
            overlay.visibility = View.GONE
            overlay.alpha = 1f
        }.start()
        if (!alerting) ticker.animate().alpha(0f).setDuration(280).withEndAction {
            if (!alerting) ticker.visibility = View.GONE
            ticker.alpha = 1f
        }.start()
    }

    /** Le bandeau apparaît en fondu, le panneau du bas glisse légèrement vers le haut. */
    private fun showOverlay() {
        overlay.animate().cancel()
        val wasHidden = overlay.visibility != View.VISIBLE
        overlay.visibility = View.VISIBLE
        if (wasHidden) {
            overlay.alpha = 0f
            findViewById<View>(R.id.band)?.let { b ->
                b.translationY = 18 * resources.displayMetrics.density
                b.animate().translationY(0f).setDuration(320).setInterpolator(android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)).start()
            }
        }
        overlay.animate().alpha(1f).setDuration(220).start()
    }

    // ---------- Bandeau des scores ----------
    private lateinit var ticker: TextView
    private var tickerItems: List<Sport.Match> = emptyList()
    private var tickerIndex = 0
    private var alerting = false
    private val lastScores = HashMap<String, String>()
    private val tickerRefresh = object : Runnable {
        override fun run() {
            if (isFinishing || !isLive || !Prefs.sportTicker) return
            Bg.task(this@PlayerActivity, { Sport.get(50_000L) }) { r -> r.getOrNull()?.let { updateTicker(it) } }
            Bg.main.postDelayed(this, 60_000)
        }
    }
    private val tickerRotate = object : Runnable {
        override fun run() {
            if (alerting || overlay.visibility != View.VISIBLE || tickerItems.isEmpty()) return
            tickerIndex = (tickerIndex + 1) % tickerItems.size
            paintTicker()
            Bg.main.postDelayed(this, 4_000)
        }
    }
    private val endAlert = Runnable {
        alerting = false
        ticker.setBackgroundResource(R.drawable.player_glass)
        ticker.setTextColor(getColor(R.color.text))
        if (overlay.visibility == View.VISIBLE && tickerItems.isNotEmpty()) paintTicker() else ticker.visibility = View.GONE
    }

    private fun scoreLine(m: Sport.Match): String {
        val a = m.home.score ?: "0"
        val b = m.away.score ?: "0"
        return "${m.home.short} $a – $b ${m.away.short}  ·  ${m.status}"
    }

    private fun updateTicker(all: List<Sport.Match>) {
        val live = all.filter { it.live }
        val mine = live.filter { Sport.involvesFollowed(it) }
        val trending = live.filter { !Sport.involvesFollowed(it) }.sortedByDescending { Sport.trend(it) }.take(4)
        tickerItems = mine + trending
        // Un score qui change sur un de ces matchs : petite alerte, même bandeau fermé.
        for (m in tickerItems) {
            val sc = "${m.home.score}-${m.away.score}"
            val old = lastScores[m.key]
            lastScores[m.key] = sc
            if (old != null && old != sc && !alerting) alert(m)
        }
    }

    private fun paintTicker() {
        val m = tickerItems.getOrNull(tickerIndex) ?: return
        ticker.text = (if (Sport.involvesFollowed(m)) "Mes équipes  ·  " else "") + scoreLine(m)
        ticker.animate().cancel()
        ticker.alpha = 1f
        ticker.visibility = View.VISIBLE
    }

    private fun alert(m: Sport.Match) {
        alerting = true
        ticker.setBackgroundResource(R.drawable.accent_pill)
        ticker.setTextColor(getColor(R.color.on_accent))
        ticker.text = "Score ! " + scoreLine(m)
        ticker.animate().cancel()
        ticker.alpha = 1f
        ticker.visibility = View.VISIBLE
        Bg.main.removeCallbacks(endAlert)
        Bg.main.postDelayed(endAlert, 8_000)
    }
    private val zapNow = Runnable { play() }
    private var digits = ""
    private val applyDigits = Runnable { zapToNumber() }

    private val list: ItemList get() = Session.playlist
    private val current: Item? get() = list[index]

    /** Version réellement lue pour la position en cours (autre qualité de la même chaîne, par exemple). */
    private var playing: Item? = null
    private var playingIndex = -1
    private val now: Item? get() = playing?.takeIf { playingIndex == index } ?: current
    private val isLive: Boolean get() = current?.kind == Kind.LIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Session.source()
        val a = Session.api()
        if (s == null || a == null || list.size == 0) {
            finish()
            return
        }
        src = s
        api = a
        index = Session.index.coerceIn(0, list.size - 1)

        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        ovTitle = findViewById(R.id.ovTitle)
        ovNow = findViewById(R.id.ovNow)
        ovNext = findViewById(R.id.ovNext)
        ovClock = findViewById(R.id.ovClock)
        ovLogo = findViewById(R.id.ovLogo)
        ovLive = findViewById(R.id.ovLive)
        ovStar = findViewById(R.id.ovStar)
        ovProgress = findViewById(R.id.ovProgress)
        ovStart = findViewById(R.id.ovStart)
        ovEnd = findViewById(R.id.ovEnd)
        ovBar = findViewById(R.id.ovBar)
        ovAudio = findViewById(R.id.ovAudio)
        ovSubs = findViewById(R.id.ovSubs)
        tracksBox = findViewById(R.id.tracksBox)
        zapPanel = findViewById(R.id.zapPanel)
        zapRows = listOf(findViewById(R.id.zapNext), findViewById(R.id.zapCur), findViewById(R.id.zapPrev))
        zapRows[1].setBackgroundResource(R.drawable.zap_cur_bg)
        zapRows[0].alpha = 0.8f
        zapRows[2].alpha = 0.8f
        tvError = findViewById(R.id.tvError)
        nextBox = findViewById(R.id.nextBox)
        nextTitle = findViewById(R.id.nextTitle)
        btnNext = findViewById(R.id.btnNext)
        nextBar = findViewById(R.id.nextBar)
        btnNext.setOnClickListener { playNextEpisode() }
        ticker = findViewById(R.id.ticker)

        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        // Pendant une reconnexion ou un zapping, la dernière image reste affichée (pas d'écran noir)
        playerView.setKeepContentOnPlayerReset(true)
        // Indicateur de chargement aux couleurs de l'appli (anneau doré, plus fin)
        playerView.findViewById<android.widget.ProgressBar>(androidx.media3.ui.R.id.exo_buffering)?.let { pb ->
            pb.indeterminateTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
            pb.scaleX = 0.8f
            pb.scaleY = 0.8f
        }
        aspect = Prefs.aspectMode.coerceIn(0, ASPECTS.size - 1)
        playerView.resizeMode = ASPECTS[aspect].first
        displayRate = DisplayRate(this)
        stats = TextView(this).apply {
            setBackgroundResource(R.drawable.player_glass)
            setTextColor(getColor(R.color.text))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            visibility = View.GONE
        }
        (playerView.parent as FrameLayout).addView(
            stats,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                val m = (24 * resources.displayMetrics.density).toInt()
                setMargins(m, m * 3, m, m)
            }
        )
        playerView.useController = !isLive
        playerView.setShowSubtitleButton(true)
        applySubtitleStyle()
    }

    private fun applySubtitleStyle() {
        val factor = floatArrayOf(0.8f, 1f, 1.3f, 1.65f)[Prefs.subtitleSize.coerceIn(0, 3)]
        playerView.subtitleView?.let { sv ->
            sv.setApplyEmbeddedFontSizes(false)
            sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * factor)
            sv.setStyle(
                CaptionStyleCompat(
                    Color.WHITE, Color.argb(150, 0, 0, 0), Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, null
                )
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (list.size == 0) return
        initPlayer()
        Bg.main.removeCallbacks(guard)
        Bg.main.postDelayed(guard, 1_000)
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(netCallback)
            netRegistered = true
        } catch (_: Exception) {
        }
        play()
        Bg.main.removeCallbacks(nextWatch)
        Bg.main.postDelayed(nextWatch, 1_000)
        if (isLive && Prefs.sportTicker) {
            Bg.main.removeCallbacks(tickerRefresh)
            Bg.main.post(tickerRefresh)
        }
    }

    override fun onStop() {
        learnMatchChannel()
        savePosition()
        Bg.main.removeCallbacks(hideOverlay)
        Bg.main.removeCallbacks(zapNow)
        Bg.main.removeCallbacks(applyDigits)
        Bg.main.removeCallbacks(tickerRefresh)
        Bg.main.removeCallbacks(tickerRotate)
        Bg.main.removeCallbacks(endAlert)
        Bg.main.removeCallbacks(nextWatch)
        Bg.main.removeCallbacks(retry)
        Bg.main.removeCallbacks(statsTick)
        Bg.main.removeCallbacks(guard)
        if (netRegistered) {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(netCallback)
            } catch (_: Exception) {
            }
            netRegistered = false
        }
        fpsToken++
        player?.release()
        player = null
        Session.index = index
        Session.lastStopAt = System.currentTimeMillis()
        super.onStop()
    }

    /**
     * Tampon. Films et séries : réglage « Durée du tampon ». Direct : démarrage rapide, puis plus de marge
     * après une coupure (un flux en direct arrive en temps réel, l'avance prise au départ sert de coussin).
     */
    private fun loadControl(live: Boolean): DefaultLoadControl {
        if (live) {
            val (start, after) = when (Prefs.liveStart) {
                0 -> 1_200 to 4_000
                2 -> 4_000 to 10_000
                else -> 2_000 to 6_000
            }
            return DefaultLoadControl.Builder()
                .setBufferDurationsMs(20_000, 40_000, start, after)
                .setTargetBufferBytes(48 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }
        val buf = Prefs.bufferSec.coerceIn(1, 60) * 1000
        val maxB = max(Prefs.maxBufferSec * 1000, buf + 5_000)
        val minB = min(max(buf * 2, 15_000), maxB)
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(minB, maxB, buf, min(buf * 2, minB))
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    // ---------- Mesures (infos techniques) ----------
    private lateinit var displayRate: DisplayRate
    private lateinit var stats: TextView
    private var statsOn = false
    @Volatile
    private var videoDecoder: String? = null
    @Volatile
    private var audioDecoder: String? = null
    private var dropped = 0L
    private var measuredFps = 0f
    private val bytesIn = AtomicLong()
    private var lastBytes = 0L
    private var lastBytesAt = 0L
    private var bitrate = 0L
    private var aspect = 0

    /** Débit réel : on compte les octets reçus du serveur. */
    private val meter = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) bytesIn.addAndGet(bytesTransferred.toLong())
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    }

    // ---------- Cadence de la vidéo (pour caler l'écran en 50 / 60 Hz) ----------
    @Volatile
    private var fpsToken = 0
    private val fpsLock = Any()
    private val fpsDeltas = LongArray(60)
    private var fpsN = 0
    private var fpsSkip = 0
    private var fpsLast = 0L
    private var fpsDone = true

    private fun resetFps() {
        synchronized(fpsLock) {
            fpsN = 0
            fpsSkip = 0
            fpsDone = false
        }
        fpsToken++
        measuredFps = 0f
    }

    /** Appelé par le décodeur pour chaque image (fil de lecture : très court). */
    private val frameListener = VideoFrameMetadataListener { pts, _, format, _ ->
        lastFrameAt = SystemClock.elapsedRealtime()
        var result = 0f
        synchronized(fpsLock) {
            if (!fpsDone) {
                if (format.frameRate > 1f) {
                    result = format.frameRate
                } else if (fpsSkip < 15) {
                    fpsSkip++
                    fpsLast = pts
                } else {
                    val d = pts - fpsLast
                    fpsLast = pts
                    if (d in 4_000L..200_000L) fpsDeltas[fpsN++] = d
                    if (fpsN >= fpsDeltas.size) result = 1_000_000f / fpsDeltas.sorted()[fpsN / 2]
                }
                if (result > 0f) fpsDone = true
            }
        }
        if (result > 0f) {
            val token = fpsToken
            Bg.main.post { if (token == fpsToken && !isFinishing) onFrameRate(result) }
        }
    }

    private fun onFrameRate(raw: Float) {
        measuredFps = DisplayRate.snap(raw)
        if (Prefs.autoFrameRate) displayRate.match(measuredFps)
    }

    private fun initPlayer() {
        val live = isLive
        tunnelOn = true
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Prefs.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(if (live) 12_000 else 30_000)
            .setTransferListener(meter)
        val dataSource = DefaultDataSource.Factory(this, http)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val leveling = Prefs.volumeLeveling
        val passthrough = Prefs.audioPassthrough
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val b = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                // Sans passthrough : le stick décode le Dolby lui-même (son garanti sur les enceintes de la TV)
                if (!passthrough) b.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                if (leveling) b.setAudioProcessors(arrayOf<AudioProcessor>(VolumeLeveler()))
                return b.build()
            }
        }.setEnableDecoderFallback(true)
            // Décodeurs audio de secours (AC3, E-AC3, DTS, TrueHD…) quand le stick ne sait pas les lire
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val selector = DefaultTrackSelector(this)
        trackSelector = selector
        selector.setParameters(
            selector.buildUponParameters()
                .setTunnelingEnabled(Prefs.tunneling)
                .setPreferredAudioLanguages(Locale.getDefault().language, "fr")
                .setExceedRendererCapabilitiesIfNecessary(true)
        )

        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .setTrackSelector(selector)
            .setLoadControl(loadControl(live))
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .build()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true
        )
        p.setVideoFrameMetadataListener(frameListener)
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDecoder = decoderName
            }

            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                audioDecoder = decoderName
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                dropped += droppedFrames
            }
        })
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w("Player", "erreur ${error.errorCodeName}", error)
                val p = player ?: return
                // HLS : trop loin derrière le direct, on y revient simplement
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                // Mode tunnel refusé par le décodeur de cette chaîne : on repasse en mode classique
                if (Prefs.tunneling && tunnelOn && error.errorCode in 4001..4005) {
                    tunnelOn = false
                    trackSelector?.let { ts -> ts.setParameters(ts.buildUponParameters().setTunnelingEnabled(false)) }
                    play(keepFormat = true)
                    return
                }
                // En direct, si le format choisi ne passe pas, on tente l'autre (TS <-> HLS) une fois.
                if (isLive && !useAltFormat) {
                    useAltFormat = true
                    play(keepFormat = true)
                    return
                }
                // Match en direct : la chaîne ne passe pas, on essaie tout de suite la suivante
                if (isLive && Session.autoFallback && index + 1 < list.size) {
                    index++
                    Ui.toast(this@PlayerActivity, "Chaîne indisponible, essai de ${current?.name ?: "la suivante"}…")
                    play()
                    return
                }
                // Coupure passagère (réseau, serveur saturé) : on se reconnecte tout seul.
                // En direct, comme une box TV : on ne renonce jamais (on espace juste les essais).
                val delays = if (isLive) LIVE_RETRY_DELAYS else RETRY_DELAYS
                if (isLive || retries < delays.size) {
                    val delay = delays[retries.coerceAtMost(delays.size - 1)]
                    retries++
                    retryAt = if (isLive) 0L else (player?.currentPosition ?: 0L)
                    tvError.text = if (retries <= 6) "Reconnexion…" else
                        "La chaîne ne répond pas, nouvel essai toutes les ${delay / 1000} s.\nEssaie une autre chaîne, ou change le format du direct dans Réglages."
                    tvError.visibility = View.VISIBLE
                    Bg.main.removeCallbacks(retry)
                    Bg.main.postDelayed(retry, delay)
                    return
                }
                showError("Lecture impossible (${error.errorCodeName}).\nEssaie une autre chaîne, ou change le format du direct ou le User Agent dans Réglages.")
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    tvError.visibility = View.GONE
                    retries = 0
                    if (readyAt == 0L) {
                        readyAt = SystemClock.elapsedRealtime()
                        readyPos = player?.currentPosition ?: 0L
                    }
                }
                if (state == Player.STATE_BUFFERING && readyAt > 0L && isLive) noteStall()
                if (state != Player.STATE_ENDED) return
                val item = current ?: return
                if (item.kind == Kind.LIVE) {
                    play(keepFormat = true) // flux coupé : on relance
                    return
                }
                markFinished(item)
                if (item.kind == Kind.EPISODE && index + 1 < list.size) {
                    index++
                    play()
                } else {
                    finish()
                }
            }
        })
        playerView.player = p
        player = p
    }

    // ---------- Garde du direct : jamais figé, jamais en retard ----------

    private var trackSelector: DefaultTrackSelector? = null
    private var tunnelOn = true
    private var netRegistered = false
    private var readyAt = 0L
    private var readyPos = 0L
    private var bufferingSince = 0L
    @Volatile
    private var lastFrameAt = 0L
    private var lastPos = -1L
    private var lastPosAt = 0L
    private var lastReconnectAt = 0L
    private val stalls = ArrayList<Long>()
    /** Version plus légère choisie automatiquement après des coupures répétées (pour cette séance). */
    private var lighter: Item? = null

    /** Retour du réseau (Wi-Fi revenu) : on relance tout de suite au lieu d'attendre le prochain essai. */
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Bg.main.post {
                val p = player ?: return@post
                if (isFinishing) return@post
                if (tvError.visibility == View.VISIBLE || p.playbackState == Player.STATE_IDLE) {
                    Bg.main.removeCallbacks(retry)
                    useAltFormat = false
                    play(keepFormat = true)
                }
            }
        }
    }

    /** Ce que la lecture du direct tolère avant de se reconnecter, selon le réglage « Démarrage des chaînes ». */
    private fun stallLimitMs(): Long = when (Prefs.liveStart) {
        0 -> 6_000L
        2 -> 12_000L
        else -> 8_000L
    }

    private fun driftLimitMs(): Long = when (Prefs.liveStart) {
        0 -> 8_000L
        2 -> 25_000L
        else -> 14_000L
    }

    /**
     * Vérifie le direct chaque seconde : chargement qui n'en finit pas, image figée, position bloquée,
     * retard accumulé sur le direct après des coupures. Dans chaque cas : reconnexion immédiate
     * (le serveur renvoie aussitôt quelques secondes d'avance, bien plus vite que d'attendre).
     */
    private val guard = object : Runnable {
        override fun run() {
            if (isFinishing) return
            Bg.main.postDelayed(this, 1_000)
            val p = player ?: return
            if (!isLive || !p.playWhenReady || tvError.visibility == View.VISIBLE) return
            val now = SystemClock.elapsedRealtime()
            when (p.playbackState) {
                Player.STATE_BUFFERING -> {
                    if (bufferingSince == 0L) bufferingSince = now
                    // Au démarrage, le serveur peut mettre un peu plus de temps à répondre
                    val limit = if (readyAt == 0L) stallLimitMs() + 6_000L else stallLimitMs()
                    if (now - bufferingSince > limit) reconnect("chargement trop long")
                }
                Player.STATE_READY -> {
                    bufferingSince = 0L
                    val pos = p.currentPosition
                    if (p.isPlaying) {
                        if (pos != lastPos) {
                            lastPos = pos
                            lastPosAt = now
                        } else if (now - lastPosAt > 5_000L) {
                            reconnect("lecture bloquée")
                            return
                        }
                        if (tunnelOn.not() || !Prefs.tunneling) {
                            if (p.videoFormat != null && lastFrameAt > 0L && now - lastFrameAt > 4_000L) {
                                reconnect("image figée")
                                return
                            }
                        }
                    }
                    // Retard pris à cause des coupures : on revient au direct
                    if (readyAt > 0L && now - readyAt > 20_000L) {
                        val drift = (now - readyAt) - (pos - readyPos)
                        if (drift > driftLimitMs()) reconnect("retour au direct")
                    }
                }
                else -> bufferingSince = 0L
            }
        }
    }

    private fun reconnect(why: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReconnectAt < 4_000L) return
        lastReconnectAt = now
        Log.i("Player", "direct : $why")
        noteStall()
        useAltFormat = false
        play(keepFormat = true)
    }

    /**
     * Coupures répétées (3 en 2 minutes) sur une chaîne qui existe en version plus légère :
     * on passe sur celle-ci pour la séance, comme le ferait une box TV, plutôt que de couper sans cesse.
     */
    private fun noteStall() {
        val t = SystemClock.elapsedRealtime()
        stalls.add(t)
        stalls.removeAll { t - it > 120_000L }
        if (stalls.size < 3 || Session.autoFallback) return
        val base = current ?: return
        val cur = this.now ?: return
        val tier = MatchFinder.tier(cur.name).let { if (it == 0) 2 else it }
        if (tier <= 1) return
        Bg.task(this, { LiveQuality.variants(src.id, base) }) { r ->
            val next = r.getOrNull().orEmpty()
                .filter { MatchFinder.tier(it.name).let { t -> if (t == 0) 2 else t } < tier }
                .maxByOrNull { MatchFinder.tier(it.name) } ?: return@task
            if (isFinishing || current?.id != base.id) return@task
            stalls.clear()
            lighter = next
            Ui.toast(this, "Connexion lente : passage en ${LiveQuality.label(next.name)} pour rester fluide.")
            play(keepFormat = true)
        }
    }

    /** Reconnexions automatiques après une coupure (remis à zéro dès que la lecture repart). */
    private var retries = 0
    private var retryAt = 0L
    private val retry = Runnable {
        if (isFinishing) return@Runnable
        // On repart du format habituel ; l'autre sera retenté juste après si besoin
        useAltFormat = false
        play(keepFormat = true)
    }

    /** Début de lecture de la chaîne en cours (pour retenir la bonne chaîne d'un match). */
    private var channelStartAt = 0L

    /** Resté plus d'une minute sur cette chaîne pendant un match : c'est la bonne, pour ce match seulement. */
    private fun learnMatchChannel() {
        val key = Session.sportMatchKey ?: return
        val item = now ?: return
        if (!isLive || channelStartAt == 0L) return
        if (System.currentTimeMillis() - channelStartAt >= 60_000L) {
            MatchFinder.learn(key, item)
            Sport.cached().firstOrNull { it.key == key }?.let { SportPrefs.noteWatched(it, 2f) }
        }
    }

    private fun play(keepFormat: Boolean = false) {
        val base = current ?: return finish()
        val p = player ?: return
        if (!keepFormat) useAltFormat = false
        if (!keepFormat) channelStartAt = System.currentTimeMillis()
        // Qualité retenue pour cette chaîne, ou qualité préférée (pas pendant la recherche auto d'un match)
        if (!keepFormat) lighter = null
        val item = lighter ?: if (base.kind == Kind.LIVE && !Session.autoFallback) LiveQuality.resolve(src.id, base) else base
        playing = item
        playingIndex = index
        resetFps()
        readyAt = 0L
        bufferingSince = 0L
        lastFrameAt = 0L
        lastPos = -1L
        dropped = 0
        videoDecoder = null
        audioDecoder = null

        val format = if (useAltFormat) (if (Prefs.liveFormat == "ts") "m3u8" else "ts") else Prefs.liveFormat
        val url = api.streamUrl(item, format)
        val builder = MediaItem.Builder().setUri(url)
        if (item.kind == Kind.LIVE && format == "m3u8") {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            // Pas d'accélération / ralentissement pour rattraper le direct : c'est ce qui fait sauter l'image
            builder.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
        }

        tvError.visibility = View.GONE
        hideNext()
        nextDismissed = false
        if (!keepFormat) retries = 0
        val fromRetry = retryAt > 0
        val resume = when {
            item.kind == Kind.LIVE -> 0L
            retryAt > 0 -> retryAt.also { retryAt = 0L }
            else -> Db.progress(src.id, item)?.takeIf { !it.finished }?.pos ?: 0L
        }
        p.setMediaItem(builder.build(), resume)
        p.prepare()
        p.playWhenReady = true
        showInfo(item, withEpg = true)
        if (resume > 0 && !fromRetry) Ui.toast(this, "Reprise à ${Ui.time(resume)}")
        if (statsOn) {
            Bg.main.removeCallbacks(statsTick)
            Bg.main.post(statsTick)
        }
    }

    private fun showInfo(item: Item, withEpg: Boolean) {
        val live = item.kind == Kind.LIVE
        ovClock.text = hhmm(System.currentTimeMillis())
        ovTitle.text = if (live) "${item.num ?: (index + 1)} · ${item.name}" else item.name
        ovLive.visibility = if (live) View.VISIBLE else View.GONE
        ovStar.visibility = if (Db.isFav(src.id, item) || current?.let { it.id != item.id && Db.isFav(src.id, it) } == true) View.VISIBLE else View.GONE
        ovLogo.visibility = if (live) View.VISIBLE else View.GONE
        if (live) Img.load(ovLogo, item.icon, R.drawable.ph_live)
        tracksBox.visibility = if (live) View.VISIBLE else View.GONE
        if (live) paintTracks()
        zapPanel.visibility = if (live && list.size > 1) View.VISIBLE else View.GONE
        if (live && list.size > 1) paintZap()

        if (live) {
            ovNow.text = if (Prefs.epgEnabled) "" else "Guide TV désactivé dans les réglages"
            ovNext.text = ""
            ovProgress.visibility = View.GONE
        } else {
            ovNow.text = item.extra ?: ""
            ovNext.text = ""
            paintVodProgress()
        }
        showOverlay()
        Bg.main.removeCallbacks(hideOverlay)
        Bg.main.postDelayed(hideOverlay, 5_000)
        if (live && !alerting && tickerItems.isNotEmpty() && Prefs.sportTicker) {
            paintTicker()
            Bg.main.removeCallbacks(tickerRotate)
            Bg.main.postDelayed(tickerRotate, 4_000)
        }

        if (!live || !withEpg || !Prefs.epgEnabled) return
        val token = ++epgToken
        Epg.prefetch(api, item.id) { l ->
            if (token != epgToken || isFinishing) return@prefetch
            val (now, next) = Epg.nowAndNext(l)
            ovNow.text = if (now != null) labelled("Maintenant", now.title, true) else "Pas de guide TV pour cette chaîne"
            ovNext.text = next?.let { labelled("Ensuite", (if (it.start > 0) hhmm(it.start * 1000) + " · " else "") + it.title, false) } ?: ""
            if (now != null && now.start > 0 && now.end > now.start) {
                val t = System.currentTimeMillis() / 1000
                ovProgress.visibility = View.VISIBLE
                ovStart.text = hhmm(now.start * 1000)
                ovEnd.text = hhmm(now.end * 1000)
                ovBar.progress = ((t - now.start) * 1000 / (now.end - now.start)).toInt().coerceIn(0, 1000)
            }
        }
    }

    /** « Maintenant » en couleur d'accent, « Ensuite » en blanc, suivi du titre. */
    private fun labelled(label: String, text: String, accent: Boolean): CharSequence {
        val sb = SpannableStringBuilder(label)
        sb.setSpan(ForegroundColorSpan(if (accent) getColor(R.color.accent) else Color.parseColor("#E7E9ED")), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("   ").append(text)
        return sb
    }

    private fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(ms))

    private fun paintVodProgress() {
        val p = player
        val dur = p?.duration ?: 0L
        if (p == null || dur <= 0) {
            ovProgress.visibility = View.GONE
            return
        }
        ovProgress.visibility = View.VISIBLE
        ovStart.text = Ui.time(p.currentPosition)
        ovEnd.text = Ui.time(dur)
        ovBar.progress = (p.currentPosition * 1000 / dur).toInt().coerceIn(0, 1000)
    }

    /** Chaîne suivante (en haut), actuelle, précédente (en bas). */
    private fun paintZap() {
        val n = list.size
        val idx = listOf((index + 1) % n, index, (index - 1 + n) % n)
        for ((row, i) in zapRows.zip(idx)) {
            val ch = list[i] ?: continue
            row.findViewById<TextView>(R.id.name).text = "${ch.num ?: (i + 1)} · ${ch.name}"
            Img.load(row.findViewById(R.id.img), ch.icon, R.drawable.ph_live)
            val sub = row.findViewById<TextView>(R.id.sub)
            if (i == index) {
                sub.text = "En cours de lecture"
                continue
            }
            sub.tag = ch.id
            val cached = Epg.cached(ch.id)
            sub.text = cached?.let { Epg.nowAndNext(it).first?.title } ?: ""
            if (cached == null && Prefs.epgEnabled) Epg.prefetch(api, ch.id) { l ->
                if (sub.tag == ch.id) sub.text = Epg.nowAndNext(l).first?.title ?: ""
            }
        }
    }

    // ---------- Pistes audio et sous-titres (direct) ----------

    private fun trackName(f: androidx.media3.common.Format, fallback: String): String {
        f.label?.takeIf { it.isNotBlank() }?.let { return it }
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" } ?: return fallback
        return Locale(lang).getDisplayLanguage(Locale.FRANCE).replaceFirstChar { it.uppercase() }.ifBlank { lang }
    }

    private class Track(val group: androidx.media3.common.Tracks.Group, val index: Int, val name: String, val selected: Boolean)

    private fun tracks(type: Int): List<Track> {
        val p = player ?: return emptyList()
        val out = ArrayList<Track>()
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out.add(Track(g, i, trackName(g.getTrackFormat(i), "Piste ${out.size + 1}"), g.isTrackSelected(i)))
            }
        }
        return out
    }

    private fun paintTracks() {
        val audio = tracks(C.TRACK_TYPE_AUDIO)
        val subs = tracks(C.TRACK_TYPE_TEXT)
        ovAudio.text = "Audio : " + (audio.firstOrNull { it.selected }?.name ?: if (audio.isEmpty()) "—" else audio[0].name)
        ovSubs.text = "Sous-titres : " + (subs.firstOrNull { it.selected }?.name ?: "aucun")
    }

    /** Menu du lecteur : qualité, format d'image, audio, sous-titres, infos techniques. */
    private fun chooseTracks() {
        val audio = tracks(C.TRACK_TYPE_AUDIO)
        val subs = tracks(C.TRACK_TYPE_TEXT)
        val base = current
        val item = now
        val options = ArrayList<Pair<String, () -> Unit>>()
        if (isLive && base != null && item != null && !Session.autoFallback) {
            options.add("Qualité · ${LiveQuality.label(item.name)}" to { chooseQuality(base, item) })
        }
        options.add("Format d'image · ${ASPECTS[aspect].second}" to { chooseAspect() })
        if (audio.size > 1) options.add("Audio · ${audio.firstOrNull { it.selected }?.name ?: audio[0].name}" to { chooseAudio(audio) })
        if (subs.isNotEmpty()) options.add("Sous-titres · ${subs.firstOrNull { it.selected }?.name ?: "aucun"}" to { chooseSubs(subs) })
        options.add((if (statsOn) "Masquer" else "Afficher") + " les infos techniques" to { toggleStats() })
        Ui.choose(this, now?.name ?: "Lecture", options.map { it.first }) { w -> options[w].second() }
    }

    private fun chooseQuality(base: Item, item: Item) {
        Bg.task(this, { LiveQuality.variants(src.id, base) }) { r ->
            val vars = r.getOrNull().orEmpty()
            if (vars.size <= 1) {
                Ui.toast(this, "Une seule version de cette chaîne dans ta liste.")
                return@task
            }
            val labels = vars.map { v -> (if (v.id == item.id) "✓  " else "") + LiveQuality.label(v.name) + "  ·  " + v.name }
            Ui.choose(this, "Qualité de la chaîne", labels) { w ->
                val v = vars[w]
                if (v.id == item.id) return@choose
                LiveQuality.remember(base, v)
                Ui.toast(this, "${LiveQuality.label(v.name)} retenue pour cette chaîne.")
                play()
            }
        }
    }

    private fun chooseAspect() {
        Ui.choose(this, "Format d'image", ASPECTS.mapIndexed { i, a -> (if (i == aspect) "✓  " else "") + a.second }) { w ->
            aspect = w
            Prefs.aspectMode = w
            playerView.resizeMode = ASPECTS[w].first
        }
    }

    private fun chooseAudio(audio: List<Track>) {
        val p = player ?: return
        Ui.choose(this, "Piste audio", audio.map { (if (it.selected) "✓  " else "") + it.name }) { w ->
            val t = audio[w]
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index)).build()
            Bg.main.postDelayed({ if (!isDestroyed) paintTracks() }, 600)
        }
    }

    private fun chooseSubs(subs: List<Track>) {
        val p = player ?: return
        val none = subs.none { it.selected }
        val labels = listOf((if (none) "✓  " else "") + "Aucun") + subs.map { (if (it.selected) "✓  " else "") + it.name }
        Ui.choose(this, "Sous-titres", labels) { w ->
            val b = p.trackSelectionParameters.buildUpon()
            if (w == 0) {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                val t = subs[w - 1]
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
            }
            p.trackSelectionParameters = b.build()
            Bg.main.postDelayed({ if (!isDestroyed) paintTracks() }, 600)
        }
    }

    // ---------- Infos techniques ----------

    private fun toggleStats() {
        statsOn = !statsOn
        Bg.main.removeCallbacks(statsTick)
        if (statsOn) {
            lastBytes = bytesIn.get()
            lastBytesAt = System.currentTimeMillis()
            Bg.main.post(statsTick)
        } else stats.visibility = View.GONE
    }

    private val statsTick = object : Runnable {
        override fun run() {
            if (!statsOn || isFinishing) return
            paintStats()
            Bg.main.postDelayed(this, 1_000)
        }
    }

    private fun codecName(mime: String?): String = when (mime) {
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_MPEG_L2 -> "MPEG audio"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        MimeTypes.AUDIO_OPUS -> "Opus"
        else -> mime?.substringAfter('/')?.uppercase() ?: "?"
    }

    private fun num(v: Double, digits: Int): String =
        String.format(Locale.FRANCE, "%.${digits}f", v).trimEnd('0').trimEnd(',')

    private fun paintStats() {
        val p = player ?: return
        val t = System.currentTimeMillis()
        val b = bytesIn.get()
        if (t - lastBytesAt >= 900) {
            bitrate = (b - lastBytes) * 8_000 / (t - lastBytesAt).coerceAtLeast(1)
            lastBytes = b
            lastBytesAt = t
        }
        val v = p.videoFormat
        val a = p.audioFormat
        val sb = StringBuilder()
        if (v != null) {
            sb.append("Image    ${v.width}×${v.height} · ${codecName(v.sampleMimeType)}")
            if (measuredFps > 0) sb.append(" · ${num(measuredFps.toDouble(), 3)} i/s")
            sb.append('\n')
        }
        sb.append("Écran    ${num(displayRate.hz().toDouble(), 2)} Hz")
        if (!Prefs.autoFrameRate) sb.append(" (cadence auto coupée)")
        if (Prefs.tunneling) sb.append(" · mode tunnel")
        sb.append('\n')
        videoDecoder?.let { sb.append("Décodeur ${it.removePrefix("OMX.").removePrefix("c2.")}\n") }
        if (a != null) {
            sb.append("Son      ${codecName(a.sampleMimeType)}")
            if (a.channelCount > 0) sb.append(" · ${when (a.channelCount) { 6 -> "5.1"; 8 -> "7.1"; 2 -> "stéréo"; else -> "${a.channelCount} canaux" }}")
            if (a.sampleRate > 0) sb.append(" · ${a.sampleRate / 1000} kHz")
            sb.append('\n')
            sb.append(
                "         " + when {
                    audioDecoder == null -> "envoyé tel quel à l'ampli"
                    audioDecoder?.contains("ffmpeg", true) == true -> "décodé par l'appli"
                    else -> "décodé par le stick"
                } + (if (Prefs.volumeLeveling) " · volume égalisé" else "") + "\n"
            )
        }
        sb.append("Débit    ${num(bitrate / 1_000_000.0, 1)} Mb/s · avance ${num(p.totalBufferedDuration / 1000.0, 1)} s\n")
        sb.append("Images perdues  $dropped")
        stats.text = sb
        stats.visibility = View.VISIBLE
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    /** Zapping : on affiche tout de suite le nom, on lance la chaîne après une courte pause. */
    private fun zap(step: Int) {
        val n = list.size
        if (n <= 1) return
        learnMatchChannel()
        index = ((index + step) % n + n) % n
        current?.let { showInfo(it, withEpg = false) }
        Bg.main.removeCallbacks(zapNow)
        Bg.main.postDelayed(zapNow, 320)
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration
        var target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        if (dur > 0) target = target.coerceAtMost(dur - 1_000)
        p.seekTo(target)
        val item = current ?: return
        showInfo(item, withEpg = false)
        ovNext.text = if (deltaMs < 0) "−10 s" else "+10 s"
        if (dur > 0) {
            ovStart.text = Ui.time(target)
            ovBar.progress = (target * 1000 / dur).toInt().coerceIn(0, 1000)
        }
        Bg.main.removeCallbacks(hideOverlay)
        Bg.main.postDelayed(hideOverlay, 2_000)
    }

    /** Numéro tapé à la télécommande : on zappe sur cette chaîne de la liste. */
    private fun typeDigit(d: Int) {
        if (digits.length >= 4) digits = ""
        digits += d
        ovTitle.text = "Chaîne $digits"
        ovNow.text = ""
        ovNext.text = ""
        ovProgress.visibility = View.GONE
        showOverlay()
        Bg.main.removeCallbacks(hideOverlay)
        Bg.main.removeCallbacks(applyDigits)
        Bg.main.postDelayed(applyDigits, 1_300)
    }

    private fun zapToNumber() {
        val n = digits.toIntOrNull()
        digits = ""
        val idx = n?.let { list.indexOfNum(it) }
        if (idx == null) {
            ovNow.text = "Pas de chaîne n° ${n ?: ""} dans cette liste"
            Bg.main.postDelayed(hideOverlay, 2_000)
            return
        }
        index = idx
        play()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Bulle « Épisode suivant » : OK lance, Retour la masque (on finit l'épisode normalement)
        if (nextBox.visibility == View.VISIBLE) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP) playNextEpisode()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP) {
                        nextDismissed = true
                        hideNext()
                    }
                    return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isLive) {
                val c = event.keyCode
                if (c in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                    typeDigit(c - KeyEvent.KEYCODE_0); return true
                }
                if (c in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
                    typeDigit(c - KeyEvent.KEYCODE_NUMPAD_0); return true
                }
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        zap(1); return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        zap(-1); return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        // Premier appui : infos. Appui suivant, bandeau visible : pistes audio et sous-titres.
                        if (overlay.visibility == View.VISIBLE) chooseTracks()
                        else current?.let { showInfo(it, withEpg = true) }
                        return true
                    }
                    KeyEvent.KEYCODE_MENU -> {
                        chooseTracks(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                        current?.let { showInfo(it, withEpg = true) }
                        return true
                    }
                }
            } else if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_INFO) {
                chooseTracks()
                return true
            } else if (!playerView.isControllerFullyVisible) {
                // Films / séries : gauche / droite = reculer / avancer de 10 s sans ouvrir les commandes.
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        seekBy(-10_000); return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        seekBy(10_000); return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Fin d'épisode : seuil d'apparition de la bulle (tout à la fin, pendant le générique). */
    private fun nextThresholdMs(dur: Long): Long = (dur * 0.03).toLong().coerceIn(25_000L, 45_000L)

    private fun checkNextEpisode() {
        val item = current ?: return hideNext()
        val p = player ?: return hideNext()
        if (item.kind != Kind.EPISODE || index + 1 >= list.size) return hideNext()
        val dur = p.duration
        if (dur <= 0 || dur == C.TIME_UNSET || dur < 5 * 60_000L) return hideNext()
        val left = dur - p.currentPosition
        val threshold = nextThresholdMs(dur)
        if (left > threshold + 5_000) {
            // Retour en arrière : la bulle pourra réapparaître
            nextDismissed = false
            hideNext()
            return
        }
        if (left > threshold || nextDismissed) return
        if (nextBox.visibility != View.VISIBLE) showNext()
        if (!p.isPlaying) {
            // En pause : le compte à rebours attend
            nextShownAt = System.currentTimeMillis() - (nextBar.progress * nextCountdownMs / 1000)
            return
        }
        val elapsed = System.currentTimeMillis() - nextShownAt
        val leftSec = ((nextCountdownMs - elapsed + 999) / 1000).coerceAtLeast(0)
        btnNext.text = "Lecture  ·  $leftSec"
        nextBar.progress = (elapsed * 1000 / nextCountdownMs).toInt().coerceIn(0, 1000)
        if (elapsed >= nextCountdownMs) playNextEpisode()
    }

    private fun showNext() {
        val next = list[index + 1] ?: return
        val se = SE_LABEL.find(next.extra ?: "")?.let { "S${it.groupValues[1]} · É${it.groupValues[2]}  " } ?: ""
        nextTitle.text = se + next.name
        nextShownAt = System.currentTimeMillis()
        nextBar.progress = 0
        playerView.hideController()
        overlay.visibility = View.GONE
        nextBox.alpha = 0f
        nextBox.translationY = 20 * resources.displayMetrics.density
        nextBox.visibility = View.VISIBLE
        nextBox.animate().alpha(1f).translationY(0f).setDuration(260).start()
        btnNext.requestFocus()
    }

    private fun hideNext() {
        if (::nextBox.isInitialized && nextBox.visibility == View.VISIBLE) nextBox.visibility = View.GONE
    }

    private fun playNextEpisode() {
        val item = current ?: return
        if (index + 1 >= list.size) return
        markFinished(item)
        hideNext()
        index++
        play()
    }

    private fun markFinished(item: Item) {
        if (item.kind == Kind.MOVIE || item.kind == Kind.EPISODE) {
            Db.saveProgress(src.id, item, 0, player?.duration?.coerceAtLeast(0) ?: 0, true)
        }
    }

    private fun savePosition() {
        val item = current ?: return
        val p = player ?: return
        if (item.kind != Kind.MOVIE && item.kind != Kind.EPISODE) return
        val pos = p.currentPosition
        val dur = p.duration
        if (dur <= 0) return
        when {
            pos >= dur - 60_000 -> markFinished(item)
            pos > 30_000 -> Db.saveProgress(src.id, item, pos, dur, false)
        }
    }

    companion object {
        /** Format d'image : adapté (bandes noires si besoin), plein écran étiré, zoom (rogne les bords). */
        private val ASPECTS = listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to "Adapté à l'écran",
            AspectRatioFrameLayout.RESIZE_MODE_FILL to "Plein écran (étiré)",
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom (sans bandes noires)"
        )
        private val RETRY_DELAYS = longArrayOf(1_500L, 3_000L, 6_000L, 10_000L)
        private val LIVE_RETRY_DELAYS = longArrayOf(800L, 1_500L, 3_000L, 5_000L, 8_000L, 10_000L, 15_000L)
        private val SE_LABEL = Regex("""Saison (\d+) · Épisode (\d+)""")
    }
}
