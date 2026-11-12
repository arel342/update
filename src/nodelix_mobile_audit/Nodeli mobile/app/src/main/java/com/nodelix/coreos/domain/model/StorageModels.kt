package com.nodelix.coreos.domain.model

data class StorageEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedMillis: Long?
)

data class StorageDirectoryListing(
    val currentDir: String,
    val entries: List<StorageEntry>,
    val storageReady: Boolean,
    val rootPath: String?,
    val totalBytes: Long?,
    val freeBytes: Long?,
    val statusMessage: String?
)

data class TransferProgress(
    val fileName: String,
    val isUpload: Boolean,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val speedMbps: Double,
    val completed: Boolean = false,
    val errorMessage: String? = null
) {
    val fraction: Float
        get() = if (totalBytes > 0L) {
            (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else 0f
}

enum class MediaPreviewType {
    IMAGE,
    VIDEO,
    TEXT,
    UNSUPPORTED
}

data class TextFilePreview(
    val relativePath: String,
    val fileName: String,
    val content: String,
    val sizeBytes: Long,
    val truncated: Boolean
)
