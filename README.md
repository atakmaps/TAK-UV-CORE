# UV-PRO — Open-Source BTECH Radio ↔ ATAK Bridge Plugin

A free, open-source ATAK plugin that connects UV-PRO radios to the Android Team Awareness Kit (ATAK) over Bluetooth. Team members with radios can share positions, chat, and situational awareness data entirely off-grid — no cell service or internet required.

- Package: `com.uvpro.plugin`
- Version string: `ext.PLUGIN_VERSION` in root `build.gradle` (the feature notes below lag that)
- Target ATAK: **5.6.0** CIV only

One plugin, two radios: a BTECH UV-PRO and a MeshCore companion. Developers and AI assistants start with [CONSTITUTION.md](CONSTITUTION.md), [docs/DEVELOPER-GUIDE.md](docs/DEVELOPER-GUIDE.md), [AGENTS.md](AGENTS.md), and [CONTRIBUTING.md](CONTRIBUTING.md). The tables below are the user-facing feature list, not the architecture.

## Features

| Feature | Status | Description |
|---------|--------|-------------|
| **Position Sharing (PLI)** | ✅ Working | Your ATAK position is beaconed over radio at a configurable interval. Incoming positions appear as contacts on the map. |
| **Smart Beaconing (APRS-style)** | ✅ Working | APRS-standard SmartBeaconing + corner pegging using live GPS Doppler speed (not position delta). Speed-proportional rate between low/high thresholds, turn detection at any speed > 0, and fixed-interval safety floor. Seven parameters in Settings → Manage Smart Beacon Settings. |
| **Dynamic CoT Stale Window** | ✅ Working | Contact `stale` timestamp now tracks current beacon policy (fixed interval or Smart Beacon profile) so receivers do not grey contacts prematurely. |
| **Ping / Ping Reply** | ✅ Working | **Send Ping** (dropdown) broadcasts a discovery ping to all stations. **Per-contact Ping** (Connectors page or radial **Contact** submenu) sends a directed `TYPE_PING` to one callsign; only that peer should reply. **Send Ping Reply** (Settings) auto-replies on the **radio that received the ping** (fallback to the other link only if that radio is disconnected—not transmit-toggle prefs). Replies use **slotted timing** (default 20 slots × 2.5 s) keyed by callsign hash. |
| **Net slot administration** | ✅ Working | Team leadership can set slot count/time and **Distribute to net** (`TYPE_NET_SLOT_CONFIG`); receivers auto-apply newer assignments. In **Settings → Tool Preferences → UV-PRO Settings → Administration** or **Plugin Settings** (bottom of dialog). |
| **Bluetooth Scan & Connect** | ✅ Working | Instant picker showing previously-connected radios with live green/gray availability dots. **UV-PRO auto-connects first at boot**; MeshCore follows once the radio link resolves. |
| **Radio Connection Status Overlay** | ✅ Working | Persistent BTECH icon in the lower-right map corner (green = connected, desaturated = disconnected). **Tap the icon** to open the UV-PRO panel (same as Menu → Tools → UV-PRO). |
| **GeoChat over RF (contact-centric)** | ✅ Working | Chat to radio peers using ATAK's native Contacts/GeoChat UI (plugin contacts route via RF transport). |
| **Contact groups over RF** | ✅ Working | Group create/add ([UPDATED CONTACTS]) relays **full GeoChat CoT** with `hierarchy` (same as Wi‑Fi/TAK), not compact chat-only. Slotted TX uses default ping-reply slots (20 × 2.5 s). |
| **GeoChat delivery receipts (checkmarks)** | ✅ Working | ATAK's native single-checkmark (delivered) and double-checkmark (read) appear on the sender's chat window. |
| **Retry on no ACK + delivery failure alert** | ✅ Working | If no delivered ACK within the configured interval, message is retransmitted up to max retries. If all retries exhausted, a persistent alert appears. Retry interval and max retries adjustable in Settings. |
| **Contact-targeted CoT over RF** | ✅ Working | Any CoT item sendable to a contact in ATAK — waypoints, routes, casevac/9-line, drawings, markers — is intercepted, compressed, and relayed over RF. |
| **SA Relay (opt-in)** | ✅ Working | Network-to-radio bridge: broadcasts received SA over RF to radio-only users. Configurable in Settings. Per-contact RF relay capped at **once every 10 minutes**; unchanged stationary contacts are still suppressed (~1 m). Relay CoT skips the automatic +3 s double-send. **RF → TAK uplink** (WiFi) uses its own 60 s keepalive and is not slowed. |
| **AES-256 Encryption** | ✅ Working | Optional shared-secret AES-256-GCM for all radio traffic. All nodes must use the same secret. |
| **Contact Tracking** | ✅ Working | Radios in range tracked as contacts with callsign, last-seen time, and position. |
| **Map Repeater Load/Tune (KML)** | ✅ Working | Tap a repeater placemark from imported KML, arm **Load Selected Repeater**, then tap a destination channel to program/tune it (TX/RX + CTCSS/DCS). |
| **TX Power (LOW / MED / HIGH)** | ✅ Working | **TX Power** button in the Radio panel (left of Dual Watch) cycles transmit power and writes both device settings and per-channel RF memory (digital/APRS + active VFO channels). Syncs from the radio on connect. |
| **APRS TX mode (plugin-generated over KISS)** | ✅ Working | Optional APRS beacon TX runs in parallel with UV-PRO traffic. Requires FCC call + icon in settings, supports manual **Send APRS Beacon**, and can temporarily disable ATAK position beacons when desired. APRS chat requests ACK on the first message to a contact and auto-ACKs inbound APRS messages that request acknowledgment. |
| **Packet Terminal (BBS/Winlink test mode)** | ✅ Working | New **Packet Terminal** action opens an AX.25 connected-mode terminal for radio-to-radio BBS style sessions. Supports SABM/UA/DISC handshake, queued TX, retransmit/ACK handling, RR processing, and basic ANSI cleanup for readable prompts. |
| **Channel grid refresh** | ✅ Working | After long-press manual channel edit/save, the channel grid re-reads that slot from the radio so labels/frequencies match what was programmed. |
| **Channel group controls + CSV import/export** | ✅ Working | **Group** cycles the radio group and refreshes the channel grid. **Import Channels** lets the user choose a CSV from `/atak/tools/import` and writes the selected group with source-of-truth slot mapping (including explicit clears). **Export Channels** writes the current group CSV to `/atak/tools/datapackage/transfer`. |
| **Initial Channel Group Setup** | ✅ Working | Actions panel button seeds empty groups only by programming **CH30 = APRS 144.390** so empty groups become selectable. Provides haptic + yellow pulse while running and completion popup when done. |
| **Bluetooth Auto-Reconnect** | ✅ Working | Three-strategy SPP connection with exponential backoff reconnect (up to 5 attempts). ACL + passive reconnect when the saved UV-PRO powers on. MeshCore auto-restores if Classic BT knocks BLE offline. |
| **Radio Silence (TX Kill Switch)** | ✅ Working | Long-press control in the Radio panel that blocks all outbound TX while still receiving beacons/pings/chat/CoT. Long-press again to restore TX. |
| **RF -> TAK Uplink Relay** | ✅ Working | Optional uplink path: forward inbound RF CoT/chat from radio-only users to TAK network when SA Relay + uplink toggle are enabled. |
| **Connection battery indicators** | ✅ Working | Green percent badge beside each connected device name in the plugin panel — UV-PRO via GAIA `READ_STATUS`, MeshCore via battery/stats commands. |
| **Per-contact Ping (Connectors page)** | ✅ Working | Contact card page 3 adds **Ping** for mesh and established RF peers; sends a directed position request to that contact's callsign over the active transport. |
| **Radial Ping (contact submenu)** | ✅ Working | Long-press a contact → radial **Contact** icon → **Ping** (blue radio icon). Same directed ping as Connectors; does not replace ATAK's stock friendly menu. |
| **Transmit auto-failover** | ✅ Working | Preferred MeshCore vs UV-PRO transmit is tracked separately from the active toggle. If the preferred radio disconnects for **5+ minutes** while the alternate is connected, the toggle switches automatically; it restores when the preferred device reconnects. Manual toggle changes update preference and cancel failover. Logged in the main plugin panel. |
| **Mesh beacon rate limits** | ✅ Working | When **ATAK MeshCore Transmit** is on and **Disable Mesh Beacon Limiting** is unchecked, runtime floors cap mesh periodic/Smart Beacon rates (interval/slow ≥ 30 min, fast/min-turn ≥ 5 min) without changing stored prefs. UV-PRO-only beacons are unaffected; checking the disable box removes caps. |
| **Mesh map Delete Contact** | ✅ Working | Long-press a MeshCore node marker → details panel → **Delete Contact** removes the map item and ATAK contact (same pattern as APRS delete). |

