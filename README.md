# PulseDrop

High-speed file sharing and communication over your local network — no servers, no accounts,
no cloud. PulseDrop runs an embedded HTTP server on your phone; any device on the same Wi-Fi
or hotspot can then send files, chat, and start a call with screen sharing straight from a
browser. Two PulseDrop phones can additionally pair end-to-end encrypted, so traffic between
them is AES-GCM sealed even while it crosses the LAN.

Source-available and free to use for anything non-commercial, under the PolyForm
Noncommercial License 1.0.0.

## What it does

| Screen | Purpose |
| --- | --- |
| **Server** | Starts the LAN server (NanoHTTPD on port 8080) as a foreground service, shows the URL and a QR code to open it, and gates every browser client behind a 4-digit PIN. |
| **Radar** | Discovers other PulseDrop devices and web clients on the local subnet, and pairs two phones with an ECDH key exchange plus a displayed safety code. |
| **Chat** | Threads with the paired device or browser clients: text, typing indicators, file attachments, voice calls, and screen sharing. |
| **History** | Room-backed log of every send and receive, filterable by direction. |
| **Hotspot** | Step-by-step setup for when there is no Wi-Fi — host a hotspot on the phone and let the other device join it. |

Files shared from any app land in `WebDrop_Received/` in the app's external storage; PulseDrop
also appears in the system share sheet (`SEND` / `SEND_MULTIPLE`).

## Requirements

- **Building:** an Android SDK providing `compileSdk` 36, and any JDK 21+ to launch the wrapper.
  The Gradle daemon is pinned to JDK 25 in `gradle/gradle-daemon-jvm.properties`; Gradle 9.3.1
  and the JDK are fetched for you (Foojay resolver), so no local install of either is required.
- **Running:** Android 7.0 (API 24) or newer. Camera and microphone are optional — the app is
  useful for file transfer without either.
- **Clients:** any modern browser on the same network. No install required.

## Build

```bash
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk, or
                              # app-release-unsigned.apk when no keystore is configured
./gradlew bundleRelease       # app/build/outputs/bundle/release/app-release.aab
./gradlew test                # unit tests
./gradlew lintRelease         # release lint
```

`local.properties` must point at your Android SDK (`sdk.dir=/path/to/Android/Sdk`); Android
Studio writes it automatically, otherwise create it — it is git-ignored.

### Release signing

Signing is opt-in so that a fresh clone always builds. `assembleRelease` reads, in this order,
environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and then
`signing.properties` in the project root; relative keystore paths resolve against the project
root. **If no keystore is present the release is produced unsigned instead of failing.**
Keystores and `signing.properties` are git-ignored — never commit them.

Debug builds use AGP's built-in signing config, which generates `~/.android/debug.keystore`
on demand.

## Architecture

```
app/src/main/java/com/pulsedrop/app/
  server/     PulseDropServer (NanoHTTPD request routing), ServerService (foreground
              service + notification), WebClientHtml (the browser UI, served inline)
  security/   Crypto (EC P-256 identity -> ECDH -> AES-256-GCM), PairManager (pairing state)
  call/       CallEngine (invite/ring/accept + media frames), ProjectionCaptureService
              (MediaProjection -> frame source for screen share)
  transfer/   TransferManager (upload/download jobs, progress, Room history rows)
  discovery/  LanScanner, PeerDiscoveryManager (subnet sweep + multicast)
  data/       Room database, TransferHistoryDao
  ui/         Compose screens and components, MainViewModel
```

### HTTP surface

The phone is the server; browsers are the clients. `GET /` serves the web UI, and the API is
polled rather than pushed (no WebSocket dependency), which keeps the client usable from any
browser without a service worker:

```
GET  /                      web UI
GET  /api/info              device name, PIN requirement, server stats
POST /api/auth              PIN check -> token
GET  /api/files             browse the received-file list
GET  /download/{id}         fetch a file        GET /preview/{id}  thumbnail/inline view
POST /upload                multipart upload    POST /api/delete   remove a file
GET|POST /api/messages      chat poll / send    GET  /api/typing   typing indicator
POST /chatupload            chat attachment     GET  /chatfile/{id}
POST /api/pair/*            pairing handshake   GET  /api/peers    discovered peers
POST /api/call/*            call signalling     POST /api/call/web browser-side signalling
GET  /api/webcall/down      browser media downlink (chunked frames)
POST /api/webcall/up        browser media uplink (batched frames)
```

