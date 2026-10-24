# Authorization Matrix

Every HTTP route the phone serves, what it demands of the caller, and what object scope it
applies. This is the reference the route code is audited against: **a new route is not done
until it has a row here and a test in the table-driven suite** (see §7).

Source of truth: `app/src/main/java/com/pulsedrop/app/server/PulseDropServer.kt`, branch
`main` at `98c2a40`. Line numbers refer to that commit. Where behaviour differs from intent,
the row says so — this document records what the code *does*, not what a future release
should do.

Rows marked **Fixed** describe `security/browser-sessions`, where the credential design changed;
the line numbers in those rows no longer point anywhere useful and are kept only for archaeology.

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
4. A **live session token** in `?auth=` — transitional only, until the download-ticket work removes
   the query string entirely (S-2). **The PIN is no longer accepted here**, which is the part that was
   leaking into history, link markup and `Referer`.
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

- **`clientIp` is the object-scope key** for the file list, downloads, previews, deletes, and
  chat threads (`:1517`, `:1552`, `:1586`, `:2214`, `:1626`). It comes from the socket peer.
  For a LAN server this is reasonably strong, but it means *one host behind a shared NAT or
  hotspot is indistinguishable from another*, and an attacker who can spoof or share the
  phone's own address (`normalizeAddr`/`sameAddr` folding, `:1617`, `:1407`) inherits that
  host's private threads and directed files.
- **`X-Pulse-Client` is caller-supplied and only decoded for display** (`:1059-1063`). It is
  never an authorization input. Correct.
- **`sender` in `/api/messages` POST and `/api/typing` POST is caller-supplied** (`:1669`,
  `:1167`) and is used as the typing map key (`:1173`). Typing presence can therefore be
  forged or evicted by name collision. See §6 P-2.
- **`?path=` on `/upload` is taken from the query string** (`:1873`), passed through
  `sanitizeRelativePath` (`:2160`), and used as a relative write target under
  `WebDrop_Received` (`:1882`). Traversal and absolute paths are stripped and the result is
  clamped to the last four segments, so it cannot escape the root — but it can create
  subdirectories anywhere inside it.

---

## 2. Tier A — routes handled with no credential