### 2026-06-26 Progress Update (v2.0.14)

- **Beacon Settings crash:** Fix Tool Preferences crash when opening Beacon Settings caused by integer Smart Beacon prefs read as strings.

### 2026-06-25 Progress Update (v2.0.13)

- **GPS beacon interval sync:** Tool Preferences beacon interval no longer reverts on exit; plugin panel **GPS Beacon Interval** updates live when settings change.

### 2026-06-18 Progress Update (v2.0.12)

- **MeshCore expanded chat:** Plugin panel adds **Expand Chat Window** for channel and contact DM tabs; large popup with keyboard, Send, and Close syncs back to the inline chat view.
- **MeshCore device contact DMs:** Load contacts from the node, favorite or open direct-message tabs in the panel, and auto-create inbound GeoChat contacts for native MeshCore app messages.

### 2026-06-17 Progress Update (v2.0.11)

- **Transmit Selection panel:** Plugin panel transport toggles (MeshCore, UV-PRO, WiFi) moved to a purple-framed **Transmit Selection** section between Channel Control and Beacon.

### 2026-06-17 Progress Update (v2.0.10)

- **SA Relay RF throttle:** WiFi/TAK → radio SA Relay broadcasts are limited to **one chirp per contact every 10 minutes** (was 30 seconds). Stops multi-contact WiFi churn from hammering the UV-PRO channel.
- **Auto Send map points on RF:** ATAK Auto Send still refreshes markers on WiFi/TAK as before; RF relay of net-wide map points is **one chirp per marker every 2 minutes**, with **no ACK retries** and no duplicate PreSend/CommsLogger double-send.
- **RF → TAK uplink unchanged:** Inbound RF CoT and the 60 s uplink keepalive still forward to WiFi/TAK at their existing cadence.

### 2026-06-07 Progress Update (v2.0.9)

- **WiFi/TAK ping:** Route broadcast ping over TAK when RF transmit toggles are off; prefer WiFi for `ANDROID-*` contacts; show ping-reply toast on inbound network CoT.

### 2026-06-15 Progress Update (v2.0.7)

- **SA Relay settings UI:** RF to TAK Uplink checkbox stays stable while scrolling (ATAK prefs as single source of truth; no duplicate checkbox desync).
- **RF → TAK uplink:** Uplink runs when SA Relay + RF uplink are enabled in Tool Preferences (no longer blocked by ATAK WiFi Transmit panel toggle).

### 2026-06-15 Progress Update (v2.0.6)

- **SA Relay settings:** **Enable RF to TAK Uplink Relay** now stays checked when enabled after **Enable SA Relay** (UI-aware validation and immediate pref commit).

### 2026-06-15 Progress Update (v2.0.5)

- **SA Relay dedupe:** Signatures use quantized lat/lon + callsign/uid instead of stripped XML so Wi-Fi PLI timestamp/speed/battery churn no longer re-broadcasts stationary contacts every 30 s. Real movement beyond ~1 m still relays.
- **SA Relay TX:** Inbound network SA relay uses `sendCotOverRadioNoRetry`—no automatic CoT double-send (+3 s duplicate burst) on relay traffic.
- **Auto ping reply routing:** Slotted ping replies prefer the inbound radio (mesh vs UV-PRO); alternate radio only when the receive path is disconnected.
- **ATAK 5.6 radio GPS:** `PluginRadioLocationProvider` registers UV-PRO/MeshCore fixes as a first-class ATAK location source (5.6 GPS source toggles) alongside legacy map injection.

### 2026-06-12 Progress Update (v1.9.70)

