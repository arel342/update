package com.nodelix.coreos.network

import com.nodelix.coreos.core.AppError
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.data.local.DiagnosticLogStore
import com.nodelix.coreos.data.model.GenericStatusDto
import com.nodelix.coreos.data.model.MobileVerifyResponseDto
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.net.ConnectException
import java.net.SocketTimeoutException

class NetworkResultAndWebSocketParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun `NetworkResultMapper maps timeout connection refused and LAN HTTP exceptions accurately`() {
        val timeout = NetworkResultMapper.mapThrowable(SocketTimeoutException("Read timed out"), json)
        assertTrue(timeout.error is AppError.Timeout)

        val refused = NetworkResultMapper.mapThrowable(ConnectException("Connection refused"), json)
        assertTrue(refused.error is AppError.ConnectionRefused)

        val lanBlocked = NetworkResultMapper.mapThrowable(LanHttpBlockedException("Public HTTP blocked"), json)
        assertTrue(lanBlocked.error is AppError.LanHttpBlocked)
    }

    @Test
    fun `NetworkResultMapper maps HTTP 401 403 revocation 405 409 setup and 429 rate limit`() {
        var unauthorizedCalled = false
        val resp401 = Response.error<String>(
            401,
            """{"error":"Unauthorized"}""".toResponseBody("application/json".toMediaType())
        )
        val mapped401 = NetworkResultMapper.mapResponse(resp401, json) { unauthorizedCalled = true }
        assertTrue(unauthorizedCalled)
        assertTrue((mapped401 as AppResult.Failure).error is AppError.AuthRequired)

        val resp409Setup = Response.error<String>(
            409,
            """{"setup":true,"redirect":"/setup.html","message":"Setup required"}"""
                .toResponseBody("application/json".toMediaType())
        )
        val mapped409 = NetworkResultMapper.mapResponse(resp409Setup, json)
        assertTrue((mapped409 as AppResult.Failure).error is AppError.SetupRequired)

        val resp403Revoked = Response.error<String>(
            403,
            """{"error":"Device access revoked by administrator"}"""
                .toResponseBody("application/json".toMediaType())
        )
        val mapped403 = NetworkResultMapper.mapResponse(resp403Revoked, json)
        assertTrue((mapped403 as AppResult.Failure).error is AppError.DeviceRevoked)

        // Wrong HTTP method on a valid route must surface as a client error, never Unknown
        val resp405 = Response.error<String>(
            405,
            """{"error":"Method Not Allowed"}""".toResponseBody("application/json".toMediaType())
        )
        val mapped405 = NetworkResultMapper.mapResponse(resp405, json)
        assertTrue((mapped405 as AppResult.Failure).error is AppError.BadRequest)

        val resp429 = Response.error<String>(
            429,
            """{"error":"Too many attempts"}""".toResponseBody("application/json".toMediaType())
        )
        val mapped429 = NetworkResultMapper.mapResponse(resp429, json)
        assertTrue((mapped429 as AppResult.Failure).error is AppError.RateLimited)
    }

    @Test
    fun `GenericStatusDto prefers human message fields and mapper falls back to machine code`() {
        val withMessage = json.decodeFromString<GenericStatusDto>(
            """{"success":false,"message":"Session expired","code":"session_expired"}"""
        )
        assertEquals("Session expired", withMessage.resolvedMessage)

        val legacyError = json.decodeFromString<GenericStatusDto>(
            """{"error":"Unauthorized"}"""
        )
        assertEquals("Unauthorized", legacyError.resolvedMessage)

        // Code-only bodies carry no human message at DTO level; the mapper promotes
        // the machine code so failures stay actionable instead of blank.
        val codeOnlyResp = Response.error<String>(
            400,
            """{"success":false,"code":"device_not_registered"}"""
                .toResponseBody("application/json".toMediaType())
        )
        val mappedCodeOnly = NetworkResultMapper.mapResponse(codeOnlyResp, json)
        val codeOnlyErr = (mappedCodeOnly as AppResult.Failure).error as AppError.BadRequest
        assertTrue(codeOnlyErr.userMessage.contains("device_not_registered"))

        // Backend dual-flag shape: either `success` or `ok` marks the outcome
        assertTrue(json.decodeFromString<GenericStatusDto>("""{"success":true}""").isOk)
        assertTrue(json.decodeFromString<GenericStatusDto>("""{"ok":true}""").isOk)
        assertTrue(!json.decodeFromString<GenericStatusDto>("""{"success":false}""").isOk)
    }

    @Test
    fun `MobileVerifyResponseDto resolves sessionToken standard field with legacy accessToken fallback`() {
        val standard = json.decodeFromString<MobileVerifyResponseDto>(
            """{"success":true,"sessionToken":"new-token-123","tokenType":"Bearer","deviceId":"d1"}"""
        )
        assertEquals("new-token-123", standard.resolvedSessionToken)

        val legacy = json.decodeFromString<MobileVerifyResponseDto>(
            """{"success":true,"accessToken":"legacy-token-456","tokenType":"Bearer","deviceId":"d1"}"""
        )
        assertEquals("legacy-token-456", legacy.resolvedSessionToken)

        val bothPreferStandard = json.decodeFromString<MobileVerifyResponseDto>(
            """{"success":true,"sessionToken":"new-token","accessToken":"legacy-token","deviceId":"d1"}"""
        )
        assertEquals("new-token", bothPreferStandard.resolvedSessionToken)

        val missing = json.decodeFromString<MobileVerifyResponseDto>(
            """{"success":true,"deviceId":"d1"}"""
        )
        assertNull(missing.resolvedSessionToken)
    }

    @Test
    fun `WebSocketManager parses valid Nodelix frames deduplicates repeats and discards malformed JSON`() {
        val logStore = DiagnosticLogStore()
        val wsManager = WebSocketManager(
            scope = CoroutineScope(SupervisorJob()),
            json = json,
            apiClient = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true),
            logStore = logStore
        )

        val systemFrame = """{"type":"SYSTEM","data":{"t":1762041600000,"cpu":28.5,"cores":4,"model":"Pi 4","load":1.2,"uptime":7200}}"""
        val first = wsManager.parseFrameForTestOrRuntime(systemFrame)
        assertTrue(first is RealtimeServerEvent.SystemSnapshot)
        assertEquals(28.5, (first as RealtimeServerEvent.SystemSnapshot).data.cpu ?: 0.0, 0.01)

        // Identical consecutive SYSTEM frame should be deduplicated
        val duplicate = wsManager.parseFrameForTestOrRuntime(systemFrame)
        assertNull(duplicate)

        // Malformed JSON must never crash and must return null
        val broken = wsManager.parseFrameForTestOrRuntime("{not valid json!!!")
        assertNull(broken)

        // Frames without a type must be discarded
        assertNull(wsManager.parseFrameForTestOrRuntime("""{"data":{"cpu":10.0}}"""))

        // DOOR_NOTE toast frames should always pass through even if repeated
        val doorNote = """{"type":"DOOR_NOTE","k":"denied","level":"warn","text":"Unknown RFID UID"}"""
        assertTrue(wsManager.parseFrameForTestOrRuntime(doorNote) is RealtimeServerEvent.DoorToastNote)
        assertTrue(wsManager.parseFrameForTestOrRuntime(doorNote) is RealtimeServerEvent.DoorToastNote)

        // DEVICE_COUNT uses a top-level `count` field instead of `data`
        val countFrame = """{"type":"DEVICE_COUNT","count":3}"""
        val countEvent = wsManager.parseFrameForTestOrRuntime(countFrame)
        assertTrue(countEvent is RealtimeServerEvent.ConnectedClientCount)
        assertEquals(3, (countEvent as RealtimeServerEvent.ConnectedClientCount).count)
    }
}
