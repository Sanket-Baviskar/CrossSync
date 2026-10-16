using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using ClipWindows.Protocol;

namespace ClipWindows.Network;

/// <summary>
/// Responds to UDP broadcast scans from Android on port 45679.
/// Broadcast query: "CROSSSYNC_DISCOVER"
/// Response: Machine Hostname
/// </summary>
public class DeviceDiscoveryService : IDisposable
{
    private UdpClient? _udpListener;
    private CancellationTokenSource? _cts;

    public void Start()
    {
        Stop();
        _cts = new CancellationTokenSource();
        _ = ListenLoopAsync(_cts.Token);
    }

    private async Task ListenLoopAsync(CancellationToken ct)
    {
        try
        {
            _udpListener = new UdpClient(ProtocolConstants.DiscoveryPort)
            {
                EnableBroadcast = true
            };

            while (!ct.IsCancellationRequested)
            {
                var result = await _udpListener.ReceiveAsync(ct);
                string message = Encoding.UTF8.GetString(result.Buffer);

                if (message.Trim() == ProtocolConstants.DiscoveryMagic)
                {
                    string machineName = Environment.MachineName;
                    byte[] response = Encoding.UTF8.GetBytes(machineName);
                    await _udpListener.SendAsync(response, response.Length, result.RemoteEndPoint);
                    System.Diagnostics.Debug.WriteLine($"[Discovery] Replied '{machineName}' to {result.RemoteEndPoint}");
                }
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"UDP Discovery error: {ex.Message}");
        }
    }

    public void Stop()
    {
        _cts?.Cancel();
        _udpListener?.Close();
        _udpListener?.Dispose();
        _udpListener = null;
        _cts?.Dispose();
        _cts = null;
    }

    public void Dispose()
    {
        Stop();
        GC.SuppressFinalize(this);
    }
}
