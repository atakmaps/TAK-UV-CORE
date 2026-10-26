# UV-PRO v1.9.52 — Integration & Field Test Plan

> **Purpose:** Single reference for multi-transport integration work (WiFi, UV-PRO RF, MeshCore).  
> **Last updated:** 2026-05-30 (dual-transport code review + contact/ACK audit)  
> **Plugin:** UV-PRO **1.9.52** · ATAK CIV **5.5.1.8** · serverless multicast/P2P (no TAK server)  
> **Repo:** `/home/paul/Documents/ATAK/Plugins/Darksteal/`  
> **Rule:** Transport-agnostic fixes — validate on **both** UV-PRO and MeshCore (`.cursor/rules/transport-agnostic-routing.mdc`).

---

## Status legend

| Mark | Meaning |
|------|---------|
| ✅ | Done — implemented **and** field-verified (or explicitly accepted) |
| 🔧 | Implemented in code — **not yet** field-verified on all required transports |
| 🧪 | Ready to test — build deployed, procedure defined |
| ⬜ | Not started |
| ⚠️ | Partial / known gap |
| 🚫 | Shelved — operator decision; not active in current builds |

Update this file as phases complete. Agents: **edit checkboxes here** instead of inferring status from chat history.

---

## Current status (2026-05-30)

**Committed baseline (Darksteal `main`, pushed):** `70a39ea` map broadcast relay · `6c7cba4` ping toasts · `15b108e` RF chat ACK 5s delay · prior `bc0cc3a` bridge/uplink/merge · GeoChat reachability UI removed.

**MeshCore standalone (`TAK-MESHCORE` v1.3.1 `eec5a9e`):** same mesh fixes ported + Send Ping button.

| Area | Status | Notes |
|------|--------|-------|
| Contact merge / single marker | 🔧 | Collapse + orphan cleanup in code; partial field verify |
| WiFi baseline (3-device) | ⚠️ | Works intermittently; cold-start discovery 3–5+ min |
| SA Relay network → RF | 🔧 | Filter + signature throttle in code |
| RF → TAK uplink + keepalive | 🔧 | `RfTakUplinkKeepalive` + `dispatchToBroadcast`; needs retest |
| Relayed RF peer on WiFi contact row | 🔧 | `ensureInboundNetworkSaContact()` on J15 — SMKY showed on map/list after uplink |
| GeoChat reachability UI (red X) | 🚫 | **Removed 2026-05-30** — all contacts use native chat bubble; delivery not guaranteed cross-transport |
| Reachability backend (SA relay filter) | 🔧 | `ContactReachability.shouldSaRelayNetworkSa()` still active |
| WiFi contact keepalive | 🔧 | 60s unicast SA — not log-verified |
| **Map broadcast relay (mesh-only)** | ✅ | `70a39ea` — broad 2525 types (`a-u-G`, etc.), `ITEM_PERSIST`/`ITEM_SHARED`, CommsLogger fallback, `sendable` refresh on connect |
| **Transit DM ACK gating (bridge)** | ✅ | J25 skips DELIVERED for non-local DMs; log-verified SMOKEY→J15 |
| **RF GeoChat ACK (delivered + read)** | ✅ | 5s TX delay `15b108e`; SMOKEY↔J15 both ticks after retest |
| **Ping discovery toasts** | 🔧 | Send/receive/reply toasts in code; slotted reply window — retest on 3-node mesh |
| MeshCore transport (UV-PRO plugin) | 🔧 | Mesh path active in mesh-only fleet tests; toggle matrix not re-run |
| MeshCore standalone plugin | ✅ | v1.3.1 parity commit pushed to `TAK-MESHCORE` |
| **Dual transport (UV-PRO + Mesh)** | ⚠️ | Shared `PacketRouter`; TX via one `btManager` — **known races** (see Phase 8) |
| Outbound exclusive routing (P1) | 🔧 | Toggle UI exists; beacon timer can override user TX choice |

**Target operating mode (next field phase):** UV-PRO **and** MeshCore **both connected**, toggling active TX path for redundancy — WiFi secondary.

**Bridge settings (J25 only, for SMKY ↔ J15 path):** SA Relay **ON**, RF → TAK Uplink **ON** *(not exercised during mesh-only session)*.

**Operator policy:** `adb install -r` to reload plugin; restart ATAK only when explicitly requested.

---

## Test fleet