### Security model

- **Transport:** cleartext HTTP over the LAN. `usesCleartextTraffic` is on deliberately — this
  is a local-network tool with no certificate authority available to it. Threat model is a
  trusted Wi-Fi/hotspot, not a hostile network. A TLS side-listener also runs on the HTTP
  port + 1 (8081 by default) with a process-lifetime self-signed certificate: browsers only
  expose `getUserMedia`/`getDisplayMedia` on a secure origin, so voice/video/screen-share
  calls with a PC require the `https://` address shown in the app (accept the certificate
  warning once per browser).
- **PIN:** 4 digits, enforced per client IP. Eight *distinct* wrong values trigger a 60 s
  lockout that doubles on further bursts (capped at ~16 min). A stale token repeated by an old
  browser tab counts once, not once per poll.
- **Pairing:** each device holds an EC P-256 identity in `AndroidKeyStore` (raw-key prefs
  fallback for devices without it). ECDH output is folded as
  `sha256(secret) || sha256(ordered SPKI) || "LocalDrop-v1"`, hashed to the AES-256-GCM key.
  Both devices derive the same key, and both show a safety code from the first four hash bytes
  — compare them out of band to detect an interception.
- **Upload proof:** a paired browser upload carries an AES-GCM blob over
  `pulsedrop-upload-v1|pairId|contentLength|issuedAt|nonce`. The server checks the key holder,
  the multipart body length, a 120 s TTL, and rejects a replayed nonce.
- **Backups:** `backup_rules.xml` and `data_extraction_rules.xml` exclude the E2E identity key,
  the pairing store and the PIN, so the pairing secret does not migrate through cloud backup or
  device transfer.
- **Foreground service:** the LAN server runs as a `dataSync` service. Android 15+ enforces a
  ~6 h per 24 h dataSync quota; when that hits, the service stops the HTTP server too and the
  UI reports the server as off rather than serving from a dying background process.

### Wire compatibility

Some identifiers carry the project's earlier names, and renaming them breaks pairing, backups and
history with existing installs. They stay as they are: the `appKind` wire value, the
`LocalDrop-v1` ECDH salt, the `localdrop_e2e_identity` keystore alias, the `localdrop_*`
preference files and the `WebDrop_Received` / `WebDrop_Chat` storage directories. They are
protocol and storage identifiers, not the product name.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) first — branch layout, code style, and what CI expects.
Security vulnerabilities are handled off-list; see [SECURITY.md](SECURITY.md). Contributors are
expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

By contributing you agree that your contributions are licensed under the same terms as the
rest of the project — the PolyForm Noncommercial License 1.0.0.

## License

Copyright (c) 2026 The PulseDrop authors

PulseDrop is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE). In plain
terms: you may read it, run it, change it, build your own version and share it with anyone —
as long as none of that is commercial. Selling PulseDrop, a build of it, or a hosted service
running it for pay is not allowed without a separate agreement from the authors.

Every copy you distribute has to carry the licence text and this notice:

```
Required Notice: Copyright (c) 2026 The PulseDrop authors
```

This is not an open-source licence in the OSI sense — it restricts commercial use on purpose.

### Third-party terms that travel with the app

The licence above covers PulseDrop's own source. Bundled components keep their own terms and
are **not** relicensed:

| Component | Licence | Where it sits |
| --- | --- | --- |
| Google Sans (4 weights) | SIL Open Font License 1.1 | `app/src/main/res/font/`, text at `app/src/main/assets/fonts/GOOGLE_SANS_OFL.txt` |
| AndroidX / Compose / Material | Apache-2.0 | Gradle dependencies |
| OkHttp | Apache-2.0 | Gradle dependency |
| NanoHTTPD | BSD-3-Clause | Gradle dependency |
| Bouncy Castle (bcpkix) | MIT | Gradle dependency — self-signed LAN TLS for browser calls |
| ZXing core | Apache-2.0 | Gradle dependency |
| Coil | Apache-2.0 | Gradle dependency |
| Room / KSP | Apache-2.0 | Gradle dependencies |

The OFL is viral in one specific way worth knowing: the font files themselves may be sold or
used commercially by anyone, but only as fonts — you cannot take them out of the app and
relicense them, and any modified font must be renamed. If you fork PulseDrop for a commercial
product, drop the Google Sans assets and swap in another family.
