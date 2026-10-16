using System;

namespace ClipWindows.Protocol;

/// <summary>
/// Message type byte values — matching MessageType in ProtocolTypes.kt.
/// </summary>
public enum MessageType : byte
{
    Handshake = 0x01,
    ClipboardText = 0x02,
    ClipboardImage = 0x03,
    FileMeta = 0x04,
    FileChunk = 0x05,
    Ack = 0x06,
    Nack = 0x07,
    TransferPause = 0x08,
    TransferResume = 0x09,
    TransferCancel = 0x0A,
    Ping = 0x0B,
    Pong = 0x0C,
    Disconnect = 0x0D,

    /// <summary>PC → phone: JSON list of the other connected phones (for "send to phone" pickers).</summary>
    DeviceList = 0x0E,

    /// <summary>Phone → PC, just before FILE_META: JSON {"pc": bool, "to": [deviceId]} — where to deliver the next file.</summary>
    FileRoute = 0x0F
}
