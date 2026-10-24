# Authorization Matrix

Every HTTP route the phone serves, what it demands of the caller, and what object scope it
applies. This is the reference the route code is audited against: **a new route is not done
until it has a row here and a test in the table-driven suite** (see §7).

Source of truth: `app/src/main/java/com/pulsedrop/app/server/PulseDropServer.kt`, branch
`main` at `98c2a40`. Line numbers refer to that commit. Where behaviour differs from intent,
the row says so — this document records what the code *does*, not what a future release
should do.

Rows marked **Fixed** describe the three branches stacked on that commit —
`security/browser-sessions` (the credential design), `security/object-scope` (who may touch *which*
object) and `security/upload-ceilings` (what a body may cost) — and their rows carry line numbers from
the tip of the last branch that touched them, which is where `:NNNN` in this file is searchable from.
Every other number still refers to `98c2a40`, and the two security branches inserted roughly two hundred
lines into `PulseDropServer.kt` above those rows, so they have drifted and are **not** reliable pointers.
Auditing them is a task of its own; converting the route table from `:NNNN` to the dispatch condition it
names (`if (uri == "/upload")`) would make it self-checking and is the better fix than renumbering.

---

## 1. How the guard actually works

`serve()` (`:1049`) is a single flat chain. Authorization is **not** applied uniformly; it is
applied at one of three points depending on where the route sits in the chain:

| Tier | Where | Routes |
| --- | --- | --- |
| **A — public** | Handled before any credential check | `OPTIONS`, `/`, `/index.html`, `/fonts/*`, `/api/info`, `/api/typing`, `/api/auth`, `POST /api/pair/*`, `POST /api/call/*`, `POST /api/relay/*` |
| **B — inline check** | Route body calls `isAuthorized()` itself | `/api/threads`, `/api/events`, `/api/call/web`, `/api/webcall/up`, `/api/webcall/down`, `/api/peers` |
| **C — global check** | One `isAuthorized()` at `:1508` gates everything below it | `/api/files`, `/download/*`, `/download-folder`, `/upload`, `/chatupload`, `/chatfile/*`, `/preview/*`, `/api/delete`, `/api/messages` |

Consequences that are easy to miss:

- **A route added anywhere above `:1508` is unauthenticated by default.** Tier C is an
  accident of position, not a policy. This is the single most important reason this file
  exists.
- **`OPTIONS` returns 200 for every path with no credential** (`:1074`), including paths that
  do not exist. Route shape is readable by any LAN host.
- **A method not explicitly handled falls through to 404 after the auth check**, so
  `PUT /upload` and `DELETE /upload` are PIN-protected, while `DELETE /api/typing` is
  unauthenticated and returns 404. The 404/401 split therefore leaks which routes exist and
  which methods they accept.
- **A GET request to a POST-only route keeps the chain moving.** `GET /upload` matches the
  `/upload && POST` test at `:1566`, fails the method, and falls through — past `/chatupload`,
  `/chatfile/` and `/preview/`, whose prefixes it does not match — to the 404 at `:1728`. Same
  for `GET /api/delete` and `GET /api/messages`. All of these are therefore PIN-protected
  (the check at `:1508` runs first), and `GET /api/auth` behaves the same way: it does not
  match the POST test at `:1434`, reaches `:1508`, and gets **401 rather than 404** — a
  detectable difference between "this route exists and needs the PIN" and "this path is
  unknown", before any credential is ever submitted.
- Every branch below `:1508` that does not match a handler returns 404 with CORS headers
  (`:1728`), so an authenticated wrong path and an authenticated missing path are
  indistinguishable. Good.

### 1.1 Credentials the server accepts

`isAuthorized()` accepts, in order:

1. **Nothing at all**, when `!isPinRequired || pinCode.isNullOrBlank()`. Every tier B and C route
   becomes public. This is the "PIN disabled" mode and is the default posture for a browser client
   that never logs in.
2. A **live session token** in `X-Pulse-Auth` — the random value `/api/auth` minted for that tab
   (`SessionStore.isValid`).
3. The **PIN itself**, in `X-Pulse-Auth` only. Kept deliberately: `TransferManager.kt:276`
   authenticates a phone-to-phone upload with the room PIN, and a phone running an older build has no
   login step with which to obtain a session. This is an app-to-app compatibility allowance, not a
   browser path — no browser is ever handed the PIN.
