package com.gmc.digitalkey.ble.obd2

import android.util.Log
import kotlinx.coroutines.delay

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
        byteArrayOf(0xF1.toByte(), 0x80.toByte()), // ECU serial / Radio feature config
        byteArrayOf(0xF1.toByte(), 0xD0.toByte()), // Digital key feature flag
        byteArrayOf(0x02.toByte(), 0x00.toByte()), // Module status bytes
    )
    private val DID_BLE_ENABLE_VALUE = byteArrayOf(0x01)

    private val LEGACY_ECU_RANGE = 0x7E0..0x7E7
    private const val ULTIUM_K73_ADDR = 0x252  // K73 VCIM on Ultium platform (11-bit CAN / ATSP6)

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
            val ids = manager.discoverEcuIds29Bit().toMutableList()
            stepLog += StepResult(
                "ECU scan (29-bit broadcast 18DB33F1)", ids.isNotEmpty(),
                if (ids.isEmpty()) "No ECUs responded" else "ECU IDs: ${ids.map { "0x%02X".format(it) }}"
            )
            // Probe known GM connectivity/VCIM addresses + broad sweep of 0x15–0x20 and
            // 0x40–0x50. On 2026+ Ultium, K73 may sit at a different address than 0x45.
            // ECU 0x28: returns NRC 0x11 on DefaultSession → retry with ExtendedSession.
            val directCandidates = buildList {
                addAll(listOf(0x45, 0x28, 0x10, 0x7D))          // known specific candidates
                addAll(0x40..0x50)                               // K73 range on Ultium 2026+
                addAll(listOf(0x15, 0x16, 0x17, 0x19, 0x1D, 0x20)) // seen in earlier vehicle scan
            }.distinct()
            val broadFound = mutableListOf<Int>()
            for (candidate in directCandidates) {
                if (candidate in ids) continue
                try {
                    var r = manager.sendUds(candidate, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_DEFAULT))
                    if (r.firstOrNull() == 0x50.toByte()) {
                        ids.add(candidate)
                        broadFound.add(candidate)
                    } else if (r.getOrNull(2) == 0x11.toByte()) {
                        // NRC 0x11 = SubFunctionNotSupported for DefaultSession — try ExtendedSession
                        r = try { manager.sendUds(candidate, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_EXTENDED)) }
                            catch (_: Exception) { byteArrayOf() }
                        if (r.firstOrNull() == 0x50.toByte()) {
                            ids.add(candidate)
                            broadFound.add(candidate)
                        }
                    }
                } catch (_: Exception) { }
            }
            if (broadFound.isNotEmpty()) {
                stepLog += StepResult("Broad probe (0x40–0x50 + known)", true,
                    "Found: ${broadFound.map { "0x%02X".format(it) }}")
            }
            // Probe Ultium K73 VCIM at 0x252 on 11-bit CAN.
            // Silverado EV / Sierra EV 2024+ route the VCIM through a sub-network not visible
            // in 29-bit ATSP7 mode. Try ATSP6 (500kbaud) first, then ATSP5 (250kbaud / MSCAN).
            if (ULTIUM_K73_ADDR !in ids) {
                val probeResult = probeUltiumVcim(manager, stepLog)
                if (probeResult) ids.add(ULTIUM_K73_ADDR)
            }
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
                    // Prioritize Ultium K73 (0x252) first, then 0x45, then rest
                    val sorted = ecus.toMutableList()
                    if (sorted.remove(0x45)) sorted.add(0, 0x45)
                    if (sorted.remove(ULTIUM_K73_ADDR)) sorted.add(0, ULTIUM_K73_ADDR)
                    sorted
                } else ecus
            }
            manager.use29BitCan -> listOf(ULTIUM_K73_ADDR, 0x45) + (0x10..0x7F).filter { it != 0x45 }
            else -> LEGACY_ECU_RANGE.toList()
        }

        for (addr in candidates) {
            try {
                // Ensure correct CAN mode for this address
                if (addr == ULTIUM_K73_ADDR && !manager.isUltiumMode) manager.enableUltiumMode()
                else if (addr != ULTIUM_K73_ADDR && manager.isUltiumMode) manager.disableUltiumMode()

                val sessResp = manager.sendUds(addr, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_EXTENDED))
                if (sessResp.firstOrNull() != 0x50.toByte()) continue

                for (did in BLE_DID_CANDIDATES) {
                    val resp = manager.sendUds(addr, byteArrayOf(SVC_READ_DATA_BY_ID, *did))
                    if (resp.firstOrNull() == 0x62.toByte()) {
                        val addrStr = if (addr >= 0x100) "0x%03X".format(addr) else "0x%02X".format(addr)
                        stepLog += StepResult("VCIM address", true, "$addrStr — DID ${did.toHex()} readable")
                        return addr
                    }
                }
                // Fall back to F1B0 (ECU ID DID — any module has it)
                val f1b0 = manager.sendUds(addr, byteArrayOf(SVC_READ_DATA_BY_ID, 0xF1.toByte(), 0xB0.toByte()))
                if (f1b0.firstOrNull() == 0x62.toByte()) {
                    val addrStr = if (addr >= 0x100) "0x%03X".format(addr) else "0x%02X".format(addr)
                    stepLog += StepResult("VCIM address", true, "$addrStr — DID F1 B0 readable")
                    return addr
                }
            } catch (_: Exception) { }
        }

        val fallback = if (manager.use29BitCan) ULTIUM_K73_ADDR else 0x7E3
        val detail = if (manager.use29BitCan)
            "K73 VCIM not found. Ensure vehicle is in READY or ACC mode (not just powered on) — " +
            "EVs have no ignition key; READY mode activates all CAN modules. If already in READY " +
            "mode, K73 may be at a different 29-bit address on this VIN."
        else "Not identified — using fallback 0x${fallback.toString(16)}"
        stepLog += StepResult("VCIM address", false, detail)
        if (fallback == ULTIUM_K73_ADDR && !manager.isUltiumMode) runCatching { manager.enableUltiumMode() }
        return fallback
    }

    // --- BLE Activation ---

    suspend fun activateDigitalKeyBle(manager: Obd2Manager, vcimAddress: Int,
                                      stepLog: MutableList<StepResult> = mutableListOf()): ActivationResult {
        return try {
            val addrStr = if (manager.use29BitCan) "0x%02X".format(vcimAddress) else "0x${vcimAddress.toString(16)}"

            // Ensure correct CAN mode before starting activation
            if (vcimAddress == ULTIUM_K73_ADDR && !manager.isUltiumMode) manager.enableUltiumMode()

            // 1. Extended diagnostic session — reset to DefaultSession first so the ECU transitions
            //    cleanly (some ECUs, e.g. 0x80, refuse to re-enter ExtendedSession from a stale
            //    session left open by findVcimAddress() without first going through DefaultSession).
            try {
                manager.sendUds(vcimAddress, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_DEFAULT))
                delay(500)
            } catch (_: Exception) { delay(500) }

            val sessionResp = manager.sendUds(vcimAddress, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_EXTENDED))
            val sessionOk = sessionResp.firstOrNull() == 0x50.toByte()
            stepLog += StepResult("ExtendedSession $addrStr", sessionOk,
                if (sessionOk) "Accepted"
                else "Rejected: ${sessionResp.toHex()} — ensure ignition is ON")
            if (!sessionOk) {
                return ActivationResult.CommunicationError(
                    "VCIM $addrStr rejected extended session.\n\nEnsure vehicle ignition is ON.")
            }

            // Identify the ECU so we know whether we're talking to the VCIM or another module
            stepLog += StepResult("ECU $addrStr identity", true, identifyEcu(manager, vcimAddress))

            // 2. Probe F1A0 only — all other DIDs return NRC 0x31 (not supported) on VCIM 0x45.
            //    A single write attempt tells us whether SecurityAccess is needed without
            //    wasting time on unsupported DIDs that risk timeouts (especially F1C1).
            val writeF1A0Pre = try {
                manager.sendUds(vcimAddress,
                    byteArrayOf(SVC_WRITE_DATA_BY_ID, 0xF1.toByte(), 0xA0.toByte(), *DID_BLE_ENABLE_VALUE))
            } catch (e: Exception) { byteArrayOf() }
            when {
                writeF1A0Pre.firstOrNull() == 0x6E.toByte() -> {
                    stepLog += StepResult("Write DID F1A0", true, "BLE enabled without SecurityAccess!")
                    return ActivationResult.Success(vcimAddress)
                }
                writeF1A0Pre.getOrNull(2) == 0x22.toByte() ->
                    stepLog += StepResult("Write DID F1A0", false, "NRC 0x22 — SecurityAccess required")
                writeF1A0Pre.getOrNull(2) == 0x33.toByte() ->
                    stepLog += StepResult("Write DID F1A0", false, "NRC 0x33 — SecurityAccess required")
                writeF1A0Pre.getOrNull(2) == 0x31.toByte() ->
                    stepLog += StepResult("Write DID F1A0", false, "NRC 0x31 — DID not found (wrong ECU?)")
                writeF1A0Pre.isEmpty() ->
                    stepLog += StepResult("Write DID F1A0", false, "No response")
                else ->
                    stepLog += StepResult("Write DID F1A0", false,
                        writeF1A0Pre.getOrNull(2)?.let { "NRC 0x%02X".format(it) } ?: writeF1A0Pre.toHex())
            }

            // 2b. RoutineControl (0x31) — some BLE activation paths are routines, not DID writes.
            //     Try before SecurityAccess since routines may not require SA.
            for ((routineId, label) in listOf(
                byteArrayOf(0xF1.toByte(), 0xA0.toByte()) to "F1A0 BLE-enable routine",
                byteArrayOf(0x02.toByte(), 0x01.toByte()) to "0201 telematics-BLE routine",
                byteArrayOf(0x04.toByte(), 0x01.toByte()) to "0401 connected-services routine",
                byteArrayOf(0xF1.toByte(), 0xC1.toByte()) to "F1C1 feature-flag routine",
            )) {
                try {
                    val r = manager.sendUds(vcimAddress,
                        byteArrayOf(0x31.toByte(), 0x01.toByte(), *routineId))
                    when {
                        r.firstOrNull() == 0x71.toByte() -> {
                            stepLog += StepResult("RoutineControl $label (pre-SA)", true,
                                "Routine started: ${r.toHex()}")
                            // Check if this flipped F1A0
                            val check = try {
                                manager.sendUds(vcimAddress,
                                    byteArrayOf(SVC_READ_DATA_BY_ID, 0xF1.toByte(), 0xA0.toByte()))
                            } catch (e: Exception) { byteArrayOf() }
                            if (check.firstOrNull() == 0x62.toByte() && check.size > 3) {
                                val v = check.drop(3).toByteArray()
                                stepLog += StepResult("Read F1A0 after routine", true, "Value: ${v.toHex()}")
                                if (v.firstOrNull() == 0x01.toByte())
                                    return ActivationResult.Success(vcimAddress)
                            }
                        }
                        r.getOrNull(2) == 0x33.toByte() || r.getOrNull(2) == 0x22.toByte() ->
                            stepLog += StepResult("RoutineControl $label (pre-SA)", false,
                                "NRC 0x%02X — SA required".format(r.getOrNull(2)))
                        else ->
                            stepLog += StepResult("RoutineControl $label (pre-SA)", false,
                                r.getOrNull(2)?.let { "NRC 0x%02X".format(it) } ?: if (r.isEmpty()) "No response" else r.toHex())
                    }
                } catch (e: Exception) {
                    stepLog += StepResult("RoutineControl $label (pre-SA)", false, "Timeout: ${e.message}")
                }
                delay(400)  // let ECU recover between failed routine attempts to avoid lockout
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

            // Let ECU recover after RoutineControl failures before attempting SecurityAccess
            delay(1_500)

            for ((algName, algFn) in keyAlgorithmsL1) {
                // Re-open extended session with retry — after RoutineControl failures the ECU
                // may stop responding temporarily; retry up to 3x with increasing delay.
                if (!reopenSession(manager, vcimAddress, algName, stepLog)) continue

                // Request a fresh seed — CRITICAL: each attempt needs its own seed
                val seedR = try {
                    manager.sendUds(vcimAddress, byteArrayOf(SVC_SECURITY_ACCESS, SEC_SEED_L1))
                } catch (e: Exception) {
                    stepLog += StepResult("SecurityAccess L1 seed ($algName)", false, "Timeout: ${e.message}")
                    continue
                }
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

            // SecurityAccess Level 2 (0x03/0x04) — try after L1 fails.
            // Level 2 may use a different (simpler) algorithm than L1.
            if (!unlocked) {
                val keyAlgorithmsL2 = listOf(
                    "LFSR"      to { seed: ByteArray -> gmLfsrKey(seed.take(4).toByteArray().toLong32()).toBytes4() },
                    "XOR-A5"    to { seed: ByteArray -> seed.take(4).map { (it.toInt() xor 0xA5 and 0xFF).toByte() }.toByteArray() },
                    "4B-NOT"    to { seed: ByteArray -> seed.take(4).map { (it.toInt().inv() and 0xFF).toByte() }.toByteArray() },
                    "32B-NOT"   to { seed: ByteArray -> seed.map { (it.toInt().inv() and 0xFF).toByte() }.toByteArray() },
                    "32B-XOR-A5" to { seed: ByteArray -> seed.map { (it.toInt() xor 0xA5 and 0xFF).toByte() }.toByteArray() },
                )
                for ((algName, algFn) in keyAlgorithmsL2) {
                    if (!reopenSession(manager, vcimAddress, "L2-$algName", stepLog)) continue
                    val seedR = try {
                        manager.sendUds(vcimAddress, byteArrayOf(SVC_SECURITY_ACCESS, 0x03.toByte()))
                    } catch (e: Exception) {
                        stepLog += StepResult("SecurityAccess L2 seed ($algName)", false, "Timeout: ${e.message}")
                        continue
                    }
                    if (seedR.firstOrNull() != 0x67.toByte() || seedR.size < 3) {
                        val nrcByte = seedR.getOrNull(2)
                        if (nrcByte == 0x12.toByte() || nrcByte == 0x31.toByte()) {
                            stepLog += StepResult("SecurityAccess L2 seed", false,
                                "NRC 0x%02X — Level 2 not supported".format(nrcByte))
                            break
                        }
                        stepLog += StepResult("SecurityAccess L2 seed ($algName)", false,
                            if (seedR.isEmpty()) "No response" else seedR.toHex())
                        continue
                    }
                    val freshSeed = seedR.drop(2).toByteArray()
                    if (lastFullSeed.isEmpty()) lastFullSeed = freshSeed
                    if (algName == "LFSR") {
                        stepLog += StepResult("SecurityAccess L2 (0x03) seed", true,
                            "Seed: ${freshSeed.toHex()} (${freshSeed.size}B)")
                        Log.i(TAG, "Full L2 seed: ${freshSeed.toHex()}")
                    }
                    val key = algFn(freshSeed)
                    val keyResp = manager.sendUds(vcimAddress,
                        byteArrayOf(SVC_SECURITY_ACCESS, 0x04.toByte(), *key))
                    val nrc = keyResp.getOrNull(2)
                    when {
                        keyResp.firstOrNull() == 0x67.toByte() -> {
                            stepLog += StepResult("SecurityAccess L2 unlock ($algName, ${key.size}B)", true,
                                "Unlocked! Key: ${key.toHex()}")
                            unlocked = true
                        }
                        nrc == 0x36.toByte() -> {
                            stepLog += StepResult("SecurityAccess L2 unlock", false, "LOCKED OUT (0x36)")
                            return ActivationResult.CommunicationError(
                                "SecurityAccess locked out — wait 10 minutes then retry.")
                        }
                        nrc == 0x13.toByte() ->
                            stepLog += StepResult("SecurityAccess L2 key $algName (${key.size}B)", false,
                                "NRC 0x13 — wrong key length — tried: ${key.toHex()}")
                        nrc == 0x35.toByte() ->
                            stepLog += StepResult("SecurityAccess L2 key $algName (${key.size}B)", false,
                                "NRC 0x35 — wrong key value — tried: ${key.toHex()}")
                        else ->
                            stepLog += StepResult("SecurityAccess L2 key $algName (${key.size}B)", false,
                                "${nrc?.let { "NRC 0x%02X".format(it) } ?: keyResp.toHex()} — tried: ${key.toHex()}")
                    }
                    if (unlocked) break
                }
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
                    if (!reopenSession(manager, vcimAddress, "L3-$algName", stepLog)) continue
                    val seedR = try {
                        manager.sendUds(vcimAddress, byteArrayOf(SVC_SECURITY_ACCESS, SEC_SEED_L3))
                    } catch (e: Exception) {
                        stepLog += StepResult("SecurityAccess L3 seed ($algName)", false, "Timeout: ${e.message}")
                        continue
                    }
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
                // Retry DID writes with SecurityAccess unlocked.
                // Each write is wrapped individually — a timeout on one DID won't abort the rest.
                for (did in BLE_DID_CANDIDATES) {
                    try {
                        // For F1A0: read-modify-write — set bit 0 on current value
                        val writeValue: ByteArray = if (did.contentEquals(byteArrayOf(0xF1.toByte(), 0xA0.toByte()))) {
                            val readR = try {
                                manager.sendUds(vcimAddress, byteArrayOf(SVC_READ_DATA_BY_ID, *did))
                            } catch (e: Exception) { byteArrayOf() }
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
                    } catch (e: Exception) {
                        stepLog += StepResult("Write DID ${did.toHex()} (unlocked)", false, "Timeout: ${e.message}")
                    }
                }

                // RoutineControl (0x31) — may activate BLE as a routine rather than a DID write
                for ((routineId, label) in listOf(
                    byteArrayOf(0xF1.toByte(), 0xA0.toByte()) to "31 F1A0 (BLE enable routine)",
                    byteArrayOf(0x02.toByte(), 0x01.toByte()) to "31 0201 (telematics BLE)",
                    byteArrayOf(0x04.toByte(), 0x01.toByte()) to "31 0401 (connected services)",
                    byteArrayOf(0xF1.toByte(), 0xC1.toByte()) to "31 F1C1 (feature flag routine)",
                )) {
                    try {
                        val routineResp = manager.sendUds(vcimAddress,
                            byteArrayOf(0x31.toByte(), 0x01.toByte(), *routineId))
                        when {
                            routineResp.firstOrNull() == 0x71.toByte() -> {
                                stepLog += StepResult("RoutineControl $label", true,
                                    "Started! Response: ${routineResp.toHex()}")
                                // After starting routine, try writing F1A0 again
                                val rw = try {
                                    manager.sendUds(vcimAddress,
                                        byteArrayOf(SVC_WRITE_DATA_BY_ID, 0xF1.toByte(), 0xA0.toByte(), 0x01))
                                } catch (e: Exception) { byteArrayOf() }
                                if (rw.firstOrNull() == 0x6E.toByte()) {
                                    stepLog += StepResult("Write F1A0 after routine", true, "BLE enabled!")
                                    return ActivationResult.Success(vcimAddress)
                                }
                            }
                            else -> stepLog += StepResult("RoutineControl $label", false,
                                routineResp.getOrNull(2)?.let { "NRC 0x%02X".format(it) } ?: routineResp.toHex())
                        }
                    } catch (e: Exception) {
                        stepLog += StepResult("RoutineControl $label", false, "Timeout: ${e.message}")
                    }
                }

                stepLog += StepResult("BLE activation", false,
                    "SecurityAccess unlocked but no DID/routine enabled BLE")
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
            // Standard GM/ISO identity DIDs
            0xF190, 0xF18C, 0xF100, 0xF101, 0xF18A, 0xF18B, 0xF195,
            // Feature config range F180–F18F (BLE, telematics, connectivity flags)
            0xF180, 0xF181, 0xF182, 0xF183, 0xF184, 0xF185, 0xF186, 0xF187,
            0xF188, 0xF189, 0xF18D, 0xF18E, 0xF18F,
            // BLE / activation DIDs
            0xF1A0, 0xF1A1, 0xF1A2, 0xF1A3,
            0xF1B0, 0xF1B1, 0xF1B2, 0xF1B3,
            0xF1C0, 0xF1C1, 0xF1C2, 0xF1C3,
            // GM Ultium connectivity IDs (021x range)
            0x021A, 0x021C, 0x021D, 0x021E,
            0x020C, 0x020A, 0x0213, 0x0216,
            // Proprietary BLE state DIDs
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

    // --- Session Retry Helper ---

    // Re-opens extended session before each SA attempt; returns false if ECU doesn't respond
    // after 3 retries (ECU is in lockout/cooldown after RoutineControl failures).
    private suspend fun reopenSession(
        manager: Obd2Manager, addr: Int, label: String,
        stepLog: MutableList<StepResult>
    ): Boolean {
        // Reset to DefaultSession first — prevents ECU lockout state from blocking ExtendedSession
        try {
            manager.sendUds(addr, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_DEFAULT))
            delay(300)
        } catch (_: Exception) { delay(300) }
        for (retry in 0..2) {
            try {
                if (retry > 0) delay(800L * retry)
                val r = manager.sendUds(addr, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_EXTENDED))
                if (r.firstOrNull() == 0x50.toByte()) return true
            } catch (_: Exception) { }
        }
        stepLog += StepResult("SA re-open session ($label)", false, "ECU not responding after 3 retries")
        return false
    }

    // --- Ultium VCIM Probe ---

    // Probes K73 VCIM at 0x252 using both 500kbaud (ATSP6) and 250kbaud (ATSP5) 11-bit CAN.
    // Returns true if found and leaves isUltiumMode=true. Returns false and restores 29-bit.
    private suspend fun probeUltiumVcim(manager: Obd2Manager, stepLog: MutableList<StepResult>): Boolean {
        // enableUltiumMode() sets isUltiumMode=true so sendUds(0x252,...) uses "ATSH 252" format
        try {
            manager.enableUltiumMode()
            val r = manager.sendUds(ULTIUM_K73_ADDR, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_DEFAULT))
            if (r.firstOrNull() == 0x50.toByte()) {
                stepLog += StepResult("Ultium K73 VCIM (0x252)", true, "Found on 11-bit CAN 500kbaud (ATSP6)")
                return true
            }
            stepLog += StepResult("Ultium K73 VCIM (0x252) ATSP6", false, "No response at 500kbaud")
        } catch (e: Exception) {
            stepLog += StepResult("Ultium K73 VCIM (0x252) ATSP6", false, e.message ?: "timeout")
        }
        // Try ATSP5 (11-bit 250kbaud) — K73 might be on MSCAN rather than HSCAN
        try {
            manager.sendCommand("ATSP5"); delay(300)
            manager.sendCommand("ATCRA 652"); delay(100)
            // isUltiumMode already true — sendUds uses correct 11-bit ATSH format
            val r = manager.sendUds(ULTIUM_K73_ADDR, byteArrayOf(SVC_DIAGNOSTIC_SESSION, SESSION_DEFAULT))
            if (r.firstOrNull() == 0x50.toByte()) {
                stepLog += StepResult("Ultium K73 VCIM (0x252)", true, "Found on 11-bit CAN 250kbaud (ATSP5)")
                return true
            }
            stepLog += StepResult("Ultium K73 VCIM (0x252) ATSP5", false, "No response at 250kbaud")
        } catch (e: Exception) {
            stepLog += StepResult("Ultium K73 VCIM (0x252) ATSP5", false, e.message ?: "timeout")
        }
        runCatching { manager.disableUltiumMode() }
        return false
    }

    // --- ECU Identification ---

    // Reads F18A (ECU system name) to identify what module we're talking to.
    private suspend fun identifyEcu(manager: Obd2Manager, addr: Int): String {
        val nameR = try {
            manager.sendUds(addr, byteArrayOf(SVC_READ_DATA_BY_ID, 0xF1.toByte(), 0x8A.toByte()))
        } catch (_: Exception) { byteArrayOf() }
        val name = if (nameR.firstOrNull() == 0x62.toByte() && nameR.size > 3)
            nameR.drop(3).toByteArray().toString(Charsets.US_ASCII).filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' }.trim()
        else null

        val swR = try {
            manager.sendUds(addr, byteArrayOf(SVC_READ_DATA_BY_ID, 0xF1.toByte(), 0x8B.toByte()))
        } catch (_: Exception) { byteArrayOf() }
        val sw = if (swR.firstOrNull() == 0x62.toByte() && swR.size > 3)
            swR.drop(3).toByteArray().toString(Charsets.US_ASCII).filter { it.isLetterOrDigit() || it == '.' || it == ' ' }.trim()
        else null

        return listOfNotNull(
            name?.takeIf { it.isNotBlank() }?.let { "Name: $it" },
            sw?.takeIf { it.isNotBlank() }?.let { "SW: $it" }
        ).joinToString(", ").ifEmpty { "F18A/F18B not readable — may not be VCIM" }
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
