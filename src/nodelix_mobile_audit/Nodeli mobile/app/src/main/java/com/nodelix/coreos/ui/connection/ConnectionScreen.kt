package com.nodelix.coreos.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nodelix.coreos.core.AppContainer
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.domain.model.ConnectionProbeResult
import com.nodelix.coreos.domain.model.ConnectionState
import com.nodelix.coreos.domain.model.ServerProfile
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
import com.nodelix.coreos.ui.theme.NodelixWarning
import com.nodelix.coreos.util.Constants
import com.nodelix.coreos.util.DateFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ConnectionFormState(
    val editingId: String? = null,
    val name: String = "",
    val host: String = "",
    val port: String = Constants.DEFAULT_PORT.toString(),
    val timeoutSeconds: String = Constants.DEFAULT_TIMEOUT_SECONDS.toString(),
    val description: String = "",
    val isTesting: Boolean = false,
    val isConnecting: Boolean = false,
    val probeResult: ConnectionProbeResult? = null,
    val statusMessage: String? = null,
    val isError: Boolean = false
)

class ConnectionViewModel(
    private val container: AppContainer
) : ViewModel() {

    val profiles: StateFlow<List<ServerProfile>> = container.serverRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeProfile: StateFlow<ServerProfile?> = container.serverRepository.activeProfile
    val connectionState: StateFlow<ConnectionState> = container.serverRepository.connectionState

    private val _form = MutableStateFlow(ConnectionFormState())
    val form: StateFlow<ConnectionFormState> = _form.asStateFlow()

    fun startNewProfile() {
        _form.value = ConnectionFormState()
    }

    fun editProfile(profile: ServerProfile) {
        _form.value = ConnectionFormState(
            editingId = profile.id,
            name = profile.name,
            host = profile.host,
            port = profile.port.toString(),
            timeoutSeconds = profile.timeoutSeconds.toString(),
            description = profile.description,
        )
    }

    fun updateForm(transform: (ConnectionFormState) -> ConnectionFormState) {
        _form.update(transform)
    }

    private fun buildCandidateProfile(): ServerProfile? {
        val f = _form.value
        val portNum = f.port.trim().toIntOrNull() ?: return null
        val timeoutNum = f.timeoutSeconds.trim().toIntOrNull() ?: return null
        return ServerProfile(
            id = f.editingId ?: UUID.randomUUID().toString(),
            name = f.name,
            host = f.host,
            port = portNum,
            timeoutSeconds = timeoutNum,
            description = f.description,
        )
    }

    fun testConnection() {
        val candidate = buildCandidateProfile() ?: run {
            _form.update { it.copy(statusMessage = "Port and timeout must be valid numbers.", isError = true) }
            return
        }
        viewModelScope.launch {
            _form.update { it.copy(isTesting = true, statusMessage = null, probeResult = null, isError = false) }
            when (val res = container.serverRepository.testConnection(candidate)) {
                is AppResult.Failure -> {
                    _form.update {
                        it.copy(isTesting = false, statusMessage = res.error.userMessage, isError = true)
                    }
                }
                is AppResult.Success -> {
                    val probe = res.data
                    val msg = buildString {
                        append("Server reachable in ${probe.responseTimeMs} ms")
                        if (probe.setupRequired) append(" • Initial /setup.html required")
                        else if (probe.mobileAuthSupported) append(" • Mobile ECDSA API ready")
                        else append(" • NodelixcoreOS v1.1 LAN compatible")
                    }
                    _form.update {
                        it.copy(
                            isTesting = false,
                            probeResult = probe,
                            statusMessage = msg,
                            isError = probe.setupRequired
                        )
                    }
                }
            }
        }
    }

    fun saveAndConnect(onConnectedToAuthOrDashboard: (authenticated: Boolean) -> Unit) {
        val candidate = buildCandidateProfile() ?: run {
            _form.update { it.copy(statusMessage = "Enter a valid port (1..65535) and timeout (3..120s).", isError = true) }
            return
        }
        viewModelScope.launch {
            _form.update { it.copy(isConnecting = true, statusMessage = null, isError = false) }
            when (val saved = container.validateAndSaveServerUseCase(candidate)) {
                is AppResult.Failure -> {
                    _form.update {
                        it.copy(isConnecting = false, statusMessage = saved.error.userMessage, isError = true)
                    }
                }
                is AppResult.Success -> {
                    when (val connected = container.connectServerUseCase(saved.data.id)) {
                        is AppResult.Failure -> {
                            _form.update {
                                it.copy(
                                    isConnecting = false,
                                    statusMessage = "Profile saved, but connection failed: ${connected.error.userMessage}",
                                    isError = true
                                )
                            }
                        }
                        is AppResult.Success -> {
                            _form.update {
                                it.copy(
                                    isConnecting = false,
                                    probeResult = connected.data,
                                    statusMessage = "Connected (${connected.data.responseTimeMs} ms).",
                                    isError = false
                                )
                            }
                            if (!connected.data.setupRequired) {
                                val authed = connected.data.authenticated &&
                                    container.sessionManager.sessionState.value.isAuthenticated
                                onConnectedToAuthOrDashboard(authed)
                            }
                        }
                    }
                }
            }
        }
    }

    fun switchActive(profile: ServerProfile, onSwitched: (Boolean) -> Unit) {
        viewModelScope.launch {
            _form.update { it.copy(isConnecting = true, statusMessage = null, isError = false) }
            when (val res = container.connectServerUseCase(profile.id)) {
                is AppResult.Failure -> {
                    _form.update {
                        it.copy(isConnecting = false, statusMessage = res.error.userMessage, isError = true)
                    }
                }
                is AppResult.Success -> {
                    _form.update { it.copy(isConnecting = false) }
                    val authed = res.data.authenticated &&
                        container.sessionManager.sessionState.value.isAuthenticated
                    onSwitched(authed)
                }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            container.serverRepository.disconnectActiveServer()
            _form.update { it.copy(statusMessage = "Disconnected from active server.", isError = false) }
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            container.serverRepository.deleteProfile(profileId)
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ConnectionViewModel(container) as T
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    canNavigateBack: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onNavigateToDashboard: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val connState by viewModel.connectionState.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()

    var pendingDeleteProfile by remember { mutableStateOf<ServerProfile?>(null) }

    Scaffold(
        containerColor = NodelixBackground,
        topBar = {
            TopAppBar(
                title = { Text("Server Connection Manager") },
                navigationIcon = {
                    if (canNavigateBack) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    StatusPill(
                        text = connState.name,
                        tone = when (connState) {
                            ConnectionState.CONNECTED -> BadgeTone.SUCCESS
                            ConnectionState.CONNECTING -> BadgeTone.INFO
                            ConnectionState.AUTHENTICATION_REQUIRED -> BadgeTone.WARNING
                            ConnectionState.ERROR -> BadgeTone.ERROR
                            ConnectionState.DISCONNECTED -> BadgeTone.NEUTRAL
                        },
                        modifier = Modifier.padding(end = 12.dp)
                    )
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SectionHeader(
                    title = "Saved Server Profiles",
                    subtitle = "Manage multiple NodelixcoreOS HTTP LAN nodes",
                    trailing = {
                        OutlinedButton(onClick = { viewModel.startNewProfile() }) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("New")
                        }
                    }
                )
            }

            if (profiles.isEmpty()) {
                item {
                    EmptyOrStatusCard(
                        icon = Icons.Outlined.Dns,
                        title = "No Server Profiles Yet",
                        description = "Add your NodelixcoreOS Server LAN IP address and port below without hardcoded endpoints."
                    )
                }
            } else {
                items(profiles, key = { it.id }) { profile ->
                    val isActive = activeProfile?.id == profile.id
                    NodelixCard(
                        accentBorder = if (isActive) NodelixPrimary else null
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "${profile.protocol.scheme.uppercase()}://${profile.host}:${profile.port}",
                                    style = NodelixMonoStyles.technical,
                                    color = NodelixTextSecondary
                                )
                                if (profile.description.isNotBlank()) {
                                    Text(profile.description, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            StatusPill(
                                text = if (isActive) "Active" else profile.protocol.name,
                                tone = if (isActive) BadgeTone.SUCCESS else BadgeTone.NEUTRAL
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "RTT: ${profile.lastResponseTimeMs?.let { "$it ms" } ?: "N/A"}",
                                style = NodelixMonoStyles.technical,
                                color = NodelixTextSecondary
                            )
                            Text(
                                text = "Last: ${DateFormatter.formatRelativeAge(profile.lastConnectedAt)}",
                                style = NodelixMonoStyles.technical,
                                color = NodelixTextSecondary
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    viewModel.switchActive(profile) { authed ->
                                        if (authed) onNavigateToDashboard() else onNavigateToLogin()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NodelixPrimary,
                                    contentColor = Color(0xFF002116)
                                )
                            ) {
                                Text(if (isActive) "Connect" else "Switch & Connect", fontWeight = FontWeight.SemiBold)
                            }
                            if (isActive && connState == ConnectionState.CONNECTED) {
                                OutlinedButton(onClick = { viewModel.disconnect() }) {
                                    Text("Disconnect")
                                }
                            }
                            IconButton(onClick = { viewModel.editProfile(profile) }) {
                                Icon(Icons.Outlined.Edit, contentDescription = "Edit profile")
                            }
                            IconButton(onClick = { pendingDeleteProfile = profile }) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = "Delete profile",
                                    tint = NodelixError
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                SectionHeader(
                    title = if (form.editingId != null) "Edit Server Profile" else "Configure Server Profile",
                    subtitle = "HTTP-only connection; private LAN addresses only"
                )
            }

            item {
                NodelixCard {
                    OutlinedTextField(
                        value = form.name,
                        onValueChange = { v -> viewModel.updateForm { it.copy(name = v) } },
                        label = { Text("Server Name (e.g. Nodelix Main Server)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = form.host,
                            onValueChange = { v -> viewModel.updateForm { it.copy(host = v) } },
                            label = { Text("IP Address / Hostname") },
                            placeholder = { Text("192.168.11.244") },
                            singleLine = true,
                            textStyle = NodelixMonoStyles.technical,
                            modifier = Modifier.weight(1.8f)
                        )
                        OutlinedTextField(
                            value = form.port,
                            onValueChange = { v -> viewModel.updateForm { it.copy(port = v.filter(Char::isDigit)) } },
                            label = { Text("Port") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = NodelixMonoStyles.technical,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    NodelixCard(
                        secondarySurface = true,
                        accentBorder = NodelixWarning.copy(alpha = 0.6f)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Security, contentDescription = null, tint = NodelixWarning)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("HTTP-only LAN mode", style = MaterialTheme.typography.titleMedium, color = NodelixWarning)
                                Text(
                                    "Traffic is unencrypted. Use only a trusted private LAN or an encrypted private tunnel. Public hosts are blocked.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = form.timeoutSeconds,
                            onValueChange = { v ->
                                viewModel.updateForm { it.copy(timeoutSeconds = v.filter(Char::isDigit)) }
                            },
                            label = { Text("Timeout (s)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = form.description,
                            onValueChange = { v -> viewModel.updateForm { it.copy(description = v) } },
                            label = { Text("Description (Optional)") },
                            singleLine = true,
                            modifier = Modifier.weight(2f)
                        )
                    }

                    if (!form.statusMessage.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = form.statusMessage!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (form.isError) NodelixError else NodelixPrimary
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.testConnection() },
                            enabled = !form.isTesting && !form.isConnecting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.NetworkCheck, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (form.isTesting) "Testing..." else "Test Connection")
                        }
                        Button(
                            onClick = {
                                viewModel.saveAndConnect { authed ->
                                    if (authed) onNavigateToDashboard() else onNavigateToLogin()
                                }
                            },
                            enabled = !form.isConnecting && !form.isTesting,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NodelixPrimary,
                                contentColor = Color(0xFF002116)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (form.isConnecting) "Connecting..." else "Save & Connect",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    pendingDeleteProfile?.let { target ->
        ConfirmSensitiveActionDialog(
            title = "Delete Server Profile?",
            description = "Remove '${target.name}' (${target.host}:${target.port}) and revoke its local session state?",
            confirmLabel = "Delete",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteProfile(target.id)
                pendingDeleteProfile = null
            },
            onDismiss = { pendingDeleteProfile = null }
        )
    }
}
