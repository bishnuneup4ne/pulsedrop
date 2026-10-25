# Authorization Matrix

Every HTTP route the phone serves, what it demands of the caller, and what object scope it
applies. This is the reference the route code is audited against: **a new route is not done
until it has a row here and a test in the table-driven suite** (see §7).

Source of truth: `PulseDropServer.kt` (dispatch and object scope), `AuthorizationPolicy.kt` (what
each route demands) and `WebClientHtml.kt` (the browser client). **References name the dispatch
condition, function or constant, not a line number.** Line numbers rotted with every merge — the
previous edition's line-number pointers were already unusable — while a name can be checked by search.
The anchors were audited against `main` at `428dc1b`. Where behaviour differs from intent, the row
says so — this document records what the code *does*, not what a future release should do.

Rows marked **Fixed** describe the work on `security/browser-sessions` (the credential design),
`security/object-scope` (who may touch *which* object) and `security/upload-ceilings` (what a body
may cost), all merged into `main`. The route table's **Code** column names the branch in
`dispatch()` that serves the row; whether that route is public is decided by `AuthorizationPolicy`,
not by where the branch sits.

---

## 1. How the guard actually works

`serve()` asks **one table** — `AuthorizationPolicy.forRequest(uri, method)` in
`server/AuthorizationPolicy.kt` — what the route demands, and `authorize()` applies that answer before
any handler runs. The decision is a row, not a position in the chain: a route that is not in the table
falls back to `SESSION`, so forgetting to list a new route makes it *protected*, not public. This
replaced the three-tier flat chain on `main` during the security series (backlog S-3); the tier names
in §§2–4 are kept as a reading aid, not as a description of where the check runs.

| Access | What the caller must present |
| --- | --- |
| `PUBLIC` | Nothing. The route says at its handler why that is acceptable. |
| `SELF` | Nothing additional: the only thing the route can do is revoke the credential it was handed. |
| `PAIRING` | Nothing at this layer — key-gated inside `PairManager`/`CallEngine`/`handleRelay`. |
| `SESSION` | A live session, the PIN, or a pairing upload proof, in `X-Pulse-Auth` (see §1.1). |
| `SESSION_OR_TICKET` | The same, or a URL ticket scoped to the exact object this GET names. |

Consequences that are easy to miss:

- **A method the table does not name for a path still requires `SESSION`.** `PUT /upload` and
  `GET /api/auth` are 401, not free probes of which paths exist. The fallback is asserted per rule by
  `AuthorizationPolicyTest`.
- **`OPTIONS` is handled before the table** and answers 200 for every path with no credential,
  including paths that do not exist — the one deliberate route-shape disclosure.
- **A path in no row is authenticated, then 404s**, so an authenticated wrong path and an authenticated
  missing path are indistinguishable.
- **The table is checked against this file.** Every rule names the matrix row that documents it;
  `AuthorizationPolicyTest` fails if a row disappears (`docs/Authorization-Matrix.md` is the reviewed
  source of truth, and the test is what makes that claim true).

### 1.1 Credentials the server accepts

A request proves itself with one of these, in the order `authorize()`/`isAuthorized()` asks:

1. **Nothing at all**, when `!isPinRequired || pinCode.isNullOrBlank()`. Every `SESSION` and
   `SESSION_OR_TICKET` route becomes public. This is the "PIN disabled" mode and is the default
   posture for a browser client that never logs in.
2. A **live session token** in `X-Pulse-Auth` — the random value `/api/auth` minted for that tab
   (`SessionStore.isValid`).
3. The **PIN itself**, in `X-Pulse-Auth` only. Kept deliberately: `TransferManager` authenticates a
   phone-to-phone upload with the room PIN, and a phone running an older build has no login step
   with which to obtain a session. This is an app-to-app compatibility allowance, not a browser
   path — no browser is ever handed the PIN.
4. A **URL ticket** in `?ticket=` — accepted only on the five GET route families a browser cannot
   put a header on (`/api/events`, `/download/{id}`, `/download-folder`, `/chatfile/{id}`,
   `/preview/{id}`), through `ticketAuthenticates` rather than `isAuthorized`. It is an HMAC over
   the exact object, the caller's normalized address and a five-minute expiry, minted by an
   authenticated `POST /api/ticket`, and it dies on restart or PIN change. No session and no PIN
   is accepted in a query string (S-2).
5. A pairing upload proof — `X-Pair-Id` + `X-Pair-Auth` + `Content-Length` verified against
   the shared E2E key — **but only for `uri == "/upload"`** (`pairedSenderIsAuthorized`). Paired
   phones cannot reach any other route without the PIN.

There **is** a session concept now: `/api/auth` mints a random 256-bit session and the PIN is no
longer a bearer credential for a browser. See §6 S-1 and S-2 (both fixed).

### 1.2 Frequency

Size ceilings bound one request's cost (P-1); they say nothing about how often a caller may ask.
`serve()` runs a per-address token bucket (`RequestRateLimiter`, 120 at rest, refilled at 30/s) before
authorization. A tab streaming a call at 10 Hz plus its polls stays well under it; a loop gets 429
with `Retry-After` and `Cache-Control: no-store`. Presence writes have a second, tighter bucket (one
per 400 ms), because the tab's own heartbeat is one per 2 s. This is a flood guard, not a quota: it
is in memory and is forgotten on restart, like the PIN lockout.

Failed guesses are counted per source IP and lock out after `PIN_MAX_ATTEMPTS = 8` distinct
wrong values with exponential backoff capped at `PIN_LOCKOUT_MAX_STEPS = 4` (`recordPinFailure`).
Identical repeats are not counted (`lastWrongCredential`), so a stale tab cannot lock itself out
forever — but an attacker who varies the guess does trip it. Lockout is keyed on IP only and is in
memory: it resets on process death.

### 1.3 Identity the server trusts but should not

