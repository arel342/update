package com.nodelix.coreos.util

import java.net.URI
import java.net.Inet4Address
import java.net.InetAddress

object Validators {
    private val IPV4_REGEX = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val HOSTNAME_REGEX = Regex(
        """^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)(\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$"""
    )
    private val HEX_UID_REGEX = Regex("""^[0-9A-Fa-f]{8,20}$""")

    fun isValidIpv4(host: String): Boolean {
        val trimmed = host.trim()
        val match = IPV4_REGEX.matchEntire(trimmed) ?: return false
        return match.groupValues.drop(1).all { octet ->
            val value = octet.toIntOrNull() ?: return@all false
            value in 0..255 && (octet.length == 1 || !octet.startsWith('0'))
        }
    }

    fun isValidIpv6(host: String): Boolean {
        val raw = host.trim().removeSurrounding("[", "]")
        if (!raw.contains(':')) return false
        val parts = raw.split("::")
        if (parts.size > 2) return false
        val groups = raw.split(':').filter { it.isNotEmpty() }
        if (groups.isEmpty() || groups.size > 8) return false
        return groups.all { g -> g.length in 1..4 && g.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
    }

    fun isValidHostname(host: String): Boolean {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.all { it.isDigit() || it == '.' }) {
            return isValidIpv4(trimmed)
        }
        return HOSTNAME_REGEX.matches(trimmed)
    }

    fun isValidHostOrIp(host: String): Boolean {
        val trimmed = host.trim()
        return isValidIpv4(trimmed) || isValidIpv6(trimmed) || isValidHostname(trimmed)
    }

    /**
     * Checks whether the host is a private RFC1918 LAN IPv4 address, loopback, RFC4193 ULA IPv6,
     * or an explicit `.local` / `.lan` / `.home.arpa` local network name.
     */
    fun isPrivateLanHost(host: String): Boolean {
        val h = host.trim().lowercase().removeSurrounding("[", "]")
        if (h == "localhost" || h == "127.0.0.1" || h == "::1") return true
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        // Tailscale MagicDNS hostnames (mis. myhost.ts.net)
        if (h.endsWith(".ts.net")) return true

        val ipv4 = IPV4_REGEX.matchEntire(h)
        if (ipv4 != null && isValidIpv4(h)) {
            val a = ipv4.groupValues[1].toInt()
            val b = ipv4.groupValues[2].toInt()
            return when {
                a == 10 -> true
                a == 172 && b in 16..31 -> true
                a == 192 && b == 168 -> true
                a == 100 && b in 64..127 -> true // Tailscale / CGNAT LAN overlay
                else -> false
            }
        }
        if (isValidIpv6(h) && (h.startsWith("fd") || h.startsWith("fc") || h.startsWith("fe80:"))) {
            return true
        }
        return false
    }


