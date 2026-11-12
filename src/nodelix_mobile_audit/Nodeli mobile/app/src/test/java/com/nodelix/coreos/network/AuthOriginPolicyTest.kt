package com.nodelix.coreos.network

import com.nodelix.coreos.domain.model.ProtocolType
import com.nodelix.coreos.domain.model.ServerProfile
import com.nodelix.coreos.util.Validators
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the credential-origin boundary.
 *
 * The NodelixcoreOS Bearer token / `sid` cookie must only ever reach the registered
 * server origin; absolute-URL requests such as the external release service must never
 * receive them.
 */
class AuthOriginPolicyTest {

    private fun lanProfile(
        host: String = "192.168.1.10",
        port: Int = 3000,
        protocol: ProtocolType = ProtocolType.HTTP
    ) = ServerProfile(
        id = "profile-1",
        name = "LAN Node",
        host = host,
        port = port,
        protocol = protocol
    )

    @Test
    fun `matches the registered server origin only`() {
        val profile = lanProfile()
        assertTrue(AuthOriginPolicy.matches("http://192.168.1.10:3000/api/system".toHttpUrl(), profile))
        assertTrue(AuthOriginPolicy.matches("http://192.168.1.10:3000/".toHttpUrl(), profile))
        assertFalse(AuthOriginPolicy.matches("https://192.168.1.10:3000/".toHttpUrl(), profile))
        assertNotNull(AuthOriginPolicy.hopViolation("https://192.168.1.10:3000/".toHttpUrl(), profile, false))
    }

    @Test
    fun `rejects GitHub and every other foreign origin`() {
        val profile = lanProfile()
        assertFalse(
            AuthOriginPolicy.matches(
                "http://api.github.com/repos/arel342/Nlodelix-mobile/releases/latest".toHttpUrl(),
                profile
            )
        )
        assertFalse(AuthOriginPolicy.matches("http://192.168.1.11:3000/api/system".toHttpUrl(), profile))
        assertFalse(AuthOriginPolicy.matches("http://192.168.1.10:3001/api/system".toHttpUrl(), profile))
        assertTrue(AuthOriginPolicy.matches("http://192.168.1.10:3000/api/system".toHttpUrl(), profile))
        assertFalse(AuthOriginPolicy.matches("http://evil.example.com/api/system".toHttpUrl(), profile))
    }

    @Test
    fun `rejects everything when no profile is active`() {
        assertFalse(AuthOriginPolicy.matches("http://192.168.1.10:3000/api/system".toHttpUrl(), null))
    }

    @Test
    fun `normalizes host casing and brackets`() {
        val lanProfileWithPort = lanProfile(host = "Nodelix.Local", port = 8443, protocol = ProtocolType.HTTP)
        assertTrue(AuthOriginPolicy.matches("http://nodelix.local:8443/api/system".toHttpUrl(), lanProfileWithPort))

        val ipv6Profile = ServerProfile(
            id = "profile-6",
            name = "IPv6 LAN",
            host = "[fd00::10]",
            port = 8443,
            protocol = ProtocolType.HTTP
        )
        assertTrue(AuthOriginPolicy.matches("http://[fd00::10]:8443/api/system".toHttpUrl(), ipv6Profile))
    }

    @Test
    fun `cleartext eligibility uses the same LAN predicate as the guard interceptor`() {
        // `LanCleartextGuardInterceptor` uses these exact predicates; asserted here so the
        // credential-boundary and cleartext-boundary decisions cannot drift apart.
        assertTrue(com.nodelix.coreos.util.Validators.isPrivateLanHost("192.168.1.10"))
        assertTrue(com.nodelix.coreos.util.Validators.isPrivateLanHost("100.101.102.103"))
        assertTrue(com.nodelix.coreos.util.Validators.isPrivateLanHost("nodelix.local"))
        assertFalse(com.nodelix.coreos.util.Validators.isPrivateLanHost("api.github.com"))
        assertFalse(com.nodelix.coreos.util.Validators.isPrivateLanHost("8.8.8.8"))

        // A public host can never be marked as an acceptable cleartext target.
        assertFalse(Validators.isPrivateLanHost("api.github.com"))
    }

    @Test
    fun `per hop guard allows same origin credentials over HTTP`() {
        val profile = lanProfile(host = "192.168.1.10", port = 8443, protocol = ProtocolType.HTTP)
        assertNull(
            AuthOriginPolicy.hopViolation(
                "http://192.168.1.10:8443/api/system".toHttpUrl(),
                profile,
                carriesCredential = true
            )
        )
    }

    @Test
    fun `per hop guard blocks credentials that a redirect would forward to a foreign origin`() {
        val profile = lanProfile(host = "192.168.1.10", port = 8443, protocol = ProtocolType.HTTP)
        // A 302 to a foreign host must not carry the session token or the `sid` cookie.
        assertNotNull(
            AuthOriginPolicy.hopViolation(
                "http://evil.example.com/collect".toHttpUrl(),
                profile,
                carriesCredential = true
            )
        )
        // Public HTTP is blocked even when no credential is attached.
        assertNotNull(
            AuthOriginPolicy.hopViolation(
                "http://api.github.com/repos/arel342/Nlodelix-mobile/releases/latest".toHttpUrl(),
                profile,
                carriesCredential = false
            )
        )
    }

    @Test
    fun `per hop guard re-applies the cleartext rule after a redirect hop`() {
        val lanProfileWithPort = lanProfile(host = "192.168.1.10", port = 8443, protocol = ProtocolType.HTTP)
        // Redirect to a public host is blocked.
        assertNotNull(
            AuthOriginPolicy.hopViolation(
                "http://evil.example.com/x".toHttpUrl(),
                lanProfileWithPort,
                carriesCredential = false
            )
        )
        // Plaintext on the configured LAN host is permitted.
        val lanHttp = lanProfile()
        assertNull(AuthOriginPolicy.hopViolation("http://192.168.1.10:3000/x".toHttpUrl(), lanHttp, false))
        assertNotNull(AuthOriginPolicy.hopViolation("http://192.168.1.10:3001/x".toHttpUrl(), lanHttp, false))
        assertNotNull(AuthOriginPolicy.hopViolation("http://192.168.1.10:3000/x".toHttpUrl(), null, false))
    }
}
