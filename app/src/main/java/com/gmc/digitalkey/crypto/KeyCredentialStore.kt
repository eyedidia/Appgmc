package com.gmc.digitalkey.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

object KeyCredentialStore {

    private const val PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS_PREFIX = "gmc_dk_"
    private const val KEY_ALIAS_ECDSA_PREFIX = "gmc_ec_"

    private fun alias(vehicleId: String) = "$KEY_ALIAS_PREFIX$vehicleId"
    private fun ecAlias(vehicleId: String) = "$KEY_ALIAS_ECDSA_PREFIX$vehicleId"

    // RSA 2048 — used for challenge-response after pairing
    fun generateKeyPair(vehicleId: String): PublicKey {
        val alias = alias(vehicleId)
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER)
        gen.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(2048)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKeyPair().public
    }

    // ECDSA P-256 — compact (65-byte uncompressed public key), sent during BLE pairing
    fun generateEcKeyPair(vehicleId: String): PublicKey {
        val alias = ecAlias(vehicleId)
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (ks.containsAlias(alias)) {
            return ks.getCertificate(alias).publicKey
        }
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        gen.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKeyPair().public
    }

    // Returns the 65-byte uncompressed EC point: 0x04 || X (32) || Y (32)
    fun getEcPublicKeyBytes(vehicleId: String): ByteArray? {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val cert = ks.getCertificate(ecAlias(vehicleId)) ?: return null
        val encoded = cert.publicKey.encoded  // DER SubjectPublicKeyInfo
        // EC P-256 public key in DER is 91 bytes; last 65 bytes = uncompressed point
        return if (encoded.size >= 65) encoded.takeLast(65).toByteArray() else encoded
    }

    fun signWithEc(vehicleId: String, data: ByteArray): ByteArray {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val privateKey = ks.getKey(ecAlias(vehicleId), null)
            ?: error("No EC key for vehicle $vehicleId")
        return java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey as java.security.PrivateKey)
            update(data)
            sign()
        }
    }

    fun getPublicKey(vehicleId: String): PublicKey? {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return ks.getCertificate(alias(vehicleId))?.publicKey
    }

    fun hasKey(vehicleId: String): Boolean {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return ks.containsAlias(alias(vehicleId))
    }

    fun hasEcKey(vehicleId: String): Boolean {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return ks.containsAlias(ecAlias(vehicleId))
    }

    fun deleteKey(vehicleId: String) {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (ks.containsAlias(alias(vehicleId)))    ks.deleteEntry(alias(vehicleId))
        if (ks.containsAlias(ecAlias(vehicleId)))  ks.deleteEntry(ecAlias(vehicleId))
    }

    fun sign(vehicleId: String, data: ByteArray): ByteArray {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val privateKey = ks.getKey(alias(vehicleId), null)
            ?: error("No key for vehicle $vehicleId")
        return java.security.Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey as java.security.PrivateKey)
            update(data)
            sign()
        }
    }
}
