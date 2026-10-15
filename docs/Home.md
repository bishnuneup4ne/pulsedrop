# PulseDrop

**Local-network file drop, chat and video calls — your phone is the server.**

PulseDrop turns an Android phone into a web server for the devices around it. Any laptop,
tablet or phone on the same Wi-Fi/hotspot opens a browser address (or scans the QR code) and
gets a full client: upload and download files, chat with attachments, join voice/video calls
with screen sharing — no internet, no accounts, no third-party servers.

## Why

Cloud drop services need upstream bandwidth, accounts and trust. On a hotspot, shared screen,
or offline LAN, PulseDrop is faster and private by default: data never leaves the network, and
device pairing is end-to-end encrypted.

## Feature overview

| Area | What it does |
| --- | --- |
| File drop | Drag-drop or pick files in the browser; multipart upload with live progress; thumbnails and inline previews |
| Chat | Real-time chat between paired devices and browser tabs, with attachments and typing indicators |
| Calls | Voice and video calls, screen sharing, and a conference bridge mixing every participant |
| Browser client | Full responsive web UI served by the phone — upload, files, chats, history and details views |
| Pairing | QR/NFC-style handshake with ECDH key agreement and an out-of-band safety code |
| Security | Optional 4-digit PIN with per-IP lockout, AES-GCM upload proofs, self-signed LAN TLS |

## Quick start

1. Install the app — see [Installation](Installation.md).
2. Start the server and connect other devices to the same hotspot/Wi-Fi.
3. Open the address the card shows or scan the QR (browsers are bounced to the secure
   port automatically) — see [Browser Calls (HTTPS)](Browser-Calls-HTTPS.md).
4. Hit a problem? [Troubleshooting](Troubleshooting.md).

## Documentation

- [Installation](Installation.md) — sideloading the APK, signing, Play Protect notes
- [Usage](Usage.md) — server, files, chat, radar, history, calls
- [Browser Calls (HTTPS)](Browser-Calls-HTTPS.md) — why the secure address matters, cert warning
- [Security Model](Security-Model.md) — PIN, pairing crypto, upload proofs, backups
- [HTTP API](HTTP-API.md) — every endpoint the phone serves
- [Building from Source](Building-from-Source.md) — toolchain, Gradle, release signing
- [Troubleshooting](Troubleshooting.md) — install errors, calls, dataSync quota

## Project facts

- Package: `com.pulsedrop.app` · current release: **v1.1.2** (versionName 1.1, versionCode 3)
- minSdk 24 / targetSdk 36 · Kotlin + Jetpack Compose · NanoHTTPD embedded server
- License: PolyForm Noncommercial 1.0.0 (see `LICENSE` in the repository)
- Source: https://github.com/bishnuneup4ne/pulsedrop
- Releases: https://github.com/bishnuneup4ne/pulsedrop/releases
