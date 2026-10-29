package com.ilian.xtreamplayer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Sortie des couleurs calculées par LightSync : 6 spots, couleur (RGB) et intensité (0..1) chacun. */
interface LightOutput {
    fun send(colors: IntArray, levels: FloatArray)
    fun close() {}
}

/** Sans spot configuré : les couleurs sont seulement écrites dans le journal (Logcat, filtre « LightSync »). */
class LogOutput : LightOutput {
    private var last = 0L
    override fun send(colors: IntArray, levels: FloatArray) {
        val now = System.currentTimeMillis()
        if (now - last < 1_000) return
        last = now
        Log.d("LightSync", colors.indices.joinToString("  ") { i -> "%06X@%.2f".format(colors[i] and 0xFFFFFF, levels[i]) })
    }
}

/**
 * Un spot connecté du réseau local.
 * [role] : 0 = automatique (selon l'ordre : 1-2 basses, 3-4 médiums, 5-6 aigus), 1 = basses, 2 = médiums, 3 = aigus.
 */
data class Lamp(val brand: String, val ip: String, val id: String, val role: Int = 0, val extra: Int = 0) {
    val label: String get() = "$brand · ${ip.substringAfterLast('.').let { "…$it" }}"

    fun json(): JSONObject = JSONObject().put("brand", brand).put("ip", ip).put("id", id).put("role", role).put("extra", extra)

    companion object {
        fun from(o: JSONObject) = Lamp(o.optString("brand"), o.optString("ip"), o.optString("id"), o.optInt("role", 0), o.optInt("extra", 0))
    }
}

/**
 * Objets connectés en Wi-Fi local, sans cloud (trop lent pour suivre la musique) :
 * WiZ, Govee (contrôle LAN activé dans l'appli Govee), LIFX, Yeelight (contrôle LAN activé), WLED, Magic Home.
 */
object Lamps {
    const val WIZ = "WiZ"
    const val GOVEE = "Govee"
    const val LIFX = "LIFX"
    const val YEELIGHT = "Yeelight"
    const val WLED = "WLED"
    const val MAGIC = "Magic Home"
    val BRANDS = listOf(WIZ, GOVEE, LIFX, YEELIGHT, WLED, MAGIC)

