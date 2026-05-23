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
        val token: String? = null,       // enrollment/pairing token
        val url: String? = null,
    ) {
        fun summary(): String = buildString {
            if (bleUuid != null) appendLine("BLE UUID: $bleUuid")
            if (bleAddress != null) appendLine("BT Address: $bleAddress")
            if (vehicleId != null) appendLine("Vehicle ID: $vehicleId")
            if (token != null) appendLine("Token: ${token.take(40)}${if (token.length > 40) "…" else ""}")
            if (url != null) appendLine("URL: ${url.take(80)}${if (url.length > 80) "…" else ""}")
            if (isEmpty()) appendLine("Raw: ${raw.take(80)}")
        }.trimEnd()
    }

    private val MAC_RE = Pattern.compile(
        "[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}"
    )
    private val UUID_RE = Pattern.compile(
        "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}"
    )
    // Matches "D263", "A1B2" after keywords like "Vehicle", "vid=", "device="
    private val VEHICLE_ID_RE = Pattern.compile(
        "(?i)(?:vehicle[_ ]?|vid[=:]?|device[=:]?)([A-Z0-9]{3,6})"
    )

    fun parse(text: String): PairingHints {
        val t = text.trim()
        Log.i(TAG, "QR raw: $t")

        val isUrl = t.startsWith("http://", ignoreCase = true) ||
                    t.startsWith("https://", ignoreCase = true)

        var bleUuid: UUID? = UUID_RE.matcher(t).run { if (find()) runCatching { UUID.fromString(group()) }.getOrNull() else null }
        val bleAddress: String? = MAC_RE.matcher(t).run { if (find()) group().uppercase() else null }
        var vehicleId: String? = VEHICLE_ID_RE.matcher(t).run { if (find()) group(1) else null }
        var token: String? = null
        var url: String? = null

        if (isUrl) {
            url = t
            val uri = runCatching { Uri.parse(t) }.getOrNull()
            if (vehicleId == null) vehicleId = uri?.getQueryParameter("vid")
                ?: uri?.getQueryParameter("vehicleId")
                ?: uri?.getQueryParameter("device")
            token = uri?.getQueryParameter("token")
                ?: uri?.getQueryParameter("t")
                ?: uri?.getQueryParameter("pairing_code")
                ?: uri?.getQueryParameter("code")
        }

        // If nothing meaningful found, treat entire string as a token
        if (!isUrl && bleUuid == null && bleAddress == null && token == null) {
            token = t
        }

        return PairingHints(t, bleUuid, bleAddress, vehicleId, token, url)
            .also { Log.i(TAG, "Parsed: uuid=${it.bleUuid} addr=${it.bleAddress} vid=${it.vehicleId} token=${it.token?.take(20)}") }
    }
}