| Callsign | adb serial | Role |
|----------|------------|------|
| **JESTER_25** | `(adb serial)` | Bridge / observer (SA Relay + RF→TAK uplink host) |
| **SMOKEY_15** | `(adb serial)` | RF-only (WiFi off) |
| **JESTER_15** | `(adb serial)` | WiFi-only (radio off) |

**Deploy:** `adb -s <serial> install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-UVPro-1.9.52-*-civ-debug.apk`  
**Restart ATAK:** `adb shell am force-stop com.atakmap.app.civ && adb shell monkey -p com.atakmap.app.civ -c android.intent.category.LAUNCHER 1`

**Log capture (example):**
```bash
LOGDIR="/home/paul/Documents/ATAK/test-logs/phaseN-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$LOGDIR"
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" logcat -v threadtime | grep -iE 'UVPro\.(ChatBridge|CotBridge|Router|Reachability|WifiKeepalive|RfTakUplink|MeshBLE)' > "$LOGDIR/J25.log" &
```

---

## Transport policy (design intent)

| Toggle / path | Intended outbound behavior |
|---------------|----------------------------|
| **WiFi ON**, no RF/Mesh | Native ATAK WiFi/TAK only; plugin does not RF-relay |
| **WiFi OFF**, RF connected (UV-PRO or Mesh per toggle) | RF/Mesh only; block native WiFi sends |
| **WiFi ON + RF/Mesh connected** | **Exclusive pick** per message: WiFi **or** off-network, never both |
| **Nothing connected** | Fail visibly; no silent drop |
| **Contacts** | One row + **one map marker** per callsign; transport invisible in UI |
| **GeoChat UI** | Native chat bubble for all contacts (reachability restriction **disabled** — operator accepts undelivered messages) |
| **SA Relay filter** | WiFi-only peer SA still **not** rebroadcast to RF when endpoint/opaque-UID rules match |

---

## Phase overview (re-assessed 2026-05-30)

| Phase | Focus | Code | Field test |
|-------|--------|------|------------|
| **0** | Analysis & transport policy (Q1–Q4) | ✅ | ✅ (design locked) |
| **1** | P0 contact merge + single map marker | ✅ | ⚠️ partial |
| **2** | WiFi baseline (3-device, radios off) | — | ⚠️ partial |
| **3** | SA Relay + RF→TAK bridge | 🔧 | 🧪 **next** |
| **4** | Ghost contacts + transit DM fixes | 🔧 | ⚠️ partial (4.1 ACK gating verified) |
| **5** | Reachability UI (WiFi / RF / Mesh) | 🚫 | 🚫 shelved |
| **5b** | Reachability backend (SA relay only) | 🔧 | 🧪 |
| **6** | WiFi contact keepalive (60s unicast SA) | 🔧 | ⬜ |
| **7** | MeshCore transport parity | 🔧 | ⚠️ partial (mesh-only) |
| **7b** | Mesh-only RF reliability (broadcast, ACK, ping) | ✅ | ⚠️ partial |
| **8** | Dual transport: UV-PRO + Mesh redundancy | 🔧 | 🧪 **next priority** |
| **9** | P1 outbound routing polish + ACK/contact fixes | 🔧 | ⬜ |
| **10** | P2 hardening (transport tags, dead code, registry split) | ⬜ | ⬜ |

---

## Phase 0 — Analysis & policy ✅

- [x] Multi-transport code review (`ChatBridge`, `CotBridge`, `PacketRouter`, toggles)
- [x] Transport policy confirmed (Q1=A exclusive send, Q2=A, Q3=registry on merge, Q4=one map marker)
- [x] Roadmap documented before implementation

---

## Phase 1 — P0: Contact merge & single map marker

**Goal:** One contact row and one map marker per callsign; merge on WiFi ingress; RF registry on merge.

### Implementation

- [x] `scheduleContactMergeForNetworkContact()` — debounced collapse on WiFi SA/chat
- [x] `finishContactMerge()` → `CotBridge.registerMergedContact()` + orphan cleanup
- [x] `injectPositionCotAtMapUid()` — position at canonical UID (WiFi wins)
- [x] Orphan `ANDROID-*` marker/contact removal when canonical differs
- [x] Collapse on UV-PRO connect, plugin init, RF GPS/chat
- [x] Collapse on **MeshCore** connect (`UVProMapComponent` listener)

### Field verification

- [x] Build installed on all three devices
- [ ] **1.1** WiFi-only: all three see each other; one row per callsign
- [ ] **1.2** J15 radio OFF: J25+SMKY still see J15 on WiFi; one marker
- [ ] **1.3** RF then WiFi: J25↔SMKY merge — no duplicate `ANDROID-*` marker when WiFi peer exists
- [ ] **1.4** Mesh connect triggers collapse (same as UV-PRO connect)