    fun saved(): List<Lamp> = try {
        val a = JSONArray(Prefs.lightsDevices)
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Lamp.from(it) } }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(list: List<Lamp>) {
        Prefs.lightsDevices = JSONArray(list.map { it.json() }).toString()
    }

    /** Sortie à utiliser pendant la lecture. */
    fun output(ctx: Context): LightOutput {
        val l = saved()
        return if (l.isEmpty()) LogOutput() else MultiOutput(ctx.applicationContext, l)
    }

    // ---------- Découverte sur le réseau ----------

    private fun broadcasts(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out.add(it) }
            }
        } catch (_: Exception) {
        }
        out.add(InetAddress.getByName("255.255.255.255"))
        return out.distinct()
    }

    /** Cherche les spots compatibles sur le Wi-Fi (bloquant, environ 3,5 s). */
    fun scan(ctx: Context, timeoutMs: Long = 3_500): List<Lamp> {
        val found = ConcurrentHashMap<String, Lamp>()
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = try {
            wifi?.createMulticastLock("xtream-lights")?.apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {
            null
        }
        val wled = Thread { scanWled(ctx, timeoutMs, found) }.apply { isDaemon = true; start() }
        val govee = Thread { scanGovee(timeoutMs, found) }.apply { isDaemon = true; start() }
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 250
                val wizProbe = """{"method":"getPilot","params":{}}""".toByteArray()
                val lifxProbe = Lifx.packet(2, ByteArray(0), null, tagged = true)
                val magicProbe = "HF-A11ASSISTHREAD".toByteArray()
                val yeeProbe = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1982\r\nMAN: \"ssdp:discover\"\r\nST: wifi_bulb\r\n".toByteArray()
                val end = System.currentTimeMillis() + timeoutMs
                var nextProbe = 0L
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() >= nextProbe) {
                        nextProbe = System.currentTimeMillis() + 1_200
                        for (b in broadcasts()) {
                            trySend(s, wizProbe, b, 38899)
                            trySend(s, lifxProbe, b, 56700)
                            trySend(s, magicProbe, b, 48899)
                        }
                        trySend(s, yeeProbe, InetAddress.getByName("239.255.255.250"), 1982)
                    }
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val ip = p.address.hostAddress ?: continue
                    parseReply(ip, p.port, p.data.copyOf(p.length))?.let { found.putIfAbsent(ip, it) }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                wled.join(800)
                govee.join(800)
            } catch (_: Exception) {
            }
            try {
                lock?.release()
            } catch (_: Exception) {
            }
        }
        return found.values.sortedWith(compareBy({ it.brand }, { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }))
    }

    private fun trySend(s: DatagramSocket, data: ByteArray, to: InetAddress, port: Int) {
        try {
            s.send(DatagramPacket(data, data.size, to, port))
        } catch (_: Exception) {
        }
    }

    private fun parseReply(ip: String, port: Int, data: ByteArray): Lamp? = try {
        when (port) {
            38899 -> {
                val o = JSONObject(String(data))
                val mac = o.optJSONObject("result")?.optString("mac").orEmpty()
                if (o.optString("method") == "getPilot") Lamp(WIZ, ip, mac.ifBlank { ip }) else null
            }
            56700 -> {
                val type = ByteBuffer.wrap(data, 32, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                if (type == 3) {
                    val mac = (8 until 14).joinToString("") { "%02x".format(data[it]) }
                    Lamp(LIFX, ip, mac)
                } else null
            }
            48899 -> {
                val t = String(data).trim().split(',')
                if (t.size >= 2 && t[0].count { it == '.' } == 3) Lamp(MAGIC, t[0], t[1]) else null
            }
            else -> {
                val t = String(data)
                if (t.contains("yeelight://")) {
                    val id = Regex("""(?im)^id:\s*(\S+)""").find(t)?.groupValues?.get(1) ?: ip
                    Lamp(YEELIGHT, ip, id)
                } else null
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Govee : réponse au « scan » sur le port 4002 (contrôle LAN à activer dans l'appli Govee). */
    private fun scanGovee(timeoutMs: Long, found: ConcurrentHashMap<String, Lamp>) {
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.bind(InetSocketAddress(4002))
                s.soTimeout = 250
                val probe = """{"msg":{"cmd":"scan","data":{"account_topic":"reserve"}}}""".toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                val end = System.currentTimeMillis() + timeoutMs
                var next = 0L
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() >= next) {
                        next = System.currentTimeMillis() + 1_200
                        trySend(s, probe, group, 4001)
                    }
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    try {
                        val d = JSONObject(String(p.data, 0, p.length)).optJSONObject("msg")?.optJSONObject("data") ?: continue
                        val ip = d.optString("ip").ifBlank { p.address.hostAddress ?: continue }
                        found.putIfAbsent(ip, Lamp(GOVEE, ip, d.optString("device").ifBlank { ip }))
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /** WLED : annoncé sur le réseau (mDNS « _wled._tcp »). */
    private fun scanWled(ctx: Context, timeoutMs: Long, found: ConcurrentHashMap<String, Lamp>) {
        val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val queue = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                queue.offer(serviceInfo)
            }
        }
        try {
            nsd.discoverServices("_wled._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            return
        }
        val end = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < end) {
                val info = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                val done = java.util.concurrent.CountDownLatch(1)
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            done.countDown()
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val ip = (serviceInfo.host as? Inet4Address)?.hostAddress
                            if (ip != null) found.putIfAbsent(ip, Lamp(WLED, ip, serviceInfo.serviceName ?: ip))
                            done.countDown()
                        }
                    })
                } catch (_: Exception) {
                    done.countDown()
                }
                done.await(1_500, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } catch (_: Exception) {
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
    }

    /** Fait clignoter un spot (blanc, trois fois) pour le reconnaître dans la pièce. Bloquant (≈ 2 s). */
    fun blink(ctx: Context, lamp: Lamp) {
        val out = MultiOutput(ctx.applicationContext, listOf(lamp.copy(role = 0)))
        try {
            repeat(3) {
                out.sendOne(0xFFFFFF, 1f)
                Thread.sleep(320)
                out.sendOne(0xFFFFFF, 0.02f)
                Thread.sleep(320)
            }
            out.sendOne(0xFFB070, 0.35f)
        } catch (_: Exception) {
        } finally {
            out.close()
        }
    }
}

// ---------- Pilotes par marque ----------

private abstract class Driver(val lamp: Lamp) {
    /** [rgb] couleur pleine, [level] intensité 0..1. */
    abstract fun set(rgb: Int, level: Float)
    open fun close() {}

    protected fun scaled(rgb: Int, level: Float): Triple<Int, Int, Int> {
        val l = level.coerceIn(0f, 1f)
        return Triple(((rgb shr 16 and 255) * l).toInt(), ((rgb shr 8 and 255) * l).toInt(), ((rgb and 255) * l).toInt())
    }
}

/** Envoi UDP partagé par tous les spots (un seul fil d'envoi). */
private class Udp {
    val socket = DatagramSocket().apply { broadcast = true }
    private val addr = HashMap<String, InetAddress>()
    fun send(ip: String, port: Int, data: ByteArray) {
        try {
            val a = addr.getOrPut(ip) { InetAddress.getByName(ip) }
            socket.send(DatagramPacket(data, data.size, a, port))
        } catch (_: Exception) {
        }
    }
}

/** WiZ : UDP 38899, « setPilot » (rouge, vert, bleu, intensité). */
private class WizDriver(lamp: Lamp, private val udp: Udp) : Driver(lamp) {
    override fun set(rgb: Int, level: Float) {
        // L'ampoule ne descend pas sous 10 % : en dessous, on assombrit la couleur elle-même
        val dim = (level * 100).toInt().coerceIn(10, 100)
        val k = if (level < 0.1f) (level / 0.1f).coerceIn(0f, 1f) else 1f
        val (r, g, b) = scaled(rgb, k)
        val msg = """{"id":1,"method":"setPilot","params":{"r":${r.coerceAtLeast(1)},"g":$g,"b":$b,"dimming":$dim}}"""
        udp.send(lamp.ip, 38899, msg.toByteArray())
    }
}

/** Govee : UDP 4003, « colorwc » (contrôle LAN à activer dans l'appli Govee). */
private class GoveeDriver(lamp: Lamp, private val udp: Udp) : Driver(lamp) {
    init {
        udp.send(lamp.ip, 4003, """{"msg":{"cmd":"turn","data":{"value":1}}}""".toByteArray())
        udp.send(lamp.ip, 4003, """{"msg":{"cmd":"brightness","data":{"value":100}}}""".toByteArray())
    }

    override fun set(rgb: Int, level: Float) {
        val (r, g, b) = scaled(rgb, level)
        udp.send(lamp.ip, 4003, """{"msg":{"cmd":"colorwc","data":{"color":{"r":$r,"g":$g,"b":$b},"colorTemInKelvin":0}}}""".toByteArray())
    }
}

/** LIFX : protocole binaire UDP 56700, transition 0 ms. */
private object Lifx {
    private val source = Random.nextInt()
    private var seq = 0

    fun packet(type: Int, payload: ByteArray, mac: String?, tagged: Boolean): ByteArray {
        val b = ByteBuffer.allocate(36 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort((36 + payload.size).toShort())
        b.putShort((1024 or 0x1000 or (if (tagged) 0x2000 else 0)).toShort())
        b.putInt(source)
        val target = ByteArray(8)
        if (mac != null && mac.length == 12) for (i in 0 until 6) target[i] = mac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        b.put(target)
        b.put(ByteArray(6))
        b.put(0)
        b.put((seq++ and 0xFF).toByte())
        b.put(ByteArray(8))
        b.putShort(type.toShort())
        b.putShort(0)
        b.put(payload)
        return b.array()
    }
}

private class LifxDriver(lamp: Lamp, private val udp: Udp) : Driver(lamp) {
    private val mac = lamp.id.takeIf { it.length == 12 }
    private val hsv = FloatArray(3)

    init {
        // Allumer (SetPower, niveau max)
        val p = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort(0xFFFF.toShort()).putInt(0).array()
        udp.send(lamp.ip, 56700, Lifx.packet(117, p, mac, tagged = mac == null))
    }

    override fun set(rgb: Int, level: Float) {
        android.graphics.Color.colorToHSV(rgb or 0xFF000000.toInt(), hsv)
        val pl = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        pl.put(0)
        pl.putShort((hsv[0] / 360f * 65535).toInt().toShort())
        pl.putShort((hsv[1] * 65535).toInt().toShort())
        pl.putShort((level.coerceIn(0f, 1f) * hsv[2] * 65535).toInt().toShort())
        pl.putShort(3500)
        pl.putInt(0) // transition : 0 ms
        udp.send(lamp.ip, 56700, Lifx.packet(102, pl.array(), mac, tagged = mac == null))
    }
}

/** WLED : temps réel UDP 21324 (DRGB), toutes les LED de la même couleur. */
private class WledDriver(lamp: Lamp, private val udp: Udp) : Driver(lamp) {
    private val count = (if (lamp.extra > 0) lamp.extra else try {
        JSONObject(Http.getText("http://${lamp.ip}/json/info")).optJSONObject("leds")?.optInt("count", 30) ?: 30
    } catch (_: Exception) {
        30
    }).coerceIn(1, 490)
    private val buf = ByteArray(2 + count * 3)

    override fun set(rgb: Int, level: Float) {
        val (r, g, b) = scaled(rgb, level)
        buf[0] = 2
        buf[1] = 2 // retour au mode normal 2 s après la fin
        var i = 2
        repeat(count) {
            buf[i++] = r.toByte()
            buf[i++] = g.toByte()
            buf[i++] = b.toByte()
        }
        udp.send(lamp.ip, 21324, buf)
    }
}

/** Magic Home : TCP 5577, trame couleur avec somme de contrôle. */
private class MagicDriver(lamp: Lamp) : Driver(lamp) {
    private var sock: Socket? = null
    private var out: OutputStream? = null

    init {
        try {
            sock = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(lamp.ip, 5577), 1_500)
            }
            out = sock?.getOutputStream()
            write(byteArrayOf(0x71, 0x23, 0x0F)) // allumer
        } catch (_: Exception) {
        }
    }

    private fun write(b: ByteArray) {
        val o = out ?: return
        var cs = 0
        for (x in b) cs += x.toInt() and 0xFF
        try {
            o.write(b + (cs and 0xFF).toByte())
        } catch (_: Exception) {
            out = null
        }
    }

    override fun set(rgb: Int, level: Float) {
        val (r, g, b) = scaled(rgb, level)
        write(byteArrayOf(0x31, r.toByte(), g.toByte(), b.toByte(), 0, 0xF0.toByte(), 0x0F))
    }

    override fun close() {
        try {
            sock?.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Yeelight : « mode musique » (l'ampoule se connecte à l'appli et accepte un flux de commandes sans limite),
 * effet « sudden » = changement immédiat. Le contrôle LAN doit être activé dans l'appli Yeelight.
 */
private class YeelightDriver(lamp: Lamp) : Driver(lamp) {
    private var music: Socket? = null
    private var out: OutputStream? = null
    private var lastBright = -1
    private var id = 1

    init {
        try {
            val me = PhoneInput.localIp()
            if (me != null) {
                ServerSocket(0).use { server ->
                    server.soTimeout = 3_000
                    Socket().use { ctl ->
                        ctl.connect(InetSocketAddress(lamp.ip, 55443), 1_500)
                        val o = ctl.getOutputStream()
                        o.write("""{"id":1,"method":"set_power","params":["on","sudden",0]}""".toByteArray() + "\r\n".toByteArray())
                        o.write("""{"id":2,"method":"set_music","params":[1,"$me",${server.localPort}]}""".toByteArray() + "\r\n".toByteArray())
                        o.flush()
                        music = server.accept().apply { tcpNoDelay = true }
                        out = music?.getOutputStream()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun cmd(method: String, params: String) {
        val o = out ?: return
        try {
            o.write("""{"id":${id++},"method":"$method","params":$params}""".toByteArray() + "\r\n".toByteArray())
        } catch (_: Exception) {
            out = null
        }
    }

    override fun set(rgb: Int, level: Float) {
        cmd("set_rgb", "[${(rgb and 0xFFFFFF).coerceAtLeast(1)},\"sudden\",30]")
        val bright = (level * 100).toInt().coerceIn(1, 100)
        if (bright != lastBright) {
            lastBright = bright
            cmd("set_bright", "[$bright,\"sudden\",30]")
        }
    }

    override fun close() {
        try {
            music?.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Tous les spots choisis : chaque spot reçoit la couleur de sa place (1 à 6) ou de son rôle.
 * Les envois inutiles (couleur quasi identique) sont évités pour ne pas saturer les ampoules.
 */
class MultiOutput(ctx: Context, lamps: List<Lamp>) : LightOutput {
    private val udp = Udp()
    private val drivers: List<Driver>
    private val slots: IntArray
    private val lastRgb: IntArray
    private val lastLevel: FloatArray
    private val lastAt: LongArray

    init {
        drivers = lamps.mapNotNull { l ->
            try {
                when (l.brand) {
                    Lamps.WIZ -> WizDriver(l, udp)
                    Lamps.GOVEE -> GoveeDriver(l, udp)
                    Lamps.LIFX -> LifxDriver(l, udp)
                    Lamps.WLED -> WledDriver(l, udp)
                    Lamps.MAGIC -> MagicDriver(l)
                    Lamps.YEELIGHT -> YeelightDriver(l)
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
        // Place de chaque spot : rôle choisi, sinon l'ordre (1-2 basses, 3-4 médiums, 5-6 aigus)
        val counters = IntArray(3)
        slots = IntArray(drivers.size) { i ->
            when (drivers[i].lamp.role) {
                1, 2, 3 -> {
                    val g = drivers[i].lamp.role - 1
                    g * 2 + (counters[g]++ % 2)
                }
                else -> i % 6
            }
        }
        lastRgb = IntArray(drivers.size) { -1 }
        lastLevel = FloatArray(drivers.size) { -1f }
        lastAt = LongArray(drivers.size)
    }

    override fun send(colors: IntArray, levels: FloatArray) {
        val now = System.currentTimeMillis()
        for (i in drivers.indices) {
            val s = slots[i]
            val rgb = colors[s] and 0xFFFFFF
            val lv = levels[s]
            // Même couleur et même intensité depuis peu : rien à envoyer
            if (rgb == lastRgb[i] && kotlin.math.abs(lv - lastLevel[i]) < 0.02f && now - lastAt[i] < 1_000) continue
            lastRgb[i] = rgb
            lastLevel[i] = lv
            lastAt[i] = now
            try {
                drivers[i].set(rgb, lv)
            } catch (_: Exception) {
            }
        }
    }

    /** Une seule couleur pour tous les spots (test, clignotement). */
    fun sendOne(rgb: Int, level: Float) {
        for (d in drivers) try {
            d.set(rgb, level)
        } catch (_: Exception) {
        }
    }

    override fun close() {
        for (d in drivers) d.close()
        try {
            udp.socket.close()
        } catch (_: Exception) {
        }
    }
}
