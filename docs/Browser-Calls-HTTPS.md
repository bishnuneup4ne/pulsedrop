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
is not up yet, the page tells you, keeps working in receive-only mode, and shows a
**Secure mode** button that appears the moment the port starts listening.

## First visit

1. Start the server on the phone (the card is on **Home**).
2. Scan the QR or type the address it shows (http works too — you will be redirected).
3. The browser warns the certificate isn't trusted (self-signed, expected on a LAN).
   Choose "Advanced → Continue". Accept once per address.
4. Grant camera/microphone when the call starts.

## How media flows

- Browser captures audio as 16 kHz mono PCM and video as **JPEG frames drawn on a canvas**
  in JavaScript.
- Frames go up with batched `POST /api/webcall/up` (base64, flushed every 100 ms) and arrive
  on a chunked `GET /api/webcall/down` stream — no WebRTC, no external STUN/TURN, works
  fully offline.
- The phone's `CallEngine` mixes every leg (app sockets + web clients) in a conference
  bridge, so phone and browser participants see each other.
- Refreshing the page restores the call from `sessionStorage` and rejoins seamlessly.

## A browser tab is on the JPEG lane. Always.

This is the most common misunderstanding about 2.0, so it is stated plainly: **the H.264
lanes are phone-to-phone only.**

- The web client contains no H.264 decoder — no WebCodecs, no `VideoDecoder`. It dispatches
  frame types 1–4 and nothing else.
- It announces no capabilities, and it has no media socket for the phone to check them on, so
  the phone can never earn an encoded lane toward a tab even if the tab could decode one.
- A phone sharing its screen to a browser tab therefore uses the JPEG share, and a phone
  watching a tab's camera uses the JPEG camera lane.

Earlier documentation claimed H.264 and a binary uplink arrive "when WebCodecs are present".
That describes an intention, not this build; the capability strings for both exist in code and
are never sent. See [Capabilities and Versioning](Capabilities-and-Versioning.md).

## What a browser actually sends

| | Value |
| --- | --- |
| Camera frame | JPEG, quality 0.72, canvas up to **1100 px** wide, one frame per 120 ms (~8 fps) |
| Shared screen frame | JPEG, quality 0.75, canvas up to **1600 px** wide, one frame per 130 ms (~7.7 fps) |
| Screen request | `getDisplayMedia` asking ideally for 1920×1080 at 15 fps, with system audio |
| Audio | Resampled to 16 kHz mono PCM |

Both widths are capped by the source: a tab sharing a 1366-px window sends 1366 px, not an
upscaled 1600. If the browser refuses to hand over system audio, the share continues with
picture only rather than failing — losing the sound is not allowed to lose the share.

## What the phone sends

A phone's own screen share is captured at **up to 1920 px on the long edge**, which is not the
same thing as 1080p: a 1080 × 2400 phone panel becomes 864 × 1920. The in-call label reports
the real figure. See [Calls and Screen Share](Calls-and-Screen-Share.md).

## Limitations

- Bandwidth is whatever the hotspot gives you. Every leg a browser touches is JPEG, so a tab
  in a call costs more uplink than two phones would.
- Screen share from the browser is `getDisplayMedia` (tab/window/screen, the browser's choice).
- From the phone, screen share uses the Android media projection capture service, which needs
  its own consent dialog per session.
- Calls are not end-to-end encrypted on any leg.
