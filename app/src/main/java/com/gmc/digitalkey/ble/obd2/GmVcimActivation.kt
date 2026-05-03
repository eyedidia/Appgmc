package com.gmc.digitalkey.ble.obd2

import android.util.Log

object GmVcimActivation {

    private const val TAG = "GmVcimActivation"

    // UDS service IDs (ISO 14229-1)
    private const val SVC_DIAGNOSTIC_SESSION = 0x10.toByte()
    private const val SVC_SECURITY_ACCESS    = 0x27.toByte()
    private const val SVC_WRITE_DATA_BY_ID   = 0x2E.toByte()
    private const val SVC_READ_DATA_BY_ID    = 0x22.toByte()

    private const val SESSION_DEFAULT        = 0x01.toByte()
    private const val SESSION_EXTENDED       = 0x03.toByte()
    private const val SEC_REQUEST_SEED       = 0x01.toByte()

    // GM candidate BLE-enable DIDs in priority order.
    // TODO: Confirm by running discoverVcimDids() before and after OnStar activation
    // on one reference vehicle and checking which DID changes value.
    private val BLE_DID_CANDIDATES = listOf(
        byteArrayOf(0xF1.toByte(), 0xB0.toByte()), // Connectivity feature config (most likely)
        byteArrayOf(0xF1.toByte(), 0xA0.toByte()), // Module feature config 1
        byteArrayOf(0xF1.toByte(), 0xA1.toByte()), // Module feature config 2
        byteArrayOf(0x41.toByte(), 0x00.toByte()), // GM proprietary BLE state
        byteArrayOf(0x41.toByte(), 0x10.toByte()), // BLE pairing control
    )
    private val DID_BLE_ENABLE_VALUE = byteArrayOf(0x01)

    // Common GM ECU address range to probe for VCIM
    private val ECU_PROBE_RANGE = 0x7E0..0x7E7

    sealed class ActivationResult {
        data class Success(val vcimAddress: Int) : ActivationResult()
        data class NeedsSecurityKey(val vcimAddress: Int, val seed: ByteArray) : ActivationResult()
        object UnsupportedModel : ActivationResult()
        data class CommunicationError(val detail: String) : ActivationResult()
        data class DiagnosticData(val vcimAddress: Int, val readDids: Map<String, ByteArray>) : ActivationResult()
    }

