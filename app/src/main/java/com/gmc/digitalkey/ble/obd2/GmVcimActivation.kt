package com.gmc.digitalkey.ble.obd2

import android.util.Log

object GmVcimActivation {

    private const val TAG = "GmVcimActivation"

    private const val SVC_DIAGNOSTIC_SESSION = 0x10.toByte()
    private const val SVC_SECURITY_ACCESS    = 0x27.toByte()
    private const val SVC_WRITE_DATA_BY_ID   = 0x2E.toByte()
    private const val SVC_READ_DATA_BY_ID    = 0x22.toByte()

    private const val SESSION_EXTENDED       = 0x03.toByte()
    private const val SESSION_DEFAULT        = 0x01.toByte()
    private const val SEC_SEED_L1            = 0x01.toByte()
    private const val SEC_KEY_L1             = 0x02.toByte()
    private const val SEC_SEED_L3            = 0x05.toByte()
    private const val SEC_KEY_L3             = 0x06.toByte()

    // F1A0 confirmed writable on 0x45 (NRC 0x22 = exists but needs SecurityAccess)
    private val BLE_DID_CANDIDATES = listOf(
        byteArrayOf(0xF1.toByte(), 0xA0.toByte()), // Confirmed: exists + writable after auth
        byteArrayOf(0x02.toByte(), 0x1A.toByte()), // GM Bluetooth enable (Ultium)
        byteArrayOf(0x41.toByte(), 0x00.toByte()), // GM proprietary BLE state
        byteArrayOf(0x41.toByte(), 0x10.toByte()), // BLE pairing control
        byteArrayOf(0xF1.toByte(), 0xB0.toByte()), // Connectivity feature config
        byteArrayOf(0xF1.toByte(), 0x8D.toByte()), // BLE Radio On/Off
        byteArrayOf(0xF1.toByte(), 0xA1.toByte()), // Module feature config 2
    )
    private val DID_BLE_ENABLE_VALUE = byteArrayOf(0x01)

