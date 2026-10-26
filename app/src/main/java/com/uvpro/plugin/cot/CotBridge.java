package com.uvpro.plugin.cot;

import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.graphics.Bitmap;
import android.util.Log;

import android.os.Bundle;

import com.atakmap.android.chat.GeoChatService;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.ContactPresenceDropdown;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.PluginConnector;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.android.util.IconUtilities;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.icons.UserIcon;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.comms.CommsLogger;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.commoncommo.CoTSendMethod;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;

import com.uvpro.plugin.BuildConfig;
import com.uvpro.plugin.ax25.AprsSymbolMapper;
import com.uvpro.plugin.ax25.Ax25Frame;
import com.uvpro.plugin.beacon.SmartBeacon;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.bluetooth.MeshBtConnectionManager;
import com.uvpro.plugin.chat.ChatBridge;
import com.uvpro.plugin.crypto.EncryptionManager;
import com.uvpro.plugin.chat.GeoChatContactListHelper;
import com.uvpro.plugin.chat.GeoChatUnreadReconciler;
import com.uvpro.plugin.chat.InboundGroupSyncApplier;
import com.uvpro.plugin.protocol.RfSlottedCoTScheduler;
import com.uvpro.plugin.protocol.RfTxArbitrator;
import com.uvpro.plugin.protocol.PingReplyNotifier;
import com.uvpro.plugin.protocol.UVProPacket;
import com.uvpro.plugin.protocol.PacketFragmenter;
import com.uvpro.plugin.ui.SettingsFragment;
import com.uvpro.plugin.UVProContactHandler;
import com.uvpro.plugin.util.CallsignUtil;

import java.io.File;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bridges between ATAK CoT events and the radio link.
 *
 * Inbound (radio → ATAK):
 *   - Receives decoded data from PacketRouter
 *   - Builds CotEvent objects using CotBuilder
 *   - Dispatches them into ATAK's CoT processing pipeline
 *
 * Outbound (ATAK → radio):
 *   - Listens for local CoT events from ATAK
 *   - Compresses and fragments if needed
 *   - Sends via radio link
 */
public class CotBridge {

    private static final String TAG = "UVPro.CotBridge";

    /** Same action as ATAK radial menu {@code actions/showdetails.xml}. */
    public static final String ACTION_COT_MARKER_DETAILS =
            "com.atakmap.android.cotdetails.COTINFO";

    /** Set on map items created from inbound APRS position reports. */
    public static final String META_UVPRO_APRS = "uvpro_aprs";

    /** Multi-line APRS packet metadata for {@link com.uvpro.plugin.aprs.AprsDetailsDropDownReceiver}. */
    public static final String META_UVPRO_APRS_DETAILS = "uvpro_aprs_details";
    /** Marker flag for MeshCore repeater advert points (not APRS contacts/messages). */
    public static final String META_UVPRO_MESH_REPEATER = "uvpro_mesh_repeater";
    /** Marker flag for MeshCore node advert points. */
    public static final String META_UVPRO_MESH_NODE = "uvpro_mesh_node";
    /** Multi-line MeshCore details text for custom details panel. */
    public static final String META_UVPRO_MESH_DETAILS = "uvpro_mesh_details";
    private static final long STALE_GRACE_MS = 30_000L;
    private static final long MIN_CONTACT_STALE_MS = 60_000L;
    /** Inbound APRS / radio peers: ATAK uses CoT stale as marker TTL; keep ≥ 2h for sparse beacons. */
    private static final long MIN_INBOUND_RADIO_STALE_MS = 2 * 60 * 60_000L;

    private static final long COT_RETRY_INTERVAL_MS   = 15_000L;
    private static final int  COT_MAX_RETRIES          = 5;
    private static final long COT_DOUBLE_SEND_DELAY_MS = 3_000L;

    private static final class PendingOutboundCot {
        final String cotUid;
        final CotEvent event;
        volatile int retryCount;
        PendingOutboundCot(String cotUid, CotEvent event) {
            this.cotUid = cotUid;
            this.event  = event;
            this.retryCount = 0;
        }
    }

    private final ConcurrentHashMap<String, PendingOutboundCot> pendingOutboundCots =
            new ConcurrentHashMap<>();