4. A **URL ticket** in `?ticket=` — accepted only on the five GET route families a browser cannot
   put a header on (`/api/events`, `/download/{id}`, `/download-folder`, `/chatfile/{id}`,
   `/preview/{id}`). It is an HMAC over the exact object, the caller's normalized address and a
   five-minute expiry, minted by an authenticated `POST /api/ticket`, and it dies on restart or PIN
   change. No session and no PIN is accepted in a query string (S-2).
5. A pairing upload proof — `X-Pair-Id` + `X-Pair-Auth` + `Content-Length` verified against
   the shared E2E key — **but only for `uri == "/upload"`** (`pairedSenderIsAuthorized`). Paired
   phones cannot reach any other route without the PIN.

There **is** a session concept now: `/api/auth` mints a random 256-bit session and the PIN is no
longer a bearer credential for a browser. See §6 S-1 (fixed) and S-2 (half fixed — the server stopped
accepting the PIN in a query string; the tab still puts its *token* in one until the download-ticket
work lands).

Failed guesses are counted per source IP and lock out after `PIN_MAX_ATTEMPTS = 8` distinct
wrong values with exponential backoff capped at `PIN_LOCKOUT_MAX_STEPS = 4` (`:1019-1043`).
Identical repeats are not counted (`:1034`), so a stale tab cannot lock itself out forever —
but an attacker who varies the guess does trip it. Lockout is keyed on IP only and is in
memory: it resets on process death.

### 1.2 Identity the server trusts but should not

- **`clientIp` is the object-scope key** for the file list, downloads, previews, deletes, folder
  zips, both call media legs and the chat threads (`:1517`, `:1552`, `:1586`, `:2214`, `:1626`).
  It comes from the socket peer.
  For a LAN server this is reasonably strong, but it means *one host behind a shared NAT or
  hotspot is indistinguishable from another*, and an attacker who can spoof or share the
  phone's own address (`normalizeAddr`/`sameAddr` folding, `:1617`, `:1407`) inherits that
  host's private threads and directed files. After `security/object-scope` the file routes answer
  that question through **one** predicate — `receivedFileIsVisibleTo` (`:526`) — instead of four
  copies that compared raw strings and so disagreed with `sameAddr` about `::ffff:` forms. The
  remaining scope-by-address risk is unchanged and is what session-scoped objects would fix.
- **`X-Pulse-Client` is caller-supplied and only decoded for display** (`:1059-1063`). It is
  never an authorization input. Correct.
- **`sender` in `/api/messages` POST and `/api/typing` POST is caller-supplied** (`:1669`,
  `:1167`) and is used as the typing map key (`:1173`). Typing presence can therefore be
  forged or evicted by name collision. See §6 P-2.
- **`?path=` on `/upload` is taken from the query string** (dispatched by `uri == "/upload"`, handled in
  `handleFileUpload`), passed through `sanitizeRelativePath`, and used as a relative write target under
  `WebDrop_Received`. Traversal and absolute paths are stripped and the result is clamped to the last
  four segments, so it cannot escape the root — but it can create subdirectories anywhere inside it.
  The character class is what answers the rest: everything outside `A-Z a-z 0-9 space . _ - ( ) [ ] { }`
  becomes `_`, leading and trailing dots are trimmed, and each segment is truncated to 120 characters.
  That single function is also the only thing between a query string and a `Content-Disposition`, so its
  rules are pinned by name rather than by line number in `HTTP-API.md` §Names.

---

## 2. Tier A — routes handled with no credential

