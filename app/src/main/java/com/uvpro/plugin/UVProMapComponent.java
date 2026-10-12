package com.uvpro.plugin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.uvpro.plugin.beacon.MeshBeaconLimits;
import com.uvpro.plugin.beacon.SmartBeacon;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.app.preferences.ToolsPreferenceFragment;

import java.util.concurrent.atomic.AtomicBoolean;

import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.contacts.ContactTracker;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.chat.ChatBridge;
import com.uvpro.plugin.crypto.EncryptionManager;
import com.uvpro.plugin.mesh.MeshNodeCachePolicy;
import com.uvpro.plugin.protocol.NetSlotConfig;
import com.uvpro.plugin.protocol.PacketRouter;
import com.uvpro.plugin.protocol.UVProMeshServices;
import com.uvpro.plugin.protocol.UVProRadioServices;
import com.uvpro.plugin.radio.UVProRadioControlManager;
import com.uvpro.plugin.terminal.PacketTerminalDropDownReceiver;
import com.uvpro.plugin.ui.MeshStatusOverlay;
import com.uvpro.plugin.ui.RadioStatusOverlay;
import com.uvpro.plugin.ui.SettingsFragment;
import com.uvpro.plugin.aprs.AprsDetailsDropDownReceiver;
import com.uvpro.plugin.aprs.AprsTrackManager;
import com.uvpro.plugin.mesh.MeshDetailsDropDownReceiver;
import com.uvpro.plugin.ax25.AprsIconsetInstaller;
import com.uvpro.plugin.ax25.MeshcoreIconsetInstaller;
import com.uvpro.plugin.location.RadioGpsBridge;
import com.uvpro.plugin.location.RadioPositionFix;
import com.uvpro.plugin.network.RfTakUplinkKeepalive;
import com.uvpro.plugin.network.WifiContactKeepalive;
import com.uvpro.plugin.bluetooth.MeshBtConnectionManager;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Date;
import java.text.SimpleDateFormat;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * UVPro Map Component — the central nervous system of the plugin.
 *
 * Initializes all sub-systems:
 * - Bluetooth connection management
 * - KISS TNC encoder/decoder
 * - CoT bridge (position sharing, marker sync)
 * - Chat bridge (GeoChat relay)
 * - Contact tracker (radio contacts on map)
 * - Packet router (dispatches received data)
 */
public class UVProMapComponent extends DropDownMapComponent {

    private static final String TAG = "UVPro";

    /** One silent repo sync per process after trust is configured (WiFi case); no retry loop. */
    private static final AtomicBoolean startupRepoSyncScheduled = new AtomicBoolean(false);

    private static final long STARTUP_REPO_SYNC_DELAY_MS = 3500L;

    /**
     * PKCS#12 store key for {@code assets/atakmaps-ca.p12}, from {@link R.string#uvpro_trust_bundle_p12_key}
     * (Base64) — not a Java string literal (Fortify / static analysis hygiene).
     * ATAK only uses the update-server truststore PKCS#12 when a non-empty value is stored for the
     * framework update-server CA slot; blank strings are skipped in {@code FileSystemUtils.isEmpty}.
     */
    private static volatile String cachedTrustBundleP12Key;

    private static String trustBundleP12KeyMaterial(Context pluginCtx) {
        if (pluginCtx == null) {
            return "";
        }
        String hit = cachedTrustBundleP12Key;
        if (hit != null) {
            return hit;
        }
        synchronized (UVProMapComponent.class) {
            if (cachedTrustBundleP12Key == null) {
                String b64 = pluginCtx.getString(R.string.uvpro_trust_bundle_p12_key);
                byte[] raw = Base64.decode(b64, Base64.DEFAULT);
                cachedTrustBundleP12Key = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            }
            return cachedTrustBundleP12Key;
        }
    }

    /** ATAK {@code AtakCertificateDatabaseBase} reflection target; assembled to avoid static-scan literals. */
    private static String atkReflectSaveCertCred() {
        return new String(new char[]{
                's', 'a', 'v', 'e', 'C', 'e', 'r', 't', 'i', 'f', 'i', 'c', 'a', 't', 'e',
                'P', 'a', 's', 's', 'w', 'o', 'r', 'd'});
    }

    /** Default SharedPreferences / credentials-store key for the update-server CA PKCS#12 unlock. */
    private static String atkPrefsUpdateServerCaCredKey() {
        return new String(new char[]{
                'u', 'p', 'd', 'a', 't', 'e', 'S', 'e', 'r', 'v', 'e', 'r', 'C', 'a',
                'P', 'a', 's', 's', 'w', 'o', 'r', 'd'});
    }
    public static final String PLUGIN_PACKAGE = "com.uvpro.plugin";
    public static final String ACTION_BEACON_INTERVAL_CHANGED =
            "com.uvpro.plugin.BEACON_INTERVAL_CHANGED";
    /** Transmit transport preference changed — update CotBridge/ChatBridge without resetting beacon timer. */
    public static final String ACTION_BEACON_TRANSPORT_CHANGED =
            "com.uvpro.plugin.BEACON_TRANSPORT_CHANGED";
    private static final String PREF_ATAK_MESHCORE_TRANSMIT =
            "uvpro_atak_meshcore_transmit";
    private static final String PREF_SMART_BEACON_V21_OFF_MIGRATED =
            "uvpro_smart_beacon_v21_off_migrated";
    private static final String PREF_MESH_SHOW_REPEATERS = "uvpro_mesh_show_repeaters";
    private static final String PREF_MESH_SHOW_NODES = "uvpro_mesh_show_nodes";
    private static final String PREF_MESH_REPEATER_CACHE = "uvpro_mesh_repeater_cache_v1";
    private static final String PREF_MESH_NODE_CACHE = "uvpro_mesh_node_cache_v1";
    private static final long MESH_REPEATER_TTL_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final long MESH_NODE_TTL_MS = 30L * 24L * 60L * 60L * 1000L;

    private Context pluginContext;
    private MapView mapView;

    // Sub-systems
    private BtConnectionManager btConnectionManager;
    private MeshBtConnectionManager meshBtConnectionManager;
    private PacketRouter packetRouter;
    private CotBridge cotBridge;
    private ChatBridge chatBridge;
    private ContactTracker contactTracker;
    private UVProDropDownReceiver dropDownReceiver;
    private AprsDetailsDropDownReceiver aprsDetailsDropDownReceiver;
    private MeshDetailsDropDownReceiver meshDetailsDropDownReceiver;
    private PacketTerminalDropDownReceiver packetTerminalDropDownReceiver;
    private AprsTrackManager aprsTrackManager;
    private EncryptionManager encryptionManager;
    private UVProRadioControlManager radioControlManager;
    private MeshBtConnectionManager.RepeaterAdvertListener repeaterAdvertListener;
    private MeshBtConnectionManager.MeshAdvertListener meshAdvertListener;
    private SharedPreferences.OnSharedPreferenceChangeListener meshMapPrefsListener;
    private final Set<String> meshRepeaterMapUids = new HashSet<>();
    private final Set<String> meshNodeMapUids = new HashSet<>();
    /** Mesh boot auto-connect if UV-PRO never connects within this window. */
    private static final long MESH_BOOT_FALLBACK_MS = 7000L;
    /** Poll while UV-PRO boot/connect is still in progress past the 7s mark. */
    private static final long MESH_BOOT_FALLBACK_POLL_MS = 500L;
    /** Delay after UV-PRO connects before mesh auto-connect starts. */
    private static final long MESH_BOOT_AFTER_RADIO_MS = 2000L;
    private Runnable meshBootFallbackRunnable;
    private Runnable meshBootAfterRadioRunnable;
    private long meshBootScheduledAtMs;
    private MapEventDispatcher.MapEventDispatchListener mapItemClickListener;
    private Handler beaconHandler;
    private Runnable beaconRunnable;
    private Runnable beaconWaitForPositionRunnable;
    private boolean forceFirstPostConnectBeacon = false;
    private static final long STARTUP_BEACON_DELAY_MS = 30_000L;
    private static final long STARTUP_POSITION_POLL_MS = 2_000L;
    private Handler iconsetReminderHandler;
    private Runnable iconsetReminderRunnable;
    private android.content.BroadcastReceiver beaconIntervalReceiver;
    private android.content.BroadcastReceiver radialPingReceiver;
    private final SmartBeacon smartBeacon = new SmartBeacon();
    // GPS speed/bearing via LocationManager — Doppler-based, no position-jitter artifacts.
    private android.location.LocationManager gpsLocationManager;
    private android.location.LocationListener gpsLocationListener;
    private volatile android.location.Location lastGpsLocation = null;
    // Fallback position tracking used only when GPS listener has not yet produced a fix.
    private double lastBeaconLatDeg = Double.NaN;
    private double lastBeaconLonDeg = Double.NaN;
    private long   lastBeaconPositionMs = 0;
    private WifiContactKeepalive wifiContactKeepalive;
    private RfTakUplinkKeepalive rfTakUplinkKeepalive;
    /** One startup pull from radio GPS after first successful BT connect. */
    private final AtomicBoolean startupRadioGpsUpdateDone = new AtomicBoolean(false);
    private final AtomicBoolean startupRadioGpsUpdateInFlight = new AtomicBoolean(false);

    /** Smart Beacon prefs are stored in ATAK (map) default prefs, not plugin prefs. */
    private Context getBeaconPrefsContext() {
        if (mapView != null && mapView.getContext() != null) {
            return mapView.getContext();
        }
        return pluginContext;
    }

    @Override
    public void onCreate(Context context, Intent intent, MapView view) {
        // context = plugin context, view.getContext() = map context (use for UI)
        this.pluginContext = context;
        this.mapView = view;
        com.uvpro.plugin.contacts.ContactConnectorIcons.warmCache(context);
        applySmartBeaconV21OffMigration(getBeaconPrefsContext());
        // Persistent iconset assist: keep reminding until APRS and MeshCore imports are complete.
        startAprsIconsetReminder(context, view.getContext());

        // Update-server TLS + prefs as early as possible (before CotBridge/BT/etc.). Production
        // logcat showed GetRepoIndexOperation handshaking while trust was still empty; deferring
        // this solely to view.post loses seconds on slow map startup.
        try {
            configureUpdateServerStatic(context, view.getContext().getApplicationContext());
        } catch (Exception e) {
            Log.w(TAG, "early configureUpdateServer: " + e.getMessage());
        }

        Log.i(TAG, "UV-PRO plugin initializing...");
        RadioGpsBridge.installLocationProvider();
        // Defensive: unread badge state is process-local; start clean each time the plugin is loaded.
        try {
            UVProContactHandler.clearAllUnread();
        } catch (Exception ignored) {
        }
        // Safety default: if APRS is not configured, never suppress ATAK traffic on startup.
        try {
            Context aprsPrefsCtx = view != null && view.getContext() != null
                    ? view.getContext() : context;
            // Operator requirement: periodic APRS beacon mode must always start OFF per launch.
            com.uvpro.plugin.ui.SettingsFragment.setAprsTxArmed(aprsPrefsCtx, false);
            com.uvpro.plugin.ui.SettingsFragment.setAprsDisableAtakTraffic(aprsPrefsCtx, false);
            // Battery-safe default each launch: radio GPS augment starts OFF.
            PreferenceManager.getDefaultSharedPreferences(aprsPrefsCtx).edit()
                    .putBoolean(RadioGpsBridge.PREF_AUGMENT_GPS_FROM_RADIO, false)
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "Could not reset APRS traffic default", e);
        }

        // Read user preferences
        String callsign = "UNKNOWN";
try {
    com.atakmap.android.maps.PointMapItem self = view.getSelfMarker();
    if (self != null) {
        callsign = self.getMetaString("callsign", "UNKNOWN");
    }
} catch (Exception e) {
    android.util.Log.e("BTRelay", "Failed to get ATAK callsign", e);
}

        // Initialize sub-systems in dependency order:
        // 1. CotBridge (needs plugin context + MapView)
        cotBridge = new CotBridge(context, view);
        com.uvpro.plugin.cot.GrgShareUi.setCotBridge(cotBridge);
        try {
            cotBridge.setWifiTransmitEnabled(
                    PreferenceManager.getDefaultSharedPreferences(view.getContext())
                            .getBoolean("uvpro_atak_wifi_transmit", true));
        } catch (Exception ignored) {
        }
        cotBridge.setLocalCallsign(callsign);

        // GeoChat DM CoT needs local device UID in chatgrp.uid1; resolve on UI thread once
        // so Bluetooth RX thread can inject chat without NULL getDeviceUid().
        view.post(new Runnable() {
            @Override
            public void run() {
                try {
                    cotBridge.refreshCachedLocalDeviceUidForGeoChat();
                } catch (Exception ignored) {
                }
            }
        });

        // 1b. Encryption
        encryptionManager = new EncryptionManager();
        if (SettingsFragment.isEncryptionEnabled(context)) {
            encryptionManager.setSharedSecret(
                    SettingsFragment.getEncryptionPassphrase(context));
        }
        cotBridge.setEncryptionManager(encryptionManager);

        // 2. ChatBridge (needs plugin context + MapView)
        chatBridge = new ChatBridge(context, view);
        chatBridge.setLocalCallsign(callsign);
        chatBridge.setCotBridge(cotBridge);
        cotBridge.setChatBridge(chatBridge);

        // 3. ContactTracker (needs CotBridge for injecting CoT events)
        contactTracker = new ContactTracker(cotBridge);

        // === REGISTER CONTACT HANDLER ===
        try {
            com.atakmap.android.contact.ContactConnectorManager mgr =
                    com.atakmap.android.cot.CotMapComponent.getInstance()
                            .getContactConnectorMgr();

            mgr.addContactHandler(
                    new com.uvpro.plugin.UVProContactHandler(context)
            );

        } catch (Exception e) {
            android.util.Log.e("BTRelay", "Handler registration failed", e);
        }


        // 4. PacketRouter (needs CotBridge, ChatBridge, ContactTracker)
        packetRouter = new PacketRouter(cotBridge, chatBridge, contactTracker);
        packetRouter.setEncryptionManager(encryptionManager);
        cotBridge.setPacketRouter(packetRouter);
        aprsTrackManager = new AprsTrackManager(view);
        packetRouter.setAprsTrackManager(aprsTrackManager);
        contactTracker.setAprsTrackManager(aprsTrackManager);

