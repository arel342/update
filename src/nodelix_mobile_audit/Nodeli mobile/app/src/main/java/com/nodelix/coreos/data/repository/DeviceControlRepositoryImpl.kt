package com.nodelix.coreos.data.repository

import com.nodelix.coreos.core.AppError
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.data.local.DiagnosticLogStore
import com.nodelix.coreos.data.model.CalibrateRequestDto
import com.nodelix.coreos.data.model.DeviceTestRequestDto
import com.nodelix.coreos.data.model.DoorCardAdminRequestDto
import com.nodelix.coreos.data.model.DoorCardRenameRequestDto
import com.nodelix.coreos.data.model.DoorConfigRequestDto
import com.nodelix.coreos.data.model.DoorEnrollRequestDto
import com.nodelix.coreos.data.model.DoorLockToggleRequestDto
import com.nodelix.coreos.data.model.DoorStatusDto
import com.nodelix.coreos.data.model.Esp32StatusDto
import com.nodelix.coreos.data.model.RelayCommandRequestDto
import com.nodelix.coreos.domain.model.AuxOutputState
import com.nodelix.coreos.domain.model.BatteryMetrics
import com.nodelix.coreos.domain.model.CurrentMetrics
import com.nodelix.coreos.domain.model.DhtMetrics
import com.nodelix.coreos.domain.model.DoorEventEntry
import com.nodelix.coreos.domain.model.DoorState
import com.nodelix.coreos.domain.model.EnrollState
import com.nodelix.coreos.domain.model.Esp32Alert
import com.nodelix.coreos.domain.model.Esp32HistorySample
import com.nodelix.coreos.domain.model.Esp32State
import com.nodelix.coreos.domain.model.NotificationCategory
import com.nodelix.coreos.domain.model.RelayChannel
import com.nodelix.coreos.domain.model.RfidCard
import com.nodelix.coreos.domain.repository.DeviceControlRepository
import com.nodelix.coreos.domain.repository.NotificationCenterRepository
import com.nodelix.coreos.network.ApiClient
import com.nodelix.coreos.network.NetworkResultMapper
import com.nodelix.coreos.network.RealtimeServerEvent
import com.nodelix.coreos.network.WebSocketManager
import com.nodelix.coreos.security.SessionManager
import com.nodelix.coreos.util.Validators
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class DeviceControlRepositoryImpl(
    scope: CoroutineScope,
    private val apiClient: ApiClient,
    private val webSocketManager: WebSocketManager,
    private val sessionManager: SessionManager,
    private val notificationRepository: NotificationCenterRepository,
    private val logStore: DiagnosticLogStore,
    private val json: Json
) : DeviceControlRepository {

    private val _esp32State = MutableStateFlow<Esp32State?>(null)
    override val esp32State: StateFlow<Esp32State?> = _esp32State.asStateFlow()

    private val _doorState = MutableStateFlow<DoorState?>(null)
    override val doorState: StateFlow<DoorState?> = _doorState.asStateFlow()

    private var esp32ConfiguredHint: Boolean = true

    init {
        scope.launch {
            webSocketManager.events.collect { event ->
                when (event) {
                    is RealtimeServerEvent.SensorSnapshot -> {
                        val prevHistory = _esp32State.value?.history.orEmpty()
                        val mapped = mapEsp32Dto(event.data, esp32ConfiguredHint, prevHistory)
                        _esp32State.value = mapped
                        mapped.alerts.forEach { alert ->
                            notificationRepository.pushNotification(
                                title = "ESP32 Alert: ${alert.key.uppercase()}",
                                description = "Threshold triggered (${alert.code}) — severity ${alert.level}",
                                category = NotificationCategory.ESP32,
                                dedupKey = "esp32_alert_${alert.key}_${alert.level}"
                            )
                        }
                    }

                    is RealtimeServerEvent.DoorSnapshot -> {
                        _doorState.value = mapDoorDto(event.data)
                    }

                    is RealtimeServerEvent.DoorToastNote -> {
                        if (event.text.isNotBlank()) {
                            notificationRepository.pushNotification(
                                title = "Door Lock Activity",
                                description = event.text,
                                category = NotificationCategory.DOOR_LOCK
                            )
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    override suspend fun refreshAllDevices(): AppResult<Pair<Esp32State, DoorState>> = coroutineScope {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return@coroutineScope r
            is AppResult.Success -> r.data
        }
        val settingsDeferred = async { runCatching { svc.getServerSettings() }.getOrNull()?.body() }
        val espDeferred = async { refreshEsp32() }
        val doorDeferred = async { refreshDoor() }

        val settings = settingsDeferred.await()
        if (settings?.esp32 != null) {
            esp32ConfiguredHint = settings.esp32.ip.isNotBlank()
        }

        val espRes = espDeferred.await()
        val doorRes = doorDeferred.await()
        when {
            espRes is AppResult.Success && doorRes is AppResult.Success ->
                AppResult.Success(espRes.data to doorRes.data)
            espRes is AppResult.Failure -> espRes
            doorRes is AppResult.Failure -> doorRes
            else -> AppResult.Failure(AppError.Unknown())
        }
    }

    override suspend fun refreshEsp32(): AppResult<Esp32State> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.getEsp32Status()
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    val configured = esp32ConfiguredHint || mapped.data.online || mapped.data.esp != null || mapped.data.lastError.isNotBlank()
                    // Preserve the previously collected history when this snapshot carries
                    // none, so the ESP32 chart does not reset to empty on every refresh.
                    val state = mapEsp32Dto(mapped.data, configured, _esp32State.value?.history.orEmpty())
                    _esp32State.value = state
                    AppResult.Success(state)
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "ESP32") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun refreshDoor(): AppResult<DoorState> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.getDoorStatus()
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    val state = mapDoorDto(mapped.data)
                    _doorState.value = state
                    AppResult.Success(state)
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Door") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun testDeviceConnection(deviceKey: String): AppResult<String> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.testDownstreamDevice(DeviceTestRequestDto(device = deviceKey))
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> AppResult.Success(
                    mapped.data.message ?: "Device ${deviceKey.uppercase()} responded (FW ${mapped.data.fw ?: "N/A"})."
                )
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "DeviceTest") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun setRelayState(relayId: String, turnOn: Boolean): AppResult<Esp32State> {
        val normalized = Validators.normalizeRelayId(relayId)
            ?: return AppResult.Failure(
                AppError.BadRequest("Unknown relay channel '$relayId'. Use 1, 3, 4, or all.")
            )
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.controlRelay(RelayCommandRequestDto(id = normalized, state = turnOn))
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    logStore.info("Relay", "Relay '$relayId' set to ${if (turnOn) "ON" else "OFF"}")
                    notificationRepository.pushNotification(
                        title = "Relay State Updated",
                        description = "Channel $relayId switched ${if (turnOn) "ON" else "OFF"}",
                        category = NotificationCategory.RELAY
                    )
                    val updatedRelays = mapped.data.relays.map { r ->
                        RelayChannel(id = r.id, channel = r.ch, pin = r.pin, name = r.name, isOn = r.on)
                    }
                    val current = _esp32State.value
                    val next = if (current != null) {
                        current.copy(
                            relays = updatedRelays,
                            aux = mapped.data.aux?.let {
                                AuxOutputState(it.relay, it.onPin, it.offPin, it.on == 1, it.off == 1)
                            } ?: current.aux
                        ).also { _esp32State.value = it }
                    } else {
                        when (val refreshed = refreshEsp32()) {
                            is AppResult.Success -> refreshed.data
                            is AppResult.Failure -> return refreshed
                        }
                    }
                    AppResult.Success(next)
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Relay") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun calibrateEsp32(
        type: String,
        tapId: String?,
        actualValue: Double?
    ): AppResult<String> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.calibrateEsp32(CalibrateRequestDto(type = type, id = tapId, actual = actualValue))
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    refreshEsp32()
                    AppResult.Success(mapped.data.message ?: "Calibration applied.")
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "ESP32") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun openDoor(): AppResult<String> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        // Single execution — NEVER retried automatically on timeout (Part 9.3)
        return try {
            val resp = svc.openDoor()
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    logStore.info("DoorLock", "Door OPEN command acknowledged by server.")
                    notificationRepository.pushNotification(
                        title = "Door Unlocked Remotely",
                        description = "Remote OPEN DOOR command executed via mobile control center.",
                        category = NotificationCategory.DOOR_LOCK
                    )
                    refreshDoor()
                    AppResult.Success(mapped.data.message ?: "Door unlocked.")
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "DoorLock") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun closeDoor(): AppResult<String> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.closeDoor()
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    logStore.info("DoorLock", "Door CLOSE command acknowledged by server.")
                    notificationRepository.pushNotification(
                        title = "Door Locked Remotely",
                        description = "Remote CLOSE DOOR command executed via mobile control center.",
                        category = NotificationCategory.DOOR_LOCK
                    )
                    refreshDoor()
                    AppResult.Success(mapped.data.message ?: "Door locked.")
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "DoorLock") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun setDoorRfidEnabled(enabled: Boolean): AppResult<String> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.setDoorRfidLock(DoorLockToggleRequestDto(enabled = enabled))
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    refreshDoor()
                    AppResult.Success(mapped.data.message ?: "RFID card access updated.")
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "DoorLock") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun setDoorOpenDuration(seconds: Int): AppResult<String> {
        if (seconds !in 0..10) {
            return AppResult.Failure(AppError.BadRequest("Door unlock duration must be between 0 and 10 seconds."))
        }
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.setDoorOpenDuration(DoorConfigRequestDto(openSec = seconds))
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    refreshDoor()
                    AppResult.Success(mapped.data.message ?: "Unlock duration set to ${seconds}s.")
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "DoorLock") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun setDoorEnrollMode(start: Boolean): AppResult<String> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.setDoorEnroll(DoorEnrollRequestDto(action = if (start) "start" else "cancel"))
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    refreshDoor()
                    AppResult.Success(if (start) "RFID card enrollment started." else "RFID enrollment cancelled.")
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "DoorLock") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun renameRfidCard(uid: String, newName: String): AppResult<Unit> {
        if (!Validators.isValidRfidUid(uid)) {
            return AppResult.Failure(AppError.BadRequest("Invalid RFID card UID."))
        }
        if (newName.trim().isEmpty()) {
            return AppResult.Failure(AppError.BadRequest("Card name cannot be empty."))
        }
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.renameDoorCard(DoorCardRenameRequestDto(uid = uid.trim(), name = newName.trim().take(24)))
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    refreshDoor()
                    AppResult.Success(Unit)
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "DoorLock") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun setRfidCardAdmin(
        uid: String,
        isAdmin: Boolean,
        adminPassword: String?
    ): AppResult<String> {
        if (isAdmin && adminPassword.isNullOrBlank()) {
            return AppResult.Failure(AppError.BadRequest("Admin password is required to grant Admin Card privilege."))
        }
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.setDoorCardAdmin(
                DoorCardAdminRequestDto(uid = uid.trim(), admin = isAdmin, password = adminPassword)
            )
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    refreshDoor()
                    AppResult.Success(mapped.data.message ?: "Card privilege updated.")
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "DoorLock") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun deleteRfidCard(uid: String): AppResult<Unit> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.deleteDoorCard(uid.trim())
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    refreshDoor()
                    AppResult.Success(Unit)
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "DoorLock") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    private fun mapEsp32Dto(
        dto: Esp32StatusDto,
        configured: Boolean,
        fallbackHistory: List<Esp32HistorySample>
    ): Esp32State {
        val history = if (dto.history.isNotEmpty()) {
            dto.history.takeLast(120).map { h ->
                Esp32HistorySample(
                    timestampMillis = h.t,
                    packVoltage = h.v,
                    currentAmps = h.i,
                    temperatureCelsius = h.tc,
                    humidityPercent = h.h
                )
            }
        } else fallbackHistory

        return Esp32State(
            configured = configured,
            online = dto.online,
            updatingFirmware = dto.updating,
            lastOkMillis = dto.lastOk.takeIf { it > 0L },
            consecutiveFailures = dto.fails,
            lastError = dto.lastError.ifBlank { null },
            ipAddress = dto.esp?.ip?.ifBlank { null },
            rssiDbm = dto.esp?.rssi,
            uptimeSeconds = dto.esp?.uptime,
            firmwareVersion = dto.esp?.fw?.ifBlank { null },
            freeHeapBytes = dto.esp?.heap,
            battery = dto.battery?.let { b ->
                BatteryMetrics(
                    mode = b.mode,
                    packVoltage = b.pack,
                    cellsVoltage = b.cells,
                    cellsSoc = b.socs,
                    averageSocPercent = b.soc,
                    minCellVoltage = b.min,
                    maxCellVoltage = b.max,
                    cellDeltaVoltage = b.delta,
                    statusText = b.status,
                    statusKey = b.statusKey,
                    level = b.level
                )
            },
            current = dto.current?.let { c ->
                CurrentMetrics(
                    amperes = c.a,
                    peakAmperes = c.peak,
                    clipping = c.clip,
                    apparentPowerVa = c.va
                )
            },
            dht = dto.dht?.let { d ->
                DhtMetrics(
                    ok = d.ok,
                    temperatureCelsius = d.t,
                    humidityPercent = d.h,
                    pin = d.pin,
                    ageMs = d.age,
                    error = d.err
                )
            },
            aux = dto.aux?.let { a ->
                AuxOutputState(
                    relayId = a.relay,
                    onPin = a.onPin,
                    offPin = a.offPin,
                    onHigh = a.on == 1,
                    offHigh = a.off == 1
                )
            },
            relays = dto.relays.map { r ->
                RelayChannel(
                    id = r.id,
                    channel = r.ch,
                    pin = r.pin,
                    name = r.name,
                    isOn = r.on
                )
            },
            alerts = dto.alerts.map { Esp32Alert(key = it.key, code = it.k, level = it.level) },
            zeroingActive = dto.zeroing,
            currentWarnLimitAmps = dto.limits?.iWarn,
            history = history
        )
    }

    private fun mapDoorDto(dto: DoorStatusDto): DoorState {
        return DoorState(
            configured = dto.configured,
            online = dto.online,
            updatingFirmware = dto.updating,
            lastOkMillis = dto.lastOk.takeIf { it > 0L },
            consecutiveFailures = dto.fails,
            lastError = dto.lastError.ifBlank { null },
            ipAddress = dto.esp?.ip?.ifBlank { null },
            rssiDbm = dto.esp?.rssi,
            uptimeSeconds = dto.esp?.uptime,
            firmwareVersion = dto.esp?.fw?.ifBlank { null },
            isDoorOpen = dto.open,
            openSecondsRemaining = dto.left,
            isRfidEnabled = dto.enabled,
            configuredOpenSeconds = dto.openSec,
            isConfigSynced = dto.synced,
            enroll = EnrollState(
                active = dto.enroll.active,
                secondsLeft = dto.enroll.left,
                totalSeconds = dto.enroll.total
            ),
            cards = dto.cards.map { c ->
                RfidCard(
                    uid = c.uid,
                    name = c.name,
                    isAdmin = c.admin,
                    addedAtMillis = c.added,
                    lastUsedMillis = c.last
                )
            },
            eventLog = dto.log.map { l ->
                DoorEventEntry(
                    timestampMillis = l.t,
                    type = l.type,
                    key = l.k,
                    text = l.text
                )
            }
        )
    }
}
