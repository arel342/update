package com.nodelix.coreos.util

/**
 * Redacts credentials, Bearer tokens, session cookies, API keys, and PEM private keys
 * before any string enters in-app diagnostic logs or clipboard exports.
 */
object LogSanitizer {
    private val BEARER_REGEX = Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~+/=-]+""")
    private val SID_COOKIE_REGEX = Regex("""(?i)(sid=)([A-Fa-f0-9]{8,})""")
    private val SECRET_JSON_REGEX = Regex(
        """(?i)("(?:password|newPassword|confirmPassword|token|accessToken|sessionToken|apiKey|api_key|privateKey|secret)"\s*:\s*")([^"]*)(")"""
    )
    private val SECRET_KV_REGEX = Regex(
        """(?i)((?:password|token|api_key|apikey|authorization|cookie)\s*[=:]\s*)([^\s,;]+)""",
    )
    private val PEM_KEY_REGEX = Regex(
        """-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""
    )

    fun sanitize(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        return input
            .replace(PEM_KEY_REGEX, "[REDACTED_PRIVATE_KEY]")
            .replace(BEARER_REGEX, "$1[REDACTED_TOKEN]")
            .replace(SID_COOKIE_REGEX, "$1[REDACTED_SID]")
            .replace(SECRET_JSON_REGEX, "$1[REDACTED]$3")
            .replace(SECRET_KV_REGEX, "$1[REDACTED]")
    }
}