| Method | Path | Line | Guard | Scope | Intentional public? | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| OPTIONS | *any* | `:1074` | none | — | yes | 200 + wildcard CORS for paths that do not exist. Reveals route shape when paired with method probing. |
| GET | `/`, `/index.html` | `:1096` | none | full page | yes | Redirects to `https://host:tlsPort/` when the TLS listener is up (`:1099-1107`). Page body itself contains no credential; it embeds `deviceName` and a "requiresPin" boolean. |
| GET | `/fonts/google-sans-{regular,medium,semibold,bold}.ttf` | `:1082`, `WEB_FONTS` `:2255` | none | 4 fixed resources | yes | Whitelisted map, no path interpolation. Cache-Control `public, max-age=604800`. Safe as written. |
| GET | `/api/info` | `:1118` | none | device metadata | yes — documented | Leaks `deviceName`, `version`, LAN `clientIp`, `deviceId`, TLS status and **`tlsError` string** (`:1137`), capability list. `tlsError` is the highest-risk field: it must be verified never to contain key material, paths, or exception text with file names. |
| GET | `/api/typing` | `:1154` | none | presence list | yes — declared non-sensitive (`:1152`) | Returns active sender *names* only, bounded by `TYPING_SENDER_LIMIT = 64`. |
| POST | `/api/typing` | `:1162` | **none** | writes presence | questionable | Body parsed and `sender` accepted verbatim from JSON (`:1167`). Any LAN host can inject presence for an arbitrary name, evict another client's presence by reusing its name (`:1173` `remove`), or fill the 64-slot map to deny presence updates to real users. Rate-limited by nothing. |
| POST | `/api/auth` | `:1434` | IP lockout only | — | yes | Mints a **random session** (`SessionStore.issue()`) and returns that. It no longer returns `pinCode`, and it sends `Cache-Control: no-store` so a minted session cannot be retained by a browser cache or a shared proxy. Wrong PIN → no `token` field at all. See §6 S-1 (fixed). |
| POST | `/api/auth/logout` | new | presents its own credential | one session | yes | Revokes exactly the session in `X-Pulse-Auth`. Self-scoped, so it needs no further authority; an already-dead token still answers 200 with `"revoked":false` because logging out twice is not an error. |
| POST | `/api/pair/request` | `:1475` | key-gated downstream | pairing | by design | PIN-exempt. `PairManager.handleIncomingRequest` must be the enforcing layer; unbounded rate — pairing CPU cost is ECDH, so this is the app's cheapest CPU-exhaustion target. |
| POST | `/api/pair/response` | `:1480` | key-gated downstream | pairing | by design | |
| POST | `/api/pair/msg` | `:1484` | key-gated downstream | pairing | by design | |
| POST | `/api/call/invite` | `:1486` | key-gated downstream | calls | by design | PIN-exempt; a LAN host can attempt to raise the incoming-call UI. Must confirm `CallEngine.handleInvite` rejects unknown pairing ids. |
| POST | `/api/call/response` | `:1487` | key-gated downstream | calls | by design | |
| POST | `/api/call/end` | `:1491` | key-gated downstream | calls | by design | |
| POST | `/api/relay/post` | `:1474`, `handleRelay` `:260` | per-device relay proof | mailbox | by design | `verifyRelayAuth(pid, "post", proof)` (`:270`) — role-scoped proof, 64 KiB envelope cap (`:298`). Sound shape. |
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
| GET | `/api/events` | `:1205` | `isAuthorized` `:1206` | global event stream | SSE. Streams `call`, `pair` and other events to **every** authenticated tab; `pushWebEvent("pair", exceptIp=...)` (`:1477`) shows the code does suppress by IP for some events, so check what `webCall`/`webThreads` payloads leak across tabs. Capped by `maxEventStreams` with oldest evicted (`:1210-1214`) — good. `markActivity()` (`:1207`) keeps the server alive, so this is an anti-idle-stop vector for an unauthenticated… no: it is protected. Fine. |
| GET | `/api/call/web` | `:1232` | `isAuthorized` `:1235` | `cid`, state, `incoming`/`self` flags | Ownership is by address: `sameAddr(c.ip, clientIp)` (`:1253`). |
| POST | `/api/call/web` `action=ring` | `:1269` | `isAuthorized` | starts a call to the phone | Refuses when a call is already up (`:1270`). Accepts `name` verbatim (`:1287`) → shown on the phone's ringing UI. Confirm it is rendered as text, not markup. |
| POST | `/api/call/web` `action=accept`/`decline` | `:1299` | `isAuthorized` | own call only | Requires `c.outgoing && sameAddr(c.ip, clientIp)` (`:1302`). Correct. |
| POST | `/api/call/web` `action=end` | `:1309` | `isAuthorized` | **any** `webCall` | **Gap.** Unlike accept/decline, `end` checks neither ownership nor even requires a matching `cid` — a blank `cid` ends whatever call is live (`:1311`). Any authenticated client (with PIN disabled: any LAN host) can hang up the phone's browser call. Compare `:1302` for the intended pattern. |
| GET | `/api/webcall/down` | `:1329` | `isAuthorized` `:1330` | `cid` must match live accepted call | `:1333` correctly rejects a wrong/absent `cid`. Chunked media body. |
| POST | `/api/webcall/up` | `:1344` | `isAuthorized` `:1345` | `cid` must match, engine must have a web leg | Base64 frame batch decoded and handed to the engine (`:1359`) with **no size cap on the JSON body or the decoded bytes**. `session.parseBody` has already buffered it to a temp file by then. See §6 R-1. |
| GET | `/api/peers` | `:1372` | `isAuthorized` `:1373` | whole-LAN peer table | Exposes every client IP, name, last-seen, file count and byte total (`:1375-1430`). This is a network map and is the most sensitive tier B response. It is fine *if* the PIN is on; with PIN disabled it is free reconnaissance for any host on the subnet. |

