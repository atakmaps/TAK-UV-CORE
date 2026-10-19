package com.uvpro.plugin.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.TextView;

import com.atakmap.android.maps.MapView;
import com.uvpro.plugin.R;

/**
 * Which radio controls are on screen. Unset until the operator picks once.
 * Hiding a radio here does not stop it from connecting or transmitting.
 */
public final class RadioProfile {

    public static final String PREF = "uvpro_radio_profile";
    public static final String MESH = "mesh";
    public static final String UVPRO = "uvpro";
    public static final String BOTH = "both";

    private static View openPanel;

    private RadioProfile() {
    }

    public static void setOpenPanel(View root) {
        openPanel = root;
    }

    public static void refreshOpenPanel() {
        if (openPanel != null) {
            applyPanel(openPanel, atakContext());
        }
    }

    public static boolean isChosen(Context atakContext) {
        return read(atakContext) != null;
    }

    /** {@code null} when the operator has not chosen yet. */
    public static String read(Context atakContext) {
        if (atakContext == null) {
            return null;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(atakContext);
        if (!prefs.contains(PREF)) {
            return null;
        }
        String value = prefs.getString(PREF, "");
        if (MESH.equals(value) || UVPRO.equals(value) || BOTH.equals(value)) {
            return value;
        }
        return null;
    }

    public static void write(Context atakContext, String value) {
        if (atakContext == null) {
            return;
        }
        if (!MESH.equals(value) && !UVPRO.equals(value) && !BOTH.equals(value)) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(atakContext)
                .edit()
                .putString(PREF, value)
                .commit();
    }

    /** Unset shows both, so the map stays as it is until the first choice. */
    public static boolean shows(RadioSurfaceCatalog.Role role, Context atakContext) {
        String profile = read(atakContext);
        if (role == RadioSurfaceCatalog.Role.SHARED || profile == null || BOTH.equals(profile)) {
            return true;
        }
        if (role == RadioSurfaceCatalog.Role.MESH) {
            return MESH.equals(profile);
        }
        return UVPRO.equals(profile);
    }

    public static void applyPanel(View root, Context atakContext) {
        if (root == null) {
            return;
        }
        setSections(root, RadioSurfaceCatalog.UVPRO_SECTIONS,
                shows(RadioSurfaceCatalog.Role.UVPRO, atakContext));
        setSections(root, RadioSurfaceCatalog.MESH_SECTIONS,
                shows(RadioSurfaceCatalog.Role.MESH, atakContext));
        TextView title = root.findViewById(R.id.header_title);
        if (title != null) {
            title.setText(headerTitle(read(atakContext)));
        }
    }

    public static void applyMapIcons(Context pluginContext) {
        if (pluginContext == null) {
            return;
        }
        Context atak = atakContext();
        if (shows(RadioSurfaceCatalog.Role.UVPRO, atak)) {
            if (!RadioStatusOverlay.isInstalled()) {
                RadioStatusOverlay.install(pluginContext);
            }
        } else {
            RadioStatusOverlay.uninstall();
        }
        if (shows(RadioSurfaceCatalog.Role.MESH, atak)) {
            if (!MeshStatusOverlay.isInstalled()) {
                MeshStatusOverlay.install(pluginContext);
            }
        } else {
            MeshStatusOverlay.uninstall();
        }
    }

    /**
     * Drop a live link on a radio the operator has hidden. Saved pairings stay.
     */
    public static void quietHiddenRadios(com.uvpro.plugin.bluetooth.BtConnectionManager uvpro,
                                          com.uvpro.plugin.bluetooth.MeshBtConnectionManager mesh) {
        Context atak = atakContext();
        if (!shows(RadioSurfaceCatalog.Role.UVPRO, atak) && uvpro != null) {
            uvpro.setRadioSilenceEnabled(false);
            if (uvpro.isConnected() || uvpro.isConnecting()) {
                android.util.Log.i("UVPro.RadioProfile", "Disconnecting hidden UV-PRO");
                uvpro.disconnect();
            }
        }
        if (!shows(RadioSurfaceCatalog.Role.MESH, atak) && mesh != null) {
            mesh.setRadioSilenceEnabled(false);
            if (mesh.isConnected() || mesh.isConnecting()) {
                android.util.Log.i("UVPro.RadioProfile", "Disconnecting hidden MeshCore");
                mesh.disconnect();
            }
        }
    }

    public static String label(String profile) {
        if (MESH.equals(profile)) {
            return "MeshCore";
        }
        if (UVPRO.equals(profile)) {
            return "UV-PRO";
        }
        if (BOTH.equals(profile)) {
            return "Both";
        }
        return "Not chosen";
    }

    private static String headerTitle(String profile) {
        if (MESH.equals(profile)) {
            return "MESHCORE";
        }
        if (UVPRO.equals(profile)) {
            return "UV-PRO";
        }
        return "UV-PRO-MESH";
    }

    private static void setSections(View root, int[] ids, boolean show) {
        for (int id : ids) {
            View child = root.findViewById(id);
            if (child == null) {
                continue;
            }
            // The terminal body starts closed. Hiding UV-PRO must not force it open.
            if (show && id == R.id.packet_terminal_section) {
                continue;
            }
            child.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private static Context atakContext() {
        MapView mv = MapView.getMapView();
        return mv != null ? mv.getContext() : null;
    }
}
