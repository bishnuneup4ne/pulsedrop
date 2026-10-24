# Prioritized Issue Backlog

Deliverable 3 of the engagement. Every item below is annotated with how well I know it to be true:

* **[confirmed]** — I read the current code at the cited line and the claim holds.
* **[needs test]** — the code shape is suspicious but exploitability depends on runtime behaviour I
  have not executed yet.
* **[recommendation]** — proposed work, not a defect claim.

Once work lands the annotation changes rather than the item disappearing: **[fixed]** means the
behaviour is implemented *and* asserted by a test on the branch, **[partly fixed]** names what is
still open in the same entry. A finding is never closed by editing its heading.

`S-`/`R-`/`K-` ids are this file's and are shared with `docs/Authorization-Matrix.md`. `A-1` below is
the matrix's own numbering for a hole this document had not itemised — filed here so the two lists
cannot disagree, and tracked as GitHub issue #17.

Baseline: `main` @ `98c2a40`, app `2.0.2` / versionCode 12. PRD reviewed the same commit, so it is
not stale. Findings the PRD asserted were re-checked here rather than copied.

---

## P0 — block a "security hardened" release

### S-1 [fixed] The PIN *is* the browser session token

`PulseDropServer.kt:1448` — `if (success) put("token", pinCode ?: "ok")`.
`isAuthorized` then compares that value to `pinCode` (`:985`). `WebClientHtml.kt:1662` additionally
does `authToken = data.token || pin`, so the browser stores the typed PIN even in the fallback path.

*Risk:* the long-lived shared secret lives in `localStorage` forever. No expiry, no per-session
revocation, no way to remove one tab without changing the PIN for everyone, and a leak of the
browser's storage is a leak of the PIN itself. Server restart does not invalidate anything.

*Fix:*
1. `SessionStore` — `SecureRandom` 32-byte token, base64url to the client, **SHA-256 only** at rest,
   in-memory map with `createdAt`, `expiresAt`, `clientIp` binding where practical.
2. `/api/auth` verifies the PIN and returns a token. It never returns `pinCode`, ever, including when
   the PIN is disabled (return a real token or `null` and let the caller rely on the public policy).
3. `isAuthorized` accepts a *valid session token* or a pairing proof — never the PIN as a bearer value.
4. `POST /api/auth/logout`; revoke-all on PIN change (`updatePinSecurity` `:674`) and an explicit
   revoke-all action for the dashboard.
5. Document: restart invalidates sessions (in-memory is the intended policy).
6. Remove the `|| pin` fallback in the browser.

*Tests:* wrong PIN rejected; valid PIN returns a token ≠ PIN; expiry honoured; logout then reuse
rejected; PIN change revokes old tokens; server restart rejects pre-restart tokens; every protected
route rejects invalid/expired/revoked tokens.
*Depends:* a working JDK/SDK build. *Rollback:* pure code, no persisted state; safe to revert.

*Shipped as `security/browser-sessions`:* items 1-6, proved by `SessionStoreTest` (9) and
`ServerSessionAuthTest` (13). The one thing still missing is the operator-facing half of item 4 —
`revokeAll()` exists and fires on a genuine PIN rotation, but there is no dashboard button that calls
it, which is `U-1`. The token is hex rather than base64url: `java.util.Base64` is API 26 and
`lintRelease` fails the build on `minSdk 24`.

### S-2 [partly fixed] PIN credentials travel in URLs

`isAuthorized:987` reads `session.parameters["auth"]`, and the browser builds it in six places —
`WebClientHtml.kt:2097, 2161, 2405, 3042, 3534, 3893` (including `/api/events`, an SSE URL that a
proxy and the browser history both retain).

*Risk:* a PIN-equivalent secret in browser history, in the events stream URL, in referrer headers,
in any diagnostics/proxy log, and shoulder-surfable in an address bar. Combined with S-1 it means
history is a credential store.

*Fix:* delete the `parameters["auth"]` branch; header-only for `fetch`; for anchor-triggered
downloads issue a **short-lived single-use ticket** bound to one file id + one action, minted by an
authenticated call; add `Cache-Control: no-store` and `Referrer-Policy: no-referrer` to sensitive
responses.
*Tests:* a ticket cannot fetch a different file; a ticket cannot be replayed; no `?auth=` remains
anywhere in the served HTML/JS; grep gate in CI for `auth=`.
*Depends:* **must follow S-1** — a ticket is theatre while the token is the PIN.

