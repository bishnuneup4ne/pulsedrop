# Browser Calls (HTTPS)

## The one rule

**Browsers only hand a page the microphone, camera and screen-share APIs on a secure
origin.** A plain `http://192.168.x.x:8080` page can signal a call but can never capture
media — `getUserMedia`/`getDisplayMedia` simply fail. That is a browser security rule,
not a PulseDrop limitation.

## What PulseDrop does about it

The phone serves the web UI twice:

| Port | Protocol | Purpose |
| --- | --- | --- |
| 8080 (configurable) | HTTP | pairing, app-to-app, legacy clients |
| 8081 (port + 1) | HTTPS | browser UI with real mic/camera/screen capture |

The HTTPS listener uses a **self-signed certificate generated at runtime** (BouncyCastle,
RSA-2048) with the phone's LAN IP baked into the SAN list. It lives only for the process —
nothing to renew, nothing to leak.

Since v1.2 the phone also **bounces plain http visits to the https port automatically**:
open `http://…:8080` in a browser and you land on `https://…:8081`. If the secure listener
is not up yet, the page tells you and calls keep working in receive-only mode.

## First visit

1. Start the server on the phone.
2. Scan the QR or type the address it shows (http works too — you will be redirected).
3. The browser warns the certificate isn't trusted (self-signed, expected on a LAN).
   Choose "Advanced → Continue". Accept once per address.
4. Grant camera/microphone when the call starts.

## How media flows

- Browser captures audio (16 kHz PCM) and video (JPEG frames on a canvas) in JavaScript.
- Frames go up with batched `POST /api/webcall/up` and arrive on a chunked
  `GET /api/webcall/down` stream — no WebRTC, no external STUN/TURN, works fully offline.
- The phone's `CallEngine` mixes every leg (app sockets + web clients) in a conference
  bridge, so phone and browser participants see each other.
- Refreshing the page restores the call from `sessionStorage` and rejoins seamlessly.

## Limitations

- Bandwidth is whatever the hotspot gives you. Camera rides ~5 fps at up to 960 px wide,
  screen share ~7 fps at up to 1600 px, JPEG quality-tuned for LAN links; the phone's own
  share captures at 1080p.
- Screen share from the browser is `getDisplayMedia` (tab/window/screen, browser's choice).
- From the phone, screen share uses the Android media projection capture service.
