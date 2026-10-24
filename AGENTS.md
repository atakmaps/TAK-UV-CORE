# AGENTS.md

Updated: 2026-10-10

This is the project guide for any AI assistant working on UV-PRO (Claude, Cursor, Copilot, Gemini, or anything else). Tool-specific files (`CLAUDE.md`, `.github/copilot-instructions.md`) only point here.

Read this file, then [`docs/DEVELOPER-GUIDE.md`](docs/DEVELOPER-GUIDE.md) for the area you are changing.

**This file is documentation.** [`CONSTITUTION.md`](CONSTITUTION.md) is policy and outranks this file. [`CONTRIBUTING.md`](CONTRIBUTING.md) is how a pull request is made. If this file conflicts with the constitution, the constitution wins and this file should be fixed.

## What this repository is

UV-PRO is an ATAK plugin, package `com.uvpro.plugin`, target ATAK-CIV **5.6.0**. One plugin, two radios:

- BTECH UV-PRO over Bluetooth SPP / KISS (`BtConnectionManager`)
- MeshCore companion over BLE (`MeshBtConnectionManager`)

Both share one `PacketRouter`, `ChatBridge`, and `CotBridge`. Wi‑Fi/TAK SA relay is a third path. GitHub: `atakmaps/TAK-UV-CORE`.

`atakmaps/TAK-MESHCORE` is the older MeshCore-only plugin. It stays published until the maintainer freezes it. Do not edit that repository unless the maintainer asks. MeshCore fixes for this product go in this tree.

Do not treat `README.md` or the files `docs/UV-PRO-*-HANDOFF.md` and `docs/UV-PRO-INTEGRATION-TEST-PLAN.md` as the architecture. They are a user feature list and old session notes. The developer guide matches the code.

## Before you edit

1. Read the constitution.
2. Find the class in the "where a change goes" table in the developer guide. Chat, CoT, and routing changes go through the shared bridges and `TransmitTransportResolver`, not a UV-PRO-only branch.
3. Leave the update-server trust chain alone unless the task is the trust chain. Order is `configureUpdateServerStatic` → `installUpdateServerTruststoreCompat` → `reloadCertificateManagerFromDatabase` → `registerUpdateServerCA` → deferred sync, and `UVProLifecycle` must call `applyUpdateServerTrustEarly`. Details are in the developer guide.
4. Do not bump `ext.PLUGIN_VERSION`, tag a release, or publish to `atakmaps.com` unless the maintainer asked for a release. When he says **bump version**, get `main` ready for the submission agent: commit outstanding work, bump `ext.PLUGIN_VERSION`, commit it, fast-forward local `main`, and leave `git status` clean. Push that source to GitHub. When `main` is protected, open a pull request into `main` and give him the link to merge. That push is the public code. Do not build the submission zip and do not upload an APK. The submission agent packages that checkout. The signed APK is published to the update server later.
5. Do not commit the ATAK SDK, tokens, phone serials, preference dumps, or logcats. The trust bundle `atakmaps-ca.p12` stays in git.

## Build

JDK 17, Android SDK API 35, ATAK-CIV 5.6.0 SDK on disk (not in the clone).

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17
ATAK_560_SDK=/path/to/ATAK-CIV-5.6.0.18-SDK ./tools/use-atak-sdk.sh 5.6.0
./gradlew assembleCivDebug -PlocalDev=true
```

APK: `app/build/outputs/apk/civ/debug/ATAK-Plugin-UVPro-*.apk`

Install with `adb install -r` and open ATAK → Menu → Tools → UV-PRO. The plugin runs inside `com.atakmap.app.civ`. There are no unit tests. Say which variant you built and which transport you tried.

Device serials differ. Use `adb devices`. Do not write a serial number into the repo.

## Pull requests

Branch from current `main`. `main` is protected. Open a pull request. Do not merge it and do not force-push `main`. The maintainer reviews.

In the pull request say:

- what changed
- UV-PRO, MeshCore, Wi‑Fi, or not run
- whether version, signing, trust assets, or the update server were touched (they should not be, unless that is the task)

Direct sends stay directed. Broadcasts stay broadcasts. A packet that is not for this station is ignored. If you only tested one radio, say so and leave parity for the other radio open.

## UI

Preserve Tool Preferences styling (yellow centered categories, blue sub-headers, white titles, green values). Do not set a custom `setLayoutResource` on ATAK preferences. Toolbar icon stays `ic_uvpro_toolbar`.

## Local Cursor rules

`.cursor/rules/` repeats points from the constitution for Cursor. The constitution wins if a rule is older. In particular, mesh fixes are not ported to `TAK-MESHCORE` unless the maintainer asks.
