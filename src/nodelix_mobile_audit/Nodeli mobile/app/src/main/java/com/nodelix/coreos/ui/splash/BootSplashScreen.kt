package com.nodelix.coreos.ui.splash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nodelix.coreos.R
import com.nodelix.coreos.core.AppContainer
import com.nodelix.coreos.ui.theme.NodelixBackground
import com.nodelix.coreos.ui.theme.NodelixBorder
import com.nodelix.coreos.ui.theme.NodelixMonoStyles
import com.nodelix.coreos.ui.theme.NodelixPrimary
import com.nodelix.coreos.ui.theme.NodelixSecondary
import com.nodelix.coreos.ui.theme.NodelixTextPrimary
import com.nodelix.coreos.ui.theme.NodelixTextSecondary
import com.nodelix.coreos.ui.theme.NodelixWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BootDestination {
    CONNECTION,
    LOGIN,
    DASHBOARD
}

data class BootLine(
    val step: String,
    val status: String,
    val isWarn: Boolean = false
)

data class BootUiState(
    val lines: List<BootLine> = emptyList(),
    val systemReady: Boolean = false,
    val destination: BootDestination? = null
)

class BootViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val _uiState = MutableStateFlow(BootUiState())
    val uiState: StateFlow<BootUiState> = _uiState.asStateFlow()

    private var started = false

    fun startBootSequence() {
        if (started) return
        started = true
        viewModelScope.launch {
            appendLine("INITIALIZING MOBILE SYSTEM", "OK")

            // 1. Load local preferences (non-blocking DataStore read)
            val prefs = container.preferencesDataStore.appPreferencesFlow.first()
            appendLine("LOADING CONFIGURATION [EN]", "OK")

            // 2. Check local security & device installation identity
            val deviceId = container.preferencesDataStore.getOrCreateInstallationDeviceId()
            appendLine("CHECKING LOCAL SECURITY [${deviceId.take(13)}]", "OK")

            // 3. Check biometric hardware capability
            val bioCap = container.biometricManager.checkCapability()
            appendLine(
                "CHECKING BIOMETRIC MODULE [${bioCap.name}]",
                if (bioCap.name == "AVAILABLE") "OK" else "FALLBACK",
                isWarn = bioCap.name != "AVAILABLE"
            )

            // 4. Initialize Android network monitor (never blocks on remote server!)
            container.networkMonitor.startMonitoring()
            val net = container.networkMonitor.refreshNow()
            appendLine(
                "INITIALIZING NETWORK [${if (net.isWifiOrEthernet) "WIFI/LAN" else if (net.isOnline) "ONLINE" else "OFFLINE"}]",
                if (net.isOnline) "OK" else "WARN",
                isWarn = !net.isOnline
            )

            // 5. Load saved server configuration
            val profiles = container.preferencesDataStore.serverProfilesFlow.first()
            val activeProfile = profiles.firstOrNull { it.id == prefs.activeServerId } ?: profiles.firstOrNull()
            appendLine(
                "CHECKING SERVER PROFILE [${activeProfile?.name ?: "UNCONFIGURED"}]",
                if (activeProfile != null) "OK" else "SETUP",
                isWarn = activeProfile == null
            )

            // 6. Restore permitted local session state & load modules
            val destination = if (activeProfile == null) {
                BootDestination.CONNECTION
            } else {
                val restored = container.sessionManager.restorePermittedSession(
                    serverId = activeProfile.id,
                    autoUnlockSession = false
                )
                if (restored.isAuthenticated) BootDestination.DASHBOARD else BootDestination.LOGIN
            }
            appendLine("LOADING APPLICATION MODULES", "OK")

            // Only show SYSTEM READY after all required local initializations have completed (Part 4)
            _uiState.update { it.copy(systemReady = true) }
            delay(260L)
            _uiState.update { it.copy(destination = destination) }
        }
    }

    private suspend fun appendLine(step: String, status: String, isWarn: Boolean = false) {
        _uiState.update { state ->
            state.copy(lines = state.lines + BootLine(step = step, status = status, isWarn = isWarn))
        }
        delay(65L)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BootViewModel(container) as T
            }
    }
}

@Composable
fun BootSplashScreen(
    viewModel: BootViewModel,
    onBootFinished: (BootDestination) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.startBootSequence()
    }

    LaunchedEffect(state.destination) {
        state.destination?.let(onBootFinished)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.ic_nodelix_logo),
                contentDescription = "NodelixcoreOS Logo",
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.size(14.dp))
            Column {
                Text(
                    text = "NODELIXCOREOS MOBILE",
                    style = NodelixMonoStyles.terminal.copy(color = NodelixTextPrimary)
                )
                Text(
                    text = "CORE OPERATING INTERFACE v1.0",
                    style = NodelixMonoStyles.technical.copy(color = NodelixSecondary)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NodelixBackground, RoundedCornerShape(12.dp))
                .border(1.dp, NodelixBorder, RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            HorizontalDivider(color = NodelixBorder)
            Spacer(Modifier.height(12.dp))

            state.lines.forEach { line ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "> ${line.step}",
                        style = NodelixMonoStyles.terminal,
                        color = NodelixTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "[${line.status}]",
                        style = NodelixMonoStyles.terminal,
                        color = if (line.isWarn) NodelixWarning else NodelixPrimary
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = NodelixBorder)

            AnimatedVisibility(visible = state.systemReady, enter = fadeIn()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "SYSTEM READY",
                        style = NodelixMonoStyles.terminal,
                        color = NodelixPrimary
                    )
                    Text(
                        text = "ONLINE HANDOFF ->",
                        style = NodelixMonoStyles.technical,
                        color = NodelixTextSecondary
                    )
                }
            }
        }
    }
}
