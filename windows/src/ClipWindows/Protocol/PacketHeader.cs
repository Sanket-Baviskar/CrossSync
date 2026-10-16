using System;
using System.Buffers.Binary;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace ClipWindows.Protocol;

/// <summary>
/// 17-byte fixed binary header for CrossSync wire frames.
/// Mirrors PacketHeader.kt on Android.
/// </summary>
public record PacketHeader(MessageType Type, ProtocolFlags Flags = ProtocolFlags.None, long PayloadLength = 0L)
{
    public byte[] ToBytes()
    {
        byte[] buffer = new byte[ProtocolConstants.HeaderSize];
        Array.Copy(ProtocolConstants.MagicBytes, 0, buffer, 0, 4);
        buffer[4] = (byte)Type;
        buffer[5] = (byte)Flags;
        BinaryPrimitives.WriteInt64BigEndian(buffer.AsSpan(6, 8), PayloadLength);
        buffer[14] = 0;
        buffer[15] = 0;
        buffer[16] = 0;
        return buffer;
    }

    public static async Task<PacketHeader> ReadFromAsync(Stream stream, CancellationToken ct = default)
    {
        byte[] buffer = new byte[ProtocolConstants.HeaderSize];
        int bytesRead = 0;
        while (bytesRead < ProtocolConstants.HeaderSize)
        {
            int read = await stream.ReadAsync(buffer.AsMemory(bytesRead, ProtocolConstants.HeaderSize - bytesRead), ct);
            if (read == 0)
                throw new EndOfStreamException("Connection closed before complete header received.");
            bytesRead += read;
        }

        // Validate magic bytes
        if (buffer[0] != ProtocolConstants.MagicBytes[0] ||
            buffer[1] != ProtocolConstants.MagicBytes[1] ||
            buffer[2] != ProtocolConstants.MagicBytes[2] ||
            buffer[3] != ProtocolConstants.MagicBytes[3])
        {
            throw new InvalidDataException($"Invalid magic bytes: 0x{buffer[0]:X2} 0x{buffer[1]:X2} 0x{buffer[2]:X2} 0x{buffer[3]:X2}");
        }

        MessageType type = (MessageType)buffer[4];
        ProtocolFlags flags = (ProtocolFlags)buffer[5];
        long payloadLength = BinaryPrimitives.ReadInt64BigEndian(buffer.AsSpan(6, 8));

        return new PacketHeader(type, flags, payloadLength);
    }

    /// <summary>
    /// Builds header + payload as one buffer so it goes out in a single write (one TLS record).
    /// </summary>
    public static byte[] BuildFrame(MessageType type, ProtocolFlags flags, ReadOnlySpan<byte> payload)
    {
        byte[] frame = new byte[ProtocolConstants.HeaderSize + payload.Length];
        new PacketHeader(type, flags, payload.Length).ToBytes().CopyTo(frame, 0);
        payload.CopyTo(frame.AsSpan(ProtocolConstants.HeaderSize));
        return frame;
    }

    public static async Task WriteToAsync(Stream stream, MessageType type, ProtocolFlags flags = ProtocolFlags.None, long payloadLength = 0L, CancellationToken ct = default)
    {
        var header = new PacketHeader(type, flags, payloadLength);
        byte[] bytes = header.ToBytes();
        await stream.WriteAsync(bytes, ct);
        await stream.FlushAsync(ct);
    }
}
