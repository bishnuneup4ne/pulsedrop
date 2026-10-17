# PulseDrop Protocol v2

How v2.0 adds capabilities without breaking phones that still run v1.7. The v1 wire stays
readable by every build that ever shipped, so this is an **additive** spec: nothing here
redefines an old field.

## Version namespace

`/api/info` keeps `"version"` starting with `"2"` (currently `"2.1.0"`). This is load-bearing:
`LanScanner` classifies a peer as pair-capable only when the version starts with `"2"`.
The MAJOR only moves when old phones are *expected* to stop pairing, which is never while an
upgrade path exists.

New builds read the additive `"caps"` array from `/api/info`, from the discovery UDP beacon,
and from the `appVersion` already exchanged at pairing time. Absent = no caps.
Every pre-"2.1.0" build ignores unknown keys by design.

## Capability strings

| Cap | Meaning | Announced by |
| --- | --- | --- |
| `h264` | Media socket may carry types 9/10 | phone v2 with HW encoder, browser tab with WebCodecs |
| `screen-h264` | Media socket may carry type 11 — this end owns a HW AVC **decoder** and a surface to present a shared screen onto | phone v2 with HW decoder |
| `binary-uplink` | `/api/webcall/up` accepts raw `[type][len][payload]` batches (`?enc=binary`) | v2 web client |
| `kdf-v2` | New pairings may negotiate HKDF key derivation (existing pairs stay v1 forever) | phone v2 |
| `chat-ext1` | Chat JSON may carry `reaction`, `readUpTo`, `replyTo`, `voiceDur` keys | phone v2, web client |
| `conf2` | Conference H.264 fan-out (type 10) | phone v2 host |

`h264` and `screen-h264` are independent promises about independent hardware, so a phone may
advertise either, both, or neither. A viewer that can decode but not encode must not be asked to
encode; a sharer must not encode a screen for a peer that cannot show it.

Rules:
1. A sender uses a cap only when **both ends** advertised it for that connection.
2. A peer that advertises `h264` must still accept types 1–7 (the JPEG lane is the fallback,
   not a legacy mode — a mid-call encoder failure drops to it via a `keyframe-request`
   dance, not a reconnect).
3. Unknown media types and unknown JSON keys must be skipped, never fatal. A frame of an
   unknown type is consumed by length and dropped.

## Media framing (unchanged core)

`[1B type][4B BE payload length][payload]`, payload cap 4 MiB, over:
- phone↔phone: raw TCP, one socket per call (per leg in a conference);
- phone↔browser: the same framing inside `/api/webcall/down` chunked responses and
  `/api/webcall/up` POST batches (batches may split mid-frame).

| Type | Name | Payload | Frozen since |
| --- | --- | --- | --- |
| 1 | audio | PCM16 mono 16 kHz, 100 ms chunks | v1 |
| 2 | camera | JPEG | v1 |
| 3 | screen | JPEG | v1 |
| 4 | screen-state | JSON `{"sharing":bool,"muted":bool}` | v1 |
| 5 | conf-audio | `[1B srcLen][src][pcm]` | v1 |
| 6 | conf-video | `[1B srcLen][src][1B kind][jpeg]`, kind 2 = screen else camera | v1 |
| 7 | conf-state | JSON roster | v1 |
| 8 | control | JSON, see below | v2 |
| 9 | h264 | one H.264 access unit (annex-B, no start codes needed — one AU per frame) | v2 |
| 10 | conf-h264 | `[1B srcLen][src][access unit]` | v2 |
| 11 | screen-h264 | one H.264 access unit of a **shared display**, same shape as type 9 | v2 |

Type 11 exists rather than a flag on type 9 because a camera picture and a desktop picture are two
reference chains, and one decoder must never see both: fed into a single chain, the peer predicts a
face against a spreadsheet and the result is corruption on *both* tiles. It is also the only way an
older peer can be *seen* to ignore the lane — unknown types are skipped by length, so a build that
predates this cap keeps receiving the JPEG share (type 3) and never hears about the encoded one.

Inside an access unit, parameter sets are the sender's problem: `csd-0`/`csd-1` arrive once from the
encoder and are re-stamped in front of every IDR that does not already carry them. An inter-predicted
frame that has never had a parameter set in front of it is **not sent at all** — the sender forces a
keyframe instead, because a P-frame on its own reaches the peer as noise.

The single owner of this format in code is `media/protocol/FrameCodec.kt`;
`FrameCodecTest` freezes the byte layout with hand-written goldens.

### Control frames (type 8)

```json
{ "t": "keyframe-request" }                     // PLI: sender must emit an IDR now, on every lane it owns
{ "t": "keyframe-request", "lane": "screen" }   // or "camera": one lane only
{ "t": "caps", "caps": ["h264", ...] }          // post-hoc cap exchange at media start
{ "t": "stats-ping" } / { "t": "stats", "fps": .., "bps": .., "tier": "hd" }
```

Unknown `"t"` values are ignored. Control frames never carry media.

A `keyframe-request` without `"lane"` deliberately asks *both* lanes, because the requester's own
loss of picture rarely names which one went stale, and an extra IDR costs one frame of bitrate.
The `"lane"` field is additive: a sender that does not know it takes the un-lane'd behaviour.

The `caps` frame is sent by the *media socket's* opening side, right after the socket is attached,
because the discovery beacon and the `/api/info` probe are not guaranteed to have run for this peer —
without it, a late-announcing phone would be refused a lane it was always able to carry. Capability
is therefore merged from all three sources, additively: announcements may only ever *add* caps, so a
lost beacon cannot downgrade a peer mid-call.

## Quality tiers

The sender's ladder (see `media/ladder/`), from best:
`1080p30@6M → 1080p30@4M → 1080p15@3M → 720p30@3M → 720p15@2M → 540p15@1.5M → 360p15@0.8M`.
JPEG fallback lane (no `h264`, or no HW encoder): the v1 rates — ~10 fps max at 1080p.
Conferences cap at 720p30 by default.

The tier actually used is `QualityLadder.fitTo(BEST, width, height)` against the *real* geometry the
device can source, not the nominal one the UI asked for: a phone whose camera tops out at 720p is
labelled 720p rather than claiming a resolution it never encodes. A shared screen starts at the best
tier the display's scaled size permits.

The screen lane does **not** step down the ladder on measured fps. A desktop that is merely sitting
there legitimately produces one frame a second, and a ladder reading that as congestion would dim a
perfectly sharp picture to 360p for no reason. Its rate is fixed by the tier; the encoder only ever
hears "make a keyframe".

## Compatibility matrix

| New ↔ old | Signaling | Pairing | Media |
| --- | --- | --- | --- |
| v2 phone ↔ v1.7 phone | unchanged `/api/call/*` | unchanged ECDH + KDF v1 | types 1–7 JPEG lane |
| v2 phone ↔ old cached tab | unchanged | n/a | base64 uplink kept, JPEG both ways |
| v2 phone ↔ v2 tab | +`caps` | n/a | H.264 + binary uplink when WebCodecs present |
| v2 phone ↔ v2 phone, screen share | unchanged | n/a | type 11 when both ends have `screen-h264`, else type 3 JPEG |
| mixed-version conference | host runs lowest common format (JPEG 720p) with a UI warning | — | never transcode at the host |

Proof strings `pulsedrop-upload-v1` / `pulsedrop-relay-v1`, the KDF v1 derivation,
pairId/deviceId/safety-code formulas and every `/api/*` path are **frozen**.
