package com.gmc.digitalkey.ble.companion

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * D2D (Device-to-Device) secure channel over AES-256-GCM.
 *
 * After UKEY2 completes, both sides have a shared [nextProtocolSecret].
 * This class derives per-direction AES-256 keys via HKDF and wraps every message
 * in a D2DConnectionContext-style frame.
 *
 * Wire format of an encrypted message (as returned by [encrypt]):
 *   [12 bytes IV] [ciphertext + 16-byte GCM tag]
 *
 * The plaintext that is encrypted is a serialised DeviceToDeviceMessage proto:
 *   { sequence_number(1): seqNum, message(2): payload }
 */
internal class D2dSecureChannel(nextProtocolSecret: ByteArray) {

    companion object {
        private val ENCODE_KEY_INFO =
            "D2D v1 initiator to responder get little endian".toByteArray(Charsets.UTF_8)
        private val DECODE_KEY_INFO =
            "D2D v1 responder to initiator get little endian".toByteArray(Charsets.UTF_8)
        private const val GCM_IV_SIZE  = 12
        private const val GCM_TAG_BITS = 128
    }

    // Phone is the UKEY2 initiator:
    //   encode = phone → vehicle
    //   decode = vehicle → phone
    private val encodeKey: ByteArray = Ukey2Handshake.hkdfSha256(nextProtocolSecret, null, ENCODE_KEY_INFO, 32)
    private val decodeKey: ByteArray = Ukey2Handshake.hkdfSha256(nextProtocolSecret, null, DECODE_KEY_INFO, 32)

    private var encodeSeq = 0
    private var decodeSeq = 0

    /**
     * Encrypt [payload] for sending to the vehicle.
     * Returns: [IV (12B)] + [AES-GCM ciphertext + tag]
     */
    fun encrypt(payload: ByteArray): ByteArray {
        val d2dMessage = encodeD2DMessage(encodeSeq++, payload)
        val iv = ByteArray(GCM_IV_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encodeKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(d2dMessage)
        return iv + ciphertext
    }

    /**
     * Decrypt a message received from the vehicle.
     * @param data [IV (12B)] + [AES-GCM ciphertext + tag]
     * @return decrypted payload bytes, or null if decryption fails
     */
    fun decrypt(data: ByteArray): ByteArray? {
        if (data.size < GCM_IV_SIZE + 1) return null
        return try {
            val iv = data.copyOfRange(0, GCM_IV_SIZE)
            val ciphertext = data.copyOfRange(GCM_IV_SIZE, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(decodeKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            val d2dMessage = cipher.doFinal(ciphertext)
            decodeSeq++
            parseD2DMessage(d2dMessage)
        } catch (e: Exception) {
            null
        }
    }

    // ── DeviceToDeviceMessage proto ───────────────────────────────────────────
    // { message(1): bytes, sequence_number(2): int32 }

    private fun encodeD2DMessage(seq: Int, message: ByteArray): ByteArray =
        ProtoUtil.concat(
            ProtoUtil.bytesField(1, message),
            ProtoUtil.varintField(2, seq)
        )

    private fun parseD2DMessage(bytes: ByteArray): ByteArray? {
        val fields = ProtoUtil.parseFields(bytes)
        return ProtoUtil.getBytes(fields, 1)
    }
}
