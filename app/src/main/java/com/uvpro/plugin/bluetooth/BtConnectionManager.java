package com.uvpro.plugin.bluetooth;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import androidx.annotation.Nullable;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.preference.PreferenceManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.uvpro.plugin.kiss.KissFrameDecoder;
import com.uvpro.plugin.kiss.KissFrameEncoder;
import com.uvpro.plugin.protocol.PacketRouter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages Bluetooth SPP connections to BTECH UV-PRO radios.
 *
 * The BTECH UV-PRO exposes a Bluetooth SPP (Serial Port Profile) service
 * that speaks the KISS TNC protocol. Data flows:
 *
 *   Android ←(BT SPP)→ BTECH Radio ←(RF)→ Other Radios
 *
 * This class handles:
 * - Discovering paired BTECH devices
 * - Establishing SPP connections
 * - Reading incoming KISS frames in a background thread
 * - Sending outbound KISS frames
 * - Auto-reconnection on connection loss
 * - Event-driven reconnect when the saved radio re-appears (ACL / UUID / BT enabled)
 * - Staggered direct SPP attempts when Classic radios power on without ACL broadcasts
 */
public class BtConnectionManager {

    private static final String TAG = "UVPro.BT";

    // Standard SPP UUID
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Context context;
    private final PacketRouter packetRouter;
    private final KissFrameDecoder kissDecoder;
    private final KissFrameEncoder kissEncoder;

    private BluetoothAdapter btAdapter;
    private BluetoothSocket btSocket;
    private InputStream inputStream;
    private OutputStream outputStream;

    private Thread readThread;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean radioSilenceEnabled = new AtomicBoolean(false);
    private final AtomicLong lastIoActivityMs = new AtomicLong(0L);
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    private BluetoothDevice lastDevice;
    private BluetoothDevice pendingBondDevice;
    // Probe sockets that are already connected — reused by connect() to avoid double-connect
    private final ConcurrentHashMap<String, BluetoothSocket> openProbeSockets =
            new ConcurrentHashMap<>();
    private final java.util.HashSet<String> seenScanAddresses = new java.util.HashSet<>();
    /** Addresses with credible in-range discovery hits during the active scan window. */
    private final java.util.HashSet<String> liveDiscoveryAddresses = new java.util.HashSet<>();
    private final java.util.HashMap<String, Integer> discoveryHitCount = new java.util.HashMap<>();
    private final java.util.HashSet<String> rememberedScanAddresses = new java.util.HashSet<>();
    private static final int MIN_DISCOVERY_RSSI_DBM = -92;
    /** One inquiry response is enough for in-range radios (typical desk-side RSSI). */
    private static final int STRONG_DISCOVERY_RSSI_DBM = -85;
    private static final int MIN_DISCOVERY_HITS = 2;
    private static final String PREF_LAST_RADIO_MAC = "uvpro_last_radio_mac";
    private final AtomicBoolean scanCompleteNotified = new AtomicBoolean(false);
    private BroadcastReceiver discoveryReceiver;
    private BroadcastReceiver bondStateReceiver;
    private BroadcastReceiver aclReceiver;
    private boolean discoveryReceiverRegistered = false;
    private boolean bondReceiverRegistered = false;
    private boolean aclReceiverRegistered = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean scanTimeoutScheduled = new AtomicBoolean(false);
    private static final long SCAN_TIMEOUT_MS = 12000L;
    private static final long ACL_CONNECT_DEBOUNCE_MS = 1500L;
    /** Startup window to reconnect the last connected radio without user action. */
    private static final long BOOT_AUTO_CONNECT_WINDOW_MS = 5000L;
    private static final long[] BOOT_AUTO_CONNECT_ATTEMPT_DELAYS_MS = {0L, 1500L, 3000L};
    /** Direct connect to saved bonded MAC — no discovery scan. */
    private static final long PASSIVE_RECONNECT_INTERVAL_MS = 60_000L;
    /** First passive attempt after mesh boot contention ends. */
    private static final long PASSIVE_RECONNECT_INITIAL_DELAY_MS = 3000L;
    /** Staggered SPP attempts when ACL/UUID events are silent (Classic power-on). */
    private static final long[] PASSIVE_RECONNECT_BACKOFF_MS = {
            3000L, 8000L, 15000L, 30000L, 60000L
    };
    private int passiveReconnectAttempt = 0;
    private final AtomicBoolean bootAutoConnectActive = new AtomicBoolean(false);
    /** True while the startup boot connect attempt is still running (may outlive the 5s window). */
    private final AtomicBoolean bootAutoConnectResolving = new AtomicBoolean(false);
    private final AtomicBoolean bootAutoConnectScheduled = new AtomicBoolean(false);
    private final AtomicInteger bootAutoConnectGeneration = new AtomicInteger(0);
    private Runnable bootAutoConnectTimeoutRunnable;
    private Runnable pendingAclConnectRunnable;
    private Runnable scanTimeoutRunnable;
    private Runnable passiveReconnectRunnable;
    private final AtomicBoolean passiveReconnectArmed = new AtomicBoolean(false);

    private final CopyOnWriteArrayList<ConnectionListener> listeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RawDataListener> rawDataListeners =
            new CopyOnWriteArrayList<>();
    private volatile ReconnectBlocker reconnectBlocker;
    private volatile MeshCoexistenceListener meshCoexistenceListener;

    /** Invoked while the socket is still open, before streams are closed. */
    private final CopyOnWriteArrayList<Runnable> beforeDisconnectHooks =
            new CopyOnWriteArrayList<>();

    public interface ConnectionListener {
        void onConnected(BluetoothDevice device);
        void onDisconnected(String reason);
        void onError(String error);
        void onDeviceFound(BluetoothDevice device);
        default void onScanComplete() {}
        default void onBootAutoConnectWindowStarted() {}
        default void onBootAutoConnectWindowEnded(boolean connected) {}
        /** Boot startup connect thread finished (success or all strategies exhausted). */
        default void onBootAutoConnectAttemptFinished(boolean connected) {}
    }

    /**
     * Optional listener for raw bytes received from the radio.
     * Return true if the data was consumed and should not be processed as KISS.
     */
    public interface RawDataListener {
        boolean onRawBytes(byte[] data);
    }

    @FunctionalInterface
    public interface ReconnectBlocker {
        /**
         * @return true when UV-PRO auto-reconnect should be suppressed.
         */
        boolean shouldBlockReconnect();
    }

    /**
     * Notifies when a UV-PRO connect may disrupt an active MeshCore BLE session on the same
     * phone adapter, so mesh can be restored after the radio link settles.
     */
    public interface MeshCoexistenceListener {
        /** True while MeshCore BLE session is still being established. */
        boolean isMeshConnecting();
        /** True when MeshCore has an active BLE session. */
        boolean isMeshConnected();
        void onRadioConnectStartingWhileMeshUp();
    }