    /** True only for private, loopback, link-local or Tailscale/CGNAT IP addresses. */
    fun isPrivateLanAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        val bytes = address.address.map { it.toInt() and 0xff }
        return when {
            address is Inet4Address && bytes.size == 4 -> {
                val a = bytes[0]; val b = bytes[1]
                a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                    (a == 100 && b in 64..127)
            }
            bytes.size == 16 -> (bytes[0] and 0xfe) == 0xfc ||
                (bytes[0] == 0xfe && (bytes[1] and 0xc0) == 0x80)
            else -> false
        }
    }

    /** True bila host adalah IP Tailscale (100.64.0.0/10) atau Tailscale MagicDNS (*.ts.net). */
    fun isTailscaleHost(host: String): Boolean {
        val h = host.trim().lowercase().removeSurrounding("[", "]")
        if (h.endsWith(".ts.net")) return true
        val m = IPV4_REGEX.matchEntire(h)
        if (m != null && isValidIpv4(h)) {
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            return a == 100 && b in 64..127
        }
        return false
    }

    fun isValidPort(port: Int): Boolean = port in 1..65535

    fun isValidTimeoutSeconds(seconds: Int): Boolean =
        seconds in Constants.MIN_TIMEOUT_SECONDS..Constants.MAX_TIMEOUT_SECONDS

    fun validateBaseUrl(protocol: String, host: String, port: Int): ValidationOutcome {
        val scheme = protocol.trim().lowercase()
        if (scheme != "http") {
            return ValidationOutcome.Invalid("Only HTTP is supported.")
        }
        val cleanHost = host.trim()
        if (!isValidHostOrIp(cleanHost)) {
            return ValidationOutcome.Invalid("Invalid IP address or hostname.")
        }
        if (!isValidPort(port)) {
            return ValidationOutcome.Invalid("Port must be between 1 and 65535.")
        }
        val bracketedHost = if (isValidIpv6(cleanHost) && !cleanHost.startsWith("[")) "[$cleanHost]" else cleanHost
        return try {
            val uri = URI("$scheme://$bracketedHost:$port/")
            if (uri.host == null && !isValidIpv6(cleanHost)) {
                ValidationOutcome.Invalid("Malformed server URL.")
            } else {
                ValidationOutcome.Valid(uri.toString())
            }
        } catch (e: Exception) {
            ValidationOutcome.Invalid("Malformed server URL.")
        }
    }

    /**
     * Prevents path traversal (`..`, null bytes, leading drive letters, backslashes) on storage paths.
     */
    fun sanitizeRelativePath(path: String): String? {
        if (path.contains('\u0000') || path.contains('\\')) return null
        val trimmed = path.trim().trimStart('/')
        if (trimmed.isEmpty()) return ""
        val segments = trimmed.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.any { ch -> ch.code < 32 } }) {
            return null
        }
        return segments.joinToString("/")
    }

    fun isValidEntryName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return false
        if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains('\u0000')) return false
        return trimmed.none { it.code < 32 }
    }

    fun isValidEspFirmwareHeader(fileName: String, headerBytes: ByteArray): ValidationOutcome {
        if (!fileName.trim().lowercase().endsWith(".bin")) {
            return ValidationOutcome.Invalid("Firmware file must have a .bin extension.")
        }
        if (headerBytes.size < 16) {
            return ValidationOutcome.Invalid("Firmware binary is too small (< 16 bytes).")
        }
        if (headerBytes[0] != Constants.ESP_FIRMWARE_MAGIC_BYTE) {
            val hex = headerBytes.take(4).joinToString(" ") { "%02X".format(it) }
            return ValidationOutcome.Invalid("Invalid ESP firmware image header ($hex). Expected 0xE9.")
        }
        return ValidationOutcome.Valid(fileName.trim())
    }

    fun isValidRfidUid(uid: String): Boolean = HEX_UID_REGEX.matches(uid.trim())

    // ==================== ESP32 RELAY CHANNEL IDS ====================
    // Mirrors backend `esp32.js resolveChannel()` (3-channel PLTS build):
    // channel 1 = "Relay 1 & 2" (GPIO 26), channel 2 = "Relay 3" (GPIO 27),
    // channel 3 = "Relay 4" (GPIO 32). Returns the normalized id to send, or null
    // when the backend would answer HTTP 400 (unknown channel).

    private val RELAY_PAIR_REGEX = Regex("""^(?:r(?:elay)?\s*)?1\s*[&,\-]\s*2$""")
    private val RELAY_GPIO_REGEX = Regex("""^(?:gpio|pin)[\s\-#]*(\d+)$""")
    private val RELAY_NUMBER_REGEX = Regex("""^(?:r(?:elay)?[\s\-#]*)?(\d+)$""")

    fun normalizeRelayId(rawId: String): String? {
        val s = rawId.trim().lowercase()
        if (s.isEmpty()) return null
        if (s == "all" || s == "semua") return "all"
        if (RELAY_PAIR_REGEX.matches(s)) return "1"
        RELAY_GPIO_REGEX.matchEntire(s)?.let { m ->
            return when (m.groupValues[1].toIntOrNull()) {
                26 -> "1"
                27 -> "3"
                32 -> "4"
                else -> null
            }
        }
        RELAY_NUMBER_REGEX.matchEntire(s)?.let { m ->
            // Mirrors backend `relay-map.js normalizeRelayId()` exactly:
            //   1, 2, 26 -> group "1&2" (GPIO 26)  -> HTTP channel 1
            //   3, 27    -> Relay 3 (GPIO 27)      -> HTTP channel 2, addressed by id 3
            //   4, 32    -> Relay 4 (GPIO 32)      -> HTTP channel 3, addressed by id 4
            return when (m.groupValues[1].toIntOrNull()) {
                1, 2, 26 -> "1"
                3, 27 -> "3"
                4, 32 -> "4"
                else -> null
            }
        }
        return null
    }

    fun isValidRelayId(rawId: String): Boolean = normalizeRelayId(rawId) != null
}

sealed interface ValidationOutcome {
    data class Valid(val normalized: String) : ValidationOutcome
    data class Invalid(val reason: String) : ValidationOutcome
}
