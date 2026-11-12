package com.nodelix.coreos.network

import com.nodelix.coreos.data.local.DiagnosticLogStore
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the WebSocket lifecycle rules from the backend contract (`docs/WEBSOCKET.md`):
 * 4401 (session expired) and 4403 (device revoked) must never trigger an automatic
 * reconnect with the dead credential, while a generic drop must reconnect.
 */
class WebSocketCloseDecisionTest {

    private fun manager() = WebSocketManager(
        scope = CoroutineScope(SupervisorJob()),
        json = Json { ignoreUnknownKeys = true; isLenient = true },
        apiClient = mockk(relaxed = true),
        sessionManager = mockk(relaxed = true),
        logStore = DiagnosticLogStore()
    )

    @Test
    fun `4401 and 4403 require re-authentication instead of reconnecting`() {
        val sut = manager()
        assertEquals(
            WebSocketManager.CloseDecision.UNAUTHORIZED,
            sut.decideOnClose(WebSocketManager.WS_CLOSE_UNAUTHORIZED, intentional = false)
        )
        assertEquals(
            WebSocketManager.CloseDecision.UNAUTHORIZED,
            sut.decideOnClose(WebSocketManager.WS_CLOSE_REVOKED, intentional = false)
        )
    }

    @Test
    fun `generic drops reconnect and client initiated closes are ignored`() {
        val sut = manager()
        assertEquals(WebSocketManager.CloseDecision.RECONNECT, sut.decideOnClose(1006, intentional = false))
        assertEquals(WebSocketManager.CloseDecision.RECONNECT, sut.decideOnClose(1011, intentional = false))
        // An intentional close (logout / profile switch) never schedules a reconnect,
        // even when the peer answers with 4401.
        assertEquals(WebSocketManager.CloseDecision.IGNORE, sut.decideOnClose(1000, intentional = true))
        assertEquals(
            WebSocketManager.CloseDecision.IGNORE,
            sut.decideOnClose(WebSocketManager.WS_CLOSE_UNAUTHORIZED, intentional = true)
        )
    }
}
