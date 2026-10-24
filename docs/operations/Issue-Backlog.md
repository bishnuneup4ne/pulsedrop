# Prioritized Issue Backlog

Deliverable 3 of the engagement. Every item below is annotated with how well I know it to be true:

* **[confirmed]** — I read the current code at the cited line and the claim holds.
* **[needs test]** — the code shape is suspicious but exploitability depends on runtime behaviour I
  have not executed yet.
* **[recommendation]** — proposed work, not a defect claim.

Baseline: `main` @ `98c2a40`, app `2.0.2` / versionCode 12. PRD reviewed the same commit, so it is
not stale. Findings the PRD asserted were re-checked here rather than copied.

---

## P0 — block a "security hardened" release

### S-1 [confirmed] The PIN *is* the browser session token

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

### S-2 [confirmed] PIN credentials travel in URLs

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

### S-4 [confirmed] Any authenticated client can hang up the phone's active call

`:1309-1311` — `action=end` accepts when `cid` is **blank**: `if (c != null && (json.optString("cid").isBlank() || c.cid == ...))`.
Compare `accept`/`decline` two branches above (`:1302`) which correctly require
`c.outgoing && sameAddr(c.ip, clientIp)`.

*Risk:* denial of service against a live call by any tab holding the PIN — or, since the whole tier
becomes public when the PIN is off (`:981`), by any host on the LAN. Also returns `success:true`
whether or not a call existed, so it cannot be distinguished by the owner.
*Fix:* require the caller's address to own the `webCall` record, exactly the `:1302` pattern; 403 on
mismatch. *Tests:* tab A cannot end tab B's call; blank cid no longer ends anything.

### S-5 [confirmed] `/download-folder` has no per-owner scope and no size bound

`:1560`, implementation `:1735-1754`. `sanitizeRelativePath` + canonical check prevents escaping
`WebDrop_Received`, which is good — but there is **no ownership check**, so any authenticated client
can zip and read another client's *directed* folder, and the ZIP is built into `cacheDir` with no
cap. `:1770` reflects the exception message into the body.

*Fix:* apply the same sender/receiver predicate used by `/api/files` (`:1517`); cap total expanded
bytes and entry count; stream rather than pre-build where possible; stop echoing exception text.
*Tests:* client A cannot download client B's directed folder; oversized folder refuses cleanly; no
path escapes the root.

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

---

## P1 — next release cycle

### R-1 [confirmed] `/api/webcall/up` has no bound on the body or decoded frames
`:1344-1359`: base64 batches are decoded and handed to the engine with no size cap; `parseBody` has
already buffered the request. Add a per-request byte cap, a per-frame cap, and bounded queues.
*Tests:* oversized body → 413; malformed/truncated frame → rejected without crash; slow client can't
grow memory.

### R-2 [confirmed] Upload limits must hold *while streaming*
`/upload` at `:1566` takes `?path=` from the query (`:1873`), has a free-space preflight (`:1895`) and
timestamp-suffix duplicate handling (`:1886`), but a client can send an unbounded body with a missing
or false `Content-Length`. Enforce per-file/per-client/concurrent/total quotas during the copy, write
to temp, atomically rename on success only, and clean partials on cancel/disconnect/failure/startup.

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

```
JDK 21 + Android SDK present  →  baseline ./gradlew test green
   └─ S-1 sessions  ─→ S-2 no URL credentials  ─→ S-3 route policy
                          (tickets need tokens first)
   └─ S-4, S-5, R-1 are independent and can land in parallel with S-1
```

S-6 (identity fallback) and S-7 (transport labelling) touch protocol compatibility and device state;
they get their own PR after the auth chain, with a migration plan rather than a behaviour flip.