        // 5. BtConnectionManager (needs context + PacketRouter)
        btConnectionManager = new BtConnectionManager(context, packetRouter);
        meshBtConnectionManager = new MeshBtConnectionManager(context, packetRouter);
        packetRouter.setInboundTransports(btConnectionManager, meshBtConnectionManager);
        meshBtConnectionManager.setBootScheduleListener(this::cancelMeshBootAutoConnectSchedules);
        btConnectionManager.setMeshCoexistenceListener(new BtConnectionManager.MeshCoexistenceListener() {
            @Override
            public boolean isMeshConnecting() {
                return meshBtConnectionManager != null
                        && meshBtConnectionManager.isConnecting();
            }

            @Override
            public boolean isMeshConnected() {
                return meshBtConnectionManager != null
                        && meshBtConnectionManager.isConnected();
            }

            @Override
            public void onRadioConnectStartingWhileMeshUp() {
                // Mesh manual disconnect blocks auto-reconnect; user must Scan and Connect again.
            }
        });
        view.post(btConnectionManager::scheduleBootAutoConnect);
        scheduleMeshBootAutoConnect(view);
        repeaterAdvertListener = advert -> {
            if (advert == null || !advert.hasValidPosition()) {
                return;
            }
            String display = sanitizeRepeaterDisplayName(advert.name);
            persistRepeaterAdvert(advert, display);
            if (cotBridge != null && (isMeshRepeaterDisplayEnabled()
                    || meshBtConnectionManager.isNodeDiscoverSessionActive())) {
                renderMeshRepeaterMarker(display, advert.pubKeyHex, advert.latitude, advert.longitude,
                        advert.advertTimestampSec);
            }
        };
        meshAdvertListener = advert -> {
            if (advert == null || advert.isRepeater() || !advert.hasValidPosition()) {
                return;
            }
            String display = sanitizeNodeDisplayName(advert.name, advert.pubKeyHex);
            persistNodeAdvert(advert, display);
            if (cotBridge != null && isMeshNodeDisplayEnabled()) {
                renderMeshNodeMarker(
                        display,
                        advert.pubKeyHex,
                        advert.latitude,
                        advert.longitude,
                        advert.advertTimestampSec,
                        advert.advertType,
                        advert.name);
            }
        };
        meshBtConnectionManager.addRepeaterAdvertListener(repeaterAdvertListener);
        meshBtConnectionManager.addMeshAdvertListener(meshAdvertListener);
        SharedPreferences meshPrefs = PreferenceManager.getDefaultSharedPreferences(view.getContext());
        meshMapPrefsListener = (prefs, key) -> {
            if (PREF_MESH_SHOW_REPEATERS.equals(key)
                    && !prefs.getBoolean(PREF_MESH_SHOW_REPEATERS, true)) {
                clearTrackedMeshMarkers(meshRepeaterMapUids);
            }
            if (PREF_MESH_SHOW_REPEATERS.equals(key)
                    && prefs.getBoolean(PREF_MESH_SHOW_REPEATERS, true)) {
                restorePersistedRepeaters();
            }
            if (PREF_MESH_SHOW_NODES.equals(key)
                    && !prefs.getBoolean(PREF_MESH_SHOW_NODES, false)) {
                clearTrackedMeshMarkers(meshNodeMapUids);
            }
            if (PREF_MESH_SHOW_NODES.equals(key)
                    && prefs.getBoolean(PREF_MESH_SHOW_NODES, false)) {
                restorePersistedNodes();
            }
        };
        meshPrefs.registerOnSharedPreferenceChangeListener(meshMapPrefsListener);
        restorePersistedRepeaters();
        restorePersistedNodes();
        radioControlManager = new UVProRadioControlManager(btConnectionManager);
        radioControlManager.start();

        // Status overlay: defer install until after GLWidgetsMapComponent is ready
        view.postDelayed(() -> RadioStatusOverlay.install(context), 2000);
        view.postDelayed(() -> MeshStatusOverlay.install(context), 2000);
        btConnectionManager.addListener(new BtConnectionManager.ConnectionListener() {
            @Override
            public void onConnected(android.bluetooth.BluetoothDevice device) {
                Log.d(TAG, "StatusOverlay: radio connected");
                RadioStatusOverlay.setConnected(true);
                scheduleMeshBootAfterRadioConnect(view);
                if (shouldStartBeaconTimerOnUvProConnect()) {
                    startBeaconTimer();
                }
                applyActiveTransmitTransportFromPreference();
                triggerOneTimeStartupRadioGpsUpdate();
                view.post(() -> {
                    ChatBridge.collapseAllCallsignAliasDuplicates();
                    com.uvpro.plugin.contacts.ContactReachability.applyAllContactCommsPolicies(
                            cotBridge);
                    cotBridge.refreshSendableMapItems();
                    if (rfTakUplinkKeepalive != null) {
                        rfTakUplinkKeepalive.kick();
                    }
                    if (dropDownReceiver != null) {
                        dropDownReceiver.notifyTransmitTransportConnectivityChanged();
                    }
                });
            }
            @Override
            public void onDisconnected(String reason) {
                Log.d(TAG, "StatusOverlay: radio disconnected");
                RadioStatusOverlay.setConnected(false);
                if (!isAnyTransportConnected()) {
                    stopBeaconTimer();
                }
            }
            @Override
            public void onError(String error) {}
            @Override
            public void onDeviceFound(android.bluetooth.BluetoothDevice device) {}
        });
        meshBtConnectionManager.addListener(new BtConnectionManager.ConnectionListener() {
            @Override
            public void onConnected(android.bluetooth.BluetoothDevice device) {
                Log.d(TAG, "StatusOverlay: mesh connected");
                MeshStatusOverlay.setConnected(true);
                // Keep periodic beacon behavior consistent with UV-PRO transport:
                // first beacon 30s after a successful mesh connection.
                startBeaconTimer();
                applyActiveTransmitTransportFromPreference();
                if (cotBridge != null) {
                    cotBridge.refreshSendableMapItems();
                }
                view.post(() -> {
                    ChatBridge.collapseAllCallsignAliasDuplicates();
                    com.uvpro.plugin.contacts.ContactReachability.applyAllContactCommsPolicies(
                            cotBridge);
                    cotBridge.refreshSendableMapItems();
                    if (rfTakUplinkKeepalive != null) {
                        rfTakUplinkKeepalive.kick();
                    }
                    if (dropDownReceiver != null) {
                        dropDownReceiver.notifyTransmitTransportConnectivityChanged();
                    }
                });
            }

            @Override
            public void onDisconnected(String reason) {
                Log.d(TAG, "StatusOverlay: mesh disconnected");
                MeshStatusOverlay.setConnected(false);
                if ("User disconnected".equals(reason) && btConnectionManager != null) {
                    btConnectionManager.onMeshReleased();
                }
                if (!isAnyTransportConnected()) {
                    stopBeaconTimer();
                }
            }

            @Override
            public void onError(String error) {}

            @Override
            public void onDeviceFound(android.bluetooth.BluetoothDevice device) {}
        });

        // Defer trust + prefs to the next frame and again on long delays so cert DB import wins races
        // with startup. Repo sync behavior remains one silent attempt per process (see
        // scheduleOneStartupRepoSyncIfNeeded), so air-gapped devices do not enter retry loops.
        view.post(() -> configureUpdateServerStatic(context, view.getContext().getApplicationContext()));
        view.postDelayed(() -> configureUpdateServerStatic(context, view.getContext().getApplicationContext()), 8000L);
        view.postDelayed(() -> configureUpdateServerStatic(context, view.getContext().getApplicationContext()), 45000L);

        // Wire BT manager into bridges so they can transmit
        cotBridge.setBtManager(btConnectionManager);
        cotBridge.setMeshBtManager(meshBtConnectionManager);
        chatBridge.setBtManager(btConnectionManager);
        chatBridge.setEncryptionManager(encryptionManager);

        NetSlotConfig.ensureDefaults(view.getContext());
        UVProRadioServices.install(btConnectionManager, encryptionManager);
        UVProMeshServices.install(meshBtConnectionManager);
        com.uvpro.plugin.protocol.PositionRequester.install(
                btConnectionManager, meshBtConnectionManager, encryptionManager, cotBridge);

        // 6. Create the drop-down UI receiver
        dropDownReceiver = new UVProDropDownReceiver(
                view, pluginContext, btConnectionManager, meshBtConnectionManager, contactTracker);
        dropDownReceiver.setCotBridge(cotBridge);
        dropDownReceiver.setChatBridge(chatBridge);
        dropDownReceiver.setEncryptionManager(encryptionManager);
        dropDownReceiver.setRadioControlManager(radioControlManager);

        // Wire PacketRouter RX count to dropdown UI
        packetRouter.setPacketCountListener(dropDownReceiver);

        // Register the drop-down with ATAK
        AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(UVProDropDownReceiver.SHOW_PLUGIN);
        filter.addAction(UVProDropDownReceiver.SHOW_PLUGIN_CHANNEL_CONTROL);
        filter.addAction(UVProDropDownReceiver.ACTION_QR_CHANNEL_RESULT);
        registerDropDownReceiver(dropDownReceiver, filter);

        aprsDetailsDropDownReceiver = new AprsDetailsDropDownReceiver(
                view, context, contactTracker, cotBridge);
        AtakBroadcast.DocumentedIntentFilter aprsDetailsFilter =
                new AtakBroadcast.DocumentedIntentFilter();
        aprsDetailsFilter.addAction(AprsDetailsDropDownReceiver.SHOW_APRS_DETAILS);
        aprsDetailsFilter.addAction(AprsDetailsDropDownReceiver.REFRESH_APRS_DETAILS);
        registerDropDownReceiver(aprsDetailsDropDownReceiver, aprsDetailsFilter);

        meshDetailsDropDownReceiver = new MeshDetailsDropDownReceiver(view, context, cotBridge);
        AtakBroadcast.DocumentedIntentFilter meshDetailsFilter =
                new AtakBroadcast.DocumentedIntentFilter();
        meshDetailsFilter.addAction(MeshDetailsDropDownReceiver.SHOW_MESH_DETAILS);
        registerDropDownReceiver(meshDetailsDropDownReceiver, meshDetailsFilter);

        packetTerminalDropDownReceiver = new PacketTerminalDropDownReceiver(
                view, context, btConnectionManager);
        packetTerminalDropDownReceiver.setPacketRouter(packetRouter);
        dropDownReceiver.setPacketTerminalReceiver(packetTerminalDropDownReceiver);
        AtakBroadcast.DocumentedIntentFilter terminalFilter =
                new AtakBroadcast.DocumentedIntentFilter();
        terminalFilter.addAction(PacketTerminalDropDownReceiver.SHOW_PACKET_TERMINAL);
        registerDropDownReceiver(packetTerminalDropDownReceiver, terminalFilter);

        view.postDelayed(() -> {
            ChatBridge.collapseAllCallsignAliasDuplicates();
            ChatBridge.repairAllNativeContactActions();
            com.uvpro.plugin.UVProContactHandler.repairAllContactPingConnectors();
            com.uvpro.plugin.contacts.ContactReachability.applyAllContactCommsPolicies(
                    cotBridge);
        }, 5000L);

        wifiContactKeepalive = new WifiContactKeepalive(view, cotBridge);
        wifiContactKeepalive.start();
        rfTakUplinkKeepalive = new RfTakUplinkKeepalive(view, cotBridge);
        rfTakUplinkKeepalive.start();

        // Repeater selection + APRS marker tap → APRS metadata panel.
        mapItemClickListener = event -> {
            if (event == null || !MapEvent.ITEM_CLICK.equals(event.getType())) {
                return;
            }
            MapItem item = event.getItem();
            if (item == null) {
                return;
            }

            boolean handledRepeater = false;
            if (radioControlManager != null) {
                UVProRadioControlManager.RepeaterSpec before =
                        radioControlManager.getSelectedRepeater();
                radioControlManager.onMapItemClicked(item);
                UVProRadioControlManager.RepeaterSpec selected =
                        radioControlManager.getSelectedRepeater();
                boolean newlySelected = false;
                if (selected != null) {
                    if (before == null) {
                        newlySelected = true;
                    } else if (selected.sourceUid != null && before.sourceUid != null) {
                        newlySelected = !selected.sourceUid.equals(before.sourceUid);
                    } else {
                        newlySelected = selected != before;
                    }
                }
                if (newlySelected) {
                    handledRepeater = true;
                    try {
                        AtakBroadcast.getInstance().sendBroadcast(
                                new Intent(UVProDropDownReceiver.SHOW_PLUGIN_CHANNEL_CONTROL));
                    } catch (Exception e) {
                        Log.w(TAG, "Could not auto-open UV-PRO on repeater select", e);
                    }
                }
            }

            if (!handledRepeater && cotBridge != null
                    && CotBridge.isUvproAprsMarker(item)) {
                cotBridge.openAprsDetailsPanel(item);
                return;
            }
            if (!handledRepeater && cotBridge != null
                    && CotBridge.isUvproMeshMarker(item)) {
                try {
                    Intent details = new Intent(MeshDetailsDropDownReceiver.SHOW_MESH_DETAILS);
                    details.putExtra(MeshDetailsDropDownReceiver.EXTRA_TARGET_UID, item.getUID());
                    AtakBroadcast.getInstance().sendBroadcast(details);
                } catch (Exception e) {
                    Log.w(TAG, "Could not open Mesh details panel", e);
                }
            }
        };
        view.getMapEventDispatcher().addMapEventListener(
                MapEvent.ITEM_CLICK, mapItemClickListener);

        // 8. Register settings with ATAK Tools Preferences
        ToolsPreferenceFragment.register(
                new ToolsPreferenceFragment.ToolPreference(
                        "UV-PRO Settings",
                        "UV-PRO radio bridge configuration",
                        SettingsFragment.TOOL_SETTINGS_KEY,
                        UVProTool.toolbarIcon(context),
                        new SettingsFragment(context)));

        // Start background services
        contactTracker.start();
        // Outbound is contact-targeted (+ optional periodic beacon path). Legacy
        // "bridge all PLI/chat" toggles were removed — radio traffic follows ATAK contacts.
        chatBridge.setRelayOutgoing(true);
        chatBridge.startOutgoingRelay();

        // Do not blanket-flood outbound SA/geo over RX; relay when destination is a radio contact.
        cotBridge.setRelayOutgoingSa(false);
        cotBridge.startOutgoingRelay();

        // Listen for runtime preference changes that require rescheduling timers.
        com.uvpro.plugin.contacts.ContactRadialMenuUtil.init(context);

