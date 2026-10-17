# Usage

## Navigation

Everything is reachable from one drawer. On a phone it opens from the hamburger; on a window
720 dp or wider it is a persistent sidebar. The ten destinations, in the order the drawer
lists them:

| Destination | What it is for |
| --- | --- |
| **Home** | The server card, the address and QR, and the decisions waiting on you |
| **Devices** | Discovery, pairing, trust, per-device diagnostics |
| **Send files** | Pick files, choose recipients, confirm the send |
| **Receive files** | Auto-accept switch, uploads waiting on a decision, where files land |
| **Transfers** | The transfer log, with search, direction filter and per-row actions |
| **Messages** | Chat inbox: the open room, direct threads, encrypted groups |
| **Notifications** | What each notification channel is doing, read live from Android |
| **Settings** | Auto-accept, PIN, paired devices, version and updates |
| **Help & support** | Verified FAQ, hotspot guide, limits, report a problem |
| **About PulseDrop** | Build facts, how it works, license, dependency list |

Four of those carry live badges: **Devices** for incoming pair requests, **Messages** for
unread threads, **Receive files** for uploads awaiting a decision, and **Transfers** for
work in flight.

There is no "Server" tab. Older documentation pointed at one; the server lives on **Home**,
with a start/stop toggle in the top bar as well. **Settings** only has a *Server* heading
whose "Open the server card" row navigates to Home.

Hardware Back returns to **Home** rather than exiting, and notification taps route straight
to the relevant section.

## The server card (Home)

- **Device name** — what clients see. It is shown in the header. There is **no rename UI**:
  `MainViewModel.setDeviceName` is wired but unreached, and the protocol has no rename
  either, so the name comes from the device model and cannot be edited in-app.
- **Address** — the plain `http://<ip>:<port>` URL, plus a QR code. This is the only
  address you ever type; the phone forwards browser visits to the secure port itself.
- **PIN** — optional 4-digit gate shown when "Require PIN in browsers" is on.
- **Start/Stop** — runs the HTTP server as a foreground service. Stopping asks first:
  "Devices using <url> lose access right away. Files already waiting on this phone stay put."

Clients on the same Wi-Fi or hotspot open that address in any browser. Pairing and
app-to-app traffic use the plain-HTTP port directly; a browser that needs mic, camera, or
screen share is bounced to `https://<ip>:<port+1>` automatically (see
[Browser Calls (HTTPS)](Browser-Calls-HTTPS.md)).

## One hub, the whole room

You do **not** need a server running on every device. One phone hosting is enough:

- Other paired phones can keep their server off. They attach to the host as relay
  clients and send/receive chat through its mailbox — sealed with each device's own
  pairwise key, so the host carries bytes it cannot read.
- Browser tabs connect to that one address; unlimited tabs, one server.
- The room is one room: a message sent from any phone or any browser tab is carried to
  every paired phone and every tab watching, under one message id, so nobody renders it
  twice and nobody reads a different conversation.
- Retracting a message travels the same paths as the message itself.
- Exceptions that still need a server on the receiving side: file pushes and calls,
  which signal peer to peer. The app says so when it refuses one —
  *"Turn your server on first - calls signal over it"*.

## Files and transfers

- Browser: drag files onto the page or use the picker; uploads show live progress.
- On the phone, **Send files** takes a file selection plus a recipient and confirms before
  anything moves; **Receive files** holds inbound uploads until you accept them unless
  "Accept files automatically" is on in Settings.
- Received files land in **`WebDrop_Received/` inside the app's own external files
  directory** — `Android/data/com.pulsedrop.app/files/WebDrop_Received`. That is app-scoped
  storage, not the shared Downloads tree, so other apps and a file manager looking at
  `/sdcard/Downloads` will not list it. **Receive files** shows the exact path with free
  space and a "Copy folder location" action; individual rows also offer
  **Save to Downloads** when you want a copy somewhere general.
- Chat attachments go to `WebDrop_Chat/` in the same app-scoped directory.
- **Transfers** is the log: search by name, device, IP or id, filter by direction, and open
  a row for size, saved location, progress, which peers it went to, an integrity check,
  timing, and copyable diagnostics.
- **A transfer cannot be paused, cancelled or retried.** Those buttons are present on the
  details screen and deliberately disabled, each with its reason: the transfer engine has
  no cancel or pause operation, and no checkpoint exists, so a "retry" would be a brand-new
  transfer — use Send again. Closing the app or dropping the peer is the only stop.

## Chat

Chat has its own page — see [Chat and History](Chat-and-History.md) for what is stored and
searched. In short:

- **Messages** lists the open LAN room, direct IP threads, encrypted private threads and
  encrypted groups, newest first, with unread counts that survive a restart.
- Long-press a bubble to **Reply**, **Edit**, or **Delete** — "for everyone" leaves a
  tombstone rather than silently vanishing, on phones and on browser tabs alike.
- Reactions and read receipts work between paired phones running 2.0 or newer.
- Search covers stored history, including messages that already scrolled out of the window.
- Messages sync over the LAN only; attachments go through `/chatupload`.
- Typing indicators are polled (`/api/typing`) — no push infrastructure involved.

## Radar and pairing (Devices)

The Devices screen scans the local subnet for other PulseDrop servers and lets you pair with
a tap, with filters for Nearby / Trusted / Connected / Pending and a "Connect by address"
field for a host the scan missed. Pairing shows a 6-digit **safety code** on both devices —
read them aloud to confirm nobody is intercepting. A phone whose server is off pairs anyway:
the handshake answer is polled back through the host it can reach. Each paired device card
also shows the PulseDrop release it reported at pairing, so an out-of-date phone is visible
before a feature quietly half-works.

Full detail, including revoking trust and what a changed code means:
[Pairing and Devices](Pairing-and-Devices.md).

## Calls

PulseDrop has one theme: light. The phone, the browser client and the in-call stage all paint from
the same palette, so a tab is never dark because the visitor's operating system happened to be.
Video keeps its own black letterbox inside that light stage — a frame must not pick up the theme's
grey — and `WebThemeParityTest` fails the build if the browser's numbers drift from the phone's.

Voice or video, phone-to-phone, phone-to-browser, or mixed. Controls: mute, camera toggle,
screen share, end, and minimise to a bar. One ringing screen serves every caller — a paired
phone and a browser tab both ring on the same stage. Reloading the browser page rejoins the
live call. Multiple participants are mixed by the phone's conference bridge.

The in-call overlay reports **measured** fps and bitrate per lane, and a resolution label
the device can actually source. How the lanes are chosen and why a share sometimes stays
JPEG: [Calls and Screen Share](Calls-and-Screen-Share.md).

## Notifications

Android decides most notification behaviour, so the **Notifications** screen reads the real
channel state live rather than restating what the app asked for, and offers "Ask Android
again" and a jump to system settings. It also lists what never notifies — the reasoning
being that a silent-by-design event is easy to mistake for a bug.

Note one gap while reading it: the screen documents five channels, but the app creates six —
the "Waiting for a decision" channel behind a held upload is not listed there yet.

## Hotspot guide

The in-app guide walks through hosting on the phone's own hotspot when no Wi-Fi exists —
clients join the hotspot, then open the same address the server card shows. It ends with the
network details as the app sees them: local IP, port, security and the server engine.
