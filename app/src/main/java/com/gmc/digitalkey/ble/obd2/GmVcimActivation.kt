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
        byteArrayOf(0xF1.toByte(), 0xC0.toByte()), // Connected Services feature flags
        byteArrayOf(0xF1.toByte(), 0xC1.toByte()), // Connected Services feature flags 2
        byteArrayOf(0x02.toByte(), 0x1C.toByte()), // GM BLE pairing enable (Ultium alt)
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

            // 3. SecurityAccess — request a FRESH seed before every key attempt.
            // After any failed key (NRC 0x13/0x35/0x24), the ECU resets its SecurityAccess
            // state and will return NRC 0x24 (sequence error) to any subsequent key without
            // a new seed request. Re-request session + seed before each attempt.
            var unlocked = false
            var lastFullSeed = ByteArray(0)

            // L1 key algorithms (seed typically 32 bytes on Ultium AES-128 challenge)
            val keyAlgorithmsL1 = listOf(
                // Confirmed: 32-byte seed requires 32-byte key (NRC 0x13 on 4-byte)
                "32B-NOT"       to { seed: ByteArray -> seed.map { (it.toInt().inv() and 0xFF).toByte() }.toByteArray() },
                "16B-NOT-nonce" to { seed: ByteArray ->
                    if (seed.size >= 32) seed.drop(16).take(16).map { (it.toInt().inv() and 0xFF).toByte() }.toByteArray()
                    else seed.map { (it.toInt().inv() and 0xFF).toByte() }.toByteArray()
                },
                "32B-XOR-A5"    to { seed: ByteArray -> seed.map { (it.toInt() xor 0xA5 and 0xFF).toByte() }.toByteArray() },
                "16B-XOR-nonce" to { seed: ByteArray ->
                    if (seed.size >= 32) seed.drop(16).take(16).map { (it.toInt() xor 0xA5 and 0xFF).toByte() }.toByteArray()
                    else seed.map { (it.toInt() xor 0xA5 and 0xFF).toByte() }.toByteArray()
                },
            )

            for ((algName, algFn) in keyAlgorithmsL1) {
                // Re-open extended session (may have timed out between attempts)
                manager.sendUds(vcimAddress, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_EXTENDED))

                // Request a fresh seed — CRITICAL: each attempt needs its own seed
                val seedR = manager.sendUds(vcimAddress, byteArrayOf(SVC_SECURITY_ACCESS, SEC_SEED_L1))
                if (seedR.firstOrNull() != 0x67.toByte() || seedR.size < 3) {
                    stepLog += StepResult("SecurityAccess L1 seed ($algName)", false,
                        if (seedR.isEmpty()) "No response" else seedR.toHex())
                    continue
                }
                val freshSeed = seedR.drop(2).toByteArray()
                lastFullSeed = freshSeed
                if (algName == "32B-NOT") {
                    stepLog += StepResult("SecurityAccess L1 (0x01) seed", true,
                        "Seed: ${freshSeed.take(4).toByteArray().toHex()} (full ${freshSeed.size}B)")
                    Log.i(TAG, "Full L1 seed: ${freshSeed.toHex()}")
                }

                val key = algFn(freshSeed)
                val keyResp = manager.sendUds(vcimAddress,
                    byteArrayOf(SVC_SECURITY_ACCESS, SEC_KEY_L1, *key))
                val nrc = keyResp.getOrNull(2)
                when {
                    keyResp.firstOrNull() == 0x67.toByte() -> {
                        stepLog += StepResult("SecurityAccess L1 unlock ($algName, ${key.size}B)", true,
                            "Unlocked! Key: ${key.toHex()}")
                        unlocked = true
                    }
                    nrc == 0x36.toByte() -> {
                        stepLog += StepResult("SecurityAccess L1 unlock", false,
                            "LOCKED OUT (0x36) — wait 10 min before retry")
                        return ActivationResult.CommunicationError(
                            "SecurityAccess locked out — too many wrong keys.\n\nWait 10 minutes then retry.")
                    }
                    nrc == 0x13.toByte() ->
                        stepLog += StepResult("SecurityAccess L1 key $algName (${key.size}B)", false,
                            "NRC 0x13 — wrong key length for this ECU — tried: ${key.toHex()}")
                    nrc == 0x35.toByte() ->
                        stepLog += StepResult("SecurityAccess L1 key $algName (${key.size}B)", false,
                            "NRC 0x35 — wrong key value — tried: ${key.toHex()}")
                    else ->
                        stepLog += StepResult("SecurityAccess L1 key $algName (${key.size}B)", false,
                            "${nrc?.let { "NRC 0x%02X".format(it) } ?: keyResp.toHex()} — tried: ${key.toHex()}")
                }
                if (unlocked) break
            }

            // SecurityAccess Level 3 (0x05/0x06) — try after L1 fails.
            // L3 often uses a simpler 4-byte algorithm (LFSR or XOR) on older-style Ultium modules.
            if (!unlocked) {
                val keyAlgorithmsL3 = listOf(
                    "LFSR"      to { seed: ByteArray -> gmLfsrKey(seed.take(4).toByteArray().toLong32()).toBytes4() },
                    "XOR-A5"    to { seed: ByteArray -> seed.take(4).map { (it.toInt() xor 0xA5 and 0xFF).toByte() }.toByteArray() },
                    "4B-NOT"    to { seed: ByteArray -> seed.take(4).map { (it.toInt().inv() and 0xFF).toByte() }.toByteArray() },
                    "32B-NOT"   to { seed: ByteArray -> seed.map { (it.toInt().inv() and 0xFF).toByte() }.toByteArray() },
                    "32B-XOR-A5" to { seed: ByteArray -> seed.map { (it.toInt() xor 0xA5 and 0xFF).toByte() }.toByteArray() },
                )

                for ((algName, algFn) in keyAlgorithmsL3) {
                    manager.sendUds(vcimAddress, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_EXTENDED))
                    val seedR = manager.sendUds(vcimAddress, byteArrayOf(SVC_SECURITY_ACCESS, SEC_SEED_L3))
                    if (seedR.firstOrNull() != 0x67.toByte() || seedR.size < 3) {
                        // NRC 0x12 = subFunction not supported → L3 not available on this module
                        val nrcByte = seedR.getOrNull(2)
                        if (nrcByte == 0x12.toByte() || nrcByte == 0x31.toByte()) {
                            stepLog += StepResult("SecurityAccess L3 seed", false,
                                "NRC 0x%02X — Level 3 not supported on this ECU".format(nrcByte))
                            break
                        }
                        stepLog += StepResult("SecurityAccess L3 seed ($algName)", false,
                            if (seedR.isEmpty()) "No response" else seedR.toHex())
                        continue
                    }
                    val freshSeed = seedR.drop(2).toByteArray()
                    if (lastFullSeed.isEmpty()) lastFullSeed = freshSeed
                    if (algName == "LFSR") {
                        stepLog += StepResult("SecurityAccess L3 (0x05) seed", true,
                            "Seed: ${freshSeed.toHex()} (${freshSeed.size}B)")
                        Log.i(TAG, "Full L3 seed: ${freshSeed.toHex()}")
                    }

                    val key = algFn(freshSeed)
                    val keyResp = manager.sendUds(vcimAddress,
                        byteArrayOf(SVC_SECURITY_ACCESS, SEC_KEY_L3, *key))
                    val nrc = keyResp.getOrNull(2)
                    when {
                        keyResp.firstOrNull() == 0x67.toByte() -> {
                            stepLog += StepResult("SecurityAccess L3 unlock ($algName, ${key.size}B)", true,
                                "Unlocked! Key: ${key.toHex()}")
                            unlocked = true
                        }
                        nrc == 0x36.toByte() -> {
                            stepLog += StepResult("SecurityAccess L3 unlock", false, "LOCKED OUT (0x36)")
                            return ActivationResult.CommunicationError(
                                "SecurityAccess locked out — too many wrong keys.\n\nWait 10 minutes then retry.")
                        }
                        nrc == 0x22.toByte() ->
                            stepLog += StepResult("SecurityAccess L3 key $algName", false,
                                "NRC 0x22 — conditions not met (L3 requires prog session?)")
                        nrc == 0x13.toByte() ->
                            stepLog += StepResult("SecurityAccess L3 key $algName (${key.size}B)", false,
                                "NRC 0x13 — wrong key length — tried: ${key.toHex()}")
                        nrc == 0x35.toByte() ->
                            stepLog += StepResult("SecurityAccess L3 key $algName (${key.size}B)", false,
                                "NRC 0x35 — wrong key value — tried: ${key.toHex()}")
                        else ->
                            stepLog += StepResult("SecurityAccess L3 key $algName (${key.size}B)", false,
                                "${nrc?.let { "NRC 0x%02X".format(it) } ?: keyResp.toHex()} — tried: ${key.toHex()}")
                    }
                    if (unlocked) break
                }
            }

            if (unlocked) {
                // Retry DID writes with SecurityAccess unlocked
                for (did in BLE_DID_CANDIDATES) {
                    // For F1A0: read-modify-write — set bit 0 on current value
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
                    when {
                        writeResp.firstOrNull() == 0x6E.toByte() -> {
                            stepLog += StepResult("Write DID ${did.toHex()} (unlocked)", true,
                                "BLE enabled! Value: ${writeValue.toHex()}")
                            return ActivationResult.Success(vcimAddress)
                        }
                        else -> stepLog += StepResult("Write DID ${did.toHex()} (unlocked)", false,
                            writeResp.getOrNull(2)?.let { "NRC 0x%02X".format(it) } ?: writeResp.toHex())
                    }
                }
                stepLog += StepResult("BLE activation", false,
                    "SecurityAccess unlocked but no candidate DID enabled BLE")
            }

            if (lastFullSeed.isNotEmpty()) {
                return ActivationResult.NeedsSecurityKey(vcimAddress, lastFullSeed)
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
            0xF1C0, 0xF1C1, 0xF1C2, 0xF1C3,  // Connected Services feature flags
            0xF18D, 0x021A, 0x021C, 0x021D, 0x021E,  // 021C/021D: GM BLE enable variants
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
