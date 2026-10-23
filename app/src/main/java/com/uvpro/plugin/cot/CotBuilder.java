package com.uvpro.plugin.cot;

import android.util.Log;

import com.uvpro.plugin.ax25.AprsSymbolMapper;
import com.uvpro.plugin.ax25.MeshcoreIconset;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Builds CoT (Cursor-on-Target) events for ATAK ingestion.
 *
 * CoT is XML-based situational awareness protocol. Key structure:
 * <pre>
 * &lt;event uid="..." type="..." how="..." time="..." start="..." stale="..."&gt;
 *   &lt;point lat="..." lon="..." hae="..." ce="..." le="..."/&gt;
 *   &lt;detail&gt;
 *     &lt;contact callsign="..."/&gt;
 *     &lt;__group name="..." role="..."/&gt;
 *     &lt;status battery="..."/&gt;
 *     &lt;track speed="..." course="..."/&gt;
 *   &lt;/detail&gt;
 * &lt;/event&gt;
 * </pre>
 */
public class CotBuilder {

    private static final String TAG = "UVPro.CotBuilder";

    /** {@link com.uvpro.plugin.network.WifiContactKeepalive} marks remarks with this source. */
    public static final String WIFI_KEEPALIVE_REMARKS_SOURCE = "UV-PRO WiFi keepalive";
    /** Broadcast/directed ping over TAK/Wi‑Fi (not RF TYPE_PING). */
    public static final String WIFI_PING_REMARKS_SOURCE = "UV-PRO WiFi ping";
    /** Slotted ping reply sent over TAK/Wi‑Fi (not RF OPENRL). */
    public static final String WIFI_PING_REPLY_REMARKS_SOURCE = "UV-PRO WiFi ping reply";
    /** Delivery ACK for contact-targeted map CoT over TAK/local Wi‑Fi (cancels RF retry). */
    public static final String WIFI_COT_ACK_REMARKS_SOURCE = "UV-PRO CoT WiFi ACK";
    private static final String MESHCORE_WIFI_COT_ACK_REMARKS_SOURCE = "MeshCore CoT WiFi ACK";

    /** Stale interval for radio contacts: 5 minutes */
    private static final long STALE_MILLIS = 5 * 60 * 1000L;

