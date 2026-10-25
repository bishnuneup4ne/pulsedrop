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

### S-2 [fixed] PIN credentials travel in URLs

`isAuthorized:987` read `session.parameters["auth"]`, and the browser built it in six places —
`WebClientHtml.kt:2097, 2161, 2405, 3042, 3534, 3893` (including `/api/events`, an SSE URL that a
proxy and the browser history both retain).

*Risk:* a PIN-equivalent secret in browser history, in the events stream URL, in referrer headers,
in any diagnostics/proxy log, and shoulder-surfable in an address bar. Combined with S-1 it means
history is a credential store.

*Fix:* `isAuthorized` is header-only; the browser builds plain URLs. The requests a header cannot
reach carry a **ticket** instead (`UrlTicket.kt`, `POST /api/ticket`): HMAC-SHA256 over the exact
scope, the caller's normalized address and a five-minute expiry, minted only by an authenticated caller,
rotated on PIN change, and gone on restart. Scopes are `events`, `download:<id>`, `folder:<path>`,
`chatfile:<id>` and `preview:<id>`, and no state-changing route accepts one. Tickets are
short-lived rather than single-use so a retried or ranged download still works inside the window.
*Tests:* `UrlTicketTest` (roundtrip, expiry, scope mismatch, address mismatch, stretched expiry,
flipped signature, rotation, malformed) and seven new `ServerSessionAuthTest` cases: a session in a
query string is 401; a ticket opens exactly its object and no other; a ticket is not a way into the
API (GET or POST); the event stream accepts a ticket and refuses `?auth=`; minting needs a live
session; an unknown scope is refused; a PIN change kills tickets already in flight.
*Depends:* followed S-1, as required.

### S-3 [fixed] Authorization is positional, not policy

`serve()` was one flat chain with three tiers: routes handled before any check, routes calling
`isAuthorized()` inline, and routes gated by a single global check. Any route inserted above that
check was silently unauthenticated.

*Fixed in `security/authorization-policy`.* `AuthorizationPolicy.forRequest(uri, method)` is one
table, consulted once at the top of `serve()`; `authorize()` applies it and hands the dispatcher a
`Principal` carrying the address and how it was admitted. The default for a path or verb the table
does not list is `SESSION`, so a new route is protected unless someone deliberately writes the
matrix row. Inline and global `isAuthorized` calls are gone from the dispatch chain.
*Tests:* `AuthorizationPolicyTest` — every rule has a row in `Authorization-Matrix.md`, the unlisted
verb falls back to `SESSION`, an unknown path is authenticated before 404, `/api/call/web` wins over
the `/api/call/` prefix, and only the five URL-bearing GET families accept a ticket. Existing socket
and `TabSession` suites still pass unchanged.
*Rollback:* route paths and response shapes are untouched; this is dispatch logic only.

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

### A-6 [fixed] Attaching a file published it, and publishing asked no permission

A-1's escalation path, and left open by A-1's own fix: `chatFileIsVisibleTo` was consulted by the
download route and by nothing else, so `POST /api/messages` looked `attachmentId` up in
`chatAttachments` and attached whatever id the caller named to its own message. Because the read rule
follows the message — a room message is readable by the room — publishing was a second way to deliver
bytes this tab was not allowed to read, and the download route's refusal was only the second door of
the same room.

*Fixed.* Attaching asks the same predicate as reading, from the same function. An id this server has
never seen stays **ignored** rather than refused, which is what it did before, so a client that
already sends a stale attachment id gets the response shape it always got.
*Tests:* `CrossAddressScopeTest` — the same upload, the same id, two tabs: the tab that uploaded gets
its own file back, the other cannot publish it. Found by the harness rather than by reading: a
single-address suite has no second tab to attach *for*, so every suite in the repo passed this route.

### S-6 [fixed] Keystore failure silently writes a raw private key

