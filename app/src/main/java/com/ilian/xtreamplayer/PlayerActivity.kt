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
class PlayerActivity : Activity() {

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
        val live = item.kind == Kind.LIVE
        ovClock.text = hhmm(System.currentTimeMillis())
        ovTitle.text = if (live) "${index + 1} · ${item.name}" else item.name
        ovLive.visibility = if (live) View.VISIBLE else View.GONE
        ovStar.visibility = if (Db.isFav(src.id, item)) View.VISIBLE else View.GONE
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
        overlay.visibility = View.VISIBLE
        Bg.main.removeCallbacks(hideOverlay)
        Bg.main.postDelayed(hideOverlay, 5_000)

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
            row.findViewById<TextView>(R.id.name).text = "${i + 1} · ${ch.name}"
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

    private fun chooseTracks() {
        val audio = tracks(C.TRACK_TYPE_AUDIO)
        val subs = tracks(C.TRACK_TYPE_TEXT)
        if (audio.size <= 1 && subs.isEmpty()) {
            Ui.toast(this, "Cette chaîne n'a qu'une piste audio et pas de sous-titres.")
            return
        }
        val options = ArrayList<String>()
        if (audio.size > 1) options.add("Audio")
        if (subs.isNotEmpty()) options.add("Sous-titres")
        val go = { what: String ->
            val p = player
            if (p != null) {
                if (what == "Audio") {
                    Ui.choose(this, "Piste audio", audio.map { (if (it.selected) "✓  " else "") + it.name }) { w ->
                        val t = audio[w]
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index)).build()
                        Bg.main.postDelayed({ if (!isDestroyed) paintTracks() }, 600)
                    }
                } else {
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
            }
        }
        if (options.size == 1) go(options[0]) else Ui.choose(this, "Changer", options) { w -> go(options[w]) }
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
        showInfo(item, withEpg = false)
        ovNext.text = if (deltaMs < 0) "−10 s" else "+10 s"
        if (dur > 0) {
            ovStart.text = Ui.time(target)
            ovBar.progress = (target * 1000 / dur).toInt().coerceIn(0, 1000)
        }
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
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        // Premier appui : infos. Appui suivant, bandeau visible : pistes audio et sous-titres.
                        if (overlay.visibility == View.VISIBLE) chooseTracks()
                        else current?.let { showInfo(it, withEpg = true) }
                        return true
                    }
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
