package com.ilian.xtreamplayer

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal des incidents : si l'appli plante, la cause est gardée sur le stick (Réglages > Interface),
 * pour pouvoir la corriger. Les erreurs rattrapées sans planter sont aussi notées.
 */
object Crash {
    private var dir: File? = null

    fun install(ctx: Context) {
        dir = ctx.filesDir
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                write("crash.txt", "Plantage", t.name, e, append = false)
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** Erreur rattrapée (l'appli continue) : notée pour diagnostic. */
    fun nonFatal(e: Throwable) {
        try {
            write("errors.txt", "Erreur rattrapée", Thread.currentThread().name, e, append = true)
        } catch (_: Throwable) {
        }
    }

    private fun write(name: String, kind: String, thread: String, e: Throwable, append: Boolean) {
        val d = dir ?: return
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val head = "$kind · ${SimpleDateFormat("d MMM yyyy HH:mm:ss", Locale.FRANCE).format(Date())} · ${thread}\n"
        val f = File(d, name)
        if (append && f.length() > 60_000) f.writeText(f.readText().takeLast(30_000))
        if (append) f.appendText(head + sw.toString().take(6_000) + "\n") else f.writeText(head + sw.toString().take(20_000))
    }

    /** Dernier plantage (null s'il n'y en a pas). */
    fun last(ctx: Context): String? = File(ctx.filesDir, "crash.txt").takeIf { it.isFile }?.readText()

    fun clear(ctx: Context) {
        File(ctx.filesDir, "crash.txt").delete()
        File(ctx.filesDir, "errors.txt").delete()
    }
}
