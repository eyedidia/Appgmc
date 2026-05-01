package com.gmc.digitalkey

import com.gmc.digitalkey.crypto.ChallengeResponseEngine
import com.gmc.digitalkey.crypto.KeyCredentialStore
import org.junit.Assert.*
import org.junit.Test

class ChallengeResponseEngineTest {

    @Test
    fun `challenge is 32 bytes`() {
        val challenge = ChallengeResponseEngine.generateChallenge()
        assertEquals(32, challenge.size)
    }

    @Test
    fun `challenges are unique`() {
        val c1 = ChallengeResponseEngine.generateChallenge()
        val c2 = ChallengeResponseEngine.generateChallenge()
        assertFalse(c1.contentEquals(c2))
    }
}
