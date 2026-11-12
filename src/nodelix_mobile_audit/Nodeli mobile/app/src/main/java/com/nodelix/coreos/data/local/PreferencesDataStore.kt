package com.nodelix.coreos.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nodelix.coreos.domain.model.ServerProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.nodelixDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nodelix_coreos_prefs"
)

data class AppPreferences(
    val activeServerId: String? = null,
    val darkThemeEnforced: Boolean = true,
    /**
     * Application UI language. Locked to English (`"en"`) by product requirement:
     * the setting cannot be changed from the UI and any legacy stored value is
     * ignored (see [PreferencesDataStore.appPreferencesFlow]).
     */
    val languageCode: String = "en",
    val hapticFeedbackEnabled: Boolean = true,
    val animationsEnabled: Boolean = true,
    val compactDashboardCards: Boolean = false,
    val autoReconnectWebSocket: Boolean = true,
    val lastDashboardSyncMillis: Long? = null,
    val installationDeviceId: String = ""
)

class PreferencesDataStore(
    private val context: Context,
    private val json: Json
) {
    private object Keys {
        val SERVER_PROFILES_JSON = stringPreferencesKey("server_profiles_json")
        val ACTIVE_SERVER_ID = stringPreferencesKey("active_server_id")
        val DARK_THEME = booleanPreferencesKey("dark_theme_enforced")
        // Legacy "language_code" key intentionally removed: UI language is locked to English.
        val HAPTIC = booleanPreferencesKey("haptic_feedback")
        val ANIMATIONS = booleanPreferencesKey("ui_animations")
        val COMPACT_DASHBOARD = booleanPreferencesKey("compact_dashboard")
        val AUTO_RECONNECT_WS = booleanPreferencesKey("auto_reconnect_ws")
        val LAST_SYNC_MS = longPreferencesKey("last_dashboard_sync_ms")
        val INSTALLATION_DEVICE_ID = stringPreferencesKey("installation_device_id")
        val CACHED_SYSTEM_JSON = stringPreferencesKey("cached_system_overview_json")
    }

    val serverProfilesFlow: Flow<List<ServerProfile>> =
        context.nodelixDataStore.data.map { prefs ->
            val raw = prefs[Keys.SERVER_PROFILES_JSON] ?: return@map emptyList()
            runCatching { json.decodeFromString<List<ServerProfile>>(raw) }.getOrDefault(emptyList())
        }

    val appPreferencesFlow: Flow<AppPreferences> =
        context.nodelixDataStore.data.map { prefs ->
            AppPreferences(
                activeServerId = prefs[Keys.ACTIVE_SERVER_ID],
                darkThemeEnforced = prefs[Keys.DARK_THEME] ?: true,
                // English-only: ignore any legacy stored language value.
                languageCode = "en",
                hapticFeedbackEnabled = prefs[Keys.HAPTIC] ?: true,
                animationsEnabled = prefs[Keys.ANIMATIONS] ?: true,
                compactDashboardCards = prefs[Keys.COMPACT_DASHBOARD] ?: false,
                autoReconnectWebSocket = prefs[Keys.AUTO_RECONNECT_WS] ?: true,
                lastDashboardSyncMillis = prefs[Keys.LAST_SYNC_MS],
                installationDeviceId = prefs[Keys.INSTALLATION_DEVICE_ID] ?: ""
            )
        }

    suspend fun getOrCreateInstallationDeviceId(): String {
        val current = context.nodelixDataStore.data.first()[Keys.INSTALLATION_DEVICE_ID]
        if (!current.isNullOrBlank()) return current
        val generated = "ndlx-" + UUID.randomUUID().toString()
        context.nodelixDataStore.edit { it[Keys.INSTALLATION_DEVICE_ID] = generated }
        return generated
    }

    suspend fun saveServerProfiles(profiles: List<ServerProfile>) {
        val encoded = json.encodeToString(profiles)
        context.nodelixDataStore.edit { it[Keys.SERVER_PROFILES_JSON] = encoded }
    }

    suspend fun setActiveServerId(serverId: String?) {
        context.nodelixDataStore.edit { prefs ->
            if (serverId == null) prefs.remove(Keys.ACTIVE_SERVER_ID)
            else prefs[Keys.ACTIVE_SERVER_ID] = serverId
        }
    }

    suspend fun updatePreferences(
        hapticFeedback: Boolean? = null,
        animationsEnabled: Boolean? = null,
        compactDashboard: Boolean? = null,
        autoReconnectWs: Boolean? = null
    ) {
        context.nodelixDataStore.edit { prefs ->
            hapticFeedback?.let { prefs[Keys.HAPTIC] = it }
            animationsEnabled?.let { prefs[Keys.ANIMATIONS] = it }
            compactDashboard?.let { prefs[Keys.COMPACT_DASHBOARD] = it }
            autoReconnectWs?.let { prefs[Keys.AUTO_RECONNECT_WS] = it }
        }
    }

    suspend fun recordSyncTimestamp(epochMillis: Long = System.currentTimeMillis()) {
        context.nodelixDataStore.edit { it[Keys.LAST_SYNC_MS] = epochMillis }
    }
}
