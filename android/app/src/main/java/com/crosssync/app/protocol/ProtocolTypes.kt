package com.crosssync.app.protocol

/**
 * Protocol constants mirroring the Windows [ProtocolConstants.cs].
 *
 * Wire format — 17-byte fixed binary header:
 * [0..3]   Magic bytes: 0x43 0x52 0x53 0x59  ("CRSY")
 * [4]      Message type  (1 byte)
 * [5]      Flags         (1 byte)
 * [6..13]  Payload length (8 bytes, big-endian long)
 * [14..16] Reserved / future use
 */
object ProtocolConstants {

    /** Magic bytes identifying a CrossSync frame: "CRSY" */
    val MAGIC_BYTES = byteArrayOf(0x43, 0x52, 0x53, 0x59)

    /** Total size of the fixed binary header in bytes. */
    const val HEADER_SIZE = 17

    /** Default TCP port the Windows server listens on. */
    const val DEFAULT_PORT = 45678

    /** UDP port used for broadcast discovery (Windows listens here). */
    const val DISCOVERY_PORT = 45679

    /**
     * Magic string sent in UDP broadcast discovery probes.
     * Must match [ProtocolConstants.DiscoveryMagic] on Windows.
     */
    const val DISCOVERY_MAGIC = "CROSSSYNC_DISCOVER"

    /** mDNS service type for auto-discovery. */
    const val MDNS_SERVICE_TYPE = "_crosssync._tcp."

    /** Max chunk size for streaming file data (64 KB). */
    const val CHUNK_SIZE = 65_536

    /** Timeout for socket connect in milliseconds. */
    const val CONNECT_TIMEOUT_MS = 5_000

    /** Reconnect delay in milliseconds. */
    const val RECONNECT_DELAY_MS = 3_000L

    /** Max reconnect attempts before giving up. */
    const val MAX_RECONNECT_ATTEMPTS = 10

    /** Largest clipboard text payload accepted (16 MB). */
    const val MAX_CLIPBOARD_TEXT_BYTES = 16 * 1024 * 1024

    /** Largest clipboard image accepted (20 MB of image data). */
    const val MAX_CLIPBOARD_IMAGE_BYTES = 20 * 1024 * 1024

    /** Largest handshake / file-meta payload accepted (64 KB). */
    const val MAX_CONTROL_PAYLOAD_BYTES = 64 * 1024

    /** TRANSFER_CANCEL payload: [kind:1][sha256 hex:64]. */
    const val CANCEL_PAYLOAD_BYTES = 1 + 64

    /** TRANSFER_CANCEL kind: the sender stopped sending; the receiver should discard the file. */
    const val CANCEL_BY_SENDER: Byte = 0

    /** TRANSFER_CANCEL kind: the receiver asks the sender to stop. */
    const val CANCEL_BY_RECEIVER: Byte = 1

    /** Version 2: TLS transport, JSON handshake with pairing, TRANSFER_CANCEL. Must match Windows. */
    const val PROTOCOL_VERSION = 2

    /** The PC pings every 20s; this much silence means the connection is dead. */
    const val READ_TIMEOUT_MS = 60_000

    /**
     * Largest payload accepted for [type]. Payloads are read into a single buffer, so the
     * length a peer claims must be bounded before allocating. Must match
     * [ProtocolConstants.MaxPayloadLength] on Windows.
     */
    fun maxPayloadLength(type: MessageType): Long = when (type) {
        MessageType.CLIPBOARD_TEXT -> MAX_CLIPBOARD_TEXT_BYTES.toLong()
        MessageType.CLIPBOARD_IMAGE -> ClipboardImagePayload.MAX_PAYLOAD_BYTES.toLong()
        MessageType.FILE_CHUNK -> CHUNK_SIZE.toLong()
        MessageType.HANDSHAKE, MessageType.FILE_META -> MAX_CONTROL_PAYLOAD_BYTES.toLong()
        MessageType.TRANSFER_CANCEL -> CANCEL_PAYLOAD_BYTES.toLong()
        MessageType.DEVICE_LIST, MessageType.FILE_ROUTE -> MAX_CONTROL_PAYLOAD_BYTES.toLong()
        MessageType.PING, MessageType.PONG, MessageType.DISCONNECT -> 0L
        else -> Long.MAX_VALUE // skipped in a streaming loop, never buffered
    }
}

/**
 * Message type byte values — must match [ProtocolConstants.cs] on Windows.
 */
enum class MessageType(val code: Byte) {
    /** Handshake / hello packet */
    HANDSHAKE(0x01),

    /** Clipboard text payload */
    CLIPBOARD_TEXT(0x02),

    /** Clipboard image payload */
    CLIPBOARD_IMAGE(0x03),

    /** File transfer metadata (name, size, checksum) */
    FILE_META(0x04),

    /** File data chunk */
    FILE_CHUNK(0x05),

    /** Acknowledgement / ACK */
    ACK(0x06),

    /** Negative ACK / NACK (request retransmit) */
    NACK(0x07),

    /** Transfer pause request */
    TRANSFER_PAUSE(0x08),

    /** Transfer resume request */
    TRANSFER_RESUME(0x09),

    /** Transfer cancel */
    TRANSFER_CANCEL(0x0A),

    /** Ping / keep-alive */
    PING(0x0B),

    /** Pong / keep-alive reply */
    PONG(0x0C),

    /** Graceful disconnect */
    DISCONNECT(0x0D),

    /** PC → phone: JSON array of the other connected phones [{"id","name"}]. */
    DEVICE_LIST(0x0E),

    /** Phone → PC, just before FILE_META: JSON {"pc": bool, "to": [deviceId]} — where the PC delivers the file. */
    FILE_ROUTE(0x0F),

    /** Any code this version doesn't know (from a newer peer). Never sent; its payload is skipped. */
    UNKNOWN(0x00);

    companion object {
        fun fromCode(code: Byte): MessageType =
            entries.firstOrNull { it.code == code && it != UNKNOWN } ?: UNKNOWN
    }
}

/**
 * Protocol flag bit masks — combinable via bitwise OR.
 */
object ProtocolFlags {
    /** No flags. */
    const val NONE: Byte = 0x00

    /** This is the last chunk in a multi-chunk stream. */
    const val LAST_CHUNK: Byte = 0x01

    /** Payload is compressed (reserved for future use). */
    const val COMPRESSED: Byte = 0x02

    /** Payload is encrypted (reserved for future use). */
    const val ENCRYPTED: Byte = 0x04

    /** Resume transfer from a given offset. */
    const val RESUME: Byte = 0x08
}
