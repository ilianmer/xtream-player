package com.ilian.xtreamplayer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private lateinit var src: Source
    private lateinit var api: XtreamApi
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var ovTitle: TextView
    private lateinit var ovNow: TextView
    private lateinit var ovNext: TextView
    private lateinit var tvError: TextView

    private var player: ExoPlayer? = null
    private var index = 0
    private var useAltFormat = false
    private var epgToken = 0

    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private val zapNow = Runnable { play() }

    private val list: ItemList get() = Session.playlist
    private val current: Item? get() = list[index]
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
        tvError = findViewById(R.id.tvError)

        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
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
        play()
    }

    override fun onStop() {
        savePosition()
        Bg.main.removeCallbacks(hideOverlay)
        Bg.main.removeCallbacks(zapNow)
        player?.release()
        player = null
        Session.index = index
        Session.lastStopAt = System.currentTimeMillis()
        super.onStop()
    }

    /** Tampon réglé dans Réglages > Lecteur vidéo. */
    private fun loadControl(): DefaultLoadControl {
        val buf = Prefs.bufferSec.coerceIn(1, 60) * 1000
        val maxB = max(Prefs.maxBufferSec * 1000, buf + 5_000)
        val minB = min(max(buf * 2, 15_000), maxB)
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(minB, maxB, buf, min(buf * 2, minB))
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    private fun initPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Prefs.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val dataSource = DefaultDataSource.Factory(this, http)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)

        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .setLoadControl(loadControl())
            .build()
        p.setAudioAttributes(AudioAttributes.DEFAULT, true)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // En direct, si le format choisi ne passe pas, on tente l'autre (TS <-> HLS) une fois.
                if (isLive && !useAltFormat) {
                    useAltFormat = true
                    play(keepFormat = true)
                    return
                }
                showError("Lecture impossible (${error.errorCodeName}).\nEssaie une autre chaîne, ou change le format du direct ou le User Agent dans Réglages.")
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) tvError.visibility = View.GONE
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

    private fun play(keepFormat: Boolean = false) {
        val item = current ?: return finish()
        val p = player ?: return
        if (!keepFormat) useAltFormat = false

        val format = if (useAltFormat) (if (Prefs.liveFormat == "ts") "m3u8" else "ts") else Prefs.liveFormat
        val url = api.streamUrl(item, format)
        val builder = MediaItem.Builder().setUri(url)
        if (item.kind == Kind.LIVE && format == "m3u8") builder.setMimeType(MimeTypes.APPLICATION_M3U8)

        tvError.visibility = View.GONE
        val resume = if (item.kind == Kind.LIVE) 0L else Db.progress(src.id, item)?.takeIf { !it.finished }?.pos ?: 0L
        p.setMediaItem(builder.build(), resume)
        p.prepare()
        p.playWhenReady = true
        showInfo(item, withEpg = true)
        if (resume > 0) Ui.toast(this, "Reprise à ${Ui.time(resume)}")
    }

    private fun showInfo(item: Item, withEpg: Boolean) {
        ovTitle.text = if (item.kind == Kind.LIVE) "${index + 1}.  ${item.name}" else item.name
        ovNow.text = ""
        ovNext.text = if (item.kind == Kind.LIVE) "" else (item.extra ?: "")
        overlay.visibility = View.VISIBLE
        Bg.main.removeCallbacks(hideOverlay)
        Bg.main.postDelayed(hideOverlay, 5_000)

        if (item.kind != Kind.LIVE || !withEpg || !Prefs.epgEnabled) return
        val token = ++epgToken
        Epg.prefetch(api, item.id) { l ->
            if (token != epgToken || isFinishing) return@prefetch
            val (now, next) = Epg.nowAndNext(l)
            ovNow.text = now?.let { "Maintenant  ${Epg.line(it)}" } ?: "Pas de guide TV pour cette chaîne"
            ovNext.text = next?.let { "Ensuite  ${Epg.line(it)}" } ?: ""
        }
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    /** Zapping : on affiche tout de suite le nom, on lance la chaîne après une courte pause. */
    private fun zap(step: Int) {
        val n = list.size
        if (n <= 1) return
        index = ((index + step) % n + n) % n
        current?.let { showInfo(it, withEpg = false) }
        Bg.main.removeCallbacks(zapNow)
        Bg.main.postDelayed(zapNow, 450)
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration
        var target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        if (dur > 0) target = target.coerceAtMost(dur - 1_000)
        p.seekTo(target)
        val item = current ?: return
        ovTitle.text = item.name
        ovNow.text = if (dur > 0) "${Ui.time(target)} / ${Ui.time(dur)}" else Ui.time(target)
        ovNext.text = if (deltaMs < 0) "−10 s" else "+10 s"
        overlay.visibility = View.VISIBLE
        Bg.main.removeCallbacks(hideOverlay)
        Bg.main.postDelayed(hideOverlay, 2_000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isLive) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        zap(1); return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        zap(-1); return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                        current?.let { showInfo(it, withEpg = true) }
                        return true
                    }
                }
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
}
