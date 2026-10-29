package com.ilian.xtreamplayer

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spots LED qui suivent la musique, à partir du son seul (aucune image).
 *
 * Le son est lu dans ExoPlayer (TeeAudioProcessor) : aucune permission, aucun micro.
 * - Couche rapide (une analyse par bloc de 1024 échantillons, ~47 fois/s) : FFT, énergie des basses (< 150 Hz),
 *   médiums (< 2 kHz) et aigus (< 12 kHz), enveloppe attaque vive / retombée douce, détection du kick.
 * - Couche lente (~20 s) : volume moyen (dBFS), tempo, brillance (centroïde), part des basses
 *   -> ambiance Calme, Groove, Énergie ou Aérien (3 s de stabilité avant de changer, fondu d'1 s).
 * - Spots 1-2 = basses (flash sur le kick), 3-4 = médiums, 5-6 = aigus.
 * - Envoi limité à 25 fois/s sur un fil dédié, avec un retard réglable pour coller au son entendu.
 * - Lumière tamisée quand la lecture est en pause ou arrêtée.
 *
 * Tout le calcul reste léger : quelques milliers d'opérations par bloc, aucune allocation en boucle.
 */
@OptIn(UnstableApi::class)
class LightSync(private val outputFactory: () -> LightOutput, private val delayMs: Int) : TeeAudioProcessor.AudioBufferSink {

    // ---------- Format du son ----------
    @Volatile
    private var rate = 48_000
    @Volatile
    private var channels = 2
    @Volatile
    private var encoding = C.ENCODING_PCM_16BIT

    // ---------- Couche rapide ----------
    private val n = 1024
    private val block = FloatArray(n)
    private var filled = 0
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val window = FloatArray(n) { i -> (0.5 - 0.5 * cos(2 * PI * i / (n - 1))).toFloat() }
    private val cosT = FloatArray(n / 2) { i -> cos(2 * PI * i / n).toFloat() }
    private val sinT = FloatArray(n / 2) { i -> sin(2 * PI * i / n).toFloat() }
    private val rev = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    // Niveaux suivis (gain automatique par bande : marche quel que soit le volume)
    private val peak = floatArrayOf(1e-6f, 1e-6f, 1e-6f)
    private val raw = FloatArray(3)
    private val env = FloatArray(3)
    private var prevBass = 0f
    private var fluxMean = 0f
    private var fluxVar = 0f
    private var lastKickMs = 0L

    // ---------- Couche lente ----------
    private val secDb = FloatArray(20)
    private val secCentroid = FloatArray(20)
    private val secBass = FloatArray(20)
    private var secIdx = 0
    private var secFilled = 0
    private var accDb = 0f
    private var accCentroid = 0f
    private var accBass = 0f
    private var accN = 0
    private var accStart = 0L
    private val kicks = LongArray(48)
    private var kickIdx = 0
    private var candidate = 0
    private var candidateSince = 0L

    // ---------- Échange avec le fil d'envoi ----------
    /** Image du moment : instant, basses, médiums, aigus, kick (0/1), ambiance. */
    private class Frame(val at: Long, val bass: Float, val mid: Float, val high: Float, val kick: Boolean, val mood: Int)

    private val queue = java.util.ArrayDeque<Frame>()

    @Volatile
    private var mood = MOOD_CALM
    @Volatile
    private var playing = true
    @Volatile
    private var running = true
    private val sender = Thread({ loop() }, "lightsync").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY - 1
        start()
    }

    fun setPlaying(p: Boolean) {
        playing = p
    }

    /** Fin de lecture : lumière tamisée, puis on libère le réseau. */
    fun release() {
        playing = false
        running = false
        sender.interrupt()
    }

    // ---------- Entrée audio (fil de lecture d'ExoPlayer : doit rester très rapide) ----------

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        rate = sampleRateHz.coerceAtLeast(8_000)
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        filled = 0
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val ch = channels
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val frameBytes = 2 * ch
                var p = b.position()
                val end = b.limit() - frameBytes + 1
                while (p < end) {
                    var s = 0f
                    for (c in 0 until ch) s += b.getShort(p + 2 * c)
                    push(s / (ch * 32768f))
                    p += frameBytes
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val frameBytes = 4 * ch
                var p = b.position()
                val end = b.limit() - frameBytes + 1
                while (p < end) {
                    var s = 0f
                    for (c in 0 until ch) s += b.getFloat(p + 4 * c)
                    push(s / ch)
                    p += frameBytes
                }
            }
            else -> Unit // autres formats : ignorés (le son, lui, est joué normalement)
        }
    }

    private fun push(v: Float) {
        block[filled++] = v
        if (filled == n) {
            filled = 0
            analyze()
        }
    }

    // ---------- Analyse ----------

    private fun fft() {
        for (i in 0 until n) {
            val j = rev[i]
            re[j] = block[i] * window[i]
            im[j] = 0f
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val c = cosT[k]
                    val s = -sinT[k]
                    val tr = re[j + half] * c - im[j + half] * s
                    val ti = re[j + half] * s + im[j + half] * c
                    re[j + half] = re[j] - tr
                    im[j + half] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }

    private fun analyze() {
        // Volume du bloc
        var sq = 0f
        for (v in block) sq += v * v
        val rms = sqrt(sq / n)
        val db = if (rms > 1e-7f) (20 * log10(rms.toDouble())).toFloat() else -90f

        fft()
        val hz = rate.toFloat() / n
        var bass = 0f
        var mid = 0f
        var high = 0f
        var wsum = 0f
        var msum = 0f
        val top = min(n / 2, (12_000 / hz).toInt())
        for (k in 1..top) {
            val m = sqrt(re[k] * re[k] + im[k] * im[k])
            val f = k * hz
            when {
                f < 150 -> bass += m
                f < 2_000 -> mid += m
                else -> high += m
            }
            wsum += f * m
            msum += m
        }
        val centroid = if (msum > 0) wsum / msum else 0f
        val total = bass + mid + high

        // Gain automatique : chaque bande est comparée à son propre maximum récent (qui redescend doucement)
        raw[0] = bass
        raw[1] = mid
        raw[2] = high
        val silent = db < -55f
        for (i in 0..2) {
            peak[i] = max(raw[i], peak[i] * 0.997f).coerceAtLeast(1e-6f)
            val x = if (silent) 0f else (raw[i] / peak[i]).coerceIn(0f, 1f)
            // Enveloppe : attaque vive, retombée douce
            env[i] += (x - env[i]) * (if (x > env[i]) 0.6f else 0.12f)
        }

        // Kick : montée brutale des basses, nettement au-dessus de la moyenne récente
        val now = SystemClock.elapsedRealtime()
        val bn = if (silent) 0f else (bass / peak[0])
        val flux = max(0f, bn - prevBass)
        prevBass = bn
        fluxMean += (flux - fluxMean) * 0.05f
        fluxVar += ((flux - fluxMean) * (flux - fluxMean) - fluxVar) * 0.05f
        val kick = !silent && flux > 0.12f && flux > fluxMean + 1.6f * sqrt(fluxVar) && now - lastKickMs > 230
        if (kick) {
            lastKickMs = now
            kicks[kickIdx % kicks.size] = now
            kickIdx++
        }

        slowLayer(now, db, centroid, if (total > 0) bass / total else 0f)

        val f = Frame(now, env[0], env[1], env[2], kick, mood)
        synchronized(queue) {
            queue.addLast(f)
            while (queue.size > 120) queue.removeFirst()
        }
    }

    /** Ambiance sur les ~20 dernières secondes. */
    private fun slowLayer(now: Long, db: Float, centroid: Float, bassShare: Float) {
        if (accStart == 0L) accStart = now
        if (db > -60f) {
            accDb += db
            accCentroid += centroid
            accBass += bassShare
            accN++
        }
        if (now - accStart < 1_000) return
        accStart = now
        if (accN > 0) {
            secDb[secIdx] = accDb / accN
            secCentroid[secIdx] = accCentroid / accN
            secBass[secIdx] = accBass / accN
            secIdx = (secIdx + 1) % secDb.size
            secFilled = min(secFilled + 1, secDb.size)
        }
        accDb = 0f
        accCentroid = 0f
        accBass = 0f
        accN = 0
        if (secFilled < 4) return

        var d = 0f
        var c = 0f
        var bs = 0f
        for (i in 0 until secFilled) {
            d += secDb[i]
            c += secCentroid[i]
            bs += secBass[i]
        }
        d /= secFilled
        c /= secFilled
        bs /= secFilled
        val bpm = tempo(now)

        val next = when {
            d < SEUIL_CALME_DB -> MOOD_CALM
            d > SEUIL_ENERGIE_DB && (bpm >= SEUIL_ENERGIE_BPM || bs > SEUIL_ENERGIE_BASSES) -> MOOD_ENERGY
            c > SEUIL_AERIEN_HZ && bs < SEUIL_AERIEN_BASSES -> MOOD_AIRY
            bpm in SEUIL_GROOVE_BPM_MIN..SEUIL_GROOVE_BPM_MAX && bs > SEUIL_GROOVE_BASSES -> MOOD_GROOVE
            else -> MOOD_CALM
        }
        // 3 s de stabilité avant de changer d'ambiance
        if (next != candidate) {
            candidate = next
            candidateSince = now
        } else if (next != mood && now - candidateSince >= 3_000) {
            mood = next
        }
    }

    /** Tempo d'après l'écart médian entre kicks (ramené entre 70 et 180 BPM). */
    private fun tempo(now: Long): Float {
        val count = min(kickIdx, kicks.size)
        if (count < 6) return 0f
        val gaps = ArrayList<Long>(count)
        val recent = (0 until count).map { kicks[it] }.filter { now - it < 20_000 }.sorted()
        for (i in 1 until recent.size) gaps.add(recent[i] - recent[i - 1])
        if (gaps.size < 4) return 0f
        gaps.sort()
        var bpm = 60_000f / gaps[gaps.size / 2]
        while (bpm < 70f) bpm *= 2
        while (bpm > 180f) bpm /= 2
        return bpm
    }

    // ---------- Envoi vers les spots (fil dédié, 25 fois/s) ----------

    private fun loop() {
        val out = try {
            outputFactory()
        } catch (_: Exception) {
            LogOutput()
        }
        val colors = IntArray(6)
        val levels = FloatArray(6)
        var shown = Frame(0, 0f, 0f, 0f, false, MOOD_CALM)
        var flash = 0f
        var dim = 0f
        // Fondu entre ambiances
        var from = PALETTES[MOOD_CALM]
        var to = PALETTES[MOOD_CALM]
        var fadeStart = 0L
        try {
            while (running) {
                val now = SystemClock.elapsedRealtime()
                // Image d'il y a [delayMs] : le son passe par la sortie audio et la TV avant d'être entendu
                var kickSeen = false
                synchronized(queue) {
                    while (queue.isNotEmpty() && queue.peekFirst()!!.at <= now - delayMs) {
                        val f = queue.removeFirst()
                        if (f.kick) kickSeen = true
                        shown = f
                    }
                }
                val target = PALETTES[shown.mood]
                if (target !== to) {
                    from = mix(from, to, ((now - fadeStart) / 1_000f).coerceIn(0f, 1f))
                    to = target
                    fadeStart = now
                }
                val pal = mix(from, to, ((now - fadeStart) / 1_000f).coerceIn(0f, 1f))

                // Pause / arrêt : lumière tamisée, qui revient en douceur
                dim += ((if (playing) 0f else 1f) - dim) * 0.08f
                if (kickSeen && playing) flash = 1f else flash *= 0.80f

                val bassLv = max(0.15f + 0.85f * shown.bass, flash)
                val midLv = 0.12f + 0.88f * shown.mid
                val highLv = 0.10f + 0.90f * shown.high
                val bassColor = lerp(pal[0], pal[3], flash)
                for (i in 0..5) {
                    val (c, l) = when (i / 2) {
                        0 -> bassColor to bassLv
                        1 -> pal[1] to midLv
                        else -> pal[2] to highLv
                    }
                    colors[i] = lerp(c, pal[1], dim)
                    levels[i] = l * (1 - dim) + 0.10f * dim
                }
                out.send(colors, levels)
                Thread.sleep(40)
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
        } finally {
            // Arrêt : on laisse une lumière douce, puis on ferme
            try {
                val pal = PALETTES[MOOD_CALM]
                for (i in 0..5) {
                    colors[i] = pal[1]
                    levels[i] = 0.10f
                }
                out.send(colors, levels)
            } catch (_: Exception) {
            }
            out.close()
        }
    }

    companion object {
        const val MOOD_CALM = 0
        const val MOOD_GROOVE = 1
        const val MOOD_ENERGY = 2
        const val MOOD_AIRY = 3
        val MOOD_NAMES = listOf("Calme", "Groove", "Énergie", "Aérien")

        // ---------- Seuils des ambiances (à ajuster après tests sur tes playlists) ----------
        /** En dessous de ce volume moyen : Calme. */
        var SEUIL_CALME_DB = -30f
        /** Au-dessus de ce volume, avec un tempo rapide ou beaucoup de basses : Énergie. */
        var SEUIL_ENERGIE_DB = -17f
        var SEUIL_ENERGIE_BPM = 118f
        var SEUIL_ENERGIE_BASSES = 0.48f
        /** Son brillant et peu de basses : Aérien. */
        var SEUIL_AERIEN_HZ = 2_400f
        var SEUIL_AERIEN_BASSES = 0.28f
        /** Tempo moyen et basses présentes : Groove. */
        var SEUIL_GROOVE_BPM_MIN = 85f
        var SEUIL_GROOVE_BPM_MAX = 125f
        var SEUIL_GROOVE_BASSES = 0.34f

        /** Palettes : basses, médiums, aigus, flash du kick. */
        private val PALETTES = arrayOf(
            intArrayOf(0xFF8A3D, 0xFFB86B, 0x9FB7FF, 0xFFD9A0), // Calme : ambre, chaud, bleu doux
            intArrayOf(0x8A2BE2, 0xFF5E8A, 0x2EC4B6, 0xFF9F1C), // Groove : violet, rose, turquoise, orange
            intArrayOf(0xFF1E1E, 0xFF00C8, 0x00E5FF, 0xFFFFFF), // Énergie : rouge, magenta, cyan, blanc
            intArrayOf(0x3D7BFF, 0x8FD3FF, 0xE6F0FF, 0xB9A7FF)  // Aérien : bleu, ciel, blanc bleuté, lavande
        )

        fun lerp(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            val r = ((a shr 16 and 255) + ((b shr 16 and 255) - (a shr 16 and 255)) * k).toInt()
            val g = ((a shr 8 and 255) + ((b shr 8 and 255) - (a shr 8 and 255)) * k).toInt()
            val bl = ((a and 255) + ((b and 255) - (a and 255)) * k).toInt()
            return (r shl 16) or (g shl 8) or bl
        }

        private fun mix(a: IntArray, b: IntArray, t: Float): IntArray = IntArray(4) { lerp(a[it], b[it], t) }

        /** Démonstration des 4 ambiances sur les spots (bloquant, ≈ 8 s). */
        fun demo(out: LightOutput) {
            val colors = IntArray(6)
            val levels = FloatArray(6)
            try {
                (out as? MultiOutput)?.awaitReady(8_000)
                for (pal in PALETTES) {
                    for (step in 0 until 50) {
                        val beat = if (step % 12 == 0) 1f else 0.4f + 0.3f * sin(step / 4.0).toFloat()
                        for (i in 0..5) {
                            colors[i] = if (i < 2) lerp(pal[0], pal[3], if (step % 12 == 0) 1f else 0f) else pal[1 + (i - 2) / 2]
                            levels[i] = if (i < 2) beat else 0.5f + 0.4f * sin((step + i * 3) / 5.0).toFloat()
                        }
                        out.send(colors, levels)
                        Thread.sleep(40)
                    }
                }
            } catch (_: Exception) {
            } finally {
                out.close()
            }
        }
    }
}
