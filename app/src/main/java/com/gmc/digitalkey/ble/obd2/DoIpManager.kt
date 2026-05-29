package com.gmc.digitalkey.ble.obd2

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.Inet4Address
import java.nio.ByteBuffer
import java.nio.ByteOrder

// DoIP (Diagnostics over IP) client — ISO 13400-2
// Connects to the vehicle's Wi-Fi hotspot and tunnels UDS commands over TCP port 13400.
// On Ultium GM vehicles the infotainment (ECU 0x80) acts as the DoIP gateway
// bridging Ethernet to the CAN-connected K73 VCIM.
class DoIpManager(private val context: Context) {

    companion object {
        const val DOIP_PORT = 13400
        private const val PROTO_VER: Byte = 0x02
        private const val PROTO_VER_INV: Byte = 0xFD.toByte()
        const val TESTER_ADDR = 0x0E00

        // DoIP payload types
        private const val TYPE_VEHICLE_ID_REQ  = 0x0001
        private const val TYPE_VEHICLE_ANNOUNCE = 0x0004
        private const val TYPE_ROUTING_ACT_REQ  = 0x0005
        private const val TYPE_ROUTING_ACT_RESP = 0x0006
        private const val TYPE_DIAG_MSG         = 0x8001
        private const val TYPE_DIAG_ACK         = 0x8002

        // K73 VCIM logical addresses to probe (DoIP logical addr ≠ CAN addr, but GM often maps them 1:1)
        val VCIM_PROBE_ADDRS = listOf(0x0045, 0x0028, 0x007D, 0x0252, 0x07E3, 0x1801, 0x0001, 0x0010)

        val BLE_DID_CANDIDATES = listOf(
            0xF1A0 to "F1A0",
            0xF1B0 to "F1B0",
            0x4100 to "4100",
            0x4101 to "4101",
            0x0200 to "0200",
            0xF180 to "F180",
        )
    }

    sealed class DiscoveryResult {
        data class Found(val ip: String, val vin: String?, val logicalAddress: Int) : DiscoveryResult()
        object NotFound : DiscoveryResult()
    }

    private val _state = MutableStateFlow<DoIpState>(DoIpState.Idle)
    val state: StateFlow<DoIpState> = _state.asStateFlow()

    private var tcpSocket: Socket? = null
    private var out: OutputStream? = null
    private var inp: InputStream? = null

    val commandLog = mutableListOf<Pair<String, String>>()

    fun getGatewayIp(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(network) ?: return null
        return lp.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress
    }

