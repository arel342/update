package com.nodelix.coreos.data.local

import com.nodelix.coreos.domain.model.DiagnosticLogEntry
import com.nodelix.coreos.domain.model.LogSeverity
import com.nodelix.coreos.util.LogSanitizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe bounded ring buffer for in-app diagnostics.
 * Automatically scrubs tokens, cookies, passwords, API keys, and private keys via [LogSanitizer].
 */
class DiagnosticLogStore(
    private val maxEntries: Int = 300
) {
    private val sequence = AtomicLong(0L)
    private val _logs = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val logs: StateFlow<List<DiagnosticLogEntry>> = _logs.asStateFlow()

    @Volatile
    var lastErrorSummary: String? = null
        private set

    fun log(severity: LogSeverity, tag: String, message: String) {
        val clean = LogSanitizer.sanitize(message)
        if (severity == LogSeverity.ERROR) {
            lastErrorSummary = clean
        }
        val entry = DiagnosticLogEntry(
            id = sequence.incrementAndGet(),
            timestampMillis = System.currentTimeMillis(),
            severity = severity,
            tag = tag.take(24),
            sanitizedMessage = clean.take(600)
        )
        _logs.update { current ->
            val updated = listOf(entry) + current
            if (updated.size > maxEntries) updated.take(maxEntries) else updated
        }
    }

    fun info(tag: String, message: String) = log(LogSeverity.INFO, tag, message)
    fun warn(tag: String, message: String) = log(LogSeverity.WARN, tag, message)
    fun error(tag: String, message: String) = log(LogSeverity.ERROR, tag, message)

    fun clear() {
        _logs.value = emptyList()
        lastErrorSummary = null
    }
}
