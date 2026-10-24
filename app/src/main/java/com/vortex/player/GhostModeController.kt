package com.vortex.player

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GhostState { UNLOCKED, LOCKED }

/**
 * Owns the Ghost Mode lock state and the BiometricPrompt flow.
 *
 * - [lock] flips to LOCKED immediately and runs [onLocked] (wipe history, stop playback, clear vault from memory).
 * - [unlock] shows BiometricPrompt; only a successful authentication flips back to UNLOCKED.
 * - Fails closed: if no biometric / screen lock is enrolled, the vault stays locked.
 */
class GhostModeController(
    private val onLocked: () -> Unit,
    private val onUnlocked: () -> Unit
) {
    private val _state = MutableStateFlow(GhostState.UNLOCKED)
    val state: StateFlow<GhostState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var prompting = false

    private val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun lock() {
        if (_state.value == GhostState.LOCKED) return
        _message.value = null
        _state.value = GhostState.LOCKED
        onLocked()
    }

    fun unlock(activity: FragmentActivity) {
        if (_state.value == GhostState.UNLOCKED || prompting) return

        val availability = BiometricManager.from(activity).canAuthenticate(authenticators)
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            _message.value = when (availability) {
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    "Set up a fingerprint, face unlock or screen lock to open the vault."
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                    "This device has no biometric hardware."
                else -> "Authentication is unavailable right now."
            }
            return
        }

        prompting = true
        _message.value = null

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                _message.value = null
                _state.value = GhostState.UNLOCKED
                onUnlocked()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
                val userDismissed = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                _message.value = if (userDismissed) null else errString.toString()
            }

            override fun onAuthenticationFailed() {
                // Wrong finger/face: the system prompt stays open and lets the user retry.
            }
        }

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Verify it's you")
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(false)
            .build()

        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }
}
