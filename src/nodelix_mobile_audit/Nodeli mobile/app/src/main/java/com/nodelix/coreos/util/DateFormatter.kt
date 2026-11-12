package com.nodelix.coreos.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateFormatter {
    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
            .withZone(ZoneId.systemDefault())

    private val shortTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
            .withZone(ZoneId.systemDefault())

    fun formatEpochMillis(epochMillis: Long?): String {
        if (epochMillis == null || epochMillis <= 0L) return "N/A"
        return try {
            timestampFormatter.format(Instant.ofEpochMilli(epochMillis))
        } catch (_: Exception) {
            "N/A"
        }
    }

    fun formatShortTime(epochMillis: Long?): String {
        if (epochMillis == null || epochMillis <= 0L) return "--:--:--"
        return try {
            shortTimeFormatter.format(Instant.ofEpochMilli(epochMillis))
        } catch (_: Exception) {
            "--:--:--"
        }
    }

    fun formatUptimeSeconds(seconds: Long?): String {
        if (seconds == null || seconds < 0L) return "N/A"
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val minutes = (seconds % 3_600) / 60
        val secs = seconds % 60
        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m ${secs}s"
            minutes > 0 -> "${minutes}m ${secs}s"
            else -> "${secs}s"
        }
    }

    fun formatRelativeAge(epochMillis: Long?, nowMillis: Long = System.currentTimeMillis()): String {
        if (epochMillis == null || epochMillis <= 0L) return "Never"
        val diffSeconds = ((nowMillis - epochMillis) / 1000L).coerceAtLeast(0L)
        return when {
            diffSeconds < 5L -> "Just now"
            diffSeconds < 60L -> "${diffSeconds}s ago"
            diffSeconds < 3_600L -> "${diffSeconds / 60L}m ago"
            diffSeconds < 86_400L -> "${diffSeconds / 3_600L}h ago"
            else -> "${diffSeconds / 86_400L}d ago"
        }
    }
}
