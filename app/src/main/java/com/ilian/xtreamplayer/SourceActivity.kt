package com.ilian.xtreamplayer

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Ajout ou modification d'une source IPTV. */
class SourceActivity : Activity() {

    private lateinit var etName: EditText
    private lateinit var etServer: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var btnSave: Button
    private lateinit var tvStatus: TextView
    private var editId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_source)
        etName = findViewById(R.id.etName)
        etServer = findViewById(R.id.etServer)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        btnSave = findViewById(R.id.btnSave)
        tvStatus = findViewById(R.id.tvStatus)

        editId = intent.getLongExtra(EXTRA_ID, -1L)
        val existing = if (editId >= 0) Db.source(editId) else null
        findViewById<TextView>(R.id.tvTitle).text = if (existing != null) "Modifier la source" else "Ajouter une source"
        if (existing != null) {
            etName.setText(existing.name)
            etServer.setText(existing.server)
            etUser.setText(existing.user)
            etPass.setText(existing.pass)
        }

        btnSave.setOnClickListener { save() }
        findViewById<Button>(R.id.btnPhone).setOnClickListener { fromPhone() }
        findViewById<Button>(R.id.btnPhone).requestFocus()
    }

    private fun fromPhone() {
        val fields = listOf(
            PhoneInput.Field("name", "Nom de la source", etName.text.toString(), hint = "ex : Maison"),
            PhoneInput.Field("server", "Serveur, ou lien M3U complet", etServer.text.toString(), hint = "http://exemple.com:8080"),
            PhoneInput.Field("user", "Nom d'utilisateur", etUser.text.toString()),
            PhoneInput.Field("pass", "Mot de passe", etPass.text.toString(), password = true)
        )
        Ui.phoneInput(this, "Source IPTV", fields) { m ->
            etName.setText(m["name"].orEmpty())
            etServer.setText(m["server"].orEmpty())
            etUser.setText(m["user"].orEmpty())
            etPass.setText(m["pass"].orEmpty())
            save()
        }
    }

    private fun save() {
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
            tvStatus.text = Bg.err(e)
            return
        }

        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            tvStatus.text = "Remplis le serveur, l'identifiant et le mot de passe."
            return
        }
        val api = try {
            XtreamApi(server, user, pass)
        } catch (e: Exception) {
            tvStatus.text = Bg.err(e)
            return
        }
        val name = etName.text.toString().trim().ifBlank { android.net.Uri.parse(api.base).host ?: "Ma source" }

        tvStatus.text = "Vérification auprès de ${api.base}…"
        btnSave.isEnabled = false
        Bg.task(this, { api.account() }) { r ->
            btnSave.isEnabled = true
            r.onSuccess { a ->
                when {
                    !a.auth -> tvStatus.text = "Identifiant ou mot de passe refusé."
                    a.status != null && !a.status.equals("Active", ignoreCase = true) ->
                        tvStatus.text = "Le serveur indique que le compte est : ${a.status}."
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
            }.onFailure { tvStatus.text = Bg.err(it) }
        }
    }

    companion object {
        const val EXTRA_ID = "source_id"
    }
}
