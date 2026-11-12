package com.nodelix.coreos.security

import com.nodelix.coreos.util.Constants
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * Canonical ECDSA P-256 + SHA-256 challenge-response payload builder & signer.
 *
 * Domain-separated canonical format agreed between Android and Backend:
 * `NODELIXCOREOS-AUTH-V1|<deviceId>|<challengeId>|<nonceBase64>|<timestampMillis>|<serverOrigin>`
 *
 * Signature encoding: Standard ASN.1 DER (`SHA256withECDSA`), transported as Base64 (no line wraps).
 */
object SignatureManager {

    fun buildCanonicalChallengePayload(
        deviceId: String,
        challengeId: String,
        nonceBase64: String,
        timestampMillis: Long,
        serverOrigin: String
    ): ByteArray {
        require(deviceId.isNotBlank() && !deviceId.contains('|')) { "Invalid deviceId" }
        require(challengeId.isNotBlank() && !challengeId.contains('|')) { "Invalid challengeId" }
        require(nonceBase64.isNotBlank() && !nonceBase64.contains('|')) { "Invalid nonce" }
        require(serverOrigin.isNotBlank() && !serverOrigin.contains('|')) { "Invalid serverOrigin" }

        val canonical = buildString {
            append(Constants.AUTH_DOMAIN_PREFIX)
            append('|')
            append(deviceId.trim())
            append('|')
            append(challengeId.trim())
            append('|')
            append(nonceBase64.trim())
            append('|')
            append(timestampMillis)
            append('|')
            append(serverOrigin.trim().trimEnd('/').lowercase())
        }
        return canonical.toByteArray(Charsets.UTF_8)
    }

    fun isChallengeValid(
        nonceBase64: String,
        expiresAtMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (expiresAtMillis <= nowMillis) return false
        return try {
            val decoded = Base64.getDecoder().decode(nonceBase64.trim())
            decoded.size >= 32
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /**
     * Signs [payload] using an already biometric-unlocked [Signature] instance (`SHA256withECDSA`)
     * and returns Base64-encoded ASN.1 DER signature bytes.
     */
    fun signPayloadBase64(unlockedSignature: Signature, payload: ByteArray): String {
        unlockedSignature.update(payload)
        val derBytes = unlockedSignature.sign()
        return Base64.getEncoder().encodeToString(derBytes)
    }

    fun verifySignatureBase64(
        publicKey: PublicKey,
        payload: ByteArray,
        signatureBase64: String
    ): Boolean {
        return try {
            val sigBytes = Base64.getDecoder().decode(signatureBase64.trim())
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(payload)
            verifier.verify(sigBytes)
        } catch (_: Exception) {
            false
        }
    }
}
