package com.uvpro.plugin.bluetooth;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Base64;

import androidx.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;

import com.uvpro.plugin.protocol.PacketRouter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MeshCore BLE companion transport used by UV-PRO-MESH plugin mode.
 */
public class MeshBtConnectionManager extends BtConnectionManager {

    private static final String TAG = "UVPro.MeshBLE";
    private static final long MESH_SCAN_TIMEOUT_MS = 15_000L;

    private static final UUID UUID_UART_SERVICE =
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UUID_UART_RX =
            UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UUID_UART_TX =
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UUID_CCC =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private static final byte RESP_CODE_OK = 0x00;
    private static final byte RESP_CODE_ERR = 0x01;
    private static final int ERR_CODE_NOT_FOUND = 2;

    private static final byte CMD_APP_START = 0x01;
    private static final byte CMD_SEND_SELF_ADVERT = 0x07;
    private static final byte CMD_SET_ADVERT_NAME = 0x08;
    private static final byte CMD_SET_ADVERT_LATLON = 0x0E;
    private static final byte CMD_SET_RADIO_PARAMS = 0x0B;
    private static final byte CMD_SET_RADIO_TX_POWER = 0x0C;
    private static final byte CMD_SEND_TXT_MSG = 0x02;
    private static final byte CMD_SEND_LOGIN = 0x1A;
    private static final byte TXT_TYPE_PLAIN = 0x00;
    public static final byte TXT_TYPE_CLI_DATA = 0x01;
    public static final byte TXT_TYPE_SIGNED_PLAIN = 0x02;
    private static final byte CMD_SEND_CHANNEL_MSG = 0x03;
    private static final byte CMD_GET_NEXT_MSG = 0x0A;
    private static final byte CMD_DEVICE_QUERY = 0x16;
    private static final byte CMD_DEVICE_QUERY_ARG = 0x03;
    private static final byte CMD_GET_CONTACTS = 0x04;
    private static final byte CMD_ADD_UPDATE_CONTACT = 0x09;
    private static final byte CMD_REMOVE_CONTACT = 0x0F;
    private static final byte CMD_GET_CONTACT_BY_KEY = 0x1E;
    private static final byte CMD_GET_CHANNEL = 0x1F;
    private static final byte CMD_SET_CHANNEL = 0x20;
    private static final byte CMD_GET_GPS_STATE = 0x28;
    private static final byte CMD_SET_OTHER_PARAMS = 0x26;
    private static final byte CMD_SET_SETTING_TEXT = 0x29;
    private static final byte CMD_SEND_CHANNEL_DATA = 0x3E;
    private static final byte CMD_SEND_CONTROL_DATA = 0x37;
    private static final byte CMD_GET_BATTERY = 0x14;
    private static final byte CMD_GET_STATS = 0x38;
    private static final byte STATS_TYPE_CORE = 0x00;

    private static final byte RESP_CHANNEL_MSG = 0x08;
    private static final byte RESP_BATTERY = 0x0C;
    private static final byte RESP_CODE_STATS = 0x18;
    private static final byte RESP_CONTACT_MSG = 0x07;
    private static final byte RESP_SELF_INFO = 0x05;
    private static final byte RESP_DEVICE_INFO = 0x0D;
    private static final byte RESP_CHANNEL_MSG_V3 = 0x11;
    private static final byte RESP_CONTACT_MSG_V3 = 0x10;
    private static final byte RESP_CHANNEL_INFO = 0x12;
    private static final byte RESP_SETTING_TEXT = 0x15;
    private static final byte RESP_CHANNEL_DATA_RECV = 0x1B;
    private static final byte RESP_NO_MORE_MSGS = 0x0A;
    private static final byte PUSH_MESSAGES_WAITING = (byte) 0x83;
    private static final byte PUSH_CODE_SEND_CONFIRMED = (byte) 0x82;
    private static final byte PUSH_CODE_LOG_RX_DATA = (byte) 0x88;
    private static final byte PUSH_CODE_ADVERT = (byte) 0x80;
    private static final byte PUSH_CODE_CONTROL_DATA = (byte) 0x8E;
    private static final byte PUSH_CODE_NEW_ADVERT = (byte) 0x8A;
    private static final byte CTL_TYPE_NODE_DISCOVER_REQ = (byte) 0x80;
    private static final byte CTL_TYPE_NODE_DISCOVER_RESP = (byte) 0x90;
    private static final byte PUSH_CODE_LOGIN_SUCCESS = (byte) 0x85;
    private static final byte PUSH_CODE_LOGIN_FAIL = (byte) 0x86;
    private static final byte RESP_CODE_CONTACTS_START = 0x02;
    private static final byte RESP_CODE_CONTACT = 0x03;
    private static final byte RESP_CODE_END_OF_CONTACTS = 0x04;
    public static final int ADV_TYPE_REPEATER = 0x02;
    public static final int ADV_TYPE_ROOM = 0x03;
    public static final int ADV_TYPE_CHAT = 0x01;
    private static final int CONTACT_PUB_KEY_BYTES = 32;
    private static final int CONTACT_PATH_BYTES = 64;
    private static final int CONTACT_NAME_BYTES = 32;
    public static final int CONTACT_FLAG_FAVORITE = 0x01;
    private static final long DEVICE_CONTACTS_FETCH_TIMEOUT_MS = 30_000L;

    private static final int MAX_MESH_MESSAGE_LEN = 130;
    /**
     * Channel-data bytes per LoRa packet. Firmware accepts 165, but a 165-byte
     * chunk never arrived. 100-byte packets did, and five of those sent at once
     * dropped a piece. 127 keeps a 308-byte point to three single packets.
     */
    private static final int MESH_GROUP_DATA_MAX = 127;
    /** One AX.25 frame in a single channel-data payload. */
    private static final int MESH_AX25_SINGLE = 0xB1;
    /** AX.25 slice when the frame is larger than one LoRa packet. */
    private static final int MESH_AX25_PART = 0xB2;
    /** marker + uint16 id + seq + total */
    private static final int MESH_AX25_PART_HEADER = 5;
    private static final int ADVERT_LOC_NONE = 0;
    private static final int ADVERT_LOC_SHARE = 1;
    private static final String ENV_PREFIX = "UVAX1|";
    // Use MeshCore companion app-id for firmware compatibility with settings commands.
    private static final String COMPANION_APP_ID = "meshcore-flutter";
    private static final int ATAK_CHANNEL_INDEX = 7;
    private static final String ATAK_CHANNEL_NAME = "ATAK_DATA";
    private static final byte[] ATAK_CHANNEL_SECRET = new byte[]{
            (byte) 0xA3, (byte) 0x74, (byte) 0x1E, (byte) 0x6A,
            (byte) 0x52, (byte) 0x9C, (byte) 0xCF, (byte) 0x31,
            (byte) 0xD0, (byte) 0x4B, (byte) 0x89, (byte) 0xFE,
            (byte) 0x17, (byte) 0x63, (byte) 0xB8, (byte) 0x2D
    };
    private static final int ATAK_DATA_TYPE_AX25 = 0xFF01;
    private static final int ATAK_DATA_TYPE_RAW = 0xFF02;
    /** Companion contact has no known route yet (firmware {@code OUT_PATH_UNKNOWN}). */
    public static final int OUT_PATH_UNKNOWN = 0xFF;
    private static final int OUT_PATH_FLOOD = 0xFF;

    private final Context context;
    private final PacketRouter packetRouter;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean radioSilenceEnabled = new AtomicBoolean(false);
    private final AtomicBoolean scanCompleteNotified = new AtomicBoolean(false);
    private final Set<String> seenScanAddresses = new HashSet<>();
    private final AtomicLong lastIoActivityMs = new AtomicLong(0L);
    private final AtomicInteger outboundMsgId = new AtomicInteger(1);

    /** True while the Scan & Connect picker is open. */
    private final AtomicBoolean scanPickerSessionActive = new AtomicBoolean(false);
    /** Incremented when Scan & Connect starts; stale picker probe callbacks are ignored. */
    private final AtomicInteger scanSessionGeneration = new AtomicInteger(0);
    /** True only while connecting from an explicit picker row tap. */
    private final AtomicBoolean userInitiatedConnect = new AtomicBoolean(false);
    /** Set when user taps Mesh Disconnect — blocks reconnect until Scan and Connect. */
    private final AtomicBoolean meshManualDisconnect = new AtomicBoolean(false);
    /** Invalidates in-flight boot/background auto-connect probes. */
    private final AtomicInteger autoConnectGeneration = new AtomicInteger(0);
    /** One boot auto-connect attempt per process unless user opens Scan and Connect. */
    private final AtomicBoolean savedTargetAutoConnectAttempted = new AtomicBoolean(false);
    private Runnable autoConnectTimeoutRunnable = null;
    /** Addresses seen during the current live BLE scan (used for availability dot logic). */
    private final Set<String> liveScanAddresses = new HashSet<>();

    private static final long MESH_ACL_DEBOUNCE_MS = 1500L;
    private static final long MESH_PASSIVE_INITIAL_DELAY_MS = 3000L;
    private static final long[] MESH_PASSIVE_BACKOFF_MS = {
            3000L, 8000L, 15000L, 30000L, 60000L
    };
    private static final long MESH_PASSIVE_INTERVAL_MS = 60_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver meshAvailabilityReceiver;
    private boolean meshAvailabilityReceiverRegistered = false;
    private final AtomicBoolean passiveMeshWatchArmed = new AtomicBoolean(false);
    private final AtomicInteger passiveMeshProbeGeneration = new AtomicInteger(0);
    private int passiveMeshWatchAttempt = 0;
    private Runnable passiveMeshWatchRunnable;
    private Runnable pendingMeshAclRunnable;

    /** Availability result constants. */
    public static final int AVAIL_AVAILABLE = BleMeshAvailabilityProber.AVAILABLE;
    public static final int AVAIL_BUSY = BleMeshAvailabilityProber.BUSY;

    /** Callback for availability probe results. */
    public interface AvailabilityCallback {
        void onResult(int availability);
    }

    /** Optional hook so startup boot timers can be cancelled when Scan and Connect opens. */
    public interface BootScheduleListener {
        void onUserScanStarting();
    }

    /** UI hook for startup mesh auto-connect (7s fallback or post-UV-PRO). */
    public interface MeshBootAutoConnectListener {
        void onMeshBootAutoConnectStarted(String reason);
        void onMeshBootAutoConnectFinished(boolean connected);
    }

    private volatile BootScheduleListener bootScheduleListener;
    private volatile MeshBootAutoConnectListener meshBootAutoConnectListener;
    private final AtomicBoolean meshBootAutoConnectResolving = new AtomicBoolean(false);

    private final BleMeshAvailabilityProber availabilityProber = new BleMeshAvailabilityProber();

    private final CopyOnWriteArrayList<RawDataListener> rawDataListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> beforeDisconnectHooks =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MeshStateListener> meshStateListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RepeaterAdvertListener> repeaterAdvertListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MeshAdvertListener> meshAdvertListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MeshDeviceContactUpdateListener> deviceContactUpdateListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MeshChannelListener> meshChannelListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MeshNativeDmListener> meshNativeDmListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MeshRoomLoginListener> meshRoomLoginListeners =
            new CopyOnWriteArrayList<>();
    private volatile DeviceContactsListener pendingDeviceContactsListener;
    private final java.util.ArrayList<MeshDeviceContact> pendingDeviceContactsList =
            new java.util.ArrayList<>();
    private volatile boolean deviceContactsFetchActive = false;
    private final Runnable deviceContactsFetchTimeoutRunnable = this::onDeviceContactsFetchTimeout;
    private final Map<String, Long> repeaterToastDedupByPubKeyTs = new ConcurrentHashMap<>();
    private final Map<String, Long> nodeToastDedupByPubKeyTs = new ConcurrentHashMap<>();
    private final Map<String, Long> contactQueryThrottleMsByPubKey = new ConcurrentHashMap<>();
    private volatile int pendingNodeDiscoverTag = 0;
    private volatile long pendingNodeDiscoverUntilMs = 0L;
    private volatile long roomPostSyncUntilMs = 0L;
    private static final long ROOM_CONTACT_REMOVE_SETTLE_MS = 1500L;
    private static final long ROOM_CONTACT_ADD_SETTLE_MS = 1200L;
    private static final long ROOM_POST_SYNC_BASE_MS = 300_000L;
    private static final long ROOM_POST_SYNC_EXTEND_MS = 60_000L;
    @Nullable
    private Runnable pendingRoomContactPrepareStep;
    private volatile boolean roomContactPrepareInProgress = false;
    private final Map<Integer, String> meshChannelNamesByIndex = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, byte[]> channelSecretsByIndex =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final Map<Integer, ChunkAccumulator> chunkBuffers = new ConcurrentHashMap<>();
    private final Map<Integer, ChunkAccumulator> binaryChunkBuffers = new ConcurrentHashMap<>();
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();
    private final ArrayDeque<PendingChannelText> pendingChannelTextSends = new ArrayDeque<>();
    private boolean writeInFlight = false;

    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner bleScanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxCharacteristic;
    private BluetoothGattCharacteristic txCharacteristic;
    private BluetoothDevice lastDevice;
    private BluetoothDevice pendingBondDevice;
    private volatile Boolean meshGpsEnabled = null;
    private volatile Boolean sendPositionWithAdvertEnabled = null;
    private volatile MeshNodeSettings latestNodeSettings = null;
    private volatile int latestBatteryMv = -1;
    private volatile int latestBatteryPercent = -1;
    private volatile MeshLocationFix latestSelfLocation = null;
    private volatile String selfPubKeyHex = "";
    private volatile int cachedManualAddContacts = 0;
    private volatile int cachedTelemetryModes = 0;
    private volatile int cachedMultiAcks = 0;
    private final HandlerThread ioThread = new HandlerThread("UVPro-MeshBLE-IO");
    private Handler ioHandler;
    private final Runnable periodicMessagePoll = new Runnable() {
        @Override
        public void run() {
            if (!connected.get()) {
                return;
            }
            enqueueCommand(buildGetNextMessageCommand());
            ioHandler.postDelayed(this, 2500L);
        }
    };

    public void setBootScheduleListener(BootScheduleListener listener) {
        bootScheduleListener = listener;
    }

    public void setMeshBootAutoConnectListener(MeshBootAutoConnectListener listener) {
        meshBootAutoConnectListener = listener;
    }

    public boolean isMeshBootAutoConnectResolving() {
        return meshBootAutoConnectResolving.get();
    }

