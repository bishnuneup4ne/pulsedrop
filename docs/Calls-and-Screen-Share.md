# Calls and Screen Share

A call carries several independent streams, and PulseDrop picks the format for each one
separately, every second, based on what the hardware at both ends is actually doing. This
page is about which lane you are on and why.

## The lanes

| Lane | Frame type | Camera/face | Shared screen |
| --- | --- | --- | --- |
| JPEG | 2 (camera), 3 (screen) | The default; always available | The fallback; the only option in a group call and for every browser tab |
| H.264 | 9 (camera), 11 (screen) | Hardware encoder when both ends claim `h264` | Hardware encoder when both ends claim `screen-h264` |
| Conference | 5, 6, 10 | Mixed by the host | Type 10 exists in the spec but is never announced |

Audio is PCM16 mono at 16 kHz in 100 ms chunks on type 1, in every lane.

A camera picture and a desktop picture are **two reference chains**, so they travel on two
frame types and are fed to two decoders. Mixing them into one decoder is how a call ends up
painting a spreadsheet out of a chin — the peer predicts a face against the wrong history and
both tiles corrupt.

## Screen share: when H.264 is used

The encoded share is offered only when **all** of these hold, checked at the moment the
capture service asks for a surface:

1. A call is live and its media socket has started.
2. The peer announced the `screen-h264` capability — a promise about its **decoder and
   display**, not its encoder. It is checked on both registry keys (by IP and by pairing id),
   and either answer counts.
3. This device has a **hardware** AVC encoder. Software codecs are refused by name: anything
   matching `OMX.google…` or `c2.android…` does not count. Encoding a desktop in software
   costs more CPU than the JPEG it would replace.
4. It is a **one-to-one call**. A group call stays on JPEG, because the conference grid draws
   one bitmap per tile and has nowhere to present a second decode chain.
5. The geometry is non-zero and **even** in both dimensions — 4:2:0 has no odd sizes.
6. The encoder actually started. If every candidate codec throws during
   create/configure/`createInputSurface`/start, the answer is "no surface" and JPEG continues.

If the encoder dies mid-share, a fault is latched so the capture loop stops rebuilding a
hardware block that already refused once, and the JPEG pump — which was never closed —
carries on. The share degrades; it does not go black.

**None of these refusals are announced to the user.** They are all ordinary hardware
variation, and the only thing you can read is the truth in the stats line: it says
`share … 864x1920@30` when encoded and `share jpeg` when not.

A **browser tab never gets the encoded lane.** The web client announces no capabilities, has
no media socket for the check to run against, and contains no H.264 decoder — no WebCodecs,
no `VideoDecoder`. A tab sends and receives JPEG, and that is the whole story.

## Resolution: what the label means

The label is what the device can source, never what was asked for.

The display's **real** size is scaled so its long edge fits 1920 px, rounded, then forced
even. A 1080 × 2400 phone panel therefore becomes **864 × 1920** — not 1080p. The encoder
tier is re-cast onto that geometry and its label rewritten to `864x1920@30`, because keeping
the nominal name would have the overlay advertising a resolution no frame ever had.

Rotation is handled by rebuilding the capture at the new size, and a lane configured for a
different geometry is torn down and re-earned rather than reused.

The same rule applies to the camera: the sensor is asked for the largest preview under the
tier's pixel budget, the result is rotated to how the phone is held, and the tier is re-cast
onto what came back. A phone whose camera tops out at 720p says 720p.

## Quality tiers

The ladder, best to worst:

| Tier | Size | fps | Bitrate |
| --- | --- | --- | --- |
| `1080p30` | 1920×1080 | 30 | 6 Mbps |
| `1080p30/4M` | 1920×1080 | 30 | 4 Mbps |
| `1080p15` | 1920×1080 | 15 | 3 Mbps |
| `720p30` | 1280×720 | 30 | 3 Mbps |
| `720p15` | 1280×720 | 15 | 2 Mbps |
| `540p15` | 960×540 | 15 | 1.5 Mbps |
| `360p15` | 640×360 | 15 | 0.8 Mbps |

- The **camera** lane starts at `1080p30`, or at `720p30` in a conference, and steps **down**
  when measured inbound fps stays below three-quarters of the tier rate for three seconds.
