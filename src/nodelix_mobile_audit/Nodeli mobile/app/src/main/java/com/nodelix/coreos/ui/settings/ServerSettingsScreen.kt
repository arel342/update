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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nodelix.coreos.core.AppContainer
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.data.model.SettingsSnapshotDto
import com.nodelix.coreos.ui.components.ConfirmSensitiveActionDialog
import com.nodelix.coreos.ui.components.EmptyOrStatusCard
import com.nodelix.coreos.ui.components.NodelixCard
import com.nodelix.coreos.ui.components.SectionHeader
import com.nodelix.coreos.ui.theme.NodelixBackground
import com.nodelix.coreos.ui.theme.NodelixError
import com.nodelix.coreos.ui.theme.NodelixMonoStyles
import com.nodelix.coreos.ui.theme.NodelixPrimary
import com.nodelix.coreos.ui.theme.NodelixTextSecondary
import com.nodelix.coreos.util.DateFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ServerSettingsViewModel(
    private val container: AppContainer
) : ViewModel() {

    val snapshot: StateFlow<SettingsSnapshotDto?> = container.serverSettingsRepository.snapshot

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _banner = MutableStateFlow<Pair<String, Boolean>?>(null)
    val banner: StateFlow<Pair<String, Boolean>?> = _banner.asStateFlow()

    /** Local drafts: group -> field -> edited text value. */
    private val _drafts = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val drafts: StateFlow<Map<String, Map<String, String>>> = _drafts.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _isBusy.value = true
            when (val res = container.serverSettingsRepository.refreshSettings()) {
                is AppResult.Failure -> _banner.value = res.error.userMessage to true
                is AppResult.Success -> {
                    _banner.value = null
                    _drafts.value = emptyMap()
                }
            }
            _isBusy.value = false
        }
    }

    fun setDraft(group: String, key: String, value: String) {
        val groupDrafts = _drafts.value[group].orEmpty().toMutableMap()
        groupDrafts[key] = value
        _drafts.value = _drafts.value.toMutableMap().apply { put(group, groupDrafts) }
    }

    fun saveGroup(group: String, fields: Map<String, String>) {
        viewModelScope.launch {
            _isBusy.value = true
            when (val res = container.serverSettingsRepository.updateSettingsGroup(group, fields)) {
                is AppResult.Failure -> _banner.value = res.error.userMessage to true
                is AppResult.Success -> {
                    _banner.value = res.data to false
                    _drafts.value = _drafts.value.toMutableMap().apply { remove(group) }
                }
            }
            _isBusy.value = false
        }
    }

    fun testTelegram() {
        viewModelScope.launch {
            _isBusy.value = true
            when (val res = container.serverSettingsRepository.testTelegramNotification()) {
                is AppResult.Failure -> _banner.value = res.error.userMessage to true
                is AppResult.Success -> _banner.value = res.data to false
            }
            _isBusy.value = false
        }
    }

    fun updateAccount(currentPassword: String, newUsername: String?, newPassword: String?, confirmPassword: String?) {
        viewModelScope.launch {
            _isBusy.value = true
            when (
                val res = container.serverSettingsRepository.updateAdminAccount(
                    currentPassword = currentPassword,
                    newUsername = newUsername,
                    newPassword = newPassword,
                    confirmPassword = confirmPassword
                )
            ) {
                is AppResult.Failure -> _banner.value = res.error.userMessage to true
                is AppResult.Success -> _banner.value = res.data to false
            }
            _isBusy.value = false
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ServerSettingsViewModel(container) as T
            }
    }
}

