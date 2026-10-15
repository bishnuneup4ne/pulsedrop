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

## First visit

1. Start the server on the phone.
2. Scan the QR or type the **https** address it shows.
3. The browser warns the certificate isn't trusted (self-signed, expected on a LAN).
   Choose "Advanced → Continue". Accept once per address.
4. Grant camera/microphone when the call starts.

If you see the toast "This page is not a secure origin", you opened the http port by
mistake — switch to the https address.

## How media flows

- Browser captures audio (16 kHz PCM) and video (JPEG frames on a canvas) in JavaScript.
- Frames go up with batched `POST /api/webcall/up` and arrive on a chunked
  `GET /api/webcall/down` stream — no WebRTC, no external STUN/TURN, works fully offline.
- The phone's `CallEngine` mixes every leg (app sockets + web clients) in a conference
  bridge, so phone and browser participants see each other.
- Refreshing the page restores the call from `sessionStorage` and rejoins seamlessly.

## Limitations

- Bandwidth is whatever the hotspot gives you; video is capped at ~5 fps 480p JPEG to
  stay smooth on 2.4 GHz Wi-Fi.
- Screen share from the browser is `getDisplayMedia` (tab/window/screen, browser's choice).
- From the phone, screen share uses the Android media projection capture service.