| Method | Path | Line | Guard | Scope | Intentional public? | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| OPTIONS | *any* | `:1074` | none | — | yes | 200 + wildcard CORS for paths that do not exist. Reveals route shape when paired with method probing. |
| GET | `/`, `/index.html` | `:1096` | none | full page | yes | Redirects to `https://host:tlsPort/` when the TLS listener is up (`:1099-1107`). Page body itself contains no credential; it embeds `deviceName` and a "requiresPin" boolean. |
| GET | `/fonts/google-sans-{regular,medium,semibold,bold}.ttf` | `:1082`, `WEB_FONTS` `:2255` | none | 4 fixed resources | yes | Whitelisted map, no path interpolation. Cache-Control `public, max-age=604800`. Safe as written. |
| GET | `/api/info` | `:1118` | none | device metadata | yes — documented | Leaks `deviceName`, `version`, LAN `clientIp`, `deviceId`, TLS status and a `tlsError` string, capability list. **Fixed:** `tlsError` is now `describeTlsFailure(e)` — the exception class, plus a message with path-shaped runs replaced by `<path>` and the whole thing capped at 200 characters; the full exception still goes to Logcat. `deviceId` remains disclosed and is P-3's open half. |
| GET | `/api/typing` | `:1154` | none | presence list | yes — declared non-sensitive (`:1152`) | Returns the display names reported by active clients, bounded by `TYPING_SENDER_LIMIT = 64`. Names are display-only and capped at 60 characters. |
| POST | `/api/typing` | `:1162` | **none** | writes presence | yes, with a bound | Body parsed and `sender` taken from JSON, but the entry is keyed by the **normalized client address**, so `typing:false` clears only the caller's own indicator; a name is display-only and truncated to 60 characters. A full room refuses a *new* address rather than evicting an existing one. Still unauthenticated and unrate-limited by design — it is a typing indicator, not a channel. Proved by `TypingPresenceTest`. |
| POST | `/api/auth` | `:1434` | IP lockout only | — | yes | Mints a **random session** (`SessionStore.issue()`) and returns that. It no longer returns `pinCode`, and it sends `Cache-Control: no-store` so a minted session cannot be retained by a browser cache or a shared proxy. Wrong PIN → no `token` field at all. See §6 S-1 (fixed). |
| POST | `/api/auth/logout` | new | presents its own credential | one session | yes | Revokes exactly the session in `X-Pulse-Auth`. Self-scoped, so it needs no further authority; an already-dead token still answers 200 with `"revoked":false` because logging out twice is not an error. |
| POST | `/api/pair/request` | `:1475` | key-gated downstream | pairing | by design | PIN-exempt. `PairManager.handleIncomingRequest` must be the enforcing layer; unbounded rate — pairing CPU cost is ECDH, so this is the app's cheapest CPU-exhaustion target. |
| POST | `/api/pair/response` | `:1480` | key-gated downstream | pairing | by design | |
| POST | `/api/pair/msg` | `:1484` | key-gated downstream | pairing | by design | |
| POST | `/api/call/invite` | `:1486` | key-gated downstream | calls | by design | PIN-exempt; a LAN host can attempt to raise the incoming-call UI. Must confirm `CallEngine.handleInvite` rejects unknown pairing ids. |
| POST | `/api/call/response` | `:1487` | key-gated downstream | calls | by design | |
| POST | `/api/call/end` | `:1491` | key-gated downstream | calls | by design | |
| POST | `/api/relay/post` | `:1474`, `handleRelay` `:260` | per-device relay proof | mailbox | by design | `verifyRelayAuth(pid, "post", proof)` (`:270`) — role-scoped proof, 64 KiB envelope cap (`:298`). Sound shape. The POST carrying an envelope is bounded separately, at four envelopes (`:1639`): the body is the envelope plus `role`, `to` and quoting, so a ceiling equal to the envelope's own refuses the last few dozen bytes of mail the relay would have accepted — and refuses them with a status the sender cannot act on, rather than the relay's own `too large`. |
| POST | `/api/relay/pull` | as above | per-device relay proof | own mailbox | by design | `role = "pull"`; returns only `relayMailbox.pending(senderDid)` (`:280`) — the proof's device id, not the caller's. Correct isolation. |
| POST | `/api/relay/pair-poll` | `:261-266` | **none** | pair answers | **gap** | This is the one relay sub-route that returns before `pairManager` is consulted (`:267` is below it). `takePairAnswer(json.optString("reqId"))` is driven by a caller-supplied `reqId` with no proof of any kind. Whether that is exploitable depends on what a pair answer contains and how guessable `reqId` is — it must be tested, not assumed. |

`PUT`/`DELETE` to any `/api/pair/*`, `/api/call/*`, `/api/relay/*` path is *not* tier A (the
branch requires `method == POST`, `:1466`); it falls through to `:1508` and is PIN-protected,
then 404s.

