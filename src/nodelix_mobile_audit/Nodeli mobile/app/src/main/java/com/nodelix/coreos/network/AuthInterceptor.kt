package com.nodelix.coreos.network

import com.nodelix.coreos.domain.model.ServerProfile
import com.nodelix.coreos.security.SessionManager
import com.nodelix.coreos.util.Validators
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Enforces HTTP-only LAN transport. HTTP is permitted only for verified private/LAN hosts.
 *
 * Android cleartext is enabled at the manifest level; this interceptor enforces the host
 * policy. It is installed on every HTTP client the app creates:
 * REST, WebSocket, Coil and Media3. [CredentialAndCleartextHopGuard] re-applies the same
 * rule at the network layer so redirect hops are covered as well.
 */
class LanCleartextGuardInterceptor(
    private val profileProvider: () -> ServerProfile?
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.isHttps) {
            throw LanHttpBlockedException("HTTPS is disabled in this HTTP-only build.")
        }
        if (request.url.scheme != "http") {
            throw LanHttpBlockedException("Only HTTP requests are supported.")
        }
        if (!request.url.isHttps) {
            val host = request.url.host
            if (!Validators.isPrivateLanHost(host)) {
                throw LanHttpBlockedException(
                    "Plaintext HTTP to non-LAN host '$host' is strictly prohibited. Use the configured private LAN host."
                )
            }
            val active = profileProvider()
            if (active == null || !Validators.isPrivateLanHost(active.host) ||
                active.host.trim().trim('[', ']').lowercase() != host.trim('[', ']').lowercase() ||
                active.port != request.url.port
            ) {
                throw LanHttpBlockedException("HTTP is allowed only for the configured private LAN server endpoint.")
            }
        }
        return chain.proceed(request)
    }
}

/**
 * Origin + scheme + port matching used to decide whether a request may carry
 * NodelixcoreOS credentials.
 *
 * Security invariants:
 * - `Authorization: Bearer <sessionToken>` and `Cookie: sid=<sid>` are attached ONLY
 *   to requests whose scheme, host and port match the active server profile.
 * - Absolute-URL calls to third-party origins never receive the
 *   NodelixcoreOS session credential, even if a caller forgets the explicit opt-out
 *   header.
 * - `Set-Cookie: sid` values are captured only from the registered server origin, so a
 *   foreign response can never overwrite the vaulted session cookie.
 */
internal object AuthOriginPolicy {

    fun matches(requestUrl: HttpUrl, profile: ServerProfile?): Boolean {
        if (profile == null) return false
        val profileHost = normalizeHost(profile.host)
        if (profileHost.isEmpty()) return false
        if (!requestUrl.scheme.equals(profile.protocol.scheme, ignoreCase = true)) return false
        if (normalizeHost(requestUrl.host) != profileHost) return false
        return requestUrl.port == profile.port
    }

    private fun normalizeHost(raw: String): String =
        raw.trim().lowercase().removeSurrounding("[", "]")

    /**
     * Per-hop policy evaluation, used by [CredentialAndCleartextHopGuard].
     *
     * Application interceptors only see the *initial* request of a call; redirect hops are
     * issued internally by OkHttp. OkHttp strips an `Authorization` header on a cross-host
     * redirect but does not strip a manually set `Cookie` header, so without this check a
     * hostile or misconfigured server could bounce this client to a foreign origin and
     * receive the vaulted `sid` cookie. It equally re-applies the cleartext rule to every
     * hop, so redirects cannot forward credentials to a different host.
     *
     * @return a human readable violation message, or `null` when the request may proceed.
     */
    internal fun hopViolation(
        requestUrl: HttpUrl,
        profile: ServerProfile?,
        carriesCredential: Boolean
    ): String? {
        if (requestUrl.isHttps) return "HTTPS is disabled in this HTTP-only build."
        if (requestUrl.scheme != "http") return "Only HTTP requests are supported."
        if (!requestUrl.isHttps) {
            if (!Validators.isPrivateLanHost(requestUrl.host)) {
                return "Plaintext HTTP to non-LAN host '${requestUrl.host}' is strictly prohibited. Use the configured private LAN host."
            }
            if (profile == null || !Validators.isPrivateLanHost(profile.host) ||
                profile.host.trim().trim('[', ']').lowercase() != requestUrl.host.lowercase() ||
                profile.port != requestUrl.port
            ) {
                return "HTTP is allowed only for the configured private LAN endpoint."
            }
        }
        if (carriesCredential && !matches(requestUrl, profile)) {
            return "Blocked NodelixcoreOS credentials from being forwarded to a foreign origin (${requestUrl.host})."
        }
        return null
    }
}

