package com.nodelix.coreos.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.nodelix.coreos.core.AppConfig
import com.nodelix.coreos.core.AppError
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.data.local.DiagnosticLogStore
import com.nodelix.coreos.data.local.PreferencesDataStore
import com.nodelix.coreos.data.remote.ProgressRequestBody
import com.nodelix.coreos.domain.model.AndroidAppUpdateState
import com.nodelix.coreos.domain.model.AppNotification
import com.nodelix.coreos.domain.model.DiagnosticLogEntry
import com.nodelix.coreos.domain.model.DiagnosticSummary
import com.nodelix.coreos.domain.model.FirmwareDeviceState
import com.nodelix.coreos.domain.model.FirmwareFlashProgress
import com.nodelix.coreos.domain.model.GitCommitSummary
import com.nodelix.coreos.domain.model.LastFirmwareFlash
import com.nodelix.coreos.domain.model.LogSeverity
import com.nodelix.coreos.domain.model.NotificationCategory
import com.nodelix.coreos.domain.model.ServerUpdateState
import com.nodelix.coreos.domain.repository.DeviceControlRepository
import com.nodelix.coreos.domain.repository.DiagnosticsRepository
import com.nodelix.coreos.domain.repository.NotificationCenterRepository
import com.nodelix.coreos.domain.repository.ServerProfileRepository
import com.nodelix.coreos.domain.repository.SystemMonitorRepository
import com.nodelix.coreos.domain.repository.UpdateCenterRepository
import com.nodelix.coreos.network.ApiClient
import com.nodelix.coreos.network.NetworkMonitor
import com.nodelix.coreos.network.NetworkResultMapper
import com.nodelix.coreos.network.RealtimeServerEvent
import com.nodelix.coreos.network.WebSocketManager
import com.nodelix.coreos.network.WsConnectionState
import com.nodelix.coreos.security.SessionManager
import com.nodelix.coreos.util.Constants
import com.nodelix.coreos.util.DateFormatter
import com.nodelix.coreos.util.LogSanitizer
import com.nodelix.coreos.util.ValidationOutcome
import com.nodelix.coreos.util.Validators
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class NotificationCenterRepositoryImpl(
    scope: CoroutineScope,
    webSocketManagerProvider: () -> WebSocketManager
) : NotificationCenterRepository {

    private val _notifications = MutableStateFlow<List<AppNotification>>(emptyList())
    override val notifications: StateFlow<List<AppNotification>> = _notifications.asStateFlow()

    override val unreadCount: StateFlow<Int> = _notifications
        .map { list -> list.count { !it.isRead } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    private val recentDedupKeys = LinkedHashMap<String, Long>()

    init {
        scope.launch {
            webSocketManagerProvider().events.collect { event ->
                when (event) {
                    is RealtimeServerEvent.FirmwareProgress -> {
                        pushNotification(
                            title = "Firmware OTA (${event.data.device.uppercase()})",
                            description = "${event.data.phase.uppercase()}: ${event.data.message}",
                            category = NotificationCategory.FIRMWARE
                        )
                    }

                    is RealtimeServerEvent.UpdateSnapshot -> {
                        if (event.data.updateAvailable && event.data.latestShort.isNotBlank()) {
                            pushNotification(
                                title = "Server Update Available (${event.data.latestShort})",
                                description = event.data.commits.firstOrNull()?.subject
                                    ?: "New commit detected on ${event.data.repo}@${event.data.branch}",
                                category = NotificationCategory.UPDATE,
                                dedupKey = "server_update_${event.data.latestCommit}"
                            )
                        }
                    }

                    is RealtimeServerEvent.GenericNotification -> {
                        pushNotification(
                            title = event.title,
                            description = event.body,
                            category = NotificationCategory.SYSTEM
                        )
                    }

                    else -> Unit
                }
            }
        }
    }

    override fun pushNotification(
        title: String,
        description: String,
        category: NotificationCategory,
        dedupKey: String?
    ) {
        val now = System.currentTimeMillis()
        if (dedupKey != null) {
            synchronized(recentDedupKeys) {
                val prev = recentDedupKeys[dedupKey]
                if (prev != null && now - prev < 300_000L) return
                recentDedupKeys[dedupKey] = now
                if (recentDedupKeys.size > 100) {
                    recentDedupKeys.remove(recentDedupKeys.keys.first())
                }
            }
        }

        val item = AppNotification(
            id = UUID.randomUUID().toString(),
            title = LogSanitizer.sanitize(title),
            description = LogSanitizer.sanitize(description),
            timestampMillis = now,
            category = category,
            severity = LogSeverity.INFO,
            isRead = false
        )
        _notifications.update { current -> (listOf(item) + current).take(200) }
    }

    override fun recordLocalNotification(notification: AppNotification) {
        val sanitized = notification.copy(
            title = LogSanitizer.sanitize(notification.title),
            description = LogSanitizer.sanitize(notification.description)
        )
        _notifications.update { current -> (listOf(sanitized) + current).take(200) }
    }

    override suspend fun markAsRead(notificationId: String) {
        _notifications.update { list ->
            list.map { if (it.id == notificationId) it.copy(isRead = true) else it }
        }
    }

    override suspend fun markAllAsRead() {
        _notifications.update { list -> list.map { it.copy(isRead = true) } }
    }

    override suspend fun clearNotifications(category: NotificationCategory?) {
        _notifications.update { list ->
            if (category == null) emptyList() else list.filterNot { it.category == category }
        }
    }
}

class UpdateCenterRepositoryImpl(
    scope: CoroutineScope,
    private val appContext: Context,
    private val apiClient: ApiClient,
    private val webSocketManager: WebSocketManager,
    private val sessionManager: SessionManager,
    private val logStore: DiagnosticLogStore,
    private val json: Json
) : UpdateCenterRepository {

    private val _serverUpdateState = MutableStateFlow<ServerUpdateState?>(null)
    override val serverUpdateState: StateFlow<ServerUpdateState?> = _serverUpdateState.asStateFlow()

    private val _firmwareDevices = MutableStateFlow<List<FirmwareDeviceState>>(emptyList())
    override val firmwareDevices: StateFlow<List<FirmwareDeviceState>> = _firmwareDevices.asStateFlow()

    private val _firmwareFlashProgress = MutableStateFlow<FirmwareFlashProgress?>(null)
    override val firmwareFlashProgress: StateFlow<FirmwareFlashProgress?> = _firmwareFlashProgress.asStateFlow()

    private val _lastFirmwareFlash = MutableStateFlow<LastFirmwareFlash?>(null)
    override val lastFirmwareFlash: StateFlow<LastFirmwareFlash?> = _lastFirmwareFlash.asStateFlow()

    /** Server-configured `OTA_MAX_MB`, refreshed from `GET /api/firmware`. */
    @Volatile
    private var serverOtaMaxMb: Int = Constants.DEFAULT_OTA_MAX_MB

    private val _androidUpdateState = MutableStateFlow(
        AndroidAppUpdateState(
            currentVersion = AppConfig.VERSION_NAME,
            latestVersion = null,
            updateAvailable = false,
            releaseNotes = null,
            releaseHtmlUrl = null,
            assetSizeBytes = null,
            checkedAtMillis = null,
            statusMessage = "Mobile release check disabled in HTTP-only mode."
        )
    )
    override val androidUpdateState: StateFlow<AndroidAppUpdateState> = _androidUpdateState.asStateFlow()

    init {
        scope.launch {
            webSocketManager.events.collect { event ->
                when (event) {
                    is RealtimeServerEvent.UpdateSnapshot -> {
                        _serverUpdateState.value = mapUpdaterDto(event.data)
                    }

                    is RealtimeServerEvent.FirmwareProgress -> {
                        val d = event.data
                        _firmwareFlashProgress.value = FirmwareFlashProgress(
                            device = d.device,
                            phase = d.phase,
                            message = d.message,
                            timestampMillis = d.at.takeIf { it > 0L } ?: System.currentTimeMillis(),
                            newFirmwareVersion = d.fw
                        )
                    }

                    else -> Unit
                }
            }
        }
    }

    override suspend fun refreshUpdateCenter(): AppResult<Unit> = coroutineScope {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return@coroutineScope r
            is AppResult.Success -> r.data
        }
        try {
            val updDeferred = async { svc.getServerUpdateStatus() }
            val fwDeferred = async { svc.getFirmwareDevices() }

            val updResp = updDeferred.await()
            val fwResp = fwDeferred.await()

            if (updResp.isSuccessful && updResp.body() != null) {
                _serverUpdateState.value = mapUpdaterDto(updResp.body()!!)
            }
            if (fwResp.isSuccessful && fwResp.body() != null) {
                val fwBody = fwResp.body()!!
                if (fwBody.maxMB > 0) serverOtaMaxMb = fwBody.maxMB
                _firmwareDevices.value = fwBody.devices.map { d ->
                    FirmwareDeviceState(
                        key = d.key,
                        label = d.label,
                        host = d.host,
                        port = d.port,
                        configured = d.configured,
                        online = d.online,
                        currentFirmware = d.fw,
                        rssiDbm = d.rssi,
                        uptimeSeconds = d.uptime,
                        updating = d.updating
                    )
                }
                _lastFirmwareFlash.value = fwBody.last?.takeIf { it.at > 0L }?.let { l ->
                    LastFirmwareFlash(
                        timestampMillis = l.at,
                        device = l.device,
                        success = l.ok,
                        firmwareVersion = l.fw,
                        durationMs = l.ms,
                        sizeBytes = l.size,
                        error = l.error
                    )
                }
            }
            if (!updResp.isSuccessful) {
                return@coroutineScope NetworkResultMapper.mapResponse(updResp, json) {
                    sessionManager.onUnauthorizedOrExpired()
                }.let {
                    when (it) {
                        is AppResult.Failure -> it
                        is AppResult.Success -> AppResult.Success(Unit)
                    }
                }
            }
            AppResult.Success(Unit)
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "UpdateCenter") {
                sessionManager.onUnauthorizedOrExpired()
            }
        }
    }

    override suspend fun checkServerUpdateManual(): AppResult<ServerUpdateState> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.checkServerUpdate()
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    val state = mapUpdaterDto(mapped.data)
                    _serverUpdateState.value = state
                    AppResult.Success(state)
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "UpdateCenter") {
                sessionManager.onUnauthorizedOrExpired()
            }
        }
    }

    override suspend fun resendUpdateNotification(): AppResult<ServerUpdateState> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.resendUpdateNotification()
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    val state = mapUpdaterDto(mapped.data)
                    _serverUpdateState.value = state
                    AppResult.Success(state)
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "UpdateCenter") {
                sessionManager.onUnauthorizedOrExpired()
            }
        }
    }

    override suspend fun flashEspFirmware(
        deviceKey: String,
        firmwareUri: Uri,
        fileName: String
    ): AppResult<String> = withContext(Dispatchers.IO) {
        if (deviceKey != "esp32" && deviceKey != "esp8266") {
            return@withContext AppResult.Failure(AppError.BadRequest("Device must be esp32 or esp8266."))
        }
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return@withContext r
            is AppResult.Success -> r.data
        }

        try {
            // The firmware image is streamed straight from the SAF Uri: nothing larger
            // than a 16-byte header probe is ever buffered in RAM, and the configured
            // OTA ceiling is enforced both up-front (known size) and while streaming.
            val maxBytes = serverOtaMaxMb.coerceAtLeast(1) * 1024L * 1024L
            val sizeBytes = resolveContentLength(firmwareUri)
            if (sizeBytes > maxBytes) {
                return@withContext AppResult.Failure(
                    AppError.BadRequest(
                        "Firmware binary (${sizeBytes} bytes) exceeds the server OTA limit (${serverOtaMaxMb} MB)."
                    )
                )
            }

            val headerBytes = readHeaderBytes(firmwareUri, 16)
                ?: return@withContext AppResult.Failure(AppError.BadRequest("Unable to read selected .bin file."))

            when (val check = Validators.isValidEspFirmwareHeader(fileName, headerBytes)) {
                is ValidationOutcome.Invalid ->
                    return@withContext AppResult.Failure(AppError.BadRequest(check.reason))
                is ValidationOutcome.Valid -> Unit
            }

            _firmwareFlashProgress.value = FirmwareFlashProgress(
                device = deviceKey,
                phase = "send",
                message = "Uploading verified .bin firmware to server...",
                timestampMillis = System.currentTimeMillis()
            )

            val uploadBody = ProgressRequestBody(
                contentType = "application/octet-stream".toMediaTypeOrNull(),
                contentLengthBytes = sizeBytes,
                cancelledFlag = AtomicBoolean(false),
                inputStreamProvider = {
                    appContext.contentResolver.openInputStream(firmwareUri)?.let { stream ->
                        CappedInputStream(stream, maxBytes)
                    }
                },
                onProgress = { written, total ->
                    val suffix = if (total > 0) " of ${total / 1024} KB" else ""
                    _firmwareFlashProgress.value = FirmwareFlashProgress(
                        device = deviceKey,
                        phase = "send",
                        message = "Uploading firmware: ${written / 1024} KB$suffix",
                        timestampMillis = System.currentTimeMillis()
                    )
                }
            )

            val devicePart = deviceKey.toRequestBody("text/plain".toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", fileName.trim(), uploadBody)

            val resp = svc.flashFirmware(device = devicePart, file = filePart)
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> {
                    _firmwareFlashProgress.value = FirmwareFlashProgress(
                        device = deviceKey,
                        phase = "error",
                        message = mapped.error.userMessage,
                        timestampMillis = System.currentTimeMillis()
                    )
                    mapped
                }
                is AppResult.Success -> {
                    val doneMsg = mapped.data.message ?: "Firmware flashed successfully."
                    _firmwareFlashProgress.value = FirmwareFlashProgress(
                        device = deviceKey,
                        phase = "done",
                        message = doneMsg,
                        timestampMillis = System.currentTimeMillis(),
                        newFirmwareVersion = mapped.data.fw
                    )
                    refreshUpdateCenter()
                    AppResult.Success(doneMsg)
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "FirmwareOTA") {
                sessionManager.onUnauthorizedOrExpired()
            }
        }
    }

    override suspend fun checkAndroidAppUpdate(): AppResult<AndroidAppUpdateState> {
        // The HTTP-only app deliberately does not contact GitHub's HTTPS-only API.
        // Server-side update metadata remains available through the configured LAN API.
        val state = AndroidAppUpdateState(
            currentVersion = AppConfig.VERSION_NAME,
            latestVersion = null,
            updateAvailable = false,
            releaseNotes = null,
            releaseHtmlUrl = null,
            assetSizeBytes = null,
            checkedAtMillis = System.currentTimeMillis(),
            statusMessage = "Mobile release check disabled in HTTP-only mode."
        )
        _androidUpdateState.value = state
        return AppResult.Success(state)
    }

    /** Reads the SAF document size, or -1 when the provider does not expose it. */
    private fun resolveContentLength(uri: Uri): Long = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use -1L
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0 || cursor.isNull(index)) -1L else cursor.getLong(index)
        } ?: -1L
    }.getOrDefault(-1L)

    /** Reads at most [count] leading bytes (enough for the 0xE9 ESP image header). */
    private fun readHeaderBytes(uri: Uri, count: Int): ByteArray? = runCatching {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(count)
            var read = 0
            while (read < count) {
                val chunk = input.read(buffer, read, count - read)
                if (chunk <= 0) break
                read += chunk
            }
            if (read == 0) null else buffer.copyOf(read)
        }
    }.getOrNull()

    /**
     * Fails the upload if a provider reports an unknown size but streams more than the
     * configured OTA ceiling, so an oversized image can never be pushed to the MCU.
     */
    private class CappedInputStream(
        private val delegate: java.io.InputStream,
        private val maxBytes: Long
    ) : java.io.InputStream() {
        private var served = 0L

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) {
                served += 1
                if (served > maxBytes) throw IOException("Firmware binary exceeds the server OTA limit.")
            }
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = delegate.read(b, off, len)
            if (read > 0) {
                served += read
                if (served > maxBytes) throw IOException("Firmware binary exceeds the server OTA limit.")
            }
            return read
        }

        override fun close() = delegate.close()
    }

    private fun mapUpdaterDto(dto: com.nodelix.coreos.data.model.UpdaterStatusDto): ServerUpdateState {
        return ServerUpdateState(
            enabled = dto.enabled,
            repo = dto.repo,
            branch = dto.branch,
            installedCommit = dto.installedCommit,
            installedShort = dto.installedShort,
            latestCommit = dto.latestCommit,
            latestShort = dto.latestShort,
            updateAvailable = dto.updateAvailable,
            detectedAtMillis = dto.detectedAt,
            lastCheckedAtMillis = dto.lastCheckedAt,
            lastCheckError = dto.lastCheckError,
            lastCheckErrorDetail = dto.lastCheckErrorDetail,
            commits = dto.commits.map { c ->
                GitCommitSummary(
                    hash = c.hash,
                    shortHash = c.short,
                    subject = c.subject,
                    author = c.author,
                    isoDate = c.date
                )
            },
            notifyStatus = dto.notifyStatus
        )
    }
}

