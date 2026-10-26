# Contributing to UV-CORE

Updated: 2026-10-25

UV-CORE (`com.uvpro.plugin`) is an ATAK plugin that connects a BTECH UV-PRO radio and a MeshCore companion to the same map. Bug fixes and new features, including layout, are welcome. The maintainer reads the pull request and merges it when they agree.

Binding rules: [`CONSTITUTION.md`](CONSTITUTION.md). How the code is put together: [`docs/DEVELOPER-GUIDE.md`](docs/DEVELOPER-GUIDE.md). AI assistants: [`AGENTS.md`](AGENTS.md).

## Pull requests

`main` is protected. Open a pull request. The maintainer is the reviewer. Do not merge your own pull request and do not force-push `main`.

In the description include:

- what changed and why
- how you built it (`assembleCivDebug` is enough for a normal change)
- which link you tried: UV-PRO, MeshCore, Wi‑Fi/TAK, or none
- a note if you touched version, signing, `atakmaps-ca.p12`, or update-server preferences

A change that removes the UV-PRO link or the MeshCore link has to say that is intentional.

Do not open a matching change against `atakmaps/TAK-MESHCORE`. That repository stays as the MeshCore-only plugin until the maintainer freezes it.

## Build

- JDK 17
- Android SDK API 35
- ATAK-CIV 5.6.0 SDK, obtained from the maintainer or through TAK. It is not in this repository. Do not commit it.

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17
ATAK_560_SDK=/path/to/ATAK-CIV-5.6.0.18-SDK ./tools/use-atak-sdk.sh 5.6.0
./gradlew assembleCivDebug -PlocalDev=true
```

`local.properties` with `sdk.dir` stays on your machine. See the developer guide for flavors, release builds, and where files go.

## What needs extra care

These keep automatic updates working. Leave them alone unless the pull request is about them:

- `UVProLifecycle` calling `UVProMapComponent.applyUpdateServerTrustEarly`
- the trust sequence in `UVProMapComponent`: `configureUpdateServerStatic` → `installUpdateServerTruststoreCompat` → `reloadCertificateManagerFromDatabase` → `registerUpdateServerCA` → deferred sync
- `app/src/main/assets/atakmaps-ca.p12`
- `app/src/main/assets/isrg-root-x1.pem`
- `uvpro_trust_bundle_p12_key` in `app/src/main/res/values/strings.xml`
- `app/proguard-gradle.txt` keeps for `com.uvpro.plugin.**`

A release (version bump in root `build.gradle`, `./tools/build-submission-zips.sh`, tag, update server) is something the maintainer asks for. Do not include it in a feature pull request.

## Code

Java 17, 4-space indent. Log with a tag that starts with `UVPro` (existing tags such as `UVPro.BT` and `UVPro.Router` are the pattern). Shared chat, CoT, and routing behavior goes through `ChatBridge`, `CotBridge`, and `PacketRouter` so both radios stay on the same path.

## Issues

Include Android version and device model, ATAK-CIV version, radio or MeshCore firmware if it matters, steps, and logcat filtered by `UVPro`. Do not paste tokens, certificate passwords, or a full preference dump from a field phone.

## License

MIT plus Commons Clause. See [LICENSE](LICENSE).