---

## Phase 2 — WiFi baseline (radios off)

**Setup:** All three — WiFi ON, UV-PRO radio OFF, MeshCore disconnected, SA Relay OFF.

- [ ] **2.1** Contacts list: exactly JESTER_25, SMOKEY_15, JESTER_15 (no ghosts)
- [ ] **2.2** J25 → SMKY WiFi DM delivers; SMKY → J25 reply *(N/A if SMKY WiFi off — use J25+J15 only for pure WiFi)*
- [ ] **2.3** J15 visible on map with fresh SA (multicast or keepalive)
- [ ] **2.4** No plugin RF injection when radios off

**Sub-test (J25 + J15 only):** WiFi DMs both directions; one contact row each.

---

## Phase 3 — SA Relay & bridge 🧪 **NEXT**

**Bridge device:** JESTER_25 only. Settings under **UV-PRO → SA Relay**.

| Setting | Default | Bridge use |
|---------|---------|------------|
| Enable SA Relay | OFF | **ON** on J25 |
| RF → TAK Uplink | OFF | **ON** on J25 |
| Restrict GeoChat to reachable peers | — | **Removed** (no UI toggle) |

### Implementation

- [x] SA Relay: network SA/markers/routes → RF broadcast (30s/UID throttle + signature dedupe)
- [x] SA Relay: **All Chat Rooms** GeoChat forwarded; **directed DMs not relayed**
- [x] SA Relay filter: skip WiFi-only peers (`shouldSaRelayNetworkSa` — TCP endpoint / opaque WiFi UID)
- [x] RF → TAK uplink: inbound RF CoT/chat → TAK network
- [x] `RfTakUplinkKeepalive` — rebroadcast known RF peer SA to TAK every 60s (first tick 5s)
- [x] `dispatchRfPeerSaToTakNetwork()` uses `CoTSendMethod.ANY` (multicast to LAN)
- [x] `ensureInboundNetworkSaContact()` — WiFi device registers relayed RF peer in Contacts (map + row)
- [x] `markRfNativeContact` / `markRfHeardCallsign` on RF GPS (`PacketRouter`)

### Field verification

- [ ] **3.1** SA Relay ON (J25): SMKY receives **RF-native** peer SA (J25 position on RF)
- [ ] **3.2** RF→TAK uplink ON: SMKY SA appears on **J15** map within ~5 min (keepalive + multicast)
- [ ] **3.3** J15 contact list shows **SMOKEY_15** (native chat icon — not red X)
- [ ] **3.4** J15 (WiFi-only) SA **not** rebroadcast to SMKY RF
- [ ] **3.5** All Chat Rooms from WiFi appears on SMKY RF
- [ ] **3.6** Directed WiFi DM J25→J15 **not** leaked to SMKY RF
- [ ] **3.7** J25 reachable from J15 via WiFi DM; J25 reachable from SMKY via RF DM
- [ ] **3.8** J15↔SMKY: chat may be attempted (native bubble) — **document whether delivery succeeds** (no UI block)

**Logs to watch:** `RF -> TAK broadcast uplink`, `RF→TAK uplink keepalive`, `ensureInboundNetworkSaContact`, `SA Relay skip WiFi-only`

---

## Phase 4 — Ghost contacts & transit DM fixes

**Goal:** No abbreviated ghost rows (e.g. `SMKY15`, `ANDROID-SMKY15`) from bridge transit traffic.

### Implementation

- [x] Inbound RF DM: transit check **before** `ensurePluginChatContact()`
- [x] `removeOrphanSyntheticRadioContacts()` on startup collapse
- [x] Inbound network GeoChat filter: ignore overheard DMs not for local device
- [x] Directed GeoChat not relayed over RF

### Field verification

- [x] **4.1** SMKY→J15 DM with J25 on mesh: J25 logs `Inbound DM ignored` + `Skipping DELIVERED ACK` — **no spurious bridge ACK** *(2026-05-30)*
- [ ] **4.1b** J25 does **not** spawn `SMKY15` / abbreviated ghost contact row for transit DM
- [ ] **4.2** J15→SMKY WiFi DM: J25 does **not** show/plugin-ingest conversation
- [ ] **4.3** J15↔J25 RF DMs still work when intended
- [ ] **4.4** After restart: orphan synthetics cleaned from contact list

---

## Phase 5 — Reachability UI 🚫 **SHELVED (2026-05-30)**

