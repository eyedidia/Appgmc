package com.gmc.digitalkey.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NetworkCaptureServer {

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

    fun clearLog() { _entries.value = emptyList() }

    private val gmKeywords = listOf(
        "gm.com", "onstar", "chevrolet.com", "cadillac.com",
        "buick.com", "gmfinancial", "ultifi", "mygmc"
    )

    fun addEntry(method: String, host: String, path: String, sni: String = "") {
        val ts   = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val isGm = gmKeywords.any { kw ->
            host.contains(kw, ignoreCase = true) || sni.contains(kw, ignoreCase = true)
        }
        _entries.value = (_entries.value + LogEntry(ts, method, host, path, sni, isGm)).takeLast(500)
    }

    companion object {
        fun extractSni(data: ByteArray, len: Int): String? {
            try {
                if (len < 44) return null
                if (data[0] != 0x16.toByte()) return null
                if (data[5] != 0x01.toByte()) return null
                var pos = 43
                val sidLen = data[pos++].toInt() and 0xFF; pos += sidLen
                if (pos + 2 > len) return null
                val csLen = ((data[pos++].toInt() and 0xFF) shl 8) or (data[pos++].toInt() and 0xFF)
                pos += csLen
                if (pos + 1 > len) return null
                val cmLen = data[pos++].toInt() and 0xFF; pos += cmLen
                if (pos + 2 > len) return null
                val extTot = ((data[pos++].toInt() and 0xFF) shl 8) or (data[pos++].toInt() and 0xFF)
                val extEnd = minOf(pos + extTot, len)
                while (pos + 4 <= extEnd) {
                    val extType = ((data[pos++].toInt() and 0xFF) shl 8) or (data[pos++].toInt() and 0xFF)
                    val extLen  = ((data[pos++].toInt() and 0xFF) shl 8) or (data[pos++].toInt() and 0xFF)
                    if (extType == 0x0000 && extLen >= 5) {
                        val nameLen = ((data[pos + 3].toInt() and 0xFF) shl 8) or (data[pos + 4].toInt() and 0xFF)
                        if (pos + 5 + nameLen <= len)
                            return String(data, pos + 5, nameLen, Charsets.US_ASCII)
                    }
                    pos += extLen
                }
            } catch (_: Exception) {}
            return null
        }
    }
}