---

## 3. Tier B — routes that check inline

| Method | Path | Line | Guard | Scope | Notes |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/threads` | `:1188` | `isAuthorized` `:1189` | all open threads | Returns `id`, `title`, `kind`, `members` for every thread the phone opened to browsers. No per-client filter: any authenticated client sees all `webThreads`. Acceptable only if thread membership is genuinely not confidential between clients — verify against `webThreads` population. |
| GET | `/api/events` | `:1368` | `isAuthorized` or `events` ticket | global event stream | SSE. `EventSource` cannot send a header, so this route also accepts a ticket (`?ticket=`) minted for the `events` scope by an authenticated caller. Streams `call`, `pair` and other events to **every** authenticated tab; `pushWebEvent("pair", exceptIp=...)` shows the code does suppress by IP for some events, so check what `webCall`/`webThreads` payloads leak across tabs. Capped by `maxEventStreams` with oldest evicted — good. `markActivity()` keeps the server alive, so this is an anti-idle-stop vector for an authenticated tab. |
| POST | `/api/ticket` | `:1683` | `isAuthorized` | mints one URL ticket | Body capped at 64 KiB from the declared length. The requested scope must be `events` or one of the four object families (`download:`, `folder:`, `chatfile:`, `preview:`), so the route cannot mint a permission it would not spend, and no state-changing route accepts one. The ticket is an HMAC-SHA256 over scope + normalized client address + a five-minute expiry, so nothing is stored; `rotate()` on PIN change kills every ticket in flight. Proved by `ServerSessionAuthTest`. |
| GET | `/api/call/web` | `:1232` | `isAuthorized` `:1235` | `cid`, state, `incoming`/`self` flags | Ownership is by address: `sameAddr(c.ip, clientIp)` (`:1253`). |
| POST | `/api/call/web` `action=ring` | `:1269` | `isAuthorized` | starts a call to the phone | Refuses when a call is already up (`:1270`). Accepts `name` verbatim (`:1287`) → shown on the phone's ringing UI. Confirm it is rendered as text, not markup. |
| POST | `/api/call/web` `action=accept`/`decline` | `:1299` | `isAuthorized` | own call only | Requires `c.outgoing && sameAddr(c.ip, clientIp)` (`:1302`). Correct. |
| POST | `/api/call/web` `action=end` | `:1369` | `isAuthorized` + own call | **Fixed.** The handler now refuses when there is no live call, when the `cid` is present but not the running one, and when `sameAddr(c.ip, clientIp)` fails (`:1384`) — the same three questions accept/decline already asked. A blank `cid` no longer means "whichever call is up", and ending a call that is not running answers a failure instead of a hollow `success`. |
| GET | `/api/webcall/down` | `:1404` | `isAuthorized` `:1405` + own call | `:1408` rejects a wrong/absent `cid`; the cid alone was never enough, because `GET /api/call/web` publishes it to every authorized tab. `sameAddr(call.ip, clientIp)` (`:1415`) now binds the media leg to the tab that owns the call. Chunked media body. |
| POST | `/api/webcall/up` | `:1481` | `isAuthorized` `:1482` + own call | Base64 frame batch decoded and handed to the engine. `sameAddr(call.ip, clientIp)` (`:1499`) refuses a frame batch from a tab that does not own the call, and `bodyTooLarge(session, WEB_CALL_BODY_LIMIT)` (`:1487`, 256 KiB) answers 413 from the declared `Content-Length` **before** `parseBody` reads it — NanoHTTPD 2.3.1 has no ceiling of its own, and `serve()` gets the headers with the body still in the socket. See §6 R-1. |
| GET | `/api/peers` | `:1372` | `isAuthorized` `:1373` | whole-LAN peer table | Exposes every client IP, name, last-seen, file count and byte total (`:1375-1430`). This is a network map and is the most sensitive tier B response. It is fine *if* the PIN is on; with PIN disabled it is free reconnaissance for any host on the subnet. |

---

## 4. Tier C — routes behind the global check at `:1508`

| Method | Path | Line | Object scope | Notes |
| --- | --- | --- | --- | --- |
| GET | `/api/files` | `:1618` | `receivedFileIsVisibleTo(file, clientIp)` (`:526`) | The isolation model. Public files are the shared room; directed files are visible to sender and receiver only. This was three hand-written copies of `file.targetClientIp != clientIp && file.uploadedByIp != clientIp` comparing **raw strings**, which locked an owner out of its own file when one side of the record was the dual-stack form (`::ffff:192.168.1.9`) of the other; the predicate folds both through `sameAddr`. `isMine` (`:1630`) is `sameAddr(uploadedByIp, clientIp)` — retract-what-I-sent, not merely "visible to me". Reports `from`/`to` IPs to the reader — LAN addresses of other clients, which the PRD's "who can see what" question should decide on deliberately. |
| GET | `/download/{id}` | `handleFileDownload` | same predicate (`receivedFileIsVisibleTo`) | 403 `Access forbidden for this device IP` on mismatch. A plain anchor cannot set a header, so the browser passes a ticket scoped `download:<id>` (`?ticket=`), minted over that exact id and the caller's address; a ticket for another id is 401. See §6 S-2. |
| GET | `/download-folder?path=` | `:1560`, `handleFolderDownload` `:1836` | **derived from the files inside** | `sanitizeRelativePath` + canonical-path check against `WebDrop_Received`. Traversal was never the hole; **ownership** was, and a folder is not a record, so the check is derived: if any `SharedFile` lives under the canonical prefix and is not visible to this client (`:1852-1861`), the whole zip is 403. Two bounds the old code lacked, both reached *before* anything is written: `FOLDER_ZIP_MAX_ENTRIES` = 2 000 and `FOLDER_ZIP_MAX_BYTES` = 512 MiB, tested against a lazy `take(MAX + 1)` walk (`:1868`) so a huge tree costs one entry, not a minute. `Content-Length` is declared, so `folderTooLarge` answers 413 and deletes nothing. A browser link carries a ticket scoped `folder:<path>` rather than a session. The catch no longer reflects `e.message` into the body (`:1902`). |
| POST | `/upload` | `:1732`, `handleFileUpload` `:2046` | writes under `WebDrop_Received` | Two credentials: PIN, or a pairing upload proof bound to `Content-Length` (`:1117-1127`). `uploadBudgetBytes(cacheDir)` is tested against the declared length before `parseBody` (`:2057`), so a file this phone cannot hold is refused instead of buffered. `?path=` controls the subdirectory (`:2074`). Duplicate names get a timestamp suffix rather than overwrite (`:2086-2093`). Free-space preflight for the destination (`:2096`) — a second, different measurement of a different volume. Paired uploads are decrypted to `.{name}.part` (`:2111`) and plain copies land there too (`:2118`), renamed onto the final name only after the write completes (`:2120`); a refusal therefore leaves nothing that looks like a received file. The failure reply no longer quotes `e.message`, which used to name a path under the app's private storage. |
| POST | `/chatupload` | `:1737`, `handleChatUpload` `:2245` | attachment store | Same bytes, never enters the hosted file list, and the same cache-volume ceiling (`:2247`). `tempFile.copyTo(target, overwrite = true)` (`:2261`) is the only `overwrite = true` in the file, and it is safe: `target` is prefixed with a freshly generated UUID (`:2260`), so the name it could overwrite is one this request made milliseconds earlier. A second client cannot clobber a first one's attachment. |
| GET | `/chatfile/{id}` | `:1719` | `chatFileIsVisibleTo` (`:564`) | **Fixed.** This route checked nothing: any client that passed the room gate could read any attachment whose id it knew, including one still unsent in the phone's own composer. The rule is now the one `/api/messages` already applied to *text* — an attachment is readable by the tab that uploaded it, or by anyone who may read a message that carries it (`threadIsVisibleTo`, `:542`: open room, the reader's own address book, the host's book when watching it, a group the phone opened). 404 rather than 403, because a UUID id that answers "real but not yours" tells a probing tab who is chatting. A browser reads it with a ticket scoped `chatfile:<id>`. |
| GET | `/preview/{id}` | `:1685` | same predicate (`:1689`) | Inline serving: no `Content-Disposition: attachment`, and `serveFileInline` passes the stored `mimeType` straight through. **Correction, on `security/upload-names`:** this row used to claim an uploaded HTML/SVG file is served "with its own declared type", which is not what the code does — the type of a web upload is *guessed from the saved extension* by `guessMimeType`, a table with no HTML and no SVG row, so `page.html` is offered as `application/octet-stream` (`FileNameAdversarialTest`). What was still true, and is now fixed: a file whose recorded type is active — a phone-side share whose content URI reports `text/html` — was served inline with no `nosniff` and no CSP anywhere. Inline previews and inline chat attachments now carry `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`, and add `Content-Security-Policy: sandbox` when the recorded type is a document (`text/html`, XHTML, SVG, XML) by `addInlineSafety`: a browser navigated straight to such a URL gets a unique origin with script disabled, while media and PDF responses keep the headers their viewers expect. Range requests are still honoured. |
| POST | `/api/delete` | `:1696` | `receivedFileIsVisibleTo` (`:2355`) + must live under `WebDrop_Received` | Two-layer check: visibility then canonical root prefix. Deleting a file outside the received dir is refused. Correct. |
| GET | `/api/messages` | `:1755` | `threadIsVisibleTo` (`:542`) | The subtlest scoping in the server, now in **one function** instead of four locals inside the loop — hoisted because `/chatfile/{id}` has to answer the identical question and did not. Depends on `sameAddr`/`normalizeAddr` (IPv4/IPv6 folding) being right; a client that presents as the host address reads the host's private threads. `fromWeb` and `mine` flags decide what the tab may retract. |
| POST | `/api/messages` | `:1821` | `thread` restricted to known groups or `ip:` form, else broadcast (`:1831-1841`) | A client can address a thread to *any* IP (`"ip:$to"`), i.e. write into a conversation it will not read but another host will. Sender name is self-declared (`:1829`). Body capped at `JSON_BODY_LIMIT` (`:1823`) — see §6 P-1 for why 64 KiB on the app's most text-shaped route is a decision worth revisiting. `attachmentId` is checked against `chatFileIsVisibleTo`: attaching is publishing, so it cannot be a way around the read rule (A-6). |
| DELETE | `/api/messages?id=` | `:1699` | `sameAddr(owner.clientIp, clientIp)` (`:1707`) | 403 on mismatch. Id must be in the query string because NanoHTTPD discards a DELETE body (`:1701-1703`) — a legitimate exception to the "no meaningful data in query strings" rule, but note that `id` is a message id, not a credential. |

Anything else reaching `:1728` → 404, CORS headers, after having been authenticated.

---

## 5. Response headers on protected routes

`addCorsHeaders()` (`:2229`) is applied to essentially every response, including
`unauthorized()` (`:1004`) and 404 (`:1729`):

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS
Access-Control-Allow-Headers: Content-Type, X-Pulse-Auth
```

