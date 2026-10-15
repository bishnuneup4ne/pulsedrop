# Building from Source

## Requirements

- JDK 17+ (the project builds on 21/25)
- Android SDK with platform **36** and a recent build-tools (36.x)
- No Android Studio required — Gradle CLI is enough

## Clone and build

```bash
git clone https://github.com/bishnuneup4ne/pulsedrop.git
cd pulsedrop

# point at your SDK if ANDROID_HOME is unset
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew :app:assembleDebug        # debug APK
./gradlew :app:assembleRelease      # release APK (signed only if you provide a key)
./gradlew test                      # unit tests
./gradlew lintRelease               # lint
```

Debug builds are signed with AGP's automatic debug key. **Release builds come out
unsigned unless you supply a keystore** — CI never does.

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
| Storage | Room (history, chat), plain files for received data |
| Crypto | AndroidKeyStore + BouncyCastle (self-signed LAN TLS certs) |
| Calls | custom TCP media for app-to-app, HTTP frame legs for browsers, conference bridge |
| QR | ZXing |

## CI

GitHub Actions (`.github/workflows/ci.yml`) runs unit tests, lint and an unsigned release
build on every push and PR. A second workflow scans published release APKs with VirusTotal
and posts the permanent report link as an issue.
