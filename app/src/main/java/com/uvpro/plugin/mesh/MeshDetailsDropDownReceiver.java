package com.uvpro.plugin.mesh;

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
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.ipc.AtakBroadcast;
import com.uvpro.plugin.UVProContactHandler;
import com.uvpro.plugin.chat.ChatBridge;
import com.uvpro.plugin.cot.CotBridge;

/**
 * One-page Mesh details panel (APRS-style) for map-clicked Mesh contacts.
 */
public class MeshDetailsDropDownReceiver extends DropDownReceiver
        implements DropDown.OnStateListener {

    public static final String SHOW_MESH_DETAILS =
            "com.uvpro.plugin.SHOW_MESH_DETAILS";
    public static final String EXTRA_TARGET_UID = "targetUID";

    private final Context pluginContext;
    private final CotBridge cotBridge;

    private View panelView;
    private TextView titleView;
    private TextView bodyView;
    private Button favoriteButton;
    private Button sendMessageButton;
    private Button deleteContactButton;
    private String openUid;
    private boolean dropDownVisible;

    public MeshDetailsDropDownReceiver(MapView mapView, Context pluginContext,
                                       CotBridge cotBridge) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.cotBridge = cotBridge;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        if (!SHOW_MESH_DETAILS.equals(intent.getAction())) {
            return;
        }
        String uid = intent.getStringExtra(EXTRA_TARGET_UID);
        if (uid == null || uid.isEmpty()) {
            return;
        }
        MapItem item = getMapView().getRootGroup().deepFindUID(uid);
        if (item == null || !CotBridge.isUvproMeshMarker(item)) {
            return;
        }

        ensurePanel();
        openUid = uid;
        String title = item.getMetaString("callsign", item.getTitle());
        if (title == null || title.trim().isEmpty()) {
            title = uid;
        }
        titleView.setText(title);
        refreshBody(item);
        wireFavoriteButton(uid, title);
        wireSendMessageButton(uid, title);
        wireDeleteContactButton(uid, title);

        try {
            setSelected(item, "asset:/icons/outline.png");
        } catch (Exception ignored) {
        }

        showDropDown(panelView,
                HALF_WIDTH, FULL_HEIGHT,
                FULL_WIDTH, HALF_HEIGHT,
                false, this);
    }

    private void ensurePanel() {
        if (panelView != null) {
            return;
        }
        int layoutId = pluginContext.getResources().getIdentifier(
                "mesh_details_dropdown", "layout", pluginContext.getPackageName());
        panelView = LayoutInflater.from(pluginContext).inflate(layoutId, null);
        titleView = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("mesh_details_title", "id", pluginContext.getPackageName()));
        bodyView = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("mesh_details_body", "id", pluginContext.getPackageName()));
        favoriteButton = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("mesh_details_favorite", "id", pluginContext.getPackageName()));
        sendMessageButton = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("mesh_details_send_message", "id", pluginContext.getPackageName()));
        deleteContactButton = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("mesh_details_delete_contact", "id", pluginContext.getPackageName()));
        if (bodyView != null) {
            bodyView.setMovementMethod(new ScrollingMovementMethod());
        }
    }

    private void refreshBody(MapItem item) {
        if (bodyView == null || item == null) {
            return;
        }
        String body = item.getMetaString(CotBridge.META_UVPRO_MESH_DETAILS, "");
        if (body == null || body.trim().isEmpty()) {
            body = "No MeshCore details captured yet.\n\n"
                    + "Wait for the next advert from this node.";
        }
        bodyView.setText(body);
    }

    private void wireFavoriteButton(String uid, String currentName) {
        if (favoriteButton == null) {
            return;
        }
        favoriteButton.setOnClickListener(v -> {
            boolean ok = UVProContactHandler.promoteMeshFavoriteContactByUid(uid, currentName);
            String target = UVProContactHandler.formatMeshFavoriteName(currentName, uid);
            Toast.makeText(getMapView().getContext(),
                    ok ? "Favorited " + target : "Could not favorite contact",
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void wireSendMessageButton(String uid, String callsign) {
        if (sendMessageButton == null) {
            return;
        }
        sendMessageButton.setOnClickListener(v -> {
            // Native MeshCore drops a direct message unless the other radio has this
            // node saved as a favorite. Warn at the moment they open the chat.
            if (uid != null && (uid.startsWith("MESHCORE-NODE-")
                    || uid.startsWith("MESHCORE-RPTR-"))) {
                Toast.makeText(getMapView().getContext(),
                        "If recipient does not have you as a favorite, your message will not be delivered.",
                        Toast.LENGTH_LONG).show();
            }
            // Force exact UID contact targeting for map-selected Mesh markers.
            // Using callsign-first resolution can bind to unrelated non-mesh contacts.
            String ensured = ChatBridge.ensurePluginChatContactExactUid(callsign, uid);
            if (ensured == null || ensured.isEmpty()) {
                Toast.makeText(getMapView().getContext(),
                        "Could not create Mesh chat contact",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (cotBridge != null) {
                cotBridge.registerBtechContactUid(ensured);
                cotBridge.registerBtechContactId(uid, ensured);
                cotBridge.registerBtechContactId(callsign, ensured);
            }
            if (!ChatBridge.openNativeChatConversation(ensured)) {
                Toast.makeText(getMapView().getContext(),
                        "Could not open ATAK chat window",
                        Toast.LENGTH_LONG).show();
            } else {
                AtakBroadcast.getInstance().sendBroadcast(
                        new Intent("com.atakmap.android.maps.HIDE_MENU"));
            }
        });
    }

    private void wireDeleteContactButton(String uid, String displayName) {
        if (deleteContactButton == null) {
            return;
        }
        deleteContactButton.setOnClickListener(v -> deleteMeshContact(uid, displayName));
    }

    private void deleteMeshContact(String markerUid, String displayName) {
        MapView mv = getMapView();
        Context ctx = mv != null ? mv.getContext() : pluginContext;
        if (mv == null || mv.getRootGroup() == null || markerUid == null || markerUid.isEmpty()) {
            return;
        }
        MapItem marker = mv.getRootGroup().deepFindUID(markerUid);
        if (marker != null && marker.getGroup() != null && CotBridge.isUvproMeshMarker(marker)) {
            marker.getGroup().removeItem(marker);
        }
        try {
            Contacts contacts = Contacts.getInstance();
            Contact contact = contacts.getContactByUuid(markerUid);
            if (contact != null) {
                contacts.removeContact(contact);
                contacts.updateTotalUnreadCount();
            }
        } catch (Exception ignored) {
        }
        UVProContactHandler.clearUnread(markerUid);
        String label = displayName != null && !displayName.trim().isEmpty()
                ? displayName.trim() : markerUid;
        Toast.makeText(ctx, "Deleted contact " + label, Toast.LENGTH_SHORT).show();
        closeDropDown();
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
        favoriteButton = null;
        sendMessageButton = null;
        deleteContactButton = null;
        openUid = null;
        dropDownVisible = false;
    }
}
