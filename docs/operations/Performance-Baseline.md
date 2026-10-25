# Performance Baseline — measured before anything was optimized

Deliverable for Stage 1 of the Performance Recovery PRD: reproduce, instrument, measure, and only
then name a cause. Every number below came from a command listed in *Commands actually run*, on
`main` @ `c297c28`, and each claim is annotated the way `Issue-Backlog.md` annotates its findings:

* **[confirmed]** — measured here, or read at the cited line and reproduced by a test.
* **[code-confirmed, unmeasured]** — the code says so at the cited line; this machine cannot time it.
* **[hypothesis]** — plausible, not demonstrated. It is listed so it does not quietly become a plan.

---

## What can be measured here, and what cannot

The host — `PulseDropServer` on NanoHTTPD 2.3.1 — runs unmodified under Robolectric, so handler
cost, payload size, socket throughput and event fan-out are all real measurements. Two of the four
symptoms the PRD names are **not measurable on this machine at all**:

| PRD symptom | Measurable here? | Why |
| --- | --- | --- |
| Web client freezes / lags | Partly | The server side and the bytes the browser must parse are measured; the browser's main thread, its layout and its garbage collector are not — no chromium, no puppeteer. |
| Connection is slow to init | No | `MainViewModel.startServer()` is Android lifecycle code. No adb, no emulator, no system image, no `/dev/kvm`. |
| Video stutters | No | Camera, MediaCodec and a live Wi-Fi link. Nothing on this box has a camera. |
| Network bottlenecks | Loopback only | Throughput here is the Linux loopback driver's ceiling, not a phone's radio. |

So: a figure below is a figure about **NanoHTTPD on an 8-core x86 CPU with 31 GB of RAM**, and a
regression on a phone can exist with every one of these numbers unchanged. That is stated rather
than hedged because the PRD's §13 asks for p50/p95 over repeated trials, and the only honest way to
hand over p50/p95 from a machine that is not the target is to say which machine it is.

---

## The numbers

`HostBaselineProbe` (test sources, skipped unless `PULSEDROP_PROBE=true`) prints these rows; 15–20
samples per measurement, percentiles of the samples actually taken.

### The document, and the poll that follows it

| Route | Payload | Handler p50 | Handler p95 |
| --- | --- | --- | --- |
| `GET /` | **236,388 B** | 0 ms | 3 ms |
| `GET /api/messages` @ 1 message | 257 B | 0 ms | 0 ms |
| `GET /api/messages` @ 200 | 51,491 B | 1 ms | 2 ms |
| `GET /api/messages` @ 1000 | 257,891 B | 3 ms | 3 ms |
| `GET /api/messages` @ 2000 | **516,891 B** | 6 ms | 11 ms |

Handler cost is in-process (no socket), so this is the route's own work, not the network's. The
message route is capped by `SERVER_CHAT_RETENTION = 2000`, so 517 KB is the ceiling for one read,
and the tab pays it *in full* every time anything changes.

### Transfers, and whether a transfer moves anything else

| Measurement | Value |
| --- | --- |
| 32 MiB upload | 157 ms (≈203 MB/s) |
| 32 MiB download | 20 ms (≈1600 MB/s), 33,554,738 B read by the client |
| Heap growth during that download | **0 MiB** |
| `GET /api/messages` while idle | p50 1 ms / p95 2 ms |
| `GET /api/messages` during a 32 MiB upload | p50 1 ms / p95 2 ms |

### Events

| Measurement | Value |
| --- | --- |
| Doorbell → one tab, 10 rounds | p50 0 ms / p95 0 ms, 0 missed |
| 200 doorbells pushed to 5 tabs | 2 ms total |
| 4 reading tabs while a 5th stopped draining | p50 1 ms / p95 1 ms, 0 missed |
| Eight seats held, ninth asked | eighth opened: 8/8 · ninth: **503** · oldest tab: **still delivered** |

### A reconnecting tab

