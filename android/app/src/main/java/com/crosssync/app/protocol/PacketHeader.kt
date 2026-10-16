package com.crosssync.app.protocol

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Represents the 17-byte fixed binary header of a CrossSync protocol frame.
 *
 * Wire layout (matches Windows [PacketHeader.cs]):
 * ```
 * Offset  Size  Field
 * 0       4     Magic bytes (CRSY)
 * 4       1     Message type
 * 5       1     Flags
 * 6       8     Payload length (big-endian int64)
 * 14      3     Reserved (zeroed)
 * ```
 *
 * @param type     The [MessageType] of this frame.
 * @param flags    Bitwise combination of [ProtocolFlags] constants.
 * @param payloadLength  The length of the payload following this header.
 */
data class PacketHeader(
    val type: MessageType,
    val flags: Byte = ProtocolFlags.NONE,
    val payloadLength: Long = 0L,
) {

    /**
     * Serializes this header into a [ByteArray] of exactly [ProtocolConstants.HEADER_SIZE] bytes.
     */
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(ProtocolConstants.HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(ProtocolConstants.MAGIC_BYTES)
        buffer.put(type.code)
        buffer.put(flags)
        buffer.putLong(payloadLength)
        // Reserved 3 bytes
        buffer.put(0x00); buffer.put(0x00); buffer.put(0x00)
        return buffer.array()
    }

    companion object {

        /**
         * Reads and parses a [PacketHeader] from [stream].
         * Blocks until all [ProtocolConstants.HEADER_SIZE] bytes are available.
         *
         * @throws ProtocolException if magic bytes are wrong or stream ends prematurely.
         */
        fun readFrom(stream: InputStream): PacketHeader {
            val raw = ByteArray(ProtocolConstants.HEADER_SIZE)
            var totalRead = 0
            while (totalRead < ProtocolConstants.HEADER_SIZE) {
                val read = stream.read(raw, totalRead, ProtocolConstants.HEADER_SIZE - totalRead)
                if (read == -1) {
                    throw ProtocolException("Stream closed before full header was received (got $totalRead/${ProtocolConstants.HEADER_SIZE} bytes)")
                }
                totalRead += read
            }

            // Verify magic bytes
            val magic = raw.sliceArray(0..3)
            if (!magic.contentEquals(ProtocolConstants.MAGIC_BYTES)) {
                throw ProtocolException(
                    "Invalid magic bytes: expected CRSY, got ${magic.toHex()}"
                )
            }

            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            buffer.position(4) // skip magic

            val typeCode = buffer.get()
            val flags = buffer.get()
            val payloadLength = buffer.getLong()

            return PacketHeader(
                type = MessageType.fromCode(typeCode),
                flags = flags,
                payloadLength = payloadLength,
            )
        }

        /**
         * Writes a header to [stream] for the given [type] and optional [payloadLength].
         */
        fun writeTo(
            stream: OutputStream,
            type: MessageType,
            flags: Byte = ProtocolFlags.NONE,
            payloadLength: Long = 0L,
        ) {
            val header = PacketHeader(type, flags, payloadLength)
            stream.write(header.toBytes())
            stream.flush()
        }

        /**
         * Writes header + payload and flushes once. With a buffered TLS stream this sends the
         * frame as one record instead of separate header and payload records.
         */
        fun writeFrame(
            stream: OutputStream,
            type: MessageType,
            flags: Byte = ProtocolFlags.NONE,
            payload: ByteArray = ByteArray(0),
            offset: Int = 0,
            length: Int = payload.size,
        ) {
            stream.write(PacketHeader(type, flags, length.toLong()).toBytes())
            stream.write(payload, offset, length)
            stream.flush()
        }

        private fun ByteArray.toHex() = joinToString(" ") { "0x%02X".format(it) }
    }
}

/** Thrown when the protocol is violated (wrong magic, unexpected truncation, etc.). */
class ProtocolException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