---

## 4. Tier C — routes behind the global check at `:1508`

| Method | Path | Line | Object scope | Notes |
| --- | --- | --- | --- | --- |
| GET | `/api/files` | `:1511` | `targetClientIp == null \|\| == clientIp \|\| uploadedByIp == clientIp` (`:1517`) | The isolation model. Public files are the shared room; directed files are visible to sender and receiver only. Reports `from`/`to` IPs to the reader (`:1530-1531`) — LAN addresses of other clients, which the PRD's "who can see what" question should decide on deliberately. |
| GET | `/download/{id}` | `:1543` | same predicate (`:1552`) | 403 `Access forbidden for this device IP` on mismatch. **Accepts the credential in `?auth=`** — the browser builds it that way (`WebClientHtml.kt:2095-2097`) because a plain anchor cannot set a header. See §6 S-2. |
| GET | `/download-folder?path=` | `:1560` | **none beyond the root** | `sanitizeRelativePath` + canonical-path check against `WebDrop_Received` (`:1735-1743`). No traversal, but **any authenticated client may zip and read any received folder**, including one another client's directed folder. `?path=` has no per-owner check at all. Also: builds the whole ZIP in `cacheDir` with no size cap (`:1747-1754`) and reflects the exception message in the body (`:1770`). |
| POST | `/upload` | `:1566` | writes under `WebDrop_Received` | Two credentials: PIN, or a pairing upload proof bound to `Content-Length` (`:1008-1016`). `?path=` controls the subdirectory (`:1873`). Duplicate names get a timestamp suffix rather than overwrite (`:1886-1893`). Free-space preflight (`:1895`). Paired uploads are decrypted from the temp file (`:1905`). |
| POST | `/chatupload` | `:1571` | attachment store | Same bytes, never enters the hosted file list. `tempFile.copyTo(target, overwrite = true)` (`:2045`) — the only `overwrite = true` in the file; check where `target` comes from and whether a second client can clobber a first one's attachment. |
| GET | `/chatfile/{id}` | `:1576` | **none** | **Gap.** After the global check this handler looks the id up in `chatAttachments` (`:2064`) and serves it. There is no `targetClientIp`, no owner, no thread test — unlike `/download/` and `/preview/`, which both have one (`:1552`, `:1586`). Any client that can pass the PIN gate can read any attachment whose id it knows. `?dl=1` toggles disposition (`:2072`). |
| GET | `/preview/{id}` | `:1582` | same predicate (`:1586`) | Inline serving: no `Content-Disposition: attachment`, and `serveFileInline` (`:2078`) passes the stored `mimeType` straight through. **An uploaded HTML/SVG file is served inline with its own declared type.** Needs `X-Content-Type-Options: nosniff` and a forced-octet-stream rule for active content. Range requests are honoured (`:2104`). |
| POST | `/api/delete` | `:1593` | `targetClientIp`/`uploadedByIp` + must live under `WebDrop_Received` (`:2210-2227`) | Two-layer check: ownership then canonical root prefix (`:2217-2221`). Deleting a file outside the received dir is refused. Correct. |
| GET | `/api/messages` | `:1606` | thread filter: own address book, host's book if watching the host, open groups, or broadcast (`:1625-1636`) | The subtlest scoping in the server. Depends on `sameAddr`/`normalizeAddr` (IPv4/IPv6 folding) being right; a client that presents as the host address reads the host's private threads. `fromWeb` and `mine` flags (`:1649`) decide what the tab may retract. |
| POST | `/api/messages` | `:1662` | `thread` restricted to known groups or `ip:` form, else broadcast (`:1675-1681`) | A client can address a thread to *any* IP (`"ip:$to"`), i.e. write into a conversation it will not read but another host will. Sender name is self-declared (`:1669`). No body-size cap. |
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

