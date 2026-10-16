using System;
using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using ClipWindows.Models;
using ClipWindows.Protocol;
using ClipWindows.Services;

namespace ClipWindows.Network;

/// <summary>
/// TLS server the phones connect to. Several paired phones can be connected at once; the PC
/// acts as a hub, relaying clipboard text from each device to every other device.
///
/// A connection only goes live after its handshake is authenticated (valid device token, or
/// the current pairing code). A phone that reconnects replaces only its own old connection, so
/// an unpaired device on the LAN can neither exchange data nor kick a paired phone off.
/// </summary>
public class NetworkServerService : IDisposable
{
    private const int HeartbeatIntervalMs = 20_000;
    private const int PeerTimeoutMs = 60_000;
    private const int HandshakeTimeoutMs = 15_000;
    private const int MaxPendingConnections = 8;
    private const int MaxFailedPairingAttempts = 5;
    private const int ProgressIntervalMs = 250;
    private const int MaxDeviceNameLength = 64;
    private const int MaxFailuresPerWindow = 10;
    private const int FailureWindowMs = 10_000;
    private const int FailureBlockMs = 30_000;

    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    /// <summary>One authenticated phone connection and its per-connection transfer state.</summary>
    private sealed class ClientConnection(TcpClient client, Stream stream, DevicePeer peer)
    {
        public TcpClient Client { get; } = client;
        public Stream Stream { get; } = stream;
        public DevicePeer Peer { get; } = peer;
        public SemaphoreSlim SendLock { get; } = new(1, 1);     // one frame on the wire at a time
        public SemaphoreSlim FileSendLock { get; } = new(1, 1); // one outgoing file at a time
        public volatile bool Closed;
        public volatile TransferItem? OutgoingTransfer;
        public volatile bool CancelOutgoing;
        public volatile TransferItem? CancelIncomingRequest;

        /// <summary>Where the next incoming file goes (set by FILE_ROUTE, used by the next FILE_META).</summary>
        public volatile FileRoute? PendingRoute;

        public void Close()
        {
            Closed = true;
            try { Client.Close(); } catch { }
        }
    }

    /// <summary>
    /// Delivery for a file a phone sends: keep it on this PC (<see cref="Pc"/>) and/or forward it
    /// to other phones (<see cref="To"/>, device IDs). No route means "PC only".
    /// </summary>
    private sealed record FileRoute(bool Pc, List<string>? To)
    {
        public IReadOnlyList<string> Targets => To ?? new List<string>();
        public bool KeepOnPc => Pc || Targets.Count == 0;
    }

    private sealed record DeviceListEntry(string Id, string Name);

    private const int RelayConnectWaitMs = 30_000;

    private readonly AppSettings _settings;
    private readonly X509Certificate2 _certificate;
    private readonly object _pairingLock = new();
    private readonly ConcurrentDictionary<string, ClientConnection> _clients = new();
    private readonly ConcurrentDictionary<IPAddress, FailureRecord> _failures = new();

    private TcpListener? _tcpListener;
    private CancellationTokenSource? _cts;
    private int _pendingConnections;
    private int _failedPairingAttempts;
    private string _pairingCode = NewPairingCode();

    public event Action<DevicePeer>? ClientConnected;
    public event Action<DevicePeer>? ClientDisconnected;
    public event Action<string, DevicePeer>? ClipboardTextReceived;

    /// <summary>A phone copied an image: the raw CLIPBOARD_IMAGE payload (see <see cref="ClipboardImagePayload"/>).</summary>
    public event Action<byte[], DevicePeer>? ClipboardImageReceived;
    public event Action<TransferItem>? FileTransferStarted;
    public event Action<TransferItem>? FileTransferProgress;
    public event Action<TransferItem, bool>? FileTransferCompleted;
    public event Action<string>? PairingCodeChanged;
    public event Action<string>? ServerError;

    public NetworkServerService(AppSettings settings)
    {
        _settings = settings;
        _certificate = CertificateManager.LoadOrCreate(AppSettings.DataDirectory);
        DownloadDirectory = settings.DownloadDirectory;
    }

    public bool IsClientConnected => !_clients.IsEmpty;
    public IReadOnlyList<DevicePeer> ConnectedDevices => _clients.Values.Select(c => c.Peer).ToList();
    public string DownloadDirectory { get; set; }
    public string CertificateFingerprint => CertificateManager.FormatFingerprint(_certificate);

    public string PairingCode
    {
        get { lock (_pairingLock) return _pairingCode; }
    }

    public void Start(int port = ProtocolConstants.DefaultPort)
    {
        Stop();
        try
        {
            Directory.CreateDirectory(DownloadDirectory);
            CleanupStaleTempFiles();
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Download directory setup failed: {ex.Message}");
        }
        _cts = new CancellationTokenSource();
        _ = RunServerLoopAsync(port, _cts.Token);
    }

    public void RegeneratePairingCode()
    {
        string code;
        lock (_pairingLock)
        {
            _pairingCode = NewPairingCode();
            _failedPairingAttempts = 0;
            code = _pairingCode;
        }
        PairingCodeChanged?.Invoke(code);
    }

