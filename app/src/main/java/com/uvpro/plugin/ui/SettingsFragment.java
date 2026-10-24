package com.uvpro.plugin.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.gui.PanCheckBoxPreference;
import com.atakmap.android.gui.PanEditTextPreference;
import com.atakmap.android.gui.PanPreference;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.PluginPreferenceFragment;
import com.atakmap.app.SettingsActivity;
import com.uvpro.plugin.protocol.NetSlotConfig;
import com.uvpro.plugin.R;
import com.uvpro.plugin.protocol.UVProRadioServices;
import com.uvpro.plugin.UVProMapComponent;
import com.uvpro.plugin.beacon.SmartBeacon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings screen for the UVPro plugin.
 *
 * Provides configuration for:
 * - Callsign
 * - Beacon interval
 * - Chat relay toggle
 * - CoT relay toggle
 * - Auto-reconnect toggle
 */
public class SettingsFragment extends PluginPreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int COLOR_STD_BLUE = 0xFF1976D2;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_CATEGORY_YELLOW = 0xFFFFEB3B;
    private static final int COLOR_VALUE_GREEN = 0xFF4CAF50;
    private static final int COLOR_DISABLED_GREY = 0xFF757575;
    private static final int COLOR_WARNING_RED = 0xFFFF5252;
    private static final int PILL_CORNER_RADIUS_DP = 20;
    private static final int COLOR_PILL_BUTTON_PRIMARY = 0xFF455A64;
    private static final int COLOR_PILL_BUTTON_STROKE = 0xFF00BCD4;
    private static final int PILL_BUTTON_TEXT_SP = 15;
    private static final int PILL_PULSE_STROKE_DP = 3;
    /** Matches {@code @style/UvproPillButton} on the plugin dropdown panel. */
    private static final int PILL_BUTTON_MIN_HEIGHT_DP = 40;
    private static final int PILL_BUTTON_PAD_HORIZONTAL_DP = 16;
    private static final int PILL_BUTTON_PAD_VERTICAL_DP = 8;
    private static final int PILL_BUTTON_ROW_MARGIN_VERTICAL_DP = 4;
    private static final String EMBEDDED_PILL_BUTTON_TAG = "uvpro_embedded_pill_button";
    private static final String EMBEDDED_CHECKBOX_TAG = "uvpro_embedded_checkbox";
    private static final int ROW_PREF_KEY_TAG = R.id.uvpro_row_pref_key;
    private static final int SUMMARY_WATCHER_TAG = R.id.uvpro_summary_watcher;
    private static final int SUMMARY_REBIND_TAG = R.id.uvpro_summary_rebind;

    private static View distributeNetButtonRowView;
    private static ValueAnimator distributeButtonPulseAnimator;
    private static GradientDrawable distributeButtonPulseDrawable;
    private static View distributeButtonPulseTarget;
    private static boolean distributeButtonPulseRestoreEnabled = true;
    private static final int PREFERENCE_TITLE_TEXT_SP = 16;
    private static final float CATEGORY_TITLE_TEXT_SP = 18f;

    /** Beacon interval + Smart Beacon (Tool Preferences section). */
    public static final String KEY_RESTORE_ALL_DEFAULTS = "uvpro_restore_all_defaults";
    public static final String KEY_CAT_BEACON = "uvpro_cat_beacon";
    public static final String KEY_RESTORE_BEACON_DEFAULTS = "uvpro_restore_beacon_defaults";
    public static final String KEY_SMART_BEACON_SECTION_HEADER = "uvpro_smart_beacon_section_header";
    private static final String BEACON_INTERVAL_DESC =
            "Sets the ATAK call sign beacon interval";
    private static final String SMART_BEACON_SECTION_DESC =
            "Sets automatic beacons based off of movement";
    private static final String APRS_ICON_DESC = "Map symbol for your position beacons";
    private static final String APRS_ICON_NOT_SET = "(not set)";
    private static final int APRS_ICON_SUMMARY_DP = 36;

    /** Key registered with {@code ToolsPreferenceFragment} in {@link com.uvpro.plugin.UVProMapComponent}. */
    public static final String TOOL_SETTINGS_KEY = "uvproPreference";

    public static final String PREF_BEACON_INTERVAL = "uvpro_beacon_interval";
    public static final String PREF_ENCRYPTION_ENABLED = "uvpro_encryption_enabled";
    public static final String PREF_ENCRYPTION_PASSPHRASE = "uvpro_encryption_passphrase";
    public static final String PREF_RETRY_INTERVAL_MIN = "uvpro_retry_interval_min";
    public static final String PREF_RETRY_MAX = "uvpro_retry_max";
    public static final String PREF_SA_RELAY_ENABLED = "uvpro_sa_relay_enabled";
    public static final String PREF_RF_TO_TAK_UPLINK_ENABLED = "uvpro_rf_to_tak_uplink_enabled";
    public static final String PREF_RESTRICT_CHAT_TO_REACHABLE_PEERS =
            "uvpro_restrict_chat_to_reachable_peers";
    public static final String PREF_PING_REPLY_ENABLED = "uvpro_ping_reply_enabled";
    public static final boolean DEFAULT_PING_REPLY_ENABLED = true;
    public static final String PREF_PING_REPLY_SAME_TRANSPORT = "uvpro_ping_reply_same_transport";
    public static final String PREF_ATAK_MESHCORE_TRANSMIT = "uvpro_atak_meshcore_transmit";
    public static final String PREF_ATAK_UVPRO_TRANSMIT = "uvpro_atak_uvpro_transmit";
    /** Last user transmit choice: {@code mesh}, {@code uvpro}, or {@code none}. */
    public static final String PREF_TRANSMIT_PREFERRED_TRANSPORT =
            "uvpro_transmit_preferred_transport";
    /** Admin-only — UI persistence only; limiting logic wired elsewhere. */
    public static final String PREF_DISABLE_MESH_BEACON_LIMITING =
            "uvpro_disable_mesh_beacon_limiting";

    public static final String PREF_APRS_CALLSIGN = "uvpro_aprs_callsign";
    public static final String PREF_APRS_SSID = "uvpro_aprs_ssid";
    public static final String PREF_APRS_SYMBOL_TABLE = "uvpro_aprs_symbol_table";
    public static final String PREF_APRS_SYMBOL_CODE = "uvpro_aprs_symbol_code";
    public static final String PREF_APRS_ICON_SELECTED = "uvpro_aprs_icon_selected";
    public static final String PREF_APRS_MESSAGE = "uvpro_aprs_message";
    public static final String PREF_APRS_TX_ARMED = "uvpro_aprs_tx_armed";
    public static final String PREF_APRS_DISABLE_ATAK_TRAFFIC = "uvpro_aprs_disable_atak_traffic";

    public static final String KEY_CAT_APRS = "uvpro_cat_aprs";
    public static final String KEY_CAT_SA_RELAY = "uvpro_cat_sa_relay";
    public static final String KEY_CAT_SECURITY = "uvpro_cat_security";
    public static final String KEY_APRS_ICON = "uvpro_aprs_icon";

    public static final String KEY_UNLOCK_ADMIN = "uvpro_admin_access";
    public static final String KEY_CAT_ADMINISTRATION = "uvpro_cat_administration";
    public static final String KEY_ADMIN_LEADERSHIP_WARNING = "uvpro_admin_leadership_warning";
    public static final String KEY_RESTORE_ADMIN_DEFAULTS = "uvpro_restore_admin_defaults";
    public static final String KEY_DISTRIBUTE_NET_SLOTS = "uvpro_distribute_net_slots";
    public static final String KEY_DISTRIBUTE_NET_WARNING = "uvpro_distribute_net_warning";
    private static final String DISTRIBUTE_NET_WARNING =
            "WARNING: DO NOT SEND THIS FEATURE UNLESS SPECIFICALLY DIRECTED BY HIGHER";
    private static final String DISABLE_MESH_BEACON_LIMITING_DESC =
            "When disabled, this allows mesh beaconing to follow all smart beacon "
                    + "settings without mesh limits";
    private static final String SA_RELAY_SECTION_DESC =
            "Re-broadcast network positions over radio";
    public static final String KEY_SA_RELAY_SECTION_HEADER = "uvpro_sa_relay_section_header";
    public static final String KEY_REPLY_SLOT_TIMES_SECTION_HEADER =
            "uvpro_reply_slot_times_section_header";

    /** String prefs mirrored into ATAK default SharedPreferences (runtime source of truth). */
    private static final String[] MIRROR_STRING_PREF_KEYS = {
            PREF_BEACON_INTERVAL,
            PREF_RETRY_INTERVAL_MIN,
            PREF_RETRY_MAX,
            PREF_APRS_CALLSIGN,
            PREF_APRS_SSID,
            PREF_APRS_MESSAGE,
            PREF_ENCRYPTION_PASSPHRASE,
            NetSlotConfig.PREF_SLOT_COUNT,
            NetSlotConfig.PREF_SLOT_TIME_SEC,
            SmartBeacon.KEY_LOW_SPEED,
            SmartBeacon.KEY_HIGH_SPEED,
            SmartBeacon.KEY_SLOW_RATE,
            SmartBeacon.KEY_FAST_RATE,
            SmartBeacon.KEY_MIN_TURN_TIME,
            SmartBeacon.KEY_TURN_THRESHOLD,
            SmartBeacon.KEY_TURN_SLOPE,
    };

    private static final String[] MIRROR_BOOLEAN_PREF_KEYS = {
            PREF_PING_REPLY_ENABLED,
            PREF_SA_RELAY_ENABLED,
            PREF_RF_TO_TAK_UPLINK_ENABLED,
            PREF_DISABLE_MESH_BEACON_LIMITING,
            NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED,
    };

    private static boolean isCheckboxPreferenceKey(String key) {
        return PREF_PING_REPLY_ENABLED.equals(key)
                || PREF_SA_RELAY_ENABLED.equals(key)
                || PREF_RF_TO_TAK_UPLINK_ENABLED.equals(key)
                || PREF_DISABLE_MESH_BEACON_LIMITING.equals(key)
                || NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(key);
    }

    private static boolean isCheckboxPreference(Preference pref) {
        return pref != null
                && (pref instanceof CheckBoxPreference
                || isCheckboxPreferenceKey(pref.getKey()));
    }

    private static final String[] ADMIN_GATED_PREF_KEYS = {
            PREF_DISABLE_MESH_BEACON_LIMITING,
            KEY_REPLY_SLOT_TIMES_SECTION_HEADER,
            NetSlotConfig.PREF_SLOT_COUNT,
            NetSlotConfig.PREF_SLOT_TIME_SEC,
            KEY_DISTRIBUTE_NET_WARNING,
            KEY_DISTRIBUTE_NET_SLOTS,
    };

    /** Bottom of Administrative Settings — warning then distribute button. */
    private static final String[] ADMIN_FOOTER_PREF_KEYS = {
            NetSlotConfig.PREF_SLOT_COUNT,
            NetSlotConfig.PREF_SLOT_TIME_SEC,
            KEY_DISTRIBUTE_NET_WARNING,
            KEY_DISTRIBUTE_NET_SLOTS,
    };

    /** Never belong inside Administrative Settings. */
    private static final String[] REMOVE_FROM_ADMIN_KEYS = {
            KEY_RESTORE_BEACON_DEFAULTS,
            KEY_RESTORE_ALL_DEFAULTS,
    };

    /** Never belong inside Beacon Settings. */
    private static final String[] REMOVE_FROM_BEACON_KEYS = {
            KEY_RESTORE_ALL_DEFAULTS,
            KEY_RESTORE_ADMIN_DEFAULTS,
    };

    /** Never belong inside APRS Settings. */
    private static final String[] REMOVE_FROM_APRS_KEYS = {
            KEY_RESTORE_ALL_DEFAULTS,
            KEY_RESTORE_BEACON_DEFAULTS,
            KEY_RESTORE_ADMIN_DEFAULTS,
    };

    /** Never belong inside SA Relay. */
    private static final String[] REMOVE_FROM_SA_RELAY_KEYS = {
            KEY_RESTORE_ALL_DEFAULTS,
            KEY_RESTORE_BEACON_DEFAULTS,
            KEY_RESTORE_ADMIN_DEFAULTS,
    };

    /** Injected after inflate — some ATAK builds omit custom Pan* prefs from XML. */
    public static final String KEY_BLUETOOTH_DEVICES = "uvpro_bluetooth_devices";
    public static final String KEY_CAT_RADIO = "uvpro_cat_radio";

    public static final String DEFAULT_BEACON_INTERVAL = "300";
    public static final String DEFAULT_RETRY_INTERVAL_MIN = "2";
    public static final String DEFAULT_RETRY_MAX = "3";
    public static final String DEFAULT_APRS_SSID = "9";

    private static Context staticPluginContext;

    private PreferenceCategory adminCategory;
    private final Map<String, Preference.OnPreferenceClickListener> preferenceClickHandlers =
            new HashMap<>();
    private boolean adminUnlockDialogOpen = false;
    /** Blocks spurious enable events fired while disabling admin settings. */
    private boolean suppressAdminPasswordPrompt = false;

    /**
     * Zero-arg constructor required by Android fragment system.
     * Only valid after the 1-arg constructor has been called once.
     */
    public SettingsFragment() {
        super(staticPluginContext, resolvePreferencesResourceId(staticPluginContext));
    }

    public SettingsFragment(final Context pluginContext) {
        super(pluginContext, resolvePreferencesResourceId(pluginContext));
        staticPluginContext = pluginContext;
    }

    private static int resolvePreferencesResourceId(Context ctx) {
        if (ctx == null) {
            return 0;
        }
        return ctx.getResources().getIdentifier(
                "preferences", "xml", ctx.getPackageName());
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureRequiredPreferences();
        removeObsoletePreferences();
        normalizeBeaconSection();
        normalizeAprsSection();
        normalizeSaRelaySection();
        normalizeAdministrationSection();
        normalizeAllRestoreControls();
        wireRestorePreferenceHandlers();
        Context ctx = getContext();
        if (ctx == null) {
            ctx = staticPluginContext;
        }
        if (ctx != null) {
            NetSlotConfig.ensureDefaults(ctx);
        }
        ensureBluetoothDevicesPreference();
        wireBeaconPreferences();
        wireAprsPreferences();
        wireAdministrationPreferences();
        wirePersistentPreferenceWriters();
        applyRadioProfilePreferences();
    }

    /** Ensure Pan* widgets persist into ATAK SharedPreferences when the user saves. */
    private void wirePersistentPreferenceWriters() {
        wireEditTextPreference(PREF_APRS_CALLSIGN, true);
        wireEditTextPreference(PREF_APRS_MESSAGE, false);
        wireEditTextPreference(PREF_ENCRYPTION_PASSPHRASE, false);
        wireEditTextPreference(NetSlotConfig.PREF_SLOT_COUNT, false);
        wireEditTextPreference(NetSlotConfig.PREF_SLOT_TIME_SEC, false);
        wireEditTextPreference(SmartBeacon.KEY_LOW_SPEED, false);
        wireEditTextPreference(SmartBeacon.KEY_HIGH_SPEED, false);
        wireEditTextPreference(SmartBeacon.KEY_SLOW_RATE, false);
        wireEditTextPreference(SmartBeacon.KEY_FAST_RATE, false);
        wireEditTextPreference(SmartBeacon.KEY_MIN_TURN_TIME, false);
        wireEditTextPreference(SmartBeacon.KEY_TURN_THRESHOLD, false);
        wireEditTextPreference(SmartBeacon.KEY_TURN_SLOPE, false);

        wireListPreference(PREF_BEACON_INTERVAL);
        wireListPreference(RadioProfile.PREF);
        wireListPreference(PREF_RETRY_INTERVAL_MIN);
        wireListPreference(PREF_RETRY_MAX);
        wireListPreference(PREF_APRS_SSID);

        wireCheckBoxPreference(PREF_PING_REPLY_ENABLED, DEFAULT_PING_REPLY_ENABLED);
        wireCheckBoxPreference(PREF_SA_RELAY_ENABLED, false);
        wireCheckBoxPreference(PREF_RF_TO_TAK_UPLINK_ENABLED, false);
        wireCheckBoxPreference(PREF_DISABLE_MESH_BEACON_LIMITING, false);
        wireAdminSettingsGate();
    }

    private void wireCheckBoxPreference(String key, boolean defaultValue) {
        Preference pref = findPreference(key);
        if (!(pref instanceof CheckBoxPreference)) {
            return;
        }
        pref.setPersistent(false);
        syncCheckBoxPreferenceFromAtak(key, defaultValue);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean checked = Boolean.TRUE.equals(newValue);
            Context relayCtx = resolveSettingsContext();
            if (PREF_RF_TO_TAK_UPLINK_ENABLED.equals(key) && checked) {
                if (!isSaRelayEnabledForUi(relayCtx)) {
                    Context toastCtx = getActivity() != null ? getActivity() : relayCtx;
                    if (toastCtx != null) {
                        Toast.makeText(toastCtx, "Enable SA Relay first",
                                Toast.LENGTH_SHORT).show();
                    }
                    return false;
                }
            }
            persistBooleanPrefToAtak(key, checked);
            syncCheckBoxPreferenceFromAtak(key, defaultValue);
            afterPreferenceValueSaved(key);
            if (PREF_SA_RELAY_ENABLED.equals(key)) {
                updateDependentPreferences();
            }
            if (PREF_PING_REPLY_ENABLED.equals(key)
                    || PREF_SA_RELAY_ENABLED.equals(key)
                    || PREF_RF_TO_TAK_UPLINK_ENABLED.equals(key)) {
                ListView list = getPreferenceListView();
                if (list != null) {
                    list.post(this::applyRowStyles);
                }
            }
            return true;
        });
    }

    private void wireEditTextPreference(String key, boolean uppercase) {
        Preference pref = findPreference(key);
        if (!(pref instanceof EditTextPreference)) {
            return;
        }
        pref.setPersistent(false);
        if (!isSmartBeaconParamKey(key)) {
            syncEditTextPreferenceFromAtak(key);
        }
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String text = newValue != null ? newValue.toString().trim() : "";
            if (uppercase) {
                text = text.toUpperCase(java.util.Locale.US);
            }
            persistStringPrefToAtak(key, text);
            if (preference instanceof PanEditTextPreference) {
                ((PanEditTextPreference) preference).setText(text);
            }
            afterPreferenceValueSaved(key);
            return true;
        });
    }

    private void wireListPreference(String key) {
        Preference pref = findPreference(key);
        if (!(pref instanceof ListPreference)) {
            return;
        }
        pref.setPersistent(false);
        syncListPreferenceFromAtak(key, defaultStringForPreferenceKey(key));
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String value = newValue != null ? newValue.toString() : "";
            persistStringPrefToAtak(key, value);
            if (preference instanceof ListPreference) {
                ((ListPreference) preference).setValue(value);
            }
            afterPreferenceValueSaved(key);
            return true;
        });
        pref.setOnPreferenceClickListener(preference -> {
            scheduleApplyRowStyles();
            return false;
        });
    }

    private void persistStringPrefToAtak(String key, String value) {
        Context ctx = resolveSettingsContext();
        if (ctx == null) {
            return;
        }
        if (value == null) {
            value = "";
        }
        value = value.trim();
        if (PREF_APRS_CALLSIGN.equals(key)) {
            value = value.toUpperCase(java.util.Locale.US);
        }
        getPrefs(ctx).edit().putString(key, value).commit();
    }

    private void persistBooleanPrefToAtak(String key, boolean value) {
        Context ctx = resolveSettingsContext();
        if (ctx == null) {
            return;
        }
        getPrefs(ctx).edit().putBoolean(key, value).commit();
    }

    private void afterPreferenceValueSaved(String key) {
        if (isSmartBeaconParamKey(key)) {
            Context smartCtx = resolveSettingsContext();
            if (smartCtx != null) {
                persistSmartBeaconFromPreferences(getPrefs(smartCtx));
            }
        }
        if (NetSlotConfig.PREF_SLOT_COUNT.equals(key)
                || NetSlotConfig.PREF_SLOT_TIME_SEC.equals(key)) {
            normalizeSlotPreferences(getPreferenceManager().getSharedPreferences());
        }
        if (PREF_BEACON_INTERVAL.equals(key)
                || isSmartBeaconParamKey(key)
                || PREF_PING_REPLY_ENABLED.equals(key)
                || PREF_SA_RELAY_ENABLED.equals(key)
                || PREF_RF_TO_TAK_UPLINK_ENABLED.equals(key)) {
            notifyRuntimeSettingsChanged();
        }
        if (PREF_APRS_CALLSIGN.equals(key) || PREF_APRS_MESSAGE.equals(key)
                || PREF_APRS_SSID.equals(key)) {
            notifyRuntimeSettingsChanged();
        }
        if (PREF_ENCRYPTION_PASSPHRASE.equals(key)) {
            com.uvpro.plugin.protocol.UVProRadioServices.syncEncryptionFromSettings(
                    resolveSettingsContext());
        }
        if (PREF_SA_RELAY_ENABLED.equals(key)) {
            updateDependentPreferences();
        }
        if (RadioProfile.PREF.equals(key)) {
            applyRadioProfilePreferences();
            RadioProfile.applyMapIcons(staticPluginContext);
            RadioProfile.refreshOpenPanel();
        }
        updateSummaries();
        ListView list = getPreferenceListView();
        if (list != null) {
            list.post(this::applyRowStyles);
        }
    }

    private void registerPreferenceClickHandler(String key,
                                                Preference.OnPreferenceClickListener listener) {
        preferenceClickHandlers.put(key, listener);
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setOnPreferenceClickListener(listener);
        }
    }

    private void firePreferenceClick(Preference pref) {
        if (pref == null || !pref.isEnabled()) {
            return;
        }
        String key = pref.getKey();
        if (key == null) {
            return;
        }
        firePreferenceClickByKey(key);
    }

    private void firePreferenceClickByKey(String key) {
        if (key == null) {
            return;
        }
        if (isPillActionPreferenceKey(key)) {
            dispatchPillActionClick(key);
            return;
        }
        Preference pref = findPreference(key);
        if (pref != null && !pref.isEnabled()) {
            return;
        }
        Preference.OnPreferenceClickListener listener = preferenceClickHandlers.get(key);
        if (listener != null) {
            listener.onPreferenceClick(pref);
        }
    }

    /** Pill buttons bypass the preference-click map — handlers can be lost after normalize/rebind. */
    private void dispatchPillActionClick(String prefKey) {
        if (prefKey == null) {
            return;
        }
        Preference pref = findPreference(prefKey);
        if (pref != null && !pref.isEnabled()) {
            return;
        }
        switch (prefKey) {
            case KEY_RESTORE_ALL_DEFAULTS:
                showRestoreConfirmDialog("Restore All Defaults",
                        () -> restoreAllDefaults(resolveSettingsContext()));
                break;
            case KEY_RESTORE_BEACON_DEFAULTS:
                showRestoreConfirmDialog("Restore Defaults",
                        () -> restoreBeaconDefaults(resolveSettingsContext()));
                break;
            case KEY_RESTORE_ADMIN_DEFAULTS:
                showRestoreConfirmDialog("Restore Defaults",
                        () -> restoreAdminDefaults(resolveSettingsContext()));
                break;
            case KEY_DISTRIBUTE_NET_SLOTS:
                Context ctx = resolveSettingsContext();
                if (ctx == null) {
                    return;
                }
                boolean enabled = pref == null || pref.isEnabled();
                pulseDistributeButtonFeedback(distributeNetButtonRowView, enabled);
                String error = distributeNetSlotsOrError(ctx);
                if (error == null) {
                    int slots = NetSlotConfig.getSlotCount(ctx);
                    float slotSec = NetSlotConfig.getSlotTimeSec(ctx);
                    Toast.makeText(ctx,
                            "Slot config sent (" + slots + " slots, " + slotSec + " s)",
                            Toast.LENGTH_LONG).show();
                    updateSummaries();
                } else {
                    Toast.makeText(ctx, error, Toast.LENGTH_LONG).show();
                }
                break;
            default:
                break;
        }
    }

    private void wireRestorePreferenceHandlers() {
        Preference restoreAll = findPreference(KEY_RESTORE_ALL_DEFAULTS);
        if (restoreAll != null) {
            restoreAll.setSummary("");
            attachPreferencePillClickHandler(restoreAll);
        }
        Preference restoreBeacon = findPreference(KEY_RESTORE_BEACON_DEFAULTS);
        if (restoreBeacon != null) {
            restoreBeacon.setSummary("");
            attachPreferencePillClickHandler(restoreBeacon);
        }
        Preference restoreAdmin = findPreference(KEY_RESTORE_ADMIN_DEFAULTS);
        if (restoreAdmin != null) {
            restoreAdmin.setSummary("");
            attachPreferencePillClickHandler(restoreAdmin);
        }
    }

    private void showRestoreConfirmDialog(String title, Runnable onConfirm) {
        Runnable show = () -> {
            Context dialogCtx = getActivity();
            if (dialogCtx == null && MapView.getMapView() != null) {
                dialogCtx = MapView.getMapView().getContext();
            }
            if (dialogCtx == null) {
                dialogCtx = resolveSettingsContext();
            }
            if (dialogCtx == null || onConfirm == null) {
                return;
            }
            try {
                new AlertDialog.Builder(dialogCtx)
                        .setTitle(title)
                        .setMessage("Are you sure?")
                        .setPositiveButton("Confirm", (dialog, which) -> onConfirm.run())
                        .setNegativeButton("Cancel", null)
                        .show();
            } catch (Exception e) {
                android.util.Log.e("UVPro.Settings", "Restore confirm dialog failed", e);
            }
        };
        if (getActivity() != null) {
            getActivity().runOnUiThread(show);
        } else {
            show.run();
        }
    }

    private void wireBeaconPreferences() {
        syncSmartBeaconPreferenceValues();
    }

    private void restoreBeaconDefaults(Context ctx) {
        if (ctx == null) {
            return;
        }
        getPrefs(ctx).edit()
                .putString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL)
                .apply();
        setListPreferenceValue(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL);
        SmartBeacon.setEnabled(ctx, SmartBeacon.DEFAULT_ENABLED);
        SmartBeacon.saveAll(ctx,
                SmartBeacon.DEFAULT_LOW_SPEED,
                SmartBeacon.DEFAULT_HIGH_SPEED,
                SmartBeacon.DEFAULT_SLOW_RATE,
                SmartBeacon.DEFAULT_FAST_RATE,
                SmartBeacon.DEFAULT_MIN_TURN_TIME,
                SmartBeacon.DEFAULT_TURN_THRESHOLD,
                SmartBeacon.DEFAULT_TURN_SLOPE);
        refreshSettingsUiAfterRestore(ctx);
    }

    private void restoreAllDefaults(Context ctx) {
        if (ctx == null) {
            return;
        }
        getPrefs(ctx).edit()
                .putString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL)
                .putBoolean(PREF_PING_REPLY_ENABLED, DEFAULT_PING_REPLY_ENABLED)
                .putString(PREF_RETRY_INTERVAL_MIN, DEFAULT_RETRY_INTERVAL_MIN)
                .putString(PREF_RETRY_MAX, DEFAULT_RETRY_MAX)
                .putString(PREF_APRS_CALLSIGN, "")
                .putString(PREF_APRS_SSID, DEFAULT_APRS_SSID)
                .putString(PREF_APRS_SYMBOL_TABLE, "/")
                .putString(PREF_APRS_SYMBOL_CODE, ">")
                .putBoolean(PREF_APRS_ICON_SELECTED, false)
                .putString(PREF_APRS_MESSAGE, "")
                .putString(PREF_ENCRYPTION_PASSPHRASE, "")
                .putBoolean(PREF_ENCRYPTION_ENABLED, false)
                .putBoolean(PREF_SA_RELAY_ENABLED, false)
                .putBoolean(PREF_RF_TO_TAK_UPLINK_ENABLED, false)
                .putBoolean(PREF_DISABLE_MESH_BEACON_LIMITING, false)
                .putString(NetSlotConfig.PREF_SLOT_COUNT,
                        String.valueOf(NetSlotConfig.DEFAULT_SLOT_COUNT))
                .putString(NetSlotConfig.PREF_SLOT_TIME_SEC,
                        String.valueOf(NetSlotConfig.DEFAULT_SLOT_TIME_SEC))
                .apply();
        SmartBeacon.setEnabled(ctx, SmartBeacon.DEFAULT_ENABLED);
        SmartBeacon.saveAll(ctx,
                SmartBeacon.DEFAULT_LOW_SPEED,
                SmartBeacon.DEFAULT_HIGH_SPEED,
                SmartBeacon.DEFAULT_SLOW_RATE,
                SmartBeacon.DEFAULT_FAST_RATE,
                SmartBeacon.DEFAULT_MIN_TURN_TIME,
                SmartBeacon.DEFAULT_TURN_THRESHOLD,
                SmartBeacon.DEFAULT_TURN_SLOPE);
        AdminAccessGate.lock(ctx);
        setListPreferenceValue(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL);
        setCheckBoxPreferenceValue(PREF_PING_REPLY_ENABLED, DEFAULT_PING_REPLY_ENABLED);
        setListPreferenceValue(PREF_RETRY_INTERVAL_MIN, DEFAULT_RETRY_INTERVAL_MIN);
        setListPreferenceValue(PREF_RETRY_MAX, DEFAULT_RETRY_MAX);
        setEditTextPreferenceText(PREF_APRS_CALLSIGN, "");
        setListPreferenceValue(PREF_APRS_SSID, DEFAULT_APRS_SSID);
        setEditTextPreferenceText(PREF_APRS_MESSAGE, "");
        setEditTextPreferenceText(PREF_ENCRYPTION_PASSPHRASE, "");
        setCheckBoxPreferenceValue(PREF_SA_RELAY_ENABLED, false);
        setCheckBoxPreferenceValue(PREF_RF_TO_TAK_UPLINK_ENABLED, false);
        setCheckBoxPreferenceValue(PREF_DISABLE_MESH_BEACON_LIMITING, false);
        setCheckBoxPreferenceValue(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false);
        setEditTextPreferenceText(NetSlotConfig.PREF_SLOT_COUNT,
                String.valueOf(NetSlotConfig.DEFAULT_SLOT_COUNT));
        setEditTextPreferenceText(NetSlotConfig.PREF_SLOT_TIME_SEC,
                String.valueOf(NetSlotConfig.DEFAULT_SLOT_TIME_SEC));
        refreshSettingsUiAfterRestore(ctx);
    }

    private void restoreAdminDefaults(Context ctx) {
        if (ctx == null) {
            return;
        }
        getPrefs(ctx).edit()
                .putBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false)
                .putBoolean(PREF_DISABLE_MESH_BEACON_LIMITING, false)
                .putBoolean(PREF_SA_RELAY_ENABLED, false)
                .putBoolean(PREF_RF_TO_TAK_UPLINK_ENABLED, false)
                .putString(NetSlotConfig.PREF_SLOT_COUNT,
                        String.valueOf(NetSlotConfig.DEFAULT_SLOT_COUNT))
                .putString(NetSlotConfig.PREF_SLOT_TIME_SEC,
                        String.valueOf(NetSlotConfig.DEFAULT_SLOT_TIME_SEC))
                .apply();
        AdminAccessGate.lock(ctx);
        setCheckBoxPreferenceValue(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false);
        setCheckBoxPreferenceValue(PREF_DISABLE_MESH_BEACON_LIMITING, false);
        setCheckBoxPreferenceValue(PREF_SA_RELAY_ENABLED, false);
        setCheckBoxPreferenceValue(PREF_RF_TO_TAK_UPLINK_ENABLED, false);
        setEditTextPreferenceText(NetSlotConfig.PREF_SLOT_COUNT,
                String.valueOf(NetSlotConfig.DEFAULT_SLOT_COUNT));
        setEditTextPreferenceText(NetSlotConfig.PREF_SLOT_TIME_SEC,
                String.valueOf(NetSlotConfig.DEFAULT_SLOT_TIME_SEC));
        refreshSettingsUiAfterRestore(ctx);
    }

    private void refreshSettingsUiAfterRestore(Context ctx) {
        syncAllPreferencesFromAtakToUi();
        syncSmartBeaconPreferenceValues();
        syncAdminSettingsGateOnResume();
        updateAdminControlsEnabled();
        updateDependentPreferences();
        updateSummaries();
        Preference aprsIconPref = findPreference(KEY_APRS_ICON);
        if (aprsIconPref != null) {
            updateAprsIconSummary(aprsIconPref);
        }
        com.uvpro.plugin.protocol.UVProRadioServices.syncEncryptionFromSettings(ctx);
        notifyRuntimeSettingsChanged();
        ListView list = getPreferenceListView();
        if (list != null) {
            list.post(this::applyRowStyles);
        }
    }

    private static String defaultStringForPreferenceKey(String key) {
        if (PREF_BEACON_INTERVAL.equals(key)) {
            return DEFAULT_BEACON_INTERVAL;
        }
        if (PREF_RETRY_INTERVAL_MIN.equals(key)) {
            return DEFAULT_RETRY_INTERVAL_MIN;
        }
        if (PREF_RETRY_MAX.equals(key)) {
            return DEFAULT_RETRY_MAX;
        }
        if (PREF_APRS_SSID.equals(key)) {
            return DEFAULT_APRS_SSID;
        }
        return "";
    }

    private String readStringPrefFromAtak(String key, String defaultValue) {
        Context ctx = resolveSettingsContext();
        if (ctx == null) {
            return defaultValue;
        }
        SharedPreferences prefs = getPrefs(ctx);
        if (!prefs.contains(key)) {
            return defaultValue;
        }
        Object raw = prefs.getAll().get(key);
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof String) {
            String value = ((String) raw).trim();
            return value.isEmpty() ? defaultValue : value;
        }
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Float) {
            return String.valueOf(raw);
        }
        if (raw instanceof Boolean) {
            return ((Boolean) raw) ? "true" : "false";
        }
        return defaultValue;
    }

    private void syncListPreferenceFromAtak(String key, String defaultValue) {
        setListPreferenceValue(key, readStringPrefFromAtak(key, defaultValue));
    }

    private void syncEditTextPreferenceFromAtak(String key) {
        setEditTextPreferenceText(key, readStringPrefFromAtak(key, ""));
    }

    private void setListPreferenceValue(String key, String value) {
        Preference pref = findPreference(key);
        if (pref instanceof ListPreference) {
            ((ListPreference) pref).setValue(value);
        }
    }

    private void setCheckBoxPreferenceValue(String key, boolean checked) {
        Preference pref = findPreference(key);
        if (pref instanceof CheckBoxPreference) {
            CheckBoxPreference check = (CheckBoxPreference) pref;
            if (check.isChecked() != checked) {
                check.setChecked(checked);
            }
        }
    }

    private static boolean defaultForCheckboxKey(String key) {
        if (PREF_PING_REPLY_ENABLED.equals(key)) {
            return DEFAULT_PING_REPLY_ENABLED;
        }
        return false;
    }

    /** ATAK default SharedPreferences are the runtime source of truth for checkbox prefs. */
    private boolean readBooleanPrefFromAtak(String key, boolean defaultValue) {
        Context ctx = resolveSettingsContext();
        if (ctx == null) {
            return defaultValue;
        }
        return getPrefs(ctx).getBoolean(key, defaultValue);
    }

    private void syncCheckBoxPreferenceFromAtak(String key, boolean defaultValue) {
        setCheckBoxPreferenceValue(key, readBooleanPrefFromAtak(key, defaultValue));
    }

    private void syncSmartBeaconPreferenceValues() {
        Context ctx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : staticPluginContext;
        if (ctx == null) {
            return;
        }
        setEditTextPreferenceText(SmartBeacon.KEY_LOW_SPEED,
                String.valueOf(SmartBeacon.getLowSpeed(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_HIGH_SPEED,
                String.valueOf(SmartBeacon.getHighSpeed(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_SLOW_RATE,
                String.valueOf(SmartBeacon.getSlowRate(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_FAST_RATE,
                String.valueOf(SmartBeacon.getFastRate(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_MIN_TURN_TIME,
                String.valueOf(SmartBeacon.getMinTurnTime(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_TURN_THRESHOLD,
                String.valueOf(SmartBeacon.getTurnThreshold(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_TURN_SLOPE,
                String.valueOf(SmartBeacon.getTurnSlope(ctx)));
    }

    private void setEditTextPreferenceText(String key, String value) {
        Preference pref = findPreference(key);
        if (pref instanceof EditTextPreference) {
            ((EditTextPreference) pref).setText(value != null ? value : "");
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        normalizeBeaconSection();
        normalizeAprsSection();
        normalizeSaRelaySection();
        normalizeAdministrationSection();
        normalizeAllRestoreControls();
        wireRestorePreferenceHandlers();
        ensureAdminCheckboxPreferences();
        ensureSaRelayCheckboxPreferences();
        wireCheckBoxPreference(PREF_SA_RELAY_ENABLED, false);
        wireCheckBoxPreference(PREF_RF_TO_TAK_UPLINK_ENABLED, false);
        wireCheckBoxPreference(PREF_DISABLE_MESH_BEACON_LIMITING, false);
        updateAdminControlsEnabled();
        refreshPreferenceList();
        ListView list = getPreferenceListView();
        if (list != null) {
            list.setItemsCanFocus(true);
            scheduleApplyRowStyles();
            list.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    scheduleApplyRowStyles();
                }

                @Override
                public void onChildViewRemoved(View parent, View child) {
                }
            });
            list.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                    if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                        scheduleApplyRowStyles();
                    }
                }

                @Override
                public void onScroll(AbsListView view, int firstVisibleItem,
                                     int visibleItemCount, int totalItemCount) {
                }
            });
            attachRowStylePreDrawListener(list);
            list.post(this::applyRowStyles);
        }
    }

    /**
     * ATAK ListView rows swallow embedded {@link Button} taps — route pill prefs here instead.
     */
    @Override
    public boolean onPreferenceTreeClick(android.preference.PreferenceScreen preferenceScreen,
                                         Preference preference) {
        if (preference != null && preference.getKey() != null
                && isPillActionPreferenceKey(preference.getKey())) {
            dispatchPillActionClick(preference.getKey());
            return true;
        }
        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    /** Pan onBindView runs during layout — re-apply after bind so icon gutters stay collapsed. */
    private void attachRowStylePreDrawListener(ListView list) {
        if (list == null || rowStylePreDrawListenerAttached) {
            return;
        }
        rowStylePreDrawListenerAttached = true;
        ViewTreeObserver observer = list.getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                scheduleApplyRowStyles();
                return true;
            }
        });
    }

    private ListView getPreferenceListView() {
        View root = getView();
        if (root == null) {
            return null;
        }
        return root.findViewById(android.R.id.list);
    }

    private Runnable pendingRowStyleApply;
    private Runnable pendingValueSummaryRebind;
    private boolean rowStylePreDrawListenerAttached;

    private void scheduleApplyRowStyles() {
        ListView list = getPreferenceListView();
        if (list == null) {
            return;
        }
        if (pendingRowStyleApply == null) {
            pendingRowStyleApply = this::applyRowStyles;
        }
        if (pendingValueSummaryRebind == null) {
            pendingValueSummaryRebind = this::rebindAllVisibleValueSummaries;
        }
        list.removeCallbacks(pendingRowStyleApply);
        list.removeCallbacks(pendingValueSummaryRebind);
        list.post(pendingRowStyleApply);
        list.post(pendingValueSummaryRebind);
    }

    private void applyRowStyles() {
        ListView list = getPreferenceListView();
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            try {
                View row = list.getChildAt(i);
                Preference pillPref = resolvePillActionPreferenceForRow(list, i, row);
                if (pillPref != null) {
                    String pillLabel = pillLabelForPreference(pillPref);
                    if (!hasStablePillTitleRow(row, pillPref, pillLabel)) {
                        stylePillButtonRowForPreference(row, pillPref);
                    } else {
                        TextView pillTitle = row.findViewById(android.R.id.title);
                        if (pillTitle != null) {
                            applyPillTitleStyle(pillTitle, pillPref.isEnabled());
                        }
                        attachPreferencePillClickHandler(pillPref);
                    }
                    continue;
                }
                Preference pref = resolvePreferenceForVisibleRow(list, i, row);
                if (pref != null) {
                    stylePreferenceRow(row, pref);
                } else {
                    forceLeftAlignRow(row);
                }
            } catch (Exception e) {
                android.util.Log.w("UVPro.Settings", "applyRowStyles failed for row " + i, e);
            }
        }
        rebindAllVisibleValueSummaries();
    }

    /** List position first — must match on-screen display order (sorted by preference order). */
    private Preference resolvePillActionPreferenceForRow(ListView list, int childIndex, View row) {
        if (row == null) {
            return null;
        }
        Preference byPosition = resolvePreferenceForListRow(list, childIndex);
        if (byPosition != null && isPillActionPreferenceKey(byPosition.getKey())) {
            return byPosition;
        }
        Object keyTag = row.getTag(ROW_PREF_KEY_TAG);
        if (keyTag instanceof String && isPillActionPreferenceKey((String) keyTag)) {
            Preference keyed = findPreference((String) keyTag);
            if (keyed != null) {
                return keyed;
            }
        }
        TextView titleView = row.findViewById(android.R.id.title);
        if (titleView != null && titleView.getText() != null) {
            CharSequence rowTitle = titleView.getText();
            if ("Restore All Defaults".contentEquals(rowTitle)) {
                return findPreference(KEY_RESTORE_ALL_DEFAULTS);
            }
            if ("Restore Defaults".contentEquals(rowTitle)) {
                return inferRestoreDefaultsPreference(list, childIndex);
            }
        }
        return null;
    }

    private Preference inferRestoreDefaultsPreference(ListView list, int childIndex) {
        Preference byPos = resolvePreferenceForListRow(list, childIndex);
        if (byPos != null) {
            String key = byPos.getKey();
            if (KEY_RESTORE_BEACON_DEFAULTS.equals(key) || KEY_RESTORE_ADMIN_DEFAULTS.equals(key)) {
                return byPos;
            }
        }
        int position = list.getFirstVisiblePosition() + childIndex;
        List<Preference> flat = buildFlatPreferenceList();
        for (int i = Math.min(position, flat.size() - 1); i >= 0; i--) {
            Preference pref = flat.get(i);
            if (!(pref instanceof PreferenceCategory)) {
                continue;
            }
            if (KEY_CAT_ADMINISTRATION.equals(pref.getKey())) {
                return findPreference(KEY_RESTORE_ADMIN_DEFAULTS);
            }
            if (KEY_CAT_BEACON.equals(pref.getKey())) {
                return findPreference(KEY_RESTORE_BEACON_DEFAULTS);
            }
        }
        return null;
    }

    private void refreshPreferenceList() {
        ListView list = getPreferenceListView();
        if (list == null) {
            return;
        }
        android.widget.ListAdapter adapter = list.getAdapter();
        if (adapter instanceof android.widget.BaseAdapter) {
            ((android.widget.BaseAdapter) adapter).notifyDataSetChanged();
        }
        list.requestLayout();
    }

    private Preference resolvePreferenceForVisibleRow(ListView list, int childIndex, View row) {
        Preference byTitle = resolvePreferenceForRow(row);
        if (byTitle != null && isPillActionPreferenceKey(byTitle.getKey())) {
            return byTitle;
        }
        Preference byPosition = resolvePreferenceForListRow(list, childIndex);
        if (byPosition != null) {
            return byPosition;
        }
        return byTitle;
    }

    private static boolean isPillActionPreferenceKey(String key) {
        return KEY_RESTORE_ALL_DEFAULTS.equals(key)
                || KEY_RESTORE_BEACON_DEFAULTS.equals(key)
                || KEY_RESTORE_ADMIN_DEFAULTS.equals(key)
                || KEY_DISTRIBUTE_NET_SLOTS.equals(key);
    }

    private Context resolveContextForRow(View row) {
        Context ctx = resolveSettingsContext();
        if (ctx != null) {
            return ctx;
        }
        if (row != null && row.getContext() != null) {
            return row.getContext();
        }
        return staticPluginContext;
    }

    /** List/edit prefs and other rows with a green current-value line under the description. */
    private boolean usesValueSummaryLine(Preference pref) {
        if (pref == null || pref.getKey() == null || pref instanceof PreferenceCategory) {
            return false;
        }
        String key = pref.getKey();
        if (pref instanceof CheckBoxPreference || isCheckboxPreferenceKey(key)) {
            return false;
        }
        if (KEY_SMART_BEACON_SECTION_HEADER.equals(key)
                || KEY_SA_RELAY_SECTION_HEADER.equals(key)
                || KEY_REPLY_SLOT_TIMES_SECTION_HEADER.equals(key)
                || KEY_ADMIN_LEADERSHIP_WARNING.equals(key)
                || KEY_DISTRIBUTE_NET_WARNING.equals(key)
                || KEY_RESTORE_BEACON_DEFAULTS.equals(key)
                || KEY_RESTORE_ALL_DEFAULTS.equals(key)
                || KEY_RESTORE_ADMIN_DEFAULTS.equals(key)
                || KEY_DISTRIBUTE_NET_SLOTS.equals(key)) {
            return false;
        }
        return KEY_APRS_ICON.equals(key) || getDescriptionForPreferenceKey(key) != null;
    }

    private void rebindAllVisibleValueSummaries() {
        ListView list = getPreferenceListView();
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            try {
                View row = list.getChildAt(i);
                if (hasVisibleEmbeddedPillButton(row)) {
                    continue;
                }
                Preference pref = resolvePreferenceForListRow(list, i);
                if (pref == null) {
                    pref = resolvePreferenceForRow(row);
                }
                if (pref == null || !usesValueSummaryLine(pref)) {
                    continue;
                }
                TextView title = row.findViewById(android.R.id.title);
                TextView summary = row.findViewById(android.R.id.summary);
                bindStyledSummary(summary, row, pref);
                if (title != null) {
                    title.setTextColor(pref.isEnabled() ? COLOR_WHITE : COLOR_DISABLED_GREY);
                    title.setEnabled(true);
                    title.setAlpha(1f);
                }
            } catch (Exception e) {
                android.util.Log.w("UVPro.Settings", "rebindAllVisibleValueSummaries failed for row "
                        + i, e);
            }
        }
    }

    /** Prefer list adapter position — title matching breaks on recycled rows (e.g. Max Retries). */
    private Preference resolvePreferenceForListRow(ListView list, int childIndex) {
        if (list == null) {
            return null;
        }
        int position = list.getFirstVisiblePosition() + childIndex;
        List<Preference> flat = buildFlatPreferenceList();
        if (position < 0 || position >= flat.size()) {
            return null;
        }
        return flat.get(position);
    }

    private List<Preference> buildFlatPreferenceList() {
        List<Preference> flat = new ArrayList<>();
        android.preference.PreferenceScreen screen = getPreferenceScreen();
        if (screen != null) {
            flattenPreferenceGroupInDisplayOrder(screen, flat);
        }
        return flat;
    }

    /** Match ListView row indices — children are sorted by {@link Preference#getOrder()}. */
    private static void flattenPreferenceGroupInDisplayOrder(PreferenceGroup group,
                                                             List<Preference> out) {
        List<Preference> children = new ArrayList<>();
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference pref = group.getPreference(i);
            if (pref != null) {
                children.add(pref);
            }
        }
        java.util.Collections.sort(children, (left, right) -> {
            int order = Integer.compare(left.getOrder(), right.getOrder());
            if (order != 0) {
                return order;
            }
            return left.getTitle() != null && right.getTitle() != null
                    ? left.getTitle().toString().compareTo(right.getTitle().toString())
                    : 0;
        });
        for (Preference pref : children) {
            out.add(pref);
            if (pref instanceof PreferenceGroup) {
                flattenPreferenceGroupInDisplayOrder((PreferenceGroup) pref, out);
            }
        }
    }

    /** Match row to preference — pill rows hide title, so prefer row key tag then title. */
    private Preference resolvePreferenceForRow(View row) {
        if (row == null) {
            return null;
        }
        Object keyTag = row.getTag(ROW_PREF_KEY_TAG);
        if (keyTag instanceof String) {
            Preference keyed = findPreference((String) keyTag);
            if (keyed != null) {
                return keyed;
            }
        }
        TextView titleView = row.findViewById(android.R.id.title);
        if (titleView != null && titleView.getText() != null) {
            CharSequence rowTitle = titleView.getText();
            if ("Restore All Defaults".contentEquals(rowTitle)) {
                return findPreference(KEY_RESTORE_ALL_DEFAULTS);
            }
            if ("Restore Defaults".contentEquals(rowTitle)) {
                ListView list = getPreferenceListView();
                if (list != null) {
                    for (int i = 0; i < list.getChildCount(); i++) {
                        if (list.getChildAt(i) == row) {
                            Preference inferred = inferRestoreDefaultsPreference(list, i);
                            if (inferred != null) {
                                return inferred;
                            }
                            break;
                        }
                    }
                }
                return null;
            }
            if ("Distribute to net".contentEquals(rowTitle)) {
                return findPreference(KEY_DISTRIBUTE_NET_SLOTS);
            }
            if (titleView.getVisibility() == View.VISIBLE) {
                return findPreferenceByTitle(rowTitle.toString());
            }
        }
        return null;
    }

    private static boolean titleMatchesPreference(View row, Preference pref) {
        if (row == null || pref == null || pref.getTitle() == null) {
            return false;
        }
        TextView titleView = row.findViewById(android.R.id.title);
        if (titleView == null || titleView.getText() == null) {
            return false;
        }
        return titleView.getText().toString().equals(pref.getTitle().toString());
    }

    private static boolean hasVisibleEmbeddedPillButton(View row) {
        Button pill = findEmbeddedPillButton(row);
        return pill != null && pill.getVisibility() == View.VISIBLE;
    }

    private static Button findEmbeddedPillButton(View root) {
        if (!(root instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Button && EMBEDDED_PILL_BUTTON_TAG.equals(child.getTag())) {
                return (Button) child;
            }
            if (child instanceof ViewGroup) {
                Button nested = findEmbeddedPillButton(child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static String pillLabelForPreference(Preference pref) {
        if (pref == null || pref.getKey() == null) {
            return "";
        }
        switch (pref.getKey()) {
            case KEY_RESTORE_ALL_DEFAULTS:
                return "Restore All Defaults";
            case KEY_RESTORE_BEACON_DEFAULTS:
            case KEY_RESTORE_ADMIN_DEFAULTS:
                return "Restore Defaults";
            case KEY_DISTRIBUTE_NET_SLOTS:
                return "Distribute to net";
            default:
                return pref.getTitle() != null ? pref.getTitle().toString() : "";
        }
    }

    private static boolean hasStablePillTitleRow(View row, Preference pref, String label) {
        if (row == null || pref == null || pref.getKey() == null || label == null) {
            return false;
        }
        if (!pref.getKey().equals(row.getTag(ROW_PREF_KEY_TAG))) {
            return false;
        }
        TextView title = row.findViewById(android.R.id.title);
        return title != null
                && title.getVisibility() == View.VISIBLE
                && label.contentEquals(title.getText())
                && title.getBackground() != null;
    }

    private void attachPreferencePillClickHandler(Preference pref) {
        if (pref == null || pref.getKey() == null) {
            return;
        }
        final String key = pref.getKey();
        pref.setOnPreferenceClickListener(preference -> {
            dispatchPillActionClick(key);
            return true;
        });
        preferenceClickHandlers.put(key, preference -> {
            dispatchPillActionClick(key);
            return true;
        });
    }

    private Preference findPreferenceByTitle(String title) {
        if (title == null || title.isEmpty()) {
            return null;
        }
        android.preference.PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return null;
        }
        return findPreferenceByTitle(screen, title);
    }

    private Preference findPreferenceByTitle(PreferenceGroup group, String title) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference pref = group.getPreference(i);
            if (pref.getTitle() != null && title.equals(pref.getTitle().toString())) {
                return pref;
            }
            if (pref instanceof PreferenceGroup) {
                Preference nested = findPreferenceByTitle((PreferenceGroup) pref, title);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static final float DISABLED_ROW_ALPHA = 0.38f;

    private void applyGatedRowVisualState(View row, Preference pref) {
        if (row == null || pref == null) {
            return;
        }
        row.setAlpha(pref.isEnabled() ? 1f : DISABLED_ROW_ALPHA);
    }

    private void stylePreferenceRow(View row, Preference pref) {
        if (row == null || pref == null) {
            stripRowLeadingInset(row);
            return;
        }
        if (pref.getKey() != null) {
            row.setTag(ROW_PREF_KEY_TAG, pref.getKey());
        }
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        if (pref instanceof PreferenceCategory) {
            styleCategoryRow(row, title, summary);
            return;
        }
        if (KEY_RESTORE_BEACON_DEFAULTS.equals(pref.getKey())) {
            stylePillActionButtonRow(row, pref, "Restore Defaults");
            row.setAlpha(1f);
            return;
        }
        if (KEY_RESTORE_ADMIN_DEFAULTS.equals(pref.getKey())) {
            stylePillActionButtonRow(row, pref, "Restore Defaults");
            row.setAlpha(1f);
            return;
        }
        if (KEY_RESTORE_ALL_DEFAULTS.equals(pref.getKey())) {
            stylePillActionButtonRow(row, pref, "Restore All Defaults");
            row.setAlpha(1f);
            return;
        }
        if (KEY_DISTRIBUTE_NET_SLOTS.equals(pref.getKey())) {
            styleDistributeNetButtonRow(row, pref);
            row.setAlpha(1f);
            return;
        }
        forceLeftAlignRow(row);
        if (KEY_SMART_BEACON_SECTION_HEADER.equals(pref.getKey())
                || KEY_SA_RELAY_SECTION_HEADER.equals(pref.getKey())
                || KEY_REPLY_SLOT_TIMES_SECTION_HEADER.equals(pref.getKey())) {
            styleBlueSectionHeaderRow(row, pref);
            applyGatedRowVisualState(row, pref);
            return;
        }
        if (KEY_DISTRIBUTE_NET_WARNING.equals(pref.getKey())) {
            styleDistributeNetWarningRow(row, pref);
            row.setAlpha(1f);
            return;
        }
        if (KEY_ADMIN_LEADERSHIP_WARNING.equals(pref.getKey())) {
            styleAdminLeadershipWarningRow(row, pref);
            row.setAlpha(1f);
            return;
        }
        if (NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(pref.getKey())) {
            styleCheckBoxPreferenceRow(row, pref);
            row.setAlpha(1f);
            return;
        }
        if (PREF_DISABLE_MESH_BEACON_LIMITING.equals(pref.getKey())) {
            styleCheckBoxPreferenceRow(row, pref);
            applyGatedRowVisualState(row, pref);
            return;
        }
        if (PREF_RF_TO_TAK_UPLINK_ENABLED.equals(pref.getKey())
                || PREF_SA_RELAY_ENABLED.equals(pref.getKey())) {
            styleCheckBoxPreferenceRow(row, pref);
            applyGatedRowVisualState(row, pref);
            return;
        }
        if (isCheckboxPreference(pref)) {
            styleCheckBoxPreferenceRow(row, pref);
            applyGatedRowVisualState(row, pref);
            return;
        }
        resetStandardPreferenceRow(row);
        if (title != null) {
            resetStandardRowTitle(title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, PREFERENCE_TITLE_TEXT_SP);
            title.setTypeface(Typeface.DEFAULT);
            title.setTextColor(pref.isEnabled() ? COLOR_WHITE : COLOR_DISABLED_GREY);
        }
        if (summary != null) {
            resetStandardSummary(summary);
            bindStyledSummary(summary, row, pref);
        }
        applyGatedRowVisualState(row, pref);
    }

    /** Yellow category headers only — centered. */
    private void styleCategoryRow(View row, TextView title, TextView summary) {
        Context ctx = resolveSettingsContext();
        if (ctx == null && row != null) {
            ctx = row.getContext();
        }
        int edgePad = dp(ctx, 16);
        row.setPaddingRelative(edgePad, row.getPaddingTop(), edgePad, row.getPaddingBottom());
        if (row instanceof LinearLayout) {
            ((LinearLayout) row).setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        }
        if (title != null) {
            styleCategoryTitle(title);
            centerTitleInRow(title);
            title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        }
        if (summary != null) {
            summary.setVisibility(View.GONE);
        }
    }

    /** Pill action rows — full-width button, centered in the list row. */
    private void styleCenteredPillButtonRow(View row) {
        if (row == null) {
            return;
        }
        Context ctx = resolveSettingsContext();
        if (ctx == null) {
            ctx = row.getContext();
        }
        int edgePad = dp(ctx, 16);
        row.setPaddingRelative(edgePad, row.getPaddingTop(), edgePad, row.getPaddingBottom());
        if (row instanceof LinearLayout) {
            ((LinearLayout) row).setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        }
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setGravity(Gravity.CENTER);
            title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        }
    }

    /** Flush-left rows: collapse ATAK icon wrapper (48dp minWidth), content margin, title reuse. */
    private void stripRowLeadingInset(View row) {
        if (row == null) {
            return;
        }
        removeHiddenEmbeddedPillButtons(row);
        Context ctx = resolveSettingsContext();
        if (ctx == null) {
            ctx = row.getContext();
        }
        int edgePad = dp(ctx, 16);
        row.setPaddingRelative(edgePad, row.getPaddingTop(), edgePad, row.getPaddingBottom());

        if (row instanceof LinearLayout) {
            ((LinearLayout) row).setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        }

        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        View contentColumn = findRowContentColumn(row, title);
        collapseLeadingRowSlots(row, contentColumn);
        collapseIconFrameById(row, ctx);
        hidePanListArrowIndicator(row);

        if (contentColumn != null) {
            zeroHorizontalInset(contentColumn);
            if (contentColumn instanceof LinearLayout) {
                ((LinearLayout) contentColumn).setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            }
            ViewGroup.LayoutParams colLp = contentColumn.getLayoutParams();
            if (colLp != null) {
                zeroHorizontalMargins(colLp);
                if (colLp instanceof LinearLayout.LayoutParams && row instanceof LinearLayout) {
                    LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) colLp;
                    llp.width = 0;
                    llp.weight = 1f;
                    llp.gravity = Gravity.START;
                }
                contentColumn.setLayoutParams(colLp);
            }
        }

        resetStandardRowTitle(title);
        resetStandardSummary(summary);
        clearNonCheckboxWidgets(row);
    }

    /** Title lives inside RelativeLayout/LinearLayout between row root and TextView. */
    private static View findRowContentColumn(View row, TextView title) {
        if (row == null || title == null) {
            return null;
        }
        View column = title;
        while (column.getParent() instanceof View) {
            View parent = (View) column.getParent();
            if (parent == row) {
                return column;
            }
            column = parent;
        }
        return title;
    }

    /** ATAK child prefs use preference_holo: icon sits in a minWidth wrapper before content. */
    private static void collapseLeadingRowSlots(View row, View contentColumn) {
        if (!(row instanceof ViewGroup) || contentColumn == null) {
            return;
        }
        ViewGroup rowGroup = (ViewGroup) row;
        for (int i = 0; i < rowGroup.getChildCount(); i++) {
            View child = rowGroup.getChildAt(i);
            if (child == contentColumn) {
                break;
            }
            collapseHorizontalSlot(child);
        }
    }

    private static void collapseIconFrameById(View row, Context ctx) {
        if (row == null || ctx == null) {
            return;
        }
        int iconFrameId = ctx.getResources().getIdentifier("icon_frame", "id", "android");
        if (iconFrameId != 0) {
            collapseHorizontalSlot(row.findViewById(iconFrameId));
        }
        collapseHorizontalSlot(row.findViewById(android.R.id.icon));
    }

    /** Pan list prefs append a trailing arrow ImageView — values live in the summary line. */
    private static void hidePanListArrowIndicator(View row) {
        if (!(row instanceof ViewGroup)) {
            return;
        }
        ViewGroup rowGroup = (ViewGroup) row;
        View widgetFrame = row.findViewById(android.R.id.widget_frame);
        for (int i = 0; i < rowGroup.getChildCount(); i++) {
            View child = rowGroup.getChildAt(i);
            if (child instanceof ImageView
                    && child.getId() != android.R.id.icon
                    && child != widgetFrame
                    && !(child instanceof ViewGroup)) {
                collapseHorizontalSlot(child);
            }
        }
    }

    private void forceLeftAlignRow(View row) {
        stripRowLeadingInset(row);
    }

    private static void zeroHorizontalInset(View view) {
        if (view == null) {
            return;
        }
        view.setPaddingRelative(0, view.getPaddingTop(), 0, view.getPaddingBottom());
        view.setPadding(0, view.getPaddingTop(), 0, view.getPaddingBottom());
        zeroHorizontalMargins(view.getLayoutParams());
    }

    private static void zeroHorizontalMargins(ViewGroup.LayoutParams lp) {
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            mlp.setMarginStart(0);
            mlp.setMarginEnd(0);
            mlp.leftMargin = 0;
            mlp.rightMargin = 0;
        }
    }

    private static void collapseHorizontalSlot(View view) {
        if (view == null) {
            return;
        }
        view.setVisibility(View.GONE);
        view.setMinimumWidth(0);
        view.setMinimumHeight(0);
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            lp.width = 0;
            lp.height = 0;
            zeroHorizontalMargins(lp);
            view.setLayoutParams(lp);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collapseHorizontalSlot(group.getChildAt(i));
            }
        }
    }

    /** Administrative leadership note — centered white title and summary. */
    private void styleAdminLeadershipWarningRow(View row, Preference pref) {
        removeEmbeddedPillButtons(row);
        resetStandardPreferenceRow(row);
        Context ctx = resolveSettingsContext();
        if (ctx == null && row != null) {
            ctx = row.getContext();
        }
        int edgePad = dp(ctx, 16);
        row.setPaddingRelative(edgePad, row.getPaddingTop(), edgePad, row.getPaddingBottom());
        if (row instanceof LinearLayout) {
            ((LinearLayout) row).setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        }
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        View contentColumn = findRowContentColumn(row, title);
        if (contentColumn instanceof LinearLayout) {
            ((LinearLayout) contentColumn).setGravity(
                    Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        }
        if (title != null) {
            centerTitleInRow(title);
            title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, PREFERENCE_TITLE_TEXT_SP);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(COLOR_WHITE);
        }
        if (summary != null) {
            summary.setGravity(Gravity.CENTER_HORIZONTAL);
            summary.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            ViewGroup.LayoutParams lp = summary.getLayoutParams();
            if (lp != null) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                summary.setLayoutParams(lp);
            }
            CharSequence summaryText = buildStyledSummaryForPreference(pref);
            if (summaryText == null || summaryText.length() == 0) {
                summaryText = pref.getSummary();
            }
            if (summaryText != null && summaryText.length() > 0) {
                summary.setText(summaryText, TextView.BufferType.SPANNABLE);
                summary.setVisibility(View.VISIBLE);
                summary.setEnabled(true);
                summary.setAlpha(1f);
            }
        }
    }

    private void styleCheckBoxPreferenceRow(View row, Preference pref) {
        resetStandardPreferenceRow(row);
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        if (title != null) {
            resetStandardRowTitle(title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, PREFERENCE_TITLE_TEXT_SP);
            title.setTypeface(Typeface.DEFAULT);
            title.setTextColor(pref.isEnabled() ? COLOR_WHITE : COLOR_DISABLED_GREY);
        }
        if (summary != null) {
            resetStandardSummary(summary);
            bindStyledSummary(summary, row, pref);
        }
        ensureCheckBoxWidgetVisible(row, pref);
    }

    /** Always show a green checkbox on the right — Pan widgets often omit or hide it on bind. */
    private void ensureCheckBoxWidgetVisible(View row, Preference pref) {
        if (!(pref instanceof CheckBoxPreference) || row == null) {
            return;
        }
        View widgetFrame = row.findViewById(android.R.id.widget_frame);
        if (!(widgetFrame instanceof ViewGroup)) {
            return;
        }
        widgetFrame.setVisibility(View.VISIBLE);
        ViewGroup widgetGroup = (ViewGroup) widgetFrame;
        CheckBoxPreference checkPref = (CheckBoxPreference) pref;
        boolean checked = readBooleanPrefFromAtak(
                checkPref.getKey(), defaultForCheckboxKey(checkPref.getKey()));
        if (PREF_RF_TO_TAK_UPLINK_ENABLED.equals(checkPref.getKey())) {
            Context ctx = resolveSettingsContext();
            if (ctx != null) {
                checked = isRfToTakUplinkEnabled(ctx);
            }
        }
        if (checkPref.isChecked() != checked) {
            checkPref.setChecked(checked);
        }
        CheckBox box = resolveRowCheckBox(widgetGroup);
        box.setChecked(checked);
        box.setEnabled(pref.isEnabled());
        try {
            box.setButtonTintList(ColorStateList.valueOf(
                    pref.isEnabled() ? COLOR_VALUE_GREEN : COLOR_DISABLED_GREY));
        } catch (Exception ignored) {
        }
    }

    /** Prefer the framework/Pan checkbox; avoid duplicate embedded boxes that desync on scroll. */
    private CheckBox resolveRowCheckBox(ViewGroup widgetGroup) {
        CheckBox embedded = null;
        CheckBox framework = null;
        for (int i = 0; i < widgetGroup.getChildCount(); i++) {
            View child = widgetGroup.getChildAt(i);
            if (!(child instanceof CheckBox)) {
                continue;
            }
            if (EMBEDDED_CHECKBOX_TAG.equals(child.getTag())) {
                embedded = (CheckBox) child;
            } else {
                framework = (CheckBox) child;
            }
        }
        if (framework != null) {
            if (embedded != null) {
                widgetGroup.removeView(embedded);
            }
            framework.setFocusable(false);
            framework.setClickable(false);
            return framework;
        }
        if (embedded != null) {
            return embedded;
        }
        CheckBox box = new CheckBox(widgetGroup.getContext());
        box.setTag(EMBEDDED_CHECKBOX_TAG);
        box.setFocusable(false);
        box.setClickable(false);
        widgetGroup.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void styleBlueSectionHeaderRow(View row, Preference pref) {
        resetStandardPreferenceRow(row);
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        if (title != null) {
            resetStandardRowTitle(title);
            title.setTextColor(pref.isEnabled() ? COLOR_STD_BLUE : COLOR_DISABLED_GREY);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, PREFERENCE_TITLE_TEXT_SP);
            title.setTypeface(Typeface.DEFAULT_BOLD);
        }
        if (summary != null) {
            resetStandardSummary(summary);
            summary.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            bindStyledSummary(summary, row, pref);
            summary.setVisibility(View.VISIBLE);
        }
    }

    private void resetStandardPreferenceRow(View row) {
        if (row == null) {
            return;
        }
        restoreStandardRowChildVisibility(row);
        stripRowLeadingInset(row);
    }

    /** List prefs leave a value TextView in widget_frame — remove it so rows match EditText prefs. */
    private static void clearNonCheckboxWidgets(View row) {
        View widgetFrame = row.findViewById(android.R.id.widget_frame);
        if (!(widgetFrame instanceof ViewGroup)) {
            return;
        }
        ViewGroup widgetGroup = (ViewGroup) widgetFrame;
        for (int i = 0; i < widgetGroup.getChildCount(); i++) {
            View child = widgetGroup.getChildAt(i);
            if (child instanceof CheckBox && EMBEDDED_CHECKBOX_TAG.equals(child.getTag())) {
                return;
            }
        }
        for (int i = widgetGroup.getChildCount() - 1; i >= 0; i--) {
            View child = widgetGroup.getChildAt(i);
            if (!(child instanceof CompoundButton)) {
                widgetGroup.removeViewAt(i);
            }
        }
        if (widgetGroup.getChildCount() == 0) {
            collapseHorizontalSlot(widgetFrame);
        }
    }

    /** Recycled list rows may still have pill-only visibility from a prior bind. */
    private static void restoreStandardRowChildVisibility(View row) {
        if (!(row instanceof ViewGroup)) {
            return;
        }
        removeHiddenEmbeddedPillButtons(row);
        ViewGroup rowGroup = (ViewGroup) row;
        for (int i = 0; i < rowGroup.getChildCount(); i++) {
            View child = rowGroup.getChildAt(i);
            if (child instanceof Button && EMBEDDED_PILL_BUTTON_TAG.equals(child.getTag())) {
                rowGroup.removeViewAt(i);
                i--;
                continue;
            }
            child.setVisibility(View.VISIBLE);
        }
    }

    private void styleDistributeNetWarningRow(View row, Preference pref) {
        removeEmbeddedPillButtons(row);
        resetStandardPreferenceRow(row);
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        if (title != null) {
            resetStandardRowTitle(title);
            title.setTextColor(pref.isEnabled() ? COLOR_WARNING_RED : COLOR_DISABLED_GREY);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, PREFERENCE_TITLE_TEXT_SP);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setVisibility(View.VISIBLE);
            if (titleMatchesPreference(row, pref)) {
                title.setText(DISTRIBUTE_NET_WARNING);
            }
        }
        if (summary != null) {
            summary.setVisibility(View.GONE);
        }
    }

    private void styleDistributeNetButtonRow(View row, Preference pref) {
        stylePillActionButtonRow(row, pref, "Distribute to net");
        TextView title = row.findViewById(android.R.id.title);
        distributeNetButtonRowView = title != null ? title : row;
    }

    /** Recycled pill rows keep the prior button label unless rebound by preference key. */
    private void stylePillButtonRowForPreference(View row, Preference pref) {
        if (pref == null) {
            styleCenteredPillButtonRow(row);
            return;
        }
        if (pref.getKey() != null) {
            row.setTag(ROW_PREF_KEY_TAG, pref.getKey());
        }
        String key = pref.getKey();
        if (KEY_DISTRIBUTE_NET_SLOTS.equals(key)) {
            styleDistributeNetButtonRow(row, pref);
            row.setAlpha(1f);
        } else if (KEY_RESTORE_BEACON_DEFAULTS.equals(key)) {
            stylePillActionButtonRow(row, pref, "Restore Defaults");
            row.setAlpha(1f);
        } else if (KEY_RESTORE_ADMIN_DEFAULTS.equals(key)) {
            stylePillActionButtonRow(row, pref, "Restore Defaults");
            row.setAlpha(1f);
        } else if (KEY_RESTORE_ALL_DEFAULTS.equals(key)) {
            stylePillActionButtonRow(row, pref, "Restore All Defaults");
            row.setAlpha(1f);
        } else {
            styleCenteredPillButtonRow(row);
        }
    }

    /**
     * Style the preference title as a pill — embedded {@link Button} widgets do not receive taps
     * inside ATAK's preference {@link ListView}.
     */
    private void stylePillActionButtonRow(View row, Preference pref, String label) {
        Context ctx = resolveContextForRow(row);
        if (ctx == null || row == null || pref == null || pref.getKey() == null) {
            return;
        }
        removeEmbeddedPillButtons(row);
        row.setTag(ROW_PREF_KEY_TAG, pref.getKey());

        int edgePad = dp(ctx, 16);
        int vMargin = dp(ctx, PILL_BUTTON_ROW_MARGIN_VERTICAL_DP);
        row.setPaddingRelative(edgePad, vMargin, edgePad, vMargin);
        row.setBackgroundColor(0);

        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        View contentColumn = findRowContentColumn(row, title);
        collapseLeadingRowSlots(row, contentColumn);
        collapseIconFrameById(row, ctx);
        if (contentColumn instanceof LinearLayout) {
            ((LinearLayout) contentColumn).setGravity(Gravity.CENTER_HORIZONTAL);
        }
        if (summary != null) {
            summary.setVisibility(View.GONE);
        }
        View icon = row.findViewById(android.R.id.icon);
        if (icon != null) {
            icon.setVisibility(View.GONE);
        }
        View widgetFrame = row.findViewById(android.R.id.widget_frame);
        if (widgetFrame != null) {
            widgetFrame.setVisibility(View.GONE);
        }
        if (title != null) {
            title.setVisibility(View.VISIBLE);
            title.setText(label);
            applyPillTitleStyle(title, pref.isEnabled());
        }
        styleCenteredPillButtonRow(row);
        pref.setSelectable(true);
        pref.setPersistent(false);
        attachPreferencePillClickHandler(pref);
    }

    private static void applyPillTitleStyle(TextView title, boolean enabled) {
        if (title == null) {
            return;
        }
        Context ctx = title.getContext();
        title.setTextColor(COLOR_WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, PILL_BUTTON_TEXT_SP);
        title.setTypeface(Typeface.DEFAULT);
        title.setMinHeight(dp(ctx, PILL_BUTTON_MIN_HEIGHT_DP));
        int hPad = dp(ctx, PILL_BUTTON_PAD_HORIZONTAL_DP);
        int vPad = dp(ctx, PILL_BUTTON_PAD_VERTICAL_DP);
        title.setPadding(hPad, vPad, hPad, vPad);
        title.setBackground(buildPillButtonBackground(ctx,
                enabled ? COLOR_PILL_BUTTON_PRIMARY : COLOR_DISABLED_GREY,
                enabled ? COLOR_PILL_BUTTON_STROKE : COLOR_DISABLED_GREY,
                enabled ? 2 : 0));
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        ViewGroup.LayoutParams lp = title.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            title.setLayoutParams(lp);
        }
    }

    private static void removeEmbeddedPillButtons(View root) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child instanceof Button && EMBEDDED_PILL_BUTTON_TAG.equals(child.getTag())) {
                group.removeViewAt(i);
            } else {
                removeEmbeddedPillButtons(child);
            }
        }
    }

    /** Recycled pill rows leave hidden buttons that block left-align styling. */
    private static void removeHiddenEmbeddedPillButtons(View root) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child instanceof Button
                    && EMBEDDED_PILL_BUTTON_TAG.equals(child.getTag())
                    && child.getVisibility() != View.VISIBLE) {
                group.removeViewAt(i);
            } else if (child instanceof ViewGroup) {
                removeHiddenEmbeddedPillButtons(child);
            }
        }
    }

    private static GradientDrawable buildPillButtonBackground(Context ctx, int fillColor,
                                                     int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(ctx, PILL_CORNER_RADIUS_DP));
        drawable.setColor(fillColor);
        if (strokeDp > 0) {
            drawable.setStroke(dp(ctx, strokeDp), strokeColor);
        }
        return drawable;
    }

    private static void applyPillButtonStyle(Button button, boolean enabled) {
        if (button == null) {
            return;
        }
        Context ctx = button.getContext();
        button.setTextColor(COLOR_WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, PILL_BUTTON_TEXT_SP);
        button.setTypeface(Typeface.DEFAULT);
        button.setAllCaps(false);
        button.setMinHeight(dp(ctx, PILL_BUTTON_MIN_HEIGHT_DP));
        button.setPadding(dp(ctx, PILL_BUTTON_PAD_HORIZONTAL_DP), dp(ctx, PILL_BUTTON_PAD_VERTICAL_DP),
                dp(ctx, PILL_BUTTON_PAD_HORIZONTAL_DP), dp(ctx, PILL_BUTTON_PAD_VERTICAL_DP));
        button.setBackground(buildPillButtonBackground(ctx,
                enabled ? COLOR_PILL_BUTTON_PRIMARY : COLOR_DISABLED_GREY,
                enabled ? COLOR_PILL_BUTTON_STROKE : COLOR_DISABLED_GREY,
                enabled ? 2 : 0));
    }

    private static void pulseDistributeButtonFeedback(View target, boolean enabledAfterPulse) {
        if (target == null) {
            return;
        }
        try {
            target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (Exception ignored) {
        }
        stopDistributeButtonPulse(false);
        distributeButtonPulseTarget = target;
        distributeButtonPulseRestoreEnabled = enabledAfterPulse;
        Context ctx = target.getContext();
        if (MapView.getMapView() != null && MapView.getMapView().getContext() != null) {
            ctx = MapView.getMapView().getContext();
        } else if (ctx == null && staticPluginContext != null) {
            ctx = staticPluginContext;
        }
        if (ctx == null) {
            return;
        }
        final Context pulseCtx = ctx;
        distributeButtonPulseDrawable = buildPillButtonBackground(
                pulseCtx, COLOR_PILL_BUTTON_PRIMARY, 0x00FFEB3B, PILL_PULSE_STROKE_DP);
        target.setBackground(distributeButtonPulseDrawable);
        distributeButtonPulseAnimator = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                0x11FFEB3B,
                0xFFFFEB3B);
        distributeButtonPulseAnimator.setDuration(220L);
        distributeButtonPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        distributeButtonPulseAnimator.setRepeatCount(4);
        distributeButtonPulseAnimator.addUpdateListener(animation -> {
            if (distributeButtonPulseDrawable == null || distributeButtonPulseTarget == null) {
                return;
            }
            int color = (Integer) animation.getAnimatedValue();
            distributeButtonPulseDrawable.setStroke(dp(pulseCtx, PILL_PULSE_STROKE_DP), color);
            distributeButtonPulseTarget.invalidate();
        });
        distributeButtonPulseAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                stopDistributeButtonPulse(true);
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                stopDistributeButtonPulse(true);
            }
        });
        distributeButtonPulseAnimator.start();
    }

    private static void stopDistributeButtonPulse(boolean restoreBackground) {
        ValueAnimator animator = distributeButtonPulseAnimator;
        distributeButtonPulseAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
        distributeButtonPulseDrawable = null;
        View target = distributeButtonPulseTarget;
        distributeButtonPulseTarget = null;
        if (restoreBackground && target != null) {
            Context ctx = target.getContext();
            if (MapView.getMapView() != null && MapView.getMapView().getContext() != null) {
                ctx = MapView.getMapView().getContext();
            }
            target.setBackground(buildPillButtonBackground(ctx,
                    distributeButtonPulseRestoreEnabled
                            ? COLOR_PILL_BUTTON_PRIMARY : COLOR_DISABLED_GREY,
                    distributeButtonPulseRestoreEnabled
                            ? COLOR_PILL_BUTTON_STROKE : COLOR_DISABLED_GREY,
                    distributeButtonPulseRestoreEnabled ? 2 : 0));
        }
    }

    private void clearRedundantListValueWidget(View row, Preference pref) {
        if (!(pref instanceof ListPreference)) {
            return;
        }
        View widgetFrame = row.findViewById(android.R.id.widget_frame);
        if (!(widgetFrame instanceof ViewGroup)) {
            return;
        }
        ViewGroup widgetGroup = (ViewGroup) widgetFrame;
        for (int i = widgetGroup.getChildCount() - 1; i >= 0; i--) {
            View child = widgetGroup.getChildAt(i);
            if (!(child instanceof CompoundButton)) {
                widgetGroup.removeViewAt(i);
            }
        }
    }

    private void centerTitleInRow(TextView title) {
        if (title == null) {
            return;
        }
        title.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        ViewGroup.LayoutParams lp = title.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            title.setLayoutParams(lp);
        }
    }

    private void resetStandardRowTitle(TextView title) {
        if (title == null) {
            return;
        }
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        ViewGroup.LayoutParams lp = title.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) lp).setMarginStart(0);
            }
            title.setLayoutParams(lp);
        }
    }

    private void resetStandardSummary(TextView summary) {
        if (summary == null) {
            return;
        }
        summary.setGravity(Gravity.START);
        summary.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        ViewGroup.LayoutParams lp = summary.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) lp).setMarginStart(0);
            }
            summary.setLayoutParams(lp);
        }
    }

    private void styleCategoryTitle(TextView title) {
        title.setTextColor(COLOR_CATEGORY_YELLOW);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, CATEGORY_TITLE_TEXT_SP);
        title.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private void refreshSummaryIfMatched(TextView summary, View row, Preference pref) {
        bindStyledSummary(summary, row, pref);
    }

    /**
     * Rebuild white/green summary spannables on every bind — Pan widgets reset summary TextView
     * to theme grey and drop the green value line after {@link #updateSummaries()}.
     */
    private void bindStyledSummary(TextView summary, View row, Preference pref) {
        if (summary == null || pref == null) {
            return;
        }
        if (Boolean.TRUE.equals(summary.getTag(SUMMARY_REBIND_TAG))) {
            return;
        }
        summary.setTag(SUMMARY_REBIND_TAG, Boolean.TRUE);
        try {
            if (pref.getKey() != null && row != null) {
                row.setTag(ROW_PREF_KEY_TAG, pref.getKey());
            }
            CharSequence styledSummary = buildStyledSummaryForPreference(pref);
            if (styledSummary == null || styledSummary.length() == 0) {
                styledSummary = pref.getSummary();
            }
            if (styledSummary == null || styledSummary.length() == 0) {
                return;
            }
            if (!styledSummary.equals(pref.getSummary())) {
                pref.setSummary(styledSummary);
            }
            summary.setText(styledSummary, TextView.BufferType.SPANNABLE);
            summary.setVisibility(View.VISIBLE);
            summary.setEnabled(true);
            summary.setAlpha(1f);
            ensureSummaryTextWatcher(summary, row);
        } finally {
            summary.setTag(SUMMARY_REBIND_TAG, null);
        }
    }

    /** Pan onBindView resets summary to plain grey XML — re-apply white/green when that happens. */
    private void ensureSummaryTextWatcher(TextView summary, View row) {
        if (summary == null || row == null) {
            return;
        }
        if (summary.getTag(SUMMARY_WATCHER_TAG) instanceof TextWatcher) {
            return;
        }
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (Boolean.TRUE.equals(summary.getTag(SUMMARY_REBIND_TAG))) {
                    return;
                }
                if (summaryHasGreenValueLine(s)) {
                    return;
                }
                Object keyTag = row.getTag(ROW_PREF_KEY_TAG);
                if (!(keyTag instanceof String)) {
                    return;
                }
                Preference boundPref = findPreference((String) keyTag);
                if (boundPref == null || !usesValueSummaryLine(boundPref)) {
                    return;
                }
                bindStyledSummary(summary, row, boundPref);
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        };
        summary.addTextChangedListener(watcher);
        summary.setTag(SUMMARY_WATCHER_TAG, watcher);
    }

    private static boolean summaryHasGreenValueLine(CharSequence text) {
        if (!(text instanceof Spanned)) {
            return false;
        }
        Spanned spanned = (Spanned) text;
        ForegroundColorSpan[] spans = spanned.getSpans(0, spanned.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : spans) {
            if (span.getForegroundColor() == COLOR_VALUE_GREEN) {
                return true;
            }
        }
        return false;
    }

    private void removeObsoletePreferences() {
        removePreferenceFromScreen(SmartBeacon.KEY_ENABLED);
        removePreferenceFromScreen(PREF_PING_REPLY_SAME_TRANSPORT);
        removePreferenceFromScreen(KEY_UNLOCK_ADMIN);
        removePreferenceFromScreen(PREF_ENCRYPTION_ENABLED);
        removePreferenceFromScreen("uvpro_sa_relay_header");
    }

    /**
     * ATAK can persist stale preference rows across plugin updates. Keep smart beacon
     * header placement; restore buttons are rebuilt in {@link #normalizeAllRestoreControls()}.
     */
    private void normalizeBeaconSection() {
        PreferenceCategory beacon = (PreferenceCategory) findPreference(KEY_CAT_BEACON);
        if (beacon == null) {
            return;
        }
        for (String key : REMOVE_FROM_BEACON_KEYS) {
            Preference pref = findPreference(key);
            if (pref != null && pref.getParent() == beacon) {
                beacon.removePreference(pref);
            }
        }
        ensureSmartBeaconSectionHeader(beacon);
    }

    /** Drop non-APRS prefs that ATAK can persist under APRS Settings. */
    private void normalizeAprsSection() {
        PreferenceCategory aprs = (PreferenceCategory) findPreference(KEY_CAT_APRS);
        if (aprs == null) {
            return;
        }
        for (String key : REMOVE_FROM_APRS_KEYS) {
            Preference pref = findPreference(key);
            if (pref != null && pref.getParent() == aprs) {
                aprs.removePreference(pref);
            }
        }
    }

    /** Keep SA Relay prefs under the SA Relay category after plugin updates. */
    private void normalizeSaRelaySection() {
        PreferenceCategory saRelay = (PreferenceCategory) findPreference(KEY_CAT_SA_RELAY);
        if (saRelay == null) {
            return;
        }
        for (String key : REMOVE_FROM_SA_RELAY_KEYS) {
            Preference pref = findPreference(key);
            if (pref != null && pref.getParent() == saRelay) {
                saRelay.removePreference(pref);
            }
        }
        migrateSaRelayPreferencesToCategory(saRelay);
        ensureSaRelaySectionHeader(saRelay);
    }

    private void migrateSaRelayPreferencesToCategory(PreferenceCategory saRelay) {
        if (saRelay == null) {
            return;
        }
        String[] keys = {
                KEY_SA_RELAY_SECTION_HEADER,
                PREF_SA_RELAY_ENABLED,
                PREF_RF_TO_TAK_UPLINK_ENABLED,
        };
        for (String key : keys) {
            Preference pref = findPreference(key);
            if (pref == null || pref.getParent() == saRelay) {
                continue;
            }
            if (pref.getParent() != null) {
                pref.getParent().removePreference(pref);
            }
            saRelay.addPreference(pref);
        }
    }

    private void ensureSaRelaySectionHeader(PreferenceCategory saRelay) {
        if (saRelay == null) {
            return;
        }
        Context ctx = getActivity() != null ? getActivity() : staticPluginContext;
        Preference header = findPreference(KEY_SA_RELAY_SECTION_HEADER);
        if (header == null) {
            if (ctx == null) {
                return;
            }
            header = new Preference(ctx);
            header.setKey(KEY_SA_RELAY_SECTION_HEADER);
            header.setTitle("SA Relay");
            header.setSummary(SA_RELAY_SECTION_DESC);
            header.setSelectable(false);
            header.setPersistent(false);
            header.setEnabled(true);
        } else if (header.getParent() != null && header.getParent() != saRelay) {
            header.getParent().removePreference(header);
        }
        if (header.getParent() == saRelay) {
            saRelay.removePreference(header);
        }
        Preference saRelayToggle = findPreference(PREF_SA_RELAY_ENABLED);
        int order = saRelayToggle != null && saRelayToggle.getParent() == saRelay
                ? saRelayToggle.getOrder() - 1
                : Preference.DEFAULT_ORDER;
        header.setOrder(order);
        saRelay.addPreference(header);
    }

    /**
     * ATAK can persist stale preference rows across plugin updates. Keep one distribute
     * block at the bottom of Administrative Settings; restore buttons are rebuilt in
     * {@link #normalizeAllRestoreControls()}.
     */
    private void normalizeAdministrationSection() {
        PreferenceCategory admin = (PreferenceCategory) findPreference(KEY_CAT_ADMINISTRATION);
        if (admin == null) {
            return;
        }
        for (String key : REMOVE_FROM_ADMIN_KEYS) {
            Preference pref = findPreference(key);
            if (pref != null && pref.getParent() == admin) {
                admin.removePreference(pref);
            }
        }
        dedupeAdminPreferencesByTitle(admin, "Distribute to net", KEY_DISTRIBUTE_NET_SLOTS);
        dedupeAdminPreferencesByTitle(admin, DISTRIBUTE_NET_WARNING, KEY_DISTRIBUTE_NET_WARNING);
        reorderAdministrationFooter(admin);
    }

    /**
     * ATAK can duplicate restore rows (often without stable keys) across plugin updates.
     * Wipe every restore control in the tree, then inject exactly three: global, beacon,
     * and admin section restores.
     */
    private void normalizeAllRestoreControls() {
        android.preference.PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return;
        }
        removeAllRestoreControlsFromTree(screen);
        ensureGlobalRestoreAllAtScreenRoot();
        PreferenceCategory beacon = (PreferenceCategory) findPreference(KEY_CAT_BEACON);
        if (beacon != null) {
            ensureBeaconRestoreDefaultsPreference(beacon);
        }
        PreferenceCategory admin = (PreferenceCategory) findPreference(KEY_CAT_ADMINISTRATION);
        if (admin != null) {
            ensureAdminRestoreDefaultsPreference(admin);
        }
        wireRestorePreferenceHandlers();
    }

    private static boolean isRestoreControlPreference(Preference pref) {
        if (pref == null) {
            return false;
        }
        String key = pref.getKey();
        if (KEY_RESTORE_ALL_DEFAULTS.equals(key)
                || KEY_RESTORE_BEACON_DEFAULTS.equals(key)
                || KEY_RESTORE_ADMIN_DEFAULTS.equals(key)) {
            return true;
        }
        CharSequence title = pref.getTitle();
        if (title == null) {
            return false;
        }
        return "Restore All Defaults".contentEquals(title)
                || "Restore Defaults".contentEquals(title);
    }

    private void removeAllRestoreControlsFromTree(PreferenceGroup group) {
        if (group == null) {
            return;
        }
        List<Preference> remove = new ArrayList<>();
        collectRestoreControls(group, remove);
        for (Preference pref : remove) {
            PreferenceGroup parent = pref.getParent();
            if (parent != null) {
                parent.removePreference(pref);
            }
        }
    }

    private void collectRestoreControls(PreferenceGroup group, List<Preference> remove) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference pref = group.getPreference(i);
            if (pref == null) {
                continue;
            }
            if (pref instanceof PreferenceGroup) {
                collectRestoreControls((PreferenceGroup) pref, remove);
            }
            if (isRestoreControlPreference(pref)) {
                remove.add(pref);
            }
        }
    }

    /** Global restore belongs at the top of the screen, never inside a category. */
    private void ensureGlobalRestoreAllAtScreenRoot() {
        android.preference.PreferenceScreen screen = getPreferenceScreen();
        Context ctx = resolveSettingsContext();
        if (screen == null || ctx == null) {
            return;
        }
        Preference restoreAll = findPreference(KEY_RESTORE_ALL_DEFAULTS);
        if (restoreAll != null && restoreAll.getParent() != null) {
            ((PreferenceGroup) restoreAll.getParent()).removePreference(restoreAll);
        }
        if (restoreAll == null) {
            restoreAll = new Preference(ctx);
            restoreAll.setKey(KEY_RESTORE_ALL_DEFAULTS);
            restoreAll.setTitle("Restore All Defaults");
        }
        restoreAll.setSummary("");
        restoreAll.setPersistent(false);
        restoreAll.setSelectable(true);
        restoreAll.setEnabled(true);
        int topOrder = Preference.DEFAULT_ORDER;
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference pref = screen.getPreference(i);
            if (pref != null) {
                topOrder = Math.min(topOrder, pref.getOrder());
            }
        }
        restoreAll.setOrder(topOrder - 10000);
        screen.addPreference(restoreAll);
    }

    private void dedupeAdminPreferencesByTitle(PreferenceCategory admin, String title,
                                                      String keepKey) {
        if (admin == null || title == null) {
            return;
        }
        List<Preference> remove = new ArrayList<>();
        for (int i = 0; i < admin.getPreferenceCount(); i++) {
            Preference pref = admin.getPreference(i);
            if (pref == null || pref.getTitle() == null) {
                continue;
            }
            if (!title.contentEquals(pref.getTitle())) {
                continue;
            }
            if (keepKey != null && keepKey.equals(pref.getKey())) {
                continue;
            }
            remove.add(pref);
        }
        for (Preference pref : remove) {
            admin.removePreference(pref);
        }
    }

    /** Slot fields, red warning, then Distribute to net — always last in the category. */
    private void reorderAdministrationFooter(PreferenceCategory admin) {
        if (admin == null) {
            return;
        }
        int order = 0;
        for (int i = 0; i < admin.getPreferenceCount(); i++) {
            Preference pref = admin.getPreference(i);
            if (pref != null) {
                order = Math.max(order, pref.getOrder());
            }
        }
        for (String key : ADMIN_FOOTER_PREF_KEYS) {
            Preference pref = findPreference(key);
            if (pref == null || pref.getParent() != admin) {
                continue;
            }
            admin.removePreference(pref);
            pref.setOrder(++order);
            admin.addPreference(pref);
        }
    }

    private void ensureAdminRestoreDefaultsPreference(PreferenceCategory admin) {
        if (admin == null) {
            return;
        }
        Context ctx = resolveSettingsContext();
        if (ctx == null) {
            return;
        }
        Preference restore = findPreference(KEY_RESTORE_ADMIN_DEFAULTS);
        if (restore != null && restore.getParent() != null && restore.getParent() != admin) {
            ((PreferenceGroup) restore.getParent()).removePreference(restore);
        }
        if (restore == null) {
            restore = new Preference(ctx);
            restore.setKey(KEY_RESTORE_ADMIN_DEFAULTS);
            restore.setTitle("Restore Defaults");
        }
        restore.setSummary("");
        restore.setPersistent(false);
        restore.setSelectable(true);
        int minOrder = Preference.DEFAULT_ORDER;
        for (int i = 0; i < admin.getPreferenceCount(); i++) {
            Preference pref = admin.getPreference(i);
            if (pref != null && pref != restore) {
                minOrder = Math.min(minOrder, pref.getOrder());
            }
        }
        restore.setOrder(minOrder - 1);
        if (restore.getParent() != admin) {
            admin.addPreference(restore);
        }
    }

    private void ensureBeaconRestoreDefaultsPreference(PreferenceCategory beacon) {
        if (beacon == null) {
            return;
        }
        Context ctx = resolveSettingsContext();
        if (ctx == null) {
            return;
        }
        Preference restore = findPreference(KEY_RESTORE_BEACON_DEFAULTS);
        if (restore != null && restore.getParent() != null && restore.getParent() != beacon) {
            ((PreferenceGroup) restore.getParent()).removePreference(restore);
        }
        if (restore == null) {
            restore = new Preference(ctx);
            restore.setKey(KEY_RESTORE_BEACON_DEFAULTS);
            restore.setTitle("Restore Defaults");
        }
        restore.setSummary("");
        restore.setPersistent(false);
        restore.setSelectable(true);
        int minOrder = Preference.DEFAULT_ORDER;
        for (int i = 0; i < beacon.getPreferenceCount(); i++) {
            Preference pref = beacon.getPreference(i);
            if (pref != null && pref != restore) {
                minOrder = Math.min(minOrder, pref.getOrder());
            }
        }
        restore.setOrder(minOrder - 1);
        if (restore.getParent() != beacon) {
            beacon.addPreference(restore);
        }
    }

    private void ensureSmartBeaconSectionHeader(PreferenceCategory beacon) {
        if (beacon == null) {
            return;
        }
        Context ctx = getActivity() != null ? getActivity() : staticPluginContext;
        Preference header = findPreference(KEY_SMART_BEACON_SECTION_HEADER);
        if (header == null) {
            if (ctx == null) {
                return;
            }
            header = new Preference(ctx);
            header.setKey(KEY_SMART_BEACON_SECTION_HEADER);
            header.setTitle("Smart Beacon Settings");
            header.setSummary(SMART_BEACON_SECTION_DESC);
            header.setSelectable(false);
            header.setPersistent(false);
            header.setEnabled(true);
        } else if (header.getParent() != null && header.getParent() != beacon) {
            header.getParent().removePreference(header);
        }
        if (header.getParent() == beacon) {
            beacon.removePreference(header);
        }
        Preference lowSpeed = findPreference(SmartBeacon.KEY_LOW_SPEED);
        int order;
        if (lowSpeed != null && lowSpeed.getParent() == beacon) {
            order = lowSpeed.getOrder() - 1;
        } else {
            Preference beaconInterval = findPreference(PREF_BEACON_INTERVAL);
            order = beaconInterval != null
                    ? beaconInterval.getOrder() + 1
                    : Preference.DEFAULT_ORDER;
        }
        header.setOrder(order);
        beacon.addPreference(header);
    }

    private void removePreferenceFromScreen(String key) {
        Preference pref = findPreference(key);
        if (pref == null) {
            return;
        }
        PreferenceGroup parent = pref.getParent();
        if (parent != null) {
            parent.removePreference(pref);
        } else {
            android.preference.PreferenceScreen screen = getPreferenceScreen();
            if (screen != null) {
                screen.removePreference(pref);
            }
        }
    }

    /**
     * Some ATAK builds drop custom Pan* prefs from XML inflation. Inject any missing
     * required controls before preference binding runs in {@code onActivityCreated}.
     */
    private void ensureRequiredPreferences() {
        PreferenceCategory radio = (PreferenceCategory) findPreference(KEY_CAT_RADIO);
        if (radio != null) {
            ensureCheckBoxPreferenceOrReplace(radio, PREF_PING_REPLY_ENABLED,
                    "Send Ping Reply",
                    "Automatically reply to incoming pings with your position",
                    DEFAULT_PING_REPLY_ENABLED);
        }
    }

    /** Admin mesh beacon limit — force checkbox widgets after the activity exists. */
    private void ensureAdminCheckboxPreferences() {
        PreferenceCategory admin = (PreferenceCategory) findPreference(KEY_CAT_ADMINISTRATION);
        if (admin == null) {
            return;
        }
        forceCheckBoxPreference(admin, PREF_DISABLE_MESH_BEACON_LIMITING,
                "Disable Mesh Beacon Limiting",
                DISABLE_MESH_BEACON_LIMITING_DESC,
                false);
    }

    /** SA Relay checkboxes — always available under the SA Relay category. */
    private void ensureSaRelayCheckboxPreferences() {
        PreferenceCategory saRelay = (PreferenceCategory) findPreference(KEY_CAT_SA_RELAY);
        if (saRelay == null) {
            return;
        }
        forceCheckBoxPreference(saRelay, PREF_SA_RELAY_ENABLED,
                "Enable SA Relay",
                "Re-broadcast inbound network positions over radio to off-grid users. "
                        + "Throttled to one update per contact per 30 seconds.",
                false);
        forceCheckBoxPreference(saRelay, PREF_RF_TO_TAK_UPLINK_ENABLED,
                "Enable RF to TAK Uplink Relay",
                "When SA Relay is enabled, forward inbound RF CoT to TAK network. "
                        + "Use with care to avoid unintended rebroadcast loops.",
                false);
    }

    /** Ensure admin SA Relay / RF uplink prefs are PanCheckBoxPreference with saved state. */
    private void forceCheckBoxPreference(PreferenceGroup parent, String key, String title,
                                         String summary, boolean defaultValue) {
        if (parent == null) {
            return;
        }
        Context ctx = getActivity() != null ? getActivity() : staticPluginContext;
        if (ctx == null) {
            return;
        }
        Preference existing = findPreference(key);
        boolean checked = defaultValue;
        Context prefsCtx = resolveSettingsContext();
        if (prefsCtx != null) {
            checked = getPrefs(prefsCtx).getBoolean(key, defaultValue);
        } else if (existing instanceof CheckBoxPreference) {
            checked = ((CheckBoxPreference) existing).isChecked();
        }
        CheckBoxPreference pref;
        if (existing instanceof CheckBoxPreference) {
            pref = (CheckBoxPreference) existing;
            pref.setTitle(title);
            pref.setSummary(summary);
        } else {
            int order = existing != null ? existing.getOrder() : Preference.DEFAULT_ORDER;
            if (existing != null && existing.getParent() != null) {
                existing.getParent().removePreference(existing);
            }
            PanCheckBoxPreference created = new PanCheckBoxPreference(ctx);
            created.setKey(key);
            created.setTitle(title);
            created.setSummary(summary);
            created.setDefaultValue(defaultValue);
            created.setOrder(order);
            created.setPersistent(true);
            parent.addPreference(created);
            pref = created;
        }
        pref.setChecked(checked);
        pref.setPersistent(false);
        applyAdminCheckboxEnabledState(pref, key);
    }

    private void applyAdminCheckboxEnabledState(CheckBoxPreference pref, String key) {
        if (pref == null) {
            return;
        }
        Context ctx = resolveSettingsContext();
        if (NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(key)) {
            applyPreferenceEnabled(pref, true);
            return;
        }
        if (PREF_SA_RELAY_ENABLED.equals(key)) {
            applyPreferenceEnabled(pref, true);
            return;
        }
        if (PREF_RF_TO_TAK_UPLINK_ENABLED.equals(key)) {
            applyPreferenceEnabled(pref, true);
            return;
        }
        if (PREF_DISABLE_MESH_BEACON_LIMITING.equals(key)) {
            applyPreferenceEnabled(pref, isAdminSectionUnlocked(ctx));
        }
    }

    private boolean isSaRelayEnabledForUi(Context ctx) {
        if (ctx != null && isSaRelayEnabled(ctx)) {
            return true;
        }
        Preference saPref = findPreference(PREF_SA_RELAY_ENABLED);
        if (saPref instanceof CheckBoxPreference) {
            return ((CheckBoxPreference) saPref).isChecked();
        }
        return false;
    }

    private void ensureCheckBoxPreferenceOrReplace(PreferenceGroup parent, String key,
                                                   String title, String summary,
                                                   boolean defaultValue) {
        Preference existing = findPreference(key);
        if (existing instanceof CheckBoxPreference) {
            return;
        }
        if (existing != null && existing.getParent() != null) {
            existing.getParent().removePreference(existing);
        }
        ensureCheckBoxPreference(parent, key, title, summary, defaultValue);
    }

    private void ensureCheckBoxPreference(PreferenceGroup parent, String key, String title,
                                          String summary, boolean defaultValue) {
        if (parent == null || findPreference(key) != null) {
            return;
        }
        Context ctx = getActivity() != null ? getActivity() : staticPluginContext;
        if (ctx == null) {
            return;
        }
        PanCheckBoxPreference pref = new PanCheckBoxPreference(ctx);
        pref.setKey(key);
        pref.setTitle(title);
        pref.setSummary(summary);
        pref.setDefaultValue(defaultValue);
        pref.setPersistent(false);
        parent.addPreference(pref);
    }

    private void updateDependentPreferences() {
        Context ctx = resolveSettingsContext();
        boolean saRelayOn = isSaRelayEnabledForUi(ctx);
        Preference rfUplinkPref = findPreference(PREF_RF_TO_TAK_UPLINK_ENABLED);
        if (rfUplinkPref != null) {
            applyPreferenceEnabled(rfUplinkPref, true);
            if (!saRelayOn && rfUplinkPref instanceof CheckBoxPreference) {
                if (readBooleanPrefFromAtak(PREF_RF_TO_TAK_UPLINK_ENABLED, false)) {
                    syncCheckBoxPreferenceFromAtak(PREF_RF_TO_TAK_UPLINK_ENABLED, false);
                    persistBooleanPrefToAtak(PREF_RF_TO_TAK_UPLINK_ENABLED, false);
                }
            }
        }
        ListView list = getPreferenceListView();
        if (list != null) {
            list.post(this::applyRowStyles);
        }
    }

    /**
     * Removes the legacy "Bluetooth Devices" (favorites manager) preference if a saved
     * preference XML or older install still surfaces it. Favorites have been retired.
     */
    private void ensureBluetoothDevicesPreference() {
        try {
            Preference existing = findPreference(KEY_BLUETOOTH_DEVICES);
            if (existing == null) return;
            PreferenceGroup parent = (PreferenceGroup) findPreference("uvpro_cat_bluetooth");
            if (parent == null) parent = (PreferenceGroup) findPreference(KEY_CAT_RADIO);
            if (parent != null) {
                parent.removePreference(existing);
            } else {
                android.preference.PreferenceScreen root = getPreferenceScreen();
                if (root != null) root.removePreference(existing);
            }
        } catch (Exception e) {
            android.util.Log.w("UVPro.Settings", "Could not remove legacy Bluetooth Devices pref", e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        wireRestorePreferenceHandlers();
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
        styleAdminLeadershipWarning();
        syncAdminSettingsGateOnResume();
        syncAllPreferencesFromAtakToUi();
        updateAdminControlsEnabled();
        updateSummaries();
        ListView list = getPreferenceListView();
        if (list != null) {
            list.post(this::applyRowStyles);
        }
    }

    @Override
    public void onPause() {
        syncAllPreferencesToAtak();
        super.onPause();
        stopDistributeButtonPulse(true);
        getPreferenceManager().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs,
                                          String key) {
        if (shouldMirrorPreferenceToAtak(key)) {
            mirrorPreferenceToAtak(prefs, key);
        }
        if (NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(key)) {
            updateAdminControlsEnabled();
        }
        if (NetSlotConfig.PREF_SLOT_COUNT.equals(key)
                || NetSlotConfig.PREF_SLOT_TIME_SEC.equals(key)) {
            normalizeSlotPreferences(prefs);
        }
        if (PREF_BEACON_INTERVAL.equals(key)
                || PREF_SA_RELAY_ENABLED.equals(key)
                || PREF_RF_TO_TAK_UPLINK_ENABLED.equals(key)
                || PREF_PING_REPLY_ENABLED.equals(key)
                || isSmartBeaconParamKey(key)) {
            if (isSmartBeaconParamKey(key)) {
                persistSmartBeaconFromPreferences(prefs);
            }
            notifyRuntimeSettingsChanged();
        }
        if (PREF_APRS_CALLSIGN.equals(key) || PREF_APRS_MESSAGE.equals(key)
                || PREF_APRS_SSID.equals(key)) {
            notifyRuntimeSettingsChanged();
        }
        if (PREF_SA_RELAY_ENABLED.equals(key)) {
            updateDependentPreferences();
        }
        if (PREF_ENCRYPTION_ENABLED.equals(key) || PREF_ENCRYPTION_PASSPHRASE.equals(key)) {
            com.uvpro.plugin.protocol.UVProRadioServices.syncEncryptionFromSettings(
                    resolveSettingsContext());
        }
        if (RadioProfile.PREF.equals(key)) {
            applyRadioProfilePreferences();
            RadioProfile.applyMapIcons(staticPluginContext);
            RadioProfile.refreshOpenPanel();
        }
        updateSummaries();
        ListView list = getPreferenceListView();
        if (list != null) {
            list.post(this::applyRowStyles);
        }
    }

    /** ATAK host context for SharedPreferences — never the plugin package store. */
    private static Context resolveAtakContext() {
        MapView mv = MapView.getMapView();
        if (mv != null && mv.getContext() != null) {
            return mv.getContext();
        }
        return null;
    }

    private Context resolveSettingsContext() {
        Context ctx = resolveAtakContext();
        if (ctx != null) {
            return ctx;
        }
        if (getActivity() != null) {
            return getActivity();
        }
        if (getContext() != null) {
            return getContext();
        }
        return staticPluginContext;
    }

    /** ATAK default SharedPreferences — never the plugin package store. */
    private SharedPreferences atakPrefs() {
        return getPrefs(resolveSettingsContext());
    }

    private boolean shouldMirrorPreferenceToAtak(String key) {
        Preference pref = findPreference(key);
        return pref != null && pref.isPersistent();
    }

    /** Copy UI preference writes into ATAK default SharedPreferences. */
    private void mirrorPreferenceToAtak(SharedPreferences source, String key) {
        Context ctx = resolveSettingsContext();
        if (ctx == null || source == null || key == null || !source.contains(key)) {
            return;
        }
        Object raw = source.getAll().get(key);
        if (raw == null) {
            return;
        }
        SharedPreferences.Editor editor = getPrefs(ctx).edit();
        if (raw instanceof String) {
            String value = ((String) raw).trim();
            if (PREF_APRS_CALLSIGN.equals(key)) {
                value = value.toUpperCase(java.util.Locale.US);
            }
            editor.putString(key, value);
        } else if (raw instanceof Boolean) {
            editor.putBoolean(key, (Boolean) raw);
        } else if (raw instanceof Integer) {
            editor.putInt(key, (Integer) raw);
        } else if (raw instanceof Long) {
            editor.putLong(key, (Long) raw);
        } else if (raw instanceof Float) {
            editor.putFloat(key, (Float) raw);
        } else {
            return;
        }
        editor.apply();
        if (raw instanceof String) {
            String value = ((String) raw).trim();
            if (PREF_APRS_CALLSIGN.equals(key)) {
                value = value.toUpperCase(java.util.Locale.US);
                setEditTextPreferenceText(key, value);
            } else if (isListPreferenceKey(key)) {
                setListPreferenceValue(key, value);
            }
        }
    }

    private static boolean isListPreferenceKey(String key) {
        return PREF_BEACON_INTERVAL.equals(key)
                || PREF_RETRY_INTERVAL_MIN.equals(key)
                || PREF_RETRY_MAX.equals(key)
                || PREF_APRS_SSID.equals(key);
    }

    /** Load ATAK prefs into Pan widgets so dialogs show saved values. */
    private void syncAllPreferencesFromAtakToUi() {
        Context ctx = resolveSettingsContext();
        if (ctx == null) {
            return;
        }
        SharedPreferences atak = getPrefs(ctx);
        setListPreferenceValue(PREF_BEACON_INTERVAL,
                atak.getString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL));
        setListPreferenceValue(PREF_RETRY_INTERVAL_MIN,
                atak.getString(PREF_RETRY_INTERVAL_MIN, DEFAULT_RETRY_INTERVAL_MIN));
        setListPreferenceValue(PREF_RETRY_MAX,
                atak.getString(PREF_RETRY_MAX, DEFAULT_RETRY_MAX));
        setListPreferenceValue(PREF_APRS_SSID,
                atak.getString(PREF_APRS_SSID, DEFAULT_APRS_SSID));
        setEditTextPreferenceText(PREF_APRS_CALLSIGN, getAprsCallsign(ctx));
        setEditTextPreferenceText(PREF_APRS_MESSAGE, getAprsMessage(ctx));
        setEditTextPreferenceText(PREF_ENCRYPTION_PASSPHRASE,
                nullToEmpty(atak.getString(PREF_ENCRYPTION_PASSPHRASE, "")));
        setEditTextPreferenceText(NetSlotConfig.PREF_SLOT_COUNT,
                String.valueOf(NetSlotConfig.getSlotCount(ctx)));
        setEditTextPreferenceText(NetSlotConfig.PREF_SLOT_TIME_SEC,
                String.valueOf(NetSlotConfig.getSlotTimeSec(ctx)));
        syncCheckBoxPreferenceFromAtak(PREF_PING_REPLY_ENABLED, DEFAULT_PING_REPLY_ENABLED);
        syncCheckBoxPreferenceFromAtak(PREF_SA_RELAY_ENABLED, false);
        syncCheckBoxPreferenceFromAtak(PREF_RF_TO_TAK_UPLINK_ENABLED, false);
        syncCheckBoxPreferenceFromAtak(PREF_DISABLE_MESH_BEACON_LIMITING, false);
        syncCheckBoxPreferenceFromAtak(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false);
        syncSmartBeaconPreferenceValues();
    }

    /** Flush ATAK-backed smart beacon params (Pan widgets may skip listeners). */
    private void syncAllPreferencesToAtak() {
        Context ctx = resolveSettingsContext();
        if (ctx != null) {
            persistSmartBeaconFromPreferences(getPrefs(ctx));
        }
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private void wireAdminSettingsGate() {
        adminCategory = (PreferenceCategory) findPreference(KEY_CAT_ADMINISTRATION);
        Preference adminToggle = findPreference(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED);
        if (!(adminToggle instanceof CheckBoxPreference)) {
            return;
        }
        CheckBoxPreference adminCheck = (CheckBoxPreference) adminToggle;
        adminCheck.setPersistent(false);
        adminCheck.setOnPreferenceChangeListener((preference, newValue) ->
                handleAdminSettingsChange((CheckBoxPreference) preference,
                        Boolean.TRUE.equals(newValue)));
    }

    /**
     * @return {@code true} when the preference framework should accept the new checked state
     */
    private boolean handleAdminSettingsChange(CheckBoxPreference checkbox, boolean enable) {
        Context prefsCtx = resolveSettingsContext();
        if (prefsCtx == null || checkbox == null) {
            return false;
        }

        if (!enable) {
            suppressAdminPasswordPrompt = true;
            AdminAccessGate.lock(prefsCtx);
            checkbox.setChecked(false);
            refreshAdminGateUi();
            postClearAdminPasswordPromptSuppression();
            return true;
        }

        if (suppressAdminPasswordPrompt) {
            checkbox.setChecked(false);
            return false;
        }

        if (AdminAccessGate.isUnlocked(prefsCtx)) {
            enableAdminSettings(checkbox, prefsCtx);
            return true;
        }

        checkbox.setChecked(false);
        Context dialogCtx = getActivity() != null ? getActivity() : prefsCtx;
        promptAdministrativeUnlockForToggle(dialogCtx, prefsCtx, checkbox);
        return false;
    }

    private void postClearAdminPasswordPromptSuppression() {
        ListView list = getPreferenceListView();
        if (list != null) {
            list.post(() -> suppressAdminPasswordPrompt = false);
        } else {
            suppressAdminPasswordPrompt = false;
        }
    }

    private void promptAdministrativeUnlockForToggle(Context dialogCtx, Context prefsCtx,
                                                     CheckBoxPreference checkbox) {
        if (dialogCtx == null || checkbox == null || adminUnlockDialogOpen) {
            return;
        }
        if (prefsCtx == null) {
            prefsCtx = dialogCtx;
        }
        if (!AdminAccessGate.isConfigured()) {
            Toast.makeText(dialogCtx,
                    "Administrative password not configured in this build",
                    Toast.LENGTH_LONG).show();
            checkbox.setChecked(false);
            refreshAdminGateUi();
            return;
        }
        adminUnlockDialogOpen = true;
        final EditText input = new EditText(dialogCtx);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final Context storeCtx = prefsCtx;
        AlertDialog dialog = new AlertDialog.Builder(dialogCtx)
                .setTitle("Administrative Settings")
                .setMessage("Enter password to unlock hidden settings.")
                .setView(input)
                .setPositiveButton("Unlock", (d, which) -> {
                    if (AdminAccessGate.unlock(storeCtx, input.getText().toString())) {
                        Toast.makeText(dialogCtx, "Administrative Settings unlocked",
                                Toast.LENGTH_SHORT).show();
                        enableAdminSettings(checkbox, storeCtx);
                    } else {
                        Toast.makeText(dialogCtx, "Incorrect password", Toast.LENGTH_LONG).show();
                        checkbox.setChecked(false);
                        refreshAdminGateUi();
                    }
                })
                .setNegativeButton("Cancel", (d, which) -> {
                    checkbox.setChecked(false);
                    refreshAdminGateUi();
                })
                .setOnCancelListener(d -> {
                    checkbox.setChecked(false);
                    refreshAdminGateUi();
                })
                .create();
        dialog.setOnDismissListener(d -> adminUnlockDialogOpen = false);
        dialog.show();
    }

    private void enableAdminSettings(CheckBoxPreference checkbox, Context prefsCtx) {
        getPrefs(prefsCtx).edit()
                .putBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, true)
                .commit();
        checkbox.setChecked(true);
        refreshAdminGateUi();
    }

    private void refreshAdminGateUi() {
        updateAdminControlsEnabled();
        updateSummaries();
        ListView list = getPreferenceListView();
        if (list != null) {
            list.post(this::applyRowStyles);
        }
    }

    private void syncAdminSettingsGateOnResume() {
        Context prefsCtx = resolveSettingsContext();
        if (prefsCtx == null) {
            return;
        }
        SharedPreferences prefs = atakPrefs();
        Preference adminToggle = findPreference(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED);
        if (!AdminAccessGate.isUnlocked(prefsCtx)) {
            if (prefs.getBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false)) {
                prefs.edit()
                        .putBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false)
                        .commit();
            }
            if (adminToggle instanceof CheckBoxPreference) {
                ((CheckBoxPreference) adminToggle).setChecked(false);
            }
        } else if (adminToggle instanceof CheckBoxPreference) {
            ((CheckBoxPreference) adminToggle).setChecked(
                    prefs.getBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false));
        }
    }

    private void notifyRuntimeSettingsChanged() {
        try {
            AtakBroadcast.getInstance().sendBroadcast(
                    new android.content.Intent(UVProMapComponent.ACTION_BEACON_INTERVAL_CHANGED));
        } catch (Exception ignored) {
        }
    }

    /** Password dialog for Tools prefs and in-plugin settings. */
    public static void promptAdministrativeUnlock(Context dialogCtx, Runnable onUnlocked) {
        Context prefsCtx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : dialogCtx;
        promptAdministrativeUnlock(dialogCtx, prefsCtx, onUnlocked);
    }

    /** @param prefsCtx ATAK context used for {@link AdminAccessGate} read/write */
    public static void promptAdministrativeUnlock(Context dialogCtx, Context prefsCtx,
                                                  Runnable onUnlocked) {
        if (dialogCtx == null) {
            return;
        }
        if (prefsCtx == null) {
            prefsCtx = dialogCtx;
        }
        if (!AdminAccessGate.isConfigured()) {
            Toast.makeText(dialogCtx,
                    "Administrative password not configured in this build",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final EditText input = new EditText(dialogCtx);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final Context storeCtx = prefsCtx;
        new AlertDialog.Builder(dialogCtx)
                .setTitle("Administrative Settings")
                .setMessage("Enter password to unlock hidden settings.")
                .setView(input)
                .setPositiveButton("Unlock", (dialog, which) -> {
                    if (AdminAccessGate.unlock(storeCtx, input.getText().toString())) {
                        Toast.makeText(dialogCtx, "Administrative Settings unlocked",
                                Toast.LENGTH_SHORT).show();
                        if (onUnlocked != null) {
                            onUnlocked.run();
                        }
                    } else {
                        Toast.makeText(dialogCtx, "Incorrect password", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void wireAdministrationPreferences() {
        Preference distributeWarning = findPreference(KEY_DISTRIBUTE_NET_WARNING);
        if (distributeWarning != null) {
            distributeWarning.setTitle(DISTRIBUTE_NET_WARNING);
            distributeWarning.setSummary("");
        }
        Preference distribute = findPreference(KEY_DISTRIBUTE_NET_SLOTS);
        if (distribute != null) {
            distribute.setSummary("");
            registerPreferenceClickHandler(KEY_DISTRIBUTE_NET_SLOTS, preference -> {
                Context ctx = getActivity() != null ? getActivity() : getContext();
                if (ctx == null && MapView.getMapView() != null) {
                    ctx = MapView.getMapView().getContext();
                }
                if (ctx == null) {
                    return true;
                }
                boolean enabled = preference.isEnabled();
                pulseDistributeButtonFeedback(distributeNetButtonRowView, enabled);
                String error = distributeNetSlotsOrError(ctx);
                if (error == null) {
                    int slots = NetSlotConfig.getSlotCount(ctx);
                    float slotSec = NetSlotConfig.getSlotTimeSec(ctx);
                    Toast.makeText(ctx,
                            "Slot config sent (" + slots + " slots, "
                                    + slotSec + " s)",
                            Toast.LENGTH_LONG).show();
                    updateSummaries();
                } else {
                    Toast.makeText(ctx, error, Toast.LENGTH_LONG).show();
                }
                return true;
            });
        }
        updateAdminControlsEnabled();
    }

    /** @return null on success, or a user-facing error message */
    private static String distributeNetSlotsOrError(Context ctx) {
        if (ctx == null) {
            return "Unable to access settings";
        }
        SharedPreferences prefs = getPrefs(ctx);
        if (!prefs.getBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false)
                || !AdminAccessGate.isUnlocked(ctx)) {
            return "Enable administrative settings and enter password";
        }
        normalizeSlotPreferencesStatic(ctx, prefs);
        int slots = NetSlotConfig.getSlotCount(ctx);
        float slotSec = NetSlotConfig.getSlotTimeSec(ctx);
        NetSlotConfig.saveLocalSlotSettings(ctx, slots, slotSec);
        if (!UVProRadioServices.isConnected()) {
            return "Connect to radio before distributing slot settings";
        }
        if (UVProRadioServices.distributeNetSlotConfig(ctx)) {
            return null;
        }
        return "Failed to send slot config over radio";
    }

    private static void normalizeSlotPreferencesStatic(Context ctx, SharedPreferences prefs) {
        int slots = NetSlotConfig.getSlotCount(ctx);
        float sec = NetSlotConfig.getSlotTimeSec(ctx);
        prefs.edit()
                .putString(NetSlotConfig.PREF_SLOT_COUNT, String.valueOf(slots))
                .putString(NetSlotConfig.PREF_SLOT_TIME_SEC, String.valueOf(sec))
                .apply();
    }

    private void wireAprsPreferences() {
        Preference iconPref = findPreference(KEY_APRS_ICON);
        if (iconPref != null) {
            updateAprsIconSummary(iconPref);
            iconPref.setOnPreferenceClickListener(p -> {
                Context ctx = getContext();
                if (ctx == null) {
                    ctx = staticPluginContext;
                }
                if (ctx != null) {
                    com.uvpro.plugin.aprs.AprsIconPickerDialog.show(
                            ctx, staticPluginContext,
                            () -> updateAprsIconSummary(iconPref));
                }
                return true;
            });
        }
    }

    private void updateAprsIconSummary(Preference iconPref) {
        if (iconPref == null) {
            return;
        }
        iconPref.setSummary(formatAprsIconSummary(iconPref.isEnabled()));
    }

    private CharSequence formatAprsIconSummary(boolean enabled) {
        Context mapCtx = resolveSettingsContext();
        if (mapCtx == null) {
            mapCtx = getContext();
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(APRS_ICON_DESC);
        int descriptionColor = enabled ? COLOR_WHITE : COLOR_DISABLED_GREY;
        sb.setSpan(new ForegroundColorSpan(descriptionColor), 0, APRS_ICON_DESC.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AbsoluteSizeSpan(PREFERENCE_TITLE_TEXT_SP, true), 0, APRS_ICON_DESC.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        sb.append('\n');
        int valueStart = sb.length();

        Bitmap iconBitmap = null;
        if (mapCtx != null && isAprsIconSelected(mapCtx)) {
            iconBitmap = com.uvpro.plugin.aprs.AprsIconPreviewLoader
                    .loadSelectedIconBitmap(mapCtx, staticPluginContext);
        }

        if (iconBitmap == null) {
            sb.append(APRS_ICON_NOT_SET);
            int valueColor = enabled ? COLOR_VALUE_GREEN : COLOR_DISABLED_GREY;
            sb.setSpan(new ForegroundColorSpan(valueColor), valueStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new AbsoluteSizeSpan(PREFERENCE_TITLE_TEXT_SP, true), valueStart,
                    sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return sb;
        }

        Context drawableCtx = mapCtx != null ? mapCtx : staticPluginContext;
        if (drawableCtx == null) {
            sb.append(APRS_ICON_NOT_SET);
            int valueColor = enabled ? COLOR_VALUE_GREEN : COLOR_DISABLED_GREY;
            sb.setSpan(new ForegroundColorSpan(valueColor), valueStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new AbsoluteSizeSpan(PREFERENCE_TITLE_TEXT_SP, true), valueStart,
                    sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return sb;
        }

        sb.append('\uFFFC');
        Bitmap scaled = scaleBitmapForSummaryLine(drawableCtx, iconBitmap);
        BitmapDrawable drawable = new BitmapDrawable(drawableCtx.getResources(), scaled);
        int sizePx = dp(drawableCtx, APRS_ICON_SUMMARY_DP);
        drawable.setBounds(0, 0, sizePx, sizePx);
        sb.setSpan(new ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), valueStart, valueStart + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static Bitmap scaleBitmapForSummaryLine(Context ctx, Bitmap src) {
        if (ctx == null || src == null) {
            return src;
        }
        int targetPx = dp(ctx, APRS_ICON_SUMMARY_DP);
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) {
            return src;
        }
        float scale = Math.min((float) targetPx / w, (float) targetPx / h);
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        if (nw == w && nh == h) {
            return src;
        }
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    private void styleAdminLeadershipWarning() {
        Preference warning = findPreference(KEY_ADMIN_LEADERSHIP_WARNING);
        if (warning != null) {
            warning.setTitle("For Team Leadership ONLY");
            warning.setSummary("Do not use unless directed by higher");
        }
    }

    private void updateAdminControlsEnabled() {
        Context ctx = resolveSettingsContext();
        SharedPreferences prefs = atakPrefs();
        boolean adminOn = prefs.getBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false);
        boolean unlocked = ctx != null && AdminAccessGate.isUnlocked(ctx);
        boolean enableChildren = adminOn && unlocked;

        if (adminCategory == null) {
            adminCategory = (PreferenceCategory) findPreference(KEY_CAT_ADMINISTRATION);
        }
        if (adminCategory != null) {
            boolean pastAdminToggle = false;
            for (int i = 0; i < adminCategory.getPreferenceCount(); i++) {
                Preference p = adminCategory.getPreference(i);
                if (p == null) {
                    continue;
                }
                String key = p.getKey();
                if (KEY_ADMIN_LEADERSHIP_WARNING.equals(key)) {
                    applyPreferenceEnabled(p, true);
                    continue;
                }
                if (NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(key)) {
                    applyPreferenceEnabled(p, true);
                    pastAdminToggle = true;
                    continue;
                }
                if (pastAdminToggle) {
                    applyPreferenceEnabled(p, enableChildren);
                }
            }
        } else {
            for (String key : ADMIN_GATED_PREF_KEYS) {
                applyPreferenceEnabled(findPreference(key), enableChildren);
            }
        }

        updateDependentPreferences();
    }

    private void applyPreferenceEnabled(Preference pref, boolean enabled) {
        if (pref == null) {
            return;
        }
        pref.setEnabled(enabled);
        pref.setShouldDisableView(true);
    }

    private boolean isAdminSectionUnlocked(Context ctx) {
        if (ctx == null) {
            return false;
        }
        SharedPreferences prefs = getPrefs(ctx);
        return prefs.getBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false)
                && AdminAccessGate.isUnlocked(ctx);
    }

    private void setPreferenceEnabled(String key, boolean enabled) {
        applyPreferenceEnabled(findPreference(key), enabled);
    }

    private void normalizeSlotPreferences(SharedPreferences prefs) {
        int slots = NetSlotConfig.getSlotCount(
                MapView.getMapView() != null
                        ? MapView.getMapView().getContext()
                        : staticPluginContext);
        float sec = NetSlotConfig.getSlotTimeSec(
                MapView.getMapView() != null
                        ? MapView.getMapView().getContext()
                        : staticPluginContext);
        prefs.edit()
                .putString(NetSlotConfig.PREF_SLOT_COUNT, String.valueOf(slots))
                .putString(NetSlotConfig.PREF_SLOT_TIME_SEC, String.valueOf(sec))
                .apply();
    }

    private void applyRadioProfilePreferences() {
        Context ctx = resolveSettingsContext();
        setPreferenceShown(findPreference(KEY_CAT_APRS),
                RadioProfile.shows(RadioSurfaceCatalog.Role.UVPRO, ctx));
        setPreferenceShown(findPreference(PREF_DISABLE_MESH_BEACON_LIMITING),
                RadioProfile.shows(RadioSurfaceCatalog.Role.MESH, ctx));
    }

    /** android.preference.Preference on the ATAK compile stub has no setVisible. */
    private static void setPreferenceShown(Preference pref, boolean shown) {
        if (pref == null) {
            return;
        }
        try {
            pref.getClass().getMethod("setVisible", boolean.class).invoke(pref, shown);
        } catch (Exception e) {
            android.util.Log.w("UVPro.Settings",
                    "Could not hide preference " + pref.getKey(), e);
        }
    }

    private void updateSummaries() {
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        Context ctx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : staticPluginContext;

        setSummaryWithValue(RadioProfile.PREF,
                "Which radio controls are shown. Hidden radios can be turned back on here.",
                RadioProfile.label(RadioProfile.read(ctx)));

        Preference beaconPref = findPreference(PREF_BEACON_INTERVAL);
        if (beaconPref != null) {
            beaconPref.setEnabled(true);
            String beaconValue = getListPreferenceValueLabel(PREF_BEACON_INTERVAL);
            if (beaconValue == null || beaconValue.isEmpty()) {
                beaconValue = prefs.getString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL)
                        + " seconds";
            }
            beaconPref.setSummary(formatSummaryWithValue(
                    BEACON_INTERVAL_DESC, beaconValue, true));
        }

        updateSmartBeaconFieldSummaries();

        setCheckBoxDescriptionSummary(PREF_PING_REPLY_ENABLED,
                "Automatically reply to incoming pings with your position");
        setSummaryWithValue(PREF_RETRY_INTERVAL_MIN,
                "How long to wait before retransmitting an unacknowledged message",
                getListPreferenceValueLabel(PREF_RETRY_INTERVAL_MIN));
        setSummaryWithValue(PREF_RETRY_MAX,
                "Number of retransmit attempts before declaring delivery failure",
                getListPreferenceValueLabel(PREF_RETRY_MAX));

        setSummaryWithValue(PREF_APRS_CALLSIGN,
                "Ham radio call (required for APRS TX when armed)",
                emptyToNotSet(getEditTextPreferenceValue(PREF_APRS_CALLSIGN)));
        setSummaryWithValue(PREF_APRS_SSID,
                "Standard APRS SSID 0–15",
                ctx != null ? getAprsSsidDisplayLabel(ctx) : getListPreferenceValueLabel(PREF_APRS_SSID));
        setSummaryWithValue(PREF_APRS_MESSAGE,
                "Comment text appended to position reports",
                emptyToNotSet(getEditTextPreferenceValue(PREF_APRS_MESSAGE)));

        setSummaryWithValue(PREF_ENCRYPTION_PASSPHRASE,
                "Same value on every radio that uses RF encryption",
                maskSecret(getEditTextPreferenceValue(PREF_ENCRYPTION_PASSPHRASE)));

        setCheckBoxDescriptionSummary(PREF_SA_RELAY_ENABLED,
                "Re-broadcast inbound network positions over radio to off-grid users. "
                        + "Throttled to one update per contact per 30 seconds.");
        setCheckBoxDescriptionSummary(PREF_RF_TO_TAK_UPLINK_ENABLED,
                "When SA Relay is enabled, forward inbound RF CoT to TAK network. "
                        + "Use with care to avoid unintended rebroadcast loops.");
        setCheckBoxDescriptionSummary(PREF_DISABLE_MESH_BEACON_LIMITING,
                DISABLE_MESH_BEACON_LIMITING_DESC);
        setCheckBoxDescriptionSummary(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED,
                "Unlock slot assignment controls for net leadership");

        if (ctx != null) {
            setSummaryWithValue(NetSlotConfig.PREF_SLOT_COUNT,
                    "Ping-reply slots (default 20)",
                    String.valueOf(NetSlotConfig.getSlotCount(ctx)));
            setSummaryWithValue(NetSlotConfig.PREF_SLOT_TIME_SEC,
                    "Seconds between slot start times (default 2.5 s)",
                    String.format(java.util.Locale.US, "%.1f s",
                            NetSlotConfig.getSlotTimeSec(ctx)));
        } else {
            setSummaryWithValue(NetSlotConfig.PREF_SLOT_COUNT,
                    "Ping-reply slots (default 20)",
                    getEditTextPreferenceValue(NetSlotConfig.PREF_SLOT_COUNT));
            setSummaryWithValue(NetSlotConfig.PREF_SLOT_TIME_SEC,
                    "Seconds between slot start times (default 2.5 s)",
                    getEditTextPreferenceValue(NetSlotConfig.PREF_SLOT_TIME_SEC));
        }

        Preference aprsIconPref = findPreference(KEY_APRS_ICON);
        if (aprsIconPref != null) {
            updateAprsIconSummary(aprsIconPref);
        }
    }

    private void updateSmartBeaconFieldSummaries() {
        Context ctx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : staticPluginContext;
        if (ctx == null) {
            return;
        }
        setSummaryWithValue(SmartBeacon.KEY_LOW_SPEED,
                "Below this speed the slowest rate is used",
                SmartBeacon.getLowSpeed(ctx) + " mph");
        setSummaryWithValue(SmartBeacon.KEY_HIGH_SPEED,
                "Above this speed the fastest rate is used",
                SmartBeacon.getHighSpeed(ctx) + " mph");
        setSummaryWithValue(SmartBeacon.KEY_SLOW_RATE,
                "Max time between beacons when slow or stopped",
                SmartBeacon.getSlowRate(ctx) + " s");
        setSummaryWithValue(SmartBeacon.KEY_FAST_RATE,
                "Min time between beacons when moving fast",
                SmartBeacon.getFastRate(ctx) + " s");
        setSummaryWithValue(SmartBeacon.KEY_MIN_TURN_TIME,
                "Minimum delay between corner-pegging beacons",
                SmartBeacon.getMinTurnTime(ctx) + " s");
        setSummaryWithValue(SmartBeacon.KEY_TURN_THRESHOLD,
                "Heading change needed to trigger an early beacon",
                SmartBeacon.getTurnThreshold(ctx) + "°");
        setSummaryWithValue(SmartBeacon.KEY_TURN_SLOPE,
                "Scales turn sensitivity with speed (higher = less sensitive at low speed)",
                String.valueOf(SmartBeacon.getTurnSlope(ctx)));
    }

    private void setCheckBoxDescriptionSummary(String key, String description) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setSummary(formatDescriptionOnly(description, pref.isEnabled()));
        }
    }

    private void setSummaryWithValue(String key, String description, String value) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setSummary(formatSummaryWithValue(description, value, pref.isEnabled()));
        }
    }

    private static CharSequence formatSummaryWithValue(String description, String value) {
        return formatSummaryWithValue(description, value, true);
    }

    private static CharSequence formatDescriptionOnly(String description) {
        return formatDescriptionOnly(description, true);
    }

    private static CharSequence formatDescriptionOnly(String description, boolean enabled) {
        if (description == null) {
            description = "";
        }
        int color = enabled ? COLOR_WHITE : COLOR_DISABLED_GREY;
        SpannableStringBuilder sb = new SpannableStringBuilder(description);
        sb.setSpan(new ForegroundColorSpan(color), 0, description.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AbsoluteSizeSpan(PREFERENCE_TITLE_TEXT_SP, true), 0, description.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static CharSequence formatSummaryWithValue(String description, String value,
                                                       boolean enabled) {
        String display = value;
        if (display == null || display.trim().isEmpty()) {
            display = "(not set)";
        } else {
            display = display.trim();
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(description);
        int descriptionColor = enabled ? COLOR_WHITE : COLOR_DISABLED_GREY;
        int valueColor = enabled ? COLOR_VALUE_GREEN : COLOR_DISABLED_GREY;
        sb.setSpan(new ForegroundColorSpan(descriptionColor), 0, description.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AbsoluteSizeSpan(PREFERENCE_TITLE_TEXT_SP, true), 0, description.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int valueStart = sb.length();
        sb.append('\n').append(display);
        sb.setSpan(new AbsoluteSizeSpan(PREFERENCE_TITLE_TEXT_SP, true), valueStart, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(valueColor), valueStart, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private CharSequence buildStyledSummaryForPreference(Preference pref) {
        if (pref == null || pref.getKey() == null) {
            return null;
        }
        String key = pref.getKey();
        if (KEY_SMART_BEACON_SECTION_HEADER.equals(key)
                || KEY_SA_RELAY_SECTION_HEADER.equals(key)
                || KEY_REPLY_SLOT_TIMES_SECTION_HEADER.equals(key)) {
            CharSequence summary = pref.getSummary();
            return formatDescriptionOnly(summary != null ? summary.toString() : "", pref.isEnabled());
        }
        if (KEY_ADMIN_LEADERSHIP_WARNING.equals(key)) {
            CharSequence summary = pref.getSummary();
            return formatDescriptionOnly(summary != null ? summary.toString() : "", true);
        }
        if (KEY_DISTRIBUTE_NET_WARNING.equals(key)
                || KEY_DISTRIBUTE_NET_SLOTS.equals(key)
                || KEY_RESTORE_BEACON_DEFAULTS.equals(key)
                || KEY_RESTORE_ALL_DEFAULTS.equals(key)
                || KEY_RESTORE_ADMIN_DEFAULTS.equals(key)) {
            return pref.getSummary();
        }
        if (KEY_APRS_ICON.equals(key)) {
            return formatAprsIconSummary(pref.isEnabled());
        }
        if (pref instanceof CheckBoxPreference || isCheckboxPreferenceKey(key)) {
            String description = getDescriptionForPreferenceKey(key);
            if (description != null) {
                return formatDescriptionOnly(description, pref.isEnabled());
            }
        }
        String description = getDescriptionForPreferenceKey(key);
        if (description == null) {
            CharSequence summary = pref.getSummary();
            return summary != null ? summary : "";
        }
        return formatSummaryWithValue(description, resolvePreferenceDisplayValue(key),
                pref.isEnabled());
    }

    private String getDescriptionForPreferenceKey(String key) {
        if (PREF_BEACON_INTERVAL.equals(key)) {
            return BEACON_INTERVAL_DESC;
        }
        if (SmartBeacon.KEY_LOW_SPEED.equals(key)) {
            return "Below this speed the slowest rate is used";
        }
        if (SmartBeacon.KEY_HIGH_SPEED.equals(key)) {
            return "Above this speed the fastest rate is used";
        }
        if (SmartBeacon.KEY_SLOW_RATE.equals(key)) {
            return "Max time between beacons when slow or stopped";
        }
        if (SmartBeacon.KEY_FAST_RATE.equals(key)) {
            return "Min time between beacons when moving fast";
        }
        if (SmartBeacon.KEY_MIN_TURN_TIME.equals(key)) {
            return "Minimum delay between corner-pegging beacons";
        }
        if (SmartBeacon.KEY_TURN_THRESHOLD.equals(key)) {
            return "Heading change needed to trigger an early beacon";
        }
        if (SmartBeacon.KEY_TURN_SLOPE.equals(key)) {
            return "Scales turn sensitivity with speed (higher = less sensitive at low speed)";
        }
        if (PREF_PING_REPLY_ENABLED.equals(key)) {
            return "Automatically reply to incoming pings with your position";
        }
        if (PREF_RETRY_INTERVAL_MIN.equals(key)) {
            return "How long to wait before retransmitting an unacknowledged message";
        }
        if (PREF_RETRY_MAX.equals(key)) {
            return "Number of retransmit attempts before declaring delivery failure. "
                    + "Will re-attempt upon receipt of beacon";
        }
        if (PREF_APRS_CALLSIGN.equals(key)) {
            return "Ham radio call (required for APRS TX when armed)";
        }
        if (PREF_APRS_SSID.equals(key)) {
            return "Standard APRS SSID 0–15";
        }
        if (KEY_APRS_ICON.equals(key)) {
            return APRS_ICON_DESC;
        }
        if (PREF_APRS_MESSAGE.equals(key)) {
            return "Comment text appended to position reports";
        }
        if (PREF_ENCRYPTION_PASSPHRASE.equals(key)) {
            return "Same value on every radio that uses RF encryption";
        }
        if (PREF_SA_RELAY_ENABLED.equals(key)) {
            return "Re-broadcast inbound network positions over radio to off-grid users. "
                    + "Throttled to one update per contact per 30 seconds.";
        }
        if (PREF_RF_TO_TAK_UPLINK_ENABLED.equals(key)) {
            return "When SA Relay is enabled, forward inbound RF CoT to TAK network. "
                    + "Use with care to avoid unintended rebroadcast loops.";
        }
        if (PREF_DISABLE_MESH_BEACON_LIMITING.equals(key)) {
            return DISABLE_MESH_BEACON_LIMITING_DESC;
        }
        if (NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(key)) {
            return "Unlock slot assignment controls for net leadership";
        }
        if (NetSlotConfig.PREF_SLOT_COUNT.equals(key)) {
            return "Ping-reply slots (default 20)";
        }
        if (NetSlotConfig.PREF_SLOT_TIME_SEC.equals(key)) {
            return "Seconds between slot start times (default 2.5 s)";
        }
        return null;
    }

    private String resolvePreferenceDisplayValue(String key) {
        Context ctx = resolveAtakContext();
        if (ctx == null) {
            ctx = resolveSettingsContext();
        }
        if (PREF_BEACON_INTERVAL.equals(key)) {
            String label = getListPreferenceValueLabel(key);
            if (label == null || label.isEmpty()) {
                SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
                label = prefs.getString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL) + " seconds";
            }
            return label;
        }
        if (SmartBeacon.KEY_LOW_SPEED.equals(key) && ctx != null) {
            return SmartBeacon.getLowSpeed(ctx) + " mph";
        }
        if (SmartBeacon.KEY_HIGH_SPEED.equals(key) && ctx != null) {
            return SmartBeacon.getHighSpeed(ctx) + " mph";
        }
        if (SmartBeacon.KEY_SLOW_RATE.equals(key) && ctx != null) {
            return SmartBeacon.getSlowRate(ctx) + " s";
        }
        if (SmartBeacon.KEY_FAST_RATE.equals(key) && ctx != null) {
            return SmartBeacon.getFastRate(ctx) + " s";
        }
        if (SmartBeacon.KEY_MIN_TURN_TIME.equals(key) && ctx != null) {
            return SmartBeacon.getMinTurnTime(ctx) + " s";
        }
        if (SmartBeacon.KEY_TURN_THRESHOLD.equals(key) && ctx != null) {
            return SmartBeacon.getTurnThreshold(ctx) + "°";
        }
        if (SmartBeacon.KEY_TURN_SLOPE.equals(key) && ctx != null) {
            return String.valueOf(SmartBeacon.getTurnSlope(ctx));
        }
        if (PREF_PING_REPLY_ENABLED.equals(key)) {
            return getCheckBoxPreferenceValueLabel(key, DEFAULT_PING_REPLY_ENABLED);
        }
        if (PREF_SA_RELAY_ENABLED.equals(key)) {
            return getCheckBoxPreferenceValueLabel(key, false);
        }
        if (PREF_RF_TO_TAK_UPLINK_ENABLED.equals(key)) {
            if (ctx != null) {
                return isRfToTakUplinkEnabled(ctx) ? "On" : "Off";
            }
            return getCheckBoxPreferenceValueLabel(key, false);
        }
        if (NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(key)) {
            return getCheckBoxPreferenceValueLabel(key, false);
        }
        if (PREF_RETRY_INTERVAL_MIN.equals(key) || PREF_RETRY_MAX.equals(key)) {
            return getListPreferenceValueLabel(key);
        }
        if (PREF_APRS_CALLSIGN.equals(key) && ctx != null) {
            return emptyToNotSet(getAprsCallsign(ctx));
        }
        if (PREF_APRS_MESSAGE.equals(key) && ctx != null) {
            return emptyToNotSet(getAprsMessage(ctx));
        }
        if (PREF_APRS_SSID.equals(key)) {
            if (ctx != null) {
                return getAprsSsidDisplayLabel(ctx);
            }
            return getListPreferenceValueLabel(key);
        }
        if (PREF_ENCRYPTION_PASSPHRASE.equals(key)) {
            return maskSecret(getEditTextPreferenceValue(key));
        }
        if (KEY_APRS_ICON.equals(key)) {
            return APRS_ICON_NOT_SET;
        }
        if (NetSlotConfig.PREF_SLOT_COUNT.equals(key) && ctx != null) {
            return String.valueOf(NetSlotConfig.getSlotCount(ctx));
        }
        if (NetSlotConfig.PREF_SLOT_TIME_SEC.equals(key) && ctx != null) {
            return String.format(java.util.Locale.US, "%.1f s", NetSlotConfig.getSlotTimeSec(ctx));
        }
        if (NetSlotConfig.PREF_SLOT_COUNT.equals(key)) {
            return getEditTextPreferenceValue(key);
        }
        if (NetSlotConfig.PREF_SLOT_TIME_SEC.equals(key)) {
            return getEditTextPreferenceValue(key);
        }
        return "";
    }

    private static String emptyToNotSet(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "(not set)";
        }
        return value.trim();
    }

    private static String maskSecret(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "(not set)";
        }
        return "••••••••";
    }

    private String getListPreferenceValueLabel(String key) {
        Preference pref = findPreference(key);
        if (!(pref instanceof ListPreference)) {
            return "";
        }
        ListPreference listPref = (ListPreference) pref;
        String value = getStoredListPreferenceValue(key);
        if (value == null || value.isEmpty()) {
            value = listPref.getValue();
        }
        if (value == null || value.isEmpty()) {
            try {
                value = getPreferenceManager().getSharedPreferences().getString(key, null);
            } catch (ClassCastException ignored) {
            }
        }
        if (value == null || value.isEmpty()) {
            return "";
        }
        return resolveListPreferenceEntryLabel(listPref, value);
    }

    private String getStoredListPreferenceValue(String key) {
        Context ctx = resolveSettingsContext();
        if (ctx == null) {
            return null;
        }
        try {
            return getPrefs(ctx).getString(key, null);
        } catch (ClassCastException ignored) {
            return null;
        }
    }

    private String getAprsSsidDisplayLabel(Context ctx) {
        String value = getPrefs(ctx).getString(PREF_APRS_SSID, DEFAULT_APRS_SSID);
        Preference pref = findPreference(PREF_APRS_SSID);
        if (pref instanceof ListPreference && value != null) {
            return resolveListPreferenceEntryLabel((ListPreference) pref, value);
        }
        return value != null ? value : "";
    }

    private static String resolveListPreferenceEntryLabel(ListPreference listPref, String value) {
        CharSequence[] entries = listPref.getEntries();
        CharSequence[] values = listPref.getEntryValues();
        if (entries != null && values != null) {
            for (int i = 0; i < values.length; i++) {
                if (value.equals(String.valueOf(values[i]))) {
                    return String.valueOf(entries[i]);
                }
            }
        }
        return value;
    }

    private String getEditTextPreferenceValue(String key) {
        Context ctx = resolveSettingsContext();
        if (ctx != null) {
            if (PREF_APRS_CALLSIGN.equals(key)) {
                return getAprsCallsign(ctx);
            }
            if (PREF_APRS_MESSAGE.equals(key)) {
                return getAprsMessage(ctx);
            }
            try {
                String fromAtak = getPrefs(ctx).getString(key, "");
                return fromAtak != null ? fromAtak : "";
            } catch (ClassCastException ignored) {
            }
        }
        Preference pref = findPreference(key);
        if (pref instanceof EditTextPreference) {
            String text = ((EditTextPreference) pref).getText();
            return text != null ? text : "";
        }
        return "";
    }

    private String getCheckBoxPreferenceValueLabel(String key, boolean defaultValue) {
        Context ctx = resolveSettingsContext();
        if (ctx != null) {
            return getPrefs(ctx).getBoolean(key, defaultValue) ? "On" : "Off";
        }
        Preference pref = findPreference(key);
        if (pref instanceof CheckBoxPreference) {
            return ((CheckBoxPreference) pref).isChecked() ? "On" : "Off";
        }
        return defaultValue ? "On" : "Off";
    }

    private static boolean isSmartBeaconParamKey(String key) {
        return SmartBeacon.KEY_LOW_SPEED.equals(key)
                || SmartBeacon.KEY_HIGH_SPEED.equals(key)
                || SmartBeacon.KEY_SLOW_RATE.equals(key)
                || SmartBeacon.KEY_FAST_RATE.equals(key)
                || SmartBeacon.KEY_MIN_TURN_TIME.equals(key)
                || SmartBeacon.KEY_TURN_THRESHOLD.equals(key)
                || SmartBeacon.KEY_TURN_SLOPE.equals(key);
    }

    private void persistSmartBeaconFromPreferences() {
        persistSmartBeaconFromPreferences(getPreferenceManager().getSharedPreferences());
    }

    private void persistSmartBeaconFromPreferences(SharedPreferences source) {
        Context ctx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : staticPluginContext;
        if (ctx == null || source == null) {
            return;
        }
        int lowSpeed = readSmartBeaconInt(source, SmartBeacon.KEY_LOW_SPEED,
                SmartBeacon.DEFAULT_LOW_SPEED);
        int highSpeed = readSmartBeaconInt(source, SmartBeacon.KEY_HIGH_SPEED,
                SmartBeacon.DEFAULT_HIGH_SPEED);
        int slowRate = readSmartBeaconInt(source, SmartBeacon.KEY_SLOW_RATE,
                SmartBeacon.DEFAULT_SLOW_RATE);
        int fastRate = readSmartBeaconInt(source, SmartBeacon.KEY_FAST_RATE,
                SmartBeacon.DEFAULT_FAST_RATE);
        int minTurnTime = readSmartBeaconInt(source, SmartBeacon.KEY_MIN_TURN_TIME,
                SmartBeacon.DEFAULT_MIN_TURN_TIME);
        int turnThreshold = readSmartBeaconInt(source, SmartBeacon.KEY_TURN_THRESHOLD,
                SmartBeacon.DEFAULT_TURN_THRESHOLD);
        int turnSlope = readSmartBeaconInt(source, SmartBeacon.KEY_TURN_SLOPE,
                SmartBeacon.DEFAULT_TURN_SLOPE);

        if (highSpeed <= lowSpeed) {
            highSpeed = lowSpeed + 10;
        }
        if (fastRate >= slowRate) {
            fastRate = Math.max(1, slowRate / 2);
        }
        fastRate = Math.max(1, fastRate);
        slowRate = Math.max(fastRate + 1, slowRate);
        minTurnTime = Math.max(1, minTurnTime);
        turnThreshold = Math.max(1, turnThreshold);
        turnSlope = Math.max(0, turnSlope);

        SmartBeacon.saveAll(ctx, lowSpeed, highSpeed, slowRate, fastRate,
                minTurnTime, turnThreshold, turnSlope);
        syncSmartBeaconPreferenceValues();
    }

    private static int readSmartBeaconInt(SharedPreferences prefs, String key, int fallback) {
        try {
            return prefs.getInt(key, fallback);
        } catch (ClassCastException ignored) {
            try {
                return Integer.parseInt(prefs.getString(key, String.valueOf(fallback)).trim());
            } catch (Exception ignored2) {
                return fallback;
            }
        }
    }

    @Override
    public String getSubTitle() {
        return "UV-CORE Settings";
    }

    /**
     * Convenience: Get a preference value from any context.
     */
    /**
     * Get the ATAK-process SharedPreferences (uses ATAK context, not plugin context).
     */
    private static android.content.SharedPreferences getPrefs(Context context) {
        // Plugin context can't write to its own shared_prefs dir because it
        // runs inside ATAK's process. Always use ATAK's context.
        Context ctx = resolveAtakContext();
        if (ctx == null) {
            ctx = context;
        }
        if (ctx == null) {
            ctx = staticPluginContext;
        }
        if (ctx != null) {
            ctx = ctx.getApplicationContext();
        }
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public static String getCallsign(Context context) {
        try {
            com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
            if (mv != null && mv.getSelfMarker() != null) {
                return mv.getSelfMarker().getMetaString("callsign", "UNKNOWN");
            }
        } catch (Exception e) {
        }
        return "UNKNOWN";
    }

    public static int getBeaconIntervalSec(Context context) {
        String val = getPrefs(context)
                .getString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL);
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    public static void setBeaconIntervalSec(Context context, int seconds) {
        if (context == null || seconds < 1) {
            return;
        }
        getPrefs(context).edit()
                .putString(PREF_BEACON_INTERVAL, String.valueOf(seconds))
                .commit();
        try {
            AtakBroadcast.getInstance().sendBroadcast(
                    new android.content.Intent(UVProMapComponent.ACTION_BEACON_INTERVAL_CHANGED));
        } catch (Exception ignored) {
        }
    }

    public static long getRetryIntervalMs(Context context) {
        String val = getPrefs(context)
                .getString(PREF_RETRY_INTERVAL_MIN, DEFAULT_RETRY_INTERVAL_MIN);
        try {
            return Long.parseLong(val) * 60_000L;
        } catch (NumberFormatException e) {
            return 2 * 60_000L;
        }
    }

    public static int getMaxChatRetries(Context context) {
        String val = getPrefs(context)
                .getString(PREF_RETRY_MAX, DEFAULT_RETRY_MAX);
        try {
            return Math.max(1, Integer.parseInt(val));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    /**
     * Use ATAK's team color ("locationTeam") rather than a plugin-managed setting, so
     * radio contacts match the operator's configured team consistently.
     */
    public static String getAtakTeamColor(Context context) {
        try {
            String team = ChatManagerMapComponent.getTeamName();
            if (team != null && !team.trim().isEmpty()) return team.trim();
        } catch (Exception ignored) {
        }
        try {
            com.atakmap.android.preference.AtakPreferences prefs =
                    com.atakmap.android.preference.AtakPreferences.getInstance(
                            com.atakmap.android.maps.MapView.getMapView() != null
                                    ? com.atakmap.android.maps.MapView.getMapView().getContext()
                                    : context);
            String team = prefs.get("locationTeam", "Cyan");
            if (team != null && !team.trim().isEmpty()) return team.trim();
        } catch (Exception ignored) {
        }
        return "Cyan";
    }

    public static boolean isSaRelayEnabled(Context context) {
        if (context == null) {
            return false;
        }
        return getPrefs(context).getBoolean(PREF_SA_RELAY_ENABLED, false);
    }

    public static boolean isPingReplyEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_PING_REPLY_ENABLED, DEFAULT_PING_REPLY_ENABLED);
    }

    /** Ping replies always TX on the transport that received the ping. */
    public static boolean isPingReplySameTransportEnabled(Context context) {
        return true;
    }

    public static boolean isMeshTransmitEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_ATAK_MESHCORE_TRANSMIT, false);
    }

    public static boolean isUvproTransmitEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_ATAK_UVPRO_TRANSMIT, false);
    }

    public static boolean isRfToTakUplinkEnabled(Context context) {
        return isSaRelayEnabled(context)
                && getPrefs(context)
                .getBoolean(PREF_RF_TO_TAK_UPLINK_ENABLED, false);
    }

    public static boolean isDisableMeshBeaconLimiting(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_DISABLE_MESH_BEACON_LIMITING, false);
    }

    public static boolean isRestrictChatToReachablePeers(Context context) {
        return false;
    }

    public static boolean isEncryptionEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_ENCRYPTION_ENABLED, false);
    }

    public static String getEncryptionPassphrase(Context context) {
        return getPrefs(context)
                .getString(PREF_ENCRYPTION_PASSPHRASE, "");
    }

    public static String getAprsCallsign(Context context) {
        String cs = getPrefs(context).getString(PREF_APRS_CALLSIGN, "");
        if (cs == null) {
            return "";
        }
        String out = cs.trim().toUpperCase(java.util.Locale.US);
        // Accept "CALL-SSID" input in FCC field by normalizing to base callsign.
        int dash = out.indexOf('-');
        if (dash > 0) {
            out = out.substring(0, dash);
        }
        return out;
    }

    public static int getAprsSsid(Context context) {
        String val = getPrefs(context).getString(PREF_APRS_SSID, "9");
        try {
            int ssid = Integer.parseInt(val);
            if (ssid < 0) {
                return 0;
            }
            if (ssid > 15) {
                return 15;
            }
            return ssid;
        } catch (NumberFormatException e) {
            return 9;
        }
    }

    public static boolean isAprsIconSelected(Context context) {
        return getPrefs(context).getBoolean(PREF_APRS_ICON_SELECTED, false);
    }

    public static char getAprsSymbolTable(Context context) {
        String s = getPrefs(context).getString(PREF_APRS_SYMBOL_TABLE, "/");
        if (s == null || s.isEmpty()) {
            return '/';
        }
        return s.charAt(0);
    }

    public static char getAprsSymbolCode(Context context) {
        String s = getPrefs(context).getString(PREF_APRS_SYMBOL_CODE, ">");
        if (s == null || s.isEmpty()) {
            return '>';
        }
        return s.charAt(0);
    }

    public static String getAprsMessage(Context context) {
        String m = getPrefs(context).getString(PREF_APRS_MESSAGE, "");
        return m != null ? m : "";
    }

    public static boolean isAprsTxArmed(Context context) {
        return getPrefs(context).getBoolean(PREF_APRS_TX_ARMED, false);
    }

    public static void setAprsTxArmed(Context context, boolean armed) {
        getPrefs(context).edit().putBoolean(PREF_APRS_TX_ARMED, armed).apply();
    }

    public static boolean isAprsDisableAtakTraffic(Context context) {
        return getPrefs(context).getBoolean(PREF_APRS_DISABLE_ATAK_TRAFFIC, false);
    }

    public static void setAprsDisableAtakTraffic(Context context, boolean disabled) {
        getPrefs(context).edit().putBoolean(PREF_APRS_DISABLE_ATAK_TRAFFIC, disabled).apply();
    }

    /** Ham base call: 1 letter + digit + 1–3 letters, or 2 letters + digit + 1–3 letters. */
    public static boolean isValidAprsCallsign(String baseCall) {
        if (baseCall == null) {
            return false;
        }
        String c = baseCall.trim().toUpperCase(java.util.Locale.US);
        return c.matches("^[A-Z][0-9][A-Z]{1,3}$")
                || c.matches("^[A-Z]{2}[0-9][A-Z]{1,3}$");
    }

    public static String formatAprsDisplayCall(Context context) {
        String base = getAprsCallsign(context);
        if (!isValidAprsCallsign(base)) {
            return "(not set)";
        }
        int ssid = getAprsSsid(context);
        return ssid > 0 ? base + "-" + ssid : base;
    }

    /**
     * Opens ATAK Tool Preferences for this plugin (Settings → Tool Preferences → UV-PRO).
     */
    public static void openToolPreferences(Context context) {
        launchPluginSettings(TOOL_SETTINGS_KEY, null, context);
    }

    /**
     * Opens plugin Tool Preferences scrolled to Beacon Settings.
     */
    public static void openRadioSettings(Context context) {
        launchPluginSettings(TOOL_SETTINGS_KEY, KEY_CAT_BEACON, context);
    }

    /**
     * Opens plugin Tool Preferences scrolled to Smart Beacon Settings.
     */
    public static void openSmartBeaconSettings(Context context) {
        launchPluginSettings(TOOL_SETTINGS_KEY, KEY_SMART_BEACON_SECTION_HEADER, context);
    }

    /**
     * Opens plugin settings scrolled to the APRS category.
     */
    public static void openAprsSettings(Context context) {
        launchPluginSettings(TOOL_SETTINGS_KEY, KEY_CAT_APRS, context);
    }

    private static void launchPluginSettings(String toolKey, String prefKey, Context context) {
        try {
            SettingsActivity.start(toolKey, prefKey);
        } catch (Exception e) {
            android.util.Log.w("UVPro.Settings", "launchPluginSettings failed: " + e.getMessage());
            if (context != null) {
                try {
                    android.content.Intent intent = new android.content.Intent(
                            "com.atakmap.app.ADVANCED_SETTINGS");
                    intent.putExtra("toolkey", toolKey);
                    if (prefKey != null) {
                        intent.putExtra("prefkey", prefKey);
                    }
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    AtakBroadcast.getInstance().sendBroadcast(intent);
                } catch (Exception e2) {
                    Toast.makeText(context,
                            "Open Settings → Tool Preferences → UV-PRO Settings",
                            Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    /**
     * APRS outbound fields for the in-panel Plugin Settings dialog.
     */
    public static final class AprsSettingsUi {
        public EditText editCallsign;
        public Spinner spinnerSsid;
        public ImageView iconPreview;
        public TextView iconNotSet;
        public EditText editMessage;
        public String[] ssidValues;
    }

    /**
     * Adds APRS section to the Plugin Settings dialog (same window as Actions → Plugin Settings).
     */
    public static AprsSettingsUi appendAprsSettingsSection(Context mapCtx, Context pluginCtx,
                                                           LinearLayout layout) {
        AprsSettingsUi ui = new AprsSettingsUi();
        if (mapCtx == null || layout == null) {
            return ui;
        }

        TextView header = new TextView(mapCtx);
        header.setText("\nAPRS");
        header.setTextColor(0xFF00BCD4);
        header.setTextSize(14);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(header);

        TextView labelCall = new TextView(mapCtx);
        labelCall.setText("FCC Call Sign");
        labelCall.setTextColor(0xFFAAAAAA);
        layout.addView(labelCall);
        ui.editCallsign = new EditText(mapCtx);
        ui.editCallsign.setText(getAprsCallsign(mapCtx));
        ui.editCallsign.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        ui.editCallsign.setTextColor(0xFFFFFFFF);
        layout.addView(ui.editCallsign);

        TextView labelSsid = new TextView(mapCtx);
        labelSsid.setText("\nAPRS suffix (SSID)");
        labelSsid.setTextColor(0xFFAAAAAA);
        layout.addView(labelSsid);
        ui.spinnerSsid = new Spinner(mapCtx);
        if (pluginCtx != null) {
            android.content.res.Resources res = pluginCtx.getResources();
            String pkg = pluginCtx.getPackageName();
            int labelsId = res.getIdentifier("aprs_ssid_labels", "array", pkg);
            int valuesId = res.getIdentifier("aprs_ssid_values", "array", pkg);
            if (labelsId != 0 && valuesId != 0) {
                String[] labels = res.getStringArray(labelsId);
                ui.ssidValues = res.getStringArray(valuesId);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(mapCtx,
                        android.R.layout.simple_spinner_item, labels) {
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        View v = super.getView(position, convertView, parent);
                        if (v instanceof TextView) {
                            TextView tv = (TextView) v;
                            tv.setTextColor(COLOR_WHITE);
                            tv.setBackgroundColor(COLOR_STD_BLUE);
                            tv.setPadding(dp(mapCtx, 10), dp(mapCtx, 8),
                                    dp(mapCtx, 10), dp(mapCtx, 8));
                        }
                        return v;
                    }

                    @Override
                    public View getDropDownView(int position, View convertView, ViewGroup parent) {
                        View v = super.getDropDownView(position, convertView, parent);
                        if (v instanceof TextView) {
                            TextView tv = (TextView) v;
                            tv.setTextColor(COLOR_WHITE);
                            tv.setBackgroundColor(COLOR_STD_BLUE);
                            tv.setPadding(dp(mapCtx, 12), dp(mapCtx, 10),
                                    dp(mapCtx, 12), dp(mapCtx, 10));
                        }
                        return v;
                    }
                };
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                ui.spinnerSsid.setAdapter(adapter);
                ui.spinnerSsid.setBackgroundColor(COLOR_STD_BLUE);
                int ssid = getAprsSsid(mapCtx);
                if (ssid >= 0 && ssid < labels.length) {
                    ui.spinnerSsid.setSelection(ssid);
                }
            }
        }
        layout.addView(ui.spinnerSsid);

        TextView labelIcon = new TextView(mapCtx);
        labelIcon.setText("\nAPRS icon");
        labelIcon.setTextColor(0xFFAAAAAA);
        layout.addView(labelIcon);

        LinearLayout iconRow = new LinearLayout(mapCtx);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        ui.iconPreview = new ImageView(mapCtx);
        int iconPx = (int) (48 * mapCtx.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconPx, iconPx);
        iconLp.setMargins(0, 0, 16, 0);
        ui.iconPreview.setLayoutParams(iconLp);
        ui.iconPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconRow.addView(ui.iconPreview);
        ui.iconNotSet = new TextView(mapCtx);
        ui.iconNotSet.setText("(not set)");
        ui.iconNotSet.setTextColor(0xFF888888);
        ui.iconNotSet.setTextSize(12);
        iconRow.addView(ui.iconNotSet);
        layout.addView(iconRow);

        Button btnPickIcon = new Button(mapCtx);
        btnPickIcon.setText("Choose APRS Icon");
        btnPickIcon.setTextColor(COLOR_WHITE);
        btnPickIcon.setBackgroundColor(COLOR_STD_BLUE);
        btnPickIcon.setOnClickListener(v ->
                com.uvpro.plugin.aprs.AprsIconPickerDialog.show(
                        mapCtx, pluginCtx, () ->
                        refreshAprsIconPreviewInDialog(mapCtx, pluginCtx, ui)));
        layout.addView(btnPickIcon);
        refreshAprsIconPreviewInDialog(mapCtx, pluginCtx, ui);

        TextView labelMsg = new TextView(mapCtx);
        labelMsg.setText("\nAPRS message (comment on position)");
        labelMsg.setTextColor(0xFFAAAAAA);
        layout.addView(labelMsg);
        ui.editMessage = new EditText(mapCtx);
        ui.editMessage.setText(getAprsMessage(mapCtx));
        ui.editMessage.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        ui.editMessage.setTextColor(0xFFFFFFFF);
        layout.addView(ui.editMessage);

        return ui;
    }

    private static void refreshAprsIconPreviewInDialog(Context mapCtx, Context pluginCtx,
                                                         AprsSettingsUi ui) {
        if (ui == null || ui.iconPreview == null || ui.iconNotSet == null) {
            return;
        }
        if (!isAprsIconSelected(mapCtx)) {
            ui.iconPreview.setVisibility(View.GONE);
            ui.iconPreview.setImageDrawable(null);
            ui.iconNotSet.setVisibility(View.VISIBLE);
            return;
        }
        android.graphics.Bitmap bmp = com.uvpro.plugin.aprs.AprsIconPreviewLoader
                .loadSelectedIconBitmap(mapCtx, pluginCtx);
        if (bmp != null) {
            ui.iconPreview.setImageBitmap(bmp);
            ui.iconPreview.setVisibility(View.VISIBLE);
            ui.iconNotSet.setVisibility(View.GONE);
        } else {
            ui.iconPreview.setVisibility(View.GONE);
            ui.iconNotSet.setVisibility(View.VISIBLE);
            ui.iconNotSet.setText(APRS_ICON_NOT_SET);
        }
    }

    public static void saveAprsSettingsFromUi(Context ctx, AprsSettingsUi ui) {
        if (ctx == null || ui == null) {
            return;
        }
        SharedPreferences.Editor editor = getPrefs(ctx).edit();
        if (ui.editCallsign != null) {
            editor.putString(PREF_APRS_CALLSIGN,
                    ui.editCallsign.getText().toString().trim().toUpperCase(java.util.Locale.US));
        }
        if (ui.spinnerSsid != null && ui.ssidValues != null) {
            int pos = ui.spinnerSsid.getSelectedItemPosition();
            if (pos >= 0 && pos < ui.ssidValues.length) {
                editor.putString(PREF_APRS_SSID, ui.ssidValues[pos]);
            }
        }
        if (ui.editMessage != null) {
            editor.putString(PREF_APRS_MESSAGE, ui.editMessage.getText().toString());
        }
        editor.apply();
    }

    /**
     * Administration block for ping-reply slot net config (Tools prefs or plugin dialog).
     */
    public static final class AdministrationUi {
        public CheckBox saRelayEnabled;
        public CheckBox rfToTakUplinkEnabled;
        public Switch adminEnabled;
        public EditText editSlotCount;
        public EditText editSlotTime;
        public Button btnDistribute;
        View[] gatedViews;
    }

    /** Adds unlock prompt when administrative settings are locked. */
    public static void appendAdministrativeUnlockPrompt(Context ctx, LinearLayout layout,
                                                        Runnable onUnlocked) {
        TextView header = new TextView(ctx);
        header.setText("\nAdministrative Settings");
        header.setTextColor(0xFF00BCD4);
        header.setTextSize(14);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(header);

        Button unlockBtn = new Button(ctx);
        unlockBtn.setText("Unlock Administrative Settings");
        unlockBtn.setTextColor(COLOR_WHITE);
        unlockBtn.setBackgroundColor(COLOR_STD_BLUE);
        unlockBtn.setOnClickListener(v ->
                promptAdministrativeUnlock(ctx, onUnlocked));
        layout.addView(unlockBtn);
    }

    /** Adds Administration section views to a scrollable dialog layout. */
    public static AdministrationUi appendAdministrationSection(Context ctx, LinearLayout layout) {
        if (!AdminAccessGate.isUnlocked(ctx)) {
            return null;
        }
        AdministrationUi ui = new AdministrationUi();

        TextView header = new TextView(ctx);
        header.setText("\nAdministrative Settings");
        header.setTextColor(0xFF00BCD4);
        header.setTextSize(14);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(header);

        TextView warning = new TextView(ctx);
        warning.setText("For Team Leadership ONLY — Do not use unless directed by higher");
        warning.setTextColor(0xFFFFFFFF);
        warning.setTextSize(12);
        warning.setTypeface(Typeface.DEFAULT_BOLD);
        warning.setPadding(0, 8, 0, 8);
        layout.addView(warning);

        TextView headerSaRelay = new TextView(ctx);
        headerSaRelay.setText("\nSA Relay");
        headerSaRelay.setTextColor(COLOR_STD_BLUE);
        headerSaRelay.setTextSize(16);
        headerSaRelay.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(headerSaRelay);

        TextView saRelayDescription = new TextView(ctx);
        saRelayDescription.setText("Re-broadcast network positions over radio");
        saRelayDescription.setTextColor(COLOR_WHITE);
        saRelayDescription.setTextSize(13);
        saRelayDescription.setPadding(0, 2, 0, 8);
        layout.addView(saRelayDescription);

        LinearLayout rowSaRelay = new LinearLayout(ctx);
        rowSaRelay.setOrientation(LinearLayout.HORIZONTAL);
        rowSaRelay.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelSaRelay = new TextView(ctx);
        labelSaRelay.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelSaRelay.setText("Enable SA Relay");
        labelSaRelay.setTextColor(0xFFFFFFFF);
        labelSaRelay.setTextSize(13);
        rowSaRelay.addView(labelSaRelay);
        ui.saRelayEnabled = new CheckBox(ctx);
        ui.saRelayEnabled.setChecked(isSaRelayEnabled(ctx));
        rowSaRelay.addView(ui.saRelayEnabled);
        layout.addView(rowSaRelay);

        LinearLayout rowRfToTak = new LinearLayout(ctx);
        rowRfToTak.setOrientation(LinearLayout.HORIZONTAL);
        rowRfToTak.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelRfToTak = new TextView(ctx);
        labelRfToTak.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelRfToTak.setText("Enable RF to TAK Uplink Relay");
        labelRfToTak.setTextColor(0xFFFFFFFF);
        labelRfToTak.setTextSize(13);
        rowRfToTak.addView(labelRfToTak);
        ui.rfToTakUplinkEnabled = new CheckBox(ctx);
        ui.rfToTakUplinkEnabled.setChecked(isRfToTakUplinkEnabled(ctx));
        ui.rfToTakUplinkEnabled.setEnabled(ui.saRelayEnabled.isChecked());
        rowRfToTak.addView(ui.rfToTakUplinkEnabled);
        layout.addView(rowRfToTak);
        ui.saRelayEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (ui.rfToTakUplinkEnabled != null) {
                ui.rfToTakUplinkEnabled.setEnabled(isChecked);
                if (!isChecked) {
                    ui.rfToTakUplinkEnabled.setChecked(false);
                }
            }
        });

        TextView hintSaRelay = new TextView(ctx);
        hintSaRelay.setText(
                "Throttled: one update per contact per 30 s. Requires TAK server + radio connected.");
        hintSaRelay.setTextColor(0xFF888888);
        hintSaRelay.setTextSize(11);
        hintSaRelay.setPadding(0, 2, 0, 0);
        layout.addView(hintSaRelay);

        TextView hintRfToTakUplink = new TextView(ctx);
        hintRfToTakUplink.setText(
                "Forwards RF CoT to TAK network. Active only when SA Relay is enabled.");
        hintRfToTakUplink.setTextColor(0xFF888888);
        hintRfToTakUplink.setTextSize(11);
        hintRfToTakUplink.setPadding(0, 2, 0, 12);
        layout.addView(hintRfToTakUplink);

        LinearLayout rowAdmin = new LinearLayout(ctx);
        rowAdmin.setOrientation(LinearLayout.HORIZONTAL);
        rowAdmin.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelAdmin = new TextView(ctx);
        labelAdmin.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelAdmin.setText("Enable administrative settings");
        labelAdmin.setTextColor(0xFFFFFFFF);
        labelAdmin.setTextSize(13);
        rowAdmin.addView(labelAdmin);
        ui.adminEnabled = new Switch(ctx);
        ui.adminEnabled.setChecked(getPrefs(ctx).getBoolean(
                NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false));
        rowAdmin.addView(ui.adminEnabled);
        layout.addView(rowAdmin);

        TextView labelSlots = new TextView(ctx);
        labelSlots.setText("\nSlot count");
        labelSlots.setTextColor(0xFFAAAAAA);
        layout.addView(labelSlots);
        ui.editSlotCount = new EditText(ctx);
        ui.editSlotCount.setText(String.valueOf(NetSlotConfig.getSlotCount(ctx)));
        ui.editSlotCount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        ui.editSlotCount.setTextColor(0xFFFFFFFF);
        layout.addView(ui.editSlotCount);

        TextView labelTime = new TextView(ctx);
        labelTime.setText("Slot time (seconds)");
        labelTime.setTextColor(0xFFAAAAAA);
        layout.addView(labelTime);
        ui.editSlotTime = new EditText(ctx);
        ui.editSlotTime.setText(String.valueOf(NetSlotConfig.getSlotTimeSec(ctx)));
        ui.editSlotTime.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        ui.editSlotTime.setTextColor(0xFFFFFFFF);
        layout.addView(ui.editSlotTime);

        TextView distributeWarning = new TextView(ctx);
        distributeWarning.setText(DISTRIBUTE_NET_WARNING);
        distributeWarning.setTextColor(COLOR_WARNING_RED);
        distributeWarning.setTextSize(13);
        distributeWarning.setTypeface(Typeface.DEFAULT_BOLD);
        distributeWarning.setPadding(0, 12, 0, 8);
        layout.addView(distributeWarning);

        ui.btnDistribute = new Button(ctx);
        ui.btnDistribute.setText("Distribute to net");
        applyPillButtonStyle(ui.btnDistribute, true);
        ui.btnDistribute.setOnClickListener(v -> {
            pulseDistributeButtonFeedback(v, v.isEnabled());
            distributeNetSlotsFromUi(ctx, ui);
        });
        layout.addView(ui.btnDistribute);

        ui.gatedViews = new View[]{
                ui.editSlotCount, ui.editSlotTime, ui.btnDistribute
        };
        ui.adminEnabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                applyAdministrationGating(ui, isChecked));
        applyAdministrationGating(ui, ui.adminEnabled.isChecked());
        return ui;
    }

    public static void saveAdministrationFromUi(Context ctx, AdministrationUi ui) {
        if (ctx == null || ui == null) {
            return;
        }
        SharedPreferences prefs = getPrefs(ctx);
        prefs.edit()
                .putBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED,
                        ui.adminEnabled.isChecked())
                .putBoolean(PREF_SA_RELAY_ENABLED,
                        ui.saRelayEnabled != null && ui.saRelayEnabled.isChecked())
                .putBoolean(PREF_RF_TO_TAK_UPLINK_ENABLED,
                        ui.rfToTakUplinkEnabled != null && ui.rfToTakUplinkEnabled.isChecked())
                .apply();
        try {
            int slots = Integer.parseInt(ui.editSlotCount.getText().toString().trim());
            float sec = Float.parseFloat(ui.editSlotTime.getText().toString().trim());
            NetSlotConfig.saveLocalSlotSettings(ctx, slots, sec);
        } catch (Exception ignored) {
            NetSlotConfig.saveLocalSlotSettings(ctx,
                    NetSlotConfig.DEFAULT_SLOT_COUNT,
                    NetSlotConfig.DEFAULT_SLOT_TIME_SEC);
        }
    }

    private static void applyAdministrationGating(AdministrationUi ui, boolean enabled) {
        if (ui.gatedViews == null) {
            return;
        }
        float alpha = enabled ? 1f : 0.38f;
        for (View v : ui.gatedViews) {
            if (v != null) {
                v.setEnabled(enabled);
                v.setAlpha(alpha);
            }
        }
        if (ui.btnDistribute != null) {
            applyPillButtonStyle(ui.btnDistribute, enabled);
        }
    }

    private static void distributeNetSlotsFromUi(Context ctx, AdministrationUi ui) {
        if (!ui.adminEnabled.isChecked()) {
            Toast.makeText(ctx, "Enable administrative settings first", Toast.LENGTH_LONG).show();
            return;
        }
        saveAdministrationFromUi(ctx, ui);
        String error = distributeNetSlotsOrError(ctx);
        if (error == null) {
            int slots = NetSlotConfig.getSlotCount(ctx);
            float sec = NetSlotConfig.getSlotTimeSec(ctx);
            Toast.makeText(ctx,
                    "Slot config sent (" + slots + " slots, " + sec + " s)",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(ctx, error, Toast.LENGTH_LONG).show();
        }
    }

    private static int dp(Context ctx, int value) {
        if (ctx == null) {
            return value;
        }
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
