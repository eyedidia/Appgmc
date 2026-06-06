package com.gmc.digitalkey.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.gmc.digitalkey.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class LocalVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.gmc.digitalkey.VPN_START"
        const val ACTION_STOP  = "com.gmc.digitalkey.VPN_STOP"
        private const val NOTIF_CHANNEL = "vpn_capture"
        private const val NOTIF_ID = 9001

        @Volatile var isRunning = false
        val captureLog = NetworkCaptureServer()
    }

    private var vpnFd: ParcelFileDescriptor? = null
    private val pool = Executors.newCachedThreadPool()
    // key = "$srcIp:$srcPort"
    private val sessions = ConcurrentHashMap<String, TcpSession>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        captureLog.clearLog()
        createNotifChannel()

        vpnFd = Builder()
            .setSession("YMGMC Inspector")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("8.8.8.8")
            .addRoute("0.0.0.0", 0)
            .setMtu(1500)
            .establish() ?: return

        isRunning = true
        startForeground(NOTIF_ID, buildNotif())
        pool.submit { runLoop() }
    }

    private fun stopVpn() {
        isRunning = false
        vpnFd?.close(); vpnFd = null
        sessions.values.forEach { it.close() }
        sessions.clear()
        stopForeground(true)
        stopSelf()
    }

    // ─── Packet loop ──────────────────────────────────────────────────────────

    private fun runLoop() {
        val fd = vpnFd ?: return
        val input  = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf    = ByteArray(32767)
        while (isRunning) {
            val n = runCatching { input.read(buf) }.getOrDefault(-1)
            if (n <= 0) continue
            val pkt = buf.copyOf(n)
            pool.submit { handlePacket(pkt, n, output) }
        }
    }

    private fun handlePacket(raw: ByteArray, len: Int, out: FileOutputStream) {
        if (len < 20) return
        if ((raw[0].toInt() and 0xF0) != 0x40) return   // IPv4 only
        val ihl  = (raw[0].toInt() and 0x0F) * 4
        when (raw[9].toInt() and 0xFF) {
            6  -> handleTcp(raw, len, ihl, out)
            17 -> handleUdp(raw, len, ihl, out)
        }
    }

    // ─── TCP ──────────────────────────────────────────────────────────────────

    private fun handleTcp(raw: ByteArray, len: Int, ihl: Int, out: FileOutputStream) {
        if (len < ihl + 20) return
        val srcIp      = raw.u32(12)
        val dstIp      = raw.u32(16)
        val srcPort    = raw.u16(ihl)
        val dstPort    = raw.u16(ihl + 2)
        val theirSeq   = raw.u32(ihl + 4)
        val dataOff    = ((raw[ihl + 12].toInt() and 0xFF) shr 4) * 4
        val flags      = raw[ihl + 13].toInt() and 0xFF
        val payloadOff = ihl + dataOff
        val payload    = if (payloadOff < len) raw.copyOfRange(payloadOff, len) else ByteArray(0)
        val key        = "$srcIp:$srcPort"

        val syn = (flags and 0x02) != 0
        val ack = (flags and 0x10) != 0
        val fin = (flags and 0x01) != 0
        val rst = (flags and 0x04) != 0

        when {
            rst || fin -> { sessions.remove(key)?.close() }
            syn && !ack -> handleSyn(key, srcIp, dstIp, srcPort, dstPort, theirSeq, out)
            payload.isNotEmpty() -> handleData(key, theirSeq, payload, dstIp, dstPort, out)
        }
    }

    private fun handleSyn(
        key: String, srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int,
        theirSeq: Int, out: FileOutputStream
    ) {
        val sock = runCatching {
            Socket().also {
                protect(it)
                it.connect(InetSocketAddress(ipStr(dstIp), dstPort), 10_000)
                it.soTimeout = 30_000
            }
        }.getOrNull()

        if (sock == null) {
            writeTcp(dstIp, srcIp, dstPort, srcPort, 0, theirSeq + 1, 0x14 /* RST+ACK */, ByteArray(0), out)
            return
        }

        val ourSeq  = AtomicInteger(System.nanoTime().toInt() ushr 1)
        val theirAck = AtomicInteger(theirSeq + 1)
        val session  = TcpSession(srcIp, dstIp, srcPort, dstPort, ourSeq, theirAck, sock)
        sessions[key] = session

        writeTcp(dstIp, srcIp, dstPort, srcPort, ourSeq.get(), theirAck.get(), 0x12 /* SYN+ACK */, ByteArray(0), out)
        ourSeq.incrementAndGet()

        pool.submit { relayServerToClient(key, session, out) }
    }

    private fun handleData(
        key: String, theirSeq: Int, payload: ByteArray,
        dstIp: Int, dstPort: Int, out: FileOutputStream
    ) {
        val session = sessions[key] ?: return
        session.theirAck.set(theirSeq + payload.size)

        if (!session.logged) {
            session.logged = true
            if (dstPort == 443) {
                val sni  = NetworkCaptureServer.extractSni(payload, payload.size) ?: ""
                val host = if (sni.isNotEmpty()) sni else ipStr(dstIp)
                captureLog.addEntry("HTTPS", host, "", sni)
            } else {
                val host   = httpHost(payload) ?: ipStr(dstIp)
                val method = httpMethod(payload) ?: "HTTP"
                val path   = httpPath(payload) ?: ""
                captureLog.addEntry(method, host, path)
            }
        }

        runCatching { session.socket.getOutputStream().let { it.write(payload); it.flush() } }
        writeTcp(session.dstIp, session.srcIp, session.dstPort, session.srcPort,
                 session.ourSeq.get(), session.theirAck.get(), 0x10 /* ACK */, ByteArray(0), out)
    }

    private fun relayServerToClient(key: String, session: TcpSession, out: FileOutputStream) {
        val buf = ByteArray(8192)
        try {
            val inp = session.socket.getInputStream()
            var n   = inp.read(buf)
            while (n > 0 && isRunning && sessions.containsKey(key)) {
                val data = buf.copyOf(n)
                writeTcp(session.dstIp, session.srcIp, session.dstPort, session.srcPort,
                         session.ourSeq.get(), session.theirAck.get(), 0x18 /* PSH+ACK */, data, out)
                session.ourSeq.addAndGet(n)
                n = inp.read(buf)
            }
        } catch (_: Exception) {}
        writeTcp(session.dstIp, session.srcIp, session.dstPort, session.srcPort,
                 session.ourSeq.get(), session.theirAck.get(), 0x11 /* FIN+ACK */, ByteArray(0), out)
        sessions.remove(key)?.close()
    }

    // ─── UDP — forward DNS only ───────────────────────────────────────────────

    private fun handleUdp(raw: ByteArray, len: Int, ihl: Int, out: FileOutputStream) {
        if (len < ihl + 8) return
        val srcIp   = raw.u32(12)
        val dstIp   = raw.u32(16)
        val srcPort = raw.u16(ihl)
        val dstPort = raw.u16(ihl + 2)
        if (dstPort != 53) return

        val udpLen  = raw.u16(ihl + 4) - 8
        if (udpLen <= 0) return
        val payload = raw.copyOfRange(ihl + 8, ihl + 8 + udpLen)

        pool.submit {
            runCatching {
                val ds = DatagramSocket().also { protect(it) }
                ds.soTimeout = 5_000
                ds.send(DatagramPacket(payload, payload.size, InetSocketAddress("8.8.8.8", 53)))
                val resp = ByteArray(512)
                val dp   = DatagramPacket(resp, resp.size)
                ds.receive(dp); ds.close()
                writeUdp(dstIp, srcIp, dstPort, srcPort, resp.copyOf(dp.length), out)
            }
        }
    }

    // ─── Packet writers ───────────────────────────────────────────────────────

    private fun writeTcp(
        srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int,
        seq: Int, ack: Int, flags: Int, data: ByteArray, out: FileOutputStream
    ) {
        val total = 40 + data.size
        val p = ByteArray(total)
        p[0] = 0x45.toByte(); p.p16(2, total); p[8] = 64; p[9] = 6
        p.p32(12, srcIp); p.p32(16, dstIp)
        p.p16(10, ipChecksum(p, 0, 20))
        p.p16(20, srcPort); p.p16(22, dstPort)
        p.p32(24, seq);     p.p32(28, ack)
        p[32] = 0x50.toByte(); p[33] = flags.toByte()
        p[34] = 0xFF.toByte(); p[35] = 0xFF.toByte()
        data.copyInto(p, 40)
        p.p16(36, tcpChecksum(p, srcIp, dstIp, 20, 20 + data.size))
        runCatching { synchronized(out) { out.write(p) } }
    }

    private fun writeUdp(
        srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int,
        data: ByteArray, out: FileOutputStream
    ) {
        val udpLen = 8 + data.size
        val total  = 20 + udpLen
        val p = ByteArray(total)
        p[0] = 0x45.toByte(); p.p16(2, total); p[8] = 64; p[9] = 17
        p.p32(12, srcIp); p.p32(16, dstIp)
        p.p16(10, ipChecksum(p, 0, 20))
        p.p16(20, srcPort); p.p16(22, dstPort); p.p16(24, udpLen)
        data.copyInto(p, 28)
        runCatching { synchronized(out) { out.write(p) } }
    }

    // ─── Checksums ────────────────────────────────────────────────────────────

    private fun ipChecksum(buf: ByteArray, off: Int, len: Int): Int {
        var s = 0L; var i = off
        while (i < off + len - 1) { s += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i+1].toInt() and 0xFF); i += 2 }
        if (len and 1 != 0) s += (buf[off + len - 1].toInt() and 0xFF) shl 8
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        return (s.inv() and 0xFFFF).toInt()
    }

    private fun tcpChecksum(pkt: ByteArray, srcIp: Int, dstIp: Int, tcpOff: Int, tcpLen: Int): Int {
        val ph = ByteArray(12 + tcpLen)
        ph.p32(0, srcIp); ph.p32(4, dstIp); ph[9] = 6; ph.p16(10, tcpLen)
        pkt.copyInto(ph, 12, tcpOff, tcpOff + tcpLen)
        ph[28] = 0; ph[29] = 0   // zero checksum field (TCP hdr offset 16 = pseudo byte 28)
        return ipChecksum(ph, 0, ph.size)
    }

    // ─── HTTP / TLS helpers ───────────────────────────────────────────────────

    private fun httpHost(data: ByteArray): String? {
        val text = runCatching { String(data, Charsets.ISO_8859_1) }.getOrNull() ?: return null
        return text.lines().firstOrNull { it.startsWith("Host:", true) }
            ?.substringAfter(":")?.trim()?.substringBefore(":")
    }

    private fun httpMethod(data: ByteArray): String? {
        val text = runCatching { String(data, Charsets.ISO_8859_1) }.getOrNull() ?: return null
        val m = text.substringBefore(" ")
        return if (m.length in 3..7 && m.all { it.isLetter() }) m else null
    }

    private fun httpPath(data: ByteArray): String? {
        val text = runCatching { String(data, Charsets.ISO_8859_1) }.getOrNull() ?: return null
        return text.lines().firstOrNull()?.split(" ")?.getOrNull(1)
    }

    // ─── Byte helpers ─────────────────────────────────────────────────────────

    private fun ByteArray.u32(off: Int) =
        ((this[off].toInt() and 0xFF) shl 24) or ((this[off+1].toInt() and 0xFF) shl 16) or
        ((this[off+2].toInt() and 0xFF) shl 8) or (this[off+3].toInt() and 0xFF)

    private fun ByteArray.u16(off: Int) =
        ((this[off].toInt() and 0xFF) shl 8) or (this[off+1].toInt() and 0xFF)

    private fun ByteArray.p32(off: Int, v: Int) {
        this[off]=(v shr 24).toByte(); this[off+1]=(v shr 16).toByte()
        this[off+2]=(v shr 8).toByte(); this[off+3]=v.toByte()
    }

    private fun ByteArray.p16(off: Int, v: Int) {
        this[off]=(v shr 8).toByte(); this[off+1]=(v and 0xFF).toByte()
    }

    private fun ipStr(ip: Int) = "%d.%d.%d.%d".format(
        (ip shr 24) and 0xFF, (ip shr 16) and 0xFF, (ip shr 8) and 0xFF, ip and 0xFF)

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CHANNEL, "Traffic Inspector", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif() = NotificationCompat.Builder(this, NOTIF_CHANNEL)
        .setSmallIcon(R.drawable.ic_ble)
        .setContentTitle("YMGMC Traffic Inspector")
        .setContentText("Capturing traffic…")
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    override fun onDestroy() { stopVpn(); super.onDestroy() }
}

data class TcpSession(
    val srcIp: Int, val dstIp: Int,
    val srcPort: Int, val dstPort: Int,
    val ourSeq: AtomicInteger,
    val theirAck: AtomicInteger,
    val socket: Socket,
    @Volatile var logged: Boolean = false
) {
    fun close() = runCatching { socket.close() }
}
