package com.uvpro.plugin.aprs;

import android.content.Context;
import android.content.Intent;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import android.util.Log;
import com.uvpro.plugin.aprs.AprsTrackManager;
import com.uvpro.plugin.chat.ChatBridge;
import com.uvpro.plugin.contacts.ContactTracker;
import com.uvpro.plugin.contacts.RadioContact;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.ui.SettingsFragment;

/**
 * APRS-specific details panel: shows comment/telemetry/symbol data from the RF packet,
 * not the generic ATAK CoT point editor (range/bearing, unit type, etc.).
 */
public class AprsDetailsDropDownReceiver extends DropDownReceiver
        implements DropDown.OnStateListener {

    public static final String SHOW_APRS_DETAILS =
            "com.uvpro.plugin.SHOW_APRS_DETAILS";
    /** Fired when stored APRS metadata changes; refreshes an open panel. */
    public static final String REFRESH_APRS_DETAILS =
            "com.uvpro.plugin.REFRESH_APRS_DETAILS";
    public static final String EXTRA_TARGET_UID = "targetUID";

    private static final String TAG = "UVPro.APRS.Details";

    private final Context pluginContext;
    private final ContactTracker contactTracker;
    private final CotBridge cotBridge;

    private View panelView;
    private TextView titleView;
    private TextView bodyView;
    private Button sendMessageButton;
    private Button deleteContactButton;
    private String openUid;
    private boolean dropDownVisible;

    public AprsDetailsDropDownReceiver(MapView mapView, Context pluginContext,
                                       ContactTracker contactTracker,
                                       CotBridge cotBridge) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.contactTracker = contactTracker;
        this.cotBridge = cotBridge;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        String uid = intent.getStringExtra(EXTRA_TARGET_UID);
        if (uid == null || uid.isEmpty()) {
            return;
        }

        if (REFRESH_APRS_DETAILS.equals(action)) {
            if (dropDownVisible && uid.equals(openUid)) {
                refreshBody(uid);
            }
            return;
        }
        if (!SHOW_APRS_DETAILS.equals(action)) {
            return;
        }
        if (!com.uvpro.plugin.ui.RadioProfile.shows(
                com.uvpro.plugin.ui.RadioSurfaceCatalog.Role.UVPRO, getMapView().getContext())) {
            return;
        }

        MapItem item = getMapView().getRootGroup().deepFindUID(uid);
        if (item == null || !CotBridge.isUvproAprsMarker(item)) {
            return;
        }

        ensurePanel();
        openUid = uid;
        String callsign = item.getMetaString("callsign", item.getTitle());
        if (callsign == null || callsign.isEmpty()) {
            callsign = uid.startsWith("ANDROID-") ? uid.substring("ANDROID-".length()) : uid;
        }
        titleView.setText(callsign);
        refreshBody(uid);
        if (sendMessageButton != null) {
            boolean isRepeater = item.getMetaBoolean(CotBridge.META_UVPRO_MESH_REPEATER, false);
            sendMessageButton.setVisibility(isRepeater ? View.GONE : View.VISIBLE);
        }
        wireSendMessageButton(callsign);
        wireDeleteContactButton(uid, callsign);

        try {
            setSelected(item, "asset:/icons/outline.png");
        } catch (Exception ignored) {
        }

        showDropDown(panelView,
                HALF_WIDTH, FULL_HEIGHT,
                FULL_WIDTH, HALF_HEIGHT,
                false, this);
    }

    private void refreshBody(String uid) {
        if (bodyView == null || uid == null) {
            return;
        }
        MapItem item = getMapView().getRootGroup().deepFindUID(uid);
        if (item == null) {
            return;
        }
        String callsign = item.getMetaString("callsign", item.getTitle());
        if (callsign == null || callsign.isEmpty()) {
            callsign = uid.startsWith("ANDROID-") ? uid.substring("ANDROID-".length()) : uid;
        }
        String body = item.getMetaString(CotBridge.META_UVPRO_APRS_DETAILS, "");
        if (body.isEmpty()) {
            RadioContact rc = contactTracker != null
                    ? contactTracker.getContact(callsign) : null;
            if (rc != null && rc.getLastAprsDetailsText() != null) {
                body = rc.getLastAprsDetailsText();
            }
        }
        body = AprsInfoFormatter.stripActivitySection(body);
        if (contactTracker != null) {
            RadioContact rc = contactTracker.getContact(callsign);
            if (rc != null) {
                contactTracker.refreshContactStatus(rc);
                body = body + AprsInfoFormatter.formatActivitySection(rc);
            }
        }
        if (body.isEmpty()) {
            body = "No APRS metadata stored yet.\n\n"
                    + "Wait for the next position packet from this station.";
        }
        bodyView.setText(body);
        Log.d(TAG, "Refreshed APRS details uid=" + uid + " len=" + body.length());
    }

    private void ensurePanel() {
        if (panelView != null) {
            return;
        }
        int layoutId = pluginContext.getResources().getIdentifier(
                "aprs_details_dropdown", "layout", pluginContext.getPackageName());
        panelView = LayoutInflater.from(pluginContext).inflate(layoutId, null);
        titleView = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("aprs_details_title", "id", pluginContext.getPackageName()));
        bodyView = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("aprs_details_body", "id", pluginContext.getPackageName()));
        sendMessageButton = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("aprs_details_send_message", "id", pluginContext.getPackageName()));
        deleteContactButton = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("aprs_details_delete_contact", "id", pluginContext.getPackageName()));
        if (bodyView != null) {
            bodyView.setMovementMethod(new ScrollingMovementMethod());
        }
    }

    private void wireSendMessageButton(String targetCallsign) {
        if (sendMessageButton == null) {
            return;
        }
        sendMessageButton.setOnClickListener(v -> openNativeAprsChat(targetCallsign));
    }

    private void wireDeleteContactButton(String markerUid, String callsign) {
        if (deleteContactButton == null) {
            return;
        }
        deleteContactButton.setOnClickListener(v -> deleteAprsContact(markerUid, callsign));
    }

    private void deleteAprsContact(String markerUid, String callsign) {
        MapView mv = getMapView();
        Context ctx = mv != null ? mv.getContext() : pluginContext;
        if (mv == null || mv.getRootGroup() == null || markerUid == null || markerUid.isEmpty()) {
            return;
        }
        MapGroup root = mv.getRootGroup();
        MapItem marker = root.deepFindUID(markerUid);
        if (marker != null && marker.getGroup() != null && CotBridge.isUvproAprsMarker(marker)) {
            marker.getGroup().removeItem(marker);
        }
        String trackUid = "uvpro-aprs-track-" + markerUid;
        MapItem track = root.deepFindUID(trackUid);
        if (track != null && track.getGroup() != null
                && track.getMetaBoolean(AprsTrackManager.META_UVPRO_APRS_TRACK, false)) {
            track.getGroup().removeItem(track);
        }
        Toast.makeText(ctx, "Deleted APRS contact " + callsign, Toast.LENGTH_SHORT).show();
        closeDropDown();
    }

    private void openNativeAprsChat(String targetCallsign) {
        Context ctx = getMapView() != null ? getMapView().getContext() : pluginContext;
        if (ctx == null) {
            return;
        }
        String to = AprsMessageTransmitter.normalizeAddressee(targetCallsign);
        if (to.isEmpty()) {
            Toast.makeText(ctx, "Invalid APRS target callsign", Toast.LENGTH_LONG).show();
            return;
        }
        if (!SettingsFragment.isValidAprsCallsign(SettingsFragment.getAprsCallsign(ctx))) {
            Toast.makeText(ctx,
                    "Set a valid APRS callsign in Edit APRS Settings first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String uid = ChatBridge.ensureAprsPluginChatContact(to);
        if (uid.isEmpty()) {
            Toast.makeText(ctx, "Could not create APRS chat contact", Toast.LENGTH_LONG).show();
            return;
        }
        if (cotBridge != null) {
            cotBridge.registerBtechContactUid(uid);
            cotBridge.registerBtechContactId(to, uid);
        }
        if (!ChatBridge.openNativeChatConversation(uid)) {
            Toast.makeText(ctx, "Could not open ATAK chat window", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onDropDownVisible(boolean v) {
        dropDownVisible = v;
        if (!v) {
            openUid = null;
        }
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void disposeImpl() {
        panelView = null;
        titleView = null;
        bodyView = null;
        sendMessageButton = null;
        deleteContactButton = null;
    }
}
