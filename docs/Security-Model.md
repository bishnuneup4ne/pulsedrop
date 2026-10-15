# Security Model

PulseDrop's threat model is a **trusted local network** (home Wi-Fi, your own hotspot) —
not a hostile one. Everything below is defense-in-depth for that assumption.

## Transport

- **HTTP** on the LAN port for pairing and app-to-app traffic (cleartext by design;
  `usesCleartextTraffic` is deliberate — there is no CA available to a phone).
- **HTTPS** on port + 1 for the browser UI, using a process-lifetime self-signed
  certificate (RSA-2048, SHA256withRSA, LAN IP in the SAN list). Browsers gate
  mic/camera/screen capture behind secure origins, so this port is what makes
  browser calls possible at all.

## PIN

- 4 digits, optional, enforced **per client IP**.
- Eight distinct wrong values → 60 s lockout, doubling on further bursts (capped ~16 min).
- A stale token repeated by an old tab counts once, not once per poll.

## Device pairing (E2E)

- Each device holds an **EC P-256 identity** in `AndroidKeyStore` (raw-key prefs
  fallback where unavailable).
- ECDH output is folded as `sha256(secret) || sha256(ordered SPKI) || "LocalDrop-v1"`,
  hashed to the AES-256-GCM key. Both sides derive the same key independently.
- Both devices display a **safety code** from the first four hash bytes — compare it out
  of band to detect interception (number-matching, same idea as Signal safety numbers).

## Relay: a postbox, not a server

A phone that pairs with a hub but runs no server sends its mail through the host. Every
envelope is sealed with the *recipient's* pairwise AES-GCM key before it leaves, and the
host stores only the ciphertext plus a destination device id — being the postbox does not
make it a party to the conversation. Pulling and posting are authorized by a signed proof
token bound to one pairing **and one role** (`pulsedrop-relay-v1|pid|role|deviceId|issuedAt|nonce`),
so a "post" capability can never empty somebody else's mailbox. Mail is held until the
addressee acknowledges it (a crashed poll simply re-fetches), and a device that never
returns is forgotten after a day.

## Upload proof

A paired browser upload carries an AES-GCM blob over
`pulsedrop-upload-v1|pairId|contentLength|issuedAt|nonce`. The server checks the key
holder, the multipart body length, a 120 s TTL, and rejects replayed nonces.

## Backups

`backup_rules.xml` and `data_extraction_rules.xml` exclude the E2E identity key, the
pairing store and the PIN — pairing secrets never migrate through cloud backup or device
transfer.

## APK signing & supply chain

- Releases are signed with APK Signature Scheme **v2 + v3**; certificate
  `CN=PulseDrop, OU=App, O=PulseDrop, L=Dhaka, ST=Dhaka, C=BD`.
- Keystore and `signing.properties` are git-ignored; CI builds unsigned by design.
- Every release publishes a SHA-256; the same hash is a permanent VirusTotal link, and
  `.github/workflows/virustotal-scan.yml` scans each release automatically.

## Foreground service

The LAN server runs as a **`specialUse`** foreground service, declared as *"Local file-transfer
and call server that must stay reachable to browsers and nearby devices"*. That type was chosen
deliberately: Android 15+ caps `dataSync` services at a few hours per day, and a room server has to
stay reachable for as long as the user leaves it on. Swiping the app away from Recents does not stop
it, and a wake lock is held only while a transfer or call is live.
