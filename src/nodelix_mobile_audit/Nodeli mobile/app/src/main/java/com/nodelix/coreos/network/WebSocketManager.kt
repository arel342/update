package com.nodelix.coreos.network

import com.nodelix.coreos.core.AppConfig
import com.nodelix.coreos.data.local.DiagnosticLogStore
import com.nodelix.coreos.data.model.DoorStatusDto
import com.nodelix.coreos.data.model.Esp32StatusDto
import com.nodelix.coreos.data.model.FirmwareWsEventDto
import com.nodelix.coreos.data.model.StorageStatusDto
import com.nodelix.coreos.data.model.SystemWsDataDto
import com.nodelix.coreos.data.model.UpdaterStatusDto
import com.nodelix.coreos.data.model.WebSocketEnvelopeDto
import com.nodelix.coreos.domain.model.ServerProfile
import com.nodelix.coreos.security.SessionManager
import com.nodelix.coreos.util.Validators
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class WsConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    UNAUTHORIZED
}

sealed interface RealtimeServerEvent {
    data class SensorSnapshot(val data: Esp32StatusDto) : RealtimeServerEvent
    data class DoorSnapshot(val data: DoorStatusDto) : RealtimeServerEvent
    data class DoorToastNote(val key: String, val level: String, val text: String) : RealtimeServerEvent
    data class SystemSnapshot(val data: SystemWsDataDto) : RealtimeServerEvent
    data class FirmwareProgress(val data: FirmwareWsEventDto) : RealtimeServerEvent
    data class UpdateSnapshot(val data: UpdaterStatusDto) : RealtimeServerEvent
    data class ConnectedClientCount(val count: Int) : RealtimeServerEvent
    data class StorageSnapshot(val data: StorageStatusDto) : RealtimeServerEvent
    data class GenericNotification(val title: String, val body: String, val level: String) : RealtimeServerEvent
}

/**
 * Authenticated, lifecycle-aware OkHttp WebSocket client for NodelixcoreOS.
 *
 * Enforces Part 17 invariants:
 * - Uses `Authorization: Bearer <token>` or `Cookie: sid=<sid>` header (NEVER URL query token).
 * - Uses `ws://` exclusively for private LAN endpoints.
 * - Mutex-guarded single connection (prevents duplicate sockets).
 * - Exponential backoff reconnection.
 * - Deduplicates consecutive identical frames and safely discards malformed JSON.
 */