Before `security/identity-key`, `IdentityKeyPair.loadOrCreate` swallowed **any** exception from
`fromKeystore()` and fell through to a path that wrote `pair.private.encoded` (PKCS#8) as base64 into
`SharedPreferences`.

*Risk:* on a device where Keystore is merely temporarily unavailable (not "unsupported"), a healthy
hardware-backed identity is never retried and the private key ends up as readable file-backed prefs.
The alias/prefs keys are what every existing pairing points at, so this cannot be "fixed" by
regenerating.

*Fixed on `security/identity-key`.* The load now asks three different questions instead of one:

- **Which store holds the identity, preferring whichever was written first.** Prefs are read before
  the keystore, so a phone that fell back once keeps that key after the keystore recovers rather than
  swapping identities under its own pairings. Nothing is written on this path.
- **Did the keystore refuse, or does this environment not have one?** A refusal is counted **once per
  launch** — three taps on Accept are one piece of evidence about the hardware, not three — and the
  device is believed unable after three. `Security.getProvider("AndroidKeyStore") == null` means no
  keystore exists to wait out (a JVM test, a ROM with none), and gets a file-backed identity at once.
  That provider is registered on every Android device that has a keystore, so on a phone the counter
  is what decides and this branch is dead: it exists so the counter cannot be reached by an
  environment that was never going to satisfy it.
- **Is a stored entry unusable?** Half a key pair (`priv` without `pub`, or either one that will not
  parse) used to be treated as *nothing* and overwritten with a freshly generated identity. It is now
  reported: `CORRUPT`, the entry left exactly as it was, and a throw.

The failure is a typed one — `IdentityKeyUnavailableException`, carrying `IdentityKeyPair.explain()`,
which is a sentence about what to do ("unlock the device and try again" versus "pair the other devices
again") and differs by state. Nothing is auto-regenerated on any path. `IdentityState` is
`NOT_LOADED | HEALTHY | LEGACY_FILE | CORRUPT | UNAVAILABLE`, and the three surfaces the brief asked
for are wired: About → *This install* states where the key lives, a crash report carries an
`| Identity | <state> |` row (the name only, never key material), and `/api/status`'s `deviceId` is
empty rather than a 500.

Every caller that stamps an identity now asks for it through a nullable door — `PairRepository
.myDeviceIdOrNull`, `PairManager.myDeviceIdOrNull`, `PairHandshake.myPubOrNull` — because a getter that
throws reached code with no handler: `PairHandshake.acceptIncoming` ran on the UI thread, and
`respondToPair` inside an uncaught `scope.launch(Dispatchers.IO)`. The rule each site applies is that a
claim the phone cannot back up is worse than the action not happening: pairing answers nothing rather
than half-pairing, sealed mail is not sent without a `from`, a read receipt and a mailbox drop are
dropped, an edit of your own message tells you why on screen. The request stays on the Accept screen,
so the retry is still there once the key can be read.

*Tests:* `IdentityKeyFallbackTest` (9 cases) — one refusal writes no private key anywhere; three taps
are one refusal; a launch that answers clears the count and copies nothing into prefs; a stored key
wins and the keystore is never asked; a believed-unable device is file-backed once and stays that way
across a launch where the keystore comes back; half a stored key is reported lost and the bytes are
untouched; an unreadable entry does not cost a keystore identity; no-keystore is not waited out.
Mutation-checked, not assumed: restoring the old fall-through fails the first two cases, and counting
per attempt instead of per launch fails the second. The 24 pre-existing pairing/relay tests, which had
passed only because any keystore exception wrote a file key, pass unchanged through the
no-provider branch.

*Still open:* (a) **migration** — the `identity_store` marker is written and nothing reads it. Moving
an existing `LEGACY_FILE` key into a working keystore is not possible with the stock platform, which
will generate or wrap-import AES but not import a raw EC private key; it needs either a re-pair or a
protocol-compatible identity handover, and either way it is a decision, not a cleanup. (b) The About
row explains the state; no screen offers the *repair* each state implies. (c) Backup exclusion of
`localdrop_identity.xml` was already in place in both `backup_rules.xml` and
`data_extraction_rules.xml`, and `webdrop_database.db` is excluded too — verified here, not newly
added. (d) A `LEGACY_FILE` phone is one root-free app-data read away from having its private key
copied; that is the documented cost of keeping such devices able to pair at all.

### S-7 [needs test] Cleartext LAN HTTP is a documented trade-off, not a guarantee

`AndroidManifest.xml` sets `usesCleartextTraffic="true"`; `WebTls.kt` issues a process-lifetime
self-signed certificate. *This is a design decision the docs already own* — the issue is only that the
UI must not overstate it. Do **not** make a breaking transport change without staging it behind an
explicit mode and testing legacy clients.
*Work:* honest status labels ("secure listener ready" ≠ "browser trusts this certificate"), one-tap
stop/pause, optional strict mode that disables unpaired endpoints.

### S-8 [decided: upload-only] A paired sender can upload but cannot read its own traffic

`pairedSenderIsAuthorized` is consulted on `/upload` only, so a paired phone whose host has since
rotated the PIN can still push files but gets 401 on `/api/messages`, `/api/threads` and
`/api/files` — it cannot confirm what it sent was delivered, and a tab cannot retract it.
Documented here because it is a *product* decision as much as a security one.
*Decision:* the read routes stay on the room credential. A pairing key is long-lived, survives a PIN
change and has no per-session revoke story, so accepting it for reads would let a device that was
paired once read everything forever. Upload-only is the narrower capability, and the 401 on a read is
the honest answer rather than a bug.
*Labelled:* the **Devices** screen's trust panel and the Help answer now say that a paired phone can
send without a PIN but does not read files, message history or the browser room;
`docs/Pairing-and-Devices.md` and `docs/Security-Model.md` say the same. `isAuthorized` already
expressed the decision in one place; what was missing was the UI saying it out loud.

---

## P1 — next release cycle

### R-1 [fixed] `/api/webcall/up` has no bound on the body or decoded frames
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
*Fixed in `security/web-media-limits`:* the decoded side now has its own caps, in `MediaLimits`.
The web leg's `BatchParser` is constructed with a 1 MiB frame cap, so a declared 4 MiB frame is
dropped instead of accumulated 190 KB at a time across posts; a batch above 64 frames is discarded
whole (the shipped tab flushes three, or 24 after shedding a backlog); and per type, audio and control
frames are held to 64 KiB while video may use the full 1 MiB. A JPEG is refused on its declared
dimensions — 4 MPix — before `BitmapFactory` allocates, because a small file can name an enormous
canvas; screen capture tops out at a 1920 px edge and 1080p camera at 2.1 MPix, so real media passes.
The egress queue was already bounded (`LinkedBlockingQueue(96)` with the oldest frame dropped).
*Also fixed here:* the 256 KiB body ceiling was too small for the client it guards. The tab sends a
shared screen 1600 px wide at quality 0.75 and base64 costs a third more, so ordinary dense desktop
frames were refused and screen share simply stopped mid-call. The ceiling is now 2 MiB, with the
decoded caps above doing the work the byte count cannot.
*Tests:* `FrameCodecTest` — a parser with a lowered cap drops an over-cap declaration, does not wait
for its payload, and accepts a frame exactly at the cap. `MediaLimitsTest` — per-type boundaries and
the pixel cap, including the largest square frame this app can capture. `ObjectScopeTest` still pins
the pre-read 413.
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
PIN guess (with the control that twelve distinct wrong guesses *are*), and a stranger at a
`SESSION` route is refused for credentials before being told anything about size. Each is verified to fail when
only its own rule is broken.

### R-2 [fixed] Upload limits must hold *while streaming*
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
* The `.part` landing file is deleted by the request that wrote it, so a process killed mid-copy used
  to leave one behind forever. Nothing enumerates `WebDrop_Received` — the browser list and the
  Receive screen both read the in-memory registry — so the leftover was invisible and permanent,
  quietly spending storage on a phone that may be short of it. `start()` now sweeps
  `.{uuid}.part` files there, including the subdirectories `?path=` can create, before the listener
  binds. The match is deliberately narrow: `sanitizeFileName` trims leading dots, so no received name
  can look like a landing file, while `movie.part`, `.nomedia` and `.not-a-uuid.part` are left alone.
  `PartialUploadSweepTest` seeds each of those and proves both halves.
*Fixed on `security/upload-sharing`:* the ceiling answers one request body, and two tabs could each
declare less than it and together fill `cacheDir`, because both measured the volume before either
wrote. `InFlightUploadBudget` reserves each admitted request's declared length before `parseBody`
and releases it in a `finally` on every exit, so the sum of uploads alive at one moment fits what the
volume can spare. A request that would overflow the shared budget answers `503` with `Retry-After: 5`
— a retry, not a refusal, because the body itself is the right size. What the reservation trusts is
still the *declared* length only: an under-declaring client is not read past it (`parseBody` stops at
`Content-Length`), and the surplus-is-a-next-request half is R-11's, closed by ending the connection.
*Collision policy, decided:* the existing behaviour stays. A name that already exists gets a
timestamp suffix; two requests that picked one name before either landed both succeed and the last
rename wins the name, with the bytes never interleaving (the `.uuid.part` landing already guarantees
that). A `409` would cost a transfer, and the page has no rename/retry affordance to recover it;
neither outcome is a security question. Per-client quotas are deliberately not added: one client can
already reserve the whole budget with a single legitimate body, so splitting by address would add
policy without shrinking the exposure — the shared sum is the invariant.
*Tests:* `UploadCeilingTest` — declared length above the budget → 413 on `/upload` and `/chatupload`;
a 40 MB body streamed against a 32 MiB budget is refused *and* the client could not finish pushing
it, which is what proves the refusal precedes the read; an upload that fits still arrives byte for
byte; a completed upload leaves no `.part`; an encrypted upload for an unknown pairing writes nothing
anywhere; the budget is `availableBytes - headroom`, floored, tested against `StatFs` rather than
against the shadow's arithmetic; and (on `security/upload-sharing`) a 20 MiB upload held with an
unfinished body reserves its length, a second 20 MiB upload against the same 32 MiB budget answers
503, and the next upload is admitted once the held socket closes.
`InFlightUploadBudgetTest` pins the arithmetic and that a release cannot create space that was never
reserved. `FileNameAdversarialTest` — the traversal, control-character, header-injection, markup,
bidirectional-override, over-long-name, blank-name, reserved-name and same-name-collision cases on
`/upload?path=`, the same sanitizer's two other users (`/download-folder?path=` and
`/chatupload?filename=`), the symlink case that only the canonical check can answer, and a race of two
same-name uploads whose overlapped copies may take the name but never interleave bytes.
`PartialUploadSweepTest` — an orphaned landing file in the root and in a `?path=` subdirectory is
gone after `start()`, while a finished upload, a name with no extension, `.nomedia`, `movie.part`
and a `.uuid.part.txt` all survive.

### R-3 [fixed] Unauthenticated typing presence is forgeable and fillable
`/api/typing` POST took `sender` verbatim with no credential, and `typingSenders.remove(sender)` on
`typing:false` let one host evict another's presence by reusing its name.
*Fixed:* writes now require the room credential, presence is keyed by the caller's normalized address
rather than its claimed name, names are de-duplicated for display, and a per-address bucket allows one
write per 400 ms. Reads remain public. Proved by `TypingPresenceTest`.

### R-4 [fixed] Wildcard CORS on a credentialed server
`addCorsHeaders` emitted `Access-Control-Allow-Origin: *` on essentially every response, including
401 and 404. Nothing in the repo is a cross-origin client — the page is same-origin and relative-URL
only — so the grant is removed rather than narrowed; a cross-origin integration would have to opt in
deliberately. `ResponseHardeningTest` asserts the absence on the page, a 401, a 404, a font, an
`OPTIONS` preflight and a mutation.

### R-5 [fixed] XSS via caller-controlled strings
`deviceName`, `X-Pulse-Client`, `sender`, filenames, thread titles and `action=ring`'s `name` are all
caller-supplied and reach the browser UI.
*Audit:* every one of those reaches the DOM through `textContent`, `createTextNode` or an input
value. All 15 `innerHTML` assignments receive SVG constants defined in the same file, and the only
helper that accepts markup as an argument (`feBtn`) is called with `FE_*` constants. There is no
`eval`, `new Function`, `document.write`, `insertAdjacentHTML`, `outerHTML`, `srcdoc` or
`javascript:` sink. The server-rendered `deviceName` goes through `esc()` at all three insertion
points.
*Also fixed here:* `/api/info`'s `tlsError` is no longer exception free text. `describeTlsFailure`
keeps the exception class, replaces path-shaped runs with `<path>`, and caps the message at 200
characters; the full exception still reaches Logcat. Inline `/preview/` and `/chatfile/` responses
now carry `nosniff` and `Referrer-Policy`, plus a `sandbox` CSP when the recorded type is a document
(H-1).
*Tests:* `WebClientSafetyTest` — hostile device name is escaped, a quote cannot leave its text node,
`innerHTML` receives only constant markup, `feBtn` is only handed icon constants, no script sink
exists, and a TLS failure shown to a browser never names a private path and stays bounded.
`FileNameAdversarialTest` asserts the inline-safety headers on `/preview/{id}`.

### R-6 [fixed] CSP, security headers, and request-hardening baseline
Done: `serve()` adds `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer` to every
response, including 401/404 and inline previews, and `Cache-Control: no-store` to JSON that did not set
its own cache policy (`ResponseHardeningTest`). The browser document now carries a CSP:
`default-src 'none'`, `script-src`/`style-src` `'self'` plus `'unsafe-inline'` for the page's own
inline code and handlers, `img-src`/`media-src` also `blob:` for the remote call's frame,
`frame-src`/`connect-src`/`font-src` `'self'`, and `base-uri`, `form-action`, `frame-ancestors`
`'none'`. `ResponseHardeningTest` parses it directive by directive, pins that no host or wildcard
reached `script-src`, and pins that JSON, 404 and font answers do not inherit a document policy.
Read/idle timeouts needed no change: NanoHTTPD 2.3.1's `ServerRunnable` applies `SO_TIMEOUT` (5 s)
to accepted sockets (verified against the pinned jar), so a wrapper would only have duplicated it.
Concurrency is now bounded: `BoundedAsyncRunner` caps each listener at 256 open handlers and closes
(does not answer) the connection beyond that, which is the one overload case that does not owe a
body; `RequestConcurrencyTest` holds the only slot with a declared body that never arrives, observes
the next connection closed, and sees a 200 once the held socket closes. `Strict-Transport-Security`
is decided against rather than deferred: the certificate is self-signed and process-lifetime and
the host is usually an IP literal, so HSTS would make the browser warning unbypassable while not
even being applied to IP hosts. Remaining upload quotas are R-2's streaming half, not a header gap.

### R-7 [fixed] Call media confidentiality is labelled imprecisely
`docs/Security-Model.md` already states call media is not E2E encrypted. The UI did not: the ringing
screen said "over your encrypted pairing", and the devices onboarding said "chat, calls and encrypted
transfers", both readable as a promise about the media. The ringing hint now says media is not
end-to-end encrypted (chat between paired phones is), and onboarding names encrypted chat and transfers
separately from calling.

*Verified:* `CallEngine.handleInvite` requires `findPairing(pid)`, so an invite carrying an id this
phone does not hold is refused and never raises `incomingCall`. `CallInviteTest` pins that refusal and
the control that a real pairing still rings.

### R-8 [fixed] Route-shape leakage via OPTIONS and 401/404 asymmetry
`OPTIONS` used to return 200 for any path with wildcard CORS, and `GET /api/auth` reached a global
check whose position in the dispatch chain decided the answer — so an existing path and a missing one
could be told apart before any credential was offered.
*Closed by S-3, and now pinned:* the policy table answers `SESSION` for both a method it does not name
and a path it does not serve, so both are 401 without a credential and both are 404 with one. `OPTIONS`
returns the same empty 200 for every path and consults no table, so its uniformity names nothing.
`ServerSessionAuthTest` asserts both halves against a real listener, including the authenticated 404
for `GET /api/auth` (a route that exists, but only as POST).
Low severity throughout; there was never a live boundary here, only a probe.

### R-11 [confirmed, fixed] Bytes past a short `Content-Length` are parsed as the next request
Discovered while bounding the upload ceiling, and deliberately not treated as one: `parseBody` reads
exactly `Content-Length` bytes and returns, so a client that declares 100 and sends 400 MB leaves the
other 399,999,900 in the socket — and `ClientHandler.run()` loops on `session.execute()` over the *same*
stream while the connection is open. Those bytes are then parsed as a following HTTP request.

This is not a disk-fill or heap risk, which is why it is P1 rather than P0: nothing is read, so nothing
is buffered. What it may be is a request-smuggling shape — a pipelined request the tab never sent,
arriving on a connection whose credential and client address are already established, and NanoHTTPD
answers HTTP/1.1 while looping on the read side. The hole was in the second request, not in the
credential: `X-Pulse-Auth` is per-request, so the surplus had to bring its own (or hit a public route).

*Observed, then closed:* `RequestSmugglingTest` opens one real socket to a real listener and sends a
`POST /api/auth` whose body ends exactly at its `Content-Length`, followed by a complete
`GET /api/info`. Before the fix the server answered **both**: the surplus was parsed as the second
request and served. The same happened with chunk framing — `Transfer-Encoding: chunked` and no
`Content-Length` — which this build has no reader for, so the request itself answers 400 and the
framing then becomes another request line. `serve()` now ends the connection after any request that
declares `Content-Length` or `Transfer-Encoding`, so the surplus dies with the socket. A bodyless poll
still reuses the connection; that control is in the same file, so the rule cannot pass by having
quietly disabled keep-alive everywhere. Closing on a body is deliberately narrow: browsers do not
pipeline a POST, so the cost is one connection per state-changing request, while the page's polling
loop and SSE keep their keep-alive.

*Threat model:* there is still no intermediary in this deployment, so the only author of a smuggled
request is the client that could send it anyway. This closes a desync primitive, not a live
privilege boundary; it matters if a proxy or cache is ever put in front of the phone.

*Why the number skips:* `R-9` and `R-10` are already taken by the P2 table, which reuses the `R`
prefix for refactor items. Nothing here was renamed.

### K-1 [partly fixed] Pairing threat-model test suite
P-256 ECDH + AES-GCM + KDF v1/v2 already have golden-vector tests.

**Key ingest is now validated.** `Crypto.isP256PublicKey` parses the presented SPKI and compares its
domain parameters against the platform's `secp256r1` spec, and `PairHandshake` applies it at all three
entry points: `POST /api/pair/request`, accepting a request rebuilt from state, and an answer on
`POST /api/pair/response` (direct or relayed). An answer naming a `kdf` outside `Kdf.isKnown` is
refused as well, with a user-visible reason. Previously an impossible key or version was stored, so the
first message after a confirmed pairing threw during ECDH or derivation. Proved by `PairKeyIngestTest`.

**The handshake now has threat-model coverage** in `PairingThreatModelTest`:
* the code shown belongs to the key that asked, so a substituted key changes it (the two sides'
  symmetry of the code itself is `CryptoTest`);
* the acceptor chooses the derivation once and stores it; an older peer or an older local build gets
  V1, and a paired device that asks again is re-answered with the stored choice and cannot re-key it;
* a duplicate request id collapses to one prompt, the prompt list is capped at twelve, an answer for
  another request id leaves the real request pending, and an answer after cancellation is ignored;
* re-pairing under a new key keeps the old pairing and warns that the key changed rather than
  silently replacing trust;
* a phone with no readable identity queues nothing, declines the requester, and stores no pairing.

Still open, and written down rather than papered over:
* the safety code covers the two identity keys, not the chosen KDF. An on-path attacker can strip
  `kdf` from the answer, leaving the acceptor on V2 and the requester on V1. Both derivations are
  strong, and the mismatch fails closed on the first message, so this is a denial of service rather
  than a confidentiality downgrade. Binding the version into what the humans compare would make old
  and new codes disagree, so it needs a protocol/UI version, not a quiet edit.
* the human comparison is still the authentication step. Six digits is a usability trade, and no
  test can prove the user looked.

The 120-second prompt TTL is now driven rather than described: `PairManager`/`PairHandshake` take
the clock they read (`clock`, defaulting to the wall clock), and `PairingThreatModelTest` moves that
clock across the boundary — a request at 119 s is still a prompt, the same one at 121 s is gone when
the next request arrives. Nothing sleeps for two minutes.

**Compatibility rule:** never rename legacy aliases, preference keys, database names, or wire
constants; never silently change KDF for an existing pairing.

---

## P1 — performance (Stage 1 baseline)

Numbering continues the `R-` family. These are ranked, annotated and evidenced in
[Performance-Baseline.md](Performance-Baseline.md); the short version, including what could *not* be
measured on the machine that produced the numbers, is there rather than repeated here.

### R-12 [partly fixed] Every message read is the whole history
`GET /api/messages` (`PulseDropServer.kt:2094-2131`) serialises every visible message under
`synchronized(messages)` with no cursor, so the cost of an idle tab grows with everything the room
has ever said: measured 257 B at one message, 51,491 B at 200, **516,891 B at the 2000 retention
cap** — re-read on the client's 2 s tick and on every doorbell.
*Fixed:* the client no longer starts a second, third and fourth concurrent copy of that read when a
doorbell lands while one is in flight (it serialises and re-runs once at the end), asserted by
`WebClientRequestDisciplineTest`; and an unchanged read now costs nothing at all, because the route
carries a validator (R-18).
*Still open:* a cursor. Reading the list's lifecycle first showed `?since=` is not the shape it
should take, and any version of it is three changes, not one:
- nothing monotonic exists to cursor on — ids are `UUID.randomUUID()` (`:1133`) and `timestamp`
  collides within a millisecond, so a cursor would have to be a new server-assigned revision;
- a retraction re-issues the *same entry* (`markMessageDeleted` replaces it in place at `:1167`), so
  a filter on the entry's original revision would never re-send the tombstone, and a tab that had
  already shown the message would keep showing text its sender retracted;
- visibility is not monotonic — `threadIsVisibleTo` (`:668`) is evaluated per request against
  `@Volatile webThreads` (`:507`), reassigned from the phone whenever its group list changes, so
  old messages can become newly visible *and* visible messages can vanish without any revision
  moving. A forward cursor cannot express either direction; it needs a visibility epoch that forces a
  full resync, which is the payload this was meant to stop sending.
Also `while (messages.size > SERVER_CHAT_RETENTION) messages.removeAt(0)` (`:1146`, `:1190`): the
front falls off at 2000, so an offset-based cursor silently skips rather than signals a gap.

### R-13 [fixed] The event-stream cap silenced tabs instead of refusing one
`maxEventStreams = 8` (`:557`) was enforced by evicting the oldest stream, so the ninth tab's arrival
cut the first, whose reconnect cut the second: a room one tab too large had every tab intermittently
deaf, which reads as "the web client stopped updating". A closed tab's seat also came back only when
a write discovered the dead socket — up to two heartbeats, ~40 s, because the handler parks in
`queue.poll(heartbeatMs)` (`:605`).
*Fixed:* the newcomer is refused with `503` + `Retry-After: 15` + `no-store` and keeps polling, so
nobody loses a stream they already had; and an admission attempt now probes every holder with a
comment frame first, so a seat that is genuinely free is reaped in about a second. Proved by
`EventStreamAdmissionTest` (both halves) and recorded by the probe (`ninth: 503`, `oldest: still
delivered`, and a tab re-admitted 693 ms after another closed)..

### R-14 [confirmed, fixed] PDF preview and file picking ran on the main thread
`AttachmentPreview.kt`'s `PdfStage` rendered a page — allocating a bitmap up to 2200 px wide — inside
composition, so every recomposition repeated it; `SendScreen.kt` resolved metadata for every selected
uri on the main dispatcher under a `LaunchedEffect` whose comment claimed otherwise. Both moved to
`Dispatchers.IO`. This is the "web client / app freezes" symptom in the one place it was reachable
without a profiler.

### R-15 [code-confirmed, unmeasured] Starting the server blocks the thread that draws it
`MainViewModel.kt:1159 startServer()` runs on the caller's thread — the main thread, from `init` and
from the toggle's click handler — and `:1317 startWithSettle` retries `server.start()` with
`Thread.sleep(350L * attempt)` up to four times. On a busy port that is up to ~2 s of dead UI. Not
timed here (no emulator, no KVM); `MainViewModel` has no test coverage, which is why this is P1 and
not already fixed.

### R-16 [code-confirmed, unmeasured] The quality ladder only steps down
`QualityLadder.stepUp` (`media/ladder/QualityLadder.kt:89`) has no production call site — only its own
tests. A call that dips once stays dipped for its remaining life. Needs a real call to measure, so it
is listed rather than patched blind.

### R-17 [code-confirmed, unmeasured] The browser leg of a call is JPEG on an unbounded queue
`CallEngine.kt:2046` compresses each frame with `compressToJpeg(…, 70, …)` off a legacy
`android.hardware.Camera` (`:11`), while H.264 is implemented for phone↔phone only; `webOut` is
`LinkedBlockingQueue<ByteArray>(96)` (`:367`) — bounded in *frames*, so ~96 MiB at the 1 MiB per-frame
cap, queued on a phone during a call. The PRD's "prefer WebRTC" is a new dependency against a rule
that local-network operation must not need an external service; that decision belongs to the owner,
not to a patch.

### R-18 [fixed] Nothing about the payloads was compressed or revalidated
There was no `Content-Encoding`, `ETag` or `Vary` anywhere in the server package, and the index route
regenerates the 236 KB document per request (`:1494`), so every read paid full price whether or not
anything had changed.
*Fixed:* `serve()` now post-processes every response through one rule — gzip for `application/json`
and `text/html` bodies of at least 1 KiB (NanoHTTPD's own `useGzipWhenAccepted` hook, so the chunked
framing stays the library's), a strong `ETag` over the exact bytes this caller would receive with
`Vary: Accept-Encoding`, and a `304` that carries neither a body nor the `Content-Length` RFC 9110
forbids on it. The page offers that validator on its own message poll, because JSON answers are
`no-store` on purpose and the browser therefore holds nothing to revalidate for it.
Measured on the wire: `GET /` 237,812 → 58,378 B; `GET /api/messages` at the retention cap
517,160 → 63,193 B; the same poll when nothing changed → **227 B**. `ResponseEncodingTest` covers the
encoding, the framing, the per-address nature of the validator (two tabs with different visible
history must not share one), a room that changed, and that a `304` does not desync the keep-alive
connection it arrived on.
Deliberately *not* claimed: poll latency over a socket barely moved (p95 20 → 14 ms), because the
route still serialises the whole history before there is a fingerprint to compare — see P1-1.

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
| C-1 | Docs/version drift: README showed v1.4.0/v1.3.0/v1.1.2 VirusTotal rows and the pre-1.7 `PulseDrop-<version>.apk` naming, `docs/Installation.md` called 2.0.1 the current release, `CONTRIBUTING.md` said Gradle 9.3.1 / `compileSdk` 36, and `docs/Home.md` called the unpublished 2.1.1 draft a release. **Docs fixed** on `docs/version-references`, verified by hashing the published asset: what a checkout builds is stated separately from what is published. `docs/Protocol-V2.md` no longer names 2.0.1 as what `/api/info` reports, and the deployment inventory no longer describes the pre-1.7 asset naming. **Closed:** `dev/check-version-refs.sh` runs in CI and cross-checks `versionName`/`versionCode`, `compileSdk`, the wrapper's Gradle version and the reference SHA-256 against the docs, then asks GitHub for the newest published release (`--online`). It proved itself immediately: v2.1.1 was published while these docs named 2.0.2, and the check failed until they named 2.1.1. | docs |

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
| X-4 | CodeQL alert #1, CWE-927 (implicit/mutable `PendingIntent`) at `diag/CrashReporter.kt`. The mutable half was already false (`FLAG_IMMUTABLE`); the real half was that the intent was *implicit* (`ACTION_VIEW` + a `github.com` URL), so it resolved to whichever app claimed that host, with the crash markdown — device model, Android version, stack trace — in the query string and in browser history. **Fixed:** the notification now targets `CrashReportActivity` explicitly (and `exported=false`); it carries only the report's file name, the screen reads the report from private storage, shows it, copies it on request and opens the *empty* new-issue page. No report text enters an intent or a URL. Proved by `CrashReportIntentTest`. | [fixed] P2 |
| X-5 | Dependabot *security* updates (the ones that patch a known-vulnerable dependency) are a repository setting under Code security, not something this file can turn on. Needs someone with administration. | [recommendation] owner action |
| X-6 | `actions/setup-java@v4`'s current tip commit was literally "Deprecate setup-java v4", and checkout/upload-artifact/setup-android/setup-gradle were one to three majors behind. | **Fixed** by Dependabot on `main`: checkout `v7.0.1`, setup-java `v6.1.0`, setup-android `v4.0.4`, setup-gradle `v6.4.0`, upload-artifact `v7.0.2`, CodeQL `v4` — each a full-SHA pin and each the current release when checked. Nothing left to bump by hand. |
| X-7 | `gradle-wrapper.properties` named a distribution URL without `distributionSha256Sum`, so the wrapper would execute whatever the CDN returned for that URL. The 9.6.0 bump made this a fresh URL nobody had vetted; the checksum is now pinned (verified against a fresh download). | **Fixed** on `ci/wrapper-checksum` |
| X-8 | `androidx.navigation:navigation-compose` 2.10.2 cannot be adopted alone: it forces Compose UI 1.11.2 over the BOM's 1.7.2 and requires `compileSdk 37`, so the build is either half-upgraded or does not configure. Verified on a scratch tree that a coherent stack (BOM `2026.06.01`, lifecycle 2.11.0, `compileSdk 37.2`) makes all 363 tests pass with two test adaptations (one asserts the first child of a status row, one clicks below the fold). Reverted on `main` instead, because a Material3 1.4 upgrade changes rendering and belongs on a device, not in a CI repair. | [recommendation] coordinated upgrade with on-device review |

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