### S-3 [confirmed] Authorization is positional, not policy

`serve()` is one flat chain. Three tiers exist (see `docs/Authorization-Matrix.md` §1): tier A handled
before any check, tier B calling `isAuthorized()` inline, tier C gated by a single global check at
`:1508`. Any route inserted above `:1508` is silently unauthenticated.

*Fix:* one `AuthorizationPolicy` table consulted per route; handlers receive an authenticated
principal instead of re-reading headers; the matrix file becomes the reviewed source of truth and a
table-driven test fails CI if a route has no row.
*Rollback:* keep old route paths and response shapes untouched; this is dispatch logic only.

### S-4 [fixed] Any authenticated client can hang up the phone's active call

`:1309-1311` — `action=end` accepts when `cid` is **blank**: `if (c != null && (json.optString("cid").isBlank() || c.cid == ...))`.
Compare `accept`/`decline` two branches above (`:1302`) which correctly require
`c.outgoing && sameAddr(c.ip, clientIp)`.

*Risk:* denial of service against a live call by any tab holding the PIN — or, since the whole tier
becomes public when the PIN is off (`:981`), by any host on the LAN. Also returns `success:true`
whether or not a call existed, so it cannot be distinguished by the owner.
*Fixed in `security/object-scope`.* `end` now answers the three questions `accept`/`decline` already
asked — is there a live call, does the `cid` name it, is this tab the one the record was made for —
and reports `No open call` / `Unknown call` / `Not your call` instead of a hollow success. The same
`sameAddr` binding was missing on `/api/webcall/down` and `/api/webcall/up`, where matching the cid
was not enough precisely *because* `GET /api/call/web` hands that cid to every authorized tab.
*Tests:* `ObjectScopeTest` — a tab that does not own the call cannot end it, a blank cid ends nothing,
a wrong cid leaves the live call alone, the owner still hangs up its own call, ending a call that is
not running is not reported as success, and the down leg goes only to the owning tab.

### S-5 [fixed] `/download-folder` has no per-owner scope and no size bound

`:1560`, implementation `:1735-1754`. `sanitizeRelativePath` + canonical check prevents escaping
`WebDrop_Received`, which is good — but there is **no ownership check**, so any authenticated client
can zip and read another client's *directed* folder, and the ZIP is built into `cacheDir` with no
cap. `:1770` reflects the exception message into the body.

*Fixed in `security/object-scope`.* A folder is not a record, so ownership is **derived** from the
records inside it: any `SharedFile` whose canonical path is under the requested prefix and which
`receivedFileIsVisibleTo` rejects makes the whole download 403. `receivedFileIsVisibleTo` is now the
single predicate behind `/api/files`, `/download`, `/preview` and `/api/delete` — those were four
hand-written copies comparing raw strings, which locked an owner out of its own file whenever one side
of the pair was the dual-stack form of the same address (`::ffff:192.168.1.9`). Two bounds are tested
before a single byte is written: `FOLDER_ZIP_MAX_ENTRIES` = 2 000 and `FOLDER_ZIP_MAX_BYTES` = 512
MiB, measured against a lazy `take(MAX + 1)` walk so an enormous tree costs one entry and a `sumOf`,
not a minute of compression; both answer 413 with `Cache-Control: no-store`. The catch logs the
exception and returns a generic message.
*Tests:* an oversized folder is refused without writing a zip, a folder outside the received directory
is still not readable, and the uploader of a folder's records is not locked out of it (200 +
`application/zip`). Not provable over loopback, and therefore not faked: the 403 branch needs two
*distinct* client addresses, which a test bound to 127.0.0.1 cannot present honestly.
*Still open:* streaming instead of pre-building the zip — the cap bounds the damage, it does not
remove the temp file.

### A-1 [fixed] `/chatfile/{id}` served any client's chat attachment

After the room-wide gate this route looked the id up in `chatAttachments` and streamed the bytes: no
owner, no target, no thread test — while `/download/` and `/preview/` each had one. Any tab holding an
id had the file, including one the phone had stored but never sent.

