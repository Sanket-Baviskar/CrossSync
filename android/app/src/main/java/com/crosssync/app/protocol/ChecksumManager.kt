package com.crosssync.app.protocol

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Computes and verifies SHA-256 checksums for file integrity validation.
 *
 * Mirrors Windows [ChecksumVerifier.cs] logic, ensuring that files received
 * over TCP match the SHA-256 hash advertised in the [FILE_META][MessageType.FILE_META] packet.
 */
object ChecksumManager {

    private const val ALGORITHM = "SHA-256"
    private const val BUFFER_SIZE = 65_536  // 64 KB — matches chunk size

    /**
     * Computes the SHA-256 digest of [file] and returns it as a lowercase hex string.
     *
     * @param file  The file to hash. Must exist and be readable.
     * @return 64-character hex string (e.g. "3a7bd3e2360a3d29...").
     * @throws java.io.IOException if the file cannot be read.
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().toHexString()
    }

    /**
     * Computes the SHA-256 digest of a [ByteArray] (for small clipboard payloads).
     */
    fun computeSha256(data: ByteArray): String {
        return MessageDigest.getInstance(ALGORITHM)
            .digest(data)
            .toHexString()
    }

    /**
     * Verifies [file]'s SHA-256 matches [expectedHex].
     *
     * @return `true` if the file passes verification; `false` on mismatch.
     * @throws java.io.IOException if the file cannot be read.
     */
    fun verify(file: File, expectedHex: String): Boolean {
        val actual = computeSha256(file)
        return actual.equals(expectedHex, ignoreCase = true)
    }

    /**
     * Verifies that a temporary `.tmp` file passes checksum before being renamed to its
     * final destination. Mirrors [TempFileHandler.cs] post-download verification.
     *
     * @param tmpFile        The partially-downloaded `.tmp` file.
     * @param finalFile      Target path after successful verification.
     * @param expectedSha256 The SHA-256 advertised by the sender.
     * @return `true` and renames the file if verification passes; `false` otherwise.
     */
    fun verifyAndFinalize(
        tmpFile: File,
        finalFile: File,
        expectedSha256: String,
    ): Boolean {
        if (!verify(tmpFile, expectedSha256)) return false
        // Rename atomically from .tmp to final path
        if (finalFile.exists()) finalFile.delete()
        return tmpFile.renameTo(finalFile)
    }

    // ---- Helpers ----

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
