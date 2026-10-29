package com.ilian.xtreamplayer

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Petit serveur web local : le stick affiche une adresse (et un QR code), tu ouvres la page
 * sur ton téléphone connecté au même Wi-Fi et tu tapes les champs au clavier du téléphone.
 * Un code à 4 chiffres affiché sur la TV empêche quelqu'un d'autre sur le réseau d'envoyer des données.
 */
class PhoneInput(
    private val title: String,
    private val fields: List<Field>,
    /** false : la page accepte plusieurs envois (ex. corriger un mot de passe refusé). */
    private val once: Boolean = true,
    private val onSubmit: (Map<String, String>) -> Unit
) {
    class Field(val key: String, val label: String, val value: String = "", val password: Boolean = false, val hint: String = "")

    val pin: String = (1000..9999).random().toString()
    var url: String = ""
        private set

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var done = false

    fun start(): Boolean {
        val ip = localIp() ?: return false
        val s = try {
            ServerSocket(0)
        } catch (_: IOException) {
            return false
        }
        server = s
        url = "http://$ip:${s.localPort}"
        thread(isDaemon = true, name = "phone-input") {
            while (!s.isClosed) {
                val sock = try {
                    s.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    handle(sock)
                } catch (_: Exception) {
                } finally {
                    try {
                        sock.close()
                    } catch (_: IOException) {
                    }
                }
            }
        }
        return true
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 15_000
        val input = BufferedInputStream(sock.getInputStream())
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val first = lines.first().split(" ")
        val method = first.getOrNull(0) ?: ""
        val path = first.getOrNull(1) ?: "/"
        if (path.startsWith("/favicon")) {
            respond(sock, "404 Not Found", "text/plain", "")
            return
        }
        val html = if (method == "POST") {
            val len = lines.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val body = ByteArray(len.coerceIn(0, 64_000))
            var read = 0
            while (read < body.size) {
                val n = input.read(body, read, body.size - read)
                if (n < 0) break
                read += n
            }
            val form = parseForm(String(body, 0, read, Charsets.UTF_8))
            when {
                done -> message("C'est envoyé", "Les infos sont déjà sur ta TV. Tu peux fermer cette page.")
                form["pin"]?.trim() != pin -> page(form, "Code incorrect. Recopie le code à 4 chiffres affiché sur la TV.")
                else -> {
                    done = once
                    val values = form.filterKeys { it != "pin" }
                    Bg.main.post { onSubmit(values) }
                    message("C'est envoyé", "Regarde ta TV : les infos sont arrivées. Tu peux fermer cette page.")
                }
            }
        } else {
            page(fields.associate { it.key to it.value }, null)
        }
        respond(sock, "200 OK", "text/html; charset=utf-8", html)
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16_384) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toString("UTF-8")
        }
        return null
    }

    private fun respond(sock: Socket, status: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = sock.getOutputStream()
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        out.write(bytes)
        out.flush()
    }

    private fun parseForm(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in s.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val k = if (i < 0) pair else pair.substring(0, i)
            val v = if (i < 0) "" else pair.substring(i + 1)
            try {
                out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun shell(body: String) = """<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Xtream Player</title>
<style>
body{margin:0;min-height:100vh;color:#F4F5F7;font:16px/1.45 system-ui,-apple-system,Roboto,sans-serif;
background:radial-gradient(60% 40% at 20% 0%,rgba(240,163,94,.45),transparent 70%),radial-gradient(70% 50% at 90% 100%,rgba(91,72,128,.55),transparent 70%),#0B0D12}
main{max-width:460px;margin:0 auto;padding:28px 18px 40px}
.card{background:rgba(255,255,255,.075);border:1px solid rgba(255,255,255,.13);border-radius:22px;padding:20px;backdrop-filter:blur(20px);-webkit-backdrop-filter:blur(20px)}
h1{font-size:24px;margin:0 0 4px}p{color:#B4B9C3;margin:0 0 18px}
label{display:block;font-size:14px;color:#B4B9C3;margin:16px 0 6px}
input{width:100%;box-sizing:border-box;background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.14);border-radius:14px;color:#F4F5F7;font-size:17px;padding:13px 14px;outline:0}
input:focus{border-color:#FFFFFF;background:rgba(255,255,255,.12)}
.pin{letter-spacing:.3em;font-size:24px;text-align:center;color:#F5C451}
button{margin-top:24px;width:100%;border:0;border-radius:26px;background:#FFFFFF;color:#0B0D12;font-weight:700;font-size:17px;padding:15px}
.err{color:#FF8A80;margin-top:14px}
</style></head><body><main><div class="card">$body</div></main></body></html>"""

    private fun page(values: Map<String, String>, error: String?): String {
        val inputs = fields.joinToString("") { f ->
            val v = values[f.key] ?: ""
            """<label for="${esc(f.key)}">${esc(f.label)}</label>
<input id="${esc(f.key)}" name="${esc(f.key)}" type="${if (f.password) "password" else "text"}" value="${esc(v)}" placeholder="${esc(f.hint)}" autocomplete="off" autocapitalize="off" spellcheck="false">"""
        }
        return shell(
            """<h1>${esc(title)}</h1><p>Remplis les champs ici, puis valide : ils arrivent directement sur ta TV.</p>
<form method="post" action="/">$inputs
<label for="pin">Code affiché sur la TV</label>
<input id="pin" class="pin" name="pin" inputmode="numeric" maxlength="4" autocomplete="off">
${if (error != null) "<div class=\"err\">${esc(error)}</div>" else ""}
<button type="submit">Envoyer sur la TV</button></form>"""
        )
    }

    private fun message(title: String, text: String) = shell("<h1>${esc(title)}</h1><p>${esc(text)}</p>")

    companion object {
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }

        fun qr(text: String, size: Int): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val px = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}