class DiagnosticsRepositoryImpl(
    private val serverRepository: ServerProfileRepository,
    private val systemRepository: SystemMonitorRepository,
    private val deviceRepository: DeviceControlRepository,
    private val sessionManager: SessionManager,
    private val webSocketManager: WebSocketManager,
    private val networkMonitor: NetworkMonitor,
    private val preferencesDataStore: PreferencesDataStore,
    private val logStore: DiagnosticLogStore
) : DiagnosticsRepository {

    override val logs: StateFlow<List<DiagnosticLogEntry>> = logStore.logs

    override suspend fun buildDiagnosticSummary(): DiagnosticSummary {
        val profile = serverRepository.activeProfile.value
        val sys = systemRepository.systemOverview.value
        val net = networkMonitor.refreshNow()
        val prefs = preferencesDataStore.appPreferencesFlow.first()
        val session = sessionManager.sessionState.value

        return DiagnosticSummary(
            serverName = profile?.name,
            serverEndpoint = profile?.let { "${it.protocol.scheme}://${it.host}:${it.port}" },
            connectionState = serverRepository.connectionState.value,
            apiResponseTimeMs = sys?.responseTimeMs ?: profile?.lastResponseTimeMs,
            webSocketConnected = webSocketManager.state.value == WsConnectionState.CONNECTED,
            lastSyncMillis = sys?.freshness?.lastSyncMillis ?: prefs.lastDashboardSyncMillis,
            lastError = logStore.lastErrorSummary,
            androidNetworkOnline = net.isOnline,
            androidWifiConnected = net.isWifiOrEthernet,
            localIpv4Address = net.localIpv4Address,
            appVersion = "${AppConfig.VERSION_NAME} (${AppConfig.VERSION_CODE})",
            backendVersion = sys?.serverVersion,
            nodeVersion = sys?.nodeVersion,
            authMode = session.authMode,
            esp32Online = deviceRepository.esp32State.value?.online,
            doorOnline = deviceRepository.doorState.value?.online
        )
    }

    override suspend fun formatSanitizedReport(): String {
        val s = buildDiagnosticSummary()
        val recentLogs = logStore.logs.value.take(40)
        return buildString {
            appendLine("=== NODELIXCOREOS MOBILE DIAGNOSTIC REPORT ===")
            appendLine("Generated       : ${DateFormatter.formatEpochMillis(System.currentTimeMillis())}")
            appendLine("App Version     : ${s.appVersion}")
            appendLine("Backend Version : ${s.backendVersion ?: "N/A"} (Node ${s.nodeVersion ?: "N/A"})")
            appendLine("Server Profile  : ${s.serverName ?: "None"} (${s.serverEndpoint ?: "N/A"})")
            appendLine("Connection      : ${s.connectionState.name}")
            appendLine("API RTT         : ${s.apiResponseTimeMs?.let { "${it} ms" } ?: "N/A"}")
            appendLine("WebSocket       : ${if (s.webSocketConnected) "CONNECTED" else "DISCONNECTED"}")
            appendLine("Auth Mode       : ${s.authMode?.name ?: "UNAUTHENTICATED"}")
            appendLine("Last Sync       : ${DateFormatter.formatEpochMillis(s.lastSyncMillis)}")
            appendLine("Android Wi-Fi   : ${s.androidWifiConnected} (Local IP: ${s.localIpv4Address ?: "N/A"})")
            appendLine("ESP32 Online    : ${s.esp32Online ?: "Unknown"}")
            appendLine("ESP8266 Online  : ${s.doorOnline ?: "Unknown"}")
            appendLine("Last Error      : ${LogSanitizer.sanitize(s.lastError ?: "None")}")
            appendLine()
            appendLine("--- SANITIZED SYSTEM LOGS (LATEST ${recentLogs.size}) ---")
            recentLogs.forEach { entry ->
                appendLine(
                    "[${DateFormatter.formatEpochMillis(entry.timestampMillis)}] [${entry.severity.name}] [${entry.tag}] ${entry.sanitizedMessage}"
                )
            }
        }
    }

    override fun clearLogs() {
        logStore.clear()
    }
}
