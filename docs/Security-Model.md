# Security Model

PulseDrop's threat model is a **trusted local network** (home Wi-Fi, your own hotspot) —
not a hostile one. Everything below is defense-in-depth for that assumption, and the
"what this does not protect against" section at the end is the honest edge of it.

## Transport

- **HTTP** on the LAN port for pairing and app-to-app traffic (cleartext by design;
  `usesCleartextTraffic` is deliberate — there is no CA available to a phone).
- **HTTPS** on port + 1 for the browser UI, using a process-lifetime self-signed
  certificate (RSA-2048, SHA256withRSA, LAN IP in the SAN list). Browsers gate
  mic/camera/screen capture behind secure origins, so this port is what makes
  browser calls possible at all.

Pairing runs over cleartext HTTP. Its security does not come from the transport; it comes
from ECDH plus the safety code you read aloud.

## PIN

- 4 digits, optional, enforced **per client IP**.
- Eight distinct wrong values → 60 s lockout, doubling on further bursts (capped ~16 min).
- A stale token repeated by an old tab counts once, not once per poll.
- Toggled in **Settings** → "Require PIN in browsers", which shows the current PIN and a
  "New PIN" action. A custom-PIN editor exists in the code but is not reachable from any
  screen; regenerate is the only path today.
- **A browser that logs in gets a session, not the PIN.** 256 random bits, stored only as a
  SHA-256, expiring after 12 hours, revocable one at a time (`/api/auth/logout`) or all at once
  (regenerating the PIN). Nothing in a tab's `localStorage` is the room secret any more, and the
  server no longer accepts the PIN in a `?auth=` query string — which is where it was reaching
  browser history, link markup and `Referer`.
- What a session does *not* buy: it is not end-to-end encryption, and it says nothing about who is
  on the network. Anyone who can read the LAN can read the traffic a session authorises, because
  the transport is a documented trade-off (above), not a secret.

## Device pairing (E2E)

- Each device holds an **EC P-256 identity** in `AndroidKeyStore` (raw-key prefs
  fallback where unavailable).
- Pairing runs ECDH. The shared point is turned into an AES-256-GCM key by one of two
  derivations, and the secret is **never stored** — it is re-derived at runtime, which is
  why a pairing survives an app update but not a key change.
- Both devices display a **6-digit safety code** — `"%06d"` of the first four bytes of the
  derived material, shown chunked as `481 920`. Compare it out of band to detect
  interception. It is never typed, which is what makes it a check rather than a shared
  secret.

### Key derivation: v1 and v2, both live

Two derivations coexist. Which one a pair uses is decided **once, by the accepting device**,
from the version strings both sides report, and then frozen for the life of that pair.

| Condition at pairing time | Derivation |
| --- | --- |
| Both ends report 2.0.0 or newer | **v2** |
| Either end is older, or silent about its version | **v1** |

**v1** — the original, frozen like a wire format:

```
key = SHA-256( SHA-256(ecdhSecret) ‖ SHA-256(orderedSpki) ‖ "LocalDrop-v1" )
```

**v2** — HKDF-SHA256 (RFC 5869), one extract and one 32-byte expand block:

```
PRK = HMAC-SHA-256(key = SHA-256(orderedSpki), data = ecdhSecret)
key = HMAC-SHA-256(key = PRK, data = "pulsedrop-kdf-v2|pair" ‖ 0x01)
```

`orderedSpki` is the two public keys' SPKI encodings concatenated in unsigned byte order, so
both ends reach the same order without negotiating one.

v2 replaces a hand-rolled concat-hash with a standard KDF and adds domain separation, so the
same ECDH output cannot be reused as a key for some other purpose. It is **not** negotiated by
the `kdf-v2` capability string, which exists in code but is never announced — the gate is the
version comparison.

Existing pairs keep v1 forever even after both phones upgrade. Re-deriving under a live
pairing would make every previously exchanged message unreadable, so per-device key lists
legitimately mix both versions.

### Why 1.7 could never reach v2

Worth recording, because it is the reason version strings are treated as load-bearing here:
1.7 announced `2.0.0` to the network while its own build number stayed `1.7`, and the gate
asks whether *both* ends are 2.0 or newer — reading one answer from the network and the other
from the build number. Every new pair silently fell back to v1. Both answers now come from
`BuildConfig.VERSION_NAME`, and `/api/info` reads the same constant, so the two
self-descriptions cannot drift apart again.

