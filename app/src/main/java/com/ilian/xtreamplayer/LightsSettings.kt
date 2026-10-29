package com.ilian.xtreamplayer

import android.app.Activity
import android.app.AlertDialog

/**
 * Réglages > Lecteur vidéo > Spots LED : activer, décaler, trouver les spots (Wi-Fi et Bluetooth),
 * les reconnaître (clignotement), choisir leur rôle (basses, médiums, aigus) et tester.
 */
object LightsSettings {
    const val REQ_BLUETOOTH = 4711
    private var afterPermission: (() -> Unit)? = null

    /** Réponse à la demande d'accès Bluetooth : on lance la recherche dans tous les cas (le Wi-Fi marche sans). */
    fun onPermissionResult(requestCode: Int) {
        if (requestCode != REQ_BLUETOOTH) return
        val next = afterPermission
        afterPermission = null
        next?.invoke()
    }

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
            "Wi-Fi : WiZ, Govee, LIFX, Yeelight, WLED, Magic Home. Bluetooth : Govee, Hue, rubans et ampoules LED (ELK-BLEDOM, Lotus Lamp, Happy Lighting, MohuanLED…). Toujours en direct, sans Internet.",
            if (lamps.isEmpty()) "Aucun" else "${lamps.size} spot${if (lamps.size > 1) "s" else ""}"
        ) { manage(act, reshow) }
        for ((i, l) in lamps.withIndex()) {
            val proto = Ble.variants(l).getOrNull(l.extra)?.takeIf { l.extra > 0 }?.let { " · Protocole : $it" }.orEmpty()
            rows.action("Spot ${i + 1} · ${l.label}", "Rôle : ${roleName(l.role)}$proto") { lampMenu(act, i, reshow) }
        }
        if (lamps.isNotEmpty()) {
            rows.action("Tester les spots", "Les 4 ambiances défilent sur tes spots (environ 8 s).") {
                Ui.toast(act, if (lamps.any { it.ble }) "Connexion Bluetooth, puis Calme, Groove, Énergie, Aérien…" else "Calme, Groove, Énergie, Aérien…")
                Thread {
                    LightSync.demo(Lamps.output(act))
                }.apply { isDaemon = true }.start()
            }
        }
    }

    private fun manage(act: Activity, reshow: () -> Unit) {
        Ui.choose(act, "Spots connectés", listOf("Rechercher les spots sur le Wi-Fi", "Ajouter un spot par son adresse IP", "Retirer tous les spots")) { w ->
            when (w) {
                0 -> askThenScan(act, reshow)
                1 -> addByIp(act, reshow)
                2 -> {
                    Lamps.save(emptyList())
                    reshow()
                }
            }
        }
    }

    /** Le Bluetooth demande une autorisation Android (une seule fois) avant de chercher. */
    private fun askThenScan(act: Activity, reshow: () -> Unit) {
        val missing = if (Ble.available(act)) Ble.missingPerms(act) else emptyArray()
        if (missing.isNotEmpty()) {
            afterPermission = { scan(act, reshow) }
            try {
                act.requestPermissions(missing, REQ_BLUETOOTH)
                return
            } catch (_: Exception) {
                afterPermission = null
            }
        }
        scan(act, reshow)
    }

    private fun scan(act: Activity, reshow: () -> Unit) {
        if (act.isFinishing || act.isDestroyed) return
        val bt = Ble.available(act) && Ble.missingPerms(act).isEmpty()
        Ui.toast(act, if (bt) "Recherche des spots en Wi-Fi et en Bluetooth…" else "Recherche des spots sur le Wi-Fi…")
        Bg.task(act, { Lamps.scan(act) }) { r ->
            val found = r.getOrNull().orEmpty()
            if (found.isEmpty()) {
                val btHint = when {
                    !Ble.available(act) -> ""
                    !bt -> "Bluetooth : autorise l'accès Bluetooth quand l'appli le demande (ou dans Paramètres Android > Applications > Xtream Player > Autorisations).\n"
                    Ble.locationOff(act) -> "Bluetooth : active la localisation dans les Paramètres Android, sinon le stick ne voit pas les spots Bluetooth.\n"
                    else -> "Bluetooth : ferme l'appli du spot sur ton téléphone (un spot Bluetooth n'accepte souvent qu'une connexion à la fois) et rapproche-le du stick.\n"
                }
                AlertDialog.Builder(act, Ui.DIALOG)
                    .setTitle("Aucun spot trouvé")
                    .setMessage(
                        "Vérifie que les spots sont allumés.\n\n" +
                            "Wi-Fi : même réseau que le stick. Govee et Yeelight : active « Contrôle LAN » dans leur appli.\n" +
                            btHint +
                            "\nSinon, ajoute-les par leur adresse (IP ou Bluetooth, visible dans l'appli du spot ou de ta box)."
                    )
                    .setPositiveButton("Ajouter par adresse") { _, _ -> addByIp(act, reshow) }
                    .setNegativeButton("Fermer", null)
                    .show()
                return@task
            }
            val current = Lamps.saved()
            val labels = found.map { it.label }.toTypedArray()
            // Un même Govee peut répondre en Wi-Fi et en Bluetooth : le Wi-Fi est plus réactif, on le préfère
            val goveeWifi = found.any { it.brand == Lamps.GOVEE }
            val checked = found.map { f ->
                if (current.isNotEmpty()) current.any { it.ip == f.ip }
                else !(f.brand == Lamps.BT_GOVEE && goveeWifi)
            }.toBooleanArray()
            AlertDialog.Builder(act, Ui.DIALOG)
                .setTitle("${found.size} spot${if (found.size > 1) "s" else ""} trouvé${if (found.size > 1) "s" else ""} · coche ceux à utiliser")
                .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
                .setPositiveButton("Enregistrer") { _, _ ->
                    // On garde l'ordre et les rôles déjà choisis, les nouveaux s'ajoutent à la suite
                    val keep = current.filter { c -> found.withIndex().any { (i, f) -> checked[i] && f.ip == c.ip } }
                    val added = found.filterIndexed { i, f -> checked[i] && keep.none { it.ip == f.ip } }
                    Lamps.save(keep + added)
                    Ui.toast(
                        act,
                        if ((keep + added).any { it.ble }) "Ouvre chaque spot pour le faire clignoter. Bluetooth : ferme l'appli du spot sur ton téléphone."
                        else "Ouvre chaque spot pour le faire clignoter et le reconnaître."
                    )
                    reshow()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    private fun addByIp(act: Activity, reshow: () -> Unit) {
        val brands = Lamps.BRANDS.map { "$it (Wi-Fi)" } + Lamps.BT_BRANDS
        Ui.choose(act, "Type de spot", brands) { b ->
            if (b < Lamps.BRANDS.size) {
                Ui.textInput(act, "Adresse IP du spot", "192.168.1.", "ex : 192.168.1.42") { ip ->
                    val t = ip.trim()
                    if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(t)) {
                        Ui.toast(act, "Adresse IP invalide.")
                        return@textInput
                    }
                    Lamps.save(Lamps.saved().filter { it.ip != t } + Lamp(Lamps.BRANDS[b], t, t))
                    reshow()
                }
            } else {
                val brand = Lamps.BT_BRANDS[b - Lamps.BRANDS.size]
                Ui.textInput(act, "Adresse Bluetooth du spot", "", "ex : A4:C1:38:12:34:56") { mac ->
                    val t = mac.trim().uppercase().replace('-', ':')
                    if (!Regex("""([0-9A-F]{2}:){5}[0-9A-F]{2}""").matches(t)) {
                        Ui.toast(act, "Adresse Bluetooth invalide.")
                        return@textInput
                    }
                    val add = { Lamps.save(Lamps.saved().filter { it.ip != t } + Lamp(brand, t, brand)); reshow() }
                    val missing = Ble.missingPerms(act)
                    if (missing.isNotEmpty()) {
                        afterPermission = add
                        try {
                            act.requestPermissions(missing, REQ_BLUETOOTH)
                        } catch (_: Exception) {
                            afterPermission = null
                            add()
                        }
                    } else add()
                }
            }
        }
    }

    private fun lampMenu(act: Activity, index: Int, reshow: () -> Unit) {
        val list = Lamps.saved()
        val l = list.getOrNull(index) ?: return
        val variants = Ble.variants(l)
        val opts = listOf("Faire clignoter", "Rôle : ${roleName(l.role)}", "Monter d'une place", "Retirer ce spot") +
            (if (variants.isNotEmpty()) listOf("Protocole : ${variants.getOrElse(l.extra) { variants[0] }}") else emptyList())
        Ui.choose(act, "Spot ${index + 1} · ${l.label}", opts) { w ->
            when (w) {
                0 -> {
                    if (l.ble) Ui.toast(act, "Connexion Bluetooth au spot…")
                    Thread { Lamps.blink(act, l) }.apply { isDaemon = true }.start()
                }
                4 -> Ui.choose(act, "Protocole du spot ${index + 1} (si le spot ne réagit pas)", variants) { v ->
                    Lamps.save(list.mapIndexed { i, x -> if (i == index) x.copy(extra = v) else x })
                    reshow()
                }
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
