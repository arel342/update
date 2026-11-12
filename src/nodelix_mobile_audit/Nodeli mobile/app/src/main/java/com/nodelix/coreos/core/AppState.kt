package com.nodelix.coreos.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodelix.coreos.data.local.AppPreferences
import com.nodelix.coreos.domain.model.ConnectionState
import com.nodelix.coreos.domain.model.SessionState
import com.nodelix.coreos.network.LocalNetworkStatus

/**
 * Top-level observable application UI state snapshot shared across root navigation chrome.
 */
@Stable
data class NodelixAppState(
    val preferences: AppPreferences,
    val connectionState: ConnectionState,
    val sessionState: SessionState,
    val networkStatus: LocalNetworkStatus,
    val unreadNotificationCount: Int
)

@Composable
fun rememberNodelixAppState(container: AppContainer): NodelixAppState {
    val prefs by container.preferencesDataStore.appPreferencesFlow
        .collectAsStateWithLifecycle(initialValue = AppPreferences())
    val connState by container.serverRepository.connectionState.collectAsStateWithLifecycle()
    val sessionState by container.sessionManager.sessionState.collectAsStateWithLifecycle()
    val netStatus by container.networkMonitor.status.collectAsStateWithLifecycle()
    val unreadCount by container.notificationRepository.unreadCount.collectAsStateWithLifecycle()

    return remember(prefs, connState, sessionState, netStatus, unreadCount) {
        NodelixAppState(
            preferences = prefs,
            connectionState = connState,
            sessionState = sessionState,
            networkStatus = netStatus,
            unreadNotificationCount = unreadCount
        )
    }
}
