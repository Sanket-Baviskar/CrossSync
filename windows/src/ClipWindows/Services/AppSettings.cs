using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace ClipWindows.Services;

public class PairedDevice
{
    public string DeviceId { get; set; } = string.Empty;
    public string Name { get; set; } = string.Empty;
    public string TokenHash { get; set; } = string.Empty;
    public DateTime PairedAt { get; set; } = DateTime.Now;
}

/// <summary>
/// Persisted user settings and paired-device list, stored as JSON in %AppData%\CrossSync.
/// Thread-safe: the network thread validates tokens while the UI edits settings.
/// </summary>
public class AppSettings
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private readonly object _lock = new();

    public static string DataDirectory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "CrossSync");

    private static string FilePath => Path.Combine(DataDirectory, "settings.json");

    public static string DefaultDownloadDirectory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "CrossSync");

    public string DownloadDirectory { get; set; } = DefaultDownloadDirectory;
    public bool ClipboardSyncEnabled { get; set; } = true;
    public bool ExplorerMenuEnabled { get; set; } = true;
    public List<PairedDevice> PairedDevices { get; set; } = new();

    public event Action? PairedDevicesChanged;

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                var loaded = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath), JsonOptions);
                if (loaded != null)
                {
                    if (string.IsNullOrWhiteSpace(loaded.DownloadDirectory))
                        loaded.DownloadDirectory = DefaultDownloadDirectory;
                    loaded.PairedDevices ??= new();
                    return loaded;
                }
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Failed to load settings: {ex.Message}");
        }
        return new AppSettings();
    }

    public void Save()
    {
        lock (_lock)
        {
            try
            {
                Directory.CreateDirectory(DataDirectory);
                string tmp = FilePath + ".tmp";
                File.WriteAllText(tmp, JsonSerializer.Serialize(this, JsonOptions));
                File.Move(tmp, FilePath, overwrite: true);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to save settings: {ex.Message}");
            }
        }
    }

    public bool IsTokenValid(string deviceId, string token)
    {
        lock (_lock)
        {
            var device = PairedDevices.FirstOrDefault(d => d.DeviceId == deviceId);
            if (device == null) return false;
            byte[] expected = Convert.FromHexString(device.TokenHash);
            return CryptographicOperations.FixedTimeEquals(HashToken(token), expected);
        }
    }

    public void AddPairedDevice(string deviceId, string name, string token)
    {
        lock (_lock)
        {
            PairedDevices.RemoveAll(d => d.DeviceId == deviceId);
            PairedDevices.Add(new PairedDevice
            {
                DeviceId = deviceId,
                Name = name,
                TokenHash = Convert.ToHexString(HashToken(token)),
                PairedAt = DateTime.Now
            });
        }
        Save();
        PairedDevicesChanged?.Invoke();
    }

    /// <summary>Keeps the stored name in step with the phone (e.g. after the user renames it).</summary>
    public void UpdateDeviceName(string deviceId, string name)
    {
        bool changed = false;
        lock (_lock)
        {
            var device = PairedDevices.FirstOrDefault(d => d.DeviceId == deviceId);
            if (device != null && device.Name != name)
            {
                device.Name = name;
                changed = true;
            }
        }
        if (!changed) return;
        Save();
        PairedDevicesChanged?.Invoke();
    }

    public void RemovePairedDevice(string deviceId)
    {
        lock (_lock)
        {
            PairedDevices.RemoveAll(d => d.DeviceId == deviceId);
        }
        Save();
        PairedDevicesChanged?.Invoke();
    }

    public List<PairedDevice> GetPairedDevices()
    {
        lock (_lock)
        {
            return PairedDevices.ToList();
        }
    }

    // Only a hash of each token is stored, so the settings file alone can't be used to connect.
    private static byte[] HashToken(string token) => SHA256.HashData(Encoding.UTF8.GetBytes(token));
}