    public MeshBtConnectionManager(Context context, PacketRouter packetRouter) {
        super(context, packetRouter);
        detachClassicBtAutoConnect();
        Context atakContext = com.atakmap.android.maps.MapView.getMapView() != null
                ? com.atakmap.android.maps.MapView.getMapView().getContext()
                : context;
        this.context = atakContext;
        this.packetRouter = packetRouter;
        this.btAdapter = BluetoothAdapter.getDefaultAdapter();
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());
        registerBondReceiver();
        registerMeshAvailabilityReceiver();
    }

    public interface MeshStateListener {
        void onMeshGpsStateChanged(boolean enabled);
        void onSendPositionWithAdvertChanged(boolean enabled);
        void onMeshNodeSettingsUpdated(MeshNodeSettings settings);
        void onMeshSelfLocationUpdated(MeshLocationFix fix);
        void onMeshBatteryUpdated(int batteryPercent, int batteryMv);
    }

    public static final class MeshNodeSettings {
        public final String nodeName;
        public final double frequencyMHz;
        public final double bandwidthKHz;
        public final int spreadingFactor;
        public final int codingRate;
        public final int txPowerDbm;
        public final int maxTxPowerDbm;
        public final long receivedAtMs;

        public MeshNodeSettings(String nodeName,
                                double frequencyMHz,
                                double bandwidthKHz,
                                int spreadingFactor,
                                int codingRate,
                                int txPowerDbm,
                                int maxTxPowerDbm,
                                long receivedAtMs) {
            this.nodeName = nodeName;
            this.frequencyMHz = frequencyMHz;
            this.bandwidthKHz = bandwidthKHz;
            this.spreadingFactor = spreadingFactor;
            this.codingRate = codingRate;
            this.txPowerDbm = txPowerDbm;
            this.maxTxPowerDbm = maxTxPowerDbm;
            this.receivedAtMs = receivedAtMs;
        }
    }

    public static final class MeshLocationFix {
        public final double latitude;
        public final double longitude;
        public final long receivedAtMs;
        public final String nodeName;

        public MeshLocationFix(double latitude, double longitude, long receivedAtMs, String nodeName) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.receivedAtMs = receivedAtMs;
            this.nodeName = nodeName;
        }

        public boolean isValid() {
            if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
                return false;
            }
            if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
                return false;
            }
            return !(Math.abs(latitude) < 0.000001 && Math.abs(longitude) < 0.000001);
        }
    }

    public interface RepeaterAdvertListener {
        void onRepeaterAdvert(RepeaterAdvert advert);
    }

    public interface MeshAdvertListener {
        void onMeshAdvert(MeshAdvert advert);
    }

    /** Single contact record pushed after advert refresh or {@code CMD_GET_CONTACT_BY_KEY}. */
    public interface MeshDeviceContactUpdateListener {
        void onDeviceContactUpdated(MeshDeviceContact contact);
    }

    public static final class MeshAdvert {
        public final int advertType;
        public final String pubKeyHex;
        public final String name;
        public final long advertTimestampSec;
        public final double latitude;
        public final double longitude;
        public final boolean hasPosition;

        public MeshAdvert(int advertType,
                          String pubKeyHex,
                          String name,
                          long advertTimestampSec,
                          double latitude,
                          double longitude,
                          boolean hasPosition) {
            this.advertType = advertType;
            this.pubKeyHex = pubKeyHex;
            this.name = name;
            this.advertTimestampSec = advertTimestampSec;
            this.latitude = latitude;
            this.longitude = longitude;
            this.hasPosition = hasPosition;
        }

        public boolean hasValidPosition() {
            return hasPosition
                    && !Double.isNaN(latitude)
                    && !Double.isNaN(longitude)
                    && latitude >= -90.0 && latitude <= 90.0
                    && longitude >= -180.0 && longitude <= 180.0
                    && !(Math.abs(latitude) < 0.000001 && Math.abs(longitude) < 0.000001);
        }

        public boolean isRepeater() {
            return advertType == ADV_TYPE_REPEATER;
        }
    }

    public static final class RepeaterAdvert {
        public final String pubKeyHex;
        public final String name;
        public final long advertTimestampSec;
        public final double latitude;
        public final double longitude;
        public final boolean hasPosition;

        public RepeaterAdvert(String pubKeyHex,
                              String name,
                              long advertTimestampSec,
                              double latitude,
                              double longitude,
                              boolean hasPosition) {
            this.pubKeyHex = pubKeyHex;
            this.name = name;
            this.advertTimestampSec = advertTimestampSec;
            this.latitude = latitude;
            this.longitude = longitude;
            this.hasPosition = hasPosition;
        }

        public boolean hasValidPosition() {
            return hasPosition
                    && !Double.isNaN(latitude)
                    && !Double.isNaN(longitude)
                    && latitude >= -90.0 && latitude <= 90.0
                    && longitude >= -180.0 && longitude <= 180.0
                    && !(Math.abs(latitude) < 0.000001 && Math.abs(longitude) < 0.000001);
        }
    }

    public interface MeshChannelListener {
        void onChannelInfo(MeshChannelInfo info);
        void onChannelMessage(MeshChannelMessage message);
    }

    public interface DeviceContactsListener {
        void onDeviceContactsReady(java.util.List<MeshDeviceContact> contacts);
        void onDeviceContactsFailed(String reason);
    }

    public static final class MeshContactInboundMessage {
        public final String senderPubKeyPrefixHex;
        public final String text;
        public final int txtType;
        /** Room post timestamp from server (seconds); 0 if unknown. */
        public final int senderTimestampSec;
        @Nullable
        public final String authorPubKeyPrefixHex;

        public MeshContactInboundMessage(String senderPubKeyPrefixHex, String text,
                                         int txtType, int senderTimestampSec,
                                         @Nullable String authorPubKeyPrefixHex) {
            this.senderPubKeyPrefixHex = senderPubKeyPrefixHex;
            this.text = text;
            this.txtType = txtType;
            this.senderTimestampSec = senderTimestampSec;
            this.authorPubKeyPrefixHex = authorPubKeyPrefixHex;
        }
    }

    public interface MeshNativeDmListener {
        void onNativeContactMessage(MeshContactInboundMessage message);
    }

    public interface MeshRoomLoginListener {
        void onRoomLoginSuccess(String pubKeyPrefixHex12, int permissions);
        void onRoomLoginFail(String pubKeyPrefixHex12);
        /** Companion rejected a command (e.g. login target not in contact table). */
        default void onCompanionCommandError(int errCode) {
        }
    }

    public static final class MeshDeviceContact {
        public final String pubKeyHex;
        public final int type;
        public final int flags;
        public final int outPathLen;
        public final String name;
        public final int lastAdvertTimestamp;
        public final double gpsLat;
        public final double gpsLon;
        public final int lastMod;

        public MeshDeviceContact(String pubKeyHex, int type, int flags, int outPathLen,
                                 String name, int lastAdvertTimestamp,
                                 double gpsLat, double gpsLon, int lastMod) {
            this.pubKeyHex = pubKeyHex;
            this.type = type;
            this.flags = flags;
            this.outPathLen = outPathLen;
            this.name = name;
            this.lastAdvertTimestamp = lastAdvertTimestamp;
            this.gpsLat = gpsLat;
            this.gpsLon = gpsLon;
            this.lastMod = lastMod;
        }

        public boolean isFavorite() {
            return (flags & CONTACT_FLAG_FAVORITE) != 0;
        }
    }

    public static final class MeshChannelInfo {
        public final int index;
        public final String name;

        public MeshChannelInfo(int index, String name) {
            this.index = index;
            this.name = name;
        }
    }

    public static final class MeshChannelMessage {
        public final int channelIndex;
        public final String text;
        public final long receivedAtMs;
        public final boolean outbound;
        public final String statusText;
        public final Integer snrQuarterDb;
        public final Integer pathLen;
        public final Integer senderTimestampSec;

        public MeshChannelMessage(int channelIndex, String text, long receivedAtMs,
                                  boolean outbound, String statusText,
                                  Integer snrQuarterDb, Integer pathLen,
                                  Integer senderTimestampSec) {
            this.channelIndex = channelIndex;
            this.text = text;
            this.receivedAtMs = receivedAtMs;
            this.outbound = outbound;
            this.statusText = statusText;
            this.snrQuarterDb = snrQuarterDb;
            this.pathLen = pathLen;
            this.senderTimestampSec = senderTimestampSec;
        }
    }

    private static final class PendingChannelText {
        final int channelIndex;
        final String text;
        final long queuedAtMs;

        PendingChannelText(int channelIndex, String text, long queuedAtMs) {
            this.channelIndex = channelIndex;
            this.text = text;
            this.queuedAtMs = queuedAtMs;
        }
    }

    public void startScan() {
        if (!com.uvpro.plugin.ui.RadioProfile.shows(
                com.uvpro.plugin.ui.RadioSurfaceCatalog.Role.MESH, null)) {
            Log.i(TAG, "Scan skipped: MeshCore hidden by radio profile");
            return;
        }
        prepareForUserScan();
        if (btAdapter == null) {
            finishScanWithError("Bluetooth not available on this device");
            return;
        }
        if (!btAdapter.isEnabled()) {
            finishScanWithError("Bluetooth is disabled. Please enable it.");
            return;
        }
        if (!checkScanPermissions()) {
            finishScanWithError("Bluetooth permission denied. Grant in Settings > Apps.");
            return;
        }

        stopScanInternal();
        scanCompleteNotified.set(false);
        synchronized (seenScanAddresses) {
            seenScanAddresses.clear();
        }
        synchronized (liveScanAddresses) {
            liveScanAddresses.clear();
        }
        emitSavedTargetCandidate();
        emitBondedMeshCandidates();

        bleScanner = btAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            finishScanWithError("BLE scanner not available");
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                if (device == null) {
                    return;
                }
                if (!MeshBleDeviceMatcher.isMeshDevice(context, result, device)) {
                    return;
                }
                String address = device.getAddress();
                String name = MeshBleDeviceMatcher.resolveName(result, device);
                boolean isNew = false;
                if (address != null) {
                    synchronized (seenScanAddresses) {
                        if (!seenScanAddresses.contains(address)) {
                            seenScanAddresses.add(address);
                            isNew = true;
                        }
                    }
                    synchronized (liveScanAddresses) {
                        liveScanAddresses.add(address);
                    }
                } else {
                    isNew = true;
                }
                if (isNew) {
                    Log.i(TAG, "BLE scan hit: " + (name != null ? name : address)
                            + " rssi=" + result.getRssi());
                    notifyDeviceFound(device);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                finishScanWithError("BLE scan failed: " + errorCode);
            }
        };
        Log.i(TAG, "Starting MeshCore BLE scan (" + (MESH_SCAN_TIMEOUT_MS / 1000) + "s)");
        try {
            bleScanner.startScan(null, settings, scanCallback);
            ioHandler.postDelayed(() -> {
                stopScanInternal();
                if (scanCompleteNotified.compareAndSet(false, true)) {
                    int seen;
                    synchronized (seenScanAddresses) {
                        seen = seenScanAddresses.size();
                    }
                    Log.i(TAG, "MeshCore BLE scan complete (" + seen + " candidate(s))");
                    notifyScanComplete();
                }
            }, MESH_SCAN_TIMEOUT_MS);
        } catch (Exception e) {
            Log.w(TAG, "BLE scan start failed", e);
            finishScanWithError("BLE scan start failed: " + e.getMessage());
        }
    }

    private void finishScanWithError(String message) {
        notifyError(message);
        stopScanInternal();
        if (scanCompleteNotified.compareAndSet(false, true)) {
            notifyScanComplete();
        }
    }

    /**
     * Emits only the saved last-connected target so it appears in the picker (greyed if it isn't
     * advertising right now). This replaces the old favorite/bonded/registry flood.
     */
    private void emitSavedTargetCandidate() {
        if (btAdapter == null) return;
        try {
            String tgt = BluetoothDeviceRegistry.getMeshConnectTargetAddress(context);
            if (tgt == null || tgt.isEmpty()) return;
            synchronized (seenScanAddresses) {
                if (seenScanAddresses.contains(tgt)) return;
                seenScanAddresses.add(tgt);
            }
            BluetoothDevice device = btAdapter.getRemoteDevice(tgt);
            if (device != null) notifyDeviceFound(device);
        } catch (Exception e) {
            Log.w(TAG, "emitSavedTargetCandidate failed", e);
        }
    }

    private void emitBondedMeshCandidates() {
        if (btAdapter == null) return;
        try {
            Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) return;
            for (BluetoothDevice device : bonded) {
                if (device == null || !MeshBleDeviceMatcher.isMeshDevice(context, device)) continue;
                String address = device.getAddress();
                boolean isNew = false;
                if (address != null && !address.isEmpty()) {
                    synchronized (seenScanAddresses) {
                        if (!seenScanAddresses.contains(address)) {
                            seenScanAddresses.add(address);
                            isNew = true;
                        }
                    }
                } else {
                    isNew = true;
                }
                if (isNew) notifyDeviceFound(device);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate bonded mesh devices", e);
        }
    }

    private void emitRegistryMeshCandidates() {
        try {
            for (BluetoothDeviceRegistry.BtDeviceRecord r :
                    BluetoothDeviceRegistry.getAllSortedForDisplay(context)) {
                if (r == null || r.address == null || r.address.isEmpty()) continue;
                String addr = r.address;
                boolean isNew;
                synchronized (seenScanAddresses) {
                    isNew = !seenScanAddresses.contains(addr);
                    if (isNew) seenScanAddresses.add(addr);
                }
                if (!isNew) continue;
                try {
                    BluetoothDevice device = btAdapter.getRemoteDevice(addr);
                    if (device != null) notifyDeviceFound(device);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "emitRegistryMeshCandidates failed", e);
        }
    }

    /** Returns true if the device was seen during the current live BLE scan. */
    public boolean isLiveScanDevice(BluetoothDevice device) {
        if (device == null) return false;
        String addr = device.getAddress();
        if (addr == null) return false;
        synchronized (liveScanAddresses) {
            return liveScanAddresses.contains(addr);
        }
    }

    @Override
    public void addProbeSocket(String address, BluetoothSocket socket) {
        // No-op for BLE path.
    }

    @Override
    public void clearProbeSockets() {
        // No-op for BLE path.
    }

    private boolean checkBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return hasLegacyScanLocationPermission();
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
        requestBtPermissions();
        return false;
    }

    private boolean checkScanPermissions() {
        if (!checkBtPermissions()) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return hasLegacyScanLocationPermission();
        }
        return true;
    }

    private boolean hasLegacyScanLocationPermission() {
        boolean fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (fine || coarse) {
            return true;
        }
        requestLegacyScanLocationPermission();
        return false;
    }

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            requestLegacyScanLocationPermission();
            return;
        }
        try {
            Context ctx = context;
            if (ctx instanceof Activity) {
                ((Activity) ctx).requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                }, 1001);
            } else {
                com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
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

    private void requestLegacyScanLocationPermission() {
        try {
            Context ctx = context;
            Activity activity = null;
            if (ctx instanceof Activity) {
                activity = (Activity) ctx;
            } else {
                com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
                if (mv != null && mv.getContext() instanceof Activity) {
                    activity = (Activity) mv.getContext();
                }
            }
            if (activity != null) {
                activity.requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, 1002);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not request location permission for BLE scan", e);
        }
    }

    @Override
    public void connect(BluetoothDevice device) {
        Log.i(TAG, "connect() not used for MeshCore — use Scan and Connect picker");
    }

    /** Called from picker row taps — sets userInitiatedConnect flag and ends picker session. */
    public void connectUserSelected(BluetoothDevice device) {
        if (!com.uvpro.plugin.ui.RadioProfile.shows(
                com.uvpro.plugin.ui.RadioSurfaceCatalog.Role.MESH, null)) {
            Log.i(TAG, "Connect skipped: MeshCore hidden by radio profile");
            return;
        }
        meshManualDisconnect.set(false);
        userInitiatedConnect.set(true);
        endScanPickerSession();
        // Cancel any in-flight availability probes so the probe GATT can't collide with the
        // real connection we're about to open.
        availabilityProber.cancelAll();
        connectInternal(device);
    }

    private void connectInternal(BluetoothDevice device) {
        if (!meshAirAllowed()) {
            Log.i(TAG, "Connect skipped: MeshCore hidden by radio profile");
            connecting.set(false);
            return;
        }
        if (device == null) return;
        if (scanPickerSessionActive.get() && !userInitiatedConnect.get()) {
            Log.i(TAG, "connectInternal blocked during scan/picker session");
            return;
        }
        if (meshManualDisconnect.get() && !userInitiatedConnect.get()) {
            Log.i(TAG, "connectInternal blocked: Mesh Disconnect is active");
            connecting.set(false);
            return;
        }
        stopScanInternal();
        if (connected.get()) {
            teardownActiveGatt(false);
        }
        if (connecting.getAndSet(true)) {
            return;
        }

        lastDevice = device;
        clearQueues();

        // If not paired yet, trigger Android pairing flow first.
        int bondState = device.getBondState();
        if (bondState != BluetoothDevice.BOND_BONDED) {
            pendingBondDevice = device;
            boolean requested = false;
            try {
                requested = device.createBond();
            } catch (Exception e) {
                Log.w(TAG, "createBond failed", e);
            }
            if (requested || bondState == BluetoothDevice.BOND_BONDING) {
                notifyError(MeshBleDeviceMatcher.pairingHintMessage(device));
                // IMPORTANT: do NOT connect now. Wait for the bond to complete — the bond
                // receiver issues the single connect. Connecting here would produce a second
                // GATT once the bond receiver fires, which closes the working link and trips a
                // supervision timeout (the connection "drops right after connecting").
                // connecting stays true; the bond receiver connects or clears it.
                return;
            }
            notifyError("Pairing not initiated for " + resolveName(device)
                    + ". Attempting BLE connect...");
            // Fall through: some devices pair lazily during GATT access.
        }

        connectGattNow(device);
    }

    @Override
    public void connectToLastDevice() {
        Log.i(TAG, "connectToLastDevice not supported — use Scan and Connect");
    }

    // -------------------------------------------------------------------------
    // Session management
    // -------------------------------------------------------------------------

    /** Called when the user taps Scan & Connect — exclusive picker session. */
    public void prepareForUserScan() {
        Log.i(TAG, "prepareForUserScan: Scan & Connect");
        BootScheduleListener bootListener = bootScheduleListener;
        if (bootListener != null) {
            bootListener.onUserScanStarting();
        }
        finishMeshBootAutoConnect(false);
        cancelPassiveMeshWatch();
        scanPickerSessionActive.set(true);
        scanSessionGeneration.incrementAndGet();
        autoConnectGeneration.incrementAndGet();
        cancelBootAutoConnect();
        cancelAutoConnectTimeout();
        availabilityProber.cancelAll();
        pendingBondDevice = null;
        userInitiatedConnect.set(false);
        connecting.set(false);
        if (gatt != null) {
            Log.i(TAG, "prepareForUserScan: aborting in-flight BLE connection");
            connected.set(false);
            ioHandler.removeCallbacks(periodicMessagePoll);
            ioHandler.post(this::closeGattInternal);
        }
    }

    /** Call when the picker is dismissed or a device is selected. */
    public void endScanPickerSession() {
        scanPickerSessionActive.set(false);
    }

    public boolean isScanPickerSessionActive() {
        return scanPickerSessionActive.get();
    }

    // -------------------------------------------------------------------------
    // Passive mesh watch (late power-on / ACL, mirrors UV-PRO Classic BT recovery)
    // -------------------------------------------------------------------------

    private void registerMeshAvailabilityReceiver() {
        if (meshAvailabilityReceiverRegistered) {
            return;
        }
        meshAvailabilityReceiver = new BroadcastReceiver() {
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
                        onMeshAclConnected(device);
                    }
                } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR);
                    if (state == BluetoothAdapter.STATE_ON) {
                        onBluetoothEnabledForMesh();
                    }
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(meshAvailabilityReceiver, filter,
                        Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(meshAvailabilityReceiver, filter);
            }
            meshAvailabilityReceiverRegistered = true;
            Log.d(TAG, "Mesh availability receiver registered");
        } catch (Exception e) {
            Log.w(TAG, "Could not register mesh availability receiver", e);
        }
    }

    private String getSavedMeshTargetAddress() {
        try {
            return BluetoothDeviceRegistry.getMeshConnectTargetAddress(context);
        } catch (Exception e) {
            return null;
        }
    }

    private BluetoothDevice resolveSavedMeshDevice() {
        String tgt = getSavedMeshTargetAddress();
        if (tgt == null || tgt.isEmpty() || btAdapter == null) {
            return null;
        }
        try {
            return btAdapter.getRemoteDevice(tgt);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSavedMeshTarget(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        String tgt = getSavedMeshTargetAddress();
        return tgt != null && tgt.equalsIgnoreCase(address);
    }

    private void onMeshAclConnected(BluetoothDevice device) {
        if (device == null || device.getAddress() == null) {
            return;
        }
        if (meshManualDisconnect.get() || scanPickerSessionActive.get()) {
            return;
        }
        if (connected.get() || connecting.get()) {
            cancelPendingMeshAclProbe();
            return;
        }
        String addr = device.getAddress();
        Log.d(TAG, "ACL connected " + addr + " (saved mesh="
                + getSavedMeshTargetAddress() + ")");
        if (!isSavedMeshTarget(addr)) {
            return;
        }
        Log.i(TAG, "ACL connected for saved mesh " + addr + " — scheduling probe");
        scheduleMeshAclProbe(device, "acl-connected");
    }

    private void onBluetoothEnabledForMesh() {
        if (meshManualDisconnect.get() || scanPickerSessionActive.get()) {
            return;
        }
        if (connected.get() || connecting.get()) {
            return;
        }
        BluetoothDevice device = resolveSavedMeshDevice();
        if (device == null) {
            return;
        }
        Log.i(TAG, "Bluetooth enabled — arming mesh passive watch for "
                + device.getAddress());
        armPassiveMeshWatch();
    }

    private void scheduleMeshAclProbe(BluetoothDevice device, String reason) {
        if (device == null) {
            return;
        }
        cancelPendingMeshAclProbe();
        final BluetoothDevice target = device;
        pendingMeshAclRunnable = () -> {
            pendingMeshAclRunnable = null;
            probeAndConnectSavedMesh(reason, target);
        };
        mainHandler.postDelayed(pendingMeshAclRunnable, MESH_ACL_DEBOUNCE_MS);
    }

    private void cancelPendingMeshAclProbe() {
        if (pendingMeshAclRunnable != null) {
            mainHandler.removeCallbacks(pendingMeshAclRunnable);
            pendingMeshAclRunnable = null;
        }
    }

    private void armPassiveMeshWatch() {
        if (meshManualDisconnect.get() || scanPickerSessionActive.get()) {
            return;
        }
        if (connected.get() || connecting.get()) {
            return;
        }
        if (resolveSavedMeshDevice() == null) {
            return;
        }
        passiveMeshWatchArmed.set(true);
        passiveMeshWatchAttempt = 0;
        cancelPassiveMeshWatchScheduled();
        Log.i(TAG, "Mesh passive watch armed (target=" + getSavedMeshTargetAddress() + ")");
        scheduleNextPassiveMeshWatch(MESH_PASSIVE_INITIAL_DELAY_MS);
    }

    private void cancelPassiveMeshWatch() {
        passiveMeshWatchArmed.set(false);
        passiveMeshWatchAttempt = 0;
        passiveMeshProbeGeneration.incrementAndGet();
        cancelPassiveMeshWatchScheduled();
        cancelPendingMeshAclProbe();
    }

    private void cancelPassiveMeshWatchScheduled() {
        if (passiveMeshWatchRunnable != null) {
            mainHandler.removeCallbacks(passiveMeshWatchRunnable);
            passiveMeshWatchRunnable = null;
        }
    }

    private boolean shouldRunPassiveMeshWatch() {
        if (!passiveMeshWatchArmed.get() || meshManualDisconnect.get()
                || scanPickerSessionActive.get()) {
            return false;
        }
        if (connected.get() || connecting.get()) {
            return false;
        }
        String tgt = getSavedMeshTargetAddress();
        return tgt != null && !tgt.isEmpty();
    }

    private void scheduleNextPassiveMeshWatch(long delayMs) {
        if (!shouldRunPassiveMeshWatch()) {
            cancelPassiveMeshWatch();
            return;
        }
        if (passiveMeshWatchRunnable != null) {
            mainHandler.removeCallbacks(passiveMeshWatchRunnable);
        }
        passiveMeshWatchRunnable = () -> {
            passiveMeshWatchRunnable = null;
            if (!shouldRunPassiveMeshWatch()) {
                cancelPassiveMeshWatch();
                return;
            }
            BluetoothDevice device = resolveSavedMeshDevice();
            if (device != null) {
                probeAndConnectSavedMesh("passive-watch", device);
            }
            scheduleNextPassiveMeshWatch(nextPassiveMeshWatchDelay());
        };
        mainHandler.postDelayed(passiveMeshWatchRunnable, delayMs);
    }

    private long nextPassiveMeshWatchDelay() {
        int idx = passiveMeshWatchAttempt++;
        if (idx < MESH_PASSIVE_BACKOFF_MS.length) {
            return MESH_PASSIVE_BACKOFF_MS[idx];
        }
        return MESH_PASSIVE_INTERVAL_MS;
    }

    private void probeAndConnectSavedMesh(String reason, BluetoothDevice device) {
        if (device == null || meshManualDisconnect.get() || scanPickerSessionActive.get()) {
            return;
        }
        if (connected.get() || connecting.get()) {
            return;
        }
        if (!checkBtPermissions()) {
            return;
        }
        if (!isSafeForAvailabilityProbe(device)) {
            return;
        }
        final int gen = passiveMeshProbeGeneration.incrementAndGet();
        final String addr = device.getAddress();
        Log.i(TAG, reason + ": probing saved mesh " + addr);
        probeDeviceAvailability(device, availability -> {
            if (gen != passiveMeshProbeGeneration.get()) {
                return;
            }
            if (meshManualDisconnect.get() || scanPickerSessionActive.get()) {
                return;
            }
            if (connected.get() || connecting.get()) {
                return;
            }
            if (availability == AVAIL_AVAILABLE) {
                Log.i(TAG, reason + ": connecting to " + addr);
                connectInternal(device);
            } else {
                Log.d(TAG, reason + ": mesh " + addr + " not available (avail=" + availability + ")");
            }
        });
    }

    // -------------------------------------------------------------------------
    // Boot auto-connect (last saved bonded mesh target)
    // -------------------------------------------------------------------------

    /** Probe the saved target and connect if available. */
    public void tryAutoConnectToSavedTarget() {
        if (!meshAirAllowed()) {
            Log.i(TAG, "Boot auto-connect skipped: MeshCore hidden by radio profile");
            return;
        }
        autoConnectToSavedTargetInternal("Boot auto-connect", true, true);
    }

    /** After UV-PRO connects at boot — may run even if the 7s fallback already probed once. */
    public void tryAutoConnectAfterRadioConnect() {
        if (!meshAirAllowed()) {
            Log.i(TAG, "Post-radio auto-connect skipped: MeshCore hidden by radio profile");
            return;
        }
        autoConnectToSavedTargetInternal("Post-radio auto-connect", false, true);
    }

    public void cancelBootAutoConnect() {
        autoConnectGeneration.incrementAndGet();
        cancelAutoConnectTimeout();
        meshBootAutoConnectResolving.set(false);
    }

    private void beginMeshBootAutoConnect(String reason) {
        if (!meshBootAutoConnectResolving.compareAndSet(false, true)) {
            return;
        }
        MeshBootAutoConnectListener listener = meshBootAutoConnectListener;
        if (listener != null) {
            listener.onMeshBootAutoConnectStarted(reason);
        }
    }

    public boolean isPassiveMeshWatchArmed() {
        return passiveMeshWatchArmed.get();
    }

    private void finishMeshBootAutoConnect(boolean connected) {
        boolean wasResolving = meshBootAutoConnectResolving.getAndSet(false);
        if (!connected && !scanPickerSessionActive.get() && !meshManualDisconnect.get()) {
            armPassiveMeshWatch();
        }
        if (!wasResolving) {
            return;
        }
        MeshBootAutoConnectListener listener = meshBootAutoConnectListener;
        if (listener != null) {
            listener.onMeshBootAutoConnectFinished(connected);
        }
        if (connected) {
            cancelPassiveMeshWatch();
        }
    }

    /**
     * Mesh boot did not start a probe (no target, not bonded, etc.) or mesh was already up.
     * Lets the UI schedule startup transmit selection after mesh boot phase ends.
     */
    private void notifyMeshBootStartupIdleIfNeeded(boolean notifyIfSkipped, boolean connectedNow) {
        if (!notifyIfSkipped || meshBootAutoConnectResolving.get()) {
            return;
        }
        MeshBootAutoConnectListener listener = meshBootAutoConnectListener;
        if (listener != null) {
            listener.onMeshBootAutoConnectFinished(connectedNow);
        }
    }

    private void autoConnectToSavedTargetInternal(String reason, boolean markBootAttempted,
                                                boolean notifyIfSkippedWithoutAttempt) {
        if (scanPickerSessionActive.get()) {
            Log.i(TAG, reason + ": scan/picker active — skipping");
            return;
        }
        if (meshManualDisconnect.get()) {
            Log.i(TAG, reason + ": Mesh Disconnect is active — skipping");
            return;
        }
        if (connected.get()) {
            notifyMeshBootStartupIdleIfNeeded(notifyIfSkippedWithoutAttempt, true);
            return;
        }
        if (connecting.get()) {
            return;
        }
        if (markBootAttempted && !savedTargetAutoConnectAttempted.compareAndSet(false, true)) {
            Log.d(TAG, reason + ": already attempted this session");
            notifyMeshBootStartupIdleIfNeeded(notifyIfSkippedWithoutAttempt, false);
            return;
        }
        if (!checkBtPermissions()) {
            Log.i(TAG, reason + ": no BT permissions yet");
            notifyMeshBootStartupIdleIfNeeded(notifyIfSkippedWithoutAttempt, false);
            return;
        }
        String tgt = BluetoothDeviceRegistry.getMeshConnectTargetAddress(context);
        if (tgt == null || tgt.isEmpty()) {
            Log.d(TAG, reason + ": no saved mesh target");
            notifyMeshBootStartupIdleIfNeeded(notifyIfSkippedWithoutAttempt, false);
            return;
        }
        BluetoothDevice device;
        try {
            device = btAdapter != null ? btAdapter.getRemoteDevice(tgt) : null;
        } catch (Exception e) {
            Log.w(TAG, reason + ": bad address " + tgt, e);
            notifyMeshBootStartupIdleIfNeeded(notifyIfSkippedWithoutAttempt, false);
            return;
        }
        if (device == null) {
            notifyMeshBootStartupIdleIfNeeded(notifyIfSkippedWithoutAttempt, false);
            return;
        }
        int savedTargetBondState = BluetoothDevice.BOND_NONE;
        try {
            savedTargetBondState = device.getBondState();
        } catch (Exception ignored) {
        }
        if (savedTargetBondState != BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, reason + ": saved target " + tgt
                    + " is not bonded — skipping background auto-connect");
            notifyMeshBootStartupIdleIfNeeded(notifyIfSkippedWithoutAttempt, false);
            return;
        }
        final int probeGeneration = autoConnectGeneration.get();
        beginMeshBootAutoConnect(reason);
        Log.i(TAG, reason + ": probing " + tgt);
        scheduleAutoConnectTimeout(30_000);
        AvailabilityCallback onProbeResult = availability -> {
            if (probeGeneration != autoConnectGeneration.get()) {
                Log.i(TAG, reason + " probe result stale — cancelled, ignoring");
                cancelAutoConnectTimeout();
                return;
            }
            if (meshManualDisconnect.get() || scanPickerSessionActive.get()) {
                cancelAutoConnectTimeout();
                return;
            }
            if (availability == AVAIL_AVAILABLE) {
                if (!connected.get() && !connecting.get()) {
                    Log.i(TAG, reason + ": connecting to " + tgt);
                    connectInternal(device);
                }
            } else {
                cancelAutoConnectTimeout();
                Log.i(TAG, reason + ": target not available");
                finishMeshBootAutoConnect(false);
                if (markBootAttempted && !connected.get()) {
                    notifyDisconnected("Boot auto-connect unavailable");
                }
            }
        };
        probeDeviceAvailability(device, onProbeResult);
    }

    /**
     * Schedule a hard stop for background auto-connect after {@code timeoutMs} milliseconds.
     */
    public void scheduleAutoConnectTimeout(long timeoutMs) {
        cancelAutoConnectTimeout();
        autoConnectTimeoutRunnable = () -> {
            autoConnectTimeoutRunnable = null;
            if (!connected.get()) {
                Log.i(TAG, "Auto-connect timeout reached — giving up");
                connecting.set(false);
                finishMeshBootAutoConnect(false);
                notifyDisconnected("Auto-connect timed out");
            }
        };
        ioHandler.postDelayed(autoConnectTimeoutRunnable, timeoutMs);
    }

    public void cancelAutoConnectTimeout() {
        if (autoConnectTimeoutRunnable != null) {
            ioHandler.removeCallbacks(autoConnectTimeoutRunnable);
            autoConnectTimeoutRunnable = null;
        }
    }

    // -------------------------------------------------------------------------
    // Availability probing (picker dots only)
    // -------------------------------------------------------------------------

    public boolean isSafeForAvailabilityProbe(BluetoothDevice device) {
        if (device == null) return false;
        try {
            return device.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (Exception e) {
            return false;
        }
    }

    public void prepareForAvailabilityProbes() {
        availabilityProber.cancelAll();
    }

    public void cancelAvailabilityProbes() {
        availabilityProber.cancelAll();
    }

    public void probeDeviceAvailability(BluetoothDevice device, AvailabilityCallback callback) {
        availabilityProber.probe(context, ioHandler, device,
                availability -> callback.onResult(availability));
    }

    public void probeDeviceAvailabilityLight(BluetoothDevice device,
                                             AvailabilityCallback callback) {
        availabilityProber.probeLight(context, ioHandler, device,
                availability -> callback.onResult(availability));
    }

    /**
     * Uses full probe for bonded devices (accurate busy detection) and light probe for
     * unbonded devices (shows green without triggering pairing dialog).
     */
    public void probeDeviceAvailabilityForPicker(BluetoothDevice device,
                                                 AvailabilityCallback callback) {
        if (isSafeForAvailabilityProbe(device)) {
            probeDeviceAvailability(device, callback);
        } else {
            probeDeviceAvailabilityLight(device, callback);
        }
    }

    @Override
    public void disconnect() {
        teardownActiveGatt(true);
    }

    private void teardownActiveGatt(boolean userRequested) {
        if (userRequested) {
            meshManualDisconnect.set(true);
            scanSessionGeneration.incrementAndGet();
            autoConnectGeneration.incrementAndGet();
            cancelBootAutoConnect();
            cancelAutoConnectTimeout();
            cancelPassiveMeshWatch();
            savedTargetAutoConnectAttempted.set(true);
            userInitiatedConnect.set(false);
            pendingBondDevice = null;
        }
        availabilityProber.cancelAll();
        connecting.set(false);
        connected.set(false);
        cancelRoomContactPrepare();
        meshGpsEnabled = null;
        sendPositionWithAdvertEnabled = null;
        latestNodeSettings = null;
        latestSelfLocation = null;
        latestBatteryMv = -1;
        latestBatteryPercent = -1;
        stopScanInternal();
        ioHandler.removeCallbacks(periodicMessagePoll);
        ioHandler.post(() -> {
            runBeforeDisconnectHooks();
            closeGattInternal();
        });
        if (userRequested) {
            notifyDisconnected("User disconnected");
        }
    }

    @Override
    public void cancelConnectionAttempts() {
        autoConnectGeneration.incrementAndGet();
        finishMeshBootAutoConnect(false);
        cancelBootAutoConnect();
        cancelAutoConnectTimeout();
        availabilityProber.cancelAll();
        userInitiatedConnect.set(false);
        connecting.set(false);
        connected.set(false);
        meshGpsEnabled = null;
        sendPositionWithAdvertEnabled = null;
        latestNodeSettings = null;
        latestSelfLocation = null;
        latestBatteryMv = -1;
        latestBatteryPercent = -1;
        stopScanInternal();
        ioHandler.removeCallbacks(periodicMessagePoll);
        ioHandler.post(() -> {
            runBeforeDisconnectHooks();
            closeGattInternal();
        });
        notifyDisconnected("Connection attempt cancelled");
    }

    @Override
    public boolean sendKissFrame(byte[] ax25Frame) {
        if (!connected.get() || ax25Frame == null || ax25Frame.length == 0) {
            return false;
        }
        if (blockedByRadioSilence()) {
            return false;
        }

        int channel = getMeshChannelIndex();
        int msgId = outboundMsgId.getAndIncrement() & 0xFFFF;
        int total = sendAx25EnvelopeChunks(channel, msgId, ax25Frame);
        if (total <= 0) {
            return false;
        }
        Log.i(TAG, "TX ATAK_DATA ch=" + channel + " ax25=" + ax25Frame.length + " bytes"
                + " chunks=" + total);
        packetRouter.notifyPacketTransmitted();
        return true;
    }

    /**
     * One LoRa group-data packet holds {@link #MESH_GROUP_DATA_MAX} bytes.
     * A frame that fits is {@code 0xB1} plus the raw AX.25. A larger frame is
     * {@code 0xB2} plus id, seq, total, and a raw slice. Channel path stays flood.
     */
    private int sendAx25EnvelopeChunks(int channel, int msgId, byte[] ax25Frame) {
        if (ax25Frame.length <= MESH_GROUP_DATA_MAX - 1) {
            byte[] payload = new byte[1 + ax25Frame.length];
            payload[0] = (byte) MESH_AX25_SINGLE;
            System.arraycopy(ax25Frame, 0, payload, 1, ax25Frame.length);
            enqueueCommand(buildSendChannelDataCommand(channel, ATAK_DATA_TYPE_AX25, payload));
            return 1;
        }
        int sliceMax = MESH_GROUP_DATA_MAX - MESH_AX25_PART_HEADER;
        int total = (ax25Frame.length + sliceMax - 1) / sliceMax;
        if (total < 2 || total > 255) {
            Log.w(TAG, "AX.25 frame does not fit mesh channel slices len=" + ax25Frame.length);
            return 0;
        }
        for (int seq = 1; seq <= total; seq++) {
            int off = (seq - 1) * sliceMax;
            int len = Math.min(sliceMax, ax25Frame.length - off);
            byte[] payload = new byte[MESH_AX25_PART_HEADER + len];
            payload[0] = (byte) MESH_AX25_PART;
            payload[1] = (byte) (msgId & 0xFF);
            payload[2] = (byte) ((msgId >> 8) & 0xFF);
            payload[3] = (byte) seq;
            payload[4] = (byte) total;
            System.arraycopy(ax25Frame, off, payload, MESH_AX25_PART_HEADER, len);
            enqueueCommand(buildSendChannelDataCommand(channel, ATAK_DATA_TYPE_AX25, payload));
        }
        return total;
    }

    /**
     * Largest UV-PRO payload that still fits in one channel packet.
     * AX.25 UI header is 16 bytes, UV-PRO header is 3, and the channel
     * payload is {@code 0xB1} plus that frame. Multi-chunk joins of a point
     * arrived at the right length and then failed gzip.
     */
    public int maxUvproPayloadForSinglePacket() {
        return (MESH_GROUP_DATA_MAX - 1) - 16 - 3;
    }

    private static final long[] INCOMPLETE_CHUNK_DRAIN_DELAYS_MS = {25L, 100L, 300L};
    private int incompleteChunkDrainGeneration = 0;

    /** Prompt companion queue drain when a multi-chunk AX.25 frame is only partially received. */
    private void scheduleIncompleteChunkDrain() {
        if (!connected.get()) {
            return;
        }
        final int gen = ++incompleteChunkDrainGeneration;
        for (long delay : INCOMPLETE_CHUNK_DRAIN_DELAYS_MS) {
            ioHandler.postDelayed(() -> {
                if (!connected.get() || gen != incompleteChunkDrainGeneration) {
                    return;
                }
                enqueueCommand(buildGetNextMessageCommand());
            }, delay);
        }
    }

    @Override
    public boolean sendRawBytes(byte[] data) {
        if (!connected.get() || data == null || data.length == 0) {
            return false;
        }
        if (blockedByRadioSilence()) {
            return false;
        }
        String payload = "UVRAW|" + Base64.encodeToString(data, Base64.NO_WRAP);
        if (payload.length() > MAX_MESH_MESSAGE_LEN) {
            return false;
        }
        enqueueCommand(buildSendChannelDataCommand(
                getMeshChannelIndex(),
                ATAK_DATA_TYPE_RAW,
                payload.getBytes(StandardCharsets.UTF_8)));
        return true;
    }

    @Override
    public void setRadioSilenceEnabled(boolean enabled) {
        radioSilenceEnabled.set(enabled);
        Log.i(TAG, "Radio Silence " + (enabled ? "enabled" : "disabled"));
        if (!enabled) {
            notifyRadioSilenceCleared();
        }
    }

    /** Air sends stop. Companion reads and local radio settings stay allowed. */
    private boolean blockedByRadioSilence() {
        if (!meshAirAllowed()) {
            Log.i(TAG, "TX blocked: MeshCore hidden by radio profile");
            return true;
        }
        if (!radioSilenceEnabled.get()) {
            return false;
        }
        Log.w(TAG, "TX blocked by Radio Silence");
        return true;
    }

    private static boolean meshAirAllowed() {
        return com.uvpro.plugin.ui.RadioProfile.shows(
                com.uvpro.plugin.ui.RadioSurfaceCatalog.Role.MESH, null);
    }

    @Override
    public boolean isRadioSilenceEnabled() {
        return radioSilenceEnabled.get();
    }

    private void handleConnectionLost() {
        finishMeshBootAutoConnect(false);
        connected.set(false);
        connecting.set(false);
        if (deviceContactsFetchActive) {
            finishDeviceContactsFetch(false, "Disconnected");
        }
        ioHandler.removeCallbacks(periodicMessagePoll);
        clearQueues();
        ioHandler.post(this::closeGattInternal);
        notifyDisconnected("Connection lost");
        if (!meshManualDisconnect.get() && !scanPickerSessionActive.get()) {
            armPassiveMeshWatch();
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public boolean isConnecting() {
        return connecting.get();
    }

    @Override
    public long getLastIoActivityMs() {
        return lastIoActivityMs.get();
    }

    @Override
    public boolean hasRecentIo(long withinMs) {
        long last = lastIoActivityMs.get();
        if (last <= 0L) {
            return false;
        }
        return (System.currentTimeMillis() - last) <= Math.max(0L, withinMs);
    }

    @Override
    public String getConnectedDeviceName() {
        if (!connected.get()) return null;
        if (lastDevice != null) {
            String name = lastDevice.getName();
            return name != null ? name : lastDevice.getAddress();
        }
        return "MeshCore";
    }

    @androidx.annotation.Nullable
    public String getConnectedDeviceAddress() {
        if (!connected.get() || lastDevice == null) {
            return null;
        }
        return lastDevice.getAddress();
    }

    @Override
    public void addRawDataListener(RawDataListener listener) {
        rawDataListeners.add(listener);
    }

    @Override
    public void removeRawDataListener(RawDataListener listener) {
        rawDataListeners.remove(listener);
    }

    @Override
    public void addBeforeDisconnectHook(Runnable hook) {
        if (hook != null) {
            beforeDisconnectHooks.add(hook);
        }
    }

    public void removeBeforeDisconnectHook(Runnable hook) {
        beforeDisconnectHooks.remove(hook);
    }

    public void addMeshStateListener(MeshStateListener listener) {
        if (listener != null) {
            meshStateListeners.add(listener);
        }
    }

    public void removeMeshStateListener(MeshStateListener listener) {
        meshStateListeners.remove(listener);
    }

    public void addRepeaterAdvertListener(RepeaterAdvertListener listener) {
        if (listener != null) {
            repeaterAdvertListeners.addIfAbsent(listener);
        }
    }

    public void removeRepeaterAdvertListener(RepeaterAdvertListener listener) {
        repeaterAdvertListeners.remove(listener);
    }

    public void addMeshAdvertListener(MeshAdvertListener listener) {
        if (listener != null) {
            meshAdvertListeners.addIfAbsent(listener);
        }
    }

    public void removeMeshAdvertListener(MeshAdvertListener listener) {
        meshAdvertListeners.remove(listener);
    }

    public void addMeshDeviceContactUpdateListener(MeshDeviceContactUpdateListener listener) {
        if (listener != null) {
            deviceContactUpdateListeners.addIfAbsent(listener);
        }
    }

    public void removeMeshDeviceContactUpdateListener(MeshDeviceContactUpdateListener listener) {
        deviceContactUpdateListeners.remove(listener);
    }

    public void addMeshChannelListener(MeshChannelListener listener) {
        if (listener != null) {
            meshChannelListeners.addIfAbsent(listener);
        }
    }

    public void removeMeshChannelListener(MeshChannelListener listener) {
        meshChannelListeners.remove(listener);
    }

    public void addMeshNativeDmListener(MeshNativeDmListener listener) {
        if (listener != null) {
            meshNativeDmListeners.addIfAbsent(listener);
        }
    }

    public void removeMeshNativeDmListener(MeshNativeDmListener listener) {
        meshNativeDmListeners.remove(listener);
    }

    public void addMeshRoomLoginListener(MeshRoomLoginListener listener) {
        if (listener != null) {
            meshRoomLoginListeners.addIfAbsent(listener);
        }
    }

    public void removeMeshRoomLoginListener(MeshRoomLoginListener listener) {
        meshRoomLoginListeners.remove(listener);
    }

    /** Ask companion radio for the latest contact record (name, path, etc.) by full pubkey. */
    public void requestContactByPubKeyHex(@Nullable String pubKeyHex) {
        if (!connected.get() || pubKeyHex == null) {
            return;
        }
        byte[] pubKey = pubKeyPrefixBytes(pubKeyHex, CONTACT_PUB_KEY_BYTES);
        if (pubKey == null) {
            return;
        }
        byte[] advertFrame = new byte[1 + CONTACT_PUB_KEY_BYTES];
        advertFrame[0] = PUSH_CODE_ADVERT;
        System.arraycopy(pubKey, 0, advertFrame, 1, CONTACT_PUB_KEY_BYTES);
        enqueueCommand(buildGetContactByKeyCommand(advertFrame));
        Log.d(TAG, "CMD_GET_CONTACT_BY_KEY queued for name/path refresh");
    }

    public void requestDeviceContacts(DeviceContactsListener listener) {
        if (!connected.get()) {
            if (listener != null) {
                listener.onDeviceContactsFailed("Not connected");
            }
            return;
        }
        if (deviceContactsFetchActive) {
            if (listener != null) {
                listener.onDeviceContactsFailed("Contact sync already in progress");
            }
            return;
        }
        pendingDeviceContactsListener = listener;
        pendingDeviceContactsList.clear();
        deviceContactsFetchActive = true;
        ioHandler.removeCallbacks(deviceContactsFetchTimeoutRunnable);
        ioHandler.postDelayed(deviceContactsFetchTimeoutRunnable, DEVICE_CONTACTS_FETCH_TIMEOUT_MS);
        enqueueCommand(new byte[]{CMD_GET_CONTACTS});
        Log.d(TAG, "CMD_GET_CONTACTS queued");
    }

    /**
     * Mark a device contact as favorite so firmware will not evict it when the table is full.
     */
    public boolean addOrUpdateDeviceContactFavorite(MeshDeviceContact contact) {
        if (!connected.get() || contact == null) {
            return false;
        }
        byte[] cmd = buildAddUpdateContactCommand(contact, contact.flags | CONTACT_FLAG_FAVORITE);
        if (cmd == null) {
            return false;
        }
        enqueueCommand(cmd);
        Log.d(TAG, "CMD_ADD_UPDATE_CONTACT favorite queued name=" + contact.name);
        return true;
    }

    public boolean addOrUpdateDeviceContact(MeshDeviceContact contact) {
        if (!connected.get() || contact == null) {
            return false;
        }
        byte[] cmd = buildAddUpdateContactCommand(contact, contact.flags);
        if (cmd == null) {
            return false;
        }
        enqueueCommand(cmd);
        Log.d(TAG, "CMD_ADD_UPDATE_CONTACT queued name=" + contact.name + " type=" + contact.type);
        return true;
    }

    /**
     * Room server skips sync_since refresh on blank-password ACL re-login (MyMesh.cpp).
     * Send a non-empty sentinel so the server runs the password path and applies sync_since.
     */
    private static final String BLANK_ROOM_LOGIN_SENTINEL = ".";

    public boolean sendRoomLogin(String pubKeyHex, @Nullable String password) {
        if (!connected.get()) {
            return false;
        }
        if (blockedByRadioSilence()) {
            return false;
        }
        byte[] pubKey = pubKeyPrefixBytes(pubKeyHex, CONTACT_PUB_KEY_BYTES);
        if (pubKey == null) {
            Log.w(TAG, "Room login aborted: invalid pubkey");
            return false;
        }
        String loginPassword = password != null ? password : "";
        boolean blankPassword = loginPassword.isEmpty();
        if (blankPassword) {
            loginPassword = BLANK_ROOM_LOGIN_SENTINEL;
        }
        byte[] cmd = buildSendLoginCommand(pubKey, loginPassword);
        enqueueCommand(cmd);
        Log.i(TAG, "CMD_SEND_LOGIN queued prefix="
                + bytesToHex(pubKey, 0, Math.min(6, pubKey.length))
                + " blankSentinel=" + blankPassword);
        return true;
    }

    public boolean sendContactCliMessage(String pubKeyHex, String cliCommand) {
        if (!connected.get() || cliCommand == null || cliCommand.trim().isEmpty()) {
            return false;
        }
        if (blockedByRadioSilence()) {
            return false;
        }
        byte[] prefix = pubKeyPrefixBytes(pubKeyHex, 6);
        if (prefix == null) {
            return false;
        }
        byte[] cmd = buildSendTxtMsgCommand(prefix, cliCommand.trim(), TXT_TYPE_CLI_DATA);
        if (cmd == null) {
            return false;
        }
        enqueueCommand(cmd);
        Log.d(TAG, "CLI to contact prefix=" + bytesToHex(prefix, 0, prefix.length)
                + " cmd=" + cliCommand.trim());
        return true;
    }

    public void requestMessageDrain(int count) {
        requestMessageDrain(count, false);
    }

    public void requestMessageDrain(int count, boolean highPriority) {
        if (!connected.get() || count <= 0) {
            return;
        }
        int n = Math.min(count, 64);
        for (int i = 0; i < n; i++) {
            enqueueCommand(buildGetNextMessageCommand(), highPriority);
        }
    }

    public void beginRoomPostSyncSession() {
        roomPostSyncUntilMs = System.currentTimeMillis() + ROOM_POST_SYNC_BASE_MS;
    }

    public void extendRoomPostSyncSession() {
        long extendTo = System.currentTimeMillis() + ROOM_POST_SYNC_EXTEND_MS;
        roomPostSyncUntilMs = Math.max(roomPostSyncUntilMs, extendTo);
    }

    public boolean isRoomPostSyncSessionActive() {
        return roomPostSyncUntilMs > 0
                && System.currentTimeMillis() <= roomPostSyncUntilMs;
    }

    public void endRoomPostSyncSession() {
        roomPostSyncUntilMs = 0L;
    }

    public void cancelRoomContactPrepare() {
        roomContactPrepareInProgress = false;
        if (ioHandler != null && pendingRoomContactPrepareStep != null) {
            ioHandler.removeCallbacks(pendingRoomContactPrepareStep);
            pendingRoomContactPrepareStep = null;
        }
    }

    public boolean isRoomContactPrepareInProgress() {
        return roomContactPrepareInProgress;
    }

    /**
     * Staged remove/re-add so the companion treats the room as a new contact ({@code sync_since=0})
     * before {@link #sendRoomLogin} runs.
     */
    public void prepareRoomContactForFullHistorySync(
            MeshDeviceContact contact, @Nullable Runnable whenReady) {
        cancelRoomContactPrepare();
        if (contact == null || !connected.get()) {
            if (whenReady != null) {
                mainHandler.post(whenReady);
            }
            return;
        }
        removeDeviceContact(contact);
        roomContactPrepareInProgress = true;
        pendingRoomContactPrepareStep = () -> {
            pendingRoomContactPrepareStep = null;
            if (!connected.get()) {
                roomContactPrepareInProgress = false;
                return;
            }
            addOrUpdateDeviceContactFavorite(contact);
            if (whenReady != null && ioHandler != null) {
                ioHandler.postDelayed(() -> mainHandler.post(() -> {
                    try {
                        whenReady.run();
                    } finally {
                        roomContactPrepareInProgress = false;
                    }
                }), ROOM_CONTACT_ADD_SETTLE_MS);
            } else {
                roomContactPrepareInProgress = false;
            }
        };
        if (ioHandler != null) {
            ioHandler.postDelayed(pendingRoomContactPrepareStep, ROOM_CONTACT_REMOVE_SETTLE_MS);
        }
    }

    public void resetRoomContactForFreshSync(MeshDeviceContact contact) {
        prepareRoomContactForFullHistorySync(contact, null);
    }

    public boolean removeDeviceContact(MeshDeviceContact contact) {
        if (!connected.get() || contact == null) {
            return false;
        }
        byte[] pubKey = pubKeyPrefixBytes(contact.pubKeyHex, CONTACT_PUB_KEY_BYTES);
        if (pubKey == null) {
            return false;
        }
        byte[] out = new byte[1 + CONTACT_PUB_KEY_BYTES];
        out[0] = CMD_REMOVE_CONTACT;
        System.arraycopy(pubKey, 0, out, 1, CONTACT_PUB_KEY_BYTES);
        enqueueCommand(out);
        Log.d(TAG, "CMD_REMOVE_CONTACT queued name=" + contact.name);
        return true;
    }

    public void trimDeviceContactsToRollingCap(
            @androidx.annotation.NonNull java.util.List<MeshDeviceContact> contacts) {
        java.util.List<MeshDeviceContact> toRemove =
                MeshDeviceContactPolicy.contactsToEvictFromDevice(contacts);
        for (MeshDeviceContact contact : toRemove) {
            removeDeviceContact(contact);
        }
    }

    public Map<Integer, String> getKnownChannelNamesSnapshot() {
        return new ConcurrentHashMap<>(meshChannelNamesByIndex);
    }

    public void requestAllChannelInfo() {
        if (!connected.get()) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            enqueueCommand(buildGetChannelInfoCommand(i));
        }
    }

    /**
     * Set a channel slot on the node. The 16-byte {@code secret} is typically derived as
     * MD5(passphrase). Pass {@code null} secret and empty name to clear the slot.
     */
    public boolean setChannelSlot(int idx, String name, byte[] secret) {
        if (!connected.get()) {
            return false;
        }
        if (idx < 0 || idx > 7) {
            return false;
        }
        if (secret != null) channelSecretsByIndex.put(idx, java.util.Arrays.copyOf(secret, secret.length));
        enqueueCommand(buildSetChannelCommand(idx, name != null ? name : "", secret));
        enqueueCommand(buildGetChannelInfoCommand(idx));
        return true;
    }

    /** Remove a channel slot (set to empty name + zeroed secret). */
    public boolean clearChannelSlot(int idx) {
        return setChannelSlot(idx, "", new byte[16]);
    }

    public byte[] getChannelSecret(int idx) {
        return channelSecretsByIndex.get(idx);
    }

    public boolean sendChannelText(int channelIndex, String text) {
        if (!connected.get()) {
            return false;
        }
        if (blockedByRadioSilence()) {
            return false;
        }
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        synchronized (pendingChannelTextSends) {
            pendingChannelTextSends.addLast(new PendingChannelText(
                    Math.max(0, Math.min(7, channelIndex)),
                    trimmed,
                    System.currentTimeMillis()));
            while (pendingChannelTextSends.size() > 64) {
                pendingChannelTextSends.removeFirst();
            }
        }
        enqueueCommand(buildSendChannelMessageCommand(channelIndex, trimmed));
        notifyMeshChannelMessage(new MeshChannelMessage(
                Math.max(0, Math.min(7, channelIndex)),
                trimmed,
                System.currentTimeMillis(),
                true,
                "queued",
                null,
                null,
                null));
        return true;
    }

    /**
     * Send a native MeshCore direct (contact) message to a node identified by pubkey, using
     * {@code CMD_SEND_TXT_MSG (0x02)}. This is the standard pubkey-to-pubkey DM that native
     * MeshCore clients understand — unlike the {@code 0xFF01} channel datagram used for the
     * AX.25 tunnel. The recipient must be a contact on this node (firmware looks it up by the
     * first 6 bytes of the pubkey); otherwise the node replies {@code ERR_CODE_NOT_FOUND}.
     *
     * @param pubKeyHex recipient pubkey (>= 12 hex chars; first 6 bytes are used as the prefix)
     * @param text      plain UTF-8 message
     */
    public boolean sendContactTextMessage(String pubKeyHex, String text) {
        if (!connected.get()) {
            return false;
        }
        if (blockedByRadioSilence()) {
            return false;
        }
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        byte[] prefix = pubKeyPrefixBytes(pubKeyHex, 6);
        if (prefix == null) {
            Log.w(TAG, "Native DM aborted: invalid pubkey hex");
            return false;
        }
        byte[] cmd = buildSendTxtMsgCommand(prefix, text.trim(), TXT_TYPE_PLAIN);
        if (cmd == null) {
            return false;
        }
        enqueueCommand(cmd);
        packetRouter.notifyPacketTransmitted();
        Log.d(TAG, "Native MeshCore DM queued pubkeyPrefix="
                + bytesToHex(prefix, 0, prefix.length) + " len=" + text.trim().length());
        return true;
    }

    private static byte[] pubKeyPrefixBytes(String pubKeyHex, int byteCount) {
        if (pubKeyHex == null) {
            return null;
        }
        String hex = pubKeyHex.trim();
        if (hex.length() < byteCount * 2) {
            return null;
        }
        byte[] out = new byte[byteCount];
        try {
            for (int i = 0; i < byteCount; i++) {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    public boolean sendSelfAdvert() {
        if (!connected.get() || blockedByRadioSilence()) {
            return false;
        }
        // Firmware: 1 byte = zero-hop only; 2nd byte 0x01 = flood (mesh-wide discovery).
        enqueueCommand(new byte[]{CMD_SEND_SELF_ADVERT, 0x01});
        Log.i(TAG, "sendSelfAdvert queued (flood)");
        return true;
    }

    /**
     * Flood a MeshCore node-discover request for nearby repeaters (companion
     * {@code CMD_SEND_CONTROL_DATA} / {@code CTL_TYPE_NODE_DISCOVER_REQ}).
     */
    public boolean sendNodeDiscoverRequest() {
        if (!connected.get() || blockedByRadioSilence()) {
            return false;
        }
        byte[] tagBytes = new byte[4];
        new java.security.SecureRandom().nextBytes(tagBytes);
        int tag = ((tagBytes[0] & 0xFF))
                | ((tagBytes[1] & 0xFF) << 8)
                | ((tagBytes[2] & 0xFF) << 16)
                | ((tagBytes[3] & 0xFF) << 24);
        pendingNodeDiscoverTag = tag;
        pendingNodeDiscoverUntilMs = System.currentTimeMillis() + 60_000L;
        byte[] cmd = new byte[11];
        cmd[0] = CMD_SEND_CONTROL_DATA;
        cmd[1] = CTL_TYPE_NODE_DISCOVER_REQ;
        cmd[2] = (byte) (1 << ADV_TYPE_REPEATER);
        System.arraycopy(tagBytes, 0, cmd, 3, 4);
        cmd[7] = cmd[8] = cmd[9] = cmd[10] = 0;
        enqueueCommand(cmd);
        Log.d(TAG, "Node discover request queued tag=" + Integer.toHexString(tag));
        return true;
    }

    public boolean isNodeDiscoverSessionActive() {
        return pendingNodeDiscoverTag != 0
                && System.currentTimeMillis() <= pendingNodeDiscoverUntilMs;
    }

    public boolean setAdvertLatLon(double latitude, double longitude, double altitudeMeters) {
        if (!connected.get()) {
            return false;
        }
        if (Double.isNaN(latitude) || Double.isNaN(longitude)
                || latitude < -90.0 || latitude > 90.0
                || longitude < -180.0 || longitude > 180.0) {
            return false;
        }
        int latE6 = (int) Math.round(latitude * 1_000_000.0);
        int lonE6 = (int) Math.round(longitude * 1_000_000.0);
        int alt = Double.isNaN(altitudeMeters) ? 0 : (int) Math.round(altitudeMeters);
        byte[] out = new byte[13];
        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(CMD_SET_ADVERT_LATLON);
        bb.putInt(latE6);
        bb.putInt(lonE6);
        bb.putInt(alt);
        enqueueCommand(out);
        return true;
    }

    public Boolean getMeshGpsEnabled() {
        return meshGpsEnabled;
    }

    public Boolean getSendPositionWithAdvertEnabled() {
        return sendPositionWithAdvertEnabled;
    }

    public MeshNodeSettings getLatestNodeSettings() {
        return latestNodeSettings;
    }

    public String getSelfPubKeyHex() {
        String v = selfPubKeyHex;
        return v != null ? v : "";
    }

    public MeshLocationFix getLatestSelfLocation() {
        return latestSelfLocation;
    }

    public int getLatestBatteryPercent() {
        return latestBatteryPercent;
    }

    public int getLatestBatteryMv() {
        return latestBatteryMv;
    }

    public void requestBattery() {
        if (!connected.get()) {
            return;
        }
        enqueueCommand(new byte[]{CMD_GET_BATTERY});
        enqueueCommand(new byte[]{CMD_GET_STATS, STATS_TYPE_CORE});
    }

    public static int meshBatteryMvToPercent(int batteryMv) {
        if (batteryMv <= 0) {
            return -1;
        }
        final int minMv = 3300;
        final int maxMv = 4200;
        if (batteryMv <= minMv) {
            return 0;
        }
        if (batteryMv >= maxMv) {
            return 100;
        }
        return Math.round(100f * (batteryMv - minMv) / (maxMv - minMv));
    }

    public void queryMeshGpsEnabled() {
        if (!connected.get()) {
            return;
        }
        enqueueCommand(new byte[]{CMD_GET_GPS_STATE});
    }

    public void setMeshGpsEnabled(boolean enabled) {
        if (!connected.get()) {
            return;
        }
        byte[] txt = ("gps:" + (enabled ? "1" : "0")).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 + txt.length];
        out[0] = CMD_SET_SETTING_TEXT;
        System.arraycopy(txt, 0, out, 1, txt.length);
        enqueueCommand(out);
        // Query immediately after set to refresh authoritative state.
        enqueueCommand(new byte[]{CMD_GET_GPS_STATE});
    }

    public void setSendPositionWithAdvertEnabled(boolean enabled) {
        if (!connected.get()) {
            return;
        }
        byte[] out = new byte[5];
        out[0] = CMD_SET_OTHER_PARAMS;
        out[1] = (byte) (cachedManualAddContacts & 0xFF);
        out[2] = (byte) (cachedTelemetryModes & 0xFF);
        out[3] = (byte) ((enabled ? ADVERT_LOC_SHARE : ADVERT_LOC_NONE) & 0xFF);
        out[4] = (byte) (cachedMultiAcks & 0xFF);
        enqueueCommand(out);
        // Refresh the authoritative state from node self-info.
        enqueueCommand(buildAppStartCommand());
    }

    public boolean setNodeAdvertName(String nodeName) {
        if (!connected.get() || nodeName == null) {
            return false;
        }
        String trimmed = nodeName.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        byte[] nameBytes = trimmed.getBytes(StandardCharsets.UTF_8);
        int maxLen = 31; // firmware stores node_name[32] including null terminator
        int n = Math.min(nameBytes.length, maxLen);
        byte[] out = new byte[1 + n];
        out[0] = CMD_SET_ADVERT_NAME;
        System.arraycopy(nameBytes, 0, out, 1, n);
        enqueueCommand(out);
        enqueueCommand(buildAppStartCommand());
        return true;
    }

    public boolean setRadioParams(double frequencyMHz, double bandwidthKHz, int sf, int cr) {
        if (!connected.get()) {
            return false;
        }
        int freqKHz = (int) Math.round(frequencyMHz * 1000.0);
        int bwHz = (int) Math.round(bandwidthKHz * 1000.0);
        if (freqKHz < 150000 || freqKHz > 2500000
                || bwHz < 7000 || bwHz > 500000
                || sf < 5 || sf > 12
                || cr < 5 || cr > 8) {
            return false;
        }
        byte[] out = new byte[11];
        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(CMD_SET_RADIO_PARAMS);
        bb.putInt(freqKHz);
        bb.putInt(bwHz);
        bb.put((byte) (sf & 0xFF));
        bb.put((byte) (cr & 0xFF));
        enqueueCommand(out);
        enqueueCommand(buildAppStartCommand());
        return true;
    }

    public boolean setRadioTxPowerDbm(int txPowerDbm) {
        if (!connected.get()) {
            return false;
        }
        byte[] out = new byte[2];
        out[0] = CMD_SET_RADIO_TX_POWER;
        out[1] = (byte) txPowerDbm;
        enqueueCommand(out);
        enqueueCommand(buildAppStartCommand());
        return true;
    }

    public void requestSelfInfo() {
        if (!connected.get()) {
            return;
        }
        enqueueCommand(buildAppStartCommand());
    }

    private void runBeforeDisconnectHooks() {
        for (Runnable hook : beforeDisconnectHooks) {
            try {
                hook.run();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected boolean shouldPersistUvProRadioOnConnect() {
        return false;
    }

    @Override
    protected void notifyConnected(BluetoothDevice device) {
        userInitiatedConnect.set(false);
        cancelAutoConnectTimeout();
        cancelPassiveMeshWatch();
        finishMeshBootAutoConnect(true);
        if (device != null && device.getAddress() != null) {
            try {
                BluetoothDeviceRegistry.setMeshConnectTargetAddress(context, device.getAddress());
            } catch (Exception e) {
                Log.w(TAG, "Could not persist last-connected mesh target", e);
            }
        }
        super.notifyConnected(device);
    }

    @Override
    protected void notifyDisconnected(String reason) {
        super.notifyDisconnected(reason);
    }

    @Override
    protected void notifyError(String error) {
        super.notifyError(error);
    }


    private void notifyMeshGpsStateChanged(boolean enabled) {
        for (MeshStateListener l : meshStateListeners) {
            try {
                l.onMeshGpsStateChanged(enabled);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifySendPositionWithAdvertChanged(boolean enabled) {
        for (MeshStateListener l : meshStateListeners) {
            try {
                l.onSendPositionWithAdvertChanged(enabled);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyMeshNodeSettingsUpdated(MeshNodeSettings settings) {
        if (settings == null) {
            return;
        }
        for (MeshStateListener l : meshStateListeners) {
            try {
                l.onMeshNodeSettingsUpdated(settings);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyMeshSelfLocation(MeshLocationFix fix) {
        for (MeshStateListener l : meshStateListeners) {
            try {
                l.onMeshSelfLocationUpdated(fix);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyMeshBatteryUpdated(int batteryMv, int batteryPercent) {
        for (MeshStateListener l : meshStateListeners) {
            try {
                l.onMeshBatteryUpdated(batteryPercent, batteryMv);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyRepeaterAdvert(RepeaterAdvert advert) {
        for (RepeaterAdvertListener l : repeaterAdvertListeners) {
            try {
                l.onRepeaterAdvert(advert);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyMeshAdvert(MeshAdvert advert) {
        for (MeshAdvertListener l : meshAdvertListeners) {
            try {
                l.onMeshAdvert(advert);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyDeviceContactUpdated(MeshDeviceContact contact) {
        for (MeshDeviceContactUpdateListener l : deviceContactUpdateListeners) {
            try {
                l.onDeviceContactUpdated(contact);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyMeshChannelInfo(MeshChannelInfo info) {
        for (MeshChannelListener l : meshChannelListeners) {
            try {
                l.onChannelInfo(info);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyMeshChannelMessage(MeshChannelMessage message) {
        for (MeshChannelListener l : meshChannelListeners) {
            try {
                l.onChannelMessage(message);
            } catch (Exception ignored) {
            }
        }
    }

    private String resolveName(BluetoothDevice device) {
        if (device == null) {
            return "MeshCore";
        }
        String name = device.getName();
        return name != null && !name.trim().isEmpty()
                ? name
                : device.getAddress();
    }

    private void markIoActivity() {
        lastIoActivityMs.set(System.currentTimeMillis());
    }

    private void stopScanInternal() {
        if (bleScanner != null && scanCallback != null) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        scanCallback = null;
    }

    private void closeGattInternal() {
        try {
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
            }
        } catch (Exception ignored) {
        }
        gatt = null;
        rxCharacteristic = null;
        txCharacteristic = null;
    }

    private void connectGattNow(BluetoothDevice device) {
        ioHandler.post(() -> {
            try {
                closeGattInternal();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    gatt = device.connectGatt(context.getApplicationContext(),
                            false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                } else {
                    gatt = device.connectGatt(context.getApplicationContext(),
                            false, gattCallback);
                }
                if (gatt == null) {
                    connecting.set(false);
                    notifyError("BLE connectGatt failed");
                }
            } catch (Exception e) {
                connecting.set(false);
                notifyError("BLE connect failed: " + e.getMessage());
            }
        });
    }

    private void registerBondReceiver() {
        try {
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            context.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent == null || !BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                        return;
                    }
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device == null || pendingBondDevice == null) {
                        return;
                    }
                    if (!device.getAddress().equalsIgnoreCase(pendingBondDevice.getAddress())) {
                        return;
                    }
                    int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                    int prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        notifyError("Pairing complete for " + resolveName(device) + ". Connecting...");
                        pendingBondDevice = null;
                        if (scanPickerSessionActive.get()) {
                            Log.i(TAG, "Bond complete but scan/picker session active — not auto-connecting");
                            connecting.set(false);
                            return;
                        }
                        // Guard against a duplicate connection: if a link is already up or a GATT
                        // is already in flight, don't open a second one (that collision drops it).
                        if (connected.get() || gatt != null) {
                            Log.i(TAG, "Bond complete; connection already active/in-flight — not reconnecting");
                            return;
                        }
                        connecting.set(true);
                        connectGattNow(device);
                    } else if (bondState == BluetoothDevice.BOND_NONE
                            && prevBondState == BluetoothDevice.BOND_BONDING) {
                        notifyError("Pairing failed or canceled for " + resolveName(device));
                        pendingBondDevice = null;
                        connecting.set(false);
                    }
                }
            }, filter);
        } catch (Exception e) {
            Log.w(TAG, "Could not register bond receiver", e);
        }
    }

    private void clearQueues() {
        synchronized (writeQueue) {
            writeQueue.clear();
            writeInFlight = false;
        }
        chunkBuffers.clear();
        binaryChunkBuffers.clear();
    }

    private void enqueueCommand(byte[] cmd) {
        enqueueCommand(cmd, false);
    }

    private void enqueueCommand(byte[] cmd, boolean highPriority) {
        if (cmd == null || cmd.length == 0) {
            return;
        }
        synchronized (writeQueue) {
            if (highPriority) {
                writeQueue.addFirst(cmd);
            } else {
                writeQueue.addLast(cmd);
            }
            if (writeInFlight) {
                return;
            }
            writeInFlight = true;
        }
        ioHandler.post(this::drainWriteQueue);
    }

    private void drainWriteQueue() {
        while (connected.get()) {
            byte[] next;
            synchronized (writeQueue) {
                next = writeQueue.peekFirst();
                if (next == null) {
                    writeInFlight = false;
                    return;
                }
            }
            if (gatt == null || rxCharacteristic == null) {
                synchronized (writeQueue) {
                    writeInFlight = false;
                }
                return;
            }
            rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            rxCharacteristic.setValue(next);
            boolean started = gatt.writeCharacteristic(rxCharacteristic);
            if (!started) {
                synchronized (writeQueue) {
                    writeInFlight = false;
                }
                ioHandler.postDelayed(this::drainWriteQueue, 150L);
                return;
            }
            return;
        }
        synchronized (writeQueue) {
            writeInFlight = false;
        }
    }

    private byte[] buildAppStartCommand() {
        byte[] app = COMPANION_APP_ID.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[8 + app.length];
        out[0] = CMD_APP_START;
        System.arraycopy(app, 0, out, 8, app.length);
        return out;
    }

    private byte[] buildDeviceQueryCommand() {
        return new byte[]{CMD_DEVICE_QUERY, CMD_DEVICE_QUERY_ARG};
    }

    private byte[] buildGetNextMessageCommand() {
        return new byte[]{CMD_GET_NEXT_MSG};
    }

    private byte[] buildGetChannelInfoCommand(int idx) {
        return new byte[]{CMD_GET_CHANNEL, (byte) (idx & 0xFF)};
    }

    private byte[] buildSetChannelCommand(int idx, String name, byte[] secret) {
        byte[] out = new byte[1 + 1 + 32 + 16];
        out[0] = CMD_SET_CHANNEL;
        out[1] = (byte) (idx & 0xFF);
        byte[] nameBytes = name != null ? name.getBytes(StandardCharsets.UTF_8) : new byte[0];
        int nameLen = Math.min(32, nameBytes.length);
        System.arraycopy(nameBytes, 0, out, 2, nameLen);
        if (secret != null) {
            int secLen = Math.min(16, secret.length);
            System.arraycopy(secret, 0, out, 34, secLen);
        }
        return out;
    }

    private byte[] buildSendChannelMessageCommand(int channel, String text) {
        byte[] msg = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(7 + msg.length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SEND_CHANNEL_MSG);
        buf.put((byte) 0x00);
        buf.put((byte) Math.max(0, Math.min(7, channel)));
        buf.putInt((int) (System.currentTimeMillis() / 1000L));
        buf.put(msg);
        return buf.array();
    }

    private byte[] buildSendLoginCommand(byte[] pubKey32, String password) {
        if (pubKey32 == null || pubKey32.length != CONTACT_PUB_KEY_BYTES) {
            return null;
        }
        byte[] pwd = (password != null ? password : "").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + CONTACT_PUB_KEY_BYTES + pwd.length);
        buf.put(CMD_SEND_LOGIN);
        buf.put(pubKey32);
        buf.put(pwd);
        return buf.array();
    }

    private byte[] buildSendTxtMsgCommand(byte[] pubKeyPrefix6, String text, byte txtType) {
        if (pubKeyPrefix6 == null || pubKeyPrefix6.length != 6) {
            return null;
        }
        byte[] msg = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(13 + msg.length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SEND_TXT_MSG);
        buf.put(txtType);
        buf.put((byte) 0x00); // attempt
        buf.putInt((int) (System.currentTimeMillis() / 1000L));
        buf.put(pubKeyPrefix6);
        buf.put(msg);
        return buf.array();
    }

    private byte[] buildSendChannelDataCommand(int channel, int dataType, byte[] payload) {
        int payloadLen = payload != null ? payload.length : 0;
        // Header is command, channel, flood path, and the uint16 data type.
        // An unused trailing byte used to ride along and cut the last character
        // off any chat that needed two channel packets.
        ByteBuffer buf = ByteBuffer.allocate(5 + payloadLen);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SEND_CHANNEL_DATA);
        buf.put((byte) Math.max(0, Math.min(7, channel)));
        buf.put((byte) OUT_PATH_FLOOD);
        buf.put((byte) (dataType & 0xFF));
        buf.put((byte) ((dataType >> 8) & 0xFF));
        if (payloadLen > 0) {
            buf.put(payload);
        }
        return buf.array();
    }

    private int getMeshChannelIndex() {
        return ATAK_CHANNEL_INDEX;
    }

    private void handleCompanionPacket(byte[] pkt) {
        if (pkt == null || pkt.length == 0) return;
        markIoActivity();
        pruneStaleChunks();
        byte t = pkt[0];
        Log.d(TAG, "RX companion pkt type=0x" + Integer.toHexString(t & 0xFF)
                + " len=" + pkt.length);
        if (t == RESP_CODE_CONTACTS_START) {
            if (deviceContactsFetchActive) {
                handleDeviceContactsStart(pkt);
            }
            return;
        }
        if (t == RESP_CODE_CONTACT) {
            MeshDeviceContact parsed = parseDeviceContactPacket(pkt);
            if (parsed != null) {
                if (deviceContactsFetchActive) {
                    pendingDeviceContactsList.add(parsed);
                } else {
                    notifyDeviceContactUpdated(parsed);
                    // Full contact from advert refresh (0x80) — same payload as NEW_ADVERT.
                    dispatchMeshAdvertDiscovery(pkt);
                }
            }
            return;
        }
        if (t == RESP_CODE_END_OF_CONTACTS) {
            if (deviceContactsFetchActive) {
                finishDeviceContactsFetch(true, null);
            }
            return;
        }
        if (t == RESP_CODE_ERR) {
            if (pkt.length >= 2) {
                int err = pkt[1] & 0xFF;
                Log.w(TAG, "Companion ERR code=" + err);
                if (err == ERR_CODE_NOT_FOUND && roomContactPrepareInProgress) {
                    return;
                }
                for (MeshRoomLoginListener l : meshRoomLoginListeners) {
                    try {
                        l.onCompanionCommandError(err);
                    } catch (Exception ignored) {
                    }
                }
            }
            return;
        }
        if (t == PUSH_MESSAGES_WAITING) {
            requestMessageDrain(isRoomPostSyncSessionActive() ? 12 : 3, true);
            return;
        }
        if (t == PUSH_CODE_LOGIN_SUCCESS) {
            handleRoomLoginPush(pkt, true);
            return;
        }
        if (t == PUSH_CODE_LOGIN_FAIL) {
            handleRoomLoginPush(pkt, false);
            return;
        }
        if (t == RESP_NO_MORE_MSGS) {
            return;
        }
        if (t == RESP_SETTING_TEXT) {
            applySettingText(pkt);
            return;
        }
        if (t == RESP_SELF_INFO) {
            logSelfInfo(pkt);
            return;
        }
        if (t == RESP_BATTERY) {
            applyBatteryInfo(pkt);
            return;
        }
        if (t == RESP_CODE_STATS) {
            applyStatsBattery(pkt);
            return;
        }
        if (t == RESP_DEVICE_INFO) {
            logDeviceInfo(pkt);
            return;
        }
        if (t == RESP_CHANNEL_INFO) {
            applyChannelInfo(pkt);
            return;
        }
        if (t == RESP_CHANNEL_DATA_RECV) {
            handleChannelData(pkt);
            enqueueCommand(buildGetNextMessageCommand());
            return;
        }
        if (t == PUSH_CODE_CONTROL_DATA) {
            handleNodeDiscoverControlData(pkt);
            enqueueCommand(buildGetNextMessageCommand());
            return;
        }
        if (t == PUSH_CODE_LOG_RX_DATA) {
            handleLogRxData(pkt);
            // Some firmware builds emit LOG_RX_DATA first, then queue follow-up companion frames
            // (including advert/contact refreshes). Prompt an immediate drain.
            enqueueCommand(buildGetNextMessageCommand());
            return;
        }
        if (t == PUSH_CODE_SEND_CONFIRMED) {
            handleSendConfirmed(pkt);
            return;
        }
        if (t == PUSH_CODE_NEW_ADVERT || t == PUSH_CODE_ADVERT) {
            if (t == PUSH_CODE_ADVERT) {
                requestFullContactForAdvertRefresh(pkt);
            }
            dispatchMeshAdvertDiscovery(pkt);
            return;
        }

        String message = null;
        if (t == RESP_CHANNEL_MSG) {
            message = extractChannelText(pkt, false);
        } else if (t == RESP_CHANNEL_MSG_V3) {
            message = extractChannelText(pkt, true);
        } else if (t == RESP_CONTACT_MSG) {
            MeshContactInboundMessage inbound = parseContactInboundMessage(pkt, false);
            if (inbound != null) {
                Log.i(TAG, "Contact msg RX prefix=" + inbound.senderPubKeyPrefixHex
                        + " type=0x" + Integer.toHexString(inbound.txtType)
                        + " len=" + (inbound.text != null ? inbound.text.length() : 0));
            }
            if (inbound != null && inbound.text != null && !inbound.text.trim().isEmpty()) {
                deliverContactInboundMessage(inbound);
            }
            enqueueCommand(buildGetNextMessageCommand());
            return;
        } else if (t == RESP_CONTACT_MSG_V3) {
            MeshContactInboundMessage inbound = parseContactInboundMessage(pkt, true);
            if (inbound != null) {
                Log.i(TAG, "Contact msg v3 RX prefix=" + inbound.senderPubKeyPrefixHex
                        + " type=0x" + Integer.toHexString(inbound.txtType)
                        + " len=" + (inbound.text != null ? inbound.text.length() : 0)
                        + " ts=" + inbound.senderTimestampSec);
            }
            if (inbound != null && inbound.text != null && !inbound.text.trim().isEmpty()) {
                deliverContactInboundMessage(inbound);
            }
            enqueueCommand(buildGetNextMessageCommand());
            return;
        }
        if (message != null) {
            int envPathLen = 0;
            if (t == RESP_CHANNEL_MSG || t == RESP_CHANNEL_MSG_V3) {
                ChannelMessageMeta meta = extractChannelMessageMeta(pkt, t == RESP_CHANNEL_MSG_V3);
                String statusText = extractChannelStatusText(message);
                notifyMeshChannelMessage(new MeshChannelMessage(
                        meta.channelIndex,
                        message,
                        System.currentTimeMillis(),
                        false,
                        statusText,
                        meta.snrQuarterDb,
                        meta.pathLen,
                        meta.senderTimestampSec));
                envPathLen = meta.pathLen != null ? meta.pathLen : 0;
            }
            String routed = extractRoutableEnvelope(message);
            if (routed != null) {
                handleMeshMessage(routed, envPathLen);
            }
            enqueueCommand(buildGetNextMessageCommand());
        }
    }

    private void deliverContactInboundMessage(MeshContactInboundMessage inbound) {
        notifyNativeContactMessage(inbound);
        if (inbound.txtType == TXT_TYPE_SIGNED_PLAIN) {
            if (isRoomPostSyncSessionActive()) {
                extendRoomPostSyncSession();
                scheduleRoomPostFollowUpDrains();
            }
            return;
        }
        if (inbound.txtType != TXT_TYPE_CLI_DATA) {
            packetRouter.routeNativeMeshDm(inbound.senderPubKeyPrefixHex, inbound.text.trim());
        }
    }

    @Nullable
    private MeshContactInboundMessage parseContactInboundMessage(byte[] pkt, boolean v3) {
        if (pkt == null) {
            return null;
        }
        int prefixOff = v3 ? 4 : 1;
        int txtTypeIndex = v3 ? 11 : 8;
        int timestampOff = v3 ? 12 : 9;
        int textOff = v3 ? 16 : 13;
        if (pkt.length < prefixOff + 6 || pkt.length < txtTypeIndex + 1) {
            return null;
        }
        String senderPrefix = bytesToHex(pkt, prefixOff, 6);
        byte txtType = pkt[txtTypeIndex];
        int senderTimestampSec = 0;
        if (pkt.length >= timestampOff + 4) {
            senderTimestampSec = (pkt[timestampOff] & 0xFF)
                    | ((pkt[timestampOff + 1] & 0xFF) << 8)
                    | ((pkt[timestampOff + 2] & 0xFF) << 16)
                    | ((pkt[timestampOff + 3] & 0xFF) << 24);
        }
        String authorPrefix = null;
        if (txtType == TXT_TYPE_SIGNED_PLAIN && pkt.length >= textOff + 4) {
            authorPrefix = bytesToHex(pkt, textOff, 4);
            textOff += 4;
        }
        if (pkt.length <= textOff) {
            return null;
        }
        String text = new String(pkt, textOff, pkt.length - textOff, StandardCharsets.UTF_8);
        return new MeshContactInboundMessage(senderPrefix, text, txtType & 0xFF,
                senderTimestampSec, authorPrefix);
    }

    private void handleRoomLoginPush(byte[] pkt, boolean success) {
        String prefix = null;
        int permissions = 0;
        if (pkt != null && pkt.length >= 8) {
            permissions = pkt[1] & 0xFF;
            prefix = bytesToHex(pkt, 2, 6);
        }
        Log.i(TAG, "Room login " + (success ? "OK" : "FAIL") + " prefix=" + prefix
                + " perm=" + permissions);
        if (prefix == null) {
            return;
        }
        for (MeshRoomLoginListener l : meshRoomLoginListeners) {
            try {
                if (success) {
                    l.onRoomLoginSuccess(prefix, permissions);
                } else {
                    l.onRoomLoginFail(prefix);
                }
            } catch (Exception ignored) {
            }
        }
        if (success) {
            beginRoomPostSyncSession();
            requestMessageDrain(4, true);
        }
    }

    public void scheduleRoomPostSyncFollowUpDrains() {
        scheduleRoomPostFollowUpDrains();
    }

    private void scheduleRoomPostFollowUpDrains() {
        if (ioHandler == null) {
            return;
        }
        long[] delays = {2000L, 5000L, 10000L, 20000L, 35000L, 50000L, 75000L, 120000L};
        for (long delay : delays) {
            ioHandler.postDelayed(() -> {
                if (connected.get() && isRoomPostSyncSessionActive()) {
                    requestMessageDrain(3, true);
                }
            }, delay);
        }
    }

    /** Sender pubkey prefix (6 bytes) from a contact-message frame: bytes 1..6 (v1) or 4..9 (v3). */
    private String extractContactSenderPubKeyPrefix(byte[] pkt, boolean v3) {
        int off = v3 ? 4 : 1;
        if (pkt == null || pkt.length < off + 6) {
            return null;
        }
        return bytesToHex(pkt, off, 6);
    }

    private MeshAdvert parseMeshAdvert(byte[] pkt) {
        // Required fields are within [1..143]; accept shorter variants from firmware forks.
        if (pkt == null || pkt.length < 144) {
            return null;
        }
        try {
            int type = pkt[33] & 0xFF;
            String pubKeyHex = bytesToHex(pkt, 1, 32);
            if (pubKeyHex.isEmpty()) {
                return null;
            }

            String rawName = new String(pkt, 100, 32, StandardCharsets.UTF_8);
            int nul = rawName.indexOf('\0');
            String name = (nul >= 0 ? rawName.substring(0, nul) : rawName).trim();
            if (name.isEmpty()) {
                name = "Mesh Repeater";
            }

            ByteBuffer bb = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN);
            long tsSec = ((long) bb.getInt(132)) & 0xFFFFFFFFL;
            int latE6 = bb.getInt(136);
            int lonE6 = bb.getInt(140);
            double lat = latE6 / 1_000_000.0;
            double lon = lonE6 / 1_000_000.0;
            boolean hasPosition = !(latE6 == 0 && lonE6 == 0);
            return new MeshAdvert(type, pubKeyHex, name, tsSec, lat, lon, hasPosition);
        } catch (Exception e) {
            Log.w(TAG, "Mesh advert parse failed", e);
            return null;
        }
    }

    private RepeaterAdvert repeaterAdvertFromMesh(MeshAdvert advert) {
        return new RepeaterAdvert(
                advert.pubKeyHex,
                advert.name,
                advert.advertTimestampSec,
                advert.latitude,
                advert.longitude,
                advert.hasPosition);
    }

    private void dispatchMeshAdvertDiscovery(byte[] pkt) {
        MeshAdvert meshAdvert = parseMeshAdvert(pkt);
        if (meshAdvert == null) {
            return;
        }
        if (!meshAdvert.isRepeater()) {
            maybeToastNodeDiscovery(meshAdvert);
        }
        notifyMeshAdvert(meshAdvert);
        if (meshAdvert.advertType == ADV_TYPE_ROOM
                && meshAdvert.name != null && !meshAdvert.name.trim().isEmpty()) {
            notifyDeviceContactUpdated(new MeshDeviceContact(
                    meshAdvert.pubKeyHex, ADV_TYPE_ROOM, 0, 0, meshAdvert.name.trim(),
                    (int) meshAdvert.advertTimestampSec,
                    meshAdvert.latitude, meshAdvert.longitude, 0));
        }
        if (meshAdvert.isRepeater()) {
            RepeaterAdvert advert = repeaterAdvertFromMesh(meshAdvert);
            maybeToastRepeaterDiscovery(advert);
            notifyRepeaterAdvert(advert);
        }
    }

    private void requestFullContactForAdvertRefresh(byte[] pkt) {
        if (pkt == null || pkt.length < 33) {
            return;
        }
        try {
            String pubKeyHex = bytesToHex(pkt, 1, 32);
            if (pubKeyHex.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            Long last = contactQueryThrottleMsByPubKey.get(pubKeyHex);
            if (last != null && (now - last) < 1500L) {
                return;
            }
            contactQueryThrottleMsByPubKey.put(pubKeyHex, now);
            enqueueCommand(buildGetContactByKeyCommand(pkt));
            Log.d(TAG, "Advert refresh 0x80 → requesting full contact for pubkey=" + pubKeyHex);
        } catch (Exception e) {
            Log.w(TAG, "Failed to request full contact for advert refresh", e);
        }
    }

    private void handleNodeDiscoverControlData(byte[] pkt) {
        if (pkt == null || pkt.length < 4 + 6 + 32) {
            return;
        }
        if (!isNodeDiscoverSessionActive()) {
            return;
        }
        int payloadOff = 4;
        int typeByte = pkt[payloadOff] & 0xFF;
        if ((typeByte & 0xF0) != (CTL_TYPE_NODE_DISCOVER_RESP & 0xFF)) {
            return;
        }
        if ((typeByte & 0x0F) != ADV_TYPE_REPEATER) {
            return;
        }
        int tagOff = payloadOff + 2;
        int tag = (pkt[tagOff] & 0xFF)
                | ((pkt[tagOff + 1] & 0xFF) << 8)
                | ((pkt[tagOff + 2] & 0xFF) << 16)
                | ((pkt[tagOff + 3] & 0xFF) << 24);
        if (tag != pendingNodeDiscoverTag) {
            return;
        }
        int pubOff = tagOff + 4;
        if (pkt.length < pubOff + 32) {
            return;
        }
        byte[] contactFrame = new byte[33];
        contactFrame[0] = PUSH_CODE_ADVERT;
        System.arraycopy(pkt, pubOff, contactFrame, 1, 32);
        String pubKeyHex = bytesToHex(contactFrame, 1, 32);
        long now = System.currentTimeMillis();
        Long last = contactQueryThrottleMsByPubKey.get(pubKeyHex);
        if (last != null && (now - last) < 1500L) {
            return;
        }
        contactQueryThrottleMsByPubKey.put(pubKeyHex, now);
        byte[] cmd = buildGetContactByKeyCommand(contactFrame);
        if (cmd != null) {
            enqueueCommand(cmd);
            Log.d(TAG, "Node discover resp → requesting contact pubkey=" + pubKeyHex);
        }
    }

    private void maybeToastRepeaterDiscovery(RepeaterAdvert advert) {
        if (advert == null || context == null) {
            return;
        }
        // Repeaters stay on the map for their cache TTL. The old key included the
        // advert timestamp and lived only in this process, so every reboot toasted
        // "new repeater" for ones already saved.
        if (com.uvpro.plugin.UVProMapComponent.isKnownMeshRepeater(advert.pubKeyHex)) {
            return;
        }
        String dedupKey = advert.pubKeyHex != null
                ? advert.pubKeyHex.trim().toUpperCase(java.util.Locale.US) : "";
        if (dedupKey.isEmpty()) {
            return;
        }
        Long previous = repeaterToastDedupByPubKeyTs.putIfAbsent(dedupKey, advert.advertTimestampSec);
        if (previous != null) {
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                String text = advert.hasValidPosition()
                        ? "New repeater discovered, #" + advert.name
                        : "New repeater discovered, #" + advert.name;
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        });
    }

    private void maybeToastNodeDiscovery(MeshAdvert advert) {
        if (advert == null || context == null) {
            return;
        }
        String dedupKey = advert.pubKeyHex + ":" + advert.advertTimestampSec;
        Long previous = nodeToastDedupByPubKeyTs.putIfAbsent(dedupKey, advert.advertTimestampSec);
        if (previous != null) {
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                String name = advert.name != null ? advert.name.trim() : "";
                if (name.isEmpty()) {
                    name = "Node";
                }
                Toast.makeText(context,
                        "New Node Discovered-#" + name,
                        Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        });
    }

    private static String bytesToHex(byte[] src, int offset, int len) {
        if (src == null || len <= 0 || offset < 0 || offset + len > src.length) {
            return "";
        }
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[len * 2];
        int j = 0;
        for (int i = offset; i < offset + len; i++) {
            int v = src[i] & 0xFF;
            out[j++] = hex[v >>> 4];
            out[j++] = hex[v & 0x0F];
        }
        return new String(out);
    }

    private byte[] buildGetContactByKeyCommand(byte[] advertFrame) {
        byte[] out = new byte[1 + 32];
        out[0] = CMD_GET_CONTACT_BY_KEY;
        System.arraycopy(advertFrame, 1, out, 1, 32);
        return out;
    }

    private void applySelfInfoOtherParams(byte[] pkt) {
        if (pkt == null) {
            return;
        }
        // Some firmware builds include 3 bytes (advType/tx/maxTx) before pubkey, some do not.
        int[][] candidates = new int[][]{
                {41, 42, 43, 44}, // companion_radio upstream layout
                {38, 39, 40, 41}  // legacy/trimmed layout
        };
        for (int[] c : candidates) {
            int multiIdx = c[0];
            int advertIdx = c[1];
            int telemetryIdx = c[2];
            int manualIdx = c[3];
            if (pkt.length <= manualIdx) {
                continue;
            }
            int advertPolicy = pkt[advertIdx] & 0xFF;
            if (advertPolicy < ADVERT_LOC_NONE || advertPolicy > 2) {
                continue;
            }
            cachedMultiAcks = pkt[multiIdx] & 0xFF;
            cachedTelemetryModes = pkt[telemetryIdx] & 0xFF;
            cachedManualAddContacts = pkt[manualIdx] & 0xFF;
            boolean enabled = advertPolicy != ADVERT_LOC_NONE;
            if (sendPositionWithAdvertEnabled == null
                    || sendPositionWithAdvertEnabled.booleanValue() != enabled) {
                sendPositionWithAdvertEnabled = enabled;
                notifySendPositionWithAdvertChanged(enabled);
            } else {
                sendPositionWithAdvertEnabled = enabled;
            }
            return;
        }
    }

    private void applySelfInfoNodeSettings(byte[] pkt) {
        if (pkt == null) {
            return;
        }
        // Self-info layout differs across firmware branches; probe known offsets.
        int[][] candidates = new int[][]{
                {48, 52, 56, 57, 2, 3, 58}, // companion_radio upstream layout
                {45, 49, 53, 54, 1, 2, 55}  // legacy layout without advType
        };
        for (int[] c : candidates) {
            int freqIdx = c[0];
            int bwIdx = c[1];
            int sfIdx = c[2];
            int crIdx = c[3];
            int txIdx = c[4];
            int maxTxIdx = c[5];
            int nameIdx = c[6];
            if (pkt.length <= crIdx || pkt.length <= txIdx || pkt.length <= maxTxIdx
                    || pkt.length < nameIdx) {
                continue;
            }
            ByteBuffer bb = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN);
            long freqKHz = ((long) bb.getInt(freqIdx)) & 0xFFFFFFFFL;
            long bwHz = ((long) bb.getInt(bwIdx)) & 0xFFFFFFFFL;
            int sf = pkt[sfIdx] & 0xFF;
            int cr = pkt[crIdx] & 0xFF;
            int txPower = (int) pkt[txIdx];
            int maxTxPower = pkt[maxTxIdx] & 0xFF;
            if (freqKHz < 150000 || freqKHz > 2500000
                    || bwHz < 7000 || bwHz > 500000
                    || sf < 5 || sf > 12
                    || cr < 5 || cr > 8) {
                continue;
            }
            String name = "";
            if (pkt.length > nameIdx) {
                name = new String(pkt, nameIdx, pkt.length - nameIdx, StandardCharsets.UTF_8).trim();
            }
            MeshNodeSettings settings = new MeshNodeSettings(
                    name,
                    freqKHz / 1000.0,
                    bwHz / 1000.0,
                    sf,
                    cr,
                    txPower,
                    maxTxPower,
                    System.currentTimeMillis());
            latestNodeSettings = settings;
            notifyMeshNodeSettingsUpdated(settings);
            return;
        }
    }

    private void logSelfInfo(byte[] pkt) {
        try {
            if (pkt.length < 55) {
                Log.d(TAG, "SELF info short len=" + pkt.length);
                return;
            }
            applySelfInfoOtherParams(pkt);
            applySelfInfoNodeSettings(pkt);
            String selfPub = bytesToHex(pkt, 1, 32);
            if (!selfPub.isEmpty()) {
                selfPubKeyHex = selfPub;
            }
            ByteBuffer bb = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN);
            int latE6 = bb.getInt(36);
            int lonE6 = bb.getInt(40);
            long freqHz = ((long) bb.getInt(48)) & 0xFFFFFFFFL;
            long bwHz = ((long) bb.getInt(52)) & 0xFFFFFFFFL;
            int sf = pkt[56] & 0xFF;
            int cr = pkt[57] & 0xFF;
            String node = "";
            if (pkt.length > 58) {
                node = new String(pkt, 58, pkt.length - 58, StandardCharsets.UTF_8).trim();
            }
            double lat = latE6 / 1_000_000.0;
            double lon = lonE6 / 1_000_000.0;
            Log.i(TAG, "SELF info node='" + node + "' lat=" + lat + " lon=" + lon
                    + " freqHz=" + freqHz + " bwHz=" + bwHz + " sf=" + sf + " cr=" + cr);
            MeshLocationFix fix = new MeshLocationFix(lat, lon, System.currentTimeMillis(), node);
            if (fix.isValid()) {
                latestSelfLocation = fix;
                notifyMeshSelfLocation(fix);
            }
        } catch (Exception e) {
            Log.w(TAG, "SELF info parse failed", e);
        }
    }

    private void applyBatteryInfo(byte[] pkt) {
        if (pkt == null || pkt.length < 3) {
            return;
        }
        int batteryMv = (pkt[1] & 0xFF) | ((pkt[2] & 0xFF) << 8);
        publishBatteryReading(batteryMv, "PACKET_BATTERY");
    }

    private void applyStatsBattery(byte[] pkt) {
        if (pkt == null || pkt.length < 4 || (pkt[1] & 0xFF) != STATS_TYPE_CORE) {
            return;
        }
        int batteryMv = (pkt[2] & 0xFF) | ((pkt[3] & 0xFF) << 8);
        publishBatteryReading(batteryMv, "STATS_CORE");
    }

    private void publishBatteryReading(int batteryMv, String source) {
        int batteryPercent = meshBatteryMvToPercent(batteryMv);
        if (batteryPercent < 0) {
            return;
        }
        latestBatteryMv = batteryMv;
        latestBatteryPercent = batteryPercent;
        Log.d(TAG, source + " mv=" + batteryMv + " pct=" + batteryPercent);
        notifyMeshBatteryUpdated(batteryMv, batteryPercent);
    }

    private void logDeviceInfo(byte[] pkt) {
        try {
            if (pkt.length < 4) {
                return;
            }
            int fwCode = pkt[1] & 0xFF;
            int maxContactsHalf = pkt[2] & 0xFF;
            int maxChannels = pkt[3] & 0xFF;
            Log.i(TAG, "DEVICE info fwCode=" + fwCode + " maxChannels=" + maxChannels
                    + " maxContacts=" + (maxContactsHalf * 2));
        } catch (Exception e) {
            Log.w(TAG, "DEVICE info parse failed", e);
        }
    }

    private void applySettingText(byte[] pkt) {
        if (pkt == null || pkt.length < 2) {
            return;
        }
        try {
            String text = new String(pkt, 1, pkt.length - 1, StandardCharsets.UTF_8).trim();
            Log.d(TAG, "SETTING text: " + text);
            if (text.startsWith("gps:")) {
                boolean enabled = text.endsWith("1")
                        || text.equalsIgnoreCase("gps:on")
                        || text.equalsIgnoreCase("gps:true");
                meshGpsEnabled = enabled;
                notifyMeshGpsStateChanged(enabled);
                return;
            }
            if (text.startsWith("adloc:") || text.startsWith("advert_loc:")) {
                boolean enabled = text.endsWith("1")
                        || text.endsWith("2")
                        || text.equalsIgnoreCase("adloc:on")
                        || text.equalsIgnoreCase("adloc:true");
                sendPositionWithAdvertEnabled = enabled;
                notifySendPositionWithAdvertChanged(enabled);
            }
        } catch (Exception ignored) {
        }
    }

    private String extractRoutableEnvelope(String text) {
        if (text == null) {
            return null;
        }
        int p = text.indexOf(ENV_PREFIX);
        if (p < 0) {
            return null;
        }
        return text.substring(p).trim();
    }

    private void applyChannelInfo(byte[] pkt) {
        if (pkt == null || pkt.length < 50) {
            return;
        }
        int idx = pkt[1] & 0xFF;
        if (idx < 0 || idx > 7) {
            return;
        }
        String raw = new String(pkt, 2, 32, StandardCharsets.UTF_8);
        int nul = raw.indexOf('\0');
        String name = (nul >= 0 ? raw.substring(0, nul) : raw).trim();
        meshChannelNamesByIndex.put(idx, name);
        if (pkt.length >= 50) {
            byte[] secret = new byte[16];
            System.arraycopy(pkt, 34, secret, 0, 16);
            channelSecretsByIndex.put(idx, secret);
        }
        notifyMeshChannelInfo(new MeshChannelInfo(idx, name));
        if (idx == ATAK_CHANNEL_INDEX) {
            Log.i(TAG, "ATAK channel slot " + idx + " name='" + name + "'");
        }
    }

    private void handleChannelData(byte[] pkt) {
        if (pkt == null || pkt.length < 9) {
            return;
        }
        int dataType = ((pkt[7] & 0xFF) << 8) | (pkt[6] & 0xFF);
        int dataLen = pkt[8] & 0xFF;
        int available = pkt.length - 9;
        if (available <= 0 || dataLen <= 0) {
            return;
        }
        int copyLen = Math.min(dataLen, available);
        if (dataType != ATAK_DATA_TYPE_AX25 && dataType != ATAK_DATA_TYPE_RAW) {
            return;
        }
        byte[] raw = Arrays.copyOfRange(pkt, 9, 9 + copyLen);
        int marker = raw[0] & 0xFF;
        if (marker == MESH_AX25_SINGLE || marker == MESH_AX25_PART) {
            int pathLen = pkt[5] & 0xFF;
            if (pathLen == 0xFF) {
                pathLen = 0;
            }
            handleBinaryAx25Payload(raw, pathLen);
            return;
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        String routed = extractRoutableEnvelope(text);
        if (routed != null) {
            handleMeshMessage(routed, 0);
        }
    }

    /**
     * Mesh channel payload is raw AX.25. {@code 0xB1} is one frame. {@code 0xB2} is a slice
     * of a frame that did not fit in one channel packet. Text envelopes that start with {@code UVAX1|}
     * stay on the old path so a radio still running that build can be heard.
     */
    private void handleBinaryAx25Payload(byte[] payload, int pathLen) {
        if (payload == null || payload.length < 2) {
            return;
        }
        int marker = payload[0] & 0xFF;
        if (marker == MESH_AX25_SINGLE) {
            deliverAssembledAx25(Arrays.copyOfRange(payload, 1, payload.length), pathLen);
            return;
        }
        if (marker != MESH_AX25_PART || payload.length <= MESH_AX25_PART_HEADER) {
            return;
        }
        int msgId = (payload[1] & 0xFF) | ((payload[2] & 0xFF) << 8);
        int seq = payload[3] & 0xFF;
        int total = payload[4] & 0xFF;
        if (total < 2 || seq < 1 || seq > total) {
            return;
        }
        byte[] slice = Arrays.copyOfRange(payload, MESH_AX25_PART_HEADER, payload.length);
        ChunkAccumulator acc = binaryChunkBuffers.get(msgId);
        if (acc == null || acc.total != total) {
            acc = new ChunkAccumulator(total);
            binaryChunkBuffers.put(msgId, acc);
        }
        acc.parts.put(seq, slice);
        acc.lastUpdateMs = System.currentTimeMillis();
        if (acc.parts.size() < total) {
            scheduleIncompleteChunkDrain();
            return;
        }
        int len = 0;
        for (int i = 1; i <= total; i++) {
            byte[] part = acc.parts.get(i);
            if (part == null) {
                return;
            }
            len += part.length;
        }
        byte[] ax25 = new byte[len];
        int off = 0;
        for (int i = 1; i <= total; i++) {
            byte[] part = acc.parts.get(i);
            System.arraycopy(part, 0, ax25, off, part.length);
            off += part.length;
        }
        binaryChunkBuffers.remove(msgId);
        deliverAssembledAx25(ax25, pathLen);
    }

    private void deliverAssembledAx25(byte[] ax25, int pathLen) {
        if (ax25 == null || ax25.length == 0) {
            return;
        }
        for (RawDataListener listener : rawDataListeners) {
            try {
                if (listener.onRawBytes(ax25)) {
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        if (packetRouter != null) {
            packetRouter.routeIncoming(ax25, pathLen);
        }
    }

    private void handleSendConfirmed(byte[] pkt) {
        long tripMs = 0L;
        try {
            if (pkt != null && pkt.length >= 9) {
                tripMs = ((long) ByteBuffer.wrap(pkt, 5, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).getInt()) & 0xffffffffL;
            }
        } catch (Exception ignored) {
            tripMs = 0L;
        }
        PendingChannelText pending = null;
        synchronized (pendingChannelTextSends) {
            while (!pendingChannelTextSends.isEmpty()) {
                PendingChannelText first = pendingChannelTextSends.removeFirst();
                if ((System.currentTimeMillis() - first.queuedAtMs) <= 120_000L) {
                    pending = first;
                    break;
                }
            }
        }
        if (pending != null) {
            String status = tripMs > 0L
                    ? ("heard (repeat count pending, ack " + tripMs + "ms)")
                    : "heard (repeat count pending)";
            notifyMeshChannelMessage(new MeshChannelMessage(
                    pending.channelIndex,
                    pending.text,
                    System.currentTimeMillis(),
                    true,
                    status,
                    null,
                    null,
                    null));
            Log.d(TAG, "TX confirm ch=" + pending.channelIndex + " tripMs=" + tripMs);
        } else {
            Log.d(TAG, "TX confirm with no pending channel text");
        }
    }

    private void handleLogRxData(byte[] pkt) {
        if (pkt == null || pkt.length < 4) {
            return;
        }
        Integer repeats = extractRepeatsFromLogRawPacket(pkt, 3, pkt.length - 3);
        if (repeats == null) {
            return;
        }
        int snrQ = (int) pkt[1];
        PendingChannelText pending = null;
        synchronized (pendingChannelTextSends) {
            long now = System.currentTimeMillis();
            while (!pendingChannelTextSends.isEmpty()) {
                PendingChannelText first = pendingChannelTextSends.peekFirst();
                if (first == null) {
                    pendingChannelTextSends.removeFirst();
                    continue;
                }
                if ((now - first.queuedAtMs) > 20_000L) {
                    pendingChannelTextSends.removeFirst();
                    continue;
                }
                pending = pendingChannelTextSends.removeFirst();
                break;
            }
        }
        if (pending == null) {
            return;
        }
        String status = "heard " + Math.max(0, repeats) + " repeats";
        notifyMeshChannelMessage(new MeshChannelMessage(
                pending.channelIndex,
                pending.text,
                System.currentTimeMillis(),
                true,
                status,
                snrQ,
                repeats,
                null));
        Log.d(TAG, "TX log-rx confirm ch=" + pending.channelIndex + " status=" + status
                + " snrQ=" + snrQ);
    }

    private Integer extractRepeatsFromLogRawPacket(byte[] src, int offset, int len) {
        if (src == null || len <= 1 || offset < 0 || (offset + len) > src.length) {
            return null;
        }
        // Raw packet blob is mesh::Packet::writeTo(): [header][optional transport(4)][path_len][path...][payload...]
        // Try both common layouts (with/without transport codes).
        int[] pathLenOffsets = {1, 5};
        for (int pathOff : pathLenOffsets) {
            if (len <= pathOff) {
                continue;
            }
            int idx = offset + pathOff;
            int pathLen = src[idx] & 0xFF;
            int hashCount = pathLen & 0x3F;
            int hashSize = ((pathLen >> 6) & 0x03) + 1;
            int pathBytes = hashCount * hashSize;
            if (hashCount > 63) {
                continue;
            }
            if (pathBytes < 0 || pathBytes > 64) {
                continue;
            }
            if ((pathOff + 1 + pathBytes) > len) {
                continue;
            }
            return hashCount;
        }
        return null;
    }

    private String extractChannelText(byte[] pkt, boolean v3) {
        int off = v3 ? 11 : 8;
        if (pkt.length < off) return null;
        return new String(pkt, off, pkt.length - off, StandardCharsets.UTF_8);
    }

    private static final class ChannelMessageMeta {
        final int channelIndex;
        final Integer snrQuarterDb;
        final Integer pathLen;
        final Integer senderTimestampSec;

        ChannelMessageMeta(int channelIndex, Integer snrQuarterDb, Integer pathLen,
                           Integer senderTimestampSec) {
            this.channelIndex = channelIndex;
            this.snrQuarterDb = snrQuarterDb;
            this.pathLen = pathLen;
            this.senderTimestampSec = senderTimestampSec;
        }
    }

    private ChannelMessageMeta extractChannelMessageMeta(byte[] pkt, boolean v3) {
        if (pkt == null) {
            return new ChannelMessageMeta(-1, null, null, null);
        }
        try {
            if (v3) {
                if (pkt.length < 11) {
                    return new ChannelMessageMeta(-1, null, null, null);
                }
                int snrQ = (int) pkt[1];
                int idx = pkt[4] & 0xFF;   // expected v3 layout
                int path = pkt[5] & 0xFF;
                int ts = ByteBuffer.wrap(pkt, 7, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                // Firmware variants can occasionally shift this layout.
                // Fallback to legacy-style offsets if parsed index is invalid.
                if (idx < 0 || idx > 7) {
                    int altIdx = pkt[1] & 0xFF;
                    int altPath = pkt.length > 2 ? (pkt[2] & 0xFF) : 0xFF;
                    Integer altTs = null;
                    if (pkt.length >= 8) {
                        altTs = ByteBuffer.wrap(pkt, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    }
                    if (altIdx >= 0 && altIdx <= 7) {
                        idx = altIdx;
                        path = altPath;
                        ts = altTs != null ? altTs : ts;
                    }
                }
                return new ChannelMessageMeta(
                        (idx >= 0 && idx <= 7) ? idx : -1,
                        snrQ,
                        path == 0xFF ? null : path,
                        ts);
            } else {
                if (pkt.length < 8) {
                    return new ChannelMessageMeta(-1, null, null, null);
                }
                int idx = pkt[1] & 0xFF;
                int path = pkt[2] & 0xFF;
                int ts = ByteBuffer.wrap(pkt, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                return new ChannelMessageMeta(
                        (idx >= 0 && idx <= 7) ? idx : -1,
                        null,
                        path == 0xFF ? null : path,
                        ts);
            }
        } catch (Exception ignored) {
            return new ChannelMessageMeta(-1, null, null, null);
        }
    }

    private String extractChannelStatusText(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(java.util.Locale.US);
        int heard = lower.indexOf("heard");
        int repeat = lower.indexOf("repeat");
        if (heard >= 0 && repeat > heard) {
            int end = Math.min(text.length(), repeat + "repeat".length() + 3);
            return text.substring(heard, end).trim();
        }
        return "";
    }

    private String extractContactText(byte[] pkt, boolean v3) {
        int txtTypeIndex = v3 ? 11 : 8;
        int off = v3 ? 16 : 13;
        if (pkt.length < off) return null;
        byte txtType = pkt[txtTypeIndex];
        if (txtType == 2) off += 4;
        if (pkt.length < off) return null;
        return new String(pkt, off, pkt.length - off, StandardCharsets.UTF_8);
    }

    private void notifyNativeContactMessage(MeshContactInboundMessage message) {
        for (MeshNativeDmListener l : meshNativeDmListeners) {
            try {
                l.onNativeContactMessage(message);
            } catch (Exception ignored) {
            }
        }
    }

    private void handleDeviceContactsStart(byte[] pkt) {
        int total = 0;
        if (pkt != null && pkt.length >= 5) {
            total = (pkt[1] & 0xFF) | ((pkt[2] & 0xFF) << 8)
                    | ((pkt[3] & 0xFF) << 16) | ((pkt[4] & 0xFF) << 24);
        }
        pendingDeviceContactsList.clear();
        Log.d(TAG, "Device contacts sync started total=" + total);
    }

    private MeshDeviceContact parseDeviceContactPacket(byte[] pkt) {
        int minLen = 1 + CONTACT_PUB_KEY_BYTES + 3 + CONTACT_PATH_BYTES + CONTACT_NAME_BYTES;
        if (pkt == null || pkt.length < minLen) {
            return null;
        }
        try {
            int i = 1;
            String pubKeyHex = bytesToHex(pkt, i, CONTACT_PUB_KEY_BYTES);
            i += CONTACT_PUB_KEY_BYTES;
            int type = pkt[i++] & 0xFF;
            int flags = pkt[i++] & 0xFF;
            int outPathLen = pkt[i++] & 0xFF;
            i += CONTACT_PATH_BYTES;
            String nameRaw = new String(pkt, i, CONTACT_NAME_BYTES, StandardCharsets.UTF_8);
            int nul = nameRaw.indexOf('\0');
            String name = (nul >= 0 ? nameRaw.substring(0, nul) : nameRaw).trim();
            i += CONTACT_NAME_BYTES;
            int lastAdvertTs = 0;
            double gpsLat = 0.0;
            double gpsLon = 0.0;
            int lastMod = 0;
            if (pkt.length >= i + 4) {
                lastAdvertTs = (pkt[i] & 0xFF) | ((pkt[i + 1] & 0xFF) << 8)
                        | ((pkt[i + 2] & 0xFF) << 16) | ((pkt[i + 3] & 0xFF) << 24);
                i += 4;
            }
            if (pkt.length >= i + 8) {
                int latE6 = (pkt[i] & 0xFF) | ((pkt[i + 1] & 0xFF) << 8)
                        | ((pkt[i + 2] & 0xFF) << 16) | ((pkt[i + 3] & 0xFF) << 24);
                int lonE6 = (pkt[i + 4] & 0xFF) | ((pkt[i + 5] & 0xFF) << 8)
                        | ((pkt[i + 6] & 0xFF) << 16) | ((pkt[i + 7] & 0xFF) << 24);
                gpsLat = latE6 / 1_000_000.0;
                gpsLon = lonE6 / 1_000_000.0;
                i += 8;
            }
            if (pkt.length >= i + 4) {
                lastMod = (pkt[i] & 0xFF) | ((pkt[i + 1] & 0xFF) << 8)
                        | ((pkt[i + 2] & 0xFF) << 16) | ((pkt[i + 3] & 0xFF) << 24);
            }
            if (name.isEmpty()) {
                name = pubKeyHex.length() >= 12 ? pubKeyHex.substring(0, 12) : pubKeyHex;
            }
            return new MeshDeviceContact(pubKeyHex, type, flags, outPathLen, name,
                    lastAdvertTs, gpsLat, gpsLon, lastMod);
        } catch (Exception e) {
            Log.w(TAG, "parseDeviceContactPacket failed", e);
            return null;
        }
    }

    private byte[] buildAddUpdateContactCommand(MeshDeviceContact contact, int flags) {
        if (contact == null || contact.pubKeyHex == null) {
            return null;
        }
        byte[] pubKey = pubKeyPrefixBytes(contact.pubKeyHex, CONTACT_PUB_KEY_BYTES);
        if (pubKey == null) {
            return null;
        }
        int frameLen = 1 + CONTACT_PUB_KEY_BYTES + 3 + CONTACT_PATH_BYTES + CONTACT_NAME_BYTES + 16;
        byte[] out = new byte[frameLen];
        int i = 0;
        out[i++] = CMD_ADD_UPDATE_CONTACT;
        System.arraycopy(pubKey, 0, out, i, CONTACT_PUB_KEY_BYTES);
        i += CONTACT_PUB_KEY_BYTES;
        out[i++] = (byte) (contact.type & 0xFF);
        out[i++] = (byte) (flags & 0xFF);
        int pathLen = contact.outPathLen;
        if (pathLen <= 0 || pathLen > 63) {
            pathLen = OUT_PATH_UNKNOWN;
        }
        out[i++] = (byte) (pathLen & 0xFF);
        i += CONTACT_PATH_BYTES;
        byte[] nameBytes = (contact.name != null ? contact.name : "")
                .getBytes(StandardCharsets.UTF_8);
        int nameLen = Math.min(CONTACT_NAME_BYTES, nameBytes.length);
        System.arraycopy(nameBytes, 0, out, i, nameLen);
        i += CONTACT_NAME_BYTES;
        int lastAdvert = contact.lastAdvertTimestamp;
        out[i++] = (byte) (lastAdvert & 0xFF);
        out[i++] = (byte) ((lastAdvert >> 8) & 0xFF);
        out[i++] = (byte) ((lastAdvert >> 16) & 0xFF);
        out[i++] = (byte) ((lastAdvert >> 24) & 0xFF);
        int latE6 = (int) Math.round(contact.gpsLat * 1_000_000.0);
        int lonE6 = (int) Math.round(contact.gpsLon * 1_000_000.0);
        out[i++] = (byte) (latE6 & 0xFF);
        out[i++] = (byte) ((latE6 >> 8) & 0xFF);
        out[i++] = (byte) ((latE6 >> 16) & 0xFF);
        out[i++] = (byte) ((latE6 >> 24) & 0xFF);
        out[i++] = (byte) (lonE6 & 0xFF);
        out[i++] = (byte) ((lonE6 >> 8) & 0xFF);
        out[i++] = (byte) ((lonE6 >> 16) & 0xFF);
        out[i++] = (byte) ((lonE6 >> 24) & 0xFF);
        int lastMod = contact.lastMod > 0
                ? contact.lastMod
                : (int) (System.currentTimeMillis() / 1000L);
        out[i++] = (byte) (lastMod & 0xFF);
        out[i++] = (byte) ((lastMod >> 8) & 0xFF);
        out[i++] = (byte) ((lastMod >> 16) & 0xFF);
        out[i] = (byte) ((lastMod >> 24) & 0xFF);
        return out;
    }

    private void onDeviceContactsFetchTimeout() {
        if (!deviceContactsFetchActive) {
            return;
        }
        Log.w(TAG, "Device contacts fetch timed out with " + pendingDeviceContactsList.size()
                + " partial results");
        finishDeviceContactsFetch(false, "Timed out waiting for device contacts");
    }

    private void finishDeviceContactsFetch(boolean success, String failureReason) {
        if (!deviceContactsFetchActive) {
            return;
        }
        deviceContactsFetchActive = false;
        ioHandler.removeCallbacks(deviceContactsFetchTimeoutRunnable);
        final DeviceContactsListener listener = pendingDeviceContactsListener;
        pendingDeviceContactsListener = null;
        final java.util.ArrayList<MeshDeviceContact> results =
                new java.util.ArrayList<>(pendingDeviceContactsList);
        pendingDeviceContactsList.clear();
        mainHandler.post(() -> {
            if (listener == null) {
                return;
            }
            if (success) {
                listener.onDeviceContactsReady(results);
            } else {
                listener.onDeviceContactsFailed(
                        failureReason != null ? failureReason : "Failed to load contacts");
            }
        });
    }

    private void handleMeshMessage(String msg) {
        handleMeshMessage(msg, 0);
    }

    private void handleMeshMessage(String msg, int pathLen) {
        if (msg == null || !msg.startsWith(ENV_PREFIX)) return;
        String[] parts = msg.split("\\|", 5);
        if (parts.length != 5) return;
        try {
            int msgId = Integer.parseInt(parts[1]);
            int seq = Integer.parseInt(parts[2]);
            int total = Integer.parseInt(parts[3]);
            byte[] chunk = Base64.decode(parts[4], Base64.DEFAULT);
            if (chunk == null || total < 1 || seq < 1 || seq > total) return;

            ChunkAccumulator acc = chunkBuffers.get(msgId);
            if (acc == null || acc.total != total) {
                acc = new ChunkAccumulator(total);
                chunkBuffers.put(msgId, acc);
            }
            acc.parts.put(seq, chunk);
            acc.lastUpdateMs = System.currentTimeMillis();
            if (acc.parts.size() < total) {
                scheduleIncompleteChunkDrain();
                return;
            }

            int len = 0;
            for (int i = 1; i <= total; i++) {
                byte[] p = acc.parts.get(i);
                if (p == null) return;
                len += p.length;
            }
            byte[] ax25 = new byte[len];
            int off = 0;
            for (int i = 1; i <= total; i++) {
                byte[] p = acc.parts.get(i);
                System.arraycopy(p, 0, ax25, off, p.length);
                off += p.length;
            }
            chunkBuffers.remove(msgId);

            for (RawDataListener listener : rawDataListeners) {
                try {
                    if (listener.onRawBytes(ax25)) {
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
            packetRouter.routeIncoming(ax25, pathLen);
        } catch (Exception ignored) {
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g;
                connected.set(false);
                g.requestMtu(512);
                g.discoverServices();
                return;
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handleConnectionLost();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyError("BLE service discovery failed");
                handleConnectionLost();
                return;
            }
            BluetoothGattService svc = g.getService(UUID_UART_SERVICE);
            if (svc == null) {
                notifyError("MeshCore UART service not found");
                handleConnectionLost();
                return;
            }
            rxCharacteristic = svc.getCharacteristic(UUID_UART_RX);
            txCharacteristic = svc.getCharacteristic(UUID_UART_TX);
            if (rxCharacteristic == null || txCharacteristic == null) {
                notifyError("MeshCore RX/TX characteristic missing");
                handleConnectionLost();
                return;
            }
            g.setCharacteristicNotification(txCharacteristic, true);
            BluetoothGattDescriptor ccc = txCharacteristic.getDescriptor(UUID_CCC);
            if (ccc == null) {
                notifyError("MeshCore CCC descriptor missing");
                handleConnectionLost();
                return;
            }
            ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            g.writeDescriptor(ccc);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (!UUID_CCC.equals(descriptor.getUuid())) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyError("BLE notification enable failed");
                handleConnectionLost();
                return;
            }
            connecting.set(false);
            connected.set(true);
            markIoActivity();
            notifyConnected(lastDevice);
            enqueueCommand(buildAppStartCommand());
            enqueueCommand(buildDeviceQueryCommand());
            enqueueCommand(buildSetChannelCommand(
                    ATAK_CHANNEL_INDEX,
                    ATAK_CHANNEL_NAME,
                    ATAK_CHANNEL_SECRET));
            enqueueCommand(new byte[]{CMD_GET_GPS_STATE});
            for (int i = 0; i < 8; i++) {
                enqueueCommand(buildGetChannelInfoCommand(i));
            }
            enqueueCommand(buildGetNextMessageCommand());
            ioHandler.removeCallbacks(periodicMessagePoll);
            ioHandler.postDelayed(periodicMessagePoll, 2500L);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            if (characteristic == null || !UUID_UART_TX.equals(characteristic.getUuid())) return;
            handleCompanionPacket(characteristic.getValue());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            synchronized (writeQueue) {
                writeQueue.pollFirst();
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                markIoActivity();
            }
            drainWriteQueue();
        }
    };

    private static final class ChunkAccumulator {
        final int total;
        final Map<Integer, byte[]> parts = new ConcurrentHashMap<>();
        long lastUpdateMs;

        ChunkAccumulator(int total) {
            this.total = total;
            this.lastUpdateMs = System.currentTimeMillis();
        }
    }

    private void pruneStaleChunks() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, ChunkAccumulator> e : chunkBuffers.entrySet()) {
            ChunkAccumulator acc = e.getValue();
            if (acc == null) continue;
            if (now - acc.lastUpdateMs > 120_000L) {
                chunkBuffers.remove(e.getKey());
            }
        }
        for (Map.Entry<Integer, ChunkAccumulator> e : binaryChunkBuffers.entrySet()) {
            ChunkAccumulator acc = e.getValue();
            if (acc == null) continue;
            if (now - acc.lastUpdateMs > 120_000L) {
                binaryChunkBuffers.remove(e.getKey());
            }
        }
    }
}