- `*` with a **header-borne credential** is not the classic ambient-credential CSRF (no
  cookies are used anywhere, so no cross-site automatic auth). It does mean **any page open in
  any browser on the LAN can read every tier-A route from JavaScript**, and — with the PIN
  disabled — every tier B/C route too. That is the real exposure: a phishing page on a
  victim's laptop can call `/api/peers`, `/api/messages`, `/download/{id}` silently.
- `X-Pulse-Client` is sent by the browser client (`WebClientHtml.kt:1627`) but is **not** in
  `Allow-Headers`, so the same code fails from any cross-origin context. The allow-list is out
  of sync with the client. Either a latent bug or an accidental partial mitigation — decide
  which on purpose.
- Missing everywhere: `X-Content-Type-Options`, `Referrer-Policy`, `Content-Security-Policy`,
  `Cache-Control: no-store` on authenticated JSON/file responses (only SSE `:1226` and the
  media leg `:1340` set it), `Strict-Transport-Security` on TLS.

No URL credential outlives its purpose any more: a ticket is minutes long, names one object, and
belongs to one address. `Referrer-Policy` is still missing (see below), so a ticket-bearing URL may
still be sent as `Referer`, but it leaks a five-minute permission rather than a room secret.

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
| **S-8** | Confirmed | `pairedSenderIsAuthorized` covers `/upload` only (`:1009`), so a paired phone with the PIN rotated on the host can send files but cannot pull its own messages or thread list. Product gap worth documenting either way. |

