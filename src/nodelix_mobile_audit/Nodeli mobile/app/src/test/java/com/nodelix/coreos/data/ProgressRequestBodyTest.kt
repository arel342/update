package com.nodelix.coreos.data

import com.nodelix.coreos.data.remote.ProgressRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Storage transfer behaviour: uploads are streamed in bounded chunks (no full-file
 * buffering), progress is monotonic and bounded by the declared length, and a cancelled
 * upload aborts with an IOException instead of silently succeeding.
 */
class ProgressRequestBodyTest {

    private val payload = ByteArray(100_000) { (it % 251).toByte() }

    private fun body(
        cancelled: AtomicBoolean = AtomicBoolean(false),
        progress: MutableList<Pair<Long, Long>> = mutableListOf()
    ) = ProgressRequestBody(
        contentType = null,
        contentLengthBytes = payload.size.toLong(),
        cancelledFlag = cancelled,
        inputStreamProvider = { ByteArrayInputStream(payload) },
        onProgress = { written, total -> progress.add(written to total) }
    )

    @Test
    fun `streams the whole payload in bounded chunks and reports monotonic progress`() {
        val progress = mutableListOf<Pair<Long, Long>>()
        val buffer = Buffer()

        body(progress = progress).writeTo(buffer)

        assertEquals(payload.size.toLong(), buffer.size)
        assertTrue(buffer.readByteArray().contentEquals(payload))
        assertTrue(progress.isNotEmpty())
        // 100_000 bytes / 16 KiB chunks => several callbacks, never more than the total
        assertTrue(progress.all { (written, total) -> total == payload.size.toLong() && written <= total })
        assertEquals(payload.size.toLong(), progress.last().first)
        val writtenValues = progress.map { it.first }
        assertEquals(writtenValues.sorted(), writtenValues)
    }

    @Test
    fun `declared content length and media type are preserved for multipart framing`() {
        val instance = body()
        assertEquals(payload.size.toLong(), instance.contentLength())
        assertTrue(instance.isOneShot())

        val typed = ProgressRequestBody(
            contentType = "application/octet-stream".toMediaTypeOrNull(),
            contentLengthBytes = payload.size.toLong(),
            cancelledFlag = AtomicBoolean(false),
            inputStreamProvider = { ByteArrayInputStream(payload) },
            onProgress = { _, _ -> }
        )
        assertEquals("application/octet-stream", typed.contentType().toString())
        // Unknown size is reported as -1 so OkHttp switches to chunked transfer encoding.
        val unknownSize = ProgressRequestBody(
            contentType = null,
            contentLengthBytes = -1L,
            cancelledFlag = AtomicBoolean(false),
            inputStreamProvider = { ByteArrayInputStream(payload) },
            onProgress = { _, _ -> }
        )
        assertEquals(-1L, unknownSize.contentLength())
    }

    @Test(expected = IOException::class)
    fun `cancelled upload aborts with IOException instead of completing`() {
        val cancelled = AtomicBoolean(true)
        body(cancelled = cancelled).writeTo(Buffer())
    }
}