**Operator decision:** Remove position-only red X and GeoChat restriction. All contacts show native chat bubble; undelivered cross-transport messages are accepted.

### What was tried

- `ContactReachability` WiFi/RF path classification + `PositionOnlyConnector` red X
- Direct LAN IP ownership (`isDirectLanWifiPeer`) for J25 on J15
- Partial success: SMKY unreachable on J15 worked; J25↔J15 and false positives on RF/WiFi cross-view persisted

### Current code state

- [x] `isPolicyEnabled()` → always **false**
- [x] `applyContactCommsPolicy()` → restores native GeoChat; strips any legacy position-only connector
- [x] Preference + dropdown toggle **removed**
- [x] `UVProContactHandler` — no unreachable intercept; always opens chat
- [ ] Dead code retained: `PositionOnlyConnector`, `ContactConnectorIcons`, `preferPositionOnlyContactActionInternal` (cleanup optional in Phase 9)

### Field verification

- [x] **5.1** All contacts show chat bubble after restart (2026-05-30)
- [ ] **5.2** Re-open reachability UI only if operator requests (future phase)

---

## Phase 5b — Reachability backend (SA relay filter only) 🧪

**Goal:** Backend classification still prevents WiFi-only ghosts on RF relay — **no contact-list UI impact**.

- [x] `shouldSaRelayNetworkSa()` — skip peers with `contact@endpoint` or opaque WiFi UID
- [x] `isInboundNetworkGeoChatForLocalDevice()` — bridge overhear filter
- [x] `isLocalWifiAvailable()` — real WiFi transport check (ConnectivityManager)
- [ ] **5b.1** Confirm J15 SA never appears on SMKY RF when J25 SA Relay ON
- [ ] **5b.2** Confirm J25 SA **does** appear on SMKY RF

---

## Phase 6 — WiFi contact keepalive

**Goal:** Unicast mini self-SA every 60s to WiFi peers when WiFi up (no UI toggle).

### Implementation

- [x] `WifiContactKeepalive.java` + `CotBuilder.buildSelfWifiKeepaliveCot()`
- [x] Wired in `UVProMapComponent` start/stop

### Field verification

- [ ] **6.1** Logcat `UVPro.WifiKeepalive` every ~60s when WiFi up
- [ ] **6.2** Serverless peer stale time improves (120s stale on keepalive SA)
- [ ] **6.3** No keepalive when WiFi down

---

## Phase 7 — MeshCore transport parity

**Goal:** MeshCore is a first-class routing path (shared `PacketRouter`).

### Implementation

- [x] MeshCore BLE transport (`MeshBtConnectionManager`)
- [x] ATAK_DATA channel (slot 7) for CoT/chat envelopes
- [x] Beacon/ping follow active transport (UV-PRO preferred, else Mesh)
- [ ] Mesh reachability hook (deferred with Phase 5 UI)
- [ ] Inbound transport tagging (`routeIncoming(..., MESH)` vs UV-PRO) — Phase 9

### Field verification

- [x] **7.1** Three devices MeshCore connected, WiFi off: chat works SMKY↔J15 *(2026-05-30)*
- [ ] **7.2** Mesh disconnect → chat fails visibly (toast / retry / failed state)
- [ ] **7.3** Mesh reconnect → chat + ACK state recovers without restart
- [ ] **7.4** DM, All Chat Rooms, contact-targeted CoT, broadcast CoT on Mesh *(DM ✅; broadcast CoT ✅ code path; All Chat Rooms / contact-targeted CoT not re-run)*
- [ ] **7.5** UV-PRO + Mesh both connected: transmit toggle selects active manager

---

## Phase 7b — Mesh-only RF reliability ✅ / 🧪

**Goal:** Reliable map-item broadcast, GeoChat ACKs, and ping feedback on lossy mesh (WiFi off, all nodes on MeshCore).

**Session setup:** J15, J25, SMOKEY — MeshCore connected, WiFi off.

### Implementation (Darksteal `main`)

- [x] Map broadcast relay — `isRelayableMapCotType()` (`a-u-`, `a-h-`, `a-n-`, …), PreSend + CommsLogger + `ITEM_PERSIST`/`ITEM_SHARED` (`70a39ea`)
- [x] `markMapItemSendable()` / `refreshSendableMapItems()` on mesh connect
- [x] Transit DM: `injectRadioMessage()` returns false when RF dest ≠ local; `PacketRouter` gates DELIVERED ACK
- [x] RF chat ACK 5s delay (delivered + read) before TX — `ChatBridge.sendRadioChatAck()` (`15b108e`)
- [x] Ping toasts — `PingReplyNotifier` + dropdown Send Ping (`6c7cba4`)
- [x] Standalone **MeshCore ATAK plugin** v1.3.1 — same fixes + Send Ping (`TAK-MESHCORE` `eec5a9e`)

