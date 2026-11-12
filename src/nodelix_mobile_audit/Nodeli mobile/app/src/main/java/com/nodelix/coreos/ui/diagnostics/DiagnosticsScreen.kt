package com.nodelix.coreos.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nodelix.coreos.core.AppContainer
import com.nodelix.coreos.domain.model.DiagnosticLogEntry
import com.nodelix.coreos.domain.model.DiagnosticSummary
import com.nodelix.coreos.domain.model.LogSeverity
import com.nodelix.coreos.ui.components.BadgeTone
import com.nodelix.coreos.ui.components.NodelixCard
import com.nodelix.coreos.ui.components.SectionHeader
import com.nodelix.coreos.ui.components.StatusPill
import com.nodelix.coreos.ui.theme.NodelixBackground
import com.nodelix.coreos.ui.theme.NodelixMonoStyles
import com.nodelix.coreos.ui.theme.NodelixPrimary
import com.nodelix.coreos.ui.theme.NodelixTextSecondary
import com.nodelix.coreos.util.DateFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DiagnosticsViewModel(
    private val container: AppContainer
) : ViewModel() {

    val logs: StateFlow<List<DiagnosticLogEntry>> = container.diagnosticsRepository.logs

    private val _summary = MutableStateFlow<DiagnosticSummary?>(null)
    val summary: StateFlow<DiagnosticSummary?> = _summary.asStateFlow()

    private val _severityFilter = MutableStateFlow<LogSeverity?>(null)
    val severityFilter: StateFlow<LogSeverity?> = _severityFilter.asStateFlow()

    private val _copiedNotice = MutableStateFlow<String?>(null)
    val copiedNotice: StateFlow<String?> = _copiedNotice.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            container.systemRepository.refreshSystemOverview()
            _summary.value = container.diagnosticsRepository.buildDiagnosticSummary()
        }
    }

    fun setFilter(severity: LogSeverity?) {
        _severityFilter.value = severity
    }

    fun copySanitizedReport(context: Context) {
        viewModelScope.launch {
            val report = container.diagnosticsRepository.formatSanitizedReport()
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Nodelix Diagnostic Report", report))
            _copiedNotice.value = "Sanitized diagnostic report copied to clipboard (zero tokens/keys)."
        }
    }

    fun clearLogs() {
        container.diagnosticsRepository.clearLogs()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DiagnosticsViewModel(container) as T
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    onNavigateBack: () -> Unit
) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val filter by viewModel.severityFilter.collectAsStateWithLifecycle()
    val copiedNotice by viewModel.copiedNotice.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val filteredLogs = remember(logs, filter) {
        if (filter == null) logs else logs.filter { it.severity == filter }
    }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold(
        containerColor = NodelixBackground,
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics & System Log") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.copySanitizedReport(context) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy Diagnostic Report")
                    }
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear Logs")
                    }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!copiedNotice.isNullOrBlank()) {
                item {
                    NodelixCard(accentBorder = NodelixPrimary) {
                        Text(copiedNotice!!, color = NodelixPrimary)
                    }
                }
            }

            summary?.let { s ->
                item {
                    SectionHeader("Measured Connection & Runtime Facts")
                    NodelixCard {
                        DiagRow("Server Connection", "${s.connectionState.name} (${s.serverEndpoint ?: "N/A"})")
                        DiagRow("API Response Time", s.apiResponseTimeMs?.let { "$it ms" } ?: "N/A")
                        DiagRow("WebSocket Status", if (s.webSocketConnected) "CONNECTED" else "DISCONNECTED")
                        DiagRow("Last Synchronization", DateFormatter.formatEpochMillis(s.lastSyncMillis))
                        DiagRow("Last Error", s.lastError ?: "None")
                        DiagRow(
                            "Android Network",
                            if (s.androidWifiConnected) "Wi-Fi/LAN (${s.localIpv4Address ?: "N/A"})"
                            else if (s.androidNetworkOnline) "Online"
                            else "Offline"
                        )
                        DiagRow("App Version", s.appVersion)
                        DiagRow("Backend Version", "${s.backendVersion ?: "N/A"} (Node ${s.nodeVersion ?: "N/A"})")
                        DiagRow("ESP32 / Door Online", "${s.esp32Online ?: "N/A"} / ${s.doorOnline ?: "N/A"}")
                    }
                }
            }

            item {
                SectionHeader("Sanitized System Log", "Passwords, private keys, and session tokens are automatically scrubbed")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = filter == null,
                        onClick = { viewModel.setFilter(null) },
                        label = { Text("ALL (${logs.size})") }
                    )
                    LogSeverity.entries.forEach { sev ->
                        FilterChip(
                            selected = filter == sev,
                            onClick = { viewModel.setFilter(sev) },
                            label = { Text(sev.name) }
                        )
                    }
                }
            }

            items(filteredLogs, key = { it.id }) { entry ->
                NodelixCard(secondarySurface = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatusPill(
                            text = "${entry.severity.name} • ${entry.tag}",
                            tone = when (entry.severity) {
                                LogSeverity.ERROR -> BadgeTone.ERROR
                                LogSeverity.WARN -> BadgeTone.WARNING
                                LogSeverity.INFO -> BadgeTone.SUCCESS
                                LogSeverity.DEBUG -> BadgeTone.NEUTRAL
                            },
                            showDot = false
                        )
                        Text(
                            text = DateFormatter.formatShortTime(entry.timestampMillis),
                            style = NodelixMonoStyles.technical,
                            color = NodelixTextSecondary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(entry.sanitizedMessage, style = NodelixMonoStyles.technical)
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
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
