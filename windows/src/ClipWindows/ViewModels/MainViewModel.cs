using System;
using Microsoft.Win32;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Threading.Tasks;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using ClipWindows.Models;
using ClipWindows.Network;
using ClipWindows.Protocol;
using ClipWindows.Services;
using ClipWindows.Views;

namespace ClipWindows.ViewModels;

public partial class MainViewModel : ObservableObject
{
    private const int MaxHistoryItems = 200;
    private const string ListeningStatus = "Listening for Android devices on port 45678...";
    private const string WaitingText = "Waiting for phones...";

    private readonly AppSettings _settings;
    private readonly NetworkServerService _networkServer;
    private readonly DeviceDiscoveryService _discoveryService;
    private readonly ClipboardMonitorService _clipboardMonitor;

    [ObservableProperty]
    private string _statusText = ListeningStatus;

    [ObservableProperty]
    private bool _isConnected;

    /// <summary>Header summary: the connected phone's name, a count, or the waiting text.</summary>
    [ObservableProperty]
    private string _connectedDeviceName = WaitingText;

    [ObservableProperty]
    private string _localIp = "127.0.0.1";

    [ObservableProperty]
    private bool _isClipboardSyncEnabled;

    [ObservableProperty]
    private string _manualSendText = string.Empty;

    [ObservableProperty]
    private string _downloadDirectory = string.Empty;

    [ObservableProperty]
    private bool _runOnStartup;

    [ObservableProperty]
    private string _pairingCode = string.Empty;

    [ObservableProperty]
    private bool _isExplorerMenuEnabled;

    /// <summary>Raised for tray notifications: (title, message, isError).</summary>
    public event Action<string, string, bool>? UserNotification;

    public string CertificateFingerprint => _networkServer.CertificateFingerprint;

    public ObservableCollection<ClipboardPayload> ClipboardHistory { get; } = new();
    public ObservableCollection<TransferItem> Transfers { get; } = new();
    public ObservableCollection<PairedDevice> PairedDevices { get; } = new();
    public ObservableCollection<DevicePeer> ConnectedDevices { get; } = new();

    public MainViewModel()
    {
        _settings = AppSettings.Load();
        _networkServer = new NetworkServerService(_settings);
        _discoveryService = new DeviceDiscoveryService();
        _clipboardMonitor = new ClipboardMonitorService();

        _networkServer.ClientConnected += OnClientConnected;
        _networkServer.ClientDisconnected += OnClientDisconnected;
        _networkServer.ClipboardTextReceived += OnRemoteClipboardReceived;
        _networkServer.ClipboardImageReceived += OnRemoteClipboardImageReceived;
        _networkServer.FileTransferStarted += OnFileTransferStarted;
        _networkServer.PairingCodeChanged += code => Application.Current?.Dispatcher.BeginInvoke(() => PairingCode = code);
        _networkServer.ServerError += message => Application.Current?.Dispatcher.BeginInvoke(() =>
        {
            StatusText = message;
            MessageBox.Show(message, "CrossSync", MessageBoxButton.OK, MessageBoxImage.Error);
        });
        _settings.PairedDevicesChanged += () => Application.Current?.Dispatcher.BeginInvoke(RefreshPairedDevices);

        _clipboardMonitor.ClipboardTextCopied += OnLocalClipboardCopied;
        _clipboardMonitor.ClipboardImageCopied += OnLocalClipboardImageCopied;

        _isClipboardSyncEnabled = _settings.ClipboardSyncEnabled;
        _isExplorerMenuEnabled = _settings.ExplorerMenuEnabled;
        _downloadDirectory = _settings.DownloadDirectory;
        _pairingCode = _networkServer.PairingCode;

        _ = InitStartupAsync();

        RefreshPairedDevices();
        ResolveLocalIp();
        NetworkChange.NetworkAddressChanged += (_, _) => Application.Current?.Dispatcher.BeginInvoke(ResolveLocalIp);
    }

    // ── Startup ──────────────────────────────────────────────────────────────
    /// <summary>
    /// Reads the startup setting and, if it's on, re-registers it with this exe's current path,
    /// which also moves older Run-entry installs to the faster logon task. Runs schtasks.exe, so
    /// it's kept off the UI thread.
    /// </summary>
    private async Task InitStartupAsync()
    {
        bool enabled = await Task.Run(StartupRegistration.IsEnabled);
        if (enabled) await Task.Run(() => StartupRegistration.Set(true));
        _syncingStartup = true;
        RunOnStartup = enabled;
        _syncingStartup = false;
    }

    private bool _syncingStartup;

    partial void OnRunOnStartupChanged(bool value)
    {
        if (_syncingStartup) return;
        _ = Task.Run(() => StartupRegistration.Set(value));
    }