/**
 * Defence in depth for the credential and cleartext boundaries, installed as an OkHttp
 * **network** interceptor so it also evaluates every redirect hop (application interceptors
 * cannot see them). See [AuthOriginPolicy.hopViolation] for the exact rules.
 */
class CredentialAndCleartextHopGuard(
    private val profileProvider: () -> ServerProfile?
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val carriesCredential =
            request.header("Authorization") != null || request.header("Cookie") != null
        AuthOriginPolicy.hopViolation(request.url, profileProvider(), carriesCredential)?.let { violation ->
            throw LanHttpBlockedException(violation)
        }
        return chain.proceed(request)
    }
}

/**
 * Injects authenticated session credentials (`Authorization: Bearer <token>` for `/api/mobile/...`
 * or `Cookie: sid=<sid>` for NodelixcoreOS v1.1.0 compatibility) into HTTP headers
 * that target the registered server origin, and captures `Set-Cookie: sid=...` from
 * that same origin only.
 *
 * Security rule: tokens are NEVER placed in URL query strings or written to logs, and
 * they are NEVER sent to a foreign origin.
 */
class AuthInterceptor(
    private val sessionManager: SessionManager,
    private val activeProfileProvider: () -> ServerProfile?
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        val activeProfile = activeProfileProvider()
        val sameOrigin = AuthOriginPolicy.matches(original.url, activeProfile)
        val skipAuthRequested = original.header(HEADER_SKIP_AUTH) != null

        // The internal opt-out marker must never reach any server, on any path.
        builder.removeHeader(HEADER_SKIP_AUTH)

        if (!skipAuthRequested && sameOrigin) {
            // Bearer is authoritative when present (backend prefers it); the legacy `sid`
            // cookie is attached only when no Bearer token is active to avoid ambiguous
            // dual-credential requests and accidental cookie-rotation overwrites.
            val bearer = sessionManager.currentBearerToken()
            if (!bearer.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $bearer")
            } else {
                sessionManager.currentCookieSid()?.let { sid ->
                    builder.header("Cookie", "sid=$sid")
                }
            }
        } else {
            // Never forward the internal opt-out marker, and never forward stale
            // credentials that a caller may have set manually on a foreign URL.
            builder.removeHeader(HEADER_SKIP_AUTH)
            builder.removeHeader("Authorization")
            builder.removeHeader("Cookie")
        }

        val response = chain.proceed(builder.build())

        if (sameOrigin) {
            val activeServerId = activeProfile?.id
            if (activeServerId != null) {
                for (setCookie in response.headers("Set-Cookie")) {
                    val sidMatch = SID_COOKIE_CAPTURE.find(setCookie)
                    if (sidMatch != null) {
                        val sidValue = sidMatch.groupValues[1]
                        if (sidValue.isNotBlank()) {
                            sessionManager.updateCookieFromResponse(activeServerId, sidValue)
                        }
                    }
                }
            }
        }
        return response
    }

    companion object {
        const val HEADER_SKIP_AUTH = "X-Nodelix-Skip-Auth"
        private val SID_COOKIE_CAPTURE = Regex("""(?:^|;\s*)sid=([a-fA-F0-9]+)""")
    }
}