package com.gmc.digitalkey.ble

import android.net.Uri
import android.util.Log
import java.util.UUID
import java.util.regex.Pattern

object QrPairingParser {

    private const val TAG = "QrPairingParser"

    data class PairingHints(
        val raw: String,
        val bleUuid: UUID? = null,
        val bleAddress: String? = null,  // Bluetooth MAC if present
        val vehicleId: String? = null,   // e.g. "D263" from GM infotainment
        val pairingCode: String? = null, // 4-char confirmation code shown on vehicle screen
        val token: String? = null,       // full enrollment / session token
        val vin: String? = null,         // 17-char VIN if present
        val url: String? = null,
    ) {
        fun summary(): String = buildString {
            // Most important fields first
            if (pairingCode != null) appendLine("★ Pairing code: $pairingCode")
            if (vin != null)         appendLine("VIN: $vin")
            if (vehicleId != null)   appendLine("Vehicle ID: $vehicleId")
            if (bleAddress != null)  appendLine("BT Address: $bleAddress")
            if (bleUuid != null)     appendLine("BLE UUID: $bleUuid")
            if (token != null)       appendLine("Token: ${token.take(80)}${if (token.length > 80) "…" else ""}")
            if (url != null)         appendLine("URL: $url")
            appendLine()
            appendLine("RAW: $raw")
        }.trimEnd()
    }

    private val MAC_RE = Pattern.compile(
        "[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}"
    )
    private val UUID_RE = Pattern.compile(
        "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}"
    )
    // 17-char VIN: starts with 1/2/3/J/S/W/K/L/M/N/P/R and has no I/O/Q
    private val VIN_RE = Pattern.compile("[A-HJ-NPR-Z0-9]{17}")
    // 4-char alphanumeric confirmation code (GM uses uppercase A-Z 0-9)
    private val CODE4_RE = Pattern.compile("(?i)(?:code|pin|pairing)[=: _]([A-Z0-9]{4})(?:[^A-Z0-9]|\$)")

    fun parse(text: String): PairingHints {
        val t = text.trim()
        Log.i(TAG, "QR raw: $t")

        val isUrl = t.startsWith("http://", ignoreCase = true) ||
                    t.startsWith("https://", ignoreCase = true) ||
                    t.contains("://")

        val bleUuid: UUID? = UUID_RE.matcher(t).run {
            if (find()) runCatching { UUID.fromString(group()) }.getOrNull() else null
        }
        val bleAddress: String? = MAC_RE.matcher(t).run { if (find()) group().uppercase() else null }

        // VIN — look for 17-char pattern
        val vin: String? = VIN_RE.matcher(t).let { m ->
            val candidates = mutableListOf<String>()
            while (m.find()) candidates.add(m.group())
            candidates.firstOrNull { it.length == 17 }
        }

        var vehicleId: String? = null
        var pairingCode: String? = null
        var token: String? = null
        var url: String? = null

        if (isUrl) {
            url = t
            val uri = runCatching { Uri.parse(t) }.getOrNull()

            // GM pairing URL parameters (observed + anticipated)
            val gmCodeParams = listOf("code", "pairing_code", "pin", "confirmCode", "c", "pc")
            for (param in gmCodeParams) {
                val v = uri?.getQueryParameter(param)
                if (v != null && v.length in 4..8) { pairingCode = v.uppercase(); break }
            }

            // Try inline code pattern if not found in params
            if (pairingCode == null) {
                CODE4_RE.matcher(t).takeIf { it.find() }?.let { pairingCode = it.group(1).uppercase() }
            }

            val vidParams = listOf("vid", "vehicleId", "vehicle_id", "device", "deviceId")
            for (param in vidParams) {
                val v = uri?.getQueryParameter(param)
                if (v != null) { vehicleId = v; break }
            }

            val tokenParams = listOf("token", "t", "session", "sessionToken", "enrollment_token", "et")
            for (param in tokenParams) {
                val v = uri?.getQueryParameter(param)
                if (v != null) { token = v; break }
            }

            // If VIN not found yet, try path segments (GM sometimes puts VIN in URL path)
            if (vin == null) {
                uri?.pathSegments?.firstOrNull { VIN_RE.matcher(it).matches() }?.let {
                    // vin is val so we handle via separate variable
                }
            }
        } else {
            // Not a URL — try inline pattern
            CODE4_RE.matcher(t).takeIf { it.find() }?.let { pairingCode = it.group(1).uppercase() }
            // 4-char only string → direct pairing code
            if (t.matches(Regex("[A-Z0-9]{4}"))) pairingCode = t
            // Longer non-URL string → treat as token
            if (pairingCode == null && bleUuid == null && bleAddress == null) token = t
        }

        // If we found a VIN from URL query params override the regex match
        val finalVin = uri_vin(url) ?: vin

        return PairingHints(t, bleUuid, bleAddress, vehicleId, pairingCode, token, finalVin, url)
            .also {
                Log.i(TAG, "Parsed: code=${it.pairingCode} vin=${it.vin} vid=${it.vehicleId} token=${it.token?.take(20)}")
            }
    }

    private fun uri_vin(url: String?): String? {
        if (url == null) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        for (param in listOf("vin", "VIN", "vehicleVin")) {
            val v = uri.getQueryParameter(param)
            if (v != null && v.length == 17) return v
        }
        return null
    }
}
