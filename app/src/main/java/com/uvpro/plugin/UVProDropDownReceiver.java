package com.uvpro.plugin;

import android.app.AlertDialog;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.preference.PreferenceManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.GridLayout;
import android.widget.CheckBox;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ArrayAdapter;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.android.user.MapClickTool;
import com.atakmap.android.toolbar.widgets.TextContainer;
import com.atakmap.coremap.filesystem.FileSystemUtils;

import com.uvpro.plugin.aprs.AprsTrackManager;
import com.uvpro.plugin.beacon.SmartBeacon;
import com.uvpro.plugin.bluetooth.BluetoothDeviceRegistry;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.bluetooth.MeshBleDeviceMatcher;
import com.uvpro.plugin.bluetooth.MeshBluetoothForgetAll;
import com.uvpro.plugin.bluetooth.MeshBtConnectionManager;
import com.uvpro.plugin.bluetooth.MeshDeviceContactCache;
import com.uvpro.plugin.bluetooth.MeshContactChatHistoryStore;
import com.uvpro.plugin.bluetooth.MeshJoinedRoomStore;
import com.uvpro.plugin.bluetooth.MeshLastChatStore;
import com.uvpro.plugin.bluetooth.MeshRoomPasswordStore;

import androidx.annotation.Nullable;
import com.uvpro.plugin.bluetooth.MeshDeviceContactPolicy;
import com.uvpro.plugin.bluetooth.UvProBtDeviceMatcher;
import com.uvpro.plugin.bluetooth.UvProRadioIdentCache;
import com.uvpro.plugin.bluetooth.TransmitTransportResolver;
import com.uvpro.plugin.chat.ChatBridge;
import com.uvpro.plugin.location.RadioGpsAugmentController;
import com.uvpro.plugin.location.RadioGpsBridge;
import com.uvpro.plugin.location.RadioPositionFix;
import com.uvpro.plugin.kiss.KissRadioFrequencyControl;
import com.uvpro.plugin.contacts.ContactTracker;
import com.uvpro.plugin.contacts.RadioContact;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.crypto.EncryptionManager;
import com.uvpro.plugin.protocol.PacketRouter;
import com.uvpro.plugin.protocol.PingReplyNotifier;
import com.uvpro.plugin.radio.UVProRadioControlManager;
import com.uvpro.plugin.terminal.PacketTerminalDropDownReceiver;
import com.uvpro.plugin.ui.MeshStatusOverlay;
import com.uvpro.plugin.ui.SettingsFragment;

import java.text.SimpleDateFormat;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UVPro Drop-Down UI Panel.
 *
 * Slides in from the right side of the ATAK map. Provides:
 * - Radio connection status and controls
 * - Contact count and statistics
 * - Quick-action buttons (beacon, ping, settings)
 * - Debug log view
 */
public class UVProDropDownReceiver extends DropDownReceiver
        implements DropDown.OnStateListener,
        BtConnectionManager.ConnectionListener,
        ContactTracker.ContactListener,
        PacketRouter.PacketCountListener {

    public static final String SHOW_PLUGIN =
            "com.uvpro.plugin.SHOW_PLUGIN";
    public static final String SHOW_PLUGIN_CHANNEL_CONTROL =
            "com.uvpro.plugin.SHOW_PLUGIN_CHANNEL_CONTROL";
    private static final String EXTRA_MESH_NODE_POSITION_PICK_RESULT =
            "mesh_node_position_pick_result";

    private static final String TAG = "UVPro.UI";
    private static final int MAX_LOG_LINES = 50;
    /** Picker list rows: light surface + dark label (ATAK theme text is often white). */
    private static final int RADIO_PICKER_ROW_BG = 0xFFF0F0F0;
    private static final int RADIO_PICKER_LABEL_COLOR = 0xFF1A1A1A;
    private static final String[] MESH_DEVICE_NAME_HINTS = {"meshcore", "mesh core", "meshtastic"};
    private static final int COLOR_A_ACTIVE = 0xFF00897B;       // Teal
    private static final int COLOR_A_SUBDUED = 0xFF2E6B63;      // Teal (subdued)
    private static final int COLOR_B_ACTIVE = 0xFF4CAF50;       // Bright Green
    private static final int COLOR_B_SUBDUED = 0xFF2E7D32;      // Green (subdued)
    private static final int COLOR_DIGITAL_ACTIVE = 0xFF005A8D; // Blue
    private static final int COLOR_DIGITAL_SUBDUED = 0xFF2A5674; // Blue (subdued)
    private static final int COLOR_EDIT_SELECTION_BORDER = 0xFFFF9800; // Bright orange
    private static final int EDIT_SELECTION_STROKE_DP = 3;
    /** Corner radius for pill-shaped buttons (matches bg_uvpro_pill_button.xml). */
    private static final int PILL_CORNER_RADIUS_DP = 20;
    private static final int COLOR_TX_HIGHLIGHT = 0xFFFF1744; // Bright red
    private static final int COLOR_TX_STROKE = 0xFFFFFFFF; // White stroke
    /** Primary full-width pill buttons (beacon settings, plugin settings, etc.). */
    private static final int COLOR_PILL_BUTTON_PRIMARY = 0xFF455A64;
    private static final int COLOR_REPEATER_LOAD_FILL = COLOR_PILL_BUTTON_PRIMARY;
    private static final int COLOR_REPEATER_LOAD_ARMED_STROKE = 0xFFFFEB3B;
    private static final int COLOR_CHANNEL_SECTION_STROKE = 0xFF4CAF50;
    private static final int COLOR_BEACON_SECTION_STROKE = 0xFF00BCD4;
    private static final int COLOR_APRS_SECTION_STROKE = 0xFFFF9800;
    private static final int CHANNEL_SECTION_STROKE_DP = 2;
    private static final String LABEL_LOAD_REPEATER = "Load Selected Repeater";
    private static final String LABEL_SELECT_CHANNEL = "Select Channel";
    private static final String LABEL_DIGITAL_ONLY = "DIGITAL ONLY MODE";
    private static final String LABEL_DIGITAL_SELECT_CHANNEL = "Select Channel";
    private static final String LABEL_DIGITAL_LONG_PRESS_DISABLE =
            "Long Press to Disable KISS Mode";
    private static final int TARGET_A = 0;
    private static final int TARGET_B = 1;
    private static final int TARGET_DIGITAL = 2;
    private static final String PREF_AUGMENT_GPS_FROM_MESHCORE =
            "uvpro_augment_gps_from_meshcore";
    private static final String PREF_ATAK_WIFI_TRANSMIT =
            "uvpro_atak_wifi_transmit";
    private static final String PREF_ATAK_MESHCORE_TRANSMIT =
            "uvpro_atak_meshcore_transmit";
    private static final String PREF_ATAK_UVPRO_TRANSMIT =
            "uvpro_atak_uvpro_transmit";
    /** User's chosen transmit transport; survives startup / failover toggle swaps. */
    private static final String PREF_TRANSMIT_PREFERRED_TRANSPORT =
            com.uvpro.plugin.ui.SettingsFragment.PREF_TRANSMIT_PREFERRED_TRANSPORT;
    private static final String PREF_MESH_CHANNEL_HISTORY =
            "uvpro_mesh_channel_history_v1";
    private static final String PREF_MESH_SHOW_REPEATERS =
            "uvpro_mesh_show_repeaters";
    private static final String PREF_MESH_SHOW_NODES =
            "uvpro_mesh_show_nodes";
    private static final String PREF_MESH_REPEATER_CACHE =
            "uvpro_mesh_repeater_cache_v1";
    private static final String PREF_MESH_NODE_CACHE =
            "uvpro_mesh_node_cache_v1";
    private static final String PREF_MESH_SEND_POSITION_WITH_ADVERT =
            "uvpro_mesh_send_position_with_advert";
    private static final String PREF_MESH_USE_GPS_FOR_POSITION =
            "uvpro_mesh_use_gps_for_position";
    private static final String PREF_MESH_USE_CALLSIGN_LOCATION_FOR_POSITION =
            "uvpro_mesh_use_callsign_location_for_position";
    private static final String PREF_MESH_USE_CUSTOM_NODE_POSITION =
            "uvpro_mesh_use_custom_node_position";
    private static final String PREF_MESH_MAP_SET_POSITION_LAT =
            "uvpro_mesh_map_set_position_lat";
    private static final String PREF_MESH_MAP_SET_POSITION_LON =
            "uvpro_mesh_map_set_position_lon";
    private static final long MESH_CALLSIGN_POSITION_PUSH_INTERVAL_MS = 15_000L;
    private static final long MESH_BATTERY_POLL_INTERVAL_MS = 30_000L;
    private static final long UVPRO_BATTERY_POLL_INTERVAL_MS = 30_000L;
    private static final String MESH_NODE_MAP_POSITION_UID = "MESHCORE-NODE-MAP-POSITION";
    private static final String MESH_NODE_UID_PREFIX = "MESHCORE-NODE-";
    private static final String MESH_RPTR_UID_PREFIX = "MESHCORE-RPTR-";
    private static final String ANDROID_MESH_NODE_UID_PREFIX = "ANDROID-MESHCORE-NODE-";
    private static final String ANDROID_MESH_RPTR_UID_PREFIX = "ANDROID-MESHCORE-RPTR-";
    private static final double[] MESH_BANDWIDTH_OPTIONS_KHZ = new double[]{
            7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125.0, 250.0, 500.0
    };
    private static final int[] MESH_SPREADING_FACTOR_OPTIONS = new int[]{
            5, 6, 7, 8, 9, 10, 11, 12
    };
    private static final int[] MESH_CODING_RATE_OPTIONS = new int[]{
            5, 6, 7, 8
    };

    private final Context pluginContext;
    private final BtConnectionManager btManager;
    private final MeshBtConnectionManager meshBtManager;
    private final ContactTracker contactTracker;
    private CotBridge cotBridge;
    private ChatBridge chatBridge;
    private EncryptionManager encryptionManager;

    private View rootView;
    private View statusDot;
    private TextView statusText;
    private View deviceRow;
    private TextView deviceName;
    private android.widget.ImageView uvproBatteryIcon;
    private TextView uvproBatteryPct;
    private int uvproBatteryPercent = -1;
    private View meshStatusDot;
    private TextView meshStatusText;
    private View meshDeviceRow;
    private TextView meshDeviceName;
    private android.widget.ImageView meshBatteryIcon;
    private TextView meshBatteryPct;
    private int meshBatteryPercent = -1;
    private TextView callsignText;
    private TextView contactsText;
    private TextView packetsText;
    private TextView receiveRssiText;
    private View receiveRssiMeterBlock;
    private View receiveRssiMeterFrame;
    private View receiveRssiFillGreen;
    private View receiveRssiFillRed;
    private android.widget.FrameLayout receiveRssiScaleFrame;
    private boolean receiveRssiScaleBuilt;
    private int lastReceiveRssiScaleLevel = -1;

    private static final int RSSI_SCALE_MAX = 12;
    private static final int[] RSSI_SCALE_TICKS = {1, 2, 4, 6, 8, 10, 11, 12};
    private static final int[] RSSI_SCALE_LABEL_POS = {3, 5, 7, 9};
    private static final String[] RSSI_SCALE_LABELS = {"3", "5", "7", "9"};
    private TextView logText;
    private TextView encryptionStatusText;
    private TextView beaconIntervalText;
    private TextView gpsBeaconIntervalLabel;
    private View rowBeaconInterval;
    private Switch switchSmartBeacon;
    private Button btnManagePluginBeaconSettings;
    private Button btnAprsBeaconArm;
    private TextView textAprsStatusCall;
    private android.widget.ImageView imageAprsStatusIcon;
    private TextView textAprsStatusIcon;
    private TextView textAprsStatusMessage;
    private Switch switchAprsDisableAtak;
    private Button btnSendBeacon;
    private Button btnSendPing;
    private Button btnSendAprsBeacon;
    private Button btnClearAprsContacts;
    private Button btnEditAprsSettings;
    private Switch switchMeshShowRepeaters;
    private Switch switchMeshShowNodes;
    private Switch switchMeshSendPositionWithAdvert;
    private Button btnAddMeshChannel;
    private Button btnMeshContacts;
    private LinearLayout stripMeshChannels;
    private TextView meshChannelTitleView;
    private TextView meshChannelLogText;
    private android.widget.EditText editMeshChannelMessage;
    private Button btnMeshChannelSend;
    private Button btnExpandMeshChannelChat;
    private android.view.View rowMeshChannelInput;
    private Button btnMeshcoreSetNodePositionMap;
    private Button btnMeshcoreSendAdvert;
    private Button btnDiscoverRepeaters;
    private Button btnClearMeshContacts;
    private Button btnMeshNodeSettings;
    private TextView teamColorText;
    private Button btnScan;
    private Button btnDisconnect;
    private Button btnMeshScan;
    private Button btnMeshDisconnect;
    private Button btnUpdateGpsFromMeshcore;
    private Button btnUpdateGpsFromRadio;
    private Button btnLoadSelectedRepeater;
    private Button btnTxPower;
    private Button btnDigitalOnlyMode;
    private Button btnRadioSilence;
    private Button btnRefreshChannels;
    private Button btnInitialChannelGroupSetup;
    private Button btnPacketTerminal;
    private View packetTerminalSection;
    private PacketTerminalDropDownReceiver packetTerminalReceiver;
    private Button btnChannelGroup;
    private Button btnImportChannels;
    private Button btnExportChannels;
    private Button btnVfoA;
    private Button btnVfoB;
    private Button btnDigital;
    private TextView selectedRepeaterText;
    private View repeaterLoadScrollAnchor;
    private GridLayout channelsGrid;
    private Switch switchDualWatch;
    private Switch switchDigitalEdit;
    private Switch switchAugmentGpsFromRadio;
    private Switch switchAugmentGpsFromMeshcore;
    private View rowAugmentGpsFromMeshcore;
    private Switch switchMeshTransmit;
    private Switch switchUvProTransmit;
    private Switch switchWifiTransmit;
    private Switch switchMeshEnableGps;
    private Switch switchMeshEnableGpsMeshcore;
    private Switch switchMeshUseCallsignLocation;
    private Switch switchMeshUseCustomNodePosition;
    private TextView textMeshUseCallsignLocation;

    private Switch switchEncryption;

    private final List<BluetoothDevice> foundDevices = new ArrayList<>();
    private final List<BluetoothDevice> meshFoundDevices = new ArrayList<>();
    private ValueAnimator scanConnectPulseAnimator;
    private GradientDrawable scanConnectPulseDrawable;
    private final Runnable deferredScanConnectPulseStart = this::startScanConnectButtonPulse;
    private final Runnable deferredMeshScanPulseStart = this::startMeshScanButtonPulse;
    private volatile boolean scanConnectPulsePending = false;
    private volatile boolean meshScanPulsePending = false;
    private ValueAnimator meshConnectPulseAnimator;
    private GradientDrawable meshConnectPulseDrawable;
    private ValueAnimator sendButtonPulseAnimator;
    private GradientDrawable sendButtonPulseDrawable;
    private Button sendButtonPulseTarget;
    private int sendButtonPulseRestoreStroke = COLOR_BEACON_SECTION_STROKE;
    private AlertDialog radioPickerDialog;

    private final LinkedList<String> logLines = new LinkedList<>();
    private final CompoundButton.OnCheckedChangeListener smartBeaconCheckedListener =
            (buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                Context c = getMapView().getContext();
                SmartBeacon.setEnabled(c, isChecked);
                applyBeaconIntervalRowState();
                appendLog("Smart beacon " + (isChecked ? "on" : "off"));
                try {
                    AtakBroadcast.getInstance().sendBroadcast(
                            new Intent(UVProMapComponent.ACTION_BEACON_INTERVAL_CHANGED));
                } catch (Exception ignored) {
                }
            };
    private int txCount = 0;
    private int rxCount = 0;
    private UVProRadioControlManager radioControlManager;
    private final RadioGpsAugmentController radioGpsAugmentController =
            new RadioGpsAugmentController();
    private boolean activeVfoB = false;
    private boolean channelTargetDigital = false;
    private int selectedTarget = TARGET_A;
    private int lastAnalogTarget = TARGET_A;
    private boolean txVfoB = false;
    private int lastChannelA = -1;
    private int lastChannelB = -1;
    private int lastDigitalChannel = -1;
    private boolean lastDualWatchEnabled = false;
    private boolean dualWatchProgrammaticUpdate = false;
    private long dualWatchWriteTimestamp = 0;
    private boolean dualWatchWriteValue = false;
    private int currentTxPowerLevel = UVProRadioControlManager.TX_POWER_LOW;
    private int pendingChannelA = -1;
    private long pendingChannelATimestamp = 0;
    private int pendingChannelB = -1;
    private long pendingChannelBTimestamp = 0;
    private int pendingDigitalChannel = -1;
    private long pendingDigitalChannelTimestamp = 0;
    private int currentChannelGroup = 0;
    private int availableChannelGroups = UVProRadioControlManager.DEFAULT_GROUP_COUNT;
    private boolean lastHasRxFocus = false;
    private ValueAnimator activeVfoPulseAnimator;
    private Button pulsingVfoButton;
    private GradientDrawable pulsingVfoDrawable;
    private ObjectAnimator repeaterLoadFocusAnimator;
    private ValueAnimator updateGpsButtonPulseAnimator;
    private GradientDrawable updateGpsButtonPulseDrawable;
    private Button updateGpsPulseTargetButton;
    private ValueAnimator initialGroupSetupPulseAnimator;
    private GradientDrawable initialGroupSetupPulseDrawable;
    private BroadcastReceiver beaconSettingsReceiver;
    private final AtomicBoolean snapshotReadInFlight = new AtomicBoolean(false);
    private final AtomicBoolean snapshotRefreshPending = new AtomicBoolean(false);
    private final AtomicBoolean snapshotFullRefreshPending = new AtomicBoolean(false);
    private final AtomicBoolean groupReadInFlight = new AtomicBoolean(false);
    private final AtomicBoolean groupRefreshGridPending = new AtomicBoolean(false);
    private UVProRadioControlManager.RadioControlSnapshot lastSnapshot;
    private boolean repeaterLoadArmed = false;
    private String repeaterLoadArmedUid;
    private UVProRadioControlManager.RepeaterSpec repeaterLoadArmedSpec;
    private boolean digitalOnlyArmed = false;
    private boolean digitalOnlyActive = false;
    private int digitalOnlyChannelId = -1;
    private final Runnable digitalOnlyDisconnectHook = this::releaseDigitalOnlyKissLockSilent;
    private boolean pendingOpenToChannelControl = false;
    private boolean meshConnected = false;
    private boolean meshTransmitEnabled = false;
    private boolean uvproTransmitEnabled = false;
    /** User transmit preference (MeshCore vs UV-PRO); survives auto-failover toggle swaps. */
    private enum PreferredTransmitTransport {
        NONE, MESH, UVPRO
    }
    private PreferredTransmitTransport preferredTransmitTransport = PreferredTransmitTransport.NONE;
    private boolean transmitFailoverActive = false;
    private static final long TRANSMIT_FAILOVER_DELAY_MS = 5 * 60 * 1000L;
    private Runnable transmitFailoverRunnable = null;
    /** Boot-only window for connection-driven transmit defaults; closed after mesh boot resolves. */
    private boolean startupTransmitWindowOpen = true;
    private static final long STARTUP_TRANSMIT_AFTER_MESH_GIVEUP_MS = 10_000L;
    private Runnable startupTransmitApplyRunnable = null;
    private boolean startupTransmitApplyScheduled = false;
    private boolean wifiTransmitEnabled = false;
    private boolean suppressTransportSwitchCallbacks = false;
    private Boolean meshGpsEnabledState = null;
    private boolean meshGpsEnableRequested = false;
    private boolean suppressMeshGpsSwitchCallbacks = false;
    private Boolean meshSendPositionWithAdvertState = null;
    private boolean meshSendPositionWithAdvertRequested = false;
    private boolean suppressMeshSendPositionWithAdvertSwitchCallbacks = false;
    private MeshBtConnectionManager.MeshNodeSettings meshNodeSettingsState;
    private AlertDialog meshNodeSettingsDialog;
    private EditText meshNodeSettingsNameField;
    private EditText meshNodeSettingsFrequencyField;
    private Spinner meshNodeSettingsBandwidthSpinner;
    private Spinner meshNodeSettingsSfSpinner;
    private Spinner meshNodeSettingsCrSpinner;
    private EditText meshNodeSettingsTxPowerField;
    private TextView meshNodeSettingsStatus;
    private ScrollView meshNodeSettingsScrollView;
    private final Map<Integer, String> meshChannelNames = new HashMap<>();
    private final Map<Integer, LinkedList<MeshBtConnectionManager.MeshChannelMessage>>
            meshChannelMessages = new HashMap<>();
    private AlertDialog meshChannelChatDialog;
    @Nullable
    private AlertDialog channelManagementDialog;
    private TextView meshChannelChatLogView;
    private TextView meshChannelChatTitleView;
    private TextView textMeshChannelExpandedLog;
    private TextView textMeshChannelExpandedTitle;
    private EditText editMeshChannelExpandedMessage;
    private int meshChannelChatActiveIndex = -1;
    private static final int ADV_TYPE_REPEATER = 0x02;
    private static final int MAX_MESH_CONTACT_CHAT_LINES = 120;
    private static final long DEVICE_CONTACTS_CACHE_STALE_MS = 15L * 60L * 1000L;
    private static final long DEVICE_CONTACTS_CONNECT_SYNC_DELAY_MS = 5000L;
    private static final String PREF_SUPPRESS_MESH_DM_CONTACT_ALERT =
            "uvpro_suppress_mesh_dm_contact_alert";
    private volatile boolean deviceContactsFetchInFlight = false;
    @Nullable
    private AlertDialog deviceContactsDialog;
    private static final class MeshContactChatSession {
        final String pubKeyHex;
        String displayName;
        boolean isRoom;
        boolean roomLoginSucceededThisConnection;
        boolean roomLoginFullResetRetryUsed;
        boolean roomLoginNotFoundRetryUsed;
        boolean roomEmptyPostSyncRetryUsed;
        long roomLastOpenedMs;
        int roomPostsReceivedThisSession;
        final LinkedList<String> lines = new LinkedList<>();

        MeshContactChatSession(String pubKeyHex, String displayName) {
            this.pubKeyHex = pubKeyHex;
            this.displayName = displayName != null && !displayName.trim().isEmpty()
                    ? displayName.trim() : "Contact";
        }
    }

    private static final class ManagedChannelEntry {
        static final int TYPE_GROUP = 0;
        static final int TYPE_ROOM = 1;
        final int type;
        final String label;
        final int slot;
        final String pubKeyHex;

        static ManagedChannelEntry group(int slot, String name) {
            return new ManagedChannelEntry(TYPE_GROUP, name, slot, null);
        }

        static ManagedChannelEntry room(String name, String pubKeyHex) {
            return new ManagedChannelEntry(TYPE_ROOM, name, -1, pubKeyHex);
        }

        private ManagedChannelEntry(int type, String label, int slot, String pubKeyHex) {
            this.type = type;
            this.label = label;
            this.slot = slot;
            this.pubKeyHex = pubKeyHex;
        }
    }

    /** Open contact DM tabs keyed by full device pubkey hex. */
    private final LinkedHashMap<String, MeshContactChatSession> meshContactChatSessions =
            new LinkedHashMap<>();
    /** Non-null when the inline chat panel is showing a contact DM (not a channel). */
    private String activeMeshContactPubKey = null;
    @Nullable
    private String pendingRoomPubKeyHex = null;
    private PendingRoomLoginAction pendingRoomLoginAction = PendingRoomLoginAction.NONE;
    private Runnable pendingRoomLoginTimeoutRunnable = null;
    private Runnable roomPostSyncFinishRunnable = null;
    private Runnable roomEmptyPostSyncRetryRunnable = null;
    private int roomLoginAttemptId = 0;
    @Nullable
    private String roomLoginPendingPubKey = null;
    private static final long ROOM_LOGIN_TIMEOUT_MS = 35_000L;
    private static final long ROOM_LOGIN_FULL_RESET_TIMEOUT_MS = 55_000L;
    private static final long ROOM_EMPTY_POST_SYNC_RETRY_MS = 12_000L;
    private static final long ROOM_CONTACT_ADD_DELAY_MS = 800L;
    private static final long ROOM_POST_SYNC_FINISH_MS = 300_000L;
    private boolean joinedRoomRestoredThisSession = false;
    private boolean meshChatUiRestorePending = true;

    private enum PendingRoomLoginAction {
        NONE,
        OPEN_CHAT,
        JOIN_ONLY
    }
    private static final int MAX_MESH_CHANNEL_MESSAGES = 120;
    private static final long MESH_CHANNEL_QUEUE_TIMEOUT_MS = 8000L;
    private boolean meshChannelHistoryLoaded = false;
    private final Runnable meshQueuedStatusTimeoutRunnable = this::applyMeshQueuedStatusTimeouts;
    private final AtomicBoolean meshGpsAugmentUpdateInFlight = new AtomicBoolean(false);
    private final Runnable meshCallsignPositionSyncRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                pushPhoneLocationToMeshNodeIfNeeded(false);
            } finally {
                scheduleMeshCallsignPositionSync();
            }
        }
    };
    private final Runnable uvproBatteryPollRunnable = new Runnable() {
        @Override
        public void run() {
            refreshUvproBatteryAsync();
            scheduleUvproBatteryPoll();
        }
    };
    private final Runnable meshBatteryPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (meshBtManager == null || !meshBtManager.isConnected()) {
                return;
            }
            meshBtManager.requestBattery();
            scheduleMeshBatteryPoll();
        }
    };
    private final Runnable meshGpsAugmentRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                Context ctx = getMapView() != null ? getMapView().getContext() : null;
                if (ctx == null || !isAugmentMeshPreferenceEnabled(ctx)) {
                    return;
                }
                if (meshBtManager == null || !meshBtManager.isConnected()) {
                    return;
                }
                if (RadioGpsBridge.isPhoneGpsAvailable(getMapView())) {
                    return;
                }
                if (!Boolean.TRUE.equals(meshGpsEnabledState)) {
                    meshBtManager.queryMeshGpsEnabled();
                    return;
                }
                if (!meshGpsAugmentUpdateInFlight.compareAndSet(false, true)) {
                    return;
                }
                new Thread(() -> {
                    try {
                        updateGpsFromMeshcoreNow(false);
                    } finally {
                        meshGpsAugmentUpdateInFlight.set(false);
                    }
                }, "uvpro-mesh-gps-augment").start();
            } finally {
                scheduleMeshGpsAugmentTick();
            }
        }
    };
    private final MeshBtConnectionManager.MeshChannelListener meshChannelListener =
            new MeshBtConnectionManager.MeshChannelListener() {
                @Override
                public void onChannelInfo(MeshBtConnectionManager.MeshChannelInfo info) {
                    if (info == null) {
                        return;
                    }
                    getMapView().post(() -> {
                        meshChannelNames.put(info.index, info.name);
                        persistMeshChannelHistory();
                        updateMeshChannelButtonLabel();
                    });
                }

                @Override
                public void onChannelMessage(MeshBtConnectionManager.MeshChannelMessage message) {
                    if (message == null) {
                        return;
                    }
                    getMapView().post(() -> {
                        appendMeshChannelMessage(message);
                        if (meshChannelChatActiveIndex == message.channelIndex
                                && meshChannelChatLogView != null) {
                            renderMeshChannelChatLog(message.channelIndex);
                        }
                    });
                }
            };

    private final MeshBtConnectionManager.MeshNativeDmListener meshNativeDmListener =
            new MeshBtConnectionManager.MeshNativeDmListener() {
                @Override
                public void onNativeContactMessage(
                        MeshBtConnectionManager.MeshContactInboundMessage message) {
                    if (message == null || message.text == null || message.text.trim().isEmpty()) {
                        return;
                    }
                    if (message.txtType == MeshBtConnectionManager.TXT_TYPE_CLI_DATA) {
                        return;
                    }
                    getMapView().post(() -> {
                        String sender = message.senderPubKeyPrefixHex;
                        if (sender == null) {
                            return;
                        }
                        String prefixUpper = sender.toUpperCase(Locale.US);
                        MeshContactChatSession session = findRoomSessionForPrefix(prefixUpper);
                        if (session == null && activeMeshContactPubKey != null) {
                            String active = activeMeshContactPubKey.toUpperCase(Locale.US);
                            if (active.startsWith(prefixUpper) || prefixUpper.startsWith(
                                    active.substring(0, Math.min(12, active.length())))) {
                                session = findSessionByPubKey(activeMeshContactPubKey);
                            }
                        }
                        if (session == null) {
                            appendLog("Room post for unknown sender " + prefixUpper
                                    + " type=0x" + Integer.toHexString(message.txtType)
                                    + " — ensure room tab is open");
                            return;
                        }
                        if (session.isRoom
                                && message.txtType != MeshBtConnectionManager.TXT_TYPE_SIGNED_PLAIN) {
                            appendLog("Room post ignored (wrong type) for " + session.displayName
                                    + " type=0x" + Integer.toHexString(message.txtType));
                            return;
                        }
                        if (!session.isRoom
                                && message.txtType == MeshBtConnectionManager.TXT_TYPE_SIGNED_PLAIN) {
                            return;
                        }
                        cancelRoomLoginTimeout();
                        cancelRoomEmptyPostSyncRetry();
                        removeRoomLoginPlaceholder(session);
                        String author = resolveRoomAuthorLabel(message.authorPubKeyPrefixHex);
                        appendMeshContactChatLine(session, false, message.text, author,
                                message.senderTimestampSec);
                        session.roomPostsReceivedThisSession++;
                        appendLog("Room post added for " + session.displayName
                                + " ts=" + message.senderTimestampSec
                                + " total=" + session.roomPostsReceivedThisSession);
                        if (meshBtManager != null) {
                            meshBtManager.requestMessageDrain(4, true);
                        }
                        renderRoomChatIfVisible(session);
                    });
                }
            };

    private final MeshBtConnectionManager.MeshRoomLoginListener meshRoomLoginListener =
            new MeshBtConnectionManager.MeshRoomLoginListener() {
                @Override
                public void onRoomLoginSuccess(String pubKeyPrefixHex12, int permissions) {
                    getMapView().post(() -> handleRoomLoginSuccess(pubKeyPrefixHex12, permissions));
                }

                @Override
                public void onRoomLoginFail(String pubKeyPrefixHex12) {
                    getMapView().post(() -> handleRoomLoginFail(pubKeyPrefixHex12));
                }

                @Override
                public void onCompanionCommandError(int errCode) {
                    getMapView().post(() -> handleRoomCompanionCommandError(errCode));
                }
            };

    private final MeshBtConnectionManager.MeshDeviceContactUpdateListener meshDeviceContactUpdateListener =
            contact -> getMapView().post(() -> applyContactNameFromRadio(contact));

    private final MeshBtConnectionManager.MeshAdvertListener meshRoomAdvertListener =
            advert -> {
                if (advert == null || advert.advertType != MeshBtConnectionManager.ADV_TYPE_ROOM) {
                    return;
                }
                if (advert.name == null || advert.name.trim().isEmpty()) {
                    return;
                }
                getMapView().post(() -> applyContactNameFromRadio(
                        new MeshBtConnectionManager.MeshDeviceContact(
                                advert.pubKeyHex, MeshBtConnectionManager.ADV_TYPE_ROOM,
                                0, 0, advert.name.trim(),
                                (int) advert.advertTimestampSec,
                                advert.latitude, advert.longitude, 0)));
            };

    public UVProDropDownReceiver(MapView mapView,
                                     Context pluginContext,
                                     BtConnectionManager btManager,
                                     MeshBtConnectionManager meshBtManager,
                                     ContactTracker contactTracker) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.btManager = btManager;
        this.meshBtManager = meshBtManager;
        this.contactTracker = contactTracker;

        // Load last-session transmit preference before any connect callbacks can apply defaults.
        Context mapCtx = mapView != null ? mapView.getContext() : null;
        if (mapCtx != null) {
            resolvePreferredTransmitTransportFromPreferences(mapCtx);
            applyTransmitTogglesForConnectivity(false, true);
        }

        if (meshBtManager == null) {
            scheduleStartupTransmitAfterMeshBoot(STARTUP_TRANSMIT_AFTER_MESH_GIVEUP_MS);
        }

        // Register as listener for connection and contact updates
        btManager.addListener(this);
        btManager.addBeforeDisconnectHook(digitalOnlyDisconnectHook);
        contactTracker.setListener(this);
        if (meshBtManager != null) {
            meshBtManager.addMeshChannelListener(meshChannelListener);
            meshBtManager.addMeshNativeDmListener(meshNativeDmListener);
            meshBtManager.addMeshRoomLoginListener(meshRoomLoginListener);
            meshBtManager.addMeshDeviceContactUpdateListener(meshDeviceContactUpdateListener);
            meshBtManager.addMeshAdvertListener(meshRoomAdvertListener);
            meshBtManager.setMeshBootAutoConnectListener(new MeshBtConnectionManager.MeshBootAutoConnectListener() {
                @Override
                public void onMeshBootAutoConnectStarted(String reason) {
                    getMapView().post(() -> {
                        requestMeshScanButtonPulse();
                        updateMeshScanButtonText();
                        appendLog("Looking for last connected MeshCore node...");
                    });
                }

                @Override
                public void onMeshBootAutoConnectFinished(boolean connected) {
                    getMapView().post(() -> {
                        if (connected) {
                            meshConnected = true;
                        }
                        if (!connected) {
                            stopMeshConnectButtonPulse(true);
                            updateMeshScanButtonText();
                            appendLog("Last MeshCore node not found — tap Scan and Connect.");
                        } else {
                            updateMeshScanButtonText();
                        }
                        scheduleStartupTransmitAfterMeshBoot(
                                connected ? 0L : STARTUP_TRANSMIT_AFTER_MESH_GIVEUP_MS);
                    });
                }
            });
            meshBtManager.addListener(new BtConnectionManager.ConnectionListener() {
                @Override
                public void onConnected(BluetoothDevice device) {
                    String name = device != null
                            ? resolveMeshDeviceDisplayName(getMapView().getContext(), device)
                            : "MeshCore";
                    meshConnected = true;
                    meshGpsEnabledState = null;
                    meshGpsEnableRequested =
                            getMeshUseGpsForPositionPreference(getMapView().getContext());
                    meshSendPositionWithAdvertState = null;
                    meshSendPositionWithAdvertRequested =
                            getMeshSendPositionWithAdvertPreference(getMapView().getContext());
                    meshNodeSettingsState = null;
                    if (device != null) {
                        Context ctx = getMapView().getContext();
                        BluetoothDeviceRegistry.recordConnection(ctx, device, false);
                        BluetoothDeviceRegistry.setMeshConnectTargetAddress(ctx, device.getAddress());
                    }
                    getMapView().post(() -> {
                        stopMeshConnectButtonPulse(true);
                        updateMeshConnectionUI(true, name);
                        applyTransmitTogglesForConnectivity(true, startupTransmitWindowOpen);
                        applyActiveTransmitTransport();
                        if (startupTransmitWindowOpen) {
                            scheduleStartupTransmitAfterMeshBoot(0L);
                        }
                        handlePreferredTransmitTransportConnected();
                        ensureExclusiveTransmitState(false);
                        ChatBridge.collapseAllCallsignAliasDuplicates();
                        appendLog("MeshCore connected to " + name);
                        updateMeshScanButtonText();
                        scheduleMeshGpsAugmentTick();
                        scheduleMeshCallsignPositionSync();
                        getMapView().postDelayed(() -> syncDeviceContactsCacheInBackground(),
                                DEVICE_CONTACTS_CONNECT_SYNC_DELAY_MS);
                        getMapView().postDelayed(() -> restoreJoinedRoomSessions(),
                                ROOM_CONTACT_ADD_DELAY_MS);
                        getMapView().postDelayed(() -> refreshJoinedRoomContactNamesFromRadio(),
                                DEVICE_CONTACTS_CONNECT_SYNC_DELAY_MS + 1500L);
                        scheduleRestoreMeshChatUi();
                    });
                    meshBtManager.queryMeshGpsEnabled();
                    meshBtManager.requestSelfInfo();
                    meshBtManager.requestAllChannelInfo();
                    if (meshGpsEnableRequested) {
                        meshBtManager.setMeshGpsEnabled(true);
                    }
                    if (meshSendPositionWithAdvertRequested) {
                        meshBtManager.setSendPositionWithAdvertEnabled(true);
                    }
                }

                @Override
                public void onDisconnected(String reason) {
                    meshConnected = false;
                    joinedRoomRestoredThisSession = false;
                    clearRoomLoginStateForDisconnect();
                    meshGpsEnabledState = null;
                    meshGpsEnableRequested =
                            getMeshUseGpsForPositionPreference(getMapView().getContext());
                    meshSendPositionWithAdvertState = null;
                    meshSendPositionWithAdvertRequested =
                            getMeshSendPositionWithAdvertPreference(getMapView().getContext());
                    meshNodeSettingsState = null;
                    getMapView().post(() -> {
                        stopMeshConnectButtonPulse(true);
                        updateMeshConnectionUI(false, null);
                        applyActiveTransmitTransport();
                        applyTransmitTogglesForConnectivity(true, false);
                        handlePreferredTransmitTransportDisconnected(PreferredTransmitTransport.MESH);
                        appendLog("MeshCore disconnected: " + reason);
                        updateMeshChannelButtonLabel();
                        updateMeshScanButtonText();
                        scheduleMeshGpsAugmentTick();
                        scheduleMeshCallsignPositionSync();
                    });
                }

                @Override
                public void onError(String error) {
                    getMapView().post(() -> {
                        stopMeshConnectButtonPulse(true);
                        appendLog("MeshCore error: " + error);
                        updateMeshScanButtonText();
                    });
                }

                @Override
                public void onDeviceFound(BluetoothDevice device) {
                    if (device != null) {
                        String addr = device.getAddress();
                        for (BluetoothDevice existing : meshFoundDevices) {
                            if (existing != null && addr != null
                                    && addr.equalsIgnoreCase(existing.getAddress())) {
                                return;
                            }
                        }
                        meshFoundDevices.add(device);
                    }
                }

                @Override
                public void onScanComplete() {
                    getMapView().post(() -> {
                        stopMeshConnectButtonPulse(true);
                        showMeshDevicePicker();
                    });
                }
            });
            meshBtManager.addMeshStateListener(new MeshBtConnectionManager.MeshStateListener() {
                @Override
                public void onMeshGpsStateChanged(boolean enabled) {
                    meshGpsEnabledState = enabled;
                    meshGpsEnableRequested = enabled;
                    getMapView().post(() -> {
                        setMeshUseGpsForPositionPreference(enabled);
                        updateMeshGpsControlsUi();
                        appendLog("Use Meschore GPS for position "
                                + (enabled ? "enabled" : "disabled"));
                        if (enabled) {
                            removeMeshNodeMapPositionMarker(true);
                        }
                        scheduleMeshGpsAugmentTick();
                        scheduleMeshCallsignPositionSync();
                    });
                }

                @Override
                public void onSendPositionWithAdvertChanged(boolean enabled) {
                    meshSendPositionWithAdvertState = enabled;
                    meshSendPositionWithAdvertRequested = enabled;
                    getMapView().post(() -> {
                        setMeshSendPositionWithAdvertPreference(enabled);
                        updateMeshGpsControlsUi();
                        appendLog("Send Position With Advert "
                                + (enabled ? "enabled" : "disabled"));
                        scheduleMeshCallsignPositionSync();
                    });
                }

                @Override
                public void onMeshNodeSettingsUpdated(MeshBtConnectionManager.MeshNodeSettings settings) {
                    meshNodeSettingsState = settings;
                    getMapView().post(() -> {
                        refreshMeshNodeSettingsDialogFromState(false);
                        updateMeshNodeMapPositionMarkerLabel();
                    });
                }

                @Override
                public void onMeshSelfLocationUpdated(MeshBtConnectionManager.MeshLocationFix fix) {
                    if (fix == null || !fix.isValid()) {
                        return;
                    }
                    getMapView().post(() -> appendLog(String.format(
                            Locale.US,
                            "MeshCore fix %.5f, %.5f",
                            fix.latitude, fix.longitude)));
                }

                @Override
                public void onMeshBatteryUpdated(int batteryPercent, int batteryMv) {
                    meshBatteryPercent = batteryPercent;
                    getMapView().post(() -> updateMeshBatteryUi(batteryPercent));
                }
            });
        }
    }

    public void setCotBridge(CotBridge cotBridge) {
        this.cotBridge = cotBridge;
        if (this.cotBridge != null) {
            this.cotBridge.setWifiTransmitEnabled(wifiTransmitEnabled);
        }
        applyActiveTransmitTransport();
    }

    public void setChatBridge(ChatBridge chatBridge) {
        this.chatBridge = chatBridge;
        applyActiveTransmitTransport();
    }

    public void setEncryptionManager(EncryptionManager encryptionManager) {
        this.encryptionManager = encryptionManager;
    }

    public void setRadioControlManager(UVProRadioControlManager radioControlManager) {
        this.radioControlManager = radioControlManager;
        radioGpsAugmentController.install(btManager, radioControlManager, getMapView());
        radioGpsAugmentController.setAugmentEnabled(
                isAugmentGpsPreferenceEnabled(getMapView().getContext()));
        if (this.radioControlManager != null) {
            this.radioControlManager.setSelectionListener(spec ->
                    getMapView().post(this::updateSelectedRepeaterUi));
            this.radioControlManager.setRadioEventListener(eventType -> getMapView().post(() -> {
                // Firmware emits channel/settings events when a user changes channel group
                // directly on the radio. Re-read current group first, then refresh grid.
                if (eventType == 5 || eventType == 6) {
                    refreshChannelGroupFromRadioAsync(true);
                } else {
                    refreshChannelGridAsync();
                }
            }));
        }
    }

    public void setPacketTerminalReceiver(PacketTerminalDropDownReceiver packetTerminalReceiver) {
        this.packetTerminalReceiver = packetTerminalReceiver;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (ACTION_QR_CHANNEL_RESULT.equals(action)) {
            String content = intent.getStringExtra(EXTRA_QR_RESULT);
            getMapView().post(() -> handleQrChannelResult(content));
            return;
        }
        if (SHOW_PLUGIN.equals(action)
                && intent.getBooleanExtra(EXTRA_MESH_NODE_POSITION_PICK_RESULT, false)) {
            handleMeshNodePositionPickResult(intent);
            return;
        }
        if (SHOW_PLUGIN.equals(action) || SHOW_PLUGIN_CHANNEL_CONTROL.equals(action)) {
            pendingOpenToChannelControl = SHOW_PLUGIN_CHANNEL_CONTROL.equals(action);
            showDropDown(createView(),
                    HALF_WIDTH, FULL_HEIGHT,
                    FULL_WIDTH, HALF_HEIGHT,
                    false, this);
            if (pendingOpenToChannelControl) {
                // Some ATAK builds do not reliably fire onDropDownVisible on first open.
                scheduleScrollToRepeaterLoadSection();
            }
        }
    }

    /**
     * Create the main plugin UI view by inflating the XML layout.
     */
    private View createView() {
        LayoutInflater inflater = LayoutInflater.from(pluginContext);
        rootView = inflater.inflate(
                pluginContext.getResources().getIdentifier(
                        "uvpro_dropdown", "layout",
                        pluginContext.getPackageName()),
                null);

        // Bind views
        bindViews();
        meshChatUiRestorePending = true;
        loadMeshChannelHistoryIfNeeded();
        // MeshStatusOverlay is installed once from UVProMapComponent on startup.
        // Do NOT call install() here — repeated panel opens would create duplicate widgets.

        // Restore actual connection state (survives dropdown close/reopen)
        if (btManager.isConnected()) {
            updateConnectionUI(true, btManager.getConnectedDeviceName());
        } else {
            updateConnectionUI(false, null);
        }
        if (meshBtManager != null && meshBtManager.isConnected()) {
            meshConnected = true;
            updateMeshConnectionUI(true, meshBtManager.getConnectedDeviceName());
            meshGpsEnabledState = meshBtManager.getMeshGpsEnabled();
            if (meshGpsEnabledState == null) {
                meshGpsEnableRequested = getMeshUseGpsForPositionPreference(getMapView().getContext());
            } else {
                meshGpsEnableRequested = Boolean.TRUE.equals(meshGpsEnabledState);
            }
            meshSendPositionWithAdvertState = meshBtManager.getSendPositionWithAdvertEnabled();
            if (meshSendPositionWithAdvertState == null) {
                meshSendPositionWithAdvertRequested =
                        getMeshSendPositionWithAdvertPreference(getMapView().getContext());
            } else {
                meshSendPositionWithAdvertRequested =
                        Boolean.TRUE.equals(meshSendPositionWithAdvertState);
            }
            meshNodeSettingsState = meshBtManager.getLatestNodeSettings();
            meshBtManager.queryMeshGpsEnabled();
            meshBtManager.requestSelfInfo();
            meshBtManager.requestAllChannelInfo();
            meshChannelNames.putAll(meshBtManager.getKnownChannelNamesSnapshot());
            if (meshGpsEnableRequested) {
                meshBtManager.setMeshGpsEnabled(true);
            }
            restoreJoinedRoomSessions();
            scheduleRestoreMeshChatUi();
        } else {
            meshConnected = false;
            meshGpsEnabledState = null;
            meshGpsEnableRequested = getMeshUseGpsForPositionPreference(getMapView().getContext());
            meshSendPositionWithAdvertState = null;
            meshSendPositionWithAdvertRequested =
                    getMeshSendPositionWithAdvertPreference(getMapView().getContext());
            meshNodeSettingsState = null;
            updateMeshConnectionUI(false, null);
        }
        updateMeshGpsControlsUi();
        Context mapCtx = getMapView().getContext();
        wifiTransmitEnabled = isWifiTransmitPreferenceEnabled(mapCtx);
        resolvePreferredTransmitTransportFromPreferences(mapCtx);
        applyTransmitTogglesForConnectivity(false, false);
        syncTransmitSwitchesUi();

        // Set callsign from ATAK self marker
        String callsign = MapView.getMapView().getSelfMarker().getMetaString("callsign","UNKNOWN");
        if (callsignText != null) {
            callsignText.setText(callsign);
        }
        updateMeshUseCallsignLocationLabel(callsign);

        // Make log scrollable inside the outer ScrollView
        if (logText != null) {
            logText.setMovementMethod(new ScrollingMovementMethod());
            logText.setOnTouchListener((v, event) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            });
        }

        // Load saved state into switches BEFORE attaching listeners
        updateStatusFields();
        // Restore packet counts in UI
        updatePacketCount();
        updateContactCount();
        // Now attach change listeners so user interactions are wired
        setupListeners();
        scheduleMeshGpsAugmentTick();
        scheduleMeshCallsignPositionSync();
        updateDigitalEditGuardUi();
        updateDigitalOnlyButtonUi();
        updateTxPowerButtonUi();
        updateScanButtonText();
        if (btManager.isBootAutoConnectResolving() && !btManager.isConnected()) {
            requestScanConnectButtonPulse();
        }
        if (meshBtManager != null && meshBtManager.isMeshBootAutoConnectResolving()
                && !meshBtManager.isConnected()) {
            requestMeshScanButtonPulse();
        }
        updateMeshScanButtonText();
        updateMeshChannelButtonLabel();
        updateSelectedRepeaterUi();
        refreshChannelGridFullAsync();
        refreshLogView();
        appendConnectionStatusLog("Plugin panel opened");
        registerBeaconSettingsReceiver();
        return rootView;
    }

    private void registerBeaconSettingsReceiver() {
        if (beaconSettingsReceiver != null) {
            return;
        }
        beaconSettingsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || getMapView() == null) {
                    return;
                }
                if (!UVProMapComponent.ACTION_BEACON_INTERVAL_CHANGED.equals(intent.getAction())) {
                    return;
                }
                getMapView().post(() -> updateStatusFields());
            }
        };
        try {
            AtakBroadcast.DocumentedIntentFilter filter =
                    new AtakBroadcast.DocumentedIntentFilter();
            filter.addAction(UVProMapComponent.ACTION_BEACON_INTERVAL_CHANGED);
            AtakBroadcast.getInstance().registerReceiver(beaconSettingsReceiver, filter);
        } catch (Exception ignored) {
            beaconSettingsReceiver = null;
        }
    }

    private void unregisterBeaconSettingsReceiver() {
        if (beaconSettingsReceiver == null) {
            return;
        }
        try {
            AtakBroadcast.getInstance().unregisterReceiver(beaconSettingsReceiver);
        } catch (Exception ignored) {
        }
        beaconSettingsReceiver = null;
    }

    private void bindViews() {
        android.widget.TextView headerVersion = rootView.findViewById(getId("header_version"));
        if (headerVersion != null) {
            headerVersion.setText("v" + com.uvpro.plugin.BuildConfig.VERSION_NAME);
        }

        statusDot = rootView.findViewById(getId("status_dot"));
        statusText = rootView.findViewById(getId("status_text"));
        deviceRow = rootView.findViewById(getId("device_row"));
        deviceName = rootView.findViewById(getId("device_name"));
        uvproBatteryIcon = rootView.findViewById(getId("uvpro_battery_icon"));
        uvproBatteryPct = rootView.findViewById(getId("uvpro_battery_pct"));
        meshStatusDot = rootView.findViewById(getId("mesh_status_dot"));
        meshStatusText = rootView.findViewById(getId("mesh_status_text"));
        meshDeviceRow = rootView.findViewById(getId("mesh_device_row"));
        meshDeviceName = rootView.findViewById(getId("mesh_device_name"));
        meshBatteryIcon = rootView.findViewById(getId("mesh_battery_icon"));
        meshBatteryPct = rootView.findViewById(getId("mesh_battery_pct"));
        callsignText = rootView.findViewById(getId("text_callsign"));
        contactsText = rootView.findViewById(getId("text_contacts"));
        packetsText = rootView.findViewById(getId("text_packets"));
        receiveRssiText = rootView.findViewById(getId("text_receive_rssi"));
        receiveRssiMeterBlock = rootView.findViewById(getId("rssi_meter_block"));
        receiveRssiMeterFrame = rootView.findViewById(getId("frame_receive_rssi"));
        receiveRssiFillGreen = rootView.findViewById(getId("view_receive_rssi_fill_green"));
        receiveRssiFillRed = rootView.findViewById(getId("view_receive_rssi_fill_red"));
        receiveRssiScaleFrame = rootView.findViewById(getId("frame_receive_rssi_scale"));
        receiveRssiScaleBuilt = false;
        ensureReceiveRssiScaleMarks();
        logText = rootView.findViewById(getId("text_log"));
        encryptionStatusText = rootView.findViewById(getId("text_encryption_status"));
        beaconIntervalText = rootView.findViewById(getId("text_beacon_interval"));
        gpsBeaconIntervalLabel = rootView.findViewById(getId("text_gps_beacon_interval_label"));
        rowBeaconInterval = rootView.findViewById(getId("row_beacon_interval"));
        if (rowBeaconInterval != null) {
            rowBeaconInterval.setClickable(true);
            rowBeaconInterval.setFocusable(true);
            rowBeaconInterval.setOnClickListener(v -> showBeaconIntervalPicker());
        }
        switchSmartBeacon = rootView.findViewById(getId("switch_smart_beacon"));
        btnManagePluginBeaconSettings = rootView.findViewById(getId("btn_manage_plugin_beacon_settings"));
        btnAprsBeaconArm = rootView.findViewById(getId("btn_aprs_beacon_arm"));
        textAprsStatusCall = rootView.findViewById(getId("text_aprs_status_call"));
        imageAprsStatusIcon = rootView.findViewById(getId("image_aprs_status_icon"));
        textAprsStatusIcon = rootView.findViewById(getId("text_aprs_status_icon"));
        textAprsStatusMessage = rootView.findViewById(getId("text_aprs_status_message"));
        switchAprsDisableAtak = rootView.findViewById(getId("switch_aprs_disable_atak"));
        btnSendBeacon = rootView.findViewById(getId("btn_send_beacon"));
        btnSendPing = rootView.findViewById(getId("btn_send_ping"));
        btnSendAprsBeacon = rootView.findViewById(getId("btn_send_aprs_beacon"));
        btnClearAprsContacts = rootView.findViewById(getId("btn_clear_aprs_contacts"));
        btnEditAprsSettings = rootView.findViewById(getId("btn_edit_aprs_settings"));
        switchMeshShowRepeaters = rootView.findViewById(getId("switch_mesh_show_repeaters"));
        switchMeshShowNodes = rootView.findViewById(getId("switch_mesh_show_nodes"));
        switchMeshSendPositionWithAdvert = rootView.findViewById(
                getId("switch_mesh_send_position_with_advert"));
        switchMeshUseCallsignLocation = rootView.findViewById(
                getId("switch_mesh_use_callsign_location"));
        textMeshUseCallsignLocation = rootView.findViewById(
                getId("text_mesh_use_callsign_location"));
        btnAddMeshChannel = rootView.findViewById(getId("btn_add_mesh_channel"));
        btnMeshContacts = rootView.findViewById(getId("btn_mesh_contacts"));
        stripMeshChannels = rootView.findViewById(getId("strip_mesh_channels"));
        meshChannelTitleView = rootView.findViewById(getId("text_mesh_channel_title"));
        meshChannelLogText = rootView.findViewById(getId("text_mesh_channel_log"));
        editMeshChannelMessage = rootView.findViewById(getId("edit_mesh_channel_message"));
        btnMeshChannelSend = rootView.findViewById(getId("btn_mesh_channel_send"));
        btnExpandMeshChannelChat = rootView.findViewById(getId("btn_expand_mesh_channel_chat"));
        rowMeshChannelInput = rootView.findViewById(getId("row_mesh_channel_input"));
        if (meshChannelLogText != null) {
            meshChannelLogText.setMovementMethod(new ScrollingMovementMethod());
            meshChannelLogText.setOnTouchListener((v, event) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            });
        }
        btnMeshcoreSetNodePositionMap = rootView.findViewById(getId("btn_meshcore_set_node_position_map"));
        btnMeshcoreSendAdvert = rootView.findViewById(getId("btn_meshcore_send_advert"));
        btnDiscoverRepeaters = rootView.findViewById(getId("btn_discover_repeaters"));
        btnClearMeshContacts = rootView.findViewById(getId("btn_clear_mesh_contacts"));
        btnMeshNodeSettings = rootView.findViewById(getId("btn_mesh_node_settings"));
        teamColorText = rootView.findViewById(getId("text_team_color"));
        btnScan = rootView.findViewById(getId("btn_scan"));
        btnDisconnect = rootView.findViewById(getId("btn_disconnect"));
        btnMeshScan = rootView.findViewById(getId("btn_mesh_scan"));
        btnMeshDisconnect = rootView.findViewById(getId("btn_mesh_disconnect"));
        btnUpdateGpsFromMeshcore = rootView.findViewById(getId("btn_update_gps_from_meshcore"));
        btnUpdateGpsFromRadio = rootView.findViewById(getId("btn_update_gps_from_radio"));
        switchAugmentGpsFromRadio = rootView.findViewById(getId("switch_augment_gps_from_radio"));
        btnLoadSelectedRepeater = rootView.findViewById(getId("btn_load_selected_repeater"));
        btnTxPower = rootView.findViewById(getId("btn_tx_power"));
        btnDigitalOnlyMode = rootView.findViewById(getId("btn_digital_only_mode"));
        btnRadioSilence = rootView.findViewById(getId("btn_radio_silence"));
        btnRefreshChannels = rootView.findViewById(getId("btn_refresh_channels"));
        btnInitialChannelGroupSetup = rootView.findViewById(getId("btn_initial_channel_group_setup"));
        btnPacketTerminal = rootView.findViewById(getId("btn_packet_terminal"));
        packetTerminalSection = rootView.findViewById(getId("packet_terminal_section"));
        btnChannelGroup = rootView.findViewById(getId("btn_channel_group"));
        btnImportChannels = rootView.findViewById(getId("btn_import_channels"));
        btnExportChannels = rootView.findViewById(getId("btn_export_channels"));
        btnVfoA = rootView.findViewById(getId("btn_vfo_a"));
        btnVfoB = rootView.findViewById(getId("btn_vfo_b"));
        btnDigital = rootView.findViewById(getId("btn_digital"));
        selectedRepeaterText = rootView.findViewById(getId("text_selected_repeater"));
        repeaterLoadScrollAnchor = rootView.findViewById(getId("repeater_load_scroll_anchor"));
        channelsGrid = rootView.findViewById(getId("grid_channels"));
        switchDualWatch = rootView.findViewById(getId("switch_dual_watch"));
        switchDigitalEdit = rootView.findViewById(getId("switch_digital_edit"));
        switchMeshTransmit = rootView.findViewById(getId("switch_mesh_transmit"));
        switchUvProTransmit = rootView.findViewById(getId("switch_uvpro_transmit"));
        switchWifiTransmit = rootView.findViewById(getId("switch_wifi_transmit"));
        switchMeshEnableGps = rootView.findViewById(getId("switch_mesh_enable_gps"));
        switchMeshEnableGpsMeshcore = rootView.findViewById(getId("switch_mesh_enable_gps_meshcore"));
        switchMeshUseCustomNodePosition = rootView.findViewById(
                getId("switch_mesh_use_custom_node_position"));
        switchAugmentGpsFromMeshcore = rootView.findViewById(getId("switch_augment_gps_from_meshcore"));
        rowAugmentGpsFromMeshcore = rootView.findViewById(getId("row_augment_gps_from_meshcore"));

        // Interactive switches
        switchEncryption = rootView.findViewById(getId("switch_encryption"));
    }

    private void setupListeners() {
        btnScan.setOnClickListener(v -> onScanOrConnectClicked());

        btnDisconnect.setOnClickListener(v -> {
            btManager.disconnect();
        });
        if (btnMeshScan != null) {
            btnMeshScan.setOnClickListener(v -> onMeshScanOrConnectClicked());
        }
        if (btnMeshDisconnect != null) {
            btnMeshDisconnect.setOnClickListener(v -> onMeshDisconnectClicked());
        }
        if (btnMeshChannelSend != null) {
            btnMeshChannelSend.setOnClickListener(v -> sendInlineMeshChannelText());
        }
        if (btnExpandMeshChannelChat != null) {
            btnExpandMeshChannelChat.setOnClickListener(v -> showExpandedMeshChannelChatDialog());
        }
        if (btnAddMeshChannel != null) {
            btnAddMeshChannel.setOnClickListener(v -> showChannelManagementDialog());
        }
        if (btnMeshContacts != null) {
            btnMeshContacts.setOnClickListener(v -> showDeviceContactsDialog());
        }
        if (btnMeshcoreSetNodePositionMap != null) {
            btnMeshcoreSetNodePositionMap.setOnClickListener(v -> startMeshNodePositionMapPick());
        }
        if (btnMeshcoreSendAdvert != null) {
            btnMeshcoreSendAdvert.setOnClickListener(v -> onMeshcoreSendAdvertClicked());
        }
        if (btnDiscoverRepeaters != null) {
            btnDiscoverRepeaters.setOnClickListener(v -> onDiscoverRepeatersClicked());
        }
        if (btnClearMeshContacts != null) {
            btnClearMeshContacts.setOnClickListener(v -> confirmClearAllMeshContacts());
        }
        if (btnMeshNodeSettings != null) {
            btnMeshNodeSettings.setOnClickListener(v -> showMeshNodeSettingsDialog());
        }
        if (switchMeshTransmit != null) {
            switchMeshTransmit.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressTransportSwitchCallbacks || !buttonView.isPressed()) {
                    return;
                }
                setMeshTransmitEnabled(isChecked, true, true);
            });
        }
        if (switchUvProTransmit != null) {
            switchUvProTransmit.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressTransportSwitchCallbacks || !buttonView.isPressed()) {
                    return;
                }
                setUvproTransmitEnabled(isChecked, true, true);
            });
        }
        if (switchWifiTransmit != null) {
            switchWifiTransmit.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressTransportSwitchCallbacks || !buttonView.isPressed()) {
                    return;
                }
                setWifiTransmitEnabled(isChecked, true);
            });
        }
        if (btnUpdateGpsFromRadio != null) {
            btnUpdateGpsFromRadio.setOnClickListener(v -> requestManualRadioGpsUpdate());
        }
        if (btnUpdateGpsFromMeshcore != null) {
            btnUpdateGpsFromMeshcore.setOnClickListener(v -> requestManualMeshGpsUpdate());
        }
        if (switchMeshEnableGps != null) {
            switchMeshEnableGps.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressMeshGpsSwitchCallbacks || !buttonView.isPressed()) {
                    return;
                }
                onMeshGpsToggleChanged(isChecked);
            });
        }
        if (switchMeshEnableGpsMeshcore != null) {
            switchMeshEnableGpsMeshcore.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressMeshGpsSwitchCallbacks || !buttonView.isPressed()) {
                    return;
                }
                if (isChecked) {
                    setMeshUseCallsignLocationPreference(false);
                    if (switchMeshUseCallsignLocation != null) {
                        switchMeshUseCallsignLocation.setChecked(false);
                    }
                    setMeshUseCustomNodePositionPreference(false);
                    if (switchMeshUseCustomNodePosition != null) {
                        switchMeshUseCustomNodePosition.setChecked(false);
                    }
                }
                onMeshGpsToggleChanged(isChecked);
            });
        }
        if (switchAugmentGpsFromMeshcore != null) {
            switchAugmentGpsFromMeshcore.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                setAugmentMeshPreference(isChecked);
                scheduleMeshGpsAugmentTick();
                appendLog(isChecked
                        ? "MeshCore GPS augment enabled (2 min when phone has no fix)."
                        : "MeshCore GPS augment disabled.");
            });
        }
        if (switchAugmentGpsFromRadio != null) {
            switchAugmentGpsFromRadio.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                setAugmentGpsPreference(isChecked);
                radioGpsAugmentController.setAugmentEnabled(isChecked);
                appendLog(isChecked
                        ? "Radio GPS augment enabled (fallback mode)."
                        : "Radio GPS augment disabled.");
            });
        }
        if (switchMeshShowRepeaters != null) {
            switchMeshShowRepeaters.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                setMeshShowRepeatersPreference(isChecked);
                appendLog("MeshCore repeater map markers " + (isChecked ? "enabled." : "disabled."));
            });
        }
        if (switchMeshShowNodes != null) {
            switchMeshShowNodes.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                setMeshShowNodesPreference(isChecked);
                appendLog("MeshCore node map markers " + (isChecked ? "enabled." : "disabled."));
            });
        }
        if (switchMeshSendPositionWithAdvert != null) {
            switchMeshSendPositionWithAdvert.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressMeshSendPositionWithAdvertSwitchCallbacks || !buttonView.isPressed()) {
                    return;
                }
                if (meshBtManager == null || !meshBtManager.isConnected()) {
                    return;
                }
                meshSendPositionWithAdvertRequested = isChecked;
                if (!isChecked) {
                    meshSendPositionWithAdvertState = Boolean.FALSE;
                }
                updateMeshGpsControlsUi();
                appendLog("Setting Send Position With Advert "
                        + (isChecked ? "ON..." : "OFF..."));
                setMeshSendPositionWithAdvertPreference(isChecked);
                meshBtManager.setSendPositionWithAdvertEnabled(isChecked);
                scheduleMeshCallsignPositionSync();
                if (isChecked) {
                    pushPhoneLocationToMeshNodeIfNeeded(true);
                }
                meshBtManager.requestSelfInfo();
            });
        }
        if (switchMeshUseCallsignLocation != null) {
            switchMeshUseCallsignLocation.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                if (isChecked) {
                    setMeshUseCustomNodePositionPreference(false);
                    if (switchMeshUseCustomNodePosition != null) {
                        switchMeshUseCustomNodePosition.setChecked(false);
                    }
                    forceDisableMeshGpsPositionSource();
                }
                setMeshUseCallsignLocationPreference(isChecked);
                appendLog(isChecked
                        ? "Node advert position source: ATAK callsign location (dynamic)."
                        : "Node advert position source: node GPS -> map-set position -> none.");
                if (isChecked) {
                    removeMeshNodeMapPositionMarker(true);
                }
                scheduleMeshCallsignPositionSync();
                if (isChecked) {
                    pushPhoneLocationToMeshNodeIfNeeded(true);
                }
            });
        }
        if (switchMeshUseCustomNodePosition != null) {
            switchMeshUseCustomNodePosition.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                if (isChecked) {
                    setMeshUseCallsignLocationPreference(false);
                    if (switchMeshUseCallsignLocation != null) {
                        switchMeshUseCallsignLocation.setChecked(false);
                    }
                    forceDisableMeshGpsPositionSource();
                }
                setMeshUseCustomNodePositionPreference(isChecked);
                updateMeshGpsControlsUi();
                appendLog(isChecked
                        ? "Custom node position controls enabled."
                        : "Custom node position controls disabled.");
            });
        }

        // --- Encryption switch ---
        if (switchEncryption != null) {
            switchEncryption.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                SharedPreferences prefs = PreferenceManager
                        .getDefaultSharedPreferences(getMapView().getContext());
                prefs.edit().putBoolean(SettingsFragment.PREF_ENCRYPTION_ENABLED, isChecked).apply();
                syncEncryptionFromSettings();
                updateEncryptionStatus();
                if (!isChecked) {
                    appendLog("Encryption disabled");
                } else {
                    String pass = SettingsFragment.getEncryptionPassphrase(
                            getMapView().getContext());
                    if (pass != null && !pass.isEmpty()) {
                        appendLog("Encryption enabled (AES-256-GCM)");
                    } else {
                        appendLog("Encryption enabled — set shared secret in Tool Preferences");
                    }
                }
            });
        }

        // Quick action buttons
        if (btnSendBeacon != null) {
            btnSendBeacon.setOnClickListener(v -> sendManualBeacon());
        }
        if (btnSendPing != null) {
            btnSendPing.setOnClickListener(v -> sendPing());
        }

        View btnSettings = rootView.findViewById(getId("btn_settings"));
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> openPluginToolPreferences());
        }
        if (btnPacketTerminal != null) {
            btnPacketTerminal.setOnClickListener(v -> togglePacketTerminalSection());
        }
        if (switchSmartBeacon != null) {
            switchSmartBeacon.setOnCheckedChangeListener(smartBeaconCheckedListener);
        }
        if (btnManagePluginBeaconSettings != null) {
            btnManagePluginBeaconSettings.setOnClickListener(v -> openPluginRadioSettings());
        }
        wireAprsSection();

        if (btnLoadSelectedRepeater != null) {
            btnLoadSelectedRepeater.setOnClickListener(v -> armSelectedRepeaterLoad());
        }
        if (btnTxPower != null) {
            btnTxPower.setOnClickListener(v -> cycleTxPower());
        }
        if (btnDigitalOnlyMode != null) {
            btnDigitalOnlyMode.setOnClickListener(v -> onDigitalOnlyModeClick());
            btnDigitalOnlyMode.setOnLongClickListener(v -> {
                if (digitalOnlyActive) {
                    disableDigitalOnlyMode();
                    return true;
                }
                return false;
            });
        }
        if (btnRadioSilence != null) {
            btnRadioSilence.setOnClickListener(v ->
                    Toast.makeText(getMapView().getContext(),
                            "Long press to toggle Radio Silence.",
                            Toast.LENGTH_SHORT).show());
            btnRadioSilence.setOnLongClickListener(v -> {
                boolean enabled = !isRadioSilenceEnabled();
                setRadioSilenceOnBothTransports(enabled);
                updateRadioSilenceButtonUi();
                refreshChannelGridAsync();
                appendLog(enabled
                        ? "Radio Silence ON: RF TX/ACKs blocked; control and RX active."
                        : "Radio Silence OFF: RF TX restored.");
                return true;
            });
        }

        if (btnRefreshChannels != null) {
            btnRefreshChannels.setOnClickListener(v -> refreshChannelGridFullAsync());
        }
        if (btnInitialChannelGroupSetup != null) {
            btnInitialChannelGroupSetup.setOnClickListener(v ->
                    Toast.makeText(getMapView().getContext(),
                            "Long press to run Initial Channel Setup.",
                            Toast.LENGTH_SHORT).show());
            btnInitialChannelGroupSetup.setOnLongClickListener(v -> {
                runInitialChannelGroupSetupAsync();
                return true;
            });
        }
        if (btnChannelGroup != null) {
            btnChannelGroup.setOnClickListener(v -> cycleChannelGroup());
        }
        if (btnImportChannels != null) {
            btnImportChannels.setOnClickListener(v -> showImportChannelsPicker());
        }
        if (btnExportChannels != null) {
            btnExportChannels.setOnClickListener(v -> exportCurrentGroupChannels());
        }

        if (switchDualWatch != null) {
            switchDualWatch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (dualWatchProgrammaticUpdate) {
                    return;
                }
                applyDualWatch(isChecked);
            });
            switchDualWatch.setText("");
        }

        if (btnVfoA != null) {
            btnVfoA.setOnClickListener(v -> {
                channelTargetDigital = false;
                selectedTarget = TARGET_A;
                lastAnalogTarget = TARGET_A;
                activeVfoB = false;
                updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                        lastDualWatchEnabled, txVfoB, lastHasRxFocus);
                rerenderGridFromLastSnapshot();
            });
            btnVfoA.setOnLongClickListener(v -> {
                applyTxSelection(false);
                return true;
            });
        }
        if (btnVfoB != null) {
            btnVfoB.setOnClickListener(v -> {
                channelTargetDigital = false;
                selectedTarget = TARGET_B;
                lastAnalogTarget = TARGET_B;
                activeVfoB = true;
                updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                        lastDualWatchEnabled, txVfoB, lastHasRxFocus);
                rerenderGridFromLastSnapshot();
            });
            btnVfoB.setOnLongClickListener(v -> {
                if (btnVfoB.getVisibility() != View.VISIBLE) {
                    return true;
                }
                applyTxSelection(true);
                return true;
            });
        }
        if (btnDigital != null) {
            btnDigital.setOnClickListener(v -> {
                if (!isDigitalEditArmed()) {
                    Toast.makeText(getMapView().getContext(),
                            "Enable 'Slide to edit' first.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                channelTargetDigital = true;
                selectedTarget = TARGET_DIGITAL;
                updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                        lastDualWatchEnabled, txVfoB, lastHasRxFocus);
            });
        }
        if (switchDigitalEdit != null) {
            switchDigitalEdit.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!isChecked && selectedTarget == TARGET_DIGITAL) {
                    restoreAnalogEditTarget();
                }
                updateDigitalEditGuardUi();
            });
            switchDigitalEdit.setText("");
            switchDigitalEdit.setChecked(false);
        }
    }

    private void onScanOrConnectClicked() {
        if (btManager.isConnected()) {
            return;
        }
        btManager.cancelBootAutoConnect();
        if (btManager.isConnecting()) {
            appendLog("Cancelling current connection attempt...");
            btManager.cancelConnectionAttempts();
        }
        if (BluetoothAdapter.getDefaultAdapter() == null) {
            appendLog("Bluetooth not available");
            return;
        }
        foundDevices.clear();
        UvProBtDeviceMatcher.clearPickerModelCache();
        dismissRadioPickerDialog();
        updateScanButtonText();
        appendLog("Scanning for UV-PRO / UV-50 / VR-N76 radios...");
        requestScanConnectButtonPulse();
        btManager.startScan();
    }

    private void onMeshScanOrConnectClicked() {
        if (meshBtManager == null) {
            appendLog("MeshCore transport unavailable");
            return;
        }
        meshBtManager.cancelAvailabilityProbes();
        if (meshBtManager.isConnected()) {
            return;
        }
        if (meshBtManager.isConnecting()) {
            appendLog("Cancelling current MeshCore connection attempt...");
            meshBtManager.cancelConnectionAttempts();
            stopMeshConnectButtonPulse(true);
        }
        if (BluetoothAdapter.getDefaultAdapter() == null) {
            appendLog("Bluetooth not available");
            return;
        }
        meshFoundDevices.clear();
        appendLog("Scanning for MeshCore devices...");
        requestMeshScanButtonPulse();
        updateMeshScanButtonText();
        meshBtManager.startScan();
    }

    private void onMeshDisconnectClicked() {
        if (meshBtManager != null) {
            meshBtManager.disconnect();
        }
        stopMeshConnectButtonPulse(true);
        meshConnected = false;
        updateMeshConnectionUI(false, null);
        appendLog("MeshCore disconnected");
    }

    private int dip(Context c, int d) {
        return (int) (d * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private android.graphics.Bitmap generateQrBitmap(String content, int sizePx) {
        try {
            com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
            com.google.zxing.common.BitMatrix matrix =
                    writer.encode(content, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx);
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    sizePx, sizePx, android.graphics.Bitmap.Config.RGB_565);
            for (int x = 0; x < sizePx; x++) {
                for (int y = 0; y < sizePx; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y)
                            ? android.graphics.Color.BLACK : android.graphics.Color.WHITE);
                }
            }
            return bmp;
        } catch (Exception e) {
            android.util.Log.w("UVPro.UI", "QR generation failed", e);
            return null;
        }
    }

    private void updateScanButtonText() {
        if (btnScan == null) return;
        if (btManager.isConnected()) {
            btnScan.setText("Connected");
        } else if (isScanConnectButtonPulsing()
                || btManager.isBootAutoConnectResolving()) {
            btnScan.setText("Scanning");
        } else if (btManager.isConnecting()) {
            btnScan.setText("CANCEL");
        } else {
            btnScan.setText("Scan and Connect");
        }
    }

    private void updateMeshScanButtonText() {
        if (btnMeshScan == null || meshBtManager == null) {
            return;
        }
        if (meshBtManager.isConnected()) {
            btnMeshScan.setText("Connected");
        } else if (isMeshScanButtonPulsing()
                || meshBtManager.isMeshBootAutoConnectResolving()) {
            btnMeshScan.setText("Scanning");
        } else {
            btnMeshScan.setText("Scan and Connect");
        }
    }

    private boolean isScanConnectButtonPulsing() {
        return scanConnectPulseAnimator != null || scanConnectPulsePending;
    }

    private boolean isMeshScanButtonPulsing() {
        return meshConnectPulseAnimator != null || meshScanPulsePending;
    }

    private void onMeshcoreChannelsClicked() {
        // Kept for legacy call-sites; channel strip is now driven by updateMeshChannelButtonLabel.
        if (meshBtManager != null && meshBtManager.isConnected()) {
            meshBtManager.requestAllChannelInfo();
            Map<Integer, String> snapshot = meshBtManager.getKnownChannelNamesSnapshot();
            if (!snapshot.isEmpty()) {
                meshChannelNames.putAll(snapshot);
                persistMeshChannelHistory();
            }
            updateMeshChannelButtonLabel();
        }
    }

    private void buildMeshChannelButtonStrip() {
        if (stripMeshChannels == null) return;
        stripMeshChannels.removeAllViews();
        Context ctx = getMapView().getContext();
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String name = meshChannelNames.get(i);
            if (name != null && !name.trim().isEmpty()
                    && !"ATAK_DATA".equalsIgnoreCase(name.trim())) {
                indices.add(i);
            }
        }
        boolean hasContactTabs = !meshContactChatSessions.isEmpty();
        if (!hasContactTabs && indices.isEmpty()) {
            TextView placeholder = new TextView(ctx);
            placeholder.setText("No channels found. Try connecting first.");
            placeholder.setTextColor(0xFF888888);
            placeholder.setTextSize(11f);
            placeholder.setPadding(8, 4, 8, 4);
            stripMeshChannels.addView(placeholder);
            return;
        }
        int tabCount = meshContactChatSessions.size() + indices.size();
        int tabIndex = 0;
        for (MeshContactChatSession session : meshContactChatSessions.values()) {
            final String pubKeyHex = session.pubKeyHex;
            Button btn = new Button(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (tabIndex < tabCount - 1) {
                lp.setMarginEnd(4);
            }
            btn.setLayoutParams(lp);
            btn.setText(stripContactTabLabel(session.displayName));
            btn.setTextSize(11f);
            btn.setAllCaps(false);
            btn.setPadding(8, 6, 8, 6);
            btn.setMinHeight(0);
            btn.setMinimumHeight(0);
            btn.setTag(pubKeyHex);
            applyMeshChannelButtonStyle(btn, pubKeyHex.equals(activeMeshContactPubKey));
            btn.setOnClickListener(v -> selectMeshContactChat(pubKeyHex));
            stripMeshChannels.addView(btn);
            tabIndex++;
        }
        for (int idx : indices) {
            final int channelIndex = idx;
            String name = meshChannelNames.get(idx);
            Button btn = new Button(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (tabIndex < tabCount - 1) {
                lp.setMarginEnd(4);
            }
            btn.setLayoutParams(lp);
            btn.setText(name);
            btn.setTextSize(11f);
            btn.setAllCaps(false);
            btn.setPadding(8, 6, 8, 6);
            btn.setMinHeight(0);
            btn.setMinimumHeight(0);
            btn.setTag(channelIndex);
            applyMeshChannelButtonStyle(btn, activeMeshContactPubKey == null
                    && channelIndex == meshChannelChatActiveIndex);
            btn.setOnClickListener(v -> {
                clearMeshContactChatMode();
                meshChannelChatActiveIndex = channelIndex;
                openMeshChannelChatDialog(channelIndex);
                buildMeshChannelButtonStrip();
                updateExpandMeshChannelChatButtonState();
            });
            final String channelNameFinal = name;
            btn.setOnLongClickListener(v -> {
                showChannelSettingsMenu(channelIndex, channelNameFinal);
                return true;
            });
            stripMeshChannels.addView(btn);
            tabIndex++;
        }
    }

    private static String stripContactTabLabel(String displayName) {
        if (displayName == null) {
            return "Contact";
        }
        String trimmed = displayName.trim();
        if (trimmed.length() <= 14) {
            return trimmed;
        }
        return trimmed.substring(0, 13) + "…";
    }

    private MeshContactChatSession getActiveContactSession() {
        if (activeMeshContactPubKey == null) {
            return null;
        }
        return findSessionByPubKey(activeMeshContactPubKey);
    }

    private boolean isSessionCurrentlyVisible(@Nullable MeshContactChatSession session) {
        if (session == null || activeMeshContactPubKey == null) {
            return false;
        }
        return session.pubKeyHex.equalsIgnoreCase(activeMeshContactPubKey);
    }

    private void renderRoomChatIfVisible(@Nullable MeshContactChatSession session) {
        if (isSessionCurrentlyVisible(session)) {
            renderMeshContactChatLog();
        }
    }

    private MeshContactChatSession ensureContactChatSession(
            MeshBtConnectionManager.MeshDeviceContact contact) {
        String pubKey = normalizePubKeyHex(contact.pubKeyHex);
        if (pubKey == null || pubKey.length() != 64) {
            pubKey = contact.pubKeyHex != null ? contact.pubKeyHex.trim() : "";
        }
        MeshContactChatSession session = meshContactChatSessions.get(pubKey);
        if (session == null) {
            session = new MeshContactChatSession(pubKey, contact.name);
            if (contact.type == MeshBtConnectionManager.ADV_TYPE_ROOM && meshBtManager != null) {
                Context ctx = getMapView().getContext();
                String addr = meshBtManager.getConnectedDeviceAddress();
                if (ctx != null && addr != null) {
                    java.util.LinkedList<String> saved =
                            MeshContactChatHistoryStore.load(ctx, addr, pubKey);
                    if (!saved.isEmpty()) {
                        session.lines.addAll(saved);
                        removeRoomLoginPlaceholder(session);
                    }
                }
            }
            meshContactChatSessions.put(pubKey, session);
        } else if (contact.name != null && !contact.name.trim().isEmpty()) {
            session.displayName = contact.name.trim();
        }
        session.isRoom = contact.type == MeshBtConnectionManager.ADV_TYPE_ROOM;
        return session;
    }

    private void selectMeshContactChat(String pubKeyHex) {
        MeshContactChatSession session = findSessionByPubKey(pubKeyHex);
        if (session == null) {
            return;
        }
        activeMeshContactPubKey = session.pubKeyHex;
        meshChannelChatActiveIndex = -1;
        showInlineContactChat(session.displayName);
        buildMeshChannelButtonStrip();
        updateExpandMeshChannelChatButtonState();
        if (meshBtManager != null && meshBtManager.isConnected()) {
            MeshJoinedRoomStore.saveActiveRoom(getMapView().getContext(),
                    meshBtManager.getConnectedDeviceAddress(), pubKeyHex);
            MeshLastChatStore.saveRoom(getMapView().getContext(),
                    meshBtManager.getConnectedDeviceAddress(), pubKeyHex);
        }
        if (session.isRoom) {
            beginRoomServerSession(session);
        }
    }

    private void updateMeshChatInputHint() {
        String hint = "Channel message";
        if (activeMeshContactPubKey != null) {
            MeshContactChatSession session = getActiveContactSession();
            if (session != null && session.displayName != null
                    && !session.displayName.trim().isEmpty()) {
                hint = "Message to " + session.displayName.trim();
            }
        } else if (meshChannelChatActiveIndex >= 0) {
            String channelName = meshChannelNames.get(meshChannelChatActiveIndex);
            if (channelName == null || channelName.trim().isEmpty()) {
                channelName = "Channel";
            }
            hint = "Message to " + channelName.trim();
        }
        if (editMeshChannelMessage != null) {
            editMeshChannelMessage.setHint(hint);
        }
        if (editMeshChannelExpandedMessage != null) {
            editMeshChannelExpandedMessage.setHint(hint);
        }
    }

    private void applyMeshChannelButtonStyle(Button btn, boolean selected) {
        if (selected) {
            btn.setBackgroundColor(0xFF00BCD4);
            btn.setTextColor(0xFF000000);
        } else {
            try {
                btn.setBackground(getMapView().getContext().getDrawable(
                        getMapView().getContext().getResources().getIdentifier(
                                "bg_uvpro_mesh_button", "drawable",
                                getMapView().getContext().getPackageName())));
            } catch (Exception e) {
                btn.setBackgroundColor(0xFF37474F);
            }
            btn.setTextColor(0xFFFFFFFF);
        }
    }

    private void onMeshcoreSendAdvertClicked() {
        if (meshBtManager == null || !meshBtManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to a MeshCore node first.", Toast.LENGTH_SHORT).show();
            return;
        }
        pushPhoneLocationToMeshNodeIfNeeded(false);
        if (!meshBtManager.sendSelfAdvert()) {
            Toast.makeText(getMapView().getContext(),
                    "Could not send MeshCore advert.", Toast.LENGTH_SHORT).show();
            return;
        }
        appendLog("MeshCore advert sent (flood).");
        Toast.makeText(getMapView().getContext(),
                "MeshCore advert sent.", Toast.LENGTH_SHORT).show();
    }

    private void onDiscoverRepeatersClicked() {
        if (meshBtManager == null || !meshBtManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to a MeshCore node first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!meshBtManager.sendNodeDiscoverRequest()) {
            Toast.makeText(getMapView().getContext(),
                    "Could not start repeater discovery.", Toast.LENGTH_SHORT).show();
            return;
        }
        appendLog("Discovering nearby repeaters (60s)…");
        Toast.makeText(getMapView().getContext(),
                "Discovering nearby repeaters…", Toast.LENGTH_SHORT).show();
    }

    /**
     * Advert position priority when node GPS is OFF:
     * 1) dynamic ATAK self/callsign location if enabled, else
     * 2) map-picked node position, else
     * 3) no advert position override.
     */
    private boolean pushPhoneLocationToMeshNodeIfNeeded(boolean verboseSkipLog) {
        if (meshBtManager == null || !meshBtManager.isConnected()) {
            return false;
        }
        boolean advertPosOn = meshSendPositionWithAdvertRequested
                || Boolean.TRUE.equals(meshSendPositionWithAdvertState);
        if (!advertPosOn) {
            if (verboseSkipLog) {
                appendLog("Advert position override skipped (Send Position With Advert is OFF).");
            }
            return false;
        }
        boolean nodeGpsOn = meshGpsEnableRequested || Boolean.TRUE.equals(meshGpsEnabledState);
        if (nodeGpsOn) {
            if (verboseSkipLog) {
                appendLog("Advert position source: node GPS.");
            }
            return false;
        }

        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        boolean useCallsign = isMeshUseCallsignLocationPreferenceEnabled(ctx);
        com.atakmap.coremap.maps.coords.GeoPoint gp = null;
        String source = null;
        if (useCallsign) {
            gp = getAtakSelfGeoPoint();
            source = "callsign location";
        } else {
            gp = getMeshMapSetPosition(ctx);
            source = "map-set node position";
        }
        if (gp == null || !gp.isValid()) {
            if (verboseSkipLog) {
                appendLog("Advert position source unavailable (" + source + ").");
            }
            return false;
        }
        boolean ok = meshBtManager.setAdvertLatLon(
                gp.getLatitude(), gp.getLongitude(), gp.getAltitude());
        if (ok) {
            appendLog(String.format(Locale.US,
                    "Pushed %s to node advert: %.5f, %.5f",
                    source, gp.getLatitude(), gp.getLongitude()));
        } else if (verboseSkipLog) {
            appendLog("Advert position push failed.");
        }
        return ok;
    }

    private com.atakmap.coremap.maps.coords.GeoPoint getAtakSelfGeoPoint() {
        MapView mv = getMapView();
        if (mv == null || mv.getSelfMarker() == null
                || !(mv.getSelfMarker() instanceof com.atakmap.android.maps.PointMapItem)) {
            return null;
        }
        return ((com.atakmap.android.maps.PointMapItem) mv.getSelfMarker()).getPoint();
    }

    private void scheduleMeshCallsignPositionSync() {
        MapView mv = getMapView();
        if (mv == null) {
            return;
        }
        mv.removeCallbacks(meshCallsignPositionSyncRunnable);
        Context ctx = mv.getContext();
        boolean shouldRun = meshConnected
                && isMeshUseCallsignLocationPreferenceEnabled(ctx)
                && (meshSendPositionWithAdvertRequested
                || Boolean.TRUE.equals(meshSendPositionWithAdvertState))
                && !(meshGpsEnableRequested || Boolean.TRUE.equals(meshGpsEnabledState));
        if (shouldRun) {
            mv.postDelayed(meshCallsignPositionSyncRunnable, MESH_CALLSIGN_POSITION_PUSH_INTERVAL_MS);
        }
    }

    private void startMeshNodePositionMapPick() {
        if (meshBtManager == null || !meshBtManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to a MeshCore node first.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent callback = new Intent(SHOW_PLUGIN);
        callback.putExtra(EXTRA_MESH_NODE_POSITION_PICK_RESULT, true);
        Intent begin = new Intent(ToolManagerBroadcastReceiver.BEGIN_TOOL);
        begin.putExtra("tool", MapClickTool.TOOL_NAME);
        begin.putExtra("callback", callback);
        begin.putExtra("prompt", "Select node position on map");
        AtakBroadcast.getInstance().sendBroadcast(begin);
        appendLog("Pick a map location for node advert position...");
    }

    private void handleMeshNodePositionPickResult(Intent intent) {
        com.atakmap.coremap.maps.coords.GeoPoint gp = parseGeoPointFromIntent(intent);
        if (gp == null || !gp.isValid()) {
            return;
        }
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        setMeshMapSetPosition(ctx, gp);
        createOrUpdateMeshNodeMapPositionMarker(gp);
        appendLog(String.format(Locale.US, "Saved node map position: %.5f, %.5f",
                gp.getLatitude(), gp.getLongitude()));
        if (!isMeshUseCallsignLocationPreferenceEnabled(ctx)
                && !(meshGpsEnableRequested || Boolean.TRUE.equals(meshGpsEnabledState))) {
            pushPhoneLocationToMeshNodeIfNeeded(true);
        }
        try {
            TextContainer.getTopInstance().closePrompt("Select node position on map");
        } catch (Exception ignored) {
        }
    }

    private com.atakmap.coremap.maps.coords.GeoPoint parseGeoPointFromIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        String point = intent.getStringExtra("point");
        if (point == null || point.trim().isEmpty()) {
            return null;
        }
        String[] parts = point.split(",");
        if (parts.length < 2) {
            return null;
        }
        try {
            double lat = Double.parseDouble(parts[0].trim());
            double lon = Double.parseDouble(parts[1].trim());
            return new com.atakmap.coremap.maps.coords.GeoPoint(lat, lon);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void createOrUpdateMeshNodeMapPositionMarker(com.atakmap.coremap.maps.coords.GeoPoint gp) {
        MapView mv = getMapView();
        if (mv == null || mv.getRootGroup() == null || gp == null || !gp.isValid()) {
            return;
        }
        removeMeshNodeMapPositionMarker(false);
        com.atakmap.android.maps.Marker marker = new com.atakmap.android.maps.Marker(gp, MESH_NODE_MAP_POSITION_UID);
        marker.setType("b-m-p-s-p-i");
        String nodeLabel = resolveMeshNodeDisplayName();
        marker.setTitle(nodeLabel);
        marker.setMetaString("callsign", nodeLabel);
        marker.setMetaBoolean("editable", true);
        marker.setMetaBoolean("movable", false);
        mv.getRootGroup().addItem(marker);
    }

    private String resolveMeshNodeDisplayName() {
        String name = null;
        if (meshNodeSettingsState != null && meshNodeSettingsState.nodeName != null) {
            name = meshNodeSettingsState.nodeName.trim();
        }
        if (name == null || name.isEmpty()) {
            name = "Mesh Node";
        }
        return name;
    }

    private void removeMeshNodeMapPositionMarker(boolean clearStoredPosition) {
        MapView mv = getMapView();
        if (mv != null && mv.getRootGroup() != null) {
            removeMapItemByUidRecursive(mv.getRootGroup(), MESH_NODE_MAP_POSITION_UID);
        }
        if (clearStoredPosition) {
            Context ctx = mv != null ? mv.getContext() : pluginContext;
            clearMeshMapSetPosition(ctx);
        }
    }

    private boolean removeMapItemByUidRecursive(MapGroup group, String uid) {
        if (group == null || uid == null || uid.trim().isEmpty()) {
            return false;
        }
        for (MapItem item : new ArrayList<>(group.getItems())) {
            if (item != null && uid.equals(item.getUID())) {
                group.removeItem(item);
                return true;
            }
        }
        for (MapGroup child : group.getChildGroups()) {
            if (removeMapItemByUidRecursive(child, uid)) {
                return true;
            }
        }
        return false;
    }

    private void updateMeshNodeMapPositionMarkerLabel() {
        MapView mv = getMapView();
        if (mv == null || mv.getRootGroup() == null) {
            return;
        }
        String label = resolveMeshNodeDisplayName();
        updateMapItemTitleByUidRecursive(mv.getRootGroup(), MESH_NODE_MAP_POSITION_UID, label);
    }

    private boolean updateMapItemTitleByUidRecursive(MapGroup group, String uid, String title) {
        if (group == null || uid == null || uid.trim().isEmpty()) {
            return false;
        }
        for (MapItem item : new ArrayList<>(group.getItems())) {
            if (item == null || !uid.equals(item.getUID())) {
                continue;
            }
            if (item instanceof com.atakmap.android.maps.Marker) {
                com.atakmap.android.maps.Marker marker = (com.atakmap.android.maps.Marker) item;
                marker.setTitle(title);
                marker.setMetaString("callsign", title);
            } else {
                item.setMetaString("callsign", title);
            }
            return true;
        }
        for (MapGroup child : group.getChildGroups()) {
            if (updateMapItemTitleByUidRecursive(child, uid, title)) {
                return true;
            }
        }
        return false;
    }

    private void showMeshNodeSettingsDialog() {
        if (meshBtManager == null || !meshBtManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to a MeshCore node first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (meshNodeSettingsDialog == null) {
            buildMeshNodeSettingsDialog();
        }
        meshNodeSettingsDialog.show();
        if (meshNodeSettingsScrollView != null) {
            meshNodeSettingsScrollView.post(() -> {
                meshNodeSettingsScrollView.scrollTo(0, 0);
                meshNodeSettingsScrollView.fullScroll(View.FOCUS_UP);
            });
        }
        refreshMeshNodeSettingsDialogFromState(true);
        appendLog("Polling MeshCore node settings...");
        meshBtManager.requestSelfInfo();
    }

    private void buildMeshNodeSettingsDialog() {
        Context ctx = getMapView().getContext();
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dip(ctx, 12);
        root.setPadding(pad, pad, pad, pad);

        TextView nameLabel = new TextView(ctx);
        nameLabel.setText("Name");
        nameLabel.setTextColor(0xFFFFFFFF);
        root.addView(nameLabel);

        meshNodeSettingsNameField = new EditText(ctx);
        meshNodeSettingsNameField.setHint("Node name");
        meshNodeSettingsNameField.setSingleLine(true);
        root.addView(meshNodeSettingsNameField);

        TextView section = new TextView(ctx);
        section.setText("Radio Settings");
        section.setTextColor(0xFF00BCD4);
        section.setTextSize(14f);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.topMargin = dip(ctx, 12);
        root.addView(section, sectionLp);

        TextView freqLabel = new TextView(ctx);
        freqLabel.setText("Frequency (MHz)");
        freqLabel.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams freqLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        freqLabelLp.topMargin = dip(ctx, 6);
        root.addView(freqLabel, freqLabelLp);

        meshNodeSettingsFrequencyField = new EditText(ctx);
        meshNodeSettingsFrequencyField.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        meshNodeSettingsFrequencyField.setHint("910.525");
        meshNodeSettingsFrequencyField.setSingleLine(true);
        root.addView(meshNodeSettingsFrequencyField);

        TextView bwLabel = new TextView(ctx);
        bwLabel.setText("Bandwidth");
        bwLabel.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams bwLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bwLabelLp.topMargin = dip(ctx, 6);
        root.addView(bwLabel, bwLabelLp);

        meshNodeSettingsBandwidthSpinner = new Spinner(ctx);
        meshNodeSettingsBandwidthSpinner.setAdapter(new ArrayAdapter<>(
                ctx, android.R.layout.simple_spinner_item, meshBandwidthLabels()));
        ((ArrayAdapter<?>) meshNodeSettingsBandwidthSpinner.getAdapter())
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        root.addView(meshNodeSettingsBandwidthSpinner);

        TextView sfLabel = new TextView(ctx);
        sfLabel.setText("Spreading Factor");
        sfLabel.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams sfLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sfLabelLp.topMargin = dip(ctx, 6);
        root.addView(sfLabel, sfLabelLp);

        meshNodeSettingsSfSpinner = new Spinner(ctx);
        meshNodeSettingsSfSpinner.setAdapter(new ArrayAdapter<>(
                ctx, android.R.layout.simple_spinner_item, meshSfLabels()));
        ((ArrayAdapter<?>) meshNodeSettingsSfSpinner.getAdapter())
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        root.addView(meshNodeSettingsSfSpinner);

        TextView crLabel = new TextView(ctx);
        crLabel.setText("Coding Rate");
        crLabel.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams crLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crLabelLp.topMargin = dip(ctx, 6);
        root.addView(crLabel, crLabelLp);

        meshNodeSettingsCrSpinner = new Spinner(ctx);
        meshNodeSettingsCrSpinner.setAdapter(new ArrayAdapter<>(
                ctx, android.R.layout.simple_spinner_item, meshCrLabels()));
        ((ArrayAdapter<?>) meshNodeSettingsCrSpinner.getAdapter())
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        root.addView(meshNodeSettingsCrSpinner);

        TextView txPowerLabel = new TextView(ctx);
        txPowerLabel.setText("Transmit Power (dBm)");
        txPowerLabel.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams txPowerLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        txPowerLabelLp.topMargin = dip(ctx, 6);
        root.addView(txPowerLabel, txPowerLabelLp);

        meshNodeSettingsTxPowerField = new EditText(ctx);
        meshNodeSettingsTxPowerField.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        meshNodeSettingsTxPowerField.setSingleLine(true);
        meshNodeSettingsTxPowerField.setHint("22");
        root.addView(meshNodeSettingsTxPowerField);

        meshNodeSettingsStatus = new TextView(ctx);
        meshNodeSettingsStatus.setTextColor(0xFF90A4AE);
        meshNodeSettingsStatus.setTextSize(11f);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dip(ctx, 8);
        root.addView(meshNodeSettingsStatus, statusLp);

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        meshNodeSettingsScrollView = scroll;

        meshNodeSettingsDialog = new AlertDialog.Builder(ctx)
                .setTitle("Node Settings")
                .setView(scroll)
                .setPositiveButton("Apply", null)
                .setNeutralButton("Refresh", null)
                .setNegativeButton("Close", null)
                .create();
        meshNodeSettingsDialog.setOnShowListener(dialog -> {
            Button apply = meshNodeSettingsDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (apply != null) {
                apply.setOnClickListener(v -> applyMeshNodeSettingsFromDialog());
            }
            Button refresh = meshNodeSettingsDialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            if (refresh != null) {
                refresh.setOnClickListener(v -> {
                    appendLog("Polling MeshCore node settings...");
                    if (meshBtManager != null) {
                        meshBtManager.requestSelfInfo();
                    }
                });
            }
        });
    }

    private void applyMeshNodeSettingsFromDialog() {
        if (meshBtManager == null || !meshBtManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "MeshCore not connected.", Toast.LENGTH_SHORT).show();
            return;
        }
        String nodeName = meshNodeSettingsNameField != null
                ? meshNodeSettingsNameField.getText().toString().trim() : "";
        String freqRaw = meshNodeSettingsFrequencyField != null
                ? meshNodeSettingsFrequencyField.getText().toString().trim() : "";
        String txRaw = meshNodeSettingsTxPowerField != null
                ? meshNodeSettingsTxPowerField.getText().toString().trim() : "";
        if (nodeName.isEmpty() || freqRaw.isEmpty() || txRaw.isEmpty()) {
            Toast.makeText(getMapView().getContext(),
                    "Name, Frequency, and Transmit Power are required.", Toast.LENGTH_SHORT).show();
            return;
        }
        double freqMHz;
        try {
            freqMHz = Double.parseDouble(freqRaw);
        } catch (Exception e) {
            Toast.makeText(getMapView().getContext(),
                    "Invalid frequency value.", Toast.LENGTH_SHORT).show();
            return;
        }
        int txPowerDbm;
        try {
            txPowerDbm = Integer.parseInt(txRaw);
        } catch (Exception e) {
            Toast.makeText(getMapView().getContext(),
                    "Invalid transmit power value.", Toast.LENGTH_SHORT).show();
            return;
        }
        double bwKHz = MESH_BANDWIDTH_OPTIONS_KHZ[
                Math.max(0, meshNodeSettingsBandwidthSpinner != null
                        ? meshNodeSettingsBandwidthSpinner.getSelectedItemPosition() : 0)];
        int sf = MESH_SPREADING_FACTOR_OPTIONS[
                Math.max(0, meshNodeSettingsSfSpinner != null
                        ? meshNodeSettingsSfSpinner.getSelectedItemPosition() : 0)];
        int cr = MESH_CODING_RATE_OPTIONS[
                Math.max(0, meshNodeSettingsCrSpinner != null
                        ? meshNodeSettingsCrSpinner.getSelectedItemPosition() : 0)];

        boolean nameOk = meshBtManager.setNodeAdvertName(nodeName);
        boolean radioOk = meshBtManager.setRadioParams(freqMHz, bwKHz, sf, cr);
        boolean txOk = meshBtManager.setRadioTxPowerDbm(txPowerDbm);
        if (!nameOk || !radioOk || !txOk) {
            Toast.makeText(getMapView().getContext(),
                    "Failed to apply one or more settings.", Toast.LENGTH_SHORT).show();
            return;
        }
        appendLog(String.format(Locale.US,
                "Node settings applied: name=%s freq=%.3f bw=%s sf=%d cr=%d tx=%d dBm",
                nodeName, freqMHz, trimDouble(bwKHz), sf, cr, txPowerDbm));
        Toast.makeText(getMapView().getContext(),
                "Node settings applied.", Toast.LENGTH_SHORT).show();
        meshBtManager.requestSelfInfo();
    }

    private void refreshMeshNodeSettingsDialogFromState(boolean initializing) {
        if (meshNodeSettingsDialog == null || !meshNodeSettingsDialog.isShowing()) {
            return;
        }
        if (meshNodeSettingsState == null) {
            if (meshNodeSettingsStatus != null) {
                meshNodeSettingsStatus.setText("Waiting for node settings response...");
            }
            return;
        }
        if (meshNodeSettingsNameField != null) {
            meshNodeSettingsNameField.setText(meshNodeSettingsState.nodeName != null
                    ? meshNodeSettingsState.nodeName : "");
        }
        if (meshNodeSettingsFrequencyField != null) {
            meshNodeSettingsFrequencyField.setText(String.format(
                    Locale.US, "%.3f", meshNodeSettingsState.frequencyMHz));
        }
        if (meshNodeSettingsBandwidthSpinner != null) {
            meshNodeSettingsBandwidthSpinner.setSelection(
                    nearestBandwidthIndex(meshNodeSettingsState.bandwidthKHz));
        }
        if (meshNodeSettingsSfSpinner != null) {
            meshNodeSettingsSfSpinner.setSelection(indexOfIntOption(
                    MESH_SPREADING_FACTOR_OPTIONS, meshNodeSettingsState.spreadingFactor));
        }
        if (meshNodeSettingsCrSpinner != null) {
            meshNodeSettingsCrSpinner.setSelection(indexOfIntOption(
                    MESH_CODING_RATE_OPTIONS, meshNodeSettingsState.codingRate));
        }
        if (meshNodeSettingsTxPowerField != null) {
            meshNodeSettingsTxPowerField.setText(String.valueOf(meshNodeSettingsState.txPowerDbm));
        }
        if (meshNodeSettingsStatus != null) {
            meshNodeSettingsStatus.setText(String.format(Locale.US,
                    "Node response: %.3f MHz, %s kHz, SF%d, CR%d, TX %d dBm (max %d)",
                    meshNodeSettingsState.frequencyMHz,
                    trimDouble(meshNodeSettingsState.bandwidthKHz),
                    meshNodeSettingsState.spreadingFactor,
                    meshNodeSettingsState.codingRate,
                    meshNodeSettingsState.txPowerDbm,
                    meshNodeSettingsState.maxTxPowerDbm));
        }
        if (!initializing) {
            appendLog("MeshCore node settings updated from node poll.");
        }
    }

    private static String[] meshBandwidthLabels() {
        String[] out = new String[MESH_BANDWIDTH_OPTIONS_KHZ.length];
        for (int i = 0; i < MESH_BANDWIDTH_OPTIONS_KHZ.length; i++) {
            out[i] = trimDouble(MESH_BANDWIDTH_OPTIONS_KHZ[i]) + " kHz";
        }
        return out;
    }

    private static String[] meshSfLabels() {
        String[] out = new String[MESH_SPREADING_FACTOR_OPTIONS.length];
        for (int i = 0; i < MESH_SPREADING_FACTOR_OPTIONS.length; i++) {
            out[i] = "SF" + MESH_SPREADING_FACTOR_OPTIONS[i];
        }
        return out;
    }

    private static String[] meshCrLabels() {
        String[] out = new String[MESH_CODING_RATE_OPTIONS.length];
        for (int i = 0; i < MESH_CODING_RATE_OPTIONS.length; i++) {
            out[i] = "CR" + MESH_CODING_RATE_OPTIONS[i];
        }
        return out;
    }

    private static int indexOfIntOption(int[] options, int value) {
        for (int i = 0; i < options.length; i++) {
            if (options[i] == value) {
                return i;
            }
        }
        return 0;
    }

    private static int nearestBandwidthIndex(double valueKHz) {
        int bestIdx = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < MESH_BANDWIDTH_OPTIONS_KHZ.length; i++) {
            double d = Math.abs(MESH_BANDWIDTH_OPTIONS_KHZ[i] - valueKHz);
            if (d < bestDiff) {
                bestDiff = d;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static String trimDouble(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.0001) {
            return String.format(Locale.US, "%.0f", v);
        }
        if (Math.abs(v * 10.0 - Math.rint(v * 10.0)) < 0.0001) {
            return String.format(Locale.US, "%.1f", v);
        }
        if (Math.abs(v * 100.0 - Math.rint(v * 100.0)) < 0.0001) {
            return String.format(Locale.US, "%.2f", v);
        }
        return String.format(Locale.US, "%.3f", v);
    }

    private void showMeshChannelPickerDialog() {
        Context ctx = getMapView().getContext();
        List<Integer> indices = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String name = meshChannelNames.get(i);
            if (name != null && !name.trim().isEmpty()) {
                if ("ATAK_DATA".equalsIgnoreCase(name.trim())) {
                    continue;
                }
                indices.add(i);
                labels.add("#" + i + "  " + name);
            }
        }
        if (indices.isEmpty()) {
            Toast.makeText(ctx, "Channel list is still loading. Tap again in a moment.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(ctx)
                .setTitle("MeshCore Channels")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which < 0 || which >= indices.size()) {
                        return;
                    }
                    openMeshChannelChatDialog(indices.get(which));
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void openMeshChannelChatDialog(int channelIndex) {
        clearMeshContactChatMode();
        String channelName = meshChannelNames.get(channelIndex);
        if (channelName == null || channelName.trim().isEmpty()) {
            channelName = "Channel";
        }
        meshChannelChatActiveIndex = channelIndex;
        if (meshBtManager != null && meshBtManager.isConnected()) {
            MeshLastChatStore.saveChannel(getMapView().getContext(),
                    meshBtManager.getConnectedDeviceAddress(), channelIndex);
        }
        // Show the inline chat window in the panel.
        if (meshChannelTitleView != null) {
            meshChannelTitleView.setText("Channel #" + channelIndex + " — " + channelName);
            meshChannelTitleView.setVisibility(android.view.View.VISIBLE);
        }
        if (meshChannelLogText != null) {
            meshChannelChatLogView = meshChannelLogText;
            meshChannelLogText.setVisibility(android.view.View.VISIBLE);
        }
        if (rowMeshChannelInput != null) {
            rowMeshChannelInput.setVisibility(android.view.View.VISIBLE);
        }
        updateMeshChatInputHint();
        renderMeshChannelChatLog(channelIndex);
        updateExpandMeshChannelChatButtonState();
    }

    private boolean isInlineMeshChatActive() {
        if (rowMeshChannelInput == null
                || rowMeshChannelInput.getVisibility() != View.VISIBLE) {
            return false;
        }
        return meshChannelChatActiveIndex >= 0 || activeMeshContactPubKey != null;
    }

    private void renderActiveMeshChatLog() {
        if (activeMeshContactPubKey != null) {
            MeshContactChatSession session = getActiveContactSession();
            if (meshChannelChatTitleView != null && session != null) {
                meshChannelChatTitleView.setText("Chat: " + session.displayName);
            } else if (meshChannelTitleView != null && session != null) {
                meshChannelTitleView.setText("Chat: " + session.displayName);
            }
            renderMeshContactChatLog();
            updateMeshChatInputHint();
            return;
        }
        if (meshChannelChatActiveIndex >= 0) {
            renderMeshChannelChatLog(meshChannelChatActiveIndex);
            updateMeshChatInputHint();
        }
    }

    private void updateExpandMeshChannelChatButtonState() {
        if (btnExpandMeshChannelChat == null) {
            return;
        }
        boolean canExpand = isInlineMeshChatActive();
        btnExpandMeshChannelChat.setEnabled(canExpand);
        btnExpandMeshChannelChat.setVisibility(canExpand ? View.VISIBLE : View.GONE);
    }

    private void showExpandedMeshChannelChatDialog() {
        if (!isInlineMeshChatActive()) {
            Toast.makeText(getMapView().getContext(),
                    "Select a channel or contact first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (meshChannelChatDialog != null && meshChannelChatDialog.isShowing()) {
            return;
        }
        Context ctx = getMapView().getContext();
        int layoutId = pluginContext.getResources().getIdentifier(
                "mesh_channel_chat_expanded", "layout", pluginContext.getPackageName());
        if (layoutId == 0) {
            Toast.makeText(ctx, "Expanded chat layout unavailable.", Toast.LENGTH_SHORT).show();
            return;
        }
        View content = LayoutInflater.from(pluginContext).inflate(layoutId, null);
        textMeshChannelExpandedTitle = content.findViewById(getId("text_mesh_channel_expanded_title"));
        textMeshChannelExpandedLog = content.findViewById(getId("text_mesh_channel_expanded_log"));
        editMeshChannelExpandedMessage = content.findViewById(getId("edit_mesh_channel_expanded_message"));
        Button sendBtn = content.findViewById(getId("btn_mesh_channel_expanded_send"));
        Button closeBtn = content.findViewById(getId("btn_mesh_channel_expanded_close"));
        if (textMeshChannelExpandedLog != null) {
            textMeshChannelExpandedLog.setMovementMethod(new ScrollingMovementMethod());
            textMeshChannelExpandedLog.setOnTouchListener((v, event) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            });
        }
        if (editMeshChannelExpandedMessage != null && editMeshChannelMessage != null) {
            CharSequence draft = editMeshChannelMessage.getText();
            if (draft != null) {
                editMeshChannelExpandedMessage.setText(draft);
                editMeshChannelExpandedMessage.setSelection(draft.length());
            }
        }
        meshChannelChatLogView = textMeshChannelExpandedLog;
        meshChannelChatTitleView = textMeshChannelExpandedTitle;
        renderActiveMeshChatLog();
        if (sendBtn != null) {
            sendBtn.setOnClickListener(v -> sendMeshChannelTextFromField(editMeshChannelExpandedMessage));
        }
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> {
                if (meshChannelChatDialog != null) {
                    meshChannelChatDialog.dismiss();
                }
            });
        }
        if (editMeshChannelExpandedMessage != null) {
            editMeshChannelExpandedMessage.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND
                        || actionId == EditorInfo.IME_ACTION_DONE) {
                    sendMeshChannelTextFromField(editMeshChannelExpandedMessage);
                    return true;
                }
                return false;
            });
        }
        meshChannelChatDialog = new AlertDialog.Builder(ctx)
                .setView(content)
                .create();
        meshChannelChatDialog.setCancelable(true);
        meshChannelChatDialog.setOnDismissListener(d -> finishExpandedMeshChannelChatClose());
        meshChannelChatDialog.show();
        if (meshChannelChatDialog.getWindow() != null) {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int height = (int) (dm.heightPixels * 0.78f);
            meshChannelChatDialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, height);
            meshChannelChatDialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        if (editMeshChannelExpandedMessage != null) {
            editMeshChannelExpandedMessage.post(() -> {
                editMeshChannelExpandedMessage.requestFocus();
                InputMethodManager imm = (InputMethodManager) ctx.getSystemService(
                        Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(editMeshChannelExpandedMessage,
                            InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }
    }

    private void finishExpandedMeshChannelChatClose() {
        if (editMeshChannelExpandedMessage != null && editMeshChannelMessage != null) {
            editMeshChannelMessage.setText(editMeshChannelExpandedMessage.getText());
        }
        meshChannelChatLogView = meshChannelLogText;
        meshChannelChatTitleView = null;
        textMeshChannelExpandedLog = null;
        textMeshChannelExpandedTitle = null;
        editMeshChannelExpandedMessage = null;
        meshChannelChatDialog = null;
        renderActiveMeshChatLog();
        updateExpandMeshChannelChatButtonState();
    }

    private void sendInlineMeshChannelText() {
        sendMeshChannelTextFromField(editMeshChannelMessage);
    }

    private void sendMeshChannelTextFromField(EditText field) {
        if (meshBtManager == null || !meshBtManager.isConnected()) {
            return;
        }
        if (field == null) {
            return;
        }
        String text = field.getText() != null ? field.getText().toString().trim() : "";
        if (text.isEmpty()) {
            return;
        }
        if (activeMeshContactPubKey != null) {
            MeshContactChatSession session = getActiveContactSession();
            if (session != null
                    && meshBtManager.sendContactTextMessage(session.pubKeyHex, text)) {
                field.setText("");
                appendMeshContactChatLine(session, true, text);
                renderMeshContactChatLog();
            } else {
                Toast.makeText(getMapView().getContext(),
                        "Direct message not sent — transmit failed", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (meshChannelChatActiveIndex < 0) {
            Toast.makeText(getMapView().getContext(),
                    "Select a channel first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!meshBtManager.sendChannelText(meshChannelChatActiveIndex, text)) {
            Toast.makeText(getMapView().getContext(),
                    "Failed to send over MeshCore channel.", Toast.LENGTH_SHORT).show();
            return;
        }
        field.setText("");
    }

    private void clearMeshContactChatMode() {
        activeMeshContactPubKey = null;
    }

    private void showDeviceContactsDialog() {
        if (!isMeshConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to a MeshCore node first.", Toast.LENGTH_SHORT).show();
            return;
        }
        Context ctx = getMapView().getContext();
        String deviceAddr = meshBtManager.getConnectedDeviceAddress();
        java.util.List<MeshBtConnectionManager.MeshDeviceContact> cached =
                MeshDeviceContactCache.load(ctx, deviceAddr);
        if (!cached.isEmpty()) {
            showDeviceContactsPicker(cached);
            fetchDeviceContactsFromRadio(true, false);
            return;
        }
        Toast.makeText(ctx, "Loading contacts from device…", Toast.LENGTH_SHORT).show();
        fetchDeviceContactsFromRadio(true, true);
    }

    private void fetchDeviceContactsFromRadio(boolean showPickerOnSuccess,
                                              boolean showErrors) {
        if (!isMeshConnected()) {
            if (showErrors) {
                Toast.makeText(getMapView().getContext(),
                        "Not connected.", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (deviceContactsFetchInFlight) {
            if (showErrors) {
                Toast.makeText(getMapView().getContext(),
                        "Contact sync already in progress.", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        Context ctx = getMapView().getContext();
        final String deviceAddr = meshBtManager.getConnectedDeviceAddress();
        deviceContactsFetchInFlight = true;
        meshBtManager.requestDeviceContacts(new MeshBtConnectionManager.DeviceContactsListener() {
            @Override
            public void onDeviceContactsReady(
                    java.util.List<MeshBtConnectionManager.MeshDeviceContact> contacts) {
                deviceContactsFetchInFlight = false;
                meshBtManager.trimDeviceContactsToRollingCap(contacts);
                java.util.List<MeshBtConnectionManager.MeshDeviceContact> kept =
                        mergeJoinedRoomsIntoDeviceContacts(ctx, deviceAddr,
                                MeshDeviceContactPolicy.filterRemoved(contacts,
                                        MeshDeviceContactPolicy.contactsToEvictFromDevice(contacts)));
                MeshDeviceContactCache.save(ctx, deviceAddr, kept);
                getMapView().post(() -> {
                    applyDeviceContactMetadataUpdates(ctx, deviceAddr, kept);
                    if (showPickerOnSuccess
                            || (deviceContactsDialog != null && deviceContactsDialog.isShowing())) {
                        showDeviceContactsPicker(kept);
                    }
                    maybeBeginActiveRoomSessionAfterContactsSync();
                });
            }

            @Override
            public void onDeviceContactsFailed(String reason) {
                deviceContactsFetchInFlight = false;
                if (!showErrors) {
                    return;
                }
                getMapView().post(() -> Toast.makeText(ctx,
                        reason != null ? reason : "Could not load contacts",
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void syncDeviceContactsCacheInBackground() {
        if (!isMeshConnected()) {
            return;
        }
        Context ctx = getMapView().getContext();
        String deviceAddr = meshBtManager.getConnectedDeviceAddress();
        if (!MeshDeviceContactCache.isStale(ctx, deviceAddr, DEVICE_CONTACTS_CACHE_STALE_MS)) {
            return;
        }
        fetchDeviceContactsFromRadio(false, false);
    }

    private void showDeviceContactsPicker(
            java.util.List<MeshBtConnectionManager.MeshDeviceContact> contacts) {
        Context ctx = getMapView().getContext();
        if (contacts == null || contacts.isEmpty()) {
            if (deviceContactsDialog != null && deviceContactsDialog.isShowing()) {
                deviceContactsDialog.dismiss();
            }
            deviceContactsDialog = null;
            Toast.makeText(ctx, "No contacts on device.", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.ArrayList<MeshBtConnectionManager.MeshDeviceContact> mutable =
                new java.util.ArrayList<>(contacts);
        if (deviceContactsDialog != null && deviceContactsDialog.isShowing()) {
            deviceContactsDialog.dismiss();
        }
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dip(ctx, 8);
        list.setPadding(pad, pad, pad, pad);
        Runnable rebuildPicker = () -> showDeviceContactsPicker(mutable);
        for (MeshBtConnectionManager.MeshDeviceContact contact : mutable) {
            list.addView(buildDeviceContactRow(ctx, contact, mutable, rebuildPicker));
        }
        scroll.addView(list);
        deviceContactsDialog = new AlertDialog.Builder(ctx)
                .setTitle("MeshCore Contacts (" + mutable.size() + ")")
                .setView(scroll)
                .setNeutralButton("Refresh", (dialog, which) -> {
                    Toast.makeText(ctx, "Refreshing contacts from device…",
                            Toast.LENGTH_SHORT).show();
                    fetchDeviceContactsFromRadio(true, true);
                })
                .setNegativeButton("Close", (dialog, which) -> deviceContactsDialog = null)
                .create();
        deviceContactsDialog.setOnDismissListener(d -> deviceContactsDialog = null);
        deviceContactsDialog.show();
    }

    private View buildDeviceContactRow(Context ctx,
            MeshBtConnectionManager.MeshDeviceContact contact,
            java.util.ArrayList<MeshBtConnectionManager.MeshDeviceContact> backingList,
            Runnable onListChanged) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vPad = dip(ctx, 6);
        row.setPadding(0, vPad, 0, vPad);

        String star = contact.isFavorite() ? "★ " : "";
        String typeLabel = deviceContactTypeLabel(contact.type);
        TextView label = new TextView(ctx);
        label.setText(star + contact.name + "  (" + typeLabel + ")");
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(14f);
        label.setOnClickListener(v -> showDeviceContactActions(contact));
        row.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton trash = new ImageButton(ctx);
        trash.setImageResource(android.R.drawable.ic_menu_delete);
        trash.setBackground(null);
        trash.setContentDescription("Remove contact");
        int iconPad = dip(ctx, 8);
        trash.setPadding(iconPad, iconPad, iconPad, iconPad);
        trash.setOnClickListener(v -> confirmRemoveDeviceContact(contact, backingList, onListChanged));
        row.addView(trash, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void confirmRemoveDeviceContact(
            MeshBtConnectionManager.MeshDeviceContact contact,
            java.util.ArrayList<MeshBtConnectionManager.MeshDeviceContact> backingList,
            @Nullable Runnable onListChanged) {
        if (contact == null) {
            return;
        }
        String name = contact.name != null && !contact.name.trim().isEmpty()
                ? contact.name.trim() : "this contact";
        new AlertDialog.Builder(getMapView().getContext())
                .setTitle("Remove Contact")
                .setMessage("Remove \"" + name + "\" from this radio?")
                .setPositiveButton("Remove", (d, w) -> {
                    performRemoveDeviceContact(contact);
                    if (contact.pubKeyHex != null && backingList != null) {
                        for (int i = backingList.size() - 1; i >= 0; i--) {
                            MeshBtConnectionManager.MeshDeviceContact c = backingList.get(i);
                            if (c.pubKeyHex != null
                                    && c.pubKeyHex.equalsIgnoreCase(contact.pubKeyHex)) {
                                backingList.remove(i);
                                break;
                            }
                        }
                    }
                    if (onListChanged != null) {
                        onListChanged.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performRemoveDeviceContact(
            MeshBtConnectionManager.MeshDeviceContact contact) {
        if (contact == null || meshBtManager == null) {
            return;
        }
        Context ctx = getMapView().getContext();
        String addr = meshBtManager.getConnectedDeviceAddress();
        if (contact.type == MeshBtConnectionManager.ADV_TYPE_ROOM
                && contact.pubKeyHex != null) {
            performRemoveJoinedRoom(contact.pubKeyHex, contact.name);
            appendLog("Removed room contact " + contact.name);
            return;
        }
        meshBtManager.removeDeviceContact(contact);
        MeshDeviceContactCache.removeByPubKey(ctx, addr, contact.pubKeyHex);
        String pubKey = normalizePubKeyHex(contact.pubKeyHex);
        if (pubKey != null) {
            meshContactChatSessions.remove(pubKey);
        }
        if (pubKey != null && pubKey.equals(activeMeshContactPubKey)) {
            clearMeshContactChatMode();
            if (meshChannelLogText != null) {
                meshChannelLogText.setVisibility(View.GONE);
            }
            if (meshChannelTitleView != null) {
                meshChannelTitleView.setVisibility(View.GONE);
            }
            if (rowMeshChannelInput != null) {
                rowMeshChannelInput.setVisibility(View.GONE);
            }
            updateExpandMeshChannelChatButtonState();
        }
        buildMeshChannelButtonStrip();
        appendLog("Removed device contact " + contact.name);
    }

    /**
     * Propagate room/contact renames from the companion radio into joined-room tabs and chat UI.
     */
    private void applyDeviceContactMetadataUpdates(
            Context ctx, String deviceAddr,
            java.util.List<MeshBtConnectionManager.MeshDeviceContact> contacts) {
        if (contacts == null || contacts.isEmpty()) {
            return;
        }
        boolean namesChanged = false;
        for (MeshBtConnectionManager.MeshDeviceContact contact : contacts) {
            if (applySingleContactNameUpdate(ctx, deviceAddr, contact)) {
                namesChanged = true;
            }
        }
        if (!namesChanged) {
            return;
        }
        refreshRoomContactUiAfterRename();
    }

    private void applyContactNameFromRadio(MeshBtConnectionManager.MeshDeviceContact contact) {
        if (contact == null || meshBtManager == null || !meshBtManager.isConnected()) {
            return;
        }
        MapView mv = getMapView();
        if (mv == null) {
            return;
        }
        Context ctx = mv.getContext();
        String addr = meshBtManager.getConnectedDeviceAddress();
        if (ctx == null || addr == null) {
            return;
        }
        MeshBtConnectionManager.MeshDeviceContact cached =
                MeshDeviceContactCache.findByPubKeyPrefix(ctx, addr, contact.pubKeyHex);
        MeshBtConnectionManager.MeshDeviceContact merged = contact;
        if (cached != null) {
            merged = new MeshBtConnectionManager.MeshDeviceContact(
                    contact.pubKeyHex, contact.type,
                    cached.flags, cached.outPathLen, contact.name,
                    contact.lastAdvertTimestamp > 0
                            ? contact.lastAdvertTimestamp : cached.lastAdvertTimestamp,
                    contact.gpsLat != 0.0 ? contact.gpsLat : cached.gpsLat,
                    contact.gpsLon != 0.0 ? contact.gpsLon : cached.gpsLon,
                    contact.lastMod > 0 ? contact.lastMod : cached.lastMod);
        }
        MeshDeviceContactCache.upsertFromDeviceContact(ctx, addr, merged);
        if (applySingleContactNameUpdate(ctx, addr, merged)) {
            refreshRoomContactUiAfterRename();
        }
    }

    private boolean applySingleContactNameUpdate(
            Context ctx, String deviceAddr,
            MeshBtConnectionManager.MeshDeviceContact contact) {
        if (contact == null || contact.pubKeyHex == null) {
            return false;
        }
        String deviceName = contact.name != null ? contact.name.trim() : "";
        if (deviceName.isEmpty()) {
            return false;
        }
        boolean namesChanged = false;
        if (contact.type == MeshBtConnectionManager.ADV_TYPE_ROOM) {
            for (MeshJoinedRoomStore.JoinedRoom room
                    : MeshJoinedRoomStore.loadJoinedRooms(ctx, deviceAddr)) {
                if (room.pubKeyHex.equalsIgnoreCase(contact.pubKeyHex)
                        && !deviceName.equals(room.displayName)) {
                    MeshJoinedRoomStore.saveJoinedRoom(ctx, deviceAddr,
                            contact.pubKeyHex, deviceName);
                    namesChanged = true;
                    appendLog("Room renamed on radio: " + deviceName);
                    break;
                }
            }
        }
        MeshContactChatSession session = findSessionByPubKey(contact.pubKeyHex);
        if (session != null && !deviceName.equals(session.displayName)) {
            session.displayName = deviceName;
            namesChanged = true;
        }
        return namesChanged;
    }

    @Nullable
    private MeshContactChatSession findSessionByPubKey(@Nullable String pubKeyHex) {
        if (pubKeyHex == null) {
            return null;
        }
        String normalized = normalizePubKeyHex(pubKeyHex);
        if (normalized != null && normalized.length() == 64) {
            MeshContactChatSession session = meshContactChatSessions.get(normalized);
            if (session != null) {
                return session;
            }
        }
        for (MeshContactChatSession session : meshContactChatSessions.values()) {
            if (session.pubKeyHex != null
                    && session.pubKeyHex.equalsIgnoreCase(pubKeyHex)) {
                return session;
            }
        }
        return null;
    }

    private void refreshRoomContactUiAfterRename() {
        buildMeshChannelButtonStrip();
        MeshContactChatSession active = getActiveContactSession();
        if (active != null) {
            showInlineContactChat(active.displayName);
            updateMeshChatInputHint();
            if (meshChannelChatTitleView != null) {
                meshChannelChatTitleView.setText("Chat: " + active.displayName);
            }
            renderMeshContactChatLog();
        }
    }

    private void refreshJoinedRoomContactNamesFromRadio() {
        if (meshBtManager == null || !meshBtManager.isConnected()) {
            return;
        }
        Context ctx = getMapView().getContext();
        String addr = meshBtManager.getConnectedDeviceAddress();
        for (MeshJoinedRoomStore.JoinedRoom room
                : MeshJoinedRoomStore.loadJoinedRooms(ctx, addr)) {
            meshBtManager.requestContactByPubKeyHex(room.pubKeyHex);
        }
    }

    private void showDeviceContactActions(MeshBtConnectionManager.MeshDeviceContact contact) {
        if (contact == null) {
            return;
        }
        Context ctx = getMapView().getContext();
        boolean isRoom = contact.type == MeshBtConnectionManager.ADV_TYPE_ROOM;
        String messageActionLabel = isRoom ? "Join Room" : "Send Message";
        new AlertDialog.Builder(ctx)
                .setTitle(contact.name)
                .setItems(new String[]{"Favorite", messageActionLabel}, (dialog, which) -> {
                    if (which == 0) {
                        favoriteDeviceContact(contact);
                    } else if (which == 1) {
                        openDeviceContactChatWithOptionalAlert(contact);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openDeviceContactChatWithOptionalAlert(
            MeshBtConnectionManager.MeshDeviceContact contact) {
        if (contact == null) {
            return;
        }
        if (contact.type == MeshBtConnectionManager.ADV_TYPE_ROOM) {
            openDeviceContactChat(contact);
            return;
        }
        if (contact.type != MeshBtConnectionManager.ADV_TYPE_CHAT) {
            openDeviceContactChat(contact);
            return;
        }
        Context ctx = getMapView().getContext();
        if (PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean(PREF_SUPPRESS_MESH_DM_CONTACT_ALERT, false)) {
            openDeviceContactChat(contact);
            return;
        }
        showMeshCompanionContactAlert(ctx, () -> openDeviceContactChat(contact));
    }

    private void showMeshCompanionContactAlert(Context ctx, Runnable onContinue) {
        int pad = dip(ctx, 16);
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad / 2, pad, 0);

        TextView message = new TextView(ctx);
        message.setText("This user must add you as a contact to receive your message.");
        message.setTextColor(0xFFCCCCCC);
        message.setTextSize(14f);
        layout.addView(message);

        CheckBox suppress = new CheckBox(ctx);
        suppress.setText("Don't show this alert again");
        suppress.setTextColor(0xFFCCCCCC);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cbLp.topMargin = dip(ctx, 12);
        layout.addView(suppress, cbLp);

        new AlertDialog.Builder(ctx)
                .setTitle("Direct Message")
                .setView(layout)
                .setPositiveButton("Continue", (dialog, which) -> {
                    if (suppress.isChecked()) {
                        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                                .putBoolean(PREF_SUPPRESS_MESH_DM_CONTACT_ALERT, true)
                                .apply();
                    }
                    if (onContinue != null) {
                        onContinue.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void favoriteDeviceContact(MeshBtConnectionManager.MeshDeviceContact contact) {
        Context ctx = getMapView().getContext();
        boolean ok = UVProContactHandler.favoriteDeviceContact(meshBtManager, contact);
        Toast.makeText(ctx,
                ok ? "Favorited " + UVProContactHandler.formatMeshFavoriteName(
                        contact.name, UVProContactHandler.uidForDeviceContact(contact))
                        : "Could not favorite contact",
                Toast.LENGTH_LONG).show();
        if (ok) {
            MeshDeviceContactCache.updateFavoriteFlag(ctx,
                    meshBtManager.getConnectedDeviceAddress(), contact.pubKeyHex, true);
            UVProContactHandler.markMeshMapCacheFavorite(ctx, contact.pubKeyHex, true);
            appendLog("Favorited device contact " + contact.name);
        }
    }

    private void openDeviceContactChat(MeshBtConnectionManager.MeshDeviceContact contact) {
        if (contact == null) {
            return;
        }
        Context ctx = getMapView().getContext();
        if (contact.type == ADV_TYPE_REPEATER) {
            Toast.makeText(ctx, "Repeaters do not support direct messages.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (contact.pubKeyHex == null || contact.pubKeyHex.length() < 12) {
            Toast.makeText(ctx, "Invalid contact pubkey.", Toast.LENGTH_SHORT).show();
            return;
        }
        MeshContactChatSession session = ensureContactChatSession(contact);
        activeMeshContactPubKey = session.pubKeyHex;
        meshChannelChatActiveIndex = -1;
        UVProContactHandler.ensureMeshInboundChatContact(
                session.pubKeyHex.substring(0, 12));
        showInlineContactChat(session.displayName);
        buildMeshChannelButtonStrip();
        appendLog("Opened chat with " + session.displayName);
        if (session.isRoom) {
            beginRoomServerSession(session);
        }
    }

    private void showInlineContactChat(String displayName) {
        meshChannelChatLogView = meshChannelLogText;
        meshChannelChatTitleView = meshChannelTitleView;
        if (meshChannelTitleView != null) {
            meshChannelTitleView.setText("Chat: " + displayName);
            meshChannelTitleView.setVisibility(View.VISIBLE);
        }
        if (meshChannelLogText != null) {
            meshChannelLogText.setVisibility(View.VISIBLE);
            renderMeshContactChatLog();
        }
        if (rowMeshChannelInput != null) {
            rowMeshChannelInput.setVisibility(View.VISIBLE);
        }
        if (editMeshChannelMessage != null) {
            editMeshChannelMessage.setText("");
        }
        updateMeshChatInputHint();
        refreshMeshChannelStripSelection();
        updateExpandMeshChannelChatButtonState();
    }

    private void appendMeshContactChatLine(MeshContactChatSession session,
                                           boolean outbound, String text) {
        appendMeshContactChatLine(session, outbound, text, null, 0);
    }

    private void appendMeshContactChatLine(MeshContactChatSession session,
                                           boolean outbound, String text,
                                           @Nullable String authorOverride) {
        appendMeshContactChatLine(session, outbound, text, authorOverride, 0);
    }

    private void appendMeshContactChatLine(MeshContactChatSession session,
                                           boolean outbound, String text,
                                           @Nullable String authorOverride,
                                           int postTimestampSec) {
        if (session == null || text == null || text.trim().isEmpty()) {
            return;
        }
        String trimmed = text.trim();
        for (String existing : session.lines) {
            String display = chatLineDisplay(existing);
            if (display.endsWith(": " + trimmed)) {
                return;
            }
        }
        long sortKey = postTimestampSec > 0
                ? postTimestampSec
                : System.currentTimeMillis() / 1000L;
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new Date(sortKey * 1000L));
        String who;
        if (outbound) {
            who = "You";
        } else if (authorOverride != null && !authorOverride.trim().isEmpty()) {
            who = authorOverride.trim();
        } else {
            who = session.displayName;
        }
        String display = "[" + ts + "] " + who + ": " + trimmed;
        session.lines.add(String.format(Locale.US, "%010d|%s", sortKey, display));
        while (session.lines.size() > MAX_MESH_CONTACT_CHAT_LINES) {
            session.lines.removeFirst();
        }
        if (session.isRoom) {
            persistRoomChatHistory(session);
        }
    }

    private void persistRoomChatHistory(MeshContactChatSession session) {
        if (session == null || !session.isRoom || meshBtManager == null) {
            return;
        }
        MapView mv = getMapView();
        if (mv == null) {
            return;
        }
        Context ctx = mv.getContext();
        String addr = meshBtManager.getConnectedDeviceAddress();
        if (ctx == null || addr == null) {
            return;
        }
        MeshContactChatHistoryStore.save(ctx, addr, session.pubKeyHex, session.lines);
    }

    private static String chatLineDisplay(String storedLine) {
        if (storedLine == null) {
            return "";
        }
        int pipe = storedLine.indexOf('|');
        return pipe >= 0 ? storedLine.substring(pipe + 1) : storedLine;
    }

    private static java.util.List<String> chatLineDisplayBody(java.util.Collection<String> lines) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            out.add(chatLineDisplay(line));
        }
        return out;
    }

    private static java.util.List<String> sortedChatLinesForDisplay(
            java.util.Collection<String> lines) {
        java.util.ArrayList<String> sorted = new java.util.ArrayList<>(lines);
        sorted.sort(java.util.Comparator.comparingLong(line -> {
            if (line == null) {
                return 0L;
            }
            int pipe = line.indexOf('|');
            if (pipe <= 0) {
                return 0L;
            }
            try {
                return Long.parseLong(line.substring(0, pipe));
            } catch (NumberFormatException e) {
                return 0L;
            }
        }));
        java.util.ArrayList<String> display = new java.util.ArrayList<>(sorted.size());
        for (String line : sorted) {
            display.add(chatLineDisplay(line));
        }
        return display;
    }

    private void renderMeshContactChatLog() {
        TextView target = meshChannelChatLogView != null ? meshChannelChatLogView : meshChannelLogText;
        if (target == null) {
            return;
        }
        MeshContactChatSession session = getActiveContactSession();
        StringBuilder sb = new StringBuilder();
        if (session != null) {
            for (String line : sortedChatLinesForDisplay(session.lines)) {
                sb.append(line).append('\n');
            }
        }
        if (sb.length() == 0) {
            sb.append("(No messages yet)\n");
        }
        target.setText(sb.toString());
        if (session != null && session.pubKeyHex.equals(activeMeshContactPubKey)) {
            updateMeshChatInputHint();
        }
        target.post(() -> {
            android.text.Layout layout = target.getLayout();
            if (layout == null) {
                return;
            }
            int scrollY = layout.getHeight() - target.getHeight();
            target.scrollTo(0, Math.max(0, scrollY));
        });
    }

    private void refreshMeshChannelStripSelection() {
        if (stripMeshChannels == null) {
            return;
        }
        for (int i = 0; i < stripMeshChannels.getChildCount(); i++) {
            View child = stripMeshChannels.getChildAt(i);
            if (!(child instanceof Button)) {
                continue;
            }
            Object tag = child.getTag();
            if (tag instanceof String) {
                applyMeshChannelButtonStyle((Button) child, tag.equals(activeMeshContactPubKey));
            } else if (tag instanceof Integer) {
                applyMeshChannelButtonStyle((Button) child,
                        activeMeshContactPubKey == null
                                && meshChannelChatActiveIndex == (Integer) tag);
            }
        }
    }

    private static String deviceContactTypeLabel(int type) {
        switch (type) {
            case 1:
                return "Chat";
            case 2:
                return "Repeater";
            case 3:
                return "Room";
            case 4:
                return "Sensor";
            default:
                return "Node";
        }
    }

    private void showChannelManagementDialog() {
        boolean connected = meshConnected
                || (meshBtManager != null && meshBtManager.isConnected());
        if (!connected) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to a MeshCore node first.", Toast.LENGTH_SHORT).show();
            return;
        }
        Context ctx = getMapView().getContext();
        List<ManagedChannelEntry> entries = collectManagedChannels();

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dip(ctx, 16);
        root.setPadding(pad, pad / 2, pad, pad / 2);

        if (entries.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("No channels joined yet.");
            empty.setTextColor(0xFF888888);
            empty.setTextSize(14f);
            root.addView(empty);
        } else {
            for (ManagedChannelEntry entry : entries) {
                root.addView(buildManagedChannelRow(ctx, entry, () -> {
                    if (channelManagementDialog != null && channelManagementDialog.isShowing()) {
                        channelManagementDialog.dismiss();
                    }
                    showChannelManagementDialog();
                }));
            }
        }

        Button addBtn = new Button(ctx);
        addBtn.setText("Add Channel…");
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        addLp.topMargin = dip(ctx, 12);
        addBtn.setLayoutParams(addLp);
        addBtn.setOnClickListener(v -> {
            if (channelManagementDialog != null && channelManagementDialog.isShowing()) {
                channelManagementDialog.dismiss();
            }
            showAddChannelOptionsDialog();
        });
        root.addView(addBtn);

        scroll.addView(root);
        channelManagementDialog = new AlertDialog.Builder(ctx)
                .setTitle("Channel Management")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();
        channelManagementDialog.show();
    }

    private List<ManagedChannelEntry> collectManagedChannels() {
        List<ManagedChannelEntry> entries = new ArrayList<>();
        Set<String> roomKeys = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            String name = meshChannelNames.get(i);
            if (name != null && !name.trim().isEmpty()
                    && !"ATAK_DATA".equalsIgnoreCase(name.trim())) {
                entries.add(ManagedChannelEntry.group(i, name.trim()));
            }
        }
        for (MeshContactChatSession session : meshContactChatSessions.values()) {
            if (session.isRoom && session.pubKeyHex != null) {
                entries.add(ManagedChannelEntry.room(session.displayName, session.pubKeyHex));
                roomKeys.add(session.pubKeyHex.toLowerCase(Locale.US));
            }
        }
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        String addr = meshBtManager != null ? meshBtManager.getConnectedDeviceAddress() : null;
        if (ctx != null && addr != null) {
            for (MeshJoinedRoomStore.JoinedRoom room : MeshJoinedRoomStore.loadJoinedRooms(ctx, addr)) {
                if (!roomKeys.contains(room.pubKeyHex.toLowerCase(Locale.US))) {
                    entries.add(ManagedChannelEntry.room(room.displayName, room.pubKeyHex));
                }
            }
        }
        return entries;
    }

    private View buildManagedChannelRow(Context ctx, ManagedChannelEntry entry, Runnable onRemoved) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vPad = dip(ctx, 8);
        row.setPadding(0, vPad, 0, vPad);

        TextView label = new TextView(ctx);
        label.setText(entry.type == ManagedChannelEntry.TYPE_ROOM
                ? "Room: " + entry.label : entry.label);
        label.setTextColor(0xFF00BCD4);
        label.setTextSize(14f);
        label.setClickable(true);
        label.setFocusable(true);
        label.setOnClickListener(v -> showManagedChannelDetails(entry));
        row.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton trash = new ImageButton(ctx);
        trash.setImageResource(android.R.drawable.ic_menu_delete);
        trash.setBackground(null);
        trash.setContentDescription("Remove");
        int iconPad = dip(ctx, 8);
        trash.setPadding(iconPad, iconPad, iconPad, iconPad);
        trash.setOnClickListener(v -> confirmRemoveManagedChannelEntry(entry, onRemoved));
        row.addView(trash, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        return row;
    }

    private void confirmRemoveManagedChannelEntry(ManagedChannelEntry entry,
                                                    @Nullable Runnable onRemoved) {
        if (entry == null) {
            return;
        }
        String target = entry.type == ManagedChannelEntry.TYPE_ROOM
                ? "room '" + entry.label + "'" : "channel '" + entry.label + "'";
        new AlertDialog.Builder(getMapView().getContext())
                .setTitle("Remove Channel")
                .setMessage("Remove " + target + " from this node?")
                .setPositiveButton("Remove", (d, w) -> {
                    if (entry.type == ManagedChannelEntry.TYPE_ROOM) {
                        performRemoveJoinedRoom(entry.pubKeyHex, entry.label);
                    } else {
                        performRemoveGroupChannel(entry.slot, entry.label);
                    }
                    if (onRemoved != null) {
                        onRemoved.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performRemoveGroupChannel(int slot, String channelName) {
        if (meshBtManager == null) {
            return;
        }
        meshBtManager.clearChannelSlot(slot);
        meshChannelNames.remove(slot);
        if (meshChannelChatActiveIndex == slot) {
            if (meshChannelChatDialog != null && meshChannelChatDialog.isShowing()) {
                meshChannelChatDialog.dismiss();
            }
            meshChannelChatActiveIndex = -1;
            if (meshChannelLogText != null) {
                meshChannelLogText.setVisibility(View.GONE);
            }
            if (meshChannelTitleView != null) {
                meshChannelTitleView.setVisibility(View.GONE);
            }
            if (rowMeshChannelInput != null) {
                rowMeshChannelInput.setVisibility(View.GONE);
            }
            updateExpandMeshChannelChatButtonState();
        }
        updateMeshChannelButtonLabel();
        appendLog("Channel '" + channelName + "' removed.");
    }

    private void performRemoveJoinedRoom(String pubKeyHex, String displayName) {
        if (pubKeyHex == null) {
            return;
        }
        meshContactChatSessions.remove(pubKeyHex);
        Context ctx = getMapView().getContext();
        String addr = meshBtManager != null ? meshBtManager.getConnectedDeviceAddress() : null;
        MeshJoinedRoomStore.removeJoinedRoom(ctx, addr, pubKeyHex);
        MeshDeviceContactCache.removeByPubKey(ctx, addr, pubKeyHex);
        MeshContactChatHistoryStore.clear(ctx, addr, pubKeyHex);
        if (pubKeyHex.equals(pendingRoomPubKeyHex)) {
            cancelRoomLoginTimeout();
            pendingRoomPubKeyHex = null;
            pendingRoomLoginAction = PendingRoomLoginAction.NONE;
        }
        if (pubKeyHex.equals(activeMeshContactPubKey)) {
            clearMeshContactChatMode();
            if (meshChannelLogText != null) {
                meshChannelLogText.setVisibility(View.GONE);
            }
            if (meshChannelTitleView != null) {
                meshChannelTitleView.setVisibility(View.GONE);
            }
            if (rowMeshChannelInput != null) {
                rowMeshChannelInput.setVisibility(View.GONE);
            }
            updateExpandMeshChannelChatButtonState();
        }
        if (meshBtManager != null) {
            String name = displayName != null && !displayName.trim().isEmpty()
                    ? displayName.trim() : pubKeyHex.substring(0, 12);
            meshBtManager.removeDeviceContact(new MeshBtConnectionManager.MeshDeviceContact(
                    pubKeyHex, MeshBtConnectionManager.ADV_TYPE_ROOM, 0, 0, name,
                    0, 0.0, 0.0, (int) (System.currentTimeMillis() / 1000L)));
        }
        buildMeshChannelButtonStrip();
        appendLog("Room '" + (displayName != null ? displayName : pubKeyHex) + "' removed.");
    }

    private void showAddChannelOptionsDialog() {
        Context ctx = getMapView().getContext();
        String[] options = {
                "Join the Public Channel",
                "Join a Hashtag Channel  (e.g. #test)",
                "Create a Private Channel",
                "Join a Private Channel",
                "Join a Room Server",
                "Scan QR Code"
        };
        new AlertDialog.Builder(ctx)
                .setTitle("Add Channel")
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: joinPublicChannel();            break;
                        case 1: showHashtagChannelDialog();     break;
                        case 2: showCreatePrivateDialog();      break;
                        case 3: showJoinPrivateDialog();        break;
                        case 4: showJoinRoomServerDialog();     break;
                        case 5: showQrScanDialog();             break;
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void joinPublicChannel() {
        // Public channel: well-known fixed key 8b3387e9c5cdea6ac9e5edbaa115cd72
        byte[] publicKey = hexToBytes("8b3387e9c5cdea6ac9e5edbaa115cd72");
        addChannelToNode("Public", publicKey);
    }

    private void showHashtagChannelDialog() {
        Context ctx = getMapView().getContext();
        LinearLayout layout = buildChannelDialogLayout(ctx, true, false, false);
        EditText nameField = (EditText) layout.getTag();

        AlertDialog hashtagDialog = new AlertDialog.Builder(ctx)
                .setTitle("Join Hashtag Channel")
                .setMessage("Key is derived automatically from the channel name.\nAnyone who knows the name can join.")
                .setView(layout)
                .setPositiveButton("Join", null)
                .setNegativeButton("Cancel", null)
                .create();
        hashtagDialog.setOnShowListener(d -> {
            Button joinBtn = hashtagDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (joinBtn == null) return;
            joinBtn.setOnClickListener(v -> {
                String raw = nameField.getText() != null
                        ? nameField.getText().toString().trim() : "";
                if (raw.isEmpty()) { nameField.setError("Name required"); return; }
                String name = raw.startsWith("#") ? raw : "#" + raw;
                byte[] secret = sha256First16(name);
                if (addChannelToNode(name, secret)) hashtagDialog.dismiss();
            });
        });
        hashtagDialog.show();
    }

    private void showCreatePrivateDialog() {
        Context ctx = getMapView().getContext();
        LinearLayout layout = buildChannelDialogLayout(ctx, true, false, false);
        EditText nameField = (EditText) layout.getTag();

        AlertDialog createPrivateDialog = new AlertDialog.Builder(ctx)
                .setTitle("Create Private Channel")
                .setMessage("A random secret key will be generated.\nShare it with your team via QR code.")
                .setView(layout)
                .setPositiveButton("Create", null)
                .setNegativeButton("Cancel", null)
                .create();
        createPrivateDialog.setOnShowListener(d -> {
            Button btn = createPrivateDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (btn == null) return;
            btn.setOnClickListener(v -> {
                String name = nameField.getText() != null
                        ? nameField.getText().toString().trim() : "";
                if (name.isEmpty()) { nameField.setError("Name required"); return; }
                byte[] secret = new byte[16];
                new java.security.SecureRandom().nextBytes(secret);
                if (addChannelToNode(name, secret)) {
                    String hex = bytesToHex(secret);
                    // Show secret in a selectable field so the user can copy it.
                    android.widget.ScrollView shareScroll = new android.widget.ScrollView(ctx);
                    LinearLayout secretLayout = new LinearLayout(ctx);
                    secretLayout.setOrientation(LinearLayout.VERTICAL);
                    int p = dip(ctx, 16);
                    secretLayout.setPadding(p, p / 2, p, p / 2);
                    // Instruction text
                    TextView msg = new TextView(ctx);
                    msg.setText("Share this QR or the secret key below with your team.\nThey join via 'Join a Private Channel'.");
                    msg.setTextColor(0xFFCCCCCC);
                    msg.setTextSize(13f);
                    LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    mlp.bottomMargin = dip(ctx, 8);
                    secretLayout.addView(msg, mlp);
                    // Secret key label
                    TextView secLbl = new TextView(ctx);
                    secLbl.setText("Secret Key (long-press to copy)");
                    secLbl.setTextColor(0xFFAAAAAA);
                    secLbl.setTextSize(12f);
                    secretLayout.addView(secLbl, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                    // Selectable secret field
                    EditText secretView = new EditText(ctx);
                    secretView.setText(hex);
                    secretView.setTextIsSelectable(true);
                    secretView.setFocusableInTouchMode(true);
                    secretView.setTextSize(13f);
                    secretView.setInputType(InputType.TYPE_NULL);
                    secretView.setTextColor(0xFF00BCD4);
                    secretView.setBackgroundColor(0xFF1A1A1A);
                    secretView.setPadding(p / 2, p / 2, p / 2, p / 2);
                    secretLayout.addView(secretView, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                    // QR code — centred, below secret
                    String qrContent = "meshcore://channel/add?name="
                            + Uri.encode(name) + "&secret=" + hex;
                    android.graphics.Bitmap qrBmp = generateQrBitmap(qrContent, 400);
                    if (qrBmp != null) {
                        android.widget.ImageView qrView = new android.widget.ImageView(ctx);
                        int qrSizePx = dip(ctx, 240);
                        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(
                                qrSizePx, qrSizePx);
                        qrLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                        qrLp.topMargin = dip(ctx, 12);
                        qrView.setImageBitmap(qrBmp);
                        qrView.setBackgroundColor(android.graphics.Color.WHITE);
                        qrView.setPadding(dip(ctx, 8), dip(ctx, 8), dip(ctx, 8), dip(ctx, 8));
                        qrView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                        secretLayout.addView(qrView, qrLp);
                    }
                    shareScroll.addView(secretLayout);
                    new AlertDialog.Builder(ctx)
                            .setTitle("Channel '" + name + "' Created")
                            .setView(shareScroll)
                            .setPositiveButton("Done", null)
                            .show();
                    createPrivateDialog.dismiss();
                }
            });
        });
        createPrivateDialog.show();
    }

    private void showJoinPrivateDialog() {
        Context ctx = getMapView().getContext();
        int pad = dip(ctx, 16);
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad / 2, pad, 0);

        TextView nameLbl = new TextView(ctx); nameLbl.setText("Channel Name");
        nameLbl.setTextColor(0xFFAAAAAA); nameLbl.setTextSize(12f);
        layout.addView(nameLbl);
        EditText nameField = new EditText(ctx);
        nameField.setHint("e.g. OPS"); nameField.setInputType(InputType.TYPE_CLASS_TEXT);
        nameField.setSingleLine(true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.bottomMargin = dip(ctx, 10);
        layout.addView(nameField, nlp);

        TextView secLbl = new TextView(ctx); secLbl.setText("Secret Key (32 hex chars)");
        secLbl.setTextColor(0xFFAAAAAA); secLbl.setTextSize(12f);
        layout.addView(secLbl);
        EditText secretField = new EditText(ctx);
        secretField.setHint("e.g. 8b3387e9c5cdea6ac9e5edbaa115cd72");
        secretField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        secretField.setSingleLine(true);
        layout.addView(secretField, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog joinPrivateDialog = new AlertDialog.Builder(ctx)
                .setTitle("Join Private Channel")
                .setView(layout)
                .setPositiveButton("Join", null)
                .setNegativeButton("Cancel", null)
                .create();
        joinPrivateDialog.setOnShowListener(d -> {
            Button joinBtn = joinPrivateDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (joinBtn == null) return;
            joinBtn.setOnClickListener(v -> {
                String name = nameField.getText() != null
                        ? nameField.getText().toString().trim() : "";
                String secretHex = secretField.getText() != null
                        ? secretField.getText().toString().trim().toLowerCase(Locale.US) : "";
                if (name.isEmpty()) { nameField.setError("Name required"); return; }
                if (secretHex.length() != 32) {
                    secretField.setError("Must be exactly 32 hex characters (16 bytes)");
                    return;
                }
                byte[] secret = hexToBytes(secretHex);
                if (secret == null) {
                    secretField.setError("Invalid hex — use 0-9, a-f only");
                    return;
                }
                if (addChannelToNode(name, secret)) joinPrivateDialog.dismiss();
            });
        });
        joinPrivateDialog.show();
    }

    private void showJoinRoomServerDialog() {
        if (meshBtManager == null || !meshBtManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to a MeshCore node first.", Toast.LENGTH_SHORT).show();
            return;
        }
        Context ctx = getMapView().getContext();
        java.util.List<MeshBtConnectionManager.MeshDeviceContact> cached =
                MeshDeviceContactCache.load(ctx, meshBtManager.getConnectedDeviceAddress());
        java.util.List<MeshBtConnectionManager.MeshDeviceContact> rooms = new java.util.ArrayList<>();
        for (MeshBtConnectionManager.MeshDeviceContact c : cached) {
            if (c != null && c.type == MeshBtConnectionManager.ADV_TYPE_ROOM) {
                rooms.add(c);
            }
        }
        if (rooms.isEmpty()) {
            showJoinRoomServerManualDialog(null);
            return;
        }
        String[] labels = new String[rooms.size() + 1];
        for (int i = 0; i < rooms.size(); i++) {
            MeshBtConnectionManager.MeshDeviceContact c = rooms.get(i);
            labels[i] = (c.name != null ? c.name : "Room") + "  (Room)";
        }
        labels[labels.length - 1] = "Enter manually…";
        new AlertDialog.Builder(ctx)
                .setTitle("Join Room Server")
                .setItems(labels, (d, which) -> {
                    if (which == rooms.size()) {
                        showJoinRoomServerManualDialog(null);
                    } else {
                        joinRoomServerContact(rooms.get(which), null);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void restoreJoinedRoomSessions() {
        if (meshBtManager == null || !meshBtManager.isConnected()) {
            return;
        }
        if (!joinedRoomRestoredThisSession) {
            Context ctx = getMapView().getContext();
            String deviceAddr = meshBtManager.getConnectedDeviceAddress();
            java.util.List<MeshJoinedRoomStore.JoinedRoom> joined =
                    MeshJoinedRoomStore.loadJoinedRooms(ctx, deviceAddr);
            for (MeshJoinedRoomStore.JoinedRoom room : joined) {
                MeshBtConnectionManager.MeshDeviceContact cached =
                        MeshDeviceContactCache.findByPubKeyPrefix(ctx, deviceAddr, room.pubKeyHex);
                MeshBtConnectionManager.MeshDeviceContact contact;
                if (cached != null && cached.type == MeshBtConnectionManager.ADV_TYPE_ROOM) {
                    contact = cached;
                } else {
                    contact = new MeshBtConnectionManager.MeshDeviceContact(
                            room.pubKeyHex, MeshBtConnectionManager.ADV_TYPE_ROOM,
                            MeshBtConnectionManager.CONTACT_FLAG_FAVORITE, 0, room.displayName,
                            0, 0.0, 0.0, (int) (System.currentTimeMillis() / 1000L));
                }
                ensureContactChatSession(contact);
                MeshDeviceContactCache.upsertFromDeviceContact(ctx, deviceAddr, contact);
            }
            joinedRoomRestoredThisSession = true;
        }
        scheduleRestoreMeshChatUi();
    }

    private void scheduleRestoreMeshChatUi() {
        if (!meshChatUiRestorePending) {
            return;
        }
        getMapView().postDelayed(this::restoreMeshChatUi, 120L);
        getMapView().postDelayed(this::restoreMeshChatUi, 450L);
    }

    private void restoreMeshChatUi() {
        if (meshBtManager == null || !meshBtManager.isConnected() || rootView == null) {
            return;
        }
        Context ctx = getMapView().getContext();
        String deviceAddr = meshBtManager.getConnectedDeviceAddress();

        if (activeMeshContactPubKey != null) {
            MeshContactChatSession session = findSessionByPubKey(activeMeshContactPubKey);
            if (session != null) {
                applyRestoredMeshChatUi(session, -1, false);
                meshChatUiRestorePending = false;
                return;
            }
        }
        if (meshChannelChatActiveIndex >= 0) {
            applyRestoredMeshChatUi(null, meshChannelChatActiveIndex, false);
            meshChatUiRestorePending = false;
            return;
        }

        MeshLastChatStore.Snapshot snap = MeshLastChatStore.load(ctx, deviceAddr);
        if (snap == null) {
            String activeRoomPubKey = MeshJoinedRoomStore.getActiveRoomPubKey(ctx, deviceAddr);
            if (activeRoomPubKey != null) {
                snap = new MeshLastChatStore.Snapshot(
                        MeshLastChatStore.TYPE_ROOM, activeRoomPubKey, -1);
            }
        }
        if (snap == null) {
            java.util.List<MeshJoinedRoomStore.JoinedRoom> joined =
                    MeshJoinedRoomStore.loadJoinedRooms(ctx, deviceAddr);
            if (!joined.isEmpty()) {
                snap = new MeshLastChatStore.Snapshot(
                        MeshLastChatStore.TYPE_ROOM, joined.get(0).pubKeyHex, -1);
            }
        }
        if (snap == null) {
            return;
        }

        if (snap.type == MeshLastChatStore.TYPE_ROOM && snap.roomPubKeyHex != null) {
            MeshContactChatSession session = ensureRestoredRoomSession(ctx, deviceAddr, snap.roomPubKeyHex);
            if (session == null) {
                return;
            }
            activeMeshContactPubKey = session.pubKeyHex;
            meshChannelChatActiveIndex = -1;
            applyRestoredMeshChatUi(session, -1, false);
            scheduleRoomLoginAfterContactsSync(session);
        } else if (snap.type == MeshLastChatStore.TYPE_CHANNEL && snap.channelIndex >= 0) {
            activeMeshContactPubKey = null;
            applyRestoredMeshChatUi(null, snap.channelIndex, false);
        }
        meshChatUiRestorePending = false;
    }

    @Nullable
    private MeshContactChatSession ensureRestoredRoomSession(
            Context ctx, String deviceAddr, String pubKeyHex) {
        MeshContactChatSession existing = meshContactChatSessions.get(pubKeyHex);
        if (existing != null) {
            return existing;
        }
        java.util.List<MeshJoinedRoomStore.JoinedRoom> joined =
                MeshJoinedRoomStore.loadJoinedRooms(ctx, deviceAddr);
        for (MeshJoinedRoomStore.JoinedRoom room : joined) {
            if (room.pubKeyHex.equalsIgnoreCase(pubKeyHex)) {
                MeshBtConnectionManager.MeshDeviceContact cached =
                        MeshDeviceContactCache.findByPubKeyPrefix(ctx, deviceAddr, pubKeyHex);
                if (cached != null && cached.type == MeshBtConnectionManager.ADV_TYPE_ROOM) {
                    return ensureContactChatSession(cached);
                }
                MeshBtConnectionManager.MeshDeviceContact contact =
                        new MeshBtConnectionManager.MeshDeviceContact(
                                room.pubKeyHex, MeshBtConnectionManager.ADV_TYPE_ROOM,
                                MeshBtConnectionManager.CONTACT_FLAG_FAVORITE, 0, room.displayName,
                                0, 0.0, 0.0, (int) (System.currentTimeMillis() / 1000L));
                return ensureContactChatSession(contact);
            }
        }
        return null;
    }

    private void applyRestoredMeshChatUi(@Nullable MeshContactChatSession session,
                                         int channelIndex,
                                         boolean beginRoomSession) {
        if (session != null) {
            showInlineContactChat(session.displayName);
            buildMeshChannelButtonStrip();
            if (beginRoomSession && session.isRoom) {
                beginRoomServerSession(session);
            }
        } else if (channelIndex >= 0) {
            openMeshChannelChatDialog(channelIndex);
        } else {
            return;
        }
        scheduleScrollToMeshChatSection();
    }

    /** Wait for device contact sync so room login uses radio contact state (path/crypto). */
    private void scheduleRoomLoginAfterContactsSync(@Nullable MeshContactChatSession session) {
        if (session == null || !session.isRoom) {
            return;
        }
        getMapView().postDelayed(() -> {
            if (!isSessionCurrentlyVisible(session)) {
                return;
            }
            beginRoomServerSession(session);
        }, DEVICE_CONTACTS_CONNECT_SYNC_DELAY_MS + 600L);
    }

    private void maybeBeginActiveRoomSessionAfterContactsSync() {
        if (activeMeshContactPubKey == null || meshBtManager == null) {
            return;
        }
        MeshContactChatSession session = findSessionByPubKey(activeMeshContactPubKey);
        if (session == null || !session.isRoom || session.roomLoginSucceededThisConnection) {
            return;
        }
        beginRoomServerSession(session);
    }

    private void scheduleScrollToMeshChatSection() {
        getMapView().postDelayed(this::scrollToMeshChatSection, 120L);
        getMapView().postDelayed(this::scrollToMeshChatSection, 400L);
        getMapView().postDelayed(this::scrollToMeshChatSection, 900L);
    }

    private void scrollToMeshChatSection() {
        if (!(rootView instanceof ScrollView)) {
            return;
        }
        View target = meshChannelLogText != null ? meshChannelLogText : stripMeshChannels;
        if (target == null || target.getVisibility() != View.VISIBLE) {
            target = stripMeshChannels;
        }
        if (target == null) {
            return;
        }
        ScrollView scroll = (ScrollView) rootView;
        final View scrollTarget = target;
        scroll.post(() -> {
            int y = 0;
            View cursor = scrollTarget;
            while (cursor != null && cursor != scroll) {
                y += cursor.getTop();
                android.view.ViewParent p = cursor.getParent();
                cursor = (p instanceof View) ? (View) p : null;
            }
            y = Math.max(0, y - dip(getMapView().getContext(), 48));
            final int scrollY = y;
            scroll.scrollTo(0, scrollY);
            scroll.post(() -> scroll.smoothScrollTo(0, scrollY));
        });
    }

    private java.util.List<MeshBtConnectionManager.MeshDeviceContact> mergeJoinedRoomsIntoDeviceContacts(
            Context ctx, String deviceAddr,
            java.util.List<MeshBtConnectionManager.MeshDeviceContact> fromRadio) {
        java.util.List<MeshJoinedRoomStore.JoinedRoom> joined =
                MeshJoinedRoomStore.loadJoinedRooms(ctx, deviceAddr);
        if (joined.isEmpty()) {
            return fromRadio;
        }
        java.util.ArrayList<MeshBtConnectionManager.MeshDeviceContact> merged =
                new java.util.ArrayList<>(fromRadio);
        for (MeshJoinedRoomStore.JoinedRoom room : joined) {
            boolean found = false;
            for (MeshBtConnectionManager.MeshDeviceContact existing : merged) {
                if (existing.pubKeyHex != null
                        && existing.pubKeyHex.equalsIgnoreCase(room.pubKeyHex)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                merged.add(new MeshBtConnectionManager.MeshDeviceContact(
                        room.pubKeyHex, MeshBtConnectionManager.ADV_TYPE_ROOM,
                        MeshBtConnectionManager.CONTACT_FLAG_FAVORITE, 0, room.displayName,
                        0, 0.0, 0.0, (int) (System.currentTimeMillis() / 1000L)));
            }
        }
        return merged;
    }

    private void showJoinRoomServerManualDialog(
            @Nullable MeshBtConnectionManager.MeshDeviceContact preset) {
        Context ctx = getMapView().getContext();
        int pad = dip(ctx, 16);
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad / 2, pad, 0);

        EditText nameField = addLabeledField(layout, ctx, "Room Name",
                preset != null ? preset.name : "e.g. OPS-BBS", false);
        if (preset != null && preset.name != null) {
            nameField.setText(preset.name);
        }
        EditText pubKeyField = addLabeledField(layout, ctx, "Public Key (64 hex)",
                "Full 32-byte pubkey", false);
        if (preset != null && preset.pubKeyHex != null) {
            pubKeyField.setText(preset.pubKeyHex);
        }
        EditText pwdField = addLabeledField(layout, ctx, "Guest Password",
                "Blank = read-only if server allows", true);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("Join Room Server")
                .setMessage("Login syncs stored posts from the room server.")
                .setView(layout)
                .setPositiveButton("Join", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> {
            Button btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (btn == null) {
                return;
            }
            btn.setOnClickListener(v -> {
                if (meshBtManager == null) {
                    return;
                }
                String name = fieldText(nameField);
                String pubKey = normalizePubKeyHex(fieldText(pubKeyField));
                String password = fieldText(pwdField);
                if (pubKey == null || pubKey.length() != 64) {
                    pubKeyField.setError("64-character hex pubkey required");
                    return;
                }
                if (name.isEmpty()) {
                    name = pubKey.length() >= 12 ? pubKey.substring(0, 12) : "Room";
                }
                MeshBtConnectionManager.MeshDeviceContact contact =
                        new MeshBtConnectionManager.MeshDeviceContact(
                                pubKey, MeshBtConnectionManager.ADV_TYPE_ROOM, 0, 0, name,
                                0, 0.0, 0.0, (int) (System.currentTimeMillis() / 1000L));
                MeshRoomPasswordStore.savePassword(ctx, meshBtManager.getConnectedDeviceAddress(),
                        pubKey, password);
                MeshDeviceContactCache.upsertFromDeviceContact(ctx,
                        meshBtManager.getConnectedDeviceAddress(), contact);
                joinRoomServerContact(contact, password);
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void joinRoomServerContact(MeshBtConnectionManager.MeshDeviceContact contact,
                                       @Nullable String passwordOverride) {
        if (contact == null || contact.pubKeyHex == null || meshBtManager == null) {
            return;
        }
        Context ctx = getMapView().getContext();
        String pubKey = normalizePubKeyHex(contact.pubKeyHex);
        if (pubKey == null || pubKey.length() != 64) {
            Toast.makeText(ctx, "Invalid room pubkey.", Toast.LENGTH_SHORT).show();
            return;
        }
        String displayName = contact.name != null && !contact.name.trim().isEmpty()
                ? contact.name.trim()
                : (pubKey.length() >= 12 ? pubKey.substring(0, 12) : "Room");
        MeshBtConnectionManager.MeshDeviceContact deviceContact =
                new MeshBtConnectionManager.MeshDeviceContact(
                        pubKey, MeshBtConnectionManager.ADV_TYPE_ROOM, 0, 0, displayName,
                        contact.lastAdvertTimestamp, contact.gpsLat, contact.gpsLon,
                        contact.lastMod > 0
                                ? contact.lastMod
                                : (int) (System.currentTimeMillis() / 1000L));
        MeshContactChatSession session = ensureContactChatSession(deviceContact);
        String pwd = passwordOverride;
        if (pwd == null) {
            if (!MeshRoomPasswordStore.hasPasswordStored(ctx,
                    meshBtManager.getConnectedDeviceAddress(), pubKey)) {
                promptRoomPasswordAndLogin(session, PendingRoomLoginAction.JOIN_ONLY);
                return;
            }
            pwd = MeshRoomPasswordStore.getPassword(ctx, meshBtManager.getConnectedDeviceAddress(),
                    pubKey);
        }
        MeshDeviceContactCache.upsertFromDeviceContact(ctx,
                meshBtManager.getConnectedDeviceAddress(), deviceContact);
        session.isRoom = true;
        activeMeshContactPubKey = session.pubKeyHex;
        meshChannelChatActiveIndex = -1;
        showInlineContactChat(session.displayName);
        buildMeshChannelButtonStrip();
        MeshJoinedRoomStore.saveJoinedRoom(ctx, meshBtManager.getConnectedDeviceAddress(),
                session.pubKeyHex, session.displayName);
        MeshLastChatStore.saveRoom(ctx, meshBtManager.getConnectedDeviceAddress(), session.pubKeyHex);
        MeshRoomPasswordStore.savePassword(ctx, meshBtManager.getConnectedDeviceAddress(),
                pubKey, pwd != null ? pwd : "");
        sendRoomLoginAfterContactSync(session, pwd != null ? pwd : "",
                PendingRoomLoginAction.JOIN_ONLY);
    }

    private void sendRoomLoginAfterContactSync(MeshContactChatSession session, String password,
                                               PendingRoomLoginAction action) {
        sendRoomLoginAfterContactSync(session, password, action, false);
    }

    private void sendRoomLoginAfterContactSync(MeshContactChatSession session, String password,
                                               PendingRoomLoginAction action,
                                               boolean resetContactForFullHistory) {
        if (session == null || meshBtManager == null) {
            return;
        }
        if (roomLoginPendingPubKey != null
                && roomLoginPendingPubKey.equalsIgnoreCase(session.pubKeyHex)) {
            appendLog("Room login already pending for " + session.displayName);
            return;
        }
        Context ctx = getMapView().getContext();
        cancelRoomLoginTimeout();
        pendingRoomPubKeyHex = session.pubKeyHex;
        pendingRoomLoginAction = action;
        roomLoginPendingPubKey = session.pubKeyHex;
        final int attemptId = ++roomLoginAttemptId;
        session.roomLoginSucceededThisConnection = false;
        session.roomLoginNotFoundRetryUsed = false;
        MeshRoomPasswordStore.savePassword(ctx, meshBtManager.getConnectedDeviceAddress(),
                session.pubKeyHex, password != null ? password : "");
        removeRoomLoginFailurePlaceholder(session);
        appendMeshContactChatLine(session, false, "(Logging in — retrieving posts…)", null);
        renderRoomChatIfVisible(session);
        final String loginPassword = password != null ? password : "";
        Runnable sendLogin = () -> {
            if (meshBtManager == null || !meshBtManager.isConnected()) {
                roomLoginPendingPubKey = null;
                return;
            }
            appendLog("Sending room login for '" + session.displayName + "' (fullReset="
                    + resetContactForFullHistory + ")");
            if (!meshBtManager.sendRoomLogin(session.pubKeyHex, loginPassword)) {
                Toast.makeText(ctx, "Failed to send room login.", Toast.LENGTH_SHORT).show();
                pendingRoomLoginAction = PendingRoomLoginAction.NONE;
                pendingRoomPubKeyHex = null;
                roomLoginPendingPubKey = null;
                return;
            }
            scheduleRoomLoginTimeout(session, attemptId, loginPassword, resetContactForFullHistory);
        };
        MeshBtConnectionManager.MeshDeviceContact contact = resolveRoomDeviceContact(session);
        if (session.isRoom && resetContactForFullHistory) {
            meshBtManager.prepareRoomContactForFullHistorySync(contact, sendLogin);
        } else {
            meshBtManager.cancelRoomContactPrepare();
            meshBtManager.addOrUpdateDeviceContactFavorite(contact);
            long delayMs = action == PendingRoomLoginAction.JOIN_ONLY
                    ? ROOM_CONTACT_ADD_DELAY_MS : 300L;
            getMapView().postDelayed(sendLogin, delayMs);
        }
    }

    private MeshBtConnectionManager.MeshDeviceContact resolveRoomDeviceContact(
            MeshContactChatSession session) {
        MeshBtConnectionManager.MeshDeviceContact fallback =
                new MeshBtConnectionManager.MeshDeviceContact(
                        session.pubKeyHex, MeshBtConnectionManager.ADV_TYPE_ROOM, 0,
                        MeshBtConnectionManager.OUT_PATH_UNKNOWN,
                        session.displayName, 0, 0.0, 0.0,
                        (int) (System.currentTimeMillis() / 1000L));
        if (meshBtManager == null) {
            return fallback;
        }
        Context ctx = getMapView().getContext();
        String deviceAddr = meshBtManager.getConnectedDeviceAddress();
        MeshBtConnectionManager.MeshDeviceContact cached =
                MeshDeviceContactCache.findByPubKeyPrefix(ctx, deviceAddr, session.pubKeyHex);
        if (cached != null && cached.type == MeshBtConnectionManager.ADV_TYPE_ROOM) {
            return cached;
        }
        return fallback;
    }

    private void beginRoomServerSession(MeshContactChatSession session) {
        if (session == null || meshBtManager == null) {
            return;
        }
        meshBtManager.requestContactByPubKeyHex(session.pubKeyHex);
        if (session.roomLoginSucceededThisConnection) {
            removeRoomLoginPlaceholder(session);
            removeRoomLoginFailurePlaceholder(session);
            renderRoomChatIfVisible(session);
            return;
        }
        Context ctx = getMapView().getContext();
        String deviceAddr = meshBtManager.getConnectedDeviceAddress();
        if (!MeshRoomPasswordStore.hasPasswordStored(ctx, deviceAddr, session.pubKeyHex)) {
            promptRoomPasswordAndLogin(session, PendingRoomLoginAction.OPEN_CHAT);
            return;
        }
        String pwd = MeshRoomPasswordStore.getPassword(ctx, deviceAddr, session.pubKeyHex);
        sendRoomLoginAfterContactSync(session, pwd != null ? pwd : "",
                PendingRoomLoginAction.OPEN_CHAT);
    }

    private void promptRoomPasswordAndLogin(MeshContactChatSession session,
                                            PendingRoomLoginAction action) {
        if (session == null || meshBtManager == null) {
            return;
        }
        Context ctx = getMapView().getContext();
        EditText pwdField = new EditText(ctx);
        pwdField.setHint("Guest password (blank = read-only)");
        pwdField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwdField.setSingleLine(true);
        new AlertDialog.Builder(ctx)
                .setTitle("Room Password")
                .setMessage("Enter the password for \"" + session.displayName + "\".\n"
                        + "Leave blank if the room allows read-only access.")
                .setView(pwdField)
                .setPositiveButton("Login", (d, w) -> {
                    String pwd = fieldText(pwdField);
                    MeshRoomPasswordStore.savePassword(ctx, meshBtManager.getConnectedDeviceAddress(),
                            session.pubKeyHex, pwd);
                    sendRoomLoginAfterContactSync(session, pwd, action);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void handleRoomLoginSuccess(String pubKeyPrefixHex12, int permissions) {
        if (meshBtManager == null) {
            return;
        }
        pendingRoomLoginAction = PendingRoomLoginAction.NONE;
        cancelRoomLoginTimeout();
        roomLoginAttemptId++;
        roomLoginPendingPubKey = null;
        Context ctx = getMapView().getContext();
        MeshContactChatSession session = findRoomSessionForPrefix(pubKeyPrefixHex12);
        if (session == null) {
            appendLog("Room login OK prefix=" + pubKeyPrefixHex12);
            scheduleRoomPostSyncDrains(null);
            return;
        }
        session.roomLoginSucceededThisConnection = true;
        String label = session.displayName;
        appendLog("Room login OK for " + label + " perm=" + permissions);
        pendingRoomPubKeyHex = session.pubKeyHex;
        removeRoomLoginPlaceholder(session);
        removeRoomLoginFailurePlaceholder(session);
        appendMeshContactChatLine(session, false, "(Logged in.)", null);
        renderRoomChatIfVisible(session);
        Toast.makeText(ctx, "Logged in to " + label,
                Toast.LENGTH_SHORT).show();
        scheduleRoomPostSyncDrains(session);
        scheduleRoomEmptyPostSyncRetry(session);
    }

    private void scheduleRoomEmptyPostSyncRetry(@Nullable MeshContactChatSession session) {
        if (session == null || !session.isRoom) {
            return;
        }
        cancelRoomEmptyPostSyncRetry();
        final MeshContactChatSession retrySession = session;
        roomEmptyPostSyncRetryRunnable = () -> {
            roomEmptyPostSyncRetryRunnable = null;
            if (retrySession.roomEmptyPostSyncRetryUsed || hasRealChatLines(retrySession)) {
                return;
            }
            if (!retrySession.roomLoginSucceededThisConnection
                    || meshBtManager == null || !meshBtManager.isConnected()) {
                return;
            }
            retrySession.roomEmptyPostSyncRetryUsed = true;
            appendLog("No room posts after login for " + retrySession.displayName
                    + " — resetting sync_since and retrying…");
            removeRoomLoginPlaceholder(retrySession);
            appendMeshContactChatLine(retrySession, false, "(Retrying full post sync…)", null);
            renderRoomChatIfVisible(retrySession);
            retrySession.roomLoginSucceededThisConnection = false;
            Context ctx = getMapView().getContext();
            String pwd = MeshRoomPasswordStore.getPassword(ctx,
                    meshBtManager.getConnectedDeviceAddress(), retrySession.pubKeyHex);
            sendRoomLoginAfterContactSync(retrySession, pwd != null ? pwd : "",
                    PendingRoomLoginAction.OPEN_CHAT, true);
        };
        getMapView().postDelayed(roomEmptyPostSyncRetryRunnable, ROOM_EMPTY_POST_SYNC_RETRY_MS);
    }

    private void cancelRoomEmptyPostSyncRetry() {
        if (roomEmptyPostSyncRetryRunnable != null) {
            getMapView().removeCallbacks(roomEmptyPostSyncRetryRunnable);
            roomEmptyPostSyncRetryRunnable = null;
        }
    }

    private void scheduleRoomPostSyncDrains(@Nullable MeshContactChatSession session) {
        if (meshBtManager == null) {
            return;
        }
        meshBtManager.beginRoomPostSyncSession();
        drainRoomPostSyncMessages();
        long[] delays = {750L, 2000L, 4000L, 8000L, 15000L, 30000L, 45000L,
                60000L, 90000L, 120000L, 180000L, 240000L};
        for (long delay : delays) {
            getMapView().postDelayed(this::drainRoomPostSyncMessages, delay);
        }
        if (roomPostSyncFinishRunnable != null) {
            getMapView().removeCallbacks(roomPostSyncFinishRunnable);
        }
        final MeshContactChatSession finishSession = session;
        roomPostSyncFinishRunnable = () -> finishRoomPostSyncIfNeeded(finishSession);
        getMapView().postDelayed(roomPostSyncFinishRunnable, ROOM_POST_SYNC_FINISH_MS);
    }

    private void drainRoomPostSyncMessages() {
        if (meshBtManager != null && meshBtManager.isConnected()
                && meshBtManager.isRoomPostSyncSessionActive()) {
            meshBtManager.requestMessageDrain(16);
        }
    }

    private void finishRoomPostSyncIfNeeded(@Nullable MeshContactChatSession session) {
        if (meshBtManager != null) {
            meshBtManager.endRoomPostSyncSession();
        }
        if (session != null) {
            removeRoomLoginPlaceholder(session);
            if (!hasRealChatLines(session)) {
                appendMeshContactChatLine(session, false, "(No posts received yet)", null);
            }
            renderRoomChatIfVisible(session);
        }
    }

    private static boolean hasRealChatLines(MeshContactChatSession session) {
        if (session == null) {
            return false;
        }
        for (String line : session.lines) {
            String display = chatLineDisplay(line);
            if (display == null) {
                continue;
            }
            if (display.contains("Logging in") || display.contains("retrieving full history")
                    || display.contains("retrieving posts")
                    || display.contains("syncing posts")
                    || display.contains("Login complete")
                    || display.contains("Logged in.")
                    || display.contains("No posts received yet")
                    || display.contains("Retrying full post sync")) {
                continue;
            }
            if (display.contains(": ")) {
                return true;
            }
        }
        return false;
    }

    private void handleRoomLoginFail(String pubKeyPrefixHex12) {
        cancelRoomLoginTimeout();
        pendingRoomLoginAction = PendingRoomLoginAction.NONE;
        roomLoginPendingPubKey = null;
        appendLog("Room login failed prefix=" + pubKeyPrefixHex12);
        Toast.makeText(getMapView().getContext(),
                "Room login failed — check password and radio path.",
                Toast.LENGTH_LONG).show();
        MeshContactChatSession session = findRoomSessionForPrefix(pubKeyPrefixHex12);
        if (session != null) {
            removeRoomLoginPlaceholder(session);
            appendMeshContactChatLine(session, false, "(Login failed — check password)", null);
            renderRoomChatIfVisible(session);
        }
        pendingRoomPubKeyHex = null;
    }

    private void handleRoomCompanionCommandError(int errCode) {
        if (pendingRoomLoginAction == PendingRoomLoginAction.NONE || pendingRoomPubKeyHex == null) {
            return;
        }
        if (errCode != 2) {
            appendLog("Room login companion error code=" + errCode);
            return;
        }
        if (roomLoginPendingPubKey != null || (meshBtManager != null
                && meshBtManager.isRoomContactPrepareInProgress())) {
            return;
        }
        MeshContactChatSession session = findSessionByPubKey(pendingRoomPubKeyHex);
        if (session == null || meshBtManager == null) {
            return;
        }
        appendLog("Room contact not on radio — re-adding and retrying login…");
        Context ctx = getMapView().getContext();
        String pwd = MeshRoomPasswordStore.getPassword(ctx,
                meshBtManager.getConnectedDeviceAddress(), session.pubKeyHex);
        if (pwd == null) {
            pwd = "";
        }
        final PendingRoomLoginAction retryAction = pendingRoomLoginAction;
        sendRoomLoginAfterContactSync(session, pwd, retryAction, true);
    }

    @Nullable
    private MeshContactChatSession findRoomSessionForPrefix(@Nullable String pubKeyPrefixHex12) {
        if (pubKeyPrefixHex12 == null || pubKeyPrefixHex12.isEmpty()) {
            return null;
        }
        String prefix = pubKeyPrefixHex12.trim().toUpperCase(Locale.US);
        if (pendingRoomPubKeyHex != null) {
            MeshContactChatSession session = findSessionByPubKey(pendingRoomPubKeyHex);
            if (session != null
                    && session.pubKeyHex.toUpperCase(Locale.US).startsWith(prefix)) {
                return session;
            }
        }
        if (activeMeshContactPubKey != null) {
            MeshContactChatSession session = findSessionByPubKey(activeMeshContactPubKey);
            if (session != null
                    && session.pubKeyHex.toUpperCase(Locale.US).startsWith(prefix)) {
                return session;
            }
        }
        for (MeshContactChatSession session : meshContactChatSessions.values()) {
            if (session.isRoom
                    && session.pubKeyHex.toUpperCase(Locale.US).startsWith(prefix)) {
                return session;
            }
        }
        return null;
    }

    private void removeRoomLoginPlaceholder(MeshContactChatSession session) {
        if (session == null) {
            return;
        }
        session.lines.removeIf(line -> {
            String display = chatLineDisplay(line);
            return display.contains("Logging in")
                    || display.contains("retrieving full history")
                    || display.contains("retrieving posts")
                    || display.contains("syncing posts")
                    || display.contains("Login complete")
                    || display.contains("Logged in.")
                    || display.contains("Retrying full post sync");
        });
        if (session.isRoom) {
            persistRoomChatHistory(session);
        }
    }

    private void removeRoomLoginFailurePlaceholder(MeshContactChatSession session) {
        if (session == null) {
            return;
        }
        session.lines.removeIf(line -> {
            String display = chatLineDisplay(line);
            return display.contains("Login timed out") || display.contains("Login failed");
        });
    }

    private void scheduleRoomLoginTimeout(MeshContactChatSession session, int attemptId,
                                          String password) {
        scheduleRoomLoginTimeout(session, attemptId, password, false);
    }

    private void scheduleRoomLoginTimeout(MeshContactChatSession session, int attemptId,
                                          String password, boolean fullResetAttempt) {
        cancelRoomLoginTimeout();
        long timeoutMs = fullResetAttempt
                ? ROOM_LOGIN_FULL_RESET_TIMEOUT_MS : ROOM_LOGIN_TIMEOUT_MS;
        pendingRoomLoginTimeoutRunnable = () -> {
            if (attemptId != roomLoginAttemptId) {
                return;
            }
            if (pendingRoomLoginAction == PendingRoomLoginAction.NONE || session == null) {
                return;
            }
            if (session.roomLoginSucceededThisConnection) {
                return;
            }
            if (!session.roomLoginFullResetRetryUsed && session.isRoom) {
                session.roomLoginFullResetRetryUsed = true;
                appendLog("Room login timed out for " + session.displayName
                        + " — retrying with contact reset…");
                removeRoomLoginFailurePlaceholder(session);
                PendingRoomLoginAction retryAction = pendingRoomLoginAction;
                sendRoomLoginAfterContactSync(session, password, retryAction, true);
                return;
            }
            removeRoomLoginPlaceholder(session);
            appendMeshContactChatLine(session, false,
                    "(Login timed out — check password, path, and radio connection)", null);
            renderRoomChatIfVisible(session);
            pendingRoomLoginAction = PendingRoomLoginAction.NONE;
            appendLog("Room login timed out for " + session.displayName);
            roomLoginPendingPubKey = null;
        };
        getMapView().postDelayed(pendingRoomLoginTimeoutRunnable, timeoutMs);
    }

    private void clearRoomLoginStateForDisconnect() {
        roomLoginAttemptId++;
        roomLoginPendingPubKey = null;
        cancelRoomLoginTimeout();
        cancelRoomEmptyPostSyncRetry();
        cancelRoomPostSyncFinish();
        pendingRoomLoginAction = PendingRoomLoginAction.NONE;
        pendingRoomPubKeyHex = null;
        for (MeshContactChatSession session : meshContactChatSessions.values()) {
            session.roomLoginSucceededThisConnection = false;
            session.roomLoginFullResetRetryUsed = false;
            session.roomLoginNotFoundRetryUsed = false;
            session.roomEmptyPostSyncRetryUsed = false;
            session.roomLastOpenedMs = 0L;
            session.roomPostsReceivedThisSession = 0;
        }
    }

    private void cancelRoomLoginTimeout() {
        if (pendingRoomLoginTimeoutRunnable != null) {
            getMapView().removeCallbacks(pendingRoomLoginTimeoutRunnable);
            pendingRoomLoginTimeoutRunnable = null;
        }
    }

    private void cancelRoomPostSyncFinish() {
        if (roomPostSyncFinishRunnable != null) {
            getMapView().removeCallbacks(roomPostSyncFinishRunnable);
            roomPostSyncFinishRunnable = null;
        }
    }

    @Nullable
    private String resolveRoomAuthorLabel(@Nullable String authorPrefixHex) {
        if (authorPrefixHex == null || authorPrefixHex.length() < 4 || meshBtManager == null) {
            return null;
        }
        String prefix = authorPrefixHex.trim().toUpperCase(Locale.US);
        java.util.List<MeshBtConnectionManager.MeshDeviceContact> contacts =
                MeshDeviceContactCache.load(getMapView().getContext(),
                        meshBtManager.getConnectedDeviceAddress());
        for (MeshBtConnectionManager.MeshDeviceContact c : contacts) {
            if (c.pubKeyHex != null
                    && c.pubKeyHex.toUpperCase(Locale.US).startsWith(prefix)) {
                return c.name != null && !c.name.isEmpty() ? c.name : prefix;
            }
        }
        return prefix.length() >= 8 ? prefix.substring(0, 8) : prefix;
    }

    @Nullable
    private static String normalizePubKeyHex(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        return raw.trim().replace(" ", "").toLowerCase(Locale.US);
    }

    private static String fieldText(EditText field) {
        return field.getText() != null ? field.getText().toString().trim() : "";
    }

    private EditText addLabeledField(LinearLayout layout, Context ctx, String label, String hint,
                                     boolean password) {
        TextView lbl = new TextView(ctx);
        lbl.setText(label);
        lbl.setTextColor(0xFFAAAAAA);
        lbl.setTextSize(12f);
        layout.addView(lbl);
        EditText field = new EditText(ctx);
        field.setHint(hint);
        field.setSingleLine(true);
        if (password) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dip(ctx, 10);
        layout.addView(field, lp);
        return field;
    }

    public static final String ACTION_QR_CHANNEL_RESULT = "com.uvpro.plugin.QR_CHANNEL_RESULT";
    public static final String EXTRA_QR_RESULT = "qr_result";

    private boolean pendingQrScan = false;

    private Runnable qrPollRunnable = null;

    private void showQrScanDialog() {
        QrResultProvider.clearPending(getMapView().getContext());

        pendingQrScan = true;
        Intent launch = new Intent(pluginContext, QrScanActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        pluginContext.startActivity(launch);

        // Poll ContentProvider every second — reliable across plugin/ATAK UIDs.
        if (qrPollRunnable != null) {
            getMapView().removeCallbacks(qrPollRunnable);
        }
        qrPollRunnable = new Runnable() {
            private int attempts = 0;
            @Override
            public void run() {
                attempts++;
                if (attempts > 30) {
                    pendingQrScan = false;
                    qrPollRunnable = null;
                    return;
                }
                String content = QrResultProvider.consumePending(
                        getMapView().getContext(), 60_000L);
                if (content != null && !content.isEmpty()) {
                    pendingQrScan = false;
                    qrPollRunnable = null;
                    handleQrChannelResult(content);
                    return;
                }
                getMapView().postDelayed(this, 1000L);
            }
        };
        getMapView().postDelayed(qrPollRunnable, 500L);
    }

    /** Parse MeshCore QR payloads (channels and contacts). */
    private void handleQrChannelResult(String rawContent) {
        pendingQrScan = false;
        QrResultProvider.clearPending(getMapView().getContext());
        if (rawContent == null || rawContent.trim().isEmpty()) return;
        Log.d(TAG, "QR result received: " + rawContent);
        try {
            android.net.Uri uri = android.net.Uri.parse(rawContent.trim());
            if (!"meshcore".equals(uri.getScheme())) {
                showJoinPrivateDialogFromQr(null, rawContent);
                return;
            }
            String host = uri.getHost();
            String path = uri.getPath();
            if ("contact".equalsIgnoreCase(host) && isMeshcoreAddPath(path)) {
                handleQrContactAdd(uri);
                return;
            }
            if ("channel".equalsIgnoreCase(host) && isMeshcoreAddPath(path)) {
                handleQrChannelAdd(uri);
                return;
            }
            if (isMeshcoreAddPath(path) || "/channel/add".equals(path)
                    || "channel/add".equals(path)) {
                handleQrChannelAdd(uri);
                return;
            }
            showJoinPrivateDialogFromQr(null, rawContent);
        } catch (Exception e) {
            Log.w(TAG, "QR parse failed: " + rawContent, e);
            showJoinPrivateDialogFromQr(null, rawContent);
        }
    }

    private static boolean isMeshcoreAddPath(@Nullable String path) {
        return "/add".equals(path);
    }

    /** meshcore://contact/add?name=X&public_key=Y&type=Z (type 3 = room server). */
    private void handleQrContactAdd(android.net.Uri uri) {
        String name = uri.getQueryParameter("name");
        String publicKeyRaw = uri.getQueryParameter("public_key");
        String typeRaw = uri.getQueryParameter("type");
        String pubKey = normalizePubKeyHex(publicKeyRaw);
        int contactType = -1;
        if (typeRaw != null && !typeRaw.trim().isEmpty()) {
            try {
                contactType = Integer.parseInt(typeRaw.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (pubKey == null || pubKey.length() != 64) {
            Toast.makeText(getMapView().getContext(),
                    "QR contact is missing a valid 64-character public key.",
                    Toast.LENGTH_LONG).show();
            if (contactType == MeshBtConnectionManager.ADV_TYPE_ROOM) {
                MeshBtConnectionManager.MeshDeviceContact preset =
                        new MeshBtConnectionManager.MeshDeviceContact(
                                publicKeyRaw != null ? publicKeyRaw.trim() : "",
                                MeshBtConnectionManager.ADV_TYPE_ROOM, 0, 0,
                                name != null ? name.trim() : "Room",
                                0, 0.0, 0.0, (int) (System.currentTimeMillis() / 1000L));
                showJoinRoomServerManualDialog(preset);
            }
            return;
        }
        String displayName = name != null && !name.trim().isEmpty()
                ? name.trim()
                : (pubKey.length() >= 12 ? pubKey.substring(0, 12) : "Contact");
        if (contactType == MeshBtConnectionManager.ADV_TYPE_ROOM) {
            if (meshBtManager == null || !meshBtManager.isConnected()) {
                Toast.makeText(getMapView().getContext(),
                        "Connect to a MeshCore node first.",
                        Toast.LENGTH_SHORT).show();
                MeshBtConnectionManager.MeshDeviceContact preset =
                        new MeshBtConnectionManager.MeshDeviceContact(
                                pubKey, MeshBtConnectionManager.ADV_TYPE_ROOM, 0, 0, displayName,
                                0, 0.0, 0.0, (int) (System.currentTimeMillis() / 1000L));
                showJoinRoomServerManualDialog(preset);
                return;
            }
            MeshBtConnectionManager.MeshDeviceContact contact =
                    new MeshBtConnectionManager.MeshDeviceContact(
                            pubKey, MeshBtConnectionManager.ADV_TYPE_ROOM, 0, 0, displayName,
                            0, 0.0, 0.0, (int) (System.currentTimeMillis() / 1000L));
            joinRoomServerContact(contact, null);
            Toast.makeText(getMapView().getContext(),
                    "Joining room '" + displayName + "' from QR…",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (contactType == MeshBtConnectionManager.ADV_TYPE_CHAT) {
            if (meshBtManager == null || !meshBtManager.isConnected()) {
                Toast.makeText(getMapView().getContext(),
                        "Connect to a MeshCore node first.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            MeshBtConnectionManager.MeshDeviceContact contact =
                    new MeshBtConnectionManager.MeshDeviceContact(
                            pubKey, MeshBtConnectionManager.ADV_TYPE_CHAT, 0, 0, displayName,
                            0, 0.0, 0.0, (int) (System.currentTimeMillis() / 1000L));
            MeshDeviceContactCache.upsertFromDeviceContact(getMapView().getContext(),
                    meshBtManager.getConnectedDeviceAddress(), contact);
            meshBtManager.addOrUpdateDeviceContactFavorite(contact);
            ensureContactChatSession(contact);
            selectMeshContactChat(pubKey);
            Toast.makeText(getMapView().getContext(),
                    "Contact '" + displayName + "' added from QR.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(getMapView().getContext(),
                "Unsupported contact QR type (" + contactType + ").",
                Toast.LENGTH_LONG).show();
    }

    /** meshcore://channel/add?name=X&secret=Y */
    private void handleQrChannelAdd(android.net.Uri uri) {
        String name = uri.getQueryParameter("name");
        String secret = uri.getQueryParameter("secret");
        if (name != null && secret != null && secret.length() == 32) {
            byte[] key = hexToBytes(secret.toLowerCase(Locale.US));
            if (key != null && addChannelToNode(name.trim(), key)) {
                Toast.makeText(getMapView().getContext(),
                        "Channel '" + name.trim() + "' added from QR.",
                        Toast.LENGTH_SHORT).show();
                buildMeshChannelButtonStrip();
                return;
            }
        }
        showJoinPrivateDialogFromQr(name, secret);
    }

    /** Pre-filled join dialog shown after QR scan. */
    private void showJoinPrivateDialogFromQr(String name, String secret) {
        Context ctx = getMapView().getContext();
        int pad = dip(ctx, 16);
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad / 2, pad, 0);

        TextView nameLbl = new TextView(ctx);
        nameLbl.setText("Channel Name");
        nameLbl.setTextColor(0xFFAAAAAA);
        nameLbl.setTextSize(12f);
        layout.addView(nameLbl);
        EditText nameField = new EditText(ctx);
        nameField.setHint("Channel name");
        nameField.setInputType(InputType.TYPE_CLASS_TEXT);
        nameField.setSingleLine(true);
        if (name != null) nameField.setText(name);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.bottomMargin = dip(ctx, 10);
        layout.addView(nameField, nlp);

        TextView secLbl = new TextView(ctx);
        secLbl.setText("Secret Key (32 hex chars)");
        secLbl.setTextColor(0xFFAAAAAA);
        secLbl.setTextSize(12f);
        layout.addView(secLbl);
        EditText secretField = new EditText(ctx);
        secretField.setHint("32 hex characters");
        secretField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        secretField.setSingleLine(true);
        if (secret != null) secretField.setText(secret);
        layout.addView(secretField, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog d = new AlertDialog.Builder(ctx)
                .setTitle("Add Channel from QR")
                .setView(layout)
                .setPositiveButton("Add", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(ds -> {
            Button addBtn = d.getButton(AlertDialog.BUTTON_POSITIVE);
            if (addBtn == null) return;
            addBtn.setOnClickListener(v -> {
                String n = nameField.getText() != null
                        ? nameField.getText().toString().trim() : "";
                String s = secretField.getText() != null
                        ? secretField.getText().toString().trim().toLowerCase(Locale.US) : "";
                if (n.isEmpty()) { nameField.setError("Name required"); return; }
                if (s.length() != 32) {
                    secretField.setError("Must be 32 hex characters"); return;
                }
                byte[] key = hexToBytes(s);
                if (key == null) { secretField.setError("Invalid hex"); return; }
                if (addChannelToNode(n, key)) d.dismiss();
            });
        });
        d.show();
    }

    /** Build a reusable channel name [+ secret] form layout. Tag = nameField. */
    private LinearLayout buildChannelDialogLayout(Context ctx,
            boolean showName, boolean showSecret, boolean showPassphrase) {
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dip(ctx, 16);
        layout.setPadding(pad, pad / 2, pad, 0);

        EditText nameField = null;
        if (showName) {
            TextView lbl = new TextView(ctx);
            lbl.setText("Channel Name");
            lbl.setTextColor(0xFFAAAAAA);
            lbl.setTextSize(12f);
            layout.addView(lbl);
            nameField = new EditText(ctx);
            nameField.setHint("e.g. OPS, #test");
            nameField.setInputType(InputType.TYPE_CLASS_TEXT);
            nameField.setSingleLine(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dip(ctx, 10);
            layout.addView(nameField, lp);
            layout.setTag(nameField);
        }

        if (showSecret) {
            TextView lbl = new TextView(ctx);
            lbl.setText("Secret Key (32 hex chars)");
            lbl.setTextColor(0xFFAAAAAA);
            lbl.setTextSize(12f);
            layout.addView(lbl);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            EditText secretField = new EditText(ctx);
            secretField.setHint("e.g. 8b3387e9c5cdea6ac9e5edbaa115cd72");
            secretField.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            secretField.setSingleLine(true);
            row.addView(secretField, new LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            layout.addView(row);
        }

        return layout;
    }

    /** Find free slot and set channel; returns true on success. */
    private boolean addChannelToNode(String name, byte[] secret) {
        int targetSlot = -1;
        for (int i = 0; i < 7; i++) {
            String existing = meshChannelNames.get(i);
            if (name.equalsIgnoreCase(existing != null ? existing.trim() : "")) {
                targetSlot = i; break;
            }
        }
        if (targetSlot < 0) {
            for (int i = 0; i < 7; i++) {
                String existing = meshChannelNames.get(i);
                if (existing == null || existing.trim().isEmpty()) {
                    targetSlot = i; break;
                }
            }
        }
        if (targetSlot < 0) {
            Toast.makeText(getMapView().getContext(),
                    "All channel slots are full (max 7). Long-press a channel to remove it.",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        if (!meshBtManager.setChannelSlot(targetSlot, name, secret)) {
            Toast.makeText(getMapView().getContext(),
                    "Failed — not connected.", Toast.LENGTH_SHORT).show();
            return false;
        }
        appendLog("Channel '" + name + "' added to slot " + targetSlot + ".");
        return true;
    }

    /** First 16 bytes of SHA-256(input). Used for hashtag channel key derivation. */
    private static byte[] sha256First16(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] full = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] key = new byte[16];
            System.arraycopy(full, 0, key, 0, 16);
            return key;
        } catch (Exception e) {
            return new byte[16];
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) return null;
        try {
            byte[] out = new byte[hex.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void showManagedChannelDetails(ManagedChannelEntry entry) {
        if (entry == null) {
            return;
        }
        if (entry.type == ManagedChannelEntry.TYPE_ROOM) {
            showRoomDetailsDialog(entry.label, entry.pubKeyHex);
        } else {
            showChannelSettingsMenu(entry.slot, entry.label);
        }
    }

    private void showRoomDetailsDialog(@Nullable String roomName, @Nullable String pubKeyRaw) {
        Context ctx = getMapView().getContext();
        String displayName = roomName != null && !roomName.trim().isEmpty()
                ? roomName.trim() : "Room";
        String pubKey = normalizePubKeyHex(pubKeyRaw);

        int pad = dip(ctx, 16);
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad / 2, pad, pad / 2);

        TextView typeLbl = new TextView(ctx);
        typeLbl.setText("Room Server");
        typeLbl.setTextColor(0xFFAAAAAA);
        typeLbl.setTextSize(12f);
        layout.addView(typeLbl, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView nameLbl = new TextView(ctx);
        nameLbl.setText(displayName);
        nameLbl.setTextColor(0xFFFFFFFF);
        nameLbl.setTextSize(16f);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nameLp.bottomMargin = dip(ctx, 8);
        layout.addView(nameLbl, nameLp);

        if (pubKey != null && pubKey.length() == 64) {
            TextView pubLbl = new TextView(ctx);
            pubLbl.setText("Public Key (long-press to copy)");
            pubLbl.setTextColor(0xFFAAAAAA);
            pubLbl.setTextSize(12f);
            layout.addView(pubLbl, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            EditText pubView = new EditText(ctx);
            pubView.setText(pubKey);
            pubView.setTextIsSelectable(true);
            pubView.setFocusableInTouchMode(true);
            pubView.setInputType(InputType.TYPE_NULL);
            pubView.setTextSize(12f);
            pubView.setTextColor(0xFF00BCD4);
            pubView.setBackgroundColor(0xFF1A1A1A);
            pubView.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
            layout.addView(pubView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            String qrContent = "meshcore://contact/add?name="
                    + Uri.encode(displayName)
                    + "&public_key=" + pubKey
                    + "&type=" + MeshBtConnectionManager.ADV_TYPE_ROOM;
            android.graphics.Bitmap qrBmp = generateQrBitmap(qrContent, 400);
            if (qrBmp != null) {
                TextView qrLbl = new TextView(ctx);
                qrLbl.setText("Share QR (scan to join this room)");
                qrLbl.setTextColor(0xFFAAAAAA);
                qrLbl.setTextSize(12f);
                LinearLayout.LayoutParams qrLblLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                qrLblLp.topMargin = dip(ctx, 12);
                layout.addView(qrLbl, qrLblLp);

                android.widget.ImageView qrView = new android.widget.ImageView(ctx);
                int qrSizePx = dip(ctx, 240);
                LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qrSizePx, qrSizePx);
                qrLp.gravity = Gravity.CENTER_HORIZONTAL;
                qrLp.topMargin = dip(ctx, 8);
                qrView.setImageBitmap(qrBmp);
                qrView.setBackgroundColor(android.graphics.Color.WHITE);
                qrView.setPadding(dip(ctx, 8), dip(ctx, 8), dip(ctx, 8), dip(ctx, 8));
                qrView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                layout.addView(qrView, qrLp);
            }
        } else {
            TextView warn = new TextView(ctx);
            warn.setText("Public key unavailable — reconnect or sync contacts from the radio.");
            warn.setTextColor(0xFF888888);
            warn.setTextSize(13f);
            layout.addView(warn, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        scroll.addView(layout);
        new AlertDialog.Builder(ctx)
                .setTitle("Room Details")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .show();
    }

    private void showChannelSettingsMenu(int slotIdx, String channelName) {
        Context ctx = getMapView().getContext();
        String[] options = {"Share", "Rename", "Participants", "Remove"};
        new AlertDialog.Builder(ctx)
                .setTitle(channelName)
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: showChannelShare(slotIdx, channelName);        break;
                        case 1: showChannelRename(slotIdx, channelName);       break;
                        case 2: showChannelParticipants(slotIdx, channelName); break;
                        case 3: removeChannelByName(channelName);              break;
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showChannelShare(int slotIdx, String channelName) {
        Context ctx = getMapView().getContext();
        byte[] secret = meshBtManager != null ? meshBtManager.getChannelSecret(slotIdx) : null;
        if (secret == null) {
            Toast.makeText(ctx, "Secret not available — reconnect to refresh channel info.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String hex = bytesToHex(secret);
        String qrContent = "meshcore://channel/add?name="
                + Uri.encode(channelName) + "&secret=" + hex;

        int pad = dip(ctx, 16);
        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad / 2, pad, pad / 2);

        // QR code — centred via per-view gravity, not root gravity
        android.graphics.Bitmap qrBmp = generateQrBitmap(qrContent, 400);

        TextView secLbl = new TextView(ctx);
        secLbl.setText("Secret Key (long-press to copy)");
        secLbl.setTextColor(0xFFAAAAAA);
        secLbl.setTextSize(12f);
        layout.addView(secLbl, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.EditText secretView = new android.widget.EditText(ctx);
        secretView.setText(hex);
        secretView.setTextIsSelectable(true);
        secretView.setFocusableInTouchMode(true);
        secretView.setInputType(android.text.InputType.TYPE_NULL);
        secretView.setTextSize(13f);
        secretView.setTextColor(0xFF00BCD4);
        secretView.setBackgroundColor(0xFF1A1A1A);
        secretView.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        layout.addView(secretView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (qrBmp != null) {
            android.widget.ImageView qrView = new android.widget.ImageView(ctx);
            int qrSizePx = dip(ctx, 240);
            LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qrSizePx, qrSizePx);
            qrLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            qrLp.topMargin = dip(ctx, 12);
            qrView.setImageBitmap(qrBmp);
            qrView.setBackgroundColor(android.graphics.Color.WHITE);
            qrView.setPadding(dip(ctx, 8), dip(ctx, 8), dip(ctx, 8), dip(ctx, 8));
            qrView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            layout.addView(qrView, qrLp);
        }

        scroll.addView(layout);
        new AlertDialog.Builder(ctx)
                .setTitle("Share — " + channelName)
                .setView(scroll)
                .setPositiveButton("Done", null)
                .show();
    }

    private void showChannelRename(int slotIdx, String currentName) {
        Context ctx = getMapView().getContext();
        int pad = dip(ctx, 16);
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad / 2, pad, 0);

        android.widget.EditText nameField = new android.widget.EditText(ctx);
        nameField.setText(currentName);
        nameField.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        nameField.setSingleLine(true);
        nameField.selectAll();
        layout.addView(nameField, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog renameDialog = new AlertDialog.Builder(ctx)
                .setTitle("Rename Channel")
                .setView(layout)
                .setPositiveButton("Rename", null)
                .setNegativeButton("Cancel", null)
                .create();
        renameDialog.setOnShowListener(d -> {
            android.widget.Button btn = renameDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (btn == null) return;
            btn.setOnClickListener(v -> {
                String newName = nameField.getText() != null
                        ? nameField.getText().toString().trim() : "";
                if (newName.isEmpty()) { nameField.setError("Name required"); return; }
                if (newName.length() > 32) newName = newName.substring(0, 32);
                byte[] secret = meshBtManager != null
                        ? meshBtManager.getChannelSecret(slotIdx) : null;
                if (secret == null) secret = new byte[16];
                if (meshBtManager.setChannelSlot(slotIdx, newName, secret)) {
                    meshChannelNames.put(slotIdx, newName);
                    if (meshChannelChatActiveIndex == slotIdx && meshChannelTitleView != null) {
                        meshChannelTitleView.setText("Channel #" + slotIdx + " — " + newName);
                    }
                    updateMeshChannelButtonLabel();
                    appendLog("Channel renamed to '" + newName + "'.");
                    renameDialog.dismiss();
                }
            });
        });
        renameDialog.show();
    }

    private void showChannelParticipants(int slotIdx, String channelName) {
        Context ctx = getMapView().getContext();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        java.util.LinkedList<MeshBtConnectionManager.MeshChannelMessage> bucket =
                meshChannelMessages.get(slotIdx);
        if (bucket != null) {
            for (MeshBtConnectionManager.MeshChannelMessage m : bucket) {
                if (!m.outbound) {
                    String sender = resolveMeshChannelSenderName(m);
                    if (sender != null && !sender.isEmpty() && !"Node".equals(sender)) {
                        seen.add(sender);
                    }
                }
            }
        }
        String body = seen.isEmpty()
                ? "No participants seen yet.\nParticipants appear here as messages are received."
                : String.join("\n", seen);
        new AlertDialog.Builder(ctx)
                .setTitle("Participants — " + channelName)
                .setMessage(body)
                .setPositiveButton("OK", null)
                .show();
    }

    /** Remove a channel by name. Called from long-press on a channel button. */
    private void removeChannelByName(String channelName) {
        if (channelName == null || channelName.trim().isEmpty()) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            String existing = meshChannelNames.get(i);
            if (channelName.trim().equalsIgnoreCase(existing != null ? existing.trim() : "")) {
                confirmRemoveManagedChannelEntry(
                        ManagedChannelEntry.group(i, channelName.trim()), null);
                return;
            }
        }
    }


    private void appendMeshChannelMessage(MeshBtConnectionManager.MeshChannelMessage message) {
        if (message == null) {
            return;
        }
        int channelIndex = message.channelIndex;
        if (message.outbound && message.statusText != null
                && !message.statusText.trim().isEmpty()
                && !"queued".equalsIgnoreCase(message.statusText.trim())
                && tryUpgradeQueuedOutboundFromOutboundStatus(message)) {
            persistMeshChannelHistory();
            return;
        }
        if (!message.outbound && tryUpgradeQueuedOutboundMessage(message)) {
            persistMeshChannelHistory();
            return;
        }
        if (!message.outbound && tryUpgradeMostRecentQueuedOutboundAnyChannel(message)) {
            persistMeshChannelHistory();
            return;
        }
        if (channelIndex < 0 || channelIndex > 7) {
            // Some firmware variants omit/shift channel index in channel message pushes.
            // We still attempt queued-upgrade paths above; if none matched, skip storing.
            return;
        }
        LinkedList<MeshBtConnectionManager.MeshChannelMessage> bucket = meshChannelMessages.get(channelIndex);
        if (bucket == null) {
            bucket = new LinkedList<>();
            meshChannelMessages.put(channelIndex, bucket);
        }
        bucket.add(message);
        while (bucket.size() > MAX_MESH_CHANNEL_MESSAGES) {
            bucket.removeFirst();
        }
        persistMeshChannelHistory();
        if (message.outbound && isRepeatStatusAwaitingEvidence(message.statusText)) {
            scheduleMeshQueuedStatusTimeout();
        }
    }

    /**
     * Mesh channel TX has no explicit send-confirm packet for this UI path.
     * Promote the latest matching queued outbound row when the same channel text
     * is observed on RX with path metadata.
     */
    private boolean tryUpgradeQueuedOutboundMessage(MeshBtConnectionManager.MeshChannelMessage inbound) {
        if (inbound == null || inbound.outbound) {
            return false;
        }
        String inboundNorm = normalizeChannelMessageText(inbound.text);
        if (inboundNorm.isEmpty()) {
            return false;
        }
        int channelIndex = inbound.channelIndex;
        if (channelIndex < 0 || channelIndex > 7) {
            channelIndex = findMostRecentQueuedChannelIndex();
            if (channelIndex < 0) {
                return false;
            }
        }
        LinkedList<MeshBtConnectionManager.MeshChannelMessage> bucket = meshChannelMessages.get(channelIndex);
        if (bucket == null || bucket.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        int fallbackQueuedIndex = -1;
        for (int i = bucket.size() - 1; i >= 0; i--) {
            MeshBtConnectionManager.MeshChannelMessage existing = bucket.get(i);
            if (!existing.outbound) {
                continue;
            }
            String status = existing.statusText != null ? existing.statusText.trim().toLowerCase(Locale.US) : "";
            if (!isRepeatStatusAwaitingEvidence(status)) {
                continue;
            }
            long ageMs = (now - existing.receivedAtMs);
            if (ageMs > 20_000L) {
                continue;
            }
            int repeats = Math.max(0, (inbound.pathLen != null ? inbound.pathLen : 0));
            String heardStatus = outboundStatusFromRepeats(repeats);
            String existingNorm = normalizeChannelMessageText(existing.text);
            boolean textMatches = !existingNorm.isEmpty()
                    && !inboundNorm.isEmpty()
                    && (existingNorm.equals(inboundNorm)
                    || inboundNorm.contains(existingNorm)
                    || existingNorm.contains(inboundNorm));
            if (!textMatches) {
                continue;
            }
            if (fallbackQueuedIndex < 0) {
                fallbackQueuedIndex = i;
            }
            if (textMatches) {
                MeshBtConnectionManager.MeshChannelMessage upgraded =
                        new MeshBtConnectionManager.MeshChannelMessage(
                                existing.channelIndex,
                                existing.text,
                                existing.receivedAtMs,
                                true,
                                heardStatus,
                                inbound.snrQuarterDb,
                                inbound.pathLen,
                                inbound.senderTimestampSec);
                bucket.set(i, upgraded);
                Log.d(TAG, "Mesh channel status upgraded by text match ch=" + channelIndex
                        + " status=" + heardStatus);
                return true;
            }
        }
        if (fallbackQueuedIndex >= 0) {
            MeshBtConnectionManager.MeshChannelMessage existing = bucket.get(fallbackQueuedIndex);
            int repeats = Math.max(0, (inbound.pathLen != null ? inbound.pathLen : 0));
            String heardStatus = outboundStatusFromRepeats(repeats);
            MeshBtConnectionManager.MeshChannelMessage upgraded =
                    new MeshBtConnectionManager.MeshChannelMessage(
                            existing.channelIndex,
                            existing.text,
                            existing.receivedAtMs,
                            true,
                            heardStatus,
                            inbound.snrQuarterDb,
                            inbound.pathLen,
                            inbound.senderTimestampSec);
            bucket.set(fallbackQueuedIndex, upgraded);
            Log.d(TAG, "Mesh channel status upgraded by fallback ch=" + channelIndex
                    + " status=" + heardStatus);
            return true;
        }
        return false;
    }

    private int findMostRecentQueuedChannelIndex() {
        long newestMs = -1L;
        int bestChannel = -1;
        for (Map.Entry<Integer, LinkedList<MeshBtConnectionManager.MeshChannelMessage>> e
                : meshChannelMessages.entrySet()) {
            LinkedList<MeshBtConnectionManager.MeshChannelMessage> bucket = e.getValue();
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            for (int i = bucket.size() - 1; i >= 0; i--) {
                MeshBtConnectionManager.MeshChannelMessage m = bucket.get(i);
                if (!m.outbound) {
                    continue;
                }
                String status = m.statusText != null ? m.statusText.trim().toLowerCase(Locale.US) : "";
                if (!isRepeatStatusAwaitingEvidence(status)) {
                    continue;
                }
                if (m.receivedAtMs > newestMs) {
                    newestMs = m.receivedAtMs;
                    bestChannel = e.getKey();
                }
                break;
            }
        }
        return bestChannel;
    }

    private boolean tryUpgradeMostRecentQueuedOutboundAnyChannel(
            MeshBtConnectionManager.MeshChannelMessage inbound) {
        if (inbound == null || inbound.outbound) {
            return false;
        }
        String inboundNorm = normalizeChannelMessageText(inbound.text);
        if (inboundNorm.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        int bestChannel = -1;
        int bestIndex = -1;
        long newestMs = -1L;
        for (Map.Entry<Integer, LinkedList<MeshBtConnectionManager.MeshChannelMessage>> e
                : meshChannelMessages.entrySet()) {
            LinkedList<MeshBtConnectionManager.MeshChannelMessage> bucket = e.getValue();
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            for (int i = bucket.size() - 1; i >= 0; i--) {
                MeshBtConnectionManager.MeshChannelMessage m = bucket.get(i);
                if (!m.outbound) {
                    continue;
                }
                String status = m.statusText != null ? m.statusText.trim().toLowerCase(Locale.US) : "";
                if (!isRepeatStatusAwaitingEvidence(status)) {
                    continue;
                }
                long ageMs = (now - m.receivedAtMs);
                if (ageMs > 20_000L) {
                    continue;
                }
                String queuedNorm = normalizeChannelMessageText(m.text);
                boolean textMatches = !queuedNorm.isEmpty()
                        && (queuedNorm.equals(inboundNorm)
                        || inboundNorm.contains(queuedNorm)
                        || queuedNorm.contains(inboundNorm));
                if (!textMatches) {
                    continue;
                }
                if (m.receivedAtMs > newestMs) {
                    newestMs = m.receivedAtMs;
                    bestChannel = e.getKey();
                    bestIndex = i;
                }
                break;
            }
        }
        if (bestChannel < 0 || bestIndex < 0) {
            return false;
        }
        LinkedList<MeshBtConnectionManager.MeshChannelMessage> bucket = meshChannelMessages.get(bestChannel);
        if (bucket == null || bestIndex >= bucket.size()) {
            return false;
        }
        MeshBtConnectionManager.MeshChannelMessage existing = bucket.get(bestIndex);
        int repeats = Math.max(0, (inbound.pathLen != null ? inbound.pathLen : 0));
        String heardStatus = outboundStatusFromRepeats(repeats);
        MeshBtConnectionManager.MeshChannelMessage upgraded =
                new MeshBtConnectionManager.MeshChannelMessage(
                        existing.channelIndex,
                        existing.text,
                        existing.receivedAtMs,
                        true,
                        heardStatus,
                        inbound.snrQuarterDb,
                        inbound.pathLen,
                        inbound.senderTimestampSec);
        bucket.set(bestIndex, upgraded);
        Log.d(TAG, "Mesh channel queued status upgraded by global fallback channel="
                + bestChannel + " status=" + heardStatus);
        return true;
    }

    private boolean tryUpgradeQueuedOutboundFromOutboundStatus(
            MeshBtConnectionManager.MeshChannelMessage update) {
        if (update == null || !update.outbound) {
            return false;
        }
        int channelIndex = update.channelIndex;
        LinkedList<MeshBtConnectionManager.MeshChannelMessage> bucket = meshChannelMessages.get(channelIndex);
        if (bucket == null || bucket.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (int i = bucket.size() - 1; i >= 0; i--) {
            MeshBtConnectionManager.MeshChannelMessage existing = bucket.get(i);
            if (!existing.outbound) {
                continue;
            }
            String status = existing.statusText != null ? existing.statusText.trim().toLowerCase(Locale.US) : "";
            if (!isRepeatStatusAwaitingEvidence(status)) {
                continue;
            }
            if ((now - existing.receivedAtMs) > 120_000L) {
                continue;
            }
            String existingNorm = normalizeChannelMessageText(existing.text);
            String updateNorm = normalizeChannelMessageText(update.text);
            if (!existingNorm.isEmpty() && !updateNorm.isEmpty() && !existingNorm.equals(updateNorm)) {
                continue;
            }
            MeshBtConnectionManager.MeshChannelMessage upgraded =
                    new MeshBtConnectionManager.MeshChannelMessage(
                            existing.channelIndex,
                            existing.text,
                            existing.receivedAtMs,
                            true,
                            update.statusText,
                            update.snrQuarterDb,
                            update.pathLen,
                            update.senderTimestampSec);
            bucket.set(i, upgraded);
            Log.d(TAG, "Mesh channel queued status upgraded from TX confirm ch=" + channelIndex
                    + " status=" + update.statusText);
            return true;
        }
        return false;
    }

    private static String normalizeChannelMessageText(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim().toLowerCase(Locale.US);
        t = t.replace('\n', ' ').replace('\r', ' ');
        while (t.contains("  ")) {
            t = t.replace("  ", " ");
        }
        return t;
    }

    private static boolean isRepeatStatusAwaitingEvidence(String statusRaw) {
        if (statusRaw == null) {
            return false;
        }
        String status = statusRaw.trim().toLowerCase(Locale.US);
        if (status.equals("queued") || status.equals("sent")) {
            return true;
        }
        if (status.startsWith("heard 0 repeats")) {
            return true;
        }
        return status.startsWith("heard (repeat count pending");
    }

    private static String outboundStatusFromRepeats(int repeats) {
        if (repeats <= 0) {
            return "Sent";
        }
        return "heard " + repeats + " repeats";
    }

    private void scheduleMeshQueuedStatusTimeout() {
        if (getMapView() == null) {
            return;
        }
        getMapView().removeCallbacks(meshQueuedStatusTimeoutRunnable);
        getMapView().postDelayed(meshQueuedStatusTimeoutRunnable, MESH_CHANNEL_QUEUE_TIMEOUT_MS);
    }

    private void applyMeshQueuedStatusTimeouts() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<Integer, LinkedList<MeshBtConnectionManager.MeshChannelMessage>> e
                : meshChannelMessages.entrySet()) {
            LinkedList<MeshBtConnectionManager.MeshChannelMessage> bucket = e.getValue();
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            for (int i = bucket.size() - 1; i >= 0; i--) {
                MeshBtConnectionManager.MeshChannelMessage m = bucket.get(i);
                if (!m.outbound) {
                    continue;
                }
                if (!isRepeatStatusAwaitingEvidence(m.statusText)) {
                    continue;
                }
                if ((now - m.receivedAtMs) < MESH_CHANNEL_QUEUE_TIMEOUT_MS) {
                    continue;
                }
                MeshBtConnectionManager.MeshChannelMessage upgraded =
                        new MeshBtConnectionManager.MeshChannelMessage(
                                m.channelIndex,
                                m.text,
                                m.receivedAtMs,
                                true,
                                "Sent",
                                m.snrQuarterDb,
                                0,
                                m.senderTimestampSec);
                bucket.set(i, upgraded);
                changed = true;
            }
        }
        if (changed) {
            persistMeshChannelHistory();
            if (meshChannelChatActiveIndex >= 0 && meshChannelChatLogView != null) {
                renderMeshChannelChatLog(meshChannelChatActiveIndex);
            }
        }
    }

    private void renderMeshChannelChatLog(int channelIndex) {
        if (meshChannelChatLogView == null) {
            return;
        }
        LinkedList<MeshBtConnectionManager.MeshChannelMessage> bucket = meshChannelMessages.get(channelIndex);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (bucket != null) {
            for (MeshBtConnectionManager.MeshChannelMessage m : bucket) {
                String ts = new SimpleDateFormat("HH:mm:ss", Locale.US)
                        .format(new Date(m.receivedAtMs));
                String sender = resolveMeshChannelSenderName(m);
                String msg = m.text == null ? "" : m.text;
                String prefix = "[" + ts + "] /" + sender + "/ ";
                int start = sb.length();
                sb.append(prefix).append(msg);
                int msgStart = start + prefix.length();
                int msgEnd = msgStart + msg.length();
                if (msgEnd > msgStart) {
                    sb.setSpan(new RelativeSizeSpan(15f / 13f), msgStart, msgEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                String meta = buildMeshChannelMetaLine(m);
                if (!meta.isEmpty()) {
                    sb.append("\n").append(meta);
                }
                sb.append("\n\n");
            }
        }
        if (sb.length() == 0) {
            sb.append("No messages yet for this channel.");
        }
        meshChannelChatLogView.setText(sb);
        // Auto-scroll to the bottom; user can still scroll up for history.
        meshChannelChatLogView.post(() -> {
            if (meshChannelChatLogView == null) return;
            android.text.Layout layout = meshChannelChatLogView.getLayout();
            if (layout == null) return;
            int visible = meshChannelChatLogView.getHeight()
                    - meshChannelChatLogView.getPaddingTop()
                    - meshChannelChatLogView.getPaddingBottom();
            int scrollY = layout.getHeight() - visible;
            meshChannelChatLogView.scrollTo(0, Math.max(0, scrollY));
        });
        if (meshChannelChatTitleView != null) {
            String channelName = meshChannelNames.get(channelIndex);
            if (channelName == null || channelName.trim().isEmpty()) {
                channelName = "Channel";
            }
            meshChannelChatTitleView.setText("Channel #" + channelIndex + " - " + channelName);
        }
        if (meshChannelTitleView != null) {
            String channelName = meshChannelNames.get(channelIndex);
            if (channelName == null || channelName.trim().isEmpty()) channelName = "Channel";
            meshChannelTitleView.setText("Channel #" + channelIndex + " — " + channelName);
        }
    }

    private void updateMeshChannelButtonLabel() {
        // Rebuild the channel strip whenever channel info changes.
        buildMeshChannelButtonStrip();
        if (stripMeshChannels != null && stripMeshChannels.getChildCount() > 0) {
            stripMeshChannels.setVisibility(android.view.View.VISIBLE);
            // Auto-select the first channel if none is active yet.
            if (meshChannelChatActiveIndex < 0 && activeMeshContactPubKey == null) {
                for (int i = 0; i < 8; i++) {
                    String n = meshChannelNames.get(i);
                    if (n != null && !n.trim().isEmpty()
                            && !"ATAK_DATA".equalsIgnoreCase(n.trim())) {
                        openMeshChannelChatDialog(i);
                        buildMeshChannelButtonStrip();
                        break;
                    }
                }
            }
        }
        updateExpandMeshChannelChatButtonState();
    }

    private String buildMeshChannelMetaLine(MeshBtConnectionManager.MeshChannelMessage m) {
        List<String> parts = new ArrayList<>();
        if (m.snrQuarterDb != null) {
            parts.add(String.format(Locale.US, "SNR %.2f dB", m.snrQuarterDb / 4.0f));
        }
        String status = deriveMeshChannelMetaStatus(m);
        if (!status.isEmpty()) {
            parts.add(status);
        }
        if (parts.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) out.append(" / ");
            out.append(parts.get(i));
        }
        return out.toString();
    }

    private String resolveMeshChannelSenderName(MeshBtConnectionManager.MeshChannelMessage m) {
        if (m.outbound) {
            // Use the node's advertised name (not the ATAK callsign) so the sender window
            // matches what the receiver sees.
            if (meshNodeSettingsState != null
                    && meshNodeSettingsState.nodeName != null
                    && !meshNodeSettingsState.nodeName.trim().isEmpty()) {
                return meshNodeSettingsState.nodeName.trim();
            }
            MeshBtConnectionManager.MeshNodeSettings latest =
                    meshBtManager != null ? meshBtManager.getLatestNodeSettings() : null;
            if (latest != null && latest.nodeName != null && !latest.nodeName.trim().isEmpty()) {
                return latest.nodeName.trim();
            }
            return "Me";
        }
        return "Node";
    }

    private String deriveMeshChannelMetaStatus(MeshBtConnectionManager.MeshChannelMessage m) {
        if (m.statusText != null) {
            String status = m.statusText.trim();
            if (!status.isEmpty()) {
                return status;
            }
        }
        if (m.pathLen != null && m.pathLen > 0) {
            return "heard " + m.pathLen + " repeats";
        }
        if (m.outbound) {
            return "Sent";
        }
        return "";
    }

    private void loadMeshChannelHistoryIfNeeded() {
        if (meshChannelHistoryLoaded) {
            return;
        }
        meshChannelHistoryLoaded = true;
        try {
            SharedPreferences prefs = PreferenceManager
                    .getDefaultSharedPreferences(getMapView().getContext());
            String raw = prefs.getString(PREF_MESH_CHANNEL_HISTORY, "");
            if (raw == null || raw.isEmpty()) {
                return;
            }
            String[] lines = raw.split("\n");
            for (String line : lines) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\|", 10);
                if (parts.length < 10) {
                    continue;
                }
                int channel = parseIntSafe(parts[0], -1);
                long recvMs = parseLongSafe(parts[1], 0L);
                boolean outbound = "1".equals(parts[2]);
                Integer snrQ = parseNullableInt(parts[3]);
                Integer pathLen = parseNullableInt(parts[4]);
                Integer senderTs = parseNullableInt(parts[5]);
                String status = decodeB64(parts[6]);
                String text = decodeB64(parts[7]);
                String channelName = decodeB64(parts[8]);
                if (channel < 0 || channel > 7 || text.isEmpty()) {
                    continue;
                }
                if (!channelName.isEmpty()) {
                    meshChannelNames.put(channel, channelName);
                }
                appendMeshChannelMessageNoPersist(new MeshBtConnectionManager.MeshChannelMessage(
                        channel,
                        text,
                        recvMs > 0 ? recvMs : System.currentTimeMillis(),
                        outbound,
                        status,
                        snrQ,
                        pathLen,
                        senderTs));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load Mesh channel history", e);
        }
    }

    private void appendMeshChannelMessageNoPersist(MeshBtConnectionManager.MeshChannelMessage message) {
        if (message == null) {
            return;
        }
        int channelIndex = message.channelIndex;
        if (channelIndex < 0 || channelIndex > 7) {
            return;
        }
        LinkedList<MeshBtConnectionManager.MeshChannelMessage> bucket = meshChannelMessages.get(channelIndex);
        if (bucket == null) {
            bucket = new LinkedList<>();
            meshChannelMessages.put(channelIndex, bucket);
        }
        bucket.add(message);
        while (bucket.size() > MAX_MESH_CHANNEL_MESSAGES) {
            bucket.removeFirst();
        }
    }

    private void persistMeshChannelHistory() {
        try {
            StringBuilder sb = new StringBuilder();
            for (int channel = 0; channel < 8; channel++) {
                LinkedList<MeshBtConnectionManager.MeshChannelMessage> bucket =
                        meshChannelMessages.get(channel);
                if (bucket == null) {
                    continue;
                }
                String channelName = meshChannelNames.get(channel);
                for (MeshBtConnectionManager.MeshChannelMessage m : bucket) {
                    sb.append(channel).append("|")
                            .append(m.receivedAtMs).append("|")
                            .append(m.outbound ? "1" : "0").append("|")
                            .append(nullableIntToken(m.snrQuarterDb)).append("|")
                            .append(nullableIntToken(m.pathLen)).append("|")
                            .append(nullableIntToken(m.senderTimestampSec)).append("|")
                            .append(encodeB64(m.statusText)).append("|")
                            .append(encodeB64(m.text)).append("|")
                            .append(encodeB64(channelName)).append("|")
                            .append("v1")
                            .append("\n");
                }
            }
            SharedPreferences prefs = PreferenceManager
                    .getDefaultSharedPreferences(getMapView().getContext());
            prefs.edit().putString(PREF_MESH_CHANNEL_HISTORY, sb.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist Mesh channel history", e);
        }
    }

    private static String encodeB64(String in) {
        if (in == null || in.isEmpty()) {
            return "";
        }
        return Base64.encodeToString(in.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Base64.NO_WRAP);
    }

    private static String decodeB64(String in) {
        if (in == null || in.isEmpty()) {
            return "";
        }
        try {
            byte[] out = Base64.decode(in, Base64.NO_WRAP);
            return new String(out, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int parseIntSafe(String s, int d) {
        try {
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return d;
        }
    }

    private static long parseLongSafe(String s, long d) {
        try {
            return Long.parseLong(s);
        } catch (Exception ignored) {
            return d;
        }
    }

    private static Integer parseNullableInt(String s) {
        if (s == null || s.isEmpty() || "-".equals(s)) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String nullableIntToken(Integer v) {
        return v == null ? "-" : Integer.toString(v);
    }

    private int getId(String name) {
        return pluginContext.getResources().getIdentifier(
                name, "id", pluginContext.getPackageName());
    }

    // --- Connection Listener callbacks ---

    @Override
    public void onConnected(BluetoothDevice device) {
        radioGpsAugmentController.onRadioConnected();
        String displayName = "Radio";
        if (device != null) {
            displayName = resolveDeviceDisplayName(getMapView().getContext(), device);
        }
        final String finalDisplay = displayName;
        getMapView().post(() -> {
            applyTransmitTogglesForConnectivity(true, startupTransmitWindowOpen);
            updateConnectionUI(true, finalDisplay);
            applyActiveTransmitTransport();
            if (startupTransmitWindowOpen) {
                scheduleStartupTransmitAfterMeshBoot(0L);
            }
            handlePreferredTransmitTransportConnected();
            ensureExclusiveTransmitState(false);
            appendLog("UV-PRO connected to " + finalDisplay);
            stopScanConnectButtonPulse(true);
            dismissRadioPickerDialog();
            refreshChannelGroupFromRadioAsync(true);
            updateScanButtonText();
            refreshTxPowerFromRadioAsync();
            // Follow-up read: some radios return channel/settings a moment later.
            getMapView().postDelayed(this::refreshChannelGridAsync, 900L);
        });
        refreshRadioIdentAfterConnect(device);
    }

    /** Reads BSS Ident ID after connect; does not probe during scan. */
    private void refreshRadioIdentAfterConnect(BluetoothDevice device) {
        if (device == null || radioControlManager == null) {
            return;
        }
        final String mac = device.getAddress();
        getMapView().postDelayed(() -> new Thread(() -> {
            if (!btManager.isConnected()) {
                return;
            }
            String ident = radioControlManager.readUserRadioId();
            if (ident == null || ident.isEmpty()) {
                return;
            }
            UvProRadioIdentCache.put(mac, ident);
            final String label = UvProBtDeviceMatcher.formatPickerLabel(device);
            getMapView().post(() -> {
                if (btManager.isConnected()) {
                    updateConnectionUI(true, label);
                    appendLog("Radio ident: " + ident);
                }
            });
        }, "uvpro-read-ident").start(), 900L);
    }

    @Override
    public void onDisconnected(String reason) {
        radioGpsAugmentController.onRadioDisconnected();
        getMapView().post(() -> {
            clearDigitalOnlyStateUiOnly();
            updateConnectionUI(false, null);
            applyActiveTransmitTransport();
            applyTransmitTogglesForConnectivity(true, false);
            handlePreferredTransmitTransportDisconnected(PreferredTransmitTransport.UVPRO);
            appendLog("UV-PRO disconnected: " + reason);
        });
    }

    @Override
    public void onError(String error) {
        getMapView().post(() -> appendLog("UV-PRO error: " + error));
    }

    @Override
    public void onDeviceFound(BluetoothDevice device) {
        if (isLikelyMeshNamedDevice(device)) {
            return;
        }
        String addr = device != null ? device.getAddress() : null;
        if (addr != null) {
            for (BluetoothDevice existing : foundDevices) {
                if (existing != null && addr.equalsIgnoreCase(existing.getAddress())) {
                    return;
                }
            }
        }
        foundDevices.add(device);
        final String display = UvProBtDeviceMatcher.formatPickerLabel(device);
        getMapView().post(() -> appendLog("UV-PRO scan found: " + display));
    }

    @Override
    public void onScanComplete() {
        getMapView().post(() -> {
            stopScanConnectButtonPulse(true);
            pruneUnbondedDiscoveryPhantoms();
            showDevicePicker();
        });
    }

    @Override
    public void onBootAutoConnectWindowStarted() {
        getMapView().post(() -> {
            if (!btManager.isConnected()) {
                requestScanConnectButtonPulse();
                updateScanButtonText();
                appendLog("Looking for last connected radio...");
            }
        });
    }

    @Override
    public void onBootAutoConnectWindowEnded(boolean connected) {
        getMapView().post(() -> {
            if (connected) {
                stopScanConnectButtonPulse(true);
                updateScanButtonText();
                return;
            }
            if (btManager.isBootAutoConnectResolving()) {
                updateScanButtonText();
                requestScanConnectButtonPulse();
                return;
            }
            stopScanConnectButtonPulse(true);
            updateScanButtonText();
            appendLog("Last radio not found — tap Scan and Connect.");
        });
    }

    @Override
    public void onBootAutoConnectAttemptFinished(boolean connected) {
        getMapView().post(() -> {
            stopScanConnectButtonPulse(true);
            updateScanButtonText();
            if (!connected) {
                appendLog("Last radio not found — tap Scan and Connect.");
            }
        });
    }

    /** Drops unbonded rows that never had a credible live discovery hit (stale BT cache). */
    private void pruneUnbondedDiscoveryPhantoms() {
        java.util.Iterator<BluetoothDevice> it = foundDevices.iterator();
        while (it.hasNext()) {
            BluetoothDevice device = it.next();
            if (device == null) {
                it.remove();
                continue;
            }
            int bondState = BluetoothDevice.BOND_NONE;
            try {
                bondState = device.getBondState();
            } catch (Exception ignored) {
            }
            if (bondState == BluetoothDevice.BOND_BONDED
                    || btManager.isRememberedScanRow(device.getAddress())) {
                continue;
            }
            if (!btManager.wasSeenInLiveDiscovery(device.getAddress())) {
                Log.i(TAG, "Pruning phantom discovery: "
                        + UvProBtDeviceMatcher.formatPickerLabel(device));
                it.remove();
            }
        }
    }

    // --- Contact Listener callbacks ---

    @Override
    public void onContactUpdated(RadioContact contact) {
        getMapView().post(() -> {
            updateContactCount();
            appendLog("Contact: " + contact.getCallsign()
                    + " (" + String.format(Locale.US, "%.4f, %.4f",
                    contact.getLatitude(), contact.getLongitude()) + ")");
        });
    }

    @Override
    public void onContactRemoved(RadioContact contact) {
        getMapView().post(() -> {
            updateContactCount();
            appendLog("Contact lost: " + contact.getCallsign());
        });
    }

    @Override
    public void onContactCountChanged(int count) {
        getMapView().post(this::updateContactCount);
    }

    // --- PacketCountListener callback ---

    @Override
    public void onPacketReceived() {
        rxCount++;
        getMapView().post(this::updatePacketCount);
    }

    @Override
    public void onPacketTransmitted() {
        txCount++;
        getMapView().post(this::updatePacketCount);
    }

    // --- UI update methods ---

    private void updateConnectionUI(boolean connected, String device) {
        if (statusDot != null) {
            statusDot.setBackgroundColor(connected ? 0xFF4CAF50 : 0xFFFF0000);
        }
        if (statusText != null) {
            statusText.setText(connected ? "Connected" : "Disconnected");
        }
        if (connected && device != null) {
            if (deviceName != null) {
                deviceName.setText(device);
            }
            if (deviceRow != null) {
                deviceRow.setVisibility(View.VISIBLE);
            } else if (deviceName != null) {
                deviceName.setVisibility(View.VISIBLE);
            }
            if (radioControlManager != null) {
                int cached = radioControlManager.getLatestBatteryPercent();
                if (cached >= 0) {
                    uvproBatteryPercent = cached;
                }
            }
            updateUvproBatteryUi(uvproBatteryPercent);
        } else {
            if (deviceRow != null) {
                deviceRow.setVisibility(View.GONE);
            } else if (deviceName != null) {
                deviceName.setVisibility(View.GONE);
            }
            uvproBatteryPercent = -1;
            updateUvproBatteryUi(-1);
        }
        if (connected) {
            refreshUvproBatteryAsync();
            scheduleUvproBatteryPoll();
            MapView mv = getMapView();
            if (mv != null) {
                mv.postDelayed(this::refreshUvproBatteryAsync, 2000L);
                mv.postDelayed(this::refreshUvproBatteryAsync, 5000L);
            }
        } else {
            stopUvproBatteryPoll();
        }
        if (btnScan != null) btnScan.setEnabled(!connected);
        if (btnDisconnect != null) btnDisconnect.setEnabled(connected);
        if (btnUpdateGpsFromRadio != null) btnUpdateGpsFromRadio.setEnabled(connected);
        if (switchAugmentGpsFromRadio != null) switchAugmentGpsFromRadio.setEnabled(connected);
        if (btnInitialChannelGroupSetup != null) btnInitialChannelGroupSetup.setEnabled(connected);
        if (btnChannelGroup != null) btnChannelGroup.setEnabled(true);
        if (btnImportChannels != null) btnImportChannels.setEnabled(true);
        if (btnExportChannels != null) btnExportChannels.setEnabled(true);
        updateScanButtonText();
        if (!connected) {
            renderChannelGrid(null);
            updateReceiveRssiUi(-1);
            clearDigitalOnlyStateUiOnly();
        }
        updateRadioSilenceButtonUi();
        updateAprsSectionUi();
        updateDigitalOnlyButtonUi();
        updateTxPowerButtonUi();
        if (!connected) {
            currentTxPowerLevel = UVProRadioControlManager.TX_POWER_LOW;
            currentChannelGroup = 0;
            availableChannelGroups = UVProRadioControlManager.DEFAULT_GROUP_COUNT;
        }
        updateChannelGroupButtonUi();
    }

    private void updateMeshConnectionUI(boolean connected, String device) {
        if (meshStatusDot != null) {
            meshStatusDot.setBackgroundColor(connected ? 0xFF4CAF50 : 0xFFFF0000);
        }
        if (meshStatusText != null) {
            meshStatusText.setText(connected ? "Connected" : "Disconnected");
        }
        if (connected && device != null) {
            if (meshDeviceName != null) {
                meshDeviceName.setText(device);
            }
            if (meshDeviceRow != null) {
                meshDeviceRow.setVisibility(View.VISIBLE);
            } else if (meshDeviceName != null) {
                meshDeviceName.setVisibility(View.VISIBLE);
            }
            if (meshBtManager != null) {
                int cached = meshBtManager.getLatestBatteryPercent();
                if (cached >= 0) {
                    meshBatteryPercent = cached;
                }
            }
            updateMeshBatteryUi(meshBatteryPercent);
        } else {
            if (meshDeviceRow != null) {
                meshDeviceRow.setVisibility(View.GONE);
            } else if (meshDeviceName != null) {
                meshDeviceName.setVisibility(View.GONE);
            }
            meshBatteryPercent = -1;
            updateMeshBatteryUi(-1);
        }
        if (btnMeshScan != null) {
            btnMeshScan.setEnabled(!connected);
        }
        if (btnMeshDisconnect != null) {
            btnMeshDisconnect.setEnabled(connected);
        }
        updateMeshScanButtonText();
        updateMeshGpsControlsUi();
        if (connected) {
            scheduleMeshBatteryPoll();
            if (meshBtManager != null) {
                meshBtManager.requestBattery();
            }
            MapView mv = getMapView();
            if (mv != null) {
                mv.postDelayed(() -> {
                    if (meshBtManager != null && meshBtManager.isConnected()) {
                        meshBtManager.requestBattery();
                    }
                }, 2000L);
            }
        } else {
            stopMeshBatteryPoll();
        }
        scheduleMeshGpsAugmentTick();
        scheduleMeshCallsignPositionSync();
        MeshStatusOverlay.setConnected(connected);
    }

    private void updateMeshBatteryUi(int batteryPercent) {
        boolean show = meshConnected && batteryPercent >= 0;
        if (meshBatteryIcon != null) {
            meshBatteryIcon.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (meshBatteryPct != null) {
            if (show) {
                meshBatteryPct.setText(batteryPercent + "%");
                meshBatteryPct.setVisibility(View.VISIBLE);
            } else {
                meshBatteryPct.setText("");
                meshBatteryPct.setVisibility(View.GONE);
            }
        }
    }

    private void scheduleMeshBatteryPoll() {
        MapView mv = getMapView();
        if (mv == null || !meshConnected) {
            return;
        }
        mv.removeCallbacks(meshBatteryPollRunnable);
        mv.postDelayed(meshBatteryPollRunnable, MESH_BATTERY_POLL_INTERVAL_MS);
    }

    private void stopMeshBatteryPoll() {
        MapView mv = getMapView();
        if (mv != null) {
            mv.removeCallbacks(meshBatteryPollRunnable);
        }
    }

    private void updateMeshGpsControlsUi() {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        boolean advertPositionEnabled = meshSendPositionWithAdvertRequested
                || Boolean.TRUE.equals(meshSendPositionWithAdvertState);
        boolean customNodePositionEnabled = isMeshUseCustomNodePositionPreferenceEnabled(ctx);
        if (switchMeshEnableGpsMeshcore != null) {
            suppressMeshGpsSwitchCallbacks = true;
            try {
                boolean gpsCapabilityKnown = meshGpsEnabledState != null;
                boolean meshGpsToggleEnabled = meshConnected
                        && gpsCapabilityKnown
                        && advertPositionEnabled;
                switchMeshEnableGpsMeshcore.setEnabled(meshGpsToggleEnabled);
                switchMeshEnableGpsMeshcore.setAlpha(meshGpsToggleEnabled ? 1f : 0.45f);
                View meshGpsRow = (View) switchMeshEnableGpsMeshcore.getParent();
                if (meshGpsRow != null) {
                    meshGpsRow.setAlpha(meshGpsToggleEnabled ? 1f : 0.55f);
                }
                switchMeshEnableGpsMeshcore.setChecked(
                        gpsCapabilityKnown
                                && (meshGpsEnableRequested
                                || Boolean.TRUE.equals(meshGpsEnabledState)));
            } finally {
                suppressMeshGpsSwitchCallbacks = false;
            }
        }
        if (switchMeshSendPositionWithAdvert != null) {
            suppressMeshSendPositionWithAdvertSwitchCallbacks = true;
            try {
                switchMeshSendPositionWithAdvert.setEnabled(meshConnected);
                switchMeshSendPositionWithAdvert.setChecked(
                        meshSendPositionWithAdvertRequested
                                || Boolean.TRUE.equals(meshSendPositionWithAdvertState));
            } finally {
                suppressMeshSendPositionWithAdvertSwitchCallbacks = false;
            }
        }
        if (switchMeshUseCallsignLocation != null) {
            boolean callsignToggleEnabled = meshConnected && advertPositionEnabled;
            switchMeshUseCallsignLocation.setEnabled(callsignToggleEnabled);
            switchMeshUseCallsignLocation.setAlpha(callsignToggleEnabled ? 1f : 0.45f);
            View callsignRow = (View) switchMeshUseCallsignLocation.getParent();
            if (callsignRow != null) {
                callsignRow.setAlpha(callsignToggleEnabled ? 1f : 0.55f);
            }
        }
        if (switchMeshUseCustomNodePosition != null) {
            boolean customNodeToggleEnabled = meshConnected && advertPositionEnabled;
            switchMeshUseCustomNodePosition.setEnabled(customNodeToggleEnabled);
            switchMeshUseCustomNodePosition.setAlpha(customNodeToggleEnabled ? 1f : 0.45f);
            switchMeshUseCustomNodePosition.setChecked(customNodePositionEnabled);
            View customNodeRow = (View) switchMeshUseCustomNodePosition.getParent();
            if (customNodeRow != null) {
                customNodeRow.setAlpha(customNodeToggleEnabled ? 1f : 0.55f);
            }
        }
        boolean customNodePositionActionsEnabled = meshConnected
                && advertPositionEnabled
                && customNodePositionEnabled;
        if (btnMeshcoreSetNodePositionMap != null) {
            btnMeshcoreSetNodePositionMap.setEnabled(customNodePositionActionsEnabled);
            btnMeshcoreSetNodePositionMap.setAlpha(customNodePositionActionsEnabled ? 1f : 0.45f);
        }
        boolean meshGpsOn = meshGpsEnableRequested || Boolean.TRUE.equals(meshGpsEnabledState);
        boolean gpsDrivenActionsEnabled = meshConnected && meshGpsOn;
        if (switchAugmentGpsFromMeshcore != null) {
            switchAugmentGpsFromMeshcore.setEnabled(gpsDrivenActionsEnabled);
            switchAugmentGpsFromMeshcore.setAlpha(gpsDrivenActionsEnabled ? 1f : 0.45f);
        }
        if (rowAugmentGpsFromMeshcore != null) {
            rowAugmentGpsFromMeshcore.setVisibility(View.VISIBLE);
            rowAugmentGpsFromMeshcore.setAlpha(gpsDrivenActionsEnabled ? 1f : 0.55f);
        }
        if (btnUpdateGpsFromMeshcore != null) {
            btnUpdateGpsFromMeshcore.setVisibility(View.VISIBLE);
            btnUpdateGpsFromMeshcore.setEnabled(gpsDrivenActionsEnabled);
            btnUpdateGpsFromMeshcore.setAlpha(gpsDrivenActionsEnabled ? 1f : 0.45f);
        }
    }

    private void onMeshGpsToggleChanged(boolean isChecked) {
        if (meshBtManager == null || !meshBtManager.isConnected()) {
            return;
        }
        if (meshGpsEnabledState == null) {
            updateMeshGpsControlsUi();
            return;
        }
        meshGpsEnableRequested = isChecked;
        setMeshUseGpsForPositionPreference(isChecked);
        if (!isChecked) {
            meshGpsEnabledState = Boolean.FALSE;
        }
        updateMeshGpsControlsUi();
        appendLog("Setting Use Meschore GPS for position " + (isChecked ? "ON..." : "OFF..."));
        if (isChecked) {
            removeMeshNodeMapPositionMarker(true);
        }
        scheduleMeshCallsignPositionSync();
        meshBtManager.setMeshGpsEnabled(isChecked);
        meshBtManager.queryMeshGpsEnabled();
        if (!isChecked) {
            pushPhoneLocationToMeshNodeIfNeeded(false);
        }
    }

    private void forceDisableMeshGpsPositionSource() {
        meshGpsEnableRequested = false;
        meshGpsEnabledState = Boolean.FALSE;
        setMeshUseGpsForPositionPreference(false);
        if (switchMeshEnableGpsMeshcore != null) {
            suppressMeshGpsSwitchCallbacks = true;
            try {
                switchMeshEnableGpsMeshcore.setChecked(false);
            } finally {
                suppressMeshGpsSwitchCallbacks = false;
            }
        }
        if (meshBtManager != null && meshBtManager.isConnected()) {
            meshBtManager.setMeshGpsEnabled(false);
            meshBtManager.queryMeshGpsEnabled();
        }
    }

    private void syncTransmitSwitchesUi() {
        suppressTransportSwitchCallbacks = true;
        try {
            if (switchMeshTransmit != null) {
                switchMeshTransmit.setChecked(meshTransmitEnabled);
            }
            if (switchUvProTransmit != null) {
                switchUvProTransmit.setChecked(uvproTransmitEnabled);
            }
            if (switchWifiTransmit != null) {
                switchWifiTransmit.setChecked(wifiTransmitEnabled);
            }
        } finally {
            suppressTransportSwitchCallbacks = false;
        }
        applyActiveTransmitTransport();
    }

    /**
     * After mesh boot finishes: immediately if mesh connected, else 10s after mesh gives up.
     * Single transport → switch toggle; both connected → keep restored preference.
     */
    private void scheduleStartupTransmitAfterMeshBoot(long delayMs) {
        if (!startupTransmitWindowOpen) {
            return;
        }
        MapView mv = getMapView();
        if (mv == null) {
            return;
        }
        if (startupTransmitApplyScheduled && delayMs > 0L) {
            return;
        }
        cancelStartupTransmitApplyTimer();
        startupTransmitApplyScheduled = true;
        final long effectiveDelay = Math.max(0L, delayMs);
        startupTransmitApplyRunnable = () -> {
            startupTransmitApplyRunnable = null;
            startupTransmitApplyScheduled = false;
            applyStartupTransmitSelectionOnce();
        };
        mv.postDelayed(startupTransmitApplyRunnable, effectiveDelay);
    }

    private void cancelStartupTransmitApplyTimer() {
        MapView mv = getMapView();
        if (mv != null && startupTransmitApplyRunnable != null) {
            mv.removeCallbacks(startupTransmitApplyRunnable);
        }
        startupTransmitApplyRunnable = null;
        startupTransmitApplyScheduled = false;
    }

    private void applyStartupTransmitSelectionOnce() {
        if (!startupTransmitWindowOpen) {
            return;
        }
        applyTransmitTogglesForConnectivity(true, true);
        startupTransmitWindowOpen = false;
        cancelStartupTransmitApplyTimer();
    }

    /**
     * Sets transmit toggles from connectivity: both up → last-session preference; single up → that
     * transport; none up → UV-PRO default.
     */
    private void applyTransmitTogglesForConnectivity(boolean logChange, boolean startupContext) {
        ensureTransmitPreferencesLoaded();
        boolean uvUp = isUvproConnected();
        boolean meshUp = meshConnected;

        if (uvUp && meshUp) {
            applyTogglesFromPreferredTransport();
            transmitFailoverActive = false;
            cancelTransmitFailoverTimer();
            syncTransmitSwitchesUi();
            persistTransmitTogglePreferences();
            applyActiveTransmitTransport();
            if (logChange) {
                appendLog(startupContext
                        ? "Startup transmit: both radios connected — using "
                                + formatPreferredTransmitLabel()
                        : "Transmit: both radios connected — using "
                                + formatPreferredTransmitLabel());
            }
            return;
        }
        if (meshUp && !uvUp) {
            applyTransmitTogglesExclusive(true, false, false, false);
            transmitFailoverActive = false;
            cancelTransmitFailoverTimer();
            applyActiveTransmitTransport();
            if (logChange) {
                appendLog(startupContext
                        ? "Startup transmit: MeshCore only — using MeshCore transmit"
                        : "Transmit: MeshCore only — using MeshCore transmit");
            }
            return;
        }
        if (uvUp && !meshUp) {
            applyTransmitTogglesExclusive(false, true, false, false);
            transmitFailoverActive = false;
            cancelTransmitFailoverTimer();
            applyActiveTransmitTransport();
            if (logChange) {
                appendLog(startupContext
                        ? "Startup transmit: UV-PRO only — using UV-PRO transmit"
                        : "Transmit: UV-PRO only — using UV-PRO transmit");
            }
            return;
        }
        applyTransmitTogglesExclusive(false, true, false, false);
        transmitFailoverActive = false;
        cancelTransmitFailoverTimer();
        applyActiveTransmitTransport();
        if (logChange) {
            appendLog(startupContext
                    ? "Startup transmit: no radio connected — default UV-PRO transmit"
                    : "Transmit: no radio connected — default UV-PRO transmit");
        }
    }

    private void ensureTransmitPreferencesLoaded() {
        MapView mv = getMapView();
        if (mv == null) {
            return;
        }
        Context ctx = mv.getContext();
        if (ctx == null) {
            return;
        }
        if (preferredTransmitTransport == PreferredTransmitTransport.NONE) {
            resolvePreferredTransmitTransportFromPreferences(ctx);
            applyTogglesFromPreferredTransport();
        }
    }

    private String formatPreferredTransmitLabel() {
        if (preferredTransmitTransport == PreferredTransmitTransport.MESH) {
            return "MeshCore";
        }
        if (preferredTransmitTransport == PreferredTransmitTransport.UVPRO) {
            return "UV-PRO";
        }
        return "none";
    }

    /** MeshCore ON and UV-PRO OFF, or the reverse — never both on or both off. */
    private void applyTogglesFromPreferredTransport() {
        if (preferredTransmitTransport == PreferredTransmitTransport.UVPRO) {
            meshTransmitEnabled = false;
            uvproTransmitEnabled = true;
        } else {
            if (preferredTransmitTransport != PreferredTransmitTransport.MESH) {
                preferredTransmitTransport = PreferredTransmitTransport.MESH;
            }
            meshTransmitEnabled = true;
            uvproTransmitEnabled = false;
        }
    }

    private void resolvePreferredTransmitTransportFromPreferences(Context ctx) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        if (prefs.contains(PREF_TRANSMIT_PREFERRED_TRANSPORT)) {
            String value = prefs.getString(PREF_TRANSMIT_PREFERRED_TRANSPORT, "uvpro");
            if ("uvpro".equals(value)) {
                preferredTransmitTransport = PreferredTransmitTransport.UVPRO;
            } else if ("mesh".equals(value)) {
                preferredTransmitTransport = PreferredTransmitTransport.MESH;
            } else {
                migratePreferredTransmitTransportFromLegacyBools(prefs);
            }
            return;
        }
        migratePreferredTransmitTransportFromLegacyBools(prefs);
        persistPreferredTransmitTransport(ctx);
    }

    private void migratePreferredTransmitTransportFromLegacyBools(SharedPreferences prefs) {
        boolean mesh = prefs.getBoolean(PREF_ATAK_MESHCORE_TRANSMIT, false);
        boolean uv = prefs.getBoolean(PREF_ATAK_UVPRO_TRANSMIT, false);
        if (mesh && !uv) {
            preferredTransmitTransport = PreferredTransmitTransport.MESH;
        } else {
            preferredTransmitTransport = PreferredTransmitTransport.UVPRO;
        }
    }

    private boolean isTransmitToggleMismatchWithPreferred() {
        if (preferredTransmitTransport == PreferredTransmitTransport.MESH) {
            return !meshTransmitEnabled || uvproTransmitEnabled;
        }
        if (preferredTransmitTransport == PreferredTransmitTransport.UVPRO) {
            return !uvproTransmitEnabled || meshTransmitEnabled;
        }
        return false;
    }

    private boolean isPreferredTransmitTransportConnected() {
        if (preferredTransmitTransport == PreferredTransmitTransport.MESH) {
            return meshConnected;
        }
        if (preferredTransmitTransport == PreferredTransmitTransport.UVPRO) {
            return isUvproConnected();
        }
        return false;
    }

    private boolean canRestorePreferredTransmitNow() {
        if (preferredTransmitTransport == PreferredTransmitTransport.NONE) {
            return false;
        }
        boolean bothUp = meshConnected && isUvproConnected();
        boolean preferredMesh = preferredTransmitTransport == PreferredTransmitTransport.MESH;
        boolean preferredUvpro = preferredTransmitTransport == PreferredTransmitTransport.UVPRO;
        return bothUp
                || (preferredMesh && meshConnected)
                || (preferredUvpro && isUvproConnected());
    }

    private void restorePreferredTransmitTransportFromPreferences(Context ctx) {
        resolvePreferredTransmitTransportFromPreferences(ctx);
    }

    private void persistPreferredTransmitTransport(Context ctx) {
        if (ctx == null) {
            return;
        }
        String value = preferredTransmitTransport == PreferredTransmitTransport.UVPRO
                ? "uvpro" : "mesh";
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit().putString(PREF_TRANSMIT_PREFERRED_TRANSPORT, value).apply();
    }

    private void persistPreferredTransmitTransport() {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        persistPreferredTransmitTransport(ctx);
    }

    private void syncPreferredTransmitTransportFromToggles() {
        if (meshTransmitEnabled && !uvproTransmitEnabled) {
            preferredTransmitTransport = PreferredTransmitTransport.MESH;
        } else if (uvproTransmitEnabled && !meshTransmitEnabled) {
            preferredTransmitTransport = PreferredTransmitTransport.UVPRO;
        } else {
            applyTogglesFromPreferredTransport();
        }
    }

    private boolean isUvproConnected() {
        return btManager != null && btManager.isConnected();
    }

    private void cancelTransmitFailoverTimer() {
        MapView mv = getMapView();
        if (mv != null && transmitFailoverRunnable != null) {
            mv.removeCallbacks(transmitFailoverRunnable);
            transmitFailoverRunnable = null;
        }
    }

    private void handlePreferredTransmitTransportDisconnected(PreferredTransmitTransport transport) {
        if (preferredTransmitTransport != transport) {
            return;
        }
        if (meshConnected && !isUvproConnected() || !meshConnected && isUvproConnected()) {
            return;
        }
        if (transmitFailoverActive) {
            return;
        }
        scheduleTransmitFailoverIfNeeded();
    }

    private void handlePreferredTransmitTransportConnected() {
        if (!meshConnected || !isUvproConnected()) {
            return;
        }
        restorePreferredTransmitToggleIfReady(
                transmitFailoverActive ? "Transmit failover" : "Transmit preference");
        if (!transmitFailoverActive
                && preferredTransmitTransport == PreferredTransmitTransport.MESH
                && meshConnected) {
            cancelTransmitFailoverTimer();
        } else if (!transmitFailoverActive
                && preferredTransmitTransport == PreferredTransmitTransport.UVPRO
                && isUvproConnected()) {
            cancelTransmitFailoverTimer();
        }
    }

    private void restorePreferredTransmitToggleIfReady(String logPrefix) {
        if (!isTransmitToggleMismatchWithPreferred()) {
            transmitFailoverActive = false;
            cancelTransmitFailoverTimer();
            return;
        }
        if (!canRestorePreferredTransmitNow()) {
            transmitFailoverActive = true;
            Log.d(TAG, "Transmit restore deferred: " + logPrefix
                    + " preferred=" + formatPreferredTransmitLabel()
                    + " meshToggle=" + meshTransmitEnabled
                    + " uvToggle=" + uvproTransmitEnabled
                    + " meshConnected=" + meshConnected
                    + " uvConnected=" + isUvproConnected());
            return;
        }
        transmitFailoverActive = false;
        cancelTransmitFailoverTimer();
        boolean preferredMesh = preferredTransmitTransport == PreferredTransmitTransport.MESH;
        if (preferredMesh) {
            applyTransmitTogglesExclusive(true, false, true, false);
            appendPluginLog(logPrefix + ": restored MeshCore transmit (MeshCore connected)");
        } else {
            applyTransmitTogglesExclusive(false, true, true, false);
            appendPluginLog(logPrefix + ": restored UV-PRO transmit (UV-PRO connected)");
        }
    }

    /** Called when mesh or UV-PRO connectivity changes (including from MapComponent). */
    public void notifyTransmitTransportConnectivityChanged() {
        MapView mv = getMapView();
        if (mv == null) {
            return;
        }
        mv.post(() -> {
            applyTransmitTogglesForConnectivity(false, false);
            handlePreferredTransmitTransportConnected();
            ensureExclusiveTransmitState(false);
        });
    }

    private void scheduleTransmitFailoverIfNeeded() {
        cancelTransmitFailoverTimer();
        if (transmitFailoverActive) {
            return;
        }
        boolean alternateReady = false;
        if (preferredTransmitTransport == PreferredTransmitTransport.MESH) {
            alternateReady = !meshConnected && isUvproConnected();
        } else if (preferredTransmitTransport == PreferredTransmitTransport.UVPRO) {
            alternateReady = !isUvproConnected() && meshConnected;
        }
        if (!alternateReady) {
            return;
        }
        MapView mv = getMapView();
        if (mv == null) {
            return;
        }
        transmitFailoverRunnable = () -> {
            transmitFailoverRunnable = null;
            evaluateAndApplyTransmitFailover();
        };
        mv.postDelayed(transmitFailoverRunnable, TRANSMIT_FAILOVER_DELAY_MS);
    }

    private void evaluateAndApplyTransmitFailover() {
        if (transmitFailoverActive) {
            return;
        }
        if (preferredTransmitTransport == PreferredTransmitTransport.MESH
                && !meshConnected && isUvproConnected()) {
            transmitFailoverActive = true;
            applyTransmitTogglesExclusive(false, true, false, false);
            appendPluginLog(
                    "Transmit failover: MeshCore disconnected >5 min — switched to UV-PRO transmit");
        } else if (preferredTransmitTransport == PreferredTransmitTransport.UVPRO
                && !isUvproConnected() && meshConnected) {
            transmitFailoverActive = true;
            applyTransmitTogglesExclusive(true, false, false, false);
            appendPluginLog(
                    "Transmit failover: UV-PRO disconnected >5 min — switched to MeshCore transmit");
        }
    }

    private void persistTransmitTogglePreferences() {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit()
                .putBoolean(PREF_ATAK_MESHCORE_TRANSMIT, meshTransmitEnabled)
                .putBoolean(PREF_ATAK_UVPRO_TRANSMIT, uvproTransmitEnabled)
                .apply();
    }

    private void setMeshTransmitEnabled(boolean enabled, boolean logChange, boolean userPreferenceChange) {
        if (userPreferenceChange) {
            cancelTransmitFailoverTimer();
            transmitFailoverActive = false;
            preferredTransmitTransport = enabled
                    ? PreferredTransmitTransport.MESH
                    : PreferredTransmitTransport.UVPRO;
            persistPreferredTransmitTransport();
        }
        if (enabled) {
            applyTransmitTogglesExclusive(true, false, logChange, userPreferenceChange);
        } else {
            applyTransmitTogglesExclusive(false, true, logChange, userPreferenceChange);
        }
    }

    private void setUvproTransmitEnabled(boolean enabled, boolean logChange, boolean userPreferenceChange) {
        if (userPreferenceChange) {
            cancelTransmitFailoverTimer();
            transmitFailoverActive = false;
            preferredTransmitTransport = enabled
                    ? PreferredTransmitTransport.UVPRO
                    : PreferredTransmitTransport.MESH;
            persistPreferredTransmitTransport();
        }
        if (enabled) {
            applyTransmitTogglesExclusive(false, true, logChange, userPreferenceChange);
        } else {
            applyTransmitTogglesExclusive(true, false, logChange, userPreferenceChange);
        }
    }

    /**
     * Sets MeshCore / UV-PRO transmit toggles — exactly one on, one off.
     */
    private void applyTransmitTogglesExclusive(
            boolean meshOn, boolean uvOn, boolean logChange, boolean userPreferenceChange) {
        if (meshOn && uvOn) {
            uvOn = false;
        }
        if (!meshOn && !uvOn) {
            applyTogglesFromPreferredTransport();
            meshOn = meshTransmitEnabled;
            uvOn = uvproTransmitEnabled;
        }
        if (userPreferenceChange) {
            cancelTransmitFailoverTimer();
            transmitFailoverActive = false;
            preferredTransmitTransport = meshOn
                    ? PreferredTransmitTransport.MESH
                    : PreferredTransmitTransport.UVPRO;
            persistPreferredTransmitTransport();
        }
        meshTransmitEnabled = meshOn;
        uvproTransmitEnabled = uvOn;
        ensureExclusiveTransmitState(false);
        persistTransmitTogglePreferences();
        syncTransmitSwitchesUi();
        notifyBeaconTransportChanged();
        if (logChange) {
            if (meshTransmitEnabled) {
                appendLog("Transmit mode: ATAK MeshCore enabled");
            } else {
                appendLog("Transmit mode: ATAK UV-PRO enabled");
            }
        }
    }

    /** Repair invalid both-on / both-off toggle state. */
    private void ensureExclusiveTransmitState(boolean logChange) {
        if (meshTransmitEnabled == uvproTransmitEnabled) {
            if (transmitFailoverActive) {
                if (preferredTransmitTransport == PreferredTransmitTransport.MESH) {
                    applyTransmitTogglesExclusive(false, true, false, false);
                } else {
                    applyTransmitTogglesExclusive(true, false, false, false);
                }
            } else {
                applyTogglesFromPreferredTransport();
                if (logChange) {
                    appendLog("Transmit toggle: normalized to " + formatPreferredTransmitLabel());
                }
                persistTransmitTogglePreferences();
                syncTransmitSwitchesUi();
                notifyBeaconTransportChanged();
            }
        }
    }

    private void reconcileTransmitTogglesIfBothOff(boolean logChange, boolean userPreferenceChange) {
        ensureExclusiveTransmitState(logChange);
        if (userPreferenceChange) {
            syncPreferredTransmitTransportFromToggles();
            persistPreferredTransmitTransport();
        }
    }

    private void reconcileAndSyncTransmitTogglesIfNeeded(boolean logChange, boolean userPreferenceChange) {
        boolean beforeMesh = meshTransmitEnabled;
        boolean beforeUv = uvproTransmitEnabled;
        reconcileTransmitTogglesIfBothOff(logChange, userPreferenceChange);
        if (meshTransmitEnabled != beforeMesh || uvproTransmitEnabled != beforeUv) {
            persistTransmitTogglePreferences();
            syncTransmitSwitchesUi();
            notifyBeaconTransportChanged();
        }
    }

    private void setWifiTransmitEnabled(boolean enabled, boolean logChange) {
        wifiTransmitEnabled = enabled;
        setWifiTransmitPreference(enabled);
        if (cotBridge != null) {
            cotBridge.setWifiTransmitEnabled(enabled);
        }
        syncTransmitSwitchesUi();
        if (logChange) {
            appendLog(enabled
                    ? "Transmit mode: ATAK WiFi enabled"
                    : "Transmit mode: ATAK WiFi disabled");
        }
    }

    private void applyActiveTransmitTransport() {
        BtConnectionManager active = resolveActiveTransmitManager();
        if (cotBridge != null) {
            cotBridge.setBtManager(active);
        }
        if (chatBridge != null) {
            chatBridge.setBtManager(active);
        }
    }

    private void notifyBeaconTransportChanged() {
        AtakBroadcast.getInstance().sendBroadcast(
                new Intent(UVProMapComponent.ACTION_BEACON_TRANSPORT_CHANGED));
    }

    private String resolveTransmitModeLogLabel() {
        if (meshTransmitEnabled) {
            return "mesh";
        }
        if (uvproTransmitEnabled) {
            return "uvpro";
        }
        return "none";
    }

    private BtConnectionManager resolveActiveTransmitManager() {
        return TransmitTransportResolver.resolve(
                meshTransmitEnabled,
                uvproTransmitEnabled,
                meshBtManager,
                btManager);
    }

    private void updateContactCount() {
        if (contactsText != null) {
            int active = contactTracker.getActiveCount();
            int total = contactTracker.getTotalCount();
            contactsText.setText(active + " active / " + total + " total");
        }
    }

    private void updatePacketCount() {
        if (packetsText != null) {
            packetsText.setText(txCount + " / " + rxCount);
        }
    }

    /** Receive S-meter from radio GET_HT_STATUS (0-15); -1 when unknown/disconnected. */
    private void updateReceiveRssiUi(int receiveRssi) {
        if (receiveRssiText == null) {
            return;
        }
        boolean connected = btManager != null && btManager.isConnected();
        if (!connected || receiveRssi < 0) {
            lastReceiveRssiScaleLevel = -1;
            receiveRssiText.setText("—");
            if (receiveRssiMeterBlock != null) {
                receiveRssiMeterBlock.setVisibility(View.GONE);
            }
            return;
        }
        final int scaleLevel = mapReceiveRssiToScale(receiveRssi);
        lastReceiveRssiScaleLevel = scaleLevel;
        receiveRssiText.setText(String.format(Locale.US, "%d / %d", scaleLevel, RSSI_SCALE_MAX));
        if (receiveRssiMeterBlock != null && receiveRssiMeterFrame != null
                && receiveRssiFillGreen != null && receiveRssiFillRed != null) {
            receiveRssiMeterBlock.setVisibility(View.VISIBLE);
            scheduleReceiveRssiMeterLayout();
        }
    }

    private void scheduleReceiveRssiMeterLayout() {
        if (receiveRssiMeterFrame == null || lastReceiveRssiScaleLevel < 0) {
            return;
        }
        receiveRssiMeterFrame.post(() -> {
            if (receiveRssiMeterFrame.getWidth() > 0) {
                applyReceiveRssiFillWidth(lastReceiveRssiScaleLevel);
                layoutReceiveRssiScale();
                return;
            }
            ViewTreeObserver observer = receiveRssiMeterFrame.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    ViewTreeObserver current = receiveRssiMeterFrame.getViewTreeObserver();
                    if (current.isAlive()) {
                        current.removeOnGlobalLayoutListener(this);
                    }
                    if (receiveRssiMeterFrame.getWidth() > 0 && lastReceiveRssiScaleLevel >= 0) {
                        applyReceiveRssiFillWidth(lastReceiveRssiScaleLevel);
                        layoutReceiveRssiScale();
                    }
                }
            });
        });
    }

    /** Map radio RSSI (0-15) to on-screen S-scale (0-12). */
    private static int mapReceiveRssiToScale(int receiveRssi) {
        int raw = Math.max(0, Math.min(15, receiveRssi));
        return Math.min(RSSI_SCALE_MAX, Math.round(raw * (RSSI_SCALE_MAX / 15.0f)));
    }

    private void applyReceiveRssiFillWidth(int scaleLevel) {
        if (receiveRssiMeterFrame == null || receiveRssiFillGreen == null
                || receiveRssiFillRed == null) {
            return;
        }
        int trackWidth = receiveRssiMeterFrame.getWidth();
        if (trackWidth <= 0) {
            return;
        }
        int clamped = Math.max(0, Math.min(RSSI_SCALE_MAX, scaleLevel));
        int ninePx = scalePositionPx(trackWidth, 9);
        int totalPx = clamped == 0
                ? 0
                : Math.max(2, scalePositionPx(trackWidth, clamped));

        int greenWidth = clamped <= 9 ? totalPx : ninePx;
        android.widget.FrameLayout.LayoutParams greenLp =
                (android.widget.FrameLayout.LayoutParams) receiveRssiFillGreen.getLayoutParams();
        greenLp.width = greenWidth;
        greenLp.leftMargin = 0;
        receiveRssiFillGreen.setLayoutParams(greenLp);

        if (clamped > 9) {
            int redWidth = Math.max(0, totalPx - ninePx);
            receiveRssiFillRed.setVisibility(redWidth > 0 ? View.VISIBLE : View.GONE);
            android.widget.FrameLayout.LayoutParams redLp =
                    (android.widget.FrameLayout.LayoutParams) receiveRssiFillRed.getLayoutParams();
            redLp.width = redWidth;
            redLp.leftMargin = ninePx;
            redLp.gravity = android.view.Gravity.START | android.view.Gravity.TOP;
            receiveRssiFillRed.setLayoutParams(redLp);
        } else {
            receiveRssiFillRed.setVisibility(View.GONE);
        }
    }

    private void ensureReceiveRssiScaleMarks() {
        if (receiveRssiScaleBuilt || receiveRssiScaleFrame == null) {
            return;
        }
        Context ctx = getMapView().getContext();
        int tickW = dip(ctx, 1);
        int tickH = dip(ctx, 6);
        for (int pos : RSSI_SCALE_TICKS) {
            View tick = new View(ctx);
            tick.setTag("tick:" + pos);
            tick.setBackgroundColor(0xFFFFFFFF);
            receiveRssiScaleFrame.addView(tick,
                    new android.widget.FrameLayout.LayoutParams(tickW, tickH));
        }
        for (int i = 0; i < RSSI_SCALE_LABELS.length; i++) {
            TextView label = new TextView(ctx);
            label.setText(RSSI_SCALE_LABELS[i]);
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(9f);
            label.setTag("label:" + RSSI_SCALE_LABEL_POS[i]);
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dip(ctx, 8);
            receiveRssiScaleFrame.addView(label, lp);
        }
        receiveRssiScaleBuilt = true;
    }

    private void layoutReceiveRssiScale() {
        if (receiveRssiScaleFrame == null) {
            return;
        }
        int width = receiveRssiScaleFrame.getWidth();
        if (width <= 0) {
            return;
        }
        for (int i = 0; i < receiveRssiScaleFrame.getChildCount(); i++) {
            View child = receiveRssiScaleFrame.getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof String)) {
                continue;
            }
            String tagStr = (String) tag;
            if (tagStr.startsWith("tick:")) {
                int pos = Integer.parseInt(tagStr.substring(5));
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) child.getLayoutParams();
                int tickW = lp.width > 0 ? lp.width : 1;
                int x = scalePositionPx(width, pos) - tickW / 2;
                lp.leftMargin = Math.min(Math.max(0, width - tickW), Math.max(0, x));
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                child.setLayoutParams(lp);
            } else if (tagStr.startsWith("label:")) {
                int pos = Integer.parseInt(tagStr.substring(6));
                int xCenter = scalePositionPx(width, pos);
                int measuredW = child.getWidth();
                if (measuredW <= 0) {
                    child.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    measuredW = child.getMeasuredWidth();
                }
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) child.getLayoutParams();
                lp.leftMargin = Math.max(0, xCenter - measuredW / 2);
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                child.setLayoutParams(lp);
            }
        }
    }

    private static int scalePositionPx(int trackWidth, int scaleMark) {
        int clamped = Math.max(0, Math.min(RSSI_SCALE_MAX, scaleMark));
        return (int) (trackWidth * (clamped / (float) RSSI_SCALE_MAX));
    }

    private void updateStatusFields() {
        Context ctx = getMapView().getContext();

        boolean encOn = SettingsFragment.isEncryptionEnabled(ctx);
        if (switchEncryption != null) {
            switchEncryption.setChecked(encOn);
        }

        syncEncryptionFromSettings();
        updateEncryptionStatus();

        // Beacon interval
        int beaconSec = SettingsFragment.getBeaconIntervalSec(
                MapView.getMapView() != null
                        ? MapView.getMapView().getContext()
                        : ctx);
        if (beaconIntervalText != null) {
            beaconIntervalText.setText(beaconSec + "s");
        }

        boolean smartOn = SmartBeacon.isEnabled(ctx);
        if (switchSmartBeacon != null) {
            switchSmartBeacon.setChecked(smartOn);
        }
        applyBeaconIntervalRowState();
        if (switchAugmentGpsFromRadio != null) {
            switchAugmentGpsFromRadio.setChecked(isAugmentGpsPreferenceEnabled(ctx));
        }
        if (switchAugmentGpsFromMeshcore != null) {
            switchAugmentGpsFromMeshcore.setChecked(isAugmentMeshPreferenceEnabled(ctx));
        }
        if (switchMeshShowRepeaters != null) {
            switchMeshShowRepeaters.setChecked(isMeshShowRepeatersPreferenceEnabled(ctx));
        }
        if (switchMeshShowNodes != null) {
            switchMeshShowNodes.setChecked(isMeshShowNodesPreferenceEnabled(ctx));
        }
        if (switchMeshSendPositionWithAdvert != null) {
            switchMeshSendPositionWithAdvert.setChecked(
                    getMeshSendPositionWithAdvertPreference(ctx));
        }
        if (switchMeshEnableGpsMeshcore != null) {
            switchMeshEnableGpsMeshcore.setChecked(getMeshUseGpsForPositionPreference(ctx));
        }
        if (switchMeshUseCallsignLocation != null) {
            switchMeshUseCallsignLocation.setChecked(
                    isMeshUseCallsignLocationPreferenceEnabled(ctx));
        }
        if (switchMeshUseCustomNodePosition != null) {
            switchMeshUseCustomNodePosition.setChecked(
                    isMeshUseCustomNodePositionPreferenceEnabled(ctx));
        }
        if (isMeshUseCallsignLocationPreferenceEnabled(ctx)
                || (meshGpsEnableRequested || Boolean.TRUE.equals(meshGpsEnabledState))) {
            removeMeshNodeMapPositionMarker(true);
        }

        // Team color (ATAK preference)
        try {
            String teamColor = com.atakmap.android.chat.ChatManagerMapComponent.getTeamName();
            if (teamColorText != null) {
                teamColorText.setText(teamColor != null ? teamColor : "Cyan");
            }
        } catch (Exception ignored) {
        }
        updateRadioSilenceButtonUi();
        updateAprsSectionUi();
        updateChannelGroupButtonUi();
    }

    private void updateMeshUseCallsignLocationLabel(String callsign) {
        if (textMeshUseCallsignLocation == null) {
            return;
        }
        String safe = (callsign == null || callsign.trim().isEmpty())
                ? "UNKNOWN"
                : callsign.trim();
        textMeshUseCallsignLocation.setText("Use " + safe + " location for position");
    }

    private void requestManualRadioGpsUpdate() {
        if (!btManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to the radio first.", Toast.LENGTH_SHORT).show();
            return;
        }
        pulseUpdateGpsButtonFeedback(btnUpdateGpsFromRadio);
        appendLog("Reading GPS from radio...");
        new Thread(() -> {
            RadioGpsBridge.UpdateResult result = radioGpsAugmentController.manualUpdate();
            getMapView().post(() -> {
                appendLog(result.message);
            });
        }, "uvpro-radio-gps-manual").start();
    }

    private void requestManualMeshGpsUpdate() {
        if (meshBtManager == null || !meshBtManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to MeshCore first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Boolean.TRUE.equals(meshGpsEnabledState)) {
            Toast.makeText(getMapView().getContext(),
                    "Enable MeshCore GPS first.", Toast.LENGTH_SHORT).show();
            return;
        }
        pulseUpdateGpsButtonFeedback(btnUpdateGpsFromMeshcore);
        appendLog("Reading GPS from MeshCore...");
        new Thread(() -> {
            RadioGpsBridge.UpdateResult result = updateGpsFromMeshcoreNow(true);
            getMapView().post(() -> appendLog(result.message));
        }, "uvpro-mesh-gps-manual").start();
    }

    private RadioGpsBridge.UpdateResult updateGpsFromMeshcoreNow(boolean requestFreshSelfInfo) {
        if (meshBtManager == null || !meshBtManager.isConnected()) {
            return new RadioGpsBridge.UpdateResult(false, "MeshCore not connected.");
        }
        if (requestFreshSelfInfo) {
            meshBtManager.requestSelfInfo();
            try {
                Thread.sleep(700L);
            } catch (InterruptedException ignored) {
            }
        }
        MeshBtConnectionManager.MeshLocationFix fix = meshBtManager.getLatestSelfLocation();
        if (fix == null || !fix.isValid()) {
            return new RadioGpsBridge.UpdateResult(false,
                    "MeshCore did not provide a valid GPS fix yet.");
        }
        RadioPositionFix radioFix = new RadioPositionFix(
                fix.latitude,
                fix.longitude,
                0.0,
                Double.NaN,
                Double.NaN,
                0,
                System.currentTimeMillis() / 1000L);
        boolean ok = RadioGpsBridge.injectIntoAtak(
                getMapView(),
                radioFix,
                RadioGpsBridge.MESHCORE_MOCK_SOURCE_LABEL);
        return ok
                ? new RadioGpsBridge.UpdateResult(true, String.format(
                Locale.US,
                "Updated ATAK from MeshCore GPS: %.5f, %.5f",
                fix.latitude,
                fix.longitude))
                : new RadioGpsBridge.UpdateResult(false,
                "Could not apply MeshCore GPS to ATAK.");
    }

    private boolean isAugmentGpsPreferenceEnabled(Context ctx) {
        if (ctx == null) {
            return false;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        return prefs.getBoolean(RadioGpsBridge.PREF_AUGMENT_GPS_FROM_RADIO, false);
    }

    private boolean isAugmentMeshPreferenceEnabled(Context ctx) {
        if (ctx == null) {
            return false;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        return prefs.getBoolean(PREF_AUGMENT_GPS_FROM_MESHCORE, false);
    }

    private void setAugmentGpsPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit().putBoolean(RadioGpsBridge.PREF_AUGMENT_GPS_FROM_RADIO, enabled).apply();
    }

    private void setAugmentMeshPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit().putBoolean(PREF_AUGMENT_GPS_FROM_MESHCORE, enabled).apply();
    }

    private boolean isMeshShowRepeatersPreferenceEnabled(Context ctx) {
        if (ctx == null) {
            return true;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        return prefs.getBoolean(PREF_MESH_SHOW_REPEATERS, true);
    }

    private boolean isMeshShowNodesPreferenceEnabled(Context ctx) {
        if (ctx == null) {
            return false;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        return prefs.getBoolean(PREF_MESH_SHOW_NODES, false);
    }

    private boolean getMeshSendPositionWithAdvertPreference(Context ctx) {
        if (ctx == null) {
            return true;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        return prefs.getBoolean(PREF_MESH_SEND_POSITION_WITH_ADVERT, true);
    }

    private void setMeshSendPositionWithAdvertPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit().putBoolean(PREF_MESH_SEND_POSITION_WITH_ADVERT, enabled).apply();
    }

    private boolean getMeshUseGpsForPositionPreference(Context ctx) {
        if (ctx == null) {
            return false;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        return prefs.getBoolean(PREF_MESH_USE_GPS_FOR_POSITION, true);
    }

    private void setMeshUseGpsForPositionPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit().putBoolean(PREF_MESH_USE_GPS_FOR_POSITION, enabled).apply();
    }

    private boolean isMeshUseCallsignLocationPreferenceEnabled(Context ctx) {
        if (ctx == null) {
            return false;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        return prefs.getBoolean(PREF_MESH_USE_CALLSIGN_LOCATION_FOR_POSITION, false);
    }

    private boolean isMeshUseCustomNodePositionPreferenceEnabled(Context ctx) {
        if (ctx == null) {
            return true;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        return prefs.getBoolean(PREF_MESH_USE_CUSTOM_NODE_POSITION, false);
    }

    private void setMeshUseCallsignLocationPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit().putBoolean(PREF_MESH_USE_CALLSIGN_LOCATION_FOR_POSITION, enabled).apply();
    }

    private void setMeshUseCustomNodePositionPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit().putBoolean(PREF_MESH_USE_CUSTOM_NODE_POSITION, enabled).apply();
    }

    private void setMeshMapSetPosition(Context ctx, com.atakmap.coremap.maps.coords.GeoPoint gp) {
        if (ctx == null || gp == null || !gp.isValid()) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit()
                .putString(PREF_MESH_MAP_SET_POSITION_LAT, Double.toString(gp.getLatitude()))
                .putString(PREF_MESH_MAP_SET_POSITION_LON, Double.toString(gp.getLongitude()))
                .apply();
    }

    private com.atakmap.coremap.maps.coords.GeoPoint getMeshMapSetPosition(Context ctx) {
        if (ctx == null) {
            return null;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        String latStr = prefs.getString(PREF_MESH_MAP_SET_POSITION_LAT, null);
        String lonStr = prefs.getString(PREF_MESH_MAP_SET_POSITION_LON, null);
        if (latStr == null || lonStr == null) {
            return null;
        }
        try {
            double lat = Double.parseDouble(latStr);
            double lon = Double.parseDouble(lonStr);
            com.atakmap.coremap.maps.coords.GeoPoint gp =
                    new com.atakmap.coremap.maps.coords.GeoPoint(lat, lon);
            return gp.isValid() ? gp : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void clearMeshMapSetPosition(Context ctx) {
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit()
                .remove(PREF_MESH_MAP_SET_POSITION_LAT)
                .remove(PREF_MESH_MAP_SET_POSITION_LON)
                .apply();
    }

    private void setMeshShowRepeatersPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit().putBoolean(PREF_MESH_SHOW_REPEATERS, enabled).apply();
    }

    private void setMeshShowNodesPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit().putBoolean(PREF_MESH_SHOW_NODES, enabled).apply();
    }

    private boolean isWifiTransmitPreferenceEnabled(Context ctx) {
        if (ctx == null) {
            return false;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        return prefs.getBoolean(PREF_ATAK_WIFI_TRANSMIT, true);
    }

    private void setWifiTransmitPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit().putBoolean(PREF_ATAK_WIFI_TRANSMIT, enabled).apply();
    }

    private boolean isWifiConnected() {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return false;
        }
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            Network active = cm.getActiveNetwork();
            if (active == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null
                    && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void scheduleMeshGpsAugmentTick() {
        if (getMapView() == null) {
            return;
        }
        getMapView().removeCallbacks(meshGpsAugmentRunnable);
        Context ctx = getMapView().getContext();
        if (!isAugmentMeshPreferenceEnabled(ctx)) {
            return;
        }
        getMapView().postDelayed(meshGpsAugmentRunnable, RadioGpsBridge.AUGMENT_INTERVAL_MS);
    }

    private void updateChannelGroupButtonUi() {
        if (btnChannelGroup == null) {
            return;
        }
        btnChannelGroup.setText(String.format(
                Locale.US, "Group\n%d", currentChannelGroup + 1));
    }

    private void refreshChannelGroupFromRadioAsync() {
        refreshChannelGroupFromRadioAsync(false);
    }

    private void refreshChannelGroupFromRadioAsync(boolean refreshGrid) {
        if (radioControlManager == null || !btManager.isConnected()) {
            return;
        }
        if (!groupReadInFlight.compareAndSet(false, true)) {
            if (refreshGrid) {
                groupRefreshGridPending.set(true);
            }
            return;
        }
        final boolean requestGridRefresh = refreshGrid;
        new Thread(() -> {
            try {
                UVProRadioControlManager.ChannelGroupInfo info =
                        radioControlManager.readCurrentGroupInfo();
                if (info == null) {
                    // Startup read can be transiently empty while radio settles.
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    info = radioControlManager.readCurrentGroupInfo();
                }
                if (info == null) {
                    if (requestGridRefresh) {
                        getMapView().post(this::refreshChannelGridAsync);
                    }
                    return;
                }
                final UVProRadioControlManager.ChannelGroupInfo finalInfo = info;
                getMapView().post(() -> {
                    currentChannelGroup = finalInfo.currentGroupIndex;
                    availableChannelGroups = Math.max(1, finalInfo.groupCount);
                    updateChannelGroupButtonUi();
                    if (requestGridRefresh) {
                        refreshChannelGridFullAsync();
                    }
                });
            } finally {
                groupReadInFlight.set(false);
                if (groupRefreshGridPending.getAndSet(false)) {
                    refreshChannelGroupFromRadioAsync(true);
                }
            }
        }, "uvpro-read-group").start();
    }

    private void runInitialChannelGroupSetupAsync() {
        if (radioControlManager == null || !btManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to radio first.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        pulseInitialGroupSetupButtonFeedback();
        appendLog("Starting Initial Channel Group Setup...");
        if (btnInitialChannelGroupSetup != null) {
            btnInitialChannelGroupSetup.setEnabled(false);
        }
        new Thread(() -> {
            try {
                UVProRadioControlManager.ChannelGroupInfo info = radioControlManager.readCurrentGroupInfo();
                final int originalGroup = info != null ? info.currentGroupIndex : currentChannelGroup;
                final int groupCount = info != null
                        ? Math.max(1, info.groupCount)
                        : UVProRadioControlManager.DEFAULT_GROUP_COUNT;
                UVProRadioControlManager.ManualChannelSpec aprsSeed =
                        new UVProRadioControlManager.ManualChannelSpec(
                                "APRS",
                                144.390,
                                144.390,
                                null,
                                null,
                                true,
                                false,
                                UVProRadioControlManager.TX_POWER_HIGH,
                                true,
                                -1);
                int seeded = 0;
                for (int group = 0; group < groupCount; group++) {
                    UVProRadioControlManager.ProgramResult select = radioControlManager.setChannelGroup(group);
                    if (!select.success) {
                        // Empty groups may reject selection until seeded.
                        UVProRadioControlManager.ProgramResult blindSeed =
                                radioControlManager.seedAprsChannelInGroupBlind(group, aprsSeed);
                        if (blindSeed.success) {
                            seeded++;
                        }
                        continue;
                    }
                    UVProRadioControlManager.RadioControlSnapshot snapshot = radioControlManager.readSnapshot(30);
                    if (isGroupEmpty(snapshot)) {
                        UVProRadioControlManager.ProgramResult write =
                                radioControlManager.programManualChannel(
                                        UVProRadioControlManager.CHANNELS_PER_GROUP - 1,
                                        aprsSeed);
                        if (write.success) {
                            seeded++;
                        }
                    }
                }
                radioControlManager.setChannelGroup(originalGroup);
                final int seededCount = seeded;
                getMapView().post(() -> {
                    if (btnInitialChannelGroupSetup != null) {
                        btnInitialChannelGroupSetup.setEnabled(true);
                    }
                    stopInitialGroupSetupPulse(true);
                    refreshChannelGroupFromRadioAsync(true);
                    appendLog("Channel Group Setup Complete");
                    showInfoDialog("Channel Group Setup Complete");
                    if (seededCount > 0) {
                        appendLog(String.format(Locale.US,
                                "Seeded APRS on CH30 for %d empty group(s).", seededCount));
                    }
                });
            } catch (Exception e) {
                getMapView().post(() -> {
                    if (btnInitialChannelGroupSetup != null) {
                        btnInitialChannelGroupSetup.setEnabled(true);
                    }
                    stopInitialGroupSetupPulse(true);
                    appendLog("Initial group setup failed: " + e.getMessage());
                });
            }
        }, "uvpro-initial-group-setup").start();
    }

    private static boolean isGroupEmpty(UVProRadioControlManager.RadioControlSnapshot snapshot) {
        if (snapshot == null || snapshot.channels == null || snapshot.channels.length == 0) {
            return true;
        }
        for (UVProRadioControlManager.ChannelSummary c : snapshot.channels) {
            if (c == null) {
                continue;
            }
            boolean hasFreq = c.rxFreqMHz > 0.0 || c.txFreqMHz > 0.0;
            boolean hasName = c.name != null && !c.name.trim().isEmpty();
            if (hasFreq || hasName) {
                return false;
            }
        }
        return true;
    }

    private void cycleChannelGroup() {
        if (radioControlManager == null || !btManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to radio first.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final int next = (currentChannelGroup + 1) % Math.max(1, availableChannelGroups);
        appendLog(String.format(Locale.US, "Switching to Group %d...", next + 1));
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult result =
                    radioControlManager.setChannelGroup(next);
            getMapView().post(() -> {
                appendLog(result.message);
                if (result.success) {
                    currentChannelGroup = next;
                    updateChannelGroupButtonUi();
                    refreshChannelGridFullAsync();
                    verifyGroupSwitchAcceptedAsync(next);
                } else {
                    Toast.makeText(getMapView().getContext(),
                            result.message, Toast.LENGTH_LONG).show();
                }
            });
        }, "uvpro-set-group").start();
    }

    private void verifyGroupSwitchAcceptedAsync(int expectedGroup) {
        if (radioControlManager == null || !btManager.isConnected()) {
            return;
        }
        new Thread(() -> {
            UVProRadioControlManager.ChannelGroupInfo info = radioControlManager.readCurrentGroupInfo();
            if (info == null) {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                info = radioControlManager.readCurrentGroupInfo();
            }
            if (info != null && info.currentGroupIndex != expectedGroup) {
                getMapView().post(() -> showInfoDialog(
                        "Please run \"Initial Channel Group Setup\" under the actions settings of the plugin"));
            }
        }, "uvpro-verify-group-switch").start();
    }

    private void showImportChannelsPicker() {
        MapView mv = getMapView();
        if (mv == null) {
            return;
        }
        mv.post(this::showImportChannelsPickerOnUi);
    }

    private void showImportChannelsPickerOnUi() {
        Context ctx = getMapView().getContext();
        File dir = resolveAtakToolsDir("tools/import");
        String importPath = formatUserVisibleToolsPath("tools/import");
        if (dir == null) {
            appendLog("Import folder unavailable: " + importPath);
            Toast.makeText(ctx, importPath, Toast.LENGTH_LONG).show();
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            appendLog("Import folder not found: " + importPath);
            Toast.makeText(ctx, "Could not create:\n" + importPath, Toast.LENGTH_LONG).show();
            return;
        }
        importPath = formatUserVisibleToolsPath("tools/import");
        appendLog("Import Channels: " + importPath);
        File[] csv = dir.listFiles((d, name) -> name != null
                && name.toLowerCase(Locale.US).endsWith(".csv"));
        if (csv == null || csv.length == 0) {
            appendLog("No CSV files in " + importPath);
            new AlertDialog.Builder(ctx)
                    .setTitle("Import Channels")
                    .setMessage("No .csv files found.\n\nCopy channel CSV files to:\n\n" + importPath)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        java.util.Arrays.sort(csv, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        String[] names = new String[csv.length];
        for (int i = 0; i < csv.length; i++) {
            names[i] = csv[i].getName();
        }
        final File[] files = csv;
        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("Import Channels")
                .setSingleChoiceItems(names, 0, null)
                .setPositiveButton("Import", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> {
            android.widget.Button importBtn =
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (importBtn != null) {
                importBtn.setOnClickListener(v -> {
                    android.widget.ListView list = dialog.getListView();
                    int checked = list != null
                            ? list.getCheckedItemPosition()
                            : android.widget.AdapterView.INVALID_POSITION;
                    if (checked < 0 || checked >= files.length) {
                        Toast.makeText(ctx, "Select a CSV file", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    importChannelsFromFile(files[checked]);
                });
            }
        });
        try {
            dialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Import channels dialog failed", e);
            appendLog("Could not open import picker: " + e.getMessage());
        }
    }

    /** ATAK tools directory; I/O uses FileSystemUtils, same location as /sdcard/atak/… */
    private static File resolveAtakToolsDir(String relativePath) {
        String rel = normalizeAtakRelativePath(relativePath);
        if (rel.isEmpty()) {
            return null;
        }
        try {
            File dir = FileSystemUtils.getItem(rel);
            if (dir != null) {
                return dir;
            }
        } catch (Exception e) {
            Log.w(TAG, "FileSystemUtils.getItem(" + rel + ") failed", e);
        }
        try {
            File root = FileSystemUtils.getRoot();
            if (root != null) {
                return new File(root, rel);
            }
        } catch (Exception ignored) {
        }
        return new File("/sdcard/atak", rel);
    }

    private static String normalizeAtakRelativePath(String relativePath) {
        if (relativePath == null) {
            return "";
        }
        String rel = relativePath.trim().replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        return rel;
    }

    /**
     * Path shown in dialogs/logs — matches file-manager labels, not {@code /storage/emulated/0}.
     * Example: {@code sdcard/atak/tools/import}
     */
    private static String formatUserVisibleToolsPath(String relativePath) {
        String rel = normalizeAtakRelativePath(relativePath);
        if (rel.isEmpty()) {
            return "sdcard/atak";
        }
        return "sdcard/atak/" + rel;
    }

    private void importChannelsFromFile(File csvFile) {
        if (radioControlManager == null || !btManager.isConnected()) {
            appendLog("UV-PRO not connected — connect before channel import.");
            return;
        }
        final int targetGroup = currentChannelGroup;
        appendLog(String.format(Locale.US,
                "Importing %s to Group %d...", csvFile.getName(), targetGroup + 1));
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult select =
                    radioControlManager.setChannelGroup(targetGroup);
            if (!select.success) {
                getMapView().post(() -> appendLog("Import aborted: " + select.message));
                return;
            }
            UVProRadioControlManager.ManualChannelSpec[] rows = parseCsvChannelsBySlot(csvFile);
            int validRows = 0;
            for (UVProRadioControlManager.ManualChannelSpec row : rows) {
                if (row != null) {
                    validRows++;
                }
            }
            if (validRows == 0) {
                getMapView().post(() -> appendLog("No valid channels parsed from CSV."));
                return;
            }
            int applied = 0;
            int cleared = 0;
            String firstError = null;
            for (int i = 0; i < Math.min(UVProRadioControlManager.CHANNELS_PER_GROUP, rows.length); i++) {
                UVProRadioControlManager.ProgramResult r;
                if (rows[i] == null) {
                    // CSV empty row means explicitly clear this channel slot.
                    r = radioControlManager.clearManualChannel(i);
                    if (r.success) {
                        cleared++;
                    }
                } else {
                    r = radioControlManager.programManualChannel(i, rows[i]);
                }
                if (r.success) {
                    applied++;
                } else if (firstError == null) {
                    firstError = r.message;
                }
            }
            int imported = applied;
            String finalError = firstError;
            int finalCleared = cleared;
            getMapView().post(() -> {
                appendLog(String.format(Locale.US,
                        "Imported %d slot(s) to Group %d (%d cleared).",
                        imported, targetGroup + 1, finalCleared));
                if (finalError != null) {
                    appendLog("Import note: " + finalError);
                }
                refreshChannelGroupFromRadioAsync(true);
            });
        }, "uvpro-import-group").start();
    }

    private void exportCurrentGroupChannels() {
        if (radioControlManager == null || !btManager.isConnected()) {
            appendLog("UV-PRO not connected — connect before channel export.");
            return;
        }
        new Thread(() -> {
            UVProRadioControlManager.RadioControlSnapshot snapshot =
                    radioControlManager.readSnapshotForGroup(currentChannelGroup, 30);
            if (snapshot == null || snapshot.channels == null) {
                getMapView().post(() -> appendLog("Export failed: could not read channels."));
                return;
            }
            File outDir = resolveAtakToolsDir("tools/datapackage/transfer");
            if (outDir == null) {
                getMapView().post(() -> appendLog("Export failed: output folder unavailable."));
                return;
            }
            if (!outDir.exists() && !outDir.mkdirs()) {
                getMapView().post(() -> appendLog("Export failed: could not create "
                        + outDir.getAbsolutePath()));
                return;
            }
            String dtg = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = String.format(Locale.US, "group%d_export_%s.csv",
                    currentChannelGroup + 1, dtg);
            File out = new File(outDir, fileName);
            try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
                pw.println("Channel,Name,RX_MHz,TX_MHz,TX_Tone,RX_Tone,Scan,Muted");
                for (UVProRadioControlManager.ChannelSummary c : snapshot.channels) {
                    if (c == null) continue;
                    pw.println(String.format(Locale.US, "%d,%s,%.5f,%.5f,%s,%s,%s,%s",
                            displayChannelNumber(c.channelId),
                            safeCsv(c.name),
                            c.rxFreqMHz,
                            c.txFreqMHz,
                            safeCsv(String.valueOf(c.txTone == null ? "" : c.txTone)),
                            safeCsv(String.valueOf(c.rxTone == null ? "" : c.rxTone)),
                            c.scanEnabled,
                            c.muted));
                }
            } catch (Exception e) {
                getMapView().post(() -> appendLog("Export failed: " + e.getMessage()));
                return;
            }
            final String exportPath = outDir.getAbsolutePath();
            getMapView().post(() -> {
                appendLog(fileName + " exported to " + exportPath);
                Toast.makeText(getMapView().getContext(),
                        fileName + " exported to transfer folder",
                        Toast.LENGTH_LONG).show();
            });
        }, "uvpro-export-group").start();
    }

    private UVProRadioControlManager.ManualChannelSpec[] parseCsvChannelsBySlot(File file) {
        UVProRadioControlManager.ManualChannelSpec[] out =
                new UVProRadioControlManager.ManualChannelSpec[UVProRadioControlManager.CHANNELS_PER_GROUP];
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            Map<String, Integer> hdr = new HashMap<>();
            boolean first = true;
            int rowIndex = 0;
            while ((line = br.readLine()) != null) {
                line = line.replace("\u0000", "");
                if (line.startsWith("#")) continue;
                // After header is parsed, blank lines represent empty channel slots.
                if (line.trim().isEmpty()) {
                    if (!first && rowIndex < UVProRadioControlManager.CHANNELS_PER_GROUP) {
                        rowIndex++;
                    }
                    continue;
                }
                String[] p = line.split(",", -1);
                if (first) {
                    first = false;
                    for (int i = 0; i < p.length; i++) {
                        hdr.put(p[i].trim().toLowerCase(Locale.US), i);
                    }
                    if (hdr.containsKey("rx_mhz") || hdr.containsKey("rx")
                            || hdr.containsKey("frequency")
                            || hdr.containsKey("rx_freq")
                            || hdr.containsKey("tx_freq")) {
                        continue;
                    }
                }
                if (rowIndex >= UVProRadioControlManager.CHANNELS_PER_GROUP) {
                    break;
                }
                double rx = parseCsvFrequency(p, hdr,
                        "rx_mhz", "rx", "frequency", "rxfreq", "rx_freq", "receive_frequency");
                if (rx <= 0) {
                    rowIndex++;
                    continue;
                }
                double tx = parseCsvFrequency(p, hdr,
                        "tx_mhz", "tx", "txfreq", "tx_freq", "transmit_frequency");
                if (tx <= 0) tx = rx;
                String name = parseCsvText(p, hdr, "name", "channel_name", "title", "channel");
                if (name == null || name.trim().isEmpty()) {
                    name = "CH";
                }
                name = name.replace("\u0000", "").trim();
                UVProRadioControlManager.ManualChannelSpec spec =
                        new UVProRadioControlManager.ManualChannelSpec(
                                name,
                                rx,
                                tx,
                                null,
                                null,
                                true,
                                false,
                                UVProRadioControlManager.TX_POWER_HIGH,
                                true,
                                -1);
                out[rowIndex] = spec;
                rowIndex++;
            }
        } catch (Exception e) {
            Log.w(TAG, "parseCsvChannels failed", e);
        }
        return out;
    }

    private static double parseCsvFrequency(String[] parts, Map<String, Integer> hdr, String... keys) {
        double raw = parseCsvDouble(parts, hdr, keys);
        if (raw <= 0) {
            return -1.0;
        }
        // Accept Hz-formatted CSVs (e.g. 146520000) and MHz-formatted CSVs.
        return raw > 1_000_000.0 ? (raw / 1_000_000.0) : raw;
    }

    private static double parseCsvDouble(String[] parts, Map<String, Integer> hdr, String... keys) {
        for (String k : keys) {
            Integer idx = hdr.get(k.toLowerCase(Locale.US));
            if (idx != null && idx >= 0 && idx < parts.length) {
                try {
                    return Double.parseDouble(parts[idx].trim());
                } catch (Exception ignored) {
                }
            }
        }
        return -1.0;
    }

    private static String parseCsvText(String[] parts, Map<String, Integer> hdr, String... keys) {
        for (String k : keys) {
            Integer idx = hdr.get(k.toLowerCase(Locale.US));
            if (idx != null && idx >= 0 && idx < parts.length) {
                String v = parts[idx].trim();
                if (!v.isEmpty()) return v;
            }
        }
        return "";
    }

    private int displayChannelNumber(int channelId) {
        if (channelId >= 0 && channelId < UVProRadioControlManager.CHANNELS_PER_GROUP) {
            return channelId + 1;
        }
        return Math.max(1, channelId + 1);
    }

    private static String safeCsv(String value) {
        if (value == null) return "";
        String v = value.replace("\"", "\"\"");
        if (v.contains(",") || v.contains("\"")) {
            return "\"" + v + "\"";
        }
        return v;
    }

    private void pulseSendButtonFeedback(Button targetButton, int idleStrokeColor) {
        if (targetButton == null) {
            return;
        }
        try {
            targetButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (Exception ignored) {
        }
        stopSendButtonPulse(false);
        sendButtonPulseTarget = targetButton;
        sendButtonPulseRestoreStroke = idleStrokeColor;
        targetButton.setBackgroundTintList(null);
        sendButtonPulseDrawable = buildVfoButtonBackground(
                COLOR_PILL_BUTTON_PRIMARY, 0x00FFEB3B, EDIT_SELECTION_STROKE_DP);
        targetButton.setBackground(sendButtonPulseDrawable);
        sendButtonPulseAnimator = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                0x11FFEB3B,
                0xFFFFEB3B);
        sendButtonPulseAnimator.setDuration(220L);
        sendButtonPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        sendButtonPulseAnimator.setRepeatCount(4);
        sendButtonPulseAnimator.addUpdateListener(animation -> {
            if (sendButtonPulseDrawable == null || sendButtonPulseTarget == null) {
                return;
            }
            int color = (Integer) animation.getAnimatedValue();
            sendButtonPulseDrawable.setStroke(
                    dip(getMapView().getContext(), EDIT_SELECTION_STROKE_DP), color);
            sendButtonPulseTarget.invalidate();
        });
        sendButtonPulseAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                stopSendButtonPulse(true);
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                stopSendButtonPulse(true);
            }
        });
        sendButtonPulseAnimator.start();
    }

    private void stopSendButtonPulse(boolean restoreBackground) {
        ValueAnimator animator = sendButtonPulseAnimator;
        sendButtonPulseAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
        sendButtonPulseDrawable = null;
        Button target = sendButtonPulseTarget;
        sendButtonPulseTarget = null;
        if (restoreBackground && target != null) {
            target.setBackgroundTintList(null);
            target.setBackground(buildVfoButtonBackground(
                    COLOR_PILL_BUTTON_PRIMARY,
                    sendButtonPulseRestoreStroke,
                    2));
        }
    }

    private void showActionToast(String message) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null || message == null || message.isEmpty()) {
            return;
        }
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
    }

    private void pulseUpdateGpsButtonFeedback(Button targetButton) {
        if (targetButton == null) {
            return;
        }
        try {
            targetButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (Exception ignored) {
        }
        stopUpdateGpsButtonPulse(false);
        updateGpsPulseTargetButton = targetButton;
        targetButton.setBackgroundTintList(null);
        updateGpsButtonPulseDrawable = buildVfoButtonBackground(
                0xFF455A64, 0x00FFEB3B, EDIT_SELECTION_STROKE_DP);
        targetButton.setBackground(updateGpsButtonPulseDrawable);
        updateGpsButtonPulseAnimator = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                0x11FFEB3B,
                0xFFFFEB3B);
        updateGpsButtonPulseAnimator.setDuration(220L);
        updateGpsButtonPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        updateGpsButtonPulseAnimator.setRepeatCount(4);
        updateGpsButtonPulseAnimator.addUpdateListener(animation -> {
            if (updateGpsButtonPulseDrawable == null || updateGpsPulseTargetButton == null) {
                return;
            }
            int color = (Integer) animation.getAnimatedValue();
            updateGpsButtonPulseDrawable.setStroke(
                    dip(getMapView().getContext(), EDIT_SELECTION_STROKE_DP), color);
            updateGpsPulseTargetButton.invalidate();
        });
        updateGpsButtonPulseAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                stopUpdateGpsButtonPulse(true);
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                stopUpdateGpsButtonPulse(true);
            }
        });
        updateGpsButtonPulseAnimator.start();
    }

    private void pulseInitialGroupSetupButtonFeedback() {
        if (btnInitialChannelGroupSetup == null) {
            return;
        }
        try {
            btnInitialChannelGroupSetup.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (Exception ignored) {
        }
        stopInitialGroupSetupPulse(false);
        btnInitialChannelGroupSetup.setBackgroundTintList(null);
        initialGroupSetupPulseDrawable = buildVfoButtonBackground(
                0xFF455A64, 0x00FFEB3B, EDIT_SELECTION_STROKE_DP);
        btnInitialChannelGroupSetup.setBackground(initialGroupSetupPulseDrawable);
        initialGroupSetupPulseAnimator = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                0x11FFEB3B,
                0xFFFFEB3B);
        initialGroupSetupPulseAnimator.setDuration(220L);
        initialGroupSetupPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        initialGroupSetupPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        initialGroupSetupPulseAnimator.addUpdateListener(animation -> {
            if (initialGroupSetupPulseDrawable == null || btnInitialChannelGroupSetup == null) {
                return;
            }
            int color = (Integer) animation.getAnimatedValue();
            initialGroupSetupPulseDrawable.setStroke(
                    dip(getMapView().getContext(), EDIT_SELECTION_STROKE_DP), color);
            btnInitialChannelGroupSetup.invalidate();
        });
        initialGroupSetupPulseAnimator.start();
    }

    private void stopInitialGroupSetupPulse(boolean restoreBackground) {
        ValueAnimator animator = initialGroupSetupPulseAnimator;
        initialGroupSetupPulseAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
        initialGroupSetupPulseDrawable = null;
        if (restoreBackground && btnInitialChannelGroupSetup != null) {
            applyPillButtonBackground(btnInitialChannelGroupSetup, 0xFF455A64);
        }
    }

    private void showInfoDialog(String message) {
        if (getMapView() == null || getMapView().getContext() == null) {
            return;
        }
        try {
            new AlertDialog.Builder(getMapView().getContext())
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception ignored) {
        }
    }

    private void stopUpdateGpsButtonPulse(boolean restoreBackground) {
        ValueAnimator animator = updateGpsButtonPulseAnimator;
        updateGpsButtonPulseAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
        updateGpsButtonPulseDrawable = null;
        Button target = updateGpsPulseTargetButton;
        updateGpsPulseTargetButton = null;
        if (restoreBackground && target != null) {
            applyPillButtonBackground(target, 0xFF455A64);
        }
    }

    private boolean isRadioSilenceEnabled() {
        return (btManager != null && btManager.isRadioSilenceEnabled())
                || (meshBtManager != null && meshBtManager.isRadioSilenceEnabled());
    }

    /** One button covers both radios. Each manager keeps its own flag. */
    private void setRadioSilenceOnBothTransports(boolean enabled) {
        if (btManager != null) {
            btManager.setRadioSilenceEnabled(enabled);
        }
        if (meshBtManager != null) {
            meshBtManager.setRadioSilenceEnabled(enabled);
        }
    }

    private void updateRadioSilenceButtonUi() {
        if (btnRadioSilence == null) {
            return;
        }
        boolean enabled = isRadioSilenceEnabled();
        btnRadioSilence.setBackgroundTintList(null);
        GradientDrawable bg = buildVfoButtonBackground(
                COLOR_PILL_BUTTON_PRIMARY,
                COLOR_CHANNEL_SECTION_STROKE,
                CHANNEL_SECTION_STROKE_DP);
        btnRadioSilence.setBackground(bg);
        btnRadioSilence.setText(enabled
                ? "Long Press for Radio Silence (ACTIVE)"
                : "Long Press for Radio Silence");
    }

    /** GPS beacon interval row stays visible and editable regardless of Smart Beacon. */
    private void applyBeaconIntervalRowState() {
        if (rowBeaconInterval != null) {
            rowBeaconInterval.setAlpha(1.0f);
            rowBeaconInterval.setEnabled(true);
        }
        if (gpsBeaconIntervalLabel != null) {
            gpsBeaconIntervalLabel.setTextColor(0xFFFFFFFF);
        }
        if (beaconIntervalText != null) {
            beaconIntervalText.setTextColor(0xFF00BCD4);
        }
    }

    private void showBeaconIntervalPicker() {
        MapView mv = getMapView();
        if (mv == null) {
            return;
        }
        Context ctx = mv.getContext();
        int labelsId = pluginContext.getResources().getIdentifier(
                "beacon_interval_labels", "array", pluginContext.getPackageName());
        int valuesId = pluginContext.getResources().getIdentifier(
                "beacon_interval_values", "array", pluginContext.getPackageName());
        if (labelsId == 0 || valuesId == 0) {
            return;
        }
        String[] labels = pluginContext.getResources().getStringArray(labelsId);
        String[] values = pluginContext.getResources().getStringArray(valuesId);
        if (labels.length == 0 || labels.length != values.length) {
            return;
        }
        int currentSec = SettingsFragment.getBeaconIntervalSec(ctx);
        int selected = 0;
        for (int i = 0; i < values.length; i++) {
            try {
                if (Integer.parseInt(values[i]) == currentSec) {
                    selected = i;
                    break;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        final int initialSelection = selected;
        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("GPS Beacon Interval")
                .setSingleChoiceItems(labels, initialSelection, null)
                .setPositiveButton("Set", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> {
            android.widget.Button setBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (setBtn != null) {
                setBtn.setOnClickListener(v -> {
                    android.widget.ListView list = dialog.getListView();
                    int which = list != null ? list.getCheckedItemPosition() : initialSelection;
                    if (which < 0 || which >= values.length) {
                        which = initialSelection;
                    }
                    try {
                        int sec = Integer.parseInt(values[which]);
                        SettingsFragment.setBeaconIntervalSec(ctx, sec);
                        updateStatusFields();
                        appendLog("Beacon interval set to " + sec + "s");
                    } catch (NumberFormatException ignored) {
                    }
                    dialog.dismiss();
                });
            }
        });
        dialog.show();
    }

    private void syncEncryptionFromSettings() {
        com.uvpro.plugin.protocol.UVProRadioServices.syncEncryptionFromSettings(
                getMapView().getContext());
    }

    private void updateEncryptionStatus() {
        if (encryptionStatusText == null) return;
        boolean encOn = SettingsFragment.isEncryptionEnabled(getMapView().getContext());
        String pass = SettingsFragment.getEncryptionPassphrase(getMapView().getContext());
        if (encOn && pass != null && !pass.isEmpty()) {
            encryptionStatusText.setText("\u2705 AES-256-GCM active");
            encryptionStatusText.setTextColor(0xFF4CAF50);
        } else if (encOn) {
            encryptionStatusText.setText("\u26A0 Set shared secret in Tool Preferences");
            encryptionStatusText.setTextColor(0xFFFF9800);
        } else {
            encryptionStatusText.setText("Shared secret is configured in Tool Preferences");
            encryptionStatusText.setTextColor(0xFF888888);
        }
    }

    private void refreshLogView() {
        if (logText != null && !logLines.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String l : logLines) {
                sb.append(l).append("\n");
            }
            logText.setText(sb.toString());
        }
    }

    /**
     * Append a line to the plugin log window from non-UI callers (e.g. MapComponent).
     */
    public void appendPluginLog(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        MapView mv = getMapView();
        if (mv != null) {
            mv.post(() -> appendLog(message));
        }
    }

    private void appendConnectionStatusLog(String event) {
        appendLog(event + " — " + formatLinkStatusSummary());
    }

    private String formatLinkStatusSummary() {
        boolean meshUp = isMeshConnected();
        return String.format(Locale.US, "UV-PRO %s, MeshCore %s",
                isUvproConnected() ? "connected" : "not connected",
                meshUp ? "connected" : "not connected");
    }

    private boolean isMeshConnected() {
        return meshBtManager != null && meshBtManager.isConnected();
    }

    private String transportLabelFor(BtConnectionManager activeTx) {
        return activeTx == meshBtManager ? "MeshCore" : "UV-PRO";
    }

    private void appendTransmitRouteLog(String action) {
        appendLog(action + " TX route: mode=" + resolveTransmitModeLogLabel()
                + " meshConnected=" + isMeshConnected()
                + " uvproConnected=" + isUvproConnected()
                + " wifiEnabled=" + wifiTransmitEnabled
                + " wifiConnected=" + isWifiConnected());
    }

    private void appendTransmitBlockedLog(String action, BtConnectionManager activeTx) {
        appendLog(action + " not sent — " + explainTransmitBlock(activeTx));
    }

    private String explainTransmitBlock(BtConnectionManager activeTx) {
        if (!isUvproConnected() && !isMeshConnected()) {
            if (wifiTransmitEnabled && cotBridge != null
                    && cotBridge.canSendPingOverWifiNetwork()) {
                return "UV-PRO and MeshCore not connected; use ATAK WiFi transmit";
            }
            if (wifiTransmitEnabled) {
                return "UV-PRO and MeshCore not connected; WiFi/TAK endpoint unavailable";
            }
            return "UV-PRO and MeshCore not connected";
        }
        if (!meshTransmitEnabled && !uvproTransmitEnabled) {
            return formatLinkStatusSummary()
                    + "; enable ATAK MeshCore or ATAK UV-PRO transmit";
        }
        if (activeTx != null && !activeTx.isConnected()) {
            String selected = meshTransmitEnabled ? "MeshCore" : "UV-PRO";
            return formatLinkStatusSummary()
                    + "; ATAK " + selected + " transmit selected but " + selected + " not connected";
        }
        return formatLinkStatusSummary() + "; no connected transmit route";
    }

    private void appendLog(String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss",
                Locale.US);
        String line = sdf.format(new Date()) + " " + message;

        logLines.addLast(line);
        while (logLines.size() > MAX_LOG_LINES) {
            logLines.removeFirst();
        }

        refreshLogView();
        Log.d(TAG, message);
    }

    private void refreshTxPowerFromRadioAsync() {
        if (radioControlManager == null) {
            return;
        }
        new Thread(() -> {
            int level = radioControlManager.readTxPowerLevel();
            getMapView().post(() -> {
                currentTxPowerLevel = level;
                updateTxPowerButtonUi();
            });
        }, "uvpro-read-tx-power").start();
    }

    private void refreshUvproBatteryAsync() {
        if (radioControlManager == null || !btManager.isConnected()) {
            return;
        }
        new Thread(() -> {
            int percent = radioControlManager.readBatteryPercent();
            getMapView().post(() -> {
                uvproBatteryPercent = percent;
                updateUvproBatteryUi(percent);
            });
        }, "uvpro-read-battery").start();
    }

    private void updateUvproBatteryUi(int batteryPercent) {
        boolean show = btManager.isConnected() && batteryPercent >= 0;
        if (uvproBatteryIcon != null) {
            uvproBatteryIcon.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (uvproBatteryPct != null) {
            if (show) {
                uvproBatteryPct.setText(batteryPercent + "%");
                uvproBatteryPct.setVisibility(View.VISIBLE);
            } else {
                uvproBatteryPct.setText("");
                uvproBatteryPct.setVisibility(View.GONE);
            }
        }
    }

    private void scheduleUvproBatteryPoll() {
        MapView mv = getMapView();
        if (mv == null || !btManager.isConnected()) {
            return;
        }
        mv.removeCallbacks(uvproBatteryPollRunnable);
        mv.postDelayed(uvproBatteryPollRunnable, UVPRO_BATTERY_POLL_INTERVAL_MS);
    }

    private void stopUvproBatteryPoll() {
        MapView mv = getMapView();
        if (mv != null) {
            mv.removeCallbacks(uvproBatteryPollRunnable);
        }
    }

    private void cycleTxPower() {
        if (radioControlManager == null || !btManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to the radio first.", Toast.LENGTH_SHORT).show();
            return;
        }
        final int next = (currentTxPowerLevel + 1) % 3;
        appendLog("Setting TX power...");
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult result =
                    radioControlManager.setTxPowerLevel(next);
            getMapView().post(() -> {
                appendLog(result.message);
                Toast.makeText(getMapView().getContext(), result.message,
                        result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                refreshTxPowerFromRadioAsync();
            });
        }, "uvpro-set-tx-power").start();
    }

    private void updateTxPowerButtonUi() {
        if (btnTxPower == null) {
            return;
        }
        boolean connected = btManager.isConnected();
        btnTxPower.setEnabled(connected);
        btnTxPower.setText("TX Power "
                + UVProRadioControlManager.txPowerLabel(currentTxPowerLevel));
        btnTxPower.setBackgroundTintList(null);
        btnTxPower.setBackground(buildVfoButtonBackground(
                COLOR_PILL_BUTTON_PRIMARY,
                COLOR_CHANNEL_SECTION_STROKE,
                CHANNEL_SECTION_STROKE_DP));
    }

    private void updateSelectedRepeaterUi() {
        if (selectedRepeaterText == null || btnLoadSelectedRepeater == null) {
            return;
        }
        UVProRadioControlManager.RepeaterSpec spec =
                radioControlManager != null ? radioControlManager.getSelectedRepeater() : null;
        if (spec == null) {
            selectedRepeaterText.setText("None selected");
            btnLoadSelectedRepeater.setEnabled(false);
            setRepeaterLoadArmed(false, null, null);
            return;
        }
        if (repeaterLoadArmed && repeaterLoadArmedUid != null
                && spec.sourceUid != null
                && !repeaterLoadArmedUid.equals(spec.sourceUid)) {
            setRepeaterLoadArmed(false, null, null);
        }
        selectedRepeaterText.setText(String.format(
                Locale.US, "%s (RX %.5f / TX %.5f)",
                spec.name, spec.rxFreqMHz, spec.txFreqMHz));
        btnLoadSelectedRepeater.setEnabled(true);
        updateLoadSelectedRepeaterButtonUi();
    }

    private void refreshChannelGridAsync() {
        refreshChannelGridAsync(false, -1);
    }

    private void refreshChannelGridAsync(int forceRefreshChannelId) {
        refreshChannelGridAsync(false, forceRefreshChannelId);
    }

    private void refreshChannelGridFullAsync() {
        refreshChannelGridAsync(true, -1);
    }

    private void refreshChannelGridAsync(boolean fullSnapshot) {
        refreshChannelGridAsync(fullSnapshot, -1);
    }

    private void refreshChannelGridAsync(boolean fullSnapshot, int forceRefreshChannelId) {
        if (radioControlManager == null || channelsGrid == null) {
            return;
        }
        if (!snapshotReadInFlight.compareAndSet(false, true)) {
            snapshotRefreshPending.set(true);
            if (fullSnapshot) {
                snapshotFullRefreshPending.set(true);
            }
            return;
        }
        new Thread(() -> {
            int targetGroup = currentChannelGroup;
            final int finalTargetGroup = targetGroup;
            UVProRadioControlManager.RadioControlSnapshot snapshot =
                    radioControlManager.readSnapshotForGroup(finalTargetGroup, 30);
            getMapView().post(() -> {
                try {
                    // Snapshot reads are not authoritative for selected group UI state.
                    // Keep button state driven by explicit user/group-sync actions only.
                    renderChannelGrid(snapshot);
                } finally {
                    snapshotReadInFlight.set(false);
                    if (snapshotRefreshPending.compareAndSet(true, false)) {
                        boolean runFull = snapshotFullRefreshPending.getAndSet(false);
                        refreshChannelGridAsync(runFull);
                    }
                }
            });
        }, "uvpro-read-channels").start();
    }

    private void rerenderGridFromLastSnapshot() {
        if (lastSnapshot != null) {
            renderChannelGrid(lastSnapshot);
            return;
        }
        updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                lastDualWatchEnabled, txVfoB, lastHasRxFocus);
    }

    private void renderChannelGrid(UVProRadioControlManager.RadioControlSnapshot snapshot) {
        if (channelsGrid == null) {
            return;
        }

        if (snapshot == null) {
            // Keep last known UI state on transient read failures while connected.
            // This avoids false flips like "dual watch off" when a single poll misses.
            if (btManager.isConnected()) {
                updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                        lastDualWatchEnabled, txVfoB, lastHasRxFocus);
                if (lastSnapshot != null) {
                    updateReceiveRssiUi(lastSnapshot.receiveRssi);
                }
                return;
            }
            channelsGrid.removeAllViews();
            // If actually disconnected, clear to baseline.
            if (switchDualWatch != null) {
                dualWatchProgrammaticUpdate = true;
                switchDualWatch.setChecked(false);
                dualWatchProgrammaticUpdate = false;
                switchDualWatch.setEnabled(false);
                switchDualWatch.setText("");
            }
            lastSnapshot = null;
            lastChannelA = -1;
            lastChannelB = -1;
            lastDigitalChannel = -1;
            lastDualWatchEnabled = false;
            lastHasRxFocus = false;
            channelTargetDigital = false;
            selectedTarget = TARGET_A;
            lastAnalogTarget = TARGET_A;
            activeVfoB = false;
            txVfoB = false;
            if (switchDigitalEdit != null) {
                switchDigitalEdit.setChecked(false);
            }
            updateVfoButtons(-1, -1, -1, false, false, false);
            updateReceiveRssiUi(-1);
            return;
        }
        channelsGrid.removeAllViews();
        lastSnapshot = snapshot;

        if (switchDualWatch != null) {
            switchDualWatch.setEnabled(true);
            dualWatchProgrammaticUpdate = true;
            boolean dualWatchDisplay = snapshot.dualWatchEnabled;
            if (System.currentTimeMillis() - dualWatchWriteTimestamp < 5000) {
                // Radio hasn't settled yet after our write; hold the optimistic value
                dualWatchDisplay = dualWatchWriteValue;
            }
            switchDualWatch.setChecked(dualWatchDisplay);
            dualWatchProgrammaticUpdate = false;
            switchDualWatch.setText("");
        }

        txVfoB = snapshot.dualWatchEnabled && snapshot.activeVfoB;
        if (!snapshot.dualWatchEnabled && selectedTarget == TARGET_B) {
            selectedTarget = TARGET_A;
            channelTargetDigital = false;
            activeVfoB = false;
            lastAnalogTarget = TARGET_A;
        } else if (selectedTarget == TARGET_A) {
            activeVfoB = false;
            channelTargetDigital = false;
            lastAnalogTarget = TARGET_A;
        } else if (selectedTarget == TARGET_B) {
            activeVfoB = true;
            channelTargetDigital = false;
            lastAnalogTarget = TARGET_B;
        } else {
            if (!isDigitalEditArmed()) {
                restoreAnalogEditTarget();
            } else {
                channelTargetDigital = true;
            }
        }
        long now = System.currentTimeMillis();
        int effectiveChannelA = (pendingChannelA >= 0 && now - pendingChannelATimestamp < 5000)
                ? pendingChannelA : snapshot.channelA;
        int effectiveChannelB = (pendingChannelB >= 0 && now - pendingChannelBTimestamp < 5000)
                ? pendingChannelB : snapshot.channelB;
        int effectiveDigitalChannel = (pendingDigitalChannel >= 0 && now - pendingDigitalChannelTimestamp < 5000)
                ? pendingDigitalChannel : snapshot.digitalChannelId;

        lastChannelA = snapshot.channelA;
        lastChannelB = snapshot.channelB;
        lastDigitalChannel = snapshot.digitalChannelId;
        lastDualWatchEnabled = snapshot.dualWatchEnabled;
        lastHasRxFocus = snapshot.currentChannelId >= 0;
        updateVfoButtons(effectiveChannelA, effectiveChannelB, effectiveDigitalChannel,
                snapshot.dualWatchEnabled, txVfoB, snapshot.currentChannelId >= 0);
        updateReceiveRssiUi(snapshot.receiveRssi);

        // Sync TX power button from the active channel's stored per-channel power level.
        int activeChId = (selectedTarget == TARGET_B && snapshot.dualWatchEnabled)
                ? effectiveChannelB : effectiveChannelA;
        if (snapshot.channels != null) {
            for (UVProRadioControlManager.ChannelSummary ch : snapshot.channels) {
                if (ch != null && ch.channelId == activeChId) {
                    currentTxPowerLevel = ch.txPowerLevel;
                    break;
                }
            }
        }
        updateTxPowerButtonUi();

        for (UVProRadioControlManager.ChannelSummary channel : snapshot.channels) {
            if (channel == null) {
                continue;
            }
            Button chip = new Button(getMapView().getContext());
            chip.setAllCaps(false);
            chip.setTextSize(10f);
            chip.setMinHeight(0);
            chip.setMinimumHeight(0);
            chip.setPadding(dip(getMapView().getContext(), 4),
                    dip(getMapView().getContext(), 3),
                    dip(getMapView().getContext(), 4),
                    dip(getMapView().getContext(), 3));

            String name = (channel.name == null || channel.name.isEmpty())
                    ? "--" : channel.name;
            chip.setText(String.format(
                    Locale.US,
                    "%02d %s\n%.5f",
                    displayChannelNumber(channel.channelId),
                    name,
                    channel.rxFreqMHz));

            boolean activeDigital = selectedTarget == TARGET_DIGITAL;
            boolean activeA = selectedTarget == TARGET_A;
            boolean activeB = selectedTarget == TARGET_B && snapshot.dualWatchEnabled;
            boolean isA = channel.channelId == effectiveChannelA;
            // Only show B assignment in the grid when dual watch is actually enabled.
            boolean isB = snapshot.dualWatchEnabled && channel.channelId == effectiveChannelB;
            boolean isDigital = effectiveDigitalChannel >= 0
                    && channel.channelId == effectiveDigitalChannel;

            int bgColor = 0xFF3D3D3D;
            if (isB) {
                bgColor = activeB ? COLOR_B_ACTIVE : COLOR_B_SUBDUED;
            }
            if (isA) {
                bgColor = activeA ? COLOR_A_ACTIVE : COLOR_A_SUBDUED;
            }
            if (isDigital) {
                bgColor = activeDigital ? COLOR_DIGITAL_ACTIVE : COLOR_DIGITAL_SUBDUED;
            }
            // If multiple roles map to same channel, keep selected active role dominant,
            // but preserve B (green) when no active override is selected.
            if (isB && !activeA && !activeDigital) {
                bgColor = activeB ? COLOR_B_ACTIVE : COLOR_B_SUBDUED;
            } else if (isA && activeA) {
                bgColor = COLOR_A_ACTIVE;
            } else if (isB) {
                bgColor = COLOR_B_ACTIVE;
            } else if (isDigital && activeDigital) {
                bgColor = COLOR_DIGITAL_ACTIVE;
            }

            boolean isSelectedEditChannel =
                    (selectedTarget == TARGET_A && isA)
                            || (selectedTarget == TARGET_B && snapshot.dualWatchEnabled && isB);
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setShape(GradientDrawable.RECTANGLE);
            chipBg.setCornerRadius(dip(getMapView().getContext(), 6));
            chipBg.setColor(bgColor);
            chipBg.setStroke(
                    dip(getMapView().getContext(), isSelectedEditChannel ? EDIT_SELECTION_STROKE_DP : 1),
                    isSelectedEditChannel ? COLOR_EDIT_SELECTION_BORDER : 0x55333333);
            chip.setBackground(chipBg);
            chip.setTextColor(0xFFFFFFFF);
            chip.setOnClickListener(v -> applyChannelSelection(channel.channelId));
            chip.setOnLongClickListener(v -> {
                showChannelProgramDialog(channel);
                return true;
            });

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dip(getMapView().getContext(), 2),
                    dip(getMapView().getContext(), 2),
                    dip(getMapView().getContext(), 2),
                    dip(getMapView().getContext(), 2));
            channelsGrid.addView(chip, lp);
        }
    }

    private void applyDualWatch(boolean enabled) {
        if (radioControlManager == null) {
            return;
        }
        appendLog("Updating dual watch...");
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult result =
                    radioControlManager.setDualWatchEnabled(enabled);
            getMapView().post(() -> {
                appendLog(result.message);
                Toast.makeText(getMapView().getContext(),
                        result.message,
                        result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                if (result.success) {
                    lastDualWatchEnabled = enabled;
                    dualWatchWriteTimestamp = System.currentTimeMillis();
                    dualWatchWriteValue = enabled;
                    if (!enabled && selectedTarget == TARGET_B) {
                        selectedTarget = TARGET_A;
                        activeVfoB = false;
                        lastAnalogTarget = TARGET_A;
                    }
                    rerenderGridFromLastSnapshot();
                }
            });
        }, "uvpro-dual-watch").start();
    }

    private void applyChannelSelection(int channelId) {
        if (radioControlManager == null) {
            return;
        }
        if (repeaterLoadArmed) {
            loadSelectedRepeaterToRadio(channelId);
            return;
        }
        if (digitalOnlyArmed) {
            applyDigitalOnlyToChannel(channelId);
            return;
        }
        if (channelTargetDigital) {
            appendLog(String.format(Locale.US,
                    "Setting Digital to CH%02d...", displayChannelNumber(channelId)));
            new Thread(() -> {
                UVProRadioControlManager.ProgramResult result =
                        radioControlManager.setDigitalChannel(channelId);
                getMapView().post(() -> {
                    appendLog(result.message);
                    if (result.success) {
                        lastDigitalChannel = channelId;
                        pendingDigitalChannel = channelId;
                        pendingDigitalChannelTimestamp = System.currentTimeMillis();
                        if (switchDigitalEdit != null) {
                            switchDigitalEdit.setChecked(false);
                        }
                        restoreAnalogEditTarget();
                        rerenderGridFromLastSnapshot();
                    } else {
                        Toast.makeText(getMapView().getContext(),
                                result.message, Toast.LENGTH_LONG).show();
                    }
                });
            }, "uvpro-set-digital-channel").start();
            return;
        }
        boolean targetB = activeVfoB;
        appendLog(String.format(Locale.US,
                "Setting %s to CH%02d...",
                targetB ? "VFO-B" : "VFO-A",
                displayChannelNumber(channelId)));
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult result =
                    radioControlManager.setWatchChannel(channelId, targetB);
            getMapView().post(() -> {
                appendLog(result.message);
                if (result.success) {
                    if (targetB) {
                        lastChannelB = channelId;
                        pendingChannelB = channelId;
                        pendingChannelBTimestamp = System.currentTimeMillis();
                    } else {
                        lastChannelA = channelId;
                        pendingChannelA = channelId;
                        pendingChannelATimestamp = System.currentTimeMillis();
                    }
                    rerenderGridFromLastSnapshot();
                } else {
                    Toast.makeText(getMapView().getContext(),
                            result.message, Toast.LENGTH_LONG).show();
                }
            });
        }, "uvpro-set-channel").start();
    }

    private void setActiveVfo(boolean useVfoB) {
        if (radioControlManager == null) {
            return;
        }
        if (useVfoB && btnVfoB != null && btnVfoB.getVisibility() != View.VISIBLE) {
            return;
        }
        appendLog(useVfoB ? "Switching active side to VFO-B..." : "Switching active side to VFO-A...");
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult result = radioControlManager.setActiveVfo(useVfoB);
            getMapView().post(() -> {
                appendLog(result.message);
                if (result.success) {
                    txVfoB = useVfoB;
                    rerenderGridFromLastSnapshot();
                } else {
                    Toast.makeText(getMapView().getContext(),
                            result.message, Toast.LENGTH_LONG).show();
                }
            });
        }, "uvpro-set-active-vfo").start();
    }

    private void applyTxSelection(boolean useVfoB) {
        if (useVfoB && (!lastDualWatchEnabled || (btnVfoB != null && btnVfoB.getVisibility() != View.VISIBLE))) {
            return;
        }
        channelTargetDigital = false;
        selectedTarget = useVfoB ? TARGET_B : TARGET_A;
        lastAnalogTarget = selectedTarget;
        activeVfoB = useVfoB;
        setActiveVfo(useVfoB);
    }

    private void updateVfoButtons(int channelA, int channelB, int digitalChannel,
                                  boolean dualWatchEnabled, boolean txOnB,
                                  boolean hasRxFocus) {
        if (btnVfoA == null) {
            return;
        }
        final int subduedStroke = COLOR_CHANNEL_SECTION_STROKE;
        final boolean activeDigital = selectedTarget == TARGET_DIGITAL;
        boolean activeA = selectedTarget == TARGET_A;
        boolean activeB = selectedTarget == TARGET_B;
        if (!dualWatchEnabled && activeB) {
            activeB = false;
            activeA = true;
        }

        String aText = channelA >= 0
                ? String.format(Locale.US, "A: CH %02d", displayChannelNumber(channelA))
                : "A: CH --";
        btnVfoA.setText(buildVfoLabelWithTxHighlight(aText, !dualWatchEnabled || !txOnB));
        btnVfoA.setSingleLine(true);
        btnVfoA.setMaxLines(1);
        GradientDrawable aBg = buildVfoButtonBackground(
                activeA ? COLOR_A_ACTIVE : COLOR_A_SUBDUED,
                activeA ? COLOR_CHANNEL_SECTION_STROKE : subduedStroke,
                activeA ? CHANNEL_SECTION_STROKE_DP : CHANNEL_SECTION_STROKE_DP);
        btnVfoA.setBackground(aBg);
        btnVfoA.setBackgroundTintList(null);
        btnVfoA.setTextColor(0xFFFFFFFF);
        btnVfoA.setAlpha(activeA ? 1.0f : 0.72f);
        Button activeButton = activeA ? btnVfoA : null;
        GradientDrawable activeDrawable = activeA ? aBg : null;

        if (btnVfoB != null) {
            if (dualWatchEnabled) {
                btnVfoB.setVisibility(View.VISIBLE);
                String bText = channelB >= 0
                        ? String.format(Locale.US, "B: CH %02d", displayChannelNumber(channelB))
                        : "B: CH --";
                btnVfoB.setText(buildVfoLabelWithTxHighlight(bText, txOnB));
                btnVfoB.setSingleLine(true);
                btnVfoB.setMaxLines(1);
                GradientDrawable bBg = buildVfoButtonBackground(
                        activeB ? COLOR_B_ACTIVE : COLOR_B_SUBDUED,
                        activeB ? COLOR_CHANNEL_SECTION_STROKE : subduedStroke,
                        activeB ? CHANNEL_SECTION_STROKE_DP : CHANNEL_SECTION_STROKE_DP);
                btnVfoB.setBackground(bBg);
                btnVfoB.setBackgroundTintList(null);
                btnVfoB.setTextColor(0xFFFFFFFF);
                btnVfoB.setAlpha(activeB ? 1.0f : 0.72f);
                if (activeB) {
                    activeButton = btnVfoB;
                    activeDrawable = bBg;
                }
            } else {
                btnVfoB.setVisibility(View.GONE);
            }
        }
        if (btnDigital != null) {
            boolean digitalArmed = isDigitalEditArmed();
            String dText = digitalChannel >= 0
                    ? String.format(Locale.US, "Digital CH %02d", digitalChannel + 1)
                    : "Digital";
            btnDigital.setText(dText);
            GradientDrawable dBg = buildVfoButtonBackground(
                    (digitalArmed && activeDigital) ? COLOR_DIGITAL_ACTIVE : COLOR_DIGITAL_SUBDUED,
                    COLOR_CHANNEL_SECTION_STROKE,
                    CHANNEL_SECTION_STROKE_DP);
            btnDigital.setBackground(dBg);
            btnDigital.setBackgroundTintList(null);
            btnDigital.setTextColor(0xFFFFFFFF);
            btnDigital.setEnabled(digitalArmed);
            btnDigital.setAlpha(digitalArmed ? (activeDigital ? 1.0f : 0.72f) : 0.45f);
        }
        // Keep pulse visible on selected A/B edit target (never Digital),
        // independent of RX-focus so operators always see active control focus.
        updateActiveVfoPulse(activeButton, activeDrawable, !activeDigital);
    }

    private boolean isDigitalEditArmed() {
        return switchDigitalEdit != null && switchDigitalEdit.isChecked();
    }

    private void restoreAnalogEditTarget() {
        if (lastAnalogTarget == TARGET_B && lastDualWatchEnabled) {
            selectedTarget = TARGET_B;
            activeVfoB = true;
        } else {
            selectedTarget = TARGET_A;
            activeVfoB = false;
            lastAnalogTarget = TARGET_A;
        }
        channelTargetDigital = false;
    }

    private void updateDigitalEditGuardUi() {
        if (btnDigital == null) {
            return;
        }
        if (!isDigitalEditArmed() && selectedTarget == TARGET_DIGITAL) {
            restoreAnalogEditTarget();
        }
        updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                lastDualWatchEnabled, txVfoB, lastHasRxFocus);
    }

    private CharSequence buildVfoLabelWithTxHighlight(String base, boolean isTx) {
        if (!isTx) {
            return base;
        }
        // Keep TX as a single replacement span token so it cannot wrap to the next line.
        final String suffix = "TX";
        SpannableStringBuilder sb = new SpannableStringBuilder(base).append(suffix);
        int txStart = sb.length() - 2;
        int txEnd = sb.length();
        sb.setSpan(new TxBadgeSpan(),
                txStart, txEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static class TxBadgeSpan extends ReplacementSpan {
        private static final float TEXT_SCALE = 0.97f; // ~10% smaller than previous 1.08
        private static final float H_PADDING_FACTOR = 0.216f;
        private static final float V_PADDING_FACTOR = 0.108f;
        private static final float LEADING_OFFSET_FACTOR = 0.30f; // visually ~2 spaces to the right
        private static final float CORNER_RADIUS_FACTOR = 0.22f;
        private static final float STROKE_WIDTH_FACTOR = 0.12f;

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            Paint p = new Paint(paint);
            p.setTextSize(p.getTextSize() * TEXT_SCALE);
            String tx = text.subSequence(start, end).toString();
            float textW = p.measureText(tx);
            float hPad = p.getTextSize() * H_PADDING_FACTOR;
            float leadingOffset = p.getTextSize() * LEADING_OFFSET_FACTOR;
            float totalW = leadingOffset + textW + (hPad * 2f);
            if (fm != null) {
                Paint.FontMetricsInt pfm = p.getFontMetricsInt();
                int vPad = (int) (p.getTextSize() * V_PADDING_FACTOR);
                fm.ascent = pfm.ascent - vPad;
                fm.descent = pfm.descent + vPad;
                fm.top = fm.ascent;
                fm.bottom = fm.descent;
            }
            return (int) Math.ceil(totalW);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            Paint p = new Paint(paint);
            p.setAntiAlias(true);
            p.setTextSize(p.getTextSize() * TEXT_SCALE);

            String tx = text.subSequence(start, end).toString();
            float textW = p.measureText(tx);
            float hPad = p.getTextSize() * H_PADDING_FACTOR;
            float vPad = p.getTextSize() * V_PADDING_FACTOR;
            float leadingOffset = p.getTextSize() * LEADING_OFFSET_FACTOR;
            float strokeW = Math.max(1f, p.getTextSize() * STROKE_WIDTH_FACTOR);

            Paint.FontMetrics fm = p.getFontMetrics();
            float txtAscent = y + fm.ascent;
            float txtDescent = y + fm.descent;

            float left = x + leadingOffset;
            float right = left + textW + (hPad * 2f);
            float boxTop = txtAscent - vPad;
            float boxBottom = txtDescent + vPad;
            float radius = p.getTextSize() * CORNER_RADIUS_FACTOR;

            RectF box = new RectF(left, boxTop, right, boxBottom);

            // Red box with white stroke.
            p.setStyle(Paint.Style.FILL);
            p.setColor(COLOR_TX_HIGHLIGHT);
            canvas.drawRoundRect(box, radius, radius, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(strokeW);
            p.setColor(COLOR_TX_STROKE);
            canvas.drawRoundRect(box, radius, radius, p);

            float txX = left + hPad;
            // White stroke around text.
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(strokeW);
            p.setColor(COLOR_TX_STROKE);
            canvas.drawText(tx, txX, y, p);
            // Red fill text.
            p.setStyle(Paint.Style.FILL);
            p.setColor(COLOR_TX_HIGHLIGHT);
            canvas.drawText(tx, txX, y, p);
        }
    }

    private GradientDrawable buildVfoButtonBackground(int fillColor, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dip(getMapView().getContext(), PILL_CORNER_RADIUS_DP));
        d.setColor(fillColor);
        d.setStroke(dip(getMapView().getContext(), strokeDp), strokeColor);
        return d;
    }

    private void applyPillButtonBackground(Button button, int fillColor) {
        if (button == null) {
            return;
        }
        button.setBackgroundTintList(null);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dip(button.getContext(), PILL_CORNER_RADIUS_DP));
        d.setColor(fillColor);
        button.setBackground(d);
    }

    private void updateActiveVfoPulse(Button activeButton,
                                      GradientDrawable activeDrawable,
                                      boolean shouldPulse) {
        if (!shouldPulse || activeButton == null || activeDrawable == null) {
            stopActiveVfoPulse();
            return;
        }
        if (activeVfoPulseAnimator != null && pulsingVfoButton == activeButton
                && activeVfoPulseAnimator.isRunning()) {
            return;
        }
        stopActiveVfoPulse();
        pulsingVfoButton = activeButton;
        pulsingVfoDrawable = activeDrawable;
        activeVfoPulseAnimator = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                0x66FF9800,
                0xFFFFB74D);
        activeVfoPulseAnimator.setDuration(900L);
        activeVfoPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        activeVfoPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        activeVfoPulseAnimator.addUpdateListener(animation -> {
            if (pulsingVfoDrawable == null) {
                return;
            }
            int color = (Integer) animation.getAnimatedValue();
            pulsingVfoDrawable.setStroke(dip(getMapView().getContext(), EDIT_SELECTION_STROKE_DP), color);
        });
        activeVfoPulseAnimator.start();
    }

    private void stopActiveVfoPulse() {
        if (activeVfoPulseAnimator != null) {
            activeVfoPulseAnimator.cancel();
            activeVfoPulseAnimator = null;
        }
        pulsingVfoButton = null;
        pulsingVfoDrawable = null;
    }

    private void showChannelProgramDialog(UVProRadioControlManager.ChannelSummary channel) {
        if (channel == null) {
            return;
        }
        Context ctx = getMapView().getContext();

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int p = dip(ctx, 12);
        layout.setPadding(p, p, p, p);
        scroll.addView(layout);

        EditText editName = new EditText(ctx);
        editName.setHint("Name (max 10)");
        editName.setSingleLine(true);
        editName.setText(channel.name == null ? "" : channel.name);
        layout.addView(editName);

        EditText editRx = new EditText(ctx);
        editRx.setHint("RX Frequency MHz (e.g. 146.940)");
        editRx.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (channel.rxFreqMHz > 0) {
            editRx.setText(String.format(Locale.US, "%.5f", channel.rxFreqMHz));
        }
        layout.addView(editRx);

        EditText editTx = new EditText(ctx);
        editTx.setHint("TX Frequency MHz");
        editTx.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (channel.txFreqMHz > 0) {
            editTx.setText(String.format(Locale.US, "%.5f", channel.txFreqMHz));
        }
        layout.addView(editTx);

        EditText editTxTone = new EditText(ctx);
        editTxTone.setHint("TX Tone (blank, 100.0, D023)");
        editTxTone.setSingleLine(true);
        String txToneText = formatToneForInput(channel.txTone);
        if (!txToneText.isEmpty()) {
            editTxTone.setText(txToneText);
        }
        layout.addView(editTxTone);

        EditText editRxTone = new EditText(ctx);
        editRxTone.setHint("RX Tone (blank, 100.0, D023)");
        editRxTone.setSingleLine(true);
        String rxToneText = formatToneForInput(channel.rxTone);
        if (!rxToneText.isEmpty()) {
            editRxTone.setText(rxToneText);
        }
        layout.addView(editRxTone);

        EditText editSquelch = new EditText(ctx);
        editSquelch.setHint("Squelch 0-9 (optional)");
        editSquelch.setInputType(InputType.TYPE_CLASS_NUMBER);
        layout.addView(editSquelch);

        CheckBox cbScan = new CheckBox(ctx);
        cbScan.setText("Scan enabled");
        cbScan.setChecked(channel.scanEnabled);
        layout.addView(cbScan);

        CheckBox cbMute = new CheckBox(ctx);
        cbMute.setText("Mute channel audio");
        cbMute.setChecked(channel.muted);
        layout.addView(cbMute);

        CheckBox cbWide = new CheckBox(ctx);
        cbWide.setText("Wide bandwidth");
        cbWide.setChecked(channel.wideBandwidth);
        layout.addView(cbWide);

        TextView tvPower = new TextView(ctx);
        tvPower.setText("TX Power");
        layout.addView(tvPower);

        Spinner spinnerPower = new Spinner(ctx);
        ArrayAdapter<String> powerAdapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item,
                new String[]{"Low", "Medium", "High"});
        powerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPower.setAdapter(powerAdapter);
        spinnerPower.setSelection(channel.txPowerLevel);
        layout.addView(spinnerPower);

        new AlertDialog.Builder(ctx)
                .setTitle(String.format(Locale.US, "Program CH%02d",
                        displayChannelNumber(channel.channelId)))
                .setView(scroll)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = editName.getText().toString().trim();
                    String rxStr = editRx.getText().toString().trim();
                    String txStr = editTx.getText().toString().trim();
                    String txToneStr = editTxTone.getText().toString().trim();
                    String rxToneStr = editRxTone.getText().toString().trim();
                    String sqStr = editSquelch.getText().toString().trim();

                    double rx;
                    double tx;
                    try {
                        rx = Double.parseDouble(rxStr);
                        tx = Double.parseDouble(txStr);
                    } catch (Exception e) {
                        Toast.makeText(ctx, "Invalid RX/TX frequency.", Toast.LENGTH_LONG).show();
                        return;
                    }

                    Object txTone = parseToneInput(txToneStr);
                    Object rxTone = parseToneInput(rxToneStr);
                    if (!txToneStr.isEmpty() && txTone == null) {
                        Toast.makeText(ctx, "Invalid TX tone format.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!rxToneStr.isEmpty() && rxTone == null) {
                        Toast.makeText(ctx, "Invalid RX tone format.", Toast.LENGTH_LONG).show();
                        return;
                    }

                    int sq = -1;
                    if (!sqStr.isEmpty()) {
                        try {
                            sq = Integer.parseInt(sqStr);
                        } catch (Exception e) {
                            Toast.makeText(ctx, "Invalid squelch value.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (sq < 0 || sq > 9) {
                            Toast.makeText(ctx, "Squelch must be 0-9.", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }

                    UVProRadioControlManager.ManualChannelSpec spec =
                            new UVProRadioControlManager.ManualChannelSpec(
                                    name,
                                    rx,
                                    tx,
                                    txTone,
                                    rxTone,
                                    cbScan.isChecked(),
                                    cbMute.isChecked(),
                                    spinnerPower.getSelectedItemPosition(),
                                    cbWide.isChecked(),
                                    sq
                            );

                    appendLog(String.format(Locale.US, "Programming CH%02d...",
                            displayChannelNumber(channel.channelId)));
                    new Thread(() -> {
                        UVProRadioControlManager.ProgramResult result =
                                radioControlManager.programManualChannel(channel.channelId, spec);
                        getMapView().post(() -> {
                            appendLog(result.message);
                            Toast.makeText(ctx, result.message,
                                    result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                            if (result.success) {
                                refreshChannelGridAsync(channel.channelId);
                            }
                        });
                    }, "uvpro-program-manual-channel").start();
                })
                .setNegativeButton("Cancel", (DialogInterface dialog, int which) -> { })
                .show();
    }

    private Object parseToneInput(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        if (t.isEmpty() || "none".equalsIgnoreCase(t)) {
            return null;
        }
        if (t.startsWith("D") || t.startsWith("d")) {
            try {
                return Integer.parseInt(t.substring(1).replaceAll("[^0-9]", ""));
            } catch (Exception e) {
                return null;
            }
        }
        try {
            return Double.parseDouble(t.replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private String formatToneForInput(Object tone) {
        if (tone == null) {
            return "";
        }
        if (tone instanceof Double) {
            return String.format(Locale.US, "%.1f", (Double) tone);
        }
        if (tone instanceof Integer) {
            int v = (Integer) tone;
            if (v > 0 && v < 1000) {
                return String.format(Locale.US, "D%03d", v);
            }
            return String.valueOf(v);
        }
        return String.valueOf(tone);
    }

    private void onDigitalOnlyModeClick() {
        if (!btManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to the radio first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (digitalOnlyActive) {
            Toast.makeText(getMapView().getContext(),
                    "Long press to disable KISS single-frequency mode.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (digitalOnlyArmed) {
            setDigitalOnlyArmed(false);
            appendLog("Digital only mode cancelled.");
            Toast.makeText(getMapView().getContext(),
                    "Digital only mode cancelled.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (repeaterLoadArmed) {
            setRepeaterLoadArmed(false, null, null);
        }
        setDigitalOnlyArmed(true);
        appendLog("Digital only mode armed. Select a digital channel.");
        Toast.makeText(getMapView().getContext(),
                "Select a channel for digital-only KISS lock.",
                Toast.LENGTH_SHORT).show();
    }

    private void setDigitalOnlyArmed(boolean armed) {
        digitalOnlyArmed = armed;
        updateDigitalOnlyButtonUi();
    }

    private void setDigitalOnlyActive(boolean active, int channelId) {
        digitalOnlyActive = active;
        digitalOnlyChannelId = active ? channelId : -1;
        digitalOnlyArmed = false;
        updateDigitalOnlyButtonUi();
    }

    private void clearDigitalOnlyStateUiOnly() {
        digitalOnlyArmed = false;
        digitalOnlyActive = false;
        digitalOnlyChannelId = -1;
        updateDigitalOnlyButtonUi();
    }

    private void updateDigitalOnlyButtonUi() {
        if (btnDigitalOnlyMode == null) {
            return;
        }
        boolean connected = btManager.isConnected();
        btnDigitalOnlyMode.setEnabled(connected);
        String label;
        if (digitalOnlyActive) {
            label = LABEL_DIGITAL_LONG_PRESS_DISABLE;
        } else if (digitalOnlyArmed) {
            label = LABEL_DIGITAL_SELECT_CHANNEL;
        } else {
            label = LABEL_DIGITAL_ONLY;
        }
        btnDigitalOnlyMode.setText(label);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dip(getMapView().getContext(), PILL_CORNER_RADIUS_DP));
        d.setColor(COLOR_REPEATER_LOAD_FILL);
        d.setStroke(
                dip(getMapView().getContext(), CHANNEL_SECTION_STROKE_DP),
                COLOR_CHANNEL_SECTION_STROKE);
        btnDigitalOnlyMode.setBackgroundTintList(null);
        btnDigitalOnlyMode.setBackground(d);
    }

    private void applyDigitalOnlyToChannel(int channelId) {
        if (radioControlManager == null) {
            appendLog("Radio control unavailable — UV-PRO not connected");
            Toast.makeText(getMapView().getContext(),
                    "Radio control unavailable", Toast.LENGTH_SHORT).show();
            setDigitalOnlyArmed(false);
            return;
        }
        final int selectedChannelId = channelId;
        double freqMHz = resolveChannelRxMHz(selectedChannelId);
        setDigitalOnlyArmed(false);
        appendLog(String.format(Locale.US,
                "Digital only: programming CH%02d and KISS lock...",
                displayChannelNumber(selectedChannelId)));
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult result =
                    radioControlManager.setDigitalChannel(selectedChannelId);
            boolean lockOk = false;
            if (result.success && freqMHz > 0) {
                lockOk = KissRadioFrequencyControl.lockFrequency(
                        btManager, (float) freqMHz);
            }
            final boolean finalLockOk = lockOk;
            final double finalFreq = freqMHz;
            getMapView().post(() -> {
                if (result.success && finalLockOk) {
                    lastDigitalChannel = selectedChannelId;
                    setDigitalOnlyActive(true, selectedChannelId);
                    appendLog(String.format(Locale.US,
                            "Digital only active on CH%02d at %.5f MHz (KISS locked).",
                            displayChannelNumber(selectedChannelId), finalFreq));
                    Toast.makeText(getMapView().getContext(),
                            "Digital only mode active. Long press button to disable.",
                            Toast.LENGTH_LONG).show();
                    rerenderGridFromLastSnapshot();
                } else if (result.success) {
                    appendLog("Digital channel set, but KISS frequency lock failed.");
                    Toast.makeText(getMapView().getContext(),
                            "Channel set; KISS lock failed. Check connection.",
                            Toast.LENGTH_LONG).show();
                } else {
                    appendLog(result.message);
                    Toast.makeText(getMapView().getContext(),
                            result.message, Toast.LENGTH_LONG).show();
                }
            });
        }, "uvpro-digital-only").start();
    }

    private double resolveChannelRxMHz(int channelId) {
        if (lastSnapshot != null && lastSnapshot.channels != null
                && channelId >= 0 && channelId < lastSnapshot.channels.length) {
            UVProRadioControlManager.ChannelSummary ch = lastSnapshot.channels[channelId];
            if (ch != null) {
                if (ch.rxFreqMHz > 0) {
                    return ch.rxFreqMHz;
                }
                if (ch.txFreqMHz > 0) {
                    return ch.txFreqMHz;
                }
            }
        }
        return 0.0;
    }

    private void disableDigitalOnlyMode() {
        new Thread(() -> {
            boolean unlocked = KissRadioFrequencyControl.unlockFrequency(btManager);
            getMapView().post(() -> {
                clearDigitalOnlyStateUiOnly();
                if (unlocked) {
                    appendLog("Digital only / KISS frequency lock disabled.");
                    Toast.makeText(getMapView().getContext(),
                            "KISS single-frequency mode disabled.",
                            Toast.LENGTH_SHORT).show();
                } else {
                    appendLog("KISS unlock failed or radio not connected.");
                    Toast.makeText(getMapView().getContext(),
                            "Could not send KISS unlock.", Toast.LENGTH_SHORT).show();
                }
            });
        }, "uvpro-digital-only-off").start();
    }

    /** Called from {@link BtConnectionManager} before the BT socket is closed. */
    private void releaseDigitalOnlyKissLockSilent() {
        if (!digitalOnlyActive && !KissRadioFrequencyControl.isFrequencyLocked()) {
            return;
        }
        KissRadioFrequencyControl.unlockFrequency(btManager);
        digitalOnlyActive = false;
        digitalOnlyArmed = false;
        digitalOnlyChannelId = -1;
    }

    private void armSelectedRepeaterLoad() {
        if (radioControlManager == null) {
            appendLog("Radio control unavailable — UV-PRO not connected");
            Toast.makeText(getMapView().getContext(),
                    "Radio control unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        if (digitalOnlyArmed) {
            setDigitalOnlyArmed(false);
        }
        if (repeaterLoadArmed) {
            setRepeaterLoadArmed(false, null, null);
            appendLog("Repeater load cancelled.");
            Toast.makeText(getMapView().getContext(),
                    "Repeater load cancelled.", Toast.LENGTH_SHORT).show();
            return;
        }
        UVProRadioControlManager.RepeaterSpec spec = radioControlManager.getSelectedRepeater();
        if (spec == null) {
            appendLog("Select a repeater marker first.");
            Toast.makeText(getMapView().getContext(),
                    "Select a repeater marker first.", Toast.LENGTH_SHORT).show();
            setRepeaterLoadArmed(false, null, null);
            return;
        }
        setRepeaterLoadArmed(true, spec.sourceUid, spec);
        appendLog("Repeater load armed. Select destination channel.");
    }

    private void setRepeaterLoadArmed(boolean armed, String sourceUid,
                                      UVProRadioControlManager.RepeaterSpec spec) {
        repeaterLoadArmed = armed;
        repeaterLoadArmedUid = armed ? sourceUid : null;
        repeaterLoadArmedSpec = armed ? spec : null;
        updateLoadSelectedRepeaterButtonUi();
    }

    private void updateLoadSelectedRepeaterButtonUi() {
        if (btnLoadSelectedRepeater == null) {
            return;
        }
        btnLoadSelectedRepeater.setText(
                repeaterLoadArmed ? LABEL_SELECT_CHANNEL : LABEL_LOAD_REPEATER);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dip(getMapView().getContext(), PILL_CORNER_RADIUS_DP));
        d.setColor(COLOR_REPEATER_LOAD_FILL);
        d.setStroke(
                dip(getMapView().getContext(), CHANNEL_SECTION_STROKE_DP),
                COLOR_CHANNEL_SECTION_STROKE);
        btnLoadSelectedRepeater.setBackgroundTintList(null);
        btnLoadSelectedRepeater.setBackground(d);
    }

    private void loadSelectedRepeaterToRadio(int channelId) {
        if (radioControlManager == null) {
            appendLog("Radio control unavailable — UV-PRO not connected");
            Toast.makeText(getMapView().getContext(),
                    "Radio control unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        final UVProRadioControlManager.RepeaterSpec armedSpec = repeaterLoadArmedSpec;
        setRepeaterLoadArmed(false, null, null);
        if (armedSpec == null) {
            appendLog("No repeater selected to load.");
            Toast.makeText(getMapView().getContext(),
                    "No repeater selected to load.", Toast.LENGTH_SHORT).show();
            return;
        }
        appendLog("Preparing repeater load...");
        new Thread(() -> {
            if (!btManager.isConnected()) {
                appendLog("Radio not connected in plugin; attempting auto-connect...");
                btManager.connectToLastDevice();
                long startMs = System.currentTimeMillis();
                while (!btManager.isConnected()
                        && (System.currentTimeMillis() - startMs) < 8000L) {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (!btManager.isConnected()) {
                getMapView().post(() -> {
                    appendLog("Could not connect to radio. Use Scan & Connect, then retry.");
                    Toast.makeText(getMapView().getContext(),
                            "Radio not connected. Tap Scan & Connect.",
                            Toast.LENGTH_LONG).show();
                });
                return;
            }

            final int selectedChannelId = channelId;
            getMapView().post(() -> appendLog(String.format(
                    Locale.US, "Loading selected repeater to CH%02d...",
                    displayChannelNumber(selectedChannelId))));
            UVProRadioControlManager.ProgramResult result =
                    radioControlManager.programRepeaterAndTune(armedSpec, selectedChannelId);
            getMapView().post(() -> {
                appendLog(result.message);
                Toast.makeText(getMapView().getContext(),
                        result.message,
                        result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                if (result.success) {
                    refreshChannelGridFullAsync();
                    // Some radios apply write+tune in two phases; re-read once more shortly after.
                    rootView.postDelayed(this::refreshChannelGridFullAsync, 650L);
                }
            });
        }, "uvpro-load-repeater").start();
    }

    private void togglePacketTerminalSection() {
        if (packetTerminalSection == null) {
            return;
        }
        boolean show = packetTerminalSection.getVisibility() != View.VISIBLE;
        packetTerminalSection.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            if (packetTerminalReceiver != null) {
                packetTerminalReceiver.attachInlinePanel(packetTerminalSection);
            }
            scheduleScrollToPacketTerminalSection();
        }
        if (btnPacketTerminal != null) {
            btnPacketTerminal.setText(show ? "Hide AX.25 Terminal" : "AX.25 Terminal");
        }
    }

    private void scheduleScrollToPacketTerminalSection() {
        getMapView().postDelayed(this::scrollToPacketTerminalSection, 120L);
        getMapView().postDelayed(this::scrollToPacketTerminalSection, 320L);
    }

    private void scrollToPacketTerminalSection() {
        if (!(rootView instanceof ScrollView) || packetTerminalSection == null) {
            return;
        }
        ScrollView scroll = (ScrollView) rootView;
        scroll.post(() -> {
            int y = 0;
            View cursor = packetTerminalSection;
            while (cursor != null && cursor != scroll) {
                y += cursor.getTop();
                android.view.ViewParent p = cursor.getParent();
                cursor = (p instanceof View) ? (View) p : null;
            }
            y = Math.max(0, y - dip(getMapView().getContext(), 48));
            final int scrollY = y;
            scroll.scrollTo(0, scrollY);
            scroll.post(() -> scroll.smoothScrollTo(0, scrollY));
        });
    }

    private void scheduleScrollToRepeaterLoadSection() {
        // Dropdown/layout animation can delay child measurements; run multiple passes.
        getMapView().postDelayed(this::scrollToRepeaterLoadSection, 120L);
        getMapView().postDelayed(this::scrollToRepeaterLoadSection, 320L);
        getMapView().postDelayed(this::scrollToRepeaterLoadSection, 700L);
        getMapView().postDelayed(this::scrollToRepeaterLoadSection, 1200L);
        getMapView().postDelayed(this::scrollToRepeaterLoadSection, 1800L);
    }

    private void scrollDropdownToView(View target, int topInsetDp) {
        if (!(rootView instanceof ScrollView) || target == null) {
            return;
        }
        ScrollView scroll = (ScrollView) rootView;
        if (scroll.getChildCount() <= 0) {
            return;
        }
        View content = scroll.getChildAt(0);
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        final int inset = dip(ctx, topInsetDp);
        scroll.post(() -> {
            if (target.getHeight() <= 0) {
                return;
            }
            Rect rect = new Rect();
            target.getDrawingRect(rect);
            if (content instanceof ViewGroup) {
                ((ViewGroup) content).offsetDescendantRectToMyCoords(target, rect);
            }
            rect.top = Math.max(0, rect.top - inset);
            scroll.requestChildRectangleOnScreen(content, rect, true);
        });
    }

    private void scrollToRepeaterLoadSection() {
        View target = repeaterLoadScrollAnchor;
        if (target == null) {
            target = btnLoadSelectedRepeater != null
                    ? btnLoadSelectedRepeater : selectedRepeaterText;
        }
        if (target == null) {
            return;
        }
        scrollDropdownToView(target, 12);
        pulseRepeaterLoadButton();
    }

    private void pulseRepeaterLoadButton() {
        if (btnLoadSelectedRepeater == null) {
            return;
        }
        if (repeaterLoadFocusAnimator != null) {
            repeaterLoadFocusAnimator.cancel();
            repeaterLoadFocusAnimator = null;
        }
        btnLoadSelectedRepeater.setAlpha(1f);
        repeaterLoadFocusAnimator = ObjectAnimator.ofFloat(
                btnLoadSelectedRepeater, "alpha",
                1f, 0.50f, 1f);
        repeaterLoadFocusAnimator.setDuration(320L);
        repeaterLoadFocusAnimator.setRepeatCount(2);
        repeaterLoadFocusAnimator.start();
    }

    /** UV-PRO Classic picker / status label (model + radio ID). */
    private String resolveDeviceDisplayName(Context ctx, BluetoothDevice device) {
        return UvProBtDeviceMatcher.formatPickerLabel(device);
    }

    /** MeshCore BLE picker / status label (MeshCore- name or MAC). */
    private String resolveMeshDeviceDisplayName(Context ctx, BluetoothDevice device) {
        if (device == null) {
            return "MeshCore";
        }
        String name = MeshBleDeviceMatcher.resolveName(null, device);
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        try {
            String addr = device.getAddress();
            if (addr != null && !addr.trim().isEmpty()) {
                return addr.trim();
            }
        } catch (Exception ignored) {
        }
        return "MeshCore";
    }

    private android.view.View buildMeshPickerTitle(Context ctx) {
        android.widget.LinearLayout col = new android.widget.LinearLayout(ctx);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dip(ctx, 16);
        col.setPadding(pad, dip(ctx, 12), pad, dip(ctx, 4));

        android.widget.TextView title = new android.widget.TextView(ctx);
        title.setText("Select MeshCore");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        col.addView(title);

        android.widget.TextView hint = new android.widget.TextView(ctx);
        hint.setText(MeshBleDeviceMatcher.pinGuidance());
        hint.setTextColor(0xFFB0B0B0);
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        android.widget.LinearLayout.LayoutParams hp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dip(ctx, 4);
        hint.setLayoutParams(hp);
        col.addView(hint);

        return col;
    }

    private void showMeshDevicePicker() {
        if (meshFoundDevices.isEmpty()) {
            stopMeshConnectButtonPulse(true);
            appendLog("No MeshCore devices found");
            meshBtManager.endScanPickerSession();
            try {
                new AlertDialog.Builder(getMapView().getContext())
                        .setTitle("Scan MeshCore")
                        .setMessage("No MeshCore devices found.\n\n"
                                + "Put the node in pairing mode, then tap Scan and Connect again.")
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            } catch (Exception e) {
                Log.e(TAG, "Could not show empty mesh scan dialog", e);
            }
            updateMeshScanButtonText();
            return;
        }
        Context ctx = getMapView().getContext();

        final int count = meshFoundDevices.size();
        final String[] names = new String[count];
        final int[] dotColors = new int[count];
        final int DOT_UNSEEN = 0xFFAAAAAA;
        final int DOT_AVAILABLE = 0xFF4CAF50;
        final int DOT_BUSY = 0xFFF44336;

        for (int i = 0; i < count; i++) {
            BluetoothDevice dev = meshFoundDevices.get(i);
            names[i] = resolveMeshDeviceDisplayName(ctx, dev);
            dotColors[i] = DOT_UNSEEN;
        }

        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(
                ctx, android.R.layout.select_dialog_singlechoice, names) {
            @Override
            public android.view.View getView(int pos, android.view.View convertView,
                                             android.view.ViewGroup parent) {
                android.view.View v = super.getView(pos, convertView, parent);
                android.widget.TextView tv = v instanceof android.widget.TextView
                        ? (android.widget.TextView) v
                        : v.findViewById(android.R.id.text1);
                if (tv != null) {
                    int color = dotColors[pos];
                    android.graphics.drawable.GradientDrawable dot =
                            new android.graphics.drawable.GradientDrawable();
                    dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    dot.setColor(color);
                    int dp8 = (int) (8 * ctx.getResources().getDisplayMetrics().density);
                    dot.setSize(dp8, dp8);
                    tv.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null);
                    tv.setCompoundDrawablePadding(dp8);
                }
                return v;
            }
        };

        try {
            new AlertDialog.Builder(ctx)
                    .setCustomTitle(buildMeshPickerTitle(ctx))
                    .setAdapter(adapter, (d, which) -> {
                        if (which < 0 || which >= meshFoundDevices.size()) {
                            return;
                        }
                        BluetoothDevice selected = meshFoundDevices.get(which);
                        BluetoothDeviceRegistry.setMeshConnectTargetAddress(ctx,
                                selected.getAddress());
                        appendLog("Connecting MeshCore to " + names[which] + "...");
                        updateMeshScanButtonText();
                        startMeshConnectButtonPulse();
                        meshBtManager.connectUserSelected(selected);
                    })
                    .setNeutralButton("Forget All", (d, w) -> onMeshForgetAllClicked())
                    .setNegativeButton("Cancel", (d, w) -> meshBtManager.endScanPickerSession())
                    .setOnCancelListener(d -> meshBtManager.endScanPickerSession())
                    .show();

            meshBtManager.prepareForAvailabilityProbes();
            for (int i = 0; i < count; i++) {
                final int idx = i;
                BluetoothDevice dev = meshFoundDevices.get(i);
                if (!meshBtManager.isLiveScanDevice(dev)) {
                    dotColors[idx] = DOT_UNSEEN;
                    adapter.notifyDataSetChanged();
                    continue;
                }
                meshBtManager.probeDeviceAvailabilityForPicker(dev, availability ->
                        getMapView().post(() -> {
                            dotColors[idx] = (availability == MeshBtConnectionManager.AVAIL_AVAILABLE)
                                    ? DOT_AVAILABLE : DOT_BUSY;
                            adapter.notifyDataSetChanged();
                        }));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error showing MeshCore picker", e);
            appendLog("Error showing MeshCore picker");
            meshBtManager.endScanPickerSession();
        }
    }

    private void onMeshForgetAllClicked() {
        Context ctx = getMapView().getContext();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        MeshBluetoothForgetAll.Result result =
                MeshBluetoothForgetAll.forgetAll(ctx, adapter);
        appendLog("MeshCore registry cleared (" + result.registryEntriesCleared + " entries).");
        if (result.needsAndroidSettingsReminder()) {
            appendLog("Some devices could not be unpaired automatically. "
                    + "Remove them in Android Bluetooth settings if needed.");
        }
        meshBtManager.endScanPickerSession();
        updateMeshScanButtonText();
    }

    private void showDevicePicker() {
        if (foundDevices.isEmpty()) {
            stopScanConnectButtonPulse(true);
            Context ctx = getMapView().getContext();
            appendLog("No supported radios found");
            try {
                new AlertDialog.Builder(ctx)
                        .setTitle("Scan Radios")
                        .setMessage("No UV-PRO, UV-50, or VR-N76 radios found.\n\n"
                                + "Put the radio in pairing mode, then tap Scan & Connect again.")
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            } catch (Exception e) {
                Log.e(TAG, "Could not show empty-scan dialog", e);
            }
            return;
        }

        Context ctx = getMapView().getContext();
        final int count = foundDevices.size();
        final int[] dotColors = new int[count];
        final String[] names = new String[count];
        refreshPickerLabelsAndDots(ctx, names, dotColors);
        Log.i(TAG, "Radio picker: " + count + " device(s): " + String.join(", ", names));

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(ctx,
                android.R.layout.simple_list_item_1, names) {
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                bindRadioPickerRow(tv, names[pos], dotColors[pos]);
                return tv;
            }
        };

        try {
            dismissRadioPickerDialog();
            radioPickerDialog = new AlertDialog.Builder(ctx)
                    .setTitle("Select Radio")
                    // Do not combine setMessage + setAdapter — on many builds the list height collapses to zero.
                    .setAdapter(adapter, (dialog, which) -> {
                        BluetoothDevice selected = foundDevices.get(which);
                        appendLog("Connecting to " + names[which] + "...");
                        stopScanConnectButtonPulse(true);
                        btManager.connect(selected);
                    })
                    .setNegativeButton("Cancel", (d, w) -> btManager.clearProbeSockets())
                    .setOnCancelListener(d -> btManager.clearProbeSockets())
                    .create();
            radioPickerDialog.show();
            appendLog("Select a radio (green = in range, grey = off, red = busy): "
                    + String.join(", ", names));
        } catch (Exception e) {
            Log.e(TAG, "Error showing device picker", e);
            appendLog("Error showing device picker");
            return;
        }

        startPickerNameRefresh(adapter, names, dotColors);
    }

    private void bindRadioPickerRow(TextView tv, String name, int dotColor) {
        Context ctx = tv.getContext();
        SpannableStringBuilder sb = new SpannableStringBuilder("\u25CF  " + name);
        sb.setSpan(
                new android.text.style.ForegroundColorSpan(dotColor),
                0, 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(
                new android.text.style.ForegroundColorSpan(RADIO_PICKER_LABEL_COLOR),
                2, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(sb);
        tv.setTextSize(16);
        tv.setBackgroundColor(RADIO_PICKER_ROW_BG);
        tv.setPadding(dip(ctx, 16), dip(ctx, 12), dip(ctx, 16), dip(ctx, 12));
        tv.setMinHeight(dip(ctx, 48));
    }

    private void refreshPickerLabelsAndDots(Context ctx, String[] names, int[] dotColors) {
        for (int i = 0; i < foundDevices.size(); i++) {
            BluetoothDevice device = foundDevices.get(i);
            names[i] = UvProBtDeviceMatcher.formatPickerLabel(device);
            int bondState = BluetoothDevice.BOND_NONE;
            try {
                bondState = device.getBondState();
            } catch (Exception ignored) {
            }
            dotColors[i] = UvProBtDeviceMatcher.availabilityDotColor(
                    bondState, btManager.wasSeenInLiveDiscovery(device.getAddress()));
        }
    }

    private void startPickerNameRefresh(ArrayAdapter<String> adapter,
                                        String[] names,
                                        int[] dotColors) {
        final Context ctx = getMapView().getContext();
        final long deadline = System.currentTimeMillis() + 4000L;
        new Thread(() -> {
            while (System.currentTimeMillis() < deadline && radioPickerDialog != null
                    && radioPickerDialog.isShowing()) {
                boolean changed = false;
                for (int i = 0; i < foundDevices.size(); i++) {
                    BluetoothDevice device = foundDevices.get(i);
                    String next = UvProBtDeviceMatcher.formatPickerLabel(device);
                    if (!next.equals(names[i])) {
                        names[i] = next;
                        changed = true;
                    }
                    int bondState = BluetoothDevice.BOND_NONE;
                    try {
                        bondState = device.getBondState();
                    } catch (Exception ignored) {
                    }
                    int nextDot = UvProBtDeviceMatcher.availabilityDotColor(
                            bondState, btManager.wasSeenInLiveDiscovery(device.getAddress()));
                    if (nextDot != dotColors[i]) {
                        dotColors[i] = nextDot;
                        changed = true;
                    }
                }
                if (changed) {
                    getMapView().post(adapter::notifyDataSetChanged);
                }
                try {
                    Thread.sleep(350L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "uvpro-radio-picker-refresh").start();
    }

    private void dismissRadioPickerDialog() {
        if (radioPickerDialog != null) {
            try {
                radioPickerDialog.dismiss();
            } catch (Exception ignored) {
            }
            radioPickerDialog = null;
        }
    }

    private boolean isLikelyMeshNamedDevice(BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        String name = UvProBtDeviceMatcher.safeDeviceName(device);
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.US);
        for (String hint : MESH_DEVICE_NAME_HINTS) {
            if (n.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private void requestScanConnectButtonPulse() {
        scanConnectPulsePending = true;
        if (getMapView() == null) {
            startScanConnectButtonPulse();
            return;
        }
        getMapView().removeCallbacks(deferredScanConnectPulseStart);
        getMapView().postDelayed(deferredScanConnectPulseStart, 60L);
    }

    private void startScanConnectButtonPulse() {
        scanConnectPulsePending = false;
        if (btnScan == null) {
            return;
        }
        stopScanConnectButtonPulse(false);
        btnScan.setBackgroundTintList(null);
        scanConnectPulseDrawable = buildVfoButtonBackground(
                COLOR_PILL_BUTTON_PRIMARY, 0x00FFEB3B, EDIT_SELECTION_STROKE_DP);
        btnScan.setBackground(scanConnectPulseDrawable);
        scanConnectPulseAnimator = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                0x11FFEB3B,
                0xFFFFEB3B);
        scanConnectPulseAnimator.setDuration(260L);
        scanConnectPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        scanConnectPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scanConnectPulseAnimator.addUpdateListener(animation -> {
            if (scanConnectPulseDrawable == null || btnScan == null) {
                return;
            }
            int color = (Integer) animation.getAnimatedValue();
            scanConnectPulseDrawable.setStroke(
                    dip(getMapView().getContext(), EDIT_SELECTION_STROKE_DP), color);
            btnScan.invalidate();
        });
        scanConnectPulseAnimator.start();
        updateScanButtonText();
    }

    private void stopScanConnectButtonPulse(boolean restoreBackground) {
        scanConnectPulsePending = false;
        if (getMapView() != null) {
            getMapView().removeCallbacks(deferredScanConnectPulseStart);
        }
        ValueAnimator animator = scanConnectPulseAnimator;
        scanConnectPulseAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
        scanConnectPulseDrawable = null;
        if (restoreBackground && btnScan != null) {
            int bgId = pluginContext.getResources().getIdentifier(
                    "bg_uvpro_connection_button", "drawable", pluginContext.getPackageName());
            if (bgId != 0) {
                btnScan.setBackgroundResource(bgId);
            } else {
                applyPillButtonBackground(btnScan, COLOR_PILL_BUTTON_PRIMARY);
            }
        }
        updateScanButtonText();
    }

    private void startMeshConnectButtonPulse() {
        requestMeshScanButtonPulse();
    }

    private void requestMeshScanButtonPulse() {
        meshScanPulsePending = true;
        if (getMapView() == null) {
            startMeshScanButtonPulse();
            return;
        }
        getMapView().removeCallbacks(deferredMeshScanPulseStart);
        getMapView().postDelayed(deferredMeshScanPulseStart, 60L);
    }

    private void startMeshScanButtonPulse() {
        meshScanPulsePending = false;
        if (btnMeshScan == null) {
            return;
        }
        stopMeshConnectButtonPulse(false);
        btnMeshScan.setBackgroundTintList(null);
        meshConnectPulseDrawable = buildVfoButtonBackground(
                COLOR_PILL_BUTTON_PRIMARY, 0x00F44336, EDIT_SELECTION_STROKE_DP);
        btnMeshScan.setBackground(meshConnectPulseDrawable);
        meshConnectPulseAnimator = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                0x11F44336,
                0xFFF44336);
        meshConnectPulseAnimator.setDuration(260L);
        meshConnectPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        meshConnectPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        meshConnectPulseAnimator.addUpdateListener(animation -> {
            if (meshConnectPulseDrawable == null || btnMeshScan == null) {
                return;
            }
            int color = (Integer) animation.getAnimatedValue();
            meshConnectPulseDrawable.setStroke(
                    dip(getMapView().getContext(), EDIT_SELECTION_STROKE_DP), color);
            btnMeshScan.invalidate();
        });
        meshConnectPulseAnimator.start();
        updateMeshScanButtonText();
    }

    private void stopMeshConnectButtonPulse(boolean restoreBackground) {
        meshScanPulsePending = false;
        if (getMapView() != null) {
            getMapView().removeCallbacks(deferredMeshScanPulseStart);
        }
        ValueAnimator animator = meshConnectPulseAnimator;
        meshConnectPulseAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
        meshConnectPulseDrawable = null;
        if (restoreBackground && btnMeshScan != null) {
            int bgId = pluginContext.getResources().getIdentifier(
                    "bg_uvpro_connection_button", "drawable", pluginContext.getPackageName());
            if (bgId != 0) {
                btnMeshScan.setBackgroundResource(bgId);
            } else {
                applyPillButtonBackground(btnMeshScan, 0xFF455A64);
            }
        }
        updateMeshScanButtonText();
    }

    private void openPluginToolPreferences() {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        SettingsFragment.openToolPreferences(ctx);
    }

    private void openPluginRadioSettings() {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        SettingsFragment.openRadioSettings(ctx);
    }

    private void openPluginAprsSettings() {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        SettingsFragment.openAprsSettings(ctx);
    }

    /** Manual beacon follows transmit toggles with cross-transport fallback when preferred is down. */
    private BtConnectionManager resolveManualBeaconTransport() {
        BtConnectionManager active = TransmitTransportResolver.resolve(
                meshTransmitEnabled,
                uvproTransmitEnabled,
                meshBtManager,
                btManager);
        return active != null && active.isConnected() ? active : null;
    }

    private void sendManualBeacon() {
        pulseSendButtonFeedback(btnSendBeacon, COLOR_BEACON_SECTION_STROKE);
        BtConnectionManager activeTx = resolveManualBeaconTransport();
        appendTransmitRouteLog("Manual beacon");
        if (cotBridge != null && activeTx != null && activeTx.isConnected()) {
            Context ctx = getMapView().getContext();
            if (SettingsFragment.isAprsDisableAtakTraffic(ctx)) {
                appendLog("Manual OPENRL beacon skipped (Disable ATAK traffic)");
                return;
            }
            // Get self location from ATAK
            com.atakmap.android.maps.MapItem self =
                    getMapView().getSelfMarker();
            if (self != null && self instanceof com.atakmap.android.maps.PointMapItem) {
                com.atakmap.coremap.maps.coords.GeoPoint gp =
                        ((com.atakmap.android.maps.PointMapItem) self).getPoint();
                boolean sent = cotBridge.sendPositionOverRadio(
                        activeTx,
                        gp.getLatitude(), gp.getLongitude(),
                        gp.getAltitude(), 0, 0, -1);
                if (sent) {
                    appendLog("Manual beacon sent (" + transportLabelFor(activeTx) + " OPENRL)");
                    showActionToast("Beacon Sent");
                } else {
                    appendLog("Manual beacon not sent — radio silence or transmit blocked");
                }
            } else {
                appendLog("Manual beacon not sent — no self-location available");
            }
        } else {
            appendTransmitBlockedLog("Manual beacon", activeTx);
        }
    }

    private void wireAprsSection() {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (btnAprsBeaconArm != null) {
            btnAprsBeaconArm.setOnClickListener(v ->
                    Toast.makeText(ctx, "Long press to enable/disable periodic APRS beacons.",
                            Toast.LENGTH_SHORT).show());
            btnAprsBeaconArm.setOnLongClickListener(v -> {
                if (ctx == null) {
                    return true;
                }
                boolean enabled = SettingsFragment.isAprsTxArmed(ctx);
                if (!enabled && !SettingsFragment.isValidAprsCallsign(
                        SettingsFragment.getAprsCallsign(ctx))) {
                    Toast.makeText(ctx,
                            "Set a valid APRS callsign in Edit APRS Settings first.",
                            Toast.LENGTH_LONG).show();
                    return true;
                }
                boolean nextEnabled = !enabled;
                SettingsFragment.setAprsTxArmed(ctx, nextEnabled);
                if (!nextEnabled && SettingsFragment.isAprsDisableAtakTraffic(ctx)) {
                    SettingsFragment.setAprsDisableAtakTraffic(ctx, false);
                }
                updateAprsSectionUi();
                appendLog(nextEnabled
                        ? "Periodic APRS beaconing enabled"
                        : "Periodic APRS beaconing disabled");
                return true;
            });
        }
        if (switchAprsDisableAtak != null && ctx != null) {
            switchAprsDisableAtak.setOnCheckedChangeListener((buttonView, isChecked) ->
                    SettingsFragment.setAprsDisableAtakTraffic(ctx, isChecked));
        }
        if (btnSendAprsBeacon != null) {
            btnSendAprsBeacon.setOnClickListener(v -> sendManualAprsBeacon());
        }
        if (btnClearAprsContacts != null) {
            btnClearAprsContacts.setOnClickListener(v -> clearAllAprsContacts());
        }
        if (btnEditAprsSettings != null) {
            btnEditAprsSettings.setOnClickListener(v -> openPluginAprsSettings());
        }
        updateAprsSectionUi();
    }

    private void clearAllAprsContacts() {
        MapView mv = getMapView();
        if (mv == null || mv.getRootGroup() == null) {
            return;
        }
        mv.post(() -> {
            int removedMarkers = removeAprsItemsRecursive(mv.getRootGroup());
            int removedTracks = removeAprsTracksRecursive(mv.getRootGroup());
            String msg = "Cleared APRS contacts: " + removedMarkers;
            if (removedTracks > 0) {
                msg += " (tracks: " + removedTracks + ")";
            }
            appendLog(msg);
            Toast.makeText(mv.getContext(), msg, Toast.LENGTH_SHORT).show();
            updateContactCount();
        });
    }

    private void confirmClearAllMeshContacts() {
        MapView mv = getMapView();
        Context ctx = mv != null ? mv.getContext() : pluginContext;
        new AlertDialog.Builder(ctx)
                .setTitle("Clear Mesh Contacts From Map")
                .setMessage("This will delete all repeaters and nodes only from your map. "
                        + "This will not delete contacts from your node. Are you sure?")
                .setPositiveButton("Yes", (d, w) -> clearAllMeshContacts())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearAllMeshContacts() {
        MapView mv = getMapView();
        if (mv == null || mv.getRootGroup() == null) {
            return;
        }
        mv.post(() -> {
            int removedMarkers = removeMeshItemsRecursive(mv.getRootGroup());
            int removedContacts = removeMeshContactsFromContactStore();
            Context ctx = mv.getContext();
            if (ctx != null) {
                try {
                    PreferenceManager.getDefaultSharedPreferences(ctx)
                            .edit()
                            .remove(PREF_MESH_REPEATER_CACHE)
                            .remove(PREF_MESH_NODE_CACHE)
                            .remove(PREF_MESH_MAP_SET_POSITION_LAT)
                            .remove(PREF_MESH_MAP_SET_POSITION_LON)
                            .apply();
                } catch (Exception ignored) {
                }
            }
            String msg = "Cleared Mesh contacts: " + removedMarkers
                    + " markers, " + removedContacts + " contacts";
            appendLog(msg);
            Toast.makeText(mv.getContext(), msg, Toast.LENGTH_SHORT).show();
            updateContactCount();
        });
    }

    private int removeMeshItemsRecursive(MapGroup group) {
        if (group == null) {
            return 0;
        }
        int removed = 0;
        List<MapItem> items = new ArrayList<>(group.getItems());
        for (MapItem item : items) {
            if (item == null) {
                continue;
            }
            if (CotBridge.isUvproMeshMarker(item)
                    || MESH_NODE_MAP_POSITION_UID.equals(item.getUID())) {
                group.removeItem(item);
                removed++;
            }
        }
        for (MapGroup child : group.getChildGroups()) {
            removed += removeMeshItemsRecursive(child);
        }
        return removed;
    }

    private int removeMeshContactsFromContactStore() {
        int removed = 0;
        try {
            Contacts contacts = Contacts.getInstance();
            List<Contact> all = contacts.getAllContacts();
            if (all == null) {
                return 0;
            }
            for (Contact c : new ArrayList<>(all)) {
                if (c == null) {
                    continue;
                }
                String uid = c.getUID();
                if (uid == null) {
                    continue;
                }
                String u = uid.trim().toUpperCase(Locale.US);
                if (u.startsWith(MESH_NODE_UID_PREFIX)
                        || u.startsWith(MESH_RPTR_UID_PREFIX)
                        || u.startsWith(ANDROID_MESH_NODE_UID_PREFIX)
                        || u.startsWith(ANDROID_MESH_RPTR_UID_PREFIX)) {
                    contacts.removeContact(c);
                    removed++;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "removeMeshContactsFromContactStore failed", e);
        }
        return removed;
    }

    private int removeAprsItemsRecursive(MapGroup group) {
        if (group == null) {
            return 0;
        }
        int removed = 0;
        List<MapItem> items = new ArrayList<>(group.getItems());
        for (MapItem item : items) {
            if (item == null) {
                continue;
            }
            if (CotBridge.isUvproAprsMarker(item)) {
                group.removeItem(item);
                removed++;
            }
        }
        for (MapGroup child : group.getChildGroups()) {
            removed += removeAprsItemsRecursive(child);
        }
        return removed;
    }

    private int removeAprsTracksRecursive(MapGroup group) {
        if (group == null) {
            return 0;
        }
        int removed = 0;
        List<MapItem> items = new ArrayList<>(group.getItems());
        for (MapItem item : items) {
            if (item != null && item.getMetaBoolean(AprsTrackManager.META_UVPRO_APRS_TRACK, false)) {
                group.removeItem(item);
                removed++;
            }
        }
        for (MapGroup child : group.getChildGroups()) {
            removed += removeAprsTracksRecursive(child);
        }
        return removed;
    }

    private void updateAprsSectionUi() {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        if (textAprsStatusCall != null) {
            textAprsStatusCall.setText("Call: " + SettingsFragment.formatAprsDisplayCall(ctx));
        }
        if (textAprsStatusMessage != null) {
            String msg = SettingsFragment.getAprsMessage(ctx);
            if (msg != null) {
                msg = msg.trim();
            }
            if (msg == null || msg.isEmpty()) {
                textAprsStatusMessage.setText("APRS Message: No Message");
            } else {
                textAprsStatusMessage.setText("APRS Message: " + msg);
            }
        }
        if (imageAprsStatusIcon != null && textAprsStatusIcon != null) {
            if (!com.uvpro.plugin.aprs.AprsIconPreviewLoader.isIconSelected(ctx)) {
                imageAprsStatusIcon.setVisibility(android.view.View.GONE);
                imageAprsStatusIcon.setImageDrawable(null);
                textAprsStatusIcon.setVisibility(android.view.View.VISIBLE);
                textAprsStatusIcon.setText("(not set)");
            } else {
                android.graphics.Bitmap bmp =
                        com.uvpro.plugin.aprs.AprsIconPreviewLoader.loadSelectedIconBitmap(
                                ctx, pluginContext);
                if (bmp != null) {
                    imageAprsStatusIcon.setImageBitmap(bmp);
                    imageAprsStatusIcon.setVisibility(android.view.View.VISIBLE);
                    textAprsStatusIcon.setVisibility(android.view.View.GONE);
                } else {
                    imageAprsStatusIcon.setVisibility(android.view.View.GONE);
                    imageAprsStatusIcon.setImageDrawable(null);
                    textAprsStatusIcon.setVisibility(android.view.View.VISIBLE);
                    textAprsStatusIcon.setText("(not set)");
                }
            }
        }
        if (switchAprsDisableAtak != null) {
            boolean aprsEnabled = SettingsFragment.isAprsTxArmed(ctx)
                    && SettingsFragment.isValidAprsCallsign(
                    SettingsFragment.getAprsCallsign(ctx));
            boolean disableAtak = SettingsFragment.isAprsDisableAtakTraffic(ctx);
            if (!aprsEnabled && disableAtak) {
                // Without a valid FCC callsign APRS TX is unavailable; do not leave ATAK TX disabled.
                SettingsFragment.setAprsDisableAtakTraffic(ctx, false);
                disableAtak = false;
            }
            switchAprsDisableAtak.setOnCheckedChangeListener(null);
            switchAprsDisableAtak.setChecked(disableAtak);
            switchAprsDisableAtak.setOnCheckedChangeListener((buttonView, isChecked) ->
                    SettingsFragment.setAprsDisableAtakTraffic(ctx, isChecked));
        }
        if (btnAprsBeaconArm != null) {
            boolean enabled = SettingsFragment.isAprsTxArmed(ctx);
            btnAprsBeaconArm.setBackgroundTintList(null);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(24f);
            bg.setColor(0xFF455A64);
            if (enabled) {
                bg.setStroke(4, 0xFFFF9800);
            }
            btnAprsBeaconArm.setBackground(bg);
            btnAprsBeaconArm.setText(enabled
                    ? "Long Press for APRS Beacon (ENABLED)"
                    : "Long Press for APRS Beacon");
        }
    }

    private void sendManualAprsBeacon() {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        pulseSendButtonFeedback(btnSendAprsBeacon, COLOR_APRS_SECTION_STROKE);
        if (!SettingsFragment.isValidAprsCallsign(SettingsFragment.getAprsCallsign(ctx))) {
            Toast.makeText(ctx,
                    "Set a valid APRS callsign in Edit APRS Settings first.",
                    Toast.LENGTH_LONG).show();
            appendLog("APRS beacon blocked (invalid FCC callsign)");
            return;
        }
        if (btManager == null || !btManager.isConnected()) {
            appendLog("APRS beacon not sent — UV-PRO not connected");
            return;
        }
        if (com.uvpro.plugin.aprs.AprsOutboundTransmitter
                .sendPositionBeacon(ctx, btManager, false)) {
            appendLog("Manual APRS beacon sent (UV-PRO)");
            showActionToast("APRS Beacon Sent");
        } else {
            appendLog("APRS beacon failed (OPENRL active / callsign / icon / location / silence)");
        }
    }

    private void sendPing() {
        pulseSendButtonFeedback(btnSendPing, COLOR_BEACON_SECTION_STROKE);
        BtConnectionManager activeTx = resolveActiveTransmitManager();
        appendTransmitRouteLog("Ping");
        boolean rfWanted = meshTransmitEnabled || uvproTransmitEnabled;
        boolean rfSent = false;
        if (rfWanted && cotBridge != null && activeTx != null && activeTx.isConnected()) {
            String callsign = MapView.getMapView().getSelfMarker().getMetaString("callsign","UNKNOWN");
            try {
                com.uvpro.plugin.protocol.UVProPacket packet =
                        com.uvpro.plugin.protocol.UVProPacket
                                .createPingPacket(com.uvpro.plugin.util.CallsignUtil.toRadioCallsign(callsign));
                byte[] packetBytes = packet.encode();
                if (encryptionManager != null && encryptionManager.isEnabled()) {
                    packetBytes = encryptionManager.encrypt(packetBytes);
                    if (packetBytes == null) {
                        appendLog("Ping not sent — encryption failed");
                        packetBytes = null;
                    }
                }
                if (packetBytes != null) {
                    com.uvpro.plugin.ax25.Ax25Frame frame =
                            com.uvpro.plugin.ax25.Ax25Frame
                                    .createUVProFrame(callsign, 0, packetBytes);
                    if (activeTx.sendKissFrame(frame.encode())) {
                        appendLog("Ping sent (" + transportLabelFor(activeTx) + ")");
                        showActionToast("Ping Sent");
                        rfSent = true;
                    } else {
                        appendLog("Ping not sent — radio silence or transmit blocked");
                    }
                }
            } catch (Exception e) {
                appendLog("Ping not sent — " + e.getMessage());
            }
        }
        boolean wifiSent = false;
        if (cotBridge != null && cotBridge.canSendPingOverWifiNetwork()) {
            if (cotBridge.sendPingOverWifiNetwork(null)) {
                appendLog("Ping sent (ATAK WiFi)");
                if (!rfSent) {
                    showActionToast("Ping Sent");
                }
                wifiSent = true;
            } else if (!rfSent) {
                appendLog("Ping not sent — WiFi/TAK dispatch failed");
            }
        } else if (rfSent && cotBridge != null && !cotBridge.isWifiTransmitEnabled()) {
            appendLog("Ping WiFi leg skipped (ATAK WiFi transmit off)");
        }
        if (rfSent || wifiSent) {
            PingReplyNotifier.notePingSent(
                    getMapView().getContext(), rfSent, wifiSent);
        }
        if (!rfSent && !wifiSent) {
            appendTransmitBlockedLog("Ping", activeTx);
        }
    }

    @Override
    public void onDropDownSelectionRemoved() { }

    @Override
    public void onDropDownClose() {
        if (getMapView() != null) {
            getMapView().removeCallbacks(meshQueuedStatusTimeoutRunnable);
            getMapView().removeCallbacks(meshCallsignPositionSyncRunnable);
        }
        stopActiveVfoPulse();
        stopUpdateGpsButtonPulse(true);
        stopInitialGroupSetupPulse(true);
        stopSendButtonPulse(true);
        if (repeaterLoadFocusAnimator != null) {
            repeaterLoadFocusAnimator.cancel();
            repeaterLoadFocusAnimator = null;
        }
        if (btnLoadSelectedRepeater != null) {
            btnLoadSelectedRepeater.setAlpha(1f);
        }
        if (meshChannelChatDialog != null && meshChannelChatDialog.isShowing()) {
            meshChannelChatDialog.dismiss();
        } else {
            finishExpandedMeshChannelChatClose();
        }
        meshChannelChatLogView = null;
        meshChannelChatTitleView = null;
        meshChannelChatActiveIndex = -1;
        clearMeshContactChatMode();
        updateExpandMeshChannelChatButtonState();
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) { }

    @Override
    public void onDropDownVisible(boolean visible) {
        if (!visible) {
            stopActiveVfoPulse();
        } else {
            refreshChannelGroupFromRadioAsync(true);
            if (btManager.isConnected()) {
                refreshTxPowerFromRadioAsync();
            }
            if (pendingOpenToChannelControl) {
                scheduleScrollToRepeaterLoadSection();
                pendingOpenToChannelControl = false;
            } else if (meshBtManager != null && meshBtManager.isConnected()) {
                meshChatUiRestorePending = true;
                scheduleRestoreMeshChatUi();
            }
            // Check for a pending QR scan result stored by QrScanActivity
            checkPendingQrResult();
            updateStatusFields();
            updateAprsSectionUi();
        }
    }

    private void checkPendingQrResult() {
        if (!pendingQrScan) return;
        String content = QrResultProvider.consumePending(getMapView().getContext(), 60_000L);
        if (content != null && !content.isEmpty()) {
            handleQrChannelResult(content);
        }
    }

    @Override
    public void disposeImpl() {
        cancelRoomLoginTimeout();
        // Unregister listeners
        if (qrPollRunnable != null && getMapView() != null) {
            getMapView().removeCallbacks(qrPollRunnable);
            qrPollRunnable = null;
        }
        btManager.removeListener(this);
        if (meshBtManager != null) {
            meshBtManager.removeMeshChannelListener(meshChannelListener);
            meshBtManager.removeMeshNativeDmListener(meshNativeDmListener);
            meshBtManager.removeMeshRoomLoginListener(meshRoomLoginListener);
            meshBtManager.removeMeshDeviceContactUpdateListener(meshDeviceContactUpdateListener);
            meshBtManager.removeMeshAdvertListener(meshRoomAdvertListener);
        }
        contactTracker.setListener(null);
        radioGpsAugmentController.shutdown();
        cancelStartupTransmitApplyTimer();
        if (getMapView() != null) {
            getMapView().removeCallbacks(meshGpsAugmentRunnable);
            getMapView().removeCallbacks(meshQueuedStatusTimeoutRunnable);
            getMapView().removeCallbacks(meshCallsignPositionSyncRunnable);
            getMapView().removeCallbacks(meshBatteryPollRunnable);
            getMapView().removeCallbacks(uvproBatteryPollRunnable);
        }
        stopActiveVfoPulse();
        stopUpdateGpsButtonPulse(true);
        stopInitialGroupSetupPulse(true);
        stopSendButtonPulse(true);
        if (repeaterLoadFocusAnimator != null) {
            repeaterLoadFocusAnimator.cancel();
            repeaterLoadFocusAnimator = null;
        }
        clearMeshContactChatMode();
        unregisterBeaconSettingsReceiver();
    }
}
