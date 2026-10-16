using System;
using System.IO;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace ClipWindows.Network;

/// <summary>
/// Loads (or creates on first run) the self-signed TLS certificate the server presents.
/// The phone identifies this PC by the certificate's SHA-256 fingerprint, so it must stay
/// stable across restarts.
/// </summary>
public static class CertificateManager
{
    public static X509Certificate2 LoadOrCreate(string directory)
    {
        string path = Path.Combine(directory, "server.pfx");
        if (File.Exists(path))
        {
            try
            {
                return X509CertificateLoader.LoadPkcs12FromFile(path, null);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to load certificate, creating a new one: {ex.Message}");
            }
        }

        using var rsa = RSA.Create(2048);
        var request = new CertificateRequest(
            $"CN=CrossSync {Environment.MachineName}", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));

        using var cert = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(20));
        byte[] pfx = cert.Export(X509ContentType.Pfx);

        Directory.CreateDirectory(directory);
        File.WriteAllBytes(path, pfx);

        // Re-import from PFX: SChannel can't use the ephemeral key of a freshly created certificate.
        return X509CertificateLoader.LoadPkcs12(pfx, null);
    }

    /// <summary>Formats the certificate's SHA-256 fingerprint as grouped hex, matching the phone's display.</summary>
    public static string FormatFingerprint(X509Certificate2 cert)
    {
        string hex = Convert.ToHexString(cert.GetCertHash(HashAlgorithmName.SHA256));
        var groups = new string[8];
        for (int i = 0; i < 8; i++)
        {
            groups[i] = hex.Substring(i * 4, 4);
        }
        return string.Join(" ", groups);
    }
}