### Field verification — done

- [x] **7b.1** Drop point (e.g. `a-u-G`) → Broadcast reaches mesh peers when WiFi/TAK path is dead *(logs: `Relaying broadcast map CoT` after type fix)*
- [x] **7b.2** SMOKEY→J15 DM: J15 injects message; J25 does **not** send spurious DELIVERED ACK
- [x] **7b.3** SMOKEY→J15 DM: delivered + read ticks on sender after 5s ACK delay *(operator confirmed “perfect” post-restart)*

### Field verification — still needed

- [ ] **7b.4** All Chat Rooms over mesh (compact + full CoT if group sync)
- [ ] **7b.5** Contact-targeted map CoT (point/route send-to-contact) over mesh
- [ ] **7b.6** Ping: Send Ping toast → receive toast on peers → slotted reply toast on sender (3-node, full slot window)
- [ ] **7b.7** Outbound DM retry watchdog — no DELIVERED within retry interval retransmits (log: `Retry watchdog` / `retransmitting`)
- [ ] **7b.8** READ ACK only after opening conversation (not on notification alone)
- [ ] **7b.9** Encrypted mesh chat + ACK path (if encryption enabled in settings)
- [ ] **7b.10** Repeat 7b.2–7b.3 on **standalone MeshCore plugin** APK (not UV-PRO) — confirm parity

**Logs to watch:** `UVPro.ChatBridge` — `Scheduling radio chat ACK`, `RF chat ACK apply`, `Skipping DELIVERED ACK`, `Inbound DM ignored`; `UVPro.CotBridge` — `Relaying broadcast map CoT`, `Shared map item broadcast relay`; `UVPro.PingReply` — ping sent/reply toasts.

**Captured logs:** `test-logs/dm-ack-test-20260530-025853/` (pre-delay); post-fix retest logs not archived — capture on next run.

---

## Code audit — contact, mapping, ACK (2026-05-30)

Review focus: contacts + GeoChat receipts with **UV-PRO and Mesh both up**, toggling TX, WiFi secondary.

### Architecture (as implemented)

| Layer | Behavior |
|-------|----------|
| **RX** | One shared `PacketRouter` — both `BtConnectionManager` (UV-PRO) and `MeshBtConnectionManager` call `routeIncoming()` with **no transport tag** |
| **TX** | One active manager on `CotBridge` / `ChatBridge` via `setBtManager()` — selected by dropdown `resolveActiveTransmitManager()` (`meshTransmitEnabled` + connection fallback) |
| **Contacts** | One row per callsign goal via `collapseDuplicateContactsForCallsign`; RF registry in `btechContactUids` / `btechIdToUid` |
| **ACK map** | `outboundWireMidToLocalLineUid`: wire `messageId` → sender GeoChat line UID for RF `TYPE_ACK` → `injectGeoChatReceipt` |

### Confirmed bug / risk hypotheses (prioritize fixes in Phase 9)

