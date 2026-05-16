package com.gmc.digitalkey.ble.obd2

import android.util.Log

object GmVcimActivation {

    private const val TAG = "GmVcimActivation"

    // UDS service IDs (ISO 14229-1)
    private const val SVC_DIAGNOSTIC_SESSION = 0x10.toByte()
    private const val SVC_SECURITY_ACCESS    = 0x27.toByte()
    private const val SVC_WRITE_DATA_BY_ID   = 0x2E.toByte()
    private const val SVC_READ_DATA_BY_ID    = 0x22.toByte()
    private const val SVC_TESTER_PRESENT     = 0x3E.toByte()

    private const val SESSION_DEFAULT        = 0x01.toByte()
    private const val SESSION_EXTENDED       = 0x03.toByte()
    private const val SEC_SEED_L1            = 0x01.toByte()  // Level 1 — standard
    private const val SEC_SEED_L3            = 0x05.toByte()  // Level 3 — Ultium

    // Ultium platform (Sierra EV / Silverado EV 2024+) confirmed CAN addresses
    private const val ULTIUM_K73_ADDR = 0x252   // K73 VCIM telematics
    private const val ULTIUM_K56_ADDR = 0x25F   // K56 Serial Data Gateway

    // GM candidate BLE-enable DIDs — priority order, Ultium-specific first
    private val BLE_DID_CANDIDATES = listOf(
        byteArrayOf(0xF1.toByte(), 0xB0.toByte()), // Connectivity feature config (primary)
        byteArrayOf(0x02.toByte(), 0x1A.toByte()), // GM Bluetooth enable (Ultium confirmed)
        byteArrayOf(0xF1.toByte(), 0x8D.toByte()), // BLE Radio On/Off (ECU ID 0x8D)
        byteArrayOf(0xF1.toByte(), 0xA0.toByte()), // Module feature config 1
        byteArrayOf(0xF1.toByte(), 0xA1.toByte()), // Module feature config 2
        byteArrayOf(0x41.toByte(), 0x00.toByte()), // GM proprietary BLE state
        byteArrayOf(0x41.toByte(), 0x10.toByte()), // BLE pairing control
    )
    private val DID_BLE_ENABLE_VALUE = byteArrayOf(0x01)

    // Legacy OBD2 ECU address range (pre-Ultium)
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

    // --- K56 Gateway probe (Ultium) ---

    private suspend fun probeK56Gateway(manager: Obd2Manager, log: MutableList<StepResult>): Boolean {
        return try {
            val resp = manager.sendUds(ULTIUM_K56_ADDR,
                byteArrayOf(SVC_TESTER_PRESENT, 0x80.toByte()))
            val ok = resp.firstOrNull() == 0x7E.toByte()
            log += StepResult("K56 Gateway (0x${ULTIUM_K56_ADDR.toString(16)})", ok,
                if (ok) resp.toHex() else "No response — may need CAN FD adapter")
            ok
        } catch (e: Exception) {
            log += StepResult("K56 Gateway (0x${ULTIUM_K56_ADDR.toString(16)})", false,
                "Timeout — ${e.message}")
            false
        }
    }

    // --- ECU Discovery ---

    suspend fun discoverEcus(manager: Obd2Manager,
                             stepLog: MutableList<StepResult> = mutableListOf()): List<Int> {
        Log.i(TAG, "=== ECU scan ===")
        val found = mutableListOf<Int>()

        // Ultium K73 first
        try {
            val resp = manager.sendUds(ULTIUM_K73_ADDR,
                byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_DEFAULT))
            if (resp.firstOrNull() == 0x50.toByte()) {
                found.add(ULTIUM_K73_ADDR)
                stepLog += StepResult("K73 Ultium (0x${ULTIUM_K73_ADDR.toString(16)})", true, "Present")
            } else {
                stepLog += StepResult("K73 Ultium (0x${ULTIUM_K73_ADDR.toString(16)})", false,
                    if (resp.isEmpty()) "No response" else resp.toHex())
            }
        } catch (_: Exception) {
            stepLog += StepResult("K73 Ultium (0x${ULTIUM_K73_ADDR.toString(16)})", false, "Timeout")
        }

