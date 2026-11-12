package com.nodelix.coreos.util

import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object FileUtils {
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v")
    private val TEXT_EXTENSIONS = setOf(
        "txt", "log", "json", "jsonl", "md", "csv", "tsv", "yml", "yaml",
        "ini", "conf", "cfg", "sh", "py", "js", "ts", "kt", "ino", "xml", "html", "css", "sql"
    )
    private val DANGEROUS_EXECUTABLE_EXTENSIONS = setOf(
        "apk", "dex", "so", "elf", "exe", "bat", "msi"
    )

    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes < 0L) return "N/A"
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.lastIndex)
        val value = bytes / 1024.0.pow(exp.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[exp])
    }

    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase(Locale.US)

    fun isImageFile(fileName: String): Boolean = extensionOf(fileName) in IMAGE_EXTENSIONS

    fun isVideoFile(fileName: String): Boolean = extensionOf(fileName) in VIDEO_EXTENSIONS

    fun isTextFile(fileName: String): Boolean = extensionOf(fileName) in TEXT_EXTENSIONS

    fun isAutoExecutableBlocked(fileName: String): Boolean =
        extensionOf(fileName) in DANGEROUS_EXECUTABLE_EXTENSIONS

    fun guessMimeType(fileName: String): String = when (extensionOf(fileName)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "json" -> "application/json"
        "zip" -> "application/zip"
        "pdf" -> "application/pdf"
        "bin" -> "application/octet-stream"
        in TEXT_EXTENSIONS -> "text/plain"
        else -> "application/octet-stream"
    }
}
