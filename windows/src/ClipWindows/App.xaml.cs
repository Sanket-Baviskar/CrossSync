using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using ClipWindows.Services;
using ClipWindows.ViewModels;
using ClipWindows.Views;

namespace ClipWindows;

public partial class App : System.Windows.Application
{
    public const string MinimizedArg = "--minimized";

    private const string MutexName = @"Local\CrossSync.ClipWindows.SingleInstance";

    /// <summary>
    /// Commands from later launches (the Explorer menu, or opening the app again) reach the
    /// running instance over this pipe. CurrentUserOnly keeps other accounts out.
    /// </summary>
    private static readonly string PipeName = $"CrossSync.ClipWindows.{Environment.UserName}";

    private sealed record InstanceCommand(string Action, string? Target = null, List<string>? Files = null);

    private Mutex? _instanceMutex;
    private CancellationTokenSource? _pipeCts;
    private MainWindow? _window;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        var sendRequest = ExplorerContextMenu.ParseSendArgs(e.Args);
        var command = sendRequest is { } req
            ? new InstanceCommand("send", req.Target, req.Files.ToList())
            : new InstanceCommand("show");

        _instanceMutex = new Mutex(true, MutexName, out bool createdNew);
        if (!createdNew)
        {
            // Already running (likely in the tray): hand the request over instead of failing to bind the port
            ForwardToRunningInstance(command);
            _instanceMutex.Dispose();
            _instanceMutex = null;
            Shutdown();
            return;
        }

        ShutdownMode = ShutdownMode.OnExplicitShutdown;

        // Listen before anything slow, so other launches can connect right away
        _pipeCts = new CancellationTokenSource();
        _ = RunCommandPipeAsync(_pipeCts.Token);

        _window = new MainWindow();
        MainWindow = _window;
        _window.Start();

        bool startMinimized = sendRequest != null ||
                              e.Args.Any(a => string.Equals(a, MinimizedArg, StringComparison.OrdinalIgnoreCase));
        if (!startMinimized)
        {
            _window.ShowWindow();
        }

        if (sendRequest != null)
        {
            HandleCommand(command);
        }
    }

    private static void ForwardToRunningInstance(InstanceCommand command)
    {
        try
        {
            using var client = new NamedPipeClientStream(".", PipeName, PipeDirection.Out, PipeOptions.CurrentUserOnly);
            // Explorer may launch one process per selected file at once; they queue for the pipe
            client.Connect(10_000);
            byte[] payload = JsonSerializer.SerializeToUtf8Bytes(command);
            client.Write(payload, 0, payload.Length);
            client.Flush();
        }
        catch (Exception ex)
        {
            MessageBox.Show($"CrossSync is running but didn't respond:\n{ex.Message}", "CrossSync",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private async Task RunCommandPipeAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await using var server = new NamedPipeServerStream(
                    PipeName, PipeDirection.In, 1, PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await server.WaitForConnectionAsync(ct);

                using var buffer = new MemoryStream();
                await server.CopyToAsync(buffer, ct);
                if (buffer.Length > 1_000_000) continue; // a file list, not a payload

                var command = JsonSerializer.Deserialize<InstanceCommand>(buffer.ToArray());
                if (command != null)
                {
                    _ = Dispatcher.BeginInvoke(() => HandleCommand(command));
                }
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Command pipe error: {ex.Message}");
            }
        }
    }

    private void HandleCommand(InstanceCommand command)
    {
        if (_window == null) return;
        switch (command.Action)
        {
            case "send" when command.Target != null && command.Files is { Count: > 0 }:
                if (_window.DataContext is MainViewModel vm)
                {
                    vm.QueueShellSend(command.Target, command.Files);
                }
                break;
            default:
                _window.ShowWindow();
                break;
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _pipeCts?.Cancel();
        if (_instanceMutex != null)
        {
            try { _instanceMutex.ReleaseMutex(); } catch { }
            _instanceMutex.Dispose();
        }
        base.OnExit(e);
    }
}
