package com.nodelix.coreos.ui.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DoorFront
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SensorsOff
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nodelix.coreos.R
import com.nodelix.coreos.core.AppContainer
import com.nodelix.coreos.core.AppError
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.core.UiState
import com.nodelix.coreos.domain.model.AppNotification
import com.nodelix.coreos.domain.model.ConnectionState
import com.nodelix.coreos.domain.model.DoorState
import com.nodelix.coreos.domain.model.Esp32State
import com.nodelix.coreos.domain.model.RelayChannel
import com.nodelix.coreos.domain.model.ServerProfile
import com.nodelix.coreos.domain.model.SystemOverview
import com.nodelix.coreos.domain.usecase.ControlDoorUseCase
import com.nodelix.coreos.network.WsConnectionState
import com.nodelix.coreos.ui.components.AuthRequiredCard
import com.nodelix.coreos.ui.components.BadgeTone
import com.nodelix.coreos.ui.components.ConfirmSensitiveActionDialog
import com.nodelix.coreos.ui.components.CyberDualRingGauge
import com.nodelix.coreos.ui.components.EmptyOrStatusCard
import com.nodelix.coreos.ui.components.ErrorStateCard
import com.nodelix.coreos.ui.components.FreshnessIndicatorRow
import com.nodelix.coreos.ui.components.LoadingStateCard
import com.nodelix.coreos.ui.components.MetricStatTile
import com.nodelix.coreos.ui.components.NodelixCard
import com.nodelix.coreos.ui.components.OfflineBannerCard
import com.nodelix.coreos.ui.components.SectionHeader
import com.nodelix.coreos.ui.components.StatusPill
import com.nodelix.coreos.ui.theme.NodelixError
import com.nodelix.coreos.ui.theme.NodelixMonoStyles
import com.nodelix.coreos.ui.theme.NodelixPrimary
import com.nodelix.coreos.ui.theme.NodelixSecondary
import com.nodelix.coreos.ui.theme.NodelixTextSecondary
import com.nodelix.coreos.ui.theme.NodelixWarning
import com.nodelix.coreos.util.DateFormatter
import com.nodelix.coreos.util.FileUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class DashboardCompositeData(
    val system: SystemOverview,
    val esp32: Esp32State?,
    val door: DoorState?
)