- **Transmit auto-failover:** Tracks user **preferred** transmit transport (MeshCore vs UV-PRO) separately from the on-screen toggle. After the preferred device is disconnected for five minutes with the alternate connected, the toggle switches to the alternate (prefs/UI update; preference unchanged). Preferred device reconnect restores the toggle immediately. Manual transmit toggle updates preference and clears auto-failover. Failover and restore lines appear in the main plugin log window.
- **Mesh beacon runtime limits:** New `MeshBeaconLimits` applies floors at TX decision time when MeshCore transmit is active and mesh limiting is not disabled: fixed interval / slow rate ≥ 1800 s, fast rate / min turn time ≥ 300 s. Stored Smart Beacon prefs are not rewritten; UV-PRO periodic beacons ignore these caps.
- **Periodic beacon transport:** Startup and periodic OPENRL beacons use the **active transmit transport** (`resolvePeriodicBeaconTransportManager()`), not UV-PRO only, when MeshCore transmit is selected.
- **Automatic beacon logging:** Startup/periodic OPENRL (MeshCore or UV-PRO) and periodic APRS sends append timestamped lines to the main plugin panel log for mesh/radio field testing.
- **Mesh map Delete Contact:** `MeshDetailsDropDownReceiver` adds **Delete Contact** on the mesh marker details dropdown to remove the selected node marker and its ATAK contact entry.
- **AX.25 Packet Terminal:** Graceful BBS hangup (`B` I-frame when connected, spaced DISC with poll/nopoll), KISS TX timing prime and frequency-lock reaffirm on disconnect, `hangupInProgress` blocks auto-reconnect during teardown, and last remote callsign/SSID persist across terminal reopen. Terminal is embedded inline in the scrollable plugin panel.
- **Independent transmit toggles:** MeshCore and UV-PRO transmit switches operate independently with persisted prefs; `TransmitTransportResolver` routes TX to the connected path with silent fallback when the preferred transport is down (failover additionally updates the toggle after 5 min).
- **Boot / BT (carry-over):** UV-PRO-first boot auto-connect, MeshCore boot 2 s after the radio connects or after a 7 s fallback if the radio does not (`UVProMapComponent` mesh boot schedule), classic BT passive reconnect with scan-button strobe feedback, and mesh fast-restore after radio/mesh BLE contention.

### 2026-06-11 Progress Update (Tool Preferences UI + restore buttons)

- **Tool Preferences layout polish:** Yellow centered category headers; blue left-aligned sub-headers for **Smart Beacon Settings**, **SA Relay**, and **Reply slot times** (above Slot count in Administration); white 16sp titles with white description + green value summaries on list/edit fields; centered white **For Team Leadership ONLY** warning in Administration.
- **Restore controls (exactly three):** **Restore All Defaults** pinned to the **top** of Tool Preferences; **Restore Defaults** under **Beacon Settings** (beacon + Smart Beacon only); **Restore Defaults** under **Administrative Settings** (admin section only). Stale duplicate restore rows from older ATAK preference caches are removed on open.
- **Restore / Distribute pill buttons — click fix:** Pills are styled on the preference **title** row (not an embedded `Button`). ATAK’s preference `ListView` swallows nested button taps; clicks now route through `onPreferenceTreeClick()` and direct pill-action dispatch. Row-to-preference mapping uses **display order** (`Preference.getOrder()`) so labels and handlers stay aligned after scroll/recycle.
- **Max Retries description:** *"Number of retransmit attempts before declaring delivery failure. Will re-attempt upon receipt of beacon"*.
- **Smart Beacon Settings entry point:** Plugin panel **Smart Beacon Settings** now opens **Settings → Tool Preferences → UV-PRO** scrolled to Smart Beacon (replaces the legacy in-panel dialog).
- **Admin footer order:** Slot fields → red distribute warning → **Distribute to net** pill (always last in Administration).
- **Dev reload note:** After `adb install -r`, ATAK prompts to load the updated plugin — tap **OK**, then force-close/reopen ATAK when you are ready to test (do not rely on `adb shell am force-stop` during that prompt).

### 2026-06-10 Progress Update (v1.9.70)

- **Directed contact ping:** 12-byte `TYPE_PING` (sender + target wire callsign). Non-target stations do not schedule a ping reply. Sender reply toasts are filtered to the ping target so routine beacons from other stations are not misreported.
- **Radial Ping:** Long-press contact → radial **Contact** submenu → **Ping** (blue `ic_uvpro` icon). Implemented via `ContactRadialMenuFactory` — injects into the stock contact submenu only; does not replace the friendly radial menu.
- **Connector icons:** Ping, Send Message, and Favorite connectors rasterize `ic_uvpro` to cached `file://` URIs for ATAK's Connectors GL view.

### 2026-06-10 Progress Update (v1.9.69)

- **Connection battery indicators:** UV-PRO battery polled via GAIA `READ_STATUS`; MeshCore via battery/stats BLE commands. Percent shown in green beside each connected device name in the dropdown panel.
- **Per-contact Ping:** New **Ping** connector on the contact card Connectors page (page 3) for `MESHCORE-NODE-*` and established RF ATAK peers. Sends directed `TYPE_PING` with sender + target callsign over UV-PRO or MeshCore.
- **Contact handling (carry-over):** Native GeoChat default connector, single notification per inbound message, map icon repair for mesh contacts.

### 2026-06-08 Progress Update (v1.9.67)

- **UV-PRO-first boot priority:** At ATAK startup, UV-PRO auto-connect runs before MeshCore. Mesh auto-connect starts 2 s after the UV-PRO connects, or after 7 s if the UV-PRO has not connected (the fallback keeps waiting while a UV-PRO connect attempt is still in flight). That spacing keeps both transports from opening sockets at the same instant.
- **Mesh/radio BT coexistence:** Classic UV-PRO SPP can briefly drop an active MeshCore BLE session on one phone. The plugin detects contention, skips duplicate radio connect attempts, and fast-restores mesh (direct GATT reconnect, no availability probe) with retries after the radio link settles.
- **Passive radio reconnect:** When mesh boot contention ends, UV-PRO passive reconnect arms with a **3s** first attempt (then every 60s). ACL events connect the saved bonded radio when it powers on without discovery scans.
- **Periodic beacons (v1.9.66 carry-over):** Smart/periodic OPENRL and APRS beacons remain UV-PRO-only; a one-shot post-connect startup beacon may still use MeshCore when that transport is selected.

### 2026-06-07 Progress Update (v1.9.66)

- **Smart Beacon GPS speed:** Beacon decisions now use Android `LocationManager` GPS Doppler speed/bearing (`src=gps`) instead of ATAK self-marker meta or position-derived delta. Eliminates false high-speed spikes from GPS position jitter; position-derived speed remains as a startup fallback only.
- **Smart Beacon turn vs. low-speed logic:** Turn detection now runs before the low-speed threshold check, so heading changes trigger beacons at any speed > 0. Speed-interval beacons are suppressed at or below the configured low-speed threshold; below that, only turns or the fixed beacon-interval floor can fire.
- **Smart Beacon safety floor:** If the configured fixed beacon interval elapses without a Smart Beacon trigger, a beacon is sent anyway so position updates are not silently blocked when speed is unavailable or ambiguous.
- **Dual watch switch stability:** Fixed flicker when toggling dual watch on/off after enable/disable.
- **Channel grid highlight lag:** Fixed delayed highlight updates after channel selection (analog and digital grids).
- **Channel program dialog:** Added bandwidth, mute, and TX power controls when programming a channel.

