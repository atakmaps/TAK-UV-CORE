# UV-PRO developer guide

Updated: 2026-10-10

This is how the plugin is built and how data moves through it. Binding rules are in [`CONSTITUTION.md`](../CONSTITUTION.md). Pull-request mechanics are in [`CONTRIBUTING.md`](../CONTRIBUTING.md). AI assistants start at [`AGENTS.md`](../AGENTS.md).

Older files under `docs/` (`UV-PRO-v1.9.51-AGENT-HANDOFF.md`, `agent_meshcore_handoff_v54.md`, `UV-PRO-INTEGRATION-TEST-PLAN.md`) are session notes. They describe past ATAK versions and local paths. When they disagree with this guide or the code, follow this guide and the code.

The README feature list is for users. It lags the code. The version string in root `build.gradle` (`ext.PLUGIN_VERSION`) is the version that builds.

## What this plugin is

UV-PRO is an ATAK plugin (`com.uvpro.plugin`) loaded inside the ATAK process. It gives one ATAK map two off-grid radios plus an optional Wi‑Fi/TAK bridge:

| Path | Hardware | Android link | Code |
|---|---|---|---|
| UV-PRO | BTECH UV-PRO (and the same KISS/AX.25 family the plugin already speaks) | Bluetooth Classic SPP | `BtConnectionManager` |
| MeshCore | MeshCore BLE companion | GATT Nordic UART | `MeshBtConnectionManager` |
| Wi‑Fi / TAK | ATAK's own network | not a Bluetooth socket | SA relay and uplink in `CotBridge` / `ChatBridge` |

Target ATAK is **CIV 5.6.0**. Product flavors `civ` and `mil` exist. Day-to-day development builds `civDebug`.

There is a separate repository, `atakmaps/TAK-MESHCORE`, for a MeshCore-only plugin. It still works and stays published until the maintainer freezes it. MeshCore bugfixes for UV-PRO are made here, not copied across, unless the maintainer asks.

## Startup

`app/src/main/assets/plugin.xml` registers one extension:

- interface `gov.tak.api.plugin.IPlugin`
- implementation `com.uvpro.plugin.UVProLifecycle`

`UVProLifecycle` constructs `UVProTool` and `UVProMapComponent`, then calls `UVProMapComponent.applyUpdateServerTrustEarly` before the rest of ATAK's plugin-repo HTTPS runs. That early call is required. Moving it later produces an empty CA set for `atakmaps.com` and a failed package sync, with no compile error.

`UVProMapComponent` owns the long-lived objects:

- `CotBridge`, `ChatBridge`, `ContactTracker`
- one `PacketRouter` shared by both radios
- `BtConnectionManager` for the UV-PRO
- `MeshBtConnectionManager` for MeshCore
- a coexistence listener so Classic Bluetooth and BLE do not sit on a dead link after they contend for the phone radio

`UVProRadioServices` holds the UV-PRO manager for settings actions (net-slot distribute, encryption sync). `UVProMeshServices` holds the MeshCore manager.

## Boot order

Mesh auto-connect waits so both radios are not opening sockets at the same instant. `UVProMapComponent.scheduleMeshBootAutoConnect`:

- If the UV-PRO connects, mesh auto-connect starts **2 seconds** later (`MESH_BOOT_AFTER_RADIO_MS`).
- If the UV-PRO has not connected, mesh auto-connect starts after **7 seconds** (`MESH_BOOT_FALLBACK_MS`), and keeps waiting while a UV-PRO connect attempt is still in flight.

## Where a change goes

| You are changing | Look here |
|---|---|
| Plugin load, update-server trust, wiring | `UVProLifecycle`, `UVProMapComponent` |
| UV-PRO Bluetooth / KISS | `bluetooth/BtConnectionManager`, `kiss/` |
| MeshCore BLE, rooms, node cache, mesh contacts | `bluetooth/MeshBtConnectionManager` and the `bluetooth/Mesh*` stores |
| Which radio transmits | `bluetooth/TransmitTransportResolver`, toggles in `UVProDropDownReceiver` |
| Inbound AX.25 dispatch, packet types | `protocol/PacketRouter`, `protocol/UVProPacket` |
| Positions, markers, SA relay, beacons on the map | `cot/CotBridge`, `cot/CotBuilder` |
| GeoChat, delivery receipts, retries | `chat/ChatBridge` |
| Contact radial menu and connectors | `contacts/` |
| APRS and AX.25 frames | `aprs/`, `ax25/` |
| Smart beacon and mesh rate floors | `beacon/SmartBeacon`, `beacon/MeshBeaconLimits` |
| Radio or mesh position as an ATAK GPS source | `location/` |
| Tool Preferences and the plugin panel | `ui/SettingsFragment`, `res/xml/preferences.xml`, `res/layout/uvpro_dropdown.xml`, `UVProDropDownReceiver` |
| Packet terminal | `terminal/` |
| Shared-secret encryption | `crypto/EncryptionManager` |
| Version and variant names | root `build.gradle` (`ext.PLUGIN_VERSION`), `app/build.gradle` |

