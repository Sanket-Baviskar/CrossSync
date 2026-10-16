package com.crosssync.app.protocol

import java.nio.charset.StandardCharsets

/**
 * CLIPBOARD_IMAGE payload: [mimeLen:1][mime: ASCII, e.g. "image/png"][image bytes].
 * Mirrors ClipboardImagePayload.cs on Windows.
 */
object ClipboardImagePayload {

    const val MAX_PAYLOAD_BYTES = 1 + 255 + ProtocolConstants.MAX_CLIPBOARD_IMAGE_BYTES

    fun build(mime: String, image: ByteArray): ByteArray {
        val mimeBytes = mime.toByteArray(StandardCharsets.US_ASCII)
        require(mimeBytes.size <= 255) { "MIME type too long" }
        val payload = ByteArray(1 + mimeBytes.size + image.size)
        payload[0] = mimeBytes.size.toByte()
        mimeBytes.copyInto(payload, 1)
        image.copyInto(payload, 1 + mimeBytes.size)
        return payload
    }

    /** Returns (mime, image bytes), or null if the payload is malformed or not an image. */
    fun parse(payload: ByteArray): Pair<String, ByteArray>? {
        if (payload.isEmpty()) return null
        val mimeLen = payload[0].toInt() and 0xFF
        if (payload.size < 1 + mimeLen + 1) return null
        val mime = String(payload, 1, mimeLen, StandardCharsets.US_ASCII)
        if (!mime.startsWith("image/")) return null
        return mime to payload.copyOfRange(1 + mimeLen, payload.size)
    }
}
