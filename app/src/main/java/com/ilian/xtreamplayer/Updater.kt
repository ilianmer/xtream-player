package com.ilian.xtreamplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Mises à jour depuis l'appli : on regarde la dernière version publiée sur GitHub,
 * on la télécharge, puis on ouvre l'écran d'installation d'Android (un clic sur « Mettre à jour »).
 * Le numéro de version de l'APK est le numéro de build GitHub, ce qui permet de comparer directement.
 */
object Updater {
    private const val REPO = "ilianmer/xtream-player"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK_URL = "https://github.com/$REPO/releases/latest/download/XtreamPlayer.apk"

    data class Release(val build: Long, val notes: String, val apkUrl: String)

    private var askedThisSession = false
    /** APK téléchargé en attente de l'autorisation « installer des applis inconnues ». */
    private var pendingApk: File? = null

    fun installedBuild(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Exception) {
        0L
    }

    fun installedLabel(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** Bloquant : dernière version publiée. */
    fun latest(): Release? {
        val o = JSONObject(Http.getText(API, mapOf("Accept" to "application/vnd.github+json")))
        val build = o.optString("tag_name").removePrefix("build-").toLongOrNull() ?: return null
        var url = APK_URL
        o.optJSONArray("assets")?.let { a ->
            for (i in 0 until a.length()) {
                val asset = a.optJSONObject(i) ?: continue
                if (asset.optString("name").endsWith(".apk")) {
                    asset.optString("browser_download_url").takeIf { it.startsWith("https://") }?.let { url = it }
                    break
                }
            }
        }
        val notes = o.optString("body").lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Co-Authored-By") && !it.startsWith("Claude-Session") }
            .take(8).joinToString("\n")
        return Release(build, notes, url)
    }

    /** Au lancement (une fois par session) : propose la nouvelle version s'il y en a une. */
    fun checkOnLaunch(act: Activity) {
        if (askedThisSession) return
        // Version de test (captures automatiques) : jamais de proposition de mise à jour
        if (act.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) return
        askedThisSession = true
        if (System.currentTimeMillis() < Prefs.updateSnoozeUntil) return
        Bg.task(act, { latest() }) { r ->
            val rel = r.getOrNull() ?: return@task
            if (rel.build > installedBuild(act)) propose(act, rel, fromLaunch = true) else clearCache(act)
        }
    }

    /** Depuis les réglages : vérification immédiate, avec une réponse dans tous les cas. */
    fun checkNow(act: Activity) {
        Ui.toast(act, "Recherche d'une mise à jour…")
        Bg.task(act, { latest() }) { r ->
            r.onSuccess { rel ->
                when {
                    rel == null -> Ui.toast(act, "Aucune version publiée trouvée.")
                    rel.build > installedBuild(act) -> propose(act, rel, fromLaunch = false)
                    else -> {
                        clearCache(act)
                        Ui.toast(act, "Tu as déjà la dernière version (build ${installedBuild(act)}).")
                    }
                }
            }.onFailure { Ui.toast(act, "Vérification impossible : ${Bg.err(it)}") }
        }
    }

    /** Notes de version mises en forme : titre en blanc, puces, rappel en petit. */
    private fun notes(act: Activity, rel: Release): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        fun span(start: Int, vararg what: Any) {
            for (w in what) sb.setSpan(w, start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        var st = sb.length
        sb.append("Version ${rel.build}  ·  tu as la ${installedBuild(act)}")
        span(st, android.text.style.ForegroundColorSpan(act.getColor(R.color.accent)), android.text.style.RelativeSizeSpan(0.92f))
        val lines = rel.notes.lines().map { it.trim() }.filter { it.isNotEmpty() }
        lines.forEachIndexed { i, line ->
            sb.append(if (i == 0) "\n\n" else "\n")
            st = sb.length
            if (line.startsWith("- ") || line.startsWith("• ")) {
                sb.append("•  ").append(line.drop(2))
                span(st, android.text.style.ForegroundColorSpan(act.getColor(R.color.text)))
            } else {
                sb.append(line)
                span(st, android.text.style.ForegroundColorSpan(act.getColor(R.color.white)), android.text.style.RelativeSizeSpan(1.06f))
            }
        }
        sb.append("\n\n")
        st = sb.length
        sb.append("Ta source, tes profils et tes favoris sont conservés.")
        span(st, android.text.style.RelativeSizeSpan(0.88f))
        return sb
    }

    private fun propose(act: Activity, rel: Release, fromLaunch: Boolean) {
        Sheet.message(
            act, "Nouvelle version disponible", notes(act, rel),
            primary = "Mettre à jour", onPrimary = { download(act, rel) },
            secondary = if (fromLaunch) "Plus tard" else "Annuler",
            onSecondary = { if (fromLaunch) Prefs.updateSnoozeUntil = System.currentTimeMillis() + 24 * 3_600_000L }
        )
    }

    private fun clearCache(ctx: Context) {
        File(ctx.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    private fun download(act: Activity, rel: Release) {
        val cancelled = AtomicBoolean(false)
        val prog = Sheet.progress(act, "Mise à jour · version ${rel.build}", "Téléchargement…") { cancelled.set(true) }
        val dlg = prog.dialog
        val bar = prog.bar
        val label = prog.label

        thread(isDaemon = true, name = "update") {
            val dir = File(act.cacheDir, "updates").apply { mkdirs() }
            val f = File(dir, "XtreamPlayer-${rel.build}.apk")
            try {
                dir.listFiles()?.forEach { if (it != f) it.delete() }
                val c = Http.open(rel.apkUrl, emptyMap(), 30_000)
                try {
                    val total = c.contentLengthLong
                    var done = 0L
                    var lastUi = 0L
                    c.inputStream.use { inp ->
                        FileOutputStream(f).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled.get()) throw IOException("annulé")
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - lastUi > 150) {
                                    lastUi = now
                                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                    val mb = String.format(java.util.Locale.FRANCE, "%.1f", done / 1_048_576.0)
                                    Bg.main.post {
                                        if (pct >= 0) {
                                            bar.isIndeterminate = false
                                            bar.setProgress(pct * 10, true)
                                            label.text = "Téléchargement… $pct %  ($mb Mo)"
                                        } else label.text = "Téléchargement… $mb Mo"
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
                // Garde-fou : c'est bien Xtream Player, et une version plus récente.
                val info = act.packageManager.getPackageArchiveInfo(f.path, 0)
                val build = when {
                    info == null -> -1L
                    Build.VERSION.SDK_INT >= 28 -> info.longVersionCode
                    else -> @Suppress("DEPRECATION") info.versionCode.toLong()
                }
                if (info?.packageName != act.packageName || build <= installedBuild(act)) {
                    throw IOException("le fichier téléchargé n'est pas une version plus récente de l'appli")
                }
                Bg.main.post {
                    dlg.dismiss()
                    if (!act.isFinishing) install(act, f)
                }
            } catch (e: Exception) {
                f.delete()
                Bg.main.post {
                    dlg.dismiss()
                    if (!cancelled.get() && !act.isFinishing) Ui.toast(act, "Mise à jour impossible : ${Bg.err(e)}")
                }
            }
        }
    }

    private fun install(act: Activity, f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            // Première fois : Android demande d'autoriser Xtream Player à installer des applis.
            pendingApk = f
            Sheet.message(
                act, "Une autorisation, une seule fois",
                "Pour s'installer elle-même, l'appli doit être autorisée à installer des applis.\n\nSur l'écran suivant, active Xtream Player, puis reviens avec la touche Retour : l'installation se lance.",
                primary = "Ouvrir le réglage",
                onPrimary = {
                    try {
                        act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName)))
                    } catch (_: ActivityNotFoundException) {
                        // Pas d'écran dédié sur ce boîtier : l'installeur d'Android proposera lui-même le réglage.
                        pendingApk = null
                        launchInstaller(act, f)
                    }
                },
                onSecondary = { pendingApk = null }
            )
            return
        }
        launchInstaller(act, f)
    }

    /** À appeler au retour sur un écran : reprend l'installation une fois l'autorisation donnée. */
    fun resume(act: Activity) {
        val f = pendingApk ?: return
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) return
        pendingApk = null
        if (f.exists()) launchInstaller(act, f)
    }

    private fun launchInstaller(act: Activity, f: File) {
        try {
            val uri = FileProvider.getUriForFile(act, act.packageName + ".files", f)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            act.startActivity(i)
        } catch (e: Exception) {
            Ui.toast(act, "Impossible d'ouvrir l'installation : ${Bg.err(e)}. Mets à jour avec Downloader.")
        }
    }
}