### 2026-06-06 Progress Update (v1.9.65)

- **Channel grid dual-watch highlight:** Secondary (B) channel is no longer highlighted in the channel grid when the radio is not in dual-watch mode. The green highlight now only appears when dual watch is enabled.
- **All Chat Rooms ACK suppressed:** Inbound RF chat messages addressed to "All Chat Rooms" (broadcast) no longer trigger a DELIVERED ACK back over RF. ACKs are only sent for directed DMs.
- **Default beacon interval:** Increased default GPS beacon interval from 60s to 600s for reduced RF congestion out-of-box.

### 2026-06-04 Progress Update (v1.9.64)

- **Inline MeshCore channel chat window:** Channel messages now appear in an embedded panel at the bottom of the plugin instead of a separate popup dialog.
- **Channel tab strip:** Each subscribed channel (excluding `ATAK_DATA`) gets its own tab button above the chat log. One channel = full-width; multiple channels = equal-width tabs. Selected channel is highlighted cyan. Long-press a tab to open channel settings.
- **Add Channel dialog (5 options):**
  - *Join the Public Channel* — one tap; uses the well-known public key.
  - *Join a Hashtag Channel* — enter `#name`; key derived from `SHA-256("#name")[0..15]`.
  - *Create a Private Channel* — random 16-byte secret generated; QR code + copyable hex shown.
  - *Join a Private Channel* — enter channel name + 32-char hex secret.
  - *Scan QR Code* — opens native camera scanner (ZXing core); parses `meshcore://channel/add?name=X&secret=Y`.
- **Channel long-press settings menu:** Share (QR + copyable secret), Rename, Participants, Remove.
- **Channel name on sender side:** Channel messages now show the node's advertised name (e.g. `ATAK-TEST1`) instead of the ATAK callsign.
- **Status upgrade fixed:** "queued" now correctly updates to "Sent" / "heard N repeats" in the inline window after delivery.
- **MeshCore status overlay dedup fix:** `install()` now skips if already installed; deferred-retry lambda uses a generation counter to cancel stale retries. Removed redundant `install()` call from `createView()`.
- **GPS power switch synced:** "Enable MeshCore GPS" appears in both CONNECTION and MESHCORE sections; toggling either updates both and gates "Update GPS from MeshCore" and "Augment GPS from MeshCore".
- **Advert toast:** "Advert Sent" toast fires on successful `sendSelfAdvert()`.

### 2026-06-02 Progress Update (v1.9.61)

- **CoT Minification:** Non-essential `<detail>` children (`link`, `creator`, `takv`, `precisionlocation`, `status`, `archive`, `height`, `track`, `uid`, `ce`, `le`, `_flow-tags_`) and XML declaration stripped before compression. Reduces typical waypoint from 3→2 fragments. Never-worse fallback — `version='2.0'` preserved (required by ATAK CoT parser).
- **CoT Hop Count Threading:** MeshCore channel message `pathLen` (repeater hop count) now flows from `MeshBtConnectionManager` → `PacketRouter` → `CotBridge`. Logged on every received CoT (`"CoT received: type=... via N hops"`).
- **CoT ACK/Retry System:** New `TYPE_COT_ACK (0x08)` packet type. Receiver sends ACK after successful CoT parse. Sender registers 30-second watchdog; retransmits at 15s intervals up to 5 times if no ACK. Immediate double-send at T+3s for RF collision resilience. GeoChat CoT (`b-t-f*`) excluded (uses existing chat retry). Executor shuts down cleanly on plugin dispose.

### 2026-06-02 Progress Update (v1.9.60)

- **Fixed contact icon revert to blue radio on inbound MESHCORE-* DM:** `UVProContactHandler.repairAtakPeerConnectorDefault` (new method) re-stamps `GeoChatConnector` as the default connector after `GeoChatService.onCotEvent` overwrites the preference. A 600ms delayed `dispatchChangeEvent` (main thread) refreshes the contacts-list icon to the chat bubble without triggering a duplicate message reload.
- **Fixed double notification badge on MESHCORE-* contacts:** `getFeature(NotificationCount)` now returns 0 for all contacts. ATAK's `ContactConnectorManager` sums all registered handler results — returning a plugin count alongside ATAK's built-in `GeoChatConnectorHandler` produced "Geo Chat: 1 + Send Message: 1" = 2 badges. Native `GeoChatConnector` tracking is now the sole badge source.
- **Fixed blue radio icon / broken tap action:** `applyMeshContactConnectors` now sets `GeoChatConnector` as the default (was `MeshSendMessageConnector`). `handleContact` intercepts both `GeoChatConnector` and `MeshSendMessageConnector` taps, sends `markmessageread` to clear the native badge, and calls `openConversation(ic, false)` to open the chat panel directly (was returning `false` for `GeoChatConnector` and calling `openConversation(true)` for `MeshSendMessageConnector`, both of which showed a contact card or did nothing).
- **Fixed `ArrayIndexOutOfBoundsException` in NetConnectString:** `buildNativeConnectorSeed` now uses `127.0.0.1:4242` instead of `*:-1`. ATAK's `NetConnectString.isMulticast()` parser threw on the wildcard host, preventing conversation fragments from opening.
- **`clearNativeGeoChatUnread` added to CotBridge:** Broadcasts `markmessageread` (300ms delayed, main thread) for MESHCORE-* senders after `GeoChatService.onCotEvent`, clearing ATAK's native unread count before the user sees it.

### 2026-06-01 Progress Update (v1.9.59)

- **MeshCore position source controls:** Added a third source toggle, `Use Custom Node Position`, directly below `Use Meschore GPS for position`.
- **Dependent toggle gating:** `Use UNKNOWN location for position`, `Use Meschore GPS for position`, and `Use Custom Node Position` now all grey out when `Send Postion With Advert` is OFF, while preserving their stored ON/OFF values.
- **Mutual exclusion for position source toggles:** Only one source toggle can be enabled at a time. Turning one ON automatically turns the other two OFF.
- **Custom map position button gating:** `Set Node Position on Map` now follows `Use Custom Node Position` and is greyed out when that toggle is OFF.
- **ATAK WiFi transmit enforcement + default OFF:** `ATAK WIFI Transmit` now actively controls RF->TAK uplink dispatch (disabled means no uplink send), and the default preference state is now OFF.

