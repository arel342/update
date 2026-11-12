package com.nodelix.coreos.domain

import com.nodelix.coreos.core.AppError
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.data.repository.NotificationCenterRepositoryImpl
import com.nodelix.coreos.domain.model.Esp32State
import com.nodelix.coreos.domain.model.NotificationCategory
import com.nodelix.coreos.domain.model.ProtocolType
import com.nodelix.coreos.domain.model.ServerProfile
import com.nodelix.coreos.domain.repository.DeviceControlRepository
import com.nodelix.coreos.domain.repository.ServerProfileRepository
import com.nodelix.coreos.domain.usecase.ControlDoorUseCase
import com.nodelix.coreos.domain.usecase.ControlRelayUseCase
import com.nodelix.coreos.domain.usecase.ValidateAndSaveServerUseCase
import com.nodelix.coreos.network.RealtimeServerEvent
import com.nodelix.coreos.network.WebSocketManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UseCasesAndDoorSafetyTest {

    private fun onlineEsp32State() = Esp32State(
        configured = true,
        online = true,
        updatingFirmware = false,
        lastOkMillis = null,
        consecutiveFailures = 0,
        lastError = null,
        ipAddress = null,
        rssiDbm = null,
        uptimeSeconds = null,
        firmwareVersion = null,
        freeHeapBytes = null,
        battery = null,
        current = null,
        dht = null,
        aux = null,
        relays = emptyList(),
        alerts = emptyList(),
        zeroingActive = false,
        currentWarnLimitAmps = null,
        history = emptyList()
    )

    @Test
    fun `ValidateAndSaveServerUseCase permits private LAN HTTP and rejects public hosts`() = runTest {
        val repo = mockk<ServerProfileRepository>(relaxed = true)
        coEvery { repo.saveProfile(any()) } answers { AppResult.Success(firstArg()) }
        val useCase = ValidateAndSaveServerUseCase(repo)

        val publicHttp = ServerProfile(
            name = "Bad Public HTTP",
            protocol = ProtocolType.HTTP,
            host = "example.com",
            port = 3000
        )
        val resPublic = useCase(publicHttp)
        assertTrue((resPublic as AppResult.Failure).error is AppError.LanHttpBlocked)

        val lanHttp = ServerProfile(
            name = "LAN Node",
            protocol = ProtocolType.HTTP,
            host = "192.168.1.20",
            port = 3000
        )
        assertTrue(useCase(lanHttp) is AppResult.Success)
    }

    @Test
    fun `ControlDoorUseCase enforces confirmation and NEVER auto-retries timed-out open command`() = runTest {
        val deviceRepo = mockk<DeviceControlRepository>()
        coEvery { deviceRepo.openDoor() } returns AppResult.Failure(AppError.Timeout())

        val doorUseCase = ControlDoorUseCase(deviceRepo)

        // Unconfirmed open must fail before hitting network
        val unconfirmed = doorUseCase(ControlDoorUseCase.Action.OPEN, userConfirmed = false)
        assertTrue((unconfirmed as AppResult.Failure).error is AppError.BadRequest)
        coVerify(exactly = 0) { deviceRepo.openDoor() }

        // Confirmed open that times out must invoke openDoor() EXACTLY ONCE (never auto-retry!)
        val timedOut = doorUseCase(ControlDoorUseCase.Action.OPEN, userConfirmed = true)
        assertTrue((timedOut as AppResult.Failure).error is AppError.Timeout)
        coVerify(exactly = 1) { deviceRepo.openDoor() }
    }

    @Test
    fun `ControlRelayUseCase validates relay IDs and requires operator confirmation`() = runTest {
        val deviceRepo = mockk<DeviceControlRepository>()
        coEvery { deviceRepo.setRelayState(any(), any()) } returns AppResult.Success(onlineEsp32State())
        val relayUseCase = ControlRelayUseCase(deviceRepo)

        // Unconfirmed actuation must fail without touching the repository
        assertTrue(relayUseCase("1&2", targetState = true, userConfirmed = false) is AppResult.Failure)
        coVerify(exactly = 0) { deviceRepo.setRelayState(any(), any()) }

        // Unknown channels (backend would answer HTTP 400) must fail fast
        assertTrue(relayUseCase("99", targetState = true, userConfirmed = true) is AppResult.Failure)
        assertTrue(relayUseCase("gpio99", targetState = true, userConfirmed = true) is AppResult.Failure)
        coVerify(exactly = 0) { deviceRepo.setRelayState(any(), any()) }

        // Valid channels from every accepted alias family must reach the repository
        assertTrue(relayUseCase("1&2", targetState = true, userConfirmed = true) is AppResult.Success)
        assertTrue(relayUseCase("3", targetState = false, userConfirmed = true) is AppResult.Success)
        assertTrue(relayUseCase("gpio26", targetState = true, userConfirmed = true) is AppResult.Success)
        assertTrue(relayUseCase("relay-4", targetState = false, userConfirmed = true) is AppResult.Success)
        assertTrue(relayUseCase("all", targetState = false, userConfirmed = true) is AppResult.Success)
        coVerify(exactly = 5) { deviceRepo.setRelayState(any(), any()) }

        // Relay pair alias ("1&2") and gpio26 alias must both normalize to backend channel "1"
        coVerify(exactly = 2) { deviceRepo.setRelayState("1", true) }
    }

    @Test
    fun `NotificationCenterRepository tracks unread count mark read and category clear`() = runTest {
        val eventsFlow = MutableSharedFlow<RealtimeServerEvent>()
        val wsMock = mockk<WebSocketManager> {
            every { events } returns eventsFlow
        }
        val repo = NotificationCenterRepositoryImpl(
            scope = CoroutineScope(SupervisorJob()),
            webSocketManagerProvider = { wsMock }
        )

        repo.pushNotification(
            title = "Door Unlocked",
            description = "Admin opened door",
            category = NotificationCategory.DOOR_LOCK
        )
        repo.pushNotification(
            title = "Biometric Login",
            description = "ECDSA verified",
            category = NotificationCategory.SECURITY
        )

        assertEquals(2, repo.notifications.value.size)
        val n1 = repo.notifications.value.first { it.title == "Door Unlocked" }
        repo.markAsRead(n1.id)
        assertEquals(1, repo.notifications.value.count { !it.isRead })

        repo.clearNotifications(NotificationCategory.DOOR_LOCK)
        assertEquals(1, repo.notifications.value.size)
        assertEquals(NotificationCategory.SECURITY, repo.notifications.value.first().category)
    }
}
