package com.gmc.digitalkey.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class NetworkCaptureServer {

    companion object {
        const val PORT = 8888
    }

    data class LogEntry(
        val ts: String,
        val method: String,
        val host: String,
        val path: String = "",
        val sni: String = "",
        val isGm: Boolean
    ) {
        val display: String get() = buildString {
            append("[$ts] $method $host")
            if (path.isNotEmpty() && path != "/") append(path)
            if (sni.isNotEmpty() && sni != host) append(" (SNI: $sni)")
        }
    }

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        if (running) return
        running = true
        pool.submit {
            try {
                val ss = ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"))
                serverSocket = ss
                while (running) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    pool.submit { handleClient(client) }
                }
            } catch (_: Exception) { }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun clearLog() {
        _entries.value = emptyList()
    }

    // ─── Per-connection handler ───────────────────────────────────────────────

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 15_000
            val clientIn  = client.getInputStream()
            val clientOut = client.getOutputStream()

            val firstLine = readLine(clientIn) ?: return
            val headers   = readHeaders(clientIn)
            val parts     = firstLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val target = parts[1]

            if (method == "CONNECT") {
                val (host, portStr) = target.split(":").let { it[0] to it.getOrNull(1) }
                val port = portStr?.toIntOrNull() ?: 443
                clientOut.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                clientOut.flush()

                val targetSock = runCatching { Socket(host, port) }.getOrNull() ?: return
                targetSock.soTimeout = 30_000

                val sni = tryExtractSni(clientIn, targetSock.getOutputStream())
                addEntry("HTTPS", host, "", sni ?: "")

                val t = Thread { relay(targetSock.getInputStream(), clientOut) }
                t.isDaemon = true; t.start()
                relay(clientIn, targetSock.getOutputStream())
                t.join(5_000)
                runCatching { targetSock.close() }

            } else {
                val hostHeader = headers["host"] ?: target.removePrefix("http://").substringBefore("/")
                val path = if (target.startsWith("http")) {
                    "/${target.removePrefix("http://").substringAfter("/")}"
                } else target

                val targetHost = hostHeader.substringBefore(":")
                val targetPort = hostHeader.substringAfter(":", "80").toIntOrNull() ?: 80
                addEntry(method, targetHost, path)

                val targetSock = runCatching { Socket(targetHost, targetPort) }.getOrNull() ?: return
                val targetOut = targetSock.getOutputStream()
                targetOut.write("$firstLine\r\n".toByteArray())
                headers.forEach { (k, v) -> targetOut.write("$k: $v\r\n".toByteArray()) }
                targetOut.write("\r\n".toByteArray())
                targetOut.flush()

                val t = Thread { relay(targetSock.getInputStream(), clientOut) }
                t.isDaemon = true; t.start()
                relay(clientIn, targetOut)
                t.join(5_000)
                runCatching { targetSock.close() }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
        }
    }

    // ─── TLS SNI extraction ───────────────────────────────────────────────────

    private fun tryExtractSni(clientIn: InputStream, targetOut: OutputStream): String? {
        return try {
            val buf = ByteArray(4096)
            val n   = clientIn.read(buf)
            if (n <= 0) return null
            targetOut.write(buf, 0, n)
            targetOut.flush()
            extractSniFromClientHello(buf, n)
        } catch (_: Exception) { null }
    }

    private fun extractSniFromClientHello(data: ByteArray, len: Int): String? {
        try {
            // TLS record: type=0x16, version(2), length(2), handshake type=0x01 (ClientHello)
            if (len < 44) return null
            if (data[0] != 0x16.toByte()) return null
            if (data[5] != 0x01.toByte()) return null
            // Skip: record hdr(5) + hs hdr(4) + version(2) + random(32) = 43
            var pos = 43
            val sidLen = data[pos++].toInt() and 0xFF;  pos += sidLen
            if (pos + 2 > len) return null
            val csLen = ((data[pos++].toInt() and 0xFF) shl 8) or (data[pos++].toInt() and 0xFF)
            pos += csLen
            if (pos + 1 > len) return null
            val cmLen = data[pos++].toInt() and 0xFF;   pos += cmLen
            if (pos + 2 > len) return null
            val extTot = ((data[pos++].toInt() and 0xFF) shl 8) or (data[pos++].toInt() and 0xFF)
            val extEnd = minOf(pos + extTot, len)
            while (pos + 4 <= extEnd) {
                val extType = ((data[pos++].toInt() and 0xFF) shl 8) or (data[pos++].toInt() and 0xFF)
                val extLen  = ((data[pos++].toInt() and 0xFF) shl 8) or (data[pos++].toInt() and 0xFF)
                if (extType == 0x0000 && extLen >= 5) {   // SNI extension
                    // list_len(2) + name_type(1)=0x00 + name_len(2) + name
                    val nameLen = ((data[pos + 3].toInt() and 0xFF) shl 8) or (data[pos + 4].toInt() and 0xFF)
                    if (pos + 5 + nameLen <= len)
                        return String(data, pos + 5, nameLen, Charsets.US_ASCII)
                }
                pos += extLen
            }
        } catch (_: Exception) { }
        return null
    }

    // ─── HTTP parsing helpers ─────────────────────────────────────────────────

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var b = input.read()
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) sb.append(b.toChar())
            b = input.read()
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val sb  = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) break
            if (b == '\n'.code) {
                val line = sb.toString().trim()
                sb.clear()
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) map[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            } else if (b != '\r'.code) {
                sb.append(b.toChar())
            }
        }
        return map
    }

    // ─── Byte relay ──────────────────────────────────────────────────────────

    private fun relay(input: InputStream, output: OutputStream) {
        val buf = ByteArray(8192)
        try {
            var n = input.read(buf)
            while (n >= 0) { output.write(buf, 0, n); output.flush(); n = input.read(buf) }
        } catch (_: Exception) { }
    }

    // ─── Log helpers ─────────────────────────────────────────────────────────

    private val gmKeywords = listOf(
        "gm.com", "onstar", "chevrolet.com", "cadillac.com",
        "buick.com", "gmfinancial", "ultifi", "mygmc"
    )

    private fun addEntry(method: String, host: String, path: String, sni: String = "") {
        val ts    = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val isGm  = gmKeywords.any { kw -> host.contains(kw, ignoreCase = true) ||
                                           sni.contains(kw, ignoreCase = true) }
        val entry = LogEntry(ts, method, host, path, sni, isGm)
        _entries.value = (_entries.value + entry).takeLast(500)
    }
}
