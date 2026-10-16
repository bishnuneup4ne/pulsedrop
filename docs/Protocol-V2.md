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
| `binary-uplink` | `/api/webcall/up` accepts raw `[type][len][payload]` batches (`?enc=binary`) | v2 web client |
| `kdf-v2` | New pairings may negotiate HKDF key derivation (existing pairs stay v1 forever) | phone v2 |
| `chat-ext1` | Chat JSON may carry `reaction`, `readUpTo`, `replyTo`, `voiceDur` keys | phone v2, web client |
| `conf2` | Conference H.264 fan-out (type 10) | phone v2 host |

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

The single owner of this format in code is `media/protocol/FrameCodec.kt`;
`FrameCodecTest` freezes the byte layout with hand-written goldens.

### Control frames (type 8)

```json
{ "t": "keyframe-request" }              // PLI: sender must emit an IDR now
{ "t": "caps", "caps": ["h264", ...] }   // post-hoc cap exchange at media start
{ "t": "stats-ping" } / { "t": "stats", "fps": .., "bps": .., "tier": "hd" }
```

Unknown `"t"` values are ignored. Control frames never carry media.

## Quality tiers

The sender's ladder (see `media/ladder/`), from best:
`1080p30@6M → 1080p30@4M → 1080p15@3M → 720p30@3M → 720p15@2M → 540p15@1.5M → 360p15@0.8M`.
JPEG fallback lane (no `h264`, or no HW encoder): the v1 rates — ~10 fps max at 1080p.
Conferences cap at 720p30 by default.

## Compatibility matrix

| New ↔ old | Signaling | Pairing | Media |
| --- | --- | --- | --- |
| v2 phone ↔ v1.7 phone | unchanged `/api/call/*` | unchanged ECDH + KDF v1 | types 1–7 JPEG lane |
| v2 phone ↔ old cached tab | unchanged | n/a | base64 uplink kept, JPEG both ways |
| v2 phone ↔ v2 tab | +`caps` | n/a | H.264 + binary uplink when WebCodecs present |
| mixed-version conference | host runs lowest common format (JPEG 720p) with a UI warning | — | never transcode at the host |

Proof strings `pulsedrop-upload-v1` / `pulsedrop-relay-v1`, the KDF v1 derivation,
pairId/deviceId/safety-code formulas and every `/api/*` path are **frozen**.
