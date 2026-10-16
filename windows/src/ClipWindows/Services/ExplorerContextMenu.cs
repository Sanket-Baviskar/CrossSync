using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.Win32;

namespace ClipWindows.Services;

/// <summary>
/// Maintains the Explorer right-click menu for files: "CrossSync" ▸ "All connected phones" /
/// one entry per paired phone. Each entry runs this exe with "--send-to &lt;deviceId|all&gt; &lt;file&gt;";
/// a running instance receives the request over a named pipe (see App).
///
/// Per-user registry keys (HKCU\Software\Classes\*\shell), so no admin rights are needed.
/// On Windows 11 the entry appears under "Show more options" (the classic menu).
/// </summary>
public static class ExplorerContextMenu
{
    private const string KeyPath = @"Software\Classes\*\shell\CrossSync";
    public const string SendToArg = "--send-to";
    public const string AllTarget = "all";

    public static void Update(IReadOnlyCollection<PairedDevice> devices, bool enabled)
    {
        try
        {
            Registry.CurrentUser.DeleteSubKeyTree(KeyPath, throwOnMissingSubKey: false);
            if (!enabled || devices.Count == 0) return;

            string? exe = Environment.ProcessPath;
            if (string.IsNullOrEmpty(exe)) return;

            using var root = Registry.CurrentUser.CreateSubKey(KeyPath);
            root.SetValue("MUIVerb", "CrossSync");
            root.SetValue("SubCommands", ""); // empty: submenu items come from the "shell" subkey
            root.SetValue("Icon", $"\"{exe}\",0");

            int order = 0;
            if (devices.Count > 1)
            {
                // ECF_SEPARATORAFTER: a line between "All" and the individual phones
                AddItem(exe, $"{order++:D2}", "All connected phones", AllTarget, commandFlags: 0x40);
            }

            foreach (var device in devices.OrderBy(d => d.Name, StringComparer.CurrentCultureIgnoreCase))
            {
                AddItem(exe, $"{order++:D2}", device.Name, device.DeviceId, commandFlags: null);
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Failed to update Explorer menu: {ex.Message}");
        }
    }

    public static void Remove()
    {
        try
        {
            Registry.CurrentUser.DeleteSubKeyTree(KeyPath, throwOnMissingSubKey: false);
        }
        catch { }
    }

    private static void AddItem(string exe, string keyName, string label, string target, int? commandFlags)
    {
        using var item = Registry.CurrentUser.CreateSubKey($@"{KeyPath}\shell\{keyName}");
        item.SetValue("MUIVerb", label.Replace("&", "&&")); // '&' would otherwise mark an access key
        if (commandFlags is { } flags) item.SetValue("CommandFlags", flags, RegistryValueKind.DWord);

        using var command = item.CreateSubKey("command");
        command.SetValue("", $"\"{exe}\" {SendToArg} {target} \"%1\"");
    }

    /// <summary>Parses "--send-to &lt;target&gt; &lt;files...&gt;"; null if the args aren't a send request.</summary>
    public static (string Target, IReadOnlyList<string> Files)? ParseSendArgs(IReadOnlyList<string> args)
    {
        if (args.Count < 3 || !string.Equals(args[0], SendToArg, StringComparison.OrdinalIgnoreCase)) return null;
        return (args[1], args.Skip(2).ToList());
    }
}
