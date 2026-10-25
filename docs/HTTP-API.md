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
| POST | `/api/ticket` | Mint a short-lived ticket for a URL that cannot carry a header |
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

## Response encoding

Every response is post-processed on one rule: a tab re-reads the same state constantly, so the bytes
it already has must not be sent to it twice, and the bytes it does not have should travel small.

- **`gzip`** is applied to `application/json` and `text/html` answers of at least 1 KiB when the
  request says `Accept-Encoding: gzip`. NanoHTTPD implements compression by switching the response
  to `Transfer-Encoding: chunked`, so a compressed answer has no `Content-Length`. Streams
  (`text/event-stream`), media downloads and the export archive are never compressed: they are
  either frames small enough that buffering them to save bytes would delay delivery, or bytes that
  are already compressed.
- **`ETag`** is a strong validator over the exact bytes this caller would receive — SHA-256 of the
  body, first 64 bits, hex. Because these routes answer per address, two tabs whose visible history
  differs get two different validators, so no tab can be served another's cached page.
- **`If-None-Match`** (or `*`) with a matching validator answers `304 Not Modified` with no body and
  deliberately no `Content-Length`, which RFC 9110 forbids on a 304 and a strict client treats as a
  protocol error. `Vary: Accept-Encoding` is sent wherever a validator is.
- A `304` is not `ok` to `fetch`, so a client must test the status before its failure branch. The
  page remembers the validator for its own polls and clears it on logout, since the next login
  answers a different room.

## The room cursor

`GET /api/messages` answers the whole visible history unless the request offers a marker:

```
GET /api/messages?since=7:1834:c0ffee1122334455    (values illustrative)
    X-Room-Cursor: 7:1841:c0ffee1122334455
    X-Room-Window: 2000
    X-Room-Delta: 1
    [{"id": … only the entries that moved … }]
```

`X-Room-Cursor` is sent on every answer and is opaque: the visibility generation, the room's revision,
and a digest of the caller's address with the phone's. `X-Room-Delta: 1` says the array is a patch on
what the caller holds rather than the room — the only signal that lets a client merge instead of
replace, since a two-entry array and a two-thousand-entry array are otherwise the same shape.

`X-Room-Window` is how many room entries the phone keeps, and it exists because a patch cannot report
a retirement: once an entry leaves the room at the cap, the marker the caller holds already counts it
as seen, so nothing about it is ever sent again and a client would keep showing it indefinitely. A
client that keeps a list of its own trims to this window from the front — the same end the phone trims
from — which drops exactly the retired entries and can never drop a live one, because the client's
list is a subset of the room's. A client that ignores the header still converges on a reload, a
logout, or any change to which threads are open, all of which force a full answer.

The server sends the whole room, without the delta flag, when it will not honour a marker: no marker
offered, a marker minted before the phone changed which groups a browser may read, or a marker minted
for another address or another host address. A refusal is a normal full answer, so a client that
ignores these headers keeps working unchanged, and one that holds a stale marker costs itself one full
read rather than missing messages.

The array's shape never changes, so a reader that does not use the cursor is unaffected. A marker is a
place in a list and not a credential: it holds no PIN, no session token and nothing about the room's
contents, which is what lets it ride in a query string at all. The message *contents* stay out of
URLs everywhere.

## Chat

