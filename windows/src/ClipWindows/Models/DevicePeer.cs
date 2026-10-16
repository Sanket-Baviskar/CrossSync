using System;

namespace ClipWindows.Models;

public class DevicePeer
{
    /// <summary>Stable ID the phone sent at pairing; one live connection per device.</summary>
    public string DeviceId { get; set; } = string.Empty;
    public string DeviceName { get; set; } = "Android Device";
    public string IpAddress { get; set; } = string.Empty;
    public int Port { get; set; }
    public bool IsConnected { get; set; }
    public DateTime LastSeen { get; set; } = DateTime.Now;

    public string DisplayName => $"{DeviceName} ({IpAddress})";

    public override string ToString() => DisplayName;
}
