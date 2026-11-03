package com.uvpro.plugin.modem;

import androidx.annotation.Nullable;

/** Static access to the Digirig / USB audio transmit transport (see {@code UVProMeshServices}). */
public final class AudioModemServices {

    private static volatile AudioModemTransport transport;

    private AudioModemServices() {
    }

    public static void install(AudioModemTransport t) {
        transport = t;
    }

    public static void clear() {
        transport = null;
    }

    @Nullable
    public static AudioModemTransport getTransport() {
        return transport;
    }

    /** The transport when the user selected it and it can key the radio now, else null. */
    @Nullable
    public static AudioModemTransport getIfReady() {
        AudioModemTransport t = transport;
        return t != null && t.isTransmitSelected() && t.isConnected() ? t : null;
    }
}