Six requests to catch up (`/api/messages`, `/api/threads`, `/api/files`, `/api/peers`, `/api/info`,
`/api/typing`), 0 ms of handler time against an empty room, `requests_per_reconnect = 6`.

---

## Confirmed bottlenecks, one per symptom

**1. [confirmed] Every message read is the whole history, and one doorbell could start several of
them at once.** `PulseDropServer.kt:2094-2131` serialises every visible message under
`synchronized(messages)` — no cursor, no `since`, no pagination — and `WebClientHtml.kt:4860-4867`
asks for all of it every 2 s. On top of that the `message` and `deleted` doorbells called
`fetchMessages()` directly, with nothing stopping a second, third and fourth copy from running
concurrently while the first was still in flight. Two tabs in a busy room therefore did not read
the room once per change but once per change *per outstanding read*.

Fixed in this batch (see *What this batch changes*), because the concurrency guard needs no API
change. The 517 KB payload itself is not fixed: giving the route a cursor changes its shape, and
that is a decision, not a patch.

**2. [confirmed] A room one tab too large went intermittently deaf.** `maxEventStreams = 8`
(`PulseDropServer.kt:557`) was enforced by evicting the oldest stream. The evicted tab's reconnect
then evicted the next, and the next — nine tabs in a room produced a rotating blackout in which no
tab could trust its stream, which reads as "the web client stopped updating" rather than as any
error. Fixed: the newcomer is refused with `503` + `Retry-After: 15` and keeps its poll, so
everybody already streaming keeps streaming. Measured above (`ninth: 503`, `oldest: still
delivered`).

**3. [confirmed] A closed tab's seat came back up to forty seconds later.** One stream is one
handler thread parked in `queue.poll(heartbeatMs)` (`:605`, `heartbeatMs = 20_000L` at `:558`), so
nothing notices a reader that vanished until a write fails — and with no traffic, that is the next
heartbeat, and if *that* write is swallowed by the kernel buffer, the one after. Before this batch
the eviction path hid it; refusing the newcomer would otherwise have made a returning tab wait out
the whole interval for a seat that was already free. Fixed: an admission attempt now probes every
holder with a comment frame before refusing, which forces the write that discovers the dead socket.
Measured: `a seat returns when its tab goes away` refuses, closes a tab, is re-admitted and receives
the next message in 693 ms of test time — and asserts the seat comes back inside 5 s — against a 20 s
heartbeat.

**4. [confirmed] Reading a PDF froze the UI, and picking files to send did too.**
`AttachmentPreview.kt`'s `PdfStage` rendered a page — allocating a bitmap up to 2200 px wide — *from
inside composition*, so every recomposition re-ran it on the main thread. `SendScreen.kt` resolved
file metadata for every selected uri on the main dispatcher under a `LaunchedEffect` whose comment
claimed it was off the main thread. Both now run under `Dispatchers.IO`.

**5. [code-confirmed, unmeasured] Starting the server blocks the thread that draws it.**
`MainViewModel.kt:1159 startServer()` is called from `init` and from the server toggle's click
handler, and `:1317 startWithSettle` retries `server.start()` with `Thread.sleep(350L * attempt)` up
to four times — on the caller's thread, i.e. the main thread. On a phone with a busy port or a slow
bind that is up to ~2 s of an unresponsive screen, and it is exactly the "connection is slow to
init / the app freezes" symptom. Not timed here: no emulator, and the cost is Android's, not
NanoHTTPD's.

**6. [code-confirmed, unmeasured] The quality ladder can only ever step down.**
`QualityLadder.stepUp` (`media/ladder/QualityLadder.kt:89`) has **no production call site** — only
its own tests (`QualityLadderTest.kt:65,67,192`). A call that dips once under `stepDown` stays dipped
for its remaining life, which reads as "the video got bad and never recovered".

