package com.uvpro.plugin.ui;

import com.uvpro.plugin.R;

/**
 * Labels for the first-run radio choice. {@link RadioProfile} hides these
 * sections. A hidden radio does not scan, connect, or transmit. Radio silence
 * applies only to the radios still shown.
 *
 * <p>The Tools menu entry stays in every mode. It is how the panel opens.
 * The map icons that follow a role are the top-right status widgets:
 * {@code RadioStatusOverlay} is UV-PRO, {@code MeshStatusOverlay} is MeshCore.
 * Mesh map markers ({@code MESHCORE-NODE-}, {@code MESHCORE-RPTR-}) and the
 * mesh details panel are MeshCore. APRS markers, trails, and the APRS details
 * panel are UV-PRO. Mesh and APRS items stay saved when they are hidden.
 */
public final class RadioSurfaceCatalog {

    public enum Role {
        UVPRO,
        MESH,
        SHARED
    }

    /** Whole blocks that disappear together when that radio is hidden. */
    public static final int[] UVPRO_SECTIONS = {
            R.id.section_uvpro_connection,
            R.id.section_uvpro_gps,
            R.id.row_uvpro_transmit,
            R.id.section_channel_control,
            R.id.section_aprs,
            R.id.btn_packet_terminal,
            R.id.packet_terminal_section,
            R.id.section_initial_channel_setup
    };

    public static final int[] MESH_SECTIONS = {
            R.id.section_mesh_connection,
            R.id.section_mesh_gps,
            R.id.row_mesh_transmit,
            R.id.section_meshcore,
            R.id.btn_mesh_node_settings
    };

    /** Stay on screen for MeshCore, UV-PRO, and Both. */
    public static final int[] SHARED_SECTIONS = {
            R.id.header_title,
            R.id.header_version,
            R.id.section_station_identity,
            R.id.row_wifi_transmit,
            R.id.section_beacon,
            R.id.section_settings,
            R.id.btn_settings,
            R.id.section_log
    };

    private RadioSurfaceCatalog() {
    }

    /**
     * Tool Preferences. Category keys cover their children except the one
     * MeshCore row inside Administrative Settings.
     */
    public static Role preferenceRole(String key) {
        if (key == null) {
            return Role.SHARED;
        }
        if (SettingsFragment.KEY_CAT_APRS.equals(key)
                || SettingsFragment.PREF_APRS_CALLSIGN.equals(key)
                || SettingsFragment.PREF_APRS_SSID.equals(key)
                || SettingsFragment.PREF_APRS_SYMBOL_TABLE.equals(key)
                || SettingsFragment.PREF_APRS_SYMBOL_CODE.equals(key)
                || SettingsFragment.PREF_APRS_ICON_SELECTED.equals(key)
                || SettingsFragment.PREF_APRS_MESSAGE.equals(key)
                || SettingsFragment.PREF_APRS_TX_ARMED.equals(key)
                || SettingsFragment.PREF_APRS_DISABLE_ATAK_TRAFFIC.equals(key)
                || SettingsFragment.KEY_APRS_ICON.equals(key)) {
            return Role.UVPRO;
        }
        if (SettingsFragment.PREF_DISABLE_MESH_BEACON_LIMITING.equals(key)) {
            return Role.MESH;
        }
        return Role.SHARED;
    }
}
