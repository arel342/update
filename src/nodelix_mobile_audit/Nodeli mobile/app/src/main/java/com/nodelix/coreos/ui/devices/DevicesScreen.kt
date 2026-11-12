package com.nodelix.coreos.ui.devices

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.DoorFront
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nodelix.coreos.core.AppContainer
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.domain.model.DoorState
import com.nodelix.coreos.domain.model.Esp32State
import com.nodelix.coreos.domain.model.RelayChannel
import com.nodelix.coreos.domain.model.RfidCard
import com.nodelix.coreos.domain.usecase.ControlDoorUseCase
import com.nodelix.coreos.ui.components.BadgeTone
import com.nodelix.coreos.ui.components.ConfirmSensitiveActionDialog
import com.nodelix.coreos.ui.components.EmptyOrStatusCard
import com.nodelix.coreos.ui.components.NodelixCard
import com.nodelix.coreos.ui.components.RealTelemetryLineChart
import com.nodelix.coreos.ui.components.SectionHeader
import com.nodelix.coreos.ui.components.StatusPill
import com.nodelix.coreos.ui.theme.NodelixBackground
import com.nodelix.coreos.ui.theme.NodelixError
import com.nodelix.coreos.ui.theme.NodelixMonoStyles
import com.nodelix.coreos.ui.theme.NodelixPrimary
import com.nodelix.coreos.ui.theme.NodelixSecondary
import com.nodelix.coreos.ui.theme.NodelixTextSecondary
import com.nodelix.coreos.ui.theme.NodelixWarning
import com.nodelix.coreos.util.DateFormatter
import com.nodelix.coreos.util.FileUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class DevicesViewModel(
    private val container: AppContainer
) : ViewModel() {

    val esp32State: StateFlow<Esp32State?> = container.deviceRepository.esp32State
    val doorState: StateFlow<DoorState?> = container.deviceRepository.doorState

    private val _statusBanner = MutableStateFlow<Pair<String, Boolean>?>(null) // message to isError
    val statusBanner: StateFlow<Pair<String, Boolean>?> = _statusBanner.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    fun refreshAll() {
        viewModelScope.launch {
            _isBusy.value = true
            when (val res = container.deviceRepository.refreshAllDevices()) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value = null
            }
            _isBusy.value = false
        }
    }

    fun refreshEsp32() {
        viewModelScope.launch {
            when (val res = container.deviceRepository.refreshEsp32()) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value = "ESP32 telemetry synchronized." to false
            }
        }
    }

    fun refreshDoor() {
        viewModelScope.launch {
            when (val res = container.deviceRepository.refreshDoor()) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value = "Door controller status synchronized." to false
            }
        }
    }

    fun testConnection(deviceKey: String) {
        viewModelScope.launch {
            _isBusy.value = true
            when (val res = container.deviceRepository.testDeviceConnection(deviceKey)) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value = res.data to false
            }
            _isBusy.value = false
        }
    }

    fun setRelay(relayId: String, targetState: Boolean) {
        viewModelScope.launch {
            _isBusy.value = true
            when (val res = container.controlRelayUseCase(relayId, targetState, userConfirmed = true)) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value = "Relay '$relayId' updated." to false
            }
            _isBusy.value = false
        }
    }

    fun calibrateEsp32(type: String, tapId: String? = null, actualValue: Double? = null) {
        viewModelScope.launch {
            _isBusy.value = true
            when (val res = container.deviceRepository.calibrateEsp32(type, tapId, actualValue)) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value = res.data to false
            }
            _isBusy.value = false
        }
    }

    fun executeDoorAction(action: ControlDoorUseCase.Action) {
        viewModelScope.launch {
            _isBusy.value = true
            when (val res = container.controlDoorUseCase(action, userConfirmed = true)) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value = res.data to false
            }
            _isBusy.value = false
        }
    }

    fun toggleRfidLock(enabled: Boolean) {
        viewModelScope.launch {
            when (val res = container.deviceRepository.setDoorRfidEnabled(enabled)) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value = res.data to false
            }
        }
    }

    fun saveDoorOpenSeconds(seconds: Int) {
        viewModelScope.launch {
            when (val res = container.deviceRepository.setDoorOpenDuration(seconds)) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value = res.data to false
            }
        }
    }

    fun setEnrollMode(start: Boolean) {
        viewModelScope.launch {
            when (val res = container.deviceRepository.setDoorEnrollMode(start)) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value = res.data to false
            }
        }
    }

    fun renameCard(uid: String, newName: String) {
        viewModelScope.launch {
            when (val res = container.deviceRepository.renameRfidCard(uid, newName)) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value = "Card renamed to '$newName'." to false
            }
        }
    }

    fun setCardAdmin(uid: String, makeAdmin: Boolean, adminPassword: String?) {
        viewModelScope.launch {
            when (val res = container.deviceRepository.setRfidCardAdmin(uid, makeAdmin, adminPassword)) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value = res.data to false
            }
        }
    }

    fun deleteCard(uid: String) {
        viewModelScope.launch {
            when (val res = container.deviceRepository.deleteRfidCard(uid)) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value = "RFID card removed." to false
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DevicesViewModel(container) as T
            }
    }
}

