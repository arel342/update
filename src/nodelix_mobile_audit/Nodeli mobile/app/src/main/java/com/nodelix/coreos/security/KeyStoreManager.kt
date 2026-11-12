package com.nodelix.coreos.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nodelix.coreos.util.Constants
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface KeyInitOutcome {
    data class Ready(val signature: Signature) : KeyInitOutcome
    data object KeyNotFound : KeyInitOutcome
    data object KeyInvalidated : KeyInitOutcome
    data class Error(val reason: String) : KeyInitOutcome
}

data class GeneratedDeviceKey(
    val alias: String,
    val publicKeyPem: String,
    val fingerprintSha256: String
)

/**
 * Manages non-exportable ECDSA P-256 device authentication keys and AES-256-GCM
 * session storage keys inside Android Keystore.
 */
class KeyStoreManager {

    private fun openKeyStore(): KeyStore =
        KeyStore.getInstance(Constants.KEYSTORE_PROVIDER).apply { load(null) }

    private fun aliasForServer(serverId: String): String =
        Constants.ECDSA_KEY_ALIAS_PREFIX + serverId.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun hasValidDeviceKey(serverId: String): Boolean {
        return when (val outcome = initSignatureForSigning(serverId)) {
            is KeyInitOutcome.Ready -> true
            else -> false
        }
    }

    /**
     * Generates a fresh ECDSA P-256 key pair in Android Keystore gated by biometric authentication.
     * Private key material never leaves Android Keystore / hardware TEE.
     */
    fun generateDeviceKeyPair(
        serverId: String,
        requireBiometricAuth: Boolean = true
    ): GeneratedDeviceKey {
        val alias = aliasForServer(serverId)
        val keyStore = openKeyStore()
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }

        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(requireBiometricAuth)
            .setInvalidatedByBiometricEnrollment(true)

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            Constants.KEYSTORE_PROVIDER
        )
        generator.initialize(builder.build())
        val pair = generator.generateKeyPair()

        return GeneratedDeviceKey(
            alias = alias,
            publicKeyPem = encodePublicKeyPem(pair.public),
            fingerprintSha256 = sha256Hex(pair.public.encoded)
        )
    }

    fun getPublicKey(serverId: String): PublicKey? {
        val alias = aliasForServer(serverId)
        val ks = openKeyStore()
        return ks.getCertificate(alias)?.publicKey
    }

    fun deleteDeviceKey(serverId: String) {
        val alias = aliasForServer(serverId)
        val ks = openKeyStore()
        if (ks.containsAlias(alias)) {
            ks.deleteEntry(alias)
        }
    }

    fun initSignatureForSigning(serverId: String): KeyInitOutcome {
        val alias = aliasForServer(serverId)
        return try {
            val ks = openKeyStore()
            val privateKey = ks.getKey(alias, null) as? PrivateKey
                ?: return KeyInitOutcome.KeyNotFound
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(privateKey)
            KeyInitOutcome.Ready(signature)
        } catch (_: KeyPermanentlyInvalidatedException) {
            deleteDeviceKey(serverId)
            KeyInitOutcome.KeyInvalidated
        } catch (e: Exception) {
            KeyInitOutcome.Error(e.message ?: "Failed to initialize Android Keystore key.")
        }
    }

    // ==================== AES-256-GCM SESSION ENCRYPTION ====================

    private fun getOrCreateAesKey(): SecretKey {
        val ks = openKeyStore()
        val existing = (ks.getEntry(Constants.SESSION_AES_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            Constants.KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            Constants.SESSION_AES_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun encryptSecret(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + cipherBytes.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(cipherBytes, 0, combined, 1 + iv.size, cipherBytes.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptSecret(encoded: String): String? {
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size < 14) return null
            val ivLen = combined[0].toInt() and 0xFF
            if (ivLen !in 12..16 || combined.size <= 1 + ivLen) return null
            val iv = combined.copyOfRange(1, 1 + ivLen)
            val cipherBytes = combined.copyOfRange(1 + ivLen, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateAesKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun encodePublicKeyPem(publicKey: PublicKey): String {
            val b64 = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
            val lines = b64.chunked(64).joinToString("\n")
            return "-----BEGIN PUBLIC KEY-----\n$lines\n-----END PUBLIC KEY-----"
        }

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
