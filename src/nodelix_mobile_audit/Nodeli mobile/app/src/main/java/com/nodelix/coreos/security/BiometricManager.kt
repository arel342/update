package com.nodelix.coreos.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.nodelix.coreos.R
import com.nodelix.coreos.core.AppError
import com.nodelix.coreos.core.AppResult
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.Signature
import kotlin.coroutines.resume

enum class BiometricCapability {
    AVAILABLE,
    NO_HARDWARE,
    HARDWARE_UNAVAILABLE,
    NONE_ENROLLED,
    UNSUPPORTED
}

/**
 * Wraps AndroidX Biometric APIs strictly for authorizing Android Keystore cryptographic signing.
 * Fingerprint images or biometric templates are NEVER accessed or stored by the application.
 */
class NodelixBiometricManager(
    private val appContext: Context
) {
    fun checkCapability(): BiometricCapability {
        val manager = BiometricManager.from(appContext)
        return when (manager.canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricCapability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricCapability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricCapability.HARDWARE_UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricCapability.NONE_ENROLLED
            else -> BiometricCapability.UNSUPPORTED
        }
    }

    suspend fun authorizeSignature(
        activity: FragmentActivity,
        signature: Signature,
        serverLabel: String
    ): AppResult<Signature> = suspendCancellableCoroutine { continuation ->
        val executor = ContextCompat.getMainExecutor(activity)

        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (!continuation.isActive) return
                    val unlockedSig = result.cryptoObject?.signature
                    if (unlockedSig != null) {
                        continuation.resume(AppResult.Success(unlockedSig))
                    } else {
                        continuation.resume(
                            AppResult.Failure(
                                AppError.BiometricUnavailable("Hardware cryptographic object was not unlocked.")
                            )
                        )
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!continuation.isActive) return
                    val mapped = when (errorCode) {
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                            AppError.BiometricLockout(errString.toString())
                        BiometricPrompt.ERROR_NO_BIOMETRICS,
                        BiometricPrompt.ERROR_HW_NOT_PRESENT,
                        BiometricPrompt.ERROR_HW_UNAVAILABLE ->
                            AppError.BiometricUnavailable(errString.toString())
                        else ->
                            AppError.AuthRequired(errString.toString())
                    }
                    continuation.resume(AppResult.Failure(mapped))
                }

                override fun onAuthenticationFailed() {
                    // Single non-fatal touch mismatch; BiometricPrompt keeps listening until success or error/cancel.
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.biometric_title))
            .setSubtitle(serverLabel)
            .setDescription(activity.getString(R.string.biometric_subtitle))
            .setNegativeButtonText(activity.getString(R.string.biometric_negative))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()

        continuation.invokeOnCancellation {
            runCatching { prompt.cancelAuthentication() }
        }

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(signature))
    }
}
