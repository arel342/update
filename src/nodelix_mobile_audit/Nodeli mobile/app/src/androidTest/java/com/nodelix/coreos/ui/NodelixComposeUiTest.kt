package com.nodelix.coreos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nodelix.coreos.ui.components.AuthRequiredCard
import com.nodelix.coreos.ui.components.ConfirmSensitiveActionDialog
import com.nodelix.coreos.ui.components.EmptyOrStatusCard
import com.nodelix.coreos.ui.components.ErrorStateCard
import com.nodelix.coreos.ui.components.OfflineBannerCard
import com.nodelix.coreos.ui.theme.NodelixTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NodelixComposeUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyOfflineAndAuthStatesRenderHonestlyWithoutFakeMetrics() {
        var reconnectClicks = 0
        var loginClicks = 0

        composeRule.setContent {
            NodelixTheme {
                Column {
                    OfflineBannerCard(
                        message = "Server unreachable on LAN",
                        onRetry = { reconnectClicks++ }
                    )
                    EmptyOrStatusCard(
                        icon = Icons.Outlined.Storage,
                        title = "No Telemetry History Yet",
                        description = "Waiting for /api/system samples."
                    )
                    AuthRequiredCard(
                        reason = "Session expired on NodelixcoreOS Server.",
                        onSignIn = { loginClicks++ }
                    )
                }
            }
        }

        composeRule.onNodeWithText("No Telemetry History Yet").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Authenticate Now").performClick()

        assertEquals(1, reconnectClicks)
        assertEquals(1, loginClicks)
    }

    @Test
    fun confirmSensitiveActionDialogProtectsDoorAndRelayControls() {
        var confirmed = false
        var dismissed = false

        composeRule.setContent {
            NodelixTheme {
                ConfirmSensitiveActionDialog(
                    title = "Confirm Door Open",
                    description = "Actuate ESP8266 solenoid lock?",
                    confirmLabel = "Unlock Door",
                    isDestructive = true,
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true }
                )
            }
        }

        composeRule.onNodeWithText("Confirm Door Open").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertFalse(confirmed)
        assertTrue(dismissed)

        composeRule.onNodeWithText("Unlock Door").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun errorStateCardDisplaysMessageAndInvokesRetry() {
        var retried = false
        composeRule.setContent {
            NodelixTheme {
                ErrorStateCard(
                    message = "HTTP-only connection is active.",
                    onRetry = { retried = true }
                )
            }
        }

        composeRule.onNodeWithText("HTTP-only connection is active.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }
}
