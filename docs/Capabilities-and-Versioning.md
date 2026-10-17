# Capabilities and Versioning

PulseDrop runs in mixed-version rooms: a 2.0 phone, a 1.6 phone and a browser tab in the same
call. The rule that makes that survivable is that **a capability is a promise about hardware,
not about which build is installed** — and that a sender only uses a capability when both ends
have it.

## The capability strings

Only three are ever announced by a real device:

| Cap | Meaning | Announced when |
| --- | --- | --- |
| `h264` | This end can decode camera H.264 (frame type 9) | a **hardware** AVC encoder is present |
| `screen-h264` | This end can watch an encoded shared screen (type 11) | a **hardware** AVC **decoder** is present |
| `chat-ext1` | Chat JSON may carry `reaction`, `readUpTo`, `replyTo`, `voiceDur` | always |

`h264` and `screen-h264` are independent promises about independent hardware, so a phone may
claim either, both or neither. A viewer that can decode but not encode must not be asked to
encode; a sharer must not encode a screen for a peer that cannot show it. That is why
`screen-h264` is answered off the **decoder** — a phone that can encode a share it cannot
display would simply be unable to watch anybody else's.

Software codecs do not count. A name matching `OMX.google…` or `c2.android…` is excluded, so
those caps mean "a dedicated block exists", not "a codec with that MIME exists". The reason a
*hardware encoder* gates the `h264` decode cap is honesty in the other direction: a phone that
advertises `h264` but cannot encode makes its peer drop the JPEG lane too, and then both ends
try to encode in software at once.

`chat-ext1` is the exception that proves the rule — it is not hardware at all. Reactions,
edits and read receipts are a wire format a build either speaks or does not, so it is
unconditional, and it is the only capability a device with no codecs still answers to.

### Declared but not announced

Three strings exist as constants and are **never** sent by any build today:

| String | Would mean | Status |
| --- | --- | --- |
| `binary-uplink` | `/api/webcall/up` accepts raw binary batches | not implemented in the web client |
| `kdf-v2` | New pairings may use HKDF | **not used for negotiation** — see below |
| `conf2` | Conference H.264 fan-out (type 10) | deliberately absent: the grid draws bitmaps and has one surface per tile |

The important one is `kdf-v2`. Key derivation is **not** negotiated by capability — it is
negotiated by version string, by the acceptor, once per pair. Reading the constant as a live
signal would be wrong. See [Pairing and Devices](Pairing-and-Devices.md).

## Where capabilities come from

A peer's capability set is merged from four sources:

1. The `"caps"` array in `/api/info`.
2. The discovery UDP beacon.
3. The `appVersion` exchanged during the pairing handshake.
4. A `caps` control frame (type 8) sent in band, by the side that opens the media socket.

Number 4 exists because the beacon and the probe are not guaranteed to have run for this peer
— without it, a phone that announced late would be refused a lane it could always carry.

Merging is **additive only**: an announcement can add a capability and can never remove one. A
lost beacon therefore cannot downgrade a peer mid-call, at the cost that a device which loses
a capability (an encoder failing over) is not re-learned as incapable until the next call.

Capabilities are indexed under both `ip/<address>` and `pid/<pairingId>`, and a check looks at
both. A device discovered by IP and a device trusted by pairing id are the same hardware;
requiring both keys to agree would refuse a lane to a phone that had simply been seen in only
one of the two ways.

## Version comparison

Versions are read as `major.minor.…`. **An unknown version is treated as the oldest possible**,
never as "new enough" — a peer that never reported a version gets no capability-based lane and
is named as such in the UI ("it has not reported a version").

A peer is recognised as a pair-capable PulseDrop phone rather than a plain web page when its
version string starts with `"2"`. That single check is load-bearing for discovery, which is why
the version in `/api/info` and the version in the build must never disagree — in 1.7 they did,
and the consequence was that no install could negotiate KDF v2. Both now read
`BuildConfig.VERSION_NAME`.

## Feature floors

| Feature | Needs | How it is gated |
| --- | --- | --- |
| Replies, retractions, relay mail | 1.6 on the peer | version comparison |
| Reactions, edits, read receipts | `chat-ext1` on both ends | capability |
| Encoded camera (type 9) | `h264` on both ends | capability |
| Encoded screen share (type 11) | `screen-h264` on both ends | capability |

When a feature is beyond a peer, the app names the peer and the version rather than failing
silently: *"Retractions needs PulseDrop 1.6 or newer on <name> — it is running 1.4"*.

## The rules that keep mixed versions working

1. A sender uses a capability only when **both ends** advertised it for that connection.
2. A peer advertising `h264` must still accept frame types 1–7. JPEG is the fallback, not a
   legacy mode — an encoder failure drops to it with a keyframe-request, not a reconnect.
3. Unknown media types and unknown JSON keys are skipped, never fatal. An unknown frame is
   consumed by its length field and dropped, which is how a 1.x phone can sit in a call with a
   2.0 phone sending type 11 and simply keep showing the JPEG share.

New semantics therefore get **new type numbers**, never new meanings on old ones. See
[Protocol v2](Protocol-V2.md).