| ID | Severity | Area | Symptom | Code evidence |
|----|----------|------|---------|---------------|
| **DT-1** | **High** | Dual TX | User selects Mesh TX; periodic beacon or mesh connect handler resets bridges to **UV-PRO** | `UVProMapComponent.sendBeaconIfConnected` ~1881–1883; mesh connect ~311–316 uses `resolveBeaconTransportManager()` (UV wins when both up) |
| **DT-2** | **High** | Dual TX | Manual **Send Beacon** may use stale `CotBridge.btManager` vs `resolveActiveTransmitManager()` | `sendManualBeacon` uses bridge manager; `sendPing` uses `activeTx` directly |
| **DT-3** | **Med** | Dual RX | Same CoT/chat frame on **both** transports → possible double inject (fragment reassembly has no cross-transport dedupe after complete) | `PacketRouter` single reassembler; no `routeIncoming(..., transport)` |
| **DT-4** | **Med** | ACK TX path | DELIVERED/READ ACK always leaves **active TX** manager, not the path that received the message | `sendRadioChatAck` → `ChatBridge.btManager`; peer may have sent on other link |
| **ACK-1** | **Med** | ACK map | `outboundWireMidToLocalLineUid` **not removed** on successful DELIVERED/READ; only on retry exhaustion / dispose | `handleIncomingRadioChatAck` applies receipt but keeps map entry |
| **ACK-2** | **Med** | ACK map | `normalizeLineUidForAck` may build line UID from **6-char wire room** (`SMKY15`) not peer `ANDROID-*` → ticks on wrong line | `sendChatOverRadio` passes `wireRoom` as room hint |
| **ACK-3** | **Med** | READ ACK | **Duplicate READ** possible: `drainAndSendReadAcks` + `markmessageread` both call `sendRadioChatAck(READ)` | `ChatBridge` ~1898, ~2097 |
| **ACK-4** | **Low** | READ ACK | READ not sent when conversation UID is **native WiFi keeper** not in `btechContactUids` | `drainAndSendReadAcks` requires `isBtechContactUid` |
| **ACK-5** | **Low** | ACK defer | Heavy RF traffic → `shouldDeferRfChatAck` drops ACK after 24×400ms | log: `Chat ACK deferred too long; dropping` |
| **CNT-1** | **Med** | Merge | `collapseDuplicateContactsForCallsign(from, syntheticUid)` +200 hint may prefer **abbreviated RF row** over native during inject | inject path ~347 |
| **CNT-2** | **Med** | Merge | After merge, **RF relay / READ drain** may not see native keeper UID in `btechContactUids` | `registerMergedContact` skips opaque WiFi UIDs |
| **OUT-1** | **Med** | Outbound | Same user send can hit **SEND_MESSAGE + PreSend + CommsLogger** → multiple wire mids / retries for one line | `handleOutgoingChat` vs PreSend; dedupe only 3s on `GeoChat.*` in compact relay |
| **OUT-2** | **Med** | Dual path | Sender RF pending not cancelled when peer got message on **other transport** (WiFi or alternate RF) | `noteInboundGeoChatDelivered(wireMid=0)` on network path |
| **GW-1** | **Med** | Gateway | `SEND_MESSAGE` gateway path may put full `ANDROID-*` in 6-byte wire room | `wrapGatewayMessage` + `rfRoom = toUid` in gateway branch |

### Design intent vs gaps (UV-PRO + Mesh as redundant pair)

**What works today:**

- Shared bridges (`ChatBridge`, `CotBridge`, `PacketRouter`) — same code path regardless of active transport (`.cursor/rules/transport-agnostic-routing.mdc`)
- Dropdown **Mesh transmit** / **UV-PRO transmit** toggles with fallback if preferred link down
- Inbound chat dedupe (`isDuplicateInboundChatDelivery`) helps when same line arrives WiFi + RF
- Transit DM gate prevents bridge ghost contacts / spurious DELIVERED ACK *(field verified mesh-only)*

**Gaps for redundancy:**

1. **No “send on both” or automatic failover TX** — exactly one active manager; no resend on alternate link if first fails
2. **Beacon / connect handlers fight user TX choice** (DT-1)
3. **No inbound transport tag** — cannot ACK on receive path or dedupe dual-RX intelligently
4. **Retry watchdog is RF-only** — does not know peer got message via mesh while UV-PRO send pending (OUT-2)
5. **`wifiTransmitEnabled` toggle is UI-only** — not wired to outbound block/allow

### Suggested redundancy model (operator + future code)

| Pattern | Operator action today | Ideal behavior (Phase 9+) |
|---------|----------------------|---------------------------|
| **Primary / backup** | Keep both connected; toggle TX to healthy link | Auto-failover TX on send failure; don’t reset TX on beacon tick |
| **Split roles** | UV-PRO for voice/APRS/beacon; Mesh for chat/CoT | Role-based TX routing (chat→mesh, beacon→UV-PRO) |
| **Dual hear, single speak** | Both RX on — acceptable duplicate hear if dedupe holds | Tag RX transport; suppress duplicate inject; ACK on **receive** transport |
| **Confirmation** | Wait for delivered tick + logs | Correlate ACK map with line UID from `COT_PLACED`; clear map on success |
| **Contact truth** | One callsign, one row | Merge keeper = chat thread UID; always register keeper in `btechContactUids` for RF READ/relay |

---

## Phase 8 — Dual transport: UV-PRO + Mesh redundancy 🧪 **NEXT PRIORITY**

**Goal:** Validate and harden operating with **both** transports connected, toggling TX, using one as backup for the other. WiFi may be on but is not the focus.

**Setup:** All three devices — UV-PRO radio connected **and** MeshCore BLE connected. WiFi optional.

### Field verification — transport selection

