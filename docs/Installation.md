# Installation

## From the release APK (normal path)

1. Download `app-release.apk` from the
   [latest release](https://github.com/bishnuneup4ne/pulsedrop/releases).
2. **If an older PulseDrop build is already installed, uninstall it first.**
   Builds signed with different keys cannot update in place — Android shows a bare
   "App not installed" with no explanation. See [Troubleshooting](Troubleshooting.md).
3. Open the APK and allow "install unknown apps" for your browser/file manager if prompted.
4. Android 15+: nothing special — PulseDrop is a normal user app.

## Verifying what you downloaded

Every release notes page lists the APK's SHA-256. Compare it locally:

```
sha256sum app-release.apk
```

The hash is also the permanent VirusTotal link:
`https://www.virustotal.com/gui/file/<sha256>` — see the README's
"Verifying a downloaded APK" section for the live links.

The APK is signed with APK Signature Scheme v2 + v3:
`CN=PulseDrop, OU=App, O=PulseDrop, L=Dhaka, ST=Dhaka, C=BD`.

## Play Protect

PulseDrop is distributed outside Google Play, so Play Protect may show an
"unknown developer" scan prompt on first install. That warning appears for *every*
sideloaded APK regardless of content; tap through it or scan the file yourself with
the links above. There is no way to remove the prompt without a Play Store listing.

## Building your own APK

See [Building from Source](Building-from-Source.md). A release you sign with your own key
can never update an installation made with the PulseDrop key (and vice versa) — uninstall
between the two.