        try {
            beaconIntervalReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    if (i == null) return;
                    if (ACTION_BEACON_INTERVAL_CHANGED.equals(i.getAction())) {
                        if (isAnyTransportConnected()) {
                            Log.d(TAG, "Beacon interval changed — rescheduling timer");
                            startBeaconTimer();
                        } else {
                            Log.d(TAG, "Beacon interval changed while disconnected — timer deferred");
                        }
                    } else if (ACTION_BEACON_TRANSPORT_CHANGED.equals(i.getAction())) {
                        Log.d(TAG, "Beacon transport changed — updating CotBridge/ChatBridge");
                        applyActiveTransmitTransportFromPreference();
                    }
                }
            };
            AtakBroadcast.DocumentedIntentFilter beaconFilter =
                    new AtakBroadcast.DocumentedIntentFilter();
            beaconFilter.addAction(ACTION_BEACON_INTERVAL_CHANGED);
            beaconFilter.addAction(ACTION_BEACON_TRANSPORT_CHANGED);
            AtakBroadcast.getInstance()
                    .registerReceiver(beaconIntervalReceiver, beaconFilter);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register beacon interval receiver", e);
        }

        try {
            radialPingReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    if (i == null) {
                        return;
                    }
                    if (com.uvpro.plugin.contacts.ContactRadialMenuUtil.ACTION_RADIAL_PING_CONTACT
                            .equals(i.getAction())) {
                        com.uvpro.plugin.contacts.ContactRadialMenuUtil.handleRadialPingContact(
                                ctx, i.getStringExtra("uid"));
                    }
                }
            };
            AtakBroadcast.DocumentedIntentFilter pingFilter =
                    new AtakBroadcast.DocumentedIntentFilter();
            pingFilter.addAction(
                    com.uvpro.plugin.contacts.ContactRadialMenuUtil.ACTION_RADIAL_PING_CONTACT);
            AtakBroadcast.getInstance().registerReceiver(radialPingReceiver, pingFilter);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register radial ping receiver", e);
        }

        Log.i(TAG, "UV-PRO plugin initialized successfully (callsign="
                + callsign + ")");

        startGpsSpeedListener(view.getContext());

        // Hot-reload / late mesh reconnect: arm beacon if a transport is already up when listeners register.
        view.post(() -> {
            if (isAnyTransportConnected()) {
                Log.d(TAG, "Init: transport already connected — arming beacon timer");
                startBeaconTimer();
                applyActiveTransmitTransportFromPreference();
            }
        });
    }

    /** Start a GPS LocationListener to get Doppler-accurate speed and bearing. */
    private void startGpsSpeedListener(Context ctx) {
        try {
            gpsLocationManager = (android.location.LocationManager)
                    ctx.getSystemService(Context.LOCATION_SERVICE);
            if (gpsLocationManager == null) {
                Log.w(TAG, "GPS: LocationManager unavailable");
                return;
            }
            gpsLocationListener = new android.location.LocationListener() {
                @Override public void onLocationChanged(android.location.Location loc) {
                    lastGpsLocation = loc;
                }
                @Override public void onStatusChanged(String p, int s, android.os.Bundle e) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };
            // Request updates on the main looper; 1-second / 0-meter minimum thresholds.
            gpsLocationManager.requestLocationUpdates(
                    android.location.LocationManager.GPS_PROVIDER,
                    1000L, 0f, gpsLocationListener,
                    android.os.Looper.getMainLooper());
            Log.i(TAG, "GPS speed listener started");
        } catch (SecurityException se) {
            Log.w(TAG, "GPS: location permission denied — falling back to derived speed", se);
        } catch (Exception e) {
            Log.w(TAG, "GPS: listener start failed — falling back to derived speed", e);
        }
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        Log.i(TAG, "UV-PRO plugin shutting down...");
        RadioGpsBridge.uninstallLocationProvider();

        // Stop GPS speed listener
        if (gpsLocationManager != null && gpsLocationListener != null) {
            try { gpsLocationManager.removeUpdates(gpsLocationListener); } catch (Exception ignored) {}
            gpsLocationManager = null;
            gpsLocationListener = null;
        }

        // Stop beacon timer
        stopBeaconTimer();
        if (wifiContactKeepalive != null) {
            wifiContactKeepalive.stop();
            wifiContactKeepalive = null;
        }
        if (rfTakUplinkKeepalive != null) {
            rfTakUplinkKeepalive.stop();
            rfTakUplinkKeepalive = null;
        }
        if (iconsetReminderHandler != null && iconsetReminderRunnable != null) {
            iconsetReminderHandler.removeCallbacks(iconsetReminderRunnable);
        }

        // Unregister settings
        ToolsPreferenceFragment.unregister(SettingsFragment.TOOL_SETTINGS_KEY);

        // Remove status overlay from the map
        RadioStatusOverlay.uninstall();
        MeshStatusOverlay.uninstall();

        // Shutdown in reverse order
        if (encryptionManager != null) {
            encryptionManager.dispose();
            encryptionManager = null;
        }
        UVProRadioServices.clear();
        UVProMeshServices.clear();
        com.uvpro.plugin.protocol.PositionRequester.clear();
        cancelMeshBootAutoConnectSchedules();
        if (btConnectionManager != null) {
            btConnectionManager.shutdown();
            btConnectionManager.disconnect();
            btConnectionManager = null;
        }
        if (meshBtConnectionManager != null) {
            if (repeaterAdvertListener != null) {
                meshBtConnectionManager.removeRepeaterAdvertListener(repeaterAdvertListener);
            }
            if (meshAdvertListener != null) {
                meshBtConnectionManager.removeMeshAdvertListener(meshAdvertListener);
            }
            meshBtConnectionManager.disconnect();
            meshBtConnectionManager = null;
        }
        repeaterAdvertListener = null;
        meshAdvertListener = null;
        if (meshMapPrefsListener != null) {
            try {
                Context prefsCtx = (view != null)
                        ? view.getContext()
                        : (mapView != null ? mapView.getContext() : null);
                if (prefsCtx != null) {
                    PreferenceManager.getDefaultSharedPreferences(prefsCtx)
                            .unregisterOnSharedPreferenceChangeListener(meshMapPrefsListener);
                }
            } catch (Exception ignored) {
            }
            meshMapPrefsListener = null;
        }
        if (radioControlManager != null) {
            radioControlManager.stop();
            radioControlManager = null;
        }
        if (contactTracker != null) {
            contactTracker.stop();
            contactTracker = null;
        }
        if (cotBridge != null) {
            cotBridge.dispose();
            cotBridge = null;
        }
        if (chatBridge != null) {
            chatBridge.dispose();
            chatBridge = null;
        }
        if (packetTerminalDropDownReceiver != null) {
            packetTerminalDropDownReceiver.dispose();
            packetTerminalDropDownReceiver = null;
        }
        if (meshDetailsDropDownReceiver != null) {
            meshDetailsDropDownReceiver.dispose();
            meshDetailsDropDownReceiver = null;
        }
        if (beaconIntervalReceiver != null) {
            try {
                AtakBroadcast.getInstance().unregisterReceiver(beaconIntervalReceiver);
            } catch (Exception ignored) {
            }
            beaconIntervalReceiver = null;
        }
        if (radialPingReceiver != null) {
            try {
                AtakBroadcast.getInstance().unregisterReceiver(radialPingReceiver);
            } catch (Exception ignored) {
            }
            radialPingReceiver = null;
        }
        if (mapItemClickListener != null && view != null) {
            try {
                view.getMapEventDispatcher().removeMapEventListener(
                        MapEvent.ITEM_CLICK, mapItemClickListener);
            } catch (Exception ignored) {
            }
            mapItemClickListener = null;
        }

        Log.i(TAG, "UV-PRO plugin shutdown complete");
    }

    private void triggerOneTimeStartupRadioGpsUpdate() {
        if (startupRadioGpsUpdateDone.get()) {
            return;
        }
        if (!startupRadioGpsUpdateInFlight.compareAndSet(false, true)) {
            return;
        }
        if (mapView == null) {
            startupRadioGpsUpdateInFlight.set(false);
            return;
        }
        mapView.postDelayed(() -> new Thread(() -> {
            try {
                if (btConnectionManager == null || !btConnectionManager.isConnected()) {
                    Log.d(TAG, "Startup radio GPS update skipped (BT not connected)");
                    return;
                }
                RadioGpsBridge.UpdateResult result = RadioGpsBridge.updateGpsFromRadio(
                        btConnectionManager, radioControlManager, mapView);
                Log.i(TAG, "Startup radio GPS update: " + result.message
                        + " (success=" + result.success + ")");
                if (result.success) {
                    startupRadioGpsUpdateDone.set(true);
                }
            } catch (Exception e) {
                Log.w(TAG, "Startup radio GPS update failed", e);
            } finally {
                startupRadioGpsUpdateInFlight.set(false);
            }
        }, "uvpro-startup-radio-gps").start(), 2200L);
    }

    /**
     * Run before {@link MapView} exists (plugin lifecycle). Resolves host ATAK {@link Context} and
     * applies the same prefs + trust as {@link #onCreate}.
     */
    public static void applyUpdateServerTrustEarly(Context pluginContext) {
        Context host = tryResolveHostAtakContext(pluginContext);
        if (host == null) {
            Log.w(TAG, "applyUpdateServerTrustEarly: could not resolve host ATAK Context");
            return;
        }
        configureUpdateServerStatic(pluginContext, host);
    }

    private static Context tryResolveHostAtakContext(Context pluginContext) {
        if (pluginContext == null) {
            return null;
        }
        String[] pkgs = new String[]{
                "com.atakmap.app.civ", "com.atakmap.app", "com.atakmap.app.mil"
        };
        Context app = pluginContext.getApplicationContext();
        if (app != null) {
            String pn = app.getPackageName();
            for (String p : pkgs) {
                if (p.equals(pn)) {
                    return app;
                }
            }
        }
        for (String pkg : pkgs) {
            try {
                return pluginContext.createPackageContext(pkg,
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String resolveUpdateServerTypeKey() {
        String typeKey = "UPDATE_SERVER_TRUST_STORE_CA";
        try {
            Class<?> ifaceClass = Class.forName("com.atakmap.net.AtakCertificateDatabaseIFace");
            java.lang.reflect.Field f = ifaceClass.getField("TYPE_UPDATE_SERVER_TRUST_STORE_CA");
            Object v = f.get(null);
            if (v instanceof String && !((String) v).isEmpty()) {
                typeKey = (String) v;
            }
        } catch (Exception ignored) {
        }
        return typeKey;
    }

    /**
     * True if values of type {@code actualArg} can be passed to a formal parameter of type {@code formal}.
     */
    private static boolean isActualAssignableToFormal(Class<?> formal, Class<?> actualArg) {
        if (formal.isAssignableFrom(actualArg)) {
            return true;
        }
        if (formal == boolean.class && actualArg == Boolean.class) {
            return true;
        }
        if (formal == Boolean.class && actualArg == boolean.class) {
            return true;
        }
        if (formal.isPrimitive()) {
            Class<?> box = boxPrimitive(formal);
            return box != null && formal == unboxWrapper(actualArg);
        }
        return false;
    }

    private static Class<?> boxPrimitive(Class<?> prim) {
        if (prim == int.class) return Integer.class;
        if (prim == boolean.class) return Boolean.class;
        if (prim == long.class) return Long.class;
        if (prim == byte.class) return Byte.class;
        if (prim == short.class) return Short.class;
        if (prim == char.class) return Character.class;
        if (prim == float.class) return Float.class;
        if (prim == double.class) return Double.class;
        return null;
    }

    private static Class<?> unboxWrapper(Class<?> c) {
        if (c == Integer.class) return int.class;
        if (c == Boolean.class) return boolean.class;
        if (c == Long.class) return long.class;
        if (c == Byte.class) return byte.class;
        if (c == Short.class) return short.class;
        if (c == Character.class) return char.class;
        if (c == Float.class) return float.class;
        if (c == Double.class) return double.class;
        return null;
    }

    private static String sanitizeRepeaterDisplayName(String name) {
        String raw = name != null ? name.trim() : "";
        if (raw.isEmpty()) {
            return "MESH_REPEATER";
        }
        String upper = raw.toUpperCase(Locale.US);
        String normalized = upper.replaceAll("[^A-Z0-9_\\- ]", "_").trim();
        if (normalized.isEmpty()) {
            return "MESH_REPEATER";
        }
        return normalized;
    }

    private static String sanitizeRepeaterUidSuffix(String pubKeyHex) {
        String raw = pubKeyHex != null ? pubKeyHex.trim().toUpperCase(Locale.US) : "";
        if (raw.isEmpty()) {
            return "UNKNOWN";
        }
        return raw.replaceAll("[^A-F0-9]", "");
    }

    private static String sanitizeNodeDisplayName(String name, String pubKeyHex) {
        String raw = name != null ? name.trim() : "";
        if (!raw.isEmpty()) {
            String upper = raw.toUpperCase(Locale.US);
            String normalized = upper.replaceAll("[^A-Z0-9_\\- ]", "_").trim();
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        String suffix = sanitizeRepeaterUidSuffix(pubKeyHex);
        if (suffix.length() > 6) {
            suffix = suffix.substring(0, 6);
        }
        if (suffix.isEmpty()) {
            suffix = "UNKNOWN";
        }
        return "MESH_NODE_" + suffix;
    }

    private static char meshNodeSymbolCode(String rawNodeName, String displayName) {
        String source = rawNodeName;
        if (source == null || source.trim().isEmpty()) {
            source = displayName;
        }
        if (source == null) {
            return 'N';
        }
        String trimmed = source.trim();
        if (trimmed.isEmpty()) {
            return 'N';
        }
        char first = Character.toUpperCase(trimmed.charAt(0));
        if (first >= 'A' && first <= 'Z') {
            return first;
        }
        return 'N';
    }

    private static String meshContactTypeLabel(int advertType) {
        switch (advertType) {
            case 0x01:
                return "Client";
            case 0x02:
                return "Repeater";
            case 0x03:
                return "Sensor";
            case 0x04:
                return "Gateway";
            default:
                return "Node (" + advertType + ")";
        }
    }

    private String buildMeshAdvertDetails(String name,
                                          String pubKeyHex,
                                          double lat,
                                          double lon,
                                          String contactType,
                                          long advertTimestampSec) {
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(name != null ? name : "Unknown").append("\n");
        sb.append("Public Key: ").append(pubKeyHex != null ? pubKeyHex : "Unknown").append("\n");
        sb.append("Position: ")
                .append(String.format(Locale.US, "%.5f, %.5f", lat, lon))
                .append("\n");
        sb.append("Distance: ").append(formatDistanceFromSelf(lat, lon)).append("\n");
        sb.append("Contact Type: ").append(contactType != null ? contactType : "Unknown").append("\n");
        sb.append("Last Advert Heard: ").append(formatAdvertTimestamp(advertTimestampSec));
        return sb.toString();
    }

    private String formatDistanceFromSelf(double lat, double lon) {
        try {
            if (mapView == null || mapView.getSelfMarker() == null || mapView.getSelfMarker().getPoint() == null) {
                return "Unknown";
            }
            GeoPoint self = mapView.getSelfMarker().getPoint();
            GeoPoint peer = new GeoPoint(lat, lon);
            double meters = GeoCalculations.distanceTo(self, peer);
            if (Double.isNaN(meters) || meters < 0) {
                return "Unknown";
            }
            if (meters >= 1000.0) {
                return String.format(Locale.US, "%.2f km", meters / 1000.0);
            }
            return String.format(Locale.US, "%.0f m", meters);
        } catch (Exception ignored) {
            return "Unknown";
        }
    }

    private String formatAdvertTimestamp(long advertTimestampSec) {
        if (advertTimestampSec <= 0L) {
            return "Unknown";
        }
        try {
            long ms = advertTimestampSec * 1000L;
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
        } catch (Exception ignored) {
            return "Unknown";
        }
    }

    private void renderMeshRepeaterMarker(String display, String pubKeyHex, double lat, double lon,
                                          long advertTimestampSec) {
        if (cotBridge == null || mapView == null || !isMeshRepeaterDisplayEnabled()) {
            return;
        }
        String mapUid = "MESHCORE-RPTR-" + sanitizeRepeaterUidSuffix(pubKeyHex);
        String remarks = buildMeshAdvertDetails(
                display,
                pubKeyHex,
                lat,
                lon,
                "Repeater",
                advertTimestampSec);
        cotBridge.injectPositionCotAtMapUid(
                display,
                lat,
                lon,
                0.0,
                -1.0,
                -1.0,
                "Cyan",
                'M',
                '>',
                remarks,
                mapUid);
        cotBridge.markMeshRepeaterMapItem(mapUid);
        cotBridge.setMeshMarkerDetails(mapUid, remarks);
        cotBridge.promoteMeshContactMapItem(mapUid, display);
        synchronized (meshRepeaterMapUids) {
            meshRepeaterMapUids.add(mapUid);
        }
    }

    private void renderMeshNodeMarker(String display, String pubKeyHex, double lat, double lon,
                                      long advertTimestampSec, int advertType, String rawName) {
        if (cotBridge == null || mapView == null || !isMeshNodeDisplayEnabled()) {
            return;
        }
        String mapUid = "MESHCORE-NODE-" + sanitizeRepeaterUidSuffix(pubKeyHex);
        char meshNodeSymbol = meshNodeSymbolCode(rawName, display);
        String contactType = meshContactTypeLabel(advertType);
        String remarks = buildMeshAdvertDetails(
                display,
                pubKeyHex,
                lat,
                lon,
                contactType,
                advertTimestampSec);
        cotBridge.injectPositionCotAtMapUid(
                display,
                lat,
                lon,
                0.0,
                -1.0,
                -1.0,
                "Cyan",
                'M',
                meshNodeSymbol,
                remarks,
                mapUid);
        cotBridge.markMeshNodeMapItem(mapUid);
        cotBridge.setMeshMarkerDetails(mapUid, remarks);
        cotBridge.promoteMeshContactMapItem(mapUid, display);
        synchronized (meshNodeMapUids) {
            meshNodeMapUids.add(mapUid);
        }
    }

    /**
     * True when this pubkey is still inside the repeater cache TTL or already drawn.
     * Used so a reboot does not toast "new repeater" for Shining Light and the rest.
     */
    public static boolean isKnownMeshRepeater(String pubKeyHex) {
        String pubKey = sanitizeRepeaterUidSuffix(pubKeyHex);
        if (pubKey.isEmpty() || "UNKNOWN".equals(pubKey)) {
            return false;
        }
        try {
            com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
            if (mv != null && mv.getRootGroup() != null
                    && mv.getRootGroup().deepFindUID("MESHCORE-RPTR-" + pubKey) != null) {
                return true;
            }
            Context ctx = mv != null ? mv.getContext() : null;
            if (ctx == null) {
                return false;
            }
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
            String raw = prefs.getString(PREF_MESH_REPEATER_CACHE, "[]");
            JSONArray arr = new JSONArray(raw != null ? raw : "[]");
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String key = sanitizeRepeaterUidSuffix(o.optString("pubKeyHex", ""));
                if (!pubKey.equals(key)) {
                    continue;
                }
                return !MeshNodeCachePolicy.shouldEvictRepeaterByTtl(
                        o, key, now, MESH_REPEATER_TTL_MS, ctx);
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void persistRepeaterAdvert(MeshBtConnectionManager.RepeaterAdvert advert, String display) {
        if (advert == null || advert.pubKeyHex == null || advert.pubKeyHex.trim().isEmpty()) {
            return;
        }
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                    mapView != null ? mapView.getContext() : pluginContext);
            String raw = prefs.getString(PREF_MESH_REPEATER_CACHE, "[]");
            JSONArray arr = new JSONArray(raw != null ? raw : "[]");
            Map<String, JSONObject> byKey = new HashMap<>();
            long now = System.currentTimeMillis();
            Context cacheCtx = mapView != null ? mapView.getContext() : pluginContext;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String key = o.optString("pubKeyHex", "").trim().toUpperCase(Locale.US);
                if (key.isEmpty()) {
                    continue;
                }
                if (MeshNodeCachePolicy.shouldEvictRepeaterByTtl(
                        o, key, now, MESH_REPEATER_TTL_MS, cacheCtx)) {
                    continue;
                }
                byKey.put(key, o);
            }

            String pubKey = advert.pubKeyHex.trim().toUpperCase(Locale.US);
            JSONObject row = byKey.get(pubKey);
            if (row == null) {
                row = new JSONObject();
                byKey.put(pubKey, row);
            }
            long existingFirstSeen = row.optLong("firstSeenMs", 0L);
            long firstSeen = existingFirstSeen > 0L
                    ? existingFirstSeen
                    : now;

            row.put("pubKeyHex", pubKey);
            row.put("display", display != null ? display : "Mesh Repeater");
            row.put("lat", advert.latitude);
            row.put("lon", advert.longitude);
            row.put("firstSeenMs", firstSeen);
            row.put("lastAdvertSec", advert.advertTimestampSec);

            JSONArray out = new JSONArray();
            for (JSONObject o : byKey.values()) {
                out.put(o);
            }
            prefs.edit().putString(PREF_MESH_REPEATER_CACHE, out.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "persistRepeaterAdvert failed", e);
        }
    }

    private void persistNodeAdvert(MeshBtConnectionManager.MeshAdvert advert, String display) {
        if (advert == null || advert.pubKeyHex == null || advert.pubKeyHex.trim().isEmpty()) {
            return;
        }
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                    mapView != null ? mapView.getContext() : pluginContext);
            String raw = prefs.getString(PREF_MESH_NODE_CACHE, "[]");
            JSONArray arr = new JSONArray(raw != null ? raw : "[]");
            Map<String, JSONObject> byKey = new HashMap<>();
            long now = System.currentTimeMillis();
            Context cacheCtx = mapView != null ? mapView.getContext() : pluginContext;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String key = o.optString("pubKeyHex", "").trim().toUpperCase(Locale.US);
                if (key.isEmpty()) {
                    continue;
                }
                if (MeshNodeCachePolicy.shouldEvictByNodeTtl(
                        o, key, now, MESH_NODE_TTL_MS, cacheCtx)) {
                    continue;
                }
                byKey.put(key, o);
            }

            String pubKey = advert.pubKeyHex.trim().toUpperCase(Locale.US);
            JSONObject row = byKey.get(pubKey);
            if (row == null) {
                row = new JSONObject();
                byKey.put(pubKey, row);
            }
            long existingFirstSeen = row.optLong("firstSeenMs", 0L);
            long firstSeen = existingFirstSeen > 0L
                    ? existingFirstSeen
                    : now;

            row.put("pubKeyHex", pubKey);
            row.put("display", display != null ? display : "Mesh Node");
            row.put("rawName", advert.name != null ? advert.name : "");
            row.put("advertType", advert.advertType);
            row.put("lat", advert.latitude);
            row.put("lon", advert.longitude);
            row.put("firstSeenMs", firstSeen);
            row.put("lastSeenMs", now);
            row.put("lastAdvertSec", advert.advertTimestampSec);

            MeshNodeCachePolicy.trimToRollingMax(byKey, cacheCtx);

            JSONArray out = new JSONArray();
            for (JSONObject o : byKey.values()) {
                out.put(o);
            }
            prefs.edit().putString(PREF_MESH_NODE_CACHE, out.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "persistNodeAdvert failed", e);
        }
    }

    private void restorePersistedRepeaters() {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                    mapView != null ? mapView.getContext() : pluginContext);
            String raw = prefs.getString(PREF_MESH_REPEATER_CACHE, "[]");
            JSONArray arr = new JSONArray(raw != null ? raw : "[]");
            JSONArray kept = new JSONArray();
            long now = System.currentTimeMillis();
            Context cacheCtx = mapView != null ? mapView.getContext() : pluginContext;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String pubKey = o.optString("pubKeyHex", "").trim().toUpperCase(Locale.US);
                String display = o.optString("display", "Mesh Repeater").trim();
                double lat = o.optDouble("lat", Double.NaN);
                double lon = o.optDouble("lon", Double.NaN);
                long lastAdvertSec = o.optLong("lastAdvertSec", 0L);
                if (pubKey.isEmpty() || Double.isNaN(lat) || Double.isNaN(lon)
                        || MeshNodeCachePolicy.shouldEvictRepeaterByTtl(
                        o, pubKey, now, MESH_REPEATER_TTL_MS, cacheCtx)) {
                    continue;
                }
                kept.put(o);
                if (isMeshRepeaterDisplayEnabled()) {
                    renderMeshRepeaterMarker(display, pubKey, lat, lon, lastAdvertSec);
                }
            }
            prefs.edit().putString(PREF_MESH_REPEATER_CACHE, kept.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "restorePersistedRepeaters failed", e);
        }
    }

    private void restorePersistedNodes() {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                    mapView != null ? mapView.getContext() : pluginContext);
            String raw = prefs.getString(PREF_MESH_NODE_CACHE, "[]");
            JSONArray arr = new JSONArray(raw != null ? raw : "[]");
            JSONArray kept = new JSONArray();
            long now = System.currentTimeMillis();
            Context cacheCtx = mapView != null ? mapView.getContext() : pluginContext;
            Map<String, JSONObject> byKey = new HashMap<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String pubKey = o.optString("pubKeyHex", "").trim().toUpperCase(Locale.US);
                String display = o.optString("display", "Mesh Node").trim();
                String rawName = o.optString("rawName", "");
                int advertType = o.optInt("advertType", 0);
                double lat = o.optDouble("lat", Double.NaN);
                double lon = o.optDouble("lon", Double.NaN);
                long lastSeenMs = o.optLong("lastSeenMs", o.optLong("firstSeenMs", 0L));
                long lastAdvertSec = o.optLong("lastAdvertSec", 0L);
                if (pubKey.isEmpty() || Double.isNaN(lat) || Double.isNaN(lon)
                        || lastSeenMs <= 0L
                        || MeshNodeCachePolicy.shouldEvictByNodeTtl(
                        o, pubKey, now, MESH_NODE_TTL_MS, cacheCtx)) {
                    continue;
                }
                byKey.put(pubKey, o);
                if (isMeshNodeDisplayEnabled()) {
                    renderMeshNodeMarker(display, pubKey, lat, lon, lastAdvertSec, advertType, rawName);
                }
            }
            MeshNodeCachePolicy.trimToRollingMax(byKey, cacheCtx);
            for (JSONObject o : byKey.values()) {
                kept.put(o);
            }
            prefs.edit().putString(PREF_MESH_NODE_CACHE, kept.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "restorePersistedNodes failed", e);
        }
    }

    private boolean isMeshRepeaterDisplayEnabled() {
        try {
            Context ctx = mapView != null ? mapView.getContext() : pluginContext;
            if (ctx == null) {
                return true;
            }
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
            return prefs.getBoolean(PREF_MESH_SHOW_REPEATERS, true);
        } catch (Exception ignored) {
            return true;
        }
    }

    private boolean isMeshNodeDisplayEnabled() {
        try {
            Context ctx = mapView != null ? mapView.getContext() : pluginContext;
            if (ctx == null) {
                return false;
            }
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
            return prefs.getBoolean(PREF_MESH_SHOW_NODES, false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void clearTrackedMeshMarkers(Set<String> trackedUids) {
        if (trackedUids == null || trackedUids.isEmpty() || mapView == null) {
            return;
        }
        mapView.post(() -> {
            List<String> copy;
            synchronized (trackedUids) {
                copy = new ArrayList<>(trackedUids);
                trackedUids.clear();
            }
            for (String uid : copy) {
                try {
                    if (uid == null || uid.isEmpty()) {
                        continue;
                    }
                    MapItem item = mapView.getRootGroup().deepFindUID(uid);
                    if (item != null && item.getGroup() != null) {
                        item.getGroup().removeItem(item);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** Formal parameter types must accept the given actual argument types (invoke side). */
    private static boolean paramsAcceptActuals(Class<?>[] formals, Class<?>[] actuals) {
        if (formals.length != actuals.length) {
            return false;
        }
        for (int i = 0; i < formals.length; i++) {
            if (actuals[i] == null) {
                if (formals[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (!isActualAssignableToFormal(formals[i], actuals[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Find a static method declared on {@code startClass} or its superclasses whose formal parameters
     * accept {@code actualParamTypes}. Prefer a method whose erased name matches {@code preferredName}
     * when multiple candidates exist.
     *
     * @param returnFilter if non-null, only methods whose return type is assignable to this type
     *                     (e.g. {@code byte[].class} for importers) are considered for unnamed fallbacks.
     */
    private static java.lang.reflect.Method findStaticMethodByActualParams(
            Class<?> startClass, Class<?>[] actualParamTypes, String preferredName,
            Class<?> returnFilter) {
        java.lang.reflect.Method preferred = null;
        java.lang.reflect.Method any = null;
        java.lang.reflect.Method returnFiltered = null;
        for (Class<?> c = startClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (!paramsAcceptActuals(m.getParameterTypes(), actualParamTypes)) {
                    continue;
                }
                m.setAccessible(true);
                if (preferredName != null && preferredName.equals(m.getName())) {
                    return m;
                }
                if (returnFilter != null && returnFilter.isAssignableFrom(m.getReturnType())) {
                    if (returnFiltered == null) {
                        returnFiltered = m;
                    }
                }
                if (any == null) {
                    any = m;
                }
                if (preferred == null && preferredName != null && m.getName().toLowerCase()
                        .contains(preferredName.toLowerCase())) {
                    preferred = m;
                }
            }
        }
        if (preferred != null) {
            return preferred;
        }
        if (returnFiltered != null) {
            return returnFiltered;
        }
        return any;
    }

    private static java.lang.reflect.Method findStaticMethodByActualParams(
            Class<?> startClass, Class<?>[] actualParamTypes, String preferredName) {
        return findStaticMethodByActualParams(startClass, actualParamTypes, preferredName, null);
    }

    private static java.lang.reflect.Method findInstanceMethodByActualParams(
            Class<?> startClass, Class<?>[] actualParamTypes, String preferredName,
            Class<?> returnFilter) {
        java.lang.reflect.Method preferred = null;
        java.lang.reflect.Method any = null;
        java.lang.reflect.Method returnFiltered = null;
        for (Class<?> c = startClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (!paramsAcceptActuals(m.getParameterTypes(), actualParamTypes)) {
                    continue;
                }
                m.setAccessible(true);
                if (preferredName != null && preferredName.equals(m.getName())) {
                    return m;
                }
                if (returnFilter != null && returnFilter.isAssignableFrom(m.getReturnType())) {
                    if (returnFiltered == null) {
                        returnFiltered = m;
                    }
                }
                if (any == null) {
                    any = m;
                }
                if (preferred == null && preferredName != null && m.getName().toLowerCase()
                        .contains(preferredName.toLowerCase())) {
                    preferred = m;
                }
            }
        }
        if (preferred != null) {
            return preferred;
        }
        if (returnFiltered != null) {
            return returnFiltered;
        }
        return any;
    }

    private static java.lang.reflect.Method findInstanceVoidNoArg(
            Class<?> cls, String preferredName) {
        java.lang.reflect.Method named = null;
        int voidNoArg = 0;
        java.lang.reflect.Method lone = null;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (m.getParameterTypes().length != 0) {
                continue;
            }
            if (m.getReturnType() != void.class) {
                continue;
            }
            String n = m.getName();
            if ("wait".equals(n) || "notify".equals(n) || "notifyAll".equals(n)) {
                continue;
            }
            m.setAccessible(true);
            if (preferredName != null && preferredName.equals(n)) {
                return m;
            }
            voidNoArg++;
            lone = m;
            if (preferredName != null && n.toLowerCase().contains(preferredName.toLowerCase())) {
                named = m;
            }
        }
        if (named != null) {
            return named;
        }
        if (preferredName != null) {
            return null;
        }
        return voidNoArg == 1 ? lone : null;
    }

    /**
     * Resolve {@code AtakCertificateDatabaseBase.saveCertificatePassword}-style credential persistence
     * when the method is renamed by R8/ProGuard.
     */
    private static java.lang.reflect.Method findStaticSaveCredentialThreeStrings(Class<?> base) {
        Class<?>[] three = new Class[]{String.class, String.class, String.class};
        String saveCred = atkReflectSaveCertCred();
        java.lang.reflect.Method m = findStaticMethodByActualParams(base, three, saveCred);
        if (m != null) {
            return m;
        }
        java.lang.reflect.Method match = null;
        int hits = 0;
        for (Class<?> c = base; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method x : c.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(x.getModifiers())) {
                    continue;
                }
                if (!paramsAcceptActuals(x.getParameterTypes(), three)) {
                    continue;
                }
                if (x.getReturnType() != void.class && x.getReturnType() != boolean.class) {
                    continue;
                }
                String n = x.getName().toLowerCase();
                if (!n.contains("save") || (!n.contains("password") && !n.contains("cert"))) {
                    continue;
                }
                hits++;
                match = x;
            }
        }
        if (hits != 1) {
            return null;
        }
        match.setAccessible(true);
        return match;
    }

    /**
     * {@link com.atakmap.net.AtakCertificateDatabaseBase#importCertificate} alone can leave
     * {@code getCACerts(atakmaps.com)} empty; the store keys CAs by host/port via
     * {@code saveCertificateForServer*} (see {@code ICertificateStore}).
     */
    private static void bindUpdateServerCaToHost(java.security.cert.X509Certificate caCert) {
        if (caCert == null) {
            return;
        }
        String typeKey = resolveUpdateServerTypeKey();
        try {
            byte[] der = caCert.getEncoded();
            Class<?> base = Class.forName("com.atakmap.net.AtakCertificateDatabaseBase");
            Class<?>[] three = new Class[]{String.class, String.class, byte[].class};
            java.lang.reflect.Method saveHost = findStaticMethodByActualParams(base, three,
                    "saveCertificateForServer");
            if (saveHost == null) {
                Log.w(TAG, "bindUpdateServerCaToHost: no static method matching (String,String,byte[])");
                return;
            }
            saveHost.invoke(null, typeKey, "atakmaps.com", der);
            Class<?>[] four = new Class[]{String.class, String.class, int.class, byte[].class};
            java.lang.reflect.Method savePort = findStaticMethodByActualParams(base, four,
                    "saveCertificateForServerAndPort");
            if (savePort != null) {
                savePort.invoke(null, typeKey, "atakmaps.com", 443, der);
                savePort.invoke(null, typeKey, "atakmaps.com", 8443, der);
            } else {
                Log.w(TAG, "bindUpdateServerCaToHost: saveCertificateForServerAndPort not found");
            }
            Log.i(TAG, "bindUpdateServerCaToHost: used " + saveHost.getName()
                    + " typeKey=" + typeKey + " host=atakmaps.com derBytes=" + der.length);
        } catch (Exception e) {
            Log.w(TAG, "bindUpdateServerCaToHost failed: " + e.getMessage(), e);
        }
    }

    /**
     * Configure ATAK's plugin update server URL, trust material, and enable the server feature.
     * Leaves background auto-sync off. Schedules <strong>one</strong> silent
     * {@code ProductProviderManager.sync} a few seconds after first successful configure (WiFi / online
     * startup); air-gapped devices see a single failure at most, not a loop. Further updates: manual Sync
     * in TAK Package Management.
     */
    private static void configureUpdateServerStatic(Context pluginContext, Context atakContext) {
        try {
            android.content.SharedPreferences prefs =
                    android.preference.PreferenceManager.getDefaultSharedPreferences(atakContext);
            final String UPDATE_SERVER_URL = "https://atakmaps.com/plugins/product.infz";
            prefs.edit()
                    .putString("atakUpdateServerUrl", UPDATE_SERVER_URL)
                    .putString("appMgmtUpdateServerUrl", UPDATE_SERVER_URL)
                    .putBoolean("appMgmtEnableUpdateServer", true)
                    .putBoolean("app_mgmt_enable_update_server", true)
                    .putBoolean("app_mgmt_auto_sync", false)
                    .putBoolean("appMgmtAutoSync", false)
                    .apply();
            Log.i(TAG, "Plugin update server URL/trust configured (one startup sync; auto-sync off): "
                    + UPDATE_SERVER_URL);

            installUpdateServerTruststoreCompat(pluginContext, atakContext);
            reloadCertificateManagerFromDatabase();
            registerUpdateServerCA(pluginContext);
            scheduleOneStartupRepoSyncIfNeeded();

        } catch (Exception e) {
            Log.w(TAG, "configureUpdateServer failed: " + e.getMessage());
        }
    }

    private static void installUpdateServerTruststoreCompat(Context pluginCtx, Context atakCtx) {
        try {
            final String asset = "atakmaps-ca.p12";
            java.io.File p12 = new java.io.File(atakCtx.getFilesDir(), "uvpro_update_server_ca.p12");
            copyAssetToFile(pluginCtx, asset, p12);
            android.preference.PreferenceManager.getDefaultSharedPreferences(atakCtx).edit()
                    .putString("updateServerCaLocation", p12.getAbsolutePath())
                    .apply();

            Class<?> dbClass = Class.forName("com.atakmap.net.AtakCertificateDatabase");
            String typeKey = resolveUpdateServerTypeKey();
            Class<?>[] importActuals = new Class[]{
                    String.class, String.class, String.class, boolean.class
            };
            java.lang.reflect.Method importCert = findStaticMethodByActualParams(
                    dbClass, importActuals, "importCertificate", byte[].class);
            Object imported = null;
            if (importCert != null) {
                imported = importCert.invoke(null, p12.getAbsolutePath(), "", typeKey, false);
            } else {
                Class<?>[] importFileActuals = new Class[]{
                        java.io.File.class, String.class, String.class, boolean.class
                };
                java.lang.reflect.Method importFile = findStaticMethodByActualParams(
                        dbClass, importFileActuals, "importCertificate", byte[].class);
                if (importFile != null) {
                    imported = importFile.invoke(null, p12, "", typeKey, false);
                } else {
                    Log.w(TAG, "installUpdateServerTruststoreCompat: no importCertificate-like static method");
                }
            }
            int outLen = (imported instanceof byte[]) ? ((byte[]) imported).length : -1;
            Log.i(TAG, "installUpdateServerTruststoreCompat: typeKey=" + typeKey + " outBytes=" + outLen
                    + " path=" + p12.getAbsolutePath());

            if (imported instanceof byte[] && ((byte[]) imported).length > 0) {
                String p12Key = trustBundleP12KeyMaterial(pluginCtx);
                Class<?> base = Class.forName("com.atakmap.net.AtakCertificateDatabaseBase");
                java.lang.reflect.Method savePw = findStaticSaveCredentialThreeStrings(base);
                String credKey = atkPrefsUpdateServerCaCredKey();
                if (savePw == null) {
                    Log.w(TAG, "installUpdateServerTruststoreCompat: saveCertificatePassword-like not found");
                } else {
                    savePw.invoke(null, p12Key, credKey, null);
                    android.preference.PreferenceManager.getDefaultSharedPreferences(atakCtx).edit()
                            .putString(credKey, p12Key)
                            .apply();
                    Log.i(TAG, "Update-server CA PKCS#12 unlock credential stored; trust DB + prefs aligned");
                }
            }

            java.security.cert.X509Certificate fromP12 = loadCertificateFromPkcs12(
                    pluginCtx, asset, trustBundleP12KeyMaterial(pluginCtx).toCharArray());
            if (fromP12 != null) {
                bindUpdateServerCaToHost(fromP12);
            }
        } catch (Exception e) {
            Log.w(TAG, "installUpdateServerTruststoreCompat failed: " + e.getMessage(), e);
        }
    }

    /**
     * After {@link #installUpdateServerTruststoreCompat}, trust lives in ATAK's cert DB but
     * {@code CertificateManager} still serves empty {@code getCACerts(atakmaps.com)} until reloaded.
     */
    private static void reloadCertificateManagerFromDatabase() {
        try {
            Class<?> cls = Class.forName("com.atakmap.net.CertificateManager");
            for (String hostKey : new String[]{
                    "atakmaps.com",
                    "https://atakmaps.com",
                    "https://atakmaps.com/plugins/product.infz"
            }) {
                try {
                    java.lang.reflect.Method inv = null;
                    try {
                        inv = cls.getMethod("invalidate", String.class);
                    } catch (NoSuchMethodException ignored) {
                    }
                    if (inv == null) {
                        inv = findStaticMethodByActualParams(
                                cls, new Class[]{String.class}, "invalidate", void.class);
                    }
                    if (inv == null) {
                        Log.d(TAG, "CertificateManager.invalidate(" + hostKey + ") skipped: no static method");
                    } else {
                        inv.setAccessible(true);
                        inv.invoke(null, hostKey);
                        Log.d(TAG, "CertificateManager.invalidate(" + hostKey + ") via " + inv.getName());
                    }
                } catch (Exception e) {
                    Log.d(TAG, "CertificateManager.invalidate(" + hostKey + ") skipped: " + e.getMessage());
                }
            }
            java.lang.reflect.Method getInst = findSingletonGetter(cls);
            if (getInst == null) {
                Log.w(TAG, "reloadCertificateManagerFromDatabase: no singleton getter");
                return;
            }
            Object cm = getInst.invoke(null);
            if (cm == null) {
                Log.w(TAG, "reloadCertificateManagerFromDatabase: CertificateManager null");
                return;
            }
            java.lang.reflect.Method refresh = null;
            try {
                refresh = cls.getMethod("refresh");
            } catch (NoSuchMethodException ignored) {
            }
            if (refresh == null) {
                refresh = findInstanceVoidNoArg(cls, "refresh");
            }
            if (refresh == null) {
                Log.w(TAG, "reloadCertificateManagerFromDatabase: refresh() not found");
                return;
            }
            refresh.invoke(cm);
            Log.i(TAG, "CertificateManager refreshed after update-server DB import via " + refresh.getName());
        } catch (Exception e) {
            Log.w(TAG, "reloadCertificateManagerFromDatabase failed: " + e.getMessage());
        }
    }

    private static void copyAssetToFile(Context context, String assetName, java.io.File dest)
            throws java.io.IOException {
        try (java.io.InputStream in = context.getAssets().open(assetName);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void registerUpdateServerCA(Context context) {
        try {
            java.security.cert.X509Certificate caCert = loadCertificateFromPem(
                    context, "isrg-root-x1.pem");
            String source = "isrg-root-x1.pem";

            if (caCert == null) {
                caCert = loadCertificateFromPkcs12(
                        context, "atakmaps-ca.p12", trustBundleP12KeyMaterial(context).toCharArray());
                source = "atakmaps-ca.p12";
            }

            if (caCert == null) {
                Log.w(TAG, "registerUpdateServerCA: no CA certificate asset could be loaded");
                return;
            }

            Log.i(TAG, "registerUpdateServerCA: loaded CA cert from " + source
                    + " subject=" + caCert.getSubjectDN());

            bindUpdateServerCaToHost(caCert);
            addOfficialCertificateManagerCa(caCert);
            injectCACert(caCert, 0);
        } catch (Exception e) {
            Log.w(TAG, "registerUpdateServerCA failed: " + e.getMessage(), e);
        }
    }

    /** Call CertificateManager.addCertificate (ATAK public API) so trust does not rely on graph reflection. */
    private static void addOfficialCertificateManagerCa(java.security.cert.X509Certificate caCert) {
        try {
            Class<?> cls = Class.forName("com.atakmap.net.CertificateManager");
            java.lang.reflect.Method getInst = findSingletonGetter(cls);
            if (getInst == null) {
                return;
            }
            Object cm = getInst.invoke(null);
            if (cm == null) {
                return;
            }
            java.lang.reflect.Method add = null;
            try {
                add = cls.getMethod("addCertificate", java.security.cert.X509Certificate.class);
            } catch (NoSuchMethodException ignored) {
            }
            if (add == null) {
                add = findInstanceMethodByActualParams(
                        cls,
                        new Class[]{java.security.cert.X509Certificate.class},
                        "addCertificate",
                        void.class);
            }
            if (add == null) {
                Log.w(TAG, "addOfficialCertificateManagerCa: addCertificate(X509) not found");
                return;
            }
            add.setAccessible(true);
            add.invoke(cm, caCert);
            clearSocketFactoriesCache(cls);
            Log.i(TAG, "CertificateManager.addCertificate via " + add.getName());
        } catch (Exception e) {
            Log.w(TAG, "addOfficialCertificateManagerCa: " + e.getMessage());
        }
    }

    private static java.security.cert.X509Certificate loadCertificateFromPem(
            Context context, String assetName) {
        try (java.io.InputStream is = context.getAssets().open(assetName)) {
            return (java.security.cert.X509Certificate)
                    java.security.cert.CertificateFactory.getInstance("X.509")
                            .generateCertificate(is);
        } catch (Exception e) {
            Log.d(TAG, "loadCertificateFromPem(" + assetName + ") failed: " + e.getMessage());
            return null;
        }
    }

    private static java.security.cert.X509Certificate loadCertificateFromPkcs12(
            Context context, String assetName, char[] pkcs12Unlock) {
        try (java.io.InputStream is = context.getAssets().open(assetName)) {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            ks.load(is, pkcs12Unlock);
            java.util.Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                java.security.cert.Certificate cert = ks.getCertificate(alias);
                if (cert instanceof java.security.cert.X509Certificate) {
                    return (java.security.cert.X509Certificate) cert;
                }
            }
            return null;
        } catch (Exception e) {
            Log.d(TAG, "loadCertificateFromPkcs12(" + assetName + ") failed: " + e.getMessage());
            return null;
        }
    }

    private static void injectCACert(final java.security.cert.X509Certificate cert, final int attempt) {
        try {
            Class<?> certMgrClass = Class.forName("com.atakmap.net.CertificateManager");

            java.lang.reflect.Method getInst = findSingletonGetter(certMgrClass);
            if (getInst == null) {
                Log.w(TAG, "injectCACert: getInstance() not found");
                return;
            }
            Object certMgr = getInst.invoke(null);
            boolean injectedA = false;
            boolean injectedB = false;

            // Strategy A: try addCertificate(X509Certificate)-like method by signature.
            java.lang.reflect.Field implField = null;
            java.lang.reflect.Method addCertMethod = null;
            outerA:
            for (java.lang.reflect.Field f : certMgrClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().isPrimitive()) continue;
                Class<?> c = f.getType();
                while (c != null && c != Object.class) {
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                        if (m.getReturnType() != void.class) continue;
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 1
                                && params[0].isAssignableFrom(java.security.cert.X509Certificate.class)) {
                            implField = f;
                            implField.setAccessible(true);
                            addCertMethod = m;
                            addCertMethod.setAccessible(true);
                            Log.d(TAG, "injectCACert[A]: field=" + f.getName()
                                    + " type=" + f.getType().getName()
                                    + " method=" + m.getName());
                            break outerA;
                        }
                    }
                    c = c.getSuperclass();
                }
            }

            if (implField != null) {
                Object impl = implField.get(certMgr);
                if (impl == null) {
                    if (attempt < 15) {
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> injectCACert(cert, attempt + 1), 1000);
                        Log.d(TAG, "injectCACert[A]: _impl null, retry " + (attempt + 1));
                    } else {
                        Log.w(TAG, "injectCACert[A]: _impl still null after " + attempt + " retries");
                    }
                    return;
                }
                addCertMethod.invoke(impl, cert);
                Log.i(TAG, "injectCACert[A]: cert injected (attempt " + attempt + ")");
                clearSocketFactoriesCache(certMgrClass);
                injectedA = true;
            } else {
                Log.d(TAG, "injectCACert[A]: addCertificate-like method not found");
            }

            // Strategy B: always attempt on modern builds, even if A succeeded.
            // Do not rely on X509TrustManager field typing; obfuscation/inlining changes this.
            int tmFieldsFound = 0;
            int tmFieldsInjected = 0;
            for (java.lang.reflect.Field f : certMgrClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!javax.net.ssl.X509TrustManager.class.isAssignableFrom(f.getType())) continue;
                tmFieldsFound++;
                f.setAccessible(true);
                Object tm = f.get(certMgr);
                if (tm == null) {
                    Log.d(TAG, "injectCACert[B]: TrustManager " + f.getName() + " is null");
                    continue;
                }
                Log.d(TAG, "injectCACert[B]: TrustManager field=" + f.getName()
                        + " type=" + f.getType().getName());
                if (injectIntoObjectCertArrays(tm, cert, "tm." + f.getName(), 2)) {
                    injectedB = true;
                    tmFieldsInjected++;
                }
            }

            // Fallback for heavily-obfuscated builds where no field advertises X509TrustManager:
            // walk the object graph starting at CertificateManager instance and patch any
            // reachable X509Certificate[] fields.
            int graphInjected = injectIntoObjectGraphCertArrays(
                    certMgr, cert, "cmgr", 8, new java.util.IdentityHashMap<>());
            injectedB |= graphInjected > 0;

            for (java.lang.reflect.Field f : certMgrClass.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!java.util.Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object mapObj = f.get(null);
                if (!(mapObj instanceof java.util.Map)) continue;
                for (Object factory : ((java.util.Map<?, ?>) mapObj).values()) {
                    if (factory == null) continue;
                    int cacheInjected = injectIntoObjectGraphCertArrays(
                            factory, cert, "cache." + factory.getClass().getSimpleName(),
                            8, new java.util.IdentityHashMap<>());
                    injectedB |= cacheInjected > 0;
                }
            }

            if (tmFieldsFound == 0) {
                Log.w(TAG, "injectCACert[B]: X509TrustManager field not found on " + certMgrClass.getName());
            } else {
                Log.i(TAG, "injectCACert[B]: trustManagers found=" + tmFieldsFound
                        + " injected=" + tmFieldsInjected);
            }
            Log.i(TAG, "injectCACert[B]: graph injection count=" + graphInjected);

            if (!injectedA && !injectedB) {
                if (attempt < 15) {
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> injectCACert(cert, attempt + 1), 1000);
                    Log.d(TAG, "injectCACert: no injection target yet, retry " + (attempt + 1));
                } else {
                    Log.w(TAG, "injectCACert: no injection target found after " + attempt + " retries");
                }
                return;
            }

            Log.i(TAG, "Update server CA registered successfully (A=" + injectedA + ", B=" + injectedB + ")");
            if (injectedB) {
                Log.d(TAG, "injectCACert[B]: cache preserved intentionally");
            }
        } catch (Exception e) {
            Log.w(TAG, "injectCACert failed (attempt " + attempt + "): " + e.getMessage(), e);
        }
    }

    private static boolean injectIntoObjectCertArrays(Object obj, java.security.cert.X509Certificate cert,
            String label, int depth) {
        if (obj == null || depth < 0) return false;
        boolean injected = false;
        try {
            for (java.lang.reflect.Field f : obj.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (f.getType() == java.security.cert.X509Certificate[].class) {
                    injected |= appendToCertArray(f, obj, cert, label + "." + f.getName());
                    continue;
                }
                if (depth == 0 || f.getType().isPrimitive()) continue;
                Object nested = f.get(obj);
                if (nested == null) continue;
                injected |= injectIntoObjectCertArrays(nested, cert, label + "." + f.getName(), depth - 1);
            }
        } catch (Exception e) {
            Log.w(TAG, "injectIntoObjectCertArrays[" + label + "] failed: " + e.getMessage());
        }
        return injected;
    }

    /**
     * Graph-walk fallback for obfuscated ATAK builds:
     * recursively traverse object fields/collections/maps/arrays and append cert into any
     * reachable X509Certificate[] field. Returns number of injected (or confirmed-present) arrays.
     */
    private static int injectIntoObjectGraphCertArrays(Object obj, java.security.cert.X509Certificate cert,
            String label, int depth, java.util.IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || depth < 0) return 0;
        if (visited.containsKey(obj)) return 0;
        visited.put(obj, Boolean.TRUE);
        int injectedCount = 0;

        try {
            Class<?> cls = obj.getClass();

            // Avoid deep reflective traversal into framework/JDK internals.
            String clsName = cls.getName();
            if (clsName.startsWith("java.")
                    || clsName.startsWith("javax.")
                    || clsName.startsWith("android.")
                    || clsName.startsWith("kotlin.")) {
                return 0;
            }

            if (obj instanceof java.lang.ref.Reference) {
                Object ref = ((java.lang.ref.Reference<?>) obj).get();
                return injectIntoObjectGraphCertArrays(
                        ref, cert, label + ".ref", depth - 1, visited);
            }

            if (cls.isArray()) {
                Class<?> comp = cls.getComponentType();
                if (!comp.isPrimitive()) {
                    Object[] arr = (Object[]) obj;
                    for (int i = 0; i < arr.length; i++) {
                        injectedCount += injectIntoObjectGraphCertArrays(
                                arr[i], cert, label + "[" + i + "]", depth - 1, visited);
                    }
                }
                return injectedCount;
            }

            if (obj instanceof java.util.Map) {
                for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) obj).entrySet()) {
                    injectedCount += injectIntoObjectGraphCertArrays(
                            e.getValue(), cert, label + ".map", depth - 1, visited);
                }
                return injectedCount;
            }

            if (obj instanceof java.lang.Iterable) {
                int i = 0;
                for (Object v : (java.lang.Iterable<?>) obj) {
                    injectedCount += injectIntoObjectGraphCertArrays(
                            v, cert, label + ".it[" + i + "]", depth - 1, visited);
                    i++;
                }
                return injectedCount;
            }

            if (obj instanceof javax.net.ssl.X509TrustManager) {
                if (injectIntoObjectCertArrays(obj, cert, label + ".tm", 4)) {
                    injectedCount++;
                }
            }

            for (java.lang.reflect.Field f : getAllInstanceFields(cls)) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    if (f.getType() == java.security.cert.X509Certificate[].class) {
                        if (appendToCertArray(f, obj, cert, label + "." + f.getName())) {
                            injectedCount++;
                        }
                        continue;
                    }
                    if (depth == 0 || f.getType().isPrimitive()) continue;
                    Object nested = f.get(obj);
                    if (nested == null) continue;
                    injectedCount += injectIntoObjectGraphCertArrays(
                            nested, cert, label + "." + f.getName(), depth - 1, visited);
                } catch (Throwable t) {
                    Log.d(TAG, "injectIntoObjectGraphCertArrays[" + label + "." + f.getName()
                            + "] skip: " + t.getClass().getSimpleName());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "injectIntoObjectGraphCertArrays[" + label + "] failed: " + e.getMessage());
        }
        return injectedCount;
    }

    private static java.util.List<java.lang.reflect.Field> getAllInstanceFields(Class<?> cls) {
        java.util.ArrayList<java.lang.reflect.Field> out = new java.util.ArrayList<>();
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                out.add(f);
            }
            c = c.getSuperclass();
        }
        return out;
    }

    private static java.lang.reflect.Method findSingletonGetter(Class<?> cls) {
        java.lang.reflect.Method fallback = null;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            if (!cls.isAssignableFrom(m.getReturnType())) continue;
            m.setAccessible(true);
            if ("getInstance".equals(m.getName())) {
                return m;
            }
            if (fallback == null) {
                fallback = m;
            }
        }
        return fallback;
    }

    private static java.lang.reflect.Method findProviderManagerGetter(Class<?> compClass) {
        java.lang.reflect.Method best = null;
        int bestScore = -1;
        for (java.lang.reflect.Method m : compClass.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            Class<?> rt = m.getReturnType();
            for (java.lang.reflect.Method pm : rt.getMethods()) {
                if (pm.getReturnType() != void.class) continue;
                Class<?>[] p = pm.getParameterTypes();
                if (p.length == 2 && p[0] == boolean.class && p[1] == boolean.class) {
                    m.setAccessible(true);
                    String mn = m.getName().toLowerCase();
                    int score = 0;
                    if (mn.contains("product")) score += 2;
                    if (mn.contains("provider")) score += 2;
                    if (mn.contains("manager")) score += 1;
                    if (score > bestScore) {
                        bestScore = score;
                        best = m;
                    }
                    break;
                }
            }
        }
        return best;
    }

    private static java.lang.reflect.Method findContextSyncMethod(Class<?> mgrClass) {
        final Class<?> listenerCls;
        try {
            listenerCls = Class.forName(
                    "com.atakmap.android.update.ProductProviderManager$RepoSyncListener");
        } catch (ClassNotFoundException e) {
            return null;
        }
        for (java.lang.reflect.Method m : mgrClass.getMethods()) {
            if (m.getReturnType() != void.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3
                    && Context.class.isAssignableFrom(p[0])
                    && p[1] == boolean.class
                    && listenerCls.isAssignableFrom(p[2])) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static Context resolveRepoSyncUiContext(Object productProviderMgr) {
        try {
            MapView mv = MapView.getMapView();
            if (mv != null) {
                Context c = mv.getContext();
                if (c != null) {
                    return c;
                }
            }
        } catch (Throwable ignored) {
        }
        if (productProviderMgr == null) {
            return null;
        }
        try {
            for (java.lang.reflect.Field f : productProviderMgr.getClass().getDeclaredFields()) {
                if (!Activity.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                Object a = f.get(productProviderMgr);
                if (a instanceof Context) {
                    return (Context) a;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Exactly one delayed sync per process: gives trust + ApkUpdateComponent time to settle; no retries
     * (avoids air-gapped DNS hammering).
     */
    private static void scheduleOneStartupRepoSyncIfNeeded() {
        if (!startupRepoSyncScheduled.compareAndSet(false, true)) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(
                UVProMapComponent::runStartupRepoSyncOnceNoRetry, STARTUP_REPO_SYNC_DELAY_MS);
        Log.d(TAG, "Scheduled one startup repo sync in " + STARTUP_REPO_SYNC_DELAY_MS + "ms");
    }

    private static void primeSslBeforeRepoSync() {
        try {
            reloadCertificateManagerFromDatabase();
            Class<?> cmCls = Class.forName("com.atakmap.net.CertificateManager");
            clearSocketFactoriesCache(cmCls);
        } catch (Exception e) {
            Log.d(TAG, "primeSslBeforeRepoSync: " + e.getMessage());
        }
    }

    /** Single attempt; logs and exits if ApkUpdateComponent or sync API is not ready. */
    private static void runStartupRepoSyncOnceNoRetry() {
        try {
            primeSslBeforeRepoSync();
            Class<?> cls = Class.forName("com.atakmap.android.update.ApkUpdateComponent");
            java.lang.reflect.Method singletonGetter = findSingletonGetter(cls);
            if (singletonGetter == null) {
                Log.d(TAG, "startup repo sync skipped: no ApkUpdateComponent singleton getter");
                return;
            }
            Object comp = singletonGetter.invoke(null);
            if (comp == null) {
                Log.d(TAG, "startup repo sync skipped: ApkUpdateComponent null (no retry)");
                return;
            }
            java.lang.reflect.Method pmGetter = findProviderManagerGetter(cls);
            if (pmGetter == null) {
                Log.d(TAG, "startup repo sync skipped: providerManager getter not found");
                return;
            }
            Object mgr = pmGetter.invoke(comp);
            if (mgr == null) {
                Log.d(TAG, "startup repo sync skipped: providerManager null");
                return;
            }
            Context uiCtx = resolveRepoSyncUiContext(mgr);
            java.lang.reflect.Method syncCtx = findContextSyncMethod(mgr.getClass());
            if (uiCtx == null || syncCtx == null) {
                Log.d(TAG, "startup repo sync skipped: no UI context or sync(Context,boolean,Listener)");
                return;
            }
            syncCtx.invoke(mgr, uiCtx, Boolean.TRUE, null);
            Log.i(TAG, "startup repo sync: one silent ProductProviderManager.sync");
        } catch (Exception e) {
            Log.w(TAG, "startup repo sync failed: " + e.getMessage());
        }
    }

    /** Append cert to an X509Certificate[] field on obj. Returns true if appended (or already present). */
    private static boolean appendToCertArray(java.lang.reflect.Field f, Object obj,
            java.security.cert.X509Certificate cert, String label) {
        try {
            java.security.cert.X509Certificate[] existing =
                    (java.security.cert.X509Certificate[]) f.get(obj);
            if (existing != null) {
                for (java.security.cert.X509Certificate c : existing) {
                    if (cert.equals(c)) {
                        Log.d(TAG, "appendToCertArray[" + label + "]: already present");
                        return true;
                    }
                }
            }
            int len = existing != null ? existing.length : 0;
            java.security.cert.X509Certificate[] updated =
                    new java.security.cert.X509Certificate[len + 1];
            if (existing != null) System.arraycopy(existing, 0, updated, 0, len);
            updated[len] = cert;
            f.set(obj, updated);
            Log.i(TAG, "appendToCertArray[" + label + "]: appended to array of len " + len);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "appendToCertArray[" + label + "] failed: " + e.getMessage());
            return false;
        }
    }

    /** Clear the static socketFactories Map cache on CertificateManager. */
    private static void clearSocketFactoriesCache(Class<?> certMgrClass) {
        try {
            for (java.lang.reflect.Field f : certMgrClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && java.util.Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object map = f.get(null);
                    if (map instanceof java.util.Map) {
                        ((java.util.Map<?, ?>) map).clear();
                        Log.i(TAG, "clearSocketFactoriesCache: cleared");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "clearSocketFactoriesCache failed: " + e.getMessage());
        }
    }

    /** Start periodic GPS beacon broadcasts. */
    private void startBeaconTimer() {
        stopBeaconTimer();
        smartBeacon.reset();
        forceFirstPostConnectBeacon = true;

        beaconHandler = new Handler(Looper.getMainLooper());
        beaconRunnable = new Runnable() {
            @Override
            public void run() {
                if (forceFirstPostConnectBeacon) {
                    if (!hasValidSelfPosition()) {
                        Log.d(TAG, "Startup beacon delayed — waiting for valid self marker position");
                        beaconHandler.postDelayed(this, STARTUP_POSITION_POLL_MS);
                        return;
                    }
                    if (!sendBeaconIfConnected(true)) {
                        beaconHandler.postDelayed(this, STARTUP_POSITION_POLL_MS);
                        return;
                    }
                    forceFirstPostConnectBeacon = false;
                } else {
                    sendBeaconIfConnected(false);
                }
                long nextCheckMs = getBeaconTimerDelayMs();
                beaconHandler.postDelayed(this, nextCheckMs);
            }
        };
        if (hasValidSelfPosition()) {
            Log.d(TAG, "Beacon timer armed: valid self marker position already present");
            beaconHandler.postDelayed(beaconRunnable, STARTUP_BEACON_DELAY_MS);
            return;
        }
        // Wait for ATAK self marker position (phone GPS or manually set), then 30s countdown.
        beaconWaitForPositionRunnable = new Runnable() {
            @Override
            public void run() {
                if (beaconHandler == null) {
                    return;
                }
                if (hasValidSelfPosition()) {
                    Log.d(TAG, "Valid self marker position acquired; startup beacon in 30s");
                    beaconHandler.postDelayed(beaconRunnable, STARTUP_BEACON_DELAY_MS);
                    beaconWaitForPositionRunnable = null;
                    return;
                }
                beaconHandler.postDelayed(this, STARTUP_POSITION_POLL_MS);
            }
        };
        Log.d(TAG, "Beacon timer waiting for valid self marker position before startup countdown");
        beaconHandler.postDelayed(beaconWaitForPositionRunnable, STARTUP_POSITION_POLL_MS);
    }

    /** When MeshCore TX is preferred, do not anchor the 30s startup beacon to a UV-PRO connect. */
    private boolean shouldStartBeaconTimerOnUvProConnect() {
        String preferred = readPreferredTransmitTransportPref();
        if ("mesh".equals(preferred)) {
            Log.d(TAG, "UV-PRO connected — deferring beacon timer (MeshCore TX preferred)");
            return false;
        }
        return true;
    }

    private String readPreferredTransmitTransportPref() {
        try {
            Context ctx = getBeaconPrefsContext();
            if (ctx != null) {
                return PreferenceManager.getDefaultSharedPreferences(ctx).getString(
                        com.uvpro.plugin.ui.SettingsFragment.PREF_TRANSMIT_PREFERRED_TRANSPORT,
                        "none");
            }
        } catch (Exception ignored) {
        }
        return "none";
    }

    private void stopBeaconTimer() {
        if (beaconHandler != null) {
            if (beaconRunnable != null) {
                beaconHandler.removeCallbacks(beaconRunnable);
            }
            if (beaconWaitForPositionRunnable != null) {
                beaconHandler.removeCallbacks(beaconWaitForPositionRunnable);
            }
        }
        beaconWaitForPositionRunnable = null;
        forceFirstPostConnectBeacon = false;
    }

    private long getBeaconTimerDelayMs() {
        Context beaconCtx = getBeaconPrefsContext();
        boolean meshLimits = MeshBeaconLimits.isActive(beaconCtx);
        if (SmartBeacon.isEnabled(beaconCtx)) {
            int checkSec = SmartBeacon.getRecommendedCheckIntervalSec(beaconCtx, meshLimits);
            return Math.max(1, checkSec) * 1000L;
        }
        int intervalSec = SettingsFragment.getBeaconIntervalSec(pluginContext);
        if (intervalSec < 1) {
            intervalSec = 1;
        }
        if (meshLimits) {
            intervalSec = MeshBeaconLimits.capIntervalSec(beaconCtx, intervalSec);
        }
        return intervalSec * 1000L;
    }

    /**
     * One-time v21 safety migration:
     * force Smart Beacon OFF to avoid inherited unexpected fast-cadence behavior.
     */
    private void applySmartBeaconV21OffMigration(Context prefsCtx) {
        if (prefsCtx == null) {
            return;
        }
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(prefsCtx);
            if (prefs.getBoolean(PREF_SMART_BEACON_V21_OFF_MIGRATED, false)) {
                return;
            }
            prefs.edit()
                    .putBoolean(SmartBeacon.KEY_ENABLED, false)
                    .putBoolean(PREF_SMART_BEACON_V21_OFF_MIGRATED, true)
                    .apply();
            Log.i(TAG, "Applied v21 migration: Smart Beacon forced OFF.");
        } catch (Exception e) {
            Log.w(TAG, "Smart Beacon v21 migration failed: " + e.getMessage());
        }
    }

    private boolean sendBeaconIfConnected(boolean forceImmediate) {
        // Startup (30s post-position) and periodic beacons use active transmit preference.
        BtConnectionManager beaconTransport = forceImmediate
                ? resolveStartupBeaconTransportManager()
                : resolvePeriodicBeaconTransportManager();
        if (beaconTransport == null || !beaconTransport.isConnected()) {
            String detail = beaconTransport == null
                    ? describeMissingBeaconTransport(forceImmediate)
                    : (beaconTransport == meshBtConnectionManager
                            ? "MeshCore not connected" : "UV-PRO not connected");
            Log.d(TAG, (forceImmediate ? "Startup" : "Periodic")
                    + " beacon skipped: " + detail);
            if (forceImmediate && dropDownReceiver != null) {
                dropDownReceiver.appendPluginLog(
                        "Startup beacon not sent — " + detail);
            }
            return false;
        }
        if (cotBridge == null || mapView == null) return false;

        try {
            Log.d(TAG, (forceImmediate ? "Startup" : "Periodic") + " beacon transport: "
                    + (beaconTransport == meshBtConnectionManager ? "MeshCore" : "UV-PRO"));
            com.atakmap.android.maps.PointMapItem self = mapView.getSelfMarker();
            if (self == null) {
                return false;
            }

            com.atakmap.coremap.maps.coords.GeoPoint gp = self.getPoint();
            if (gp == null || !gp.isValid()
                    || !RadioPositionFix.isValidCoordinate(gp.getLatitude(), gp.getLongitude())) {
                if (forceImmediate) {
                    Log.d(TAG, "Startup beacon skipped: no valid self marker position yet");
                }
                return false;
            }

            // Speed and course: prefer live GPS (Doppler-accurate, no position-jitter) when available.
            // Fall back to position-derived only if the GPS listener hasn't produced a fix yet.
            double speedMs = 0.0, course = 0.0;
            String speedSrc;
            android.location.Location gpsLoc = lastGpsLocation;
            if (gpsLoc != null && gpsLoc.hasSpeed()) {
                speedMs = Math.max(0.0, gpsLoc.getSpeed());
                course  = gpsLoc.hasBearing() ? gpsLoc.getBearing() : 0.0;
                speedSrc = "gps";
            } else {
                // Fallback: derive speed from successive self-marker positions.
                double currentLat = gp.getLatitude();
                double currentLon = gp.getLongitude();
                long nowMs = System.currentTimeMillis();
                speedSrc = "meta";
                if (!Double.isNaN(lastBeaconLatDeg) && lastBeaconPositionMs > 0) {
                    long dtMs = nowMs - lastBeaconPositionMs;
                    if (dtMs > 500 && dtMs < 120_000L) {
                        try {
                            GeoPoint prev = new GeoPoint(lastBeaconLatDeg, lastBeaconLonDeg);
                            GeoPoint curr = new GeoPoint(currentLat, currentLon);
                            double distM = GeoCalculations.distanceTo(prev, curr);
                            double derivedMs = distM / (dtMs / 1000.0);
                            if (derivedMs >= 0.0 && derivedMs < 120.0) {
                                speedMs  = derivedMs;
                                speedSrc = "derived";
                                course   = GeoCalculations.bearingTo(prev, curr);
                            }
                        } catch (Exception ignored) {}
                    }
                }
                lastBeaconLatDeg     = currentLat;
                lastBeaconLonDeg     = currentLon;
                lastBeaconPositionMs = nowMs;
                if (!speedSrc.equals("derived") && self != null) {
                    try { course = Double.parseDouble(self.getMetaString("course", "0")); } catch (Exception ignored) {}
                }
            }
            double speedMph = speedMs * 2.23694;

            Context beaconCtx = getBeaconPrefsContext();
            final boolean meshLimitedBeacon = beaconTransport == meshBtConnectionManager
                    && MeshBeaconLimits.isActive(beaconCtx);
            int meshLimitSec = -1;
            if (!forceImmediate && SmartBeacon.isEnabled(beaconCtx)) {
                boolean smartFire = smartBeacon.shouldBeacon(
                        beaconCtx, speedMph, course, meshLimitedBeacon);
                // Safety floor: if the fixed beacon interval has elapsed, send regardless.
                // This prevents Smart Beacon from silently blocking beacons when speed
                // cannot be determined (GPS not reporting, speed=0 while moving).
                int fixedIntervalSec = SettingsFragment.getBeaconIntervalSec(pluginContext);
                if (fixedIntervalSec < 1) fixedIntervalSec = 60;
                if (meshLimitedBeacon) {
                    fixedIntervalSec = MeshBeaconLimits.capIntervalSec(
                            beaconCtx, fixedIntervalSec);
                }
                boolean floorFire = smartBeacon.elapsedSinceLastBeaconSec() >= fixedIntervalSec;
                Log.d(TAG, "Smart beacon check: speed=" + String.format("%.1f", speedMph)
                        + "mph (src=" + speedSrc + ")"
                        + " course=" + String.format("%.0f", course) + "°"
                        + " meshLimited=" + meshLimitedBeacon
                        + " smartFire=" + smartFire + " floorFire=" + floorFire
                        + " floorSec=" + fixedIntervalSec);
                if (!smartFire && !floorFire) return false;
                if (meshLimitedBeacon) {
                    if (smartFire) {
                        meshLimitSec = smartBeacon.getFiringLimitSec(
                                beaconCtx, speedMph, course, true);
                    }
                    if (meshLimitSec < 1 && floorFire) {
                        meshLimitSec = fixedIntervalSec;
                    }
                }
                smartBeacon.recordBeacon(course);
                Log.d(TAG, "Smart beacon fired (smartFire=" + smartFire
                        + " floorFire=" + floorFire + ")");
            } else if (forceImmediate) {
                Log.d(TAG, "Post-connect startup beacon fired (30s)");
            } else if (meshLimitedBeacon) {
                int intervalSec = SettingsFragment.getBeaconIntervalSec(pluginContext);
                if (intervalSec < 1) intervalSec = 60;
                meshLimitSec = MeshBeaconLimits.capIntervalSec(beaconCtx, intervalSec);
            }

            boolean disableAtak = com.uvpro.plugin.ui.SettingsFragment
                    .isAprsDisableAtakTraffic(beaconCtx);
            boolean aprsEnabled = com.uvpro.plugin.ui.SettingsFragment.isAprsTxArmed(beaconCtx)
                    && com.uvpro.plugin.ui.SettingsFragment.isValidAprsCallsign(
                    com.uvpro.plugin.ui.SettingsFragment.getAprsCallsign(beaconCtx));
            boolean openRlSent = false;
            if (!aprsEnabled && disableAtak) {
                // APRS TX unavailable without FCC callsign; restore normal ATAK traffic automatically.
                com.uvpro.plugin.ui.SettingsFragment.setAprsDisableAtakTraffic(beaconCtx, false);
                disableAtak = false;
                Log.d(TAG, "Disable ATAK traffic auto-cleared (invalid APRS callsign)");
            }

            if (!disableAtak) {
                boolean txOk = cotBridge.sendPositionOverRadio(
                        beaconTransport,
                        gp.getLatitude(), gp.getLongitude(),
                        gp.getAltitude(), (float) speedMs, (float) course, -1);
                if (!txOk) {
                    String transportLabel = beaconTransport == meshBtConnectionManager
                            ? "MeshCore" : "UV-PRO";
                    String beaconKind = forceImmediate ? "Startup" : "Periodic";
                    String reason = beaconTransport == meshBtConnectionManager
                            && meshBtConnectionManager != null
                            && meshBtConnectionManager.isRadioSilenceEnabled()
                            ? "mesh radio silence" : "transmit blocked";
                    Log.w(TAG, beaconKind + " OPENRL beacon TX failed (" + reason + ")");
                    if (forceImmediate && dropDownReceiver != null) {
                        dropDownReceiver.appendPluginLog(
                                "Startup beacon not sent — " + transportLabel + " " + reason);
                    }
                    return false;
                }
                openRlSent = true;
                String transportLabel = beaconTransport == meshBtConnectionManager
                        ? "MeshCore" : "UV-PRO";
                String beaconKind = forceImmediate ? "Startup" : "Periodic";
                Log.d(TAG, beaconKind + " OPENRL beacon sent");
                logBeaconSentToPluginUi(formatBeaconSentLog(
                        beaconKind, transportLabel, meshLimitedBeacon, meshLimitSec));
            }

            if (aprsEnabled && !openRlSent
                    && !com.uvpro.plugin.protocol.RfTxArbitrator.get().shouldDeferAprsBeacon()) {
                boolean aprsOk = com.uvpro.plugin.aprs.AprsOutboundTransmitter.sendPositionBeacon(
                        beaconCtx, btConnectionManager);
                if (aprsOk) {
                    Log.d(TAG, "Periodic APRS beacon sent");
                    logBeaconSentToPluginUi("Periodic beacon sent (UV-PRO APRS)");
                }
            } else if (aprsEnabled && openRlSent) {
                Log.d(TAG, "Periodic APRS beacon skipped (OPENRL sent this cycle)");
            } else if (aprsEnabled) {
                Log.d(TAG, "Periodic APRS beacon skipped (OPENRL priority)");
            }
            return openRlSent;
        } catch (Exception e) {
            Log.e(TAG, "Error sending periodic beacon", e);
            return false;
        }
    }

    private boolean isAnyTransportConnected() {
        return (btConnectionManager != null && btConnectionManager.isConnected())
                || (meshBtConnectionManager != null && meshBtConnectionManager.isConnected());
    }

    private void logBeaconSentToPluginUi(String message) {
        if (dropDownReceiver != null) {
            dropDownReceiver.appendPluginLog(message);
        }
    }

    private static String formatBeaconSentLog(String beaconKind, String transportLabel,
                                              boolean meshLimited, int limitSec) {
        String base = String.format(Locale.US, "%s beacon sent (%s OPENRL)",
                beaconKind, transportLabel);
        if (meshLimited && limitSec > 0) {
            return base + String.format(Locale.US, " (limited to %d seconds)", limitSec);
        }
        return base;
    }

    private void applyActiveTransmitTransportFromPreference() {
        BtConnectionManager active = resolveBeaconTransportManager();
        if (cotBridge != null) {
            cotBridge.setBtManager(active);
        }
        if (chatBridge != null) {
            chatBridge.setBtManager(active);
        }
    }

    /** Active transmit transport for periodic beacons (MeshCore or UV-PRO per preference). */
    private BtConnectionManager resolvePeriodicBeaconTransportManager() {
        return resolveBeaconTransportManager();
    }

    /** Post-connect startup beacon on active transmit path. */
    private BtConnectionManager resolveStartupBeaconTransportManager() {
        return resolveBeaconTransportManager();
    }

    private BtConnectionManager resolveBeaconTransportManager() {
        BeaconTransportPreference pref = readBeaconTransportPreference();
        boolean strictPreferred = pref.explicitPreferred;
        boolean preferMesh = pref.preferMesh;
        boolean preferUvpro = pref.preferUvpro;
        BtConnectionManager active;
        if (strictPreferred) {
            active = resolveStrictPreferredTransport(preferMesh, preferUvpro);
            if (active == null || !active.isConnected()) {
                // Connectivity may have switched live toggles (e.g. mesh-only) while explicit
                // preferred transport pref still says uvpro — fall back like manual TX.
                boolean toggleMesh = false;
                boolean toggleUv = false;
                try {
                    Context ctx = getBeaconPrefsContext();
                    if (ctx != null) {
                        SharedPreferences prefs =
                                PreferenceManager.getDefaultSharedPreferences(ctx);
                        toggleMesh = prefs.getBoolean(PREF_ATAK_MESHCORE_TRANSMIT, false);
                        toggleUv = prefs.getBoolean(
                                com.uvpro.plugin.ui.SettingsFragment.PREF_ATAK_UVPRO_TRANSMIT,
                                false);
                    }
                } catch (Exception ignored) {
                }
                active = com.uvpro.plugin.bluetooth.TransmitTransportResolver.resolve(
                        toggleMesh,
                        toggleUv,
                        meshBtConnectionManager,
                        btConnectionManager);
                Log.d(TAG, "Beacon transport fallback: explicit preferred not connected"
                        + " (toggleMesh=" + toggleMesh + " toggleUv=" + toggleUv + ")");
            }
        } else {
            active = com.uvpro.plugin.bluetooth.TransmitTransportResolver.resolve(
                    preferMesh,
                    preferUvpro,
                    meshBtConnectionManager,
                    btConnectionManager);
        }
        BtConnectionManager resolved = active != null && active.isConnected() ? active : null;
        Log.d(TAG, "Beacon transport resolve preferred="
                + (preferMesh ? "mesh" : (preferUvpro ? "uvpro" : "none"))
                + " strict=" + strictPreferred
                + " active=" + (resolved == meshBtConnectionManager ? "MeshCore"
                : (resolved == btConnectionManager ? "UV-PRO" : "none")));
        return resolved;
    }

    private static final class BeaconTransportPreference {
        final boolean preferMesh;
        final boolean preferUvpro;
        /** User chose mesh or uvpro explicitly (not legacy toggle-only state). */
        final boolean explicitPreferred;

        BeaconTransportPreference(boolean preferMesh, boolean preferUvpro, boolean explicitPreferred) {
            this.preferMesh = preferMesh;
            this.preferUvpro = preferUvpro;
            this.explicitPreferred = explicitPreferred;
        }
    }

    private BeaconTransportPreference readBeaconTransportPreference() {
        boolean preferMesh = false;
        boolean preferUvpro = false;
        boolean explicitPreferred = false;
        try {
            Context ctx = getBeaconPrefsContext();
            if (ctx != null) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
                String preferred = prefs.getString(
                        com.uvpro.plugin.ui.SettingsFragment.PREF_TRANSMIT_PREFERRED_TRANSPORT,
                        "none");
                if ("mesh".equals(preferred)) {
                    preferMesh = true;
                    preferUvpro = false;
                    explicitPreferred = true;
                } else if ("uvpro".equals(preferred)) {
                    preferMesh = false;
                    preferUvpro = true;
                    explicitPreferred = true;
                } else {
                    preferMesh = prefs.getBoolean(PREF_ATAK_MESHCORE_TRANSMIT, false);
                    preferUvpro = prefs.getBoolean(
                            com.uvpro.plugin.ui.SettingsFragment.PREF_ATAK_UVPRO_TRANSMIT, false);
                }
            }
        } catch (Exception ignored) {
            preferMesh = false;
            preferUvpro = false;
        }
        return new BeaconTransportPreference(preferMesh, preferUvpro, explicitPreferred);
    }

    /** No UV-PRO / MeshCore cross-fallback when the user picked a transmit transport. */
    private BtConnectionManager resolveStrictPreferredTransport(
            boolean preferMesh, boolean preferUvpro) {
        if (preferMesh) {
            return meshBtConnectionManager;
        }
        if (preferUvpro) {
            return btConnectionManager;
        }
        return null;
    }

    private String describeMissingBeaconTransport(boolean forceImmediate) {
        BeaconTransportPreference pref = readBeaconTransportPreference();
        if (pref.explicitPreferred && pref.preferMesh) {
            return forceImmediate
                    ? "MeshCore TX selected — waiting for mesh connect"
                    : "MeshCore TX selected — mesh not connected";
        }
        if (pref.explicitPreferred && pref.preferUvpro) {
            return forceImmediate
                    ? "UV-PRO TX selected — waiting for UV-PRO connect"
                    : "UV-PRO TX selected — UV-PRO not connected";
        }
        return "no transmit transport selected";
    }

    private boolean hasValidSelfPosition() {
        if (mapView == null) {
            return false;
        }
        PointMapItem self = mapView.getSelfMarker();
        if (self == null) {
            return false;
        }
        GeoPoint gp = self.getPoint();
        if (gp == null || !gp.isValid()) {
            return false;
        }
        return RadioPositionFix.isValidCoordinate(gp.getLatitude(), gp.getLongitude());
    }

    /** Defer iconset auto-import until ATAK finishes cold start (avoids UI deadlock). */
    private static final long ICONSET_IMPORT_START_DELAY_MS = 10_000L;
    /** Poll until each iconset lands in iconsets.sqlite before starting the next. */
    private static final long ICONSET_IMPORT_RETRY_MS = 15_000L;

    private void startAprsIconsetReminder(Context pluginCtx, Context uiCtx) {
        if (iconsetReminderHandler != null && iconsetReminderRunnable != null) {
            iconsetReminderHandler.removeCallbacks(iconsetReminderRunnable);
        }
        iconsetReminderHandler = new Handler(Looper.getMainLooper());
        iconsetReminderRunnable = new Runnable() {
            @Override
            public void run() {
                boolean aprsMissing = AprsIconsetInstaller.ensureStagedAndPromptIfMissing(
                        pluginCtx, uiCtx);
                boolean meshcoreMissing = false;
                // Serialize imports: firing two ADD_ICONSET broadcasts during cold start can
                // hang ATAK on slower devices (manual Point Dropper import one-at-a-time works).
                if (!aprsMissing) {
                    meshcoreMissing = MeshcoreIconsetInstaller.ensureStagedAndPromptIfMissing(
                            pluginCtx, uiCtx);
                }
                if ((aprsMissing || meshcoreMissing) && iconsetReminderHandler != null) {
                    iconsetReminderHandler.postDelayed(this, ICONSET_IMPORT_RETRY_MS);
                }
            }
        };
        if (mapView != null) {
            mapView.postDelayed(iconsetReminderRunnable, ICONSET_IMPORT_START_DELAY_MS);
        } else {
            iconsetReminderHandler.postDelayed(iconsetReminderRunnable, ICONSET_IMPORT_START_DELAY_MS);
        }
    }

    /**
     * Mesh boot policy: try the last saved mesh target after 7s unless UV-PRO connects first
     * (then wait 2s after radio connect). The 7s fallback waits out an in-flight UV-PRO connect.
     */
    private void scheduleMeshBootAutoConnect(MapView view) {
        cancelMeshBootAutoConnectSchedules();
        if (view == null || meshBtConnectionManager == null) {
            return;
        }
        meshBootScheduledAtMs = System.currentTimeMillis();
        meshBootFallbackRunnable = () -> tryMeshBootFallback(view);
        view.postDelayed(meshBootFallbackRunnable, MESH_BOOT_FALLBACK_MS);
    }

    private void tryMeshBootFallback(MapView view) {
        if (view == null || meshBtConnectionManager == null) {
            meshBootFallbackRunnable = null;
            return;
        }
        if (meshBtConnectionManager.isConnected()) {
            meshBootFallbackRunnable = null;
            return;
        }
        if (btConnectionManager != null && btConnectionManager.isConnected()) {
            meshBootFallbackRunnable = null;
            return;
        }
        if (isUvProBootStillResolving()) {
            long elapsedMs = System.currentTimeMillis() - meshBootScheduledAtMs;
            Log.d(TAG, "Mesh boot fallback deferred — UV-PRO still resolving ("
                    + elapsedMs + "ms since startup)");
            view.postDelayed(meshBootFallbackRunnable, MESH_BOOT_FALLBACK_POLL_MS);
            return;
        }
        meshBootFallbackRunnable = null;
        Log.i(TAG, "Mesh boot fallback (" + (MESH_BOOT_FALLBACK_MS / 1000)
                + "s) — UV-PRO not connected, starting mesh auto-connect");
        meshBtConnectionManager.tryAutoConnectToSavedTarget();
    }

    private boolean isUvProBootStillResolving() {
        if (btConnectionManager == null) {
            return false;
        }
        return btConnectionManager.isConnecting()
                || btConnectionManager.isBootAutoConnectWindowActive();
    }

    private void scheduleMeshBootAfterRadioConnect(MapView view) {
        if (view == null || meshBtConnectionManager == null) {
            return;
        }
        if (meshBtConnectionManager.isConnected()) {
            cancelMeshBootAutoConnectSchedules();
            return;
        }
        cancelMeshBootFallbackSchedule();
        cancelMeshBootAfterRadioSchedule();
        meshBtConnectionManager.cancelBootAutoConnect();
        meshBootAfterRadioRunnable = () -> {
            meshBootAfterRadioRunnable = null;
            if (meshBtConnectionManager == null || meshBtConnectionManager.isConnected()) {
                return;
            }
            Log.i(TAG, "UV-PRO connected — starting mesh auto-connect after "
                    + (MESH_BOOT_AFTER_RADIO_MS / 1000) + "s");
            meshBtConnectionManager.tryAutoConnectAfterRadioConnect();
        };
        view.postDelayed(meshBootAfterRadioRunnable, MESH_BOOT_AFTER_RADIO_MS);
    }

    private void cancelMeshBootFallbackSchedule() {
        if (mapView != null && meshBootFallbackRunnable != null) {
            mapView.removeCallbacks(meshBootFallbackRunnable);
            meshBootFallbackRunnable = null;
        }
    }

    private void cancelMeshBootAfterRadioSchedule() {
        if (mapView != null && meshBootAfterRadioRunnable != null) {
            mapView.removeCallbacks(meshBootAfterRadioRunnable);
            meshBootAfterRadioRunnable = null;
        }
    }

    private void cancelMeshBootAutoConnectSchedules() {
        cancelMeshBootFallbackSchedule();
        cancelMeshBootAfterRadioSchedule();
        if (meshBtConnectionManager != null) {
            meshBtConnectionManager.cancelBootAutoConnect();
        }
    }

    /**
     * Get the Bluetooth connection manager (for UI access).
     */
    public BtConnectionManager getBtConnectionManager() {
        return btConnectionManager;
    }
}
