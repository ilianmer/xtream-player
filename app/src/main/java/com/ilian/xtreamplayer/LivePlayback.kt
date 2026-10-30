package com.ilian.xtreamplayer

import android.app.Activity
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Versions d'une même chaîne (4K, FHD, HD, SD…) : choix de la qualité, retenu par chaîne,
 * ou qualité préférée pour toutes les chaînes (Réglages > Lecteur vidéo).
 */
object LiveQuality {
    const val SAME = 0
    const val UHD = 4
    const val FHD = 3
    const val HD = 2
    const val SD = 1

    val CHOICES = listOf(
        SAME to "Celle choisie dans la liste",
        UHD to "4K quand elle existe",
        FHD to "Full HD",
        HD to "HD",
        SD to "SD (connexion faible)"
    )

    private val LANG = Regex("""^\W*([A-Za-z]{2,3})\s*[|:]""")

    fun label(name: String): String = when (MatchFinder.tier(name)) {
        4 -> "4K"
        3 -> "Full HD"
        2 -> "HD"
        1 -> "SD"
        else -> "Standard"
    }

    private fun memKey(item: Item) = Db.pkey("liveq:" + XmlTv.key(item.name))

    /** Toutes les versions de cette chaîne dans le catalogue, de la meilleure à la plus légère (bloquant, rapide). */
    fun variants(src: Long, item: Item): List<Item> {
        val base = XmlTv.key(item.name)
        if (base.length < 2) return listOf(item)
        val lang = LANG.find(item.name)?.groupValues?.get(1)?.lowercase()
        val plus1 = MatchFinder.isPlus1(item.name)
        val found = try {
            Db.liveByName(src, MatchFinder.baseQuery(item.name), 80)
        } catch (_: Exception) {
            emptyList()
        }.filter { v ->
            XmlTv.key(v.name) == base && MatchFinder.isPlus1(v.name) == plus1 &&
                (lang == null || LANG.find(v.name)?.groupValues?.get(1)?.lowercase() == lang)
        }
        return (listOf(item) + found).distinctBy { it.id }
            .sortedWith(compareByDescending<Item> { MatchFinder.tier(it.name).let { t -> if (t == 0) 2 else t } }.thenBy { it.name.length })
    }

    /** Version à lancer pour cette chaîne : choix retenu pour elle, sinon la qualité préférée, sinon elle-même. */
    fun resolve(src: Long, item: Item): Item {
        val remembered = try {
            Db.kvGet(memKey(item))
        } catch (_: Exception) {
            null
        }
        val pref = Prefs.liveQuality
        if (remembered == null && pref == SAME) return item
        val all = variants(src, item)
        if (all.size <= 1) return item
        remembered?.let { id -> all.firstOrNull { it.id == id }?.let { return it } }
        if (pref == SAME) return item
        // Palier le plus proche de celui demandé ; à égalité, le meilleur (sauf 4K non demandée)
        return all.minWithOrNull(
            compareBy<Item> { abs(MatchFinder.tier(it.name).let { t -> if (t == 0) 2 else t } - pref) }
                .thenByDescending { MatchFinder.tier(it.name).let { t -> if (t == 4 && pref != UHD) -1 else t } }
        ) ?: item
    }

    /** La chaîne [base] se lancera désormais dans la version [chosen]. */
    fun remember(base: Item, chosen: Item) {
        try {
            Db.kvPut(memKey(base), chosen.id)
        } catch (_: Exception) {
        }
    }
}

/**
 * Fréquence de l'écran calée sur la cadence de la vidéo, comme une box TV :
 * 50 Hz pour les chaînes françaises (25 ou 50 images/s), 60 Hz pour l'américain, 24 Hz pour les films.
 * Sans ça, une vidéo à 25 i/s sur un écran à 60 Hz avance par à-coups (saccades dans les mouvements).
 */
class DisplayRate(private val act: Activity) {

    private fun display(): Display? = try {
        if (Build.VERSION.SDK_INT >= 30) act.display else {
            @Suppress("DEPRECATION")
            act.windowManager.defaultDisplay
        }
    } catch (_: Exception) {
        null
    }

    fun hz(): Float = display()?.mode?.refreshRate ?: 0f

    private fun err(refresh: Float, fps: Float): Float {
        val n = max(1, (refresh / fps).roundToInt())
        return abs(refresh - n * fps)
    }