        // Legacy OBD2 range
        val legacyFound = mutableListOf<Int>()
        for (addr in LEGACY_ECU_RANGE) {
            try {
                val resp = manager.sendUds(addr, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_DEFAULT))
                if (resp.firstOrNull() == 0x50.toByte()) {
                    found.add(addr); legacyFound.add(addr)
                }
            } catch (_: Exception) { }
        }
        if (legacyFound.isNotEmpty()) {
            stepLog += StepResult("Legacy ECUs (0x7E0–0x7E7)", true,
                legacyFound.joinToString(", ") { "0x${it.toString(16)}" })
        } else {
            stepLog += StepResult("Legacy ECUs (0x7E0–0x7E7)", false, "None found")
        }

        Log.i(TAG, "ECUs found: ${found.map { "0x${it.toString(16)}" }}")
        return found
    }

    // --- VCIM Identification ---

    suspend fun findVcimAddress(manager: Obd2Manager, ecus: List<Int>,
                                stepLog: MutableList<StepResult> = mutableListOf()): Int {
        if (ULTIUM_K73_ADDR in ecus) {
            stepLog += StepResult("VCIM address", true, "Ultium K73 at 0x${ULTIUM_K73_ADDR.toString(16)}")
            return ULTIUM_K73_ADDR
        }
        val candidates = if (ecus.isEmpty()) LEGACY_ECU_RANGE.toList() else ecus
        for (addr in candidates) {
            try {
                val sessResp = manager.sendUds(addr, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_EXTENDED))
                if (sessResp.firstOrNull() != 0x50.toByte()) continue
                for (did in BLE_DID_CANDIDATES) {
                    val resp = manager.sendUds(addr, byteArrayOf(SVC_READ_DATA_BY_ID, *did))
                    if (resp.firstOrNull() == 0x62.toByte()) {
                        stepLog += StepResult("VCIM address", true,
                            "0x${addr.toString(16)} — DID ${did.toHex()} readable")
                        return addr
                    }
                }
                val vinResp = manager.sendUds(addr, byteArrayOf(SVC_READ_DATA_BY_ID, 0xF1.toByte(), 0x90.toByte()))
                if (vinResp.firstOrNull() == 0x62.toByte()) {
                    stepLog += StepResult("VCIM address", true, "0x${addr.toString(16)} — VIN DID readable")
                    return addr
                }
            } catch (_: Exception) { }
        }
        val fallback = if (ecus.isEmpty()) 0x7E3 else ecus.first()
        stepLog += StepResult("VCIM address", false, "Not identified — using 0x${fallback.toString(16)}")
        Log.w(TAG, "VCIM not identified — defaulting to 0x${fallback.toString(16)}")
        return fallback
    }

    // --- BLE Activation ---

    suspend fun activateDigitalKeyBle(manager: Obd2Manager, vcimAddress: Int,
                                      stepLog: MutableList<StepResult> = mutableListOf()): ActivationResult {
        return try {
            val isUltium = vcimAddress == ULTIUM_K73_ADDR

            // 1. K56 gateway ping (Ultium only)
            if (isUltium) probeK56Gateway(manager, stepLog)

            // 2. Extended diagnostic session
            val sessionResp = manager.sendUds(vcimAddress,
                byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_EXTENDED))
            val sessionOk = sessionResp.firstOrNull() == 0x50.toByte()
            stepLog += StepResult("ExtendedSession 0x${vcimAddress.toString(16)}", sessionOk,
                if (sessionOk) "Accepted" else "Rejected: ${sessionResp.toHex()}")
            if (!sessionOk) {
                val hint = if (isUltium)
                    "\n\nUltium K73 detected. ELM327 supports Classic CAN only — " +
                    "Ultium diagnostic buses use CAN FD. A CAN FD adapter (PCAN-USB FD) may be required."
                else "\n\nEnsure vehicle ignition is ON."
                return ActivationResult.CommunicationError(
                    "VCIM 0x${vcimAddress.toString(16)} rejected extended session " +
                    "(${sessionResp.toHex()}).$hint")
            }

            // 3. Write DID candidates without SecurityAccess
            var needsSecurity = false
            for (did in BLE_DID_CANDIDATES) {
                val writeResp = manager.sendUds(vcimAddress,
                    byteArrayOf(SVC_WRITE_DATA_BY_ID, *did, *DID_BLE_ENABLE_VALUE))
                val nrc = writeResp.getOrNull(2)
                when {
                    writeResp.firstOrNull() == 0x6E.toByte() -> {
                        stepLog += StepResult("Write DID ${did.toHex()}", true, "BLE enabled!")
                        Log.i(TAG, "BLE enabled via DID ${did.toHex()}")
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

            // 4. SecurityAccess — Level 3 first for Ultium, Level 1 first for legacy
            val seedLevels = if (isUltium) listOf(SEC_SEED_L3, SEC_SEED_L1)
                             else          listOf(SEC_SEED_L1, SEC_SEED_L3)
            for (level in seedLevels) {
                val levelName = if (level == SEC_SEED_L3) "L3/Ultium (0x05)" else "L1/standard (0x01)"
                val seedResp = manager.sendUds(vcimAddress, byteArrayOf(SVC_SECURITY_ACCESS, level))
                val seedOk = seedResp.size >= 4 && seedResp[0] == 0x67.toByte()
                if (seedOk) {
                    val seed = seedResp.drop(2).take(8).toByteArray()
                    stepLog += StepResult("SecurityAccess $levelName", true, "Seed: ${seed.toHex()}")
                    Log.w(TAG, "VCIM seed $levelName: ${seed.toHex()}")
                    return ActivationResult.NeedsSecurityKey(vcimAddress, seed)
                } else {
                    stepLog += StepResult("SecurityAccess $levelName", false,
                        if (seedResp.isEmpty()) "No response" else seedResp.toHex())
                }
            }

            // 5. DID discovery for research
            val dids = discoverVcimDids(manager, vcimAddress, stepLog)
            if (dids.isEmpty() && isUltium) {
                return ActivationResult.CommunicationError(
                    "No DIDs readable from K73 at 0x${ULTIUM_K73_ADDR.toString(16)}.\n\n" +
                    "The Ultium platform uses CAN FD on diagnostic buses.\n" +
                    "The ELM327 adapter supports Classic CAN 2.0 only.\n\n" +
                    "A CAN FD-capable interface is required:\n" +
                    "• PCAN-USB FD  • Kvaser USBcan Light v2  • GM MDI 2"
                )
            }
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
        Log.i(TAG, "=== DID discovery on 0x${vcimAddress.toString(16)} ===")
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
        Log.i(TAG, "=== Discovery done: ${results.size}/${probeList.size} DIDs readable ===")
        return results
    }

    fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
    private fun byteArrayOf(vararg elements: Byte) = elements
}