    /// <summary>Disconnects a phone (e.g. after removing its pairing).</summary>
    public void DisconnectDevice(string deviceId)
    {
        if (_clients.TryGetValue(deviceId, out var conn)) conn.Close();
    }

    private static string NewPairingCode() => RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");

    // ── Accept loop ──────────────────────────────────────────────────────────

    private async Task RunServerLoopAsync(int port, CancellationToken ct)
    {
        try
        {
            _tcpListener = new TcpListener(IPAddress.Any, port);
            _tcpListener.Start();
        }
        catch (SocketException ex)
        {
            ServerError?.Invoke($"Could not listen on port {port}: {ex.Message}. Is another copy of CrossSync running?");
            return;
        }

        try
        {
            while (!ct.IsCancellationRequested)
            {
                var client = await _tcpListener.AcceptTcpClientAsync(ct);

                // Refuse a device that keeps failing (e.g. an outdated app retrying in a tight loop)
                if ((client.Client.RemoteEndPoint as IPEndPoint)?.Address is { } remote && IsThrottled(remote))
                {
                    try { client.Close(); } catch { }
                    continue;
                }

                // Bound the number of connections still in the TLS/pairing stage
                if (Interlocked.Increment(ref _pendingConnections) > MaxPendingConnections)
                {
                    Interlocked.Decrement(ref _pendingConnections);
                    try { client.Close(); } catch { }
                    continue;
                }

                _ = Task.Run(() => HandleConnectionAsync(client, ct));
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Server loop error: {ex.Message}");
        }
    }

    private async Task HandleConnectionAsync(TcpClient client, CancellationToken ct)
    {
        var endpoint = client.Client.RemoteEndPoint as IPEndPoint;
        SslStream? ssl = null;
        DevicePeer? peer = null;

        try
        {
            client.NoDelay = true;
            ssl = new SslStream(client.GetStream(), leaveInnerStreamOpen: false);

            using var handshakeCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            handshakeCts.CancelAfter(HandshakeTimeoutMs);

            await ssl.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
            {
                ServerCertificate = _certificate,
                ClientCertificateRequired = false
            }, handshakeCts.Token);

            peer = await PerformHandshakeAsync(ssl, endpoint, handshakeCts.Token);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Handshake with {endpoint} failed: {ex.Message}");
        }
        finally
        {
            Interlocked.Decrement(ref _pendingConnections);
        }

        if (peer == null || ssl == null)
        {
            if (endpoint != null) RecordFailedConnection(endpoint.Address);
            try { ssl?.Dispose(); } catch { }
            try { client.Close(); } catch { }
            return;
        }

        // Authenticated: this replaces only the same phone's previous connection (e.g. after a Wi-Fi blip)
        var conn = new ClientConnection(client, ssl, peer);
        ClientConnection? previous = null;
        _clients.AddOrUpdate(peer.DeviceId, conn, (_, old) =>
        {
            previous = old;
            return conn;
        });
        previous?.Close();

        ClientConnected?.Invoke(peer);
        _ = BroadcastDeviceListAsync();

        try
        {
            await HandleClientCommunicationAsync(conn, ct);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Connection to {peer.DeviceName} ended: {ex.Message}");
        }
        finally
        {
            conn.Close();
            try { ssl.Dispose(); } catch { }

            // Only report a disconnect if a newer connection from this phone hasn't taken over
            if (_clients.TryRemove(new KeyValuePair<string, ClientConnection>(peer.DeviceId, conn)))
            {
                ClientDisconnected?.Invoke(peer);
                _ = BroadcastDeviceListAsync();
            }
        }
    }

    private sealed class FailureRecord
    {
        public int Count;
        public long WindowStart;
        public long BlockedUntil;
    }

    private bool IsThrottled(IPAddress address)
    {
        if (!_failures.TryGetValue(address, out var record)) return false;
        lock (record) return Environment.TickCount64 < record.BlockedUntil;
    }

    /// <summary>
    /// Counts failed connections (bad TLS, old protocol, rejected pairing) per address; too many
    /// in a short window blocks the address briefly so it can't keep the server busy.
    /// </summary>
    private void RecordFailedConnection(IPAddress address)
    {
        var record = _failures.GetOrAdd(address, _ => new FailureRecord());
        lock (record)
        {
            long now = Environment.TickCount64;
            if (now - record.WindowStart > FailureWindowMs)
            {
                record.WindowStart = now;
                record.Count = 0;
            }
            if (++record.Count >= MaxFailuresPerWindow)
            {
                record.BlockedUntil = now + FailureBlockMs;
                record.Count = 0;
                System.Diagnostics.Debug.WriteLine($"Blocking {address} for {FailureBlockMs / 1000}s after repeated failed connections");
            }
        }
    }

    private sealed record HandshakeRequest(string? Name, string? DeviceId, string? Token, string? PairingCode, int Version);

    private sealed record HandshakeResponse(bool Accepted, string Name, string? Token, string? Reason, int Version);