    /** Bascule l'écran sur le mode qui affiche [fps] sans à-coups. true si un changement a été demandé. */
    fun match(fps: Float): Boolean {
        if (fps < 10f || fps > 125f) return false
        val d = display() ?: return false
        val cur = d.mode ?: return false
        val modes = try {
            d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
        } catch (_: Exception) {
            return false
        }
        val best = modes.minWithOrNull(
            compareBy<Display.Mode> { err(it.refreshRate, fps) > 0.05f }
                .thenBy { if (it.refreshRate >= 47f) 0 else 1 }
                .thenBy { err(it.refreshRate, fps) }
                .thenBy { it.refreshRate }
        ) ?: return false
        if (err(best.refreshRate, fps) > 0.5f) return false
        if (best.modeId == cur.modeId || err(cur.refreshRate, fps) <= err(best.refreshRate, fps) + 0.01f) return false
        apply(act, best.modeId)
        sticky = best.modeId
        return true
    }

    companion object {
        /** Mode choisi pendant la lecture : gardé dans les menus pour éviter un écran noir à chaque sortie du lecteur. */
        @Volatile
        var sticky = 0

        private val STANDARD = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f)

        /** Cadence mesurée -> cadence standard la plus proche (à 3 % près). */
        fun snap(fps: Float): Float {
            val s = STANDARD.minByOrNull { abs(it - fps) } ?: return fps
            return if (abs(s - fps) / s < 0.03f) s else fps
        }

        fun apply(act: Activity, modeId: Int) {
            try {
                val lp = act.window.attributes
                if (lp.preferredDisplayModeId != modeId) {
                    lp.preferredDisplayModeId = modeId
                    act.window.attributes = lp
                }
            } catch (_: Exception) {
            }
        }

        /** Chaque écran de l'appli reprend le mode du lecteur (pas de bascule 50/60 Hz en sortant de la lecture). */
        fun keep(act: Activity) {
            if (sticky != 0 && Prefs.autoFrameRate) apply(act, sticky)
        }
    }
}

/**
 * Volume égalisé (son en PCM 16 bits) : ramène doucement chaque chaîne au même niveau moyen,
 * monte lentement, baisse vite (pas de sursaut sur les pubs), et ne sature jamais (limiteur doux).
 * Inactif quand le son part tel quel vers l'ampli (Dolby), l'ampli gère alors le volume.
 */
@OptIn(UnstableApi::class)
class VolumeLeveler : BaseAudioProcessor() {
    private var gain = 1f
    private var env = 0f
    private var count = 0
    private var envK = 0f
    private var upK = 0f
    private var downK = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(8_000).toFloat()
        envK = 1f / (rate * 0.4f)             // niveau moyen sur ≈ 0,4 s
        upK = BLOCK / (rate * 4f)             // remonte en ≈ 4 s
        downK = BLOCK / (rate * 0.35f)        // baisse en ≈ 0,35 s
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val ch = inputAudioFormat.channelCount.coerceAtLeast(1)
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val inp = inputBuffer.order(ByteOrder.nativeOrder())
        val frames = size / (2 * ch)
        val tmp = FloatArray(ch)
        for (f in 0 until frames) {
            var sq = 0f
            for (c in 0 until ch) {
                val v = inp.getShort() / 32768f
                tmp[c] = v
                sq = max(sq, v * v)
            }
            env += (sq - env) * envK
            if (++count >= BLOCK) {
                count = 0
                // Silence ou presque : on ne remonte pas le souffle
                if (env > SILENCE) {
                    val target = (TARGET / sqrt(env)).coerceIn(MIN_GAIN, MAX_GAIN)
                    gain += (target - gain) * (if (target < gain) downK else upK).coerceAtMost(1f)
                }
            }
            for (c in 0 until ch) out.putShort((limit(tmp[c] * gain) * 32767f).toInt().toShort())
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    private fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val y = KNEE + (1f - KNEE) * tanh(((a - KNEE) / (1f - KNEE)).toDouble()).toFloat()
        return if (x < 0) -y else y
    }

    override fun onReset() {
        gain = 1f
        env = 0f
        count = 0
    }

    private companion object {
        const val BLOCK = 256
        const val TARGET = 0.18f      // ≈ -15 dBFS crête moyenne
        const val SILENCE = 1e-5f     // ≈ -50 dBFS
        const val MIN_GAIN = 0.3f
        const val MAX_GAIN = 2.8f
        const val KNEE = 0.85f
    }
}