- The **screen** lane does not step at all. It is fixed at the best tier re-cast to the real
  display size: 30 fps, 6 Mbps CBR. A desktop that is merely sitting there legitimately
  produces about one frame a second, and a ladder reading that as congestion would dim a
  perfectly sharp picture to 360p for no reason.
- Frames on the encoded lane are paced by the display server, not by an app timer.

The JPEG fallback for a share is 8 fps at quality 82 on a 1920 px long edge, which spends
roughly 15 Mbps to do it — the reason the encoded lane exists. The JPEG camera lane is
throttled to 10 fps.

## Encoder settings

`video/avc` in surface-input mode (`COLOR_FormatSurface`), so the display is rendered
straight into the codec rather than being copied through CPU memory:

- CBR rate control, `KEY_LATENCY = 0`, `KEY_PRIORITY = 0`
- I-frame interval **2 seconds** — a phone joining mid-call waits at most two seconds for a
  picture, which is why an on-demand keyframe matters more than a short interval
- Baseline profile, Level 4.0 attempted first (Level 3.1 cannot carry a 1920×1080 frame at
  any frame rate), falling back to no profile constraint if the codec refuses
- Keyframes on request via `PARAMETER_KEY_REQUEST_SYNC_FRAME`, not the zero-length-GOP trick

## Reading the stats line

Tap the status line in a call to toggle it. It is one dot-joined line, rebuilt every second
from counters that then reset:

`1080p30 · 28 fps up · 5.4 Mbps · 24 fps down · 3 dropped · share 864x1920@30 · 29 fps share · 5.8 Mbps share`

What is measured, honestly:

- **fps up / Mbps** are counted from frames actually handed to the peer's media socket.
- **fps down** counts inbound camera frames. An encoded peer *screen* (type 11) is decoded and
  displayed but is not added to that counter — read the share figure for that.
- **dropped** counts frames the camera decoders discarded while waiting for a keyframe. Drops
  on the screen decoder are not surfaced.
- On the JPEG camera lane, up-figure reads 0 rather than guessing: JPEG frames are not counted
  by the same counter, and a sub-second window reports 0 instead of extrapolating.
- The peer answers a `stats-ping` with its own measured numbers, so the stats you see about
  the other side are their measurement, not an inference.

## Keyframes and recovery

A receiver that loses its picture asks for a new one with a control frame (type 8):

```json
{ "t": "keyframe-request" }                      // both lanes
{ "t": "keyframe-request", "lane": "screen" }    // or "camera"
```

No `lane` field deliberately means "both", because the side that lost the picture rarely
knows which lane went stale, and one extra IDR costs a frame of bitrate. The field is
additive: a sender that predates it takes the both-lanes behaviour.

On the sending side, H.264 parameter sets (`csd-0`/`csd-1`, i.e. SPS and PPS) are the
sender's problem. Vendors deliver them inconsistently, so they are normalised to start-code
form and re-stamped in front of every IDR that lacks them. An inter-predicted frame that has
never had a parameter set in front of it is **never put on the wire at all** — the sender
forces a keyframe instead, because a lone P-frame reaches the peer as noise.

On the receiving side, one decoder instance is kept per remote source, and a separate one per
shared screen, and any access unit arriving before the first IDR is dropped and counted until
a keyframe lands.

## Practical expectations

- Two 2.0 phones, one-to-one, both with hardware AVC codecs: a screen share moves as H.264 at
  the display's scaled size, up to 30 fps and ~6 Mbps.
- Any other combination — a browser tab, a 1.x phone, a group call, a device without a
  hardware AVC encoder, a peer that cannot decode: JPEG, 8 fps, higher bandwidth.
- All of this is per-call and re-decided continuously; an encoder that fails mid-call drops to
  JPEG without reconnecting.
- **Unverified:** the 1080p30 figure describes what the encoder is configured for. It has not
  been confirmed by watching a handset hold it, so treat sustained frame rate on your specific
  device as unknown until reported.

Related: [Protocol v2](Protocol-V2.md) for the byte-level format,
[Capabilities and Versioning](Capabilities-and-Versioning.md) for how the lanes are agreed.
