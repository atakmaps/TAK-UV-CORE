package com.uvpro.plugin.bluetooth;

/**
 * Picks the active RF transmit transport from user toggle preferences.
 * When the preferred transport is not connected, falls back to the other link if available.
 */
public final class TransmitTransportResolver {

    private TransmitTransportResolver() {
    }

    public static BtConnectionManager resolve(
            boolean meshTransmitEnabled,
            boolean uvproTransmitEnabled,
            BtConnectionManager meshManager,
            BtConnectionManager uvproManager) {
        boolean meshAllowed = com.uvpro.plugin.ui.RadioProfile.shows(
                com.uvpro.plugin.ui.RadioSurfaceCatalog.Role.MESH, null);
        boolean uvproAllowed = com.uvpro.plugin.ui.RadioProfile.shows(
                com.uvpro.plugin.ui.RadioSurfaceCatalog.Role.UVPRO, null);
        if (!meshAllowed) {
            meshTransmitEnabled = false;
        }
        if (!uvproAllowed) {
            uvproTransmitEnabled = false;
        }
        BtConnectionManager preferred = null;
        if (meshTransmitEnabled && meshManager != null) {
            preferred = meshManager;
        } else if (uvproTransmitEnabled && uvproManager != null) {
            preferred = uvproManager;
        }
        if (preferred != null) {
            if (preferred.isConnected()) {
                return preferred;
            }
            BtConnectionManager alternate =
                    preferred == meshManager ? uvproManager : meshManager;
            boolean alternateAllowed = alternate == meshManager ? meshAllowed : uvproAllowed;
            if (alternateAllowed && alternate != null && alternate.isConnected()) {
                return alternate;
            }
            return preferred;
        }
        if (meshAllowed && meshManager != null && meshManager.isConnected()) {
            return meshManager;
        }
        if (uvproAllowed && uvproManager != null && uvproManager.isConnected()) {
            return uvproManager;
        }
        if (meshAllowed && meshManager != null) {
            return meshManager;
        }
        return uvproAllowed ? uvproManager : null;
    }
}
