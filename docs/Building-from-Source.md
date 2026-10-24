# Building from Source

## Requirements

- JDK 17+ (the project builds on 21/25)
- Android SDK with platform **36** and a recent build-tools (36.x)
- No Android Studio required — Gradle CLI is enough

The wrapper carries both the Gradle version and the distribution's SHA-256, so always
invoke `./gradlew` rather than a system `gradle`; the checksum is what makes the
downloaded distribution verifiable.

## Clone and build

```bash
git clone https://github.com/bishnuneup4ne/pulsedrop.git
cd pulsedrop

# point at your SDK if ANDROID_HOME is unset
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew :app:assembleDebug        # debug APK
./gradlew :app:assembleRelease      # release APK (signed only if you provide a key)
./gradlew :app:bundleRelease        # release AAB
./gradlew test                      # unit tests
./gradlew lintRelease               # lint
```

Debug builds are signed with AGP's automatic debug key. **Release builds come out
unsigned unless you supply a keystore** — CI never does.

## Before you change the database

`AppDatabase` is `exportSchema = true` and the JSON lands in `app/schemas/`. A Room
**migration is written by hand and is checked against that JSON**: the DDL in
`MIGRATION_3_4` is copied from the schema file on purpose, because a migration that
disagrees with the schema Room expects fails at runtime on real installs and not in a JVM
test. There is deliberately no `fallbackToDestructiveMigration` — a missed migration throws
on open rather than wiping somebody's chat history. If you add an entity, add the migration
and commit the new schema JSON in the same change.

## Release signing

`assembleRelease` reads, in this order, environment variables and then `signing.properties`
in the project root:

```properties
KEYSTORE_PATH=my-release.jks
STORE_PASSWORD=...
KEY_ALIAS=upload
KEY_PASSWORD=...
```

Relative keystore paths resolve against the project root. Keystores and
`signing.properties` are git-ignored — never commit them. If no keystore is present the
release is produced unsigned instead of failing.

## Generating your own signing key

```bash
keytool -genkeypair -v -keystore my-release.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

An APK signed with your key cannot update installs made with anyone else's key (and vice
versa) — uninstall between them.

## Stack

| Layer | Choice |
| --- | --- |
| UI | Jetpack Compose + Material 3, bundled Google Sans |
| Server | NanoHTTPD (HTTP + TLS side-listener) |
| Storage | Room (transfer log, chat with an FTS4 index), plain files for received data |
| Crypto | AndroidKeyStore + BouncyCastle (self-signed LAN TLS certs), HKDF-SHA256 for new pairs |
| Calls | custom TCP media for app-to-app, HTTP frame legs for browsers, conference bridge |
| Media encode/decode | MediaCodec — surface-input H.264 for a 1:1 screen share, byte-buffer H.264 for camera, JPEG everywhere else |
| QR | ZXing |

## What the tests do and do not cover

`./gradlew test` is a JVM run — 332 tests across 41 classes, no emulator, no device. It
covers the wire format against hand-written byte goldens, the Annex-B assembler, the quality
ladder, capability negotiation, the pairing KDF against pinned vectors, the Room chat store
including the 3 → 4 migration, and the Compose screens through Robolectric.

What it structurally cannot cover is **encoder behaviour on a real chip**. Which hardware
codecs exist, whether `createInputSurface()` succeeds at a given geometry, and what frame
rate a device actually sustains are all invisible to a JVM test. That is why a release can be
green on CI and still be unverified on a handset — see the note in the release notes.

## CI

GitHub Actions (`.github/workflows/ci.yml`) runs on JDK 21: unit tests, `lintRelease` and an
unsigned release build on every push to `main` and every PR. A second workflow
(`virustotal-scan.yml`) fires when a release is published, downloads whatever APK the release
carries, scans it and posts the permanent report link as an issue.

## Documentation lives here too

`docs/` is the source of the GitHub wiki: same filenames, same page titles. Change a page
here, then copy it into the wiki clone
(`https://github.com/bishnuneup4ne/pulsedrop.wiki.git`) so the two do not drift. The wiki is
what readers see; this folder is what reviewers can diff in a pull request.