### Access-control scope

| ID | Status | Finding |
| --- | --- | --- |
| **A-1** | **Fixed** | `/chatfile/{id}` had no ownership check while `/download/` and `/preview/` both had one — cross-client attachment read, including a file the phone had stored but never sent. It now applies the same thread rule `/api/messages` uses, from one shared function, plus the uploader's own grant so a composer can still preview what it just picked. Six cases in `ObjectScopeTest`, and unlike the folder-scope tests these **can** show a genuine foreign owner over loopback: the attachment is planted as one the *phone* wrote into another address's thread. |
| **A-2** | **Fixed** | `/download-folder?path=` had no ownership check at all — any authenticated client could zip any received folder, including one another visitor's directed downloads. A folder is not a record, so the check is derived from the records inside it: any `SharedFile` under the canonical prefix that `receivedFileIsVisibleTo` rejects makes the whole request 403 (`:1852-1861`). Covered by `ObjectScopeTest`; the uploader-of-records case is asserted as *allowed*, because over loopback the requester is always also the recorded owner and the forbid branch cannot be reached honestly. |
| **A-3** | **Fixed** | `POST /api/call/web action=end` ended another client's call, and a blank `cid` ended whichever call was running. `end` now asks the same three questions accept/decline asked — is there a live call, does the `cid` match it, is this tab the one it was made for (`:1384`) — and reports failure rather than `success` when there is nothing to end. `/api/webcall/down` and `/api/webcall/up` gained the same `sameAddr` binding, since `GET /api/call/web` publishes the cid to every authorized tab. |
| **A-4** | Confirmed | `/api/relay/pair-poll` returns before the relay proof check (`:261-266`) on a caller-supplied `reqId`. |
| **A-5** | Needs test | Everything above `:1508` is unauthenticated — the chain position *is* the policy. A route inserted in the wrong place is public with no signal. This is what the `RouteRegistry`/`AuthorizationPolicy` refactor in the PRD exists to replace. |
| **A-6** | **Fixed** | A-1's escalation path, left open by the fix itself: `chatFileIsVisibleTo` was asked by the download route and by nothing else, so `POST /api/messages` attached *any* id the caller named to its own message — and because the read rule follows the message, publishing was a second way to deliver the bytes. Attaching now asks the same predicate as reading (`:1850`). An id this server has never seen stays ignored rather than refused, which is what it did before, so the response shape does not move. Found by `CrossAddressScopeTest`, which is the only suite here with two addresses to point at the question. |