*Fixed on `security/object-scope`.* The grant is the rule `/api/messages` already applied to *text*:
readable if you uploaded it, or if a message you are allowed to read carries it. `threadIsVisibleTo`
was hoisted out of the message loop so both routes answer from one implementation — writing the second
copy is precisely how the attachment store ended up with none. Unknown *or* not-yours is 404, because a
UUID that answers "real, but not yours" tells a probing tab who is chatting.
*Tests:* six in `ObjectScopeTest` — an unsent phone attachment is refused, a room-posted one is allowed,
one sent into another address's thread is refused, one sent into *this* address's thread is granted, a
group the phone opened grants, and the uploading tab can still preview before sending (a check that
broke the composer would be a worse bug than the one it closed).

### S-6 [confirmed] Keystore failure silently writes a raw private key

`IdentityKeyPair.kt:26-27` — `loadOrCreate` swallows **any** exception from `fromKeystore()` and
falls through to `fromPrefsOrGenerate`, which at `:60-63` writes `pair.private.encoded` (PKCS#8) as
base64 into `SharedPreferences`.

*Risk:* on a device where Keystore is merely temporarily unavailable (not "unsupported"), a healthy
hardware-backed identity is never retried and the private key ends up as readable file-backed prefs.
The alias/prefs keys are what every existing pairing points at, so this cannot be "fixed" by
regenerating.

*Fix:* distinguish "device genuinely lacks Keystore" from "transient failure"; keep the fallback but
mark the lifecycle state (`healthy | legacy-fallback | corrupt | lost`), surface it in the dashboard
and diagnostics, exclude those prefs from backup, and add a migration marker. Never auto-regenerate.
*Tests:* transient failure does not silently downgrade; existing pairing survives; corrupt prefs
reports "lost" rather than minting a new identity.

### S-7 [needs test] Cleartext LAN HTTP is a documented trade-off, not a guarantee

`AndroidManifest.xml` sets `usesCleartextTraffic="true"`; `WebTls.kt` issues a process-lifetime
self-signed certificate. *This is a design decision the docs already own* — the issue is only that the
UI must not overstate it. Do **not** make a breaking transport change without staging it behind an
explicit mode and testing legacy clients.
*Work:* honest status labels ("secure listener ready" ≠ "browser trusts this certificate"), one-tap
stop/pause, optional strict mode that disables unpaired endpoints.

### S-8 [confirmed] A paired sender can upload but cannot read its own traffic

`pairedSenderIsAuthorized` is consulted on `/upload` only (`:1009`), so a paired phone whose host has
since rotated the PIN can still push files but gets 401 on `/api/messages`, `/api/threads` and
`/api/files` — it cannot confirm what it sent was delivered, and the browser tab cannot retract it.
Documented here because it is a *product* decision as much as a security one: either the pairing
secret is a credential for the read routes too, or the pairing UI has to say that pairing grants
upload-only.
*Work:* decide the intent, then make `isAuthorized` agree with it in one place, and label the pairing
screen to match.

---

## P1 — next release cycle

### R-1 [partly fixed] `/api/webcall/up` has no bound on the body or decoded frames
`:1344-1359`: base64 batches are decoded and handed to the engine with no size cap; `parseBody` has
already buffered the request. NanoHTTPD 2.3.1 has **no** ceiling of its own — it copies
`Content-Length` bytes into a Java String for a JSON post, or into a file in `cacheDir` for a
multipart one, and neither path checks the number first.
*Correction, because the earlier version of this entry got the library wrong:* it claimed
`TempFilePostOutputStream` "writes until the disk complains". **That class does not exist in 2.3.1**
— it belongs to 2.5+ — and `HTTPSession.getTmpBucket()` takes only `TempFile.getName()` and opens its
own `RandomAccessFile`, so wrapping the `TempFile` to count bytes counts nothing. The conclusion
survives (the cap cannot live in the library) but the reason was invented, and a ceiling built on it
would have been dead code.
*Fixed in `security/object-scope`:* `bodyTooLarge(session, WEB_CALL_BODY_LIMIT)` runs **before**
`parseBody` and refuses from the declared `Content-Length` at 256 KiB, sized for one ~10 Hz batch of
frames; the same guard covers `/api/call/web` at 64 KiB. Both answer 413 with `Cache-Control:
no-store`.
*Finished in `security/upload-ceilings`:* the other seven routes that read a body — typing (4 KiB),
login (8 KiB), the signaling dispatcher, `/api/delete`, `POST /api/messages` (64 KiB each), and both
upload routes against `uploadBudgetBytes(cacheDir)`, free space less headroom, which is the right
shape for a file and was not available as a constant.
*Corrected here, and it was load-bearing:* under-declaring is not a fill risk. `parseBody` reads
`min(remaining, 512)` per pass and counts the declaration down, so it stops wherever the client said
it would and leaves the surplus unread in the socket; the fill is *over*-declaration plus streaming it.
Which is exactly why answering the header is a real ceiling rather than a courtesy — the spool cannot
exceed what it bounds, and the two heap copies behind it cannot exceed the spool.
*Fixed here:* the dispatcher's ceiling was `JSON_BODY_LIMIT`, which is **equal** to
`MAX_RELAY_ENVELOPE`. The relay routes carry an envelope *inside* the POST, so the body is always
larger than what the relay rule accepts; equal ceilings refused the last few dozen bytes of the
relay's own budget with a 413 the sender cannot act on, instead of the relay's own "too large", which
it can. The dispatcher now has `RELAY_BODY_LIMIT` = four envelopes. The comment above that guard
reasoned from an SDP offer — true of `/api/call/*`, and the reason 64 KiB looked generous — without
noticing `/api/relay/*` shares the line.
*Still open:* the per-frame and per-batch decoded-size caps and the bounded egress queue — a client
inside the 256 KiB ceiling can still send many batches, and the frames are decoded without a bound on
what one batch may expand to. And **size is not frequency**: nothing counts requests.
*One ceiling worth a second look, not changed without the owner:* `POST /api/messages` at 64 KiB is
the smallest JSON ceiling on the app's most text-shaped route. The composer puts no `maxlength` on
the message box and nothing clips `text` server-side, so pasting a log larger than ~60 K characters
to your own phone now answers 413 rather than arriving. Bumping it to the 256 KiB the media batch
already undertakes to read is the obvious move, and it is a product decision about what a note may be,
not a security one — so it is written down rather than done here.
*Tests:* `UploadCeilingTest` and `ObjectScopeTest` — the refusals, the uploads that fit, and that a
normal-sized action still gets through the ceiling (a guard tested only at the refusal cannot be told
apart from a block). `CeilingPrecedenceTest` — the three things a per-route size test cannot see: the
wrapper ceiling clears the envelope ceiling, a refusal at the login route is not charged as a wrong
PIN guess (with the control that twelve distinct wrong guesses *are*), and a stranger at a tier C
route is refused for credentials before being told anything about size. Each is verified to fail when
only its own rule is broken.

### R-2 [partly fixed] Upload limits must hold *while streaming*
`/upload` takes `?path=` from the query, has a free-space preflight and timestamp-suffix duplicate
handling, but nothing bounded the *declared* length: a POST for a file larger than the phone has room
for was buffered whole into `cacheDir` before `hasFreeSpaceFor` was asked whether the destination
could hold it. `/chatupload` had no preflight at all.
*Fixed in `security/upload-ceilings` and `security/upload-names`:*
* `bodyTooLarge(session, uploadBudgetBytes(context.cacheDir))` before `parseBody` on both upload
  routes. The budget is what the volume reports free, less 64 MiB headroom, floored at 32 MiB —
  measured per request, because the question is what this phone can spare now. A volume that cannot
  be measured falls back to 2 GiB rather than to unlimited, which is deliberately the opposite of
  `hasFreeSpaceFor`'s fail-open.
* Bytes land as `.{uuid}.part` and are renamed only after the copy or decrypt completes, so a refused or
  interrupted transfer leaves nothing that looks like a received file. The landing name carries a uuid
  rather than the final name because `?path=` is not unique: two tabs sending to one name both find it
  free, both computed the *same* landing file, and the second one's `renameTo` then had no source left —
  it answered 500 `Upload failed` for bytes that had arrived whole. `FileNameAdversarialTest` catches
  that, and reverting the naming turns it red again.
* A caller-chosen name whose extension alone outran the 120-character budget used to be refused: the
  stem count is `120 - extension - 1`, which is negative for `a.` + 300 characters, the `take()` threw,
  and the upload handler caught it as an ordinary failure. It now takes the plain truncation.
* Neither route reflects `e.message` into the response any more; an exception string from this depth
  is a filesystem path, and the path is under the app's private storage.
*Still open, and it is not a small remainder:* per-client, per-concurrent and total quotas; the
`.part` is cleaned by the same request that wrote it, so a process killed mid-copy leaves one behind
(a crash-recovery sweep of `.*.part` in `WebDrop_Received` is not written); the `?path=` collision
policy is undecided — the second sender of one name gets a timestamp suffix when the first has already
landed and the same name when it has not, and whether a collision should instead be a `409` is a
response-shape question, not a security one; and the ceiling bounds the *declared* length only. That
last point is weaker than it sounds in one direction and stronger in the other: a client that
over-declares is refused, and a client that under-declares is not read at all — `parseBody` stops at
`Content-Length` — so the unconsumed bytes are left in the socket to be parsed as the next pipelined
request, which is a request-smelling question (filed separately) rather than a disk-fill one.
*Tests:* `UploadCeilingTest` — declared length above the budget → 413 on `/upload` and `/chatupload`;
a 40 MB body streamed against a 32 MiB budget is refused *and* the client could not finish pushing
it, which is what proves the refusal precedes the read; an upload that fits still arrives byte for
byte; a completed upload leaves no `.part`; an encrypted upload for an unknown pairing writes nothing
anywhere; and the budget is `availableBytes - headroom`, floored, tested against `StatFs` rather
than against the shadow's arithmetic. `FileNameAdversarialTest` — the traversal, control-character,
header-injection, markup, bidirectional-override, over-long-name, blank-name, reserved-name and
same-name-collision cases on `/upload?path=`, the same sanitizer's two other users
(`/download-folder?path=` and `/chatupload?filename=`), and the symlink case that only the canonical
check can answer.

### R-3 [confirmed] Unauthenticated typing presence is forgeable and fillable
`/api/typing` POST (`:1162-1180`) takes `sender` verbatim with no credential and no rate limit;
`typingSenders.remove(sender)` on `typing:false` lets one host evict another's presence by reusing its
name, and the 64-slot cap (`TYPING_SENDER_LIMIT`) can be filled to deny real users. Either bind
presence to an authenticated session or bound/normalize/rate-limit the public form.

### R-4 [confirmed] Wildcard CORS on a credentialed server
`addCorsHeaders` emits `Access-Control-Allow-Origin: *` on essentially every response, including 401
and 404. Document what cross-origin access is actually required and narrow it; keep state-changing
operations off ambient credentials; validate `Origin` where browser semantics allow.

### R-5 [needs test] XSS via caller-controlled strings
`deviceName`, `X-Pulse-Client`, `sender`, filenames, thread titles and `action=ring`'s `name`
(`:1287`) are all caller-supplied and reach the browser UI. Audit every `innerHTML` in
`WebClientHtml.kt`; dynamic values must go through `textContent`. `/api/info` also returns `tlsError`
free text (`:1137`) that must be proven free of paths/key material.
*Tests:* payload in filename / device name / message body / attachment metadata renders as text.

### R-6 [recommendation] CSP, security headers, and request-hardening baseline
Add `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` on
private responses, and a CSP **tested against the embedded inline CSS/JS first** (report-only until it
passes). Add read/idle timeouts, concurrent-request caps, and per-endpoint body caps.

### R-7 [needs test] Call media confidentiality is labelled imprecisely
`docs/Security-Model.md` already states call media is not E2E encrypted — the docs are honest, the UI
may not be. Label it before start/accept. *Also:* `GET /api/relay/...` style probing aside, verify
`CallEngine.handleInvite` rejects unknown pairing ids so an invite cannot be spoofed.

### R-8 [confirmed, partially fixed] Route-shape leakage via OPTIONS and 401/404 asymmetry
`OPTIONS` returns 200 for any path with wildcard CORS (`:1074`), and `GET /api/auth` reaches the global
check and 401s instead of 404ing — both reveal which routes exist before any credential is offered.
Low severity on a LAN; worth folding into S-3 rather than a separate change.

### R-11 [needs verification] Bytes past a short `Content-Length` are parsed as the next request
Discovered while bounding the upload ceiling, and deliberately not treated as one: `parseBody` reads
exactly `Content-Length` bytes and returns, so a client that declares 100 and sends 400 MB leaves the
other 399,999,900 in the socket — and `ClientHandler.run()` loops on `session.execute()` over the *same*
stream while the connection is open. Those bytes are then parsed as a following HTTP request.

This is not a disk-fill or heap risk, which is why it is P1 rather than P0: nothing is read, so nothing
is buffered. What it may be is a request-smuggling shape — a pipelined request the tab never sent,
arriving on a connection whose credential and client address are already established, and NanoHTTPD
answers HTTP/1.0 per response while accepting HTTP/1.1 pipelining on the read side.

*Needs verification first,* because this is a reading of the bytecode and not yet an observation: two
requests on one connection, the second one smuggled behind a short `Content-Length`, against a real
listener, asserting what the server does with it. Until that test exists this entry is a hypothesis and
should be labelled as one wherever it is repeated.

*Why the number skips:* `R-9` and `R-10` are already taken by the P2 table, which reuses the `R`
prefix for refactor items. Nothing here was renamed.

### K-1 [recommendation] Pairing threat-model test suite
P-256 ECDH + AES-GCM + KDF v1/v2 already have golden-vector tests. Still missing: safety code derived
from the exact transcript both sides authenticate, key-substitution → code-mismatch, curve/key
validation on parse, deterministic KDF selection bound to the transcript, duplicate/stale/concurrent
pair requests, identity loss and reinstall. **Compatibility rule:** never rename legacy aliases,
preference keys, database names, or wire constants; never silently change KDF for an existing pairing.

---

## P2 — reliability and product

| ID | Item | Where |
| --- | --- | --- |
| U-1 | Server screen as a safety dashboard: Off/Starting/Ready/Trouble, separate LAN and HTTPS rows, PIN state + regenerate, active sessions with revoke, paired devices, active transfers with cancel, visible stop, honest TLS-trust status. Never a green badge just because TLS is listening. | `ui/screens` |
| U-2 | First-run onboarding that explains the three trust models (paired / local-room / browser) and what starting the server exposes. Skippable, not hidden. | onboarding |
| U-3 | Security & privacy settings screen; expose or delete the custom-PIN editor the security docs call unreachable. | settings |
| U-4 | Transfer UX: queue states, honest ETA, cancel/retry, duplicate-name choices, never show incomplete as completed, never auto-open received files. | `TransfersScreen.kt` |
| R-9 | `PulseDropServer.kt` is **2 262 lines**; `WebClientHtml.kt` is **4 752**. Split routing/security/files per the PRD §5 plan *after* tests pin current behaviour. | server |
| R-10 | `CallEngine.kt` is **2 346 lines**. Split only after protocol tests exist; do not change frame numbers or wire formats without versioning. | call |
| D-1 | Local data at rest: chat history plaintext in Room, received files unencrypted (documented). Design key lifecycle, backup, export and recovery **before** shipping encryption. | data |
| C-1 | Docs/version drift: README still shows v1.4.0/v1.3.0/v1.1.2 VirusTotal rows while the app is 2.0.2; `docs/Installation.md` references 2.0.1. Add a stale-version CI check. | docs |

## P3 — later

Feature mini-PRDs, in the order the brief sets: transfer queue and history → SHA-256 integrity →
resumable transfers → session/device management UI → per-device permissions → encrypted history →
redacted diagnostics bundle → browser parity/a11y → localization → E2E call media → desktop/group
chat. **Not scheduled:** cloud accounts, cloud sync, Kubernetes, rewrite.

---

## CI/CD and static analysis

Found while checking whether the security automation added to `main` on 2026-10-24 does anything.

| ID | Item | Status |
| --- | --- | --- |
| X-1 | `.github/dependabot.yml` shipped as the untouched web-UI template with `package-ecosystem: ""`. That is not a valid value, so GitHub rejects the manifest and Dependabot opened **zero** update PRs while the setup page looked configured. | **Fixed** on `ci/security-automation` (Gradle + GitHub Actions, weekly) |
| X-2 | Every `uses:` in `ci.yml` and `codeql.yml` tracked a mutable tag, so a republished tag could change what a security-scanning workflow executes. | **Fixed** on `ci/security-automation` (full SHA + version comment) |
| X-3 | CodeQL cannot analyse the browser client. It is JavaScript inside a Kotlin string literal (`WebClientHtml.kt`), and extractors are chosen by file extension — so the app's most exposed input-handling code gets Kotlin queries only. The `javascript-typescript` matrix entry sees `site/` and `dev/`. Fixing this properly means shipping the client as an asset file, which changes packaging: design it, do not slip it into a security PR. | [confirmed] — blind spot is documented in the workflow header meanwhile |
| X-4 | CodeQL alert #1, CWE-927 (implicit/mutable `PendingIntent`) at `diag/CrashReporter.kt:126`. **Partly a false alarm:** `FLAG_IMMUTABLE` is set, so the receiving app cannot rewrite the intent, which is the escalation the rule describes. What is real: the intent is *implicit* (`ACTION_VIEW` + `https://github.com/...`) so it resolves to whichever app claims that URL, and that app receives the crash markdown — device model, Android version, stack trace — in the query string. It also lands in browser history. Route the notification through an explicit in-app component that shows the report and offers to send it. | [verified, downgraded] P2 |
| X-5 | Dependabot *security* updates (the ones that patch a known-vulnerable dependency) are a repository setting under Code security, not something this file can turn on. Needs someone with administration. | [recommendation] owner action |
| X-6 | `actions/setup-java@v4`'s current tip commit is literally "Deprecate setup-java v4", and checkout/upload-artifact/setup-android/setup-gradle are one to three majors behind. Deliberately **not** bumped here: pinning preserves behaviour exactly, a major bump does not, and it can only be judged on a real runner. | [recommendation] separate PR |

## Corrections to the PRD (verified, not assumed)

1. **`/api/relay/pair-poll` is not an authentication bypass.** I initially flagged it from
   `docs/Authorization-Matrix.md` (which calls it a "gap") because `:261-266` returns before the
   `pairManager` proof check. On reading `PairHandshake.kt:63` the key is
   `UUID.randomUUID().toString()` — 122 unguessable bits. It is a capability token, so knowledge of
   `reqId` *is* the entitlement. Real residual issues: no rate limit on an unauthenticated POST, and
   first-poller-wins means a leaked `reqId` lets a third party consume the pairing answer. Downgraded
   to **R-3 class** (rate limit + document the capability design), **not** P0.
2. **`X-Pulse-Client` is correctly non-authoritative** — decoded for display only (`:1059-1063`), never
   an authz input. The PRD's concern here does not apply to the current code.
3. **Path traversal on `/upload` and `/download-folder` is already mitigated** (`sanitizeRelativePath`
   `:2160` + canonical check `:1735`). The remaining gap is *ownership*, not traversal (S-5). Tests
   still need writing; the code is not as bad as a first reading suggests.
4. **No AWS deployment exists to harden.** See `docs/operations/AWS-Deployment-Inventory.md` — the
   brief's Phase 7 does not apply to any current component.

## Dependency order for the first three PRs

Shipped, in the order the dependency actually required:

```
JDK 21 + Android SDK present  →  baseline ./gradlew test green  (362 tests, lint clean)
   └─ security/browser-sessions   S-1 (+ S-1b browser half)     — 378 tests after it
   └─ security/object-scope       S-4, S-5, R-1 (body caps)     — needed S-1's tokens in the tests first
   └─ ci/security-automation      X-1, X-2, X-3 documentation
```

Remaining order: **S-2b** (path-scoped tickets so no credential appears in a URL) is next and is a
smaller change than it looks now that the PIN is gone; **S-3** (route policy instead of chain
position) wants the object-scope tests in place as its regression net, which is why it follows rather
than precedes them; **S-4b** (`/chatfile`, matrix `A-1`) and **S-5b** (streaming upload limits, `R-2`)
are the two remaining P0-class scope holes.

S-6 (identity fallback) and S-7 (transport labelling) touch protocol compatibility and device state;
they get their own PR after the auth chain, with a migration plan rather than a behaviour flip.
