package com.uvpro.plugin.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.util.Log;
import android.view.MotionEvent;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.LinearLayoutWidget;
import com.atakmap.android.widgets.MapWidget;
import com.atakmap.android.widgets.MarkerIconWidget;
import com.atakmap.android.widgets.RootLayoutWidget;
import com.atakmap.coremap.maps.assets.Icon;
import com.uvpro.plugin.R;
import com.uvpro.plugin.UVProDropDownReceiver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Status widget anchored to the ATAK top-right layout.
 *
 * Connected    → full-colour Baofeng Tech icon (green tint)
 * Disconnected → same icon desaturated (red tint)
 *
 * Icons are written to the app's files dir so ATAK's GL renderer can load
 * them via file:// URI (android.resource:// URIs don't resolve cross-APK in
 * ATAK's OpenGL widget renderer).
 *
 * Tap opens the UV-PRO plugin panel (same as Menu → Tools → UV-PRO).
 */
public class RadioStatusOverlay extends MarkerIconWidget implements MapWidget.OnClickListener {

    private static final String TAG = "UVPro.StatusOverlay";
    private static final int ICON_WIDTH  = 64;
    private static final int ICON_HEIGHT = 64;

    private static RadioStatusOverlay instance;
    private static boolean lastKnownConnected = false;

    private final MapView mapView;
    private final String connectedUri;
    private final String disconnectedUri;

    private RadioStatusOverlay(Context pluginContext, MapView mv,
                                LinearLayoutWidget trLayout) {
        this.mapView = mv;
        this.connectedUri    = extractIcon(pluginContext, mv, R.drawable.ic_radio_status_connected,    "ic_radio_connected.png");
        this.disconnectedUri = extractIcon(pluginContext, mv, R.drawable.ic_radio_status_disconnected, "ic_radio_disconnected.png");
        Log.d(TAG, "connectedUri=" + connectedUri);
        Log.d(TAG, "disconnectedUri=" + disconnectedUri);
        trLayout.addWidget(this);
        // Pin UV-PRO icon at top-right with a safer inset from edges.
        setMargins(0f, 12f, 12f, 0f);
        setTouchable(true);
        addOnClickListener(this);
        applyIcon(false);
        Log.d(TAG, "Widget installed in TOP_RIGHT");
    }

    @Override
    public void onMapWidgetClick(MapWidget widget, MotionEvent event) {
        openPluginPanel();
    }

    private static void openPluginPanel() {
        try {
            AtakBroadcast.getInstance().sendBroadcast(
                    new Intent(UVProDropDownReceiver.SHOW_PLUGIN));
            Log.d(TAG, "Status icon tapped — opening UV-PRO panel");
        } catch (Exception e) {
            Log.e(TAG, "Failed to open UV-PRO panel from status icon", e);
        }
    }

    public static boolean isInstalled() {
        return instance != null;
    }

    public static void install(Context pluginContext) {
        MapView mv = MapView.getMapView();
        if (mv == null) { Log.e(TAG, "install: MapView null"); return; }
        uninstall();
        try {
            RootLayoutWidget root =
                    (RootLayoutWidget) mv.getComponentExtra("rootLayoutWidget");
            if (root == null) {
                Log.e(TAG, "install: rootLayoutWidget null — retrying in 2s");
                mv.postDelayed(() -> install(pluginContext), 2000);
                return;
            }
            LinearLayoutWidget tr = root.getLayout(RootLayoutWidget.TOP_RIGHT);
            if (tr == null) { Log.e(TAG, "install: TOP_RIGHT null"); return; }
            instance = new RadioStatusOverlay(pluginContext, mv, tr);
            // Apply whatever state the radio is already in
            instance.applyIcon(lastKnownConnected);
            Log.d(TAG, "Widget installed, connected=" + lastKnownConnected);
        } catch (Exception e) {
            Log.e(TAG, "install failed", e);
        }
    }

    public static void uninstall() {
        if (instance == null) return;
        try {
            instance.removeOnClickListener(instance);
            RootLayoutWidget root =
                    (RootLayoutWidget) instance.mapView.getComponentExtra("rootLayoutWidget");
            root.getLayout(RootLayoutWidget.TOP_RIGHT).removeWidget(instance);
        } catch (Exception ignored) {}
        instance = null;
    }

    public static void setConnected(boolean connected) {
        lastKnownConnected = connected;
        if (instance == null) return;
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        mv.post(() -> { if (instance != null) instance.applyIcon(connected); });
    }

    private void applyIcon(boolean connected) {
        String uri = connected ? connectedUri : disconnectedUri;
        if (uri == null) { Log.e(TAG, "applyIcon: uri is null"); return; }
        Log.d(TAG, "applyIcon connected=" + connected);
        Icon.Builder b = new Icon.Builder();
        b.setAnchor(0, 0);
        b.setColor(Icon.STATE_DEFAULT, Color.WHITE); // neutral — PNG carries all color
        b.setSize(ICON_WIDTH, ICON_HEIGHT);
        b.setImageUri(Icon.STATE_DEFAULT, uri);
        setIcon(b.build());
    }

    /** Copies a drawable resource to ATAK's cache dir and returns a file:// URI. */
    private static String extractIcon(Context pluginCtx, MapView mv, int resId, String filename) {
        try {
            // Plugin runs inside ATAK's process — use ATAK's cache dir (writable)
            File dir = new File(mv.getContext().getCacheDir(), "uvpro_icons");
            dir.mkdirs();
            File out = new File(dir, filename);
            try (InputStream in = pluginCtx.getResources().openRawResource(resId);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
            }
            Log.d(TAG, "Extracted icon to " + out.getAbsolutePath() + " size=" + out.length());
            return "file://" + out.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "extractIcon failed for " + filename, e);
            return null;
        }
    }
}