`ChatBridge` and `CotBridge` are large because they sit on ATAK's contact, chat, and comms APIs. Prefer adding a named helper next to the behavior you are changing over growing an unrelated method in those classes.

## How a packet moves

Both radios share one `PacketRouter`.

UV-PRO path:

1. `BtConnectionManager` reads a Bluetooth SPP socket.
2. KISS frames are decoded to AX.25 bytes.
3. `PacketRouter.routeIncoming(bytes)` tags the frame as `RfInboundTransport.UVPRO`.

MeshCore path:

1. `MeshBtConnectionManager` speaks the MeshCore BLE protocol (Nordic UART service).
2. Traffic that is carried as AX.25 is passed to `PacketRouter.routeIncoming(bytes, pathLen)`, tagged `RfInboundTransport.MESHCORE`. `pathLen` is the hop count shown on the CoT.
3. A native MeshCore direct message (`CMD_SEND_TXT_MSG`) does not go through AX.25. It calls `PacketRouter.routeNativeMeshDm`, which injects the text with `ChatBridge.injectInboundMeshDm`.

`PacketRouter` then splits AX.25 into APRS (standard position, message, telemetry) or a UV-PRO custom packet. Custom types in `UVProPacket`:

| Type | Value | Role |
|---|---|---|
| `TYPE_COT` | `0x01` | Map item / situation awareness |
| `TYPE_CHAT` | `0x02` | GeoChat |
| `TYPE_GPS` | `0x03` | Position |
| `TYPE_PING` | `0x04` | Discovery or directed ping |
| `TYPE_ACK` | `0x05` | Chat delivery / read |
| `TYPE_COT_FRAGMENT` | `0x06` | Large CoT, reassembled by `PacketFragmenter` |
| `TYPE_NET_SLOT_CONFIG` | `0x07` | Admin slot-count assignment |
| `TYPE_COT_ACK` | `0x08` | CoT delivery ack |

`RfInboundTransport` is `UVPRO`, `MESHCORE`, or `WIFI`. Ping replies prefer the link that received the ping, and use the other radio only when that link is down.

Outbound transmit uses `TransmitTransportResolver`. It looks at the MeshCore and UV-PRO toggles, returns the preferred `BtConnectionManager` when that link is connected, and otherwise the other link if that one is connected. The Wi‑Fi toggle is not part of this class. Panel failover (`TRANSMIT_FAILOVER_DELAY_MS`, five minutes, in `UVProDropDownReceiver`) can move the visible toggle after the preferred radio has been down while the other is up. A manual toggle change updates the preference and cancels that failover.

When MeshCore transmit is on and mesh beacon limiting is not disabled, `MeshBeaconLimits` raises the mesh send rate at transmit time (interval and slow rate at least 1800 seconds, fast rate and turn time at least 300 seconds). Stored preferences are not rewritten. UV-PRO-only beacons ignore those floors.

## Update server

On startup the plugin points ATAK at `https://atakmaps.com/plugins/product.infz` and imports a trust bundle so that host verifies.

The chain, in this order, lives in `UVProMapComponent`:

1. `configureUpdateServerStatic`
2. `installUpdateServerTruststoreCompat`
3. `reloadCertificateManagerFromDatabase`
4. `registerUpdateServerCA`
5. a deferred sync

`UVProLifecycle` must still call `applyUpdateServerTrustEarly`. The import uses reflection on ATAK (`AtakCertificateDatabase`, `AtakCertificateDatabaseBase`, `CertificateManager`). Preference keys such as `atakUpdateServerUrl`, `appMgmtUpdateServerUrl`, and `updateServerCaLocation` are ATAK's names. Renaming them breaks an already-installed ATAK.