- [ ] **8.1** Both connected, default on open: UV-PRO TX selected (connection priority)
- [ ] **8.2** User toggles **Mesh transmit** while UV-PRO still connected → chat/CoT/ping use mesh (log active manager / `sendKissFrame` path)
- [ ] **8.3** Toggle back to UV-PRO TX → outbound uses UV-PRO without ATAK restart
- [ ] **8.4** **Regression DT-1:** After 8.2, wait for periodic beacon (~smart beacon interval) — confirm TX manager **stays on mesh** (currently expected **FAIL** → documents bug)
- [ ] **8.5** Disconnect mesh only → TX falls back to UV-PRO automatically
- [ ] **8.6** Disconnect UV-PRO only → TX falls back to mesh automatically
- [ ] **8.7** Manual Send Ping vs Send Beacon use **same** active transport (currently ping=activeTx, beacon=bridge — may **FAIL** DT-2)

### Field verification — redundancy scenarios

- [ ] **8.8** Send DM on UV-PRO TX; peer hears on UV-PRO → delivered tick on sender
- [ ] **8.9** Send DM on Mesh TX; peer hears on mesh → delivered tick on sender
- [ ] **8.10** Sender on mesh TX, peer only receiving on UV-PRO (or vice versa) — document delivery / tick behavior *(likely fail or one-way)*
- [ ] **8.11** Toggle TX mid-conversation — new messages use new path; no duplicate contact rows
- [ ] **8.12** Same DM not duplicated in UI when both links hear the frame *(dedupe)*

### Field verification — contacts + ACK under dual transport

- [ ] **8.13** One contact row per callsign with both transports up
- [ ] **8.14** Delivered + read ticks after send on **each** TX path separately
- [ ] **8.15** READ ACK not doubled on open chat *(watch for duplicate `Scheduling radio chat ACK kind=2`)*
- [ ] **8.16** No “Message Not Delivered” when message actually arrived on alternate transport *(OUT-2)*

**Logs:** `UVPro.UI` (TX route), `UVPro.ChatBridge` (ACK map, retry), `UVPro.CotBridge` (PreSend), `UVPro.Router` (chat mid), `UVPro.MeshBLE` + UV-PRO BT tags.

---

## Phase 9 — P1 fixes: routing, ACK, contacts ⬜

**Goal:** Close audit items needed for reliable UV-PRO ↔ Mesh marriage.

### Code fixes (proposed — not yet implemented)

- [ ] **9.1** DT-1: Beacon timer + mesh connect use `resolveActiveTransmitManager()`, not `resolveBeaconTransportManager()`, for `setBtManager`
- [ ] **9.2** DT-2: Align `sendManualBeacon` with `resolveActiveTransmitManager()` (match `sendPing`)
- [ ] **9.3** DT-4: Remember inbound transport per wire mid; send ACK on receive manager (or both if redundant ACK desired)
- [ ] **9.4** ACK-1: Remove `outboundWireMidToLocalLineUid` entry after successful receipt apply
- [ ] **9.5** ACK-2: Prefer `COT_PLACED` / full GeoChat line UID for ACK map; avoid wire-room-only normalization
- [ ] **9.6** ACK-3: Dedupe READ ACK scheduling (single path per mid)
- [ ] **9.7** CNT-2: Register merged **keeper** UID in `btechContactUids` for READ drain + relay
- [ ] **9.8** OUT-1: Extend outbound dedupe to `SEND_MESSAGE` path; widen beyond `GeoChat.*` prefix
- [ ] **9.9** OUT-2: Cancel `pendingOutboundChats` when inbound dedupe proves peer already has line (any transport)
- [ ] **9.10** Optional: auto-retry failed send on **alternate** connected transport once

### Field verification (after fixes)

- [ ] Re-run Phase 8 matrix
- [ ] Re-run Phase 7b ACK tests on dual-transport setup

---

## Phase 10 — P2: Polish & hardening ⬜

- [ ] Remove dead reachability UI code (`PositionOnlyConnector`, icons, prefs)
- [ ] Split `meshContactUids` vs `uvProContactUids` (optional — only if transport-specific policy needed)
- [ ] Inbound transport tagging on `PacketRouter.routeIncoming(..., Transport)`
- [ ] Dual-RX dedupe after fragment reassembly complete
- [ ] Wire `wifiTransmitEnabled` into outbound policy (when WiFi phase resumes)
- [ ] SA Relay → Mesh path policy when mesh is primary TX
- [ ] Regression suite documented after each release

---

## Phase 8 (legacy P1 label retired)