    /// <summary>
    /// Reads the phone's handshake and authenticates it. Returns the peer if accepted, else null
    /// (after telling the phone why, so it can ask the user for the pairing code).
    /// </summary>
    private async Task<DevicePeer?> PerformHandshakeAsync(Stream stream, IPEndPoint? endpoint, CancellationToken ct)
    {
        var header = await PacketHeader.ReadFromAsync(stream, ct);
        if (header.Type != MessageType.Handshake ||
            header.PayloadLength < 0 || header.PayloadLength > ProtocolConstants.MaxControlPayloadBytes)
        {
            throw new InvalidDataException($"Expected handshake, got {header.Type}");
        }

        byte[] payload = new byte[header.PayloadLength];
        await stream.ReadExactlyAsync(payload, ct);

        HandshakeRequest? request;
        try
        {
            request = JsonSerializer.Deserialize<HandshakeRequest>(payload, JsonOptions);
        }
        catch (JsonException)
        {
            request = null; // e.g. an older app version that sends a bare device name
        }

        bool accepted = false;
        string? reason;
        string? newToken = null;
        string deviceName = "Android Device";

        if (request == null || string.IsNullOrWhiteSpace(request.DeviceId) || request.Version < ProtocolConstants.ProtocolVersion)
        {
            reason = "unsupported_version";
        }
        else
        {
            if (!string.IsNullOrWhiteSpace(request.Name))
            {
                deviceName = request.Name.Length > MaxDeviceNameLength ? request.Name[..MaxDeviceNameLength] : request.Name;
            }

            if (!string.IsNullOrEmpty(request.Token) && _settings.IsTokenValid(request.DeviceId, request.Token))
            {
                accepted = true;
                reason = null;
                _settings.UpdateDeviceName(request.DeviceId, deviceName);
            }
            else if (!string.IsNullOrEmpty(request.PairingCode))
            {
                if (TryConsumePairingCode(request.PairingCode))
                {
                    newToken = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32));
                    _settings.AddPairedDevice(request.DeviceId, deviceName, newToken);
                    accepted = true;
                    reason = null;
                }
                else
                {
                    reason = "bad_code";
                }
            }
            else
            {
                reason = "pairing_required";
            }
        }

        var response = new HandshakeResponse(accepted, Environment.MachineName, newToken, reason, ProtocolConstants.ProtocolVersion);
        byte[] frame = PacketHeader.BuildFrame(MessageType.Handshake, ProtocolFlags.None, JsonSerializer.SerializeToUtf8Bytes(response, JsonOptions));
        await stream.WriteAsync(frame, ct);
        await stream.FlushAsync(ct);

        if (!accepted) return null;

        return new DevicePeer
        {
            DeviceId = request!.DeviceId!,
            DeviceName = deviceName,
            IpAddress = endpoint?.Address.ToString() ?? "Unknown",
            Port = endpoint?.Port ?? 0,
            IsConnected = true
        };
    }

    private bool TryConsumePairingCode(string code)
    {
        bool matched;
        bool regenerated = false;
        string current;
        lock (_pairingLock)
        {
            matched = CryptographicOperations.FixedTimeEquals(
                Encoding.ASCII.GetBytes(code.Trim()), Encoding.ASCII.GetBytes(_pairingCode));

            // A code is single-use, and too many wrong guesses burn it, so brute force is hopeless
            if (matched || ++_failedPairingAttempts >= MaxFailedPairingAttempts)
            {
                _pairingCode = NewPairingCode();
                _failedPairingAttempts = 0;
                regenerated = true;
            }
            current = _pairingCode;
        }

        if (regenerated)
        {
            PairingCodeChanged?.Invoke(current);
        }
        return matched;
    }

    // ── Receive loop ─────────────────────────────────────────────────────────

    private sealed class IncomingTransfer
    {
        public required TransferItem Item { get; init; }
        public required FileStream Stream { get; init; }
        public required string TempPath { get; init; }
        public FileRoute? Route { get; init; }
        public required DevicePeer From { get; init; }
        public long LastProgressTick { get; set; } = Environment.TickCount64;
        public long BytesAtLastProgress { get; set; }
    }

    private async Task HandleClientCommunicationAsync(ClientConnection conn, CancellationToken ct)
    {
        var stream = conn.Stream;
        IncomingTransfer? incoming = null;
        long lastReceivedTick = Environment.TickCount64;

        // Heartbeat: PING every 20s, and drop the connection if the phone has gone silent
        // (e.g. Wi-Fi switched off), since TCP alone may not notice for hours.
        using var heartbeatCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var heartbeatTask = Task.Run(async () =>
        {
            try
            {
                while (!heartbeatCts.Token.IsCancellationRequested)
                {
                    await Task.Delay(HeartbeatIntervalMs, heartbeatCts.Token);
                    if (Environment.TickCount64 - Interlocked.Read(ref lastReceivedTick) > PeerTimeoutMs)
                    {
                        System.Diagnostics.Debug.WriteLine($"{conn.Peer.DeviceName} timed out; closing connection");
                        conn.Close();
                        break;
                    }
                    await SendFrameAsync(conn, MessageType.Ping, ProtocolFlags.None, ReadOnlyMemory<byte>.Empty, heartbeatCts.Token);
                }
            }
            catch { }
        });

        void AbortIncoming(TransferStatus status, string? error)
        {
            if (incoming == null) return;
            try { incoming.Stream.Dispose(); } catch { }
            try { File.Delete(incoming.TempPath); } catch { }
            incoming.Item.Status = status;
            incoming.Item.ErrorMessage = error;
            incoming.Item.SpeedBytesPerSec = 0;
            FileTransferCompleted?.Invoke(incoming.Item, false);
            incoming = null;
        }

        try
        {
            while (!ct.IsCancellationRequested)
            {
                var header = await PacketHeader.ReadFromAsync(stream, ct);
                Interlocked.Exchange(ref lastReceivedTick, Environment.TickCount64);

                if (header.PayloadLength < 0 || header.PayloadLength > ProtocolConstants.MaxPayloadLength(header.Type))
                {
                    throw new InvalidDataException($"Payload length {header.PayloadLength} out of range for {header.Type}");
                }

                switch (header.Type)
                {
                    case MessageType.ClipboardText:
                    {
                        byte[] clipBuf = new byte[header.PayloadLength];
                        await stream.ReadExactlyAsync(clipBuf, ct);
                        string text = Encoding.UTF8.GetString(clipBuf);
                        ClipboardTextReceived?.Invoke(text, conn.Peer);

                        // Hub: pass it on to every other phone
                        _ = SendClipboardTextAsync(text, exceptDeviceId: conn.Peer.DeviceId);
                        break;
                    }

                    case MessageType.ClipboardImage:
                    {
                        byte[] imageBuf = new byte[header.PayloadLength];
                        await stream.ReadExactlyAsync(imageBuf, ct);
                        if (!ClipboardImagePayload.TryParse(imageBuf, out _, out _)) break;

                        ClipboardImageReceived?.Invoke(imageBuf, conn.Peer);
                        _ = SendToAllAsync(MessageType.ClipboardImage, imageBuf, exceptDeviceId: conn.Peer.DeviceId);
                        break;
                    }

                    case MessageType.FileMeta:
                    {
                        byte[] metaBuf = new byte[header.PayloadLength];
                        await stream.ReadExactlyAsync(metaBuf, ct);

                        if (!TryParseFileMeta(metaBuf, out string fileName, out long fileSize, out string sha256))
                        {
                            throw new InvalidDataException("Malformed FILE_META");
                        }

                        // A new file before the previous one finished means the sender gave up on it
                        AbortIncoming(TransferStatus.Failed, "Interrupted by a new transfer");

                        var route = conn.PendingRoute;
                        conn.PendingRoute = null;

                        // Files only passing through to other phones don't go to the user's Downloads
                        string receiveDir = route is { KeepOnPc: false } ? RelayDirectory : DownloadDirectory;
                        Directory.CreateDirectory(receiveDir);
                        string tempPath = Path.Combine(receiveDir, $"{fileName}.{Guid.NewGuid():N}.crosssync.tmp");
                        var item = new TransferItem
                        {
                            FileName = fileName,
                            FileSizeBytes = fileSize,
                            Sha256 = sha256,
                            Direction = TransferDirection.Receiving,
                            DeviceId = conn.Peer.DeviceId,
                            DeviceName = conn.Peer.DeviceName,
                            Status = TransferStatus.InProgress,
                            LocalPath = tempPath
                        };
                        incoming = new IncomingTransfer
                        {
                            Item = item,
                            Stream = new FileStream(tempPath, FileMode.CreateNew, FileAccess.Write, FileShare.None),
                            TempPath = tempPath,
                            Route = route,
                            From = conn.Peer
                        };
                        FileTransferStarted?.Invoke(item);
                        break;
                    }

                    case MessageType.FileChunk:
                    {
                        if (incoming == null)
                        {
                            // Chunks of a cancelled/aborted file: consume them to stay in sync
                            await SkipPayloadAsync(stream, header.PayloadLength, ct);
                            break;
                        }

                        var cancelRequest = conn.CancelIncomingRequest;
                        if (cancelRequest != null && ReferenceEquals(cancelRequest, incoming.Item))
                        {
                            conn.CancelIncomingRequest = null;
                            await SkipPayloadAsync(stream, header.PayloadLength, ct);
                            await SendCancelAsync(conn, ProtocolConstants.CancelByReceiver, incoming.Item.Sha256, ct);
                            AbortIncoming(TransferStatus.Cancelled, null);
                            break;
                        }

                        byte[] chunkBuf = new byte[header.PayloadLength];
                        await stream.ReadExactlyAsync(chunkBuf, ct);

                        var item = incoming.Item;
                        if (item.BytesTransferred + chunkBuf.Length > item.FileSizeBytes)
                        {
                            AbortIncoming(TransferStatus.Failed, "Received more data than the announced file size");
                            break;
                        }

                        await incoming.Stream.WriteAsync(chunkBuf, ct);
                        item.BytesTransferred += chunkBuf.Length;
                        ReportProgress(incoming);

                        if ((header.Flags & ProtocolFlags.LastChunk) != 0)
                        {
                            await FinalizeIncomingAsync(incoming, ct);
                            incoming = null;
                        }
                        break;
                    }

                    case MessageType.FileRoute:
                    {
                        byte[] routeBuf = new byte[header.PayloadLength];
                        await stream.ReadExactlyAsync(routeBuf, ct);
                        try
                        {
                            conn.PendingRoute = JsonSerializer.Deserialize<FileRoute>(routeBuf, JsonOptions);
                        }
                        catch (JsonException)
                        {
                            conn.PendingRoute = null; // malformed: deliver to the PC as usual
                        }
                        break;
                    }

                    case MessageType.TransferCancel:
                    {
                        byte[] cancelBuf = new byte[header.PayloadLength];
                        await stream.ReadExactlyAsync(cancelBuf, ct);
                        if (cancelBuf.Length != ProtocolConstants.CancelPayloadBytes) break;

                        byte kind = cancelBuf[0];
                        string sha = Encoding.ASCII.GetString(cancelBuf, 1, 64);

                        if (kind == ProtocolConstants.CancelBySender &&
                            incoming != null && string.Equals(incoming.Item.Sha256, sha, StringComparison.OrdinalIgnoreCase))
                        {
                            AbortIncoming(TransferStatus.Cancelled, "Cancelled on phone");
                        }
                        else if (kind == ProtocolConstants.CancelByReceiver &&
                                 conn.OutgoingTransfer is { } outgoing &&
                                 string.Equals(outgoing.Sha256, sha, StringComparison.OrdinalIgnoreCase))
                        {
                            conn.CancelOutgoing = true;
                        }
                        break;
                    }

                    case MessageType.Ping:
                        await SendFrameAsync(conn, MessageType.Pong, ProtocolFlags.None, ReadOnlyMemory<byte>.Empty, ct);
                        break;

                    case MessageType.Pong:
                        // Heartbeat reply; lastReceivedTick already updated
                        break;

                    case MessageType.Disconnect:
                        return;

                    default:
                        // Unknown, or a repeated handshake: skip the payload to stay in sync
                        await SkipPayloadAsync(stream, header.PayloadLength, ct);
                        break;
                }
            }
        }
        finally
        {
            heartbeatCts.Cancel();
            try { await heartbeatTask; } catch { }
            AbortIncoming(TransferStatus.Failed, "Connection lost");
        }
    }

    private static bool TryParseFileMeta(byte[] buf, out string fileName, out long fileSize, out string sha256)
    {
        fileName = string.Empty;
        fileSize = 0;
        sha256 = string.Empty;

        // Payload: [nameLen:4][name:nameLen][size:8][sha256:64]
        if (buf.Length < 4) return false;
        int nameLen = BinaryPrimitives.ReadInt32BigEndian(buf.AsSpan(0, 4));
        if (nameLen < 0 || buf.Length != 4 + nameLen + 8 + 64) return false;

        fileName = SanitizeFileName(Encoding.UTF8.GetString(buf, 4, nameLen));
        fileSize = BinaryPrimitives.ReadInt64BigEndian(buf.AsSpan(4 + nameLen, 8));
        sha256 = Encoding.ASCII.GetString(buf, 4 + nameLen + 8, 64);
        return fileSize >= 0;
    }

    private void ReportProgress(IncomingTransfer incoming)
    {
        long now = Environment.TickCount64;
        long elapsed = now - incoming.LastProgressTick;
        if (elapsed < ProgressIntervalMs) return;

        var item = incoming.Item;
        item.SpeedBytesPerSec = (item.BytesTransferred - incoming.BytesAtLastProgress) * 1000 / Math.Max(elapsed, 1);
        incoming.BytesAtLastProgress = item.BytesTransferred;
        incoming.LastProgressTick = now;
        FileTransferProgress?.Invoke(item);
    }

    private async Task FinalizeIncomingAsync(IncomingTransfer incoming, CancellationToken ct)
    {
        var item = incoming.Item;
        await incoming.Stream.FlushAsync(ct);
        await incoming.Stream.DisposeAsync();

        var route = incoming.Route;
        bool keepOnPc = route?.KeepOnPc ?? true;

        bool valid = false;
        if (item.BytesTransferred == item.FileSizeBytes)
        {
            // A relay-only file keeps its own folder so its name can stay as sent
            string finalDir = keepOnPc
                ? DownloadDirectory
                : Directory.CreateDirectory(Path.Combine(RelayDirectory, Guid.NewGuid().ToString("N"))).FullName;
            string finalPath = GetUniqueFilePath(finalDir, item.FileName);
            valid = await ChecksumVerifier.VerifyAndFinalizeAsync(incoming.TempPath, finalPath, item.Sha256, ct);
            if (valid) item.LocalPath = finalPath;
        }

        if (valid && route != null && route.Targets.Count > 0)
        {
            _ = RelayFileAsync(item.LocalPath, route.Targets, incoming.From, deleteWhenDone: !keepOnPc);
        }

        if (!valid)
        {
            try { File.Delete(incoming.TempPath); } catch { }
            item.ErrorMessage = item.BytesTransferred == item.FileSizeBytes ? "Checksum mismatch" : "Incomplete file";
        }

        item.SpeedBytesPerSec = 0;
        item.Status = valid ? TransferStatus.Completed : TransferStatus.Failed;
        FileTransferCompleted?.Invoke(item, valid);
    }

    private static async Task SkipPayloadAsync(Stream stream, long length, CancellationToken ct)
    {
        if (length <= 0) return;
        byte[] discard = new byte[(int)Math.Min(length, 65536)];
        long remaining = length;
        while (remaining > 0)
        {
            int read = await stream.ReadAsync(discard.AsMemory(0, (int)Math.Min(remaining, discard.Length)), ct);
            if (read == 0) throw new EndOfStreamException();
            remaining -= read;
        }
    }

    // ── Phone-to-phone relay ─────────────────────────────────────────────────

    /// <summary>Holds files that are only passing through to other phones.</summary>
    private static string RelayDirectory => Path.Combine(AppSettings.DataDirectory, "relay");

    /// <summary>
    /// Forwards a received file to other phones. A target that is offline gets a short grace
    /// period to (re)connect. Relay-only copies are deleted once every target has been tried.
    /// </summary>
    private async Task RelayFileAsync(string path, IReadOnlyList<string> targetIds, DevicePeer from, bool deleteWhenDone)
    {
        try
        {
            var sends = targetIds
                .Where(id => id != from.DeviceId)
                .Distinct()
                .Select(async id =>
                {
                    var peer = await WaitForDeviceAsync(id);
                    if (peer == null)
                    {
                        string name = _settings.GetPairedDevices().FirstOrDefault(d => d.DeviceId == id)?.Name ?? "Phone";
                        var failed = new TransferItem
                        {
                            FileName = Path.GetFileName(path),
                            FileSizeBytes = new FileInfo(path).Length,
                            Direction = TransferDirection.Sending,
                            DeviceId = id,
                            DeviceName = name,
                            Status = TransferStatus.Failed,
                            ErrorMessage = $"{name} isn't connected (relay from {from.DeviceName})",
                            LocalPath = path
                        };
                        FileTransferStarted?.Invoke(failed);
                        FileTransferCompleted?.Invoke(failed, false);
                        return;
                    }
                    await SendFileAsync(path, peer);
                });
            await Task.WhenAll(sends);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Relay of {path} failed: {ex.Message}");
        }
        finally
        {
            if (deleteWhenDone)
            {
                try
                {
                    File.Delete(path);
                    Directory.Delete(Path.GetDirectoryName(path)!); // its own per-file folder
                }
                catch { }
            }
        }
    }

    private async Task<DevicePeer?> WaitForDeviceAsync(string deviceId)
    {
        for (int waited = 0; waited <= RelayConnectWaitMs; waited += 500)
        {
            if (_clients.TryGetValue(deviceId, out var conn) && !conn.Closed) return conn.Peer;
            await Task.Delay(500);
        }
        return null;
    }

    /// <summary>
    /// Tells every phone which other phones are connected, so their "send to" pickers stay
    /// current. Called whenever a phone connects or disconnects.
    /// </summary>
    private async Task BroadcastDeviceListAsync()
    {
        var all = _clients.Values.ToList();
        await Task.WhenAll(all.Select(async conn =>
        {
            var others = all
                .Where(c => c.Peer.DeviceId != conn.Peer.DeviceId)
                .Select(c => new DeviceListEntry(c.Peer.DeviceId, c.Peer.DeviceName))
                .ToList();
            try
            {
                await SendFrameAsync(conn, MessageType.DeviceList, ProtocolFlags.None,
                    JsonSerializer.SerializeToUtf8Bytes(others, JsonOptions), CancellationToken.None);
            }
            catch (Exception ex) when (ex is IOException or ObjectDisposedException)
            {
                conn.Close();
            }
        }));
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    /// <summary>
    /// Writes one frame to one phone under that connection's send lock. Each frame is locked
    /// separately, so clipboard text and heartbeats can go out between the chunks of a large file.
    /// </summary>
    private static async Task SendFrameAsync(ClientConnection conn, MessageType type, ProtocolFlags flags, ReadOnlyMemory<byte> payload, CancellationToken ct)
    {
        byte[] frame = PacketHeader.BuildFrame(type, flags, payload.Span);
        await conn.SendLock.WaitAsync(ct);
        try
        {
            if (conn.Closed) throw new IOException("Connection is no longer active.");
            await conn.Stream.WriteAsync(frame, ct);
            await conn.Stream.FlushAsync(ct);
        }
        finally
        {
            conn.SendLock.Release();
        }
    }

    private static Task SendCancelAsync(ClientConnection conn, byte kind, string sha256, CancellationToken ct)
    {
        byte[] payload = new byte[ProtocolConstants.CancelPayloadBytes];
        payload[0] = kind;
        Encoding.ASCII.GetBytes(sha256.PadRight(64).Substring(0, 64)).CopyTo(payload, 1);
        return SendFrameAsync(conn, MessageType.TransferCancel, ProtocolFlags.None, payload, ct);
    }

    /// <summary>
    /// Sends clipboard text to every connected phone except <paramref name="exceptDeviceId"/>
    /// (the phone it came from). A phone whose connection fails is dropped; it reconnects.
    /// </summary>
    public Task SendClipboardTextAsync(string text, string? exceptDeviceId = null, CancellationToken ct = default)
    {
        byte[] textBytes = Encoding.UTF8.GetBytes(text);
        if (textBytes.Length > ProtocolConstants.MaxClipboardTextBytes) return Task.CompletedTask; // receivers would reject it
        return SendToAllAsync(MessageType.ClipboardText, textBytes, exceptDeviceId, ct);
    }

    /// <summary>Sends a copied image (e.g. PNG bytes) to every connected phone.</summary>
    public Task SendClipboardImageAsync(string mime, byte[] image, CancellationToken ct = default)
    {
        if (image.Length > ProtocolConstants.MaxClipboardImageBytes) return Task.CompletedTask;
        return SendToAllAsync(MessageType.ClipboardImage, ClipboardImagePayload.Build(mime, image), null, ct);
    }

    /// <summary>
    /// Sends one frame to every connected phone except <paramref name="exceptDeviceId"/>
    /// (the phone it came from). A phone whose connection fails is dropped; it reconnects.
    /// </summary>
    private async Task SendToAllAsync(MessageType type, byte[] payload, string? exceptDeviceId, CancellationToken ct = default)
    {
        var targets = _clients.Values.Where(c => c.Peer.DeviceId != exceptDeviceId).ToList();
        await Task.WhenAll(targets.Select(async conn =>
        {
            try
            {
                await SendFrameAsync(conn, type, ProtocolFlags.None, payload, ct);
            }
            catch (Exception ex) when (ex is IOException or ObjectDisposedException)
            {
                System.Diagnostics.Debug.WriteLine($"Send {type} to {conn.Peer.DeviceName} failed: {ex.Message}");
                conn.Close(); // the stream may be left mid-frame
            }
        }));
    }

    /// <summary>
    /// Sends a file to one phone. Never throws for transfer failures: the outcome is reported on
    /// the TransferItem. Throws only if the file doesn't exist.
    /// </summary>
    public async Task SendFileAsync(string filePath, DevicePeer target, CancellationToken ct = default)
    {
        var fileInfo = new FileInfo(filePath);
        if (!fileInfo.Exists) throw new FileNotFoundException("File not found.", filePath);

        var transfer = new TransferItem
        {
            FileName = fileInfo.Name,
            FileSizeBytes = fileInfo.Length,
            Direction = TransferDirection.Sending,
            DeviceId = target.DeviceId,
            DeviceName = target.DeviceName,
            Status = TransferStatus.Pending,
            LocalPath = filePath
        };
        FileTransferStarted?.Invoke(transfer);

        if (!_clients.TryGetValue(target.DeviceId, out var conn))
        {
            transfer.Status = TransferStatus.Failed;
            transfer.ErrorMessage = $"{target.DeviceName} is not connected";
            FileTransferCompleted?.Invoke(transfer, false);
            return;
        }

        await conn.FileSendLock.WaitAsync(ct);
        try
        {
            if (transfer.Status == TransferStatus.Cancelled) return; // cancelled while queued
            if (conn.Closed) throw new IOException($"{target.DeviceName} disconnected");

            transfer.Status = TransferStatus.InProgress;
            transfer.Sha256 = await ChecksumVerifier.ComputeSha256Async(filePath, ct);
            conn.CancelOutgoing = false;
            conn.OutgoingTransfer = transfer;

            byte[] nameBytes = Encoding.UTF8.GetBytes(fileInfo.Name);
            byte[] metaPayload = new byte[4 + nameBytes.Length + 8 + 64];
            BinaryPrimitives.WriteInt32BigEndian(metaPayload.AsSpan(0, 4), nameBytes.Length);
            nameBytes.CopyTo(metaPayload, 4);
            BinaryPrimitives.WriteInt64BigEndian(metaPayload.AsSpan(4 + nameBytes.Length, 8), fileInfo.Length);
            Encoding.ASCII.GetBytes(transfer.Sha256).CopyTo(metaPayload, 4 + nameBytes.Length + 8);
            await SendFrameAsync(conn, MessageType.FileMeta, ProtocolFlags.None, metaPayload, ct);

            // Empty file: the loop below never runs, so send a single empty LAST_CHUNK
            // or the receiver waits forever with its .tmp file open.
            if (fileInfo.Length == 0)
            {
                await SendFrameAsync(conn, MessageType.FileChunk, ProtocolFlags.LastChunk, ReadOnlyMemory<byte>.Empty, ct);
            }

            byte[] buffer = new byte[ProtocolConstants.ChunkSize];
            long bytesSent = 0;
            long lastTick = Environment.TickCount64;
            long bytesAtLastTick = 0;
            await using var fileStream = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read);

            while (bytesSent < fileInfo.Length)
            {
                int read = await fileStream.ReadAsync(buffer.AsMemory(0, (int)Math.Min(buffer.Length, fileInfo.Length - bytesSent)), ct);
                if (read == 0) break;

                if (conn.CancelOutgoing || transfer.Status == TransferStatus.Cancelled)
                {
                    await SendCancelAsync(conn, ProtocolConstants.CancelBySender, transfer.Sha256, ct);
                    transfer.Status = TransferStatus.Cancelled;
                    return;
                }

                bytesSent += read;
                var flags = bytesSent >= fileInfo.Length ? ProtocolFlags.LastChunk : ProtocolFlags.None;
                await SendFrameAsync(conn, MessageType.FileChunk, flags, buffer.AsMemory(0, read), ct);
                transfer.BytesTransferred = bytesSent;

                long now = Environment.TickCount64;
                if (now - lastTick >= ProgressIntervalMs)
                {
                    transfer.SpeedBytesPerSec = (bytesSent - bytesAtLastTick) * 1000 / Math.Max(now - lastTick, 1);
                    bytesAtLastTick = bytesSent;
                    lastTick = now;
                    FileTransferProgress?.Invoke(transfer);
                }
            }

            if (bytesSent < fileInfo.Length)
            {
                // File shrank while sending: tell the phone to discard it
                await SendCancelAsync(conn, ProtocolConstants.CancelBySender, transfer.Sha256, ct);
                transfer.Status = TransferStatus.Failed;
                transfer.ErrorMessage = "File changed while sending";
                return;
            }

            transfer.Status = TransferStatus.Completed;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"SendFile error: {ex.Message}");
            transfer.Status = TransferStatus.Failed;
            transfer.ErrorMessage = ex.Message;
            if (ex is not OperationCanceledException)
            {
                conn.Close(); // the stream may be left mid-frame; the phone reconnects
            }
        }
        finally
        {
            conn.OutgoingTransfer = null;
            conn.FileSendLock.Release();
            transfer.SpeedBytesPerSec = 0;
            FileTransferCompleted?.Invoke(transfer, transfer.Status == TransferStatus.Completed);
        }
    }

    /// <summary>Cancels a queued or running transfer in either direction.</summary>
    public void CancelTransfer(TransferItem item)
    {
        if (item.Status == TransferStatus.Pending)
        {
            item.Status = TransferStatus.Cancelled;
            return;
        }
        if (!_clients.TryGetValue(item.DeviceId, out var conn)) return;

        if (ReferenceEquals(item, conn.OutgoingTransfer))
        {
            conn.CancelOutgoing = true;
        }
        else if (item.Direction == TransferDirection.Receiving && item.Status == TransferStatus.InProgress)
        {
            // Applied by that phone's receive loop on the next chunk
            conn.CancelIncomingRequest = item;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /// <summary>
    /// Reduces a peer-supplied name to a bare file name so it can't escape DownloadDirectory
    /// (e.g. "..\..\Startup\x.exe" or an absolute path).
    /// </summary>
    private static string SanitizeFileName(string name)
    {
        name = name.Replace('/', '\\');
        name = Path.GetFileName(name);
        foreach (char c in Path.GetInvalidFileNameChars())
        {
            name = name.Replace(c, '_');
        }
        name = name.Trim().TrimEnd('.');
        return string.IsNullOrEmpty(name) ? $"file_{DateTime.Now:yyyyMMdd_HHmmss}" : name;
    }

    /// <summary>Returns "name.ext", or "name (1).ext" etc. if that already exists, so nothing is overwritten.</summary>
    private static string GetUniqueFilePath(string directory, string fileName)
    {
        string path = Path.Combine(directory, fileName);
        if (!File.Exists(path)) return path;

        string stem = Path.GetFileNameWithoutExtension(fileName);
        string ext = Path.GetExtension(fileName);
        for (int i = 1; ; i++)
        {
            path = Path.Combine(directory, $"{stem} ({i}){ext}");
            if (!File.Exists(path)) return path;
        }
    }

    private void CleanupStaleTempFiles()
    {
        // Relay copies left by a crash or exit mid-relay
        try
        {
            if (Directory.Exists(RelayDirectory)) Directory.Delete(RelayDirectory, recursive: true);
        }
        catch { }

        foreach (var file in Directory.EnumerateFiles(DownloadDirectory, "*.crosssync.tmp"))
        {
            try
            {
                if (DateTime.Now - File.GetLastWriteTime(file) > TimeSpan.FromDays(1))
                    File.Delete(file);
            }
            catch { }
        }
    }

    public void Stop()
    {
        _cts?.Cancel();
        foreach (var conn in _clients.Values)
        {
            conn.Close();
        }
        _clients.Clear();
        _tcpListener?.Stop();
        _tcpListener = null;
        _cts?.Dispose();
        _cts = null;
    }

    public void Dispose()
    {
        Stop();
        _certificate.Dispose();
        GC.SuppressFinalize(this);
    }
}