Assets that must ship in the APK:

- `app/src/main/assets/atakmaps-ca.p12` — PKCS#12 trust bundle (certificate entry), built with `keytool -importcert`
- `app/src/main/assets/isrg-root-x1.pem` — public ISRG root
- `uvpro_trust_bundle_p12_key` in `app/src/main/res/values/strings.xml` — Base64 store unlock that matches the p12

R8 uses `app/proguard-gradle.txt` with `-applymapping` and keeps `com.uvpro.plugin.**`. Shrinking those reflection entry points breaks trust setup in release builds only.

After a change in this area, `./gradlew assembleCivRelease`, install on a cleared ATAK-CIV, and confirm logcat shows `getCACerts` for `atakmaps.com` and a green sync in TAK Package Management.

Publishing a signed APK to that server, tagging a GitHub release, and regenerating the catalog are maintainer release steps. A feature pull request does not do them.

## Build

Prerequisites:

- JDK 17
- Android SDK, API 35, build-tools 35
- ATAK-CIV 5.6.0 SDK, on disk, not in git

SDK files used locally:

| File | Where it goes |
|---|---|
| `main.jar` | `app/libs/atak-civ/` |
| `android_keystore` | `app/libs/atak-civ/` |
| `atak-gradle-takdev.jar` | already vendored at `gradle/takdev/atak-gradle-takdev.jar` for TAK portal builds that have no Artifactory init script |
| `proguard-release-keep.txt` | `app/libs/atak-civ/` when the SDK ships it |

Obtain the SDK from the maintainer or through TAK. Do not add a download of the SDK to the repo. The demo keystore password shipped with the ATAK SDK (`tnttnt` / alias `wintec_mapping`) is the public SDK debug credential. Release builds that go to users are the TAK-portal-signed APK, not this keystore.

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17

# Copy main.jar and android_keystore from a local 5.6.0 SDK tree.
# Default search path is ~/Documents/ATAK/Versions/ATAK-CIV-5.6.0.18-SDK
ATAK_560_SDK=/path/to/ATAK-CIV-5.6.0.18-SDK ./tools/use-atak-sdk.sh 5.6.0
```

`local.properties` (gitignored) needs `sdk.dir`. `gradle.properties` is committed with shared Gradle flags. A machine-specific `org.gradle.java.home` may be added locally and should not be committed if it is only your path.

```bash
# Day-to-day: civDebug only
./gradlew assembleCivDebug -PlocalDev=true

# civDebug without disabling other variants
./gradlew assembleCivDebug

# Release variant used before a TAK submission
./gradlew assembleCivRelease -Patak.version=5.6.0
```

APK output: `app/build/outputs/apk/civ/debug/ATAK-Plugin-UVPro-*.apk`

`versionCode` is derived from `ext.PLUGIN_VERSION` in `app/build.gradle`. Change the version only in root `build.gradle`.

There are no unit tests. `./gradlew lintCivDebug` and a device install are the checks. The plugin runs inside ATAK (`com.atakmap.app.civ` for CIV). Attach a debugger to that process.

```bash
adb install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-UVPro-*.apk
```

Open ATAK → Menu → Tools → UV-PRO.

Useful log tags: `UVPro`, `UVPro.Router`, `UVPro.BT`, `UVPro.MeshBLE`, `UVPro.ChatBridge`, `UVPro.CotBridge`.

## TAK submission zip

Maintainer release only.

```bash
./tools/use-atak-sdk.sh 5.6.0
./tools/build-submission-zips.sh
```

`git archive` includes committed files only. The zip is expected to contain `gradle/takdev/atak-gradle-takdev.jar`. The APK copied beside a local zip is unsigned. The APK users install from the update server is the one returned signed by the TAK portal.

## UI conventions

Tool Preferences keep the styling already in the panel: yellow centered category headers, blue left-aligned sub-headers, white titles, green value summaries. Do not call `setLayoutResource` on ATAK preferences; the host inflates rows and a plugin layout id crashes. Style rows on bind. The toolbar icon is `ic_uvpro_toolbar` via `UVProTool.toolbarIcon`. The full-color `ic_uvpro` is for in-panel and contact icons, where ATAK tint is not applied the same way.
