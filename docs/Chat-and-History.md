# Chat and History

Before 2.0, chat lived in RAM: a restart lost the conversation, and "history" meant whatever
was still on screen. That is no longer true on the phone, and the difference matters enough
to be documented separately — including the part that did **not** change, which is the
browser.

## What is stored

Chat lives in the app's Room database, `webdrop_database` (schema version 4), alongside the
file-transfer log. Three tables carry it:

| Table | Holds |
| --- | --- |
| `chat_message` | One row per message: id, thread, sender, text, timestamp, attachment metadata, reply quote, tombstone flag, reaction, edit time, read time |
| `chat_message_fts` | A standalone SQLite **FTS4** index of `message_id`, `sender`, `body` — the thing that makes search cover history rather than the visible window |
| `conversation` | One row per thread: display name, last message preview and time, `unreadCount`, and a `lastReadAt` watermark |

Message ids are the sender's own, so the same message arriving down two paths (directly and
via a hub relay, say) lands as one row rather than two — the insert is a `REPLACE`, and the
write is a transaction that merges any reaction, read time or edit already recorded onto it.

Threads are keyed by a `threadId` that is `null` for the shared LAN room and otherwise
`pair:<id>`, `group:<id>` or `ip:<address>`.

## What survives, and what does not

| | Across a restart | Notes |
| --- | --- | --- |
| Message text | **Yes** | Also indexed for search |
| Reactions | **Yes** | One reaction per message, not a per-user list |
| Edits | **Yes** | Text is rewritten in place and `editedAt` is stamped; the index is rebuilt |
| Reply quotes | **Yes** | Stored as a snapshot of the quoted text, never a live lookup, so editing the original does not rewrite history |
| Read receipts | **Yes** | A per-thread watermark plus a per-message read time on your own sent rows |
| Tombstones | **Yes** | The row stays, its text and reaction are cleared and its index entry is deleted |
| Attachment metadata | **Yes** | Name, size, MIME and id |
| Attachment bytes | **Yes** | On disk under `WebDrop_Chat/`, not in SQLite |
| Typing indicators | No | In memory with a 6-second lifetime, by design |
| **Browser tab chat** | **No** | The room the phone serves to tabs is still an in-memory list, capped at the newest 2000 and lost on restart |

A tombstone outranks a redelivered copy of the same message: if a retracted message is
replayed from a relay after the deletion, it comes back deleted rather than resurrecting.

## Search

Search runs against the FTS index with SQLite's `MATCH` operator, so it is an inverted-index
lookup rather than a scan, and it reaches rows that have already left the on-screen window.
The inbox itself renders the newest 2000 messages across all threads; a single thread pages
200 at a time. Neither number is a retention limit — nothing is deleted from the database to
enforce them, and history grows until you clear it.

Deleting a message for everyone removes it from the index, so retracted text does not remain
findable.

## Is it encrypted?

**On the wire between paired phones: yes.** Private threads are sealed with the pair's
AES-GCM key before they leave, which is what the `encrypted` flag on a message means — that
it travelled end-to-end encrypted.

**At rest: no.** Decrypted text is written to SQLite as plaintext, and the FTS index stores
it verbatim so it can be searched. There is no SQLCipher and no passphrase. Anyone with the
device unlocked, or anyone who pulls the app's data on a rooted device, reads the history.
The `encrypted` column is a transit marker; treating it as "this row is ciphertext" would be
wrong.

Because of that, the chat database is **excluded from Google Drive backup and from
device-to-device transfer** — see [Security Model](Security-Model.md). Restoring a phone does
not bring chat back, and that is the intended trade: message text is private between two
peers, and carrying it into a cloud account the other peer never agreed to would undo the
part of end-to-end encryption that still matters once the message is stored.

## Who can see what

- Paired phones exchange chat over the pair wire and, for a phone with no server, through the
  hub's relay mailbox. The hub cannot read it.
- Browser tabs talk to the phone over the HTTP endpoints in [HTTP API](HTTP-API.md) and see
  only the room and the web-visible threads they are allowed.
- Reactions, edits, read receipts and full-text search are **phone-to-phone only**. They are
  not exposed over HTTP, and a browser tab neither sends nor receives them.

## Version gates

Replies and retractions need PulseDrop **1.6** or newer on the far end. When a peer is
older, the app says so instead of failing quietly —
*"Retractions needs PulseDrop 1.6 or newer on <name> — it is running <version>"*, or
"…it has not reported a version" when the peer never told anyone what it runs.

Reactions, edits and read receipts are gated on the peer announcing the `chat-ext1`
capability, which is a wire-format promise rather than a hardware one: a build either
speaks it or does not, so every 2.0-or-newer phone claims it unconditionally. An older peer
ignores the extra JSON keys by design. See
[Capabilities and Versioning](Capabilities-and-Versioning.md).

## Upgrading from 1.x

Version 3 → 4 creates the chat tables in place; 2 → 3 adds a column to the transfer log.
There is deliberately **no destructive fallback** — if a migration is missing, Room throws
on open instead of quietly wiping the database, so a lost history after an update is a bug
to report, not expected behaviour. Databases older than version 2 predate the migration
chain and are rebuilt.
