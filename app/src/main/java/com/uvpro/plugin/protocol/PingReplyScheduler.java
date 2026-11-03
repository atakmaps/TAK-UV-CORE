package com.uvpro.plugin.protocol;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.ui.SettingsFragment;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Schedules ping replies using deterministic callsign slots ({@link NetSlotConfig}).
 */
public final class PingReplyScheduler {

    private static final String TAG = "UVPro.PingReply";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CotBridge cotBridge;
    private Runnable pendingReply;
    private BtConnectionManager uvproTransport;
    private BtConnectionManager meshTransport;
    private RfInboundTransport pendingInboundTransport = RfInboundTransport.UVPRO;

    public PingReplyScheduler(CotBridge cotBridge) {
        this.cotBridge = cotBridge;
    }

    public void setInboundTransports(BtConnectionManager uvpro, BtConnectionManager mesh) {
        this.uvproTransport = uvpro;
        this.meshTransport = mesh;
    }

    /**
     * Queue a position reply after the configured slot delay for this device.
     *
     * @param inboundTransport link that received the ping (used when same-transport reply is on)
     */
    public void scheduleReply(Context context, RfInboundTransport inboundTransport) {
        if (context == null || cotBridge == null) {
            return;
        }
        if (!SettingsFragment.isPingReplyEnabled(context)) {
            return;
        }
        MapView mv = MapView.getMapView();
        if (mv == null) {
            return;
        }
        RfInboundTransport incoming = inboundTransport != null
                ? inboundTransport
                : RfInboundTransport.UVPRO;

        if (pendingReply != null) {
            // Same ping often hits both Mesh and UV-PRO; keep the direct radio path.
            if (pendingInboundTransport == RfInboundTransport.UVPRO
                    && incoming == RfInboundTransport.MESHCORE) {
                Log.d(TAG, "Ping reply already scheduled on UV-PRO; ignoring Mesh duplicate");
                return;
            }
            if (pendingInboundTransport == RfInboundTransport.WIFI
                    && incoming != RfInboundTransport.WIFI) {
                Log.d(TAG, "Ping reply already scheduled on WiFi; ignoring duplicate on "
                        + incoming);
                return;
            }
            if (pendingInboundTransport == RfInboundTransport.MESHCORE
                    && incoming == RfInboundTransport.UVPRO) {
                Log.d(TAG, "Upgrading ping reply transport Mesh → UV-PRO");
                cancelPending();
            } else {
                Log.d(TAG, "Ping reply already pending on " + pendingInboundTransport
                        + "; ignoring duplicate on " + incoming);
                return;
            }
        }

        pendingInboundTransport = incoming;
        String callsign = SettingsFragment.getCallsign(context);
        int slotCount = NetSlotConfig.getSlotCount(context);
        int rawSlot = NetSlotConfig.computeSlotIndex(callsign, slotCount);
        // Remove "slot 0" as a transmit option: effective slots are 1..N.
        int effectiveSlot = rawSlot + 1;
        long slotTimeMs = Math.max(1L, Math.round(NetSlotConfig.getSlotTimeSec(context) * 1000.0f));
        long baseDelayMs = effectiveSlot * slotTimeMs;
        long jitterWindowMs = Math.max(1L, Math.round(slotTimeMs * 0.10));
        long jitterMs = ThreadLocalRandom.current().nextLong(-jitterWindowMs, jitterWindowMs + 1L);
        long delayMs = Math.max(0L, baseDelayMs + jitterMs);
        cancelPending();
        RfTxArbitrator.get().setPingReplyPending(true);
        final Context appCtx = context.getApplicationContext();
        pendingReply = () -> transmitReply(appCtx);
        handler.postDelayed(pendingReply, delayMs);
        Log.d(TAG, "Ping reply scheduled in " + delayMs + "ms (slot " + effectiveSlot
                + "/" + slotCount
                + ", inbound=" + pendingInboundTransport
                + ", rawSlot=" + rawSlot
                + ", base=" + baseDelayMs + "ms, jitter=" + jitterMs + "ms)");
    }

    public void cancelPending() {
        if (pendingReply != null) {
            handler.removeCallbacks(pendingReply);
            pendingReply = null;
        }
        RfTxArbitrator.get().setPingReplyPending(false);
    }

    private void transmitReply(Context context) {
        pendingReply = null;
        RfTxArbitrator.get().setPingReplyPending(false);
        if (!SettingsFragment.isPingReplyEnabled(context)) {
            return;
        }
        try {
            MapView mv = MapView.getMapView();
            if (mv == null) {
                return;
            }
            if (pendingInboundTransport == RfInboundTransport.WIFI) {
                if (cotBridge.sendSelfPositionOverWifiNetwork()) {
                    Log.d(TAG, "Ping reply sent (slotted) over WiFi/TAK");
                    PingReplyNotifier.notifyPingReplySent(context);
                } else {
                    Log.w(TAG, "Ping reply skipped — WiFi/TAK unavailable");
                }
                return;
            }
            PointMapItem self = mv.getSelfMarker();
            if (self == null) {
                return;
            }
            com.atakmap.coremap.maps.coords.GeoPoint gp = self.getPoint();
            double speedMs = 0.0;
            double course = 0.0;
            try {
                speedMs = Double.parseDouble(self.getMetaString("Speed", "0"));
            } catch (Exception ignored) {
            }
            try {
                course = Double.parseDouble(self.getMetaString("course", "0"));
            } catch (Exception ignored) {
            }
            BtConnectionManager tx = resolveReplyTransport();
            if (tx == null || !tx.isConnected()) {
                Log.w(TAG, "Ping reply skipped — no connected transport for inbound="
                        + pendingInboundTransport);
                return;
            }
            cotBridge.sendPositionOverRadio(tx,
                    gp.getLatitude(), gp.getLongitude(),
                    gp.getAltitude(), (float) speedMs, (float) course, -1);
            Log.d(TAG, "Ping reply sent (slotted) over " + linkNameFor(tx)
                    + " (inbound=" + pendingInboundTransport + ")");
            PingReplyNotifier.notifyPingReplySent(context);
        } catch (Exception e) {
            Log.w(TAG, "Ping reply transmit failed: " + e.getMessage());
        }
    }

    private BtConnectionManager resolveReplyTransport() {
        if (pendingInboundTransport == RfInboundTransport.AUDIO) {
            BtConnectionManager audio = com.uvpro.plugin.modem.AudioModemServices.getIfReady();
            if (audio != null) {
                return audio;
            }
        }
        BtConnectionManager inbound = pendingInboundTransport == RfInboundTransport.MESHCORE
                ? meshTransport
                : uvproTransport;
        BtConnectionManager alternate = inbound == meshTransport
                ? uvproTransport
                : meshTransport;
        if (inbound != null && inbound.isConnected()) {
            return inbound;
        }
        if (alternate != null && alternate.isConnected()) {
            Log.d(TAG, "Ping reply fallback: inbound " + pendingInboundTransport
                    + " not connected — using alternate");
            return alternate;
        }
        return null;
    }

    private String linkNameFor(BtConnectionManager tx) {
        if (tx == null) {
            return "?";
        }
        if (meshTransport != null && tx == meshTransport) {
            return "MeshCore";
        }
        if (tx instanceof com.uvpro.plugin.bluetooth.MeshBtConnectionManager) {
            return "MeshCore";
        }
        return "UV-PRO";
    }
}