### Public-surface abuse

| ID | Status | Finding |
| --- | --- | --- |
| **P-1** | Partially fixed | No rate limit anywhere except the PIN-guess counter. **Every route that reads a body now answers a ceiling first, from the declared `Content-Length` and before `parseBody` runs:** `TYPING_BODY_LIMIT` 4 KiB (`:1279`) and `LOGIN_BODY_LIMIT` 8 KiB (`:1584`) on the two tier A routes that take no credential, `JSON_BODY_LIMIT` 64 KiB on `/api/call/web` (`:1381`), `/api/delete` (`:1769`) and `POST /api/messages` (`:1823`), `RELAY_BODY_LIMIT` 256 KiB — four envelopes — on the tier A pairing/call/relay group (`:1639`), `WEB_CALL_BODY_LIMIT` 256 KiB on `/api/webcall/up` (`:1488`), and `uploadBudgetBytes(cacheDir)` — free space less headroom — on `/upload` (`:2067`) and `/chatupload` (`:2265`). All answer 413 with `Cache-Control: no-store`. The dispatcher is kept off `JSON_BODY_LIMIT` because that number is *equal* to `MAX_RELAY_ENVELOPE` and a relay POST is a wrapper strictly larger than the envelope inside it: equal ceilings refuse the top of the relay's own budget before the relay rule can answer. Still open: **size is not frequency**, and nothing counts requests, so a tab inside every ceiling can still ask as often as the socket allows; and only the relay envelope (`MAX_RELAY_ENVELOPE`) was ever capped against a decoded payload. |
| **P-2** | **Fixed** | `POST /api/typing` still accepts a self-declared display name with no credential, which is the declared contract for presence, but the entry is now keyed by the caller's normalized address: a host can no longer erase another's indicator by reusing its name, and a full 64-slot map refuses a new reporter instead of evicting one. The name is capped at 60 characters. Proved by `TypingPresenceTest` (four cases, two addresses). |
| **P-3** | Partly fixed | `/api/info` discloses `deviceId` to any LAN host, which is a documented product choice. The `tlsError` half is no longer free text: exception class plus a path-redacted, length-bounded message (`describeTlsFailure`, proved by `WebClientSafetyTest`). |

