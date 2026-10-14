# Security Policy

## Supported versions

| Version | Supported |
| --- | --- |
| latest release | yes |
| `main` (development) | yes, best-effort |
| anything older | no — reproduce on the current release first |

## What this project considers a vulnerability

PulseDrop is a local-network tool. Its threat model is a network you control (your own Wi-Fi or
your own hotspot), not an adversary on-path. Some consequences of that model are documented
behaviour, not bugs, and reports matching them will be closed as `not-a-security-issue`:

- **HTTP, not HTTPS.** The LAN server is cleartext. There is no certificate authority for
  `192.168.x.x`, and pairing encryption is the layer that provides confidentiality where it
  matters. Reported "missing TLS" on the browser endpoint is not a vulnerability.
- **Anyone on the same network can reach the server port.** Access requires the PIN; that is the
  design boundary.
- **The PIN is 4 digits, and brute-forcing it is rate-limited rather than impossible.** A 10k
  keyspace with an 8-attempt / 60 s escalating lockout per client IP is the intended trade-off
  for a human-memorable code. Note that the lockout is per source IP, so an attacker with many
  addresses gets more attempts; that limitation is known and stated.
- **Files received from a browser are trusted as files.** PulseDrop does not sandbox or scan
  downloaded content, and never opens it automatically.

Real findings worth reporting:

- Unauthenticated access to `/download/`, `/preview/`, `/chatfile/`, `/api/delete` or the
  received-file tree — including via path traversal (`..`, encoded separators, symlink escape
  from `WebDrop_Received/`).
- Pairing that completes without both sides deriving the same key, a safety code that does not
  actually bind the exchanged key, or an AES-GCM nonce reuse.
- The upload proof (`pulsedrop-upload-v1`) accepted with the wrong key, for a different
  `contentLength`, after its TTL, or with a replayed nonce.
- A PIN check that can be bypassed, or a session token that survives a PIN change or server
  restart and keeps working.
- Anything that makes the media frame reader (`CallEngine`) write outside its buffer on a
  crafted length.
- Credentials, pairing keys, or history leaking through cloud backup or device-to-device
  transfer despite the `backup_rules.xml` / `data_extraction_rules.xml` exclusions.

## Reporting

**Do not open a public issue for a vulnerability.** Use the repository **Security** tab and click
**Report a vulnerability** — that creates a private advisory visible only to you and the
maintainers, and it lets us discuss a fix without publishing an exploit against every running
install. If private reporting is not enabled on the repository, ask a maintainer to open one
before describing the problem in plain view.

Include, in whatever form is easiest:

1. PulseDrop version (`versionName` in `app/build.gradle.kts`, or the APK build) and Android
   version.
2. Whether the issue is on the phone side, the browser side, or the pairing/call protocol.
3. A description of the reachable effect, and steps or a script to reproduce it. A short Python
   or shell transcript against the HTTP surface is usually enough.
4. Your assessment of what an attacker gains, and what network position they need.

Expect an acknowledgement within a week and a coordinated disclosure plan before anything is
made public. We ask for 90 days before public disclosure, and will tell you sooner if the fix
lands earlier.

## Hardening notes for users

- Keep the server running only while you are transferring; stop it from the Server screen
  afterwards, since the notification is the only thing reminding you it is listening.
- Change the PIN if the same device repeatedly hosts public hotspots.
- Verify the on-screen safety code when pairing two phones, especially anywhere you do not own
  the access point.
