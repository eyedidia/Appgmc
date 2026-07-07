package com.gmc.digitalkey.crypto

import java.security.SecureRandom

object ChallengeResponseEngine {

    private val random = SecureRandom()

    fun generateChallenge(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun signChallenge(vehicleId: String, challenge: ByteArray): ByteArray =
        KeyCredentialStore.signWithEc(vehicleId, challenge)

    fun verify(vehicleId: String, challenge: ByteArray, signature: ByteArray): Boolean {
        val publicKey = KeyCredentialStore.getPublicKey(vehicleId) ?: return false
        return runCatching {
            java.security.Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey)
                update(challenge)
                verify(signature)
            }
        }.getOrDefault(false)
    }
}
