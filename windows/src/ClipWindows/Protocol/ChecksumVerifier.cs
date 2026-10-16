using System;
using System.IO;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;

namespace ClipWindows.Protocol;

/// <summary>
/// Mirrors ChecksumManager.kt on Android.
/// </summary>
public static class ChecksumVerifier
{
    public static async Task<string> ComputeSha256Async(string filePath, CancellationToken ct = default)
    {
        using var sha256 = SHA256.Create();
        await using var stream = File.OpenRead(filePath);
        byte[] hash = await sha256.ComputeHashAsync(stream, ct);
        return Convert.ToHexStringLower(hash);
    }

    public static string ComputeSha256(byte[] data)
    {
        byte[] hash = SHA256.HashData(data);
        return Convert.ToHexStringLower(hash);
    }

    public static async Task<bool> VerifyAndFinalizeAsync(string tempFilePath, string destinationFilePath, string expectedSha256, CancellationToken ct = default)
    {
        if (!File.Exists(tempFilePath)) return false;

        string actualSha256 = await ComputeSha256Async(tempFilePath, ct);
        if (!string.Equals(actualSha256, expectedSha256, StringComparison.OrdinalIgnoreCase))
        {
            File.Delete(tempFilePath);
            return false;
        }

        if (File.Exists(destinationFilePath))
        {
            File.Delete(destinationFilePath);
        }

        File.Move(tempFilePath, destinationFilePath);
        return true;
    }
}