    public BtConnectionManager(Context context, PacketRouter packetRouter) {
        // Use ATAK's activity context for BT operations — the plugin context
        // runs under a different package and lacks ATAK's runtime permissions.
        Context atakContext = com.atakmap.android.maps.MapView.getMapView() != null
                ? com.atakmap.android.maps.MapView.getMapView().getContext()
                : context;
        this.context = atakContext;
        this.packetRouter = packetRouter;
        this.kissDecoder = new KissFrameDecoder();
        this.kissEncoder = new KissFrameEncoder();
        this.btAdapter = BluetoothAdapter.getDefaultAdapter();
        registerBondReceiver();
        registerAclReceiver();
    }

    /** Release broadcast receivers when the plugin shuts down. */
    public void shutdown() {
        detachClassicBtAutoConnect();
    }

    /**
     * Tear down UV-PRO Classic auto-connect receivers on this manager instance.
     * {@link MeshBtConnectionManager} calls this so mesh does not register duplicate ACL/passive
     * listeners that compete with UV-PRO startup connect.
     */
    protected void detachClassicBtAutoConnect() {
        cancelPendingAclConnect();
        cancelPassiveReconnect();
        cancelBootAutoConnect();
        unregisterAclReceiver();
        unregisterBondReceiver();
    }

    private void unregisterBondReceiver() {
        if (!bondReceiverRegistered || bondStateReceiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(bondStateReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Could not unregister bond receiver", e);
        } finally {
            bondStateReceiver = null;
            bondReceiverRegistered = false;
        }
    }

    /** True while the startup reconnect window is active (up to {@link #BOOT_AUTO_CONNECT_WINDOW_MS}). */
    public boolean isBootAutoConnectWindowActive() {
        return bootAutoConnectActive.get();
    }

    /** True during the initial startup connect attempt (including after the 5s window ends). */
    public boolean isBootAutoConnectResolving() {
        return bootAutoConnectResolving.get();
    }

    /**
     * On plugin startup, try to reconnect the last connected bonded radio for up to five seconds.
     * No-op when already connected, no remembered MAC, or boot auto-connect already ran.
     */
    public void scheduleBootAutoConnect() {
        if (!com.uvpro.plugin.ui.RadioProfile.shows(
                com.uvpro.plugin.ui.RadioSurfaceCatalog.Role.UVPRO, null)) {
            Log.i(TAG, "Boot auto-connect skipped: UV-PRO hidden by radio profile");
            return;
        }
        if (!bootAutoConnectScheduled.compareAndSet(false, true)) {
            return;
        }
        if (connected.get() || connecting.get()) {
            return;
        }
        BluetoothDevice device = resolveSavedRadioDevice();
        if (device == null) {
            Log.d(TAG, "Boot auto-connect skipped: no remembered radio");
            return;
        }
        int bondState = BluetoothDevice.BOND_NONE;
        try {
            bondState = device.getBondState();
        } catch (Exception ignored) {
        }
        if (bondState != BluetoothDevice.BOND_BONDED) {
            Log.d(TAG, "Boot auto-connect skipped: remembered radio not bonded");
            return;
        }
        shouldReconnect.set(true);
        bootAutoConnectActive.set(true);
        bootAutoConnectResolving.set(true);
        bootAutoConnectGeneration.incrementAndGet();
        final int generation = bootAutoConnectGeneration.get();
        final String target = device.getAddress();
        Log.i(TAG, "Boot auto-connect started for remembered radio " + target
                + " (" + (BOOT_AUTO_CONNECT_WINDOW_MS / 1000) + "s window)");
        notifyBootAutoConnectWindowStarted();
        for (long delayMs : BOOT_AUTO_CONNECT_ATTEMPT_DELAYS_MS) {
            scheduleBootAutoConnectAttempt(device, generation, delayMs);
        }
        if (bootAutoConnectTimeoutRunnable != null) {
            mainHandler.removeCallbacks(bootAutoConnectTimeoutRunnable);
        }
        bootAutoConnectTimeoutRunnable = () -> endBootAutoConnectWindow(connected.get());
        mainHandler.postDelayed(bootAutoConnectTimeoutRunnable, BOOT_AUTO_CONNECT_WINDOW_MS);
    }

    /** Stop the startup reconnect window (e.g. user tapped Scan and Connect). */
    public void cancelBootAutoConnect() {
        if (!bootAutoConnectActive.get()) {
            bootAutoConnectGeneration.incrementAndGet();
            if (bootAutoConnectTimeoutRunnable != null) {
                mainHandler.removeCallbacks(bootAutoConnectTimeoutRunnable);
                bootAutoConnectTimeoutRunnable = null;
            }
            bootAutoConnectResolving.set(false);
            return;
        }
        endBootAutoConnectWindow(false);
    }

    private void scheduleBootAutoConnectAttempt(BluetoothDevice device, int generation, long delayMs) {
        mainHandler.postDelayed(() -> {
            if (generation != bootAutoConnectGeneration.get()
                    || !bootAutoConnectActive.get()
                    || connected.get()
                    || connecting.get()) {
                return;
            }
            if (isMeshAutoConnectBlocked()) {
                Log.d(TAG, "Boot auto-connect deferred — MeshCore has priority");
                return;
            }
            Log.i(TAG, "Boot auto-connect attempt for " + device.getAddress());
            maybeConnectToSavedRadio(device, "boot-auto-connect");
        }, delayMs);
    }

    private void endBootAutoConnectWindow(boolean connectedNow) {
        if (!bootAutoConnectActive.getAndSet(false)) {
            return;
        }
        bootAutoConnectGeneration.incrementAndGet();
        if (bootAutoConnectTimeoutRunnable != null) {
            mainHandler.removeCallbacks(bootAutoConnectTimeoutRunnable);
            bootAutoConnectTimeoutRunnable = null;
        }
        Log.i(TAG, "Boot auto-connect window ended (connected=" + connectedNow + ")");
        if (connectedNow || !connecting.get()) {
            finishBootAutoConnectAttempt(connectedNow);
        }
        notifyBootAutoConnectWindowEnded(connectedNow);
    }

    private void finishBootAutoConnectAttempt(boolean connectedNow) {
        if (!bootAutoConnectResolving.getAndSet(false)) {
            return;
        }
        notifyBootAutoConnectAttemptFinished(connectedNow);
    }

    private void notifyBootAutoConnectWindowStarted() {
        for (ConnectionListener l : listeners) {
            l.onBootAutoConnectWindowStarted();
        }
    }

    private void notifyBootAutoConnectWindowEnded(boolean connectedNow) {
        for (ConnectionListener l : listeners) {
            l.onBootAutoConnectWindowEnded(connectedNow);
        }
    }

    private void notifyBootAutoConnectAttemptFinished(boolean connectedNow) {
        for (ConnectionListener l : listeners) {
            l.onBootAutoConnectAttemptFinished(connectedNow);
        }
    }

    /**
     * Start (or keep) a low-cost passive reconnect loop: one direct connect attempt per minute
     * to the saved bonded radio. No discovery scans. Complements ACL events, which many Classic
     * BT radios never fire when they power on.
     */
    public void armPassiveReconnect() {
        resumeRadioAutoConnect(PASSIVE_RECONNECT_INTERVAL_MS);
    }

    /** Arm passive reconnect and schedule a near-term first attempt. */
    public void resumeRadioAutoConnect() {
        resumeRadioAutoConnect(PASSIVE_RECONNECT_INITIAL_DELAY_MS);
    }

    private void resumeRadioAutoConnect(long firstDelayMs) {
        if (connected.get() || connecting.get()) {
            return;
        }
        passiveReconnectArmed.set(true);
        passiveReconnectAttempt = 0;
        if (passiveReconnectRunnable != null) {
            mainHandler.removeCallbacks(passiveReconnectRunnable);
            passiveReconnectRunnable = null;
        }
        Log.i(TAG, "Radio auto-connect resumed (first attempt in "
                + (firstDelayMs / 1000) + "s, target="
                + getSavedRadioTargetAddress() + ")");
        scheduleNextPassiveReconnect(firstDelayMs);
    }

    public void setReconnectBlocker(ReconnectBlocker blocker) {
        reconnectBlocker = blocker;
    }

    public void setMeshCoexistenceListener(MeshCoexistenceListener listener) {
        meshCoexistenceListener = listener;
    }

    /** Resume UV-PRO auto-connect once MeshCore boot contention is over. */
    public void onMeshReleased() {
        if (!shouldReconnect.get() || connected.get() || connecting.get()) {
            return;
        }
        resumeRadioAutoConnect();
    }

    private boolean isMeshAutoConnectBlocked() {
        MeshCoexistenceListener listener = meshCoexistenceListener;
        if (listener == null) {
            return false;
        }
        return listener.isMeshConnecting();
    }

    /**
     * Scan for UV-PRO / UV-50 / VR-N76 radios (pairing mode and already-paired).
     * Emits bonded matches immediately, then Classic discovery for unbonded adverts.
     */
    public void startScan() {
        if (!com.uvpro.plugin.ui.RadioProfile.shows(
                com.uvpro.plugin.ui.RadioSurfaceCatalog.Role.UVPRO, null)) {
            Log.i(TAG, "Scan skipped: UV-PRO hidden by radio profile");
            return;
        }
        if (btAdapter == null) {
            notifyError("Bluetooth not available on this device");
            return;
        }
        if (!btAdapter.isEnabled()) {
            notifyError("Bluetooth is disabled. Please enable it.");
            return;
        }
        if (!checkBtPermissions()) {
            notifyError("Bluetooth permission denied. Grant in Settings > Apps.");
            return;
        }

        stopDiscoveryIfRunning();
        cancelScanTimeout();
        scanCompleteNotified.set(false);
        synchronized (seenScanAddresses) {
            seenScanAddresses.clear();
        }
        synchronized (liveDiscoveryAddresses) {
            liveDiscoveryAddresses.clear();
        }
        synchronized (discoveryHitCount) {
            discoveryHitCount.clear();
        }
        synchronized (rememberedScanAddresses) {
            rememberedScanAddresses.clear();
        }

        // Emit bonded and last-connected radios immediately (grey until live discovery).
        emitBondedRadioCandidates();
        emitRememberedRadioCandidate();

        registerDiscoveryReceiverIfNeeded();
        boolean started = false;
        try {
            started = btAdapter.startDiscovery();
        } catch (Exception e) {
            Log.w(TAG, "startDiscovery failed", e);
        }
        if (!started) {
            notifyScanCompleteOnce();
            return;
        }
        scheduleScanTimeout();
    }

    private void emitBondedRadioCandidates() {
        try {
            Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
            if (bonded == null) return;
            for (BluetoothDevice device : bonded) {
                emitRememberedDeviceRow(device, "bonded");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate bonded radios", e);
        }
    }

    /** Last successfully connected radio — shown grey when off/unbonded. */
    private void emitRememberedRadioCandidate() {
        BluetoothDevice device = lastDevice;
        if (device == null) {
            device = loadRememberedRadioDevice();
        }
        if (device == null) {
            return;
        }
        emitRememberedDeviceRow(device, "last-connected");
    }

    private void emitRememberedDeviceRow(BluetoothDevice device, String source) {
        if (device == null) {
            return;
        }
        boolean known = UvProBtDeviceMatcher.isLikelyUvProDevice(device);
        if (!known) {
            try {
                known = UvProBtDeviceMatcher.isBtechRadioMac(device.getAddress());
            } catch (Exception ignored) {
            }
        }
        if (!known) {
            return;
        }
        String mac = device.getAddress();
        if (mac != null) {
            synchronized (rememberedScanAddresses) {
                rememberedScanAddresses.add(mac.trim().toUpperCase(java.util.Locale.US));
            }
        }
        UvProBtDeviceMatcher.cachePickerModelLabel(
                device.getAddress(), UvProBtDeviceMatcher.safeDeviceName(device));
        if (markSeenIfNew(device.getAddress())) {
            Log.d(TAG, "Remembered scan row (" + source + "): "
                    + UvProBtDeviceMatcher.formatPickerLabel(device));
            notifyDeviceFound(device);
        }
    }

    public boolean isRememberedScanRow(@Nullable String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        String normalized = address.trim().toUpperCase(java.util.Locale.US);
        synchronized (rememberedScanAddresses) {
            return rememberedScanAddresses.contains(normalized);
        }
    }

    @Nullable
    private BluetoothDevice loadRememberedRadioDevice() {
        if (btAdapter == null) {
            return null;
        }
        BluetoothDevice fromLastMac = loadRememberedRadioFromPref(PREF_LAST_RADIO_MAC);
        if (fromLastMac != null && isUvProRadioTarget(fromLastMac)) {
            return fromLastMac;
        }
        if (fromLastMac != null) {
            Log.w(TAG, "Stale uvpro_last_radio_mac is not a UV-PRO radio ("
                    + resolveName(fromLastMac) + ") — clearing");
            clearRememberedRadioMacPref();
        }
        String registryMac = BluetoothDeviceRegistry.getConnectTargetAddress(context);
        if (registryMac == null || registryMac.isEmpty()) {
            return null;
        }
        try {
            BluetoothDevice fromRegistry = btAdapter.getRemoteDevice(registryMac);
            if (isUvProRadioTarget(fromRegistry)) {
                return fromRegistry;
            }
        } catch (Exception e) {
            Log.w(TAG, "Invalid registry UV-PRO connect target " + registryMac, e);
        }
        return null;
    }

    @Nullable
    private BluetoothDevice loadRememberedRadioFromPref(String prefKey) {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String mac = prefs.getString(prefKey, "").trim();
            if (mac.isEmpty()) {
                return null;
            }
            return btAdapter.getRemoteDevice(mac);
        } catch (Exception e) {
            return null;
        }
    }

    private void clearRememberedRadioMacPref() {
        try {
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putString(PREF_LAST_RADIO_MAC, "")
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private boolean isUvProRadioTarget(@Nullable BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        if (MeshBleDeviceMatcher.isMeshDevice(context, device)) {
            return false;
        }
        return UvProBtDeviceMatcher.isLikelyUvProDevice(device);
    }

    /** MeshCore uses the same base class but must not overwrite UV-PRO remembered MACs. */
    protected boolean shouldPersistUvProRadioOnConnect() {
        return true;
    }

    private void persistRememberedRadioMac(@Nullable BluetoothDevice device) {
        if (device == null || !isUvProRadioTarget(device)) {
            return;
        }
        try {
            String mac = UvProBtDeviceMatcher.normalizeMacAddress(device.getAddress());
            if (mac == null) {
                return;
            }
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putString(PREF_LAST_RADIO_MAC, mac)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    /** True if Android Bluetooth has at least one bonded UV-PRO-class radio. */
    public boolean hasBondedUvProRadio() {
        if (btAdapter == null) {
            return false;
        }
        try {
            Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
            if (bonded == null) {
                return false;
            }
            for (BluetoothDevice device : bonded) {
                if (UvProBtDeviceMatcher.isLikelyUvProDevice(device)) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not check bonded radios", e);
        }
        return false;
    }

    /** Stores a probe socket that connect() can reuse to avoid a double-connect. */
    public void addProbeSocket(String address, BluetoothSocket socket) {
        openProbeSockets.put(address, socket);
    }

    /** Closes and discards all open probe sockets. */
    public void clearProbeSockets() {
        for (BluetoothSocket s : openProbeSockets.values()) {
            try { s.close(); } catch (Exception ignored) {}
        }
        openProbeSockets.clear();
    }


    /**
     * Check (and request if possible) Bluetooth runtime permissions for Android 12+.
     * @return true if permissions are granted
     */
    private boolean checkBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true; // Pre-Android 12 doesn't need runtime BT permissions
        }
        boolean connectGranted = context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
        boolean scanGranted = context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;

        if (connectGranted && scanGranted) {
            return true;
        }

        // Try to request permissions if we can get an Activity
        requestBtPermissions();
        return false;
    }

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            // ATAK MapView.getMapView().getContext() returns the Activity
            Context ctx = context;
            if (ctx instanceof Activity) {
                ((Activity) ctx).requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                }, 1001);
            } else {
                // Try via MapView
                com.atakmap.android.maps.MapView mv =
                        com.atakmap.android.maps.MapView.getMapView();
                if (mv != null && mv.getContext() instanceof Activity) {
                    ((Activity) mv.getContext()).requestPermissions(
                            new String[]{
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.BLUETOOTH_SCAN
                            }, 1001);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not request BT permissions", e);
        }
    }

    /**
     * Pair (if needed) and connect to a supported radio over Classic SPP.
     * Unbonded devices trigger the system pairing dialog via {@link BluetoothDevice#createBond()}.
     */
    public void connect(BluetoothDevice device) {
        if (!com.uvpro.plugin.ui.RadioProfile.shows(
                com.uvpro.plugin.ui.RadioSurfaceCatalog.Role.UVPRO, null)) {
            Log.i(TAG, "Connect skipped: UV-PRO hidden by radio profile");
            return;
        }
        if (device == null) {
            notifyError("Invalid Bluetooth device");
            return;
        }
        String targetAddr = device.getAddress();
        if (isConnectedToAddress(targetAddr)) {
            Log.d(TAG, "Already connected to " + resolveName(device) + " — ignoring duplicate connect");
            cancelPendingAclConnect();
            return;
        }
        if (connecting.get() && isConnectingToAddress(targetAddr)) {
            Log.d(TAG, "Already connecting to " + resolveName(device) + " — ignoring duplicate connect");
            cancelPendingAclConnect();
            return;
        }
        if (connected.get()) {
            disconnect();
        }
        if (connecting.getAndSet(true)) {
            Log.w(TAG, "Already connecting, ignoring duplicate request");
            return;
        }

        lastDevice = device;
        shouldReconnect.set(true);
        reconnectAttempts = 0;

        MeshCoexistenceListener coexistence = meshCoexistenceListener;
        if (coexistence != null && coexistence.isMeshConnected()) {
            Log.i(TAG, "Radio connect while MeshCore is up — mesh restore will run if BLE drops");
            coexistence.onRadioConnectStartingWhileMeshUp();
        }

        int bondState = BluetoothDevice.BOND_NONE;
        try {
            bondState = device.getBondState();
        } catch (Exception ignored) {
        }
        if (bondState != BluetoothDevice.BOND_BONDED) {
            pendingBondDevice = device;
            registerBondReceiver();
            try {
                if (!device.createBond()) {
                    pendingBondDevice = null;
                    connecting.set(false);
                    notifyError("Could not start pairing with " + resolveName(device));
                } else {
                    Log.i(TAG, "Pairing started for " + resolveName(device));
                }
            } catch (Exception e) {
                pendingBondDevice = null;
                connecting.set(false);
                notifyError("Pairing failed: " + e.getMessage());
            }
            return;
        }

        connectBondedDevice(device);
    }

    private void connectBondedDevice(BluetoothDevice device) {
        new Thread(() -> {
            try {
                String devName = device.getName() != null ? device.getName() : device.getAddress();
                Log.i(TAG, "Connecting to " + devName + "...");

                // Cancel discovery to speed up connection
                if (btAdapter.isDiscovering()) {
                    btAdapter.cancelDiscovery();
                }

                // Strategy 0: Reuse probe socket if we have one already open from startScan()
                BluetoothSocket socket = null;
                BluetoothSocket probeSocket = openProbeSockets.remove(device.getAddress());
                if (probeSocket != null && probeSocket.isConnected()) {
                    Log.i(TAG, "Reusing probe socket for " + devName);
                    socket = probeSocket;
                    // Close any other leftover probe sockets we won't use
                    for (BluetoothSocket s : openProbeSockets.values()) {
                        try { s.close(); } catch (Exception ignored) {}
                    }
                    openProbeSockets.clear();
                }

                // Strategy 1: Standard SPP UUID
                if (socket == null) {
                    socket = tryConnect(device, "SPP UUID", () ->
                            device.createRfcommSocketToServiceRecord(SPP_UUID));
                }

                // Strategy 2: Reflection-based createRfcommSocket on channel 1
                if (socket == null) {
                    socket = tryConnect(device, "RFCOMM ch1", () -> {
                        Method m = device.getClass().getMethod(
                                "createRfcommSocket", int.class);
                        return (BluetoothSocket) m.invoke(device, 1);
                    });
                }

                // Strategy 3: Insecure SPP (no encryption handshake)
                if (socket == null) {
                    socket = tryConnect(device, "Insecure SPP", () ->
                            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID));
                }

                if (socket == null) {
                    notifyError("All connection methods failed for " + devName
                            + ". Try turning the radio off/on and re-pairing.");
                    connecting.set(false);
                    finishBootAutoConnectAttempt(false);
                    if (shouldReconnect.get()) {
                        scheduleReconnect();
                    }
                    return;
                }

                btSocket = socket;
                inputStream = btSocket.getInputStream();
                outputStream = btSocket.getOutputStream();
                connected.set(true);
                connecting.set(false);
                reconnectAttempts = 0;
                markIoActivity();
                cancelPendingAclConnect();

                // Reset decoder state from any previous partial frames
                kissDecoder.reset();

                Log.i(TAG, "Connected to " + devName);
                notifyConnected(device);

                // Start reading KISS frames
                startReadThread();

            } catch (Exception e) {
                Log.e(TAG, "Connection failed: " + e.getMessage());
                notifyError("Connection failed: " + e.getMessage());
                cleanup();
                connecting.set(false);
                finishBootAutoConnectAttempt(false);

                // Auto-reconnect
                if (shouldReconnect.get()) {
                    scheduleReconnect();
                }
            }
        }, "BT-Connect").start();
    }

    /**
     * Try a single socket connection strategy.
     * @return Connected socket, or null if failed.
     */
    private BluetoothSocket tryConnect(BluetoothDevice device, String label,
                                       SocketFactory factory) {
        try {
            Log.d(TAG, "Trying " + label + "...");
            BluetoothSocket socket = factory.create();
            socket.connect();
            Log.i(TAG, "Connected via " + label);
            return socket;
        } catch (Exception e) {
            Log.w(TAG, label + " failed: " + e.getMessage());
            return null;
        }
    }

    @FunctionalInterface
    private interface SocketFactory {
        BluetoothSocket create() throws Exception;
    }

    /**
     * Connect to the last known device.
     */
    public void connectToLastDevice() {
        BluetoothDevice device = resolveSavedRadioDevice();
        if (device != null) {
            connect(device);
        } else {
            startScan();
        }
    }

    /**
     * Disconnect from the radio.
     */
    public void disconnect() {
        shouldReconnect.set(false);
        connecting.set(false);
        connected.set(false);
        cancelPassiveReconnect();
        cleanup();
        notifyDisconnected("User disconnected");
    }

    /**
     * Cancel any in-progress connect attempt and stop auto-reconnect.
     * Useful when the user explicitly wants to scan/select another device.
     */
    public void cancelConnectionAttempts() {
        shouldReconnect.set(false);
        reconnectAttempts = 0;
        bootAutoConnectResolving.set(false);
        connecting.set(false);
        connected.set(false);
        pendingBondDevice = null;
        cancelPassiveReconnect();
        stopDiscoveryIfRunning();        cancelScanTimeout();
        cleanup();
        clearProbeSockets();
        notifyDisconnected("Connection attempt cancelled");
    }

    /**
     * Send raw data through the KISS TNC to the radio.
     * The data should be an AX.25 frame (without KISS framing — we add that).
     */
    /**
     * Prime TNC TX timing so short control frames (DISC) get enough key-up time on RF.
     */
    public boolean primeKissTxTiming() {
        if (!connected.get() || outputStream == null) {
            return false;
        }
        try {
            outputStream.write(kissEncoder.encodeCommand(
                    com.uvpro.plugin.kiss.KissConstants.CMD_TXDELAY, (byte) 30));
            outputStream.write(kissEncoder.encodeCommand(
                    com.uvpro.plugin.kiss.KissConstants.CMD_TXTAIL, (byte) 50));
            outputStream.flush();
            markIoActivity();
            Log.d(TAG, "KISS TX timing primed");
            return true;
        } catch (IOException e) {
            Log.w(TAG, "KISS TX timing prime failed: " + e.getMessage());
            return false;
        }
    }

    public boolean sendKissFrame(byte[] ax25Frame) {
        if (!com.uvpro.plugin.ui.RadioProfile.shows(
                com.uvpro.plugin.ui.RadioSurfaceCatalog.Role.UVPRO, null)) {
            Log.i(TAG, "TX blocked: UV-PRO hidden by radio profile");
            return false;
        }
        if (!connected.get() || outputStream == null) {
            Log.w(TAG, "Cannot send: not connected");
            return false;
        }
        if (radioSilenceEnabled.get()) {
            Log.w(TAG, "TX blocked by Radio Silence");
            return false;
        }

        try {
            byte[] kissFrame = kissEncoder.encode(ax25Frame);
            byte[] wireBytes = java.util.Arrays.copyOf(kissFrame, kissFrame.length);
            java.util.Arrays.fill(kissFrame, (byte) 0);
            outputStream.write(wireBytes);
            outputStream.flush();
            markIoActivity();
            packetRouter.notifyPacketTransmitted();
            Log.d(TAG, "Sent KISS frame: " + wireBytes.length + " bytes");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Send failed: " + e.getMessage());
            handleConnectionLost();
            return false;
        }
    }

    /**
     * Send HT Commander control commands over Bluetooth (not over-the-air KISS/AX.25).
     * Radio Silence does not block these — only {@link #sendKissFrame} RF traffic is inhibited.
     */
    public boolean sendRawBytes(byte[] data) {
        if (!connected.get() || outputStream == null) {
            Log.w(TAG, "Cannot send raw bytes: not connected");
            return false;
        }
        try {
            outputStream.write(data);
            outputStream.flush();
            markIoActivity();
            Log.d(TAG, "Sent raw bytes: " + data.length + " bytes");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Raw send failed: " + e.getMessage());
            handleConnectionLost();
            return false;
        }
    }

    /**
     * Radio Silence blocks outbound KISS/AX.25 (chat, ACKs, beacons, CoT) while receive and
     * Bluetooth radio-control commands ({@link #sendRawBytes}) remain active.
     */
    public void setRadioSilenceEnabled(boolean enabled) {
        radioSilenceEnabled.set(enabled);
        Log.i(TAG, "Radio Silence " + (enabled ? "enabled" : "disabled"));
        if (!enabled) {
            notifyRadioSilenceCleared();
        }
    }

    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> radioSilenceClearedListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Runs when Radio Silence is switched off, so held outbound traffic can be sent. */
    public void addRadioSilenceClearedListener(Runnable listener) {
        if (listener != null) {
            radioSilenceClearedListeners.addIfAbsent(listener);
        }
    }

    protected void notifyRadioSilenceCleared() {
        for (Runnable r : radioSilenceClearedListeners) {
            try {
                r.run();
            } catch (Exception e) {
                Log.w(TAG, "Radio Silence cleared listener failed: " + e.getMessage());
            }
        }
    }

    public boolean isRadioSilenceEnabled() {
        return radioSilenceEnabled.get();
    }

    /**
     * Background thread that continuously reads KISS frames from the radio.
     */
    private void startReadThread() {
        readThread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            Log.i(TAG, "Read thread started");

            while (connected.get()) {
                try {
                    int bytesRead = inputStream.read(buffer);
                    if (bytesRead > 0) {
                        markIoActivity();
                        byte[] data = new byte[bytesRead];
                        System.arraycopy(buffer, 0, data, 0, bytesRead);
                        java.util.Arrays.fill(buffer, (byte) 0);

                        boolean consumed = false;
                        for (RawDataListener listener : rawDataListeners) {
                            try {
                                if (listener.onRawBytes(data)) {
                                    consumed = true;
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "RawDataListener failed: " + e.getMessage());
                            }
                        }
                        if (consumed) {
                            continue;
                        }

                        // KissFrameDecoder accumulates bytes and emits
                        // complete AX.25 frames when FEND delimiters are found
                        byte[][] frames = kissDecoder.decode(data);
                        for (byte[] frame : frames) {
                            Log.d(TAG, "Received AX.25 frame: " + frame.length + " bytes");
                            packetRouter.routeIncoming(frame);
                        }
                    } else if (bytesRead == -1) {
                        // Stream ended
                        handleConnectionLost();
                        break;
                    }
                } catch (IOException e) {
                    if (connected.get()) {
                        Log.e(TAG, "Read error: " + e.getMessage());
                        handleConnectionLost();
                    }
                    break;
                }
            }

            Log.i(TAG, "Read thread stopped");
        }, "BT-Read");