    private final java.util.concurrent.ScheduledExecutorService cotRetryExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "UVPro-CotRetry");
                t.setDaemon(true);
                return t;
            });
    private final java.util.concurrent.atomic.AtomicInteger meshGpsDeferGeneration =
            new java.util.concurrent.atomic.AtomicInteger();

    private final Context pluginContext;
    private final MapView mapView;

    /** Set from UI thread once; BT read thread often cannot resolve {@link MapView#getDeviceUid()}. */
    private volatile String cachedLocalDeviceUidForGeoChat;
    private BtConnectionManager btManager;
    /** MeshCore BLE transport — used to send CoT ACKs back over the mesh. */
    private com.uvpro.plugin.bluetooth.MeshBtConnectionManager meshBtManager;
    private volatile String localCallsign = "OPENRL";
    private EncryptionManager encryptionManager;
    private CommsMapComponent.PreSendProcessor preSendProcessor;
    private BroadcastReceiver localCotBroadcastReceiver;
    private MapEventDispatcher.MapEventDispatchListener sharedMapItemListener;

    /** Catches outbound GeoChat: ATAK sends via CotMapComponent external dispatcher, not PreSend. */
    private CommsLogger outboundCommsLogger;

    /** Whether to relay all outgoing SA to radio (can flood the channel) */
    private boolean relayOutgoingSa = false;

    /**
     * UIDs that the plugin considers radio-transport contacts.
     * Populated when the plugin creates/registers contacts from radio packets.
     */
    private final Set<String> btechContactUids = ConcurrentHashMap.newKeySet();

    /** Peers observed on TAK network with their own {@code contact@endpoint} (direct Wi‑Fi). */
    private final Set<String> wifiNativeContactUids = ConcurrentHashMap.newKeySet();

    /** Peers observed from inbound RF position packets (direct radio net). */
    private final Set<String> rfNativeContactUids = ConcurrentHashMap.newKeySet();

    /** Callsigns heard on RF (covers UID alias rows for the same operator). */
    private final Set<String> rfHeardCallsigns = ConcurrentHashMap.newKeySet();

    /**
     * Map plugin-created display identifiers to contact UIDs.
     * Key is typically the normalized callsign (upper) or a known chat-room label.
     */
    private final Map<String, String> btechIdToUid = new ConcurrentHashMap<>();

    /**
     * Alternate wire UIDs (e.g. {@code ANDROID-DEV_ONE}) mapped to the merged keeper UID
     * (often opaque Wi‑Fi {@code ANDROID-…}) so RF routing still recognizes both forms.
     */
    private final Map<String, String> btechUidAliases = new ConcurrentHashMap<>();

    private final RfSlottedCoTScheduler slottedCoTScheduler = new RfSlottedCoTScheduler();

    /** Minimum interval between SA Relay broadcasts onto RF (WiFi/TAK → UV-PRO/Mesh). */
    private static final long SA_RELAY_RF_BROADCAST_INTERVAL_MS = 10 * 60_000L;
    private long lastSaRelay = 0;

    /** Per-UID throttle map for SA Relay to prevent channel flooding */
    private final Map<String, Long> saRelayLastSentByUid = new ConcurrentHashMap<>();
    /** Per-UID payload signature for SA Relay; unchanged payloads are suppressed. */
    private final Map<String, String> saRelayLastSignatureByUid = new ConcurrentHashMap<>();
    /** Short de-dupe window so PreSend + COT_PLACED do not double-transmit the same event. */
    private final Map<String, Long> recentLocalRelayKeys = new ConcurrentHashMap<>();
    private static final long LOCAL_RELAY_DEDUPE_MS = 1500L;
    /**
     * Net-wide map points (ATAK Auto Send): one RF chirp per marker at most this often.
     * WiFi/TAK rebroadcast is unchanged; only UV-PRO radio relay is throttled.
     */
    private static final long BROADCAST_MAP_RF_MIN_INTERVAL_MS = 120_000L;
    private final Map<String, Long> lastBroadcastMapRfRelayByUid = new ConcurrentHashMap<>();
    /** Avoid duplicate Wi‑Fi map CoT ACKs when logReceive fires more than once. */
    private final Map<String, Long> wifiCotAckSentUntil = new ConcurrentHashMap<>();
    private static final long WIFI_COT_ACK_DEDUPE_MS = 120_000L;
    private static final long INBOUND_CHAT_NOTIFY_DEDUPE_MS = 7000L;
    private static final int APRS_ICON_TARGET_PX = 52;
    /** Cache upscaled APRS icons by iconset path to avoid per-refresh bitmap work. */
    private final Map<String, Icon> aprsUpscaledIconCache = new ConcurrentHashMap<>();
    /** Avoid duplicate user alerts when redundant RF GeoChat CoT arrives. */
    private final Map<String, Long> inboundChatNotifyUntil = new ConcurrentHashMap<>();

    /**
     * Inbound network CoT types eligible for SA Relay (network → radio).
     * Matches friendly SA ({@code a-*-G…}), points/markers ({@code b-m-p…}), routes ({@code b-m-r…}).
     */
    private static final java.util.regex.Pattern SA_RELAY_TYPE_PATTERN =
            java.util.regex.Pattern.compile(
                    "^(a-[a-z]-G|b-m-p|b-m-r)");
    private static final Pattern COT_TIME_ATTR_PATTERN =
            Pattern.compile("\\s+(time|start|stale)=\"[^\"]*\"");
    private static final String ALL_CHAT_ROOMS = "All Chat Rooms";

    /**
     * Read SA Relay preference from plugin SharedPreferences.
     * Called on the radio-receive thread; SharedPreferences reads are thread-safe.
     */
    private boolean isSaRelayEnabled() {
        try {
            return com.uvpro.plugin.ui.SettingsFragment
                    .isSaRelayEnabled(pluginContext);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isRfToTakUplinkEnabled() {
        try {
            return isSaRelayEnabled() && com.uvpro.plugin.ui.SettingsFragment
                    .isRfToTakUplinkEnabled(pluginContext);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Injected inbound GeoChat (and similar) is re-processed by core comms; PreSendProcessor
     * then sees the same b-t-f with toUIDs pointing at a BTECH contact and would re-transmit
     * over RF (echo loop, duplicate fragments, unknown receipt UIDs).
     */
    private static final long INBOUND_INJECT_NO_RELAY_MS = 10_000L;
    private final Map<String, Long> inboundInjectNoRelayUntil = new ConcurrentHashMap<>();

    /** Set after {@link ChatBridge} construction; used to send compact TYPE_CHAT with wire ACK ids. */
    private volatile ChatBridge chatBridge;
    private volatile com.uvpro.plugin.protocol.PacketRouter packetRouter;
    private volatile boolean wifiTransmitEnabled = false;

    private void markInboundInjectSkipOutboundRelay(String cotUid) {
        if (cotUid == null || cotUid.isEmpty()) return;
        inboundInjectNoRelayUntil.put(cotUid,
                System.currentTimeMillis() + INBOUND_INJECT_NO_RELAY_MS);
    }

    private boolean shouldSkipOutboundRelayWasInboundInject(String cotUid) {
        if (cotUid == null) return false;
        Long until = inboundInjectNoRelayUntil.get(cotUid);
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            inboundInjectNoRelayUntil.remove(cotUid);
            return false;
        }
        return true;
    }

    public CotBridge(Context pluginContext, MapView mapView) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
    }

    public void setBtManager(BtConnectionManager btManager) {
        this.btManager = btManager;
    }

    /** Active transmit manager (Mesh or UV-PRO per UI toggle). */
    public BtConnectionManager getActiveBtManager() {
        return btManager;
    }

    public void setMeshBtManager(com.uvpro.plugin.bluetooth.MeshBtConnectionManager m) {
        this.meshBtManager = m;
    }

    private String localCallsign() {
        try {
            com.atakmap.android.maps.PointMapItem self = mapView.getSelfMarker();
            if (self != null) {
                String live = self.getMetaString("callsign", null);
                if (live != null && !live.trim().isEmpty()
                        && !"UNKNOWN".equalsIgnoreCase(live.trim())) {
                    localCallsign = live.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return localCallsign;
    }

    public void setLocalCallsign(String callsign) {
        this.localCallsign = callsign;
    }

    /**
     * Snapshot local ATAK device/self-marker UID on the UI thread for GeoChat {@code chatgrp} uid1
     * when injecting inbound chat from Bluetooth RX (background thread).
     */
    public void refreshCachedLocalDeviceUidForGeoChat() {
        String u = tryResolveAtakSelfUidForChatGrp(mapView);
        if (u != null) {
            cachedLocalDeviceUidForGeoChat = u;
            Log.d(TAG, "Cached local ATAK UID for inbound GeoChat DMs: " + u);
        }
    }

    public void setRelayOutgoingSa(boolean relay) {
        this.relayOutgoingSa = relay;
    }

    public void setEncryptionManager(EncryptionManager em) {
        this.encryptionManager = em;
    }

    public void setChatBridge(ChatBridge chatBridge) {
        this.chatBridge = chatBridge;
    }

    public void setPacketRouter(com.uvpro.plugin.protocol.PacketRouter packetRouter) {
        this.packetRouter = packetRouter;
    }

    public void setWifiTransmitEnabled(boolean enabled) {
        wifiTransmitEnabled = enabled;
    }

    public boolean isWifiTransmitEnabled() {
        return wifiTransmitEnabled;
    }

    /** TAK/Wi-Fi ping only when ATAK WiFi transmit is explicitly enabled. */
    public boolean canSendPingOverWifiNetwork() {
        if (!isTakNetworkAvailable() || !wifiTransmitEnabled) {
            return false;
        }
        return true;
    }

    private boolean isTakNetworkAvailable() {
        try {
            String endpoint = CotMapComponent.getEndpoint();
            return endpoint != null && !endpoint.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Broadcast or directed ping over TAK/Wi‑Fi when RF transports are unavailable.
     *
     * @param targetCallsign ATAK callsign for directed ping, or null/empty for broadcast
     */
    public boolean sendPingOverWifiNetwork(String targetCallsign) {
        if (!canSendPingOverWifiNetwork()) {
            return false;
        }
        CotEvent event = CotBuilder.buildWifiPingCot(mapView, targetCallsign);
        if (event == null || !event.isValid()) {
            Log.w(TAG, "WiFi ping CoT build failed");
            return false;
        }
        try {
            com.atakmap.comms.CotDispatcher dispatcher =
                    CotMapComponent.getExternalDispatcher();
            if (dispatcher == null) {
                return false;
            }
            String target = targetCallsign != null ? targetCallsign.trim() : "";
            if (!target.isEmpty()) {
                IndividualContact peer = findWifiPingContact(target);
                if (peer != null) {
                    dispatcher.dispatchToContact(
                            event, peer, CoTSendMethod.POINT_TO_POINT);
                    Log.i(TAG, "WiFi directed ping sent to " + target);
                } else {
                    dispatcher.dispatchToBroadcast(event, CoTSendMethod.ANY);
                    Log.i(TAG, "WiFi ping broadcast (no contact for " + target + ")");
                }
            } else {
                dispatcher.dispatchToBroadcast(event, CoTSendMethod.ANY);
                Log.i(TAG, "WiFi ping broadcast");
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "WiFi ping send failed: " + e.getMessage());
            return false;
        }
    }

    /** Slotted ping reply over TAK/Wi‑Fi — overrides WiFi transmit toggle (same-transport reply). */
    public boolean sendSelfPositionOverWifiNetwork() {
        if (!isTakNetworkAvailable()) {
            return false;
        }
        try {
            CotEvent event = CotBuilder.buildWifiPingReplyCot(mapView);
            if (event == null || !event.isValid()) {
                return false;
            }
            com.atakmap.comms.CotDispatcher dispatcher =
                    CotMapComponent.getExternalDispatcher();
            if (dispatcher == null) {
                return false;
            }
            dispatcher.dispatchToBroadcast(event, CoTSendMethod.ANY);
            Log.i(TAG, "WiFi ping reply (position SA) broadcast");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "WiFi ping reply failed: " + e.getMessage());
            return false;
        }
    }

    private IndividualContact findWifiPingContact(String targetCallsign) {
        if (targetCallsign == null || targetCallsign.trim().isEmpty()) {
            return null;
        }
        try {
            Contacts contacts = Contacts.getInstance();
            Contact match = ChatBridge.findContactByCallsignVariants(
                    contacts, targetCallsign.trim());
            if (match instanceof IndividualContact) {
                return (IndividualContact) match;
            }
        } catch (Exception e) {
            Log.w(TAG, "findWifiPingContact failed", e);
        }
        return null;
    }

    private void maybeHandleInboundNetworkWifiPing(CotEvent event) {
        if (!CotBuilder.isWifiPingCot(event)) {
            return;
        }
        if (packetRouter == null) {
            return;
        }
        String localUid = null;
        try {
            localUid = MapView.getDeviceUid();
        } catch (Exception ignored) {
        }
        String eventUid = event.getUID();
        if (localUid != null && eventUid != null
                && localUid.trim().equalsIgnoreCase(eventUid.trim())) {
            return;
        }
        packetRouter.handleInboundWifiPing(event);
    }

    /**
     * Register a contact UID as a UV-PRO radio endpoint.
     * This is used to route ATAK "send to contact" actions to the radio link
     * without globally relaying all CoT.
     */
    public void registerBtechContactUid(String uid) {
        if (uid == null) return;
        btechContactUids.add(uid.trim());
    }

    /**
     * Register a secondary UID (typically synthetic {@code ANDROID-<CALLSIGN>}) that routes
     * to the merged keeper without creating a second Contacts row.
     */
    public void registerBtechContactUidAlias(String aliasUid, String canonicalUid) {
        if (aliasUid == null || canonicalUid == null) {
            return;
        }
        String alias = aliasUid.trim();
        String canonical = canonicalUid.trim();
        if (alias.isEmpty() || canonical.isEmpty() || alias.equalsIgnoreCase(canonical)) {
            return;
        }
        btechUidAliases.put(alias.toUpperCase(Locale.US), canonical);
        btechContactUids.add(alias);
    }

    /**
     * Resolve a wire UID to the merged keeper when an alias was registered during contact merge.
     */
    public String resolveBtechCanonicalUid(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return null;
        }
        String trimmed = uid.trim();
        String alias = btechUidAliases.get(trimmed.toUpperCase(Locale.US));
        return alias != null ? alias : trimmed;
    }

    /**
     * Register a plugin-created BTECH contact identifier (e.g. callsign) → UID mapping.
     * Used for routing GeoChat messages where ATAK does not provide explicit toUIDs.
     */
    public void registerBtechContactId(String id, String uid) {
        if (id == null || uid == null) return;
        String key = id.trim().toUpperCase();
        if (key.isEmpty()) return;
        btechIdToUid.put(key, uid);
        registerBtechContactUid(uid);
    }

    /**
     * Best ATAK callsign alias registered for a specific contact UID (reverse of
     * {@link #registerBtechContactId}). Prefers full callsign forms over compact wire keys
     * so directed pings target the intended peer, not a colliding 6-character alias.
     */
    public String resolveRegisteredCallsignForUid(String uid, String preferredNameHint) {
        if (uid == null || uid.trim().isEmpty()) {
            return null;
        }
        String uidKey = uid.trim();
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> e : btechIdToUid.entrySet()) {
            if (e == null || e.getKey() == null || e.getValue() == null) {
                continue;
            }
            if (uidKey.equalsIgnoreCase(e.getValue().trim())) {
                keys.add(e.getKey());
            }
        }
        if (keys.isEmpty()) {
            return null;
        }
        if (preferredNameHint != null && !preferredNameHint.trim().isEmpty()) {
            String hint = preferredNameHint.trim();
            String hintUpper = hint.toUpperCase(Locale.US);
            String hintFlat = hintUpper.replace("_", "").replace("-", "");
            for (String key : keys) {
                if (key.equalsIgnoreCase(hint)) {
                    return key;
                }
                String keyFlat = key.replace("_", "").replace("-", "");
                if (keyFlat.equalsIgnoreCase(hintFlat)) {
                    return key;
                }
            }
        }
        String best = keys.get(0);
        int bestScore = scoreRegisteredCallsignKey(best);
        for (int i = 1; i < keys.size(); i++) {
            String candidate = keys.get(i);
            int score = scoreRegisteredCallsignKey(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static int scoreRegisteredCallsignKey(String key) {
        if (key == null || key.isEmpty()) {
            return 0;
        }
        int score = key.length();
        if (key.indexOf('_') >= 0 || key.indexOf('-') >= 0) {
            score += 1000;
        }
        if (key.length() > 6) {
            score += 500;
        }
        return score;
    }

    /** True for opaque Wi-Fi device ids ({@code ANDROID-b726a98286ca1d08} suffix). */
    public static boolean isOpaqueDeviceUid(String value) {
        return isOpaqueDeviceId(value);
    }

    /**
     * After contact-list merge, register the keeper for RF routing and alias lookup.
     */
    public void registerMergedContact(IndividualContact contact) {
        if (contact == null) {
            return;
        }
        String uid = contact.getUID();
        if (uid == null || uid.trim().isEmpty()) {
            return;
        }
        String bareUid = null;
        if (uid.toUpperCase(Locale.US).startsWith(ANDROID_UID_PREFIX)) {
            bareUid = uid.substring(ANDROID_UID_PREFIX.length());
        }
        String keeperUid = uid.trim();
        // Opaque Wi-Fi device UIDs are map/chat identities, not RF wire UIDs — register
        // callsign aliases and synthetic ANDROID-<CALLSIGN> forms instead.
        if (bareUid == null || !isOpaqueDeviceId(bareUid)) {
            registerBtechContactUid(keeperUid);
        }
        String name = contact.getName();
        if (name != null && !name.trim().isEmpty()) {
            String normalized = name.trim().toUpperCase(Locale.US);
            registerBtechContactId(normalized, keeperUid);
            String syntheticUid = ANDROID_UID_PREFIX + normalized;
            if (!syntheticUid.equalsIgnoreCase(keeperUid)) {
                registerBtechContactUidAlias(syntheticUid, keeperUid);
            }
            String radio = com.uvpro.plugin.util.CallsignUtil.toRadioCallsign(normalized);
            if (radio != null && !radio.trim().isEmpty()
                    && !radio.equalsIgnoreCase(normalized)) {
                registerBtechContactId(radio.trim(), keeperUid);
            }
        }
        if (bareUid != null && !bareUid.isEmpty() && !isOpaqueDeviceId(bareUid)) {
            registerBtechContactId(bareUid, keeperUid);
        }
        if (name != null && !name.trim().isEmpty() && isRfHeardCallsign(name.trim())) {
            markRfNativeContact(keeperUid);
        }
    }

    /**
     * Remove synthetic {@code ANDROID-<CALLSIGN>} map marker when canonical UID differs.
     */
    public void removeOrphanRfMapMarkerForCallsign(String callsignRaw, String canonicalUid) {
        if (callsignRaw == null || canonicalUid == null) {
            return;
        }
        String normalized = callsignRaw.trim().toUpperCase(Locale.US);
        if (normalized.isEmpty()) {
            return;
        }
        String syntheticUid = ANDROID_UID_PREFIX + normalized;
        if (syntheticUid.equalsIgnoreCase(canonicalUid.trim())) {
            return;
        }
        MapView mv = mapView;
        if (mv == null) {
            return;
        }
        Runnable work = () -> {
            try {
                com.atakmap.android.maps.MapItem orphan =
                        mv.getRootGroup().deepFindUID(syntheticUid);
                if (orphan != null && orphan.getGroup() != null) {
                    orphan.getGroup().removeItem(orphan);
                    Log.d(TAG, "Removed orphan RF map marker uid=" + syntheticUid
                            + " canonical=" + canonicalUid);
                }
                Contact dup = Contacts.getInstance().getContactByUuid(syntheticUid);
                if (dup != null && !canonicalUid.equalsIgnoreCase(dup.getUID())) {
                    Contacts.getInstance().removeContact(dup);
                    btechContactUids.remove(syntheticUid);
                    btechIdToUid.entrySet().removeIf(e -> syntheticUid.equals(e.getValue()));
                }
            } catch (Exception e) {
                Log.w(TAG, "removeOrphanRfMapMarkerForCallsign failed callsign=" + normalized, e);
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            work.run();
        } else {
            mv.post(work);
        }
    }

    public boolean isBtechContactUid(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return false;
        }
        String trimmed = uid.trim();
        if (btechContactUids.contains(trimmed)) {
            return true;
        }
        String canonical = btechUidAliases.get(trimmed.toUpperCase(Locale.US));
        return canonical != null && btechContactUids.contains(canonical);
    }

    public boolean isWifiNativeContact(String uid) {
        return uid != null && wifiNativeContactUids.contains(uid.trim());
    }

    public boolean isRfNativeContact(String uid) {
        return uid != null && rfNativeContactUids.contains(uid.trim());
    }

    public boolean isRfHeardCallsign(String callsign) {
        if (callsign == null || callsign.trim().isEmpty()) {
            return false;
        }
        String key = callsign.trim().toUpperCase(Locale.US);
        if (rfHeardCallsigns.contains(key)) {
            return true;
        }
        String compact = compactRoutingKey(key);
        return !compact.isEmpty() && rfHeardCallsigns.contains(compact);
    }

    public void markWifiNativeContact(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return;
        }
        String trimmed = uid.trim();
        if (wifiNativeContactUids.add(trimmed)) {
            Log.d(TAG, "WiFi-native contact uid=" + trimmed);
            scheduleReachabilityRefreshForUid(trimmed);
        }
    }

    public void markRfNativeContact(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return;
        }
        String trimmed = uid.trim();
        if (rfNativeContactUids.add(trimmed)) {
            Log.d(TAG, "RF-native contact uid=" + trimmed);
            scheduleReachabilityRefreshForUid(trimmed);
        }
    }

    public void markRfHeardCallsign(String callsign) {
        if (callsign == null || callsign.trim().isEmpty()) {
            return;
        }
        String key = callsign.trim().toUpperCase(Locale.US);
        if (rfHeardCallsigns.add(key)) {
            Log.d(TAG, "RF-heard callsign=" + key);
            scheduleReachabilityRefreshForCallsign(key);
        }
        String compact = compactRoutingKey(key);
        if (!compact.isEmpty()) {
            rfHeardCallsigns.add(compact);
        }
    }

    private void scheduleReachabilityRefreshForCallsign(String callsign) {
        if (mapView == null || callsign == null || callsign.trim().isEmpty()) {
            return;
        }
        mapView.post(() -> {
            try {
                Contact match = com.uvpro.plugin.chat.ChatBridge.findContactByCallsignVariants(
                        Contacts.getInstance(), callsign);
                if (match instanceof IndividualContact) {
                    com.uvpro.plugin.contacts.ContactReachability.applyContactCommsPolicy(
                            (IndividualContact) match, this);
                }
            } catch (Exception e) {
                Log.d(TAG, "Reachability refresh failed callsign=" + callsign, e);
            }
        });
    }

    private void scheduleReachabilityRefreshForUid(String uid) {
        if (mapView == null || uid == null || uid.trim().isEmpty()) {
            return;
        }
        mapView.post(() -> {
            try {
                Contact c = Contacts.getInstance().getContactByUuid(uid.trim());
                if (c instanceof IndividualContact) {
                    com.uvpro.plugin.contacts.ContactReachability.applyContactCommsPolicy(
                            (IndividualContact) c, this);
                }
            } catch (Exception e) {
                Log.d(TAG, "Reachability refresh failed uid=" + uid, e);
            }
        });
    }

    public boolean isRadioConnected() {
        return btManager != null && btManager.isConnected();
    }

    private static boolean isRelayableMapCotType(String type) {
        if (type == null || type.isEmpty()) {
            return false;
        }
        // 2525 markers (a-*-G…), map points/routes, user-defined shapes, CASEVAC, deletes.
        return type.startsWith("b-m-p")
                || type.startsWith("b-m-r")
                || type.startsWith("a-f-")
                || type.startsWith("a-h-")
                || type.startsWith("a-n-")
                || type.startsWith("a-u-")
                || type.startsWith("a-p-")
                || type.startsWith("u-")
                || type.startsWith("b-r-f-h")
                || type.startsWith("t-x-d-d");
    }

    /** Periodic self-SA beacons — not user-initiated map-item broadcasts. */
    private boolean isSelfPliBeacon(CotEvent event) {
        if (event == null) {
            return false;
        }
        String type = event.getType();
        if (type == null || !type.startsWith("a-f-")) {
            return false;
        }
        if (type.contains("-U-C")) {
            return true;
        }
        String uid = event.getUID();
        if (uid == null || uid.isEmpty()) {
            return false;
        }
        String localUid = cachedLocalDeviceUidForGeoChat;
        if (localUid == null || localUid.isEmpty()) {
            try {
                localUid = MapView.getDeviceUid();
            } catch (Exception ignored) {
            }
        }
        return uid.equals(localUid);
    }

    private boolean shouldRelayBroadcastMapCot(CotEvent event, String[] toUIDs) {
        if (event == null) {
            return false;
        }
        if (toUIDs != null && toUIDs.length > 0) {
            return false;
        }
        String type = event.getType();
        if (!isRelayableMapCotType(type)) {
            return false;
        }
        return !isSelfPliBeacon(event);
    }

    private boolean isInboundDirectedRfCotForThisDevice(CotEvent event) {
        String self = cachedLocalDeviceUidForGeoChat;
        if (self == null || self.trim().isEmpty()) {
            try {
                self = MapView.getDeviceUid();
            } catch (Exception ignored) {
            }
        }
        if (CotBuilder.isDirectedCotForLocalDevice(event, self, this::resolveBtechUidForId)) {
            return true;
        }
        // ATAK send-to-contact uses ANDROID-{callsign} labels; btechIdToUid only maps remotes.
        for (String dest : CotBuilder.extractDirectedDestUids(event)) {
            if (directedDestLooksLikeSelf(dest)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a directed CoT {@code __dest} label refers to this device by callsign
     * (e.g. {@code ANDROID-SMOKEY_15}) rather than opaque {@code ANDROID-{hex}} UID.
     */
    private boolean directedDestLooksLikeSelf(String dest) {
        if (dest == null || dest.trim().isEmpty() || localCallsign() == null) {
            return false;
        }
        String r = dest.trim();
        String loc = localCallsign().trim();
        if (loc.isEmpty()) {
            return false;
        }
        if (CallsignUtil.isSameCallsignAlias(loc, r)) {
            return true;
        }
        try {
            if (mapView != null) {
                com.atakmap.android.maps.PointMapItem selfMarker = mapView.getSelfMarker();
                if (selfMarker != null) {
                    String m = selfMarker.getMetaString("callsign", null);
                    if (m != null && CallsignUtil.isSameCallsignAlias(m.trim(), r)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void relayContactTargetedCotOverRadio(CotEvent event, String[] toUIDs) {
        new Thread(() -> {
            CotEvent rfEvent = (toUIDs != null && toUIDs.length > 0)
                    ? CotBuilder.stampDirectedDestinations(event, toUIDs)
                    : event;
            sendCotOverRadio(rfEvent != null ? rfEvent : event);
        }).start();
    }

    /**
     * Contact-targeted map CoT (point/route/marker send-to-contact) when at least one
     * destination is RF-reachable. WiFi/TAK-only recipients rely on ATAK core unicast.
     */
    private boolean shouldRelayMapCotToContactUids(String[] toUIDs) {
        return com.uvpro.plugin.contacts.ContactReachability
                .hasAnyRfReachableMapRecipient(toUIDs, this);
    }

    /** No RF peer can ACK when all directed recipients are WiFi/TAK-only. */
    private boolean shouldRegisterCotRfRetry(CotEvent event) {
        java.util.List<String> destUids = CotBuilder.extractDirectedDestUids(event);
        if (!destUids.isEmpty()) {
            return shouldRelayMapCotToContactUids(
                    destUids.toArray(new String[0]));
        }
        return true;
    }

    private void cancelPendingCotRfRetry(String cotUid) {
        if (cotUid == null || cotUid.trim().isEmpty()) {
            return;
        }
        PendingOutboundCot removed = pendingOutboundCots.remove(cotUid.trim());
        if (removed != null) {
            Log.d(TAG, "Cancelled pending CoT RF retry uid=" + cotUid.trim());
        }
    }

    private static final String ANDROID_UID_PREFIX = "ANDROID-";
    private static final long GROUP_CONTACT_COT_REDUNDANT_TX_DELAY_MS = 1200L;
    private static final Pattern OPAQUE_DEVICE_ID_HEX16 =
            Pattern.compile("^[0-9A-F]{16}$", Pattern.CASE_INSENSITIVE);

    /**
     * ATAK GeoChat/direct destinations sometimes use the literal contact UID label
     * (e.g. ANDROID-VETTE1); registration keys are typically the bare callsign
     * (VETTE1). Normalizes for routing-map lookup.
     */
    private static String normalizeBtechRoutingId(String id) {
        if (id == null) return "";
        String key = id.trim().toUpperCase();
        if (key.startsWith(ANDROID_UID_PREFIX)) {
            key = key.substring(ANDROID_UID_PREFIX.length());
        }
        return key;
    }

    private static boolean isOpaqueDeviceId(String value) {
        String normalized = normalizeBtechRoutingId(value);
        if (normalized.isEmpty()) {
            return false;
        }
        return OPAQUE_DEVICE_ID_HEX16.matcher(normalized).matches();
    }

    /**
     * Mesh chat sender IDs are often 6-char compressed forms (e.g. SMKY15, JSTR15).
     * Build a compact consonant+digit key so they can map back to full callsigns
     * already registered from GPS/contacts (e.g. SMOKEY_15, JESTER_15).
     */
    private static String compactRoutingKey(String id) {
        String normalized = normalizeBtechRoutingId(id);
        if (normalized.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                if (ch == 'A' || ch == 'E' || ch == 'I' || ch == 'O' || ch == 'U') {
                    continue;
                }
                out.append(ch);
            } else if (ch >= '0' && ch <= '9') {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /**
     * Resolve a chat destination label/callsign to a BTECH contact UID, if known.
     */
    public String resolveBtechUidForId(String id) {
        if (id == null) return null;
        String key = id.trim().toUpperCase();
        if (key.isEmpty()) return null;
        if (key.startsWith(ANDROID_UID_PREFIX)) {
            String aliasCanonical = btechUidAliases.get(key);
            if (aliasCanonical != null) {
                return aliasCanonical;
            }
        }
        if (isOpaqueDeviceId(key)) return null;
        String mapped = btechIdToUid.get(key);
        if (mapped != null) return mapped;
        String stripped = normalizeBtechRoutingId(id);
        if (!stripped.isEmpty() && !stripped.equals(key)) {
            mapped = btechIdToUid.get(stripped);
            if (mapped != null) return mapped;
        }
        // Fallback for compact sender IDs from RF chat payloads (SMKY15/JSTR15).
        String compact = compactRoutingKey(id);
        if (!compact.isEmpty() && compact.length() >= 4) {
            for (Map.Entry<String, String> e : btechIdToUid.entrySet()) {
                if (e == null || e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                String existingCompact = compactRoutingKey(e.getKey());
                if (compact.equals(existingCompact)) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    /**
     * True if an outbound GeoChat/send intent targets a plugin-registered radio
     * contact, using UID, chat-room label, or ATAK ANDROID-* display identifiers.
     */
    public boolean isBtechOutboundChatDestination(String uid, String chatroom) {
        if (uid != null) {
            String u = uid.trim();
            if (!u.isEmpty() && isBtechContactUid(u)) return true;
            String resolvedUid = resolveBtechUidForId(u);
            if (resolvedUid != null && isBtechContactUid(resolvedUid)) return true;
        }
        if (chatroom != null && !chatroom.isEmpty()) {
            if ("ALL CHAT ROOMS".equalsIgnoreCase(chatroom.trim())) return false;
            String resolvedRm = resolveBtechUidForId(chatroom);
            if (resolvedRm != null && isBtechContactUid(resolvedRm)) return true;
        }
        return false;
    }

    /**
     * Decide whether an ATAK outbound event (from broadcast/intent) should be
     * relayed to the radio based on its destination UIDs.
     *
     * Many ATAK broadcasts include a `toUIDs` extra. If that list intersects
     * with our plugin-created contact UIDs, we treat it as a radio-targeted send.
     */
    public boolean shouldRelayToRadio(Intent intent) {
        if (intent == null) return false;
        try {
            java.util.ArrayList<String> toUIDs =
                    intent.getStringArrayListExtra("toUIDs");
            if (toUIDs == null || toUIDs.isEmpty()) return false;
            for (String uid : toUIDs) {
                if (uid != null && btechContactUids.contains(uid)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Decide whether an outbound GeoChat CoT event should be relayed to radio.
     *
     * ATAK GeoChat CoT varies by release: {@code __chat} vs {@code chat}, and
     * destination may appear under {@code chatgrp}, {@code chatroom}, or only in
     * the serialized XML. We combine structured parsing with a containment check
     * against registered radio-contact UIDs.
     */
    public boolean shouldRelayGeoChatToRadio(CotEvent event) {
        if (event == null) return false;
        try {
            if (!"b-t-f".equals(event.getType())) return false;
            com.atakmap.coremap.cot.event.CotDetail detail = event.getDetail();
            if (detail == null) return false;

            com.atakmap.coremap.cot.event.CotDetail chat =
                    detail.getFirstChildByName(0, "__chat");
            if (chat == null) {
                chat = detail.getFirstChildByName(0, "chat");
            }
            // "All Chat Rooms" is a deliberate broadcast chat intent. Relay it from this device
            // regardless of specific contact UIDs.
            if (chat != null) {
                String room = chat.getAttribute("chatroom");
                if (room != null && ALL_CHAT_ROOMS.equalsIgnoreCase(room.trim())
                        && isLikelyLocalGeoChatSender(event)) {
                    return true;
                }
            }
            if (chat != null && geoChatDetailTargetsBtechContact(chat, detail)) {
                return true;
            }

            // Some ATAK layouts omit renameable wrappers; probe full serialization.
            if (geoChatXmlReferencesRegisteredBtechContact(event)) {
                Log.d(TAG, "GeoChat relay: matched BTECH UID via CoT substring probe");
                return true;
            }

            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isLikelyLocalGeoChatSender(CotEvent event) {
        if (event == null) {
            return false;
        }
        String senderUid = GeoChatContactListHelper.extractChatSenderUid(event);
        String localUid = null;
        try {
            localUid = MapView.getDeviceUid();
        } catch (Exception ignored) {
        }
        if (localUid != null && !localUid.isEmpty()
                && senderUid != null && senderUid.equalsIgnoreCase(localUid)) {
            return true;
        }
        String senderCallsign = GeoChatContactListHelper.extractChatSenderCallsign(event);
        if (senderCallsign != null && localCallsign() != null
                && senderCallsign.trim().equalsIgnoreCase(localCallsign().trim())) {
            return true;
        }
        return false;
    }

    private boolean geoChatDetailTargetsBtechContact(
            com.atakmap.coremap.cot.event.CotDetail chat,
            com.atakmap.coremap.cot.event.CotDetail detail) {

        com.atakmap.coremap.cot.event.CotDetail chatgrp =
                chat.getFirstChildByName(0, "chatgrp");
        if (chatgrp != null) {
            String uid0 = chatgrp.getAttribute("uid0");
            String uid1 = chatgrp.getAttribute("uid1");
            if (isBtechContactUid(uid0) || isBtechContactUid(uid1)) {
                return true;
            }
        }

        for (String attr : new String[] {"chatroom", "id", "destination", "recipient"}) {
            String chatRoom = chat.getAttribute(attr);
            String uidFromRoom = resolveBtechUidForId(chatRoom);
            if (isBtechContactUid(uidFromRoom)) return true;
        }

        com.atakmap.coremap.cot.event.CotDetail remarks =
                detail.getFirstChildByName(0, "remarks");
        if (remarks != null) {
            String to = remarks.getAttribute("to");
            String uidFromTo = resolveBtechUidForId(to);
            if (isBtechContactUid(uidFromTo)) return true;
        }
        return false;
    }

    /**
     * Last-resort matcher for outbound b-t-f when structured {@code __chat} is absent
     * or uses nonstandard tags (different ATAK revisions).
     */
    private boolean geoChatXmlReferencesRegisteredBtechContact(CotEvent event) {
        try {
            if (btechContactUids.isEmpty()) return false;
            String s = event.toString();
            if (s == null || s.length() > 524288) return false;
            for (String uid : btechContactUids) {
                if (uid != null && uid.length() > 8 && s.indexOf(uid) >= 0) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Backwards-compatible name for chat routing decisions.
     */
    public boolean shouldRelayChatCotToRadio(CotEvent event) {
        return shouldRelayGeoChatToRadio(event);
    }

    /**
     * Inject a position CoT event into ATAK from radio GPS data.
     *
     * @param senderTeamFromPeer ATAK {@code locationTeam} from the transmitting node
     *                           (embedded in GPS packet). If null or empty (legacy/APRS path),
     *                           falls back to {@code "Cyan"} so we do not apply the
     *                           <em>receiver's</em> team tint to peers.
     */
    public void injectPositionCot(String callsign, double lat, double lon,
                                  double alt, double speed, double course,
                                  String senderTeamFromPeer) {
        injectPositionCot(callsign, lat, lon, alt, speed, course,
                senderTeamFromPeer, null, null, null, null, null);
    }

    /** Position inject with explicit map UID (merged contact / single-marker policy). */
    public void injectPositionCotAtMapUid(String callsign, double lat, double lon,
                                        double alt, double speed, double course,
                                        String senderTeamFromPeer, String mapUidOverride) {
        injectPositionCot(callsign, lat, lon, alt, speed, course,
                senderTeamFromPeer, null, null, null, null, mapUidOverride);
    }

    /** Position inject with explicit map UID and custom icon symbol mapping. */
    public void injectPositionCotAtMapUid(String callsign, double lat, double lon,
                                          double alt, double speed, double course,
                                          String senderTeamFromPeer,
                                          Character aprsSymbolTable,
                                          Character aprsSymbolCode,
                                          String remarksInner,
                                          String mapUidOverride) {
        injectPositionCot(callsign, lat, lon, alt, speed, course,
                senderTeamFromPeer, null, aprsSymbolTable, aprsSymbolCode, remarksInner, mapUidOverride);
    }

    public void injectPositionCot(String callsign, double lat, double lon,
                                  double alt, double speed, double course,
                                  String senderTeamFromPeer, String cotTypeOverride) {
        injectPositionCot(callsign, lat, lon, alt, speed, course,
                senderTeamFromPeer, cotTypeOverride, null, null, null, null);
    }

    public void injectPositionCot(String callsign, double lat, double lon,
                                  double alt, double speed, double course,
                                  String senderTeamFromPeer,
                                  Character aprsSymbolTable,
                                  Character aprsSymbolCode) {
        injectPositionCot(callsign, lat, lon, alt, speed, course,
                senderTeamFromPeer, null, aprsSymbolTable, aprsSymbolCode, null, null);
    }

    /**
     * Injects position CoT with optional remarks (e.g. APRS telemetry summary at last fix).
     */
    public void injectPositionCot(String callsign, double lat, double lon,
                                  double alt, double speed, double course,
                                  String senderTeamFromPeer,
                                  Character aprsSymbolTable,
                                  Character aprsSymbolCode,
                                  String remarksInner) {
        injectPositionCot(callsign, lat, lon, alt, speed, course,
                senderTeamFromPeer, null, aprsSymbolTable, aprsSymbolCode, remarksInner, null);
    }

    private void injectPositionCot(String callsign, double lat, double lon,
                                   double alt, double speed, double course,
                                   String senderTeamFromPeer, String cotTypeOverride,
                                   Character aprsSymbolTable, Character aprsSymbolCode,
                                   String remarksInner, String mapUidOverride) {
        try {
            String teamForCot = senderTeamFromPeer != null && !senderTeamFromPeer.trim().isEmpty()
                    ? senderTeamFromPeer.trim()
                    : "Cyan";

            String normalizedCall = callsign != null ? callsign.trim().toUpperCase(Locale.US) : "";
            String syntheticUid = ANDROID_UID_PREFIX + normalizedCall;
            String mapUid = mapUidOverride;
            if (mapUid == null || mapUid.trim().isEmpty()) {
                mapUid = ChatBridge.resolveCanonicalPeerUid(normalizedCall, syntheticUid);
            }
            if (mapUid == null || mapUid.trim().isEmpty()) {
                mapUid = syntheticUid;
            } else {
                mapUid = mapUid.trim();
            }

            CotEvent event = CotBuilder.buildPositionCot(
                    callsign, lat, lon, alt, speed, course, teamForCot,
                    resolveInboundContactStaleMs(),
                    cotTypeOverride,
                    aprsSymbolTable,
                    aprsSymbolCode,
                    remarksInner,
                    mapUid);

            if (event != null && event.isValid()) {
                Log.d(TAG, "Injecting position CoT for " + callsign + " uid=" + mapUid
                        + " team=" + teamForCot);
                markRfNativeContact(mapUid);
                if (normalizedCall != null && !normalizedCall.isEmpty()) {
                    markRfHeardCallsign(normalizedCall);
                }
                markInboundInjectSkipOutboundRelay(event.getUID());
                dispatchCotEvent(event);
                maybeRelayInboundRadioCotToTak(event);
                MapView pingMv = MapView.getMapView();
                if (pingMv != null) {
                    PingReplyNotifier.maybeNotifyPingReply(pingMv.getContext(), callsign);
                }
                if (!mapUid.equalsIgnoreCase(syntheticUid)) {
                    removeOrphanRfMapMarkerForCallsign(normalizedCall, mapUid);
                }
            } else {
                Log.w(TAG, "Invalid position CoT for " + callsign);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error injecting position CoT", e);
        }
    }

    /**
     * Receiver-side stale policy for inbound radio contacts.
     * ATAK marks contacts stale based on the CoT stale timestamp we dispatch.
     */
    private long resolveInboundContactStaleMs() {
        try {
            Context prefsCtx = mapView != null && mapView.getContext() != null
                    ? mapView.getContext()
                    : pluginContext;
            if (prefsCtx == null) {
                return MIN_INBOUND_RADIO_STALE_MS;
            }

            long staleMs;
            if (SmartBeacon.isEnabled(prefsCtx)) {
                int slowRateSec = Math.max(1, SmartBeacon.getSlowRate(prefsCtx));
                int fastRateSec = Math.max(1, SmartBeacon.getFastRate(prefsCtx));
                int minTurnTimeSec = Math.max(1, SmartBeacon.getMinTurnTime(prefsCtx));
                int expectedMaxGapSec = Math.max(slowRateSec, fastRateSec + minTurnTimeSec);
                staleMs = expectedMaxGapSec * 1000L;
            } else {
                int fixedSec = Math.max(1, SettingsFragment.getBeaconIntervalSec(pluginContext));
                staleMs = fixedSec * 1000L;
            }
            long fromBeacon = Math.max(MIN_CONTACT_STALE_MS, staleMs + STALE_GRACE_MS);
            return Math.max(MIN_INBOUND_RADIO_STALE_MS, fromBeacon);
        } catch (Exception ignored) {
            return MIN_INBOUND_RADIO_STALE_MS;
        }
    }

    /**
     * Inject GeoChat delivered ({@code b-t-f-d}) or read ({@code b-t-f-r}) receipt for a sent line.
     */
    public void injectGeoChatReceipt(String referencedOriginalMessageLineUid,
                                     boolean readNotDelivered) {
        try {
            CotEvent event = CotBuilder.buildGeoChatReceiptCot(
                    referencedOriginalMessageLineUid,
                    readNotDelivered,
                    cachedLocalDeviceUidForGeoChat,
                    localCallsign());
            if (event == null || !event.isValid()) {
                return;
            }
            markInboundInjectSkipOutboundRelay(event.getUID());
            deliverInboundGeoChatToAtak(event);
            broadcastReceiptCotPlaced(event);
            Log.d(TAG, "Injected GeoChat receipt type=" + event.getType()
                    + " cotUID=" + event.getUID()
                    + " for line=" + referencedOriginalMessageLineUid);
        } catch (Exception e) {
            Log.e(TAG, "Error injecting GeoChat receipt", e);
        }
    }

    /**
     * Some ATAK GeoChat paths hook {@code COT_PLACED} rather than the internal dispatcher alone;
     * receipts still skip RF relay via PreSend guards on {@code b-t-f-d}/{@code b-t-f-r}.
     */
    private void broadcastReceiptCotPlaced(CotEvent event) {
        if (event == null) {
            return;
        }
        try {
            Intent intent = new Intent("com.atakmap.android.maps.COT_PLACED");
            intent.putExtra("xml", event.toString());
            AtakBroadcast.getInstance().sendBroadcast(intent);
            Log.d(TAG, "Broadcast COT_PLACED for GeoChat receipt uid=" + event.getUID());
        } catch (Exception e) {
            Log.w(TAG, "COT_PLACED broadcast for GeoChat receipt failed", e);
        }
    }

    /**
     * Inject a compressed CoT XML received from another UV-PRO node.
     */
    public void injectCompressedCot(byte[] compressed) {
        injectCompressedCot(compressed, 0);
    }

    /**
     * Inject a compressed CoT XML received from another UV-PRO node, carrying
     * the MeshCore pathLen (number of repeater hops) for display purposes.
     */
    public void injectCompressedCot(byte[] compressed, int pathLen) {
        injectCompressedCot(compressed, pathLen, null);
    }

    private volatile BtConnectionManager uvproInboundManager;
    private volatile BtConnectionManager meshInboundManager;

    public void setInboundTransports(BtConnectionManager uvpro, BtConnectionManager mesh) {
        this.uvproInboundManager = uvpro;
        this.meshInboundManager = mesh;
    }

    /** ACK over the radio the CoT arrived on; active transmit radio only as fallback. */
    private BtConnectionManager resolveAckManager(
            com.uvpro.plugin.protocol.RfInboundTransport inbound) {
        BtConnectionManager preferred = null;
        if (inbound == com.uvpro.plugin.protocol.RfInboundTransport.MESHCORE) {
            preferred = meshInboundManager;
        } else if (inbound == com.uvpro.plugin.protocol.RfInboundTransport.UVPRO) {
            preferred = uvproInboundManager;
        }
        if (preferred != null && preferred.isConnected()) {
            return preferred;
        }
        return btManager;
    }

    // --- Delete relay: a local delete of an item that crossed the radio is sent as t-x-d-d ---

    private static final String PREF_RADIO_SYNCED_ITEMS = "uvpro_radio_synced_map_items";
    private static final int MAX_RADIO_SYNCED_ITEMS = 400;
    private static final long INBOUND_DELETE_GUARD_MS = 60_000L;
    private static final long LOCAL_DELETE_SETTLE_MS = 3000L;
    private final Map<String, String> radioSyncedMapItems = new ConcurrentHashMap<>();
    private final Map<String, Long> inboundDeleteGuard = new ConcurrentHashMap<>();
    private volatile boolean radioSyncedItemsLoaded;
    private MapEventDispatcher.MapEventDispatchListener itemRemovedListener;

    private boolean isDeleteSyncType(String type) {
        return type != null && !type.startsWith("t-x-d-d") && isRelayableMapCotType(type)
                && !type.contains("-U-C");
    }

    private void loadRadioSyncedItems() {
        if (radioSyncedItemsLoaded) {
            return;
        }
        radioSyncedItemsLoaded = true;
        try {
            android.content.SharedPreferences prefs = android.preference.PreferenceManager
                    .getDefaultSharedPreferences(mapView.getContext());
            Set<String> saved = prefs.getStringSet(PREF_RADIO_SYNCED_ITEMS, null);
            if (saved != null) {
                for (String entry : saved) {
                    int tab = entry.indexOf('\t');
                    if (tab > 0) {
                        radioSyncedMapItems.put(entry.substring(0, tab), entry.substring(tab + 1));
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "loadRadioSyncedItems failed", e);
        }
    }

    private void persistRadioSyncedItems() {
        try {
            Set<String> out = new java.util.HashSet<>();
            for (Map.Entry<String, String> e : radioSyncedMapItems.entrySet()) {
                out.add(e.getKey() + "\t" + e.getValue());
            }
            android.preference.PreferenceManager.getDefaultSharedPreferences(mapView.getContext())
                    .edit().putStringSet(PREF_RADIO_SYNCED_ITEMS, out).apply();
        } catch (Exception e) {
            Log.w(TAG, "persistRadioSyncedItems failed", e);
        }
    }

    /** Remember a map item that went out over, or arrived on, the radio. */
    private void noteRadioSyncedMapItem(CotEvent event) {
        if (event == null) {
            return;
        }
        String uid = event.getUID();
        String type = event.getType();
        if (uid == null || uid.trim().isEmpty() || uid.startsWith(ANDROID_UID_PREFIX)
                || !isDeleteSyncType(type) || isSelfPliBeacon(event)) {
            return;
        }
        loadRadioSyncedItems();
        String trimUid = uid.trim();
        if (radioSyncedMapItems.size() >= MAX_RADIO_SYNCED_ITEMS
                && !radioSyncedMapItems.containsKey(trimUid)) {
            radioSyncedMapItems.clear();
        }
        if (!type.equals(radioSyncedMapItems.put(trimUid, type))) {
            persistRadioSyncedItems();
        }
    }

    private void forgetRadioSyncedMapItem(String uid) {
        loadRadioSyncedItems();
        if (radioSyncedMapItems.remove(uid) != null) {
            persistRadioSyncedItems();
        }
    }

    private void registerItemRemovedListener() {
        itemRemovedListener = this::onMapItemRemoved;
        try {
            mapView.getMapEventDispatcher().addMapEventListener(
                    MapEvent.ITEM_REMOVED, itemRemovedListener);
            Log.d(TAG, "Registered ITEM_REMOVED delete relay listener");
        } catch (Exception e) {
            Log.w(TAG, "Failed to register ITEM_REMOVED listener", e);
        }
    }

    private void unregisterItemRemovedListener() {
        if (itemRemovedListener != null && mapView != null) {
            try {
                mapView.getMapEventDispatcher().removeMapEventListener(
                        MapEvent.ITEM_REMOVED, itemRemovedListener);
            } catch (Exception e) {
                Log.w(TAG, "unregister itemRemovedListener", e);
            }
        }
        itemRemovedListener = null;
    }

    private void onMapItemRemoved(MapEvent event) {
        try {
            MapItem item = event == null ? null : event.getItem();
            String uid = item == null ? null : item.getUID();
            if (uid == null) {
                return;
            }
            loadRadioSyncedItems();
            String type = radioSyncedMapItems.get(uid);
            if (type == null) {
                return;
            }
            Long guardUntil = inboundDeleteGuard.get(uid);
            if (guardUntil != null && System.currentTimeMillis() < guardUntil) {
                inboundDeleteGuard.remove(uid);
                forgetRadioSyncedMapItem(uid);
                Log.d(TAG, "Removal of uid=" + uid + " came from a received delete — not re-sent");
                return;
            }
            // ATAK sometimes removes and re-adds an item on refresh; only a lasting removal counts.
            final String deleteType = type;
            mapView.postDelayed(() -> {
                try {
                    if (mapView.getMapItem(uid) != null) {
                        return;
                    }
                    if (radioSyncedMapItems.remove(uid) == null) {
                        return;
                    }
                    persistRadioSyncedItems();
                    relayLocalDeleteOverRadio(uid, deleteType);
                } catch (Exception e) {
                    Log.w(TAG, "Delete relay failed uid=" + uid, e);
                }
            }, LOCAL_DELETE_SETTLE_MS);
        } catch (Exception e) {
            Log.w(TAG, "onMapItemRemoved failed", e);
        }
    }

    private void relayLocalDeleteOverRadio(String uid, String type) {
        if (btManager == null || !btManager.isConnected()) {
            Log.d(TAG, "Local delete uid=" + uid + " not sent — radio not connected");
            return;
        }
        CotEvent del = CotBuilder.buildDeleteCot(uid, type);
        Log.d(TAG, "Local delete of radio-synced item uid=" + uid + " type=" + type
                + " — sending t-x-d-d uid=" + del.getUID());
        new Thread(() -> sendCotOverRadio(del)).start();
    }

    /** Remove the item named by a received delete without re-sending it. */
    private void applyInboundRadioDelete(CotEvent event) {
        final String uid = CotBuilder.extractDeleteTargetUid(event);
        if (uid == null) {
            Log.w(TAG, "Received t-x-d-d without a link uid");
            return;
        }
        inboundDeleteGuard.put(uid, System.currentTimeMillis() + INBOUND_DELETE_GUARD_MS);
        forgetRadioSyncedMapItem(uid);
        recentInboundCotByUid.keySet().removeIf(k -> k.startsWith(uid + "|"));
        mapView.post(() -> {
            try {
                MapItem item = mapView.getMapItem(uid);
                MapItem target = item == null ? null : ATAKUtilities.findAssocShape(item);
                if (target == null) {
                    target = item;
                }
                if (target == null) {
                    Log.d(TAG, "Received delete for uid=" + uid + " — not on this map");
                    inboundDeleteGuard.remove(uid);
                    return;
                }
                if (!target.getMetaBoolean("remoteDelete", true)) {
                    Log.d(TAG, "Received delete for uid=" + uid + " — item refuses remote delete");
                    inboundDeleteGuard.remove(uid);
                    return;
                }
                Log.d(TAG, "Received delete over radio — removing uid=" + target.getUID()
                        + " type=" + target.getType());
                target.removeFromGroup();
            } catch (Exception e) {
                Log.w(TAG, "applyInboundRadioDelete failed uid=" + uid, e);
            }
        });
    }

    /**
     * UV-PRO senders send a second copy about 3 s after the first, and the radio is half duplex.
     * An ACK keyed straight after copy 1 landed on top of copy 2 and was lost, which cost a 15 s
     * retry. The ACK now waits until the channel has been quiet for the OPENRL guard.
     */
    private static final long UVPRO_COT_ACK_FIRST_DELAY_MS = 300L;
    private static final long UVPRO_COT_ACK_POLL_MS = 400L;
    private static final int UVPRO_COT_ACK_MAX_DEFERS = 24;
    private final Set<String> pendingUvproCotAcks = ConcurrentHashMap.newKeySet();

    private void scheduleQuietCotAck(BtConnectionManager tx, byte[] ax25, String uid, int defers) {
        if (defers == 0 && !pendingUvproCotAcks.add(uid)) {
            Log.d(TAG, "CoT ACK already queued uid=" + uid);
            return;
        }
        long delay = defers == 0 ? UVPRO_COT_ACK_FIRST_DELAY_MS : UVPRO_COT_ACK_POLL_MS;
        cotRetryExecutor.schedule(() -> {
            try {
                if (!tx.isConnected()) {
                    pendingUvproCotAcks.remove(uid);
                    return;
                }
                if (RfTxArbitrator.get().shouldDeferRfChatAck()
                        && defers < UVPRO_COT_ACK_MAX_DEFERS) {
                    scheduleQuietCotAck(tx, ax25, uid, defers + 1);
                    return;
                }
                pendingUvproCotAcks.remove(uid);
                tx.sendKissFrame(ax25);
                Log.d(TAG, "CoT ACK sent uid=" + uid + " after " + defers + " channel waits");
            } catch (Exception e) {
                pendingUvproCotAcks.remove(uid);
                Log.w(TAG, "Failed to send CoT ACK uid=" + uid, e);
            }
        }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static final long INBOUND_COT_DUPLICATE_WINDOW_MS = 120_000L;
    private final java.util.concurrent.ConcurrentHashMap<String, Long> recentInboundCotByUid =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * True when an identical CoT body for this uid was already injected recently, i.e. a sender
     * retransmit because our ACK was lost.
     */
    private boolean isDuplicateInboundCot(String uid, String xml) {
        if (uid == null || xml == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        String fingerprint = xml.length() + ":" + xml.hashCode();
        String key = uid + "|" + fingerprint;
        if (recentInboundCotByUid.size() > 512) {
            recentInboundCotByUid.entrySet().removeIf(
                    e -> now - e.getValue() > INBOUND_COT_DUPLICATE_WINDOW_MS);
        }
        Long previous = recentInboundCotByUid.put(key, now);
        return previous != null && now - previous <= INBOUND_COT_DUPLICATE_WINDOW_MS;
    }

    /**
     * @param inboundTransport radio the CoT arrived on; the ACK is sent back over it. Null falls
     *                         back to the active transmit radio.
     */
    public void injectCompressedCot(byte[] compressed, int pathLen,
                                    com.uvpro.plugin.protocol.RfInboundTransport inboundTransport) {
        try {
            String xml = CotBuilder.decompressCot(compressed);
            if (xml == null || xml.isEmpty()) {
                Log.w(TAG, "Failed to decompress CoT");
                return;
            }

            CotEvent event = CotEvent.parse(xml);
            if (event == null || !event.isValid()) {
                Log.w(TAG, "injectCompressedCot: CotEvent.parse returned null/invalid — xml="
                        + (xml.length() > 120 ? xml.substring(0, 120) + "…" : xml));
                return;
            }
            if (!isInboundDirectedRfCotForThisDevice(event)) {
                Log.d(TAG, "Skipping directed RF CoT not addressed to this device: type="
                        + event.getType() + " uid=" + event.getUID()
                        + " destUids=" + CotBuilder.extractDirectedDestUids(event));
                return;
            }
            CotBuilder.stripDirectedDestinations(event);
            if (isDuplicateInboundCot(event.getUID(), xml)) {
                Log.d(TAG, "Duplicate inbound CoT (retransmit) uid=" + event.getUID()
                        + " type=" + event.getType() + " — ACK only, not processed again");
                sendCotAckForInboundEvent(event, inboundTransport);
                return;
            }
            if ("t-x-d-d".equals(event.getType())) {
                applyInboundRadioDelete(event);
                sendCotAckForInboundEvent(event, inboundTransport);
                return;
            }
            Log.d(TAG, "Injecting decompressed CoT: type=" + event.getType()
                    + " uid=" + event.getUID());
            Log.d(TAG, "CoT received: type=" + event.getType() + " via "
                    + (pathLen <= 0 ? "direct" : pathLen + (pathLen == 1 ? " hop" : " hops")));
            // Mark ALL injected CoT to skip outbound RF relay — prevents the
            // PreSendProcessor from echoing received items back over the air.
            markInboundInjectSkipOutboundRelay(event.getUID());
            if (GrgSettingsShare.isGrgSettingsCot(event)) {
                handleInboundGrgSettingsCot(event);
                maybeRelayInboundRadioCotToTak(event);
                sendCotAckForInboundEvent(event, inboundTransport);
                return;
            }
            if (isGeoChatCotType(event.getType())) {
                deliverInboundGeoChatToAtak(event);
            } else {
                dispatchCotEvent(event);
            }
            noteRadioSyncedMapItem(event);
            maybeRelayInboundRadioCotToTak(event);
            MapView pingMv = MapView.getMapView();
            if (pingMv != null) {
                PingReplyNotifier.maybeNotifyPingReplyFromCot(
                        pingMv.getContext(), event);
            }
            sendCotAckForInboundEvent(event, inboundTransport);
        } catch (Exception e) {
            Log.e(TAG, "Error injecting compressed CoT", e);
        }
    }

    private void sendCotAckForInboundEvent(CotEvent event,
                                           com.uvpro.plugin.protocol.RfInboundTransport inboundTransport) {
        if (event == null) {
            return;
        }
        String ackUid = event.getUID();
        final BtConnectionManager ackTx = resolveAckManager(inboundTransport);
        if (ackUid == null || ackUid.trim().isEmpty()
                || ackTx == null || !ackTx.isConnected()) {
            return;
        }
        try {
            UVProPacket ackPkt = UVProPacket.createCotAck(ackUid.trim());
            byte[] ackBytes = ackPkt.encode();
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                ackBytes = encryptionManager.encrypt(ackBytes);
            }
            if (ackBytes != null) {
                Ax25Frame ackFrame = Ax25Frame.createUVProFrame(localCallsign(), 0, ackBytes);
                byte[] ax25 = ackFrame.encode();
                if (ackTx instanceof MeshBtConnectionManager) {
                    final byte[] paced = ax25;
                    final BtConnectionManager tx = ackTx;
                    final String uid = ackUid.trim();
                    cotRetryExecutor.schedule(() -> {
                        try {
                            if (!tx.isConnected()) {
                                return;
                            }
                            com.uvpro.plugin.protocol.RfTxArbitrator.get().markOpenRlTxStart();
                            try {
                                tx.sendKissFrame(paced);
                                Log.d(TAG, "CoT ACK sent uid=" + uid);
                            } finally {
                                com.uvpro.plugin.protocol.RfTxArbitrator.get().markOpenRlTxEnd();
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to send CoT ACK uid=" + uid, e);
                        }
                    }, MESH_COT_ACK_QUIET_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                } else {
                    scheduleQuietCotAck(ackTx, ax25, ackUid.trim(), 0);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to send CoT ACK uid=" + ackUid, e);
        }
    }

    private void handleInboundGrgSettingsCot(CotEvent event) {
        if (event == null) {
            return;
        }
        String uid = event.getUID();
        if (!GrgSettingsShare.markReceivedOnce(uid)) {
            Log.d(TAG, "Duplicate GRG settings CoT ignored uid=" + uid);
            return;
        }
        String json = GrgSettingsShare.extractSettingsJson(event);
        if (json == null || json.isEmpty()) {
            Log.w(TAG, "GRG settings CoT missing JSON payload uid=" + uid);
            return;
        }
        try {
            File saved = GrgSettingsShare.writeReceivedSettings(
                    json, GrgSettingsShare.extractFilename(event));
            Log.i(TAG, "GRG settings saved: " + saved.getAbsolutePath());
            MapView mv = mapView != null ? mapView : MapView.getMapView();
            if (mv != null) {
                String path = GrgShareUi.formatUserPath(saved);
                mv.post(() -> android.widget.Toast.makeText(mv.getContext(),
                        "GRG settings received — import in GRG Builder:\n" + path,
                        android.widget.Toast.LENGTH_LONG).show());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save inbound GRG settings uid=" + uid, e);
            MapView mv = mapView != null ? mapView : MapView.getMapView();
            if (mv != null) {
                mv.post(() -> android.widget.Toast.makeText(mv.getContext(),
                        "GRG settings receive failed: " + e.getMessage(),
                        android.widget.Toast.LENGTH_LONG).show());
            }
        }
    }

    public static final class GrgShareResult {
        public final boolean success;
        public final String message;

        public GrgShareResult(boolean success, String message) {
            this.success = success;
            this.message = message != null ? message : "";
        }
    }

    /**
     * Send map markers inside the GRG corners, then the settings JSON, to RF contact(s).
     */
    public void sendGrgSettingsBundle(java.io.File jsonFile, String[] toUIDs,
                                      java.util.function.Consumer<GrgShareResult> callback) {
        new Thread(() -> {
            GrgShareResult result = sendGrgSettingsBundleInternal(jsonFile, toUIDs);
            if (callback == null) {
                return;
            }
            MapView mv = mapView != null ? mapView : MapView.getMapView();
            if (mv != null) {
                mv.post(() -> callback.accept(result));
            } else {
                callback.accept(result);
            }
        }, "UVPro-GrgShare").start();
    }

    private GrgShareResult sendGrgSettingsBundleInternal(java.io.File jsonFile, String[] toUIDs) {
        if (jsonFile == null || !jsonFile.isFile()) {
            return new GrgShareResult(false, "GRG settings file not found.");
        }
        if (btManager == null || !btManager.isConnected()) {
            return new GrgShareResult(false, "Radio not connected.");
        }
        if (toUIDs == null || toUIDs.length == 0) {
            return new GrgShareResult(false, "No destination contact.");
        }
        if (!com.uvpro.plugin.contacts.ContactReachability
                .hasAnyRfReachableMapRecipient(toUIDs, this)) {
            return new GrgShareResult(false, "Contact is not reachable over RF.");
        }
        try {
            String json = GrgSettingsShare.readUtf8File(jsonFile);
            if (json.isEmpty()) {
                return new GrgShareResult(false, "GRG settings file is empty.");
            }
            MapView mv = mapView != null ? mapView : MapView.getMapView();
            double[] bounds = GrgSettingsShare.parseCornerBounds(json);
            int markerCount = 0;
            if (bounds != null && mv != null) {
                java.util.List<CotEvent> markers =
                        GrgSettingsShare.collectMarkerCotsInsideBounds(mv, bounds);
                for (CotEvent marker : markers) {
                    CotEvent directed = CotBuilder.stampDirectedDestinations(marker, toUIDs);
                    sendCotOverRadio(directed != null ? directed : marker);
                    markerCount++;
                    try {
                        Thread.sleep(GrgSettingsShare.markerSendGapMs());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            CotEvent settingsCot = GrgSettingsShare.buildGrgSettingsCot(
                    json, jsonFile.getName(), mv);
            if (settingsCot == null) {
                return new GrgShareResult(false, "Failed to build GRG settings CoT.");
            }
            CotEvent directedSettings = CotBuilder.stampDirectedDestinations(settingsCot, toUIDs);
            sendCotOverRadio(directedSettings != null ? directedSettings : settingsCot);
            return new GrgShareResult(true,
                    "Sent " + markerCount + " point(s) + GRG settings to contact.");
        } catch (Exception e) {
            Log.e(TAG, "sendGrgSettingsBundle failed", e);
            return new GrgShareResult(false, "GRG share failed: " + e.getMessage());
        }
    }

    /**
     * Inject a chat CoT event into ATAK.
     */
    /**
     * @param radioPacketMessageId UV-PRO wire id ({@literal >} 0 distinguishes duplicate ATAK merges); 0 = unknown
     * @param originatingLineUidOrNull When set (from RF gateway envelope), reuse the sender's GeoChat line UID
     */
    public void injectChatCot(String senderCallsign, String message,
                              String chatRoom, int radioPacketMessageId,
                              String originatingLineUidOrNull) {
        injectChatCot(senderCallsign, message, chatRoom, radioPacketMessageId,
                originatingLineUidOrNull, null);
    }

    /**
     * @param senderUidOverride When set (APRS inbound), use this exact ANDROID-* UID and FCC
     *                          display label — do not re-resolve via UV-PRO contact maps.
     */
    public void injectChatCot(String senderCallsign, String message,
                              String chatRoom, int radioPacketMessageId,
                              String originatingLineUidOrNull, String senderUidOverride) {
        try {
            String trimmed = senderCallsign != null ? senderCallsign.trim() : "";
            String canonicalUid;
            String displayCallsign;
            if (senderUidOverride != null && !senderUidOverride.trim().isEmpty()) {
                canonicalUid = senderUidOverride.trim().toUpperCase(Locale.US);
                if (!canonicalUid.startsWith(ANDROID_UID_PREFIX)) {
                    canonicalUid = ANDROID_UID_PREFIX + canonicalUid;
                }
                displayCallsign = trimmed.isEmpty()
                        ? canonicalUid.substring(ANDROID_UID_PREFIX.length())
                        : trimmed;
            } else {
                // Align with GPS-registered contacts: AX.25 truncates sender (e.g. JUNIOR → JNR).
                canonicalUid = resolveBtechUidForId(trimmed);
                if (canonicalUid == null && !trimmed.isEmpty()) {
                    if (isOpaqueDeviceId(trimmed)) {
                        Log.w(TAG, "injectChatCot: dropping sender opaque device id: " + trimmed);
                        return;
                    }
                    String key = normalizeBtechRoutingId(trimmed);
                    if (!key.isEmpty()) {
                        canonicalUid = ANDROID_UID_PREFIX + key;
                    }
                }
                if (canonicalUid == null || canonicalUid.isEmpty()) {
                    Log.w(TAG, "injectChatCot: no UID for sender " + trimmed);
                    return;
                }
                displayCallsign = ChatBridge.displayCallsignForContact(trimmed, canonicalUid);
            }
            String localUid = cachedLocalDeviceUidForGeoChat;
            if (localUid == null || localUid.isEmpty()) {
                try {
                    localUid = MapView.getDeviceUid();
                } catch (Exception ignored) {
                }
            }
            if (localUid != null && !localUid.isEmpty()
                    && canonicalUid.equalsIgnoreCase(localUid)) {
                Log.d(TAG, "injectChatCot: ignored self-origin chat sender uid=" + canonicalUid);
                return;
            }
            String chatGrpUid1ForDm = null;
            if (chatRoom != null && chatRoom.startsWith("ANDROID-")) {
                chatGrpUid1ForDm = cachedLocalDeviceUidForGeoChat;
                if (chatGrpUid1ForDm == null) {
                    chatGrpUid1ForDm = resolveLocalAtakUidForChatGrp(canonicalUid, chatRoom);
                }
            }

            CotEvent event;
            String existingLineUid = originatingLineUidOrNull != null
                    ? originatingLineUidOrNull.trim() : "";
            if (ChatBridge.isLikelyGeoChatLineUid(existingLineUid)) {
                event = CotBuilder.buildChatCotWithExistingLineUid(
                        existingLineUid, canonicalUid, displayCallsign, message, chatRoom,
                        chatGrpUid1ForDm);
            } else {
                long uniq;
                if (radioPacketMessageId != 0) {
                    long mid = ((long) radioPacketMessageId) & 0xffffffffL;
                    long t = System.currentTimeMillis() & 0xffffffffL;
                    uniq = (mid << 32) | t;
                } else {
                    uniq = System.nanoTime();
                }
                event = CotBuilder.buildChatCot(
                        canonicalUid, displayCallsign, message, chatRoom, uniq,
                        chatGrpUid1ForDm);
            }

            if (event != null && event.isValid()) {
                if (chatGrpUid1ForDm != null && chatRoom != null
                        && chatRoom.startsWith(ANDROID_UID_PREFIX)) {
                    Log.d(TAG, "GeoChat DM __chat id(local)=" + chatGrpUid1ForDm
                            + " chatroom(callsign)=" + displayCallsign
                            + " peerTHREAD=" + chatRoom + " sender=" + canonicalUid);
                }
                Log.d(TAG, "Injecting chat CoT from " + displayCallsign
                        + " (uid=" + canonicalUid + " midpkt=" + radioPacketMessageId
                        + (existingLineUid.isEmpty() ? "" : " lineUid=" + existingLineUid) + ")");
                markInboundInjectSkipOutboundRelay(event.getUID());
                deliverInboundGeoChatToAtak(event);
                maybeRelayInboundRadioCotToTak(event);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error injecting chat CoT", e);
        }
    }

    /** Backward-compatible overload without originating line UID. */
    public void injectChatCot(String senderCallsign, String message,
                              String chatRoom, int radioPacketMessageId) {
        injectChatCot(senderCallsign, message, chatRoom, radioPacketMessageId, null);
    }

    private static boolean isGeoChatCotType(String type) {
        return type != null && type.startsWith("b-t-f");
    }

    /**
     * Wi‑Fi/TAK delivers GeoChat through {@link GeoChatService}; internal {@link CotMapComponent}
     * dispatch alone creates stray contacts and skips {@code hierarchy} group parsing.
     */
    private void deliverInboundGeoChatToAtak(CotEvent event) {
        if (event == null) {
            return;
        }
        boolean hasHierarchy = GeoChatContactListHelper.cotHasContactHierarchy(event);
        boolean contactListUpdate = GeoChatContactListHelper.isContactListUpdateMessage(
                extractGeoChatRemarks(event));
        boolean groupSync = hasHierarchy || contactListUpdate;
        try {
            // GeoChatService treats unknown senders as stray IP contacts and can rewrite
            // conversationId to senderUid — seed the RF peer first.
            seedInboundGeoChatSender(event, groupSync);
            if (groupSync) {
                registerBtechPeersFromGroupHierarchy(event);
            }
            GeoChatService.getInstance().onCotEvent(event, new Bundle());
            Log.i(TAG, "Inbound GeoChat via GeoChatService: type=" + event.getType()
                    + " uid=" + event.getUID()
                    + " convo=" + GeoChatContactListHelper.extractConversationId(event)
                    + " sender=" + GeoChatContactListHelper.extractChatSenderUid(event)
                    + " hasHierarchy=" + GeoChatContactListHelper.cotHasContactHierarchy(event));
            String inboundSenderUid = GeoChatContactListHelper.extractChatSenderUid(event);
            com.uvpro.plugin.UVProContactHandler.repairAtakPeerConnectorDefault(inboundSenderUid);
            if (inboundSenderUid != null
                    && (inboundSenderUid.startsWith("MESHCORE-NODE-")
                        || inboundSenderUid.startsWith("MESHCORE-RPTR-"))) {
                final CotEvent eventForClear = event;
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> clearNativeGeoChatUnread(eventForClear), 300);
            }
            if (groupSync) {
                InboundGroupSyncApplier.applyAfterInboundGroupCot(event, contactListUpdate);
            }
            if (!contactListUpdate) {
                notifyInboundRfChat(event);
            }
            if ("b-t-f".equals(event.getType())) {
                final String badgeConversation = GeoChatContactListHelper.isAllChatRoomsGeoChat(event)
                        ? "All Chat Rooms"
                        : inboundSenderUid;
                if (badgeConversation != null && !badgeConversation.trim().isEmpty() && mapView != null) {
                    mapView.post(() -> GeoChatUnreadReconciler.reconcileConversation(
                            badgeConversation.trim()));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "GeoChatService.onCotEvent failed, fallback dispatch", e);
            dispatchCotEvent(event);
        }
    }

    private void clearNativeGeoChatUnread(CotEvent event) {
        try {
            String convo = GeoChatContactListHelper.extractConversationId(event);
            if (convo != null && !convo.trim().isEmpty()) {
                android.content.Intent markRead = new android.content.Intent(
                        "com.atakmap.chat.markmessageread");
                markRead.putExtra("conversationId", convo.trim());
                com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(markRead);
            }
            String senderUid = GeoChatContactListHelper.extractChatSenderUid(event);
            if (senderUid != null && !senderUid.trim().isEmpty()
                    && !senderUid.trim().equals(convo != null ? convo.trim() : "")) {
                android.content.Intent markRead2 = new android.content.Intent(
                        "com.atakmap.chat.markmessageread");
                markRead2.putExtra("conversationId", senderUid.trim());
                com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(markRead2);
            }
        } catch (Exception ignored) {
        }
    }

    private static String extractGeoChatRemarks(CotEvent event) {
        if (event == null || event.getDetail() == null) {
            return null;
        }
        CotDetail remarks = event.getDetail().getFirstChildByName(0, "remarks");
        return remarks != null ? remarks.getInnerText() : null;
    }

    private void notifyInboundRfChat(CotEvent event) {
        if (event == null) {
            return;
        }
        String uid = event.getUID();
        if (uid == null || uid.trim().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long until = inboundChatNotifyUntil.get(uid);
        if (until != null && now < until) {
            return;
        }
        inboundChatNotifyUntil.put(uid, now + INBOUND_CHAT_NOTIFY_DEDUPE_MS);

        String message = extractGeoChatRemarks(event);
        if (message == null || message.trim().isEmpty()) {
            message = "New RF chat message";
        }
        String conversationId = GeoChatContactListHelper.extractConversationId(event);
        if (conversationId != null && !conversationId.trim().isEmpty()) {
            int msgId = uid.hashCode() & 0x7FFFFFFF;
            UVProContactHandler.incrementUnreadOnce(conversationId.trim(), msgId, message);
        }
        Log.d(TAG, "Inbound RF chat popup suppressed uid=" + uid);
    }

    /**
     * Ensure the sender exists before {@link GeoChatService#onCotEvent} so ATAK does not
     * synthesize a TCP/UDP {@link IndividualContact} in the root Contacts list.
     * Group-sync uses a plain ATAK contact (no {@link PluginConnector}) so the sender does
     * not appear as a stray UV‑PRO peer above the imported group tree.
     */
    private void seedInboundGeoChatSender(CotEvent event, boolean groupSync) {
        String senderUid = GeoChatContactListHelper.extractChatSenderUid(event);
        String callsign = GeoChatContactListHelper.extractChatSenderCallsign(event);
        String linkUid = null;
        CotDetail detail = event != null ? event.getDetail() : null;
        CotDetail link = detail != null ? detail.getFirstChildByName(0, "link") : null;
        if (link != null) {
            linkUid = link.getAttribute("uid");
        }
        if (senderUid == null || senderUid.isEmpty()) {
            senderUid = linkUid;
        }
        if (callsign == null || callsign.isEmpty()) {
            if (senderUid != null && senderUid.startsWith(ANDROID_UID_PREFIX)) {
                callsign = senderUid.substring(ANDROID_UID_PREFIX.length());
            } else if (senderUid != null) {
                callsign = senderUid;
            }
        }

        if (senderUid != null && ChatBridge.isAprsChatContactUid(senderUid)) {
            ChatBridge.ensureAprsPluginChatContact(callsign);
            registerBtechAliases(callsign, senderUid, senderUid, linkUid);
            return;
        }

        String canonical = ChatBridge.resolveCanonicalPeerUid(callsign, senderUid, linkUid);
        if (canonical == null || canonical.isEmpty()) {
            return;
        }
        String localUid = cachedLocalDeviceUidForGeoChat;
        if (localUid == null || localUid.isEmpty()) {
            try {
                localUid = MapView.getDeviceUid();
            } catch (Exception ignored) {
            }
        }
        if (localUid != null && !localUid.isEmpty()
                && canonical.equalsIgnoreCase(localUid)) {
            Log.d(TAG, "seedInboundGeoChatSender: skip local self uid=" + canonical);
            return;
        }

        if (groupSync) {
            ensureAtakGeoChatSenderContact(canonical, callsign);
        } else if (canonical.toUpperCase(Locale.US).startsWith(ANDROID_UID_PREFIX)) {
            // GeoChatService may emit delivered/read status to this exact UID immediately.
            // Seed as exact plugin contact so Comms does not treat it as unknown.
            ChatBridge.ensurePluginChatContactExactUid(callsign, canonical);
        } else {
            ensureAtakGeoChatSenderContact(canonical, callsign);
        }
        registerBtechAliases(callsign, canonical, senderUid, linkUid);
    }

    /** ATAK {@code GeoChatService.sendMessage} errors if peer UID is not in Contacts yet. */
    private static void ensureOutboundGeoChatDestinationsKnown(String[] toUIDs) {
        if (toUIDs == null) {
            return;
        }
        for (String raw : toUIDs) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String uid = raw.trim().toUpperCase(Locale.US);
            if (!uid.startsWith(ANDROID_UID_PREFIX)) {
                continue;
            }
            String callsign = uid.substring(ANDROID_UID_PREFIX.length());
            ChatBridge.ensurePluginChatContactExactUid(callsign, uid);
        }
    }

    private void registerBtechAliases(String callsign, String canonicalUid, String... aliases) {
        if (canonicalUid == null || canonicalUid.isEmpty()) {
            return;
        }
        registerBtechContactUid(canonicalUid);
        if (callsign != null && !callsign.isEmpty()) {
            registerBtechContactId(callsign, canonicalUid);
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        seen.add(canonicalUid.toUpperCase(Locale.US));
        if (aliases != null) {
            for (String raw : aliases) {
                if (raw == null || raw.trim().isEmpty()) {
                    continue;
                }
                String uid = raw.trim();
                String upper = uid.toUpperCase(Locale.US);
                if (!seen.add(upper)) {
                    continue;
                }
                registerBtechContactUid(upper);
                if (callsign != null && !callsign.isEmpty()) {
                    registerBtechContactId(callsign, upper);
                }
                if (!upper.startsWith(ANDROID_UID_PREFIX) && upper.matches("[0-9A-F]+")) {
                    String android = ANDROID_UID_PREFIX + upper;
                    if (seen.add(android)) {
                        registerBtechContactUid(android);
                        if (callsign != null && !callsign.isEmpty()) {
                            registerBtechContactId(callsign, android);
                        }
                    }
                }
            }
        }
    }

    private static void ensureAtakGeoChatSenderContact(String senderUid, String callsign) {
        if (senderUid == null || senderUid.isEmpty()) {
            return;
        }
        try {
            Contacts contacts = Contacts.getInstance();
            String uid = senderUid.trim();
            String canonicalUid = ChatBridge.resolveCanonicalPeerUid(callsign, uid);
            if (canonicalUid != null && !canonicalUid.isEmpty()) {
                if (!uid.equalsIgnoreCase(canonicalUid)) {
                    Contact dup = contacts.getContactByUuid(uid);
                    if (dup != null) {
                        contacts.removeContact(dup);
                    }
                }
                Contact existingCanonical = contacts.getContactByUuid(canonicalUid);
                if (existingCanonical != null) {
                    return;
                }
            }
            Contact existing = contacts.getContactByUuid(uid);
            if (existing == null) {
                existing = contacts.getContactByUuid(uid.toUpperCase(Locale.US));
            }
            if (existing != null) {
                return;
            }
            String name = callsign != null && !callsign.isEmpty() ? callsign.trim() : uid;
            contacts.addContact(new IndividualContact(name, uid));
        } catch (Exception e) {
            Log.w(TAG, "ensureAtakGeoChatSenderContact failed uid=" + senderUid, e);
        }
    }

    /** RF route map only — do not add every group member to the root Contacts pane. */
    private void registerBtechPeersFromGroupHierarchy(CotEvent event) {
        if (event == null || chatBridge == null) {
            return;
        }
        String senderUid = GeoChatContactListHelper.extractChatSenderUid(event);
        for (String uid : GeoChatContactListHelper.collectContactUidsFromGroupCot(event)) {
            if (uid == null || !uid.startsWith(ANDROID_UID_PREFIX)) {
                continue;
            }
            // Skip sender + local device — only RF-route members already in the group tree.
            if (senderUid != null && uid.equalsIgnoreCase(senderUid)) {
                continue;
            }
            String localUid = MapView.getDeviceUid();
            if (localUid != null && uid.equalsIgnoreCase(localUid)) {
                continue;
            }
            String callsign = uid.substring(ANDROID_UID_PREFIX.length());
            registerBtechContactUid(uid);
            registerBtechContactId(callsign, uid);
        }
    }

    /**
     * Send a CoT event out over the radio link.
     * The CoT XML is gzipped and sent as an UV-PRO packet.
     */
    /** Max compressed CoT size to send over RF. Larger items flood the channel. */
    private static final int MAX_COT_COMPRESSED_BYTES = 4096;
    /**
     * Pause between mesh CoT packets. 800ms let the first two 126-byte floods
     * land and often dropped the third while the radio or repeater was still busy.
     */
    private static final long MESH_COT_PACKET_GAP_MS = 2500L;
    /** Wait after the last inbound piece before the ACK, so it is not stepped on. */
    private static final long MESH_COT_ACK_QUIET_MS = 3000L;
    /** Mesh retry waits past the paced send and the quiet ACK. UV-PRO stays at 15s. */
    private static final long MESH_COT_RETRY_INTERVAL_MS = 20_000L;

    public void sendCotOverRadio(CotEvent event) {
        sendCotOverRadioInternal(event, true);
    }

    private void sendCotOverRadioNoRetry(CotEvent event) {
        sendCotOverRadioInternal(event, false);
    }

    /**
     * A user-dropped friendly marker ({@code a-f-*}, not an SA/PLI) carries no {@code __group}, so
     * the receiver drew it with no team ({@code team=NULL}). Stamp the sender's ATAK team on a
     * copy so the receiver keeps team and colour. The original event is never changed.
     */
    private CotEvent withFriendlyMarkerTeam(CotEvent event) {
        try {
            String type = event == null ? null : event.getType();
            if (type == null || !type.startsWith("a-f-") || isSelfPliBeacon(event)
                    || event.getDetail() == null
                    || event.getDetail().getFirstChildByName(0, "__group") != null) {
                return event;
            }
            CotEvent copy = CotEvent.parse(event.toString());
            if (copy == null || copy.getDetail() == null) {
                return event;
            }
            CotDetail group = new CotDetail("__group");
            group.setAttribute("name", resolveLocalAtakTeamForOutbound());
            group.setAttribute("role", "Team Member");
            copy.getDetail().addChild(group);
            Log.d(TAG, "Friendly marker uid=" + event.getUID() + " type=" + type
                    + " — added sender team " + group.getAttribute("name"));
            return copy;
        } catch (Exception e) {
            Log.w(TAG, "withFriendlyMarkerTeam failed uid="
                    + (event == null ? null : event.getUID()), e);
            return event;
        }
    }

    private void sendCotOverRadioInternal(CotEvent event, boolean registerRetry) {
        if (btManager == null || !btManager.isConnected()) {
            Log.w(TAG, "Not connected to radio — cannot send CoT");
            return;
        }
        event = withFriendlyMarkerTeam(event);
        noteRadioSyncedMapItem(event);

        com.uvpro.plugin.protocol.RfTxArbitrator.get().markOpenRlTxStart();
        boolean holdMeshTx = false;
        try {
            String xml = event.toString();
            byte[] full = CotBuilder.compressCot(xml);
            if (full == null) {
                Log.e(TAG, "Failed to compress CoT for radio");
                return;
            }

            String minXml = CotBuilder.minifyCotXml(event);
            byte[] min = (minXml != null) ? CotBuilder.compressCot(minXml) : null;
            byte[] compressed = (min != null && min.length < full.length) ? min : full;
            Log.d(TAG, "CoT size: full=" + full.length + " min=" + (min == null ? -1 : min.length)
                    + " used=" + compressed.length + " type=" + event.getType());

            if (compressed.length > MAX_COT_COMPRESSED_BYTES) {
                Log.w(TAG, "CoT too large for RF (" + compressed.length + " bytes compressed"
                        + ", type=" + event.getType() + " uid=" + event.getUID()
                        + ") — skipping to avoid blocking channel");
                return;
            }

            Log.d(TAG, "Sending CoT over radio: type=" + event.getType()
                    + " uid=" + event.getUID()
                    + " xmlBytes=" + xml.length()
                    + " compressedBytes=" + compressed.length);

            // Mesh keeps each piece in one channel packet. A 3-chunk join of a
            // point reassembled to the right length and then failed gzip.
            // UV-PRO still uses the 240-byte AX.25 limit.
            int maxPayload = PacketFragmenter.MAX_FRAGMENT_PAYLOAD;
            if (btManager instanceof MeshBtConnectionManager) {
                maxPayload = ((MeshBtConnectionManager) btManager)
                        .maxUvproPayloadForSinglePacket();
            }
            List<UVProPacket> packets = PacketFragmenter.fragment(
                    UVProPacket.TYPE_COT, compressed, maxPayload);

            boolean paceMeshPackets = btManager instanceof MeshBtConnectionManager
                    && packets.size() > 1;
            int packetIndex = 0;
            for (UVProPacket packet : packets) {
                byte[] packetBytes = packet.encode();
                // Encrypt entire packet bytes if enabled
                if (encryptionManager != null && encryptionManager.isEnabled()) {
                    packetBytes = encryptionManager.encrypt(packetBytes);
                    if (packetBytes == null) {
                        Log.e(TAG, "Encryption failed — aborting CoT send");
                        return;
                    }
                }
                Ax25Frame frame = Ax25Frame.createUVProFrame(
                        localCallsign(), 0, packetBytes);
                byte[] ax25 = frame.encode();
                if (paceMeshPackets && packetIndex > 0) {
                    final byte[] paced = ax25;
                    final BtConnectionManager tx = btManager;
                    cotRetryExecutor.schedule(() -> {
                        if (tx.isConnected()) {
                            tx.sendKissFrame(paced);
                        }
                    }, MESH_COT_PACKET_GAP_MS * packetIndex,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                } else {
                    btManager.sendKissFrame(ax25);
                }
                packetIndex++;
            }
            if (paceMeshPackets) {
                holdMeshTx = true;
                cotRetryExecutor.schedule(
                        () -> com.uvpro.plugin.protocol.RfTxArbitrator.get().markOpenRlTxEnd(),
                        MESH_COT_PACKET_GAP_MS * (packets.size() - 1L),
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            }

            // Register ACK watchdog — skip GeoChat CoT (b-t-f*) which uses chat retry.
            if (registerRetry && shouldRegisterCotRfRetry(event)) {
                String cotType = event.getType();
                if (cotType == null || !cotType.startsWith("b-t-f")) {
                    String uid = event.getUID();
                    if (uid != null && !uid.trim().isEmpty()) {
                        String trimUid = uid.trim();
                        PendingOutboundCot pending = new PendingOutboundCot(trimUid, event);
                        pendingOutboundCots.put(trimUid, pending);
                        // Mesh channel data is one flood. A second full copy 3s later collides
                        // with the first. UV-PRO still sends the copy; it can hold the channel.
                        if (!(btManager instanceof MeshBtConnectionManager)) {
                            cotRetryExecutor.schedule(() -> {
                                if (pendingOutboundCots.containsKey(trimUid)) {
                                    Log.d(TAG, "CoT double-send uid=" + trimUid);
                                    sendCotOverRadioNoRetry(pending.event);
                                }
                            }, COT_DOUBLE_SEND_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                        }
                        scheduleCotRetryCheck(trimUid);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending CoT over radio", e);
        } finally {
            if (!holdMeshTx) {
                com.uvpro.plugin.protocol.RfTxArbitrator.get().markOpenRlTxEnd();
            }
        }
    }

    private void scheduleCotRetryCheck(String cotUid) {
        if (cotRetryExecutor.isShutdown()) return;
        long delayMs = btManager instanceof MeshBtConnectionManager
                ? MESH_COT_RETRY_INTERVAL_MS
                : COT_RETRY_INTERVAL_MS;
        cotRetryExecutor.schedule(() -> onCotRetryTimer(cotUid),
                delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void onCotRetryTimer(String cotUid) {
        try {
            PendingOutboundCot pending = pendingOutboundCots.get(cotUid);
            if (pending == null) return;
            if (btManager != null && btManager.isRadioSilenceEnabled()) {
                Log.d(TAG, "CoT retry deferred uid=" + cotUid + " — Radio Silence active");
                scheduleCotRetryCheck(cotUid);
                return;
            }
            if (pending.retryCount < COT_MAX_RETRIES) {
                pending.retryCount++;
                Log.d(TAG, "CoT retry " + pending.retryCount + "/" + COT_MAX_RETRIES
                        + " uid=" + cotUid);
                sendCotOverRadioNoRetry(pending.event);
                scheduleCotRetryCheck(cotUid);
            } else {
                pendingOutboundCots.remove(cotUid);
                Log.w(TAG, "CoT delivery gave up after " + pending.retryCount
                        + " retries uid=" + cotUid);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in CoT retry timer uid=" + cotUid, e);
        }
    }

    public void handleCotAck(String cotUid) {
        if (cotUid == null || cotUid.trim().isEmpty()) return;
        PendingOutboundCot removed = pendingOutboundCots.remove(cotUid.trim());
        if (removed != null) {
            Log.d(TAG, "CoT ACK received uid=" + cotUid.trim()
                    + " after " + removed.retryCount + " retries");
        }
    }

    private void maybeHandleInboundWifiCotAck(CotEvent event) {
        if (!CotBuilder.isWifiCotAck(event)) {
            return;
        }
        String refUid = CotBuilder.extractWifiCotAckRefUid(event);
        if (refUid == null || refUid.isEmpty()) {
            return;
        }
        if (refUid.startsWith(ANDROID_UID_PREFIX)) {
            Log.d(TAG, "CoT WiFi ACK ignored — refUid is device uid, not map uid: " + refUid);
            return;
        }
        handleCotAck(refUid);
        Log.d(TAG, "CoT WiFi ACK received refUid=" + refUid);
    }

    /** Map item types eligible for WiFi delivery ACK — excludes SA/PLI ({@code a-f-*}). */
    private boolean shouldAckMapCotOverWifi(CotEvent event) {
        if (event == null || CotBuilder.isWifiNetworkOnlyCot(event)) {
            return false;
        }
        String type = event.getType();
        if (type == null) {
            return false;
        }
        if (!type.startsWith("b-m-p")
                && !type.startsWith("b-m-r")
                && !type.startsWith("u-")
                && !type.startsWith("b-r-f-h")
                && !type.startsWith("t-x-d-d")) {
            return false;
        }
        String cotUid = event.getUID();
        if (cotUid == null || cotUid.trim().isEmpty()
                || cotUid.trim().startsWith(ANDROID_UID_PREFIX)) {
            return false;
        }
        String creatorUid = CotBuilder.extractMapCotSenderUid(event);
        if (creatorUid == null || creatorUid.trim().isEmpty()) {
            return false;
        }
        String self = null;
        try {
            self = MapView.getDeviceUid();
        } catch (Exception ignored) {
        }
        return self == null || !self.equalsIgnoreCase(creatorUid.trim());
    }

    private void maybeSendWifiCotAck(CotEvent event, String senderEndpointId) {
        if (event == null) {
            return;
        }
        if (!shouldAckMapCotOverWifi(event)) {
            return;
        }
        String cotUid = event.getUID();
        if (cotUid == null || cotUid.trim().isEmpty()) {
            return;
        }
        String trimUid = cotUid.trim();
        long now = System.currentTimeMillis();
        Long dedupeUntil = wifiCotAckSentUntil.get(trimUid);
        if (dedupeUntil != null && now < dedupeUntil) {
            return;
        }
        IndividualContact sender = resolveWifiCotAckRecipientContact(event, senderEndpointId);
        if (sender == null) {
            Log.d(TAG, "CoT WiFi ACK skipped — no contact for sender endpoint="
                    + senderEndpointId
                    + " cotSenderUid=" + CotBuilder.extractMapCotSenderUid(event)
                    + " cotSenderCallsign=" + CotBuilder.extractMapCotSenderCallsign(event)
                    + " mapUid=" + trimUid);
            return;
        }
        MapView mv = MapView.getMapView();
        if (mv == null) {
            return;
        }
        CotEvent ack = CotBuilder.buildWifiCotAck(mv, trimUid);
        if (ack == null || !ack.isValid()) {
            return;
        }
        try {
            com.atakmap.comms.CotDispatcher dispatcher =
                    CotMapComponent.getExternalDispatcher();
            if (dispatcher == null) {
                return;
            }
            dispatcher.dispatchToContact(ack, sender, CoTSendMethod.POINT_TO_POINT);
            wifiCotAckSentUntil.put(trimUid, now + WIFI_COT_ACK_DEDUPE_MS);
            Log.d(TAG, "CoT WiFi ACK sent refUid=" + trimUid
                    + " toContact=" + sender.getUID());
        } catch (Exception e) {
            Log.w(TAG, "CoT WiFi ACK send failed refUid=" + trimUid, e);
        }
    }

    private void maybeSendWifiCotAckFromPlacedIntent(Intent intent) {
        if (intent == null
                || !"com.atakmap.android.maps.COT_PLACED".equals(intent.getAction())) {
            return;
        }
        CotEvent event = parseCotEventFromMapIntent(intent);
        if (event == null) {
            return;
        }
        String senderHint = CotBuilder.extractMapCotSenderUid(event);
        maybeSendWifiCotAck(event, senderHint != null ? senderHint : "");
    }

    private CotEvent parseCotEventFromMapIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        String cotXml = intent.getStringExtra("xml");
        if (cotXml == null || cotXml.isEmpty()) {
            cotXml = intent.getStringExtra("cotXml");
        }
        if (cotXml == null || cotXml.isEmpty()) {
            cotXml = intent.getStringExtra("cot");
        }
        CotEvent event = null;
        if (cotXml != null && !cotXml.isEmpty()) {
            try {
                event = CotEvent.parse(cotXml);
            } catch (Exception ignored) {
            }
        }
        if (event == null) {
            String uid = intent.getStringExtra("uid");
            if (uid != null && !uid.isEmpty() && mapView != null) {
                MapItem item = mapView.getRootGroup().deepFindUID(uid);
                if (item != null) {
                    try {
                        event = CotEventFactory.createCotEvent(item);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return event;
    }

    private IndividualContact findNetworkContactByUid(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return null;
        }
        try {
            Contact c = Contacts.getInstance().getContactByUuid(uid.trim());
            if (c instanceof IndividualContact) {
                return (IndividualContact) c;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private IndividualContact resolveWifiCotAckRecipientContact(CotEvent event, String rxEndpointId) {
        String senderUid = CotBuilder.extractMapCotSenderUid(event);
        if (senderUid != null) {
            IndividualContact byUid = findNetworkContactByUid(senderUid);
            if (byUid != null) {
                return byUid;
            }
            String resolved = resolveBtechUidForId(senderUid);
            if (resolved != null) {
                byUid = findNetworkContactByUid(resolved);
                if (byUid != null) {
                    return byUid;
                }
            }
        }
        if (rxEndpointId != null && rxEndpointId.trim().startsWith(ANDROID_UID_PREFIX)) {
            IndividualContact byRx = findNetworkContactByUid(rxEndpointId.trim());
            if (byRx != null) {
                return byRx;
            }
        }
        String callsign = CotBuilder.extractMapCotSenderCallsign(event);
        if (callsign != null && !callsign.isEmpty()) {
            try {
                Contact c = com.uvpro.plugin.chat.ChatBridge.findContactByCallsignVariants(
                        Contacts.getInstance(), callsign);
                if (c instanceof IndividualContact) {
                    return (IndividualContact) c;
                }
            } catch (Exception ignored) {
            }
        }
        String cotEndpoint = com.uvpro.plugin.contacts.ContactReachability
                .extractCotContactEndpoint(event);
        IndividualContact byHost = findNetworkContactByConnectHint(cotEndpoint);
        if (byHost != null) {
            return byHost;
        }
        return findNetworkContactByConnectHint(rxEndpointId);
    }

    private IndividualContact findNetworkContactByConnectHint(String connectHint) {
        String host = parseConnectHintHost(connectHint);
        if (host == null || host.isEmpty()) {
            return null;
        }
        return com.uvpro.plugin.contacts.ContactReachability
                .findContactByRoutableHost(host, this);
    }

    private static String parseConnectHintHost(String connectHint) {
        if (connectHint == null || connectHint.trim().isEmpty()) {
            return null;
        }
        String trimmed = connectHint.trim();
        try {
            com.atakmap.comms.NetConnectString ncs =
                    com.atakmap.comms.NetConnectString.fromString(trimmed);
            if (ncs != null) {
                String host = ncs.getHost();
                if (host != null && !host.trim().isEmpty() && !"*".equals(host.trim())) {
                    return host.trim();
                }
            }
        } catch (Exception ignored) {
        }
        if (trimmed.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            return trimmed;
        }
        return null;
    }

    /**
     * Team string from ATAK Settings (sender side) for outbound GPS TLV extension.
     */
    private String resolveLocalAtakTeamForOutbound() {
        try {
            String t = com.atakmap.android.chat.ChatManagerMapComponent.getTeamName();
            if (t != null && !t.trim().isEmpty()) {
                return t.trim();
            }
        } catch (Exception ignored) {
        }
        try {
            return com.uvpro.plugin.ui.SettingsFragment.getAtakTeamColor(pluginContext);
        } catch (Exception ignored) {
        }
        return "Cyan";
    }

    /**
     * Send local GPS position over radio as compact GPS packet on a specific transport.
     */
    public boolean sendPositionOverRadio(BtConnectionManager txManager,
                                      double lat, double lon, double alt,
                                      float speed, float course, int battery) {
        if (txManager == null || !txManager.isConnected()) {
            return false;
        }
        if (txManager instanceof MeshBtConnectionManager) {
            long waitMs = com.uvpro.plugin.protocol.RfTxArbitrator.get().millisUntilOpenRlClear();
            if (waitMs > 0) {
                final int generation = meshGpsDeferGeneration.incrementAndGet();
                final BtConnectionManager tx = txManager;
                Log.d(TAG, "GPS beacon deferred " + waitMs + "ms — channel busy");
                cotRetryExecutor.schedule(() -> {
                    if (generation != meshGpsDeferGeneration.get()) {
                        return;
                    }
                    sendPositionOverRadio(tx, lat, lon, alt, speed, course, battery);
                }, waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                return true;
            }
        }

        com.uvpro.plugin.protocol.RfTxArbitrator.get().markOpenRlTxStart();
        try {
            UVProPacket packet = UVProPacket.createGpsPacket(
                    com.uvpro.plugin.util.CallsignUtil.toRadioCallsign(localCallsign()),
                    localCallsign(), lat, lon, (float) alt,
                    speed, course, battery,
                    resolveLocalAtakTeamForOutbound());

            byte[] packetBytes = packet.encode();

            // Encrypt entire packet bytes if enabled
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                packetBytes = encryptionManager.encrypt(packetBytes);
                if (packetBytes == null) {
                    Log.e(TAG, "Encryption failed — aborting GPS send");
                    return false;
                }
            }

            Ax25Frame frame = Ax25Frame.createUVProFrame(
                    localCallsign(), 0, packetBytes);
            byte[] ax25 = frame.encode();

            Log.d(TAG, "Sending GPS beacon: " + ax25.length + " bytes");
            return txManager.sendKissFrame(ax25);
        } catch (Exception e) {
            Log.e(TAG, "Error sending position over radio", e);
            return false;
        } finally {
            com.uvpro.plugin.protocol.RfTxArbitrator.get().markOpenRlTxEnd();
        }
    }

    /**
     * Send local GPS position over radio using the active transmit manager.
     */
    public void sendPositionOverRadio(double lat, double lon, double alt,
                                      float speed, float course, int battery) {
        sendPositionOverRadio(btManager, lat, lon, alt, speed, course, battery);
    }

    /**
     * True for map markers injected from inbound APRS (see {@link #META_UVPRO_APRS}).
     */
    public static boolean isUvproAprsMarker(com.atakmap.android.maps.MapItem item) {
        return item != null
                && item.getMetaBoolean(META_UVPRO_APRS, false)
                && !item.getMetaBoolean(META_UVPRO_MESH_REPEATER, false);
    }

    /** True for MeshCore advert markers (node or repeater). */
    public static boolean isUvproMeshMarker(com.atakmap.android.maps.MapItem item) {
        if (item == null) {
            return false;
        }
        if (item.getMetaBoolean(META_UVPRO_MESH_REPEATER, false)
                || item.getMetaBoolean(META_UVPRO_MESH_NODE, false)) {
            return true;
        }
        String uid = item.getUID();
        return uid != null && (uid.startsWith("MESHCORE-RPTR-") || uid.startsWith("MESHCORE-NODE-"));
    }

    /**
     * Opens the APRS metadata panel (packet comment, symbol, telemetry — not generic CoT point UI).
     */
    public void openAprsDetailsPanel(com.atakmap.android.maps.MapItem item) {
        if (item == null || this.mapView == null) {
            return;
        }
        final String uid = item.getUID();
        Runnable open = () -> {
            try {
                Intent details = new Intent(
                        com.uvpro.plugin.aprs.AprsDetailsDropDownReceiver.SHOW_APRS_DETAILS);
                details.putExtra(
                        com.uvpro.plugin.aprs.AprsDetailsDropDownReceiver.EXTRA_TARGET_UID, uid);
                AtakBroadcast.getInstance().sendBroadcast(details);
                AtakBroadcast.getInstance().sendBroadcast(
                        new Intent("com.atakmap.android.maps.HIDE_MENU"));
                Log.d(TAG, "Opened APRS details panel uid=" + uid);
            } catch (Exception e) {
                Log.w(TAG, "openAprsDetailsPanel failed uid=" + uid, e);
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            open.run();
        } else {
            this.mapView.post(open);
        }
    }

    /**
     * Store formatted APRS metadata on the map marker for the details panel.
     */
    public void setAprsMarkerDetails(String uid, String detailsText) {
        if (uid == null || uid.isEmpty() || this.mapView == null) {
            return;
        }
        Runnable apply = () -> {
            try {
                com.atakmap.android.maps.MapItem item =
                        this.mapView.getRootGroup().deepFindUID(uid);
                if (item != null) {
                    item.setMetaBoolean(META_UVPRO_APRS, true);
                    if (detailsText != null && !detailsText.trim().isEmpty()) {
                        item.setMetaString(META_UVPRO_APRS_DETAILS, detailsText.trim());
                    }
                    Intent refresh = new Intent(
                            com.uvpro.plugin.aprs.AprsDetailsDropDownReceiver.REFRESH_APRS_DETAILS);
                    refresh.putExtra(
                            com.uvpro.plugin.aprs.AprsDetailsDropDownReceiver.EXTRA_TARGET_UID, uid);
                    AtakBroadcast.getInstance().sendBroadcast(refresh);
                }
            } catch (Exception e) {
                Log.w(TAG, "setAprsMarkerDetails failed uid=" + uid, e);
            }
            markAprsMapItem(uid);
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            apply.run();
        } else {
            this.mapView.post(apply);
        }
    }

    /**
     * Tag a marker as UV-PRO APRS (call after position inject when iconset may be absent).
     */
    public void markAprsMapItem(String uid) {
        if (uid == null || uid.isEmpty() || this.mapView == null) {
            return;
        }
        Runnable tag = () -> {
            try {
                com.atakmap.android.maps.MapItem item =
                        this.mapView.getRootGroup().deepFindUID(uid);
                if (item != null) {
                    item.setMetaBoolean(META_UVPRO_APRS, true);
                }
            } catch (Exception e) {
                Log.w(TAG, "markAprsMapItem failed uid=" + uid, e);
            }
            applyAprsMarkerPresentation(uid);
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            tag.run();
        } else {
            this.mapView.post(tag);
        }
    }

    /** Tag marker as Mesh repeater so APRS-only UI/actions are suppressed. */
    public void markMeshRepeaterMapItem(String uid) {
        if (uid == null || uid.isEmpty() || this.mapView == null) {
            return;
        }
        Runnable tag = () -> {
            try {
                com.atakmap.android.maps.MapItem item =
                        this.mapView.getRootGroup().deepFindUID(uid);
                if (item != null) {
                    item.setMetaBoolean(META_UVPRO_MESH_REPEATER, true);
                    item.setMetaBoolean(META_UVPRO_MESH_NODE, false);
                    item.setMetaBoolean(META_UVPRO_APRS, false);
                }
            } catch (Exception e) {
                Log.w(TAG, "markMeshRepeaterMapItem failed uid=" + uid, e);
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            tag.run();
        } else {
            this.mapView.post(tag);
        }
    }

    /** Tag marker as Mesh node so custom Mesh details panel can handle map-click selection. */
    public void markMeshNodeMapItem(String uid) {
        if (uid == null || uid.isEmpty() || this.mapView == null) {
            return;
        }
        Runnable tag = () -> {
            try {
                com.atakmap.android.maps.MapItem item =
                        this.mapView.getRootGroup().deepFindUID(uid);
                if (item != null) {
                    item.setMetaBoolean(META_UVPRO_MESH_NODE, true);
                    item.setMetaBoolean(META_UVPRO_MESH_REPEATER, false);
                    item.setMetaBoolean(META_UVPRO_APRS, false);
                }
            } catch (Exception e) {
                Log.w(TAG, "markMeshNodeMapItem failed uid=" + uid, e);
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            tag.run();
        } else {
            this.mapView.post(tag);
        }
    }

    /** Store Mesh summary/details on a marker for the one-page Mesh details dropdown. */
    public void setMeshMarkerDetails(String uid, String detailsText) {
        if (uid == null || uid.isEmpty() || this.mapView == null) {
            return;
        }
        Runnable apply = () -> {
            try {
                com.atakmap.android.maps.MapItem item =
                        this.mapView.getRootGroup().deepFindUID(uid);
                if (item != null && detailsText != null && !detailsText.trim().isEmpty()) {
                    item.setMetaString(META_UVPRO_MESH_DETAILS, detailsText.trim());
                }
            } catch (Exception e) {
                Log.w(TAG, "setMeshMarkerDetails failed uid=" + uid, e);
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            apply.run();
        } else {
            this.mapView.post(apply);
        }
    }

    /**
     * Promote MeshCore advert markers as contact-card capable ATAK contacts.
     * Keeps pinwheel contact card action enabled and updates display metadata.
     */
    public void promoteMeshContactMapItem(String uid, String displayName) {
        if (uid == null || uid.isEmpty() || this.mapView == null) {
            return;
        }
        Runnable work = () -> {
            try {
                com.atakmap.android.maps.MapItem item =
                        this.mapView.getRootGroup().deepFindUID(uid);
                if (item != null) {
                    if (uid.startsWith("MESHCORE-RPTR-")) {
                        item.setMetaString("menu", "menus/default_item_w_type.xml");
                    } else {
                        com.uvpro.plugin.contacts.ContactRadialMenuUtil
                                .applyPingCapableRadialMenu(item, uid);
                    }
                    item.setMetaBoolean("sendable", true);
                    if (displayName != null && !displayName.trim().isEmpty()) {
                        item.setTitle(displayName.trim());
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "promoteMeshContactMapItem map metadata failed uid=" + uid, e);
            }
            try {
                registerBtechContactUid(uid);
                if (displayName != null && !displayName.trim().isEmpty()) {
                    registerBtechContactId(displayName.trim(), uid);
                }
            } catch (Exception e) {
                Log.w(TAG, "promoteMeshContactMapItem contacts failed uid=" + uid, e);
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            work.run();
        } else {
            this.mapView.post(work);
        }
    }

    /**
     * After CoT import: APRS usericon markers get label bounds and a minimal pinwheel whose
     * Details action opens CoT Info (remarks), not the TAK contact-card menu.
     */
    private void applyAprsMarkerPresentation(String uid) {
        if (uid == null || uid.isEmpty() || this.mapView == null) {
            return;
        }
        try {
            com.atakmap.android.maps.MapItem item =
                    this.mapView.getRootGroup().deepFindUID(uid);
            if (!(item instanceof com.atakmap.android.maps.Marker)) {
                return;
            }
            String iconPath = item.getMetaString(UserIcon.IconsetPath, "");
            if (item.getMetaBoolean(META_UVPRO_MESH_REPEATER, false)
                    || isMeshRepeaterIconPath(iconPath)) {
                return;
            }
            boolean aprs = item.getMetaBoolean(META_UVPRO_APRS, false)
                    || (!iconPath.isEmpty()
                    && iconPath.startsWith(AprsSymbolMapper.ICONSET_UID));
            if (!aprs) {
                return;
            }
            item.setMetaBoolean(META_UVPRO_APRS, true);
            com.atakmap.android.maps.Marker marker =
                    (com.atakmap.android.maps.Marker) item;
            applyAprsUpscaledIcon(marker, iconPath);
            marker.setShowLabel(true);
            marker.setMinLabelRenderResolution(0.0d);
            marker.setMaxLabelRenderResolution(0.1d);
            marker.setMetaString("menu", "menus/default_item_w_type.xml");
            removeAprsFromContactsPane(uid);
        } catch (Exception e) {
            Log.w(TAG, "applyAprsMarkerPresentation failed uid=" + uid, e);
        }
    }

    private boolean isMeshRepeaterIconPath(String iconPath) {
        if (iconPath == null || iconPath.isEmpty()) {
            return false;
        }
        return iconPath.endsWith("/meshcore.png") || iconPath.endsWith("/meschore.png");
    }

    /**
     * UserIcon PNGs from APRS iconset can render small at some DPI/zoom combinations.
     * Replace marker icon with an upscaled bitmap for better readability.
     */
    private void applyAprsUpscaledIcon(com.atakmap.android.maps.Marker marker, String iconPath) {
        if (marker == null || iconPath == null || iconPath.isEmpty()) {
            return;
        }
        try {
            Icon cached = aprsUpscaledIconCache.get(iconPath);
            if (cached == null) {
                UserIcon userIcon = UserIcon.GetIconFromIconsetPath(
                        iconPath, true, this.mapView.getContext());
                if (userIcon == null || userIcon.getBitMap() == null) {
                    return;
                }
                Bitmap src = userIcon.getBitMap();
                int srcW = Math.max(1, src.getWidth());
                int srcH = Math.max(1, src.getHeight());
                float scale = Math.max(
                        (float) APRS_ICON_TARGET_PX / (float) srcW,
                        (float) APRS_ICON_TARGET_PX / (float) srcH);
                int outW = Math.max(APRS_ICON_TARGET_PX, Math.round(srcW * scale));
                int outH = Math.max(APRS_ICON_TARGET_PX, Math.round(srcH * scale));
                Bitmap scaled = Bitmap.createScaledBitmap(src, outW, outH, true);
                String encoded = IconUtilities.encodeBitmap(scaled);
                if (encoded == null || encoded.isEmpty()) {
                    return;
                }
                cached = new Icon.Builder().setImageUri(0, encoded).build();
                aprsUpscaledIconCache.put(iconPath, cached);
            }
            marker.setMetaBoolean("adapt_marker_icon", false);
            marker.setIcon(cached);
        } catch (Exception e) {
            Log.w(TAG, "applyAprsUpscaledIcon failed path=" + iconPath, e);
        }
    }

    /**
     * APRS map markers must not appear in ATAK's Contacts list (map + details panel only).
     */
    private void removeAprsFromContactsPane(String uid) {
        if (uid == null || uid.isEmpty()) {
            return;
        }
        try {
            Contact existing = Contacts.getInstance().getContactByUuid(uid);
            if (existing != null) {
                Contacts.getInstance().removeContact(existing);
                btechContactUids.remove(uid);
                btechIdToUid.entrySet().removeIf(e -> uid.equals(e.getValue()));
                Log.d(TAG, "Removed APRS from Contacts pane uid=" + uid);
            }
        } catch (Exception e) {
            Log.w(TAG, "removeAprsFromContactsPane failed uid=" + uid, e);
        }
    }

    /**
     * Dispatch a CotEvent into ATAK's internal processing.
     */
    private void dispatchCotEvent(CotEvent event) {
        // Use ATAK's internal CotMapComponent to dispatch
        try {
            CotMapComponent.getInternalDispatcher().dispatch(event);
            Log.d(TAG, "Dispatched CoT event: " + event.getUID());
            applyAprsMarkerPresentation(event.getUID());

            if (BuildConfig.DEBUG) {
                try {
                    com.atakmap.android.maps.MapItem item =
                            com.atakmap.android.maps.MapView.getMapView()
                                    .getRootGroup()
                                    .deepFindUID(event.getUID());

                    if (item != null) {
                        Log.d(TAG, "MARKER_DEBUG uid=" + item.getUID()
                                + " title=" + item.getTitle()
                                + " type=" + item.getType()
                                + " callsign=" + item.getMetaString("callsign", "NULL")
                                + " team=" + item.getMetaString("team", "NULL")
                                + " labels_on=" + item.hasMetaValue("labels_on")
                                + " hideLabel=" + item.hasMetaValue("hideLabel")
                                + " menu=" + item.getMetaString("menu", "NULL"));

                        if (item instanceof com.atakmap.android.maps.Marker) {
                            Log.d(TAG, "MARKER_DEBUG marker_class=true");
                        }
                    } else {
                        Log.d(TAG, "MARKER_DEBUG item not found uid=" + event.getUID());
                    }
                } catch (Exception dbg) {
                    Log.e(TAG, "MARKER_DEBUG failed", dbg);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to dispatch via CotMapComponent, "
                    + "trying broadcast", e);
            // Fallback: send as broadcast intent
            try {
                Intent intent = new Intent("com.atakmap.android.maps.COT_PLACED");
                intent.putExtra("xml", event.toString());
                AtakBroadcast.getInstance().sendBroadcast(intent);
            } catch (Exception e2) {
                Log.e(TAG, "Failed to broadcast CoT event", e2);
            }
        }
    }

    private void maybeNoteInboundNetworkGeoChat(CotEvent event) {
        if (chatBridge == null || event == null) {
            return;
        }
        if (!"b-t-f".equals(event.getType())) {
            return;
        }
        if (com.uvpro.plugin.contacts.ContactReachability.isPolicyEnabled(pluginContext)
                && !com.uvpro.plugin.contacts.ContactReachability
                .isInboundNetworkGeoChatForLocalDevice(event)) {
            return;
        }
        String lineUid = ChatBridge.resolveGeoChatLineUid(event);
        if (lineUid == null) {
            lineUid = event.getUID();
        }
        String senderUid = GeoChatContactListHelper.extractChatSenderUid(event);
        String message = extractGeoChatRemarks(event);
        chatBridge.noteInboundGeoChatDelivered(lineUid, senderUid, message, 0);
        String eventUid = event.getUID();
        if (eventUid != null && lineUid != null && !eventUid.equalsIgnoreCase(lineUid)) {
            chatBridge.noteInboundGeoChatDelivered(eventUid, senderUid, message, 0);
        }
    }

    /**
     * Start listening for outgoing CoT events to relay to radio.
     * Uses ATAK's PreSendProcessor to intercept all outgoing CoT.
     */
    public void startOutgoingRelay() {
        Log.d(TAG, "Outgoing CoT relay: "
                + (relayOutgoingSa ? "enabled" : "disabled"));

        outboundCommsLogger = new CommsLogger() {
            @Override
            public void dispose() {
            }

            @Override
            public void logSend(CotEvent event, String dest) {
                maybeRelayGeoChatFromCommsLogger(event);
                maybeRelayMapCotFromCommsLogger(event, dest);
            }

            @Override
            public void logSend(CotEvent event, String[] dests) {
                maybeRelayGeoChatFromCommsLogger(event);
            }

            @Override
            public void logReceive(CotEvent event, String src, String dest) {
                maybeHandleInboundWifiCotAck(event);
                noteInboundNetworkTransport(event);
                maybeNoteInboundNetworkGeoChat(event);
                maybeHandleInboundNetworkWifiPing(event);
                maybeSendWifiCotAck(event, src);
                MapView pingMv = MapView.getMapView();
                if (pingMv != null) {
                    PingReplyNotifier.maybeNotifyPingReplyFromCot(
                            pingMv.getContext(), event);
                }
                maybeSaRelayInboundNetworkCot(event);
            }
        };
        try {
            CommsMapComponent.getInstance().registerCommsLogger(outboundCommsLogger);
            Log.d(TAG, "Registered CommsLogger for outbound GeoChat capture");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register CommsLogger", e);
        }

        // Capture locally placed/edited/deleted CoT so RF-only nodes can propagate
        // point updates/deletes without requiring manual "send again".
        localCotBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                maybeSendWifiCotAckFromPlacedIntent(intent);
                maybeRelayLocalCotBroadcast(intent);
            }
        };
        try {
            AtakBroadcast.DocumentedIntentFilter cotFilter =
                    new AtakBroadcast.DocumentedIntentFilter();
            cotFilter.addAction("com.atakmap.android.maps.COT_PLACED");
            cotFilter.addAction("com.atakmap.android.maps.COT_DELETED");
            AtakBroadcast.getInstance().registerReceiver(localCotBroadcastReceiver, cotFilter);
            Log.d(TAG, "Registered local COT_PLACED/COT_DELETED relay receiver");
        } catch (Exception e) {
            Log.w(TAG, "Failed to register local COT relay receiver", e);
        }

        // Broadcast/share from ContactPresenceDropdown persists the map item after dispatch.
        // When WiFi/TAK is unavailable that dispatch can be a no-op; relay from the item here.
        sharedMapItemListener = event -> maybeRelaySharedMapItem(event);
        try {
            mapView.getMapEventDispatcher().addMapEventListener(
                    MapEvent.ITEM_PERSIST, sharedMapItemListener);
            mapView.getMapEventDispatcher().addMapEventListener(
                    MapEvent.ITEM_SHARED, sharedMapItemListener);
            Log.d(TAG, "Registered ITEM_PERSIST/ITEM_SHARED relay listener");
        } catch (Exception e) {
            Log.w(TAG, "Failed to register shared map item relay listener", e);
        }

        registerItemRemovedListener();

        preSendProcessor = (event, toUIDs) -> {
            if (event == null) return;

            Log.d(TAG, "PreSendProcessor fired: type=" + event.getType()
                    + " uid=" + event.getUID()
                    + " toUIDs=" + (toUIDs == null ? "null" : java.util.Arrays.toString(toUIDs)));

            String type = event.getType();
            // GeoChat delivery/read receipts must never go back out over RF.
            if ("b-t-f-r".equals(type) || "b-t-f-d".equals(type)) {
                return;
            }
            // Wi‑Fi unicast keepalive SA is Wi‑Fi-only (dispatchToContact); never RF-relay.
            if (CotBuilder.isWifiKeepaliveCot(event)) {
                return;
            }
            if (CotBuilder.isWifiNetworkOnlyCot(event)) {
                return;
            }
            boolean btConnected = btManager != null && btManager.isConnected();

            // Log GeoChat BEFORE BT gate — previous bug: early return hid whether PreSend
            // fired at all (shows up as zero lines when BLE disconnected during send tests).
            if ("b-t-f".equals(type)) {
                ensureOutboundGeoChatDestinationsKnown(toUIDs);
                Log.d(TAG, "PreSend GeoChat bluetoothOk=" + btConnected
                        + " uid=" + event.getUID()
                        + " toUIDs="
                        + (toUIDs == null ? "null"
                        : java.util.Arrays.toString(toUIDs))
                        + " registeredBtechUids=" + btechContactUids.size());
            }

            if (!btConnected) return;

            if (shouldSkipOutboundRelayWasInboundInject(event.getUID())) {
                return;
            }

            // Contact-targeted send: only relay when ATAK is sending to a
            // plugin-registered radio contact.
            boolean targetsBtechContact = false;
            if (toUIDs != null && toUIDs.length > 0) {
                for (String uid : toUIDs) {
                    if (uid == null) continue;
                    String trimmed = uid.trim();
                    // Fast path: already registered in our in-memory set (populated on beacon).
                    if (btechContactUids.contains(trimmed)) {
                        targetsBtechContact = true;
                        break;
                    }
                    // Fallback: contact persisted from a previous session but no beacon yet
                    // this session — check whether it carries our PluginConnector.
                    try {
                        Contact c = Contacts.getInstance().getContactByUuid(trimmed);
                        Log.d(TAG, "PreSend UID lookup: " + trimmed + " → " + (c == null ? "null" : c.getClass().getSimpleName()));
                        if (c instanceof IndividualContact) {
                            com.atakmap.android.contact.Connector conn =
                                    ((IndividualContact) c).getConnector(
                                            PluginConnector.CONNECTOR_TYPE);
                            if (conn instanceof PluginConnector
                                    && ChatBridge.ACTION_PLUGIN_CONTACT_GEOCHAT_SEND.equals(
                                            conn.getConnectionString())) {
                                targetsBtechContact = true;
                                // Also register now so future checks are fast.
                                btechContactUids.add(trimmed);
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (type == null) return;

            // GeoChat (b-t-f) often lacks reliable toUID[] in PreSendProcessor; infer
            // destination from CoT (__chat/chatgrp/chatroom/remarks) like SEND_MESSAGE/COT_PLACED.
            if (!targetsBtechContact && "b-t-f".equals(type)) {
                boolean geoRelay = shouldRelayGeoChatToRadio(event);
                Log.d(TAG, "GeoChat shouldRelayGeoChatToRadio=" + geoRelay);
                if (geoRelay) {
                    targetsBtechContact = true;
                    Log.d(TAG, "GeoChat to BTECH contact via CoT routing (weak/missing toUIDs)");
                }
            }

            if (targetsBtechContact) {
                if ("b-t-f".equals(type)) {
                    Log.d(TAG, "Relaying contact-targeted CoT to radio: type=" + type
                            + " uid=" + event.getUID()
                            + " toUIDs=" + java.util.Arrays.toString(toUIDs));
                    if (chatBridge != null) {
                        new Thread(() -> chatBridge.relayOutboundGeoChatCot(event)).start();
                        return;
                    }
                }
                if (isRelayableMapCotType(type)) {
                    if (isSelfPliBeacon(event)) {
                        return;
                    }
                    if (!shouldRelayMapCotToContactUids(toUIDs)) {
                        Log.d(TAG, "Skipping RF relay for WiFi-only map CoT recipients"
                                + " (plugin contact): type=" + type
                                + " uid=" + event.getUID()
                                + " toUIDs=" + java.util.Arrays.toString(toUIDs));
                        cancelPendingCotRfRetry(event.getUID());
                        return;
                    }
                    Log.d(TAG, "Relaying contact-targeted map CoT to radio: type=" + type
                            + " uid=" + event.getUID()
                            + " toUIDs=" + java.util.Arrays.toString(toUIDs));
                    relayContactTargetedCotOverRadio(event, toUIDs);
                    return;
                }
                Log.d(TAG, "Relaying contact-targeted CoT to radio: type=" + type
                        + " uid=" + event.getUID()
                        + " toUIDs=" + java.util.Arrays.toString(toUIDs));
                relayContactTargetedCotOverRadio(event, toUIDs);
                return;
            }

            if (isRelayableMapCotType(type) && shouldRelayMapCotToContactUids(toUIDs)) {
                if (isSelfPliBeacon(event)) {
                    return;
                }
                Log.d(TAG, "Relaying contact-targeted map CoT to radio: type=" + type
                        + " uid=" + event.getUID()
                        + " toUIDs=" + java.util.Arrays.toString(toUIDs));
                relayContactTargetedCotOverRadio(event, toUIDs);
                return;
            }

            if (isRelayableMapCotType(type) && toUIDs != null && toUIDs.length > 0) {
                Log.d(TAG, "Skipping RF relay for WiFi-only map CoT recipients: type=" + type
                        + " uid=" + event.getUID()
                        + " toUIDs=" + java.util.Arrays.toString(toUIDs));
                cancelPendingCotRfRetry(event.getUID());
                return;
            }

            // Net-wide map broadcasts (Auto Send): throttled RF chirp, no retry storm.
            if (shouldRelayBroadcastMapCot(event, toUIDs)) {
                maybeRelayBroadcastMapCotToRadio(event, toUIDs);
                return;
            }

            // Optional broadcast SA relay (rate-limited) — does NOT depend on
            // a specific destination contact.
            if (!relayOutgoingSa) return;

            long now = System.currentTimeMillis();
            if (now - lastSaRelay < SA_RELAY_RF_BROADCAST_INTERVAL_MS) return;

            boolean shouldRelay = type.startsWith("a-f-")   // friendly SA
                    || type.startsWith("b-r-f-h")           // CASEVAC/medevac
                    || type.startsWith("b-m-p")             // markers/points
                    || type.equals("b-t-f")                 // geochat
                    || type.startsWith("u-");               // user-defined
            if (!shouldRelay) return;

            // Don't relay our own injected radio events (avoid loops / chatter).
            // Radio-injected contacts and events use ANDROID-* UIDs.
            String uid = event.getUID();
            if (uid != null && uid.startsWith("ANDROID-")) return;

            lastSaRelay = now;
            Log.d(TAG, "Relaying broadcast CoT to radio: type=" + type
                    + " uid=" + event.getUID());
            new Thread(() -> sendCotOverRadio(event)).start();
        };

        try {
            CommsMapComponent.getInstance().registerPreSendProcessor(
                    preSendProcessor);
            Log.d(TAG, "Registered PreSendProcessor for outgoing CoT relay");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register PreSendProcessor", e);
        }
    }

    private void noteInboundNetworkTransport(CotEvent event) {
        if (event == null) {
            return;
        }
        String uid = event.getUID();
        if (uid == null || uid.trim().isEmpty()) {
            return;
        }
        String endpoint = com.uvpro.plugin.contacts.ContactReachability.extractCotContactEndpoint(event);
        if (endpoint != null && !endpoint.trim().isEmpty()) {
            markWifiNativeContact(uid.trim());
            return;
        }
        if (isFriendlyNetworkSa(event.getType())) {
            String callsign = extractInboundSaCallsign(event);
            if (callsign != null && !callsign.trim().isEmpty()) {
                ChatBridge.ensureInboundNetworkSaContact(callsign, uid.trim());
            }
        }
    }

    private static boolean isFriendlyNetworkSa(String type) {
        return type != null && type.startsWith("a-f-G-U");
    }

    private static String extractInboundSaCallsign(CotEvent event) {
        if (event == null || event.getDetail() == null) {
            return null;
        }
        try {
            CotDetail contact = event.getDetail().getFirstChildByName(0, "contact");
            if (contact == null) {
                return null;
            }
            String callsign = contact.getAttribute("callsign");
            return callsign != null ? callsign.trim() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * SA Relay: when enabled, broadcast inbound network CoT (PLI, waypoints, routes)
     * over radio so radio-only users receive the picture.
     *
     * Guards:
     *  - SA Relay setting must be on
     *  - Radio must be connected
     *  - CoT must match the relay type filter
     *  - Must NOT be CoT that we injected from the radio (loop prevention)
     *  - Must NOT be our own PLI (beacon already handles local position)
     *  - Must respect the per-UID throttle so a fast-moving contact doesn't flood the channel
     */
    private void maybeSaRelayInboundNetworkCot(CotEvent event) {
        if (event == null) return;
        if (CotBuilder.isWifiNetworkOnlyCot(event)) {
            return;
        }
        // SA Relay is RF-only — irrelevant when this device has no radio link.
        if (!isRadioConnected()) {
            return;
        }
        if (!isSaRelayEnabled()) return;
        if (btManager == null || !btManager.isConnected()) return;

        String type = event.getType();
        String uid = event.getUID();
        if (uid == null) return;

        // GeoChat: only All Chat Rooms may be relayed; DMs stay on WiFi/TAK unicast.
        if ("b-t-f".equals(type)) {
            if (relayInboundNetworkGeoChatIfAllRooms(event)) {
                Log.d(TAG, "SA Relay: forwarded All Chat Rooms uid=" + uid);
            } else {
                Log.d(TAG, "SA Relay: skip directed GeoChat uid=" + uid);
            }
            return;
        }
        if ("b-t-f-r".equals(type) || "b-t-f-d".equals(type)) {
            return;
        }

        if (!isSaRelayEligibleType(type)) return;

        // Skip CoT we injected from the radio — loop prevention
        if (shouldSkipOutboundRelayWasInboundInject(uid)) return;

        // Skip our own PLI — the beacon timer handles local position
        String localUid = null;
        try { localUid = MapView.getDeviceUid(); } catch (Exception ignored) {}
        if (uid.equals(localUid)) return;

        if (isDirectedInboundNetworkCot(event)) {
            Log.d(TAG, "SA Relay: skip directed CoT type=" + type + " uid=" + uid);
            return;
        }

        if (!com.uvpro.plugin.contacts.ContactReachability.shouldSaRelayNetworkSa(event, this)) {
            return;
        }

        // Per-UID throttle: don't relay the same contact onto RF more than once per interval.
        long now = System.currentTimeMillis();
        Long lastRelay = saRelayLastSentByUid.get(uid);
        if (lastRelay != null && (now - lastRelay) < SA_RELAY_RF_BROADCAST_INTERVAL_MS) return;
        saRelayLastSentByUid.put(uid, now);

        // Suppress unchanged periodic SA/status relays (Wi-Fi/TAK contact PLI churn).
        // Keep non-SA events (routes, markers, chat-like events) forwarding normally.
        String signature = null;
        if (type.startsWith("a-")) {
            signature = buildSaRelaySignature(event);
            String lastSignature = saRelayLastSignatureByUid.get(uid);
            if (signature != null && signature.equals(lastSignature)) {
                Log.d(TAG, "SA Relay: suppressed unchanged payload type=" + type + " uid=" + uid);
                return;
            }
        }

        Log.d(TAG, "SA Relay: broadcasting type=" + type + " uid=" + uid);
        if (signature != null) {
            saRelayLastSignatureByUid.put(uid, signature);
        }
        new Thread(() -> sendCotOverRadioNoRetry(event)).start();
    }

    /** ~1.1 m — movement beyond this changes the SA relay signature. */
    private static final double SA_RELAY_COORD_QUANTUM = 0.00001;

    /**
     * Stable SA signature: position + identity, ignoring timestamps, motion fields,
     * and other Wi-Fi PLI churn so stationary contacts are not re-relayed every 30s.
     */
    private static String buildSaRelaySignature(CotEvent event) {
        if (event == null) {
            return null;
        }
        try {
            String type = event.getType();
            String uid = event.getUID();
            String callsign = extractInboundSaCallsign(event);
            CotPoint point = event.getCotPoint();
            if (point != null) {
                double lat = point.getLat();
                double lon = point.getLon();
                if (lat != CotPoint.UNKNOWN && lon != CotPoint.UNKNOWN
                        && !Double.isNaN(lat) && !Double.isNaN(lon)) {
                    return type + "|" + uid + "|" + callsign + "|"
                            + quantizeSaRelayCoord(lat) + "|" + quantizeSaRelayCoord(lon);
                }
            }
            String xml = event.toString();
            if (xml == null || xml.isEmpty()) {
                return null;
            }
            return COT_TIME_ATTR_PATTERN.matcher(xml).replaceAll("");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String quantizeSaRelayCoord(double value) {
        long q = Math.round(value / SA_RELAY_COORD_QUANTUM);
        return String.format(Locale.US, "%.5f", q * SA_RELAY_COORD_QUANTUM);
    }

    private static boolean isSaRelayEligibleType(String type) {
        if (type == null || type.isEmpty()) return false;
        return SA_RELAY_TYPE_PATTERN.matcher(type).find();
    }

    /** True for net-wide All Chat Rooms traffic; false for DMs and other directed GeoChat. */
    private static boolean isAllChatRoomsGeoChat(CotEvent event) {
        if (event == null || !"b-t-f".equals(event.getType())) {
            return false;
        }
        try {
            com.atakmap.coremap.cot.event.CotDetail detail = event.getDetail();
            if (detail == null) {
                return false;
            }
            com.atakmap.coremap.cot.event.CotDetail chat =
                    detail.getFirstChildByName(0, "__chat");
            if (chat == null) {
                chat = detail.getFirstChildByName(0, "chat");
            }
            String room = chat != null ? chat.getAttribute("chatroom") : null;
            if (room == null || room.trim().isEmpty()) {
                room = ALL_CHAT_ROOMS;
            }
            return ALL_CHAT_ROOMS.equalsIgnoreCase(room.trim());
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Inbound network CoT addressed to a specific peer (not net-wide broadcast).
     * SA Relay must not rebroadcast these over RF.
     */
    private static boolean isDirectedInboundNetworkCot(CotEvent event) {
        if (event == null) {
            return false;
        }
        String type = event.getType();
        if ("b-t-f".equals(type)) {
            return !isAllChatRoomsGeoChat(event);
        }
        if ("b-t-f-r".equals(type) || "b-t-f-d".equals(type)) {
            return true;
        }
        try {
            com.atakmap.coremap.cot.event.CotDetail detail = event.getDetail();
            if (detail == null) {
                return false;
            }
            if (detail.getChild("__dest") != null) {
                return true;
            }
            com.atakmap.coremap.cot.event.CotDetail marti = detail.getChild("marti");
            if (marti != null && marti.getChild("dest") != null) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean relayInboundNetworkGeoChatIfAllRooms(CotEvent event) {
        if (event == null || chatBridge == null) return false;
        if (!isAllChatRoomsGeoChat(event)) return false;
        try {
            com.atakmap.coremap.cot.event.CotDetail detail = event.getDetail();
            if (detail == null) return false;

            com.atakmap.coremap.cot.event.CotDetail remarks =
                    detail.getFirstChildByName(0, "remarks");
            String message = remarks != null ? remarks.getInnerText() : null;
            if (message == null || message.trim().isEmpty()) return false;

            String lineUid = event.getUID();
            Log.d(TAG, "SA Relay: forwarding network All Chat Rooms over RF uid=" + lineUid);
            chatBridge.sendChatOverRadio(localCallsign(), ALL_CHAT_ROOMS, message, lineUid);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "SA Relay: failed forwarding network chat", e);
            return false;
        }
    }

    /**
     * Duplicate path after core comms successfully accepts the send — often skipped for
     * plugin-native contacts ("unknown contact" path). PreSend geo hook is authoritative.
     */
    private void maybeRelayGeoChatFromCommsLogger(CotEvent event) {
        if (btManager == null || !btManager.isConnected()) return;
        if (event == null || !"b-t-f".equals(event.getType())) return;
        if (shouldSkipOutboundRelayWasInboundInject(event.getUID())) return;
        Log.d(TAG, "CommsLogger logSend b-t-f uid=" + event.getUID());
        if (!shouldRelayGeoChatToRadio(event)) return;

        String uid = event.getUID();
        Log.d(TAG, "Outbound GeoChat (CommsLogger) relay: uid=" + uid);
        if (chatBridge != null) {
            new Thread(() -> chatBridge.relayOutboundGeoChatCot(event)).start();
        } else if (GeoChatContactListHelper.requiresFullCotRelay(event)) {
            scheduleSlottedGroupContactCotRelay(event);
        } else {
            new Thread(() -> sendCotOverRadio(event)).start();
        }
    }

    /**
     * Fallback when core comms accepts a net-wide map broadcast but mesh/RF is the only path.
     */
    private void maybeRelayMapCotFromCommsLogger(CotEvent event, String dest) {
        if (dest == null || !"broadcast".equalsIgnoreCase(dest.trim())) return;
        maybeRelayBroadcastMapCotToRadio(event, null);
    }

    /**
     * One RF transmission for a net-wide map point (Auto Send / broadcast): no ACK retries,
     * max once per {@link #BROADCAST_MAP_RF_MIN_INTERVAL_MS} per marker uid.
     */
    private void maybeRelayBroadcastMapCotToRadio(CotEvent event, String[] toUIDs) {
        if (btManager == null || !btManager.isConnected()) return;
        if (event == null || CotBuilder.isWifiNetworkOnlyCot(event)) return;
        if (!shouldRelayBroadcastMapCot(event, toUIDs)) return;

        String uid = event.getUID();
        if (uid == null || uid.trim().isEmpty()) return;
        String trimUid = uid.trim();
        if (shouldSkipOutboundRelayWasInboundInject(trimUid)) return;

        long now = System.currentTimeMillis();
        Long lastRf = lastBroadcastMapRfRelayByUid.get(trimUid);
        if (lastRf != null && now - lastRf < BROADCAST_MAP_RF_MIN_INTERVAL_MS) {
            Log.d(TAG, "Broadcast map RF skipped (max 1 per 2m): uid=" + trimUid
                    + " retry in " + ((BROADCAST_MAP_RF_MIN_INTERVAL_MS - (now - lastRf)) / 1000) + "s");
            return;
        }
        if (isDuplicateLocalRelay(event)) return;

        lastBroadcastMapRfRelayByUid.put(trimUid, now);
        Log.d(TAG, "Broadcast map RF chirp: type=" + event.getType() + " uid=" + trimUid);
        new Thread(() -> sendCotOverRadioNoRetry(event)).start();
    }

    /**
     * Relay map items the user shared via ContactPresenceDropdown "Broadcast".
     * ATAK marks these with {@code dontSend=true} after attempting native dispatch.
     */
    private void maybeRelaySharedMapItem(MapEvent event) {
        if (event == null || btManager == null || !btManager.isConnected()) return;

        String eventType = event.getType();
        if (!MapEvent.ITEM_PERSIST.equals(eventType)
                && !MapEvent.ITEM_SHARED.equals(eventType)) {
            return;
        }

        Class<?> from = event.getFrom();
        if (from == null
                || !ContactPresenceDropdown.class.getName().equals(from.getName())) {
            return;
        }

        Bundle extras = event.getExtras();
        if (extras == null || !extras.getBoolean("dontSend", false)) {
            return;
        }
        if (extras.getBoolean("internal", true)) {
            return;
        }
        String[] toUIDs = extras.getStringArray("toUIDs");
        if (toUIDs != null && toUIDs.length > 0) {
            return;
        }

        MapItem item = event.getItem();
        if (item == null) return;

        CotEvent cotEvent;
        try {
            cotEvent = CotEventFactory.createCotEvent(item);
        } catch (Exception e) {
            Log.w(TAG, "Failed to build CoT for shared map item uid=" + item.getUID(), e);
            return;
        }
        if (cotEvent == null || !isRelayableMapCotType(cotEvent.getType())) {
            return;
        }

        String uid = cotEvent.getUID();
        if (uid != null && shouldSkipOutboundRelayWasInboundInject(uid)) return;
        if (isDuplicateLocalRelay(cotEvent)) return;

        markMapItemSendable(uid);
        Log.d(TAG, "Shared map item broadcast relay: type=" + cotEvent.getType()
                + " uid=" + uid + " via=" + eventType);
        new Thread(() -> sendCotOverRadio(cotEvent)).start();
    }

    /**
     * Full {@code b-t-f} with contact {@code hierarchy} — slotted to reduce RF collisions.
     */
    public void scheduleSlottedGroupContactCotRelay(CotEvent event) {
        if (event == null || btManager == null || !btManager.isConnected()) {
            return;
        }
        RfTxArbitrator.get().setSlottedTxPending(true);
        slottedCoTScheduler.scheduleGroupContactCot(pluginContext, () -> {
            Log.i(TAG, "Group/contact-list CoT TX: uid=" + event.getUID());
            sendCotOverRadio(event);
            // RF links can drop one fragment; send one delayed duplicate for reliability.
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (btManager != null && btManager.isConnected()) {
                    Log.i(TAG, "Group/contact-list CoT TX (redundant): uid=" + event.getUID());
                    sendCotOverRadio(event);
                }
            }, GROUP_CONTACT_COT_REDUNDANT_TX_DELAY_MS);
        });
    }

    /**
     * Optional RF -> TAK uplink path.
     * Active only when SA Relay and RF-to-TAK uplink are both enabled.
     */
    public boolean isRfToTakUplinkActive() {
        return isRfToTakUplinkEnabled();
    }

    public boolean dispatchRfPeerSaToTakNetwork(CotEvent event) {
        if (event == null) {
            return false;
        }
        if (!isRfToTakUplinkEnabled()) {
            return false;
        }
        try {
            CotMapComponent.getExternalDispatcher().dispatchToBroadcast(
                    event, CoTSendMethod.ANY);
            Log.i(TAG, "RF -> TAK broadcast uplink: type=" + event.getType()
                    + " uid=" + event.getUID());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "RF -> TAK uplink failed: " + e.getMessage());
            return false;
        }
    }

    private void maybeRelayInboundRadioCotToTak(CotEvent event) {
        dispatchRfPeerSaToTakNetwork(event);
    }

    private void maybeRelayLocalCotBroadcast(Intent intent) {
        if (intent == null) return;
        if (btManager == null || !btManager.isConnected()) return;

        final String action = intent.getAction();
        if (action == null) return;

        CotEvent event = parseCotEventFromMapIntent(intent);
        if (event == null) return;

        final CotEvent relayEvent = event;
        if (isRelayableMapCotType(relayEvent.getType())) {
            markMapItemSendable(relayEvent.getUID());
        }
        if (!shouldRelayLocalMapCot(relayEvent, action)) return;

        String uid = relayEvent.getUID();
        if (uid != null && shouldSkipOutboundRelayWasInboundInject(uid)) return;
        if (isDuplicateLocalRelay(relayEvent)) return;

        Log.d(TAG, "Local map CoT relay over RF: action=" + action
                + " type=" + relayEvent.getType() + " uid=" + uid);
        new Thread(() -> sendCotOverRadio(relayEvent)).start();
    }

    private void runOnMapView(Runnable action) {
        if (action == null || mapView == null) {
            return;
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action.run();
        } else {
            mapView.post(action);
        }
    }

    /**
     * ATAK radial send submenu ({@code menus/a-f.xml}) is disabled unless {@code sendable=true}
     * on the map item. WiFi-off / mesh-only drops do not get this from ATAK by default.
     */
    public void markMapItemSendable(String uid) {
        if (uid == null || uid.trim().isEmpty() || mapView == null) {
            return;
        }
        if (!isRadioConnected()) {
            return;
        }
        final String trimmed = uid.trim();
        runOnMapView(() -> {
            try {
                com.atakmap.android.maps.MapItem item =
                        mapView.getRootGroup().deepFindUID(trimmed);
                if (item == null) {
                    return;
                }
                if (!item.getMetaBoolean("sendable", false)) {
                    item.setMetaBoolean("sendable", true);
                    Log.d(TAG, "Marked map item sendable uid=" + trimmed
                            + " type=" + item.getType());
                }
            } catch (Exception e) {
                Log.w(TAG, "markMapItemSendable failed uid=" + trimmed, e);
            }
        });
    }

    /** Refresh sendable flag on all relayable map items (e.g. after mesh connect). */
    public void refreshSendableMapItems() {
        if (!isRadioConnected() || mapView == null) {
            return;
        }
        runOnMapView(() -> {
            try {
                refreshSendableMapItems(mapView.getRootGroup());
            } catch (Exception e) {
                Log.w(TAG, "refreshSendableMapItems failed", e);
            }
        });
    }

    private void refreshSendableMapItems(com.atakmap.android.maps.MapGroup group) {
        if (group == null) {
            return;
        }
        for (com.atakmap.android.maps.MapItem item : group.getItems()) {
            if (item == null) {
                continue;
            }
            String type = item.getType();
            if (type != null && isRelayableMapCotType(type)) {
                if (!item.getMetaBoolean("sendable", false)) {
                    item.setMetaBoolean("sendable", true);
                }
            }
        }
        for (com.atakmap.android.maps.MapGroup child : group.getChildGroups()) {
            refreshSendableMapItems(child);
        }
    }

    private boolean shouldRelayLocalMapCot(CotEvent event, String action) {
        if (event == null) return false;
        String type = event.getType();
        if (type == null || type.isEmpty()) return false;

        // Chat has its own dedicated compact relay path; avoid duplicate chat TX.
        if ("b-t-f".equals(type) || "b-t-f-r".equals(type) || "b-t-f-d".equals(type)) {
            return false;
        }

        if (isSelfPliBeacon(event)) {
            return false;
        }

        if ("com.atakmap.android.maps.COT_DELETED".equals(action)) {
            return true;
        }
        return isRelayableMapCotType(type);
    }

    private boolean isDuplicateLocalRelay(CotEvent event) {
        if (event == null) return true;
        String uid = event.getUID();
        String type = event.getType();
        String key = (uid == null ? "nouid" : uid) + "|" + (type == null ? "notype" : type);
        long now = System.currentTimeMillis();
        Long until = recentLocalRelayKeys.get(key);
        if (until != null && now < until) {
            return true;
        }
        recentLocalRelayKeys.put(key, now + LOCAL_RELAY_DEDUPE_MS);
        return false;
    }

    /**
     * UID for GeoChat {@code chatgrp}/{@code __chat} "local endpoint" when ingesting inbound DMs from
     * a background thread. {@link MapView#getDeviceUid()} may return NULL off the UI thread unless
     * {@link MapView#getMapView()} is initialized — fall back to self marker UID.
     */
    private String resolveLocalAtakUidForChatGrp(String peerSenderUid,
            String dmConversationId) {
        String local = tryResolveAtakSelfUidForChatGrp(mapView);
        if (local == null && dmConversationId != null
                && dmConversationId.startsWith(ANDROID_UID_PREFIX)) {
            Log.w(TAG, "injectChatCot: could not resolve local ATAK UID for chatgrp.uid1;"
                    + " GeoChat ACK/DM pairing may fail (conversation=" + dmConversationId + ")");
        }
        if (local != null && peerSenderUid != null && peerSenderUid.equals(local)) {
            Log.w(TAG, "injectChatCot: device uid equals peer sender " + local
                    + " — chatgrp may still be invalid");
        }
        return local;
    }

    private static String tryResolveAtakSelfUidForChatGrp(MapView instanceMapView) {
        MapView mv = null;
        try {
            mv = MapView.getMapView();
        } catch (Exception ignored) {
        }
        if (mv == null && instanceMapView != null) {
            mv = instanceMapView;
        }
        try {
            String id = MapView.getDeviceUid();
            if (!isBlank(id)) {
                return id.trim();
            }
        } catch (Exception ignored) {
        }
        try {
            if (mv != null) {
                com.atakmap.android.maps.Marker sm = mv.getSelfMarker();
                if (sm != null) {
                    String id = sm.getUID();
                    if (!isBlank(id)) {
                        return id.trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Stop and clean up.
     */
    public void dispose() {
        // Unregister PreSendProcessor — no unregister API, just null it out
        preSendProcessor = null;
        if (localCotBroadcastReceiver != null) {
            try {
                AtakBroadcast.getInstance().unregisterReceiver(localCotBroadcastReceiver);
            } catch (Exception e) {
                Log.w(TAG, "unregister localCotBroadcastReceiver", e);
            }
            localCotBroadcastReceiver = null;
        }
        unregisterItemRemovedListener();
        if (sharedMapItemListener != null && mapView != null) {
            try {
                mapView.getMapEventDispatcher().removeMapEventListener(
                        MapEvent.ITEM_PERSIST, sharedMapItemListener);
                mapView.getMapEventDispatcher().removeMapEventListener(
                        MapEvent.ITEM_SHARED, sharedMapItemListener);
            } catch (Exception e) {
                Log.w(TAG, "unregister sharedMapItemListener", e);
            }
            sharedMapItemListener = null;
        }
        if (outboundCommsLogger != null) {
            try {
                CommsMapComponent.getInstance()
                        .unregisterCommsLogger(outboundCommsLogger);
            } catch (Exception e) {
                Log.w(TAG, "unregisterCommsLogger", e);
            }
            outboundCommsLogger = null;
        }
        cotRetryExecutor.shutdownNow();
        pendingOutboundCots.clear();
        btechContactUids.clear();
        btechIdToUid.clear();
        btechUidAliases.clear();
        saRelayLastSentByUid.clear();
        recentLocalRelayKeys.clear();
        Log.d(TAG, "CotBridge disposed");
    }
}
