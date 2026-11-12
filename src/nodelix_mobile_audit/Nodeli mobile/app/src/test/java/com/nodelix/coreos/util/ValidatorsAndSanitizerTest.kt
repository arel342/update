package com.nodelix.coreos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorsAndSanitizerTest {

    @Test
    fun `validateBaseUrl accepts HTTP and valid host and port`() {
        val result = Validators.validateBaseUrl("http", "nodelix.local", 8443)
        assertTrue(result is ValidationOutcome.Valid)
        assertEquals("http://nodelix.local:8443/", (result as ValidationOutcome.Valid).normalized)
    }

    @Test
    fun `validateBaseUrl rejects out of range port and unsupported scheme`() {
        assertTrue(Validators.validateBaseUrl("https", "192.168.1.50", 3000) is ValidationOutcome.Invalid)
        assertTrue(Validators.validateBaseUrl("ftp", "192.168.1.50", 3000) is ValidationOutcome.Invalid)
        assertTrue(Validators.validateBaseUrl("http", "192.168.1.50", 0) is ValidationOutcome.Invalid)
        assertTrue(Validators.validateBaseUrl("http", "192.168.1.50", 70000) is ValidationOutcome.Invalid)
        assertTrue(Validators.validateBaseUrl("http", "999.999.1.1", 3000) is ValidationOutcome.Invalid)
    }

    @Test
    fun `isPrivateLanHost distinguishes RFC1918 and local names from public hosts`() {
        assertTrue(Validators.isPrivateLanHost("192.168.1.10"))
        assertTrue(Validators.isPrivateLanHost("10.0.0.25"))
        assertTrue(Validators.isPrivateLanHost("172.16.4.8"))
        assertTrue(Validators.isPrivateLanHost("172.31.255.1"))
        assertTrue(Validators.isPrivateLanHost("nodelix-hub.local"))
        assertTrue(Validators.isPrivateLanHost("127.0.0.1"))

        assertFalse(Validators.isPrivateLanHost("8.8.8.8"))
        assertFalse(Validators.isPrivateLanHost("172.32.0.1"))
        assertFalse(Validators.isPrivateLanHost("api.example.com"))
        assertTrue(Validators.isPrivateLanAddress(java.net.InetAddress.getByName("192.168.1.10")))
        assertTrue(Validators.isPrivateLanAddress(java.net.InetAddress.getByName("100.101.102.103")))
        assertFalse(Validators.isPrivateLanAddress(java.net.InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun `sanitizeRelativePath blocks directory traversal and null bytes`() {
        assertEquals("", Validators.sanitizeRelativePath(""))
        assertEquals("logs/2026/system.log", Validators.sanitizeRelativePath("/logs/2026/system.log"))

        assertNull(Validators.sanitizeRelativePath("../etc/passwd"))
        assertNull(Validators.sanitizeRelativePath("logs/../../server.js"))
        assertNull(Validators.sanitizeRelativePath("logs\\secret.txt"))
        assertNull(Validators.sanitizeRelativePath("logs/bad\u0000file.txt"))
    }

    @Test
    fun `isValidEntryName blocks slashes dots and control chars`() {
        assertTrue(Validators.isValidEntryName("backup_2026.zip"))
        assertFalse(Validators.isValidEntryName(".."))
        assertFalse(Validators.isValidEntryName("."))
        assertFalse(Validators.isValidEntryName("sub/folder"))
        assertFalse(Validators.isValidEntryName("sub\\folder"))
    }

    @Test
    fun `isValidEspFirmwareHeader enforces bin extension and 0xE9 magic byte`() {
        val validHeader = ByteArray(32).also { it[0] = 0xE9.toByte() }
        val wrongMagic = ByteArray(32).also { it[0] = 0x50.toByte() } // ZIP 'P'

        assertTrue(Validators.isValidEspFirmwareHeader("esp32_v2.bin", validHeader) is ValidationOutcome.Valid)
        assertTrue(Validators.isValidEspFirmwareHeader("esp32_v2.zip", validHeader) is ValidationOutcome.Invalid)
        assertTrue(Validators.isValidEspFirmwareHeader("esp32_v2.bin", wrongMagic) is ValidationOutcome.Invalid)
        assertTrue(Validators.isValidEspFirmwareHeader("esp32_v2.bin", ByteArray(8)) is ValidationOutcome.Invalid)
    }

    @Test
    fun `normalizeRelayId maps every accepted alias to backend channels and rejects unknown`() {
        // Relay 1 & 2 pair (GPIO 26) -> backend channel "1"
        assertEquals("1", Validators.normalizeRelayId("1&2"))
        assertEquals("1", Validators.normalizeRelayId("1 & 2"))
        assertEquals("1", Validators.normalizeRelayId("relay 1&2"))
        assertEquals("1", Validators.normalizeRelayId("1"))
        assertEquals("1", Validators.normalizeRelayId("26"))
        assertEquals("1", Validators.normalizeRelayId("gpio26"))
        assertEquals("1", Validators.normalizeRelayId("GPIO 26"))

        // Relay 1 & 2 aliases "2" and "26" address the pair group, exactly like the
        // backend `relay-map.js normalizeRelayId()` (previously mismapped to Relay 3,
        // which actuated the wrong physical relay).
        assertEquals("1", Validators.normalizeRelayId("2"))
        assertEquals("1", Validators.normalizeRelayId("relay 2"))

        // Relay 3 (GPIO 27) -> backend channel "3"
        assertEquals("3", Validators.normalizeRelayId("3"))
        assertEquals("3", Validators.normalizeRelayId("relay-3"))
        assertEquals("3", Validators.normalizeRelayId("gpio27"))

        // Relay 4 (GPIO 32) -> backend channel "4"
        assertEquals("4", Validators.normalizeRelayId("4"))
        assertEquals("4", Validators.normalizeRelayId("relay-4"))
        assertEquals("4", Validators.normalizeRelayId("relay 4"))
        assertEquals("4", Validators.normalizeRelayId("gpio32"))

        // Broadcast alias passes through untouched
        assertEquals("all", Validators.normalizeRelayId("all"))
        assertEquals("all", Validators.normalizeRelayId("ALL"))

        // Unknown channels must fail fast (backend would answer HTTP 400)
        assertNull(Validators.normalizeRelayId("99"))
        assertNull(Validators.normalizeRelayId("gpio99"))
        assertNull(Validators.normalizeRelayId("relay-9"))
        assertNull(Validators.normalizeRelayId(""))
        assertNull(Validators.normalizeRelayId("relay"))
    }

    @Test
    fun `LogSanitizer scrubs Bearer tokens session cookies passwords and PEM private keys`() {
        val raw = """
            Authorization: Bearer eyJhbGciOiJFUzI1NiJ9.superSecretPayload
            Cookie: sid=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
            {"username":"admin","password":"MySuperSecretPassword!","token":"99887766"}
            -----BEGIN EC PRIVATE KEY-----
            MHcCAQEEII1234567890secretkeymaterial==
            -----END EC PRIVATE KEY-----
        """.trimIndent()

        val sanitized = LogSanitizer.sanitize(raw)
        assertFalse(sanitized.contains("superSecretPayload"))
        assertFalse(sanitized.contains("0123456789abcdef0123456789abcdef"))
        assertFalse(sanitized.contains("MySuperSecretPassword!"))
        assertFalse(sanitized.contains("99887766"))
        assertFalse(sanitized.contains("secretkeymaterial"))
        assertTrue(sanitized.contains("[REDACTED"))
    }
}
