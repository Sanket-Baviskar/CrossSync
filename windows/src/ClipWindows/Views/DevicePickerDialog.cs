using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using ListBox = System.Windows.Controls.ListBox;
using Button = System.Windows.Controls.Button;
using Orientation = System.Windows.Controls.Orientation;
using HorizontalAlignment = System.Windows.HorizontalAlignment;
using Color = System.Windows.Media.Color;
using System.Windows.Media;
using ClipWindows.Models;

namespace ClipWindows.Views;

/// <summary>
/// Asks which connected phone(s) to send files to. Returns null if cancelled.
/// </summary>
public class DevicePickerDialog : Window
{
    private readonly ListBox _list;
    private readonly IReadOnlyList<DevicePeer> _devices;

    public IReadOnlyList<DevicePeer>? SelectedDevices { get; private set; }

    private DevicePickerDialog(IReadOnlyList<DevicePeer> devices, int fileCount)
    {
        _devices = devices;
        Title = "Send to Phone";
        Width = 380;
        SizeToContent = SizeToContent.Height;
        ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        Background = new SolidColorBrush(Color.FromRgb(0x1E, 0x1E, 0x2E));
        Foreground = new SolidColorBrush(Color.FromRgb(0xCD, 0xD6, 0xF4));

        var root = new StackPanel { Margin = new Thickness(16) };
        root.Children.Add(new TextBlock
        {
            Text = fileCount == 1 ? "Send this file to:" : $"Send {fileCount} files to:",
            FontWeight = FontWeights.SemiBold,
            Margin = new Thickness(0, 0, 0, 8)
        });

        _list = new ListBox
        {
            Background = new SolidColorBrush(Color.FromRgb(0x18, 0x18, 0x25)),
            Foreground = Foreground,
            BorderBrush = new SolidColorBrush(Color.FromRgb(0x31, 0x32, 0x44)),
            MinHeight = 60
        };
        _list.Items.Add("All connected phones");
        foreach (var device in devices)
        {
            _list.Items.Add(device.DisplayName);
        }
        _list.SelectedIndex = 0;
        _list.MouseDoubleClick += (_, _) => Accept();
        root.Children.Add(_list);

        var buttons = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right,
            Margin = new Thickness(0, 12, 0, 0)
        };
        var send = new Button { Content = "Send", IsDefault = true, Padding = new Thickness(16, 4, 16, 4), Margin = new Thickness(0, 0, 8, 0) };
        send.Click += (_, _) => Accept();
        var cancel = new Button { Content = "Cancel", IsCancel = true, Padding = new Thickness(16, 4, 16, 4) };
        buttons.Children.Add(send);
        buttons.Children.Add(cancel);
        root.Children.Add(buttons);

        Content = root;
    }

    private void Accept()
    {
        int index = _list.SelectedIndex;
        if (index < 0) return;
        SelectedDevices = index == 0 ? _devices.ToList() : new List<DevicePeer> { _devices[index - 1] };
        DialogResult = true;
    }

    /// <summary>Shows the picker; returns the chosen phones, or null if the user cancelled.</summary>
    public static IReadOnlyList<DevicePeer>? Pick(Window? owner, IReadOnlyList<DevicePeer> devices, int fileCount)
    {
        var dialog = new DevicePickerDialog(devices, fileCount);
        if (owner is { IsVisible: true }) dialog.Owner = owner;
        return dialog.ShowDialog() == true ? dialog.SelectedDevices : null;
    }
}