### 2026-06-01 Progress Update (v1.9.58)

- **Silent iconset auto-install:** APRS and MeshCore iconsets now install automatically on first ATAK launch with no user interaction. The plugin stages the bundled zips to `/sdcard/atak/tools/import/` then sends the `com.atakmap.android.icons.ADD_ICONSET` broadcast directly to ATAK's `IconsMapAdapter`. No notification, no dialog, and no manual Point Dropper → Add Iconset step required. Install is idempotent — skipped if the iconset UID is already present in `iconsets.sqlite`.
- **WiFi keepalive TX leak identified:** SA keepalive unicasts intended for Wi-Fi peers were being double-relayed over RF when those peers also had a radio/plugin connector registered. Documented in the SA Relay section; fix pending.

### 2026-05-30 Progress Update (v1.9.55)

- **MeshCore DMs use native contact messages:** Map-selected GeoChat direct messages to MeshCore nodes now go out as standard pubkey-to-pubkey contact messages (`CMD_SEND_TXT_MSG`) instead of the proprietary `0xFF01` AX.25 channel datagram. Native MeshCore clients previously rejected those datagrams as "Unhandled," so DMs were never delivered; they now interoperate with the native MeshCore app in both directions.
- **Inbound native DMs → GeoChat:** Incoming MeshCore contact messages (`RESP_CONTACT_MSG` / `_V3`) are parsed by the sender's pubkey prefix and injected into the matching `MESHCORE-NODE-<pubkey>` GeoChat thread.
- **Mesh contact routing:** Mesh-node contacts are kept on the `MeshSendMessageConnector` (plugin RF) path rather than the unroutable native `stcp` connector that caused ATAK to throw "Send to unknown contact."
- **Transmit-mode persistence:** The `ATAK MeshCore Transmit` preference is now persisted and honored for beacon transport selection.
- **Note:** As with the native app, the recipient node must already be a contact on the sender's MeshCore node (the firmware routes DMs by 6-byte pubkey prefix).

### 2026-05-31 Progress Update (v1.9.56)

- **MeshCore Node Settings panel:** Added a new `Node Settings` action in the MeshCore section with live polling and apply controls for node name, frequency, bandwidth, spreading factor, coding rate, and transmit power (dBm). `Apply` and `Refresh` now keep the dialog open, and the panel always opens scrolled to the top.
- **Advert persistence decoupled from display toggles:** Node and repeater adverts are now stored even when `Show Nodes` / `Show Repeaters` is OFF. The toggles now control rendering only.
- **Node cache policy:** Mesh node cache keeps a rolling cap of the most recent **75** nodes (favorites exempt), evicts oldest non-favorites when over cap, and purges non-favorites older than 30 days.
- **Channels picker cleanup:** The internal `ATAK_DATA` channel is hidden from the user-facing MeshCore `Channels` list.
- **Inbound DM trust gate:** Native MeshCore inbound DMs are now accepted only from existing mesh contacts (favorited/known in ATAK); unknown senders are dropped instead of auto-creating a new contact thread.

### 2026-05-21 Progress Update

- RF group sync now relays full GeoChat CoT `b-t-f` with `hierarchy` and improved inbound handling through ATAK GeoChat paths.
- RF group/message behavior was validated across a 3-device Wi-Fi↔RF bridge test (group create/send path stable, slot timing confirmed).
- SA Relay now suppresses unchanged periodic SA/status payloads (`a-*`) per UID, so stationary Wi-Fi contacts are not rebroadcast over RF every 30 seconds when content has not changed.
- Non-SA traffic (chat, routes, markers, targeted CoT) continues to relay normally.

### 2026-05-23 Progress Update

- Fixed channel-group state ownership so hardware group changes on the radio are no longer overwritten by background plugin polling.
- Group/event handling now refreshes selected group state first, then refreshes the channel grid, so UI group label and grid stay aligned with the radio.

### 2026-05-25 Progress Update

- Added MeshCore BLE transport integration in the UV-PRO plugin with dedicated map overlay icon, scan/connect/disconnect controls, and optional startup auto-connect to last Mesh favorite target.
- Added dual transport selectors (`ATAK MeshCore Transmit` / `ATAK UV-PRO Transmit`) with mutual exclusivity and persisted direct-connect target behavior for both transports.
- Added Mesh GPS controls (`Enable MeshCore GPS`, `Update GPS from MeshCore`, and `Augment GPS from MeshCore`) with source labeling as `MeshCore GPS` and corrected UI visibility logic.
- Added dedicated `FAVORITE MESH` chips above Mesh connect controls, independent from `FAVORITE RADIOS`, while preserving UV-PRO direct-connect behavior.
- Updated Actions panel workflow: `Initial Channel Setup (Long Press)` with one-time setup helper text.

### 2026-05-26 Progress Update

- Added a Packet Terminal entrypoint in the Actions panel and integrated terminal frame interception in `PacketRouter` so connected-mode AX.25 traffic reaches the terminal session.
- Implemented connected-mode reliability for terminal sessions: TX queue, small send window, RR/Nr ACK advancement, timeout-based retransmit, and link-timeout teardown.
- Hardened AX.25 decode for valid 15-byte control frames (SABM/UA/DISC), which fixed control-frame drops during terminal handshake on live radios.
- Improved terminal transcript rendering by stripping common ANSI escape sequences and applying backspace handling to keep BBS prompts readable.

### 2026-05-27 Progress Update (v1.9.47)

- MeshCore transport now provisions and uses a dedicated ATAK data channel (`ATAK_DATA`, slot 7) with app-managed secret, keeping TAK payloads off user-visible channel text paths.
- MeshCore payload TX/RX now uses companion data packets (`CMD_SEND_CHANNEL_DATA` / `RESP_CHANNEL_DATA_RECV`) for ATAK envelopes and contact updates.
- Startup beacon timing was hardened: the first forced beacon now waits for a valid ATAK self position (GPS or manually set), then sends 30 seconds later.
- Beacon routing now selects the active connected transport (UV-PRO or MeshCore), including mesh-only boot/connect scenarios.
- Transmit mode defaults are connection-aware: prefer UV-PRO when UV-PRO is connected, otherwise prefer MeshCore when only MeshCore is connected.
- Compact RF sender aliases and auto-point sanitization were added to reduce random pseudo-contact creation from compressed IDs and auto-generated point names.

### 2026-05-27 Progress Update (v1.9.48)

