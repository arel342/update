package com.nodelix.coreos.ui.monitor

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
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nodelix.coreos.core.AppConfig
import com.nodelix.coreos.core.AppContainer
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.domain.model.SystemOverview
import com.nodelix.coreos.network.LocalNetworkStatus
import com.nodelix.coreos.network.WsConnectionState
import com.nodelix.coreos.ui.components.BadgeTone
import com.nodelix.coreos.ui.components.CyberLinearBar
import com.nodelix.coreos.ui.components.EmptyOrStatusCard
import com.nodelix.coreos.ui.components.FreshnessIndicatorRow
import com.nodelix.coreos.ui.components.NodelixCard
import com.nodelix.coreos.ui.components.RealTelemetryLineChart
import com.nodelix.coreos.ui.components.SectionHeader
import com.nodelix.coreos.ui.components.StatusPill
import com.nodelix.coreos.ui.theme.NodelixBackground
import com.nodelix.coreos.ui.theme.NodelixMonoStyles
import com.nodelix.coreos.ui.theme.NodelixPrimary
import com.nodelix.coreos.ui.theme.NodelixSecondary
import com.nodelix.coreos.ui.theme.NodelixTextSecondary
import com.nodelix.coreos.util.DateFormatter
import com.nodelix.coreos.util.FileUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class SystemMonitorViewModel(
    private val container: AppContainer
) : ViewModel() {

    val overview: StateFlow<SystemOverview?> = container.systemRepository.systemOverview
    val networkStatus: StateFlow<LocalNetworkStatus> = container.networkMonitor.status
    val wsState: StateFlow<WsConnectionState> = container.webSocketManager.state

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _errorMessage.value = null
            when (val res = container.systemRepository.refreshSystemOverview()) {
                is AppResult.Failure -> _errorMessage.value = res.error.userMessage
                is AppResult.Success -> _errorMessage.value = null
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SystemMonitorViewModel(container) as T
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemMonitorScreen(
    viewModel: SystemMonitorViewModel
) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val netStatus by viewModel.networkStatus.collectAsStateWithLifecycle()
    val wsState by viewModel.wsState.collectAsStateWithLifecycle()
    val errorMsg by viewModel.errorMessage.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold(
        containerColor = NodelixBackground,
        topBar = {
            TopAppBar(
                title = { Text("System Monitor") },
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
            if (!errorMsg.isNullOrBlank()) {
                item {
                    EmptyOrStatusCard(
                        icon = Icons.Outlined.Memory,
                        title = "Telemetry Sync Warning",
                        description = errorMsg!!,
                        tone = BadgeTone.WARNING
                    )
                }
            }

            val sys = overview
            if (sys == null) {
                item {
                    EmptyOrStatusCard(
                        icon = Icons.Outlined.Memory,
                        title = "Awaiting Host Telemetry",
                        description = "Connect to NodelixcoreOS Server to stream /api/system and WebSocket SYSTEM frames.",
                        actionLabel = "Refresh",
                        onAction = { viewModel.refresh() }
                    )
                }
            } else {
                item { FreshnessIndicatorRow(sys.freshness) }

                // ==================== CPU ====================
                item {
                    SectionHeader("Processor (CPU)", sys.cpuModel ?: "Host Linux / Termux Kernel")
                    NodelixCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = sys.cpuUsagePercent?.let {
                                        String.format(Locale.US, "%.1f%%", it)
                                    } ?: "N/A",
                                    style = NodelixMonoStyles.metricValue
                                )
                                Text(
                                    text = "Cores: ${sys.cpuCores ?: "N/A"} • Load(1m): ${sys.loadAverage1m ?: "N/A"}",
                                    style = NodelixMonoStyles.technical,
                                    color = NodelixTextSecondary
                                )
                            }
                            StatusPill(
                                text = "${sys.history.size} samples",
                                tone = BadgeTone.INFO,
                                showDot = false
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        val cpuHistory = sys.history.mapNotNull { it.cpuPercent }
                        RealTelemetryLineChart(
                            points = cpuHistory,
                            minY = 0.0,
                            maxY = 100.0,
                            lineColor = NodelixPrimary,
                            emptyMessage = "Waiting for >= 2 real CPU history samples..."
                        )
                    }
                }

                // ==================== MEMORY ====================
                item {
                    SectionHeader("Memory (RAM)", "Physical host memory allocation")
                    NodelixCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = sys.ramUsagePercent?.let {
                                    String.format(Locale.US, "%.1f%%", it)
                                } ?: "N/A",
                                style = NodelixMonoStyles.metricValue
                            )
                            Text(
                                text = "${FileUtils.formatBytes(sys.ramUsedBytes)} / ${FileUtils.formatBytes(sys.ramTotalBytes)}",
                                style = NodelixMonoStyles.technical,
                                color = NodelixTextSecondary
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        CyberLinearBar(
                            fraction = ((sys.ramUsagePercent ?: 0.0) / 100.0).toFloat(),
                            color = NodelixSecondary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Free / Available: ${FileUtils.formatBytes(sys.ramAvailableBytes)}",
                            style = NodelixMonoStyles.technical,
                            color = NodelixTextSecondary
                        )
                        Spacer(Modifier.height(10.dp))
                        val ramHistory = sys.history.mapNotNull { it.ramPercent }
                        RealTelemetryLineChart(
                            points = ramHistory,
                            minY = 0.0,
                            maxY = 100.0,
                            lineColor = NodelixSecondary,
                            height = 90.dp,
                            emptyMessage = "Waiting for >= 2 real RAM history samples..."
                        )
                    }
                }

                // ==================== STORAGE ====================
                item {
                    SectionHeader("External Flash Storage", sys.storageRoot ?: "OTG Flash Drive")
                    NodelixCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatusPill(
                                text = if (sys.storageReady) "READY" else "UNAVAILABLE",
                                tone = if (sys.storageReady) BadgeTone.SUCCESS else BadgeTone.WARNING
                            )
                            Text(
                                text = "Mode: ${sys.storageMode ?: "N/A"}",
                                style = NodelixMonoStyles.technical,
                                color = NodelixTextSecondary
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        MonitorRow("Total Storage", FileUtils.formatBytes(sys.storageTotalBytes))
                        MonitorRow("Used Storage", FileUtils.formatBytes(sys.storageUsedBytes))
                        MonitorRow("Free Storage", FileUtils.formatBytes(sys.storageFreeBytes))
                        MonitorRow(
                            "Usage Percentage",
                            sys.storageUsagePercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "N/A"
                        )
                        if (sys.storageUsagePercent != null) {
                            Spacer(Modifier.height(8.dp))
                            CyberLinearBar(fraction = (sys.storageUsagePercent!! / 100.0).toFloat())
                        }
                        if (!sys.storageMessage.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(sys.storageMessage, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // ==================== NETWORK & PROCESS ====================
                item {
                    SectionHeader("Network & Process Runtime", "LAN interfaces and Node.js server health")
                    NodelixCard(modifier = Modifier.fillMaxWidth()) {
                        MonitorRow(
                            "Server LAN IP(s)",
                            sys.lanAddresses.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "N/A"
                        )
                        MonitorRow("Android Local IP", netStatus.localIpv4Address ?: "N/A")
                        MonitorRow(
                            "Interface",
                            if (netStatus.isWifiOrEthernet) "Wi-Fi / LAN" else if (netStatus.isOnline) "Cellular" else "Offline"
                        )
                        MonitorRow("Network RX / TX", "Not Available (/api/system omits netCounters)")
                        MonitorRow("WebSocket Stream", wsState.name)
                        MonitorRow("API Response Time", sys.responseTimeMs?.let { "$it ms" } ?: "N/A")
                        MonitorRow("Node.js Version", sys.nodeVersion ?: "N/A")
                        MonitorRow("Server Version", sys.serverVersion ?: "N/A")
                        MonitorRow("Server Uptime", DateFormatter.formatUptimeSeconds(sys.serverUptimeSeconds))
                        MonitorRow("Mobile App Version", AppConfig.VERSION_NAME)
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun MonitorRow(label: String, value: String) {
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