- **`clientIp` is the object-scope key** for the file list, downloads, previews, deletes, folder
  zips, both call media legs and the chat threads (the Scope column in §§3–4 names each one).
  It comes from the socket peer.
  For a LAN server this is reasonably strong, but it means *one host behind a shared NAT or
  hotspot is indistinguishable from another*, and an attacker who can spoof or share the
  phone's own address (`normalizeAddr`/`sameAddr` folding) inherits that
  host's private threads and directed files. After `security/object-scope` the file routes answer
  that question through **one** predicate — `receivedFileIsVisibleTo` — instead of four
  copies that compared raw strings and so disagreed with `sameAddr` about `::ffff:` forms. The
  remaining scope-by-address risk is unchanged and is what session-scoped objects would fix.
- **`X-Pulse-Client` is caller-supplied and only decoded for display** (`noteWebClient` stores it,
  `webClientName` reads it back). It is never an authorization input. Correct.
- **`sender` in `/api/messages` POST and `/api/typing` POST is caller-supplied.** In a message it is a
  display label attached to the caller's own address. In typing presence the name is still a label, but
  the map is keyed by the normalized address, so a caller can rename itself and cannot remove or
  overwrite another address's entry; writes also require the room credential (§6 P-2, fixed).
- **`?path=` on `/upload` is taken from the query string** (dispatched by `uri == "/upload"`, handled in
  `handleFileUpload`), passed through `sanitizeRelativePath`, and used as a relative write target under
  `WebDrop_Received`. Traversal and absolute paths are stripped and the result is clamped to the last
  four segments, so it cannot escape the root — but it can create subdirectories anywhere inside it.
  The character class is what answers the rest: everything outside `A-Z a-z 0-9 space . _ - ( ) [ ] { }`
  becomes `_`, leading and trailing dots are trimmed, and each segment is truncated to 120 characters.
  That single function is also the only thing between a query string and a `Content-Disposition`, so its
  rules are pinned by name rather than by line number in `HTTP-API.md` §Names.

---

## 2. Routes the table opens without a session (`PUBLIC`, `SELF`, `PAIRING`)

| Method | Path | Code | Guard | Scope | Intentional public? | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| OPTIONS | *any* | `if (method == Method.OPTIONS)` | none | — | yes | 200 with an empty body and no CORS headers. It still answers 200 for paths that do not exist, which is a route-shape oracle when paired with method probing (R-8). |
| GET | `/`, `/index.html` | `uri == "/" \|\| uri == "/index.html"` | none | full page | yes | Redirects to `https://host:tlsPort/` when the TLS listener is up (the `tlsServer != null` redirect inside that branch). Page body itself contains no credential; it embeds `deviceName` and a "requiresPin" boolean. |
| GET | `/fonts/google-sans-{regular,medium,semibold,bold}.ttf` | `WEB_FONTS[uri]` | none | 4 fixed resources | yes | Whitelisted map, no path interpolation. Cache-Control `public, max-age=604800`. Safe as written. |
| GET | `/api/info` | `uri == "/api/info"` (GET) | none | device metadata | yes — documented | Leaks `deviceName`, `version`, LAN `clientIp`, `deviceId`, TLS status and a `tlsError` string, capability list. **Fixed:** `tlsError` is now `describeTlsFailure(e)` — the exception class, plus a message with path-shaped runs replaced by `<path>` and the whole thing capped at 200 characters; the full exception still goes to Logcat. `deviceId` remains disclosed and is P-3's open half. |
| GET | `/api/typing` | `uri == "/api/typing"` (GET) | none | presence list | yes — declared non-sensitive in that branch | Returns active sender *names* only, bounded by `TYPING_SENDER_LIMIT = 64`. |
| POST | `/api/auth` | `uri == "/api/auth"` (POST) | IP lockout only | — | yes | Mints a **random session** (`SessionStore.issue()`) and returns that. It no longer returns `pinCode`, and it sends `Cache-Control: no-store` so a minted session cannot be retained by a browser cache or a shared proxy. Wrong PIN → no `token` field at all. See §6 S-1 (fixed). |
| POST | `/api/auth/logout` | `uri == "/api/auth/logout"` (POST) | presents its own credential | one session | yes | Revokes exactly the session in `X-Pulse-Auth`. Self-scoped, so it needs no further authority; an already-dead token still answers 200 with `"revoked":false` because logging out twice is not an error. |
| POST | `/api/pair/request` | `uri == "/api/pair/request"` | key-gated downstream | pairing | by design | PIN-exempt. `PairManager.handleIncomingRequest` is the enforcing layer: the presented `pub` must parse as a P-256 key (`Crypto.isP256PublicKey`), or nothing reaches the confirmation screen. Unbounded rate remains — pairing CPU cost is ECDH, so this is still the app's cheapest CPU-exhaustion target. `PairKeyIngestTest`. |
| POST | `/api/pair/response` | `uri == "/api/pair/response"` | key-gated downstream | pairing | by design | PIN-exempt, but accepted only for a request this phone is still awaiting (live or late-answer window). The presented key must be P-256 and `kdf` must be a version this build performs (`Kdf.isKnown`); otherwise the answer stores no pairing and says why. `PairKeyIngestTest`. |
| POST | `/api/pair/msg` | `uri == "/api/pair/msg"` | key-gated downstream | pairing | by design | |
| POST | `/api/call/invite` | `uri == "/api/call/invite"` | key-gated downstream | calls | by design | PIN-exempt; a LAN host can attempt to raise the incoming-call UI. Confirmed: `CallEngine.handleInvite` requires `findPairing(pid)`, so an unknown id is refused and never reaches the ringing screen (`CallInviteTest`). |
| POST | `/api/call/response` | `uri == "/api/call/response"` | key-gated downstream | calls | by design | |
| POST | `/api/call/end` | `uri == "/api/call/end"` | key-gated downstream | calls | by design | |
| POST | `/api/relay/post` | `handleRelay`, `uri == "/api/relay/post"` | per-device relay proof | mailbox | by design | `verifyRelayAuth(pid, "post", proof)` — role-scoped proof, `MAX_RELAY_ENVELOPE` 64 KiB cap. Sound shape. The POST carrying an envelope is bounded separately, at `RELAY_BODY_LIMIT` (four envelopes): the body is the envelope plus `role`, `to` and quoting, so a ceiling equal to the envelope's own refuses the last few dozen bytes of mail the relay would have accepted — and refuses them with a status the sender cannot act on, rather than the relay's own `too large`. |
| POST | `/api/relay/pull` | `handleRelay`, `uri == "/api/relay/pull"` | per-device relay proof | own mailbox | by design | `role = "pull"`; returns only `relayMailbox.pending(senderDid)` — the proof's device id, not the caller's. Correct isolation. |
| POST | `/api/relay/pair-poll` | `handleRelay`, `uri == "/api/relay/pair-poll"` | **none** | pair answers | **gap** | This is the one relay sub-route that returns before `pairManager` and `verifyRelayAuth` are consulted — the branch is the first thing in `handleRelay`, above `val pm = pairManager`. `takePairAnswer(json.optString("reqId"))` is driven by a caller-supplied `reqId` with no proof of any kind. Whether that is exploitable depends on what a pair answer contains and how guessable `reqId` is — it must be tested, not assumed. |