    // Returns the phone's local IPv4 + prefix length (e.g. "10.2.114.100" / 24)
    fun getLocalNetworkInfo(): Triple<String, String, Int>? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(network) ?: return null
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress ?: "?"
        return Triple(la.address.hostAddress ?: "?", gateway, la.prefixLength)
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(PROTO_VER)
        buf.put(PROTO_VER_INV)
        buf.putShort(type.toShort())
        buf.putInt(payload.size)
        buf.put(payload)
        return buf.array()
    }

    suspend fun discoverVehicle(): DiscoveryResult = withContext(Dispatchers.IO) {
        _state.value = DoIpState.Scanning

        val netInfo = getLocalNetworkInfo()
        val localIp  = netInfo?.first  ?: "unknown"
        val gateway  = netInfo?.second ?: "unknown"
        val prefix   = netInfo?.third  ?: 24
        commandLog.add("Network info" to "local=$localIp  gw=$gateway  /$prefix")

        val reqFrame = frame(TYPE_VEHICLE_ID_REQ, byteArrayOf())

        // Phase 1 — UDP broadcast + unicast VehicleIdentificationRequest
        commandLog.add("UDP:$DOIP_PORT broadcast" to "sending VehicleIdentificationRequest (0x0001)…")
        var udpFound: DiscoveryResult.Found? = null
        try {
            val udp = DatagramSocket()
            udp.broadcast = true
            udp.soTimeout = 2000
            runCatching {
                udp.send(DatagramPacket(reqFrame, reqFrame.size, InetAddress.getByName("255.255.255.255"), DOIP_PORT))
            }
            if (gateway != "unknown") runCatching {
                udp.send(DatagramPacket(reqFrame, reqFrame.size, InetAddress.getByName(gateway), DOIP_PORT))
                commandLog.add("UDP:$DOIP_PORT → $gateway" to "VehicleIdentificationRequest sent")
            }
            // Wait up to 2 s for announcements
            repeat(6) {
                try {
                    val buf = ByteArray(512); val pkt = DatagramPacket(buf, buf.size)
                    udp.receive(pkt)
                    val from = pkt.address.hostAddress ?: ""
                    val parsed = parseAnnouncement(pkt.data.copyOf(pkt.length), from)
                    if (parsed != null) {
                        commandLog.add("UDP:$DOIP_PORT ← $from" to "VehicleAnnouncement ✓  VIN=${parsed.vin ?: "?"} logAddr=0x%04X".format(parsed.logicalAddress))
                        udpFound = parsed
                    } else {
                        commandLog.add("UDP:$DOIP_PORT ← $from" to "response: ${pkt.data.take(pkt.length).joinToString(" ") { "%02X".format(it) }}")
                    }
                } catch (_: Exception) {}
            }
            udp.close()
        } catch (e: Exception) {
            commandLog.add("UDP:$DOIP_PORT" to "error: ${e.message}")
        }
        udpFound?.let { return@withContext it }

        // Phase 2 — TCP probe: gateway + common IPs
        val priorityCandidates = buildList {
            if (gateway != "unknown") add(gateway)
            addAll(listOf("192.168.43.1", "192.168.1.1", "10.0.0.1", "172.20.10.1", "192.168.0.1"))
        }.distinct()

        commandLog.add("TCP:$DOIP_PORT probe" to "trying ${priorityCandidates.size} priority IPs…")
        for (ip in priorityCandidates) {
            try {
                val s = Socket(); s.connect(InetSocketAddress(ip, DOIP_PORT), 600); s.close()
                commandLog.add("TCP:$DOIP_PORT → $ip" to "OPEN ✓ — using this as DoIP server")
                return@withContext DiscoveryResult.Found(ip, null, 0)
            } catch (_: Exception) {
                commandLog.add("TCP:$DOIP_PORT → $ip" to "closed/timeout")
            }
        }

        // Phase 3 — Parallel full-subnet scan (skips phone's own IP)
        val subnetBase = computeSubnetBase(localIp, prefix)
        if (subnetBase != null && prefix in 16..28) {
            val hostCount = (1 shl (32 - prefix)) - 2
            val scanCount = minOf(hostCount, 254)
            commandLog.add("Subnet scan" to "$subnetBase.x/$prefix — scanning $scanCount hosts on TCP:$DOIP_PORT (300ms timeout)")
            val openIps = coroutineScope {
                (1..scanCount).map { i ->
                    async(Dispatchers.IO) {
                        val ip = "$subnetBase.$i"
                        if (ip == localIp) return@async null
                        try {
                            val s = Socket(); s.connect(InetSocketAddress(ip, DOIP_PORT), 300); s.close()
                            ip
                        } catch (_: Exception) { null }
                    }
                }.awaitAll().filterNotNull()
            }
            if (openIps.isNotEmpty()) {
                commandLog.add("Subnet scan result" to "DoIP port open on: ${openIps.joinToString(", ")}")
                return@withContext DiscoveryResult.Found(openIps.first(), null, 0)
            } else {
                commandLog.add("Subnet scan result" to "TCP:$DOIP_PORT closed on all $scanCount hosts in $subnetBase.x/$prefix")
            }
        }

        DiscoveryResult.NotFound
    }

    private fun computeSubnetBase(localIp: String, prefix: Int): String? {
        val parts = localIp.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return null
        val ipInt = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val net = ipInt and mask
        // Return the /24 base regardless of actual prefix — scanning /16+ takes too long
        val base24 = if (prefix <= 24) {
            val n = net ushr 8
            "${(n shr 16) and 0xFF}.${(n shr 8) and 0xFF}.${n and 0xFF}"
        } else {
            "${parts[0]}.${parts[1]}.${parts[2]}"
        }
        return base24
    }

    private fun parseAnnouncement(data: ByteArray, ip: String): DiscoveryResult.Found? {
        if (data.size < 8) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buf.get(); buf.get()
        val type = buf.short.toInt() and 0xFFFF
        val length = buf.int
        if (type != TYPE_VEHICLE_ANNOUNCE || length < 25 || data.size < 8 + length) return null

        val vinBytes = ByteArray(17); buf.get(vinBytes)
        val vin = String(vinBytes, Charsets.US_ASCII).filter { it.isLetterOrDigit() }
        val logicalAddr = buf.short.toInt() and 0xFFFF
        return DiscoveryResult.Found(ip, vin.takeIf { it.length == 17 }, logicalAddr)
    }

    suspend fun connect(host: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, DOIP_PORT), 5000)
            s.soTimeout = 10_000
            tcpSocket = s
            out = s.getOutputStream()
            inp = s.getInputStream()

            val payload = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN)
                .putShort(TESTER_ADDR.toShort())
                .put(0x00)          // activation type = Default
                .putInt(0x00000000) // reserved
                .array()
            out!!.write(frame(TYPE_ROUTING_ACT_REQ, payload))
            out!!.flush()

            val resp = readFrame(5000) ?: return@withContext false
            val rb = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN)
            rb.get(); rb.get()
            val rType = rb.short.toInt() and 0xFFFF
            val rLen  = rb.int
            if (rType == TYPE_ROUTING_ACT_RESP && rLen >= 5) {
                rb.short; rb.short          // src addr echo + entity addr
                val code = rb.get().toInt() and 0xFF
                if (code == 0x10) {
                    _state.value = DoIpState.Connected(host)
                    return@withContext true
                }
                // 0x00 = Successfully activated (older implementations)
                if (code == 0x00) { _state.value = DoIpState.Connected(host); return@withContext true }
            }
            false
        } catch (e: Exception) {
            _state.value = DoIpState.Error("Routing activation failed: ${e.message}")
            false
        }
    }

    suspend fun sendUds(targetAddress: Int, uds: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val udsHex = uds.joinToString(" ") { "%02X".format(it) }
        val label  = "→ 0x%04X  $udsHex".format(targetAddress)

        val payload = ByteBuffer.allocate(4 + uds.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(TESTER_ADDR.toShort())
            .putShort(targetAddress.toShort())
            .put(uds)
            .array()
        out!!.write(frame(TYPE_DIAG_MSG, payload))
        out!!.flush()

        // Read frames: skip acks (0x8002) and NRC 0x78 pending responses (0x8001 + 7F xx 78)
        var result = byteArrayOf()
        repeat(10) iter@{
            val msg = readFrame(8000) ?: return@iter
            val mb = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN)
            mb.get(); mb.get()
            val mType = mb.short.toInt() and 0xFFFF
            val mLen  = mb.int
            if (mType == TYPE_DIAG_ACK) return@iter         // positive ack — keep waiting
            if (mType == TYPE_DIAG_MSG && mLen > 4) {
                mb.short; mb.short   // src / dst addresses
                val udsData = ByteArray(mLen - 4); mb.get(udsData)
                result = udsData
                // NRC 0x78 = responseCorrectlyReceivedResponsePending — keep reading
                if (udsData.firstOrNull() == 0x7F.toByte() && udsData.getOrNull(2) == 0x78.toByte()) return@iter
                commandLog.add(label to udsData.joinToString(" ") { "%02X".format(it) })
                return@withContext udsData
            }
        }
        commandLog.add(label to (if (result.isEmpty()) "(timeout)" else result.joinToString(" ") { "%02X".format(it) }))
        result
    }

    private suspend fun readFrame(timeoutMs: Long): ByteArray? = withContext(Dispatchers.IO) {
        try {
            withTimeout(timeoutMs) {
                val stream = inp ?: return@withTimeout null
                val header = ByteArray(8)
                var read = 0
                while (read < 8) {
                    val n = stream.read(header, read, 8 - read)
                    if (n < 0) return@withTimeout null
                    read += n
                }
                val length = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.BIG_ENDIAN).int
                if (length > 65535) return@withTimeout null  // sanity check
                val payload = ByteArray(length)
                var pRead = 0
                while (pRead < length) {
                    val n = stream.read(payload, pRead, length - pRead)
                    if (n < 0) break
                    pRead += n
                }
                header + payload
            }
        } catch (_: Exception) { null }
    }

    fun disconnect() {
        runCatching { tcpSocket?.close() }
        tcpSocket = null; out = null; inp = null
        _state.value = DoIpState.Idle
    }
}
