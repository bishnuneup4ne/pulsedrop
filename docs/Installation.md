# Installation

## From the release APK (normal path)

> Once PulseDrop is installed, later versions can update themselves in-app — see the
> "Keeping up to date" section of the README. To install the very first copy, follow these steps.

1. Download `app-release.apk` from the
   [latest release](https://github.com/bishnuneup4ne/pulsedrop/releases). Every release
   attaches exactly one APK and it is always named `app-release.apk`, so the release *tag*
   is what tells versions apart, not the filename.
2. Open the APK and allow "install unknown apps" for your browser/file manager if prompted.
3. Android 15+: nothing special — PulseDrop is a normal user app.

## You do not have to uninstall first

Official releases are all signed with the same upload key, so **a new release installs straight over
1.7** and keeps its data — the database upgrade is a real migration, not a wipe. An
app-scoped Room upgrade from version 2 or 3 to 4 runs on first launch; there is no
destructive fallback, so a migration that failed would be a bug worth reporting rather than
something that silently empties your history.

Uninstalling is only necessary when the copy already on the device was signed with a
*different* key — a build you compiled yourself, or someone else's fork. Android reports
that case as a bare "App not installed" with no explanation. See
[Troubleshooting](Troubleshooting.md).

## Verifying what you downloaded

Every release's SHA-256 is published twice: on the release page, and in the
"VirusTotal scan: v…" issue the CI opens automatically when the release goes out. Compare
locally:

```
sha256sum app-release.apk
```

The hash doubles as a permanent VirusTotal link:
`https://www.virustotal.com/gui/file/<sha256>` — the permalink always points at that exact
file, so it cannot be quietly swapped later.

For reference, the current release **2.0.1** APK is 16,545,119 bytes with SHA-256
`9eaea8564572656b45da061decf3b43d08d5c11c4dfc060a172ac42ffaf8441e`, and its VirusTotal report is
https://www.virustotal.com/gui/file/9eaea8564572656b45da061decf3b43d08d5c11c4dfc060a172ac42ffaf8441e —
which CI files as an issue on the release too. The previous release, 2.0.0, was 16,545,351 bytes
with SHA-256 `0389647c84dec72e8e06bac2cf03e93f940932ea7d4a010e98245046eed684c2`.

From 2.0.1 the app's name is `PulseDrop <version>` in the launcher and in the drawer header,
generated from the build's own `versionName`. Two installs side by side — this one and an older
APK you kept — are then distinguishable without opening either, and the name cannot fall behind
the version it is supposed to report.

The APK is signed with APK Signature Scheme v2 + v3:
`CN=PulseDrop, OU=App, O=PulseDrop, L=Dhaka, ST=Dhaka, C=BD`.

To check the signing key yourself, which is the thing that decides whether you can update in
place:

```
apksigner verify --print-certs app-release.apk
```

## Play Protect

PulseDrop is distributed outside Google Play, so Play Protect may show an
"unknown developer" scan prompt on first install. That warning appears for *every*
sideloaded APK regardless of content; tap through it or scan the file yourself with the
links above. There is no way to remove the prompt without a Play Store listing.

A VirusTotal scan of a fresh APK typically reports **zero malicious and zero suspicious**
with most engines listing the file as *undetected* — which means "no engine has a rule for
this", not "verified clean". That is the honest reading of the number.

## Building your own APK

See [Building from Source](Building-from-Source.md). A release you sign with your own key
can never update an installation made with the PulseDrop key (and vice versa) — uninstall
between the two.
