package com.gmc.digitalkey.ble.companion

import android.util.Log
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * UKEY2 handshake (P256_SHA512 cipher suite).
 *
 * Protocol flow:
 *   1. [buildClientInit]  → send over BLE (with 4-byte length prefix via ProtoUtil.wrapLength)
 *   2. [processServerInit] ← receive from vehicle
 *   3. [buildClientFinish] → send over BLE
 *   4. Call [nextProtocolSecret] to get the D2D session key seed.
 *
 * Reference: https://github.com/google/ukey2
 */
internal class Ukey2Handshake {

    private val TAG = "Ukey2Handshake"

    // UKEY2 Ukey2Message.Type values
    private val MSG_CLIENT_INIT   = 1
    private val MSG_SERVER_INIT   = 2
    private val MSG_CLIENT_FINISH = 3

    private val HANDSHAKE_CIPHER_P256_SHA512 = 100

    // Generated ECDH keypair for this session
    private val ecKeyPair: KeyPair = generateP256KeyPair()

    // Saved wire bytes for key derivation (raw Ukey2Message proto, no length prefix)
    private var rawClientInit: ByteArray? = null
    private var rawServerInit: ByteArray? = null

    // ECDH shared secret computed in processServerInit
    private var dhSecret: ByteArray? = null

    // Serialized GenericPublicKey for our ECDH public key
    private val encodedPublicKey: ByteArray = encodeGenericPublicKey(ecKeyPair.public as ECPublicKey)

    // Pre-computed CLIENT_FINISH Ukey2Message bytes (used for commitment and for sending)
    private val clientFinishMessage: ByteArray = buildClientFinishMessage()

    // ── Step 1: CLIENT_INIT ───────────────────────────────────────────────────

    /**
     * Build the UKEY2 CLIENT_INIT message (raw proto bytes, without BLE length prefix).
     * Call ProtoUtil.wrapLength() before sending over BLE.
     */
    fun buildClientInit(): ByteArray {
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }

        // commitment = SHA-512(CLIENT_FINISH Ukey2Message bytes)
        val commitment = MessageDigest.getInstance("SHA-512").digest(clientFinishMessage)

        // Ukey2CipherCommitment { handshake_cipher(1)=100, commitment(2)=<bytes> }
        val cipherCommitment = ProtoUtil.concat(
            ProtoUtil.varintField(1, HANDSHAKE_CIPHER_P256_SHA512),
            ProtoUtil.bytesField(2, commitment)
        )

        // Ukey2ClientInit { version(1)=1, random(2)=<32B>, cipher_commitments(3)=<msg>, next_protocol(5)=<str> }
        // Note: field 4 is intentionally absent (reserved in spec)
        val clientInit = ProtoUtil.concat(
            ProtoUtil.varintField(1, 1),
            ProtoUtil.bytesField(2, random),
            ProtoUtil.bytesField(3, cipherCommitment),
            ProtoUtil.stringField(5, "AES_256_CBC-HMAC_SHA256")
        )

        // Ukey2Message { message_type(1)=CLIENT_INIT(1), message_data(2)=<clientInit> }
        val message = ProtoUtil.concat(
            ProtoUtil.varintField(1, MSG_CLIENT_INIT),
            ProtoUtil.bytesField(2, clientInit)
        )

