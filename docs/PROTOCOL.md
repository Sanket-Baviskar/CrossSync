# CrossSync wire protocol (version 2)

The Windows app is the **hub**: it listens for connections, and every phone connects to it.
Phone ↔ phone traffic (clipboard, files) is relayed by the PC.

| Item | Value |
|---|---|
| Transport | TLS over TCP, port **45678** |
| Discovery | UDP broadcast on port **45679** |
| Byte order | Big-endian |
| Implementations | `windows/src/ClipWindows/Protocol/` and `android/app/src/main/java/com/crosssync/app/protocol/` |

---

## Discovery

The phone broadcasts the ASCII string `CROSSSYNC_DISCOVER` to UDP port 45679.
Each PC running CrossSync replies with its machine name, so the phone learns the PC's name and IP.
Users can also type the PC's IP address by hand.

## Transport security and pairing

- The PC creates a self-signed RSA-2048 certificate on first run and keeps it across restarts
  (`%AppData%\CrossSync\server.pfx`). It is never shipped with the app.
- The phone pins the PC by the certificate's **SHA-256 fingerprint**. It shows the first 128 bits
  as 8 groups of 4 hex characters, and the PC displays the same value so the user can compare.
- **First connection:** the PC shows a 6-digit, single-use pairing code. The user types it on the
  phone. A new code is generated after each use and after 5 wrong attempts.
- **After pairing:** the PC issues a random token to the phone.
  - The PC stores only the token's SHA-256 hash, per device.
  - The phone stores the token per PC fingerprint, so pairing with one PC doesn't affect another.
- The PC throttles an IP address after 10 failed handshakes within 10 seconds (blocked for 30 s)
  and allows at most 8 handshakes in progress at once.

## Frame format

Every message is a 17-byte header followed by `length` bytes of payload.

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | Magic `CRSY` (`43 52 53 59`) |
| 4 | 1 | Message type |
| 5 | 1 | Flags: `0x01` LAST_CHUNK, `0x02` COMPRESSED (reserved) |
| 6 | 8 | Payload length (int64) |
| 14 | 3 | Reserved, must be 0 |

The receiver checks the length against a per-type limit before reading the payload. A frame over
the limit closes the connection. Unknown message types are skipped.

## Message types

| Type | Name | Payload | Max size |
|---|---|---|---|
| `0x01` | HANDSHAKE | JSON, see below | 64 KB |
| `0x02` | CLIPBOARD_TEXT | UTF-8 text | 16 MB |
| `0x03` | CLIPBOARD_IMAGE | `[mimeLen:1][mime][image bytes]` | 20 MB of image data + header |
| `0x04` | FILE_META | `[nameLen:4][name UTF-8][size:8][sha256 hex:64]` | 64 KB |
| `0x05` | FILE_CHUNK | Raw file bytes. The last chunk has the LAST_CHUNK flag. | 64 KB |
| `0x0A` | TRANSFER_CANCEL | `[kind:1][sha256 hex:64]`. Kind 0 = sender gave up; 1 = receiver asks sender to stop. | 65 B |
| `0x0B` | PING | — | 0 |
| `0x0C` | PONG | — | 0 |
| `0x0D` | DISCONNECT | — | 0 |
| `0x0E` | DEVICE_LIST | JSON `[{"id": "...", "name": "..."}]` listing the *other* connected phones | 64 KB |
| `0x0F` | FILE_ROUTE | JSON `{"pc": bool, "to": ["deviceId", ...]}`, sent just before FILE_META | 64 KB |

`0x06`–`0x09` (ACK, NACK, PAUSE, RESUME) are reserved and currently unused.

### Handshake

Phone → PC:

```json
{ "name": "Pixel 8", "deviceId": "uuid", "version": 2, "token": "..." }
```

The phone sends `pairingCode` instead of `token` when it doesn't have a token yet.

PC → phone:

```json
{ "accepted": true, "name": "DESKTOP-PC", "version": 2, "token": "issued on first pairing" }
```

When the PC rejects the phone, it sends `"accepted": false` and a `reason`. The reasons are:

| `reason` | Meaning |
|---|---|
| `pairing_required` | The phone has no valid token and sent no code. |
| `bad_code` | The pairing code was wrong. |
| `unsupported_version` | The phone speaks a different protocol version. |

### File transfer

1. The sender sends FILE_META. When the file goes through the PC to other phones, the sender
   first sends FILE_ROUTE.
2. The sender sends FILE_CHUNKs of up to 64 KB each. The last one has LAST_CHUNK set. An empty file
   is a single empty chunk with LAST_CHUNK set.
3. The receiver checks the SHA-256 against FILE_META before keeping the file.
4. Either side can abort with TRANSFER_CANCEL.

The receiver cleans up file names:
- Paths are stripped, so `../../x` becomes `x`.
- A name that's already taken gets a number: `name (1).ext`.

### Keep-alive

The PC pings every connected phone every 20 seconds and drops a connection that has been silent
for 60 seconds. The phone uses a 60-second read timeout and reconnects with backoff
(2 s, then doubling, up to 30 s).