    // Discover all ECUs responding on the CAN bus, then identify VCIM
    suspend fun discoverEcus(manager: Obd2Manager): List<Int> {
        Log.i(TAG, "=== Scanning CAN bus for ECUs (0x7E0–0x7E7) ===")
        val found = mutableListOf<Int>()
        for (addr in ECU_PROBE_RANGE) {
            try {
                val resp = manager.sendUds(addr, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_DEFAULT))
                if (resp.firstOrNull() == 0x50.toByte()) {
                    found.add(addr)
                    Log.i(TAG, "ECU present at 0x${addr.toString(16)}")
                }
            } catch (_: Exception) {
                // timeout = no ECU at this address
            }
        }
        Log.i(TAG, "ECUs found: ${found.map { "0x${it.toString(16)}" }}")
        return found
    }

    // Among discovered ECUs, find the one that has connectivity/VIN DIDs (= VCIM)
    suspend fun findVcimAddress(manager: Obd2Manager, ecus: List<Int>): Int {
        val candidates = if (ecus.isEmpty()) ECU_PROBE_RANGE.toList() else ecus

        for (addr in candidates) {
            try {
                // Try Extended Session first — only VCIM/telematics modules accept it
                val sessResp = manager.sendUds(addr, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_EXTENDED))
                if (sessResp.firstOrNull() != 0x50.toByte()) continue

                // Probe a connectivity DID
                for (did in BLE_DID_CANDIDATES) {
                    val resp = manager.sendUds(addr, byteArrayOf(SVC_READ_DATA_BY_ID, *did))
                    if (resp.firstOrNull() == 0x62.toByte()) {
                        Log.i(TAG, "VCIM identified at 0x${addr.toString(16)} (DID ${did.toHex()} readable)")
                        return addr
                    }
                }

                // Fallback: try VIN DID (F190) — VCIM usually has it
                val vinResp = manager.sendUds(addr, byteArrayOf(SVC_READ_DATA_BY_ID, 0xF1.toByte(), 0x90.toByte()))
                if (vinResp.firstOrNull() == 0x62.toByte()) {
                    Log.i(TAG, "VCIM identified at 0x${addr.toString(16)} (VIN DID readable)")
                    return addr
                }
            } catch (_: Exception) { }
        }

        // Default fallback — most common address for Hummer EV / Sierra EV VCIM
        Log.w(TAG, "VCIM not identified — defaulting to 0x7E3")
        return 0x7E3
    }

    suspend fun activateDigitalKeyBle(manager: Obd2Manager, vcimAddress: Int): ActivationResult {
        return try {
            Log.d(TAG, "Activation: ExtendedSession on VCIM 0x${vcimAddress.toString(16)}")
            val sessionResp = manager.sendUds(vcimAddress, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_EXTENDED))
            if (sessionResp.isEmpty() || sessionResp[0] != 0x50.toByte()) {
                return ActivationResult.CommunicationError(
                    "VCIM at 0x${vcimAddress.toString(16)} rejected extended session " +
                    "(${sessionResp.toHex()}). Make sure ignition is ON."
                )
            }
            Log.i(TAG, "Extended session accepted on 0x${vcimAddress.toString(16)}")

            // Try each candidate DID without SecurityAccess first
            for (did in BLE_DID_CANDIDATES) {
                val writeResp = manager.sendUds(
                    vcimAddress,
                    byteArrayOf(SVC_WRITE_DATA_BY_ID, *did, *DID_BLE_ENABLE_VALUE)
                )
                when {
                    writeResp.firstOrNull() == 0x6E.toByte() -> {
                        Log.i(TAG, "BLE enabled via DID ${did.toHex()} on 0x${vcimAddress.toString(16)}")
                        return ActivationResult.Success(vcimAddress)
                    }
                    writeResp.getOrNull(2) == 0x33.toByte() -> {
                        // SecurityAccessDenied — need seed/key
                        Log.d(TAG, "DID ${did.toHex()} requires SecurityAccess")
                        break
                    }
                    writeResp.getOrNull(2) == 0x31.toByte() ->
                        Log.v(TAG, "DID ${did.toHex()} not recognized on this VCIM")
                    else ->
                        Log.v(TAG, "DID ${did.toHex()} response: ${writeResp.toHex()}")
                }
            }

            // SecurityAccess: request seed level 01
            val seedResp = manager.sendUds(vcimAddress, byteArrayOf(SVC_SECURITY_ACCESS, SEC_REQUEST_SEED))
            if (seedResp.size >= 6 && seedResp[0] == 0x67.toByte()) {
                val seed = seedResp.drop(2).take(4).toByteArray()
                Log.w(TAG, "VCIM seed (save this!): ${seed.toHex()}")
                Log.w(TAG, "Share this seed to help derive the GM VCIM seed-key algorithm")
                return ActivationResult.NeedsSecurityKey(vcimAddress, seed)
            }

            // Couldn't write or get seed — run full discovery for research
            val dids = discoverVcimDids(manager, vcimAddress)
            ActivationResult.DiagnosticData(vcimAddress, dids)

        } catch (e: Exception) {
            Log.e(TAG, "Activation error", e)
            ActivationResult.CommunicationError(e.message ?: "Unknown error")
        }
    }

    suspend fun discoverVcimDids(manager: Obd2Manager, vcimAddress: Int): Map<String, ByteArray> {
        Log.i(TAG, "=== DID discovery on 0x${vcimAddress.toString(16)} ===")
        val results = mutableMapOf<String, ByteArray>()

        // Extended probe list — standard + GM proprietary candidates
        val probeList = listOf(
            0xF190, // VIN
            0xF18C, // ECU serial number
            0xF100, // Boot software ID
            0xF101,
            0xF18A, // System supplier ID
            0xF18B, // ECU manufacturing date
            0xF195, // Software version
            0xF1A0, // Connectivity module config 1
            0xF1A1, // Connectivity module config 2
            0xF1A2,
            0xF1A3,
            0xF1B0, // Digital key BLE enable (primary candidate)
            0xF1B1,
            0xF1B2,
            0xF1B3,
            0x4100, // GM proprietary connectivity state
            0x4101,
            0x4102,
            0x4103,
            0x4110, // BLE pairing state candidates
            0x4111,
            0x4112,
            0x4113,
            0x4120,
            0x4121,
            0x4130,
            0x4200,
            0x4201,
        )

        probeList.forEach { did ->
            try {
                val didBytes = byteArrayOf((did shr 8).toByte(), (did and 0xFF).toByte())
                val resp = manager.sendUds(vcimAddress, byteArrayOf(SVC_READ_DATA_BY_ID, *didBytes))
                if (resp.firstOrNull() == 0x62.toByte() && resp.size > 3) {
                    val data = resp.drop(3).toByteArray()
                    val key = "0x%04X".format(did)
                    results[key] = data
                    Log.i(TAG, "DID $key = ${data.toHex()}")
                }
            } catch (_: Exception) { }
        }

        Log.i(TAG, "=== Discovery done: ${results.size}/${probeList.size} DIDs readable ===")
        return results
    }

    fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
    private fun byteArrayOf(vararg elements: Byte) = elements
}
