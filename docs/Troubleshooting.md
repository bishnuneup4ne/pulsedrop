# Troubleshooting

## "App not installed"

Almost always a **signing-key conflict**: an older PulseDrop on the device was signed with
a different key than the APK you're installing. Android gives no explanation.

**Fix:** Settings → Apps → PulseDrop → **Uninstall**, then install the new APK. There is no
in-place update across different signing keys — this is expected for first sideloads.

Other causes, rarer:

- Download truncated — re-download and compare the SHA-256 with the release notes.
- "Install unknown apps" not allowed for your browser/file manager.
- Android too old — PulseDrop needs API 24+ (Android 7.0).

## Play Protect blocks the install

PulseDrop is not on Google Play, so Play Protect shows an "unknown developer" scan prompt.
It appears for every sideloaded APK. Verify the file yourself (SHA-256 → VirusTotal link in
the release notes) and proceed.

## Browser can't use camera/mic/screen

You opened the **http** address. Browsers only expose capture APIs on secure origins.

**Fix:** use the **https** address (port 8081) shown by the QR/server card and accept the
self-signed certificate warning once. The web UI shows a toast when it detects it is not a
secure context.

## Calls don't connect

1. Both devices must be on the **same** Wi-Fi/hotspot (client isolation on some guest
   networks blocks LAN traffic — use the phone's own hotspot instead).
2. Server must be running on the callee phone (check the server card says "running").
3. Browser legs need the https port for media; signalling alone can look "connected but
   silent".
4. If a stale "ringing" bubble never clears, the phone ends it after 35 s automatically.

## Server stops by itself

Android 15+ enforces a ~6 h per 24 h quota on `dataSync` foreground services. When hit,
PulseDrop deliberately stops the HTTP server instead of degrading silently — restart the
server from the app.

## QR code opens the wrong port

Fixed in 1.1 — older builds hardcoded 8080 in the QR even with a custom port. Update to the
latest release.

## Files don't appear after upload

Check the received folders: `WebDrop_Received/` (drops) and `WebDrop_Chat/` (attachments)
under shared storage. Very large uploads on flaky hotspots can stall — the progress bar
shows the failing transfer; retry.

## Still stuck?

Open an issue: https://github.com/bishnuneup4ne/pulsedrop/issues — include app version
(see About), Android version and what you expected vs what happened.
