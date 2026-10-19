package com.uvpro.plugin.protocol;

import android.util.Log;

import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.IpConnector;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.comms.NetConnectString;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;

import com.uvpro.plugin.aprs.AprsInfoFormatter;
import com.uvpro.plugin.aprs.AprsTrackManager;
import com.uvpro.plugin.ax25.Ax25Frame;
import com.uvpro.plugin.ax25.AprsParser;
import com.uvpro.plugin.ax25.AprsSymbolMapper;
import com.uvpro.plugin.ax25.AprsWeatherParser;
import com.uvpro.plugin.chat.ChatBridge;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.cot.CotBuilder;
import com.uvpro.plugin.util.CallsignUtil;
import com.uvpro.plugin.contacts.ContactTracker;
import com.uvpro.plugin.contacts.RadioContact;
import com.uvpro.plugin.crypto.EncryptionManager;
import com.uvpro.plugin.protocol.PacketFragmenter;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes incoming packets to the appropriate handler based on their type.
 *
 * Incoming data from the radio arrives as raw AX.25 frames. The PacketRouter:
 * 1. Decodes the AX.25 frame to extract callsign and info field
 * 2. Determines if it's an APRS packet or an UV-PRO custom packet
 * 3. Routes to the appropriate handler (CoT bridge, chat bridge, contact tracker)
 *
 * For APRS packets (from standard APRS radios / BTECH native APRS):
 *   - Position reports → ContactTracker + CotBridge (APRS-101: comment tail → CoT remarks)
 *   - Telemetry ({@code T#}…) → refresh marker at last fix + remarks when known (APRS-102:
 *     telemetry-only reinject throttled per callsign)
 *   - Messages → ChatBridge
 *
 * For UV-PRO custom packets (from other UV-PRO plugins):
 *   - TYPE_GPS → ContactTracker + CotBridge
 *   - TYPE_CHAT → ChatBridge
 *   - TYPE_COT → CotBridge
 *   - TYPE_PING → ContactTracker
 */
public class PacketRouter {

    private static final String TAG = "UVPro.Router";
    private static final List<String> APRS_TEAM_POOL = Arrays.asList(
            "Red", "Orange", "Yellow", "Green", "Blue", "Purple", "Magenta", "White", "Cyan");

    /**
     * APRS-101: max chars from the APRS position comment tail into CoT {@code detail/remarks}
     * inner text (ATAK/UI friendly).
     */
    private static final int APRS_POSITION_REMARK_MAX_CHARS = 512;

    /**
     * APRS-102: minimum time between telemetry-only position CoT reinjects for the same
     * display callsign (same lat/lon; remarks refresh only).
     */
    private static final long APRS_TELEMETRY_REINJECT_MIN_MS = 30_000L;

    private final ConcurrentHashMap<String, Long> aprsLastTelemetryReinjectMs =
            new ConcurrentHashMap<>();

    private final CotBridge cotBridge;
    private final ChatBridge chatBridge;
    private final ContactTracker contactTracker;
    private final PacketFragmenter.Reassembler reassembler;
    private final PingReplyScheduler pingReplyScheduler;
    private EncryptionManager encryptionManager;
    private AprsTrackManager aprsTrackManager;

    /** Listener for packet count updates */
    private PacketCountListener packetCountListener;
    /** Optional listener for terminal-mode AX.25 monitoring. */
    private Ax25FrameListener ax25FrameListener;

    public interface PacketCountListener {
        void onPacketReceived();

        /** One AX.25 frame successfully sent over KISS (beacon, chat, CoT, ping, etc.). */
        void onPacketTransmitted();
    }

    public interface Ax25FrameListener {
        /**
         * @return true if APRS routing should be skipped for this frame
         */
        boolean onAx25Frame(Ax25Frame frame);
    }

    public PacketRouter(CotBridge cotBridge, ChatBridge chatBridge,
                        ContactTracker contactTracker) {
        this.cotBridge = cotBridge;
        this.chatBridge = chatBridge;
        this.contactTracker = contactTracker;
        this.reassembler = new PacketFragmenter.Reassembler();
        this.pingReplyScheduler = new PingReplyScheduler(cotBridge);
    }

    public void setAprsTrackManager(AprsTrackManager aprsTrackManager) {
        this.aprsTrackManager = aprsTrackManager;
    }

    public void setPacketCountListener(PacketCountListener listener) {
        this.packetCountListener = listener;
    }

    public void setAx25FrameListener(Ax25FrameListener listener) {
        this.ax25FrameListener = listener;
    }

    /** Called by {@link com.uvpro.plugin.bluetooth.BtConnectionManager} after a successful KISS TX. */
    public void notifyPacketTransmitted() {
        if (packetCountListener != null) {
            packetCountListener.onPacketTransmitted();
        }
    }

    public void setEncryptionManager(EncryptionManager em) {
        this.encryptionManager = em;
    }

    /**
     * Inbound native MeshCore DM ({@code CMD_SEND_TXT_MSG} path): inject the plain text into ATAK
     * GeoChat, threaded by the sender's pubkey prefix.
     */
    public void routeNativeMeshDm(String senderPubKeyPrefixHex, String text) {
        if (chatBridge == null) {
            return;
        }
        chatBridge.injectInboundMeshDm(senderPubKeyPrefixHex, text);
    }

    /**
     * Route an incoming AX.25 frame from the radio.
     * Called by BtConnectionManager on the read thread.
     */
    public void routeIncoming(byte[] ax25Data) {
        routeIncoming(ax25Data, 0, RfInboundTransport.UVPRO);
    }

    /**
     * Route an incoming AX.25 frame from MeshCore (pathLen for hop display).
     */
    public void routeIncoming(byte[] ax25Data, int pathLen) {
        routeIncoming(ax25Data, pathLen, RfInboundTransport.MESHCORE);
    }

    /**
     * Route an incoming AX.25 frame, tagged with the receiving transport.
     */
    public void routeIncoming(byte[] ax25Data, int pathLen, RfInboundTransport inboundTransport) {
        Ax25Frame frame = Ax25Frame.decode(ax25Data);
        if (frame == null) {
            Log.w(TAG, "Failed to decode AX.25 frame");
            return;
        }

        String srcCall = frame.getSrcCallsign();
        int srcSsid = frame.getSrcSsid();
        String destCall = frame.getDestCallsign();
        String info = frame.getInfoString();
        byte[] infoBytes = frame.getInfoField();

        Log.d(TAG, "Received from " + srcCall + "-" + srcSsid +
                " → " + destCall + ": " + info.length() + " bytes");

        // Notify listener of received packet
        if (packetCountListener != null) {
            packetCountListener.onPacketReceived();
        }
        boolean skipAprsRouting = false;
        if (ax25FrameListener != null) {
            try {
                skipAprsRouting = ax25FrameListener.onAx25Frame(frame);
            } catch (Exception e) {
                Log.w(TAG, "AX.25 terminal listener error: " + e.getMessage());
            }
        }

        // Check if this is an UV-PRO custom packet
        if ("OPENRL".equals(destCall)) {
            RfTxArbitrator.get().markInboundOpenRl();
            byte[] infoField = frame.getInfoField();

            // Decrypt if encryption is enabled
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                byte[] decrypted = encryptionManager.decrypt(infoField);
                if (decrypted != null) {
                    infoField = java.util.Arrays.copyOf(decrypted, decrypted.length);
                    java.util.Arrays.fill(decrypted, (byte) 0);
                }
                // If decryption returns null, use raw (unencrypted packet)
            }

            routeUVProPacket(srcCall, srcSsid, infoField, pathLen, inboundTransport);
            return;
        }

        if (skipAprsRouting) {
            return;
        }

        // Otherwise, try to parse as standard APRS
        routeAprsPacket(srcCall, srcSsid, info, infoBytes, destCall, 0);
    }

    /**
     * Wire UV-PRO and MeshCore managers for ping-reply same-transport routing.
     */
    public void setInboundTransports(
            com.uvpro.plugin.bluetooth.BtConnectionManager uvpro,
            com.uvpro.plugin.bluetooth.BtConnectionManager mesh) {
        pingReplyScheduler.setInboundTransports(uvpro, mesh);
    }

    /**
     * Route an UV-PRO custom packet.
     */
    private void routeUVProPacket(String callsign, int ssid, byte[] data) {
        routeUVProPacket(callsign, ssid, data, 0, RfInboundTransport.UVPRO);
    }

    private void routeUVProPacket(String callsign, int ssid, byte[] data, int pathLen,
                                  RfInboundTransport inboundTransport) {
        UVProPacket packet = UVProPacket.decode(data);
        if (packet == null) {
            Log.w(TAG, "Failed to decode UV-PRO packet");
            return;
        }

        switch (packet.getType()) {
            case UVProPacket.TYPE_GPS:
                UVProPacket.GpsData gps =
                        UVProPacket.decodeGpsPayload(packet.getPayload());
                if (gps != null) {
                    Log.d(TAG, "GPS from " + gps.callsign +
                            ": " + gps.latitude + ", " + gps.longitude);
                    MapView gpsMv = MapView.getMapView();
                    if (gpsMv != null) {
                        PingReplyNotifier.maybeNotifyPingReply(
                                gpsMv.getContext(), gps.callsign);
                    }
                    contactTracker.updateContact(gps.callsign, gps.latitude,
                            gps.longitude, gps.altitude, gps.speed,
                            gps.course, gps.battery);

                    final String normalized = gps.callsign.trim().toUpperCase();
                    final String syntheticUid = "ANDROID-" + normalized;
                    final String resolvedUid = ChatBridge.resolveCanonicalPeerUid(
                            normalized, syntheticUid);
                    final String uid = (resolvedUid != null && !resolvedUid.trim().isEmpty())
                            ? resolvedUid.trim()
                            : syntheticUid;

                    // Position CoT at canonical map UID (one marker per callsign when Wi‑Fi peer exists).
                    cotBridge.injectPositionCotAtMapUid(gps.callsign, gps.latitude,
                            gps.longitude, gps.altitude, gps.speed,
                            gps.course,
                            gps.teamColor, uid);

                    cotBridge.registerBtechContactUid(uid);
                    cotBridge.markRfNativeContact(uid);
                    cotBridge.markRfHeardCallsign(normalized);
                    cotBridge.registerBtechContactId(normalized, uid);
                    if (!syntheticUid.equalsIgnoreCase(uid)) {
                        cotBridge.registerBtechContactUidAlias(syntheticUid, uid);
                    }
                    String radioTrunc = CallsignUtil.toRadioCallsign(normalized);
                    if (radioTrunc != null && !radioTrunc.isEmpty()
                            && !radioTrunc.equalsIgnoreCase(normalized)) {
                        cotBridge.registerBtechContactId(radioTrunc, uid);
                    }

                    MapView mv = MapView.getMapView();
                    if (mv != null) {
                        mv.post(() -> linkRadioIndividualContactToMapMarker(
                                normalized, uid, 0));
                    }

                    // Notify ChatBridge so any pending/failed messages for this peer are sent.
                    chatBridge.onPeerActivity(gps.callsign);
                }
                break;

            case UVProPacket.TYPE_CHAT:
                routeChatPacket(packet.getPayload());
                break;

            case UVProPacket.TYPE_COT:
                cotBridge.injectCompressedCot(packet.getPayload(), pathLen);
                break;

            case UVProPacket.TYPE_PING:
                UVProPacket.PingPayload pingPayload =
                        UVProPacket.decodePingPayload(packet.getPayload());
                if (pingPayload == null) {
                    break;
                }
                byte[] pingRawPayload = packet.getPayload();
                int pingPayloadLen = pingRawPayload != null ? pingRawPayload.length : 0;
                String pingCall = pingPayload.sourceCallsign;
                Log.d(TAG, "Ping from: " + pingCall
                        + (pingPayload.isDirected()
                        ? " (directed to " + pingPayload.targetCallsign + ")"
                        : " (broadcast)")
                        + " payloadLen=" + pingPayloadLen
                        + " via " + inboundTransport);
                MapView pingMv = MapView.getMapView();
                boolean shouldReply = !pingPayload.isDirected();
                if (pingPayload.isDirected() && pingMv != null) {
                    String selfAtak = com.uvpro.plugin.ui.SettingsFragment.getCallsign(
                            pingMv.getContext());
                    shouldReply = com.uvpro.plugin.util.CallsignUtil.isSameRadioStation(
                            selfAtak, pingPayload.targetCallsign);
                    if (!shouldReply) {
                        Log.d(TAG, "Directed ping for " + pingPayload.targetCallsign
                                + " — not this station (self="
                                + com.uvpro.plugin.util.CallsignUtil.toRadioCallsign(selfAtak)
                                + ")");
                    }
                }
                if (pingMv != null && (shouldReply || !pingPayload.isDirected())) {
                    PingReplyNotifier.notifyPingReceived(pingMv.getContext(), pingCall);
                }
                contactTracker.handlePing(pingCall);
                chatBridge.onPeerActivity(callsign);
                if (!callsign.equalsIgnoreCase(pingCall)) {
                    chatBridge.onPeerActivity(pingCall);
                }
                if (shouldReply) {
                    schedulePingReply(inboundTransport);
                }
                break;

            case UVProPacket.TYPE_NET_SLOT_CONFIG:
                MapView slotMv = MapView.getMapView();
                if (slotMv != null) {
                    NetSlotConfig.applyFromNetwork(
                            slotMv.getContext(), packet.getPayload(), callsign);
                }
                break;

            case UVProPacket.TYPE_ACK:
                UVProPacket.AckPayload ack =
                        UVProPacket.decodeAckPayload(packet.getPayload());
                if (ack != null) {
                    chatBridge.handleIncomingRadioChatAck(
                            ack.wireMessageId, ack.kind);
                }
                break;

            case UVProPacket.TYPE_COT_FRAGMENT:
                byte[] reassembled = reassembler.addFragment(
                        packet.getPayload());
                if (reassembled != null) {
                    Log.d(TAG, "Fragment reassembly complete: "
                            + reassembled.length + " bytes");
                    cotBridge.injectCompressedCot(reassembled, pathLen);
                }
                break;

            case UVProPacket.TYPE_COT_ACK:
                String ackedUid = UVProPacket.decodeCotAckUid(packet.getPayload());
                if (ackedUid != null) {
                    cotBridge.handleCotAck(ackedUid);
                }
                break;

            default:
                Log.w(TAG, "Unknown UV-PRO packet type: " + packet.getType());
        }
    }

    /**
     * Route a standard APRS packet.
     *
     * @param destCallsign AX.25 destination (required for Mic-E latitude encoding)
     * @param unwrapDepth guard nested {@code }} third-party frames
     */
    private void routeAprsPacket(String callsign, int ssid, String info, byte[] infoBytes,
                                 String destCallsign, int unwrapDepth) {
        if (!com.uvpro.plugin.ui.RadioProfile.shows(
                com.uvpro.plugin.ui.RadioSurfaceCatalog.Role.UVPRO, null)) {
            return;
        }
        if (info.isEmpty()) {
            return;
        }
        if (unwrapDepth < 5 && info.charAt(0) == '}') {
            AprsParser.ThirdPartyInner inner = AprsParser.unwrapThirdParty(info);
            if (inner != null) {
                byte[] innerBytes = AprsParser.toUtf8Bytes(inner.payload);
                routeAprsPacket(inner.callsign, inner.ssid, inner.payload, innerBytes,
                        inner.toDest, unwrapDepth + 1);
                return;
            }
        }

        // Diagnostic trace for symbol mismatches: compare raw payload bytes to parsed fields.
        Log.d(TAG, "APRS raw " + aprsDisplayCallsign(callsign, ssid)
                + " info_ascii=\"" + sanitizeInfoForLog(info) + "\""
                + " info_hex=" + toHex(infoBytes, 96));

        // Try position first
        AprsParser.AprsPosition pos =
                AprsParser.parsePosition(callsign, ssid, info, destCallsign);
        if (pos != null) {
            String baseId = pos.callsign != null && !pos.callsign.trim().isEmpty()
                    ? pos.callsign.trim()
                    : callsign.trim();
            String mapCall = aprsDisplayCallsign(baseId, pos.ssid);
            Log.d(TAG, "APRS position from " + mapCall +
                    ": " + pos.latitude + ", " + pos.longitude);
            String iconsetPath = AprsSymbolMapper.iconsetPath(pos.symbolTable, pos.symbol);
            Log.d(TAG, "APRS symbol " + mapCall
                    + " table='" + pos.symbolTable + "'"
                    + " symbol='" + pos.symbol + "'"
                    + " iconsetpath=" + (iconsetPath != null ? iconsetPath : "<none>"));
            double trackSpeed = pos.speed;
            double trackCourse = pos.course;
            if (AprsWeatherParser.shouldSuppressVehicleMotion(pos)) {
                trackSpeed = -1;
                trackCourse = -1;
            }
            contactTracker.updateContact(mapCall, pos.latitude,
                    pos.longitude, pos.altitude, trackSpeed, trackCourse, -1);
            RadioContact rcAprs = contactTracker.getContact(mapCall);
            if (rcAprs != null) {
                rcAprs.setLastAprsMapSymbol(pos.symbolTable, pos.symbol);
                rcAprs.setSource(RadioContact.ContactSource.APRS);
            }
            String aprsTeam = resolveSharedAprsTeamExcludingLocal();
            // New position clears telemetry throttle so the next T# can refresh remarks immediately.
            aprsLastTelemetryReinjectMs.remove(mapCall);
            String positionRemarks = buildAprsPositionRemarks(pos);
            cotBridge.injectPositionCot(mapCall, pos.latitude,
                    pos.longitude, pos.altitude, trackSpeed, trackCourse,
                    aprsTeam, pos.symbolTable, pos.symbol, positionRemarks);

            final String normalized = mapCall.trim().toUpperCase(Locale.US);
            final String uid = "ANDROID-" + normalized;
            String aprsDetails = AprsInfoFormatter.formatPosition(mapCall, pos);
            if (rcAprs != null) {
                rcAprs.setLastAprsDetailsText(aprsDetails);
            }
            cotBridge.setAprsMarkerDetails(uid, aprsDetails);
            if (aprsTrackManager != null) {
                aprsTrackManager.recordPosition(uid, mapCall, pos);
            }
            // Map marker only — do not register IndividualContact (not actionable in Contacts pane).
            return;
        }

        // Try message
        AprsParser.AprsMessage msg = AprsParser.parseMessage(callsign, info);
        if (msg != null) {
            String msgFrom = aprsDisplayCallsign(callsign, ssid);
            if (!chatBridge.shouldAcceptAprsDestination(msg.toCallsign)) {
                Log.d(TAG, "Ignoring APRS message not addressed to this station: from="
                        + msgFrom + " to=" + msg.toCallsign);
                return;
            }
            Log.d(TAG, "APRS message from " + msgFrom
                    + " to " + msg.toCallsign + ": " + msg.message);
            if (msg.messageId != null && !msg.messageId.trim().isEmpty()) {
                String m = msg.message != null ? msg.message.trim().toLowerCase(Locale.US) : "";
                if (!m.startsWith("ack") && !m.startsWith("rej")) {
                    chatBridge.sendAprsAckIfRequested(msgFrom, msg.messageId);
                }
            }
            com.uvpro.plugin.aprs.AprsMessageTransmitter.markContactResponded(
                    MapView.getMapView() != null ? MapView.getMapView().getContext() : null,
                    msgFrom);
            chatBridge.injectRadioMessage(msgFrom,
                    msg.toCallsign, msg.message, 0);
            return;
        }

        AprsParser.AprsTelemetry telem = AprsParser.parseTelemetry(info);
        if (telem != null) {
            String mapCall = aprsDisplayCallsign(callsign, ssid);
            Log.d(TAG, "APRS telemetry from " + mapCall + ": " + telem.formatSummary());
            RadioContact c = contactTracker.getContact(mapCall);
            if (c != null) {
                contactTracker.touchIfPresent(mapCall);
                if (c.hasPosition()) {
                    String telemSummary = telem.formatSummary();
                    long now = System.currentTimeMillis();
                    Long lastMs = aprsLastTelemetryReinjectMs.get(mapCall);
                    if (lastMs != null && (now - lastMs) < APRS_TELEMETRY_REINJECT_MIN_MS) {
                        Log.d(TAG, "APRS telemetry reinject throttled for " + mapCall
                                + " (min interval " + (APRS_TELEMETRY_REINJECT_MIN_MS / 1000) + "s)");
                    } else {
                        Character symTab = c.getLastAprsSymbolTable();
                        Character symCode = c.getLastAprsSymbolCode();
                        String aprsTeam = resolveSharedAprsTeamExcludingLocal();
                        cotBridge.injectPositionCot(mapCall, c.getLatitude(),
                                c.getLongitude(), c.getAltitude(), c.getSpeed(),
                                c.getCourse(), aprsTeam, symTab, symCode,
                                telemSummary);
                        String details = AprsInfoFormatter.stripActivitySection(
                                c.getLastAprsDetailsText());
                        details = AprsInfoFormatter.withTelemetry(details, telem);
                        c.setLastAprsDetailsText(details);
                        cotBridge.setAprsMarkerDetails("ANDROID-" + mapCall.trim().toUpperCase(Locale.US),
                                details);
                        aprsLastTelemetryReinjectMs.put(mapCall, now);
                    }
                }
            }
            return;
        }

        Log.d(TAG, "Unhandled APRS packet from " + aprsDisplayCallsign(callsign, ssid) + ": " + info);
    }

    /**
     * APRS / AX.25 SSID distinguishes multiple stations on one base callsign (matches radio menus).
     */
    private static String aprsDisplayCallsign(String baseCallsign, int ssid) {
        if (baseCallsign == null) {
            return "";
        }
        String b = baseCallsign.trim();
        if (b.isEmpty()) {
            return "";
        }
        if (ssid > 0 && ssid <= 15) {
            return b + "-" + ssid;
        }
        return b;
    }

    /** Prefer decoded WX readings for map remarks when present. */
    private static String buildAprsPositionRemarks(AprsParser.AprsPosition pos) {
        if (pos != null && pos.weather != null && pos.weather.hasAnyReading()) {
            String wx = pos.weather.formatOneLine();
            String tail = trimAprsCommentForRemarks(pos.comment);
            if (wx != null && !wx.isEmpty()) {
                if (tail != null && !tail.isEmpty()) {
                    return wx + " — " + tail;
                }
                return wx;
            }
        }
        return trimAprsCommentForRemarks(pos != null ? pos.comment : null);
    }

    /**
     * APRS-101: trim and cap APRS position comment for CoT remarks inner text; null if empty.
     */
    private static String trimAprsCommentForRemarks(String comment) {
        if (comment == null) {
            return null;
        }
        String t = comment.trim().replace('\r', ' ').replace('\n', ' ');
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() <= APRS_POSITION_REMARK_MAX_CHARS) {
            return t;
        }
        return t.substring(0, APRS_POSITION_REMARK_MAX_CHARS) + "…";
    }

    private static String sanitizeInfoForLog(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= 32 && c <= 126) {
                out.append(c);
            } else {
                out.append(String.format(Locale.US, "\\x%02X", (int) c & 0xFF));
            }
        }
        return out.toString();
    }

    private static String toHex(byte[] bytes, int maxBytes) {
        if (bytes == null || bytes.length == 0) {
            return "<empty>";
        }
        int n = Math.min(bytes.length, Math.max(1, maxBytes));
        StringBuilder sb = new StringBuilder(n * 3 + 16);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", bytes[i] & 0xFF));
        }
        if (bytes.length > n) {
            sb.append(" ...(").append(bytes.length).append(" bytes)");
        }
        return sb.toString();
    }

    private String resolveSharedAprsTeamExcludingLocal() {
        String localTeam = null;
        try {
            localTeam = com.atakmap.android.chat.ChatManagerMapComponent.getTeamName();
        } catch (Exception ignored) {
        }
        String normalizedLocal = localTeam == null
                ? ""
                : localTeam.trim().toLowerCase(Locale.US);
        // Use one shared APRS color for all APRS beacons, but never the local user's team color.
        for (String preferred : Arrays.asList("Yellow", "Orange", "Magenta", "White", "Blue")) {
            if (!preferred.toLowerCase(Locale.US).equals(normalizedLocal)) {
                return preferred;
            }
        }
        for (String team : APRS_TEAM_POOL) {
            if (!team.toLowerCase(Locale.US).equals(normalizedLocal)) {
                return team;
            }
        }
        return "Cyan";
    }

    /**
     * Decode and route a chat message from UV-PRO packet payload.
     */
    private void routeChatPacket(byte[] payload) {
        if (payload == null || payload.length < 16) return;

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(payload);
        buf.order(java.nio.ByteOrder.BIG_ENDIAN);

        byte[] senderBytes = new byte[6];
        byte[] roomBytes = new byte[6];
        buf.get(senderBytes);
        buf.get(roomBytes);
        int messageId = buf.getInt();

        byte[] msgBytes = new byte[buf.remaining()];
        buf.get(msgBytes);

        String sender = new String(senderBytes, java.nio.charset.StandardCharsets.US_ASCII).trim();
        java.util.Arrays.fill(senderBytes, (byte) 0);
        String room = new String(roomBytes, java.nio.charset.StandardCharsets.US_ASCII).trim();
        java.util.Arrays.fill(roomBytes, (byte) 0);
        String message = new String(msgBytes, java.nio.charset.StandardCharsets.UTF_8);
        java.util.Arrays.fill(msgBytes, (byte) 0);

        Log.d(TAG, "Chat mid=" + messageId + " from " + sender + " [" + room + "] len=" + message.length());
        boolean accepted = chatBridge.injectRadioMessage(sender, room, message, messageId);
        if (accepted && chatBridge.shouldSendInboundDeliveredAck(room)) {
            chatBridge.sendRadioChatAck(messageId, UVProPacket.ACK_KIND_DELIVERED);
        } else if (accepted) {
            Log.d(TAG, "Skipping DELIVERED ACK for All Chat Rooms mid=" + messageId
                    + " room=" + room + " sender=" + sender);
        } else {
            Log.d(TAG, "Skipping DELIVERED ACK for non-local chat mid=" + messageId
                    + " room=" + room + " sender=" + sender);
        }
    }

    /**
     * Associate {@link IndividualContact} with the CoT-driven map marker ({@link MapItem}) so
     * Contacts UI uses the peer's team tint from SA (matches native Wi‑Fi / server contacts).
     * Retries briefly if CoT processing has not yet created the marker.
     */
    private void linkRadioIndividualContactToMapMarker(final String normalized,
                                                       final String uid,
                                                       final int attempt) {
        try {
            MapView mv = MapView.getMapView();
            if (mv == null) {
                return;
            }
            MapItem item = mv.getRootGroup().deepFindUID(uid);
            String syntheticUid = "ANDROID-" + normalized;

            Contacts contacts = Contacts.getInstance();
            Contact existing = contacts.getContactByUuid(uid);
            if (!(existing instanceof IndividualContact)) {
                String canonicalUid = ChatBridge.resolveCanonicalPeerUid(normalized, uid);
                if (canonicalUid != null && !canonicalUid.trim().isEmpty()) {
                    Contact byCanonical = contacts.getContactByUuid(canonicalUid.trim());
                    if (byCanonical instanceof IndividualContact) {
                        existing = byCanonical;
                    }
                }
            }

            if (existing instanceof IndividualContact) {
                IndividualContact ic = (IndividualContact) existing;
                // Preserve existing ATAK/Wi-Fi contact icon/action behavior on existing contacts.
                // We only bind map item metadata and keep this contact as the canonical route target.
                ChatBridge.preferNativeContactAction(ic);
                if (item != null) {
                    item.setMetaBoolean("sendable", true);
                    item.setMetaString("endpoint", ChatBridge.ACTION_PLUGIN_CONTACT_GEOCHAT_SEND);
                    com.uvpro.plugin.contacts.ContactRadialMenuUtil
                            .applyPingCapableRadialMenu(item, ic);
                    ic.setMapItem(item);
                    ic.dispatchChangeEvent();
                    removeDuplicateSyntheticContact(contacts, syntheticUid, ic.getUID());
                    ChatBridge.collapseDuplicateContactsForCallsign(normalized, null);
                    return;
                }
                if (attempt < 12) {
                    mv.postDelayed(() -> linkRadioIndividualContactToMapMarker(
                            normalized, uid, attempt + 1), 50L);
                    return;
                }
                ic.dispatchChangeEvent();
                removeDuplicateSyntheticContact(contacts, syntheticUid, ic.getUID());
                ChatBridge.collapseDuplicateContactsForCallsign(normalized, null);
                return;
            }

            if (item == null && attempt < 12) {
                mv.postDelayed(() -> linkRadioIndividualContactToMapMarker(
                        normalized, uid, attempt + 1), 50L);
                return;
            }

            String ensuredUid = ChatBridge.ensurePluginChatContact(normalized, uid);
            if (ensuredUid == null || ensuredUid.trim().isEmpty()) {
                ensuredUid = uid;
            }
            Contact ensured = contacts.getContactByUuid(ensuredUid.trim());
            if (ensured instanceof IndividualContact) {
                IndividualContact ic = (IndividualContact) ensured;
                ChatBridge.preferNativeContactAction(ic);
                if (item != null) {
                    item.setMetaBoolean("sendable", true);
                    item.setMetaString("endpoint", ChatBridge.ACTION_PLUGIN_CONTACT_GEOCHAT_SEND);
                    com.uvpro.plugin.contacts.ContactRadialMenuUtil
                            .applyPingCapableRadialMenu(item, ic);
                    ic.setMapItem(item);
                }
                removeDuplicateSyntheticContact(contacts, syntheticUid, ic.getUID());
                ChatBridge.collapseDuplicateContactsForCallsign(normalized, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "linkRadioIndividualContactToMapMarker failed uid=" + uid, e);
        }
    }

    private void removeDuplicateSyntheticContact(Contacts contacts, String syntheticUid, String keepUid) {
        if (contacts == null || syntheticUid == null || syntheticUid.isEmpty()) {
            return;
        }
        try {
            if (keepUid != null && syntheticUid.equalsIgnoreCase(keepUid)) {
                return;
            }
            Contact dup = contacts.getContactByUuid(syntheticUid);
            if (dup != null && (keepUid == null || !dup.getUID().equalsIgnoreCase(keepUid))) {
                contacts.removeContact(dup);
            }
        } catch (Exception ignored) {
        }
    }

    private static NetConnectString buildNativeConnectorSeed(String callsign) {
        NetConnectString ncs = new NetConnectString("stcp", "*", -1);
        if (callsign != null && !callsign.trim().isEmpty()) {
            ncs.setCallsign(callsign.trim().toUpperCase(Locale.US));
        }
        return ncs;
    }

    private void schedulePingReply(RfInboundTransport inboundTransport) {
        MapView mv = MapView.getMapView();
        if (mv == null) {
            return;
        }
        pingReplyScheduler.scheduleReply(mv.getContext(), inboundTransport);
    }

    /**
     * Inbound Wi‑Fi/TAK ping CoT (remarks source {@link CotBuilder#WIFI_PING_REMARKS_SOURCE}).
     */
    public void handleInboundWifiPing(CotEvent event) {
        if (event == null) {
            return;
        }
        UVProPacket.PingPayload payload = decodeWifiPingPayload(event);
        if (payload == null) {
            return;
        }
        String pingCall = payload.sourceCallsign;
        if (pingCall == null || pingCall.trim().isEmpty()) {
            return;
        }
        Log.d(TAG, "WiFi ping from: " + pingCall
                + (payload.isDirected()
                ? " (directed to " + payload.targetCallsign + ")"
                : " (broadcast)"));
        MapView pingMv = MapView.getMapView();
        boolean shouldReply = !payload.isDirected();
        if (payload.isDirected() && pingMv != null) {
            String selfAtak = com.uvpro.plugin.ui.SettingsFragment.getCallsign(
                    pingMv.getContext());
            shouldReply = com.uvpro.plugin.util.CallsignUtil.isSameRadioStation(
                    selfAtak, payload.targetCallsign);
            if (!shouldReply) {
                Log.d(TAG, "Directed WiFi ping for " + payload.targetCallsign
                        + " — not this station");
            }
        }
        if (pingMv != null && (shouldReply || !payload.isDirected())) {
            PingReplyNotifier.notifyPingReceived(pingMv.getContext(), pingCall);
        }
        contactTracker.handlePing(pingCall);
        chatBridge.onPeerActivity(pingCall);
        if (shouldReply) {
            schedulePingReply(RfInboundTransport.WIFI);
        }
    }

    private static UVProPacket.PingPayload decodeWifiPingPayload(CotEvent event) {
        try {
            CotDetail detail = event.getDetail();
            if (detail == null) {
                return null;
            }
            CotDetail contact = detail.getFirstChildByName(0, "contact");
            String source = contact != null ? contact.getAttribute("callsign") : null;
            if (source == null || source.trim().isEmpty()) {
                return null;
            }
            String target = CotBuilder.extractWifiPingTarget(event);
            if (target.isEmpty()) {
                return new UVProPacket.PingPayload(source.trim(), null);
            }
            return new UVProPacket.PingPayload(source.trim(), target);
        } catch (Exception e) {
            return null;
        }
    }
}