| Method | Path | Purpose |
| --- | --- | --- |
| GET / POST | `/api/messages` | Poll / send chat messages. `?since=` asks for only what moved — see [The room cursor](#the-room-cursor) |
| DELETE | `/api/messages` | Retract a message — only what the calling tab itself wrote, and only from the same address |
| GET | `/api/typing` | Active typing senders (public, like `/api/info`) |
| POST | `/api/typing` | Report typing (`sender`, `typing`) — held for 6 s; requires `X-Pulse-Auth`, keyed by caller address, one write per 400 ms |
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

Two things about that cadence are worth knowing from the client side:

- **The phone holds eight streams.** Each one pins a server thread for as long as the tab keeps
  it, so a ninth `GET /api/events` is refused with `503`, `Retry-After: 15` and
  `Cache-Control: no-store`. The refused tab keeps polling and loses only the fast path, while
  every tab already streaming keeps its stream. Refusing the newcomer beats cutting an
  established tab, because an evicted tab's reconnect evicts the next one and a room that is one
  tab too large ends up with every tab intermittently silent.
- **A stream that stops draining is dropped, not waited on.** Each tab has a 64-frame queue;
  when it fills, the newest frame is discarded rather than blocking whatever just happened. An
  empty stream sends a `:hb` comment every 20 seconds, and a comment is not an event.

The `message` event is not sent back to the address that posted the message — a tab already has
what it just sent — so it names the id and lets the recipient's own poll do the rest.

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

`POST /api/pair/request` answers `{"success":true,"waiting":true}` only when the request was
actually queued. It answers `{"success":false,"error":"not queued"}` when it was not — a body with
no public key, or a phone that cannot read its own identity key — and in the second case it sends
back a decline as well, which is the one pairing message a phone with no identity can honestly
produce. A decline may therefore carry no `pub`: only an acceptance names a key, and a requester
reads `pub` on the accept branch alone.

Nothing about a pairing succeeds quietly on a phone that has no identity. Every point that would
stamp this device's key checks for one first, so the attempt stays where it was — the request still
on the Accept screen, the reason in a toast — instead of leaving one side holding a pairing the
other can never prove. [Pairing and Devices](Pairing-and-Devices.md) has the states and what each
one means.

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

Send it in `X-Pulse-Auth`. No route accepts a credential in the query string any more, for a
session or for the PIN: a URL ends up in history, in `Referer`, in screenshots of a network
panel, and in any proxy log on the way. The few requests a browser cannot attach a header to —
`<img>` and media sources, `<a download>` links, and the `EventSource` stream — carry a ticket
instead.

### URL tickets

`POST /api/ticket` with an authenticated caller and a JSON body `{"scope": "<scope>"}` returns
`{"success": true, "ticket": "<expiry>.<mac>"}`. The ticket is an HMAC-SHA256 over the scope,
the caller's normalized address and an expiry; the phone stores nothing. It is valid for five
minutes, works only from the address that minted it, and dies immediately on a server restart
or a PIN change. The phone rejects a minted scope it has no route for.

| Scope | Opens |
| --- | --- |
| `events` | `GET /api/events?ticket=…` |
| `download:<id>` | `GET /download/<id>?ticket=…` |
| `folder:<path>` | `GET /download-folder?path=<path>&ticket=…` |
| `chatfile:<id>` | `GET /chatfile/<id>?ticket=…` |
| `preview:<id>` | `GET /preview/<id>?ticket=…` |

A ticket names one object and opens only that object: a ticket for one file id answers 401 on
any other, and no state-changing route accepts one at all. Tickets are short-lived rather than
single-use, so a range request or a retried download inside the window still works.

One deliberate exception: the PIN is still accepted in `X-Pulse-Auth` from another **PulseDrop app**.
A phone uploading to a peer presents the room PIN and an older build cannot negotiate a session
first, so this keeps mixed-version rooms working. Browsers never receive the PIN, so this is not the
path that leaks into history or `Referer`.

## Object scope

Passing the room credential is necessary but not sufficient: it says you belong to the room, not that
every object in it is yours. Three things key off the socket peer address, folded through the same
IPv4/IPv6 normalisation:

* A received file with no target is room-visible. A file *addressed* to someone is visible to that
  client and to whoever uploaded it — which is what lets a tab retract its own send
  (`isMine` in `/api/files`) without seeing anyone else's. Applies to `/api/files`, `/download/{id}`,
  `/preview/{id}`, `/api/delete` and `/download-folder`.
* `/download-folder?path=` inherits the rule from the files inside it: if any recorded download under
  that prefix is not yours, the whole zip is 403. Two ceilings answer 413 before a byte is compressed
  — 2 000 entries and 512 MiB total — because the zip is built in `cacheDir` on the phone.
* A browser call record belongs to the tab it was made for. `action=end`, `/api/webcall/down` and
  `/api/webcall/up` all check that binding; the `cid` is not a capability here, because
  `GET /api/call/web` reports the live `cid` to every authorized tab. `action=end` now fails with
  `No open call`, `Unknown call` or `Not your call` rather than always reporting success — the shipped
  tab ignores that response, a third-party client that checked it should be updated.
* A chat attachment is readable by the tab that uploaded it, or by anyone allowed to read a message that
  carries it — which is the same thread rule `/api/messages` uses, in one function both routes call.
  An attachment stored but not yet sent belongs to its uploader alone. `/chatfile/{id}` answers **404**
  rather than 403 for someone else's id: the id is a UUID, and "that attachment exists but is not yours"
  would disclose who is chatting to a tab that was only probing.

### Body ceilings

NanoHTTPD hands `serve()` the headers with the body still in the socket, so a route can refuse a body
before reading any of it — and once it has called `parseBody`, the whole declared length is already a
Java String (JSON) or a file in `cacheDir` (multipart). Every route that reads a body therefore answers
a ceiling first, from the declared `Content-Length`, with `413` and `Cache-Control: no-store`:

| Route | Ceiling |
| --- | --- |
| `POST /api/typing` | 4 KiB |
| `POST /api/auth` (no credential) | 8 KiB |
| `POST /api/pair/*`, `/api/call/*`, `/api/relay/*` (key-gated, not PIN-gated) | 64 KiB |
| `POST /api/call/web`, `/api/delete`, `/api/messages`, `/api/ticket` | 64 KiB |
| `POST /api/webcall/up` | 2 MiB — one 100 ms batch of base64 frames, sized for a 1600 px screen JPEG; each decoded frame is capped separately (1 MiB video, 64 KiB audio/control, 64 frames per batch, 4 MPix JPEG) |
| `POST /upload`, `POST /chatupload` | free space on the cache volume less 64 MiB headroom, floored at 32 MiB |

The upload ceiling is measured per request rather than fixed, because the honest answer to "how big a
file may I send you" is "however much this phone has left". A volume that `StatFs` cannot measure falls
back to 2 GiB rather than to unlimited.

A per-request ceiling cannot see two requests at once, so both upload routes also reserve their
declared length in one server-wide in-flight budget before `parseBody` and release it on every exit
(including a refused or failed body). The sum of uploads alive at one moment therefore fits the
volume. A request that would overflow that sum answers `503 Service Unavailable` with
`Retry-After: 5`: the body itself is the right size, so it may succeed later.

A client that *under*-declares `Content-Length` is not refused and is not read: `parseBody` stops at the
declared count, so the remaining bytes stay in the socket to be parsed as the next pipelined request.

Separately from size, `serve()` counts requests: a per-address token bucket (120 at rest, refilled at
30/s) answers `429 Too Many Requests` with `Retry-After: 1` and `Cache-Control: no-store` when a
caller outruns it. Presence writes have a tighter bucket, one per 400 ms, because the tab's own
heartbeat is one per 2 s. The counters are in memory and reset on restart; this is flood protection,
not an account quota.

Uploads land under a name of the phone's own making — `.<uuid>.part`, renamed into place once the bytes
have been written or decrypted — so a transfer that fails leaves nothing in the received folder that
looks complete, and two tabs sending to one `?path=` never copy into the same file at the same time. A
failed upload answers a fixed sentence rather than `e.message`, which from that depth is a filesystem
path inside the app's private storage.

### Names

`?path=` on `/upload` and `?filename=` on `/chatupload` are the only places a client chooses a name this
phone stores, and the room credential is shared by every tab in the room, so a name is hostile input. One
sanitizer (`sanitizeFileName`, applied per segment by `sanitizeRelativePath`) answers all of it, and it
is worth reading as a list of guarantees rather than as a filter:

* every character outside `A-Z a-z 0-9 space . _ - ( ) [ ] { }` becomes `_`. That is what removes control
  characters — a CR in a saved name is a response-splitting bug exactly one `Content-Disposition` away,
  because the header is built from the saved name and NanoHTTPD writes the value out as it was handed it
  — and with them every markup metacharacter, quote and Unicode directional override;
* `/` and `\` separate segments instead of surviving inside one, and `.` and `..` segments are dropped, so
  no name is absolute and none points above `WebDrop_Received`. `/download-folder?path=` goes through the
  same function and adds a canonical-path containment check on top;
* leading and trailing dots and spaces are trimmed, so an upload cannot plant a hidden file, a
  name-that-is-only-dots, or a file in the shape the server uses while a transfer is still landing;
* at most four segments are kept, and each is truncated to 120 characters with the extension preferred.
  An extension longer than the whole budget takes the plain truncation: the stem count there is
  `120 - extension - 1`, which is negative for `a.<300 characters>`, and the exception it threw was
  caught as an ordinary upload failure — so that name used to cost the transfer rather than be renamed.

A name that sanitizes away to nothing gets a generated one, and the Windows reserved names (`nul`,
`CON.txt`, `com1.log`) are ordinary files on Android and are not refused.

Two things a name does *not* decide. It does not decide who may read the file — that is the object-scope
rule above, and `/preview/{id}` answers an upload with the MIME type guessed from the saved extension by a
table with no HTML row, so an uploaded `page.html` is offered as `application/octet-stream` rather than as
a document from this origin. A file whose *recorded* type is a document (`text/html`, XHTML, SVG, XML)
is served with `Content-Security-Policy: sandbox`, so navigating to it directly yields a script-less,
unique-origin document; every inline response carries `nosniff` and `Referrer-Policy: no-referrer`, and
media and PDF responses are left unsandboxed so their viewers still work. And a name does not decide who
wins when two clients pick the same name: the second gets a timestamp suffix if the first has landed,
which is an open question, not a policy — see backlog `R-2`.
