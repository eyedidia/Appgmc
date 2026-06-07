package com.gmc.digitalkey.network

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LocalVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.gmc.digitalkey.VPN_START"
        const val ACTION_STOP  = "com.gmc.digitalkey.VPN_STOP"

        @Volatile var isRunning = false
        val captureLog = NetworkCaptureServer()

        private const val MAX_SESSIONS = 64
    }

    private var vpnFd: ParcelFileDescriptor? = null
    // Cached pool is safe now that pendingKeys prevents SYN-retransmit thread explosion
    private val tcpPool = Executors.newCachedThreadPool()
    private val dnsPool = Executors.newFixedThreadPool(8)
    private val sessions = ConcurrentHashMap<String, TcpSession>()
    // Tracks keys with a connect attempt in-flight (before session is registered)
    private val pendingKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var tunOut: FileOutputStream? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        captureLog.clearLog()
        captureLog.addEntry("SYS", "vpn", "starting…")

        val fd = runCatching {
            Builder()
                .setSession("YMGMC Inspector")
                .addAddress("10.0.0.2", 32)
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)
                .establish()
        }.getOrNull() ?: run {
            captureLog.addEntry("SYS", "vpn", "establish() failed")
            return
        }

        vpnFd  = fd
        tunOut = FileOutputStream(fd.fileDescriptor)
        isRunning = true
        captureLog.addEntry("SYS", "vpn", "active ✓")
        tcpPool.submit { runLoop(fd) }
    }

    private fun stopVpn() {
        isRunning = false
        sessions.values.forEach { it.close() }
        sessions.clear()
        pendingKeys.clear()
        tunOut = null
        runCatching { vpnFd?.close() }
        vpnFd = null
        stopSelf()
    }

    // ─── Main read loop — processes packets inline, never submits to pool ─────

    private fun runLoop(fd: ParcelFileDescriptor) {
        captureLog.addEntry("SYS", "loop", "started")
        val input = FileInputStream(fd.fileDescriptor)
        val buf   = ByteArray(32767)
        try {
            while (isRunning) {
                val n = input.read(buf)
                if (n <= 0) continue
                handlePacket(buf, n)    // buf passed directly — no copy in hot path
            }
        } catch (e: Throwable) {        // catches Error (OOM) as well as Exception
            captureLog.addEntry("SYS", "loop", "died: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            runCatching { input.close() }
        }
    }

    private fun handlePacket(raw: ByteArray, len: Int) {
        if (len < 20) return
        if ((raw[0].toInt() and 0xF0) != 0x40) return   // IPv4 only
        val ihl = (raw[0].toInt() and 0x0F) * 4
        when (raw[9].toInt() and 0xFF) {
            6  -> handleTcp(raw, len, ihl)
            17 -> handleUdp(raw, len, ihl)
        }
    }

    // ─── TCP ──────────────────────────────────────────────────────────────────

    private fun handleTcp(raw: ByteArray, len: Int, ihl: Int) {
        if (len < ihl + 20) return
        val srcIp    = raw.u32(12);  val dstIp   = raw.u32(16)
        val srcPort  = raw.u16(ihl); val dstPort = raw.u16(ihl + 2)
        val theirSeq = raw.u32(ihl + 4)
        val dataOff  = ((raw[ihl + 12].toInt() and 0xFF) shr 4) * 4
        val flags    = raw[ihl + 13].toInt() and 0xFF
        val payOff   = ihl + dataOff
        val payload  = if (payOff < len) raw.copyOfRange(payOff, len) else null
        val key      = "$srcIp:$srcPort"

        val syn = (flags and 0x02) != 0
        val ack = (flags and 0x10) != 0
        val fin = (flags and 0x01) != 0
        val rst = (flags and 0x04) != 0

        when {
            rst || fin -> {
                pendingKeys.remove(key)
                sessions.remove(key)?.close()
            }
            syn && !ack -> {
                // Skip retransmitted SYNs — session already established or connect in-flight
                if (sessions.containsKey(key) || pendingKeys.contains(key)) return
                if (sessions.size + pendingKeys.size >= MAX_SESSIONS) {
                    writeTcp(dstIp, srcIp, dstPort, srcPort, 0, theirSeq + 1, 0x14)
                    return
                }
                pendingKeys.add(key)
                spawnConnection(key, srcIp, dstIp, srcPort, dstPort, theirSeq)
            }
            payload != null && payload.isNotEmpty() -> {
                val session = sessions[key] ?: return
                session.theirAck.addAndGet(payload.size)
                logIfFirst(session, payload, dstIp, dstPort)
                session.sendQueue.offer(payload)   // non-blocking, drops if full
                writeTcp(session.dstIp, session.srcIp, session.dstPort, session.srcPort,
                         session.ourSeq.get(), session.theirAck.get(), 0x10)
            }
        }
    }

    // One pair of pool threads per new TCP connection
    private fun spawnConnection(
        key: String, srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int, theirSeq: Int
    ) {
        tcpPool.submit {
            val sock = runCatching {
                Socket().also {
                    protect(it)
                    it.connect(InetSocketAddress(ipStr(dstIp), dstPort), 8_000)
                    it.soTimeout = 30_000
                }
            }.getOrElse {
                pendingKeys.remove(key)
                writeTcp(dstIp, srcIp, dstPort, srcPort, 0, theirSeq + 1, 0x14)
                return@submit
            }

            val ourSeq   = AtomicInteger(System.nanoTime().toInt() ushr 1)
            val theirAck = AtomicInteger(theirSeq + 1)
            val session  = TcpSession(srcIp, dstIp, srcPort, dstPort, ourSeq, theirAck, sock)
            sessions[key] = session
            pendingKeys.remove(key)

            writeTcp(dstIp, srcIp, dstPort, srcPort, ourSeq.get(), theirAck.get(), 0x12)
            ourSeq.incrementAndGet()

            // Second thread: server → client relay
            tcpPool.submit { relayFromServer(key, session) }

            // This thread: drain sendQueue → server (blocking writes off runLoop)
            try {
                val out = sock.getOutputStream()
                while (isRunning && sessions.containsKey(key)) {
                    val data = session.sendQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    out.write(data); out.flush()
                }
            } catch (_: Exception) {}
            sessions.remove(key)?.close()
        }
    }

    private fun relayFromServer(key: String, session: TcpSession) {
        val buf = ByteArray(8192)
        try {
            val inp = session.socket.getInputStream()
            var n   = inp.read(buf)
            while (n > 0 && isRunning && sessions.containsKey(key)) {
                val data = buf.copyOf(n)
                writeTcp(session.dstIp, session.srcIp, session.dstPort, session.srcPort,
                         session.ourSeq.get(), session.theirAck.get(), 0x18, data)
                session.ourSeq.addAndGet(n)
                n = inp.read(buf)
            }
        } catch (_: Exception) {}
        writeTcp(session.dstIp, session.srcIp, session.dstPort, session.srcPort,
                 session.ourSeq.get(), session.theirAck.get(), 0x11)
        sessions.remove(key)?.close()
    }

    private fun logIfFirst(session: TcpSession, payload: ByteArray, dstIp: Int, dstPort: Int) {
        if (session.logged) return
        session.logged = true
        if (dstPort == 443) {
            val sni = NetworkCaptureServer.extractSni(payload, payload.size) ?: ""
            captureLog.addEntry("HTTPS", if (sni.isNotEmpty()) sni else ipStr(dstIp), "", sni)
        } else {
            captureLog.addEntry(
                httpMethod(payload) ?: "HTTP",
                httpHost(payload) ?: ipStr(dstIp),
                httpPath(payload) ?: ""
            )
        }
    }

    // ─── UDP ─────────────────────────────────────────────────────────────────

    private fun handleUdp(raw: ByteArray, len: Int, ihl: Int) {
        if (len < ihl + 8) return
        val srcIp   = raw.u32(12); val dstIp = raw.u32(16)
        val srcPort = raw.u16(ihl); val dstPort = raw.u16(ihl + 2)
        val udpLen  = raw.u16(ihl + 4) - 8
        if (udpLen <= 0) return
        val payload = raw.copyOfRange(ihl + 8, ihl + 8 + udpLen)

        if (dstPort == 53) {
            // DNS: intercept, forward to 8.8.8.8, return response
            dnsPool.submit {
                runCatching {
                    val ds = DatagramSocket().also { protect(it) }
                    ds.soTimeout = 5_000
                    ds.send(DatagramPacket(payload, payload.size, InetSocketAddress("8.8.8.8", 53)))
                    val resp = ByteArray(512); val dp = DatagramPacket(resp, resp.size)
                    ds.receive(dp); ds.close()
                    writeUdp(dstIp, srcIp, dstPort, srcPort, resp.copyOf(dp.length))
                }
            }
        } else {
            // Other UDP (QUIC, NTP, etc.): pass through without logging
            dnsPool.submit {
                runCatching {
                    val ds = DatagramSocket().also { protect(it) }
                    ds.soTimeout = 3_000
                    ds.send(DatagramPacket(payload, payload.size, InetSocketAddress(ipStr(dstIp), dstPort)))
                    val resp = ByteArray(2048); val dp = DatagramPacket(resp, resp.size)
                    ds.receive(dp); ds.close()
                    writeUdp(dstIp, srcIp, dstPort, srcPort, resp.copyOf(dp.length))
                }
            }
        }
    }

    // ─── Packet builders ──────────────────────────────────────────────────────

    private fun writeTcp(
        srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int,
        seq: Int, ack: Int, flags: Int, data: ByteArray = ByteArray(0)
    ) {
        val total = 40 + data.size
        val p = ByteArray(total)
        p[0] = 0x45.toByte(); p.p16(2, total); p[8] = 64; p[9] = 6
        p.p32(12, srcIp); p.p32(16, dstIp)
        p.p16(10, ipChecksum(p, 0, 20))
        p.p16(20, srcPort); p.p16(22, dstPort)
        p.p32(24, seq); p.p32(28, ack)
        p[32] = 0x50.toByte(); p[33] = flags.toByte()
        p[34] = 0xFF.toByte(); p[35] = 0xFF.toByte()
        data.copyInto(p, 40)
        p.p16(36, tcpChecksum(p, srcIp, dstIp, 20, 20 + data.size))
        runCatching { val o = tunOut ?: return; synchronized(o) { o.write(p) } }
    }

    private fun writeUdp(srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int, data: ByteArray) {
        val udpLen = 8 + data.size; val total = 20 + udpLen
        val p = ByteArray(total)
        p[0] = 0x45.toByte(); p.p16(2, total); p[8] = 64; p[9] = 17
        p.p32(12, srcIp); p.p32(16, dstIp)
        p.p16(10, ipChecksum(p, 0, 20))
        p.p16(20, srcPort); p.p16(22, dstPort); p.p16(24, udpLen)
        data.copyInto(p, 28)
        runCatching { val o = tunOut ?: return; synchronized(o) { o.write(p) } }
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
        ph[28] = 0; ph[29] = 0
        return ipChecksum(ph, 0, ph.size)
    }

    // ─── HTTP / TLS helpers ───────────────────────────────────────────────────

    private fun httpHost(data: ByteArray) =
        runCatching { String(data, Charsets.ISO_8859_1) }.getOrNull()
            ?.lines()?.firstOrNull { it.startsWith("Host:", true) }
            ?.substringAfter(":")?.trim()?.substringBefore(":")

    private fun httpMethod(data: ByteArray) =
        runCatching { String(data, Charsets.ISO_8859_1).substringBefore(" ") }.getOrNull()
            ?.takeIf { it.length in 3..7 && it.all { c -> c.isLetter() } }

    private fun httpPath(data: ByteArray) =
        runCatching { String(data, Charsets.ISO_8859_1).lines().firstOrNull() }.getOrNull()
            ?.split(" ")?.getOrNull(1)

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

    override fun onDestroy() { stopVpn(); super.onDestroy() }
}

data class TcpSession(
    val srcIp: Int, val dstIp: Int,
    val srcPort: Int, val dstPort: Int,
    val ourSeq: AtomicInteger,
    val theirAck: AtomicInteger,
    val socket: Socket,
    @Volatile var logged: Boolean = false,
    val sendQueue: LinkedBlockingQueue<ByteArray> = LinkedBlockingQueue(128)
) {
    fun close() = runCatching { socket.close() }
}
