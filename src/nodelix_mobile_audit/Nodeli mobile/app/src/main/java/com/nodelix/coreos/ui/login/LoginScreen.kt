package com.nodelix.coreos.ui.login

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nodelix.coreos.R
import com.nodelix.coreos.core.AppContainer
import com.nodelix.coreos.core.AppError
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.domain.model.ServerProfile
import com.nodelix.coreos.security.BiometricCapability
import com.nodelix.coreos.ui.components.BadgeTone
import com.nodelix.coreos.ui.components.EmptyOrStatusCard
import com.nodelix.coreos.ui.components.NodelixCard
import com.nodelix.coreos.ui.components.StatusPill
import com.nodelix.coreos.ui.theme.NodelixBackground
import com.nodelix.coreos.ui.theme.NodelixCardSecondary
import com.nodelix.coreos.ui.theme.NodelixError
import com.nodelix.coreos.ui.theme.NodelixMonoStyles
import com.nodelix.coreos.ui.theme.NodelixPrimary
import com.nodelix.coreos.ui.theme.NodelixSecondary
import com.nodelix.coreos.ui.theme.NodelixTextSecondary
import com.nodelix.coreos.ui.theme.NodelixWarning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginTab {
    BIOMETRIC,
    ADMIN_REGISTER,
    OTP_RECOVERY
}

