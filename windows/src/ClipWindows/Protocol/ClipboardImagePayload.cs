using System;
using System.Text;

namespace ClipWindows.Protocol;

/// <summary>
/// CLIPBOARD_IMAGE payload: [mimeLen:1][mime: ASCII, e.g. "image/png"][image bytes].
/// Mirrors ClipboardImagePayload in the Android app.
/// </summary>
public static class ClipboardImagePayload
{
    public const int MaxPayloadBytes = 1 + 255 + ProtocolConstants.MaxClipboardImageBytes;

    public static byte[] Build(string mime, ReadOnlySpan<byte> image)
    {
        byte[] mimeBytes = Encoding.ASCII.GetBytes(mime);
        if (mimeBytes.Length > 255) throw new ArgumentException("MIME type too long", nameof(mime));

        byte[] payload = new byte[1 + mimeBytes.Length + image.Length];
        payload[0] = (byte)mimeBytes.Length;
        mimeBytes.CopyTo(payload, 1);
        image.CopyTo(payload.AsSpan(1 + mimeBytes.Length));
        return payload;
    }

    public static bool TryParse(byte[] payload, out string mime, out byte[] image)
    {
        mime = string.Empty;
        image = Array.Empty<byte>();
        if (payload.Length < 1) return false;

        int mimeLen = payload[0];
        if (payload.Length < 1 + mimeLen + 1) return false;

        mime = Encoding.ASCII.GetString(payload, 1, mimeLen);
        image = payload.AsSpan(1 + mimeLen).ToArray();
        return mime.StartsWith("image/", StringComparison.OrdinalIgnoreCase);
    }
}
