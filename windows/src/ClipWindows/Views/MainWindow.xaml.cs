using System;
using System.Drawing;
using System.Windows;
using System.Windows.Forms;
using ClipWindows.ViewModels;

namespace ClipWindows.Views;

public partial class MainWindow : Window
{
    private NotifyIcon? _notifyIcon;
    private bool _isExplicitExit;
    private bool _balloonShown;

    public MainWindow()
    {
        InitializeComponent();
        Closing += OnClosing;
    }

    /// <summary>
    /// Starts sync and the tray icon. Called by App before (or instead of) showing the window,
    /// so a start-minimized launch still runs fully in the tray.
    /// </summary>
    public void Start()
    {
        SetupTrayIcon();

        if (DataContext is MainViewModel vm)
        {
            vm.UserNotification += (title, message, isError) =>
                _notifyIcon?.ShowBalloonTip(4000, title, message, isError ? ToolTipIcon.Warning : ToolTipIcon.Info);
            vm.Initialize(this);
        }
    }

    private void SetupTrayIcon()
    {
        _notifyIcon = new NotifyIcon
        {
            Icon = LoadAppIcon(),
            Text = "CrossSync — Clipboard Sync Active",
            Visible = true
        };

        var contextMenu = new ContextMenuStrip();
        var openItem = new ToolStripMenuItem("Open CrossSync", null, (s, e) => ShowWindow());
        var sendFileItem = new ToolStripMenuItem("Send File to Phone...", null, (s, e) =>
        {
            ShowWindow();
            if (DataContext is MainViewModel vm)
            {
                _ = vm.SendFile();
            }
        });
        var downloadsItem = new ToolStripMenuItem("Open Downloads Folder", null, (s, e) =>
        {
            if (DataContext is MainViewModel vm)
            {
                vm.OpenDownloadFolder();
            }
        });
        var exitItem = new ToolStripMenuItem("Exit CrossSync", null, (s, e) => ExitApplication());

        contextMenu.Items.Add(openItem);
        contextMenu.Items.Add(sendFileItem);
        contextMenu.Items.Add(downloadsItem);
        contextMenu.Items.Add(new ToolStripSeparator());
        contextMenu.Items.Add(exitItem);

        _notifyIcon.ContextMenuStrip = contextMenu;
        _notifyIcon.DoubleClick += (s, e) => ShowWindow();
    }

    private static System.Drawing.Icon LoadAppIcon()
    {
        try
        {
            if (Environment.ProcessPath is { } exe)
            {
                return System.Drawing.Icon.ExtractAssociatedIcon(exe) ?? SystemIcons.Application;
            }
        }
        catch { }
        return SystemIcons.Application;
    }

    public void ShowWindow()
    {
        Show();
        WindowState = WindowState.Normal;
        ShowInTaskbar = true;
        Activate();
    }

    private void ExitApplication()
    {
        _isExplicitExit = true;
        _notifyIcon?.Dispose();
        _notifyIcon = null;
        if (DataContext is MainViewModel vm)
        {
            vm.Shutdown();
        }
        Close();
        Application.Current.Shutdown();
    }

    private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (!_isExplicitExit)
        {
            e.Cancel = true;
            Hide();
            ShowInTaskbar = false;
            if (!_balloonShown)
            {
                _balloonShown = true;
                _notifyIcon?.ShowBalloonTip(3000, "CrossSync is still running", "CrossSync is minimized to the system tray and keeps syncing in the background.", ToolTipIcon.Info);
            }
        }
    }

    protected override void OnStateChanged(EventArgs e)
    {
        base.OnStateChanged(e);
        if (WindowState == WindowState.Minimized)
        {
            Hide();
            ShowInTaskbar = false;
        }
    }
}
