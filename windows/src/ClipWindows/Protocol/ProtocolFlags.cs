using System;

namespace ClipWindows.Protocol;

[Flags]
public enum ProtocolFlags : byte
{
    None = 0x00,
    LastChunk = 0x01,
    Compressed = 0x02
}