`?auth=` in a URL plus no `Referrer-Policy` is the concrete pairing that makes §6 S-2 worse
than credential-in-history alone.

---

## 6. Gaps this matrix exposes

Ordered by the risk the code shows, not by how hard the fix is. **Confirmed** = visible in
source and actionable now; **Needs test** = the code does not prove it is broken.

### Credential design

| ID | Status | Finding |
| --- | --- | --- |
| **S-1** | **Fixed** | Was: the PIN *was* the session token (`pinCode ?: "ok"`), with no expiry, no revocation and no per-session identity. Now: `SessionStore` (`app/security/SessionStore.kt`) mints a 256-bit random session, keeps only its SHA-256, expires it after 12 hours, caps the store at 64 live sessions, revokes singly or all, and clears on PIN rotation (`updatePinSecurity`). In-memory is the policy: a server restart logs every browser out. Proved by `SessionStoreTest` (9) and `ServerSessionAuthTest` (12) — including that the token is not the PIN, that logout kills it, and that rotating the PIN kills every tab. |
| **S-2** | Half fixed | The server **no longer accepts the PIN in a query string**, which was the leak that mattered: history, the SSE URL, link markup and `Referer` were all carrying a long-lived room secret. A tab that still holds one gets 401, runs its own `lockOut()`, and re-authenticates. Still open: the tab continues to build `?auth=<session>` for anchor-triggered downloads (`WebClientHtml.kt:2097, 2161, 2405, 3042, 3534, 3893`), so a *session* — not the PIN — appears in URLs. Closing that needs short-lived single-use download tickets scoped to one file id and action, which is the next PR. |
| **S-3** | **Fixed** | Was: `authToken = data.token \|\| pin` — the tab cached the typed PIN whenever a response had no token. Now the tab refuses to proceed without a real session and shows an error instead, so nothing in `localStorage` is the PIN. `lockOut()` also posts `/api/auth/logout` first, so closing a tab does not leave a session alive on the phone until expiry. |
| **S-4** | Confirmed | `pairedSenderIsAuthorized` covers `/upload` only (`:1009`), so a paired phone with the PIN rotated on the host can send files but cannot pull its own messages or thread list. Product gap worth documenting either way. |

### Access-control scope

| ID | Status | Finding |
| --- | --- | --- |
| **A-1** | Confirmed | `/chatfile/{id}` has no ownership check (`:2063-2075`) while `/download/` and `/preview/` both do. Cross-client attachment read. |
| **A-2** | Confirmed | `/download-folder?path=` has no ownership check (`:1560`, `:1733`). Any authenticated client can zip any received folder. |
| **A-3** | Confirmed | `POST /api/call/web action=end` ends another client's call (`:1309-1311`); accept/decline correctly bind to `c.ip` (`:1302`). |
| **A-4** | Confirmed | `/api/relay/pair-poll` returns before the relay proof check (`:261-266`) on a caller-supplied `reqId`. |
| **A-5** | Needs test | Everything above `:1508` is unauthenticated — the chain position *is* the policy. A route inserted in the wrong place is public with no signal. This is what the `RouteRegistry`/`AuthorizationPolicy` refactor in the PRD exists to replace. |

### Public-surface abuse

| ID | Status | Finding |
| --- | --- | --- |
| **P-1** | Confirmed | No rate limit anywhere except the PIN-guess counter. Tier A POST bodies are parsed and buffered (`session.parseBody`, `:1165`, `:1266`, `:1437`, `:1469`) before any size test — unauthenticated disk-fill in `cacheDir`. Only the relay envelope is capped (`MAX_RELAY_ENVELOPE`, `:298`). |
| **P-2** | Confirmed | `POST /api/typing` accepts a self-declared `sender` with no authentication (`:1167`) and lets any host evict another's presence by name (`:1173-1176`); 64 entries is the only bound. |
| **P-3** | Needs test | `/api/info` discloses `tlsError` text (`:1137`) and `deviceId` (`:1140`) to any LAN host. |

