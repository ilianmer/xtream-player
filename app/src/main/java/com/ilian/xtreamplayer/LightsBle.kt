package com.ilian.xtreamplayer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * Spots Bluetooth (BLE), pilotés directement depuis le stick, sans cloud ni passerelle :
 * Govee, Philips Hue, et les rubans / ampoules LED génériques (ELK-BLEDOM, Lotus Lamp, duoCo,
 * Triones / Happy Lighting, MohuanLED / BJ_LED, LEDBLE…). Le protocole est reconnu à la connexion.
 */
@SuppressLint("MissingPermission")
object Ble {
    /** Un seul fil pour tout le Bluetooth : les rappels Android et nos envois y sont sérialisés. */
    val handler: Handler by lazy {
        Handler(HandlerThread("ble-lights", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
    }

    private val links = HashMap<String, BleLink>()

    fun perms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun missingPerms(ctx: Context): Array<String> =
        perms().filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    fun adapter(ctx: Context): BluetoothAdapter? = try {
        (ctx.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    } catch (_: Exception) {
        null
    }

    fun available(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && adapter(ctx) != null

    fun canConnect(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Android 11 et avant : sans la localisation activée, la recherche Bluetooth ne trouve rien. */
    fun locationOff(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 31) return false
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            if (Build.VERSION.SDK_INT >= 28) !lm.isLocationEnabled
            else {
                @Suppress("DEPRECATION")
                android.provider.Settings.Secure.getInt(ctx.contentResolver, android.provider.Settings.Secure.LOCATION_MODE, 1) == 0
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---------- Recherche ----------

    private val LED_NAMES = listOf(
        "elk-", "melk", "modelx", "duoco", "lotus", "xsl-", "led light strip", "ledble", "led-", "jackyled",
        "triones", "dream~", "ledblue", "qhm-", "bj_led", "mohuan", "happylighting", "happy lighting"
    )
    private val LED_SERVICES = setOf("fff0", "ffe0", "ffd5", "ffd0", "ffe5", "ee00")
    private val GOVEE_NAME = Regex("^(ihoment|govee|gbk|minger)_", RegexOption.IGNORE_CASE)

    private fun short(u: UUID): String {
        val s = u.toString().lowercase()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) s.substring(4, 8) else s
    }

    /** Marque reconnue à partir du nom annoncé et des services, ou null si ce n'est pas une lumière connue. */
    fun classify(mac: String, name: String?, services: Set<String>): Lamp? {
        val n = name.orEmpty().trim()
        val l = n.lowercase()
        val brand = when {
            GOVEE_NAME.containsMatchIn(l) -> Lamps.BT_GOVEE
            "fe0f" in services && !l.startsWith("wiz") -> Lamps.BT_HUE
            l.startsWith("hue ") || l.startsWith("philips hue") -> Lamps.BT_HUE
            LED_NAMES.any { l.startsWith(it) } -> Lamps.BT_LED
            n.isNotBlank() && services.any { it in LED_SERVICES } -> Lamps.BT_LED
            else -> return null
        }
        return Lamp(brand, mac.uppercase(), n.ifBlank { brand })
    }

    /** Cherche les spots Bluetooth à proximité (bloquant, environ 5 s). */
    fun scan(ctx: Context, timeoutMs: Long = 5_000): List<Lamp> {
        val ad = adapter(ctx) ?: return emptyList()
        if (!ad.isEnabled || missingPerms(ctx).isNotEmpty()) return emptyList()
        val found = ConcurrentHashMap<String, Lamp>()
        // Déjà appairés avec le stick (rare pour des lumières, mais possible)
        try {
            for (d in ad.bondedDevices.orEmpty()) {
                if (d.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) continue
                classify(d.address, d.name, emptySet())?.let { found.putIfAbsent(d.address, it) }
            }
        } catch (_: Exception) {
        }
        val scanner = try {
            ad.bluetoothLeScanner
        } catch (_: Exception) {
            null
        } ?: return found.values.toList()

        fun add(r: ScanResult) {
            try {
                val rec = r.scanRecord
                val name = rec?.deviceName ?: try {
                    r.device.name
                } catch (_: Exception) {
                    null
                }
                val svc = HashSet<String>()
                rec?.serviceUuids?.forEach { svc.add(short(it.uuid)) }
                rec?.serviceData?.keys?.forEach { svc.add(short(it.uuid)) }
                val mac = r.device.address ?: return
                classify(mac, name, svc)?.let { lamp ->
                    // Un nom peut arriver plus tard (réponse de scan) : on garde le plus parlant
                    val prev = found[mac]
                    if (prev == null || (prev.id == prev.brand && lamp.id != lamp.brand)) found[mac] = lamp
                }
            } catch (_: Exception) {
            }
        }

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = add(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { add(it) }
        }
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            Thread.sleep(timeoutMs)
        } catch (_: Exception) {
        } finally {
            try {
                scanner.stopScan(cb)
            } catch (_: Exception) {
            }
        }
        return found.values.toList()
    }

    // ---------- Connexions (partagées, gardées quelques secondes entre deux lectures) ----------

    internal fun link(ctx: Context, lamp: Lamp): BleLink {
        synchronized(links) {
            val key = lamp.ip.uppercase()
            val cur = links[key]
            if (cur != null && cur.lamp.brand == lamp.brand && cur.lamp.extra == lamp.extra && !cur.dead) return cur
            cur?.retire()
            val created = BleLink(ctx.applicationContext, key, lamp)
            links[key] = created
            return created
        }
    }

    internal fun forget(link: BleLink) {
        synchronized(links) {
            if (links[link.mac] === link) links.remove(link.mac)
        }
    }

    // ---------- Protocoles ----------

    const val P_NONE = 0
    const val P_ELK = 1
    const val P_ELK7 = 2
    const val P_TRIONES = 3
    const val P_BJ = 4
    const val P_LEDBLE = 5
    const val P_LEDRBG = 6
    const val P_GOVEE = 7
    const val P_GOVEE_SEG = 8
    const val P_GOVEE_D = 9
    const val P_HUE = 10
    const val P_HUE_SPLIT = 11

    /** Choix proposés dans Réglages (« Protocole ») si un spot reste éteint en automatique. */
    val LED_VARIANTS = listOf(
        "Automatique", "ELK-BLEDOM / Lotus Lamp (A)", "ELK-BLEDOM (B)", "Triones / Happy Lighting",
        "MohuanLED / BJ_LED", "LEDBLE", "LEDBLE (rouge-bleu-vert)"
    )
    private val LED_VARIANT_PROTO = intArrayOf(P_NONE, P_ELK, P_ELK7, P_TRIONES, P_BJ, P_LEDBLE, P_LEDRBG)
    val GOVEE_VARIANTS = listOf("Automatique", "Classique", "Segments (rubans RGBIC)", "Alternatif")
    private val GOVEE_VARIANT_PROTO = intArrayOf(P_NONE, P_GOVEE, P_GOVEE_SEG, P_GOVEE_D)

    fun variants(lamp: Lamp): List<String> = when (lamp.brand) {
        Lamps.BT_LED -> LED_VARIANTS
        Lamps.BT_GOVEE -> GOVEE_VARIANTS
        else -> emptyList()
    }

    internal fun forcedProto(lamp: Lamp): Int = when (lamp.brand) {
        Lamps.BT_LED -> LED_VARIANT_PROTO.getOrElse(lamp.extra) { P_NONE }
        Lamps.BT_GOVEE -> GOVEE_VARIANT_PROTO.getOrElse(lamp.extra) { P_NONE }
        else -> P_NONE
    }

    fun u16(s: String): UUID = UUID.fromString("0000$s-0000-1000-8000-00805f9b34fb")
    val GOVEE_CTRL: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b11")
    private fun hue(n: String): UUID = UUID.fromString("932c32bd-$n-47a2-835a-a8d455b859dd")
    val HUE_POWER = hue("0002")
    val HUE_BRIGHT = hue("0003")
    val HUE_XY = hue("0005")
    val HUE_CTRL = hue("0007")

    /** Rubans RGBIC Govee connus pour n'accepter que la couleur « par segments ». */
    private val GOVEE_SEGMENTED = setOf("H6053", "H6072", "H6102", "H6199")

    internal fun goveeModel(name: String): String =
        Regex("""_(H[0-9A-Z]{4})""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.uppercase().orEmpty()

    internal fun goveeSegmented(name: String): Boolean {
        val m = goveeModel(name)
        return m in GOVEE_SEGMENTED || m.startsWith("H619")
    }

    /** Trame Govee : 0x33, commande, données, complétée à 19 octets + somme XOR. */
    internal fun govee(cmd: Int, vararg p: Int): ByteArray {
        val f = ByteArray(20)
        f[0] = 0x33
        f[1] = cmd.toByte()
        for (i in p.indices) f[2 + i] = p[i].toByte()
        var x = 0
        for (i in 0 until 19) x = x xor (f[i].toInt() and 0xFF)
        f[19] = x.toByte()
        return f
    }

    internal fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Couleur Hue : sRGB → coordonnées xy (gamut large), sur 16 bits. */
    internal fun hueXy(rgb: Int): IntArray? {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v > 0.04045) ((v + 0.055) / 1.055).pow(2.4) else v / 12.92
        }
        val r = lin(rgb shr 16 and 255)
        val g = lin(rgb shr 8 and 255)
        val b = lin(rgb and 255)
        val x = r * 0.664511 + g * 0.154324 + b * 0.162028
        val y = r * 0.283881 + g * 0.668433 + b * 0.047685
        val z = r * 0.000088 + g * 0.072310 + b * 0.986039
        val s = x + y + z
        if (s <= 1e-6) return null
        return intArrayOf(((x / s) * 65535).toInt().coerceIn(0, 65535), ((y / s) * 65535).toInt().coerceIn(0, 65535))
    }
}

/**
 * Une connexion Bluetooth vers un spot. Seule la dernière couleur demandée est envoyée :
 * si le lien est lent, on saute des images au lieu d'accumuler du retard.
 */
@SuppressLint("MissingPermission")
internal class BleLink(private val app: Context, val mac: String, val lamp: Lamp) {
    private class Step(val ch: BluetoothGattCharacteristic, val data: ByteArray, val pauseMs: Long = 0)

    private val h = Ble.handler
    private var gatt: BluetoothGatt? = null
    private var main: BluetoothGattCharacteristic? = null
    private var bright: BluetoothGattCharacteristic? = null
    private var proto = Ble.P_NONE
    @Volatile var ready = false
        private set
    @Volatile var dead = false
        private set
    private var busy = false
    private var busySince = 0L
    private var holdUntil = 0L
    private val steps = ArrayDeque<Step>()
    private var pendingRgb = 0
    private var pendingLevel = 0f
    private var hasPending = false
    private var lastXy: IntArray? = null
    private var refs = 0
    private var retryMs = 1_500L
    private var connecting = false

    private val pumpR = Runnable { pump() }
    private val retryR = Runnable { if (refs > 0 && gatt == null && !dead) connect() }
    private val idleR = Runnable { if (refs == 0) shutdown() }

    fun acquire() = h.post {
        refs++
        h.removeCallbacks(idleR)
        if (gatt == null && !connecting && !dead) connect()
    }

    /** Libéré : on garde le lien quelques secondes (épisode suivant, test) avant de couper. */
    fun release() = h.post {
        refs = (refs - 1).coerceAtLeast(0)
        if (refs == 0) {
            h.removeCallbacks(idleR)
            h.postDelayed(idleR, 8_000)
        }
    }

    /** Remplacé par une nouvelle configuration (autre protocole choisi) : on coupe tout de suite. */
    fun retire() {
        dead = true
        h.post { shutdown() }
    }

    fun set(rgb: Int, level: Float) = h.post {
        pendingRgb = rgb
        pendingLevel = level
        hasPending = true
        pump()
    }

    private fun connect() {
        if (!Ble.canConnect(app)) return
        val ad = Ble.adapter(app)
        if (ad == null || !ad.isEnabled) {
            scheduleRetry()
            return
        }
        val dev = try {
            ad.getRemoteDevice(mac)
        } catch (_: Exception) {
            dead = true
            return
        }
        connecting = true
        gatt = try {
            dev.connectGatt(app, false, cb, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, h)
        } catch (_: Exception) {
            null
        }
        if (gatt == null) {
            connecting = false
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (dead) return
        h.removeCallbacks(retryR)
        h.postDelayed(retryR, retryMs)
        retryMs = (retryMs * 2).coerceAtMost(15_000)
    }

    private fun drop(g: BluetoothGatt) {
        ready = false
        connecting = false
        busy = false
        steps.clear()
        try {
            g.close()
        } catch (_: Exception) {
        }
        if (gatt === g) gatt = null
        if (refs > 0) scheduleRetry()
    }

    private fun shutdown() {
        h.removeCallbacks(retryR)
        h.removeCallbacks(pumpR)
        ready = false
        connecting = false
        steps.clear()
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (_: Exception) {
            }
        }
        gatt = null
        dead = true
        Ble.forget(this)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) {
                try {
                    g.close()
                } catch (_: Exception) {
                }
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                retryMs = 1_500
                // Petit délai : certains contrôleurs ratent la découverte si on la lance trop tôt
                h.postDelayed({
                    if (g === gatt) try {
                        if (!g.discoverServices()) drop(g)
                    } catch (_: Exception) {
                        drop(g)
                    }
                }, 300)
            } else drop(g)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS || !resolve(g)) {
                drop(g)
                return
            }
            connecting = false
            ready = true
            try {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
            } catch (_: Exception) {
            }
            pump()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt) return
            busy = false
            pump()
        }
    }

    private fun writable(c: BluetoothGattCharacteristic?): BluetoothGattCharacteristic? =
        c?.takeIf {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        }

    private fun find(g: BluetoothGatt, vararg ids: UUID): BluetoothGattCharacteristic? {
        val all = try {
            g.services.flatMap { it.characteristics }
        } catch (_: Exception) {
            emptyList()
        }
        for (id in ids) writable(all.firstOrNull { it.uuid == id })?.let { return it }
        return null
    }

    private fun led(g: BluetoothGatt, vararg s: String) = find(g, *s.map { Ble.u16(it) }.toTypedArray())

    /** Reconnaît le protocole d'après les caractéristiques présentes et le nom, puis prépare l'allumage. */
    private fun resolve(g: BluetoothGatt): Boolean {
        val name = lamp.id.lowercase()
        val forced = Ble.forcedProto(lamp)
        var p = Ble.P_NONE
        var c: BluetoothGattCharacteristic? = null
        when (lamp.brand) {
            Lamps.BT_GOVEE -> {
                c = find(g, Ble.GOVEE_CTRL)
                p = if (forced != Ble.P_NONE) forced else if (Ble.goveeSegmented(lamp.id)) Ble.P_GOVEE_SEG else Ble.P_GOVEE
            }
            Lamps.BT_HUE -> {
                c = find(g, Ble.HUE_CTRL)
                p = Ble.P_HUE
                if (c == null) {
                    c = find(g, Ble.HUE_XY)
                    bright = find(g, Ble.HUE_BRIGHT)
                    p = Ble.P_HUE_SPLIT
                }
            }
            else -> {
                if (forced != Ble.P_NONE) {
                    p = forced
                    c = when (forced) {
                        Ble.P_TRIONES -> led(g, "ffd9", "ffe9", "ffd5", "ffe5", "ffe1", "fff3")
                        Ble.P_BJ -> led(g, "ee01", "ffe1", "fff3")
                        Ble.P_LEDBLE, Ble.P_LEDRBG -> led(g, "ffe1", "fff3", "ffd9", "ffe9")
                        else -> led(g, "fff3", "ffe1", "ffd9", "ffe9")
                    }
                } else {
                    find(g, Ble.GOVEE_CTRL)?.let {
                        c = it
                        p = Ble.P_GOVEE
                    }
                    if (c == null) find(g, Ble.HUE_CTRL)?.let {
                        c = it
                        p = Ble.P_HUE
                    }
                    if (c == null) led(g, "ee01")?.let {
                        c = it
                        p = Ble.P_BJ
                    }
                    if (c == null) led(g, "fff3")?.let {
                        c = it
                        p = if (name.startsWith("elk-bledob") || name.startsWith("elk-bledom0e")) Ble.P_ELK7 else Ble.P_ELK
                    }
                    if (c == null) led(g, "ffd9", "ffe9", "ffd5", "ffe5")?.let {
                        c = it
                        p = Ble.P_TRIONES
                    }
                    if (c == null) led(g, "ffe1")?.let {
                        c = it
                        p = if (name.startsWith("bj_led") || name.startsWith("mohuan")) Ble.P_BJ
                        else if (name.startsWith("led-") || name.startsWith("jackyled")) Ble.P_LEDRBG
                        else Ble.P_LEDBLE
                    }
                }
            }
        }
        val ch = c ?: return false
        main = ch
        proto = p
        steps.clear()
        when (p) {
            Ble.P_ELK -> {
                if (name.startsWith("melk") || name.startsWith("modelx")) {
                    steps.add(Step(ch, Ble.bytes(0x7e, 0x07, 0x83), 600))
                    steps.add(Step(ch, Ble.bytes(0x7e, 0x04, 0x04), 600))
                }
                steps.add(Step(ch, Ble.bytes(0x7e, 0x00, 0x04, 0xf0, 0x00, 0x01, 0xff, 0x00, 0xef)))
            }
            Ble.P_ELK7 -> steps.add(Step(ch, Ble.bytes(0x7e, 0x07, 0x04, 0xff, 0x00, 0x01, 0x02, 0x01, 0xef)))
            Ble.P_TRIONES -> steps.add(Step(ch, Ble.bytes(0xcc, 0x23, 0x33)))
            Ble.P_BJ -> steps.add(Step(ch, Ble.bytes(0x69, 0x96, 0x02, 0x01, 0x01)))
            Ble.P_LEDBLE -> steps.add(Step(ch, Ble.bytes(0x7e, 0x00, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0xef)))
            Ble.P_LEDRBG -> steps.add(Step(ch, Ble.bytes(0x7e, 0x04, 0x04, 0x01, 0xff, 0xff, 0xff, 0x00, 0xef)))
            Ble.P_GOVEE, Ble.P_GOVEE_SEG, Ble.P_GOVEE_D -> {
                steps.add(Step(ch, Ble.govee(0x01, 0x01)))
                steps.add(Step(ch, Ble.govee(0x04, 0xff)))
            }
            Ble.P_HUE_SPLIT -> find(g, Ble.HUE_POWER)?.let { steps.add(Step(it, Ble.bytes(0x01))) }
        }
        return true
    }

    /** Trames à envoyer pour une couleur (déjà multipliée par l'intensité, sauf Hue qui a sa luminosité). */
    private fun frames(rgb: Int, level: Float): List<Step> {
        val ch = main ?: return emptyList()
        val l = level.coerceIn(0f, 1f)
        val r = ((rgb shr 16 and 255) * l).toInt()
        val g = ((rgb shr 8 and 255) * l).toInt()
        val b = ((rgb and 255) * l).toInt()
        return when (proto) {
            Ble.P_ELK, Ble.P_LEDBLE -> listOf(Step(ch, Ble.bytes(0x7e, 0x00, 0x05, 0x03, r, g, b, 0x00, 0xef)))
            Ble.P_ELK7 -> listOf(Step(ch, Ble.bytes(0x7e, 0x07, 0x05, 0x03, r, g, b, 0x0a, 0xef)))
            Ble.P_LEDRBG -> listOf(Step(ch, Ble.bytes(0x7e, 0x07, 0x05, 0x03, r, b, g, 0x00, 0xef)))
            Ble.P_TRIONES -> listOf(Step(ch, Ble.bytes(0x56, r, g, b, 0x00, 0xf0, 0xaa)))
            Ble.P_BJ -> listOf(Step(ch, Ble.bytes(0x69, 0x96, 0x05, 0x02, r, g, b)))
            Ble.P_GOVEE -> listOf(Step(ch, Ble.govee(0x05, 0x02, r, g, b)))
            Ble.P_GOVEE_D -> listOf(Step(ch, Ble.govee(0x05, 0x0d, r, g, b)))
            Ble.P_GOVEE_SEG -> listOf(Step(ch, Ble.govee(0x05, 0x15, 0x01, r, g, b, 0, 0, 0, 0, 0, 0xff, 0x7f)))
            Ble.P_HUE, Ble.P_HUE_SPLIT -> {
                // Hue ne s'éteint pas (le rallumage serait lent) : luminosité minimale à la place
                val bri = (1 + l * 253).toInt().coerceIn(1, 254)
                val xy = Ble.hueXy(rgb)?.also { lastXy = it } ?: lastXy
                if (proto == Ble.P_HUE) {
                    val out = ArrayList<Int>(14)
                    out.addAll(listOf(0x01, 0x01, 0x01, 0x02, 0x01, bri))
                    if (xy != null) out.addAll(listOf(0x04, 0x04, xy[0] and 255, xy[0] shr 8, xy[1] and 255, xy[1] shr 8))
                    listOf(Step(ch, ByteArray(out.size) { out[it].toByte() }))
                } else {
                    val list = ArrayList<Step>(2)
                    if (xy != null) list.add(Step(ch, Ble.bytes(xy[0] and 255, xy[0] shr 8, xy[1] and 255, xy[1] shr 8)))
                    bright?.let { list.add(Step(it, Ble.bytes(bri))) }
                    list
                }
            }
            else -> emptyList()
        }
    }

    private fun pump() {
        val g = gatt ?: return
        if (!ready) return
        val now = SystemClock.uptimeMillis()
        // Rappel d'écriture perdu : on ne reste pas bloqué
        if (busy && now - busySince < 600) return
        busy = false
        if (now < holdUntil) {
            h.removeCallbacks(pumpR)
            h.postAtTime(pumpR, holdUntil)
            return
        }
        if (steps.isEmpty() && hasPending) {
            hasPending = false
            steps.addAll(frames(pendingRgb, pendingLevel))
        }
        val s = steps.removeFirstOrNull() ?: return
        if (write(g, s)) {
            busy = true
            busySince = now
            if (s.pauseMs > 0) holdUntil = now + s.pauseMs
        } else {
            // Pile Bluetooth occupée : on réessaie juste après
            steps.addFirst(s)
            h.removeCallbacks(pumpR)
            h.postDelayed(pumpR, 30)
        }
    }

    private fun write(g: BluetoothGatt, s: Step): Boolean = try {
        val type = if (s.ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(s.ch, s.data, type) == BluetoothStatusCodes.SUCCESS
        } else {
            s.ch.writeType = type
            @Suppress("DEPRECATION")
            s.ch.value = s.data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(s.ch)
        }
    } catch (_: Exception) {
        false
    }
}

/** Pilote d'un spot Bluetooth pour MultiOutput. */
internal class BleDriver(ctx: Context, lamp: Lamp) : Driver(lamp) {
    private val app = ctx.applicationContext
    private var link = Ble.link(app, lamp).also { it.acquire() }
    private var relinkAt = 0L

    override fun ready(): Boolean = link.ready

    override fun set(rgb: Int, level: Float) {
        // Lien coupé entre-temps (inactivité, protocole changé) : on en reprend un neuf
        if (link.dead) {
            val now = SystemClock.uptimeMillis()
            if (now < relinkAt) return
            relinkAt = now + 3_000
            link = Ble.link(app, lamp).also { it.acquire() }
        }
        link.set(rgb, level)
    }

    override fun close() {
        link.release()
    }
}
