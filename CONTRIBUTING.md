# Contributing to PulseDrop

Thanks for taking the time. This document covers what a good contribution looks like here.
Everything is non-negotiable only where CI enforces it; the rest is guidance.

## Before you start

- Pick up an existing issue, or open one before writing code. Pairing, calls and screen share
  are protocol-level features where a "fix" often changes behaviour on the other side of the
  wire, and it is cheaper to agree the shape up front than to close a PR afterwards.
- Read the *Architecture* and *Security model* sections of [README.md](README.md). They describe
  invariants that are not obvious from the code.
- Check [SECURITY.md](SECURITY.md) first if your finding is a vulnerability. Do not open a
  public issue for it.

## Getting set up

```bash
git clone https://github.com/pulsedrop/pulsedrop.git   # or your fork
cd pulsedrop
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # git-ignored
./gradlew assembleDebug test
```

You need JDK 21+ and an Android SDK providing `compileSdk` 37. The Gradle wrapper fetches
Gradle 9.6.0 itself. A release build needs no keystore — it comes out unsigned.

## The two commands that must pass

```bash
./gradlew test          # unit tests (Robolectric + plain JUnit)
./gradlew lintRelease   # release lint; fails on new errors
```

Run these before pushing; CI runs exactly them plus `assembleRelease`.

## Making the change

- Branch from `main`: `feat/…`, `fix/…`, `docs/…`.
- Keep one logical change per commit. Write the message for the reader who has to `git blame`
  it in a year — say *why*, not *what* (`fix: stop PIN lockout counting a stale token per poll`
  beats `update auth`).
- Match the existing style: 2-space indentation in Kotlin, explicit imports (there are no
  wildcard imports in the tree), Compose `@Composable` functions in `PascalCase`.
  `kotlin.code.style=official` is set in `gradle.properties`; do not reformat files you are not
  otherwise changing, since a whitespace-only diff buries the real edit.
- Add a test with any behaviour change to `security/`, `transfer/` or `server/` — those are the
  packages where a regression is silent. UI-only changes do not need a test, but do say which
  device or emulator configuration you checked them on.

## Verifying a protocol change

Changing pairing, the upload proof, call signalling or the media frame format affects both ends.
Prove it against a second implementation rather than against yourself:

1. Run the app on an emulator or device and start the server.
2. Use `adb forward tcp:8080 tcp:8080` and drive the server from the emulator host at
   `http://127.0.0.1:8080`, or point a browser at the URL the app prints.
3. Exercise the flow end to end — pair, send a file, start a call, share the screen. Check the
   user-visible result (received file, safety codes matching on both sides, frames arriving),
   not just a 2xx response. PulseDrop does not log to logcat, so observe outcomes on screen or
   through the `/api/*` responses.

Note that two emulators on one host cannot pair with each other: each sits behind its own SLIRP
NAT, reports the same internal IP, and has no route to the other. Use the host as the peer.

## Wire compatibility

`appKind` (`"LocalDrop"`), the `LocalDrop-v1` ECDH salt, the `localdrop_e2e_identity` keystore
alias, the `localdrop_*` preference files, the `pulsedrop-upload-v1` proof version and the
`WebDrop_Received` / `WebDrop_Chat` storage directories identify the protocol and on-device
storage, not the product name. Renaming any of them strands existing installs, so PRs that
"clean up" those strings are rejected on principle. If a protocol bump is genuinely needed, ship
it as a versioned change that still accepts the old value.

## Pull requests

- Fill in the template. Describe what a reviewer should *see*, and paste screenshots or a short
  recording for anything user-facing.
- Say explicitly whether the change affects the wire format, needs a migration, or touches
  pairing/crypto.
- Keep diffs reviewable — under ~400 changed lines is fine, a large PR needs to be split or
  strongly justified.
- Respond to review rather than force-pushing over it. History is rebased on merge, so local
  commits do not need to be tidy.

By submitting a pull request you confirm that you have the right to contribute the code under
the project's licence, and you agree that it is licensed under the PolyForm Noncommercial
License 1.0.0.