### Headers and browser-side rendering

| ID | Status | Finding |
| --- | --- | --- |
| **H-1** | Confirmed | `/preview/` inlines the stored MIME type with no `nosniff` and no forced-attachment rule for HTML/SVG (`:2078-2091`, `:2095`). Stored-XSS-by-opening-a-shared-file within the authenticated origin. |
| **H-2** | Confirmed | `Access-Control-Allow-Origin: *` on every response including 401/404 (`:2229`), with no `Referrer-Policy` and no `Cache-Control: no-store` on private JSON. |
| **H-3** | Needs test | Caller-supplied strings rendered by the phone or the tab: `sender`, `name` on `action=ring` (`:1287`), device names, filenames, `X-Pulse-Client` (`:1059`). Audit every `innerHTML` in `WebClientHtml.kt` against these. |

---

## 7. Required tests

The table-driven suite must, for **each row above**, assert status and body-shape for:

1. no credential, PIN required;
2. wrong credential (once, then repeatedly → expect lockout at 8 distinct guesses);
3. correct PIN via `X-Pulse-Auth`;
4. correct PIN via `?auth=` — **and after the S-2 fix, assert this returns 401**;
5. PIN disabled (`isPinRequired = false`) — assert which routes a LAN host can then reach;
6. pairing upload proof against `/upload`, and the *same* proof against `/api/files`,
   `/download/{id}`, `/chatfile/{id}`, `/api/messages` — all must be rejected (`:1009`);
7. wrong `pid` / replayed proof / proof bound to a different `Content-Length`;
8. relay `post` proof used on `pull` and vice versa (role binding, `:269-270`);
9. **two clients, A and B**: A writes, A targets B, B reads, A reads B's directed
   file/thread/attachment. Expect 403 or absence — this is the suite that catches A-1, A-2, A-3;
10. `/api/relay/pair-poll` with a `reqId` the caller never requested;
11. oversized body on every tier A and tier C POST: 1 MiB over cap on `/api/typing`,
    `/api/messages`, `/api/pair/*`, `/api/webcall/up`, `/upload`, `/chatupload` — assert a
    bounded rejection, not a buffered temp file and not a crash;
12. path traversal on `/download-folder?path=`: `../../`, `%2e%2e%2f`, absolute paths, `\`
    separators, NUL, `..%00`, Unicode-normalised fullwidth `．．／`, symlink under
    `WebDrop_Received`, depth beyond 4 segments;
13. filename adversaria on `/upload?path=`: control characters, `\r\n` header injection into
    `Content-Disposition`, HTML/JS payloads, bidirectional overrides, 300-char names, reserved
    names, duplicate concurrent uploads of the same name;
14. `/preview/{id}` with an uploaded `.html` and `.svg` — assert it cannot execute in the
    page's origin;
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
- `?auth=` must be **accepted with a deprecation window or refused outright** — but decide it
  in one place, and remember `WebClientHtml.kt:3893` (SSE) and `:2097` (downloads) are the two
  reasons it exists. Ticketed downloads have to work for an `<a href>` and for
  `EventSource`, neither of which can set a header.
- `X-Pulse-Auth` is in the CORS `Allow-Headers` (`:2232`); renaming the credential header
  requires updating both that list and `WebClientHtml.kt:1626`, `:2407`, `:3535`.
- Do not rename the pairing aliases, preference keys (`localdrop_identity`,
  `localdrop_e2e_identity`), or `WebDrop_Received` — every existing pairing and received-file
  index points at those strings (`IdentityKeyPair.kt:23-24`, `:2237`).
- IP-scoping (`sameAddr`/`normalizeAddr`) is load-bearing for four routes. Replacing it with
  session scoping is the right direction, but it changes who can read history — write the
  migration before the code.
