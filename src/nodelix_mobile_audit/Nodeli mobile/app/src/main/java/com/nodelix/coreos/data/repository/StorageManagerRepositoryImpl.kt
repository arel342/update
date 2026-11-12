package com.nodelix.coreos.data.repository

import android.content.Context
import android.net.Uri
import com.nodelix.coreos.core.AppConfig
import com.nodelix.coreos.core.AppError
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.data.local.DiagnosticLogStore
import com.nodelix.coreos.data.model.CompressFileRequestDto
import com.nodelix.coreos.data.model.CreateFolderRequestDto
import com.nodelix.coreos.data.model.PasteRequestDto
import com.nodelix.coreos.data.model.RenameFileRequestDto
import com.nodelix.coreos.data.remote.ProgressRequestBody
import com.nodelix.coreos.domain.model.NotificationCategory
import com.nodelix.coreos.domain.model.ServerProfile
import com.nodelix.coreos.domain.model.StorageDirectoryListing
import com.nodelix.coreos.domain.model.StorageEntry
import com.nodelix.coreos.domain.model.TextFilePreview
import com.nodelix.coreos.domain.model.TransferProgress
import com.nodelix.coreos.domain.repository.NotificationCenterRepository
import com.nodelix.coreos.domain.repository.StorageManagerRepository
import com.nodelix.coreos.network.ApiClient
import com.nodelix.coreos.network.NetworkResultMapper
import com.nodelix.coreos.security.SessionManager
import com.nodelix.coreos.util.FileUtils
import com.nodelix.coreos.util.Validators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class StorageManagerRepositoryImpl(
    private val appContext: Context,
    private val apiClient: ApiClient,
    private val sessionManager: SessionManager,
    private val notificationRepository: NotificationCenterRepository,
    private val logStore: DiagnosticLogStore,
    private val json: Json,
    private val activeProfileProvider: () -> ServerProfile?
) : StorageManagerRepository {

    private val _transferProgress = MutableStateFlow<TransferProgress?>(null)
    override val transferProgress: StateFlow<TransferProgress?> = _transferProgress.asStateFlow()

    private var activeCancelFlag = AtomicBoolean(false)

    override fun cancelActiveTransfer() {
        activeCancelFlag.set(true)
        _transferProgress.value = null
    }

    override suspend fun listDirectory(relativeDir: String): AppResult<StorageDirectoryListing> {
        val safeDir = Validators.sanitizeRelativePath(relativeDir)
            ?: return AppResult.Failure(AppError.BadRequest("Unsafe or invalid directory path rejected."))

        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }

        return try {
            coroutineScope {
                val statusDeferred = async { svc.getStorageStatus() }
                val filesDeferred = async { svc.listFiles(safeDir) }

                val statusResp = statusDeferred.await()
                val statusBody = statusResp.body()
                if (statusResp.isSuccessful && statusBody != null && !statusBody.ready) {
                    return@coroutineScope AppResult.Failure(
                        AppError.StorageUnavailable(statusBody.message ?: "External USB flash drive is not ready.")
                    )
                }

                val filesResp = filesDeferred.await()
                when (val mapped = NetworkResultMapper.mapResponse(filesResp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                    is AppResult.Failure -> mapped
                    is AppResult.Success -> {
                        val cleanCur = Validators.sanitizeRelativePath(mapped.data.currentDir) ?: safeDir
                        val entries = mapped.data.files.map { f ->
                            val rel = if (cleanCur.isEmpty()) f.name else "$cleanCur/${f.name}"
                            val modMillis = f.modified?.let { iso ->
                                runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
                            }
                            StorageEntry(
                                name = f.name,
                                relativePath = rel,
                                isDirectory = f.isDirectory,
                                sizeBytes = f.size,
                                modifiedMillis = modMillis
                            )
                        }
                        AppResult.Success(
                            StorageDirectoryListing(
                                currentDir = cleanCur,
                                entries = entries,
                                storageReady = statusBody?.ready ?: true,
                                rootPath = statusBody?.root?.ifBlank { null },
                                totalBytes = statusBody?.total,
                                freeBytes = statusBody?.free,
                                statusMessage = statusBody?.message
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Storage") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun createFolder(parentDir: String, folderName: String): AppResult<Unit> {
        val safeDir = Validators.sanitizeRelativePath(parentDir)
            ?: return AppResult.Failure(AppError.BadRequest("Invalid parent folder path."))
        if (!Validators.isValidEntryName(folderName)) {
            return AppResult.Failure(AppError.BadRequest("Invalid folder name."))
        }
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.createFolder(CreateFolderRequestDto(dir = safeDir, folderName = folderName.trim()))
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> AppResult.Success(Unit)
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Storage") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun renameEntry(parentDir: String, oldName: String, newName: String): AppResult<Unit> {
        val safeDir = Validators.sanitizeRelativePath(parentDir)
            ?: return AppResult.Failure(AppError.BadRequest("Invalid directory path."))
        if (!Validators.isValidEntryName(oldName) || !Validators.isValidEntryName(newName)) {
            return AppResult.Failure(AppError.BadRequest("Invalid file or folder name."))
        }
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.renameFile(
                RenameFileRequestDto(dir = safeDir, oldName = oldName.trim(), newName = newName.trim())
            )
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> AppResult.Success(Unit)
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Storage") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun deleteEntry(relativePath: String): AppResult<Unit> {
        val safePath = Validators.sanitizeRelativePath(relativePath)
        if (safePath.isNullOrEmpty()) {
            return AppResult.Failure(AppError.BadRequest("Root storage directory cannot be deleted."))
        }
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.deleteFile(safePath)
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    logStore.info("Storage", "Deleted storage entry: $safePath")
                    notificationRepository.pushNotification(
                        title = "Storage Item Deleted",
                        description = safePath,
                        category = NotificationCategory.STORAGE
                    )
                    AppResult.Success(Unit)
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Storage") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun compressToZip(parentDir: String, entryName: String): AppResult<String> {
        val safeDir = Validators.sanitizeRelativePath(parentDir)
            ?: return AppResult.Failure(AppError.BadRequest("Invalid directory path."))
        if (!Validators.isValidEntryName(entryName)) {
            return AppResult.Failure(AppError.BadRequest("Invalid item name."))
        }
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.compressFile(CompressFileRequestDto(dir = safeDir, name = entryName.trim()))
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> AppResult.Success(mapped.data.name ?: "$entryName.zip")
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Storage") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun pasteEntries(
        action: String,
        sourceDir: String,
        targetDir: String,
        items: List<String>
    ): AppResult<String> {
        val op = action.trim().lowercase()
        if (op != "copy" && op != "cut") {
            return AppResult.Failure(AppError.BadRequest("Paste action must be copy or cut."))
        }
        val safeSource = Validators.sanitizeRelativePath(sourceDir)
            ?: return AppResult.Failure(AppError.BadRequest("Invalid source directory path."))
        val safeTarget = Validators.sanitizeRelativePath(targetDir)
            ?: return AppResult.Failure(AppError.BadRequest("Invalid target directory path."))
        val cleanItems = items.map { it.trim() }.filter { Validators.isValidEntryName(it) }
        if (cleanItems.isEmpty()) {
            return AppResult.Failure(AppError.BadRequest("No valid items selected to paste."))
        }
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.pasteEntries(
                PasteRequestDto(
                    action = op,
                    sourceDir = safeSource,
                    targetDir = safeTarget,
                    items = cleanItems
                )
            )
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    val verb = if (op == "copy") "Copied" else "Moved"
                    notificationRepository.pushNotification(
                        title = "Storage Items ${if (op == "copy") "Copied" else "Moved"}",
                        description = "$verb ${cleanItems.size} item(s) to '${safeTarget.ifBlank { "/" }}'.",
                        category = NotificationCategory.STORAGE
                    )
                    AppResult.Success(mapped.data.resolvedMessage ?: "$verb ${cleanItems.size} item(s).")
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Storage") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun uploadFile(
        targetDir: String,
        sourceUri: Uri,
        fileName: String,
        sizeBytes: Long
    ): AppResult<String> = withContext(Dispatchers.IO) {
        val safeDir = Validators.sanitizeRelativePath(targetDir)
            ?: return@withContext AppResult.Failure(AppError.BadRequest("Invalid upload target folder."))
        if (!Validators.isValidEntryName(fileName)) {
            return@withContext AppResult.Failure(AppError.BadRequest("Invalid upload filename."))
        }

        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return@withContext r
            is AppResult.Success -> r.data
        }

        // Own the cancellation flag for THIS transfer only. Reading the shared
        // `activeCancelFlag` field inside the catch block could observe a *different*
        // (or a stale) flag and misreport a real network failure as "cancelled".
        val cancelFlag = AtomicBoolean(false).also { activeCancelFlag = it }

        try {
            // Check free storage space first (mirrors public/upload.js behavior)
            val statusResp = svc.getStorageStatus()
            val status = statusResp.body()
            if (status != null) {
                if (!status.ready) {
                    return@withContext AppResult.Failure(
                        AppError.StorageUnavailable(status.message ?: "Server storage not ready.")
                    )
                }
                if (status.free != null && sizeBytes > 0L && sizeBytes > status.free) {
                    return@withContext AppResult.Failure(
                        AppError.BadRequest(
                            "Insufficient server flash storage (${FileUtils.formatBytes(status.free)} free)."
                        )
                    )
                }
            }

            val mime = appContext.contentResolver.getType(sourceUri) ?: FileUtils.guessMimeType(fileName)
            val startNs = System.nanoTime()

            val body = ProgressRequestBody(
                contentType = mime.toMediaTypeOrNull(),
                contentLengthBytes = sizeBytes,
                cancelledFlag = cancelFlag,
                inputStreamProvider = { appContext.contentResolver.openInputStream(sourceUri) },
                onProgress = { written, total ->
                    val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.05)
                    val mbps = (written * 8.0 / 1_000_000.0) / elapsedSec
                    _transferProgress.value = TransferProgress(
                        fileName = fileName,
                        isUpload = true,
                        bytesTransferred = written,
                        totalBytes = total,
                        speedMbps = mbps
                    )
                }
            )
            val part = MultipartBody.Part.createFormData("file", fileName.trim(), body)
            val resp = svc.uploadFile(safeDir, part)
            _transferProgress.value = null

            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    val savedName = mapped.data.name ?: fileName
                    notificationRepository.pushNotification(
                        title = "File Uploaded",
                        description = "$savedName (${FileUtils.formatBytes(sizeBytes)})",
                        category = NotificationCategory.STORAGE
                    )
                    AppResult.Success(savedName)
                }
            }
        } catch (e: Exception) {
            _transferProgress.value = null
            if (cancelFlag.get()) {
                AppResult.Failure(AppError.BadRequest("Upload cancelled."))
            } else {
                NetworkResultMapper.mapThrowable(e, json, logStore, "Storage") { sessionManager.onUnauthorizedOrExpired() }
            }
        }
    }

    override suspend fun downloadFile(
        relativePath: String,
        destinationUri: Uri,
        asZip: Boolean
    ): AppResult<Long> = withContext(Dispatchers.IO) {
        val safePath = Validators.sanitizeRelativePath(relativePath)
        if (safePath.isNullOrEmpty()) {
            return@withContext AppResult.Failure(AppError.BadRequest("Invalid download path."))
        }
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return@withContext r
            is AppResult.Success -> r.data
        }
        val cancelFlag = AtomicBoolean(false).also { activeCancelFlag = it }
        val fileName = safePath.substringAfterLast('/')

        try {
            val resp = svc.downloadFile(safePath, zipFlag = if (asZip) "1" else null)
            if (!resp.isSuccessful || resp.body() == null) {
                return@withContext NetworkResultMapper.mapResponse(resp, json) {
                    sessionManager.onUnauthorizedOrExpired()
                }.let {
                    when (it) {
                        is AppResult.Failure -> it
                        is AppResult.Success -> AppResult.Failure(AppError.Unknown())
                    }
                }
            }

            val body = resp.body()!!
            val total = body.contentLength().coerceAtLeast(0L)
            val startNs = System.nanoTime()
            var copied = 0L

            body.byteStream().use { input ->
                val out = appContext.contentResolver.openOutputStream(destinationUri)
                    ?: throw IOException("Cannot write to selected destination file.")
                out.use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        ensureActive()
                        if (cancelFlag.get()) throw IOException("Download cancelled.")
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read
                        val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.05)
                        val mbps = (copied * 8.0 / 1_000_000.0) / elapsedSec
                        _transferProgress.value = TransferProgress(
                            fileName = fileName,
                            isUpload = false,
                            bytesTransferred = copied,
                            totalBytes = total,
                            speedMbps = mbps
                        )
                    }
                    output.flush()
                }
            }
            _transferProgress.value = null
            // Security invariant (Part 10): Downloaded files are strictly saved to SAF storage and NEVER auto-executed.
            notificationRepository.pushNotification(
                title = "File Downloaded",
                description = "$fileName (${FileUtils.formatBytes(copied)}) saved to device storage.",
                category = NotificationCategory.STORAGE
            )
            AppResult.Success(copied)
        } catch (e: Exception) {
            _transferProgress.value = null
            if (cancelFlag.get()) {
                AppResult.Failure(AppError.BadRequest("Download cancelled."))
            } else {
                NetworkResultMapper.mapThrowable(e, json, logStore, "Storage") { sessionManager.onUnauthorizedOrExpired() }
            }
        }
    }

    override suspend fun readTextPreview(relativePath: String): AppResult<TextFilePreview> =
        withContext(Dispatchers.IO) {
            val safePath = Validators.sanitizeRelativePath(relativePath)
            if (safePath.isNullOrEmpty()) {
                return@withContext AppResult.Failure(AppError.BadRequest("Invalid file path."))
            }
            val fileName = safePath.substringAfterLast('/')
            if (!FileUtils.isTextFile(fileName)) {
                return@withContext AppResult.Failure(
                    AppError.BadRequest("Text preview is only permitted for UTF-8 text files.")
                )
            }
            val svc = when (val r = apiClient.requireService()) {
                is AppResult.Failure -> return@withContext r
                is AppResult.Success -> r.data
            }

            try {
                // Request at most 200 KiB via HTTP Range so large logs never exhaust mobile RAM (Part 10)
                val maxCap = AppConfig.TEXT_PREVIEW_MAX_BYTES
                val resp = svc.downloadFile(
                    relativePath = safePath,
                    byteRange = "bytes=0-${maxCap - 1}"
                )
                if (!resp.isSuccessful || resp.body() == null) {
                    return@withContext NetworkResultMapper.mapResponse(resp, json) {
                        sessionManager.onUnauthorizedOrExpired()
                    }.let {
                        when (it) {
                            is AppResult.Failure -> it
                            is AppResult.Success -> AppResult.Failure(AppError.Unknown())
                        }
                    }
                }
                val bytes = resp.body()!!.byteStream().use { stream ->
                    stream.readNBytesCompat(maxCap.toInt())
                }
                if (bytes.any { it == 0.toByte() }) {
                    return@withContext AppResult.Failure(
                        AppError.BadRequest("Binary content detected; cannot preview as plain text.")
                    )
                }
                val contentRange = resp.headers()["Content-Range"].orEmpty()
                val totalSize = Regex("""/(\d+)$""").find(contentRange)?.groupValues?.get(1)?.toLongOrNull()
                    ?: bytes.size.toLong()

                AppResult.Success(
                    TextFilePreview(
                        relativePath = safePath,
                        fileName = fileName,
                        content = String(bytes, Charsets.UTF_8),
                        sizeBytes = totalSize,
                        truncated = totalSize > bytes.size
                    )
                )
            } catch (e: Exception) {
                NetworkResultMapper.mapThrowable(e, json, logStore, "Storage") { sessionManager.onUnauthorizedOrExpired() }
            }
        }

    override fun buildAuthenticatedMediaUrl(relativePath: String): String? {
        val profile = activeProfileProvider() ?: return null
        val safePath = Validators.sanitizeRelativePath(relativePath)?.takeIf { it.isNotEmpty() } ?: return null
        val encodedSegments = safePath.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        return "${profile.baseUrl()}uploads/$encodedSegments"
    }

    private fun java.io.InputStream.readNBytesCompat(maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var remaining = maxBytes
        while (remaining > 0) {
            val r = read(buf, 0, minOf(buf.size, remaining))
            if (r == -1) break
            out.write(buf, 0, r)
            remaining -= r
        }
        return out.toByteArray()
    }
}