- UV-PRO `Scan & Connect` now performs classic Bluetooth discovery for unpaired radios (not just bonded devices), while still listing previously paired UV-PRO targets.
- Selecting an unpaired UV-PRO now triggers Android bonding (`createBond`) and auto-connects immediately after pairing succeeds.
- Scan responsiveness was improved by ending discovery early when a new unpaired UV-PRO is found and by enforcing an 8-second scan timeout cap.
- Connect mode now only appears for explicit favorite selection; if a remembered non-favorite radio is unavailable, the UI remains in `Scan & Connect` mode.
- MeshCore connect-button pulse animation was disabled so auto-connect attempts no longer flash the button.
- Added persistent agent rule `.cursor/rules/transport-agnostic-routing.mdc` requiring all routing/message/CoT fixes to be transport-agnostic and verified on both UV-PRO and MeshCore paths.

### 2026-05-31 Progress Update (v1.9.53)

- **APRS chat contacts (FCC callsign):** Inbound APRS text messages create a dedicated Contacts row labeled with the sender's **ham FCC call** (not the ATAK tactical callsign). GeoChat opened from **Send APRS Message** routes outbound traffic over **APRS KISS** instead of UV-PRO TYPE_CHAT, so receivers do not merge APRS traffic into existing Wi‑Fi/RF peers (e.g. `SMOKEY_15`). UV-PRO gateway fallback can carry optional `aprsSender` metadata for the same identity on mesh hops.

### 2026-05-29 Progress Update (v1.9.52)

- **Wi‑Fi contact keepalive (Wi‑Fi only):** Unicast mini-SA keepalives sent via `dispatchToContact()` are tagged in CoT remarks and excluded from RF relay in `CotBridge` PreSend, CommsLogger broadcast fallback, and contact-targeted map relay — so reachability probes stay on Wi‑Fi/TAK and do not go out over UV‑PRO radio.

### 2026-05-29 Progress Update (v1.9.51)

- **Multi-transport contacts (Wi‑Fi + RF):** When a peer is reachable on both TAK network and radio, the plugin keeps **one** ATAK contact per operator. Wi‑Fi/TAK contacts use native `ANDROID-*` hash UIDs; RF may introduce compressed wire callsigns or synthetic `ANDROID-<CALLSIGN>` UIDs. On startup and radio connect, `collapseAllCallsignAliasDuplicates()` merges aliases (`JESTER_25` / `JSTR25`, `SMOKEY_15` / `SMKY15`) and prefers the native Wi‑Fi contact when present so chat, map markers, and send actions stay unified.
- **AX.25 wire vs UI callsign:** The 6-character radio address (`SMKY15`, `JSTR15`) is an **AX.25 / TYPE_CHAT room limit only** — it is never shown in Contacts or GeoChat. The UI always displays the full ATAK callsign (`SMOKEY_15`, `JESTER_15`) entered in settings.
- **RF gateway envelope:** Direct messages over RF wrap metadata as `__UVGW__|wireDest|displayCallsign|lineUid|message` where `wireDest` is the 6-char AX.25 destination and `displayCallsign` is the full ATAK name for threading and labels. Wi‑Fi opaque device UUIDs are **not** placed in RF gateway routing.
- **Duplicate message suppression:** When Wi‑Fi and radio are both connected, the first delivery (usually network GeoChat) is recorded by line UID; a redundant RF copy is skipped (`Skip duplicate inbound chat`).
- **Direct-message RF routing:** Inbound DMs accept only when the wire destination matches this operator (full/radio/alphanumeric callsign variants). Non-target peers ignore the packet and do not emit delivered ACKs.
- **Sender display:** Inbound GeoChat prefers mapped contact names over compressed wire callsigns when a UID mapping exists.
- **Bluetooth Scan & Connect:** Pairing flow filters for pairable unpaired radios while preserving multi-candidate picker selection.
- **ACK correlation:** Wire chat message IDs seed from a time-based value each launch; outbound ACK mapping accepts full GeoChat IDs and UUID-only line IDs.

### Multi-transport contacts (Wi‑Fi + radio at the same time)

When multiple transports are active, ATAK can show the same operator under different identities:

| Source | Typical UID | Display name |
|--------|-------------|--------------|
| TAK / Wi‑Fi | `ANDROID-b726a98286ca1d08` (opaque hash) | `SMOKEY_15` |
| RF GPS / chat | `ANDROID-SMOKEY_15` or wire `SMKY15` | full or compressed callsign |

The plugin treats these as **one person**, not two contacts:

1. **Variant matching** — `radioCallsignKey()`, alphanumeric keys, and `CallsignUtil.toRadioCallsign()` link `SMOKEY_15`, `SMOKEY15`, and `SMKY15`.
2. **Canonical contact selection** — `ensurePluginChatContact()` and `collapseDuplicateContactsForCallsign()` prefer an existing native Wi‑Fi `IndividualContact` (TCP/stcp connector) over a plugin-synthesized RF UID; duplicate UIDs are removed from the contact list.
3. **Startup / connect sweep** — `UVProMapComponent` calls `collapseAllCallsignAliasDuplicates()` after plugin init and again ~5 s after radio connect.
4. **Outbound chat** — sending to a merged contact routes over the active transmit path (UV‑PRO, MeshCore, or Wi‑Fi toggles). RF DMs use `wireDest` (6-char) on the air and `displayCallsign` (full name) in the gateway envelope for the receiver’s UI.
5. **Inbound chat** — Wi‑Fi delivery is noted by GeoChat line UID; RF copies with the same line UID are dropped. RF-only peers still create/update plugin contacts and map markers as before.

**Rule of thumb:** 6-character strings exist only on the RF wire; everything the operator sees in ATAK uses the full callsign.

## How It Works

```
┌─────────────────────────────────────┐
│           ATAK Application          │
│  ┌───────────────────────────────┐  │
│  │      UV-PRO Plugin        │  │
│  │                                │  │
│  │  Bluetooth ─► KISS TNC ─►    │  │
│  │  AX.25 frames ─► Packet      │  │
│  │  Router ─► CoT / Chat /      │  │
│  │  GPS / Encryption             │  │
│  └───────────────────────────────┘  │
└──────────────┬──────────────────────┘
               │ Bluetooth SPP (Data)
               ▼
┌─────────────────────────────────────┐
│       BTECH Radio (KISS TNC)        │
└──────────────┬──────────────────────┘
               │ RF (VHF/UHF)
               ▼
┌─────────────────────────────────────┐
│     Other Radios + EUDs in Range    │
└─────────────────────────────────────┘
```

