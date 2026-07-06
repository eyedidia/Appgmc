package com.gmc.digitalkey.nfc

import android.content.SharedPreferences
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import androidx.preference.PreferenceManager
import com.gmc.digitalkey.crypto.ChallengeResponseEngine
import com.gmc.digitalkey.crypto.KeyCredentialStore

class HceService : HostApduService() {

    companion object {
        private const val TAG = "HceService"

        // GMC Digital Key AID: F04756494E47454E4B4579 ("F0GVINGENKey")
        private val GM_DK_AID = byteArrayOf(
            0xF0.toByte(), 0x47, 0x56, 0x49, 0x4E, 0x47, 0x45, 0x4E, 0x4B, 0x45, 0x79
        )

        private val SW_OK      = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_UNKNOWN = byteArrayOf(0x6D.toByte(), 0x00)
        private val SW_ERROR   = byteArrayOf(0x6F.toByte(), 0x00)
        private const val PREF_ACTIVE_VEHICLE = "active_vehicle_id"

        // ── APDU log (shared with NFC Analyzer UI) ────────────────────────────
        val apduLog = mutableListOf<String>()
        private const val MAX_LOG = 60

        fun addLog(msg: String) {
            Log.d(TAG, msg)
            synchronized(apduLog) {
                apduLog.add(msg)
                if (apduLog.size > MAX_LOG) apduLog.removeAt(0)
            }
        }

        fun clearLog() = synchronized(apduLog) { apduLog.clear() }

        private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }
    }

    private val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
    }

    private var pendingChallenge: ByteArray? = null
    private var sessionAid: ByteArray? = null

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        val hexIn = apdu.hex()
        addLog("← $hexIn")

        if (apdu.size < 4) {
            addLog("→ ${SW_UNKNOWN.hex()}  [too short]")
            return SW_UNKNOWN
        }

        val cla = apdu[0]
        val ins = apdu[1]

        val resp = when {
            // ── SELECT AID ────────────────────────────────────────────────────
            cla == 0x00.toByte() && ins == 0xA4.toByte() -> handleSelect(apdu)

            // ── GET CHALLENGE (vehicle sends us a nonce to sign) ──────────────
            cla == 0x00.toByte() && ins == 0x84.toByte() -> handleGetChallenge(apdu)

            // ── SIGN (vehicle asks us to sign the challenge) ──────────────────
            cla == 0x00.toByte() && ins == 0x2A.toByte() -> handleSign()

            else -> {
                addLog("  INS 0x${"%02X".format(ins)} not handled")
                SW_UNKNOWN
            }
        }

        addLog("→ ${resp.hex()}")
        return resp
    }

    private fun handleSelect(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return SW_UNKNOWN
        val aidLen = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + aidLen) return SW_UNKNOWN

        val aid = apdu.copyOfRange(5, 5 + aidLen)
        sessionAid = aid
        addLog("  AID: ${aid.hex()}  (${aidLen * 8}-bit)")

        return if (aid.contentEquals(GM_DK_AID)) {
            val vehicleId = prefs.getString(PREF_ACTIVE_VEHICLE, null)
            val hasKey = vehicleId != null && KeyCredentialStore.hasKey(vehicleId)
            addLog("  GM DK AID matched — hasKey=$hasKey  vehicle=$vehicleId")
            byteArrayOf(0x01, if (hasKey) 0x01 else 0x00) + SW_OK
        } else {
            addLog("  Unknown AID — returning SW_UNKNOWN")
            SW_UNKNOWN
        }
    }

    private fun handleGetChallenge(apdu: ByteArray): ByteArray {
        val challengeLen = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 32
        val challenge = apdu.copyOfRange(5, minOf(5 + challengeLen, apdu.size))
        pendingChallenge = challenge
        addLog("  Challenge (${challenge.size}B): ${challenge.hex()}")
        return SW_OK
    }

    private fun handleSign(): ByteArray {
        val challenge = pendingChallenge ?: run {
            addLog("  SIGN — no pending challenge")
            return SW_ERROR
        }
        val vehicleId = prefs.getString(PREF_ACTIVE_VEHICLE, null) ?: run {
            addLog("  SIGN — no active vehicle")
            return SW_ERROR
        }
        return runCatching {
            val sig = ChallengeResponseEngine.signChallenge(vehicleId, challenge)
            pendingChallenge = null
            addLog("  Signed (${sig.size}B)")
            sig + SW_OK
        }.getOrElse {
            addLog("  Sign error: ${it.message}")
            SW_ERROR
        }
    }

    override fun onDeactivated(reason: Int) {
        val why = if (reason == DEACTIVATION_LINK_LOSS) "link loss" else "deselected"
        addLog("── deactivated ($why) ──")
        pendingChallenge = null
        sessionAid = null
    }
}