    private val LEGACY_ECU_RANGE = 0x7E0..0x7E7

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
        if (manager.use29BitCan) {
            val ids = manager.discoverEcuIds29Bit()
            stepLog += StepResult(
                "ECU scan (29-bit broadcast 18DB33F1)", ids.isNotEmpty(),
                if (ids.isEmpty()) "No ECUs responded" else "ECU IDs: ${ids.map { "0x%02X".format(it) }}"
            )
            return ids
        }
        val found = mutableListOf<Int>()
        for (addr in LEGACY_ECU_RANGE) {
            try {
                val r = manager.sendUds(addr, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_DEFAULT))
                if (r.firstOrNull() == 0x50.toByte()) found.add(addr)
            } catch (_: Exception) { }
        }
        stepLog += StepResult("ECU scan (11-bit 0x7E0–0x7E7)", found.isNotEmpty(),
            if (found.isNotEmpty()) found.joinToString { "0x${it.toString(16)}" } else "None found")
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
                        val addrStr = if (manager.use29BitCan) "0x%02X".format(addr) else "0x${addr.toString(16)}"
                        stepLog += StepResult("VCIM address", true, "$addrStr — DID ${did.toHex()} readable")
                        return addr
                    }
                }
                // Fall back to F1B0 (ECU ID DID — any module has it)
                val f1b0 = manager.sendUds(addr, byteArrayOf(SVC_READ_DATA_BY_ID, 0xF1.toByte(), 0xB0.toByte()))
                if (f1b0.firstOrNull() == 0x62.toByte()) {
                    val addrStr = if (manager.use29BitCan) "0x%02X".format(addr) else "0x${addr.toString(16)}"
                    stepLog += StepResult("VCIM address", true, "$addrStr — DID F1 B0 readable")
                    return addr
                }
            } catch (_: Exception) { }
        }

        val fallback = if (manager.use29BitCan) 0x45 else 0x7E3
        stepLog += StepResult("VCIM address", false,
            "Not identified — using fallback ${if (manager.use29BitCan) "0x%02X".format(fallback) else "0x${fallback.toString(16)}"}")
        return fallback
    }

    // --- BLE Activation ---

    suspend fun activateDigitalKeyBle(manager: Obd2Manager, vcimAddress: Int,
                                      stepLog: MutableList<StepResult> = mutableListOf()): ActivationResult {
        return try {
            val addrStr = if (manager.use29BitCan) "0x%02X".format(vcimAddress) else "0x${vcimAddress.toString(16)}"

            // 1. Extended diagnostic session
            val sessionResp = manager.sendUds(vcimAddress, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_EXTENDED))
            val sessionOk = sessionResp.firstOrNull() == 0x50.toByte()
            stepLog += StepResult("ExtendedSession $addrStr", sessionOk,
                if (sessionOk) "Accepted"
                else "Rejected: ${sessionResp.toHex()} — ensure ignition is ON")
            if (!sessionOk) {
                return ActivationResult.CommunicationError(
                    "VCIM $addrStr rejected extended session.\n\nEnsure vehicle ignition is ON.")
            }

            // 2. Try DID writes without SecurityAccess
            for (did in BLE_DID_CANDIDATES) {
                val writeResp = manager.sendUds(vcimAddress,
                    byteArrayOf(SVC_WRITE_DATA_BY_ID, *did, *DID_BLE_ENABLE_VALUE))
                val nrc = writeResp.getOrNull(2)
                when {
                    writeResp.firstOrNull() == 0x6E.toByte() -> {
                        stepLog += StepResult("Write DID ${did.toHex()}", true, "BLE enabled!")
                        return ActivationResult.Success(vcimAddress)
                    }
                    nrc == 0x33.toByte() ->
                        stepLog += StepResult("Write DID ${did.toHex()}", false, "NRC 0x33 — SecurityAccess required")
                    nrc == 0x22.toByte() ->
                        stepLog += StepResult("Write DID ${did.toHex()}", false, "NRC 0x22 — conditions not met (needs SecurityAccess)")
                    nrc == 0x31.toByte() ->
                        stepLog += StepResult("Write DID ${did.toHex()}", false, "NRC 0x31 — DID not supported")
                    else ->
                        stepLog += StepResult("Write DID ${did.toHex()}", false,
                            if (writeResp.isEmpty()) "No response" else writeResp.toHex())
                }
            }

            // 3. SecurityAccess — Level 1, then Level 3 fallback
            for ((seedLevel, keyLevel) in listOf(SEC_SEED_L1 to SEC_KEY_L1, SEC_SEED_L3 to SEC_KEY_L3)) {
                val levelStr = if (seedLevel == SEC_SEED_L3) "L3 (0x05)" else "L1 (0x01)"

                // Re-open session in case it timed out
                manager.sendUds(vcimAddress, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_EXTENDED))

                val seedResp = manager.sendUds(vcimAddress, byteArrayOf(SVC_SECURITY_ACCESS, seedLevel))
                if (seedResp.firstOrNull() != 0x67.toByte() || seedResp.size < 3) {
                    stepLog += StepResult("SecurityAccess $levelStr seed", false,
                        if (seedResp.isEmpty()) "No response" else seedResp.toHex())
                    continue
                }

                val fullSeed = seedResp.drop(2).toByteArray()
                val seed4    = fullSeed.take(4).toByteArray()
                stepLog += StepResult("SecurityAccess $levelStr seed", true,
                    "Seed: ${seed4.toHex()} (full ${fullSeed.size}B)")
                Log.i(TAG, "SecurityAccess $levelStr seed (full): ${fullSeed.toHex()}")

                // Try computed key candidates — STOP immediately on lockout (NRC 0x36)
                val keyCandidates = computeSecurityKeyCandidates(seed4)
                var unlocked = false

                for ((algIdx, key) in keyCandidates.withIndex()) {
                    val keyResp = manager.sendUds(vcimAddress,
                        byteArrayOf(SVC_SECURITY_ACCESS, keyLevel, *key))
                    val nrc = keyResp.getOrNull(2)
                    when {
                        keyResp.firstOrNull() == 0x67.toByte() -> {
                            stepLog += StepResult("SecurityAccess $levelStr unlock (alg${algIdx + 1})", true,
                                "Unlocked! Key: ${key.toHex()}")
                            unlocked = true
                        }
                        nrc == 0x36.toByte() -> {
                            stepLog += StepResult("SecurityAccess $levelStr unlock", false,
                                "LOCKED OUT (0x36) — wait 10 min before retry")
                            return ActivationResult.CommunicationError(
                                "SecurityAccess locked out — too many wrong keys.\n\nWait 10 minutes then retry.")
                        }
                        else -> {
                            val nrcStr = nrc?.let { "NRC 0x%02X".format(it) } ?: keyResp.toHex()
                            stepLog += StepResult("SecurityAccess $levelStr key alg${algIdx + 1}", false,
                                "$nrcStr — tried: ${key.toHex()}")
                        }
                    }
                    if (unlocked) break
                }

                if (unlocked) {
                    // Retry DID writes with SecurityAccess unlocked
                    for (did in BLE_DID_CANDIDATES) {
                        // For F1A0: read current value first, set BLE bit, write back
                        val writeValue: ByteArray = if (did.contentEquals(byteArrayOf(0xF1.toByte(), 0xA0.toByte()))) {
                            val readR = manager.sendUds(vcimAddress, byteArrayOf(SVC_READ_DATA_BY_ID, *did))
                            if (readR.firstOrNull() == 0x62.toByte() && readR.size > 3) {
                                val curr = readR.drop(3).toByteArray()
                                stepLog += StepResult("Read DID F1A0", true, "Current: ${curr.toHex()}")
                                curr.copyOf().also { it[0] = (it[0].toInt() or 0x01).toByte() }
                            } else DID_BLE_ENABLE_VALUE
                        } else DID_BLE_ENABLE_VALUE

                        val writeResp = manager.sendUds(vcimAddress,
                            byteArrayOf(SVC_WRITE_DATA_BY_ID, *did, *writeValue))
                        val nrc = writeResp.getOrNull(2)
                        when {
                            writeResp.firstOrNull() == 0x6E.toByte() -> {
                                stepLog += StepResult("Write DID ${did.toHex()} (unlocked)", true,
                                    "BLE enabled! Value: ${writeValue.toHex()}")
                                return ActivationResult.Success(vcimAddress)
                            }
                            else -> stepLog += StepResult("Write DID ${did.toHex()} (unlocked)", false,
                                nrc?.let { "NRC 0x%02X".format(it) } ?: writeResp.toHex())
                        }
                    }
                    // Unlocked but no DID worked — return full seed for further research
                    stepLog += StepResult("BLE activation", false,
                        "Security unlocked but no candidate DID enabled BLE advertising")
                    return ActivationResult.NeedsSecurityKey(vcimAddress, fullSeed)
                }

                // Wrong key(s) — return seed for external research / manual key entry
                return ActivationResult.NeedsSecurityKey(vcimAddress, fullSeed)
            }

            // 4. DID discovery (research mode)
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
                    results["0x%04X".format(did)] = data
                    Log.i(TAG, "DID 0x%04X = ${data.toHex()}".format(did))
                }
            } catch (_: Exception) { }
        }
        stepLog += StepResult("DID discovery", results.isNotEmpty(),
            "${results.size}/${probeList.size} DIDs readable")
        return results
    }

    // --- SecurityAccess Key Computation ---

    // Returns key candidates for the 4-byte seed, in order of likelihood.
    // Max 2 candidates to stay below GM's typical 3-attempt lockout threshold.
    private fun computeSecurityKeyCandidates(seed4: ByteArray): List<ByteArray> {
        val s = seed4.toLong32()
        return listOf(
            gmLfsrKey(s).toBytes4(),                                    // GM GMLAN LFSR
            seed4.map { (it.toInt().inv() and 0xFF).toByte() }.toByteArray()  // bitwise NOT
        )
    }

    // GM GMLAN Level 1 SecurityAccess LFSR (taps: 0, 1, 21, 31; mask: 0x04040404)
    // Used in BCM, VCIM, and other GMLAN modules from Delphi/Continental.
    private fun gmLfsrKey(seed: Long): Long {
        var k = seed and 0xFFFFFFFFL
        repeat(35) {
            val bit = (k xor (k ushr 1) xor (k ushr 21) xor (k ushr 31)) and 1L
            k = ((k ushr 1) or (bit shl 31)) and 0xFFFFFFFFL
        }
        return (k xor 0x04040404L) and 0xFFFFFFFFL
    }

    private fun ByteArray.toLong32(): Long =
        ((getOrElse(0) { 0 }.toLong() and 0xFF) shl 24) or
        ((getOrElse(1) { 0 }.toLong() and 0xFF) shl 16) or
        ((getOrElse(2) { 0 }.toLong() and 0xFF) shl 8) or
        (getOrElse(3) { 0 }.toLong() and 0xFF)

    private fun Long.toBytes4(): ByteArray = byteArrayOf(
        ((this ushr 24) and 0xFF).toByte(),
        ((this ushr 16) and 0xFF).toByte(),
        ((this ushr 8) and 0xFF).toByte(),
        (this and 0xFF).toByte()
    )

    fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
    private fun byteArrayOf(vararg elements: Byte) = elements
}
