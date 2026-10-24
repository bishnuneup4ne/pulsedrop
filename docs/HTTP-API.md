# HTTP API

The phone is the server; browsers are clients. Most of the interaction is **polled**, so a
tab works from any browser with no service worker — but since 2.0 the phone also offers a
server-sent-events channel (`/api/events`) that tabs use to hear about new work without
waiting for the next poll tick.

Base: `http://<ip>:8080` (all endpoints) and `https://<ip>:8081` (same endpoints over TLS,
required for browser media).

## Core

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/` | Web UI (single page) |
| GET | `/api/info` | Device name, PIN requirement, version, capability list, server stats |
| POST | `/api/auth` | PIN check → **session token** (never the PIN) |
| POST | `/api/auth/logout` | Revoke the session the caller is holding |
| GET | `/api/files` | Browse received-file list |
| GET | `/download/{id}` | Fetch a file |
| GET | `/preview/{id}` | Thumbnail / inline view |
| POST | `/upload` | Multipart upload |
| POST | `/api/delete` | Remove a file |
| GET | `/api/peers` | Discovered peers |

`/api/info` reports `"version"` and an additive `"caps"` array
(`h264`, `screen-h264`, `chat-ext1` — see
[Capabilities and Versioning](Capabilities-and-Versioning.md)). A pre-2.1 build simply
omits the key; absent means "no capabilities claimed", never "unknown".

## Chat

| Method | Path | Purpose |
| --- | --- | --- |
| GET / POST | `/api/messages` | Poll / send chat messages |
| DELETE | `/api/messages` | Retract a message — only what the calling tab itself wrote, and only from the same address |
| GET | `/api/typing` | Active typing senders |
| POST | `/api/typing` | Report typing (`sender`, `typing`) — held for 6 s |
| GET | `/api/threads` | Group threads a browser may join (`id`, `title`, `kind`, `members`) |
| GET | `/api/events` | Server-sent events stream (`text/event-stream`) |
| POST | `/chatupload` | Chat attachment upload |
| GET | `/chatfile/{id}` | Fetch a chat attachment |

**Reactions, edits, read receipts and chat search are not HTTP endpoints.** They exist only
on the phone-to-phone pair wire and the relay mailbox, and a browser tab neither sends nor
receives them. Browser chat is also still the in-memory room list — newest 2000, lost when
the server restarts — while the phone's own history is the Room database described in
[Chat and History](Chat-and-History.md).

### Events stream

`/api/events` requires authorization and holds the connection open. Event kinds the phone
pushes:

| Kind | Fired when |
| --- | --- |
| `message` | A chat message arrived for a visible thread |
| `deleted` | A message was retracted |
| `file` | A file finished, failed, or is waiting for a decision |
| `call` | An incoming call is ringing |
| `callstate` | Call state changed (accepted, ended, participant left) |
| `pair` | A pairing request or answer arrived |

Polling still works and still backs everything; the stream is a latency optimisation, so a
tab that cannot hold an open connection falls back to the same endpoints at its own cadence.

## Relay (one hub, phones with no server)

A paired phone that runs no server of its own sends and receives through the host's
mailbox. Every envelope is sealed with the *recipient's* pairwise key before it gets here,
so the host carries traffic it cannot read.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/relay/post` | Queue one sealed envelope for another paired device |
| POST | `/api/relay/pull` | Fetch a device's mail, acknowledging the last round |
| POST | `/api/relay/pair-poll` | Collect a pairing answer a server-less phone was never handed |

`/api/relay/post` and `/api/relay/pull` require a signed proof bound to the caller's
pairing id and the role ("post"/"pull") — a "post" token cannot empty a mailbox.
`/api/relay/pair-poll` is authorized only by the request uuid, since no key exists yet.

## Pairing and discovery

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/pair/request` | Start a pairing handshake (name, public key, version, reachable address) |
| POST | `/api/pair/response` | Accept or decline |
| POST | `/api/pair/msg` | Encrypted chat to a paired device that answers HTTP |
| GET | `/api/peers` | Discovered peers |

Paths under `/api/pair/` are the family; the handshake is ECDH and the version string it
carries is what decides KDF v1 versus v2 for the pair.

## Calls

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/call/*` | Call signalling (ring/accept/decline/end) |
| POST | `/api/call/web` | Browser-side call signalling |
| GET | `/api/webcall/down` | Chunked downlink media stream |
| POST | `/api/webcall/up` | Batched base64 uplink frames |

Signalling is peer-to-peer, so a call needs a reachable server on both phones. Chat and
pairing do not — those route through the relay.

## Media frame format

Every media frame, on the phone-to-phone TCP socket and inside the web up/downlink bodies,
is:

```
[1 byte type][4 byte big-endian payload length][payload]
```

Payload cap 4 MiB. Unknown types are consumed by length and dropped — that is what lets a
1.x peer sit in a call with a 2.0 peer sending type 11.

| Type | Name | Payload | Since |
| --- | --- | --- | --- |
| 1 | audio | PCM16 mono 16 kHz, 100 ms chunks | v1 |
| 2 | camera | JPEG | v1 |
| 3 | screen | JPEG | v1 |
| 4 | screen-state | JSON `{"sharing":bool,"muted":bool}` | v1 |
| 5 | conf-audio | `[1B srcLen][src][pcm]` | v1 |
| 6 | conf-video | `[1B srcLen][src][1B kind][jpeg]`, kind 2 = screen else camera | v1 |
| 7 | conf-state | JSON roster | v1 |
| 8 | control | JSON — keyframe requests, caps exchange, stats | v2 |
| 9 | h264 | one H.264 access unit (annex-B) — **camera** | v2 |
| 10 | conf-h264 | `[1B srcLen][src][access unit]` — declared, never sent | v2 |
| 11 | screen-h264 | one H.264 access unit of a **shared display** | v2 |

**A browser tab only ever sees and sends types 1–4.** It has no H.264 decoder, announces no
capabilities, and uploads JPEG over base64. Types 9 and 11 are phone-to-phone only.

The full byte-level spec, including control-frame bodies and why type 11 is a new number
rather than a flag on 9, is in [Protocol v2](Protocol-V2.md).

## Auth model

Most endpoints require a token from `/api/auth` when a PIN is set. Paired-device uploads
additionally carry an AES-GCM upload proof, and relay access a role-bound proof token (see
[Security Model](Security-Model.md)).

A token from `/api/auth` is a **session**, not the PIN. It is 256 random bits, the phone keeps only
its SHA-256, it expires after 12 hours, `POST /api/auth/logout` drops the one presented, and
generating a new PIN drops all of them. Sessions live in memory: restarting the server logs every
browser out, which is the intended policy.

Send it in `X-Pulse-Auth`. `?auth=` is accepted for a *session* only, on the routes a browser cannot
attach a header to (anchor downloads, the `/api/events` stream); **the PIN itself is not accepted in a
query string any more**, and short-lived per-file tickets are scheduled to replace that path too.

One deliberate exception: the PIN is still accepted in `X-Pulse-Auth` from another **PulseDrop app**.
A phone uploading to a peer presents the room PIN and an older build cannot negotiate a session
first, so this keeps mixed-version rooms working. Browsers never receive the PIN, so this is not the
path that leaks into history or `Referer`.
