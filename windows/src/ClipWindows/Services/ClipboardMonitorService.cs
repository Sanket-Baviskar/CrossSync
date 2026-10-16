using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Windows;
using System.Windows.Interop;
using DataObject = System.Windows.DataObject;
using DataFormats = System.Windows.DataFormats;
using System.Windows.Media.Imaging;
using ClipWindows.Protocol;
using ClipWindows.Utils;

namespace ClipWindows.Services;

/// <summary>
/// Monitors Windows Clipboard updates via Win32 AddClipboardFormatListener (text and images).
/// Avoids feedback loops when the clipboard is set by incoming remote sync.
/// </summary>
public class ClipboardMonitorService : IDisposable
{
    private HwndSource? _hwndSource;

    // Text last received from or sent to a phone. Updates matching it are echoes and are not re-sent.
    private string _lastSyncedText = string.Empty;

    // Hash of the last image sent, so re-copying the same image isn't sent twice
    private byte[]? _lastSentImageHash;

    // Clipboard sequence number right after this app set the clipboard. Windows re-encodes images
    // we put on the clipboard, so their bytes can't identify an echo; the sequence number can.
    private uint _ownClipboardSequence;

    public event Action<string>? ClipboardTextCopied;

    /// <summary>A copied image, PNG-encoded.</summary>
    public event Action<byte[]>? ClipboardImageCopied;

    public void Start(Window window)
    {
        var helper = new WindowInteropHelper(window);
        IntPtr handle = helper.EnsureHandle();

        _hwndSource = HwndSource.FromHwnd(handle);
        _hwndSource?.AddHook(HwndHook);

        NativeMethods.AddClipboardFormatListener(handle);
    }

    public void SetRemoteClipboardText(string text)
    {
        _lastSyncedText = text;
        Application.Current?.Dispatcher.Invoke(() => SetClipboard(new DataObject(DataFormats.UnicodeText, text)));
    }

    /// <summary>Puts an image from a phone on the clipboard, as a bitmap plus "PNG" (keeps transparency).</summary>
    public void SetRemoteClipboardImage(byte[] imageBytes)
    {
        Application.Current?.Dispatcher.Invoke(() =>
        {
            try
            {
                var frame = DecodeImage(imageBytes);
                var data = new DataObject();
                data.SetImage(frame);
                data.SetData("PNG", new MemoryStream(EncodePng(frame)));
                SetClipboard(data);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to decode clipboard image: {ex.Message}");
            }
        });
    }

    private void SetClipboard(DataObject data)
    {
        // Retry for ~0.5s: another application may briefly hold the clipboard open
        for (int attempt = 1; attempt <= 10; attempt++)
        {
            try
            {
                Clipboard.SetDataObject(data, true);
                _ownClipboardSequence = NativeMethods.GetClipboardSequenceNumber();
                return;
            }
            catch (COMException ex)
            {
                if (attempt == 10)
                {
                    System.Diagnostics.Debug.WriteLine($"Failed to set clipboard: {ex.Message}");
                    return;
                }
                System.Threading.Thread.Sleep(50);
            }
        }
    }

    private IntPtr HwndHook(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == NativeMethods.WM_CLIPBOARDUPDATE)
        {
            OnClipboardUpdated();
            handled = false;
        }
        return IntPtr.Zero;
    }

    private void OnClipboardUpdated()
    {
        // Our own write (content that just came from a phone)
        if (NativeMethods.GetClipboardSequenceNumber() == _ownClipboardSequence) return;

        try
        {
            // Text wins when both are present (e.g. Office cells also put a picture on the clipboard)
            if (Clipboard.ContainsText())
            {
                string text = Clipboard.GetText();
                if (!string.IsNullOrEmpty(text) && text != _lastSyncedText)
                {
                    _lastSyncedText = text;
                    ClipboardTextCopied?.Invoke(text);
                }
                return;
            }

            byte[]? png = ReadClipboardImageAsPng();
            if (png == null || png.Length > ProtocolConstants.MaxClipboardImageBytes) return;

            byte[] hash = SHA256.HashData(png);
            if (_lastSentImageHash != null && CryptographicOperations.FixedTimeEquals(hash, _lastSentImageHash)) return;
            _lastSentImageHash = hash;
            ClipboardImageCopied?.Invoke(png);
        }
        catch
        {
            // Clipboard access concurrency guard
        }
    }

    /// <summary>
    /// Prefers the "PNG" format (browsers, Snipping Tool; keeps transparency), else the bitmap.
    /// </summary>
    private static byte[]? ReadClipboardImageAsPng()
    {
        var data = Clipboard.GetDataObject();
        if (data == null) return null;

        if (data.GetDataPresent("PNG") && data.GetData("PNG") is MemoryStream pngStream)
        {
            return pngStream.ToArray();
        }

        if (Clipboard.ContainsImage() && Clipboard.GetImage() is { } bitmap)
        {
            return EncodePng(bitmap);
        }
        return null;
    }

    public static BitmapSource DecodeImage(byte[] bytes)
    {
        using var stream = new MemoryStream(bytes);
        var decoder = BitmapDecoder.Create(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
        var frame = decoder.Frames[0];
        frame.Freeze();
        return frame;
    }

    private static byte[] EncodePng(BitmapSource image)
    {
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(image));
        using var output = new MemoryStream();
        encoder.Save(output);
        return output.ToArray();
    }

    public void Dispose()
    {
        if (_hwndSource != null)
        {
            NativeMethods.RemoveClipboardFormatListener(_hwndSource.Handle);
            _hwndSource.RemoveHook(HwndHook);
            _hwndSource = null;
        }
        GC.SuppressFinalize(this);
    }
}