        readThread.setDaemon(true);
        readThread.start();
    }

    private void handleConnectionLost() {
        connected.set(false);
        connecting.set(false);
        cleanup();
        notifyDisconnected("Connection lost");

        if (shouldReconnect.get()) {
            cancelPendingAclConnect();
            requestSdpRefreshForSavedRadio();
            Log.i(TAG, "Link lost — saved radio " + getSavedRadioTargetAddress()
                    + "; ACL/SDP watch + direct SPP recovery armed");
            resumeRadioAutoConnect(PASSIVE_RECONNECT_INITIAL_DELAY_MS);
        }
    }

    private void scheduleReconnect() {
        if (!isAutoReconnectEnabled()) {
            return;
        }
        if (isMeshAutoConnectBlocked()) {
            Log.d(TAG, "Reconnect deferred — MeshCore has priority");
            return;
        }
        if (lastDevice == null) return;
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached (" + MAX_RECONNECT_ATTEMPTS
                    + "). Falling back to passive reconnect.");
            notifyError("Reconnect failed after " + MAX_RECONNECT_ATTEMPTS
                    + " attempts. Will keep trying periodically.");
            armPassiveReconnect();
            return;
        }

        reconnectAttempts++;
        int delaySec = 5 * reconnectAttempts; // Back off: 5s, 10s, 15s...
        Log.i(TAG, "Scheduling reconnect #" + reconnectAttempts + " in " + delaySec + " seconds...");
        new Thread(() -> {
            try {
                Thread.sleep(delaySec * 1000L);
                if (shouldReconnect.get() && !connected.get() && !connecting.get()) {
                    Log.i(TAG, "Attempting reconnect #" + reconnectAttempts + "...");
                    connect(lastDevice);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "BT-Reconnect").start();
    }

    private boolean isReconnectBlocked() {
        ReconnectBlocker blocker = reconnectBlocker;
        if (blocker == null) {
            return false;
        }
        try {
            return blocker.shouldBlockReconnect();
        } catch (Exception e) {
            Log.w(TAG, "Reconnect blocker check failed", e);
            return false;
        }
    }

    private void cleanup() {
        runBeforeDisconnectHooks();
        try {
            if (inputStream != null) inputStream.close();
        } catch (IOException ignored) {}
        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException ignored) {}
        try {
            if (btSocket != null) btSocket.close();
        } catch (IOException ignored) {}

        inputStream = null;
        outputStream = null;
        btSocket = null;
    }

    private String resolveName(BluetoothDevice device) {
        if (device == null) {
            return "Radio";
        }
        try {
            String name = device.getName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (Exception ignored) {
        }
        String addr = device.getAddress();
        return addr != null ? addr : "Radio";
    }

    private void recordSeenAddress(String address) {
        if (address == null || address.isEmpty()) {
            return;
        }
        synchronized (seenScanAddresses) {
            seenScanAddresses.add(address);
        }
    }

    private boolean markSeenIfNew(String address) {
        if (address == null || address.isEmpty()) {
            return true;
        }
        synchronized (seenScanAddresses) {
            if (seenScanAddresses.contains(address)) {
                return false;
            }
            seenScanAddresses.add(address);
            return true;
        }
    }

    /** True when the address had enough credible discovery hits during the current scan. */
    public boolean wasSeenInLiveDiscovery(@Nullable String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        String normalized = address.trim().toUpperCase(java.util.Locale.US);
        synchronized (liveDiscoveryAddresses) {
            return liveDiscoveryAddresses.contains(normalized);
        }
    }

    private boolean isCredibleDiscoveryHit(@Nullable String address, short rssi) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        if (!isAcceptableDiscoveryRssi(rssi)) {
            Log.d(TAG, "Ignoring discovery " + address + " (rssi=" + rssi + ")");
            return false;
        }
        String normalized = address.trim().toUpperCase(java.util.Locale.US);
        int hits;
        synchronized (discoveryHitCount) {
            Integer prev = discoveryHitCount.get(normalized);
            hits = (prev != null ? prev : 0) + 1;
            discoveryHitCount.put(normalized, hits);
        }
        boolean strongSignal = rssi == Short.MIN_VALUE || rssi >= STRONG_DISCOVERY_RSSI_DBM;
        if (hits < MIN_DISCOVERY_HITS && !strongSignal) {
            Log.d(TAG, "Discovery tick " + hits + "/" + MIN_DISCOVERY_HITS
                    + " for " + normalized + " rssi=" + rssi);
            return false;
        }
        if (hits == 1 && strongSignal) {
            Log.d(TAG, "Nearby discovery (" + hits + " hit) for " + normalized + " rssi=" + rssi);
        }
        synchronized (liveDiscoveryAddresses) {
            liveDiscoveryAddresses.add(normalized);
        }
        Log.d(TAG, "Live discovery confirmed for " + normalized + " rssi=" + rssi);
        return true;
    }

    private static boolean isAcceptableDiscoveryRssi(short rssi) {
        if (rssi == Short.MIN_VALUE) {
            return true;
        }
        if (rssi >= 127 || rssi == 0) {
            return false;
        }
        return rssi >= MIN_DISCOVERY_RSSI_DBM;
    }

    /** User Disconnect sets {@link #shouldReconnect} false; link-loss keeps it true for ACL reconnect. */
    private boolean isAutoReconnectEnabled() {
        return shouldReconnect.get();
    }

    private void registerAclReceiver() {
        if (aclReceiverRegistered) {
            return;
        }
        aclReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null) {
                    return;
                }
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) {
                        onAclConnected(device);
                    }
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) {
                        onAclDisconnected(device);
                    }
                } else if (BluetoothDevice.ACTION_UUID.equals(action)) {
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) {
                        onRadioUuidAvailable(device);
                    }
                } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR);
                    if (state == BluetoothAdapter.STATE_ON) {
                        onBluetoothEnabled();
                    }
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            filter.addAction(BluetoothDevice.ACTION_UUID);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(aclReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(aclReceiver, filter);
            }
            aclReceiverRegistered = true;
            Log.d(TAG, "ACL/SDP reconnect listener registered");
        } catch (Exception e) {
            Log.w(TAG, "Could not register ACL receiver", e);
        }
    }

    private void unregisterAclReceiver() {
        if (!aclReceiverRegistered || aclReceiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(aclReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Could not unregister ACL receiver", e);
        } finally {
            aclReceiver = null;
            aclReceiverRegistered = false;
        }
    }

    private void cancelPendingAclConnect() {
        if (pendingAclConnectRunnable != null) {
            mainHandler.removeCallbacks(pendingAclConnectRunnable);
            pendingAclConnectRunnable = null;
        }
    }

    private void onAclDisconnected(BluetoothDevice device) {
        if (device == null || device.getAddress() == null) {
            return;
        }
        String addr = device.getAddress();
        if (!isSavedRadioTarget(addr)) {
            return;
        }
        Log.d(TAG, "ACL disconnected " + addr);
        requestSdpRefreshForSavedRadio();
    }

    private void onRadioUuidAvailable(BluetoothDevice device) {
        if (device == null || device.getAddress() == null) {
            return;
        }
        if (!isAutoReconnectEnabled() || !shouldReconnect.get()) {
            return;
        }
        if (connected.get() || connecting.get()) {
            return;
        }
        String addr = device.getAddress();
        if (!isSavedRadioTarget(addr)) {
            return;
        }
        Log.i(TAG, "UUID update for saved radio " + addr + " — scheduling plugin connect");
        scheduleAclConnect(device, "uuid-available");
    }

    private void requestSdpRefreshForSavedRadio() {
        BluetoothDevice device = resolveSavedRadioDevice();
        if (device == null) {
            return;
        }
        try {
            device.fetchUuidsWithSdp();
            Log.d(TAG, "SDP refresh requested for " + device.getAddress());
        } catch (Exception e) {
            Log.w(TAG, "SDP refresh failed", e);
        }
    }

    private void onAclConnected(BluetoothDevice device) {
        if (device == null || device.getAddress() == null) {
            return;
        }
        String addr = device.getAddress();
        if (isConnectedToAddress(addr) || isConnectingToAddress(addr)) {
            cancelPendingAclConnect();
            return;
        }
        String saved = getSavedRadioTargetAddress();
        Log.d(TAG, "ACL connected " + addr + " (saved target="
                + (saved != null ? saved : "none") + ")");
        if (!isSavedRadioTarget(addr)) {
            return;
        }
        Log.i(TAG, "ACL connected for saved radio " + addr + " — scheduling plugin connect");
        scheduleAclConnect(device, "acl-connected");
    }

    private void onBluetoothEnabled() {
        if (!isAutoReconnectEnabled() || !shouldReconnect.get()) {
            return;
        }
        if (connected.get() || connecting.get()) {
            return;
        }
        BluetoothDevice device = resolveSavedRadioDevice();
        if (device == null) {
            return;
        }
        try {
            String tgt = device.getAddress();
            Log.i(TAG, "Bluetooth enabled — scheduling connect to saved radio " + tgt);
            scheduleAclConnect(device, "bt-enabled");
        } catch (Exception e) {
            Log.w(TAG, "Could not schedule connect after BT enabled", e);
        }
    }

    private void scheduleAclConnect(BluetoothDevice device, String reason) {
        if (device == null) {
            return;
        }
        cancelPendingAclConnect();
        final BluetoothDevice target = device;
        pendingAclConnectRunnable = () -> {
            pendingAclConnectRunnable = null;
            maybeConnectToSavedRadio(target, reason);
        };
        mainHandler.postDelayed(pendingAclConnectRunnable, ACL_CONNECT_DEBOUNCE_MS);
    }

    /**
     * Connect to the saved radio when it re-appears at the BT stack (ACL) without polling.
     * Skipped after explicit user Disconnect or when auto-reconnect is disabled in settings.
     */
    private void maybeConnectToSavedRadio(BluetoothDevice device, String reason) {
        if (device == null) {
            return;
        }
        if (!isAutoReconnectEnabled()) {
            Log.d(TAG, "ACL connect skipped (" + reason + "): auto-reconnect disabled");
            return;
        }
        if (!shouldReconnect.get()) {
            Log.d(TAG, "ACL connect skipped (" + reason + "): user stopped reconnect");
            return;
        }
        if (isMeshAutoConnectBlocked()) {
            Log.d(TAG, "Radio connect skipped (" + reason + "): MeshCore has priority");
            return;
        }
        String addr = device.getAddress();
        if (isConnectedToAddress(addr) || isConnectingToAddress(addr)) {
            Log.d(TAG, "ACL connect skipped (" + reason + "): already linked to " + addr);
            return;
        }
        if (!isSavedRadioTarget(addr)) {
            return;
        }
        int bondState = BluetoothDevice.BOND_NONE;
        try {
            bondState = device.getBondState();
        } catch (Exception ignored) {
        }
        if (bondState != BluetoothDevice.BOND_BONDED) {
            Log.d(TAG, "ACL connect skipped (" + reason + "): not bonded");
            return;
        }
        reconnectAttempts = 0;
        Log.i(TAG, "ACL trigger (" + reason + "): connecting to " + resolveName(device));
        connect(device);
    }

    private boolean isConnectedToAddress(String address) {
        if (!connected.get() || address == null || lastDevice == null) {
            return false;
        }
        String linked = lastDevice.getAddress();
        return linked != null && linked.equalsIgnoreCase(address);
    }

    private boolean isConnectingToAddress(String address) {
        if (!connecting.get() || address == null) {
            return false;
        }
        String tgt = getSavedRadioTargetAddress();
        return tgt != null && tgt.equalsIgnoreCase(address);
    }

    @Nullable
    private BluetoothDevice resolveSavedRadioDevice() {
        if (lastDevice != null) {
            return lastDevice;
        }
        return loadRememberedRadioDevice();
    }

    private String getSavedRadioTargetAddress() {
        BluetoothDevice device = resolveSavedRadioDevice();
        if (device == null) {
            return null;
        }
        try {
            return UvProBtDeviceMatcher.normalizeMacAddress(device.getAddress());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSavedRadioTarget(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        String tgt = getSavedRadioTargetAddress();
        return tgt != null && !tgt.isEmpty() && tgt.equalsIgnoreCase(address);
    }

    private void cancelPassiveReconnect() {
        passiveReconnectArmed.set(false);
        passiveReconnectAttempt = 0;
        if (passiveReconnectRunnable != null) {
            mainHandler.removeCallbacks(passiveReconnectRunnable);
            passiveReconnectRunnable = null;
        }
    }

    private boolean shouldRunPassiveReconnect() {
        if (!passiveReconnectArmed.get() || !isAutoReconnectEnabled() || !shouldReconnect.get()) {
            return false;
        }
        if (isMeshAutoConnectBlocked()) {
            return false;
        }
        if (connected.get() || connecting.get()) {
            return false;
        }
        String tgt = getSavedRadioTargetAddress();
        return tgt != null && !tgt.isEmpty();
    }

    private void scheduleNextPassiveReconnect(long delayMs) {
        if (!shouldRunPassiveReconnect()) {
            cancelPassiveReconnect();
            return;
        }
        if (passiveReconnectRunnable != null) {
            mainHandler.removeCallbacks(passiveReconnectRunnable);
        }
        passiveReconnectRunnable = () -> {
            passiveReconnectRunnable = null;
            if (!shouldRunPassiveReconnect()) {
                cancelPassiveReconnect();
                return;
            }
            BluetoothDevice device = resolveSavedRadioDevice();
            if (device == null || btAdapter == null) {
                scheduleNextPassiveReconnect(nextPassiveReconnectDelay());
                return;
            }
            try {
                String tgt = device.getAddress();
                Log.i(TAG, "Direct SPP recovery attempt for saved radio " + tgt);
                maybeConnectToSavedRadio(device, "passive-watch");
            } catch (Exception e) {
                Log.w(TAG, "Passive reconnect attempt failed", e);
            }
            scheduleNextPassiveReconnect(nextPassiveReconnectDelay());
        };
        mainHandler.postDelayed(passiveReconnectRunnable, delayMs);
    }

    private long nextPassiveReconnectDelay() {
        int idx = passiveReconnectAttempt++;
        if (idx < PASSIVE_RECONNECT_BACKOFF_MS.length) {
            return PASSIVE_RECONNECT_BACKOFF_MS[idx];
        }
        return PASSIVE_RECONNECT_INTERVAL_MS;
    }

    private void registerDiscoveryReceiverIfNeeded() {
        if (discoveryReceiverRegistered) {
            return;
        }
        discoveryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    String advertName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
                    short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                    if (device == null
                            || !UvProBtDeviceMatcher.isDiscoveryCandidate(device, advertName)) {
                        return;
                    }
                    String address = device.getAddress();
                    if (!isCredibleDiscoveryHit(address, rssi)) {
                        return;
                    }
                    UvProBtDeviceMatcher.cachePickerModelLabel(address, advertName);
                    if (!markSeenIfNew(address)) {
                        return;
                    }
                    notifyDeviceFound(device);
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    notifyScanCompleteOnce();
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            context.registerReceiver(discoveryReceiver, filter);
            discoveryReceiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "Could not register discovery receiver", e);
            notifyScanCompleteOnce();
        }
    }

    private void registerBondReceiver() {
        if (bondReceiverRegistered) {
            return;
        }
        bondStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null
                        || !BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                    return;
                }
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null || pendingBondDevice == null) {
                    return;
                }
                String addr = device.getAddress();
                String pendingAddr = pendingBondDevice.getAddress();
                if (addr == null || pendingAddr == null || !addr.equalsIgnoreCase(pendingAddr)) {
                    return;
                }
                int bondState = intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                int prevBondState = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    pendingBondDevice = null;
                    Log.i(TAG, "Pairing complete for " + resolveName(device) + " — connecting");
                    connecting.set(false);
                    connectBondedDevice(device);
                } else if (bondState == BluetoothDevice.BOND_NONE
                        && prevBondState == BluetoothDevice.BOND_BONDING) {
                    pendingBondDevice = null;
                    connecting.set(false);
                    notifyError("Pairing failed or cancelled for " + resolveName(device));
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            context.registerReceiver(bondStateReceiver, filter);
            bondReceiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "Could not register bond receiver", e);
        }
    }

    private void stopDiscoveryIfRunning() {
        if (btAdapter == null) {
            return;
        }
        try {
            if (btAdapter.isDiscovering()) {
                btAdapter.cancelDiscovery();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not cancel discovery", e);
        }
    }

    private void scheduleScanTimeout() {
        if (scanTimeoutRunnable != null) {
            mainHandler.removeCallbacks(scanTimeoutRunnable);
            scanTimeoutRunnable = null;
        }
        if (!scanTimeoutScheduled.compareAndSet(false, true)) {
            return;
        }
        scanTimeoutRunnable = () -> {
            scanTimeoutScheduled.set(false);
            scanTimeoutRunnable = null;
            if (scanCompleteNotified.get()) {
                return;
            }
            Log.i(TAG, "Scan timeout reached; finishing discovery");
            stopDiscoveryIfRunning();
            notifyScanCompleteOnce();
        };
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);
    }

    private void cancelScanTimeout() {
        scanTimeoutScheduled.set(false);
        if (scanTimeoutRunnable != null) {
            mainHandler.removeCallbacks(scanTimeoutRunnable);
            scanTimeoutRunnable = null;
        }
    }

    private void notifyScanCompleteOnce() {
        cancelScanTimeout();
        if (scanCompleteNotified.compareAndSet(false, true)) {
            notifyScanComplete();
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean isConnecting() {
        return connecting.get();
    }

    public long getLastIoActivityMs() {
        return lastIoActivityMs.get();
    }

    public boolean hasRecentIo(long withinMs) {
        long last = lastIoActivityMs.get();
        if (last <= 0L) {
            return false;
        }
        long window = Math.max(0L, withinMs);
        return (System.currentTimeMillis() - last) <= window;
    }

    public String getConnectedDeviceName() {
        if (!connected.get()) return null;
        if (lastDevice != null) {
            String name = lastDevice.getName();
            return name != null ? name : lastDevice.getAddress();
        }
        return "Radio";
    }

    // --- Listener management ---

    public void addListener(ConnectionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ConnectionListener listener) {
        listeners.remove(listener);
    }

    public void addRawDataListener(RawDataListener listener) {
        rawDataListeners.add(listener);
    }

    public void removeRawDataListener(RawDataListener listener) {
        rawDataListeners.remove(listener);
    }

    public void addBeforeDisconnectHook(Runnable hook) {
        if (hook != null) {
            beforeDisconnectHooks.add(hook);
        }
    }

    public void removeBeforeDisconnectHook(Runnable hook) {
        beforeDisconnectHooks.remove(hook);
    }

    private void runBeforeDisconnectHooks() {
        for (Runnable hook : beforeDisconnectHooks) {
            try {
                hook.run();
            } catch (Exception e) {
                Log.w(TAG, "beforeDisconnect hook failed: " + e.getMessage());
            }
        }
    }

    protected void notifyConnected(BluetoothDevice device) {
        cancelPassiveReconnect();
        if (bootAutoConnectActive.get()) {
            endBootAutoConnectWindow(true);
        } else {
            finishBootAutoConnectAttempt(true);
        }
        if (shouldPersistUvProRadioOnConnect()) {
            persistRememberedRadioMac(device);
        }
        for (ConnectionListener l : listeners) l.onConnected(device);
    }

    protected void notifyDisconnected(String reason) {
        for (ConnectionListener l : listeners) l.onDisconnected(reason);
    }

    protected void notifyError(String error) {
        for (ConnectionListener l : listeners) l.onError(error);
    }

    protected void notifyDeviceFound(BluetoothDevice device) {
        for (ConnectionListener l : listeners) l.onDeviceFound(device);
    }

    protected void notifyScanComplete() {
        for (ConnectionListener l : listeners) l.onScanComplete();
    }

    private void markIoActivity() {
        lastIoActivityMs.set(System.currentTimeMillis());
    }
}