A verb the table does not name for these prefixes — `PUT`/`DELETE` to `/api/pair/*`,
`/api/call/*`, `/api/relay/*` — takes the rule's fallback, which is `SESSION`, so it is refused
without a room credential and falls through to the final 404 with one.

---

## 3. Routes that only need a session (`SESSION`)

| Method | Path | Code | Guard | Scope | Notes |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/typing` | `uri == "/api/typing"` (POST) | `SESSION` (P-2) | writes presence | **Fixed.** The route requires the room credential, keys presence by the caller's normalized address instead of the self-declared name, and rate-limits writes to one per 400 ms. A caller can choose its own label; it cannot evict or overwrite another address's entry, and an unauthenticated stranger cannot write at all. The 64-entry cap remains the outer bound. `TypingPresenceTest` proves the unauthenticated refusal, the cross-address eviction refusal, name de-duplication and the 429. |
| GET | `/api/threads` | `uri == "/api/threads"` (GET) | `SESSION` via the table | all open threads | Returns `id`, `title`, `kind`, `members` for every thread the phone opened to browsers. No per-client filter: any authenticated client sees all `webThreads`. Acceptable only if thread membership is genuinely not confidential between clients — verify against `webThreads` population. |
| GET | `/api/events` | `uri == "/api/events"` (GET) | `SESSION` or an `events` ticket (`ticketAuthenticates`) | global event stream | SSE. `EventSource` cannot send a header, so this route also accepts a ticket (`?ticket=`) minted for the `events` scope by an authenticated caller. Streams `call`, `pair` and other events to **every** authenticated tab; `pushWebEvent("pair", exceptIp=...)` shows the code does suppress by IP for some events, so check what `webCall`/`webThreads` payloads leak across tabs. Capped by `maxEventStreams` with oldest evicted — good. `markActivity()` keeps the server alive, so this is an anti-idle-stop vector for an authenticated tab. |
| POST | `/api/ticket` | `uri == "/api/ticket"` (POST) | `SESSION` via the table | mints one URL ticket | Body capped at `JSON_BODY_LIMIT` from the declared length. The requested scope must be `events` or one of the four object families (`download:`, `folder:`, `chatfile:`, `preview:`), so the route cannot mint a permission it would not spend, and no state-changing route accepts one. The ticket is an HMAC-SHA256 over scope + normalized client address + a five-minute expiry, so nothing is stored; `rotate()` on PIN change kills every ticket in flight. Proved by `ServerSessionAuthTest`. |
| GET | `/api/call/web` | `uri == "/api/call/web"` (GET) | `SESSION` via the table | `cid`, state, `incoming`/`self` flags | Ownership is by address: `sameAddr(c.ip, clientIp)`. |
| POST | `/api/call/web` `action=ring` | `when (json.optString("action"))`, `"ring"` arm | `SESSION` via the table | starts a call to the phone | Refuses when a call is already up (`webCall?.state` / `callEngine?.state` check). Accepts `name` verbatim (`json.optString("name", …)`) → shown on the phone's ringing UI. Confirm it is rendered as text, not markup. |
| POST | `/api/call/web` `action=accept`/`decline` | `"accept", "decline"` arm | `SESSION` via the table | own call only | Requires `c.outgoing && sameAddr(c.ip, clientIp)` and `c.state == "ringing"`. Correct. |
| POST | `/api/call/web` `action=end` | `"end"` arm | `SESSION` via the table + own call | own call | **Fixed.** The handler now refuses when there is no live call, when the `cid` is present but not the running one, and when `sameAddr(c.ip, clientIp)` fails — the same three questions accept/decline already asked. A blank `cid` no longer means "whichever call is up", and ending a call that is not running answers a failure instead of a hollow `success`. |
| GET | `/api/webcall/down` | `uri == "/api/webcall/down"` (GET) | `SESSION` via the table + own call | own call (cid + address) | `call == null \|\| call.cid != cid \|\| call.state != "accepted"` rejects a wrong/absent `cid`; the cid alone was never enough, because `GET /api/call/web` publishes it to every authorized tab. `sameAddr(call.ip, clientIp)` now binds the media leg to the tab that owns the call. Chunked media body. |
| POST | `/api/webcall/up` | `uri == "/api/webcall/up"` (POST) | `SESSION` via the table + own call | own call (cid + address) | Base64 frame batch decoded and handed to the engine. `sameAddr(call.ip, clientIp)` refuses a frame batch from a tab that does not own the call, and `bodyTooLarge(session, WEB_CALL_BODY_LIMIT)` (256 KiB) answers 413 from the declared `Content-Length` **before** `parseBody` reads it — NanoHTTPD 2.3.1 has no ceiling of its own, and `serve()` gets the headers with the body still in the socket. See §6 R-1. |
| GET | `/api/peers` | `uri == "/api/peers"` (GET) | `SESSION` via the table | whole-LAN peer table | Exposes every client IP, name, last-seen, file count and byte total (the `byIp` map built in that branch). This is a network map and is the most sensitive `SESSION` response. It is fine *if* the PIN is on; with PIN disabled it is free reconnaissance for any host on the subnet. |

---

## 4. Routes that ask a second, object-scoped question after the session

| Method | Path | Code | Object scope | Notes |
| --- | --- | --- | --- | --- |
| GET | `/api/files` | `uri == "/api/files"` (GET) | `receivedFileIsVisibleTo(file, clientIp)` | The isolation model. Public files are the shared room; directed files are visible to sender and receiver only. This was three hand-written copies of `file.targetClientIp != clientIp && file.uploadedByIp != clientIp` comparing **raw strings**, which locked an owner out of its own file when one side of the record was the dual-stack form (`::ffff:192.168.1.9`) of the other; the predicate folds both through `sameAddr`. `isMine` is `sameAddr(file.uploadedByIp, clientIp)` — retract-what-I-sent, not merely "visible to me". Reports `from`/`to` IPs to the reader — LAN addresses of other clients, which the PRD's "who can see what" question should decide on deliberately. |
| GET | `/download/{id}` | `handleFileDownload` | same predicate (`receivedFileIsVisibleTo`) | 403 `Access forbidden for this device IP` on mismatch. A plain anchor cannot set a header, so the browser passes a ticket scoped `download:<id>` (`?ticket=`), minted over that exact id and the caller's address; a ticket for another id is 401. See §6 S-2. |
| GET | `/download-folder?path=` | `handleFolderDownload` | **derived from the files inside** | `sanitizeRelativePath` + canonical-path check against `WebDrop_Received`. Traversal was never the hole; **ownership** was, and a folder is not a record, so the check is derived: if any `SharedFile` lives under the canonical prefix and is not visible to this client, the whole zip is 403. Two bounds the old code lacked, both reached *before* anything is written: `FOLDER_ZIP_MAX_ENTRIES` = 2 000 and `FOLDER_ZIP_MAX_BYTES` = 512 MiB, tested against a lazy `take(MAX + 1)` walk so a huge tree costs one entry, not a minute. `Content-Length` is declared, so `folderTooLarge` answers 413 and deletes nothing. A browser link carries a ticket scoped `folder:<path>` rather than a session. The catch no longer reflects `e.message` into the body. |
| POST | `/upload` | `handleFileUpload` | writes under `WebDrop_Received` | Two credentials: the room credential (session or PIN), or a pairing upload proof bound to `Content-Length` (`pairedSenderIsAuthorized`). `uploadBudgetBytes(cacheDir)` is tested against the declared length before `parseBody`, so a file this phone cannot hold is refused instead of buffered. `?path=` controls the subdirectory (`sanitizeRelativePath`). Duplicate names get a timestamp suffix rather than overwrite. `hasFreeSpaceFor` preflights the destination — a second, different measurement of a different volume. Paired uploads are decrypted to a `.{uuid}.part` landing file and plain copies land there too, renamed onto the final name only after the write completes; a refusal therefore leaves nothing that looks like a received file, and `start()` calls `sweepPartialUploads` to collect any landing file a killed process left behind. The failure reply no longer quotes `e.message`, which used to name a path under the app's private storage. |
| POST | `/chatupload` | `handleChatUpload` | attachment store | Same bytes, never enters the hosted file list, and the same cache-volume ceiling (`uploadBudgetBytes`). `tempFile.copyTo(target, overwrite = true)` is the only `overwrite = true` in the file, and it is safe: `target` is prefixed with a freshly generated UUID, so the name it could overwrite is one this request made milliseconds earlier. A second client cannot clobber a first one's attachment. |
| GET | `/chatfile/{id}` | `handleChatFileDownload` | `chatFileIsVisibleTo` | **Fixed.** This route checked nothing: any client that passed the room gate could read any attachment whose id it knew, including one still unsent in the phone's own composer. The rule is now the one `/api/messages` already applied to *text* — an attachment is readable by the tab that uploaded it, or by anyone who may read a message that carries it (`threadIsVisibleTo`: open room, the reader's own address book, the host's book when watching it, a group the phone opened). 404 rather than 403, because a UUID id that answers "real but not yours" tells a probing tab who is chatting. A browser reads it with a ticket scoped `chatfile:<id>`. |
| GET | `/preview/{id}` | `serveFileInline` | same predicate (`receivedFileIsVisibleTo`) | Inline serving: no `Content-Disposition: attachment`, and `serveFileInline` passes the stored `mimeType` straight through. **Correction, on `security/upload-names`:** this row used to claim an uploaded HTML/SVG file is served "with its own declared type", which is not what the code does — the type of a web upload is *guessed from the saved extension* by `guessMimeType`, a table with no HTML and no SVG row, so `page.html` is offered as `application/octet-stream` (`FileNameAdversarialTest`). What was still true, and is now fixed: a file whose recorded type is active — a phone-side share whose content URI reports `text/html` — was served inline with no `nosniff` and no CSP anywhere. Inline previews and inline chat attachments now carry `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`, and add `Content-Security-Policy: sandbox` when the recorded type is a document (`text/html`, XHTML, SVG, XML) by `addInlineSafety`: a browser navigated straight to such a URL gets a unique origin with script disabled, while media and PDF responses keep the headers their viewers expect. Range requests are still honoured. |
| POST | `/api/delete` | `deleteReceivedFile` | `receivedFileIsVisibleTo` + must live under `WebDrop_Received` | Two-layer check: visibility then canonical root prefix. Deleting a file outside the received dir is refused. Correct. |
| GET | `/api/messages` | `uri == "/api/messages"` (GET) | `threadIsVisibleTo` | The subtlest scoping in the server, now in **one function** instead of four locals inside the loop — hoisted because `/chatfile/{id}` has to answer the identical question and did not. Depends on `sameAddr`/`normalizeAddr` (IPv4/IPv6 folding) being right; a client that presents as the host address reads the host's private threads. `fromWeb` and `mine` flags decide what the tab may retract. |
| POST | `/api/messages` | `uri == "/api/messages"` (POST) | `thread` restricted to known groups or `ip:` form, else broadcast | A client can address a thread to *any* IP (`"ip:$to"`), i.e. write into a conversation it will not read but another host will. Sender name is self-declared. Body capped at `JSON_BODY_LIMIT` — see §6 P-1 for why 64 KiB on the app's most text-shaped route is a decision worth revisiting. `attachmentId` is checked against `chatFileIsVisibleTo`: attaching is publishing, so it cannot be a way around the read rule (A-6). |
| DELETE | `/api/messages?id=` | `uri == "/api/messages"` (DELETE) | `sameAddr(owner.clientIp, clientIp)` | 403 on mismatch. Id must be in the query string because NanoHTTPD discards a DELETE body (`session.parms["id"]`; NanoHTTPD reads a body only for POST/PUT) — a legitimate exception to the "no meaningful data in query strings" rule, but note that `id` is a message id, not a credential. |

Anything else that no rule matches takes the default rule — prefix `*`, `SESSION` for every verb —
so it is refused without a room credential and answers 404 once authenticated.
`AuthorizationPolicyTest` pins that default, because a newly added route inheriting `SESSION` is
what keeps "forgot to add a rule" from meaning "public".

---

## 5. Response headers on protected routes

No route sends CORS headers any more. `serve()` is a thin wrapper over `dispatch()` that adds, to
every answer:

```
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
```

The pair is applied once at the boundary — after a route has built its reply, before NanoHTTPD writes
it — so it covers the page, JSON, 401, 404, fonts, inline previews and the SSE stream without any
route having to remember. The old wildcard:

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS
Access-Control-Allow-Headers: Content-Type, X-Pulse-Auth
```