**7. [code-confirmed, unmeasured] The browser leg of a call is JPEG, and its queue is not byte
bounded.** The browser path compresses every frame with `yuv.compressToJpeg(..., 70, bos)`
(`CallEngine.kt:2046`) off a legacy `android.hardware.Camera` (`:11`, camera thread `:1933`), while
the H.264 encoder/decoder is fully implemented for phone↔phone. `webOut` is a
`LinkedBlockingQueue<ByteArray>(96)` (`:367`) with drop-oldest, bounded in *frames*, not bytes: at
the 1 MiB per-frame cap that is ~96 MiB of queued JPEG, on a phone, during a call.

**8. [confirmed, not a bottleneck] Bulk transfers do not stall the host, and fan-out is not slow.**
A 32 MiB upload left message latency unchanged (1 ms/2 ms both ways), a download grew the heap by
nothing, and 200 events across 5 tabs cost 2 ms with a stalled reader costing the others ~0 ms. So
"the phone grinds to a halt while a file moves" and "notifications arrive late" are **not** explained
by server-side throughput or by the event path on this hardware. If they reproduce on a phone, the
candidates are the radio (one client saturating the AP and everyone's TCP backing off), the browser's
parse of the payloads in §1, or the media lane in §7 — not fan-out.

---

## Corrected PRD premises

The PRD lists these as problems to fix. Reading the current code, they are not what it says; §17
asked for them to be revalidated rather than copied.

1. **"Files are buffered whole in RAM."** Not on the download side (`handleFileDownload` streams a
   live `InputStream`, measured as 0 MiB heap growth for 32 MiB) and not on the upload side, which
   spools to a temp file and copies in 8 KiB blocks. The real upload cost is *disk* — a multipart
   body lands in a `cacheDir` temp file, is mmap'd whole by NanoHTTPD's `parseBody`, is written to a
   second temp file and then renamed: roughly three times the file's size touched per upload.
2. **"H.264/WebCodecs is advertised but unimplemented."** The browser leg is JPEG-only, which is
   honest; H.264 exists and works between phones. Nothing needs unpublishing, and the PRD's
   "prefer WebRTC" is a proposal to add a dependency, not to fix a lie.
3. **"A boot request waterfall."** A fresh tab issues six reads (`requests_per_reconnect` above) and
   they are not on the critical path of first paint; there is no measured startup cascade to untangle.
4. **"Add gzip and caching headers."** Correct that there is no `Content-Encoding`, `ETag` or `Vary`
   anywhere in the server package — so the 236 KB document and the 517 KB message payload go out
   uncompressed and un-revalidatable, every time. This is a real gap, but it is a *decision* about
   response handling in NanoHTTPD, not a patch to slip in quietly.

---

## What this batch changes

Four fixes, each with a test that fails without it, all currently **uncommitted in the working
tree** — nothing here is pushed, per the PRD's own rule that committing needs explicit approval.

| Change | Files | Test |
| --- | --- | --- |
| One message read at a time, with a trailing re-run so a change during a read is never lost; SSE reconnect on exponential backoff with jitter that resets only after a connection that actually lived; the event ticket taken from the same cache every other ticketed URL uses; a pending reconnect cancelled on unload. | `server/WebClientHtml.kt` | `WebClientRequestDisciplineTest` — 7 tests over the served page text |
| A ninth stream is refused, not bought with somebody else's. | `server/PulseDropServer.kt:1595-1626` | `EventStreamAdmissionTest` |
| A refused admission probes the holders first, so a closed tab's seat returns in about a second rather than at the next heartbeat. | same route | same test, `a seat returns when its tab goes away` |
| PDF pages and send-screen metadata off the main thread. | `ui/components/AttachmentPreview.kt`, `ui/screens/SendScreen.kt` | `SendScreenTest`; the preview change is verified by build + suite only |
| Test transport: a chunked-response reader, because asserting on a stream without decoding its framing measures the test. | `RoomHttp.kt` (`live`, `LiveStream`) | used by the admission test and the probe |

The page grew by 1,875 bytes (234,513 → 236,388) as the discipline was added. That is the cost of
one fix being paid by every tab, and it is worth stating next to the 517 KB it avoids re-reading.

---

## Ranked remaining work

P1 is what the measurements say still hurts; P2 is real but slower. Each says what it needs from the
owner, because "fast" and "allowed" are different questions.

| # | Item | Evidence | Risk / needs a decision |
| --- | --- | --- | --- |
| P1-1 | Give `/api/messages` a cursor (`?since=`), so a tab reads what changed instead of the room's whole life. | §1: 517 KB per read, `:2094` | Changes an endpoint's shape and every reader of it. Needs approval. |
| P1-2 | Get `startServer()` and `startWithSettle` off the main thread. | §5: `MainViewModel.kt:1159,1317` | Touches lifecycle, no test coverage of `MainViewModel` exists today. Needs a device to prove. |
| P1-3 | Compress and revalidate the document. | §4 correction 4: no gzip/ETag anywhere | NanoHTTPD has no built-in encoding; needs a response hook. Needs approval. |
| P1-4 | Bound `webOut` in bytes, not frames, and stop the camera/mic when the tab reading them is gone. | §7: `CallEngine.kt:367` | A watchdog that kills a call is a behaviour change. Needs a device. |
| P2-1 | Let the ladder step back up, or delete `stepUp`. | §6: no production call site | Small, but only measurable in a call. |
| P2-2 | Decide the browser media lane: WebRTC (a new dependency, and no mandatory external service is allowed) vs adaptive JPEG profiles. | §7 | The PRD prefers WebRTC; §17 says do not replace a working API without a demonstrated benefit. Not demonstrated yet, because nothing here has a camera. |
| P2-3 | Audio priority / separate queues. | not instrumented | Needs a call to observe. |
| P2-4 | Modularise the web client into `web/js/*.js` with no CDN. | X-3: it is JS inside a Kotlin string, invisible to linters and extractors | Changes packaging. Belongs to the Architecture PRD, not to this one. |

---

## Commands actually run, and what they returned

```
./gradlew :app:testDebugUnitTest --tests 'com.pulsedrop.app.server.EventStreamAdmissionTest'
    BUILD SUCCESSFUL — 2 tests, 0 failures (4.634 s, 0.693 s)
./gradlew :app:testDebugUnitTest                        # the whole unit suite
    BUILD SUCCESSFUL in 4m 30s — 70 classes, 548 tests, 5 skipped, 0 failures, 0 errors
./gradlew :app:testDebugUnitTest                        # again, over the tree this document describes
    BUILD SUCCESSFUL in 4m 57s — 70 classes, 548 tests, 5 skipped, 0 failures, 0 errors
./gradlew :app:lintDebug
    BUILD SUCCESSFUL in 32s — no issues reported
PULSEDROP_PROBE=true ./gradlew :app:testDebugUnitTest --tests '…HostBaselineProbe'
    BUILD SUCCESSFUL in 1m 13s — the rows above
```

Five skipped: the probe's own tests, which stay skipped in an ordinary run on purpose, so no CI job
turns a loopback timing into a flaky failure.

**Not run, and not claimed:** no instrumented or on-device test ran (no adb, no emulator, no KVM); no
browser was opened, so nothing in *Web client* above is a browser measurement; no call was placed, so
§6 and §7 are code readings, not timings. `lintRelease` was not run — `lintDebug` is the variant these
sources build under.

---

## How to close the gap this leaves

The PRD's §4 targets that are still unproven here — input-to-visible under 100 ms, no JS task over
100 ms, video startup, 25–30 FPS — all need two things this machine does not have: a browser and a
phone. The cheapest version of that is one session with Chrome's Performance panel on a room with
2000 messages, and one call from a phone to a laptop browser, with the frame counters the code
already keeps. Everything in P1-1 and P1-3 is otherwise a guess dressed as a number.
