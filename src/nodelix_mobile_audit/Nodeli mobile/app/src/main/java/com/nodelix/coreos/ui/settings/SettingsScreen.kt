package com.nodelix.coreos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nodelix.coreos.core.AppConfig
import com.nodelix.coreos.core.AppContainer
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.data.local.AppPreferences
import com.nodelix.coreos.domain.model.RegisteredMobileDevice
import com.nodelix.coreos.domain.model.ServerProfile
import com.nodelix.coreos.domain.model.SessionState
import com.nodelix.coreos.ui.components.BadgeTone
import com.nodelix.coreos.ui.components.ConfirmSensitiveActionDialog
import com.nodelix.coreos.ui.components.EmptyOrStatusCard
import com.nodelix.coreos.ui.components.NodelixCard
import com.nodelix.coreos.ui.components.SectionHeader
import com.nodelix.coreos.ui.components.StatusPill
import com.nodelix.coreos.ui.theme.NodelixBackground
import com.nodelix.coreos.ui.theme.NodelixError
import com.nodelix.coreos.ui.theme.NodelixMonoStyles
import com.nodelix.coreos.ui.theme.NodelixPrimary
import com.nodelix.coreos.ui.theme.NodelixTextSecondary
import com.nodelix.coreos.util.DateFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val container: AppContainer
) : ViewModel() {

    val preferences: StateFlow<AppPreferences> = container.preferencesDataStore.appPreferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences())

    val activeProfile: StateFlow<ServerProfile?> = container.serverRepository.activeProfile
    val sessionState: StateFlow<SessionState> = container.securityRepository.sessionState

    private val _registeredDevices = MutableStateFlow<List<RegisteredMobileDevice>>(emptyList())
    val registeredDevices: StateFlow<List<RegisteredMobileDevice>> = _registeredDevices.asStateFlow()

    private val _securityMessage = MutableStateFlow<String?>(null)
    val securityMessage: StateFlow<String?> = _securityMessage.asStateFlow()

    fun biometricCapabilityName(): String = container.biometricManager.checkCapability().name

    fun setHaptic(enabled: Boolean) {
        viewModelScope.launch { container.preferencesDataStore.updatePreferences(hapticFeedback = enabled) }
    }

    fun setAnimations(enabled: Boolean) {
        viewModelScope.launch { container.preferencesDataStore.updatePreferences(animationsEnabled = enabled) }
    }

    fun setCompactDashboard(enabled: Boolean) {
        viewModelScope.launch { container.preferencesDataStore.updatePreferences(compactDashboard = enabled) }
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { container.preferencesDataStore.updatePreferences(autoReconnectWs = enabled) }
    }

    fun loadRegisteredDevices() {
        viewModelScope.launch {
            when (val res = container.securityRepository.getRegisteredDevices()) {
                is AppResult.Failure -> _securityMessage.value = res.error.userMessage
                is AppResult.Success -> {
                    _registeredDevices.value = res.data
                    _securityMessage.value = null
                }
            }
        }
    }

    fun revokeDevice(deviceId: String, onSelfRevoked: () -> Unit) {
        viewModelScope.launch {
            when (val res = container.securityRepository.revokeRegisteredDevice(deviceId)) {
                is AppResult.Failure -> _securityMessage.value = res.error.userMessage
                is AppResult.Success -> {
                    if (!container.sessionManager.sessionState.value.isAuthenticated) {
                        onSelfRevoked()
                    } else {
                        loadRegisteredDevices()
                    }
                }
            }
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            container.securityRepository.logout()
            onLoggedOut()
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(container) as T
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenConnectionManager: () -> Unit,
    onOpenServerSettings: () -> Unit,
    onOpenRegisteredDevices: () -> Unit,
    onOpenUpdateCenter: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onLoggedOut: () -> Unit
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val profile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val session by viewModel.sessionState.collectAsStateWithLifecycle()

    var confirmLogout by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = NodelixBackground,
        topBar = {
            TopAppBar(
                title = { Text("Settings & Security") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NodelixBackground)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // GENERAL
            item {
                SectionHeader("General", "Appearance, language, & UI ergonomics")
                NodelixCard {
                    SettingRowLabel("Theme", "Nodelix Cyber Dark (#080B10 Default)")
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Language", style = MaterialTheme.typography.bodyLarge)
                        // Application language is locked to English by product
                        // requirement and cannot be changed from the UI.
                        StatusPill(text = "English (Locked)", tone = BadgeTone.INFO, showDot = false)
                    }
                    ToggleRow("Haptic Feedback", prefs.hapticFeedbackEnabled) { viewModel.setHaptic(it) }
                    ToggleRow("UI Animations", prefs.animationsEnabled) { viewModel.setAnimations(it) }
                    ToggleRow("Compact Dashboard Cards", prefs.compactDashboardCards) {
                        viewModel.setCompactDashboard(it)
                    }
                }
            }

            // CONNECTION
            item {
                SectionHeader("Connection", "Active HTTP LAN server profile")
                NodelixCard {
                    SettingRowLabel("Active Server", profile?.name ?: "None")
                    SettingRowLabel("Server Address", profile?.host ?: "N/A")
                    SettingRowLabel("Port", profile?.port?.toString() ?: "N/A")
                    SettingRowLabel("Protocol", profile?.protocol?.name ?: "HTTP")
                    SettingRowLabel("Timeout", "${profile?.timeoutSeconds ?: 15}s")
                    ToggleRow("Auto-Reconnect WebSocket", prefs.autoReconnectWebSocket) {
                        viewModel.setAutoReconnect(it)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onOpenConnectionManager,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Dns, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("Open Server Connection Manager")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onOpenServerSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Tune, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("Server Configuration & Telegram")
                    }
                }
            }

            // SECURITY
            item {
                SectionHeader("Security & Device Access", "Android Keystore ECDSA & session controls")
                NodelixCard {
                    SettingRowLabel("Biometric Hardware", viewModel.biometricCapabilityName())
                    SettingRowLabel(
                        "Device Keystore Status",
                        if (session.deviceRegistered) "ECDSA P-256 Bound" else "Not Registered"
                    )
                    SettingRowLabel("Session Mode", session.authMode?.name ?: "UNAUTHENTICATED")
                    SettingRowLabel("Session Expires", DateFormatter.formatEpochMillis(session.expiresAtMillis))
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onOpenRegisteredDevices,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.PhonelinkLock, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("Manage Registered Android Devices")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { confirmLogout = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NodelixError,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("Revoke Session & Logout", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // SYSTEM TOOLS & APPLICATION
            item {
                SectionHeader("Application & Diagnostics")
                NodelixCard {
                    SettingRowLabel("Application", "NodelixcoreOS Mobile")
                    SettingRowLabel("Package ID", AppConfig.APPLICATION_ID)
                    SettingRowLabel("App Version", AppConfig.VERSION_NAME)
                    SettingRowLabel("Build Number", AppConfig.VERSION_CODE.toString())
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onOpenUpdateCenter, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null)
                            Spacer(Modifier.padding(horizontal = 3.dp))
                            Text("Updates")
                        }
                        OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.BugReport, contentDescription = null)
                            Spacer(Modifier.padding(horizontal = 3.dp))
                            Text("Diagnostics")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showLicensesDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("About & Open Source Licenses")
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmLogout) {
        ConfirmSensitiveActionDialog(
            title = "Logout & Revoke Session?",
            description = "Disconnect live WebSocket stream, revoke active server session token, and lock dashboard?",
            confirmLabel = "Logout",
            isDestructive = true,
            onConfirm = {
                confirmLogout = false
                viewModel.logout(onLoggedOut)
            },
            onDismiss = { confirmLogout = false }
        )
    }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = { Text("About NodelixcoreOS Mobile") },
            text = {
                Text(
                    "Official native Android control center for NodelixcoreOS Server.\n\n" +
                        "Open Source Libraries (Apache-2.0 / MIT):\n" +
                        "• AndroidX Jetpack Compose, Lifecycle, Navigation, Biometric, DataStore, WorkManager, Media3\n" +
                        "• Kotlin Coroutines & kotlinx.serialization\n" +
                        "• Square Retrofit & OkHttp\n" +
                        "• Coil Image Loader"
                )
            },
            confirmButton = {
                Button(onClick = { showLicensesDialog = false }) { Text("Close") }
            }
        )
    }
}

// ==================== REGISTERED DEVICES SECURITY SCREEN ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisteredDevicesScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onSelfRevoked: () -> Unit
) {
    val devices by viewModel.registeredDevices.collectAsStateWithLifecycle()
    val message by viewModel.securityMessage.collectAsStateWithLifecycle()
    var deviceToRevoke by remember { mutableStateOf<RegisteredMobileDevice?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadRegisteredDevices()
    }

    Scaffold(
        containerColor = NodelixBackground,
        topBar = {
            TopAppBar(
                title = { Text("Registered Android Devices") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadRegisteredDevices() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NodelixBackground)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!message.isNullOrBlank()) {
                item {
                    NodelixCard(accentBorder = NodelixError) {
                        Text(message!!, color = NodelixError)
                    }
                }
            }

            if (devices.isEmpty()) {
                item {
                    EmptyOrStatusCard(
                        icon = Icons.Outlined.PhonelinkLock,
                        title = "No Registered Mobile Keys",
                        description = "Register this phone's ECDSA P-256 public key from the Login screen to enable biometric challenge-response authentication."
                    )
                }
            } else {
                items(devices, key = { it.deviceId }) { dev ->
                    NodelixCard(accentBorder = if (dev.isCurrentDevice) NodelixPrimary else null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dev.deviceName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "${dev.androidVersion} • App v${dev.appVersion}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            StatusPill(
                                text = if (dev.isCurrentDevice) "THIS PHONE" else dev.status,
                                tone = if (dev.status.contains("REVOK", true)) BadgeTone.ERROR else BadgeTone.SUCCESS
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        SettingRowLabel("Device ID", dev.deviceId.take(22))
                        SettingRowLabel("Key SHA-256", dev.publicKeyFingerprint.ifBlank { "N/A" })
                        SettingRowLabel("Last Seen", DateFormatter.formatEpochMillis(dev.lastSeenMillis))
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { deviceToRevoke = dev },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = NodelixError)
                            Spacer(Modifier.padding(horizontal = 4.dp))
                            Text("Revoke Device Access", color = NodelixError)
                        }
                    }
                }
            }
        }
    }

    deviceToRevoke?.let { dev ->
        ConfirmSensitiveActionDialog(
            title = "Revoke '${dev.deviceName}'?",
            description = "Invalidate this device's public key registration and terminate all active sessions associated with ${dev.deviceId}?",
            confirmLabel = "Revoke Device",
            isDestructive = true,
            onConfirm = {
                viewModel.revokeDevice(dev.deviceId, onSelfRevoked)
                deviceToRevoke = null
            },
            onDismiss = { deviceToRevoke = null }
        )
    }
}

@Composable
private fun SettingRowLabel(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = NodelixTextSecondary)
        Text(value, style = NodelixMonoStyles.technical)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