is gone rather than narrowed. Nothing in this repo is a cross-origin client: the page is served from
the same origin, every URL in it is relative, and `X-Pulse-Client` is a request header the tab sends
to its own origin. So `*` granted a capability no shipped client used, to any web page the user
happened to have open — including the 401 that confirms a room exists. A future cross-origin
integration must opt in deliberately instead of inheriting the grant.

JSON answers are `Cache-Control: no-store` by default: the wrapper adds it when a route has not chosen
a policy of its own, so the file list and message page are not left for the back button to find. SSE,
the media leg and login already set their own `no-store`; the page and its fonts stay cacheable.

Still missing: `Content-Security-Policy` (the page is inline CSS/JS and blob media, so this needs a
browser pass first, not a guessed header) and `Strict-Transport-Security` on the TLS listener.
Backlog R-6 carries the remainder.

No URL credential outlives its purpose any more: a ticket is minutes long, names one object, and
belongs to one address. `Referrer-Policy: no-referrer` now applies to the whole origin, so a
ticket-bearing URL is not sent as `Referer` when a link leaves the page.

---

## 6. Gaps this matrix exposes

Ordered by the risk the code shows, not by how hard the fix is. **Confirmed** = visible in
source and actionable now; **Needs test** = the code does not prove it is broken.

