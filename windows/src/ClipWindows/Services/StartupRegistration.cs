using System;
using System.Diagnostics;
using System.IO;
using System.Security;
using System.Security.Principal;
using Microsoft.Win32;

namespace ClipWindows.Services;

/// <summary>
/// "Run on startup" as a Task Scheduler logon task rather than an HKCU\...\Run entry.
///
/// Explorer starts Run entries late and at low priority while the desktop loads (minutes on a
/// busy sign-in), whereas a logon task starts as soon as the user signs in. A per-user logon task
/// needs no admin rights. If the task can't be created, the Run entry is used as a fallback.
/// </summary>
public static class StartupRegistration
{
    private const string TaskName = "CrossSync";
    private const string RunKey = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValue = "CrossSync";

    /// <summary>True if either the logon task or the older Run entry exists.</summary>
    public static bool IsEnabled() => TaskExists() || RunEntryExists();

    /// <summary>Creates (or refreshes, with the current exe path) or removes startup registration.</summary>
    public static void Set(bool enabled)
    {
        if (!enabled)
        {
            DeleteTask();
            DeleteRunEntry();
            return;
        }

        string? exe = Environment.ProcessPath;
        if (string.IsNullOrEmpty(exe)) return;

        if (CreateTask(exe))
        {
            DeleteRunEntry(); // migrate from the old, slower Run entry
        }
        else
        {
            SetRunEntry(exe);
        }
    }

    private static bool CreateTask(string exe)
    {
        string user = WindowsIdentity.GetCurrent().Name;
        string xml = $"""
            <?xml version="1.0" encoding="UTF-16"?>
            <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
              <RegistrationInfo><Description>Starts CrossSync in the tray when you sign in.</Description></RegistrationInfo>
              <Triggers>
                <LogonTrigger><Enabled>true</Enabled><UserId>{SecurityElement.Escape(user)}</UserId></LogonTrigger>
              </Triggers>
              <Principals>
                <Principal id="Author">
                  <UserId>{SecurityElement.Escape(user)}</UserId>
                  <LogonType>InteractiveToken</LogonType>
                  <RunLevel>LeastPrivilege</RunLevel>
                </Principal>
              </Principals>
              <Settings>
                <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
                <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
                <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
                <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
                <Priority>4</Priority>
              </Settings>
              <Actions Context="Author">
                <Exec>
                  <Command>{SecurityElement.Escape(exe)}</Command>
                  <Arguments>{App.MinimizedArg}</Arguments>
                  <WorkingDirectory>{SecurityElement.Escape(Path.GetDirectoryName(exe) ?? "")}</WorkingDirectory>
                </Exec>
              </Actions>
            </Task>
            """;

        string file = Path.Combine(Path.GetTempPath(), $"crosssync-task-{Guid.NewGuid():N}.xml");
        try
        {
            File.WriteAllText(file, xml, System.Text.Encoding.Unicode);
            return RunSchtasks($"/Create /TN \"{TaskName}\" /XML \"{file}\" /F");
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Creating startup task failed: {ex.Message}");
            return false;
        }
        finally
        {
            try { File.Delete(file); } catch { }
        }
    }

    private static bool TaskExists() => RunSchtasks($"/Query /TN \"{TaskName}\"");

    private static void DeleteTask()
    {
        if (TaskExists()) RunSchtasks($"/Delete /TN \"{TaskName}\" /F");
    }

    private static bool RunSchtasks(string arguments)
    {
        try
        {
            using var process = Process.Start(new ProcessStartInfo("schtasks.exe", arguments)
            {
                CreateNoWindow = true,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            });
            if (process == null) return false;
            process.StandardOutput.ReadToEnd();
            process.StandardError.ReadToEnd();
            return process.WaitForExit(10_000) && process.ExitCode == 0;
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"schtasks {arguments} failed: {ex.Message}");
            return false;
        }
    }

    private static bool RunEntryExists()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, false);
            return key?.GetValue(RunValue) != null;
        }
        catch
        {
            return false;
        }
    }

    private static void SetRunEntry(string exe)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, true);
            key?.SetValue(RunValue, $"\"{exe}\" {App.MinimizedArg}");
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Failed to write startup entry: {ex.Message}");
        }
    }

    private static void DeleteRunEntry()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, true);
            key?.DeleteValue(RunValue, throwOnMissingValue: false);
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Failed to remove startup entry: {ex.Message}");
        }
    }
}
