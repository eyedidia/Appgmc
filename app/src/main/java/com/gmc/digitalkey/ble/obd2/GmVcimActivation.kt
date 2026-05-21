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
    private const val SEC_SEED_L1            = 0x01.toByte()
    private const val SEC_SEED_L3            = 0x05.toByte()

    // GM candidate BLE-enable DIDs — priority order
    private val BLE_DID_CANDIDATES = listOf(
        byteArrayOf(0xF1.toByte(), 0xB0.toByte()), // Connectivity feature config (primary)
        byteArrayOf(0x02.toByte(), 0x1A.toByte()), // GM Bluetooth enable (Ultium)
        byteArrayOf(0xF1.toByte(), 0x8D.toByte()), // BLE Radio On/Off
        byteArrayOf(0xF1.toByte(), 0xA0.toByte()), // Module feature config 1
        byteArrayOf(0xF1.toByte(), 0xA1.toByte()), // Module feature config 2
        byteArrayOf(0x41.toByte(), 0x00.toByte()), // GM proprietary BLE state
        byteArrayOf(0x41.toByte(), 0x10.toByte()), // BLE pairing control
    )
    private val DID_BLE_ENABLE_VALUE = byteArrayOf(0x01)

    // Legacy 11-bit CAN address range (pre-Ultium)
    private val LEGACY_ECU_RANGE = 0x7E0..0x7E7

    // --- Result types ---

    sealed class ActivationResult {
        data class Success(val vcimAddress: Int) : ActivationResult()
        data class NeedsSecurityKey(val vcimAddress: Int, val seed: ByteArray) : ActivationResult()
        object UnsupportedModel : ActivationResult()
        data class CommunicationError(val detail: String) : ActivationResult()
        data class DiagnosticData(val vcimAddress: Int, val readDids: Map<String, ByteArray>) : ActivationResult()
    }

    data class StepResult(val name: String, val ok: Boolean, val detail: String = "")

    // --- ECU Discovery ---

    suspend fun discoverEcus(manager: Obd2Manager,
                             stepLog: MutableList<StepResult> = mutableListOf()): List<Int> {
        Log.i(TAG, "=== ECU scan (${if (manager.use29BitCan) "29-bit" else "11-bit"}) ===")

        if (manager.use29BitCan) {
            // 29-bit: functional broadcast — all responding ECUs reply at once
            val ids = manager.discoverEcuIds29Bit()
            stepLog += StepResult(
                "ECU scan (29-bit broadcast 18DB33F1)", ids.isNotEmpty(),
                if (ids.isEmpty()) "No ECUs responded to functional broadcast"
                else "ECU IDs: ${ids.map { "0x%02X".format(it) }}"
            )
            Log.i(TAG, "29-bit ECUs found: ${ids.map { "0x%02X".format(it) }}")
            return ids
        }

        // Legacy 11-bit: probe 0x7E0–0x7E7
        val found = mutableListOf<Int>()
        for (addr in LEGACY_ECU_RANGE) {
            try {
                val resp = manager.sendUds(addr, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_DEFAULT))
                if (resp.firstOrNull() == 0x50.toByte()) {
                    found.add(addr)
                    Log.i(TAG, "ECU at 0x${addr.toString(16)}")
                }
            } catch (_: Exception) { }
        }
        if (found.isNotEmpty()) {
            stepLog += StepResult("ECU scan (11-bit 0x7E0–0x7E7)", true,
                found.joinToString(", ") { "0x${it.toString(16)}" })
        } else {
            stepLog += StepResult("ECU scan (11-bit 0x7E0–0x7E7)", false, "None found")
        }
        return found
    }

    // --- VCIM Identification ---

    suspend fun findVcimAddress(manager: Obd2Manager, ecus: List<Int>,
                                stepLog: MutableList<StepResult> = mutableListOf()): Int {
        val candidates = when {
            ecus.isNotEmpty() -> {
                if (manager.use29BitCan) {
                    // Ultium platform: K73 VCIM is at 0x45 — always probe it first
                    val sorted = ecus.toMutableList()
                    if (sorted.remove(0x45)) sorted.add(0, 0x45)
                    sorted
                } else ecus
            }
            manager.use29BitCan -> listOf(0x45) + (0x10..0x7F).filter { it != 0x45 }
            else -> LEGACY_ECU_RANGE.toList()
        }

        for (addr in candidates) {
            try {
                val sessResp = manager.sendUds(addr, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_EXTENDED))
                if (sessResp.firstOrNull() != 0x50.toByte()) continue

                for (did in BLE_DID_CANDIDATES) {
                    val resp = manager.sendUds(addr, byteArrayOf(SVC_READ_DATA_BY_ID, *did))
                    if (resp.firstOrNull() == 0x62.toByte()) {
                        val addrStr = if (manager.use29BitCan) "0x%02X".format(addr)
                                      else "0x${addr.toString(16)}"
                        stepLog += StepResult("VCIM address", true,
                            "$addrStr — DID ${did.toHex()} readable")
                        return addr
                    }
                }

                val vinResp = manager.sendUds(addr, byteArrayOf(SVC_READ_DATA_BY_ID, 0xF1.toByte(), 0x90.toByte()))
                if (vinResp.firstOrNull() == 0x62.toByte()) {
                    val addrStr = if (manager.use29BitCan) "0x%02X".format(addr)
                                  else "0x${addr.toString(16)}"
                    stepLog += StepResult("VCIM address", true, "$addrStr — VIN DID readable")
                    return addr
                }
            } catch (_: Exception) { }
        }

        val fallback = if (manager.use29BitCan) 0x28 else 0x7E3
        stepLog += StepResult("VCIM address", false,
            "Not identified — using fallback ${if (manager.use29BitCan) "0x%02X".format(fallback) else "0x${fallback.toString(16)}"}")
        Log.w(TAG, "VCIM not identified — defaulting to ${fallback}")
        return fallback
    }

    // --- BLE Activation ---

    suspend fun activateDigitalKeyBle(manager: Obd2Manager, vcimAddress: Int,
                                      stepLog: MutableList<StepResult> = mutableListOf()): ActivationResult {
        return try {
            val addrStr = if (manager.use29BitCan) "0x%02X".format(vcimAddress)
                          else "0x${vcimAddress.toString(16)}"

            // 1. Extended diagnostic session
            val sessionResp = manager.sendUds(vcimAddress,
                byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_EXTENDED))
            val sessionOk = sessionResp.firstOrNull() == 0x50.toByte()
            stepLog += StepResult("ExtendedSession $addrStr", sessionOk,
                if (sessionOk) "Accepted"
                else "Rejected: ${sessionResp.toHex()} — ensure ignition is ON")
            if (!sessionOk) {
                return ActivationResult.CommunicationError(
                    "VCIM $addrStr rejected extended session (${sessionResp.toHex()}).\n\n" +
                    "Ensure vehicle ignition is ON."
                )
            }

            // 2. Write DID candidates without SecurityAccess
            var needsSecurity = false
            for (did in BLE_DID_CANDIDATES) {
                val writeResp = manager.sendUds(vcimAddress,
                    byteArrayOf(SVC_WRITE_DATA_BY_ID, *did, *DID_BLE_ENABLE_VALUE))
                val nrc = writeResp.getOrNull(2)
                when {
                    writeResp.firstOrNull() == 0x6E.toByte() -> {
                        stepLog += StepResult("Write DID ${did.toHex()}", true, "BLE enabled!")
                        Log.i(TAG, "BLE enabled via DID ${did.toHex()} on $addrStr")
                        return ActivationResult.Success(vcimAddress)
                    }
                    nrc == 0x33.toByte() -> {
                        stepLog += StepResult("Write DID ${did.toHex()}", false, "NRC 0x33 — SecurityAccess required")
                        needsSecurity = true; break
                    }
                    nrc == 0x22.toByte() ->
                        stepLog += StepResult("Write DID ${did.toHex()}", false, "NRC 0x22 — conditions not met")
                    nrc == 0x31.toByte() ->
                        stepLog += StepResult("Write DID ${did.toHex()}", false, "NRC 0x31 — DID not supported")
                    else ->
                        stepLog += StepResult("Write DID ${did.toHex()}", false,
                            if (writeResp.isEmpty()) "No response" else writeResp.toHex())
                }
            }

            // 3. SecurityAccess — Level 1 then Level 3
            for (level in listOf(SEC_SEED_L1, SEC_SEED_L3)) {
                val levelName = if (level == SEC_SEED_L3) "L3 (0x05)" else "L1 (0x01)"
                val seedResp = manager.sendUds(vcimAddress, byteArrayOf(SVC_SECURITY_ACCESS, level))
                val seedOk = seedResp.size >= 4 && seedResp[0] == 0x67.toByte()
                if (seedOk) {
                    val seed = seedResp.drop(2).take(8).toByteArray()
                    stepLog += StepResult("SecurityAccess $levelName seed", true, "Seed: ${seed.toHex()}")
                    Log.w(TAG, "VCIM seed $levelName: ${seed.toHex()}")
                    return ActivationResult.NeedsSecurityKey(vcimAddress, seed)
                } else {
                    stepLog += StepResult("SecurityAccess $levelName seed", false,
                        if (seedResp.isEmpty()) "No response" else seedResp.toHex())
                }
            }

            // 4. DID discovery for research
            val dids = discoverVcimDids(manager, vcimAddress, stepLog)
            ActivationResult.DiagnosticData(vcimAddress, dids)

        } catch (e: Exception) {
            Log.e(TAG, "Activation error", e)
            stepLog += StepResult("Activation", false, e.message ?: "Unknown error")
            ActivationResult.CommunicationError(e.message ?: "Unknown error")
        }
    }

    // --- DID Discovery ---

    suspend fun discoverVcimDids(manager: Obd2Manager, vcimAddress: Int,
                                 stepLog: MutableList<StepResult> = mutableListOf()): Map<String, ByteArray> {
        Log.i(TAG, "=== DID discovery on ${vcimAddress} ===")
        val results = mutableMapOf<String, ByteArray>()
        val probeList = listOf(
            0xF190, 0xF18C, 0xF100, 0xF101, 0xF18A, 0xF18B, 0xF195,
            0xF1A0, 0xF1A1, 0xF1A2, 0xF1A3,
            0xF1B0, 0xF1B1, 0xF1B2, 0xF1B3,
            0xF18D, 0x021A, 0x021E,
            0x4100, 0x4101, 0x4102, 0x4103,
            0x4110, 0x4111, 0x4112, 0x4113,
            0x4120, 0x4121, 0x4130,
            0x4200, 0x4201,
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
        stepLog += StepResult("DID discovery", results.isNotEmpty(),
            "${results.size}/${probeList.size} DIDs readable")
        Log.i(TAG, "=== Discovery done: ${results.size}/${probeList.size} ===")
        return results
    }

    fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
    private fun byteArrayOf(vararg elements: Byte) = elements
}