    // ── Settings ─────────────────────────────────────────────────────────────
    partial void OnIsClipboardSyncEnabledChanged(bool value)
    {
        _settings.ClipboardSyncEnabled = value;
        _settings.Save();
    }

    partial void OnDownloadDirectoryChanged(string value)
    {
        if (string.IsNullOrWhiteSpace(value) || !Path.IsPathFullyQualified(value)) return;

        try
        {
            Directory.CreateDirectory(value);
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Can't use this folder:\n{ex.Message}", "Download Directory", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        _networkServer.DownloadDirectory = value;
        _settings.DownloadDirectory = value;
        _settings.Save();
    }

    [RelayCommand]
    private void BrowseDownloadDirectory()
    {
        var dialog = new System.Windows.Forms.FolderBrowserDialog
        {
            Description = "Select download folder for files received from phone",
            SelectedPath = DownloadDirectory,
            UseDescriptionForTitle = true
        };
        if (dialog.ShowDialog() == System.Windows.Forms.DialogResult.OK)
        {
            DownloadDirectory = dialog.SelectedPath;
        }
    }

    // ── Pairing ──────────────────────────────────────────────────────────────
    [RelayCommand]
    private void RegeneratePairingCode() => _networkServer.RegeneratePairingCode();

    [RelayCommand]
    private void RemovePairedDevice(PairedDevice? device)
    {
        if (device == null) return;
        var answer = MessageBox.Show(
            $"Remove \"{device.Name}\"? It will need the pairing code to connect again.",
            "Remove Device", MessageBoxButton.YesNo, MessageBoxImage.Question);
        if (answer == MessageBoxResult.Yes)
        {
            _settings.RemovePairedDevice(device.DeviceId);
            _networkServer.DisconnectDevice(device.DeviceId);
        }
    }

    private void RefreshPairedDevices()
    {
        var devices = _settings.GetPairedDevices();
        PairedDevices.Clear();
        foreach (var device in devices.OrderByDescending(d => d.PairedAt))
        {
            PairedDevices.Add(device);
        }
        // Also rewrites the menu's exe path, in case the app was moved
        ExplorerContextMenu.Update(devices, IsExplorerMenuEnabled);
    }

    partial void OnIsExplorerMenuEnabledChanged(bool value)
    {
        _settings.ExplorerMenuEnabled = value;
        _settings.Save();
        ExplorerContextMenu.Update(_settings.GetPairedDevices(), value);
    }

    // ── Network ──────────────────────────────────────────────────────────────
    /// <summary>
    /// Picks the address the phone should use: an active physical adapter with a default
    /// gateway, skipping virtual adapters (Hyper-V, WSL, VMware, VirtualBox, Docker).
    /// </summary>
    private void ResolveLocalIp()
    {
        try
        {
            static bool IsVirtual(NetworkInterface n)
            {
                string text = (n.Name + " " + n.Description).ToLowerInvariant();
                return new[] { "virtual", "hyper-v", "vethernet", "vmware", "virtualbox", "wsl", "docker", "loopback" }
                    .Any(text.Contains);
            }

            var address = NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up &&
                            n.NetworkInterfaceType != NetworkInterfaceType.Loopback &&
                            n.NetworkInterfaceType != NetworkInterfaceType.Tunnel &&
                            !IsVirtual(n))
                .Select(n => n.GetIPProperties())
                .OrderByDescending(p => p.GatewayAddresses.Any(g =>
                    g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any)))
                .SelectMany(p => p.UnicastAddresses)
                .Select(a => a.Address)
                .FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a));

            LocalIp = address?.ToString() ?? "127.0.0.1";
        }
        catch
        {
            LocalIp = "127.0.0.1";
        }
    }

    public void Initialize(Window window)
    {
        _clipboardMonitor.Start(window);
        _discoveryService.Start();
        _networkServer.Start();
    }

    public void Shutdown()
    {
        _clipboardMonitor.Dispose();
        _discoveryService.Dispose();
        _networkServer.Dispose();
    }

    private void OnClientConnected(DevicePeer peer)
    {
        Application.Current?.Dispatcher.Invoke(() =>
        {
            // A reconnecting phone replaces its own old entry
            RemoveConnected(peer.DeviceId);
            ConnectedDevices.Add(peer);
            RefreshConnectionSummary();
        });
    }

    private void OnClientDisconnected(DevicePeer peer)
    {
        Application.Current?.Dispatcher.Invoke(() =>
        {
            RemoveConnected(peer.DeviceId);
            RefreshConnectionSummary();
        });
    }

    private void RemoveConnected(string deviceId)
    {
        foreach (var existing in ConnectedDevices.Where(d => d.DeviceId == deviceId).ToList())
        {
            ConnectedDevices.Remove(existing);
        }
    }

    private void RefreshConnectionSummary()
    {
        IsConnected = ConnectedDevices.Count > 0;
        ConnectedDeviceName = ConnectedDevices.Count switch
        {
            0 => WaitingText,
            1 => ConnectedDevices[0].DisplayName,
            _ => $"{ConnectedDevices.Count} phones"
        };
        StatusText = ConnectedDevices.Count switch
        {
            0 => ListeningStatus,
            1 => $"Connected to {ConnectedDevices[0].DisplayName} — encrypted",
            _ => $"Connected to {string.Join(", ", ConnectedDevices.Select(d => d.DeviceName))} — encrypted"
        };
    }

    // ── Clipboard ────────────────────────────────────────────────────────────
    private void AddToHistory(string text, ClipboardSource source, string? deviceName = null) =>
        AddToHistory(new ClipboardPayload
        {
            Text = text,
            Source = source,
            DeviceName = deviceName,
            Timestamp = DateTime.Now
        });

    private void AddToHistory(ClipboardPayload item)
    {
        ClipboardHistory.Insert(0, item);
        while (ClipboardHistory.Count > MaxHistoryItems)
        {
            ClipboardHistory.RemoveAt(ClipboardHistory.Count - 1);
        }
    }

    /// <summary>
    /// A phone copied something. The server has already relayed it to the other phones;
    /// here it goes onto this PC's clipboard.
    /// </summary>
    private void OnRemoteClipboardReceived(string text, DevicePeer from)
    {
        Application.Current?.Dispatcher.Invoke(() =>
        {
            // With sync off, keep the item in history but leave the PC clipboard alone
            if (IsClipboardSyncEnabled)
            {
                _clipboardMonitor.SetRemoteClipboardText(text);
            }
            AddToHistory(text, ClipboardSource.RemoteAndroid, from.DeviceName);
        });
    }

    private void OnLocalClipboardCopied(string text)
    {
        if (!IsClipboardSyncEnabled) return;

        Application.Current?.Dispatcher.Invoke(() => AddToHistory(text, ClipboardSource.LocalWindows));

        if (IsConnected)
        {
            _ = _networkServer.SendClipboardTextAsync(text);
        }
    }

    /// <summary>A phone copied an image: put it on this PC's clipboard (the server already relayed it).</summary>
    private void OnRemoteClipboardImageReceived(byte[] payload, DevicePeer from)
    {
        if (!ClipboardImagePayload.TryParse(payload, out _, out byte[] image)) return;

        Application.Current?.Dispatcher.Invoke(() =>
        {
            if (IsClipboardSyncEnabled)
            {
                _clipboardMonitor.SetRemoteClipboardImage(image);
            }
            try
            {
                AddToHistory(ClipboardPayload.ForImage(image, ClipboardSource.RemoteAndroid, from.DeviceName));
            }
            catch (Exception ex)
            {
                // A format WPF can't decode (e.g. no WebP codec) still syncs; it just has no preview
                System.Diagnostics.Debug.WriteLine($"No preview for clipboard image: {ex.Message}");
                AddToHistory("Image (no preview)", ClipboardSource.RemoteAndroid, from.DeviceName);
            }
        });
    }

    private void OnLocalClipboardImageCopied(byte[] png)
    {
        if (!IsClipboardSyncEnabled) return;

        Application.Current?.Dispatcher.Invoke(() =>
            AddToHistory(ClipboardPayload.ForImage(png, ClipboardSource.LocalWindows, null)));

        if (IsConnected)
        {
            _ = _networkServer.SendClipboardImageAsync("image/png", png);
        }
    }

    [RelayCommand]
    private async Task SendManualText()
    {
        if (string.IsNullOrWhiteSpace(ManualSendText)) return;

        string text = ManualSendText;
        ManualSendText = string.Empty;
        AddToHistory(text, ClipboardSource.LocalWindows);

        if (IsConnected)
        {
            await _networkServer.SendClipboardTextAsync(text);
        }
    }

    [RelayCommand]
    private void ClearHistory()
    {
        ClipboardHistory.Clear();
    }

    // ── Transfers ────────────────────────────────────────────────────────────
    private void OnFileTransferStarted(TransferItem item)
    {
        // TransferItem raises its own change notifications, so progress/completion need no handling here
        Application.Current?.Dispatcher.Invoke(() => Transfers.Insert(0, item));
    }

    [RelayCommand]
    public async Task SendFile()
    {
        if (!IsConnected)
        {
            MessageBox.Show("Please wait for a phone to connect first.", "Not Connected", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        var dialog = new Microsoft.Win32.OpenFileDialog
        {
            Title = "Select Files to Send to Phone",
            Multiselect = true
        };
        if (dialog.ShowDialog() != true) return;

        // With several phones connected, ask which one(s)
        IReadOnlyList<DevicePeer>? targets = ConnectedDevices.ToList();
        if (targets.Count > 1)
        {
            targets = Views.DevicePickerDialog.Pick(Application.Current?.MainWindow, targets, dialog.FileNames.Length);
            if (targets == null) return;
        }

        // Phones receive in parallel; each phone gets its files one after another
        await Task.WhenAll(targets.Select(async target =>
        {
            foreach (var file in dialog.FileNames)
            {
                try
                {
                    await _networkServer.SendFileAsync(file, target);
                }
                catch (Exception ex)
                {
                    MessageBox.Show($"Could not send {Path.GetFileName(file)}:\n{ex.Message}", "Send Failed", MessageBoxButton.OK, MessageBoxImage.Warning);
                }
            }
        }));
    }

    /// <summary>
    /// Handles Explorer's "CrossSync ▸ phone" menu. If the phone isn't connected yet (e.g. this
    /// launch just started CrossSync), waits briefly for it to connect.
    /// </summary>
    public async Task SendFilesFromShellAsync(string target, IReadOnlyList<string> paths)
    {
        var files = paths.Where(File.Exists).ToList();
        if (files.Count == 0)
        {
            bool folders = paths.Any(Directory.Exists);
            UserNotification?.Invoke("Nothing sent",
                folders ? "Folders can't be sent yet; select the files inside." : "The file no longer exists.", true);
            return;
        }

        bool sendToAll = target == ExplorerContextMenu.AllTarget;
        string targetName = sendToAll
            ? "your phones"
            : _settings.GetPairedDevices().FirstOrDefault(d => d.DeviceId == target)?.Name ?? "the phone";

        List<DevicePeer> Resolve() => sendToAll
            ? ConnectedDevices.ToList()
            : ConnectedDevices.Where(d => d.DeviceId == target).ToList();

        var targets = Resolve();
        for (int waited = 0; targets.Count == 0 && waited < ShellSendWaitMs; waited += 500)
        {
            await Task.Delay(500);
            targets = Resolve();
        }
        if (targets.Count == 0)
        {
            UserNotification?.Invoke("Not sent",
                $"{targetName} isn't connected. Open CrossSync on the phone and try again.", true);
            return;
        }

        string what = files.Count == 1 ? Path.GetFileName(files[0]) : $"{files.Count} files";
        string to = string.Join(", ", targets.Select(t => t.DeviceName));
        UserNotification?.Invoke("Sending", $"{what} → {to}", false);

        await Task.WhenAll(targets.Select(async device =>
        {
            foreach (var file in files)
            {
                try
                {
                    await _networkServer.SendFileAsync(file, device);
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"Shell send failed: {ex.Message}");
                }
            }
        }));

        var mine = Transfers.Where(t => t.Direction == TransferDirection.Sending &&
                                        files.Contains(t.LocalPath) &&
                                        targets.Any(d => d.DeviceId == t.DeviceId)).ToList();
        int failed = mine.Count(t => t.Status != TransferStatus.Completed);
        if (failed == 0)
        {
            UserNotification?.Invoke("Sent", $"{what} → {to}", false);
        }
        else
        {
            UserNotification?.Invoke("Some files weren't sent", $"{failed} of {mine.Count} failed. See Transfers in CrossSync.", true);
        }
    }

    private const int ShellSendWaitMs = 20_000;
    private const int ShellBatchWindowMs = 800;

    // Explorer runs one process per selected file; requests arriving together become one batch
    private readonly Dictionary<string, List<string>> _pendingShellSends = new();

    /// <summary>Queues files from the Explorer menu. Must be called on the UI thread.</summary>
    public void QueueShellSend(string target, IReadOnlyList<string> files)
    {
        if (_pendingShellSends.TryGetValue(target, out var pending))
        {
            pending.AddRange(files);
            return;
        }
        _pendingShellSends[target] = files.ToList();
        _ = FlushShellSendAsync(target);
    }

    private async Task FlushShellSendAsync(string target)
    {
        await Task.Delay(ShellBatchWindowMs);
        var files = _pendingShellSends[target].Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        _pendingShellSends.Remove(target);
        await SendFilesFromShellAsync(target, files);
    }

    [RelayCommand]
    private void CancelTransfer(TransferItem? item)
    {
        if (item != null) _networkServer.CancelTransfer(item);
    }

    [RelayCommand]
    private void ClearFinishedTransfers()
    {
        foreach (var item in Transfers.Where(t => !t.IsActive).ToList())
        {
            Transfers.Remove(item);
        }
    }

    [RelayCommand]
    public void OpenDownloadFolder()
    {
        var folder = string.IsNullOrWhiteSpace(DownloadDirectory) ? _networkServer.DownloadDirectory : DownloadDirectory;
        try
        {
            Directory.CreateDirectory(folder);
            System.Diagnostics.Process.Start("explorer.exe", folder);
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Can't open the folder:\n{ex.Message}", "Open Downloads", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }
}
