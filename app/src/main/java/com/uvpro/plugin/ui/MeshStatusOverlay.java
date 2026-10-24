package com.uvpro.plugin.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
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

/**
 * MeshCore status widget anchored in ATAK top-right layout.
 * Mirrors UV-PRO overlay behavior: tap opens plugin panel, icon changes by connection state.
 */
public class MeshStatusOverlay extends MarkerIconWidget implements MapWidget.OnClickListener {

    private static final String TAG = "UVPro.MeshOverlay";
    private static final int ICON_WIDTH = 64;
    private static final int ICON_HEIGHT = 64;

    private static MeshStatusOverlay instance;
    private static boolean lastKnownConnected = false;
    // Install-generation counter: incremented on every install() call so stale
    // postDelayed retries can detect they are no longer the current install attempt.
    private static int installGeneration = 0;

    private final MapView mapView;
    private final String connectedUri;
    private final String disconnectedUri;

    private MeshStatusOverlay(Context pluginContext, MapView mv, LinearLayoutWidget trLayout) {
        this.mapView = mv;
        this.connectedUri = extractIcon(pluginContext, mv,
                R.drawable.ic_mesh_status_connected, "ic_mesh_connected.png");
        this.disconnectedUri = extractIcon(pluginContext, mv,
                R.drawable.ic_mesh_status_disconnected, "ic_mesh_disconnected.png");
        trLayout.addWidget(this);
        // Raise MeshCore by about one icon width while keeping right alignment.
        setMargins(0f, 24f, 12f, 0f);
        setTouchable(true);
        addOnClickListener(this);
        applyIcon(false);
    }

    @Override
    public void onMapWidgetClick(MapWidget widget, MotionEvent event) {
        try {
            AtakBroadcast.getInstance().sendBroadcast(
                    new Intent(UVProDropDownReceiver.SHOW_PLUGIN));
            Log.d(TAG, "Mesh status icon tapped — opening UV-PRO panel");
        } catch (Exception e) {
            Log.e(TAG, "Failed to open panel from Mesh status icon", e);
        }
    }

    public static boolean isInstalled() {
        return instance != null;
    }

    public static void install(Context pluginContext) {
        // Skip if already installed — prevents duplicate widget on repeated panel opens.
        if (instance != null) {
            Log.d(TAG, "install skipped: already installed");
            return;
        }
        Log.d(TAG, "install requested");
        MapView mv = MapView.getMapView();
        if (mv == null) {
            Log.w(TAG, "install deferred: MapView is null");
            return;
        }
        final int myGeneration = ++installGeneration;
        try {
            RootLayoutWidget root =
                    (RootLayoutWidget) mv.getComponentExtra("rootLayoutWidget");
            if (root == null) {
                Log.d(TAG, "install deferred: rootLayoutWidget not ready");
                mv.postDelayed(() -> {
                    // Only proceed if this retry is still the current install attempt
                    // and no successful install has happened in the meantime.
                    if (myGeneration == installGeneration && instance == null) {
                        install(pluginContext);
                    }
                }, 2000);
                return;
            }
            LinearLayoutWidget tr = root.getLayout(RootLayoutWidget.TOP_RIGHT);
            if (tr == null) {
                Log.w(TAG, "install aborted: TOP_RIGHT layout missing");
                return;
            }
            instance = new MeshStatusOverlay(pluginContext, mv, tr);
            instance.applyIcon(lastKnownConnected);
            Log.d(TAG, "Widget installed, connected=" + lastKnownConnected);
        } catch (Exception e) {
            Log.e(TAG, "install failed", e);
        }
    }

    public static void uninstall() {
        if (instance == null) {
            return;
        }
        try {
            instance.removeOnClickListener(instance);
            RootLayoutWidget root =
                    (RootLayoutWidget) instance.mapView.getComponentExtra("rootLayoutWidget");
            root.getLayout(RootLayoutWidget.TOP_RIGHT).removeWidget(instance);
        } catch (Exception ignored) {
        }
        instance = null;
    }

    public static void setConnected(boolean connected) {
        lastKnownConnected = connected;
        if (instance == null) {
            return;
        }
        MapView mv = MapView.getMapView();
        if (mv == null) {
            return;
        }
        mv.post(() -> {
            if (instance != null) {
                instance.applyIcon(connected);
            }
        });
    }

    private void applyIcon(boolean connected) {
        String uri = connected ? connectedUri : disconnectedUri;
        if (uri == null) {
            Log.w(TAG, "applyIcon skipped: uri missing connected=" + connected);
            return;
        }
        Icon.Builder b = new Icon.Builder();
        b.setAnchor(0, 0);
        b.setColor(Icon.STATE_DEFAULT, Color.WHITE);
        b.setSize(ICON_WIDTH, ICON_HEIGHT);
        b.setImageUri(Icon.STATE_DEFAULT, uri);
        setIcon(b.build());
        Log.d(TAG, "applyIcon connected=" + connected + " uri=" + uri);
    }

    private static String extractIcon(Context pluginCtx, MapView mv, int resId, String filename) {
        try {
            File dir = new File(mv.getContext().getCacheDir(), "uvpro_icons");
            dir.mkdirs();
            File out = new File(dir, filename);

            Drawable drawable = pluginCtx.getResources().getDrawable(resId, pluginCtx.getTheme());
            if (drawable == null) {
                Log.e(TAG, "extractIcon failed for " + filename + ": drawable is null");
                return null;
            }

            int width = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : ICON_WIDTH;
            int height = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : ICON_HEIGHT;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);

            try (FileOutputStream fos = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            bitmap.recycle();

            Log.d(TAG, "Extracted icon to " + out.getAbsolutePath() + " size=" + out.length());
            return "file://" + out.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "extractIcon failed for " + filename, e);
            return null;
        }
    }
}
