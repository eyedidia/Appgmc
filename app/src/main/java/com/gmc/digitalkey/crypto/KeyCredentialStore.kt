package com.gmc.digitalkey.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey

object KeyCredentialStore {

    private const val PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS_PREFIX = "gmc_dk_"

    private fun alias(vehicleId: String) = "$KEY_ALIAS_PREFIX$vehicleId"

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

    fun getPublicKey(vehicleId: String): PublicKey? {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return (ks.getCertificate(alias(vehicleId)))?.publicKey
    }

    fun hasKey(vehicleId: String): Boolean {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return ks.containsAlias(alias(vehicleId))
    }

    fun deleteKey(vehicleId: String) {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (ks.containsAlias(alias(vehicleId))) ks.deleteEntry(alias(vehicleId))
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
