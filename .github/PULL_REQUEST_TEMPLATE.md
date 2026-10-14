<!--
Read CONTRIBUTING.md first. Keep one logical change per commit and say *why* in the message.
Delete this comment block before submitting.
-->

## What does this change?

<!-- One or two sentences on the problem, then what you did about it. -->

## How did you verify it?

<!--
Be specific about topology: which device/emulator was the server, whether the peer was a real
browser, a second phone, or the host acting as a peer via `adb forward`.
If a protocol path is involved, state that you checked BOTH ends.
-->

- [ ] `./gradlew test` passes
- [ ] `./gradlew lintRelease` passes (no new errors)
- [ ] `./gradlew assembleRelease` produces an APK
- [ ] Exercised on a device or emulator: <!-- which screens / flows -->

## Wire and data impact

<!-- Tick whatever applies; these are the ones a reviewer cannot check from the diff alone. -->

- [ ] Changes a wire format (`appKind`, pairing handshake, upload proof, call signalling, media
      frames)
- [ ] Needs a Room migration or changes the database schema
- [ ] Changes on-disk storage layout or preference keys (`localdrop_*`)
- [ ] Changes the security model (PIN handling, E2E key derivation, token issuance)
- [ ] None of the above

## Screenshots / recording

<!-- Required for anything user-facing. A short clip beats a paragraph. -->

## Notes for the reviewer

<!-- Known gaps, things you are unsure about, follow-ups you deliberately left out. -->

---

By submitting this pull request I confirm I have the right to contribute this code under the
project's licence, and I license my contributions under the PolyForm Noncommercial License
1.0.0.

- [ ] I have read CONTRIBUTING.md and SECURITY.md, and I agree to the above.
