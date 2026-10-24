# Troubleshooting

## "App not installed"

If you are upgrading from an **official release**, this should not happen — every published
APK is signed with the same key, so 2.0.0 installs straight over 1.7 and keeps its data.
Check the SHA-256 against the release page first; a truncated download reports the same
unhelpful error.

The usual cause is a **signing-key conflict**: the copy on the device was signed with a
different key than the APK you're installing — a build you compiled yourself, or a fork.
Android gives no explanation.

**Fix:** Settings → Apps → PulseDrop → **Uninstall**, then install the new APK. There is no
in-place update across different signing keys.

To see which key a file carries:

```
apksigner verify --print-certs app-release.apk
```

Other causes, rarer:

- "Install unknown apps" not allowed for your browser/file manager.
- Android too old — PulseDrop needs API 24+ (Android 7.0).

## Play Protect blocks the install

PulseDrop is not on Google Play, so Play Protect shows an "unknown developer" scan prompt.
It appears for every sideloaded APK. Verify the file yourself (SHA-256 → VirusTotal link in
the release notes) and proceed.

## Browser can't use camera/mic/screen

You reached the page over plain **http**. Browsers only expose capture APIs on secure
origins.

**Fix:** the phone bounces `http://…:8080` to `https://…:8081` automatically, so just
accept the self-signed certificate warning once and the redirect lands you on the secure
origin. The secure port can also take a few seconds to come up after the server starts, in
which case the page stays on http and shows a **Secure mode** button in its top bar once the
port is listening — tap it, accept the certificate warning, and the camera, mic and screen
share work from then on. If a tab tried to open the camera and was refused, it switches
itself across as soon as the port is ready. The **Details** view states which mode the tab is
in, and typing the **https** address (port + 1) by hand always works as a last resort.

## Calls don't connect