// ==================== DEVICE CENTER OVERVIEW ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel,
    onOpenEsp32Detail: () -> Unit,
    onOpenDoorDetail: () -> Unit,
    onOpenFirmwareUpdater: () -> Unit
) {
    val esp32 by viewModel.esp32State.collectAsStateWithLifecycle()
    val door by viewModel.doorState.collectAsStateWithLifecycle()
    val banner by viewModel.statusBanner.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshAll()
    }

    Scaffold(
        containerColor = NodelixBackground,
        topBar = {
            TopAppBar(
                title = { Text("Device Center") },
                actions = {
                    IconButton(onClick = onOpenFirmwareUpdater) {
                        Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = "Firmware Update")
                    }
                    IconButton(onClick = { viewModel.refreshAll() }) {
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

            item {
                SectionHeader("Microcontroller Nodes", "Tap a node to inspect sensors, relays, door lock, & logs")
            }

            // ESP32 Card
            item {
                NodelixCard(onClick = onOpenEsp32Detail) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.DeveloperBoard, contentDescription = null, tint = NodelixPrimary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("ESP32 Power & Sensor Node", style = MaterialTheme.typography.titleLarge)
                                Text("Type: ESP32 • PLTS / Relays / DHT11", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        StatusPill(
                            text = when {
                                esp32?.updatingFirmware == true -> "OTA BUSY"
                                esp32?.online == true -> "ONLINE"
                                else -> "OFFLINE"
                            },
                            tone = if (esp32?.online == true) BadgeTone.SUCCESS else BadgeTone.WARNING
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    DeviceMetaRow("IP Address", esp32?.ipAddress ?: "N/A")
                    DeviceMetaRow("MAC Address", "Not Exposed by Firmware API")
                    DeviceMetaRow("Firmware", esp32?.firmwareVersion ?: "N/A")
                    DeviceMetaRow("Wi-Fi Signal", esp32?.rssiDbm?.let { "$it dBm" } ?: "N/A")
                    DeviceMetaRow("Last Seen", DateFormatter.formatRelativeAge(esp32?.lastOkMillis))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onOpenEsp32Detail,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NodelixPrimary,
                                contentColor = Color(0xFF002116)
                            )
                        ) {
                            Text("Open ESP32 Console", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(onClick = { viewModel.testConnection("esp32") }) {
                            Text("Ping / Test")
                        }
                    }
                }
            }

            // ESP8266 Door Lock Card
            item {
                NodelixCard(onClick = onOpenDoorDetail) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.DoorFront, contentDescription = null, tint = NodelixSecondary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("ESP8266 RFID Door Controller", style = MaterialTheme.typography.titleLarge)
                                Text("Type: ESP8266 • Door Lock & RFID Access", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        StatusPill(
                            text = when {
                                door?.updatingFirmware == true -> "OTA BUSY"
                                door?.online == true -> "ONLINE"
                                else -> "OFFLINE"
                            },
                            tone = if (door?.online == true) BadgeTone.SUCCESS else BadgeTone.WARNING
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    DeviceMetaRow("IP Address", door?.ipAddress ?: "N/A")
                    DeviceMetaRow("MAC Address", "Not Exposed by Firmware API")
                    DeviceMetaRow("Firmware", door?.firmwareVersion ?: "N/A")
                    DeviceMetaRow(
                        "Door State",
                        when {
                            door == null || !door!!.online -> "UNKNOWN"
                            door!!.isDoorOpen -> "OPEN (${door!!.openSecondsRemaining}s)"
                            else -> "LOCKED"
                        }
                    )
                    DeviceMetaRow("Last Seen", DateFormatter.formatRelativeAge(door?.lastOkMillis))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onOpenDoorDetail,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NodelixSecondary,
                                contentColor = Color(0xFF001F29)
                            )
                        ) {
                            Text("Door & RFID Control", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(onClick = { viewModel.testConnection("esp8266") }) {
                            Text("Ping / Test")
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ==================== ESP32 DETAIL SCREEN ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Esp32DetailScreen(
    viewModel: DevicesViewModel,
    onNavigateBack: () -> Unit,
    onOpenFirmwareUpdater: () -> Unit
) {
    val esp32 by viewModel.esp32State.collectAsStateWithLifecycle()
    val banner by viewModel.statusBanner.collectAsStateWithLifecycle()

    var pendingRelayAction by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var confirmResetCal by remember { mutableStateOf(false) }
    var selectedTapId by remember { mutableStateOf("s1") }
    var tapActualVoltage by remember { mutableStateOf("") }
    var gainActualCurrent by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.refreshEsp32()
    }

    Scaffold(
        containerColor = NodelixBackground,
        topBar = {
            TopAppBar(
                title = { Text("ESP32 Control & Sensors") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenFirmwareUpdater) {
                        Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = "Update Firmware")
                    }
                    IconButton(onClick = { viewModel.refreshEsp32() }) {
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

            val s = esp32
            if (s == null) {
                item {
                    EmptyOrStatusCard(
                        icon = Icons.Outlined.DeveloperBoard,
                        title = "ESP32 Data Unavailable",
                        description = "Tap Refresh to query /api/esp32/status.",
                        actionLabel = "Refresh",
                        onAction = { viewModel.refreshEsp32() }
                    )
                }
            } else {
                item {
                    SectionHeader("Node Identity & Firmware")
                    NodelixCard {
                        DeviceMetaRow("Device ID", "esp32")
                        DeviceMetaRow("Connection", if (s.online) "ONLINE" else "OFFLINE")
                        DeviceMetaRow("IP Address", s.ipAddress ?: "N/A")
                        DeviceMetaRow("Firmware", s.firmwareVersion ?: "N/A")
                        DeviceMetaRow("RSSI", s.rssiDbm?.let { "$it dBm" } ?: "N/A")
                        DeviceMetaRow("Free Heap", FileUtils.formatBytes(s.freeHeapBytes))
                        DeviceMetaRow("Node Uptime", DateFormatter.formatUptimeSeconds(s.uptimeSeconds))
                        DeviceMetaRow("Last Activity", DateFormatter.formatEpochMillis(s.lastOkMillis))
                        if (!s.lastError.isNullOrBlank()) {
                            DeviceMetaRow("Last Error", s.lastError)
                        }
                    }
                }

                item {
                    SectionHeader("Battery & Power Telemetry")
                    NodelixCard {
                        val b = s.battery
                        if (b == null) {
                            Text("Battery tap telemetry not available.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            DeviceMetaRow("Pack Voltage", String.format(Locale.US, "%.2f V", b.packVoltage))
                            DeviceMetaRow("Estimated SoC", String.format(Locale.US, "%.1f%% (%s)", b.averageSocPercent, b.statusText))
                            if (b.cellsVoltage.isNotEmpty()) {
                                DeviceMetaRow(
                                    "Cells (S1/S2/S3)",
                                    b.cellsVoltage.joinToString(" / ") { String.format(Locale.US, "%.2fV", it) }
                                )
                            }
                            DeviceMetaRow("Cell Delta", String.format(Locale.US, "%.3f V", b.cellDeltaVoltage))
                        }
                        val c = s.current
                        if (c != null) {
                            Spacer(Modifier.height(6.dp))
                            DeviceMetaRow("Load Current", String.format(Locale.US, "%.2f A (Peak %.2f A)", c.amperes, c.peakAmperes))
                            DeviceMetaRow("Apparent Power", String.format(Locale.US, "%.1f VA", c.apparentPowerVa))
                            DeviceMetaRow("ADC Clipping", if (c.clipping) "WARNING: CLIPPING" else "CLEAR")
                        }
                        val d = s.dht
                        Spacer(Modifier.height(6.dp))
                        DeviceMetaRow(
                            "DHT11 Sensor",
                            if (d == null) "Not Installed"
                            else if (d.ok) "${d.temperatureCelsius ?: "--"} °C / ${d.humidityPercent ?: "--"} %"
                            else "Sensor Read Error (${d.error ?: "check wiring"})"
                        )

                        Spacer(Modifier.height(10.dp))
                        val vPoints = s.history.mapNotNull { it.packVoltage }
                        RealTelemetryLineChart(
                            points = vPoints,
                            minY = 9.0,
                            maxY = 13.0,
                            lineColor = NodelixPrimary,
                            height = 90.dp,
                            emptyMessage = "Waiting for live voltage samples..."
                        )
                    }
                }

                item {
                    SectionHeader("Relay Channels", "Server-authorized GPIO relay actuation")
                    NodelixCard {
                        if (s.relays.isEmpty()) {
                            Text("No relay channels reported.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            s.relays.forEach { relay ->
                                RelayControlRow(
                                    relay = relay,
                                    enabled = s.online,
                                    onToggle = { pendingRelayAction = relay.id.toString() to !relay.isOn }
                                )
                            }
                            s.aux?.let { aux ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "Companion Aux (Relay 1 & 2): GPIO ${aux.onPin}=${if (aux.onHigh) "HIGH" else "LOW"} · GPIO ${aux.offPin}=${if (aux.offHigh) "HIGH" else "LOW"}",
                                    style = NodelixMonoStyles.technical,
                                    color = NodelixTextSecondary
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = { pendingRelayAction = "all" to false },
                                enabled = s.online,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Turn ALL Relays OFF")
                            }
                        }
                    }
                }

                item {
                    SectionHeader("Sensor Calibration", "ADC tap gain, current zero & gain")
                    NodelixCard {
                        Text(
                            "Voltage Tap Calibration (measure with a multimeter, then enter the real value):",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("s1", "s2", "s3").forEach { tap ->
                                FilterChip(
                                    selected = selectedTapId == tap,
                                    onClick = { selectedTapId = tap },
                                    label = { Text(tap.uppercase()) }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = tapActualVoltage,
                                onValueChange = { tapActualVoltage = it.filter { c -> c.isDigit() || c == '.' } },
                                label = { Text("Actual Volts") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    tapActualVoltage.toDoubleOrNull()?.let { v ->
                                        viewModel.calibrateEsp32("tap", selectedTapId, v)
                                    }
                                },
                                enabled = s.online && tapActualVoltage.toDoubleOrNull() != null,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NodelixPrimary,
                                    contentColor = Color(0xFF002116)
                                )
                            ) {
                                Text("Calibrate", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Current Gain Calibration (compare against a clamp meter under load ≥ 0.2 A):",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = gainActualCurrent,
                                onValueChange = { gainActualCurrent = it.filter { c -> c.isDigit() || c == '.' } },
                                label = { Text("Actual Amps") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = {
                                    gainActualCurrent.toDoubleOrNull()?.let { a ->
                                        viewModel.calibrateEsp32("i-gain", null, a)
                                    }
                                },
                                enabled = s.online && gainActualCurrent.toDoubleOrNull() != null
                            ) {
                                Text("Set Gain")
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.calibrateEsp32("i-zero") },
                                enabled = s.online && !s.zeroingActive,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (s.zeroingActive) "Zeroing..." else "Zero Current")
                            }
                            OutlinedButton(
                                onClick = { confirmResetCal = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Reset Calibration")
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    pendingRelayAction?.let { (id, targetOn) ->
        ConfirmSensitiveActionDialog(
            title = "Confirm Relay Command",
            description = "Send POST /api/esp32/relay (id='$id', state=${if (targetOn) "ON" else "OFF"})?",
            confirmLabel = if (targetOn) "Switch ON" else "Switch OFF",
            isDestructive = id == "all",
            onConfirm = {
                viewModel.setRelay(id, targetOn)
                pendingRelayAction = null
            },
            onDismiss = { pendingRelayAction = null }
        )
    }

    if (confirmResetCal) {
        ConfirmSensitiveActionDialog(
            title = "Reset ESP32 Calibration?",
            description = "Restore all voltage tap gains and current floor/gain calibration factors to factory defaults (1.0 / 0.0)?",
            confirmLabel = "Reset Defaults",
            isDestructive = true,
            onConfirm = {
                viewModel.calibrateEsp32("reset")
                confirmResetCal = false
            },
            onDismiss = { confirmResetCal = false }
        )
    }
}

@Composable
private fun RelayControlRow(
    relay: RelayChannel,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(relay.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "ID ${relay.id} • CH ${relay.channel} • GPIO ${relay.pin ?: "N/A"}",
                style = NodelixMonoStyles.technical,
                color = NodelixTextSecondary
            )
        }
        StatusPill(
            text = if (relay.isOn) "ON" else "OFF",
            tone = if (relay.isOn) BadgeTone.SUCCESS else BadgeTone.NEUTRAL
        )
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = onToggle,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (relay.isOn) NodelixWarning else NodelixPrimary,
                contentColor = Color(0xFF002116)
            )
        ) {
            Text(if (relay.isOn) "Turn OFF" else "Turn ON", fontWeight = FontWeight.SemiBold)
        }
    }
}

// ==================== ESP8266 & DOOR LOCK DETAIL SCREEN ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoorDetailScreen(
    viewModel: DevicesViewModel,
    onNavigateBack: () -> Unit,
    onOpenFirmwareUpdater: () -> Unit
) {
    val door by viewModel.doorState.collectAsStateWithLifecycle()
    val banner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    var pendingDoorAction by remember { mutableStateOf<ControlDoorUseCase.Action?>(null) }
    var cardToRename by remember { mutableStateOf<RfidCard?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var cardToDelete by remember { mutableStateOf<RfidCard?>(null) }
    var cardForAdminPrompt by remember { mutableStateOf<RfidCard?>(null) }
    var adminPasswordInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.refreshDoor()
    }

    Scaffold(
        containerColor = NodelixBackground,
        topBar = {
            TopAppBar(
                title = { Text("ESP8266 Door Lock Control") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenFirmwareUpdater) {
                        Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = "Update Firmware")
                    }
                    IconButton(onClick = { viewModel.refreshDoor() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh Status")
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

            val d = door
            if (d == null) {
                item {
                    EmptyOrStatusCard(
                        icon = Icons.Outlined.DoorFront,
                        title = "Door Controller Not Loaded",
                        description = "Tap REFRESH STATUS to query /api/door/status.",
                        actionLabel = "REFRESH STATUS",
                        onAction = { viewModel.refreshDoor() }
                    )
                }
            } else {
                // Primary Door Actuation Panel (Part 9.3: OPEN DOOR / CLOSE DOOR / REFRESH STATUS)
                item {
                    SectionHeader("Door Lock Actuation", "Authenticated, audited, non-retried physical control")
                    NodelixCard(
                        accentBorder = if (d.isDoorOpen) NodelixWarning else NodelixPrimary
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = if (!d.online) "OFFLINE" else if (d.isDoorOpen) "DOOR UNLOCKED" else "DOOR LOCKED",
                                    style = NodelixMonoStyles.metricValue,
                                    color = if (d.isDoorOpen) NodelixWarning else NodelixPrimary
                                )
                                Text(
                                    text = "IP ${d.ipAddress ?: "N/A"} • FW ${d.firmwareVersion ?: "N/A"} • Sync ${if (d.isConfigSynced) "OK" else "PENDING"}",
                                    style = NodelixMonoStyles.technical,
                                    color = NodelixTextSecondary
                                )
                            }
                            Icon(
                                imageVector = if (d.isDoorOpen) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = if (d.isDoorOpen) NodelixWarning else NodelixPrimary,
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { pendingDoorAction = ControlDoorUseCase.Action.OPEN },
                                enabled = d.online && !isBusy,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NodelixPrimary,
                                    contentColor = Color(0xFF002116)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("OPEN DOOR", fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = { pendingDoorAction = ControlDoorUseCase.Action.CLOSE },
                                enabled = d.online && !isBusy,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NodelixSecondary,
                                    contentColor = Color(0xFF001F29)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("CLOSE DOOR", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.refreshDoor() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("REFRESH STATUS")
                        }
                    }
                }

                // RFID Lock & Duration Settings
                item {
                    SectionHeader("RFID Access Configuration")
                    var sliderSec by remember(d.configuredOpenSeconds) {
                        mutableFloatStateOf(d.configuredOpenSeconds.toFloat())
                    }
                    NodelixCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Allow Registered RFID Cards", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "When disabled, standard RFID taps are rejected while remote/admin remains active.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Switch(
                                checked = d.isRfidEnabled,
                                onCheckedChange = { viewModel.toggleRfidLock(it) }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Unlock Relay Duration: ${sliderSec.roundToInt()} sec (0 = pulse)",
                            style = NodelixMonoStyles.technical
                        )
                        Slider(
                            value = sliderSec,
                            onValueChange = { sliderSec = it },
                            valueRange = 0f..10f,
                            steps = 9
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.saveDoorOpenSeconds(sliderSec.roundToInt()) }
                            ) {
                                Text("Save Duration")
                            }
                            OutlinedButton(
                                onClick = { viewModel.setEnrollMode(!d.enroll.active) },
                                enabled = d.online
                            ) {
                                Icon(Icons.Outlined.Badge, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (d.enroll.active) "Cancel Enroll (${d.enroll.secondsLeft}s)"
                                    else "Enroll New Card"
                                )
                            }
                        }
                    }
                }

                // Registered RFID Cards
                item {
                    SectionHeader("Registered RFID Cards (${d.cards.size})")
                }
                if (d.cards.isEmpty()) {
                    item {
                        EmptyOrStatusCard(
                            icon = Icons.Outlined.Badge,
                            title = "No RFID Cards Registered",
                            description = "Tap 'Enroll New Card' and present an RFID tag to the ESP8266 reader."
                        )
                    }
                } else {
                    items(d.cards, key = { it.uid }) { card ->
                        NodelixCard(secondarySurface = true) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(card.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = "UID: ${card.uid} • Last: ${DateFormatter.formatRelativeAge(card.lastUsedMillis)}",
                                        style = NodelixMonoStyles.technical,
                                        color = NodelixTextSecondary
                                    )
                                }
                                if (card.isAdmin) {
                                    StatusPill("ADMIN CARD", BadgeTone.WARNING, showDot = false)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    cardToRename = card
                                    renameInput = card.name
                                }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Rename")
                                }
                                OutlinedButton(onClick = {
                                    if (card.isAdmin) {
                                        viewModel.setCardAdmin(card.uid, makeAdmin = false, adminPassword = null)
                                    } else {
                                        cardForAdminPrompt = card
                                        adminPasswordInput = ""
                                    }
                                }) {
                                    Text(if (card.isAdmin) "Revoke Admin" else "Grant Admin")
                                }
                                IconButton(onClick = { cardToDelete = card }) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete", tint = NodelixError)
                                }
                            }
                        }
                    }
                }

                // Door Audit Event Log
                item {
                    SectionHeader("Door Audit Event Log (${d.eventLog.size})")
                }
                items(d.eventLog.take(25)) { entry ->
                    NodelixCard(secondarySurface = true) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatusPill(
                                text = entry.type,
                                tone = when (entry.type) {
                                    "ok", "remote" -> BadgeTone.SUCCESS
                                    "warn" -> BadgeTone.WARNING
                                    "bad" -> BadgeTone.ERROR
                                    else -> BadgeTone.INFO
                                },
                                showDot = false
                            )
                            Text(
                                text = DateFormatter.formatEpochMillis(entry.timestampMillis),
                                style = NodelixMonoStyles.technical,
                                color = NodelixTextSecondary
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(entry.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    pendingDoorAction?.let { action ->
        ConfirmSensitiveActionDialog(
            title = if (action == ControlDoorUseCase.Action.OPEN) "Confirm OPEN DOOR" else "Confirm CLOSE DOOR",
            description = if (action == ControlDoorUseCase.Action.OPEN) {
                "Unlock physical door via server-validated API? Safety notice: on network timeout, the command will NOT be auto-retried."
            } else {
                "Engage door lock immediately?"
            },
            confirmLabel = if (action == ControlDoorUseCase.Action.OPEN) "OPEN DOOR" else "CLOSE DOOR",
            isDestructive = action == ControlDoorUseCase.Action.OPEN,
            onConfirm = {
                viewModel.executeDoorAction(action)
                pendingDoorAction = null
            },
            onDismiss = { pendingDoorAction = null }
        )
    }

    cardToDelete?.let { card ->
        ConfirmSensitiveActionDialog(
            title = "Delete RFID Card?",
            description = "Remove '${card.name}' (${card.uid}) from authorized door access?",
            confirmLabel = "Delete Card",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteCard(card.uid)
                cardToDelete = null
            },
            onDismiss = { cardToDelete = null }
        )
    }

    cardToRename?.let { card ->
        AlertDialog(
            onDismissRequest = { cardToRename = null },
            title = { Text("Rename RFID Card") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Card Holder Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.renameCard(card.uid, renameInput)
                    cardToRename = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { cardToRename = null }) { Text("Cancel") }
            }
        )
    }

    cardForAdminPrompt?.let { card ->
        AlertDialog(
            onDismissRequest = { cardForAdminPrompt = null },
            title = { Text("Verify Admin Password") },
            text = {
                Column {
                    Text("Server requires dashboard password verification to promote '${card.name}' to Admin Card.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = adminPasswordInput,
                        onValueChange = { adminPasswordInput = it },
                        label = { Text("Admin Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.setCardAdmin(card.uid, makeAdmin = true, adminPassword = adminPasswordInput)
                    adminPasswordInput = ""
                    cardForAdminPrompt = null
                }) { Text("Verify & Grant") }
            },
            dismissButton = {
                TextButton(onClick = { cardForAdminPrompt = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DeviceMetaRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = NodelixTextSecondary)
        Text(value, style = NodelixMonoStyles.technical)
    }
}
