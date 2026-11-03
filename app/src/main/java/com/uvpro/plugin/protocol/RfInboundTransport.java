package com.uvpro.plugin.protocol;

/**
 * Identifies which link delivered an inbound ping (UV-PRO radio, MeshCore, or TAK/Wi‑Fi).
 * AUDIO is the software AFSK modem behind a USB audio interface (Digirig). ACKs and ping replies
 * go back over it when Digirig transmit is on and ready, else over the usual fallback.
 */
public enum RfInboundTransport {
    UVPRO,
    MESHCORE,
    WIFI,
    AUDIO
}
