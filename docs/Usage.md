# Usage

## The server card (home screen)

The phone is always the server. The home screen shows:

- **Device name** — what clients see. It is shown in the header; renaming it has no UI yet
  (`MainViewModel.setDeviceName` is wired but unreached).
- **Address** — the plain `http://<ip>:<port>` URL, plus a QR code. This is the only
  address you ever type; the phone forwards browser visits to the secure port itself.
- **PIN** — optional 4-digit gate shown when "Require PIN" is on.
- **Start/Stop** — runs the HTTP server as a foreground service.

Clients on the same Wi-Fi or hotspot open that address in any browser. Pairing and
app-to-app traffic use the plain-HTTP port directly; a browser that needs mic, camera, or
screen share is bounced to `https://<ip>:<port+1>` automatically (see
[Browser Calls (HTTPS)](Browser-Calls-HTTPS.md)).

## Files

- Browser: drag files onto the page or use the picker; uploads show live progress.
- Received files land in `WebDrop_Received/` on shared storage and are listed under
  **Files** with previews and download links.
- The phone can also push files to paired devices.

## Chat

- **Chats** lists paired devices and open browser sessions. Message text is held in memory
  (newest 2000 per thread) and is not written to disk.
- Messages sync over the LAN only; attachments go through `/chatupload` and are stored
  under `WebDrop_Chat/`.
- Typing indicators are polled (`/api/typing`) — no push infrastructure involved.

## Radar (device discovery)

The radar scans the local subnet for other PulseDrop servers and lets you pair with a
tap. Pairing shows a short **safety code** on both devices — read them aloud to confirm
nobody is intercepting.

## Calls

- Voice or video, phone-to-phone, phone-to-browser, or mixed.
- In-call controls: mute, camera toggle, screen share (Android projection capture), end.
- One ringing screen serves every caller - a paired phone and a browser tab both ring with
  `IncomingCallStage`; the live call is an overlay you can minimise to a bar.
- **Refresh safe**: reloading the browser page rejoins the live call.
- Multiple participants are mixed by the phone's conference bridge (audio + video tiles).

## History

File transfers are recorded locally with timestamps, sizes and the peer involved; the history
screen lets you filter, re-download received files or clear entries. Chat text is not part of this
log (it lives in memory), and a finished call ends with a notification rather than an entry.

## Hotspot guide

The in-app guide walks through hosting on the phone's own hotspot when no Wi-Fi exists —
clients join the hotspot, then open the same address the server card shows.
