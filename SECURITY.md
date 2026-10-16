# Security policy

CrossSync handles your clipboard and files and runs an accessibility service on Android, so security reports are taken seriously.

## Reporting a vulnerability

**Please don't open a public issue for security problems.** Report them privately through GitHub instead:

1. Go to the repository's **Security** tab.
2. Click **Report a vulnerability**.

Include:
- what an attacker can do, and the conditions they need (for example: on the same Wi-Fi network, unpaired);
- steps to reproduce, or a proof of concept;
- the CrossSync version, Windows version, phone model and Android version.

You should get a reply within a week. Once a fix is released, the report can be made public, with credit to you if you want it.

## Supported versions

Only the [latest release](https://github.com/Sanket-Baviskar/CrossSync/releases/latest) receives security fixes.

## Scope

In scope:
- the network protocol, pairing and TLS: anything that lets an unpaired device connect, read or inject clipboard data, or send files;
- the PC's file receiving, including path traversal and overwriting files;
- the Android accessibility service, share target and file handling.

Out of scope:
- attacks that need control of the PC or phone themselves (malware already on the device, physical access to an unlocked device);
- the SmartScreen warning on the unsigned Windows exe (known, see the README).

How CrossSync protects your data is described in [Security model](README.md#security-model) and [docs/PROTOCOL.md](docs/PROTOCOL.md).