    /** ISO 8601 date format for CoT timestamps */
    private static final SimpleDateFormat COT_DATE_FORMAT;
    static {
        COT_DATE_FORMAT = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        COT_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /**
     * Build a CoT event for a radio contact position.
     *
     * @param callsign  Callsign of the radio contact
     * @param lat       Latitude in decimal degrees
     * @param lon       Longitude in decimal degrees
     * @param alt       Altitude in meters (HAE), or 9999999 if unknown
     * @param speed     Speed in m/s, or -1 if unknown
     * @param course    Course in degrees, or -1 if unknown
     * @return CotEvent ready for dispatch to ATAK
     */
    public static CotEvent buildPositionCot(String callsign,
                                            double lat, double lon,
                                            double alt, double speed,
                                            double course) {
        return buildPositionCot(callsign, lat, lon, alt, speed, course, "Cyan");
    }

    /**
     * Build a CoT event for a radio contact position with configurable team color.
     */
    public static CotEvent buildPositionCot(String callsign,
                                            double lat, double lon,
                                            double alt, double speed,
                                            double course, String teamColor) {
        return buildPositionCot(callsign, lat, lon, alt, speed, course, teamColor, STALE_MILLIS, null);
    }

    /**
     * Build a CoT event for a radio contact position with configurable team color and stale window.
     */
    public static CotEvent buildPositionCot(String callsign,
                                            double lat, double lon,
                                            double alt, double speed,
                                            double course, String teamColor,
                                            long staleMillis) {
        return buildPositionCot(callsign, lat, lon, alt, speed, course, teamColor, staleMillis, null);
    }

    public static CotEvent buildPositionCot(String callsign,
                                            double lat, double lon,
                                            double alt, double speed,
                                            double course, String teamColor,
                                            long staleMillis,
                                            String cotTypeOverride) {
        return buildPositionCot(callsign, lat, lon, alt, speed, course,
                teamColor, staleMillis, cotTypeOverride, null, null, null);
    }

    public static CotEvent buildPositionCot(String callsign,
                                            double lat, double lon,
                                            double alt, double speed,
                                            double course, String teamColor,
                                            long staleMillis,
                                            String cotTypeOverride,
                                            Character aprsSymbolTable,
                                            Character aprsSymbolCode) {
        return buildPositionCot(callsign, lat, lon, alt, speed, course,
                teamColor, staleMillis, cotTypeOverride,
                aprsSymbolTable, aprsSymbolCode, null);
    }

    /**
     * Full position CoT builder including optional APRS icon and remarks body.
     */
    public static CotEvent buildPositionCot(String callsign,
                                            double lat, double lon,
                                            double alt, double speed,
                                            double course, String teamColor,
                                            long staleMillis,
                                            String cotTypeOverride,
                                            Character aprsSymbolTable,
                                            Character aprsSymbolCode,
                                            String remarksInner) {
        return buildPositionCot(callsign, lat, lon, alt, speed, course, teamColor,
                staleMillis, cotTypeOverride, aprsSymbolTable, aprsSymbolCode, remarksInner, null);
    }

    /**
     * @param mapUidOverride when set, updates that map marker (merged Wi‑Fi/TAK UID); otherwise
     *                       {@code ANDROID-<CALLSIGN>}.
     */
    public static CotEvent buildPositionCot(String callsign,
                                            double lat, double lon,
                                            double alt, double speed,
                                            double course, String teamColor,
                                            long staleMillis,
                                            String cotTypeOverride,
                                            Character aprsSymbolTable,
                                            Character aprsSymbolCode,
                                            String remarksInner,
                                            String mapUidOverride) {
        CotEvent event = new CotEvent();

        String normalizedCall = callsign.trim().toUpperCase();
        String uid = (mapUidOverride != null && !mapUidOverride.trim().isEmpty())
                ? mapUidOverride.trim()
                : "ANDROID-" + normalizedCall;
        event.setUID(uid);
        String iconsetPath = null;
        if (aprsSymbolTable != null && aprsSymbolCode != null) {
            iconsetPath = AprsSymbolMapper.iconsetPath(aprsSymbolTable, aprsSymbolCode);
        }
        if (iconsetPath != null) {
            // APRS usericon supplies the bitmap; use friendly ground unit type so ATAK opens the
            // full marker details (remarks, track, etc.). Generic a-u-G is treated as minimal UI
            // (pinwheel only, no properties sheet).
            event.setType("a-f-G-U-C");
        } else {
            event.setType((cotTypeOverride != null && !cotTypeOverride.trim().isEmpty())
                    ? cotTypeOverride.trim()
                    : "a-f-G-U-C"); // friendly ground unit combat
        }
        event.setHow("m-g");          // machine GPS

        long now = System.currentTimeMillis();
        long effectiveStaleMs = Math.max(60_000L, staleMillis);

        event.setTime(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStart(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStale(new com.atakmap.coremap.maps.time.CoordinatedTime(
                now + effectiveStaleMs));

        // HAE: Height Above Ellipsoid
        double hae = (alt >= 0 && alt < 99999) ? alt : CotPoint.UNKNOWN;
        event.setPoint(new CotPoint(lat, lon, hae,
                CotPoint.UNKNOWN, CotPoint.UNKNOWN));

        CotDetail detail = new CotDetail("detail");

        // Contact element
        CotDetail contact = new CotDetail("contact");
        contact.setAttribute("callsign", normalizedCall);
        detail.addChild(contact);

        if (iconsetPath != null) {
            CotDetail usericon = new CotDetail("usericon");
            usericon.setAttribute("iconsetpath", iconsetPath);
            detail.addChild(usericon);
        }

        // APRS markers use usericon for the bitmap; omit __group so ATAK does not classify them
        // as TAK server contacts (team meta). MeshCore usericons still need __group so ATAK keeps
        // the contact-card path active for contact details.
        boolean isMeshcoreUserIcon = iconsetPath != null
                && iconsetPath.startsWith(MeshcoreIconset.ICONSET_UID + "/");
        if (iconsetPath == null || isMeshcoreUserIcon) {
            CotDetail group = new CotDetail("__group");
            group.setAttribute("name", teamColor != null ? teamColor : "Cyan");
            group.setAttribute("role", "Team Member");
            detail.addChild(group);
        }

        // Track element (speed/course)
        if (speed >= 0 || course >= 0) {
            CotDetail track = new CotDetail("track");
            if (speed >= 0) {
                track.setAttribute("speed",
                        String.format(Locale.US, "%.1f", speed));
            }
            if (course >= 0) {
                track.setAttribute("course",
                        String.format(Locale.US, "%.1f", course));
            }
            detail.addChild(track);
        }

        // Remark with source info
        CotDetail remarks = new CotDetail("remarks");
        remarks.setAttribute("source", "UV-PRO Radio");
        if (remarksInner != null && !remarksInner.trim().isEmpty()) {
            remarks.setInnerText(remarksInner.trim());
        }
        detail.addChild(remarks);

        event.setDetail(detail);

        return event;
    }

    /**
     * Build a GeoChat CoT event.
     *
     * GeoChat messages use type "b-t-f" (bits-text-free).
     *
     * @param senderUid  UID of the sender
     * @param senderCall Callsign of the sender
     * @param message    Chat message text
     * @param chatRoom   Chat room identifier (use "All Chat Rooms" for broadcast)
     * @return CotEvent for the chat message
     */
    /**
     * For inbound DMs, {@link com.atakmap.android.chat.ChatMessageParser} expects
     * {@code __chat} {@code chatroom}/{@code id} to equal {@link MapView#getDeviceUid} when the peer
     * is the sender; it then rewrites parsed {@code conversationId} to the peer contact UID so the
     * thread matches Contacts. If chatroom holds only the peer UID, ATAK skips that rewrite,
     * {@code sendStatusMessage} targets the wrong contact, and GeoChat IDs stay
     * {@code ANDROID-VETTE.ANDROID-VETTE}.
     *
     * @param localDeviceUidIfDm When non-null at same time {@code dmPeerConversationUid} is
     *                           {@code ANDROID-*}, becomes {@code chatroom}/{@code id}/{@link CotEvent} UID suffix
     *                           and remarks {@code to}; sender stays {@code senderUid} in {@code link}.
     */
    public static CotEvent buildChatCot(String senderUid, String senderCall,
                                        String message, String dmPeerConversationUid,
                                        long uniqueSuffix,
                                        String localDeviceUidIfDm) {
        CotEvent event = new CotEvent();

        // CHAT3 (CoT with <chatgrp>): ChatMessageParser.getConversationUid reads __chat "chatroom"
        // and resolves via getFirstContactWithCallsign — must be the peer's callsign (e.g. VETTE),
        // not ANDROID-1729… or lookup fails and GeoChat routing breaks.
        String chatroomAttr = dmPeerConversationUid;
        String idAttr = dmPeerConversationUid;
        if (localDeviceUidIfDm != null && !localDeviceUidIfDm.isEmpty()
                && dmPeerConversationUid != null && dmPeerConversationUid.startsWith("ANDROID-")) {
            idAttr = localDeviceUidIfDm.trim();
            chatroomAttr = senderCall != null ? senderCall.trim() : dmPeerConversationUid;
        }

        String uid = "GeoChat." + senderUid + "." + idAttr + "." + uniqueSuffix;
        event.setUID(uid);
        event.setType("b-t-f");
        event.setHow("h-g-i-g-o"); // human-generated

        long now = System.currentTimeMillis();
        event.setTime(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStart(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStale(new com.atakmap.coremap.maps.time.CoordinatedTime(
                now + STALE_MILLIS));

        // Point is required but not meaningful for chat — use 0,0
        event.setPoint(new CotPoint(0, 0, CotPoint.UNKNOWN,
                CotPoint.UNKNOWN, CotPoint.UNKNOWN));

        CotDetail detail = new CotDetail("detail");

        // __chat element
        CotDetail chat = new CotDetail("__chat");
        chat.setAttribute("parent", "RootContactGroup");
        chat.setAttribute("groupOwner", "false");
        chat.setAttribute("messageId", uid);
        chat.setAttribute("chatroom", chatroomAttr);
        chat.setAttribute("id", idAttr);
        chat.setAttribute("senderCallsign", senderCall);

        CotDetail chatgrp = new CotDetail("chatgrp");
        chatgrp.setAttribute("uid0", senderUid);
        String uid1 = idAttr;
        if (localDeviceUidIfDm != null && !localDeviceUidIfDm.isEmpty()
                && dmPeerConversationUid != null && dmPeerConversationUid.startsWith("ANDROID-")) {
            uid1 = localDeviceUidIfDm.trim();
        }
        chatgrp.setAttribute("uid1", uid1);
        chatgrp.setAttribute("id", dmPeerConversationUid != null ? dmPeerConversationUid : idAttr);
        chat.addChild(chatgrp);

        detail.addChild(chat);

        // link element
        CotDetail link = new CotDetail("link");
        link.setAttribute("uid", senderUid);
        link.setAttribute("type", "a-f-G-U-C");
        link.setAttribute("relation", "p-p");
        detail.addChild(link);

        // remarks element — contains the actual message
        CotDetail remarks = new CotDetail("remarks");
        remarks.setAttribute("source", "BAO.F.ATAK." + senderUid);
        // Keep wire "to" as the peer thread; __chat id/chatroom carry local surface for CHAT3 parser remap.
        remarks.setAttribute("to",
                (dmPeerConversationUid != null && !dmPeerConversationUid.isEmpty())
                        ? dmPeerConversationUid : chatroomAttr);
        remarks.setAttribute("time", formatCotTime(now));
        remarks.setInnerText(message);
        detail.addChild(remarks);

        event.setDetail(detail);

        return event;
    }

    /**
     * Build inbound GeoChat using the sender's original line UID so Wi‑Fi and RF paths dedupe.
     */
    public static CotEvent buildChatCotWithExistingLineUid(String existingLineUid,
                                                           String senderUid, String senderCall,
                                                           String message, String dmPeerConversationUid,
                                                           String localDeviceUidIfDm) {
        if (existingLineUid == null || existingLineUid.trim().isEmpty()) {
            return null;
        }
        String lineUid = existingLineUid.trim();
        if (!lineUid.startsWith("GeoChat.")) {
            String chatroomAttr = dmPeerConversationUid;
            String idAttr = dmPeerConversationUid;
            if (localDeviceUidIfDm != null && !localDeviceUidIfDm.isEmpty()
                    && dmPeerConversationUid != null
                    && dmPeerConversationUid.startsWith("ANDROID-")) {
                idAttr = localDeviceUidIfDm.trim();
            }
            lineUid = "GeoChat." + senderUid + "." + idAttr + "." + lineUid;
        }

        CotEvent event = new CotEvent();
        event.setUID(lineUid);
        event.setType("b-t-f");
        event.setHow("h-g-i-g-o");

        long now = System.currentTimeMillis();
        event.setTime(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStart(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStale(new com.atakmap.coremap.maps.time.CoordinatedTime(
                now + STALE_MILLIS));

        event.setPoint(new CotPoint(0, 0, CotPoint.UNKNOWN,
                CotPoint.UNKNOWN, CotPoint.UNKNOWN));

        String chatroomAttr = dmPeerConversationUid;
        String idAttr = dmPeerConversationUid;
        if (localDeviceUidIfDm != null && !localDeviceUidIfDm.isEmpty()
                && dmPeerConversationUid != null && dmPeerConversationUid.startsWith("ANDROID-")) {
            idAttr = localDeviceUidIfDm.trim();
            chatroomAttr = senderCall != null ? senderCall.trim() : dmPeerConversationUid;
        }

        CotDetail detail = new CotDetail("detail");

        CotDetail chat = new CotDetail("__chat");
        chat.setAttribute("parent", "RootContactGroup");
        chat.setAttribute("groupOwner", "false");
        chat.setAttribute("messageId", lineUid);
        chat.setAttribute("chatroom", chatroomAttr);
        chat.setAttribute("id", idAttr);
        chat.setAttribute("senderCallsign", senderCall);

        CotDetail chatgrp = new CotDetail("chatgrp");
        chatgrp.setAttribute("uid0", senderUid);
        String uid1 = idAttr;
        if (localDeviceUidIfDm != null && !localDeviceUidIfDm.isEmpty()
                && dmPeerConversationUid != null && dmPeerConversationUid.startsWith("ANDROID-")) {
            uid1 = localDeviceUidIfDm.trim();
        }
        chatgrp.setAttribute("uid1", uid1);
        chatgrp.setAttribute("id", dmPeerConversationUid != null ? dmPeerConversationUid : idAttr);
        chat.addChild(chatgrp);

        detail.addChild(chat);

        CotDetail link = new CotDetail("link");
        link.setAttribute("uid", senderUid);
        link.setAttribute("type", "a-f-G-U-C");
        link.setAttribute("relation", "p-p");
        detail.addChild(link);

        CotDetail remarks = new CotDetail("remarks");
        remarks.setAttribute("source", "BAO.F.ATAK." + senderUid);
        remarks.setAttribute("to",
                (dmPeerConversationUid != null && !dmPeerConversationUid.isEmpty())
                        ? dmPeerConversationUid : chatroomAttr);
        remarks.setAttribute("time", formatCotTime(now));
        remarks.setInnerText(message);
        detail.addChild(remarks);

        event.setDetail(detail);

        return event;
    }

    /**
     * GeoChat delivered/read receipt CoT ({@code b-t-f-d} / {@code b-t-f-r}) referencing an
     * existing chat line by {@code __chat messageId} (the original GeoChat event UID).
     *
     * @param localDeviceUidOrNull from {@link MapView#getDeviceUid()} (cached on UI thread); improves
     *                             parity with {@link #buildChatCot} DM {@code __chat}/{@code chatgrp}.
     * @param localCallsignOrNull  local operator callsign for {@code __chat chatroom} on DM sends
     */
    public static CotEvent buildGeoChatReceiptCot(String referencedMessageLineUid,
                                                  boolean readNotDelivered,
                                                  String localDeviceUidOrNull,
                                                  String localCallsignOrNull) {
        if (referencedMessageLineUid == null || referencedMessageLineUid.trim().isEmpty()) {
            return null;
        }
        final String lineUid = referencedMessageLineUid.trim();
        final String[] triplet = parseGeoChatUidTriplet(lineUid);

        CotEvent event = new CotEvent();

        long now = System.currentTimeMillis();
        // GeoChatService.onCotEvent resolves receipts via chatDb.getChatMessage(cotEvent.getUID()).
        // Native sendStatusMessage sets cotEvent.setUID(messageId) where messageId is the bare
        // UUID from the chat bundle — the LAST segment of the GeoChat.* UID — NOT the full string.
        // Using the full GeoChat.* UID means the DB lookup always misses.
        String receiptUid = extractMessageIdSuffix(lineUid);
        event.setUID(receiptUid != null ? receiptUid : lineUid);
        event.setType(readNotDelivered ? "b-t-f-r" : "b-t-f-d");
        event.setHow("h-e");

        event.setTime(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStart(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStale(new com.atakmap.coremap.maps.time.CoordinatedTime(
                now + STALE_MILLIS));

        event.setPoint(new CotPoint(0, 0, CotPoint.UNKNOWN,
                CotPoint.UNKNOWN, CotPoint.UNKNOWN));

        CotDetail detail = new CotDetail("detail");
        CotDetail chat = new CotDetail("__chat");
        chat.setAttribute("parent", "RootContactGroup");
        chat.setAttribute("groupOwner", "false");
        chat.setAttribute("messageId", lineUid);

        if (triplet != null) {
            String senderUid = triplet[0];
            String threadId = triplet[1];
            String localDev = localDeviceUidOrNull != null
                    ? localDeviceUidOrNull.trim() : "";
            String callsign = localCallsignOrNull != null
                    ? localCallsignOrNull.trim() : "";

            if (threadId != null && threadId.startsWith("ANDROID-") && !localDev.isEmpty()) {
                // Mirror buildChatCot DM: chatroom = local callsign, id = local device UID.
                String chatroom = !callsign.isEmpty() ? callsign
                        : threadId.substring("ANDROID-".length());
                chat.setAttribute("chatroom", chatroom);
                chat.setAttribute("id", localDev);
            } else if (threadId != null && !threadId.isEmpty()) {
                chat.setAttribute("chatroom", threadId);
                chat.setAttribute("id", senderUid != null ? senderUid : localDev);
            }

            CotDetail chatgrp = new CotDetail("chatgrp");
            chatgrp.setAttribute("uid0", senderUid != null ? senderUid : "");
            String uid1 = threadId != null ? threadId : "";
            if (threadId != null && threadId.startsWith("ANDROID-") && !localDev.isEmpty()) {
                uid1 = localDev;
            }
            chatgrp.setAttribute("uid1", uid1);
            chatgrp.setAttribute("id", threadId != null ? threadId : "");
            chat.addChild(chatgrp);
        }

        detail.addChild(chat);

        if (triplet != null) {
            String senderUid = triplet[0];
            String threadId = triplet[1];

            CotDetail link = new CotDetail("link");
            link.setAttribute("uid", senderUid != null ? senderUid : "");
            link.setAttribute("type", "a-f-G-U-C");
            link.setAttribute("relation", "p-p");
            detail.addChild(link);

            CotDetail remarks = new CotDetail("remarks");
            remarks.setAttribute("source", "BAO.F.ATAK."
                    + (senderUid != null ? senderUid : ""));
            remarks.setAttribute("to", threadId != null ? threadId : "");
            remarks.setAttribute("time", formatCotTime(now));
            remarks.setInnerText("");
            detail.addChild(remarks);
        }

        event.setDetail(detail);
        return event;
    }

    /**
     * Extracts the bare messageId (last dot-segment) from a {@code GeoChat.*} UID.
     * For {@code GeoChat.ANDROID-e39bc5456040ade8.ANDROID-JUNIOR.8f7974d9-3cd3-4355-afaa-4755219d6243}
     * returns {@code 8f7974d9-3cd3-4355-afaa-4755219d6243}.
     * Returns null if the input is not a GeoChat.* string or has no suffix.
     */
    private static String extractMessageIdSuffix(String geoChatLineUid) {
        if (geoChatLineUid == null || !geoChatLineUid.startsWith("GeoChat.")) {
            return null;
        }
        int lastDot = geoChatLineUid.lastIndexOf('.');
        if (lastDot < 0 || lastDot >= geoChatLineUid.length() - 1) {
            return null;
        }
        String suffix = geoChatLineUid.substring(lastDot + 1);
        return suffix.isEmpty() ? null : suffix;
    }

    /**
     * Parses {@code GeoChat.{senderUid}.{threadOrRoom}.{uniqueSuffix}} into {@code [senderUid, threadOrRoom]}.
     */
    private static String[] parseGeoChatUidTriplet(String geoChatLineUid) {        if (geoChatLineUid == null || !geoChatLineUid.startsWith("GeoChat.")) {
            return null;
        }
        String rest = geoChatLineUid.substring("GeoChat.".length());
        int lastDot = rest.lastIndexOf('.');
        if (lastDot <= 0 || lastDot >= rest.length() - 1) {
            return null;
        }
        rest = rest.substring(0, lastDot);
        int prevDot = rest.lastIndexOf('.');
        if (prevDot <= 0 || prevDot >= rest.length() - 1) {
            return null;
        }
        String senderUid = rest.substring(0, prevDot);
        String threadId = rest.substring(prevDot + 1);
        return new String[]{senderUid, threadId};
    }

    /**
     * Build a mini self-SA for unicast Wi‑Fi keepalive (local device UID, endpoint, position).
     */
    public static CotEvent buildSelfWifiKeepaliveCot(MapView mapView, long staleMillis) {
        if (mapView == null) {
            return null;
        }
        PointMapItem self;
        try {
            self = mapView.getSelfMarker();
        } catch (Exception e) {
            return null;
        }
        if (self == null) {
            return null;
        }
        GeoPoint gp = self.getPoint();
        if (gp == null || !gp.isValid()) {
            return null;
        }

        String uid;
        try {
            uid = MapView.getDeviceUid();
        } catch (Exception e) {
            return null;
        }
        if (uid == null || uid.trim().isEmpty()) {
            return null;
        }

        String callsign = mapView.getDeviceCallsign();
        if (callsign == null || callsign.trim().isEmpty()) {
            callsign = self.getMetaString("callsign", "UNKNOWN");
        }

        String teamColor = "Cyan";
        try {
            String deviceTeam = mapView.getMapData().getMetaString("deviceTeam", null);
            if (deviceTeam != null && !deviceTeam.trim().isEmpty()) {
                teamColor = deviceTeam.trim();
            }
        } catch (Exception ignored) {
        }

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

        CotEvent event = buildPositionCot(
                callsign,
                gp.getLatitude(),
                gp.getLongitude(),
                gp.getAltitude(),
                (float) speedMs,
                (float) course,
                teamColor,
                staleMillis,
                null,
                null,
                null,
                null,
                uid.trim());

        if (event == null || event.getDetail() == null) {
            return event;
        }

        CotDetail detail = event.getDetail();
        CotDetail contact = detail.getFirstChildByName(0, "contact");
        if (contact == null) {
            contact = new CotDetail("contact");
            contact.setAttribute("callsign", callsign.trim().toUpperCase(Locale.US));
            detail.addChild(contact);
        }

        try {
            String endpoint = CotMapComponent.getEndpoint();
            if (endpoint != null && !endpoint.trim().isEmpty()) {
                contact.setAttribute("endpoint", endpoint.trim());
            }
        } catch (Exception ignored) {
        }

        CotDetail remarks = detail.getFirstChildByName(0, "remarks");
        if (remarks != null) {
            remarks.setAttribute("source", WIFI_KEEPALIVE_REMARKS_SOURCE);
            remarks.setInnerText("");
        }

        return event;
    }

    /** True for unicast Wi‑Fi keepalive mini-SA — must never be relayed over RF. */
    public static boolean isWifiKeepaliveCot(CotEvent event) {
        return remarksSourceEquals(event, WIFI_KEEPALIVE_REMARKS_SOURCE);
    }

    /**
     * Build a Wi‑Fi/TAK ping CoT (broadcast or directed via remarks {@code to}).
     */
    public static CotEvent buildWifiPingCot(MapView mapView, String targetCallsign) {
        CotEvent event = buildSelfWifiKeepaliveCot(mapView, 60_000L);
        if (event == null) {
            return null;
        }
        event.setType("t-x-v-p");
        CotDetail detail = event.getDetail();
        if (detail == null) {
            return null;
        }
        CotDetail remarks = detail.getFirstChildByName(0, "remarks");
        if (remarks == null) {
            remarks = new CotDetail("remarks");
            detail.addChild(remarks);
        }
        remarks.setAttribute("source", WIFI_PING_REMARKS_SOURCE);
        remarks.setInnerText("");
        String target = targetCallsign != null ? targetCallsign.trim() : "";
        if (!target.isEmpty()) {
            remarks.setAttribute("to", target);
        }
        return event;
    }

    /** True for Wi‑Fi/TAK ping CoT — must never be SA-relayed over RF. */
    public static boolean isWifiPingCot(CotEvent event) {
        return remarksSourceEquals(event, WIFI_PING_REMARKS_SOURCE);
    }

    /** True for Wi‑Fi/TAK ping reply position SA — must never be SA-relayed over RF. */
    public static boolean isWifiPingReplyCot(CotEvent event) {
        return remarksSourceEquals(event, WIFI_PING_REPLY_REMARKS_SOURCE);
    }

    /** Wi‑Fi/TAK-only plugin traffic that must stay on the network (never RF relay). */
    public static boolean isWifiNetworkOnlyCot(CotEvent event) {
        return isWifiKeepaliveCot(event)
                || isWifiPingCot(event)
                || isWifiPingReplyCot(event)
                || isWifiCotAck(event);
    }

    /** True for Wi‑Fi/TAK map CoT delivery ACK (remarks {@code refUid}). */
    public static boolean isWifiCotAck(CotEvent event) {
        return remarksSourceEquals(event, WIFI_COT_ACK_REMARKS_SOURCE)
                || remarksSourceEquals(event, MESHCORE_WIFI_COT_ACK_REMARKS_SOURCE);
    }

    /** Referenced map CoT uid from a Wi‑Fi delivery ACK, or null. */
    public static String extractWifiCotAckRefUid(CotEvent event) {
        if (event == null || event.getDetail() == null) {
            return null;
        }
        try {
            CotDetail remarks = event.getDetail().getFirstChildByName(0, "remarks");
            if (remarks == null) {
                return null;
            }
            String refUid = remarks.getAttribute("refUid");
            if (refUid != null && !refUid.trim().isEmpty()) {
                return refUid.trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Sender device uid from map CoT {@code <creator uid="…"/>}, when present. */
    public static String extractMapCotSenderUid(CotEvent event) {
        if (event == null || event.getDetail() == null) {
            return null;
        }
        try {
            CotDetail creator = event.getDetail().getChild("creator");
            if (creator == null) {
                creator = event.getDetail().getFirstChildByName(0, "creator");
            }
            if (creator != null) {
                String uid = creator.getAttribute("uid");
                if (uid != null && !uid.trim().isEmpty()) {
                    return uid.trim();
                }
            }
            CotDetail link = event.getDetail().getFirstChildByName(0, "link");
            if (link != null) {
                String uid = link.getAttribute("uid");
                if (uid != null && uid.trim().startsWith("ANDROID-")) {
                    return uid.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Sender callsign from map CoT creator/contact detail, when present. */
    public static String extractMapCotSenderCallsign(CotEvent event) {
        if (event == null || event.getDetail() == null) {
            return null;
        }
        try {
            CotDetail creator = event.getDetail().getChild("creator");
            if (creator == null) {
                creator = event.getDetail().getFirstChildByName(0, "creator");
            }
            if (creator != null) {
                String callsign = creator.getAttribute("callsign");
                if (callsign != null && !callsign.trim().isEmpty()) {
                    return callsign.trim();
                }
            }
            CotDetail contact = event.getDetail().getFirstChildByName(0, "contact");
            if (contact != null) {
                String callsign = contact.getAttribute("callsign");
                if (callsign != null && !callsign.trim().isEmpty()) {
                    return callsign.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Minimal CoT ACK for contact-targeted map items delivered over TAK/local Wi‑Fi.
     * Sender cancels RF retry watchdog when this arrives via CommsLogger logReceive.
     */
    public static CotEvent buildWifiCotAck(MapView mapView, String ackedCotUid) {
        if (mapView == null || ackedCotUid == null || ackedCotUid.trim().isEmpty()) {
            return null;
        }
        CotEvent event = new CotEvent();
        event.setUID(java.util.UUID.randomUUID().toString());
        event.setType("t-x-cot-a");
        event.setHow("m-g");
        long now = System.currentTimeMillis();
        event.setTime(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStart(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStale(new com.atakmap.coremap.maps.time.CoordinatedTime(now + 60_000L));
        event.setPoint(new CotPoint(0, 0, CotPoint.UNKNOWN,
                CotPoint.UNKNOWN, CotPoint.UNKNOWN));

        CotDetail detail = new CotDetail("detail");
        CotDetail contact = new CotDetail("contact");
        String callsign = mapView.getDeviceCallsign();
        if (callsign != null && !callsign.trim().isEmpty()) {
            contact.setAttribute("callsign", callsign.trim().toUpperCase(Locale.US));
        }
        try {
            String endpoint = CotMapComponent.getEndpoint();
            if (endpoint != null && !endpoint.trim().isEmpty()) {
                contact.setAttribute("endpoint", endpoint.trim());
            }
        } catch (Exception ignored) {
        }
        detail.addChild(contact);

        CotDetail remarks = new CotDetail("remarks");
        remarks.setAttribute("source", WIFI_COT_ACK_REMARKS_SOURCE);
        remarks.setAttribute("refUid", ackedCotUid.trim());
        remarks.setInnerText("");
        detail.addChild(remarks);
        event.setDetail(detail);
        return event;
    }

    /** Directed Wi‑Fi ping target from remarks {@code to}, or empty for broadcast. */
    public static String extractWifiPingTarget(CotEvent event) {
        if (event == null || event.getDetail() == null) {
            return "";
        }
        try {
            CotDetail remarks = event.getDetail().getFirstChildByName(0, "remarks");
            if (remarks == null) {
                return "";
            }
            String to = remarks.getAttribute("to");
            return to != null ? to.trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean remarksSourceEquals(CotEvent event, String source) {
        if (event == null || event.getDetail() == null || source == null) {
            return false;
        }
        try {
            CotDetail remarks = event.getDetail().getFirstChildByName(0, "remarks");
            if (remarks == null) {
                return false;
            }
            return source.equals(remarks.getAttribute("source"));
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Self position SA for a slotted ping reply over TAK/Wi‑Fi (device UID + endpoint).
     */
    public static CotEvent buildWifiPingReplyCot(MapView mapView) {
        CotEvent event = buildSelfWifiKeepaliveCot(mapView, STALE_MILLIS);
        if (event == null) {
            return null;
        }
        CotDetail detail = event.getDetail();
        if (detail == null) {
            return null;
        }
        CotDetail remarks = detail.getFirstChildByName(0, "remarks");
        if (remarks == null) {
            remarks = new CotDetail("remarks");
            detail.addChild(remarks);
        }
        remarks.setAttribute("source", WIFI_PING_REPLY_REMARKS_SOURCE);
        remarks.setInnerText("");
        return event;
    }

    /**
     * Format a timestamp as CoT-compatible ISO 8601 UTC string.
     */
    public static String formatCotTime(long millis) {
        synchronized (COT_DATE_FORMAT) {
            return COT_DATE_FORMAT.format(new Date(millis));
        }
    }

    /**
     * Detail children that are non-essential for rendering a marker but bloat the
     * compressed CoT payload. Removing these can drop a typical point under a single
     * radio fragment (240 bytes compressed). This is a BLACKLIST so unusual-but-small
     * details survive untouched.
     */
    private static final java.util.Set<String> MINIFY_DROP_DETAILS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "precisionlocation",
                    "status",
                    "takv",
                    "_flow-tags_",
                    "archive",
                    "height",
                    "height_unit",
                    "track",
                    "uid",
                    "ce",
                    "le",
                    "link",
                    "creator"));

    /**
     * ATAK-style delete task ({@code t-x-d-d}) for a map item this plugin relayed. The link needs
     * uid, relation and type; {@code __forcedelete} makes the receiver remove the item.
     */
    public static CotEvent buildDeleteCot(String deletedUid, String deletedType) {
        CotEvent event = new CotEvent();
        event.setUID(java.util.UUID.randomUUID().toString());
        event.setType("t-x-d-d");
        event.setHow("h-g-i-g-o");
        long now = System.currentTimeMillis();
        event.setTime(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStart(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStale(new com.atakmap.coremap.maps.time.CoordinatedTime(now + 5 * 60_000L));
        event.setPoint(new CotPoint(0, 0, CotPoint.UNKNOWN, CotPoint.UNKNOWN, CotPoint.UNKNOWN));
        CotDetail detail = new CotDetail("detail");
        CotDetail link = new CotDetail("link");
        link.setAttribute("uid", deletedUid);
        link.setAttribute("relation", "none");
        link.setAttribute("type", deletedType != null && !deletedType.isEmpty()
                ? deletedType : "b-m-p-w");
        detail.addChild(link);
        detail.addChild(new CotDetail("__forcedelete"));
        event.setDetail(detail);
        return event;
    }

    /** UID named by the link of a {@code t-x-d-d} event, or null. */
    public static String extractDeleteTargetUid(CotEvent event) {
        if (event == null || event.getDetail() == null) {
            return null;
        }
        CotDetail link = event.getDetail().getFirstChildByName(0, "link");
        if (link == null) {
            return null;
        }
        String uid = link.getAttribute("uid");
        return uid == null || uid.trim().isEmpty() ? null : uid.trim();
    }

    /**
     * Some CoT types store geometry in {@code link} detail children. Stripping those
     * breaks ATAK importers. Shapes ({@code u-d-} / {@code u-rb}) need their corner
     * links. Routes ({@code b-m-r}) need each waypoint link, including {@code point},
     * {@code type}, {@code uid}, and {@code callsign} (the waypoint name).
     * {@code RouteImporter.createMarker} drops a link with no {@code point}, and
     * {@code loadPoints} fails the import when fewer than two points remain.
     */
    private static boolean minifyPreservesLinkDetails(CotEvent event) {
        if (event == null) {
            return false;
        }
        String type = event.getType();
        if (type == null || type.isEmpty()) {
            return false;
        }
        return type.startsWith("u-d-")
                || "t-x-d-d".equals(type)
                || type.startsWith("u-rb")
                || type.startsWith("b-m-r");
    }

    /**
     * Produce a minified CoT XML string by stripping non-essential {@code <detail>}
     * children before compression, so a typical point fits in a single radio fragment.
     *
     * <p>The original {@link CotEvent} is never mutated. A clone is created via
     * {@link CotEvent#parse(String)} and only the clone's detail tree is pruned.
     * Essential rendering children — {@code point} (an event field, not a detail),
     * {@code contact}, {@code __group}, {@code color}, {@code usericon}, {@code remarks}
     * — are preserved.
     *
     * @return minified CoT XML, or the original {@code event.toString()} if cloning fails.
     */
    public static String minifyCotXml(CotEvent event) {
        if (event == null) {
            return null;
        }
        String original = event.toString();
        try {
            CotEvent clone = CotEvent.parse(original);
            if (clone == null) {
                return original;
            }
            CotDetail detail = clone.getDetail();
            if (detail == null) {
                return clone.toString();
            }
            // getChildren() returns a copy, so iterating while removing is safe.
            for (CotDetail child : detail.getChildren()) {
                if (child == null) {
                    continue;
                }
                String name = child.getElementName();
                if (name != null && MINIFY_DROP_DETAILS.contains(name)) {
                    if ("link".equals(name) && minifyPreservesLinkDetails(clone)) {
                        continue;
                    }
                    detail.removeChild(child);
                }
            }
            // Strip only what is proven safe — preserve all CoT parser-required attributes.
            String minXml = clone.toString();
            // XML declaration — not needed; ATAK's CoT parser handles raw XML without it.
            minXml = minXml.replaceAll("<\\?xml[^?]*\\?>", "");
            // access='...' — access control attribute, irrelevant on mesh.
            minXml = minXml.replaceAll(" access='[^']*'", "")
                           .replaceAll(" access=\"[^\"]*\"", "");
            // Empty <remarks/> — adds no content.
            minXml = minXml.replace("<remarks/>", "")
                           .replace("<remarks></remarks>", "");
            return minXml.trim();
        } catch (Exception e) {
            Log.w(TAG, "minifyCotXml failed — using original CoT", e);
            return original;
        }
    }

    /**
     * Compress a CoT XML string with GZIP.
     */
    public static byte[] compressCot(String cotXml) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            GZIPOutputStream gzip = new GZIPOutputStream(baos);
            gzip.write(cotXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            gzip.close();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Failed to compress CoT", e);
            return null;
        }
    }

    /**
     * Decompress a GZIP-compressed CoT XML.
     */
    public static String decompressCot(byte[] compressed) {
        byte[] buf = new byte[512];
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
            GZIPInputStream gzip = new GZIPInputStream(bais);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int n;
            while ((n = gzip.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            gzip.close();
            byte[] cotBytes = baos.toByteArray();
            String cotXml = new String(cotBytes, java.nio.charset.StandardCharsets.UTF_8);
            java.util.Arrays.fill(cotBytes, (byte) 0);
            return cotXml;
        } catch (Exception e) {
            Log.e(TAG, "Failed to decompress CoT", e);
            return null;
        }
    }

    private static final String ALL_CHAT_ROOMS = "All Chat Rooms";

    /**
     * Stamp {@code <__dest><contact uid="…"/>…</__dest>} for contact-targeted RF relay.
     * Receivers that are not listed should ignore the CoT (RF has no true unicast).
     */
    public static CotEvent stampDirectedDestinations(CotEvent source, String[] toUIDs) {
        if (source == null || toUIDs == null || toUIDs.length == 0) {
            return source;
        }
        CotEvent event;
        try {
            event = CotEvent.parse(source.toString());
        } catch (Exception e) {
            Log.w(TAG, "stampDirectedDestinations: clone failed", e);
            return source;
        }
        if (event == null || !event.isValid()) {
            return source;
        }
        CotDetail detail = event.getDetail();
        if (detail == null) {
            detail = new CotDetail("detail");
            event.setDetail(detail);
        }
        CotDetail existing = detail.getChild("__dest");
        if (existing != null) {
            detail.removeChild(existing);
        }
        CotDetail destRoot = new CotDetail("__dest");
        boolean any = false;
        for (String raw : toUIDs) {
            if (raw == null) {
                continue;
            }
            String uid = raw.trim();
            if (uid.isEmpty() || ALL_CHAT_ROOMS.equalsIgnoreCase(uid)) {
                continue;
            }
            CotDetail contact = new CotDetail("contact");
            contact.setAttribute("uid", uid);
            destRoot.addChild(contact);
            any = true;
        }
        if (!any) {
            return event;
        }
        detail.addChild(destRoot);
        return event;
    }

    /** Remove plugin routing metadata before handing CoT to ATAK. */
    public static void stripDirectedDestinations(CotEvent event) {
        if (event == null || event.getDetail() == null) {
            return;
        }
        CotDetail dest = event.getDetail().getChild("__dest");
        if (dest != null) {
            event.getDetail().removeChild(dest);
        }
    }

    /**
     * @return true when the event is broadcast (no {@code __dest}) or lists this device UID.
     */
    public static boolean isDirectedCotForLocalDevice(CotEvent event, String selfUid,
            java.util.function.Function<String, String> uidResolver) {
        java.util.List<String> destUids = extractDirectedDestUids(event);
        if (destUids.isEmpty()) {
            return true;
        }
        if (selfUid == null || selfUid.trim().isEmpty()) {
            return false;
        }
        String self = selfUid.trim();
        for (String dest : destUids) {
            if (dest == null || dest.trim().isEmpty()) {
                continue;
            }
            String trimmed = dest.trim();
            if (self.equalsIgnoreCase(trimmed)) {
                return true;
            }
            if (com.uvpro.plugin.util.CallsignUtil.isSameCallsignAlias(self, trimmed)) {
                return true;
            }
            if (uidResolver != null) {
                String resolvedDest = uidResolver.apply(trimmed);
                if (resolvedDest != null && self.equalsIgnoreCase(resolvedDest.trim())) {
                    return true;
                }
                String resolvedSelf = uidResolver.apply(self);
                if (resolvedSelf != null && resolvedSelf.trim().equalsIgnoreCase(trimmed)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static java.util.List<String> extractDirectedDestUids(CotEvent event) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (event == null || event.getDetail() == null) {
            return out;
        }
        CotDetail dest = event.getDetail().getChild("__dest");
        if (dest == null) {
            return out;
        }
        for (CotDetail child : dest.getChildren()) {
            if (child == null) {
                continue;
            }
            String uid = child.getAttribute("uid");
            if (uid != null && !uid.trim().isEmpty()) {
                out.add(uid.trim());
            }
        }
        return out;
    }
}
