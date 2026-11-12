package com.nodelix.coreos.ui.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nodelix.coreos.core.AppContainer
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.domain.model.AndroidAppUpdateState
import com.nodelix.coreos.domain.model.FirmwareDeviceState
import com.nodelix.coreos.domain.model.FirmwareFlashProgress
import com.nodelix.coreos.domain.model.ServerUpdateState
import com.nodelix.coreos.ui.components.BadgeTone
import com.nodelix.coreos.ui.components.ConfirmSensitiveActionDialog
import com.nodelix.coreos.ui.components.NodelixCard
import com.nodelix.coreos.ui.components.SectionHeader
import com.nodelix.coreos.ui.components.StatusPill
import com.nodelix.coreos.ui.theme.NodelixBackground
import com.nodelix.coreos.ui.theme.NodelixError
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

data class PendingFirmwareSelection(
    val deviceKey: String,
    val uri: Uri,
    val fileName: String
)

class UpdateCenterViewModel(
    private val container: AppContainer
) : ViewModel() {

    val serverUpdate: StateFlow<ServerUpdateState?> = container.updateRepository.serverUpdateState
    val firmwareDevices: StateFlow<List<FirmwareDeviceState>> = container.updateRepository.firmwareDevices
    val flashProgress: StateFlow<FirmwareFlashProgress?> = container.updateRepository.firmwareFlashProgress
    val lastFlash: StateFlow<com.nodelix.coreos.domain.model.LastFirmwareFlash?> =
        container.updateRepository.lastFirmwareFlash
    val androidUpdate: StateFlow<AndroidAppUpdateState> = container.updateRepository.androidUpdateState

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _statusBanner = MutableStateFlow<Pair<String, Boolean>?>(null)
    val statusBanner: StateFlow<Pair<String, Boolean>?> = _statusBanner.asStateFlow()

    fun refreshAll() {
        viewModelScope.launch {
            _isBusy.value = true
            when (val res = container.updateRepository.refreshUpdateCenter()) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value = null
            }
            _isBusy.value = false
        }
    }

    fun checkServerGitUpdate() {
        viewModelScope.launch {
            _isBusy.value = true
            when (val res = container.updateRepository.checkServerUpdateManual()) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> {
                    val msg = if (res.data.updateAvailable) {
                        "New server commit detected: ${res.data.latestShort}"
                    } else {
                        "NodelixcoreOS Server is up to date (${res.data.installedShort})."
                    }
                    _statusBanner.value = msg to false
                }
            }
            _isBusy.value = false
        }
    }

    fun resendTelegramNotification() {
        viewModelScope.launch {
            _isBusy.value = true
            when (val res = container.updateRepository.resendUpdateNotification()) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value =
                    "Telegram notification re-sent for ${res.data.latestShort.ifBlank { "server update" }}." to false
            }
            _isBusy.value = false
        }
    }

    fun flashConfirmed(selection: PendingFirmwareSelection) {
        viewModelScope.launch {
            _isBusy.value = true
            val res = container.updateRepository.flashEspFirmware(
                deviceKey = selection.deviceKey,
                firmwareUri = selection.uri,
                fileName = selection.fileName
            )
            when (res) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value = res.data to false
            }
            _isBusy.value = false
        }
    }

    fun checkMobileApkUpdate() {
        viewModelScope.launch {
            _isBusy.value = true
            when (val res = container.updateRepository.checkAndroidAppUpdate()) {
                is AppResult.Failure -> _statusBanner.value = res.error.userMessage to true
                is AppResult.Success -> _statusBanner.value = (res.data.statusMessage ?: "Checked GitHub releases.") to false
            }
            _isBusy.value = false
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    UpdateCenterViewModel(container) as T
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateCenterScreen(
    viewModel: UpdateCenterViewModel,
    onNavigateBack: () -> Unit
) {
    val serverUpd by viewModel.serverUpdate.collectAsStateWithLifecycle()
    val fwDevices by viewModel.firmwareDevices.collectAsStateWithLifecycle()
    val flashProg by viewModel.flashProgress.collectAsStateWithLifecycle()
    val lastFlash by viewModel.lastFlash.collectAsStateWithLifecycle()
    val appUpd by viewModel.androidUpdate.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val banner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val mobileReleaseCheckDisabled = appUpd.statusMessage?.contains("HTTP-only mode", ignoreCase = true) == true

    var pickingDeviceKey by remember { mutableStateOf<String?>(null) }
    var pendingFlash by remember { mutableStateOf<PendingFirmwareSelection?>(null) }
    var pendingOpenReleaseUrl by remember { mutableStateOf<String?>(null) }

    val binPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val devKey = pickingDeviceKey
        pickingDeviceKey = null
        if (uri != null && devKey != null) {
            val fileName = resolveFileName(context, uri)
            pendingFlash = PendingFirmwareSelection(devKey, uri, fileName)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshAll()
    }

    Scaffold(
        containerColor = NodelixBackground,
        topBar = {
            TopAppBar(
                title = { Text("Update Center") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
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

            flashProg?.let { p ->
                item {
                    NodelixCard(accentBorder = NodelixSecondary) {
                        Text(
                            "OTA Progress (${p.device.uppercase()}): ${p.phase.uppercase()}",
                            style = MaterialTheme.typography.titleMedium,
                            color = NodelixSecondary
                        )
                        Text(p.message, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            lastFlash?.let { last ->
                item {
                    NodelixCard(secondarySurface = true) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Last OTA Flash (${last.device.uppercase()})",
                                style = MaterialTheme.typography.titleMedium
                            )
                            StatusPill(
                                text = if (last.success) "SUCCESS" else "FAILED",
                                tone = if (last.success) BadgeTone.SUCCESS else BadgeTone.ERROR
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "At: ${DateFormatter.formatEpochMillis(last.timestampMillis)}" +
                                (last.firmwareVersion?.let { " • FW: $it" } ?: "") +
                                (last.durationMs?.let { " • ${it}ms" } ?: "") +
                                (last.sizeBytes?.let { " • ${FileUtils.formatBytes(it)}" } ?: ""),
                            style = NodelixMonoStyles.technical,
                            color = NodelixTextSecondary
                        )
                        if (!last.error.isNullOrBlank()) {
                            Text(last.error!!, style = MaterialTheme.typography.bodyMedium, color = NodelixError)
                        }
                    }
                }
            }

            // 1. NODELIXCOREOS SERVER UPDATE
            item {
                SectionHeader("1. NodelixcoreOS Server Update", "GitHub origin commit detector (updater.js)")
                NodelixCard {
                    val s = serverUpd
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(s?.repo ?: "arel342/NodelixcoreOS", style = MaterialTheme.typography.titleMedium)
                        StatusPill(
                            text = if (s?.updateAvailable == true) "UPDATE AVAILABLE" else "UP TO DATE",
                            tone = if (s?.updateAvailable == true) BadgeTone.WARNING else BadgeTone.SUCCESS
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Installed: ${s?.installedShort?.ifBlank { "N/A" } ?: "N/A"} • Latest: ${s?.latestShort?.ifBlank { "N/A" } ?: "N/A"}",
                        style = NodelixMonoStyles.technical
                    )
                    Text(
                        text = "Last Checked: ${DateFormatter.formatEpochMillis(s?.lastCheckedAtMillis)}",
                        style = NodelixMonoStyles.technical,
                        color = NodelixTextSecondary
                    )
                    if (!s?.commits.isNullOrEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Incoming Commits / Changelog:", style = MaterialTheme.typography.titleMedium)
                        s!!.commits.take(5).forEach { c ->
                            Text(
                                text = "• [${c.shortHash}] ${c.subject} (${c.author})",
                                style = NodelixMonoStyles.technical,
                                color = NodelixTextSecondary
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Policy: NodelixcoreOS backend checks updates via `git fetch origin` only and intentionally requires manual administrator `git pull` on the host.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { viewModel.checkServerGitUpdate() },
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NodelixPrimary,
                                contentColor = Color(0xFF002116)
                            )
                        ) {
                            Text("Check Server Update", fontWeight = FontWeight.SemiBold)
                        }
                        if (s?.updateAvailable == true) {
                            OutlinedButton(
                                onClick = { viewModel.resendTelegramNotification() },
                                enabled = !isBusy
                            ) {
                                Text("Notify via Telegram")
                            }
                        }
                    }
                }
            }

            // 2. ESP32 FIRMWARE UPDATE
            item {
                val esp32Fw = fwDevices.firstOrNull { it.key == "esp32" }
                SectionHeader("2. ESP32 Firmware Update", "OTA .bin flasher via /api/firmware/flash")
                FirmwareCategoryCard(
                    title = "ESP32 Sensor & Relay Node",
                    device = esp32Fw,
                    isBusy = isBusy,
                    onSelectBin = {
                        pickingDeviceKey = "esp32"
                        binPicker.launch(arrayOf("application/octet-stream", "*/*"))
                    }
                )
            }

            // 3. ESP8266 FIRMWARE UPDATE
            item {
                val esp8266Fw = fwDevices.firstOrNull { it.key == "esp8266" }
                SectionHeader("3. ESP8266 Firmware Update", "OTA .bin flasher for RFID Door Controller")
                FirmwareCategoryCard(
                    title = "ESP8266 RFID Door Node",
                    device = esp8266Fw,
                    isBusy = isBusy,
                    onSelectBin = {
                        pickingDeviceKey = "esp8266"
                        binPicker.launch(arrayOf("application/octet-stream", "*/*"))
                    }
                )
            }

            // 4. ANDROID APPLICATION UPDATE
            item {
                SectionHeader("4. Android Application Update", "NodelixcoreOS Mobile APK release status")
                NodelixCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("NodelixcoreOS Mobile", style = MaterialTheme.typography.titleMedium)
                        StatusPill(
                            text = when {
                                mobileReleaseCheckDisabled -> "DISABLED"
                                appUpd.updateAvailable -> "NEW APK"
                                else -> "CURRENT"
                            },
                            tone = when {
                                mobileReleaseCheckDisabled -> BadgeTone.WARNING
                                appUpd.updateAvailable -> BadgeTone.INFO
                                else -> BadgeTone.SUCCESS
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Current: v${appUpd.currentVersion} • Latest: ${if (mobileReleaseCheckDisabled) "Disabled (HTTP-only)" else appUpd.latestVersion?.let { "v$it" } ?: "Unchecked"}",
                        style = NodelixMonoStyles.technical
                    )
                    appUpd.assetSizeBytes?.let {
                        Text(
                            text = "APK Size: ${FileUtils.formatBytes(it)}",
                            style = NodelixMonoStyles.technical,
                            color = NodelixTextSecondary
                        )
                    }
                    if (!appUpd.releaseNotes.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(appUpd.releaseNotes!!, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.checkMobileApkUpdate() },
                            enabled = !isBusy && !mobileReleaseCheckDisabled
                        ) {
                            Text(if (mobileReleaseCheckDisabled) "Release Check Disabled" else "Check APK Update")
                        }
                        if (appUpd.updateAvailable && !appUpd.releaseHtmlUrl.isNullOrBlank()) {
                            Button(
                                onClick = { pendingOpenReleaseUrl = appUpd.releaseHtmlUrl },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NodelixSecondary,
                                    contentColor = Color(0xFF001F29)
                                )
                            ) {
                                Text("Download Release", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    pendingFlash?.let { sel ->
        ConfirmSensitiveActionDialog(
            title = "Confirm ${sel.deviceKey.uppercase()} OTA Firmware Flash",
            description = "Upload '${sel.fileName}' (validated for 0xE9 ESP magic header) and reboot '${sel.deviceKey.uppercase()}'? Do not power off the microcontroller during flashing.",
            confirmLabel = "Confirm & Flash",
            isDestructive = true,
            onConfirm = {
                viewModel.flashConfirmed(sel)
                pendingFlash = null
            },
            onDismiss = { pendingFlash = null }
        )
    }

    pendingOpenReleaseUrl?.let { url ->
        ConfirmSensitiveActionDialog(
            title = "Open Official Release Page?",
            description = "Open GitHub Release ($url) in your browser to review and download the signed APK?",
            confirmLabel = "Open Browser",
            onConfirm = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                pendingOpenReleaseUrl = null
            },
            onDismiss = { pendingOpenReleaseUrl = null }
        )
    }
}

@Composable
private fun FirmwareCategoryCard(
    title: String,
    device: FirmwareDeviceState?,
    isBusy: Boolean,
    onSelectBin: () -> Unit
) {
    NodelixCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            StatusPill(
                text = when {
                    device?.updating == true -> "FLASHING"
                    device?.online == true -> "ONLINE"
                    else -> "OFFLINE"
                },
                tone = if (device?.online == true) BadgeTone.SUCCESS else BadgeTone.WARNING
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Current Firmware: ${device?.currentFirmware?.ifBlank { "N/A" } ?: "N/A"}",
            style = NodelixMonoStyles.technical
        )
        Text(
            text = "Endpoint: ${device?.host?.ifBlank { "Unconfigured" } ?: "N/A"}:${device?.port ?: 80} • RSSI: ${device?.rssiDbm?.let { "$it dBm" } ?: "N/A"}",
            style = NodelixMonoStyles.technical,
            color = NodelixTextSecondary
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onSelectBin,
            enabled = !isBusy && device?.online == true
        ) {
            Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null)
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text("Select Firmware (.bin) & Flash")
        }
    }
}

private fun resolveFileName(context: Context, uri: Uri): String {
    var result = "firmware.bin"
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) result = c.getString(idx) ?: result
            }
        }
    }
    return result
}