## Relay: a postbox, not a server

A phone that pairs with a hub but runs no server sends its mail through the host. Every
envelope is sealed with the *recipient's* pairwise AES-GCM key before it leaves, and the
host stores only the ciphertext plus a destination device id — being the postbox does not
make it a party to the conversation. Pulling and posting are authorized by a signed proof
token bound to one pairing **and one role**
(`pulsedrop-relay-v1|pid|role|deviceId|issuedAt|nonce`), so a "post" capability can never
empty somebody else's mailbox. Mail is held until the addressee acknowledges it (a crashed
poll simply re-fetches), and a device that never returns is forgotten after a day.

## Upload proof

A paired browser upload carries an AES-GCM blob over
`pulsedrop-upload-v1|pairId|contentLength|issuedAt|nonce`. The server checks the key
holder, the multipart body length, a 120 s TTL, and rejects replayed nonces.

## What is encrypted, and where

| | On the wire | At rest on the phone |
| --- | --- | --- |
| Private/group chat between paired phones | **Yes** — AES-GCM per pair | **No** — plaintext in the app database |
| The open LAN room | Cleartext HTTP, or TLS to a browser tab | Not stored on the phone; the browser's view is in memory |
| File transfers | LAN TLS for browsers, cleartext TCP for app pushes | Files are written unencrypted |
| Calls | **No** — media is not end-to-end encrypted | Not stored |
| Pairing handshake | ECDH over cleartext, verified by the safety code | Identity key in `AndroidKeyStore` |

The `encrypted` flag on a stored chat message records that it *travelled* end-to-end
encrypted. It does not mean the row is ciphertext — decrypted text is written as plaintext so
it can be full-text searched. See [Chat and History](Chat-and-History.md).

## Backups

`backup_rules.xml` and `data_extraction_rules.xml` exclude the E2E identity key, the
pairing store and the PIN, so pairing secrets never migrate through cloud backup or device
transfer.

The chat database (`webdrop_database`, plus its `-wal` and `-shm` sidecars) is excluded from
both `cloud-backup` and `device-transfer` for the same reason: message text is private
between two peers, and copying it into a Google account the other peer never agreed to would
undo the part of end-to-end encryption that still matters once the message is stored. The
cost is real and accepted — chat history and the file-transfer log do not survive a restore,
and a new phone starts empty.

## APK signing and supply chain

- Releases are signed with APK Signature Scheme **v2 + v3**; certificate
  `CN=PulseDrop, OU=App, O=PulseDrop, L=Dhaka, ST=Dhaka, C=BD`.
- Keystore and `signing.properties` are git-ignored; CI builds unsigned by design.
- Every release publishes a SHA-256; the same hash is a permanent VirusTotal link, and
  `.github/workflows/virustotal-scan.yml` scans each release automatically and files the
  report as an issue.
- Before installing an upgrade, the thing worth checking is that the signing certificate
  matches the previous release — that, not the engine count, is what proves the update came
  from the same party.

## Foreground service

The LAN server runs as a **`specialUse`** foreground service, declared as *"Local
file-transfer and call server that must stay reachable to browsers and nearby devices"*.
That type was chosen deliberately: Android 15+ caps `dataSync` services at a few hours per
day, and a room server has to stay reachable for as long as the user leaves it on. Swiping
the app away from Recents does not stop it, and a wake lock is held only while a transfer or
call is live. Screen capture runs as a separate `mediaProjection` service, which is the type
Android requires for capturing the display.

## What this does not protect against

- **Anyone on the LAN without a PIN** can open the address and join the open room. The PIN is
  the gate; pairing is not involved.
- **A seized or rooted device** exposes chat history and received files, which are unencrypted
  at rest. The identity key in the keystore is the durable secret; the history is not.
- **A call is not confidential** in the way a paired message is. Treat a call like a speakerphone
  in the same room.
- **A missed safety-code check** is undetectable afterwards. The 6-digit code is the only
  thing standing between ECDH and an attacker-in-the-middle who performed ECDH with each of you.