private data class SettingFieldDef(
    val key: String,
    val label: String,
    val isSecret: Boolean = false,
    val isBoolean: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(
    viewModel: ServerSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()
    var pendingAccountChange by remember { mutableStateOf<PendingAccountChange?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold(
        containerColor = NodelixBackground,
        topBar = {
            TopAppBar(
                title = { Text("Server Configuration") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            banner?.let { (msg, isErr) ->
                item {
                    NodelixCard(accentBorder = if (isErr) NodelixError else NodelixPrimary) {
                        Text(msg, color = if (isErr) NodelixError else NodelixPrimary)
                    }
                }
            }

            val snap = snapshot
            if (snap == null) {
                item {
                    EmptyOrStatusCard(
                        icon = Icons.Outlined.Refresh,
                        title = "Server Settings Unavailable",
                        description = "Tap Refresh to query GET /api/settings.",
                        actionLabel = "Refresh",
                        onAction = { viewModel.refresh() }
                    )
                }
            } else {
                // SERVER INFO (READ-ONLY)
                item {
                    SectionHeader("Server Identity", "Read-only runtime facts from GET /api/settings")
                    NodelixCard {
                        InfoRow("Version", snap.info?.version?.ifBlank { "N/A" } ?: "N/A")
                        InfoRow("Node.js", snap.info?.node?.ifBlank { "N/A" } ?: "N/A")
                        InfoRow("Username", snap.info?.username ?: "admin")
                        InfoRow("Uptime", DateFormatter.formatUptimeSeconds(snap.info?.uptime))
                        InfoRow(
                            "LAN Addresses",
                            snap.info?.ips?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "N/A"
                        )
                        InfoRow("Setup Complete", if (snap.info?.setupDone == true) "YES" else "NO")
                    }
                }

                // ESP32 GROUP
                item {
                    SettingsGroupCard(
                        title = "ESP32 PLTS Node",
                        subtitle = "Sensor polling & relay labels",
                        groupKey = "esp32",
                        fields = listOf(
                            SettingFieldDef("ip", "IP Address"),
                            SettingFieldDef("port", "Port"),
                            SettingFieldDef("apiKey", "API Key", isSecret = true),
                            SettingFieldDef("pollMs", "Poll Interval (ms)"),
                            SettingFieldDef("labels", "Relay Labels (csv)")
                        ),
                        serverValues = mapOf(
                            "ip" to (snap.esp32?.ip ?: ""),
                            "port" to (snap.esp32?.port?.toString() ?: "80"),
                            "apiKey" to (snap.esp32?.apiKey ?: ""),
                            "pollMs" to (snap.esp32?.pollMs?.toString() ?: "1000"),
                            "labels" to (snap.esp32?.labels ?: "")
                        ),
                        viewModel = viewModel,
                        drafts = drafts,
                        isBusy = isBusy
                    )
                }

                // DOOR GROUP
                item {
                    SettingsGroupCard(
                        title = "ESP8266 Door Lock",
                        subtitle = "RFID controller & notifications",
                        groupKey = "door",
                        fields = listOf(
                            SettingFieldDef("ip", "IP Address"),
                            SettingFieldDef("port", "Port"),
                            SettingFieldDef("apiKey", "API Key", isSecret = true),
                            SettingFieldDef("enrollSec", "Enroll Window (s)"),
                            SettingFieldDef("pollMs", "Poll Interval (ms)"),
                            SettingFieldDef("notifyOpen", "Notify on Open", isBoolean = true),
                            SettingFieldDef("notifyUnknown", "Notify on Unknown Card", isBoolean = true)
                        ),
                        serverValues = mapOf(
                            "ip" to (snap.door?.ip ?: ""),
                            "port" to (snap.door?.port?.toString() ?: "80"),
                            "apiKey" to (snap.door?.apiKey ?: ""),
                            "enrollSec" to (snap.door?.enrollSec?.toString() ?: "30"),
                            "pollMs" to (snap.door?.pollMs?.toString() ?: "1000"),
                            "notifyOpen" to (snap.door?.notifyOpen?.toString() ?: "false"),
                            "notifyUnknown" to (snap.door?.notifyUnknown?.toString() ?: "false")
                        ),
                        viewModel = viewModel,
                        drafts = drafts,
                        isBusy = isBusy
                    )
                }

                // TELEGRAM GROUP
                item {
                    SettingsGroupCard(
                        title = "Telegram Notifications",
                        subtitle = "Bot alerts for sensors, door & updates",
                        groupKey = "telegram",
                        fields = listOf(
                            SettingFieldDef("alerts", "Enable Alerts", isBoolean = true),
                            SettingFieldDef("token", "Bot Token", isSecret = true),
                            SettingFieldDef("chatId", "Admin Chat ID")
                        ),
                        serverValues = mapOf(
                            "alerts" to (snap.telegram?.alerts?.toString() ?: "true"),
                            "token" to (snap.telegram?.token ?: ""),
                            "chatId" to (snap.telegram?.chatId ?: "")
                        ),
                        viewModel = viewModel,
                        drafts = drafts,
                        isBusy = isBusy,
                        extraAction = {
                            OutlinedButton(
                                onClick = { viewModel.testTelegram() },
                                enabled = !isBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Send, contentDescription = null)
                                Spacer(Modifier.padding(horizontal = 4.dp))
                                Text("Send Telegram Test Message")
                            }
                        }
                    )
                }

                // AI GROUP
                item {
                    SettingsGroupCard(
                        title = "AI Agent (Gemini)",
                        subtitle = "On-server assistant configuration",
                        groupKey = "ai",
                        fields = listOf(
                            SettingFieldDef("enabled", "Enable AI Agent", isBoolean = true),
                            SettingFieldDef("apiKey", "Gemini API Key", isSecret = true),
                            SettingFieldDef("model", "Model Name")
                        ),
                        serverValues = mapOf(
                            "enabled" to (snap.ai?.enabled?.toString() ?: "true"),
                            "apiKey" to (snap.ai?.apiKey ?: ""),
                            "model" to (snap.ai?.model ?: "")
                        ),
                        viewModel = viewModel,
                        drafts = drafts,
                        isBusy = isBusy
                    )
                }

                // SERVER GROUP
                item {
                    SettingsGroupCard(
                        title = "Server Runtime",
                        subtitle = "Session TTL, storage & OTA limits",
                        groupKey = "server",
                        fields = listOf(
                            SettingFieldDef("port", "HTTP Port"),
                            SettingFieldDef("sessionHours", "Web Session TTL (h)"),
                            SettingFieldDef("storageFolder", "Storage Folder"),
                            SettingFieldDef("otaMaxMB", "OTA Max Size (MB)"),
                            SettingFieldDef("otaTimeoutS", "OTA Timeout (s)"),
                            SettingFieldDef("otaWaitS", "OTA Reboot Wait (s)")
                        ),
                        serverValues = mapOf(
                            "port" to (snap.server?.port?.toString() ?: "3000"),
                            "sessionHours" to (snap.server?.sessionHours?.toString() ?: "24"),
                            "storageFolder" to (snap.server?.storageFolder ?: "upload"),
                            "otaMaxMB" to (snap.server?.otaMaxMB?.toString() ?: "4"),
                            "otaTimeoutS" to (snap.server?.otaTimeoutS?.toString() ?: "240"),
                            "otaWaitS" to (snap.server?.otaWaitS?.toString() ?: "60")
                        ),
                        viewModel = viewModel,
                        drafts = drafts,
                        isBusy = isBusy
                    )
                }

                // MONITOR GROUP
                item {
                    SettingsGroupCard(
                        title = "Monitoring Thresholds",
                        subtitle = "Battery, current & power alarms",
                        groupKey = "monitor",
                        fields = listOf(
                            SettingFieldDef("iWarn", "Current Warn (A)"),
                            SettingFieldDef("cellVMax", "Cell Max (V)"),
                            SettingFieldDef("cellVCrit", "Cell Critical (V)"),
                            SettingFieldDef("cellVLow", "Cell Low (V)"),
                            SettingFieldDef("cellDeltaWarn", "Cell Delta Warn (V)"),
                            SettingFieldDef("iDeadband", "Current Deadband (A)"),
                            SettingFieldDef("acVoltage", "AC Nominal (V)"),
                            SettingFieldDef("acPowerFactor", "AC Power Factor")
                        ),
                        serverValues = mapOf(
                            "iWarn" to (snap.monitor?.iWarn?.toString() ?: "5"),
                            "cellVMax" to (snap.monitor?.cellVMax?.toString() ?: "4.25"),
                            "cellVCrit" to (snap.monitor?.cellVCrit?.toString() ?: "3.2"),
                            "cellVLow" to (snap.monitor?.cellVLow?.toString() ?: "3.4"),
                            "cellDeltaWarn" to (snap.monitor?.cellDeltaWarn?.toString() ?: "0.1"),
                            "iDeadband" to (snap.monitor?.iDeadband?.toString() ?: "0.05"),
                            "acVoltage" to (snap.monitor?.acVoltage?.toString() ?: "220"),
                            "acPowerFactor" to (snap.monitor?.acPowerFactor?.toString() ?: "0.8")
                        ),
                        viewModel = viewModel,
                        drafts = drafts,
                        isBusy = isBusy
                    )
                }

                // ADMIN ACCOUNT
                item {
                    AdminAccountCard(
                        isBusy = isBusy,
                        onSubmit = { current, newUser, newPass, confirm ->
                            pendingAccountChange = PendingAccountChange(current, newUser, newPass, confirm)
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    pendingAccountChange?.let { pending ->
        ConfirmSensitiveActionDialog(
            title = "Change Admin Account?",
            description = "Update the server admin credentials? Other dashboard sessions will be revoked when the password changes.",
            confirmLabel = "Apply Change",
            isDestructive = true,
            onConfirm = {
                viewModel.updateAccount(
                    pending.currentPassword,
                    pending.newUsername,
                    pending.newPassword,
                    pending.confirmPassword
                )
                pendingAccountChange = null
            },
            onDismiss = { pendingAccountChange = null }
        )
    }
}

private data class PendingAccountChange(
    val currentPassword: String,
    val newUsername: String?,
    val newPassword: String?,
    val confirmPassword: String?
)

@Composable
private fun SettingsGroupCard(
    title: String,
    subtitle: String,
    groupKey: String,
    fields: List<SettingFieldDef>,
    serverValues: Map<String, String>,
    viewModel: ServerSettingsViewModel,
    drafts: Map<String, Map<String, String>>,
    isBusy: Boolean,
    extraAction: @Composable (() -> Unit)? = null
) {
    SectionHeader(title, subtitle)
    NodelixCard {
        fields.forEach { field ->
            val serverValue = serverValues[field.key].orEmpty()
            val currentValue = drafts[groupKey]?.get(field.key) ?: serverValue
            if (field.isBoolean) {
                val checked = currentValue == "true"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(field.label, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = checked,
                        onCheckedChange = { viewModel.setDraft(groupKey, field.key, it.toString()) }
                    )
                }
            } else {
                OutlinedTextField(
                    value = currentValue,
                    onValueChange = { viewModel.setDraft(groupKey, field.key, it) },
                    label = { Text(field.label) },
                    singleLine = true,
                    visualTransformation = if (field.isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val groupDrafts = drafts[groupKey].orEmpty()
                val payload = fields.associate { f ->
                    f.key to (groupDrafts[f.key] ?: serverValues[f.key].orEmpty())
                }
                viewModel.saveGroup(groupKey, payload)
            },
            enabled = !isBusy,
            colors = ButtonDefaults.buttonColors(
                containerColor = NodelixPrimary,
                contentColor = Color(0xFF002116)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save $title", fontWeight = FontWeight.SemiBold)
        }
        extraAction?.let {
            Spacer(Modifier.height(8.dp))
            it()
        }
    }
}

@Composable
private fun AdminAccountCard(
    isBusy: Boolean,
    onSubmit: (current: String, newUsername: String?, newPassword: String?, confirm: String?) -> Unit
) {
    var currentPassword by remember { mutableStateOf("") }
    var newUsername by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    SectionHeader("Admin Account", "Requires current dashboard password")
    NodelixCard(accentBorder = NodelixError) {
        OutlinedTextField(
            value = currentPassword,
            onValueChange = { currentPassword = it },
            label = { Text("Current Password (required)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = newUsername,
            onValueChange = { newUsername = it },
            label = { Text("New Username (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = { Text("New Password (min 8 chars, optional)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = { Text("Confirm New Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                onSubmit(
                    currentPassword,
                    newUsername.ifBlank { null },
                    newPassword.ifBlank { null },
                    confirmPassword.ifBlank { null }
                )
                currentPassword = ""
                newPassword = ""
                confirmPassword = ""
            },
            enabled = !isBusy && currentPassword.isNotBlank() &&
                (newUsername.isNotBlank() || newPassword.isNotBlank()),
            colors = ButtonDefaults.buttonColors(
                containerColor = NodelixError,
                contentColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Change Admin Account", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
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
