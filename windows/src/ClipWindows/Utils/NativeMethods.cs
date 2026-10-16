using System;
using System.Runtime.InteropServices;

namespace ClipWindows.Utils;

public static class NativeMethods
{
    public const int WM_CLIPBOARDUPDATE = 0x031D;

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool AddClipboardFormatListener(IntPtr hwnd);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool RemoveClipboardFormatListener(IntPtr hwnd);

    /// <summary>Increments every time the clipboard contents change.</summary>
    [DllImport("user32.dll")]
    public static extern uint GetClipboardSequenceNumber();
}
