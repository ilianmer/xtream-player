package com.ilian.xtreamplayer

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView

/** Écran de connexion : ajout ou modification d'une source IPTV (téléphone ou télécommande). */
class SourceActivity : Activity() {

    private lateinit var etName: EditText
    private lateinit var etServer: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var btnSave: Button
    private lateinit var tvStatus: TextView
    private var editId = -1L
    private var phone: PhoneInput? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_source)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        etName = findViewById(R.id.etName)
        etServer = findViewById(R.id.etServer)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        btnSave = findViewById(R.id.btnSave)
        tvStatus = findViewById(R.id.tvStatus)

        editId = intent.getLongExtra(EXTRA_ID, -1L)
        val existing = if (editId >= 0) Db.source(editId) else null
        if (existing != null) {
            findViewById<TextView>(R.id.tvTitle).text = "Modifier la source"
            etName.setText(existing.name)
            etServer.setText(existing.server)
            etUser.setText(existing.user)
            etPass.setText(existing.pass)
        } else if (Db.sources().isNotEmpty()) {
            findViewById<TextView>(R.id.tvTitle).text = "Ajouter une source"
        }

        btnSave.setOnClickListener { save() }
        startPhone()
        if (existing != null) btnSave.requestFocus() else etServer.requestFocus()
    }

    /** Démarre la page de saisie sur téléphone et affiche son QR code directement à l'écran. */
    private fun startPhone() {
        val fields = listOf(
            PhoneInput.Field("name", "Nom de la source", etName.text.toString(), hint = "ex : Maison"),
            PhoneInput.Field("server", "Serveur, ou lien M3U complet", etServer.text.toString(), hint = "http://exemple.com:8080"),
            PhoneInput.Field("user", "Nom d'utilisateur", etUser.text.toString()),
            PhoneInput.Field("pass", "Mot de passe", etPass.text.toString(), password = true)
        )
        val pi = PhoneInput("Source IPTV", fields, once = false) { m ->
            if (!busy && !isDestroyed) {
                etName.setText(m["name"].orEmpty())
                etServer.setText(m["server"].orEmpty())
                etUser.setText(m["user"].orEmpty())
                etPass.setText(m["pass"].orEmpty())
                save()
            }
        }
        val qr = findViewById<ImageView>(R.id.qr)
        val step1 = findViewById<TextView>(R.id.step1)
        val pin = findViewById<TextView>(R.id.pin)
        if (pi.start()) {
            phone = pi
            qr.setImageBitmap(PhoneInput.qr(pi.url, 300))
            step1.text = "Scanne le QR code, ou ouvre ${pi.url.removePrefix("http://")} sur ton téléphone"
            pin.text = pi.pin
        } else {
            qr.setImageResource(R.drawable.ic_phone)
            qr.setColorFilter(0xFF0B0D12.toInt())
            step1.text = "Connecte le stick au Wi-Fi pour pouvoir tout saisir depuis ton téléphone"
            pin.text = "—"
        }
    }

    override fun onDestroy() {
        phone?.stop()
        super.onDestroy()
    }

    private fun status(text: String, error: Boolean) {
        tvStatus.text = text
        tvStatus.setTextColor(getColor(if (error) R.color.danger else R.color.accent))
    }

    private fun save() {
        if (busy) return
        var server = etServer.text.toString().trim()
        var user = etUser.text.toString().trim()
        var pass = etPass.text.toString()

        // Lien M3U collé dans le champ serveur : on en extrait tout.
        try {
            XtreamApi.parseM3uLink(server)?.let { (s, u, p) ->
                server = s
                user = u
                pass = p
                etServer.setText(s)
                etUser.setText(u)
                etPass.setText(p)
            }
        } catch (e: Exception) {
            status(Bg.err(e), true)
            return
        }

        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            status("Remplis le serveur, l'identifiant et le mot de passe.", true)
            return
        }
        val api = try {
            XtreamApi(server, user, pass)
        } catch (e: Exception) {
            status(Bg.err(e), true)
            return
        }
        val name = etName.text.toString().trim().ifBlank { android.net.Uri.parse(api.base).host ?: "Ma source" }

        status("Vérification auprès du serveur…", false)
        busy = true
        btnSave.isEnabled = false
        Bg.task(this, { api.account() }) { r ->
            busy = false
            btnSave.isEnabled = true
            r.onSuccess { a ->
                when {
                    !a.auth -> status("Identifiant ou mot de passe refusé.", true)
                    a.status != null && !a.status.equals("Active", ignoreCase = true) ->
                        status("Le serveur indique que le compte est : ${a.status}.", true)
                    else -> {
                        val id = if (editId >= 0) {
                            Db.updateSource(editId, name, api.base, user, pass)
                            editId
                        } else {
                            Db.addSource(name, api.base, user, pass)
                        }
                        Db.updateAccount(id, a)
                        Prefs.activeSourceId = id
                        // La mise à jour du catalogue se fait si besoin (identifiants changés ou nouvelle source).
                        Ui.restart(this, forceSync = false)
                    }
                }
            }.onFailure { status(Bg.err(it), true) }
        }
    }

    companion object {
        const val EXTRA_ID = "source_id"
    }
}
