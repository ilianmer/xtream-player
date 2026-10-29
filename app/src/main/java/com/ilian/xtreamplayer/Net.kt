package com.ilian.xtreamplayer

import android.app.Activity
import android.os.Handler
import android.os.Looper
import org.json.JSONException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors

object Http {
    /** Ouvre une connexion en suivant les redirections, y compris http <-> https. */
    fun open(url: String, headers: Map<String, String> = emptyMap(), readTimeoutMs: Int = 60_000): HttpURLConnection {
        var current = url
        repeat(5) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = readTimeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", Prefs.userAgent)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) throw IOException("Redirection invalide")
                current = URL(URL(current), loc).toString()
                return@repeat
            }
            if (code >= 400) {
                c.disconnect()
                throw IOException(
                    when (code) {
                        401, 403 -> "Accès refusé par le serveur (erreur $code). Vérifie tes identifiants ou le User Agent."
                        404 -> "Adresse introuvable sur le serveur (erreur 404)."
                        else -> "Le serveur a répondu avec l'erreur $code."
                    }
                )
            }
            return c
        }
        throw IOException("Trop de redirections")
    }

    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = open(url, headers, 30_000)
        try {
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun getBytes(url: String): ByteArray {
        val c = open(url, emptyMap(), 20_000)
        try {
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}

object Bg {
    private val pool = Pools.fixed(4, "taches")
    val main = Handler(Looper.getMainLooper())

    /** Lance [work] en arrière-plan, puis [done] sur le thread principal si l'écran existe encore. */
    fun <T> task(owner: Activity, work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val r = runCatching { work() }
            main.post {
                if (!owner.isFinishing && !owner.isDestroyed) {
                    // Une erreur dans l'affichage d'un résultat ne doit jamais fermer l'appli
                    try {
                        done(r)
                    } catch (e: Exception) {
                        Crash.nonFatal(e)
                    }
                }
            }
        }
    }

    fun err(e: Throwable): String = when (e) {
        is UnknownHostException -> "Serveur introuvable. Vérifie l'adresse et ta connexion."
        is SocketTimeoutException -> "Le serveur ne répond pas (délai dépassé)."
        is ConnectException -> "Connexion au serveur impossible."
        is FileNotFoundException -> "Adresse introuvable sur le serveur."
        is JSONException -> "Réponse illisible du serveur. Vérifie l'adresse et tes identifiants."
        is IllegalArgumentException -> e.message ?: "Paramètre invalide"
        else -> e.message ?: "Erreur inconnue"
    }
}
