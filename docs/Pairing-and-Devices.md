# Pairing and Devices

Pairing is what turns "a machine on the LAN that happens to run PulseDrop" into "a device
whose chat I can encrypt to". Everything about it happens on the **Devices** screen.

## The Devices screen

- **Rescan** re-runs the subnet scan; **Scan QR** opens the camera to read an address.
- A search box matches name, address or kind, and filters narrow to
  **All / Nearby / Trusted / Connected / Pending**, sortable by connection state, name or
  last seen.
- Nearby, unpaired rows offer **Send** or **Pair**. A pending invite offers **Cancel invite**.
- Trusted rows offer **Verify / Code**, **Details**, **View transfers** and **Revoke trust**.
- **Connect by address** takes a literal `192.168.1.55:8080` for a host discovery missed —
  a guest network with client isolation, or a device on another interface.
- Tapping a device opens a sheet with its capabilities, the version it reported, and copyable
  diagnostics.

## Starting a pair

Tap **Pair** on a device row (or "Pair with this device" in its sheet). The app POSTs
`/api/pair/request` carrying its name, its public key, its version, and the address it can be
reached on. The row goes to **Invite pending** until the other side answers.

Two things this is **not**:

- **The QR code is not a pairing code.** It encodes the server address, for connecting and
  sending. The scanner prompt says so: "Scan the address QR code shown on the other device".
- **There is no code entry.** You never type anything to pair. The code that does appear is
  compared by eye, not entered.

## The safety code

Both devices derive the same 6-digit code from the agreed key and show it. It is display-only.

The request dialog reads:

> *<name> wants to pair with you.*
> From 192.168.1.55:8080
> **Safety code** — `481920`
> Say this code out loud to the other person. If it matches on both screens, you are paired
> with each other — not someone else.

Accept with **Accept & pair** or refuse with **Decline**. Afterwards, the device's **Verify**
action re-shows the code chunked as `481 920` with "Codes match" / "Mark unverified", so trust
can be confirmed later rather than only at the moment of pairing.

This is number-matching, the same idea as a Signal safety number: the crypto proves both sides
hold the same key, and reading it aloud proves the other end is the person holding the phone
rather than something in the middle.

Outcomes, verbatim:

| Event | Message |
| --- | --- |
| Accepted | `Paired with <name> — end-to-end encrypted` |
| Accepted on a device that had an older key for that id | `Paired with <name>, but this is a new key. Check the safety code matches the old one before trusting it.` |
| Declined by the receiver | `Declined pairing from <name>` (on the receiver), `<name> declined pairing` (on the requester) |
| Request never arrived | `Pair request did not reach <name>` |
| No answer within 60 s | `No pairing answer from <name> yet — try again later` |

A late answer is still accepted for up to ten minutes, because the other person may have
stepped away.

The "new key" warning is the one to take seriously: it means the device id is the same but the
identity key is not, which is either a reinstall or an interception. Re-verify the code before
trusting it.

## Pairing when one side runs no server

A phone that keeps its server off cannot be reached, so it marks its request as relayed and
the answer comes back through the hub it *can* reach, via `/api/relay/pair-poll`. The
handshake is authorised by the request id alone at that stage, because no shared key exists
yet — which is exactly why the safety code is the real check rather than a formality.

In practice the app starts its own server on launch, so this path is the exception.

## What a pair actually agrees

Each device holds an **EC P-256** identity in `AndroidKeyStore`. Raw key preferences are the store
only on a device that has no keystore at all — a keystore that is present and then fails to give up
the key is treated as a bad moment, not as a missing feature, so the phone keeps presenting the
identity it already has (or, if it had none, a key it never writes to disk) instead of minting a new
one and re-rooting every pair. Pairing runs ECDH; both sides then derive the same AES-256-GCM key
from the shared point. The secret is never stored — it is re-derived at runtime, which is why a
pairing survives an app update but not a key change.

**Which derivation is used is decided once, by the device accepting the request**, and then
frozen for the life of that pair:

| Both ends report | New pair uses |
| --- | --- |
| 2.0.0 or newer | **KDF v2** — HKDF-SHA256 with domain separation |
| Either end older than 2.0 | **KDF v1** — the original concat-hash formula |

Existing pairs keep whatever they were created with, forever, even after both phones upgrade.
That is deliberate: the key is re-derived from the ECDH point, so changing the recipe under a
live pairing would make every stored message unreadable. The derivation is per-pair, not
per-device, and a device's list of pairs can legitimately mix both.

The formulas and the reasoning are in [Security Model](Security-Model.md).

## Revoking trust

**Revoke trust** on a device, confirm "Revoke trust for <name>?", and the pair is deleted —
`Unpaired <name>`. Private threads with that device stop working immediately, since the key
that decrypted them is gone. History already stored on each phone stays where it is; see
[Chat and History](Chat-and-History.md) for what is on disk and why removing a pair does not
erase a peer's copy.

## What pairing is not

Pairing authenticates a **device**, and encrypts **chat** between two paired devices. It does
not encrypt calls, and it does not gate the open LAN room — any browser that can reach the
address can join the room unless a PIN is set in Settings. If you need strangers off the
address, use the PIN; if you need content private, pair first and use a private thread.
