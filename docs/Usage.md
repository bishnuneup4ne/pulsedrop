# Usage

## The server card (home screen)

The phone is always the server. The home screen shows:

- **Device name** — what clients see (editable).
- **Address** — the secure `https://<ip>:<port+1>` URL for browsers, plus a QR code.
- **PIN** — optional 4-digit gate shown when "Require PIN" is on.
- **Start/Stop** — runs the HTTP server as a foreground service.

Clients on the same Wi-Fi or hotspot open the address in any browser. The plain-HTTP
port still works for pairing and app-to-app traffic; use **https** for browser calls.

## Files

- Browser: drag files onto the page or use the picker; uploads show live progress.
- Received files land in `WebDrop_Received/` on shared storage and are listed under
  **Files** with previews and download links.
- The phone can also push files to paired devices.

## Chat

- **Chats** lists paired devices and open browser sessions.
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
- The browser shows a full-page call stage; the phone shows an overlay you can expand.
- **Refresh safe**: reloading the browser page rejoins the live call.
- Multiple participants are mixed by the phone's conference bridge (audio + video tiles).

## History

Every transfer, message and call is recorded locally with timestamps and sizes; the
history screen lets you re-download received files or clear entries.

## Hotspot guide

The in-app guide walks through hosting on the phone's own hotspot when no Wi-Fi exists —
clients join the hotspot, then open the same https address.
