using System;
using CommunityToolkit.Mvvm.ComponentModel;

namespace ClipWindows.Models;

public enum TransferDirection
{
    Sending,
    Receiving
}

public enum TransferStatus
{
    Pending,
    InProgress,
    Completed,
    Failed,
    Cancelled
}

/// <summary>
/// Observable so progress, speed and status update live in the Transfers list.
/// </summary>
public partial class TransferItem : ObservableObject
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string FileName { get; set; } = string.Empty;
    public long FileSizeBytes { get; set; }
    public TransferDirection Direction { get; set; }

    /// <summary>The phone this file is going to or coming from.</summary>
    public string DeviceId { get; set; } = string.Empty;
    public string DeviceName { get; set; } = string.Empty;

    public string DirectionLabel => Direction == TransferDirection.Sending
        ? $"To {DeviceName}"
        : $"From {DeviceName}";
    public string Sha256 { get; set; } = string.Empty;
    public string LocalPath { get; set; } = string.Empty;
    public DateTime CreatedAt { get; set; } = DateTime.Now;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ProgressPercentage))]
    private long _bytesTransferred;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsActive))]
    private TransferStatus _status = TransferStatus.Pending;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(SpeedText))]
    private long _speedBytesPerSec;

    [ObservableProperty]
    private string? _errorMessage;

    public double ProgressPercentage => FileSizeBytes > 0 ? (double)BytesTransferred / FileSizeBytes * 100.0 : (Status == TransferStatus.Completed ? 100.0 : 0.0);

    public bool IsActive => Status is TransferStatus.Pending or TransferStatus.InProgress;

    public string SpeedText => IsActive && SpeedBytesPerSec > 0 ? $"{FormatSize(SpeedBytesPerSec)}/s" : string.Empty;

    partial void OnStatusChanged(TransferStatus value)
    {
        OnPropertyChanged(nameof(ProgressPercentage));
        OnPropertyChanged(nameof(SpeedText));
    }

    private static string FormatSize(long bytes) => bytes switch
    {
        >= 1_073_741_824 => $"{bytes / 1_073_741_824.0:F1} GB",
        >= 1_048_576 => $"{bytes / 1_048_576.0:F1} MB",
        >= 1_024 => $"{bytes / 1_024.0:F1} KB",
        _ => $"{bytes} B"
    };
}
