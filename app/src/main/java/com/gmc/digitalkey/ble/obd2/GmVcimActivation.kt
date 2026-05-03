package com.gmc.digitalkey.ble.obd2

import android.util.Log

object GmVcimActivation {

    private const val TAG = "GmVcimActivation"

    // GM VCIM address on the high-speed GMLAN bus (observed on Hummer EV / Sierra EV)
    private const val VCIM_ECU_ADDRESS = 0x7E3

    // UDS service IDs (ISO 14229-1)
    private const val SVC_DIAGNOSTIC_SESSION = 0x10.toByte()
    private const val SVC_SECURITY_ACCESS    = 0x27.toByte()
    private const val SVC_WRITE_DATA_BY_ID   = 0x2E.toByte()
    private const val SVC_READ_DATA_BY_ID    = 0x22.toByte()

    private const val SESSION_EXTENDED       = 0x03.toByte()
    private const val SEC_REQUEST_SEED       = 0x01.toByte()

    // TODO: Confirm this DID by running discoverVcimDids() on an activated and an
    // unactivated vehicle and comparing which value changes. Candidate DIDs:
    //   0xF1A0 — Connectivity Module Feature Config
    //   0xF1B0 — Digital Key BLE Enable (most likely)
    //   0x4100 — GM proprietary BLE advertising control
    private val DID_BLE_ENABLE = byteArrayOf(0xF1.toByte(), 0xB0.toByte())
    private val DID_BLE_ENABLE_VALUE = byteArrayOf(0x01)

    sealed class ActivationResult {
        object Success : ActivationResult()
        object NeedsSecurityKey : ActivationResult()
        object UnsupportedModel : ActivationResult()
        data class CommunicationError(val detail: String) : ActivationResult()
        data class DiagnosticData(val readDids: Map<String, ByteArray>) : ActivationResult()
    }

    suspend fun activateDigitalKeyBle(manager: Obd2Manager): ActivationResult {
        return try {
            // Step 1: Extended diagnostic session on VCIM
            Log.d(TAG, "Requesting extended session on VCIM 0x${VCIM_ECU_ADDRESS.toString(16)}")
            val sessionResp = manager.sendUds(
                VCIM_ECU_ADDRESS,
                byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_EXTENDED)
            )
            // Positive response for DiagnosticSessionControl = 0x50
            if (sessionResp.isEmpty() || sessionResp[0] != 0x50.toByte()) {
                Log.w(TAG, "Session response: ${sessionResp.toHex()}")
                return ActivationResult.CommunicationError(
                    "VCIM did not accept extended session (${sessionResp.toHex()}). " +
                    "Is the vehicle's ignition on?"
                )
            }
            Log.i(TAG, "Extended session accepted")

            // Step 2: Try WriteDataByIdentifier without SecurityAccess first
            // Some GM ECUs allow feature-config DIDs without security in extended session
            val writeResp = manager.sendUds(
                VCIM_ECU_ADDRESS,
                byteArrayOf(SVC_WRITE_DATA_BY_ID, *DID_BLE_ENABLE, *DID_BLE_ENABLE_VALUE)
            )

            when {
                writeResp.firstOrNull() == 0x6E.toByte() -> {
                    // Positive response — BLE enable written successfully
                    Log.i(TAG, "BLE enable DID written without SecurityAccess — success")
                    return ActivationResult.Success
                }
                writeResp.getOrNull(2) == 0x31.toByte() -> {
                    // NRC 0x31 = RequestOutOfRange — DID not recognized on this VCIM
                    Log.w(TAG, "DID ${DID_BLE_ENABLE.toHex()} not recognized — running discovery")
                    val dids = discoverVcimDids(manager)
                    return ActivationResult.DiagnosticData(dids)
                }
                writeResp.getOrNull(2) == 0x33.toByte() -> {
                    // NRC 0x33 = SecurityAccessDenied — need seed-key first
                    Log.d(TAG, "SecurityAccess required, requesting seed")
                }
                else -> {
                    Log.d(TAG, "Unexpected write response: ${writeResp.toHex()}, trying SecurityAccess")
                }
            }

            // Step 3: SecurityAccess seed request (level 0x01)
            val seedResp = manager.sendUds(
                VCIM_ECU_ADDRESS,
                byteArrayOf(SVC_SECURITY_ACCESS, SEC_REQUEST_SEED)
            )
            // Positive: 0x67 0x01 <4-byte seed>
            if (seedResp.size < 6 || seedResp[0] != 0x67.toByte()) {
                return ActivationResult.CommunicationError(
                    "SecurityAccess seed request failed (${seedResp.toHex()})"
                )
            }
            val seed = seedResp.drop(2).take(4).toByteArray()
            Log.w(TAG, "VCIM SecurityAccess seed: ${seed.toHex()}")
            Log.w(TAG, "GM VCIM seed-key algorithm is proprietary. " +
                "Capture a GDS2/Tech2Win session to reverse the transform. " +
                "Share this seed with the community to derive the key constant.")

            ActivationResult.NeedsSecurityKey

        } catch (e: Exception) {
            Log.e(TAG, "Activation error", e)
            ActivationResult.CommunicationError(e.message ?: "Unknown error")
        }
    }

    // Reads a range of candidate DIDs from VCIM for research purposes.
    // Run on one activated vehicle and one unactivated vehicle, then compare
    // which DID value differs — that DID controls BLE enable.
    suspend fun discoverVcimDids(manager: Obd2Manager): Map<String, ByteArray> {
        Log.i(TAG, "=== VCIM DID discovery at 0x${VCIM_ECU_ADDRESS.toString(16)} ===")
        val results = mutableMapOf<String, ByteArray>()

        val probeList = listOf(
            0xF190, // VIN (standard, confirms comms)
            0xF18C, // ECU serial number
            0xF100, // Boot software ID
            0xF1A0, // Connectivity module config 1
            0xF1A1, // Connectivity module config 2
            0xF1B0, // Digital key BLE enable (primary candidate)
            0xF1B1, // BLE advertising config
            0xF1B2,
            0x4100, // GM proprietary connectivity state
            0x4101,
            0x4102,
            0x4110, // BLE pairing state candidates
            0x4111,
            0x4112,
            0x4120
        )

        probeList.forEach { did ->
            try {
                val didBytes = byteArrayOf((did shr 8).toByte(), (did and 0xFF).toByte())
                val resp = manager.sendUds(
                    VCIM_ECU_ADDRESS,
                    byteArrayOf(SVC_READ_DATA_BY_ID, *didBytes)
                )
                // Positive: 0x62 DID_HIGH DID_LOW <data>
                if (resp.firstOrNull() == 0x62.toByte() && resp.size > 3) {
                    val data = resp.drop(3).toByteArray()
                    val key = "0x%04X".format(did)
                    results[key] = data
                    Log.i(TAG, "DID $key = ${data.toHex()}")
                } else {
                    Log.v(TAG, "DID 0x%04X: NRC ${resp.toHex()}".format(did))
                }
            } catch (e: Exception) {
                Log.v(TAG, "DID 0x%04X: timeout".format(did))
            }
        }

        Log.i(TAG, "=== Discovery complete: ${results.size} DIDs readable ===")
        return results
    }

    private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
    private fun byteArrayOf(vararg elements: Byte) = elements
}