class WebSocketManager(
    private val scope: CoroutineScope,
    private val json: Json,
    private val apiClient: ApiClient,
    private val sessionManager: SessionManager,
    private val logStore: DiagnosticLogStore,
    private val autoReconnectAllowed: () -> Boolean = { true }
) {
    companion object {
        /** Server closed: session missing/expired (re-authenticate, do not auto-reconnect). */
        const val WS_CLOSE_UNAUTHORIZED = 4401

        /** Server closed: session/device revoked (re-authenticate, do not auto-reconnect). */
        const val WS_CLOSE_REVOKED = 4403
    }

    private val mutex = Mutex()
    private var activeWebSocket: WebSocket? = null
    private var activeProfile: ServerProfile? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var intentionalClose = false

    /**
     * Monotonic connection generation. Every socket created by [connect] captures the
     * current value; callbacks from any older socket are ignored so a superseded
     * connection can never flip the state of the live one, schedule a duplicate
     * reconnect, or emit stale events.
     */
    @Volatile
    private var connectionGeneration = 0L

    private val lastFrameHashByType = HashMap<String, Int>()

    private val _state = MutableStateFlow(WsConnectionState.DISCONNECTED)
    val state: StateFlow<WsConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RealtimeServerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RealtimeServerEvent> = _events.asSharedFlow()

    suspend fun connect(profile: ServerProfile, resetBackoffCounter: Boolean = true) {
        mutex.withLock {
            // Manual (re)connect resets the backoff counter so a user-initiated retry
            // after max-attempts exhaustion starts fresh. Automatic reconnects keep it.
            if (resetBackoffCounter) {
                reconnectAttempt = 0
                reconnectJob?.cancel()
                reconnectJob = null
            }
            // Idempotent: an already-live (or still-handshaking) socket for this exact
            // endpoint is reused instead of being torn down and re-opened, so repeated
            // refresh taps cannot create duplicate connections.
            val sameEndpoint = activeProfile?.id == profile.id &&
                activeProfile?.host == profile.host &&
                activeProfile?.port == profile.port &&
                activeProfile?.protocol == profile.protocol
            if (sameEndpoint && activeWebSocket != null &&
                (_state.value == WsConnectionState.CONNECTED || _state.value == WsConnectionState.CONNECTING)
            ) {
                return
            }
            if (!Validators.isPrivateLanHost(profile.host)) {
                logStore.warn("WebSocket", "Blocked ws:// connection: a private LAN host is required.")
                _state.value = WsConnectionState.DISCONNECTED
                return
            }
            val bearer = sessionManager.currentBearerToken()
            val sid = sessionManager.currentCookieSid()
            if (bearer.isNullOrBlank() && sid.isNullOrBlank()) {
                _state.value = WsConnectionState.UNAUTHORIZED
                return
            }

            // Invalidate the previous socket BEFORE closing it: its asynchronous
            // callbacks are then ignored (stale generation) and can never schedule a
            // competing reconnect or overwrite the state of the new connection.
            connectionGeneration += 1
            val generation = connectionGeneration
            intentionalClose = true
            closeSocketLocked(code = 1000, reason = "Switching connection")
            intentionalClose = false
            activeProfile = profile
            _state.value = if (reconnectAttempt > 0) WsConnectionState.RECONNECTING else WsConnectionState.CONNECTING

            // Exactly one credential is attached: Bearer is preferred, the legacy `sid`
            // cookie is only used when no Bearer session is active.
            val requestBuilder = Request.Builder().url(profile.webSocketUrl())
            if (!bearer.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $bearer")
            } else if (!sid.isNullOrBlank()) {
                requestBuilder.header("Cookie", "sid=$sid")
            }

            val wsClient = apiClient.buildWebSocketOkHttpClient(profile)
            activeWebSocket = wsClient.newWebSocket(requestBuilder.build(), Listener(profile, generation))
        }
    }

    suspend fun disconnect() {
        mutex.withLock {
            intentionalClose = true
            connectionGeneration += 1
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempt = 0
            lastFrameHashByType.clear()
            closeSocketLocked(1000, "Client disconnected")
            _state.value = WsConnectionState.DISCONNECTED
        }
    }

    private fun closeSocketLocked(code: Int, reason: String) {
        runCatching { activeWebSocket?.close(code, reason) }
        activeWebSocket = null
    }

    private fun scheduleReconnect(profile: ServerProfile) {
        if (intentionalClose || !autoReconnectAllowed()) return
        // Bounded reconnect: stop automatic retries after the configured maximum so a
        // permanently unreachable server does not drain battery/RAM; the user can retry
        // manually (which resets the counter in connect()).
        if (reconnectAttempt >= com.nodelix.coreos.util.Constants.WS_MAX_RECONNECT_ATTEMPTS) {
            _state.value = WsConnectionState.DISCONNECTED
            logStore.warn(
                "WebSocket",
                "Automatic reconnect stopped after $reconnectAttempt attempts. Tap refresh to retry manually."
            )
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val shift = reconnectAttempt.coerceAtMost(5)
            val delayMs = (AppConfig.WS_INITIAL_BACKOFF_MS * (1L shl shift))
                .coerceAtMost(AppConfig.WS_MAX_BACKOFF_MS)
            reconnectAttempt++
            _state.value = WsConnectionState.RECONNECTING
            logStore.info("WebSocket", "Reconnecting in ${delayMs}ms (attempt #$reconnectAttempt)")
            delay(delayMs)
            if (!intentionalClose && sessionManager.sessionState.value.isAuthenticated) {
                connect(profile, resetBackoffCounter = false)
            }
        }
    }

    internal fun parseFrameForTestOrRuntime(rawText: String): RealtimeServerEvent? {
        return try {
            val envelope = json.decodeFromString<WebSocketEnvelopeDto>(rawText)
            val type = envelope.type.uppercase()
            if (type.isBlank()) return null

            // Deduplicate identical consecutive frames per message type (except toast notifications)
            val frameHash = rawText.hashCode()
            if (type != "DOOR_NOTE" && type != "NOTIFICATION") {
                synchronized(lastFrameHashByType) {
                    if (lastFrameHashByType[type] == frameHash) return null
                    lastFrameHashByType[type] = frameHash
                }
            }

            when (type) {
                "SENSOR" -> envelope.data?.let {
                    RealtimeServerEvent.SensorSnapshot(json.decodeFromJsonElement<Esp32StatusDto>(it))
                }
                "DOOR" -> envelope.data?.let {
                    RealtimeServerEvent.DoorSnapshot(json.decodeFromJsonElement<DoorStatusDto>(it))
                }
                "DOOR_NOTE" -> RealtimeServerEvent.DoorToastNote(
                    key = envelope.k.orEmpty(),
                    level = envelope.level ?: "info",
                    text = envelope.text.orEmpty()
                )
                "SYSTEM", "SERVER_STATUS" -> envelope.data?.let {
                    RealtimeServerEvent.SystemSnapshot(json.decodeFromJsonElement<SystemWsDataDto>(it))
                }
                "FW" -> envelope.data?.let {
                    RealtimeServerEvent.FirmwareProgress(json.decodeFromJsonElement<FirmwareWsEventDto>(it))
                }
                "UPDATE", "UPDATE_AVAILABLE" -> envelope.data?.let {
                    RealtimeServerEvent.UpdateSnapshot(json.decodeFromJsonElement<UpdaterStatusDto>(it))
                }
                "DEVICE_COUNT" -> envelope.count?.let {
                    RealtimeServerEvent.ConnectedClientCount(it)
                }
                "STORAGE_STATUS" -> envelope.data?.let {
                    RealtimeServerEvent.StorageSnapshot(json.decodeFromJsonElement<StorageStatusDto>(it))
                }
                "NOTIFICATION" -> RealtimeServerEvent.GenericNotification(
                    title = envelope.k ?: "System Event",
                    body = envelope.text.orEmpty(),
                    level = envelope.level ?: "info"
                )
                else -> null
            }
        } catch (e: Exception) {
            logStore.warn("WebSocket", "Discarded malformed WebSocket payload (${e.javaClass.simpleName})")
            null
        }
    }

    /** Decision derived from a WebSocket close code (unit-testable). */
    internal enum class CloseDecision {
        /** Session/device no longer valid: stop reconnecting and require a fresh login. */
        UNAUTHORIZED,

        /** Generic drop: schedule a bounded exponential-backoff reconnect. */
        RECONNECT,

        /** Client-initiated close: do nothing. */
        IGNORE
    }

    internal fun decideOnClose(code: Int, intentional: Boolean): CloseDecision {
        if (intentional) return CloseDecision.IGNORE
        return when (code) {
            WS_CLOSE_UNAUTHORIZED, WS_CLOSE_REVOKED -> CloseDecision.UNAUTHORIZED
            else -> CloseDecision.RECONNECT
        }
    }

    private inner class Listener(
        private val profile: ServerProfile,
        private val generation: Long
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (generation != connectionGeneration) {
                // A newer socket already replaced this one: close the stale handshake.
                runCatching { webSocket.close(1000, "Superseded connection") }
                return
            }
            reconnectAttempt = 0
            _state.value = WsConnectionState.CONNECTED
            logStore.info("WebSocket", "Realtime WebSocket connected to ${profile.name}")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (generation != connectionGeneration) return
            val parsed = parseFrameForTestOrRuntime(text) ?: return
            _events.tryEmit(parsed)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (generation != connectionGeneration) return
            when (decideOnClose(code, intentionalClose)) {
                CloseDecision.IGNORE -> return
                CloseDecision.UNAUTHORIZED -> {
                    // Backend session lifecycle (docs/WEBSOCKET.md): 4401 = session
                    // missing/expired, 4403 = session/device revoked. Both require fresh
                    // credentials — never auto-reconnect with the dead token.
                    val revoked = code == WS_CLOSE_REVOKED
                    logStore.warn(
                        "WebSocket",
                        if (revoked) "Server revoked this session/device (4403). Sign in again."
                        else "Server session expired (4401). Sign in again."
                    )
                    _state.value = WsConnectionState.UNAUTHORIZED
                    reconnectJob?.cancel()
                    reconnectJob = null
                    sessionManager.onUnauthorizedOrExpired()
                }
                CloseDecision.RECONNECT -> {
                    _state.value = WsConnectionState.DISCONNECTED
                    scheduleReconnect(profile)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (generation != connectionGeneration) return
            val httpCode = response?.code
            if (httpCode == 401 || httpCode == 403) {
                logStore.warn("WebSocket", "WebSocket handshake rejected with HTTP $httpCode")
                _state.value = WsConnectionState.UNAUTHORIZED
                reconnectJob?.cancel()
                reconnectJob = null
                sessionManager.onUnauthorizedOrExpired()
                return
            }
            logStore.warn("WebSocket", "WebSocket disconnected: ${t.javaClass.simpleName}")
            if (!intentionalClose) {
                _state.value = WsConnectionState.DISCONNECTED
                scheduleReconnect(profile)
            }
        }
    }
}