data class LoginUiState(
    val selectedTab: LoginTab = LoginTab.BIOMETRIC,
    val username: String = "admin",
    val password: String = "",
    val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    val bindBiometricKey: Boolean = true,
    val biometricCapability: BiometricCapability = BiometricCapability.UNSUPPORTED,
    val hasRegisteredDeviceKey: Boolean = false,
    val keyInvalidatedWarning: Boolean = false,
    val otpCode: String = "",
    val otpResetToken: String? = null,
    val newPassword: String = "",
    val confirmPassword: String = "",
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

class LoginViewModel(
    private val container: AppContainer
) : ViewModel() {

    val activeProfile: StateFlow<ServerProfile?> = container.serverRepository.activeProfile

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        refreshSecurityStatus()
    }

    fun refreshSecurityStatus() {
        // Android Keystore I/O must never block the main thread: probe the device key
        // off the UI thread, then publish the result.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cap = container.biometricManager.checkCapability()
            val serverId = activeProfile.value?.id
            val hasKey = serverId != null && container.keyStoreManager.hasValidDeviceKey(serverId)
            val initialTab = if (hasKey && cap == BiometricCapability.AVAILABLE) {
                LoginTab.BIOMETRIC
            } else {
                LoginTab.ADMIN_REGISTER
            }
            _uiState.update {
                it.copy(
                    biometricCapability = cap,
                    hasRegisteredDeviceKey = hasKey,
                    selectedTab = initialTab,
                    bindBiometricKey = cap == BiometricCapability.AVAILABLE
                )
            }
        }
    }

    fun update(transform: (LoginUiState) -> LoginUiState) {
        _uiState.update(transform)
    }

    fun authenticateWithBiometric(activity: FragmentActivity, onAuthenticated: () -> Unit) {
        val profile = activeProfile.value ?: run {
            _uiState.update { it.copy(errorMessage = "Configure and select a server first.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null, infoMessage = null) }
            when (val challengeRes = container.securityRepository.prepareBiometricChallenge()) {
                is AppResult.Failure -> {
                    val invalidated = challengeRes.error is AppError.KeyInvalidated
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            keyInvalidatedWarning = invalidated,
                            selectedTab = if (invalidated) LoginTab.ADMIN_REGISTER else it.selectedTab,
                            errorMessage = challengeRes.error.userMessage
                        )
                    }
                }
                is AppResult.Success -> {
                    val challenge = challengeRes.data
                    val bioRes = container.biometricManager.authorizeSignature(
                        activity = activity,
                        signature = challenge.signatureObject,
                        serverLabel = "${profile.name} (${profile.host}:${profile.port})"
                    )
                    when (bioRes) {
                        is AppResult.Failure -> {
                            _uiState.update {
                                it.copy(isBusy = false, errorMessage = bioRes.error.userMessage)
                            }
                        }
                        is AppResult.Success -> {
                            when (val verifyRes = container.securityRepository.completeBiometricChallenge(challenge, bioRes.data)) {
                                is AppResult.Failure -> {
                                    _uiState.update {
                                        it.copy(isBusy = false, errorMessage = verifyRes.error.userMessage)
                                    }
                                }
                                is AppResult.Success -> {
                                    // Clear sensitive input fields
                                    _uiState.update { it.copy(isBusy = false, password = "") }
                                    onAuthenticated()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun signInAdminAndRegister(onAuthenticated: () -> Unit) {
        val s = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null, infoMessage = null) }
            val result = container.securityRepository.loginAdminAndRegisterDevice(
                username = s.username,
                password = s.password,
                deviceName = s.deviceName,
                enableBiometricKey = s.bindBiometricKey && s.biometricCapability == BiometricCapability.AVAILABLE
            )
            when (result) {
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isBusy = false, errorMessage = result.error.userMessage) }
                }
                is AppResult.Success -> {
                    // Never keep admin password in memory once login completes
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            password = "",
                            keyInvalidatedWarning = false,
                            hasRegisteredDeviceKey = result.data.deviceRegistered
                        )
                    }
                    onAuthenticated()
                }
            }
        }
    }

    fun requestRecoveryOtp() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null, infoMessage = null) }
            when (val res = container.securityRepository.requestPasswordResetOtp()) {
                is AppResult.Failure -> _uiState.update { it.copy(isBusy = false, errorMessage = res.error.userMessage) }
                is AppResult.Success -> _uiState.update { it.copy(isBusy = false, infoMessage = res.data) }
            }
        }
    }

    fun verifyRecoveryOtp() {
        val code = _uiState.value.otpCode
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null, infoMessage = null) }
            when (val res = container.securityRepository.verifyPasswordResetOtp(code)) {
                is AppResult.Failure -> _uiState.update { it.copy(isBusy = false, errorMessage = res.error.userMessage) }
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        isBusy = false,
                        otpResetToken = res.data,
                        infoMessage = "OTP verified. Enter a new admin password (minimum 8 characters)."
                    )
                }
            }
        }
    }

    fun submitNewPassword() {
        val s = _uiState.value
        val tok = s.otpResetToken ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null, infoMessage = null) }
            when (val res = container.securityRepository.resetAdminPassword(tok, s.newPassword, s.confirmPassword)) {
                is AppResult.Failure -> _uiState.update { it.copy(isBusy = false, errorMessage = res.error.userMessage) }
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        isBusy = false,
                        otpCode = "",
                        otpResetToken = null,
                        newPassword = "",
                        confirmPassword = "",
                        selectedTab = LoginTab.ADMIN_REGISTER,
                        infoMessage = res.data
                    )
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LoginViewModel(container) as T
            }
    }
}

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onAuthenticated: () -> Unit,
    onOpenServerConfig: () -> Unit
) {
    val profile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    Scaffold(containerColor = NodelixBackground) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_nodelix_logo),
                contentDescription = "NodelixcoreOS",
                modifier = Modifier.size(68.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text("NODELIXCOREOS", style = MaterialTheme.typography.headlineMedium)
            Text(
                "BIOMETRIC SECURITY GATEWAY",
                style = MaterialTheme.typography.labelSmall,
                color = NodelixSecondary
            )

            Spacer(Modifier.height(16.dp))

            // Active Server Identity Banner
            NodelixCard(
                modifier = Modifier.fillMaxWidth(),
                secondarySurface = true,
                onClick = onOpenServerConfig
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Dns, contentDescription = null, tint = NodelixPrimary)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = profile?.name ?: "No Server Selected",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = profile?.let { "${it.protocol.scheme}://${it.host}:${it.port}" }
                                    ?: "Tap to configure server profile",
                                style = NodelixMonoStyles.technical,
                                color = NodelixTextSecondary
                            )
                        }
                    }
                    StatusPill(
                        text = "Switch",
                        tone = BadgeTone.INFO,
                        showDot = false
                    )
                }
            }

            if (state.keyInvalidatedWarning) {
                Spacer(Modifier.height(12.dp))
                EmptyOrStatusCard(
                    icon = Icons.Outlined.Key,
                    title = "Biometric Enrollment Changed",
                    description = "Android Keystore invalidated the previous ECDSA private key for your protection. Sign in with your Admin Password to register a new hardware key.",
                    tone = BadgeTone.WARNING
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip(
                    selected = state.selectedTab == LoginTab.BIOMETRIC,
                    onClick = { viewModel.update { it.copy(selectedTab = LoginTab.BIOMETRIC, errorMessage = null) } },
                    label = { Text("Biometric") }
                )
                FilterChip(
                    selected = state.selectedTab == LoginTab.ADMIN_REGISTER,
                    onClick = { viewModel.update { it.copy(selectedTab = LoginTab.ADMIN_REGISTER, errorMessage = null) } },
                    label = { Text("Admin / Register") }
                )
                FilterChip(
                    selected = state.selectedTab == LoginTab.OTP_RECOVERY,
                    onClick = { viewModel.update { it.copy(selectedTab = LoginTab.OTP_RECOVERY, errorMessage = null) } },
                    label = { Text("OTP Recovery") }
                )
            }

            Spacer(Modifier.height(14.dp))

            when (state.selectedTab) {
                LoginTab.BIOMETRIC -> {
                    NodelixCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "ECDSA P-256 CHALLENGE AUTHENTICATION",
                                style = MaterialTheme.typography.labelSmall,
                                color = NodelixSecondary
                            )
                            Spacer(Modifier.height(22.dp))

                            Box(
                                modifier = Modifier
                                    .size(116.dp)
                                    .clip(CircleShape)
                                    .background(NodelixCardSecondary)
                                    .border(
                                        width = 2.dp,
                                        color = if (state.hasRegisteredDeviceKey) NodelixSecondary else NodelixWarning,
                                        shape = CircleShape
                                    )
                                    .clickable(enabled = !state.isBusy && activity != null) {
                                        if (activity != null) {
                                            viewModel.authenticateWithBiometric(activity, onAuthenticated)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (state.isBusy) {
                                    CircularProgressIndicator(color = NodelixPrimary)
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.Fingerprint,
                                        contentDescription = "Authenticate with Fingerprint",
                                        tint = NodelixSecondary,
                                        modifier = Modifier.size(64.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(18.dp))
                            Text(
                                text = if (state.hasRegisteredDeviceKey) {
                                    "Touch sensor to authorize hardware private key signing"
                                } else {
                                    "This phone has not registered an ECDSA key for this server yet."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )

                            Spacer(Modifier.height(16.dp))
                            if (state.hasRegisteredDeviceKey && state.biometricCapability == BiometricCapability.AVAILABLE) {
                                Button(
                                    onClick = {
                                        if (activity != null) {
                                            viewModel.authenticateWithBiometric(activity, onAuthenticated)
                                        }
                                    },
                                    enabled = !state.isBusy,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NodelixPrimary,
                                        contentColor = Color(0xFF002116)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Verify Biometric Identity", fontWeight = FontWeight.SemiBold)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.update { it.copy(selectedTab = LoginTab.ADMIN_REGISTER) } },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Register Device with Admin Account")
                                }
                            }
                        }
                    }
                }

                LoginTab.ADMIN_REGISTER -> {
                    NodelixCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Admin Login & Device Key Registration",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Authenticates against your NodelixcoreOS admin account and generates a non-exportable Android Keystore ECDSA P-256 key pair.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = state.username,
                            onValueChange = { v -> viewModel.update { it.copy(username = v) } },
                            label = { Text("Admin Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = { v -> viewModel.update { it.copy(password = v) } },
                            label = { Text("Admin Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.deviceName,
                            onValueChange = { v -> viewModel.update { it.copy(deviceName = v) } },
                            label = { Text("Device Label") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = state.bindBiometricKey && state.biometricCapability == BiometricCapability.AVAILABLE,
                                onCheckedChange = { c -> viewModel.update { it.copy(bindBiometricKey = c) } },
                                enabled = state.biometricCapability == BiometricCapability.AVAILABLE
                            )
                            Text(
                                text = if (state.biometricCapability == BiometricCapability.AVAILABLE) {
                                    "Register ECDSA P-256 Fingerprint Key in Android Keystore"
                                } else {
                                    "Biometric sensor unavailable (${state.biometricCapability.name}) — session login only"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.signInAdminAndRegister(onAuthenticated) },
                            enabled = !state.isBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NodelixPrimary,
                                contentColor = Color(0xFF002116)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (state.isBusy) "Authenticating..." else "Sign In & Register Device",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                LoginTab.OTP_RECOVERY -> {
                    NodelixCard(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LockReset, contentDescription = null, tint = NodelixSecondary)
                            Spacer(Modifier.width(8.dp))
                            Text("Telegram OTP Admin Recovery", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Requests a 6-digit recovery OTP sent to the server's configured Telegram admin chat (/api/request-otp).",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))

                        if (state.otpResetToken == null) {
                            OutlinedButton(
                                onClick = { viewModel.requestRecoveryOtp() },
                                enabled = !state.isBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Send 6-Digit OTP to Telegram")
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = state.otpCode,
                                onValueChange = { v -> viewModel.update { it.copy(otpCode = v.filter(Char::isDigit).take(6)) } },
                                label = { Text("Enter 6-Digit OTP") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                textStyle = NodelixMonoStyles.metricValue,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { viewModel.verifyRecoveryOtp() },
                                enabled = !state.isBusy && state.otpCode.length == 6,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NodelixSecondary,
                                    contentColor = Color(0xFF001F29)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Verify OTP", fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            OutlinedTextField(
                                value = state.newPassword,
                                onValueChange = { v -> viewModel.update { it.copy(newPassword = v) } },
                                label = { Text("New Admin Password (min 8 chars)") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = state.confirmPassword,
                                onValueChange = { v -> viewModel.update { it.copy(confirmPassword = v) } },
                                label = { Text("Confirm New Password") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.submitNewPassword() },
                                enabled = !state.isBusy && state.newPassword.length >= 8,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NodelixPrimary,
                                    contentColor = Color(0xFF002116)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Reset Admin Password", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            if (!state.errorMessage.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.errorMessage!!,
                    color = NodelixError,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            if (!state.infoMessage.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.infoMessage!!,
                    color = NodelixPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onOpenServerConfig) {
                Text("Configure LAN Server Address", color = NodelixTextSecondary)
            }
        }
    }
}
