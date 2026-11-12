package com.nodelix.coreos.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class SignatureManagerAndChallengeFlowTest {

    @Test
    fun `canonical challenge payload matches domain separated V1 contract`() {
        val nonce = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val payload = SignatureManager.buildCanonicalChallengePayload(
            deviceId = "ndlx-device-01",
            challengeId = "chl-9941",
            nonceBase64 = nonce,
            timestampMillis = 1762041600000L,
            serverOrigin = "http://192.168.1.10:3000/"
        )

        val asText = String(payload, Charsets.UTF_8)
        assertEquals(
            "NODELIXCOREOS-AUTH-V1|ndlx-device-01|chl-9941|$nonce|1762041600000|http://192.168.1.10:3000",
            asText
        )
    }

    @Test
    fun `ECDSA P-256 signature signs canonical challenge and verifies with public key`() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val keyPair = kpg.generateKeyPair()

        val nonceBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonceBase64 = Base64.getEncoder().encodeToString(nonceBytes)
        val now = 1_762_041_600_000L

        assertTrue(SignatureManager.isChallengeValid(nonceBase64, expiresAtMillis = now + 60_000L, nowMillis = now))

        val payload = SignatureManager.buildCanonicalChallengePayload(
            deviceId = "ndlx-pixel-9",
            challengeId = "challenge-abc",
            nonceBase64 = nonceBase64,
            timestampMillis = now,
            serverOrigin = "http://nodelix.local:8443"
        )

        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
        }
        val signatureBase64 = SignatureManager.signPayloadBase64(signer, payload)

        assertTrue(
            SignatureManager.verifySignatureBase64(
                publicKey = keyPair.public,
                payload = payload,
                signatureBase64 = signatureBase64
            )
        )

        // Tampered serverOrigin or replay against different challengeId MUST fail verification
        val tamperedPayload = SignatureManager.buildCanonicalChallengePayload(
            deviceId = "ndlx-pixel-9",
            challengeId = "challenge-replayed",
            nonceBase64 = nonceBase64,
            timestampMillis = now,
            serverOrigin = "http://nodelix.local:8443"
        )
        assertFalse(
            SignatureManager.verifySignatureBase64(
                publicKey = keyPair.public,
                payload = tamperedPayload,
                signatureBase64 = signatureBase64
            )
        )
    }

    @Test
    fun `canonical payload normalizes server origin casing and trailing slash`() {
        val nonce = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
        val payload = SignatureManager.buildCanonicalChallengePayload(
            deviceId = "ndlx-device-02",
            challengeId = "chl-100",
            nonceBase64 = nonce,
            timestampMillis = 1762041600000L,
            serverOrigin = "HTTP://192.168.1.10:8443/"
        )
        assertEquals(
            "NODELIXCOREOS-AUTH-V1|ndlx-device-02|chl-100|$nonce|1762041600000|http://192.168.1.10:8443",
            String(payload, Charsets.UTF_8)
        )
    }

    @Test
    fun `isChallengeValid rejects expired challenges and short nonces`() {
        val validNonce = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val shortNonce = Base64.getEncoder().encodeToString(ByteArray(16) { 1 })
        val now = 1_762_041_600_000L

        assertFalse(SignatureManager.isChallengeValid(validNonce, expiresAtMillis = now - 1L, nowMillis = now))
        assertFalse(SignatureManager.isChallengeValid(shortNonce, expiresAtMillis = now + 60_000L, nowMillis = now))
        assertFalse(SignatureManager.isChallengeValid("%%%not-base64%%%", expiresAtMillis = now + 60_000L, nowMillis = now))
    }
}
