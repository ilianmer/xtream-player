package com.ilian.xtreamplayer

import android.app.Activity
import android.app.AlertDialog

/**
 * Réglages > Lecteur vidéo > Spots LED : activer, décaler, trouver les spots sur le Wi-Fi,
 * les reconnaître (clignotement), choisir leur rôle (basses, médiums, aigus) et tester.
 */
object LightsSettings {

    private fun roleName(r: Int) = when (r) {
        1 -> "Basses"
        2 -> "Médiums"
        3 -> "Aigus"
        else -> "Automatique"
    }

    fun section(act: Activity, rows: Rows, reshow: () -> Unit) {
        rows.header("Spots LED")
        rows.toggle(
            "Spots LED sur la musique",
            {
                if (it) "Les spots suivent le son de ce qui est lu : basses sur les spots 1-2 (flash sur chaque kick), médiums sur 3-4, aigus sur 5-6, ambiance choisie selon la musique."
                else "Les spots connectés ne suivent pas la lecture."
            },
            { Prefs.lightsOn }, { Prefs.lightsOn = it }
        )
        rows.slider(
            "Décalage des spots",
            "Retard des lumières pour coller exactement au son entendu (la TV ajoute souvent un petit délai).",
            0, 600, 25, { "$it ms" }, { Prefs.lightsDelayMs }, { Prefs.lightsDelayMs = it }
        )
        val lamps = Lamps.saved()
        rows.action(
            "Spots connectés",
            "WiZ, Govee, LIFX, Yeelight, WLED, Magic Home : en Wi-Fi local, sans passer par Internet.",
            if (lamps.isEmpty()) "Aucun" else "${lamps.size} spot${if (lamps.size > 1) "s" else ""}"
        ) { manage(act, reshow) }
        for ((i, l) in lamps.withIndex()) {
            rows.action("Spot ${i + 1} · ${l.label}", "Rôle : ${roleName(l.role)}") { lampMenu(act, i, reshow) }
        }
        if (lamps.isNotEmpty()) {
            rows.action("Tester les spots", "Les 4 ambiances défilent sur tes spots (environ 8 s).") {
                Ui.toast(act, "Calme, Groove, Énergie, Aérien…")
                Thread {
                    LightSync.demo(Lamps.output(act))
                }.apply { isDaemon = true }.start()
            }
        }
    }

    private fun manage(act: Activity, reshow: () -> Unit) {
        Ui.choose(act, "Spots connectés", listOf("Rechercher les spots sur le Wi-Fi", "Ajouter un spot par son adresse IP", "Retirer tous les spots")) { w ->
            when (w) {
                0 -> scan(act, reshow)
                1 -> addByIp(act, reshow)
                2 -> {
                    Lamps.save(emptyList())
                    reshow()
                }
            }
        }
    }

    private fun scan(act: Activity, reshow: () -> Unit) {
        Ui.toast(act, "Recherche des spots sur le Wi-Fi…")
        Bg.task(act, { Lamps.scan(act) }) { r ->
            val found = r.getOrNull().orEmpty()
            if (found.isEmpty()) {
                AlertDialog.Builder(act, Ui.DIALOG)
                    .setTitle("Aucun spot trouvé")
                    .setMessage(
                        "Vérifie que les spots sont allumés et sur le même Wi-Fi que le stick.\n\n" +
                            "Govee : active « Contrôle LAN » dans l'appli Govee Home.\n" +
                            "Yeelight : active « Contrôle LAN » dans l'appli Yeelight.\n" +
                            "Sinon, ajoute-les par leur adresse IP (visible dans l'appli du spot ou de ta box)."
                    )
                    .setPositiveButton("Ajouter par adresse IP") { _, _ -> addByIp(act, reshow) }
                    .setNegativeButton("Fermer", null)
                    .show()
                return@task
            }
            val current = Lamps.saved()
            val labels = found.map { it.label }.toTypedArray()
            val checked = found.map { f -> current.isEmpty() || current.any { it.ip == f.ip } }.toBooleanArray()
            AlertDialog.Builder(act, Ui.DIALOG)
                .setTitle("${found.size} spot${if (found.size > 1) "s" else ""} trouvé${if (found.size > 1) "s" else ""} · coche ceux à utiliser")
                .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
                .setPositiveButton("Enregistrer") { _, _ ->
                    // On garde l'ordre et les rôles déjà choisis, les nouveaux s'ajoutent à la suite
                    val keep = current.filter { c -> found.withIndex().any { (i, f) -> checked[i] && f.ip == c.ip } }
                    val added = found.filterIndexed { i, f -> checked[i] && keep.none { it.ip == f.ip } }
                    Lamps.save(keep + added)
                    Ui.toast(act, "Ouvre chaque spot pour le faire clignoter et le reconnaître.")
                    reshow()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    private fun addByIp(act: Activity, reshow: () -> Unit) {
        Ui.choose(act, "Marque du spot", Lamps.BRANDS) { b ->
            Ui.textInput(act, "Adresse IP du spot", "192.168.1.", "ex : 192.168.1.42") { ip ->
                val t = ip.trim()
                if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(t)) {
                    Ui.toast(act, "Adresse IP invalide.")
                    return@textInput
                }
                Lamps.save(Lamps.saved().filter { it.ip != t } + Lamp(Lamps.BRANDS[b], t, t))
                reshow()
            }
        }
    }

    private fun lampMenu(act: Activity, index: Int, reshow: () -> Unit) {
        val list = Lamps.saved()
        val l = list.getOrNull(index) ?: return
        val opts = listOf("Faire clignoter", "Rôle : ${roleName(l.role)}", "Monter d'une place", "Retirer ce spot")
        Ui.choose(act, "Spot ${index + 1} · ${l.label}", opts) { w ->
            when (w) {
                0 -> Thread { Lamps.blink(act, l) }.apply { isDaemon = true }.start()
                1 -> Ui.choose(act, "Rôle du spot ${index + 1}", listOf("Automatique (selon sa place)", "Basses (flash sur le kick)", "Médiums", "Aigus")) { r ->
                    Lamps.save(list.mapIndexed { i, x -> if (i == index) x.copy(role = r) else x })
                    reshow()
                }
                2 -> if (index > 0) {
                    val m = list.toMutableList()
                    m.add(index - 1, m.removeAt(index))
                    Lamps.save(m)
                    reshow()
                }
                3 -> {
                    Lamps.save(list.filterIndexed { i, _ -> i != index })
                    reshow()
                }
            }
        }
    }
}
