package com.nodelix.coreos.network

import com.nodelix.coreos.core.AppConfig
import com.nodelix.coreos.core.AppError
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.data.local.DiagnosticLogStore
import com.nodelix.coreos.domain.model.ProtocolType
import com.nodelix.coreos.domain.model.ServerProfile
import com.nodelix.coreos.security.SessionManager
import com.nodelix.coreos.util.ValidationOutcome
import com.nodelix.coreos.util.Validators
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Dynamically constructs and caches [OkHttpClient] and [ApiService] instances per [ServerProfile]
 * without any hardcoded IP addresses.
 */
internal class PrivateLanDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = delegate.lookup(hostname)
        if (resolved.isEmpty() || resolved.any { !Validators.isPrivateLanAddress(it) }) {
            throw UnknownHostException("Host '$hostname' did not resolve exclusively to private LAN addresses.")
        }
        return resolved
    }
}

class ApiClient(
    private val json: Json,
    private val sessionManager: SessionManager,
    private val logStore: DiagnosticLogStore,
    private val activeProfileProvider: () -> ServerProfile?
) {
    @Volatile
    private var cachedProfileSnapshot: ServerProfile? = null

    @Volatile
    private var cachedOkHttpClient: OkHttpClient? = null

    @Volatile
    private var cachedApiService: ApiService? = null

    /**
     * Two snapshots describe the same HTTP endpoint when everything that affects the
     * OkHttp/Retrofit configuration matches. Mutable telemetry such as
     * `lastConnectedAt` or `lastResponseTimeMs` is deliberately excluded: it changes on
     * every successful connect and must not tear down the cached client (and its
     * connection pool / dispatcher threads).
     */
    private fun ServerProfile.sameEndpointAs(other: ServerProfile?): Boolean =
        other != null &&
            other.id == id &&
            other.host == host &&
            other.port == port &&
            other.protocol == protocol &&
            other.timeoutSeconds == timeoutSeconds

    fun requireService(): AppResult<ApiService> {
        val profile = activeProfileProvider()
            ?: return AppResult.Failure(AppError.InvalidEndpoint("No active server profile configured."))
        return serviceForProfile(profile, cacheAsActive = true)
    }

    fun serviceForProfile(profile: ServerProfile, cacheAsActive: Boolean = false): AppResult<ApiService> {
        val urlValidation = Validators.validateBaseUrl(profile.protocol.scheme, profile.host, profile.port)
        if (urlValidation is ValidationOutcome.Invalid) {
            return AppResult.Failure(AppError.InvalidEndpoint(urlValidation.reason))
        }
        if (profile.protocol == ProtocolType.HTTP && !Validators.isPrivateLanHost(profile.host)) {
            return AppResult.Failure(AppError.LanHttpBlocked())
        }

        if (cacheAsActive && profile.sameEndpointAs(cachedProfileSnapshot) && cachedApiService != null) {
            return AppResult.Success(cachedApiService!!)
        }

        return try {
            val client = buildOkHttpClient(profile)
            val contentType = "application/json; charset=UTF-8".toMediaType()
            val retrofit = Retrofit.Builder()
                .baseUrl(profile.baseUrl())
                .client(client)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()
            val service = retrofit.create(ApiService::class.java)

            if (cacheAsActive) {
                cachedProfileSnapshot = profile
                cachedOkHttpClient = client
                cachedApiService = service
            }
            AppResult.Success(service)
        } catch (e: Exception) {
            logStore.error("ApiClient", "Failed to initialize HTTP client: ${e.message}")
            AppResult.Failure(AppError.InvalidEndpoint("Could not initialize endpoint for ${profile.host}:${profile.port}."))
        }
    }

    fun currentOkHttpClient(): OkHttpClient {
        val profile = activeProfileProvider()
        if (profile != null) {
            if (profile.sameEndpointAs(cachedProfileSnapshot) && cachedOkHttpClient != null) {
                return cachedOkHttpClient!!
            }
            serviceForProfile(profile, cacheAsActive = true)
            cachedOkHttpClient?.let { return it }
        }
        // No active profile: all HTTP is rejected and no credentials are attached.
        return OkHttpClient.Builder()
            .dns(PrivateLanDns())
            .retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(LanCleartextGuardInterceptor { activeProfileProvider() })
            // Per-hop re-check also covers redirect hops.
            .addNetworkInterceptor(CredentialAndCleartextHopGuard { activeProfileProvider() })
            .build()
    }

    fun buildWebSocketOkHttpClient(profile: ServerProfile): OkHttpClient {
        val timeoutSec = profile.timeoutSeconds.coerceIn(3, 120).toLong()
        return OkHttpClient.Builder()
            .dns(PrivateLanDns())
            .retryOnConnectionFailure(false)
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Long-lived WebSocket stream
            .pingInterval(AppConfig.WS_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(LanCleartextGuardInterceptor { profile })
            .addNetworkInterceptor(CredentialAndCleartextHopGuard { profile })
            .build()
    }

    private fun buildOkHttpClient(profile: ServerProfile): OkHttpClient {
        val timeoutSec = profile.timeoutSeconds.coerceIn(3, 120).toLong()

        val logging = HttpLoggingInterceptor { rawLine ->
            logStore.log(com.nodelix.coreos.domain.model.LogSeverity.DEBUG, "HTTP", rawLine)
        }.apply {
            redactHeader("Authorization")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
            redactHeader("X-API-Key")
            level = if (AppConfig.IS_DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .dns(PrivateLanDns())
            // Never auto-retry indeterminate state-changing requests (Door Open, Relay, OTA)
            .retryOnConnectionFailure(false)
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(maxOf(timeoutSec, 30L), TimeUnit.SECONDS)
            .writeTimeout(maxOf(timeoutSec, 60L), TimeUnit.SECONDS)
            .addInterceptor(LanCleartextGuardInterceptor { profile })
            // Network-level defence in depth: re-validates cleartext + credential origin on
            // every actual connection, including redirect hops. A 302 to a foreign origin can
            // then never carry the vaulted `sid` cookie (OkHttp strips only `Authorization`).
            .addNetworkInterceptor(CredentialAndCleartextHopGuard { profile })
            // Bind the network-hop guard to this client's configured endpoint. The
            // AuthInterceptor independently binds credentials to the active profile,
            // so a connection probe cannot inherit another server's session.
            .addInterceptor(AuthInterceptor(sessionManager) { activeProfileProvider() })
            .addInterceptor(logging)
            .build()
    }
}