The `S-n` numbers here are the **same** items as `docs/operations/Issue-Backlog.md`, which is the
canonical list: `A-n` rows are findings this matrix is the only place describing, and `P-n`/`H-n`
describe the public surface and the browser. Two rows carry ids that only existed here and have been
renumbered to match the backlog — `S-1b` (the browser half of `S-1`) and `S-8` (the paired-sender
scope, which had no backlog entry until now).

### Credential design

| ID | Status | Finding |
| --- | --- | --- |
| **S-1** | **Fixed** | Was: the PIN *was* the session token (`pinCode ?: "ok"`), with no expiry, no revocation and no per-session identity. Now: `SessionStore` (`app/security/SessionStore.kt`) mints a 256-bit random session, keeps only its SHA-256, expires it after 12 hours, caps the store at 64 live sessions, revokes singly or all, and clears on PIN rotation (`updatePinSecurity`). In-memory is the policy: a server restart logs every browser out. Proved by `SessionStoreTest` (9) and `ServerSessionAuthTest` (13) — including that the token is not the PIN, that logout kills it, and that rotating the PIN kills every tab. |
| **S-2** | **Fixed** | Was: the PIN, then a 12-hour session, rode in `?auth=` — into browser history, the SSE URL, link markup and `Referer`. Now no route reads a credential from the query string: `isAuthorized` is header-only, `WebClientHtml.kt` builds plain URLs, and the five GET route families a header cannot reach accept a **ticket** instead (`UrlTicket.kt`, `POST /api/ticket`). A ticket is an HMAC-SHA256 over `scope` + normalized client address + expiry, valid five minutes, minted only by an authenticated caller, and rotated to death on PIN change (`updatePinSecurity`) or restart (in-memory secret). A ticket opens exactly the object in its scope and only from the address that asked; it is rejected on every state-changing route. Proved by `UrlTicketTest` (11) and `ServerSessionAuthTest` (7 new cases: no session in a query string, one-object scope, no ticket on the API, events stream, mint requires a credential, unknown scope refused, PIN change kills in-flight tickets). |
| **S-1b** | **Fixed** | Was: `authToken = data.token \|\| pin` — the tab cached the typed PIN whenever a response had no token. Now the tab refuses to proceed without a real session and shows an error instead, so nothing in `localStorage` is the PIN. `lockOut()` also posts `/api/auth/logout` first, so closing a tab does not leave a session alive on the phone until expiry. This is item 6 of the backlog's `S-1` fix list, not a separate finding. |
| **S-8** | Confirmed | `pairedSenderIsAuthorized` covers `/upload` only, so a paired phone with the PIN rotated on the host can send files but cannot pull its own messages or thread list. Product gap worth documenting either way. |

### Access-control scope

