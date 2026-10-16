using System;

namespace ClipWindows.Protocol;

/// <summary>
/// Protocol constants mirroring the Android CrossSync ProtocolConstants.kt.
/// 
/// Wire format — 17-byte fixed binary header:
/// [0..3]   Magic bytes: 0x43 0x52 0x53 0x59 ("CRSY")
/// [4]      Message type  (1 byte)
/// [5]      Flags         (1 byte)
/// [6..13]  Payload length (8 bytes, big-endian long)
/// [14..16] Reserved / future use (3 bytes zeroed)
/// </summary>
public static class ProtocolConstants
{
    public static readonly byte[] MagicBytes = new byte[] { 0x43, 0x52, 0x53, 0x59 }; // "CRSY"

    public const int HeaderSize = 17;

    public const int DefaultPort = 45678;

    public const int DiscoveryPort = 45679;

    public const string DiscoveryMagic = "CROSSSYNC_DISCOVER";

    public const string MdnsServiceType = "_crosssync._tcp.";

    public const int ChunkSize = 65536; // 64 KB

    public const int ConnectTimeoutMs = 5000;

    public const int ReconnectDelayMs = 3000;

    public const int MaxReconnectAttempts = 10;

    public const int MaxClipboardTextBytes = 16 * 1024 * 1024; // 16 MB

    public const int MaxControlPayloadBytes = 64 * 1024; // handshake, file meta

    public const int MaxClipboardImageBytes = 20 * 1024 * 1024; // 20 MB of image data

    /// <summary>TRANSFER_CANCEL payload: [kind:1][sha256 hex:64].</summary>
    public const int CancelPayloadBytes = 1 + 64;

    /// <summary>TRANSFER_CANCEL kind: the sender stopped sending; the receiver should discard the file.</summary>
    public const byte CancelBySender = 0;

    /// <summary>TRANSFER_CANCEL kind: the receiver asks the sender to stop.</summary>
    public const byte CancelByReceiver = 1;

    /// <summary>
    /// Version 2: TLS transport, JSON handshake with pairing, TRANSFER_CANCEL.
    /// </summary>
    public const int ProtocolVersion = 2;

    /// <summary>
    /// Largest payload accepted for a message type. Payloads are read into a single buffer,
    /// so the length a peer claims must be bounded before allocating. Unknown types are
    /// skipped in a streaming loop and need no cap.
    /// </summary>
    public static long MaxPayloadLength(MessageType type) => type switch
    {
        MessageType.ClipboardText => MaxClipboardTextBytes,
        MessageType.ClipboardImage => ClipboardImagePayload.MaxPayloadBytes,
        MessageType.FileChunk => ChunkSize,
        MessageType.Handshake or MessageType.FileMeta => MaxControlPayloadBytes,
        MessageType.TransferCancel => CancelPayloadBytes,
        MessageType.DeviceList or MessageType.FileRoute => MaxControlPayloadBytes,
        MessageType.Ping or MessageType.Pong or MessageType.Disconnect => 0,
        _ => long.MaxValue
    };
}
