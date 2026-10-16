using System;
using System.IO;
using System.Windows.Media.Imaging;

namespace ClipWindows.Models;

public enum ClipboardSource
{
    LocalWindows,
    RemoteAndroid
}

public class ClipboardPayload
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string Text { get; set; } = string.Empty;
    public ClipboardSource Source { get; set; } = ClipboardSource.LocalWindows;

    /// <summary>Name of the phone the clip came from (for RemoteAndroid).</summary>
    public string? DeviceName { get; set; }

    public DateTime Timestamp { get; set; } = DateTime.Now;

    /// <summary>Small preview for image clips; null for text.</summary>
    public BitmapImage? Thumbnail { get; private set; }

    public bool IsImage => Thumbnail != null;

    public string SourceLabel => Source == ClipboardSource.LocalWindows
        ? "This PC"
        : $"From {DeviceName ?? "phone"}";

    /// <summary>Creates an image history entry with a downscaled preview (the full image isn't kept).</summary>
    public static ClipboardPayload ForImage(byte[] imageBytes, ClipboardSource source, string? deviceName)
    {
        var thumbnail = new BitmapImage();
        thumbnail.BeginInit();
        thumbnail.CacheOption = BitmapCacheOption.OnLoad;
        thumbnail.DecodePixelHeight = 120;
        thumbnail.StreamSource = new MemoryStream(imageBytes);
        thumbnail.EndInit();
        thumbnail.Freeze();

        return new ClipboardPayload
        {
            Text = $"Image · {FormatSize(imageBytes.Length)}",
            Source = source,
            DeviceName = deviceName,
            Thumbnail = thumbnail
        };
    }

    private static string FormatSize(long bytes) => bytes switch
    {
        >= 1_048_576 => $"{bytes / 1_048_576.0:F1} MB",
        >= 1_024 => $"{bytes / 1_024.0:F0} KB",
        _ => $"{bytes} B"
    };
}
