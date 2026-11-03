package com.meu.adblock

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class AdVpnService : VpnService() {

    companion object {
        @Volatile var running = false
        val queries = java.util.concurrent.atomic.AtomicInteger()
        val blockedCount = java.util.concurrent.atomic.AtomicInteger()
        val recent = java.util.concurrent.ConcurrentLinkedDeque<String>()
        const val ACTION_STOP = "com.meu.adblock.STOP"
        private const val VPN_DNS = "10.111.0.1"
        private const val UPSTREAM_DNS = "1.1.1.1"
    }

    private var tun: ParcelFileDescriptor? = null
    private var thread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn(); stopSelf(); return START_NOT_STICKY
        }
        if (!running) startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        Blocklist.load(this)
        Thread { Blocklist.update(this) }.start()
        queries.set(0); blockedCount.set(0); recent.clear()
        val apps = getSharedPreferences("p", MODE_PRIVATE).getStringSet("apps", emptySet())!!
        if (apps.isEmpty()) { stopSelf(); return }

        val b = Builder()
            .setSession("AdBlocker")
            .addAddress("10.111.0.2", 32)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32)   // só o tráfego de DNS passa pelo app
            // Também captura apps que usam DNS público fixo (8.8.8.8 etc.)
            .addRoute("8.8.8.8", 32).addRoute("8.8.4.4", 32)
            .addRoute("1.1.1.1", 32).addRoute("1.0.0.1", 32)
            .addRoute("9.9.9.9", 32).addRoute("149.112.112.112", 32)
            .addRoute("94.140.14.14", 32).addRoute("94.140.15.15", 32)
            .setBlocking(true)
        // Só os apps escolhidos usam este filtro; o resto (ex.: YouTube) fica intacto.
        for (pkg in apps) try { b.addAllowedApplication(pkg) } catch (_: Exception) {}

        tun = b.establish() ?: run { stopSelf(); return }
        running = true
        thread = Thread { loop(tun!!) }.also { it.start() }
    }

    private fun stopVpn() {
        running = false
        thread?.interrupt()
        try { tun?.close() } catch (_: Exception) {}
        tun = null
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }
    override fun onRevoke() { stopVpn(); super.onRevoke() }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        val pool = Executors.newFixedThreadPool(8)
        while (running) {
            val n = try { input.read(buf) } catch (_: Exception) { break }
            if (n < 28) continue
            if ((buf[0].toInt() shr 4) != 4) continue          // só IPv4
            val ihl = (buf[0].toInt() and 0x0F) * 4
            if (buf[9].toInt() != 17) continue                 // só UDP
            if (u16(buf, ihl + 2) != 53) continue              // só DNS
            val pkt = buf.copyOf(n)
            val dns = pkt.copyOfRange(ihl + 8, n)
            pool.execute {
                try {
                    val q = parseQuestion(dns) ?: return@execute
                    val isBlocked = Blocklist.isBlocked(q.name)
                    queries.incrementAndGet()
                    if (isBlocked) blockedCount.incrementAndGet()
                    recent.addFirst((if (isBlocked) "✗ " else "✓ ") + q.name)
                    while (recent.size > 15) recent.pollLast()
                    val resp = if (isBlocked) blocked(dns, q) else forward(dns)
                    if (resp != null) {
                        val out = wrap(pkt, ihl, resp)
                        synchronized(output) { output.write(out) }
                    }
                } catch (_: Exception) {}
            }
        }
        pool.shutdownNow()
    }

    private class Question(val name: String, val type: Int, val end: Int)

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun parseQuestion(d: ByteArray): Question? {
        if (d.size < 17) return null
        var p = 12
        val sb = StringBuilder()
        while (p < d.size) {
            val len = d[p].toInt() and 0xFF
            if (len == 0) { p++; break }
            if (len and 0xC0 != 0 || p + 1 + len > d.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(d, p + 1, len, Charsets.ISO_8859_1))
            p += len + 1
        }
        if (p + 4 > d.size) return null
        return Question(sb.toString(), u16(d, p), p + 4)
    }

    // Responde 0.0.0.0 (ou ::) para domínios de anúncio.
    private fun blocked(d: ByteArray, q: Question): ByteArray {
        val ans = when (q.type) { 1 -> 4; 28 -> 16; else -> 0 }
        val r = ByteArray(q.end + if (ans > 0) 12 + ans else 0)
        System.arraycopy(d, 0, r, 0, q.end)
        r[2] = (0x80 or (d[2].toInt() and 0x01)).toByte()
        r[3] = 0x80.toByte()
        r[6] = 0; r[7] = if (ans > 0) 1 else 0
        r[8] = 0; r[9] = 0; r[10] = 0; r[11] = 0
        if (ans > 0) {
            var p = q.end
            r[p++] = 0xC0.toByte(); r[p++] = 0x0C
            r[p++] = (q.type shr 8).toByte(); r[p++] = q.type.toByte()
            r[p++] = 0; r[p++] = 1
            r[p++] = 0; r[p++] = 0; r[p++] = 0; r[p++] = 60
            r[p++] = 0; r[p] = ans.toByte()
        }
        return r
    }

    private fun forward(d: ByteArray): ByteArray? = try {
        DatagramSocket().use { s ->
            protect(s)   // evita que a consulta volte para a própria VPN
            s.soTimeout = 3000
            s.send(DatagramPacket(d, d.size, InetAddress.getByName(UPSTREAM_DNS), 53))
            val b = ByteArray(4096)
            val p = DatagramPacket(b, b.size)
            s.receive(p)
            b.copyOf(p.length)
        }
    } catch (_: Exception) { null }

    private fun wrap(req: ByteArray, ihl: Int, dns: ByteArray): ByteArray {
        val total = 20 + 8 + dns.size
        val o = ByteArray(total)
        o[0] = 0x45
        o[2] = (total shr 8).toByte(); o[3] = total.toByte()
        o[6] = 0x40
        o[8] = 64; o[9] = 17
        System.arraycopy(req, 16, o, 12, 4)   // origem = destino original
        System.arraycopy(req, 12, o, 16, 4)   // destino = origem original
        var sum = 0
        for (i in 0 until 20 step 2) sum += u16(o, i)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val ck = sum.inv() and 0xFFFF
        o[10] = (ck shr 8).toByte(); o[11] = ck.toByte()
        o[20] = req[ihl + 2]; o[21] = req[ihl + 3]   // porta origem = 53
        o[22] = req[ihl];     o[23] = req[ihl + 1]   // porta destino = porta do app
        val ul = 8 + dns.size
        o[24] = (ul shr 8).toByte(); o[25] = ul.toByte()
        System.arraycopy(dns, 0, o, 28, dns.size)
        return o
    }
}
