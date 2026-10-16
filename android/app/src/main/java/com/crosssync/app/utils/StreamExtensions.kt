package com.crosssync.app.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Extension functions for buffered I/O stream operations.
 *
 * Mirrors Android-side analogues of [FileStreamExtensions.cs] used on Windows,
 * providing reliable chunk-based reading/writing over TCP sockets.
 */

/**
 * Reads exactly [length] bytes from this [InputStream] into a newly allocated [ByteArray].
 * Blocks until all bytes are available or the stream is closed.
 *
 * @throws java.io.EOFException if the stream closes before [length] bytes are received.
 */
suspend fun InputStream.readExactly(length: Int): ByteArray = withContext(Dispatchers.IO) {
    val buffer = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(buffer, offset, length - offset)
        if (read == -1) throw java.io.EOFException("Stream closed after $offset / $length bytes")
        offset += read
    }
    buffer
}

/**
 * Reads exactly [length] bytes from this [InputStream] synchronously (blocking).
 * Use [readExactly] (suspend) when called from a coroutine.
 */
fun InputStream.readNBytes(length: Int): ByteArray {
    val buffer = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(buffer, offset, length - offset)
        if (read == -1) return buffer.copyOf(offset)
        offset += read
    }
    return buffer
}

/**
 * Writes [data] to this [OutputStream] and flushes, running on [Dispatchers.IO].
 */
suspend fun OutputStream.writeAndFlush(data: ByteArray) = withContext(Dispatchers.IO) {
    write(data)
    flush()
}

/**
 * Copies [byteCount] bytes from [source] to this [OutputStream] in
 * [chunkSize]-byte increments, calling [onProgress] after each chunk.
 *
 * @param source     Input stream to read from.
 * @param byteCount  Total bytes to transfer.
 * @param chunkSize  Chunk size in bytes (default 64 KB).
 * @param onProgress Lambda receiving bytes transferred so far.
 */
suspend fun OutputStream.copyChunked(
    source: InputStream,
    byteCount: Long,
    chunkSize: Int = 65_536,
    onProgress: suspend (bytesTransferred: Long) -> Unit = {},
) = withContext(Dispatchers.IO) {
    val buffer = ByteArray(chunkSize)
    var remaining = byteCount
    var totalSent = 0L

    while (remaining > 0) {
        val toRead = minOf(chunkSize.toLong(), remaining).toInt()
        val read = source.read(buffer, 0, toRead)
        if (read == -1) break
        write(buffer, 0, read)
        flush()
        totalSent += read
        remaining -= read
        onProgress(totalSent)
    }
}

/** Returns a human-readable size string (e.g. "3.2 MB", "512 KB"). */
fun Long.toReadableSize(): String = when {
    this >= 1_073_741_824L -> "%.1f GB".format(this / 1_073_741_824.0)
    this >= 1_048_576L     -> "%.1f MB".format(this / 1_048_576.0)
    this >= 1_024L         -> "%.1f KB".format(this / 1_024.0)
    else                   -> "$this B"
}

/** Returns a human-readable speed string (e.g. "2.4 MB/s"). */
fun Long.toReadableSpeed(): String = toReadableSize() + "/s"