*Former “Phase 8 — P1 outbound routing” merged into Phase 8 (dual transport test) + Phase 9 (fixes) above.*

---

## Phase 9 (legacy P2 label retired)

*Former “Phase 9 — P2 polish” → **Phase 10** above.*

---

## Recommended test order (2026-05-30)

**Completed (mesh-only):** Phase 7b.1–7b.3 ✅

**Next priority — dual transport (UV-PRO + Mesh):**

1. **Deploy** latest `main` (`15b108e`+) → restart ATAK; connect **both** UV-PRO and Mesh on all devices  
2. **Phase 8** full matrix (transport toggles, redundancy, ACK/contacts) — **documents known DT-1/DT-2 failures**  
3. **Phase 7b** remainder (7b.4–7b.10) on dual-transport setup  
4. **Phase 9** implement + verify audit fixes  
5. **Phase 3** SA Relay + RF→TAK when WiFi bridge testing resumes  
6. **Phase 4–6, 5b, 10** as before  

---

## Key source files (for agents)

| Area | Files |
|------|--------|
| Reachability (backend) | `contacts/ContactReachability.java` |
| RF→TAK uplink | `network/RfTakUplinkKeepalive.java`, `cot/CotBridge.java` |
| Chat / merge | `chat/ChatBridge.java`, `chat/GeoChatContactListHelper.java` |
| CoT / SA Relay | `cot/CotBridge.java`, `cot/CotBuilder.java` |
| Routing | `protocol/PacketRouter.java` |
| Dual transport TX | `UVProDropDownReceiver.java` — `resolveActiveTransmitManager`, `applyActiveTransmitTransport` |
| Beacon TX override | `UVProMapComponent.java` — `sendBeaconIfConnected`, `resolveBeaconTransportManager` |
| Mesh | `bluetooth/MeshBtConnectionManager.java`, `bluetooth/BtConnectionManager.java` |
| Map broadcast / mesh relay | `cot/CotBridge.java` (`isRelayableMapCotType`, `maybeRelaySharedMapItem`) |
| RF chat ACK / DM gate | `chat/ChatBridge.java`, `protocol/PacketRouter.java` |
| Ping toasts | `protocol/PingReplyNotifier.java`, `UVProDropDownReceiver.java` |
| WiFi keepalive | `network/WifiContactKeepalive.java` |
| Lifecycle | `UVProMapComponent.java`, `UVProDropDownReceiver.java` |
| Contact tap | `UVProContactHandler.java` |

---

## Session notes

| Date | Note |
|------|------|
| 2026-05-29 | Reachability + mesh hook built; phased WiFi/SA Relay testing started |
| 2026-05-29 | User confirmed J15↔J25 DM leak fixed (prior SA Relay GeoChat change) |
| 2026-05-30 | RF→TAK uplink keepalive + `dispatchToBroadcast` fix; `ensureInboundNetworkSaContact` |
| 2026-05-30 | Reachability red-X UI **removed** per operator; native chat for all contacts |
| 2026-05-30 | Committed bridge/uplink/merge session work; Phase 3 field test is next |
| 2026-05-30 | **`70a39ea`** map broadcast relay (incl. `a-u-G`); mesh-only broadcast test passed |
| 2026-05-30 | **`6c7cba4`** ping toasts; **`15b108e`** 5s RF chat ACK delay — DM delivered+read verified SMKY↔J15 |
| 2026-05-30 | DM ACK test: J25 correctly skips transit DELIVERED; pre-delay ACK drops on mesh (RF contention) |
| 2026-05-30 | MeshCore standalone **v1.3.1** pushed (`TAK-MESHCORE`) with UV-PRO mesh parity + Send Ping |
| 2026-05-30 | Fleet paused on **mesh-only** (WiFi off); Phase 3 bridge tests deferred until mixed transport |
| 2026-05-30 | **Code audit:** dual-transport races (DT-1/2), ACK map/READ dupes, merge vs `btechContactUids` — see Phase 8–9 |
| 2026-05-30 | **Next field focus:** Phase 8 UV-PRO + Mesh both connected, redundancy toggling |

---

## Agent checklist (start of session)

- [ ] Read this file + `docs/UV-PRO-v1.9.51-AGENT-HANDOFF.md`
- [ ] Confirm `adb devices` and callsign ↔ serial mapping
- [ ] Confirm APK version on devices matches latest local build
- [ ] Update checkboxes in this file when tests pass/fail
- [ ] Log paths under `/home/paul/Documents/ATAK/test-logs/`