| ID | Status | Finding |
| --- | --- | --- |
| **A-1** | **Fixed** | `/chatfile/{id}` had no ownership check while `/download/` and `/preview/` both had one — cross-client attachment read, including a file the phone had stored but never sent. It now applies the same thread rule `/api/messages` uses, from one shared function, plus the uploader's own grant so a composer can still preview what it just picked. Six cases in `ObjectScopeTest`, and unlike the folder-scope tests these **can** show a genuine foreign owner over loopback: the attachment is planted as one the *phone* wrote into another address's thread. |
| **A-2** | **Fixed** | `/download-folder?path=` had no ownership check at all — any authenticated client could zip any received folder, including one another visitor's directed downloads. A folder is not a record, so the check is derived from the records inside it: any `SharedFile` under the canonical prefix that `receivedFileIsVisibleTo` rejects makes the whole request 403 (the visibility loop in `handleFolderDownload`). Covered by `ObjectScopeTest`; the uploader-of-records case is asserted as *allowed*, because over loopback the requester is always also the recorded owner and the forbid branch cannot be reached honestly. |
| **A-3** | **Fixed** | `POST /api/call/web action=end` ended another client's call, and a blank `cid` ended whichever call was running. `end` now asks the same three questions accept/decline asked — is there a live call, does the `cid` match it, is this tab the one it was made for (the `"end"` arm of `POST /api/call/web`) — and reports failure rather than `success` when there is nothing to end. `/api/webcall/down` and `/api/webcall/up` gained the same `sameAddr` binding, since `GET /api/call/web` publishes the cid to every authorized tab. |
| **A-4** | Confirmed | `/api/relay/pair-poll` returns before the relay proof check (the `pair-poll` branch of `handleRelay`, above `pairManager` and `verifyRelayAuth`) on a caller-supplied `reqId`. |
| **A-5** | **Fixed** | The chain position used to *be* the policy: everything above the one global `isAuthorized` was unauthenticated, so a route inserted in the wrong place was public with no signal. Authorization now comes from a single table (`AuthorizationPolicy.forRequest`) evaluated once at the top of `serve()`, and every path it does not name takes a default rule that requires a session. A new route is therefore closed until someone writes a rule for it, and `AuthorizationPolicyTest` pins both the default and the rule-to-matrix-row match. |
| **A-6** | **Fixed** | A-1's escalation path, left open by the fix itself: `chatFileIsVisibleTo` was asked by the download route and by nothing else, so `POST /api/messages` attached *any* id the caller named to its own message — and because the read rule follows the message, publishing was a second way to deliver the bytes. Attaching now asks the same predicate as reading (the `chatFileIsVisibleTo` check in `POST /api/messages`). An id this server has never seen stays ignored rather than refused, which is what it did before, so the response shape does not move. Found by `CrossAddressScopeTest`, which is the only suite here with two addresses to point at the question. |

### Public-surface abuse

| ID | Status | Finding |
| --- | --- | --- |
| **P-1** | **Fixed** (size and frequency) | Every route that reads a body answers a ceiling first, from the declared `Content-Length` and before `parseBody` runs: `TYPING_BODY_LIMIT` 4 KiB and `LOGIN_BODY_LIMIT` 8 KiB on the two public POSTs, `JSON_BODY_LIMIT` 64 KiB on `/api/call/web`, `/api/delete`, `POST /api/messages` and `/api/ticket`, `RELAY_BODY_LIMIT` 256 KiB — four envelopes — on the pairing/call/relay group, `WEB_CALL_BODY_LIMIT` 256 KiB on `/api/webcall/up`, and `uploadBudgetBytes(cacheDir)` on `/upload` and `/chatupload`. All answer 413 with `Cache-Control: no-store`. **Frequency is now measured too:** `serve()` runs a per-address token bucket (120 burst, 30/s) before authorization, and presence has a tighter one-per-400 ms bucket; both are in memory and reset on restart. Proved by `RequestRateLimiterTest`, `TypingPresenceTest` and `UploadCeilingTest`. What remains open is not frequency but *expansion*: only the relay envelope is capped against a decoded payload, not the per-frame media batches. |
| **P-2** | **Fixed** | `POST /api/typing` accepted a self-declared `sender` with no authentication and let any host evict another's presence by name. It now requires the room credential, keys the presence map by the caller's normalized address, and limits writes to one per 400 ms per address. Reading presence stays public and non-sensitive. `TypingPresenceTest` covers the refusal, the eviction attempt, name de-duplication and the 429. |
| **P-3** | Partly fixed | `/api/info` discloses `deviceId` to any LAN host, which is a documented product choice. The `tlsError` half is no longer free text: exception class plus a path-redacted, length-bounded message (`describeTlsFailure`, proved by `WebClientSafetyTest`). |

### Headers and browser-side rendering

| ID | Status | Finding |
| --- | --- | --- |
| **H-1** | **Fixed** | `/preview/` inlined the stored MIME type with no `nosniff` and no CSP. An uploaded `.html` was already inert (the MIME guess has no HTML row), but a phone-side share reporting `text/html` or `image/svg+xml` could run on this origin if the URL was opened directly. Inline responses now carry `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`, and add `Content-Security-Policy: sandbox` when the recorded type is a document (`text/html`, XHTML, SVG, XML) — so media and PDF viewers are untouched while a direct navigation is a script-less, unique-origin document. Range responses carry the same headers. Proved by `FileNameAdversarialTest`. |
| **H-2** | **Fixed** | Was: `Access-Control-Allow-Origin: *` on every response including 401/404, no `Referrer-Policy`, no `Cache-Control: no-store` on private JSON. Now: the wildcard and both companion `Allow-*` headers are removed; `serve()` adds `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer` to every answer, and `Cache-Control: no-store` to JSON that did not set its own policy. Proved by `ResponseHardeningTest`. |
| **H-3** | **Fixed (audit)** | Every caller-supplied string — `sender`, `action=ring`'s `name`, device names, filenames, thread titles, `X-Pulse-Client`, message bodies — reaches the DOM through `textContent` / `createTextNode` / input values. The 15 `innerHTML` assignments all receive SVG constants defined in the same file; the one helper that takes markup as an argument is only called with `FE_*` constants. No `eval`, `new Function`, `document.write`, `insertAdjacentHTML`, `outerHTML`, `srcdoc` or `javascript:` sink exists. `WebClientSafetyTest` pins all four properties against the served page, and the server-side `deviceName` is escaped by `esc()` at all three insertion points. |

---

## 7. Required tests

The table-driven suite must, for **each row above**, assert status and body-shape for:

1. no credential, PIN required;
2. wrong credential (once, then repeatedly → expect lockout at 8 distinct guesses);
3. correct PIN via `X-Pulse-Auth`;
4. correct PIN via `?auth=` — **assert 401** (no credential in a query string), and the same for a
   session token; then a ticket minted by an authenticated caller — 200 on its exact object, 401 on any
   other, 401 on a state-changing route, and 401 after a PIN change;