1. Both devices must be on the **same** Wi-Fi/hotspot (client isolation on some guest
   networks blocks LAN traffic — use the phone's own hotspot instead).
2. Calls signal peer to peer, so a call needs a server on the phones taking it — chat
   and pairing do not: a phone with its server off reaches the whole room through its
   host's relay mailbox. Dialling from a phone whose server is off says
   *"Turn your server on first - calls signal over it"*.
3. Browser legs need the https port for media; signalling alone can look "connected but
   silent".
4. If a stale "ringing" bubble never clears, the phone ends it after 35 s automatically.

## My screen share looks soft, blocky or slow

First, find out which lane you are on: tap the stats line during the call. It reads
`share 864x1920@30` when encoded and `share jpeg` when not.

The encoded lane is only used when **all** of these are true, so a JPEG share is usually
expected behaviour rather than a fault:

- The other end is a **phone**, not a browser tab. A tab never receives the encoded lane.
- The other end runs PulseDrop 2.0 or newer **and** has a hardware AVC decoder.
- This phone has a hardware AVC encoder. Software codecs are refused on purpose.
- It is a **one-to-one** call. A group call always uses the JPEG share.
- Nothing failed mid-call: if the encoder died, the share drops to JPEG and stays there for
  the rest of the call. End and re-dial to try again.

Blockiness at a fixed bitrate is normal for text on a 6 Mbps CBR channel — the encoder is
spending its budget on a large, changing frame. Making the window smaller, or scrolling
less, helps more than any setting in the app.

A **still desktop showing ~1 fps is not a bug.** Nothing is changing, so nothing is sent.
The share lane deliberately does not step down its quality for a low measured frame rate.

## The share says a resolution I did not expect

That is the honest number. A 1080 × 2400 phone panel is scaled to fit a 1920 px long edge,
which is **864 × 1920**, and the label says exactly that rather than calling it 1080p.
Nothing is upscaled to match a name.

## Chat history disappeared after an update

It should not — 2.0 migrates the database in place (version 3 → 4 creates the chat tables)
and there is no destructive fallback, so a wiped history is a bug worth filing with your
previous version.

Two legitimate reasons for missing messages:

- **The browser tab's room is not stored.** The phone's own history survives a restart; the
  list a tab sees is in memory, capped at the newest 2000, and is gone when the server
  restarts.
- **A new phone starts empty.** The chat database is excluded from Google Drive backup and
  from device-to-device transfer, deliberately — see
  [Security Model](Security-Model.md).

Reactions, edits and read receipts need the **other phone** to run 2.0 or newer; an older
peer ignores them rather than failing.

## "Needs PulseDrop 1.6 or newer on <name>"

The peer you are trying to use a feature with is older than the feature, or never reported a
version at all ("it has not reported a version"). Update that device. The message names the
device and its version on purpose — a feature that quietly half-works is worse than one that
says no.

## The safety code does not match

**Do not accept the pairing.** Either someone is intercepting the handshake, or the device
reinstalled and now holds a new identity key — the app warns about that case explicitly
("this is a new key. Check the safety code matches the old one before trusting it").

Revoke trust on the old pairing, re-pair, and compare the new 6-digit code aloud before
trusting the thread.

## Pairing says this phone cannot read its identity key

Two similar sentences, two different remedies, so they are worth telling apart. **About → This
install** names the state under *Identity key*, and it is the fastest way to know which one you are
looking at.

- *"the Android keystore refused it and nothing was written in its place"* — the key is intact, this
  launch just cannot have it. Usually a phone that has not been unlocked since boot, or one whose
  lock screen changed underneath the keystore. **Unlock the screen and do it again.** Nothing was
  lost, and the app deliberately did not fall back to a readable copy of the key to keep going.
- *"cannot be read, and the Android keystore has none either"* — what is stored will not parse as a
  key, or only half of one is there. The identity is gone. **Nothing was regenerated**, because a new
  key would leave every other device unable to read this one with nothing on screen to say why, so
  this phone now refuses to pair rather than pretend. Revoke it on the peers and pair again.

While either is true, the phone keeps working as a browser hub: the open room, transfers to and from
browsers, and history all still work, because none of them need a pairing. What stops is anything
that has to say *who this phone is* — pairing itself, sending to a paired phone, read receipts, and
editing your own older messages. Those say why on the spot instead of half-succeeding.

## Server stops by itself

The server runs as a `specialUse` foreground service, so Android 15's `dataSync` hours-per-day
cap does not apply. If it is stopping, look at notification permission first (Android 13+):
without it the ongoing notification cannot exist, and the service loses the reason it stays
alive. Grant notifications for PulseDrop in system settings, restart the app, and start the
server from the card on **Home**.

Battery optimisation can still kill a phone that is idle for a long time; Settings offers the
system request that lets you exempt the app.

## Files "missing" after a transfer

Received files are **not** in shared storage or Downloads. They land in the app's own external
directory:

```
/sdcard/Android/data/com.pulsedrop.app/files/WebDrop_Received/
```

chat attachments in `WebDrop_Chat/` beside it. A file manager pointed at `/sdcard/Downloads`
will not show them, and some Android versions hide `Android/data` entirely. **Receive files**
shows the exact path with a "Copy folder location" action, and a row's **Save to Downloads**
puts a copy somewhere general.

A file sent but not yet accepted is not lost — it is staged, and appears under "Needs your
decision" on the receiving phone until you accept or decline it.

Very large uploads on flaky hotspots can stall; the progress bar shows the failing transfer.
There is no resume — a retry is a new transfer, which is what the transfer details screen
tells you when its Retry button is disabled.

## QR code opens the wrong port

Fixed in 1.1 — older builds hardcoded 8080 in the QR. Update to the latest release. The port
itself is not user-configurable today: 8080 for HTTP, 8081 for the secure browser origin.

## Still stuck?

Open an issue: https://github.com/bishnuneup4ne/pulsedrop/issues — include the app version
(**About PulseDrop** shows it), Android version, and what you expected vs what happened.
**Devices** → a device → copy diagnostics gives the per-peer detail, and a transfer's details
screen gives its full timeline.
