package com.gmc.digitalkey.nfc

import android.content.SharedPreferences
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import androidx.preference.PreferenceManager
import com.gmc.digitalkey.crypto.ChallengeResponseEngine
import com.gmc.digitalkey.crypto.KeyCredentialStore

class HceService : HostApduService() {

    companion object {
        // GMC Digital Key AID: F04756494E47454E4B4579
        private val SELECT_AID_CMD = byteArrayOf(
            0xF0.toByte(), 0x47.toByte(), 0x56.toByte(), 0x49.toByte(),
            0x4E.toByte(), 0x47.toByte(), 0x45.toByte(), 0x4E.toByte(),
            0x4B.toByte(), 0x45.toByte(), 0x79.toByte()
        )
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_UNKNOWN = byteArrayOf(0x6D.toByte(), 0x00.toByte())
        private val SW_ERROR = byteArrayOf(0x6F.toByte(), 0x00.toByte())
        private const val PREF_ACTIVE_VEHICLE = "active_vehicle_id"
    }

    private val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
    }

    private var pendingChallenge: ByteArray? = null

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        if (apdu.size < 4) return SW_UNKNOWN

        val cla = apdu[0]
        val ins = apdu[1]

        return when {
            // SELECT command — vehicle is initiating digital key session
            cla == 0x00.toByte() && ins == 0xA4.toByte() -> {
                val aidLen = apdu[4].toInt() and 0xFF
                if (apdu.size >= 5 + aidLen) {
                    val aid = apdu.copyOfRange(5, 5 + aidLen)
                    if (aid.contentEquals(SELECT_AID_CMD)) {
                        // Respond with app version + key present flag
                        val vehicleId = prefs.getString(PREF_ACTIVE_VEHICLE, null)
                        val hasKey = vehicleId != null && KeyCredentialStore.hasKey(vehicleId)
                        byteArrayOf(0x01.toByte(), if (hasKey) 0x01.toByte() else 0x00.toByte()) + SW_OK
                    } else SW_UNKNOWN
                } else SW_UNKNOWN
            }

            // GET CHALLENGE — vehicle sends us a challenge to sign
            cla == 0x00.toByte() && ins == 0x84.toByte() -> {
                val challengeLen = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 32
                pendingChallenge = apdu.copyOfRange(5, minOf(5 + challengeLen, apdu.size))
                SW_OK
            }

            // SIGN — vehicle asks us to sign the challenge
            cla == 0x00.toByte() && ins == 0x2A.toByte() -> {
                val challenge = pendingChallenge ?: return SW_ERROR
                val vehicleId = prefs.getString(PREF_ACTIVE_VEHICLE, null) ?: return SW_ERROR
                runCatching {
                    val sig = ChallengeResponseEngine.signChallenge(vehicleId, challenge)
                    pendingChallenge = null
                    sig + SW_OK
                }.getOrElse { SW_ERROR }
            }

            else -> SW_UNKNOWN
        }
    }

    override fun onDeactivated(reason: Int) {
        pendingChallenge = null
    }
}
