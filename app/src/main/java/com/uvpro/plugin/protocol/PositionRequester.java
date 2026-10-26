package com.uvpro.plugin.protocol;

import android.content.Context;
import android.util.Log;

import com.atakmap.android.maps.MapView;
import com.uvpro.plugin.ax25.Ax25Frame;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.crypto.EncryptionManager;
import com.uvpro.plugin.ui.SettingsFragment;
import com.uvpro.plugin.util.CallsignUtil;

/**
 * Transmits directed position-request pings (TYPE_PING with target callsign) over UV-PRO / MeshCore RF.
 */
public final class PositionRequester {

    private static final String TAG = "UVPro.PositionReq";

    private static volatile BtConnectionManager uvproTransport;
    private static volatile BtConnectionManager meshTransport;
    private static volatile EncryptionManager encryptionManager;
    private static volatile com.uvpro.plugin.cot.CotBridge cotBridge;

    private PositionRequester() {
    }

    public static void install(BtConnectionManager uvproBt, BtConnectionManager meshBt,
                               EncryptionManager encryption) {
        uvproTransport = uvproBt;
        meshTransport = meshBt;
        encryptionManager = encryption;
    }

    public static void install(BtConnectionManager uvproBt, BtConnectionManager meshBt,
                               EncryptionManager encryption,
                               com.uvpro.plugin.cot.CotBridge bridge) {
        install(uvproBt, meshBt, encryption);
        cotBridge = bridge;
    }

    public static void clear() {
        uvproTransport = null;
        meshTransport = null;
        encryptionManager = null;
        cotBridge = null;
    }

    public static boolean requestPosition(Context context, String targetCallsign) {
        return requestPosition(context, null, targetCallsign);
    }

    /**
     * @param contactUid used to pick MeshCore vs UV-PRO transport and for diagnostics
     */
    public static boolean requestPosition(Context context, String contactUid,
                                          String targetCallsign) {
        if (targetCallsign == null || targetCallsign.trim().isEmpty()) {
            return false;
        }
        Context ctx = resolveContext(context);
        if (ctx == null) {
            return false;
        }
        String atakTarget = targetCallsign.trim();
        com.uvpro.plugin.cot.CotBridge bridge = cotBridge;
        BtConnectionManager tx = resolveTransport(contactUid);
        boolean radioUp = tx != null && tx.isConnected();
        boolean wifiOk = false;
        if (bridge != null && bridge.canSendPingOverWifiNetwork()
                && (preferWifiPingForContact(contactUid) || !radioUp)) {
            wifiOk = bridge.sendPingOverWifiNetwork(atakTarget);
        }
        if (!radioUp) {
            if (wifiOk) {
                PingReplyNotifier.noteDirectedPingSent(ctx, atakTarget, "ATAK WiFi");
                return true;
            }
            Log.w(TAG, "Request position: no connected radio transport");
            return false;
        }
        String sender = SettingsFragment.getCallsign(ctx);
        String targetRadio = CallsignUtil.toRadioCallsign(atakTarget);
        if (targetRadio.isEmpty()) {
            return false;
        }
        Log.i(TAG, "Directed ping uid=" + contactUid
                + " atak=" + atakTarget + " wire=" + targetRadio);
        try {
            UVProPacket packet = UVProPacket.createDirectedPingPacket(sender, atakTarget);
            byte[] payload = packet.getPayload();
            if (payload == null || payload.length != 12) {
                Log.e(TAG, "Directed ping encode failed: payloadLen="
                        + (payload == null ? 0 : payload.length));
                return false;
            }
            byte[] packetBytes = packet.encode();
            EncryptionManager em = encryptionManager;
            if (em != null && em.isEnabled()) {
                packetBytes = em.encrypt(packetBytes);
                if (packetBytes == null) {
                    Log.e(TAG, "Encrypt failed for directed ping");
                    return false;
                }
            }
            Ax25Frame frame = Ax25Frame.createUVProFrame(sender, 0, packetBytes);
            boolean ok = tx.sendKissFrame(frame.encode());
            if (ok) {
                String transportLabel = tx == meshTransport ? "MeshCore" : "UV-PRO";
                if (wifiOk) {
                    transportLabel += " + ATAK WiFi";
                }
                PingReplyNotifier.noteDirectedPingSent(ctx, atakTarget, transportLabel);
            }
            return ok || wifiOk;
        } catch (Exception e) {
            Log.e(TAG, "requestPosition failed", e);
            return false;
        }
    }

    private static boolean preferWifiPingForContact(String contactUid) {
        if (contactUid == null || contactUid.trim().isEmpty()) {
            return true;
        }
        String uid = contactUid.trim();
        if (uid.startsWith("MESHCORE-NODE-") || uid.startsWith("MESHCORE-RPTR-")) {
            return false;
        }
        return uid.startsWith("ANDROID-");
    }

    private static BtConnectionManager resolveTransport(String contactUid) {
        boolean meshContact = contactUid != null
                && (contactUid.startsWith("MESHCORE-NODE-")
                || contactUid.startsWith("MESHCORE-RPTR-"));
        BtConnectionManager mesh = meshTransport;
        BtConnectionManager uv = uvproTransport;
        if (meshContact && mesh != null && mesh.isConnected()) {
            return mesh;
        }
        Context ctx = resolveContext(null);
        if (ctx != null && SettingsFragment.isMeshTransmitEnabled(ctx)
                && mesh != null && mesh.isConnected()) {
            return mesh;
        }
        if (uv != null && uv.isConnected()) {
            return uv;
        }
        if (mesh != null && mesh.isConnected()) {
            return mesh;
        }
        return null;
    }

    private static Context resolveContext(Context context) {
        if (context != null) {
            return context;
        }
        MapView mv = MapView.getMapView();
        return mv != null ? mv.getContext() : null;
    }
}