The plugin talks to the radio over Bluetooth SPP using the KISS TNC protocol. Data is encapsulated in AX.25 frames with a compact binary format. On the ATAK side, incoming packets become CoT events, map contacts, and chat messages; outgoing ATAK data is serialized, optionally encrypted, and transmitted over radio.

## Requirements

### To Use the Plugin
- **ATAK-CIV 5.5.1** (or compatible version) installed on your Android device
- **UV-PRO** radio (UV-PRO, GMRS-PRO, or UV-50X series with KISS TNC support)
- **Bluetooth** pairing between the Android device and radio

### To Build from Source
- **JDK 17** — [Eclipse Temurin](https://adoptium.net/) recommended. Other JDK 17 distributions work too.
- **Android SDK** with API level 35 — install via [Android Studio](https://developer.android.com/studio) or the [command-line tools](https://developer.android.com/studio#command-line-tools-only)
- **ATAK-CIV 5.5.1 SDK** — available from the [atak-civ GitHub repo](https://github.com/TAK-Product-Center/atak-civ)
- **Git** — to clone the repo

> **Note:** You do _not_ need to install Gradle. The included Gradle wrapper (`gradlew` / `gradlew.bat`) downloads the correct version automatically.

## Quick Install (Pre-built APK)

If you just want to install the plugin without building it:

1. Download the latest APK from the [Releases](../../releases) page.
2. Transfer it to your Android device.
3. Install with: `adb install -r UVPro-*.apk`
4. Open ATAK → Menu → Tools → **UV-PRO**.

APK filenames look like `ATAK-Plugin-UVPro-*-civ-release.apk` (or `civ-debug` for debug builds).

### Upgrading from a debug or self-signed build

If you previously installed a **debug** or **self-signed** (non-TPC) build, Android will block the upgrade with an "App not installed" or "incompatible" error because the signing certificate changed. This is a one-time issue — all TPC-signed releases share the same certificate going forward.

**Fix (your ATAK data is safe):**

> Uninstalling the plugin APK does **not** affect the `/atak` directory, your maps, contacts, tracks, or any other ATAK data. That data belongs to the ATAK application, not this plugin.

```bash
# 1. Remove the old plugin (ATAK data is untouched)
adb uninstall com.uvpro.plugin

# 2. Install the new TPC-signed APK
adb install ATAK-Plugin-UVPro-*-tpc-*-civ-release.apk
```

After reinstalling, open ATAK → Menu → Tools → **UV-PRO** and reconnect your radio. All previous settings are stored in ATAK's shared preferences and will be restored automatically.

## GitHub releases and signing

- **Third-party (TPC) signing:** The APK that is fully aligned with **stock ATAK-CIV** and the usual install rules is the one built and signed on the **TAK Product Center third-party pipeline** (takrepo). It may show the standard indicator that the plugin was signed with the third-party service. No extra code is required in this repo for that — trust comes from the **pipeline signature**, not a flag in Java.
- **GitHub Releases:** Each [release](https://github.com/atakmaps/UV-PRO/releases) can attach the **same civ-release APK** produced for that version (ideally the TPC output). You can also build `assembleCivRelease` yourself (see below); for **public distribution**, prefer the **TPC-signed** binary when you have it.
- **Local `assembleCivRelease`:** ProGuard/R8 needs an **ATAK apply-mapping** file. This repo sets `atak.proguard.mapping` automatically: if you place the real `proguard-civ-release-mapping.txt` from a TPC/takrepo build in `app/libs/atak-civ/`, that is used; otherwise a **placeholder empty mapping** (`tools/empty-atak-applymapping.txt`) is used so the build completes. A build with the placeholder is fine for **CI smoke tests**; for **field use**, prefer a release built with the **official ATAK mapping** and/or the **TPC APK**.
- The `android` block in `app/build.gradle` sets `bundle { storeArchive { enable = false } }` as required by **atak-takdev** `takdevLint` for release signing.

## Building from Source

### 1. Download the ATAK-CIV SDK

Go to the [atak-civ GitHub repo](https://github.com/TAK-Product-Center/atak-civ), download the **ATAK-CIV 5.5.1 SDK**, and extract these files into `app/libs/atak-civ/`:

```
app/libs/atak-civ/
├── main.jar
├── atak-gradle-takdev.jar
├── android_keystore
└── proguard-release-keep.txt
```

> The SDK zip contains more files — you only need these four.

### 2. Configure `gradle.properties`

The repository includes a committed `gradle.properties` with shared flags. If Gradle does not pick up **JDK 17** automatically, add a line to that file (or copy from the template and merge):

```bash
# optional if JAVA_HOME is not JDK 17:
# cp gradle.properties.example gradle.properties   # only if you need a fresh file
```

Open `gradle.properties` and set `org.gradle.java.home` to your JDK 17 if needed:

| OS | Typical JDK 17 Path |
|----|---------------------|
| **Windows** | `C:\\Program Files\\Eclipse Adoptium\\jdk-17.x.x-hotspot` |
| **macOS** | `/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home` |
| **Linux** | `/usr/lib/jvm/temurin-17-jdk-amd64` |

> **Tip:** If your system `JAVA_HOME` already points to JDK 17, you can delete the `org.gradle.java.home` line entirely.

### 3. Build the APK

```bash
# Clone the repo
git clone https://github.com/atakmaps/UV-PRO.git
cd UV-PRO

# Linux/macOS
./gradlew assembleCivDebug

# Windows (Command Prompt or PowerShell)
gradlew.bat assembleCivDebug
```

The APK will be at:
```
app/build/outputs/apk/civ/debug/ATAK-Plugin-UVPro-*.apk
```

### 4. Install

```bash
adb install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-UVPro-*.apk
```

When ATAK is already running, accept the **load plugin** prompt after install, then restart ATAK when you want the new build active.

Then open ATAK → Menu → Tools → **UV-PRO**.

### 5. Release (minified) build — `assembleCivRelease`

For a **R8/ProGuard** release build (smaller, obfuscated) matching the TPC `civRelease` variant:

```bash
./gradlew :app:assembleCivRelease
# Windows: gradlew.bat :app:assembleCivRelease
```

Output:

```
app/build/outputs/apk/civ/release/ATAK-Plugin-UVPro-*-civ-release.apk
```

Use the **official ProGuard apply-mapping** from the ATAK/takrepo pipeline when you need a **production-equivalent** binary (see [GitHub releases and signing](#github-releases-and-signing) above).

### Troubleshooting

| Problem | Fix |
|---------|-----|
| `Android Gradle plugin requires Java 17` | Your `gradle.properties` is missing or `org.gradle.java.home` points to the wrong JDK. See step 2. |
| `Could not find main.jar` | The ATAK SDK files aren't in `app/libs/atak-civ/`. See step 1. |
| `AAPT: error: resource not found` | Run `./gradlew clean` and rebuild. |
| Build succeeds but plugin doesn't appear in ATAK | Make sure you're running **ATAK-CIV 5.5.1** — the plugin is compiled against this specific version. |

## Usage

1. **Pair your radio** with your Android device via Bluetooth settings.
2. Open the **UV-PRO** plugin in ATAK.
3. Tap **Scan** to find your radio, then tap it to connect.
4. The status dot turns green when connected.

### Plugin Controls

| Control | What It Does |
|---------|-------------|
| **AES-256-GCM switch** | Enable encryption (enter the shared secret first) |
| **Send Beacon** | Immediately broadcast your current ATAK/UV-PRO position beacon |
| **Send Ping** | Broadcast a discovery ping to radios in range |
| **Long Press for APRS Beacon** | Enable/disable scheduled APRS beacons (follows ATAK beacon interval policy while enabled) |
| **Send APRS Beacon** | Manually transmit one APRS position beacon now |
| **Long Press for Radio Silence** | Toggle TX block on/off (RX remains active). Active state is highlighted with an orange border. |
| **Load Selected Repeater** | Arms repeater load mode (yellow border + `Select Channel` label), then writes/tunes selected repeater to the tapped channel |
| **TX Power** | Tap to cycle **LOW → MED → HIGH**; updates global VFO settings and channel power flags on the digital/APRS channel plus active VFO slots |
| **Group** | Cycles the radio's current channel group and refreshes the grid for that group. If group change does not stick (typically empty-group condition), plugin prompts to run **Initial Channel Group Setup**. |
| **Import Channels** | Opens a chooser for CSV files in `/atak/tools/import`; after user confirmation, imports selected CSV into the active group using full-slot overwrite semantics (empty rows clear channels). |
| **Export Channels** | Exports the active group to `/atak/tools/datapackage/transfer/groupx_export_DTG.csv`. |
| **Initial Channel Setup (Long Press)** | Long-press only one-time group bootstrap from Actions panel: programs empty groups only with **CH30 APRS 144.390** and shows `Channel Group Setup Complete` on completion. |
| **Plugin Settings** | APRS (FCC call, SSID, icon grid picker, message), Beacon, ping reply, SA Relay, encryption, retries, restore defaults (global / per-section), and **Administration** (slot count/time, distribute to net). Full list under ATAK **Settings → Tool Preferences → UV-PRO Settings**. Panel **Smart Beacon Settings** jumps to the same Tool Preferences screen. |

### Repeater workflow (KML)

1. Import repeater KML into ATAK.
2. Tap a repeater placemark on the map (must contain TX/RX and tone metadata).
3. UV-PRO opens and updates **Selected Repeater**.
4. Tap **Load Selected Repeater** (button arms and turns yellow with `Select Channel` text).
5. Tap destination channel in **Channel Control** grid to program/tune that channel.

### Contact-centric routing (important)

Radio peers are represented as **native ATAK Contacts** (UIDs look like `ANDROID-<CALLSIGN>`). Use the ATAK Contacts UI to:

- open GeoChat with a radio contact (messages route over RF via the plugin).

Waypoints, routes, casevac/9-line, drawings, and other CoT items can all be sent to a radio contact using the native ATAK "Send to Contact" UI. The plugin intercepts the outbound CoT, compresses it, fragments it if needed, and transmits it over RF.

### SA Relay

Enable **SA Relay** in Settings to automatically broadcast received network SA (team positions, waypoints, routes) over RF to all radio users on frequency. This is designed for a single designated relay node — **do not enable unless instructed by your team leader.** Each contact is relayed onto RF at most **once every 10 minutes** (unchanged stationary contacts are still suppressed). **Enable RF to TAK Uplink Relay** is separate: it forwards RF traffic onto WiFi/TAK and is not limited by the 10-minute RF throttle.

Stationary Wi-Fi/TAK contacts are suppressed when position has not meaningfully changed (~1 m quantization on lat/lon, ignoring timestamps, speed, course, and battery fields). Relayed SA is not subject to the generic CoT +3 s double-send used for other outbound CoT.

For deeper implementation details and a full “new agent” handoff (logic trees, key files, known ATAK gotchas), see `HANDOFF.md`.

### Encryption

When enabled, all outgoing packets are encrypted with AES-256-CBC using a key derived from your passphrase (PBKDF2). **All radios in your group must use the same passphrase.** If a packet fails to decrypt on the receiving end, it is silently dropped.

## Project Structure

```
app/src/main/java/com/uvpro/plugin/
├── UVProLifecycle.java       # Plugin entry point
├── UVProTool.java            # Tool registration
├── UVProMapComponent.java    # Core component — wires everything together
├── UVProDropDownReceiver.java # UI panel
├── bluetooth/
│   └── BtConnectionManager.java  # Bluetooth SPP + KISS TNC connection
├── kiss/
│   ├── KissConstants.java        # KISS protocol constants
│   ├── KissFrameEncoder.java     # Encode AX.25 → KISS frames
│   └── KissFrameDecoder.java     # Decode KISS frames → AX.25
├── ax25/
│   ├── Ax25Frame.java            # AX.25 frame builder/parser
│   └── AprsParser.java           # APRS position parser
├── protocol/
│   ├── UVProPacket.java          # Binary packet format
│   ├── PacketRouter.java         # Routes incoming packets to subsystems
│   ├── PacketFragmenter.java     # Fragment/reassemble large packets
│   ├── NetSlotConfig.java        # Ping-reply slots + net distribution
│   ├── PingReplyScheduler.java   # Slotted GPS ping replies
│   └── UVProRadioServices.java   # Live radio TX for administration
├── cot/
│   ├── CotBridge.java            # CoT ↔ radio relay
│   └── CotBuilder.java           # Build CoT events from radio data
├── chat/
│   └── ChatBridge.java           # GeoChat ↔ radio relay
├── crypto/
│   └── EncryptionManager.java    # AES-256-GCM + PBKDF2 (envelope v3)
├── contacts/
│   ├── ContactTracker.java       # Track radios in range
│   └── RadioContact.java         # Contact data model
├── voice/
│   └── (legacy PTT scaffolding; not shipped as a feature in this fork)
└── ui/
    └── SettingsFragment.java     # Preference constants and helpers
```

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions and guidelines.

## License

MIT + Commons Clause — free to use, modify, and distribute, but commercial sale rights are reserved. See [LICENSE](LICENSE).
