# HTTP API

The phone is the server; browsers are clients. Everything is **polled, not pushed** —
no WebSocket dependency, so the client works from any browser without a service worker.

Base: `http://<ip>:8080` (all endpoints) and `https://<ip>:8081` (same endpoints over TLS,
required for browser media).

## Core

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/` | Web UI (single page) |
| GET | `/api/info` | Device name, PIN requirement, server stats |
| POST | `/api/auth` | PIN check → token |
| GET | `/api/files` | Browse received-file list |
| GET | `/download/{id}` | Fetch a file |
| GET | `/preview/{id}` | Thumbnail / inline view |
| POST | `/upload` | Multipart upload |
| POST | `/api/delete` | Remove a file |

## Chat

| Method | Path | Purpose |
| --- | --- | --- |
| GET / POST | `/api/messages` | Poll / send chat messages |
| GET | `/api/typing` | Typing indicator |
| POST | `/chatupload` | Chat attachment upload |
| GET | `/chatfile/{id}` | Fetch a chat attachment |

## Pairing & discovery

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/pair/*` | Pairing handshake (ECDH) |
| GET | `/api/peers` | Discovered peers |

## Calls

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/call/*` | Call signalling (ring/accept/decline/end) |
| POST | `/api/call/web` | Browser-side call signalling |
| GET | `/api/webcall/down` | Chunked downlink media stream |
| POST | `/api/webcall/up` | Batched base64 uplink frames |

## Media frame format (web legs)

Each frame on the web up/downlink is:

```
[1 byte type][4 byte big-endian length][payload]
```

Types: `1` audio, `2` camera, `3` screen, `4` screen-state, `5` conference audio,
`6` conference video, `7` conference state. Audio is 16 kHz mono PCM16; video is JPEG
(~480 px wide) at up to ~5 fps.

## Auth model

Most endpoints require a token from `/api/auth` when a PIN is set. Paired-device uploads
additionally carry an AES-GCM upload proof (see [Security Model](Security-Model.md)).