### Headers and browser-side rendering

| ID | Status | Finding |
| --- | --- | --- |
| **H-1** | **Fixed** | `/preview/` inlined the stored MIME type with no `nosniff` and no CSP. An uploaded `.html` was already inert (the MIME guess has no HTML row), but a phone-side share reporting `text/html` or `image/svg+xml` could run on this origin if the URL was opened directly. Inline responses now carry `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`, and add `Content-Security-Policy: sandbox` when the recorded type is a document (`text/html`, XHTML, SVG, XML) — so media and PDF viewers are untouched while a direct navigation is a script-less, unique-origin document. Range responses carry the same headers. Proved by `FileNameAdversarialTest`. |
| **H-2** | Confirmed | `Access-Control-Allow-Origin: *` on every response including 401/404 (`:2229`), with no `Referrer-Policy` and no `Cache-Control: no-store` on private JSON. |
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
   `/download/{id}`, `/chatfile/{id}`, `/api/messages` — all must be rejected (`:1009`);
7. wrong `pid` / replayed proof / proof bound to a different `Content-Length`;
8. relay `post` proof used on `pull` and vice versa (role binding, `:269-270`);
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
11. oversized body on every tier A and tier C POST: 1 MiB over cap on `/api/typing`,
    `/api/messages`, `/api/pair/*`, `/api/webcall/up`, `/upload`, `/chatupload` — assert a
    bounded rejection, not a buffered temp file and not a crash.
    *Written:* `ObjectScopeTest` asserts 413 on `/api/webcall/up` and `/api/call/web` from a
    declared 400 MB `Content-Length`, *and* that a normal-sized action still passes — a ceiling test
    that only checks the refusal cannot tell a cap from a block. `UploadCeilingTest` covers the rest:
    `/upload` and `/chatupload` against a measured cache volume, both tier A POSTs, the honest small
    case on each, and one case that cannot be faked — a 40 MB body offered to a 32 MiB budget where
    the assertion is that the client *could not finish sending it*, which distinguishes a refusal
    before the read from a refusal after it. Still untested: frequency (no rate limit exists to test)
    and a body whose declared length is a lie in the under-declaring direction, which NanoHTTPD does
    not read at all.
    *Added here, because per-route sizes do not cover the relations between them:*
    `CeilingPrecedenceTest` asserts that the wrapper ceiling clears the envelope ceiling, that a
    refusal at the login route is not charged to the PIN lockout — with the control that twelve
    distinct wrong guesses are, so the loop cannot pass vacuously — and that a stranger hitting a
    tier C route with an oversized body is refused for credentials, not told the route is
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
15. session lifecycle (after S-1): issue, use, expire, logout, PIN change, server restart,
    revoked-token reuse;
16. SSE: `/api/events` from two tabs, assert an event meant for one does not disclose the
    other's payload.

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
- `X-Pulse-Auth` is in the CORS `Allow-Headers` (`:2232`); renaming the credential header
  requires updating both that list and `WebClientHtml.kt:1626`, `:2407`, `:3535`.
- Do not rename the pairing aliases, preference keys (`localdrop_identity`,
  `localdrop_e2e_identity`), or `WebDrop_Received` — every existing pairing and received-file
  index points at those strings (`IdentityKeyPair.kt:23-24`, `:2237`).
- IP-scoping (`sameAddr`/`normalizeAddr`) is load-bearing for every file and call route, and after
  `security/object-scope` it is expressed in exactly one place (`receivedFileIsVisibleTo`, `:526`)
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
  (`ui/MainViewModel.kt:111`) is a path string shown to the user, and the phone's own file list is the
  same registry. **Correction:** an earlier note here claimed the Receive page walks the directory and
  could show a stale partial; it does not, and nothing on this device reads that folder by listing it.
  A process killed between the write and the rename still leaves the file behind, which is the open
  cleanup in `R-2`. The landing name used to be derived from the final name, which is what made two
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
