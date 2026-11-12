package com.nodelix.coreos.data.repository

import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.data.local.DiagnosticLogStore
import com.nodelix.coreos.data.local.PreferencesDataStore
import com.nodelix.coreos.domain.model.DataFreshness
import com.nodelix.coreos.domain.model.MetricSample
import com.nodelix.coreos.domain.model.SystemOverview
import com.nodelix.coreos.domain.repository.SystemMonitorRepository
import com.nodelix.coreos.network.ApiClient
import com.nodelix.coreos.network.NetworkResultMapper
import com.nodelix.coreos.network.RealtimeServerEvent
import com.nodelix.coreos.network.WebSocketManager
import com.nodelix.coreos.security.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.system.measureTimeMillis

class SystemMonitorRepositoryImpl(
    scope: CoroutineScope,
    private val apiClient: ApiClient,
    private val webSocketManager: WebSocketManager,
    private val sessionManager: SessionManager,
    private val preferencesDataStore: PreferencesDataStore,
    private val logStore: DiagnosticLogStore,
    private val json: Json
) : SystemMonitorRepository {

    private val _systemOverview = MutableStateFlow<SystemOverview?>(null)
    override val systemOverview: StateFlow<SystemOverview?> = _systemOverview.asStateFlow()

    init {
        scope.launch {
            webSocketManager.events.collect { event ->
                when (event) {
                    is RealtimeServerEvent.SystemSnapshot -> {
                        val ws = event.data
                        val nowMs = System.currentTimeMillis()
                        _systemOverview.update { prev ->
                            val newSample = MetricSample(
                                timestampMillis = ws.t.takeIf { it > 0L } ?: nowMs,
                                cpuPercent = ws.cpu,
                                ramPercent = ws.ram?.pct
                            )
                            val mergedHistory = ((prev?.history.orEmpty()) + newSample)
                                .distinctBy { it.timestampMillis }
                                .takeLast(90)

                            SystemOverview(
                                cpuUsagePercent = ws.cpu ?: prev?.cpuUsagePercent,
                                cpuCores = ws.cores?.takeIf { it > 0 } ?: prev?.cpuCores,
                                cpuModel = ws.model?.takeIf { it.isNotBlank() } ?: prev?.cpuModel,
                                loadAverage1m = ws.load ?: prev?.loadAverage1m,
                                ramTotalBytes = ws.ram?.total ?: prev?.ramTotalBytes,
                                ramUsedBytes = ws.ram?.used ?: prev?.ramUsedBytes,
                                ramUsagePercent = ws.ram?.pct ?: prev?.ramUsagePercent,
                                storageReady = ws.flash?.ready ?: prev?.storageReady ?: false,
                                storageRoot = ws.flash?.root ?: prev?.storageRoot,
                                storageMode = prev?.storageMode,
                                storageTotalBytes = ws.flash?.total ?: prev?.storageTotalBytes,
                                storageFreeBytes = ws.flash?.free ?: prev?.storageFreeBytes,
                                storageMessage = ws.flash?.message ?: prev?.storageMessage,
                                serverUptimeSeconds = ws.uptime ?: prev?.serverUptimeSeconds,
                                serverVersion = prev?.serverVersion,
                                nodeVersion = prev?.nodeVersion,
                                lanAddresses = prev?.lanAddresses.orEmpty(),
                                activeWebSocketClients = prev?.activeWebSocketClients,
                                responseTimeMs = prev?.responseTimeMs,
                                unavailableMetrics = ws.unavailable,
                                history = mergedHistory,
                                freshness = DataFreshness(
                                    isFromCache = false,
                                    serverTimestampMillis = ws.t.takeIf { it > 0L } ?: nowMs,
                                    lastSyncMillis = nowMs
                                )
                            )
                        }
                    }

                    is RealtimeServerEvent.ConnectedClientCount -> {
                        _systemOverview.update { prev ->
                            prev?.copy(activeWebSocketClients = event.count)
                        }
                    }

                    is RealtimeServerEvent.StorageSnapshot -> {
                        val st = event.data
                        _systemOverview.update { prev ->
                            prev?.copy(
                                storageReady = st.ready,
                                storageRoot = st.root.ifBlank { prev.storageRoot },
                                storageMode = st.mode.ifBlank { prev.storageMode },
                                storageTotalBytes = st.total ?: prev.storageTotalBytes,
                                storageFreeBytes = st.free ?: prev.storageFreeBytes,
                                storageMessage = st.message
                            )
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    override suspend fun refreshSystemOverview(): AppResult<SystemOverview> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> {
                _systemOverview.update { prev ->
                    prev?.copy(freshness = prev.freshness.copy(isFromCache = true))
                }
                return r
            }
            is AppResult.Success -> r.data
        }

        return try {
            coroutineScope {
                var rttMs = 0L
                val sysDeferred = async {
                    var resp: retrofit2.Response<com.nodelix.coreos.data.model.SystemResponseDto>?
                    rttMs = measureTimeMillis {
                        resp = svc.getSystemMetrics()
                    }
                    resp!!
                }
                val settingsDeferred = async { runCatching { svc.getServerSettings() }.getOrNull() }
                val storageDeferred = async { runCatching { svc.getStorageStatus() }.getOrNull() }

                val sysResp = sysDeferred.await()
                val mappedSys = NetworkResultMapper.mapResponse(sysResp, json) {
                    sessionManager.onUnauthorizedOrExpired()
                }
                when (mappedSys) {
                    is AppResult.Failure -> {
                        _systemOverview.update { prev ->
                            prev?.copy(freshness = prev.freshness.copy(isFromCache = true))
                        }
                        mappedSys
                    }
                    is AppResult.Success -> {
                        val sysDto = mappedSys.data
                        val settingsDto = settingsDeferred.await()?.body()
                        val storageDto = storageDeferred.await()?.body()
                        val syncNow = System.currentTimeMillis()
                        preferencesDataStore.recordSyncTimestamp(syncNow)

                        val prev = _systemOverview.value
                        val historySamples = sysDto.history.map { h ->
                            MetricSample(
                                timestampMillis = h.t,
                                cpuPercent = h.cpu,
                                ramPercent = h.ram
                            )
                        }

                        val overview = SystemOverview(
                            cpuUsagePercent = sysDto.now?.cpu ?: prev?.cpuUsagePercent,
                            cpuCores = prev?.cpuCores,
                            cpuModel = prev?.cpuModel,
                            loadAverage1m = prev?.loadAverage1m,
                            ramTotalBytes = sysDto.now?.ram?.total ?: prev?.ramTotalBytes,
                            ramUsedBytes = sysDto.now?.ram?.used ?: prev?.ramUsedBytes,
                            ramUsagePercent = sysDto.now?.ram?.pct ?: prev?.ramUsagePercent,
                            storageReady = storageDto?.ready ?: prev?.storageReady ?: false,
                            storageRoot = storageDto?.root?.ifBlank { null } ?: prev?.storageRoot,
                            storageMode = storageDto?.mode?.ifBlank { null } ?: prev?.storageMode,
                            storageTotalBytes = storageDto?.total ?: prev?.storageTotalBytes,
                            storageFreeBytes = storageDto?.free ?: prev?.storageFreeBytes,
                            storageMessage = storageDto?.message ?: prev?.storageMessage,
                            serverUptimeSeconds = settingsDto?.info?.uptime ?: prev?.serverUptimeSeconds,
                            serverVersion = settingsDto?.info?.version?.ifBlank { null } ?: prev?.serverVersion,
                            nodeVersion = settingsDto?.info?.node?.ifBlank { null } ?: prev?.nodeVersion,
                            lanAddresses = settingsDto?.info?.ips ?: prev?.lanAddresses.orEmpty(),
                            activeWebSocketClients = prev?.activeWebSocketClients,
                            responseTimeMs = rttMs,
                            unavailableMetrics = prev?.unavailableMetrics.orEmpty(),
                            history = if (historySamples.isNotEmpty()) historySamples else prev?.history.orEmpty(),
                            freshness = DataFreshness(
                                isFromCache = false,
                                serverTimestampMillis = sysDto.now?.t?.takeIf { it > 0L } ?: syncNow,
                                lastSyncMillis = syncNow
                            )
                        )
                        _systemOverview.value = overview
                        AppResult.Success(overview)
                    }
                }
            }
        } catch (e: Exception) {
            _systemOverview.update { prev ->
                prev?.copy(freshness = prev.freshness.copy(isFromCache = true))
            }
            NetworkResultMapper.mapThrowable(e, json, logStore, "System") {
                sessionManager.onUnauthorizedOrExpired()
            }
        }
    }
}