        rawClientInit = message
        Log.d(TAG, "buildClientInit: ${message.size}B, commitment=${commitment.size}B")
        return message
    }

    // ── Step 2: process SERVER_INIT ───────────────────────────────────────────

    /**
     * Parse SERVER_INIT from vehicle and compute ECDH shared secret.
     * @param raw raw proto bytes (already stripped of BLE length prefix)
     * @throws IllegalArgumentException if the message is malformed or wrong cipher
     */
    fun processServerInit(raw: ByteArray) {
        rawServerInit = raw

        val outerFields = ProtoUtil.parseFields(raw)
        val msgType = ProtoUtil.getInt(outerFields, 1)
        check(msgType == MSG_SERVER_INIT) { "Expected SERVER_INIT($MSG_SERVER_INIT), got $msgType" }

        val serverInitBytes = ProtoUtil.getBytes(outerFields, 2)
            ?: error("SERVER_INIT missing message_data")
        val serverInitFields = ProtoUtil.parseFields(serverInitBytes)

        val cipher = ProtoUtil.getInt(serverInitFields, 3)
        check(cipher == HANDSHAKE_CIPHER_P256_SHA512) {
            "Unsupported cipher $cipher (expected P256_SHA512=$HANDSHAKE_CIPHER_P256_SHA512)"
        }

        val pubKeyBytes = ProtoUtil.getBytes(serverInitFields, 4)
            ?: error("SERVER_INIT missing public_key")

        val serverPubKey = decodeGenericPublicKey(pubKeyBytes)

        // ECDH
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(ecKeyPair.private)
        ka.doPhase(serverPubKey, true)
        dhSecret = ka.generateSecret()
        Log.d(TAG, "processServerInit: ECDH OK, sharedSecret=${dhSecret!!.size}B")
    }

    // ── Step 3: CLIENT_FINISH ─────────────────────────────────────────────────

    /**
     * Build the UKEY2 CLIENT_FINISH message (raw proto bytes, without BLE length prefix).
     * Call after processServerInit. Send with ProtoUtil.wrapLength().
     */
    fun buildClientFinish(): ByteArray {
        check(dhSecret != null) { "Call processServerInit before buildClientFinish" }
        return clientFinishMessage
    }

    // ── Step 4: derive session key ────────────────────────────────────────────

    /**
     * Return the next-protocol secret used to initialize the D2D secure channel.
     * Call after buildClientFinish has been sent.
     */
    fun nextProtocolSecret(): ByteArray {
        val m1 = checkNotNull(rawClientInit)  { "CLIENT_INIT not built yet" }
        val m2 = checkNotNull(rawServerInit)  { "SERVER_INIT not received yet" }
        val dh = checkNotNull(dhSecret)       { "ECDH not complete" }

        // salt = SHA-512(m1 || m2)
        val salt = MessageDigest.getInstance("SHA-512").let {
            it.update(m1); it.update(m2); it.digest()
        }
        return hkdfSha256(dh, salt, "UKEY2 v1 next".toByteArray(), 32)
    }

    /**
     * Return the auth string used for out-of-band visual confirmation.
     * The first few bytes are shown to the user on the vehicle screen.
     */
    fun authString(): ByteArray {
        val m1 = checkNotNull(rawClientInit)
        val m2 = checkNotNull(rawServerInit)
        val dh = checkNotNull(dhSecret)
        val salt = MessageDigest.getInstance("SHA-512").let {
            it.update(m1); it.update(m2); it.digest()
        }
        return hkdfSha256(dh, salt, "UKEY2 v1 auth".toByteArray(), 32)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildClientFinishMessage(): ByteArray {
        // Ukey2ClientFinished { public_key(1)=<GenericPublicKey bytes> }
        val clientFinished = ProtoUtil.bytesField(1, encodedPublicKey)
        // Ukey2Message { message_type(1)=CLIENT_FINISH(3), message_data(2)=<clientFinished> }
        return ProtoUtil.concat(
            ProtoUtil.varintField(1, MSG_CLIENT_FINISH),
            ProtoUtil.bytesField(2, clientFinished)
        )
    }

    /** Encode an EC P-256 public key as securemessage.GenericPublicKey proto. */
    private fun encodeGenericPublicKey(pub: ECPublicKey): ByteArray {
        val x = pub.w.affineX.toFixed32Bytes()
        val y = pub.w.affineY.toFixed32Bytes()
        // EcP256PublicKey { x(1)=<bytes>, y(2)=<bytes> }
        val ecP256 = ProtoUtil.concat(
            ProtoUtil.bytesField(1, x),
            ProtoUtil.bytesField(2, y)
        )
        // GenericPublicKey { type(1)=EC_P256(1), ec_p256_public_key(2)=<msg> }
        return ProtoUtil.concat(
            ProtoUtil.varintField(1, 1),
            ProtoUtil.bytesField(2, ecP256)
        )
    }

    /** Decode a securemessage.GenericPublicKey proto back to an ECPublicKey. */
    private fun decodeGenericPublicKey(bytes: ByteArray): ECPublicKey {
        val outer = ProtoUtil.parseFields(bytes)
        val keyType = ProtoUtil.getInt(outer, 1)
        check(keyType == 1) { "GenericPublicKey: expected EC_P256(1) type, got $keyType" }
        val ecP256Bytes = ProtoUtil.getBytes(outer, 2) ?: error("GenericPublicKey: missing ec_p256_public_key")
        val inner = ProtoUtil.parseFields(ecP256Bytes)
        val x = ProtoUtil.getBytes(inner, 1) ?: error("EcP256PublicKey: missing x")
        val y = ProtoUtil.getBytes(inner, 2) ?: error("EcP256PublicKey: missing y")
        return ecPublicKeyFromXY(x, y)
    }

    private fun generateP256KeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        return kpg.generateKeyPair()
    }

    private fun ecPublicKeyFromXY(x: ByteArray, y: ByteArray): ECPublicKey {
        val kf = KeyFactory.getInstance("EC")
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        val ecSpec = params.getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, x), BigInteger(1, y))
        return kf.generatePublic(ECPublicKeySpec(point, ecSpec)) as ECPublicKey
    }

    private fun BigInteger.toFixed32Bytes(): ByteArray {
        val b = toByteArray()
        return when {
            b.size == 33 && b[0] == 0.toByte() -> b.copyOfRange(1, 33)
            b.size == 32 -> b
            b.size < 32  -> ByteArray(32 - b.size) + b
            else          -> error("BigInteger too large for 32 bytes: ${b.size}")
        }
    }

    companion object {
        /** HKDF-SHA256: extract + expand. Uses null salt → 32 zero bytes if salt is null. */
        internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            val realSalt = if (salt == null || salt.isEmpty()) ByteArray(32) else salt
            mac.init(SecretKeySpec(realSalt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)

            val result = ByteArray(length)
            var pos = 0; var counter = 1; var prev = ByteArray(0)
            while (pos < length) {
                mac.init(SecretKeySpec(prk, "HmacSHA256"))
                mac.update(prev); mac.update(info); mac.update(counter.toByte())
                prev = mac.doFinal()
                val copyLen = minOf(prev.size, length - pos)
                prev.copyInto(result, pos, 0, copyLen)
                pos += copyLen; counter++
            }
            return result
        }
    }
}