class DashboardViewModel(
    private val container: AppContainer
) : ViewModel() {

    val activeProfile: StateFlow<ServerProfile?> = container.serverRepository.activeProfile
    val connectionState: StateFlow<ConnectionState> = container.serverRepository.connectionState
    val wsState: StateFlow<WsConnectionState> = container.webSocketManager.state
    val systemOverview: StateFlow<SystemOverview?> = container.systemRepository.systemOverview
    val esp32State: StateFlow<Esp32State?> = container.deviceRepository.esp32State
    val doorState: StateFlow<DoorState?> = container.deviceRepository.doorState
    val notifications: StateFlow<List<AppNotification>> = container.notificationRepository.notifications
    val unreadCount: StateFlow<Int> = container.notificationRepository.unreadCount

    private val _uiState = MutableStateFlow<UiState<Unit>>(UiState.Loading)
    val uiState: StateFlow<UiState<Unit>> = _uiState.asStateFlow()

    private val _actionFeedback = MutableStateFlow<String?>(null)
    val actionFeedback: StateFlow<String?> = _actionFeedback.asStateFlow()

    fun refreshAll() {
        viewModelScope.launch {
            if (systemOverview.value == null) {
                _uiState.value = UiState.Loading
            }
            // Proactively rotate Bearer sessions approaching expiry so long-lived
            // dashboards never trip on an expired token mid-refresh.
            if (container.sessionManager.needsBearerRefresh()) {
                runCatching { container.securityRepository.refreshBearerSession() }
            }
            val profile = activeProfile.value
            if (profile != null && container.sessionManager.sessionState.value.isAuthenticated) {
                container.webSocketManager.connect(profile)
            }
            coroutineScope {
                val sysJob = async { container.systemRepository.refreshSystemOverview() }
                val devJob = async { container.deviceRepository.refreshAllDevices() }
                val updJob = async { container.updateRepository.refreshUpdateCenter() }

                val sysRes = sysJob.await()
                devJob.await()
                updJob.await()

                when (sysRes) {
                    is AppResult.Success -> {
                        container.serverRepositoryImpl.markAuthenticatedConnected()
                        _uiState.value = UiState.Success(Unit)
                    }
                    is AppResult.Failure -> {
                        val err = sysRes.error
                        _uiState.value = when (err) {
                            is AppError.AuthRequired, is AppError.DeviceRevoked -> {
                                container.serverRepositoryImpl.markAuthenticationRequired()
                                UiState.AuthenticationRequired(err.userMessage)
                            }
                            is AppError.Offline, is AppError.ConnectionRefused, is AppError.Timeout ->
                                UiState.Offline(err.userMessage)
                            else -> UiState.Error(err)
                        }
                    }
                }
            }
        }
    }

    fun toggleRelayConfirmed(relay: RelayChannel) {
        viewModelScope.launch {
            val res = container.controlRelayUseCase(
                relayId = relay.id.toString(),
                targetState = !relay.isOn,
                userConfirmed = true
            )
            _actionFeedback.value = when (res) {
                is AppResult.Success -> "${relay.name} switched ${if (!relay.isOn) "ON" else "OFF"}."
                is AppResult.Failure -> res.error.userMessage
            }
        }
    }

    fun controlDoorConfirmed(action: ControlDoorUseCase.Action) {
        viewModelScope.launch {
            val res = container.controlDoorUseCase(action, userConfirmed = true)
            _actionFeedback.value = when (res) {
                is AppResult.Success -> res.data
                is AppResult.Failure -> res.error.userMessage
            }
        }
    }

    fun dismissFeedback() {
        _actionFeedback.value = null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DashboardViewModel(container) as T
            }
    }
}

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onOpenNotifications: () -> Unit,
    onOpenServerConfig: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenEsp32Detail: () -> Unit,
    onOpenDoorDetail: () -> Unit,
    onOpenUpdateCenter: () -> Unit
) {
    val profile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val wsState by viewModel.wsState.collectAsStateWithLifecycle()
    val system by viewModel.systemOverview.collectAsStateWithLifecycle()
    val esp32 by viewModel.esp32State.collectAsStateWithLifecycle()
    val door by viewModel.doorState.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val feedback by viewModel.actionFeedback.collectAsStateWithLifecycle()

    var pendingRelay by remember { mutableStateOf<RelayChannel?>(null) }
    var pendingDoorAction by remember { mutableStateOf<ControlDoorUseCase.Action?>(null) }

    LaunchedEffect(profile?.id) {
        viewModel.refreshAll()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ==================== HEADER ====================
        item {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_nodelix_logo),
                        contentDescription = "NodelixcoreOS",
                        modifier = Modifier.size(38.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = profile?.name ?: "Nodelix Control Center",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(
                                text = if (wsState == WsConnectionState.CONNECTED) "WS LIVE" else wsState.name,
                                tone = if (wsState == WsConnectionState.CONNECTED) BadgeTone.SUCCESS else BadgeTone.WARNING
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = profile?.let { "${it.host}:${it.port}" } ?: "Unconfigured",
                                style = NodelixMonoStyles.technical,
                                color = NodelixTextSecondary
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenUpdateCenter) {
                        Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = "Update Center")
                    }
                    IconButton(onClick = onOpenNotifications) {
                        BadgedBox(
                            badge = {
                                if (unreadCount > 0) {
                                    Badge(containerColor = NodelixPrimary, contentColor = Color(0xFF002116)) {
                                        Text(unreadCount.coerceAtMost(99).toString())
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Outlined.Notifications, contentDescription = "Notifications")
                        }
                    }
                    IconButton(onClick = onOpenServerConfig) {
                        Icon(Icons.Outlined.Dns, contentDescription = "Server Profile")
                    }
                    IconButton(onClick = { viewModel.refreshAll() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                }
            }
        }

        // ==================== FEEDBACK / STATUS BANNERS ====================
        if (!feedback.isNullOrBlank()) {
            item {
                NodelixCard(
                    secondarySurface = true,
                    accentBorder = NodelixSecondary,
                    onClick = { viewModel.dismissFeedback() }
                ) {
                    Text(feedback!!, style = MaterialTheme.typography.bodyLarge, color = NodelixSecondary)
                }
            }
        }

        when (val s = uiState) {
            UiState.Loading -> {
                if (system == null) {
                    item { LoadingStateCard() }
                }
            }
            is UiState.AuthenticationRequired -> {
                item { AuthRequiredCard(reason = s.reason, onSignIn = onOpenLogin) }
            }
            is UiState.Offline -> {
                item {
                    OfflineBannerCard(
                        message = s.message,
                        onRetry = { viewModel.refreshAll() },
                        onConfigureServer = onOpenServerConfig
                    )
                }
            }
            is UiState.Error -> {
                item { ErrorStateCard(message = s.error.userMessage, onRetry = { viewModel.refreshAll() }) }
            }
            else -> Unit
        }

        // ==================== SERVER & SYSTEM OVERVIEW ====================
        system?.let { sys ->
            item {
                FreshnessIndicatorRow(sys.freshness)
            }

            item {
                SectionHeader("Server Overview", "Node.js runtime & LAN telemetry")
                NodelixCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CyberDualRingGauge(
                            outerFraction = ((sys.cpuUsagePercent ?: 0.0) / 100.0).toFloat(),
                            innerFraction = ((sys.ramUsagePercent ?: 0.0) / 100.0).toFloat(),
                            centerValue = sys.cpuUsagePercent?.let {
                                String.format(Locale.US, "%.0f%%", it)
                            } ?: "N/A",
                            centerLabel = "CPU / RAM"
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            InfoKeyValue("Status", if (sys.freshness.isFromCache) "CACHED" else "ONLINE")
                            InfoKeyValue("Uptime", DateFormatter.formatUptimeSeconds(sys.serverUptimeSeconds))
                            InfoKeyValue("Response", sys.responseTimeMs?.let { "$it ms" } ?: "N/A")
                            InfoKeyValue("Server Ver", sys.serverVersion ?: "N/A")
                            InfoKeyValue("Node.js", sys.nodeVersion ?: "N/A")
                            InfoKeyValue(
                                "Clients",
                                sys.activeWebSocketClients?.toString() ?: "N/A"
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader("System Resources", "Host CPU, RAM, and external flash disk")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricStatTile(
                        label = "CPU Usage",
                        value = sys.cpuUsagePercent?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A",
                        unit = if (sys.cpuUsagePercent != null) "%" else "",
                        badgeText = if ((sys.cpuUsagePercent ?: 0.0) > 85.0) "HIGH" else "OK",
                        badgeTone = if ((sys.cpuUsagePercent ?: 0.0) > 85.0) BadgeTone.WARNING else BadgeTone.SUCCESS,
                        progressFraction = sys.cpuUsagePercent?.let { (it / 100.0).toFloat() },
                        modifier = Modifier.weight(1f)
                    )
                    MetricStatTile(
                        label = "RAM Usage",
                        value = sys.ramUsagePercent?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A",
                        unit = if (sys.ramUsagePercent != null) "%" else "",
                        badgeText = FileUtils.formatBytes(sys.ramAvailableBytes) + " free",
                        badgeTone = BadgeTone.INFO,
                        progressFraction = sys.ramUsagePercent?.let { (it / 100.0).toFloat() },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                MetricStatTile(
                    label = "External Flash Storage",
                    value = if (sys.storageReady) {
                        sys.storageUsagePercent?.let { String.format(Locale.US, "%.1f", it) } ?: "READY"
                    } else "NOT READY",
                    unit = if (sys.storageReady && sys.storageUsagePercent != null) "%" else "",
                    badgeText = if (sys.storageReady) {
                        "${FileUtils.formatBytes(sys.storageFreeBytes)} free"
                    } else "UNMOUNTED",
                    badgeTone = if (sys.storageReady) BadgeTone.SUCCESS else BadgeTone.WARNING,
                    progressFraction = sys.storageUsagePercent?.let { (it / 100.0).toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ==================== DEVICE OVERVIEW ====================
        item {
            SectionHeader("Device Overview", "Connected Nodelix microcontroller nodes")
            val espOnline = esp32?.online == true
            val doorOnline = door?.online == true
            val totalConfigured = listOfNotNull(
                esp32?.takeIf { it.configured },
                door?.takeIf { it.configured }
            ).size
            val onlineCount = (if (espOnline) 1 else 0) + (if (doorOnline) 1 else 0)
            val offlineCount = (totalConfigured - onlineCount).coerceAtLeast(0)

            NodelixCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SummaryCounter("Total Configured", totalConfigured.toString(), NodelixSecondary)
                    SummaryCounter("Online", onlineCount.toString(), NodelixPrimary)
                    SummaryCounter("Offline", offlineCount.toString(), if (offlineCount > 0) NodelixWarning else NodelixTextSecondary)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onOpenEsp32Detail, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.DeveloperBoard, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("ESP32: ${if (espOnline) "ONLINE" else "OFFLINE"}")
                    }
                    OutlinedButton(onClick = onOpenDoorDetail, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.DoorFront, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("ESP8266: ${if (doorOnline) "ONLINE" else "OFFLINE"}")
                    }
                }
            }
        }

        // ==================== SENSOR OVERVIEW (REAL DATA ONLY) ====================
        item {
            SectionHeader("Sensor Telemetry", "Live readings from ESP32 monitoring module")
            val currentEsp = esp32
            if (currentEsp == null || !currentEsp.online) {
                EmptyOrStatusCard(
                    icon = Icons.Outlined.SensorsOff,
                    title = "ESP32 Telemetry Offline",
                    description = currentEsp?.lastError ?: "ESP32 sensor node is offline or not configured.",
                    tone = BadgeTone.WARNING
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val bat = currentEsp.battery
                        MetricStatTile(
                            label = "Battery Pack",
                            value = bat?.let { String.format(Locale.US, "%.2f", it.packVoltage) } ?: "N/A",
                            unit = if (bat != null) "V" else "",
                            badgeText = bat?.let { String.format(Locale.US, "%.0f%%", it.averageSocPercent) } ?: "N/A",
                            badgeTone = when (bat?.level) {
                                "bad" -> BadgeTone.ERROR
                                "warn" -> BadgeTone.WARNING
                                "ok" -> BadgeTone.SUCCESS
                                else -> BadgeTone.NEUTRAL
                            },
                            progressFraction = bat?.let { (it.averageSocPercent / 100.0).toFloat() },
                            modifier = Modifier.weight(1f)
                        )

                        val cur = currentEsp.current
                        MetricStatTile(
                            label = "Load Current",
                            value = cur?.let { String.format(Locale.US, "%.2f", it.amperes) } ?: "N/A",
                            unit = if (cur != null) "A" else "",
                            badgeText = cur?.let { String.format(Locale.US, "%.0f VA", it.apparentPowerVa) },
                            badgeTone = if (cur?.clipping == true) BadgeTone.ERROR else BadgeTone.INFO,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Only show DHT11 values if actually present & valid; otherwise clearly mark Not Installed / N/A (Part 7)
                    val dht = currentEsp.dht
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricStatTile(
                            label = "Temperature",
                            value = if (dht?.ok == true && dht.temperatureCelsius != null) {
                                String.format(Locale.US, "%.1f", dht.temperatureCelsius)
                            } else "N/A",
                            unit = if (dht?.ok == true && dht.temperatureCelsius != null) "°C" else "",
                            badgeText = if (dht == null) "Not Installed" else if (dht.ok) "DHT11" else "Sensor Err",
                            badgeTone = if (dht?.ok == true) BadgeTone.SUCCESS else BadgeTone.NEUTRAL,
                            modifier = Modifier.weight(1f)
                        )
                        MetricStatTile(
                            label = "Humidity",
                            value = if (dht?.ok == true && dht.humidityPercent != null) {
                                String.format(Locale.US, "%.1f", dht.humidityPercent)
                            } else "N/A",
                            unit = if (dht?.ok == true && dht.humidityPercent != null) "%" else "",
                            badgeText = if (dht == null) "Not Installed" else if (dht.ok) "SAFE" else "N/A",
                            badgeTone = if (dht?.ok == true) BadgeTone.SUCCESS else BadgeTone.NEUTRAL,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // ==================== QUICK CONTROL OVERVIEW ====================
        item {
            SectionHeader("Quick Control Center", "Server-authorized relay & door actuation")
            NodelixCard(modifier = Modifier.fillMaxWidth()) {
                val relays = esp32?.relays.orEmpty()
                Text("ESP32 Relays", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (relays.isEmpty()) {
                    Text(
                        "No relay channels reported (ESP32 offline).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    relays.forEach { relay ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(relay.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = "CH ${relay.channel} • GPIO ${relay.pin ?: "N/A"}",
                                    style = NodelixMonoStyles.technical,
                                    color = NodelixTextSecondary
                                )
                            }
                            OutlinedButton(
                                onClick = { pendingRelay = relay },
                                enabled = esp32?.online == true
                            ) {
                                Icon(
                                    Icons.Outlined.PowerSettingsNew,
                                    contentDescription = null,
                                    tint = if (relay.isOn) NodelixPrimary else NodelixTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (relay.isOn) "ON" else "OFF")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("RFID Door Lock", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = when {
                                door == null || !door!!.online -> "Controller Offline"
                                door!!.isDoorOpen -> "UNLOCKED (${door!!.openSecondsRemaining}s)"
                                else -> "LOCKED • RFID ${if (door!!.isRfidEnabled) "ENABLED" else "LOCKED"}"
                            },
                            style = NodelixMonoStyles.technical,
                            color = if (door?.isDoorOpen == true) NodelixWarning else NodelixPrimary
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { pendingDoorAction = ControlDoorUseCase.Action.OPEN },
                            enabled = door?.online == true,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NodelixPrimary,
                                contentColor = Color(0xFF002116)
                            )
                        ) {
                            Text("OPEN", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = { pendingDoorAction = ControlDoorUseCase.Action.CLOSE },
                            enabled = door?.online == true
                        ) {
                            Text("CLOSE")
                        }
                    }
                }
            }
        }

        // ==================== RECENT ACTIVITY ====================
        item {
            SectionHeader("Recent Activity", "Latest notifications & door events")
        }

        val combinedFeed = notifications.take(5)
        if (combinedFeed.isEmpty() && door?.eventLog.isNullOrEmpty()) {
            item {
                EmptyOrStatusCard(
                    icon = Icons.Outlined.Bolt,
                    title = "No Recent Events",
                    description = "Realtime device, security, and system events will appear here."
                )
            }
        } else {
            items(combinedFeed, key = { it.id }) { item ->
                NodelixCard(modifier = Modifier.fillMaxWidth(), secondarySurface = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatusPill(text = item.category.label, tone = BadgeTone.INFO, showDot = false)
                        Text(
                            text = DateFormatter.formatShortTime(item.timestampMillis),
                            style = NodelixMonoStyles.technical,
                            color = NodelixTextSecondary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(item.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    pendingRelay?.let { relay ->
        ConfirmSensitiveActionDialog(
            title = "Confirm Relay Control",
            description = "Switch '${relay.name}' (GPIO ${relay.pin ?: "N/A"}) ${if (relay.isOn) "OFF" else "ON"}?",
            confirmLabel = if (relay.isOn) "Turn OFF" else "Turn ON",
            onConfirm = {
                viewModel.toggleRelayConfirmed(relay)
                pendingRelay = null
            },
            onDismiss = { pendingRelay = null }
        )
    }

    pendingDoorAction?.let { action ->
        ConfirmSensitiveActionDialog(
            title = if (action == ControlDoorUseCase.Action.OPEN) "Unlock Door?" else "Lock Door Immediately?",
            description = if (action == ControlDoorUseCase.Action.OPEN) {
                "Send authenticated OPEN DOOR command to ESP8266 controller. Note: If the request times out, the app will NOT auto-retry."
            } else {
                "Send authenticated CLOSE DOOR command to lock the door relay immediately."
            },
            confirmLabel = if (action == ControlDoorUseCase.Action.OPEN) "OPEN DOOR" else "CLOSE DOOR",
            isDestructive = action == ControlDoorUseCase.Action.OPEN,
            onConfirm = {
                viewModel.controlDoorConfirmed(action)
                pendingDoorAction = null
            },
            onDismiss = { pendingDoorAction = null }
        )
    }
}

@Composable
private fun InfoKeyValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = NodelixTextSecondary)
        Text(value, style = NodelixMonoStyles.technical)
    }
}

@Composable
private fun SummaryCounter(label: String, count: String, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count, style = NodelixMonoStyles.metricValue.copy(color = accent))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
