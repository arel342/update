package com.nodelix.coreos.data.remote

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams file uploads directly from an [InputStream] (such as Android SAF ContentResolver)
 * in 16 KiB chunks without loading large files into heap memory.
 */
class ProgressRequestBody(
    private val contentType: MediaType?,
    private val contentLengthBytes: Long,
    private val cancelledFlag: AtomicBoolean,
    private val inputStreamProvider: () -> InputStream?,
    private val onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = if (contentLengthBytes >= 0L) contentLengthBytes else -1L

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        val stream = inputStreamProvider()
            ?: throw IOException("Unable to open local file stream for upload.")

        stream.use { input ->
            val buffer = ByteArray(DEFAULT_CHUNK_BYTES)
            var uploaded = 0L
            while (true) {
                if (cancelledFlag.get()) {
                    throw IOException("File upload cancelled by user.")
                }
                val read = input.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                uploaded += read
                onProgress(uploaded, contentLengthBytes)
            }
            sink.flush()
        }
    }

    companion object {
        private const val DEFAULT_CHUNK_BYTES = 16 * 1024
    }
}