5. PIN disabled (`isPinRequired = false`) — assert which routes a LAN host can then reach;
6. pairing upload proof against `/upload`, and the *same* proof against `/api/files`,
   `/download/{id}`, `/chatfile/{id}`, `/api/messages` — all must be rejected (`pairedSenderIsAuthorized`);
7. wrong `pid` / replayed proof / proof bound to a different `Content-Length`;
8. relay `post` proof used on `pull` and vice versa (role binding, in `handleRelay`'s
   `verifyRelayAuth(pid, role, proof)`);
9. **two clients, A and B**: A writes, A targets B, B reads, A reads B's directed
   file/thread/attachment. Expect 403 or absence — this is the suite that catches A-1, A-2, A-3.
   *Written:* `ObjectScopeTest` covers the call-object half completely (A cannot end, subscribe to, or
   feed B's call) and the attachment half (A-1) — the latter is reachable honestly over loopback because
   the server can be told, through its own public `addMessage`, that a file the *phone* wrote belongs
   to another address's thread. What it still cannot do from `127.0.0.1` is present as two distinct
   *sockets*, which is what A-2's forbid branch and item 16 need.
   **Solved, without an injected resolver or a second stack:** `serve()` is public and takes the
   `IHTTPSession` interface, so `TabSession` hands a route an address of its own choosing and no
   socket is opened at all. `CrossAddressScopeTest` runs the directed file, the direct thread and the
   attachment against three addresses — and it was the two-address form of the attachment question
   that surfaced A-6, which every single-address suite had passed. That this harness is load-bearing
   rather than convenient is asserted, not assumed: inverting each rule turns exactly one test red.
   The socket version of this file passed every test vacuously, because Robolectric reports
   `127.0.0.1` for a client bound to `127.0.0.2`.
10. `/api/relay/pair-poll` with a `reqId` the caller never requested;
11. oversized body on every body-reading POST: 1 MiB over cap on `/api/typing`,
    `/api/messages`, `/api/pair/*`, `/api/webcall/up`, `/upload`, `/chatupload` — assert a
    bounded rejection, not a buffered temp file and not a crash.
    *Written:* `ObjectScopeTest` asserts 413 on `/api/webcall/up` and `/api/call/web` from a
    declared 400 MB `Content-Length`, *and* that a normal-sized action still passes — a ceiling test
    that only checks the refusal cannot tell a cap from a block. `UploadCeilingTest` covers the rest:
    `/upload` and `/chatupload` against a measured cache volume, both public POSTs, the honest small
    case on each, and one case that cannot be faked — a 40 MB body offered to a 32 MiB budget where
    the assertion is that the client *could not finish sending it*, which distinguishes a refusal
    before the read from a refusal after it. Frequency is now tested separately: `RequestRateLimiterTest`
    covers burst/refill/bound, `TypingPresenceTest` proves a presence loop is 429 while another address
    is unaffected. Under-declaring is now tested as well: `RequestSmugglingTest` shows that bytes past
    a declared body used to be parsed as a second request, and that a reply to any request declaring
    `Content-Length` or `Transfer-Encoding` now ends the connection so the surplus dies with the
    socket; a bodyless poll still reuses the connection, which is the control in the same file.
    *Added here, because per-route sizes do not cover the relations between them:*
    `CeilingPrecedenceTest` asserts that the wrapper ceiling clears the envelope ceiling, that a
    refusal at the login route is not charged to the PIN lockout — with the control that twelve
    distinct wrong guesses are, so the loop cannot pass vacuously — and that a stranger hitting a
    `SESSION` route with an oversized body is refused for credentials, not told the route is
    size-sensitive. Each case was checked to go red only when its own rule is broken.
12. path traversal on `/download-folder?path=`: `../../`, `%2e%2e%2f`, absolute paths, `\`
    separators, NUL, `..%00`, Unicode-normalised fullwidth `．．／`, symlink under
    `WebDrop_Received`, depth beyond 4 segments.
    *Written:* `FileNameAdversarialTest` now walks both layers — every encoding above is offered to
    `/upload?path=` and to `/download-folder?path=`, and each is asserted to land as a printable-ASCII
    relative name inside `WebDrop_Received` with its payload intact. A symlink into the received folder
    is refused by the canonical check, which is the layer the sanitizer cannot reach.
    `ObjectScopeTest` keeps the ownership case (a folder with someone else's file in it). Still open:
    depth is asserted at the sanitizer's own limit (4), and a link is only reachable locally, so this is
    defence in depth rather than a remote hole.
13. filename adversarial on `/upload?path=`: control characters, `\r\n` header injection into
    `Content-Disposition`, HTML/JS payloads, bidirectional overrides, 300-char names, reserved
    names, duplicate concurrent uploads of the same name;
    *Written:* `FileNameAdversarialTest` (19 cases, `security/upload-names`). The rules it pins are in
    `HTTP-API.md` §Names; two of them were not true before this branch:
    a name whose extension alone exceeded the 120-character budget computed a negative stem count, threw,
    and the upload answered **500** for bytes that arrived fine; and the landing file was named after the
    final name, so two tabs sending to one `?path=` copied into the same path at the same time — one of
    the two then answered 500 for a source that the other had already renamed away. Both are fixed here
    and both are mutation-checked: reverting either change turns the corresponding test red.
    The name-collision *policy* (timestamp suffix vs a refusal) is still undecided — backlog `R-2`.
14. `/preview/{id}` with an uploaded `.html` and `.svg` — assert it cannot execute in the
    page's origin;
    *Half written:* the offered `.html` is served `application/octet-stream`, because the MIME type is
    guessed from the saved extension by a table with no HTML or SVG row and nothing consults the uploaded
    bytes. That closes the route's own half. It does not close the claim: this server sends **no CSP**
    (`R-6`) and the shipped tab builds its file list from `/api/files` names, so whether a name can
    execute is a question about `WebClientHtml.kt`'s rendering (`R-5`), not about this response.
15. every response shape, not just data routes: `GET /`, a 401, a 404, a font, an `OPTIONS` preflight
    and a successful mutation — each must carry `X-Content-Type-Options: nosniff` and
    `Referrer-Policy: no-referrer`, and none may carry an `Access-Control-Allow-*` header; every JSON
    answer must be `Cache-Control: no-store` unless the route set its own policy.
    *Written:* `ResponseHardeningTest` asserts exactly that, once per shape, against the wire — a 401
    and an authorized `/api/files` both carry `no-store`. The font case also pins that the global pair
    did not displace `Cache-Control: public, max-age=…`, since NanoHTTPD stores headers by name.
15. session lifecycle (after S-1): issue, use, expire, logout, PIN change, server restart,
    revoked-token reuse;
16. SSE: `/api/events` from two tabs, assert an event meant for one does not disclose the
    other's payload.
17. the route table itself: every `AuthorizationPolicy` rule names a row in this file, an unlisted
    verb on a known path falls back to `SESSION`, an unknown path is authenticated before 404, and
    only the five URL-bearing GET families accept a ticket. *Written:* `AuthorizationPolicyTest`.

Where a test contradicts this matrix, the code is wrong until proven otherwise — update
§§2-4 from the test, never from the README.

---

## 8. Compatibility rules for whoever implements the fixes

- **Keep every route path and response shape** in §§2-4 until a compatibility test proves a
  migration. Old phones and open tabs are in the wild with these strings hard-coded.
- `?auth=` was **removed outright** rather than deprecated: a tab holding a session in a URL gets 401,
  runs its own `lockOut()`, and re-authenticates, which is the intended behaviour for a leaked
  credential. `WebClientHtml.kt` was updated in the same change so no shipped page sends one. The
  ticketed replacements cover the two reasons the query string existed — an `<a download>` and
  `EventSource` — neither of which can set a header.
- The credential header is read by `isAuthorized` (`x-pulse-auth`) and written by the tab in
  `authHeaders()`, `lockOut()`, `uploadSingleFile()` and `uploadChatFile()`; `TransferManager`
  sends it for phone-to-phone uploads. There is no CORS `Allow-Headers` list any more (§5), so
  renaming the header means updating those call sites together.
- Do not rename the pairing aliases, preference keys (`localdrop_identity`,
  `localdrop_e2e_identity`) or `WebDrop_Received` — every existing pairing and received-file
  index points at those strings (`IdentityKeyPair.KEY_ALIAS`/`PREFS_NAME`,
  `PulseDropServer.RECEIVED_DIR_NAME`).
- IP-scoping (`sameAddr`/`normalizeAddr`) is load-bearing for every file and call route, and after
  `security/object-scope` it is expressed in exactly one place (`receivedFileIsVisibleTo`)
  rather than four hand-written copies. Replacing it with session scoping is the right direction, but
  it changes who can read history — write the migration before the code.
- **The `end` refusal is a deliberate response-shape change** and the only one in §§3-4. `action=end`
  used to answer `success:true` for any authenticated caller and for no call at all; it now answers
  400 `No open call` / `Unknown call` / `Not your call`. The shipped tab ignores this (`callAction`
  is fire-and-forget with a `.catch`), so nothing in the APK breaks, but a client written against the
  old always-success cannot be told apart from a caller that was refused — say so in the release
  notes rather than pretending the shape was stable.
- Folder zips can now fail with 413 (`Too many files to zip`, `Folder is too large to zip`) where they
  previously either succeeded or filled `cacheDir`. Both carry `Cache-Control: no-store`.
- **Uploads and JSON POSTs can now fail with 413 too**, which no shipped client renders — the tab shows
  its generic failure text. That is the intended outcome for a body the phone cannot hold; a third-party
  client that retried the same oversized file forever was already broken, it was just breaking the phone
  as well as itself. The limits are ceilings, not quotas: raising one later changes nothing observable
  for a compliant client.
- Uploads now land as `.{uuid}.part` and are renamed on completion. Nothing can ever show one in the
  *browser* list — that list is the in-memory `sharedFiles` registry, not a directory walk — and the
  on-phone Receive page does **not** enumerate the folder either: `MainViewModel.receiveDir`
  is a path string shown to the user, and the phone's own file list is the
  same registry. **Correction:** an earlier note here claimed the Receive page walks the directory and
  could show a stale partial; it does not, and nothing on this device reads that folder by listing it.
  A process killed between the write and the rename would still leave the file behind, invisible and
  permanent; `start()` now sweeps `.{uuid}.part` under the received folder (root and `?path=`
  subdirectories) before the listener binds, and `PartialUploadSweepTest` pins what is and is not
  swept. The landing name used to be derived from the final name, which is what made two
  concurrent sends of one `?path=` share a copy target — if you change it again, keep it caller-proof
  (a name built from `?path=` can be made to collide by anyone in the room) and sweep for the shapes you
  retire.
- **A caller-chosen name that used to fail now succeeds.** A name whose extension alone exceeded the
  120-character budget (`a.` + 300 characters) computed a negative stem count and threw, and `/upload`
  answered 500 `Upload failed`; the truncation now falls back to the plain 120-character cut, so that
  upload succeeds under a name the client did not exactly ask for. No response shape changed and no
  compliant client can see the difference, but a client that was *retrying* on that 500 will now stop
  retrying — which is the intended outcome, not a regression.
- **`PairRepository.myDeviceId()` throws.** Since `security/identity-key`, a phone that cannot read its
  own identity key raises `IdentityKeyUnavailableException` rather than writing a readable fallback key
  and carrying on, so any new caller that stamps an identity goes through the nullable door
  (`myDeviceIdOrNull`, `PairHandshake.myPubOrNull`) and decides for itself what "no identity" means.
  What that did to the wire, all of it on a branch that used to be a crash or a half-state:
  `/api/pair/request` can answer `{"success":false,"error":"not queued"}` where it always said
  `waiting:true`; a decline can arrive with no `pub`; `/api/info`'s `deviceId` can be `""` on a phone
  whose keystore is refusing (P-3's disclosure claim is unchanged on a healthy one); and sealed mail
  without a `from` is not sent at all. No shipped client reads a decline's `pub`, and the requester's
  own `sendPairRequest` never gets as far as the wire when it has no key to put in the request, so the
  only new body a legacy peer can meet is the `success:false`, which it does not parse.
