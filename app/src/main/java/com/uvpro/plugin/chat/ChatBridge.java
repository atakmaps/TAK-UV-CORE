package com.uvpro.plugin.chat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.fragment.app.Fragment;

import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.chat.GeoChatConnector;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.PluginConnector;
import com.atakmap.android.contact.IpConnector;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.comms.NetConnectString;

import com.uvpro.plugin.ax25.Ax25Frame;
import com.uvpro.plugin.aprs.AprsMessageTransmitter;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.bluetooth.MeshBtConnectionManager;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.crypto.EncryptionManager;
import com.uvpro.plugin.protocol.UVProPacket;
import com.uvpro.plugin.util.CallsignUtil;
import com.uvpro.plugin.UVProContactHandler;

import com.atakmap.android.util.NotificationUtil;
import com.uvpro.plugin.ui.SettingsFragment;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bridges ATAK GeoChat messages with the radio link.
 *
 * Inbound (radio → ATAK):
 *   - Receives chat messages from PacketRouter
 *   - Uses CotBridge to inject GeoChat CoT events
 *
 * Outbound (ATAK → radio):
 *   - Listens for GeoChat send intents from ATAK
 *   - Packages as UVPro chat packets and sends to radio
 *
 * GeoChat in ATAK uses CoT events with type "b-t-f" (bits-text-free).
 * The actual message text is in detail/remarks inner text.
 */
public class ChatBridge {

    private static final String TAG = "UVPro.ChatBridge";
    private static final String MESH_NODE_UID_PREFIX = "MESHCORE-NODE-";
    private static final String MESH_RPTR_UID_PREFIX = "MESHCORE-RPTR-";

    /** ATAK broadcasts some GeoChat sends with this intent (extras vary). */
    private static final String ACTION_CHAT_SEND =
            "com.atakmap.android.chat.SEND_MESSAGE";
    /** RF payload wrapper for non-radio destination gatewaying (B -> A -> TAK). */
    private static final String GW_PREFIX = "__UVGW__|";
    private static final String ALL_CHAT_ROOMS = "All Chat Rooms";
    private static final String ANDROID_UID_PREFIX = "ANDROID-";
    private static final java.util.regex.Pattern OPAQUE_WIFI_DEVICE_UID =
            java.util.regex.Pattern.compile("^[A-F0-9]{16}$");

    /**
     * Broadcast action for outbound GeoChat to a contact whose delivery path uses
     * {@link com.atakmap.android.contact.PluginConnector}. ATAK invokes
     * {@code new Intent(connector.getConnectionString())...putExtra(\"MESSAGE\", bundle)}
     * (see ChatManagerMapComponent.sendMessageToDests) — so the action must be predictable
     * for our listener, not an opaque placeholder string.
     */
    public static final String ACTION_PLUGIN_CONTACT_GEOCHAT_SEND =
            "com.uvpro.plugin.action.PLUGIN_CONTACT_GEOCHAT_SEND";

    private final Context pluginContext;
    private final MapView mapView;
    private CotBridge cotBridge;
    private BtConnectionManager btManager;
    private EncryptionManager encryptionManager;
    private volatile String localCallsign = "OPENRL";

    /** Whether to relay outgoing chat to radio */
    private boolean relayOutgoing = true;

    private BroadcastReceiver chatReceiver;
    private BroadcastReceiver chatMarkReadReceiver;
    private BroadcastReceiver chatOpenReceiver;
    private BroadcastReceiver chatClosedReceiver;

    /**
     * Track the currently-open GeoChat conversation (if any). If the conversation is open,
     * inbound messages should not increment the contacts badge because the user is already
     * viewing the chat.
     */
    private volatile String openConversationId;

    /**
     * ATAK marks lines read in {@code ConversationFragment} via {@code markAllRead} →
     * {@code Contact.setUnreadCount} without always broadcasting {@code markmessageread}.
     * When native unread drops from &gt; 0 to 0, clear our plugin badge for that ANDROID-* uid.
     */
    private final ConcurrentHashMap<String, Integer> lastAtakUnreadByUid =
            new ConcurrentHashMap<>();
    private Contacts.OnContactsChangedListener contactsUnreadSyncListener;

    /** Runs after ATAK delivers chat to the UI (fragment may not exist yet on first callback). */
    private ChatManagerMapComponent.ChatMessageListener atakChatMessageListener;

    /**
     * Conversations with pending unread messages. Some ATAK paths open GeoChat without
     * broadcasting OPEN_GEOCHAT/markmessageread; we poll for a visible ConversationFragment
     * and clear unread as soon as the user is actually viewing the thread.
     */
    private final Set<String> pendingUnreadConversationUids = ConcurrentHashMap.newKeySet();
    private volatile boolean unreadVisibilityPollRunning;
    private static final Set<String> aprsConversationUids = ConcurrentHashMap.newKeySet();

    private volatile boolean disposed;

    /**
     * Wire {@link UVProPacket} chat {@code messageId} → sender's local GeoChat line UID
     * (from {@code COT_PLACED}), used to apply RF {@link UVProPacket#TYPE_ACK} receipts.
     */
    private final ConcurrentHashMap<Integer, String> outboundWireMidToLocalLineUid =
            new ConcurrentHashMap<>();
    private static final int MAX_OUTBOUND_ACK_ENTRIES = 384;

    /**
     * Inbound wire mid ACKs waiting for the user to open the conversation (READ receipt).
     * Key: ANDROID-* conversation UID on this device.
     * Value: set of wire message ids received but not yet READ-acked over RF.
     */
    private final ConcurrentHashMap<String, Set<Integer>> pendingReadAcksByConversation =
            new ConcurrentHashMap<>();

    // --- Outbound retry / delivery-failure tracking ---

    /** Retry a send if no DELIVERED ACK received within this window. */
    private static final long RETRY_INTERVAL_MS = 2 * 60 * 1000L; // 2 minutes

    /** Number of retransmit attempts before declaring failure. */
    private static final int MAX_CHAT_RETRIES = 3;

    /** Tracks unacknowledged outbound messages for retry and failure notification. */
    private final ConcurrentHashMap<Integer, PendingOutboundChat> pendingOutboundChats =
            new ConcurrentHashMap<>();

    /**
     * Messages that exhausted all retries. Keyed by peer callsign (bare, no "ANDROID-" prefix).
     * Re-sent automatically when the peer's beacon or ping is received.
     */
    private final ConcurrentHashMap<String, java.util.Queue<PendingOutboundChat>> failedOutboundChatsByPeer =
            new ConcurrentHashMap<>();

    /** Single-thread executor for retry watchdog scheduling. */
    private final ScheduledExecutorService retryExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "UVPro-ChatRetry");
                t.setDaemon(true);
                return t;
            });

    /**
     * Suppress duplicate chat delivery when the same line arrives over Wi‑Fi/TAK and RF.
     * Keys are GeoChat line UIDs (full or bare suffix), wire mids, or sender+body hashes.
     */
    private static final long INBOUND_LINE_UID_DEDUPE_MS = 120_000L;
    private static final long INBOUND_CONTENT_DEDUPE_MS = 30_000L;
    private static final int MAX_INBOUND_DEDUPE_ENTRIES = 512;
    private final ConcurrentHashMap<String, Long> recentInboundLineUids = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recentInboundContentKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> recentInboundWireMids = new ConcurrentHashMap<>();

    /** Tracks all parameters needed to retransmit an unacknowledged outbound chat. */
    private static final class PendingOutboundChat {
        final int wireMid;
        final String sender;
        final String room;
        final String message;
        final String geoChatLineUid; // may be null
        volatile int retryCount;

        PendingOutboundChat(int wireMid, String sender, String room, String message, String geoChatLineUid) {
            this.wireMid = wireMid;
            this.sender = sender;
            this.room = room;
            this.message = message;
            this.geoChatLineUid = geoChatLineUid;
            this.retryCount = 0;
        }
    }

    /**
     * Skip sending the same GeoChat line twice when both PreSend and COT_PLACED (or CommsLogger)
     * observe one user action.
     */
    private final Object outboundGeoChatDedupeLock = new Object();
    private String lastOutboundGeoChatDedupeUid;
    private long lastOutboundGeoChatDedupeMs;

    private volatile boolean loggedPluginChatBundleKeysMissingUid;

    private static volatile CotBridge mergeRoutingBridge;

    private final android.os.Handler contactMergeHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final ConcurrentHashMap<String, Runnable> pendingMergeByCallsign =
            new ConcurrentHashMap<>();
    private Runnable pendingCollapseAllTask;

    public ChatBridge(Context pluginContext, MapView mapView) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
    }

    public static void setMergeRoutingBridge(CotBridge cotBridge) {
        mergeRoutingBridge = cotBridge;
    }

    public static CotBridge getMergeRoutingBridge() {
        return mergeRoutingBridge;
    }

    public void setCotBridge(CotBridge cotBridge) {
        this.cotBridge = cotBridge;
        mergeRoutingBridge = cotBridge;
    }

    public void setBtManager(BtConnectionManager btManager) {
        this.btManager = btManager;
        if (btManager != null && silenceListenerManagers.add(btManager)) {
            btManager.addRadioSilenceClearedListener(this::flushChatsHeldForSilence);
        }
    }

    private final java.util.Set<BtConnectionManager> silenceListenerManagers =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private static final int MAX_HELD_SILENCE_CHATS = 20;
    private static final long HELD_CHAT_SEND_SPACING_MS = 2000L;

    private static final class HeldChat {
        final String sender;
        final String room;
        final String message;
        final String lineUid;

        HeldChat(String sender, String room, String message, String lineUid) {
            this.sender = sender;
            this.room = room;
            this.message = message;
            this.lineUid = lineUid;
        }
    }

    /** Chat lines typed during Radio Silence, sent in order when silence clears. */
    private final java.util.concurrent.ConcurrentLinkedQueue<HeldChat> chatsHeldForSilence =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private void holdChatForSilence(String sender, String room, String message, String lineUid) {
        if (chatsHeldForSilence.size() >= MAX_HELD_SILENCE_CHATS) {
            chatsHeldForSilence.poll();
        }
        chatsHeldForSilence.add(new HeldChat(sender, room, message, lineUid));
        Log.i(TAG, "Radio Silence active — holding chat room=" + room + " lineUid=" + lineUid
                + " (held=" + chatsHeldForSilence.size() + ")");
    }

    private void flushChatsHeldForSilence() {
        if (disposed || chatsHeldForSilence.isEmpty()) {
            return;
        }
        BtConnectionManager tx = btManager;
        if (tx == null || tx.isRadioSilenceEnabled()) {
            return;
        }
        final java.util.List<HeldChat> batch = new java.util.ArrayList<>();
        HeldChat h;
        while ((h = chatsHeldForSilence.poll()) != null) {
            batch.add(h);
        }
        Log.i(TAG, "Radio Silence cleared — sending " + batch.size() + " held chat(s)");
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        long delay = 500L;
        for (HeldChat held : batch) {
            final HeldChat toSend = held;
            main.postDelayed(() -> sendChatOverRadio(
                    toSend.sender, toSend.room, toSend.message, toSend.lineUid), delay);
            delay += HELD_CHAT_SEND_SPACING_MS;
        }
    }

    public void setEncryptionManager(EncryptionManager em) {
        this.encryptionManager = em;
    }

    private String localCallsign() {
        try {
            com.atakmap.android.maps.PointMapItem self = mapView.getSelfMarker();
            if (self != null) {
                String live = self.getMetaString("callsign", null);
                if (live != null && !live.trim().isEmpty()
                        && !"UNKNOWN".equalsIgnoreCase(live.trim())) {
                    localCallsign = live.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return localCallsign;
    }

    public void setLocalCallsign(String callsign) {
        this.localCallsign = callsign;
    }

    public void setRelayOutgoing(boolean relay) {
        this.relayOutgoing = relay;
    }

    /**
     * Inject a message received from the radio into ATAK as GeoChat.
     *
     * @param fromCallsign         Sender callsign (may be AX.25-truncated)
     * @param toCallsign           Destination (callsign or room name) from the wire
     * @param message              Message body
     * @param radioPacketMessageId TYPE_CHAT payload id ({@code putInt}); 0 if unknown (APRS path).
     */
    public boolean injectRadioMessage(String fromCallsign, String toCallsign,
                                   String message, int radioPacketMessageId) {
        if (cotBridge == null) {
            Log.w(TAG, "CotBridge not set — cannot inject chat");
            return false;
        }
        if (message == null || message.isEmpty()) return false;

        // Gateway envelope: preserve full TAK destination identifiers across 6-byte RF room limits.
        String gatewayWireDest = null;
        String gatewayDisplayCallsign = null;
        String gatewayLineUid = null;
        GatewayWrapped gw = parseGatewayWrappedMessage(message);
        if (gw != null) {
            message = gw.message;
            gatewayWireDest = gw.wireDest;
            gatewayDisplayCallsign = gw.displayCallsign;
            gatewayLineUid = gw.lineUid;
            Log.d(TAG, "Inbound RF gateway envelope wireDest=" + gatewayWireDest
                    + " displayCallsign=" + gatewayDisplayCallsign
                    + (gw.aprsSenderCall != null && !gw.aprsSenderCall.isEmpty()
                    ? " aprsSender=" + gw.aprsSenderCall : "")
                    + (gatewayLineUid != null && !gatewayLineUid.isEmpty()
                    ? " lineUid=" + gatewayLineUid : ""));
        }

        final boolean aprsContext = radioPacketMessageId == 0
                || (gw != null && gw.aprsSenderCall != null && !gw.aprsSenderCall.trim().isEmpty());
        String effectiveFromCallsign = fromCallsign;
        if (gw != null && gw.aprsSenderCall != null && !gw.aprsSenderCall.trim().isEmpty()) {
            effectiveFromCallsign = gw.aprsSenderCall.trim();
        }

        // Determine chat room — if destination is a specific callsign,
        // use direct chat. Otherwise use broadcast.
        String chatRoom;
        if (isBroadcastRoom(toCallsign)) {
            chatRoom = ALL_CHAT_ROOMS;
        } else {
            chatRoom = toCallsign.trim();
        }
        if (gatewayDisplayCallsign != null && !gatewayDisplayCallsign.isEmpty()) {
            chatRoom = gatewayDisplayCallsign;
        }
        // gatewayWireDest is the 6-char AX.25 address (wire only, never shown in UI).

        String lineSenderUid = parseGeoChatSenderUid(gatewayLineUid);
        String senderUid;
        if (aprsContext) {
            senderUid = ensureAprsPluginChatContact(effectiveFromCallsign);
            if (senderUid.isEmpty() && effectiveFromCallsign != null) {
                senderUid = syntheticAndroidUid(
                        AprsMessageTransmitter.normalizeAddressee(effectiveFromCallsign));
            }
        } else {
            String btechUid = cotBridge != null ? cotBridge.resolveBtechUidForId(fromCallsign) : null;
            if (lineSenderUid != null && !lineSenderUid.isEmpty()) {
                senderUid = resolveCanonicalPeerUid(fromCallsign, lineSenderUid, btechUid);
            } else {
                senderUid = resolveCanonicalPeerUid(fromCallsign, btechUid);
            }
        }

        // Direct DM: thread id must be the *remote* peer's ANDROID-* UID. Packets include a
        // 6-byte "room" (RF destination) that is often THIS operator's callsign (e.g. JUNIOR).
        // Local operator is rarely in btechIdToUid MapView.getDeviceUid() is ANDROID-1729… not
        // ANDROID-JUNIOR — so resolveBtechUidForId("JUNIOR") is often NULL and we incorrectly
        // used sender ANDROID-VETTE as both ends (GeoChat.ANDROID-VETTE.ANDROID-VETTE).
        // Detect RF dest == configured local callsign (or its AX.25 form) FIRST, then use sender UID.
        if (!ALL_CHAT_ROOMS.equalsIgnoreCase(chatRoom)) {
            String destUid = cotBridge.resolveBtechUidForId(chatRoom);
            String selfUid = null;
            try {
                selfUid = MapView.getDeviceUid();
            } catch (Exception ignored) {
            }

            boolean peerThreadResolved = false;
            boolean keepGroupThread = isLikelyGroupConversationThread(chatRoom);
            boolean destinationLooksSelf = inboundRfDestinationLooksLikeSelf(
                    gatewayWireDest, gatewayDisplayCallsign, chatRoom, toCallsign);

            // Direct RF chat is not routed/hopped: if this packet is explicitly addressed to
            // another peer, do not inject it locally or mutate Contacts. Prevents bridge nodes
            // from spawning abbreviated ghost rows (e.g. SMKY15) for transit DMs.
            if (!keepGroupThread && !destinationLooksSelf) {
                Log.d(TAG, "Inbound DM ignored (not for this device): from=" + fromCallsign
                        + " room=" + chatRoom + " destUid=" + destUid + " selfUid=" + selfUid);
                return false;
            }

            // APRS: create a Contacts row keyed by FCC callsign (not ATAK tactical name).
            if (senderUid != null && senderUid.startsWith(ANDROID_UID_PREFIX)
                    && (selfUid == null || !selfUid.equalsIgnoreCase(senderUid))) {
                if (aprsContext) {
                    registerAprsChatContactRouting(effectiveFromCallsign, senderUid);
                } else {
                    if ((senderUid == null || senderUid.isEmpty()) && fromCallsign != null) {
                        senderUid = syntheticAndroidUid(fromCallsign);
                    }
                    String canonicalUid = ensurePluginChatContact(fromCallsign, senderUid);
                    if (canonicalUid == null || canonicalUid.isEmpty()) {
                        canonicalUid = senderUid;
                    } else {
                        senderUid = canonicalUid;
                    }
                    collapseDuplicateContactsForCallsign(fromCallsign, null);
                    if (canonicalUid != null && !canonicalUid.isEmpty()) {
                        cotBridge.registerBtechContactUid(canonicalUid);
                        if (senderUid != null && !senderUid.isEmpty()
                                && !senderUid.equalsIgnoreCase(canonicalUid)) {
                            cotBridge.registerBtechContactUidAlias(senderUid, canonicalUid);
                        }
                        if (fromCallsign != null && !fromCallsign.trim().isEmpty()) {
                            cotBridge.registerBtechContactId(fromCallsign, canonicalUid);
                            String radioTrunc = CallsignUtil.toRadioCallsign(fromCallsign);
                            if (radioTrunc != null && !radioTrunc.isEmpty()
                                    && !radioTrunc.equalsIgnoreCase(fromCallsign.trim())) {
                                cotBridge.registerBtechContactId(radioTrunc, canonicalUid);
                            }
                        }
                    }
                    if (lineSenderUid != null && !lineSenderUid.isEmpty()) {
                        String lineCanonical = cotBridge.resolveBtechCanonicalUid(lineSenderUid);
                        cotBridge.registerBtechContactUid(lineCanonical);
                        if (!lineSenderUid.equalsIgnoreCase(lineCanonical)) {
                            cotBridge.registerBtechContactUidAlias(lineSenderUid, lineCanonical);
                        }
                    }
                }
            }

            if (destinationLooksSelf
                    && senderUid != null && !senderUid.isEmpty()
                    && (selfUid == null || !selfUid.equals(senderUid))) {
                Log.d(TAG, "Inbound DM: RF destination is local callsign \"" + chatRoom
                        + "\" — thread → remote peer " + senderUid);
                chatRoom = senderUid;
                peerThreadResolved = true;
            }

            if (!peerThreadResolved && !keepGroupThread
                    && senderUid != null && !senderUid.isEmpty()
                    && (selfUid == null || !selfUid.equals(senderUid))) {
                Log.d(TAG, "Inbound DM: destination is self — thread id → remote " + senderUid
                        + " (RF room was " + chatRoom + ")");
                chatRoom = senderUid;
            } else if (!peerThreadResolved && destUid != null && !destUid.isEmpty()
                    && (selfUid == null || !selfUid.equals(destUid))) {
                chatRoom = destUid;
            }
        }

        if (isDuplicateInboundChatDelivery(gatewayLineUid, senderUid, message, radioPacketMessageId)) {
            Log.d(TAG, "Skip duplicate inbound chat (already delivered) mid=" + radioPacketMessageId
                    + " from=" + fromCallsign
                    + (gatewayLineUid != null ? " lineUid=" + gatewayLineUid : ""));
            return true;
        }

        Log.d(TAG, "Injecting radio message (mid=" + radioPacketMessageId + "): "
                + fromCallsign + " → " + chatRoom + ": " + message);

        if (GeoChatContactListHelper.isContactListUpdateMessage(message)
                && radioPacketMessageId > 0) {
            Log.w(TAG, "Received compact [UPDATED CONTACTS] without hierarchy — "
                    + "group sync requires full CoT over RF; ask sender to update plugin");
        }

        // Maintain a plugin unread counter for Contacts icon badge.
        // (Native GeoChat unread tracking is not reliably reflected for plugin contacts on all builds.)
        if (chatRoom != null && chatRoom.startsWith("ANDROID-")) {
            // Track wire mid for READ ACK once the user opens the conversation.
            if (radioPacketMessageId > 0) {
                addPendingReadAck(chatRoom, radioPacketMessageId);
            }
            String open = openConversationId;
            if (open != null && open.equals(chatRoom)) {
                // Conversation is open; treat as already-seen.
                clearUnreadLocal(chatRoom);
            } else {
                UVProContactHandler.incrementUnreadOnce(chatRoom, radioPacketMessageId, message);
                pendingUnreadConversationUids.add(chatRoom);
                startUnreadVisibilityPollIfNeeded();
            }
        }

        String senderDisplay = aprsContext
                ? AprsMessageTransmitter.normalizeAddressee(effectiveFromCallsign)
                : resolveSenderDisplayCallsign(fromCallsign, senderUid);
        if ((senderDisplay == null || senderDisplay.isEmpty()) && effectiveFromCallsign != null) {
            senderDisplay = effectiveFromCallsign.trim();
        }
        cotBridge.injectChatCot(senderDisplay, message, chatRoom,
                radioPacketMessageId, gatewayLineUid, aprsContext ? senderUid : null);
        noteInboundGeoChatDelivered(gatewayLineUid, senderUid, message, radioPacketMessageId);
        return true;
    }

    /**
     * Record a GeoChat line delivered via network (Wi‑Fi/TAK) so a redundant RF copy is skipped.
     */
    public void noteInboundGeoChatDelivered(String lineUidOrNull, String senderUid,
                                            String messageBody, int wireMid) {
        long now = System.currentTimeMillis();
        if (lineUidOrNull != null && !lineUidOrNull.trim().isEmpty()) {
            String trimmed = lineUidOrNull.trim();
            recentInboundLineUids.put(normalizeInboundLineUidKey(trimmed),
                    now + INBOUND_LINE_UID_DEDUPE_MS);
            String suffix = extractBareLineSuffix(trimmed);
            if (suffix != null) {
                recentInboundLineUids.put("suffix:" + suffix.toLowerCase(Locale.US),
                        now + INBOUND_LINE_UID_DEDUPE_MS);
            }
        }
        if (wireMid > 0) {
            recentInboundWireMids.put(wireMid, now + INBOUND_LINE_UID_DEDUPE_MS);
        }
        String contentKey = buildInboundContentKey(senderUid, messageBody);
        if (contentKey != null) {
            recentInboundContentKeys.put(contentKey, now + INBOUND_CONTENT_DEDUPE_MS);
        }
        trimInboundDedupeMaps();
    }

    private boolean isDuplicateInboundChatDelivery(String lineUidOrNull, String senderUid,
                                                   String messageBody, int wireMid) {
        long now = System.currentTimeMillis();
        if (lineUidOrNull != null && !lineUidOrNull.trim().isEmpty()) {
            String trimmed = lineUidOrNull.trim();
            if (isRecentDedupeEntry(recentInboundLineUids, normalizeInboundLineUidKey(trimmed), now)) {
                return true;
            }
            String suffix = extractBareLineSuffix(trimmed);
            if (suffix != null
                    && isRecentDedupeEntry(recentInboundLineUids,
                    "suffix:" + suffix.toLowerCase(Locale.US), now)) {
                return true;
            }
        }
        if (wireMid > 0 && isRecentDedupeEntry(recentInboundWireMids, wireMid, now)) {
            return true;
        }
        String contentKey = buildInboundContentKey(senderUid, messageBody);
        return contentKey != null
                && isRecentDedupeEntry(recentInboundContentKeys, contentKey, now);
    }

    private static boolean isRecentDedupeEntry(ConcurrentHashMap<String, Long> map,
                                               String key, long now) {
        Long until = map.get(key);
        return until != null && now < until;
    }

    private static boolean isRecentDedupeEntry(ConcurrentHashMap<Integer, Long> map,
                                               int key, long now) {
        Long until = map.get(key);
        return until != null && now < until;
    }

    private static String normalizeInboundLineUidKey(String lineUid) {
        return lineUid.trim().toLowerCase(Locale.US);
    }

    private static String extractBareLineSuffix(String lineUid) {
        if (lineUid == null) {
            return null;
        }
        String trimmed = lineUid.trim();
        if (trimmed.startsWith("GeoChat.")) {
            int lastDot = trimmed.lastIndexOf('.');
            if (lastDot >= 0 && lastDot < trimmed.length() - 1) {
                return trimmed.substring(lastDot + 1);
            }
            return null;
        }
        if (trimmed.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            return trimmed;
        }
        return null;
    }

    private static String buildInboundContentKey(String senderUid, String messageBody) {
        if (senderUid == null || messageBody == null) {
            return null;
        }
        String sender = senderUid.trim().toUpperCase(Locale.US);
        String body = messageBody.trim();
        if (sender.isEmpty() || body.isEmpty()) {
            return null;
        }
        return sender + "\0" + body;
    }

    private void trimInboundDedupeMaps() {
        long now = System.currentTimeMillis();
        recentInboundLineUids.entrySet().removeIf(e -> e.getValue() <= now);
        recentInboundContentKeys.entrySet().removeIf(e -> e.getValue() <= now);
        recentInboundWireMids.entrySet().removeIf(e -> e.getValue() <= now);
        while (recentInboundLineUids.size() > MAX_INBOUND_DEDUPE_ENTRIES) {
            Iterator<String> it = recentInboundLineUids.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            recentInboundLineUids.remove(it.next());
        }
    }

    private String resolveSenderDisplayCallsign(String fromCallsign, String senderUid) {
        String wire = fromCallsign != null ? fromCallsign.trim() : "";
        if (!wire.isEmpty()) {
            try {
                Contact byWire = findContactByCallsignVariants(
                        Contacts.getInstance(), wire);
                if (byWire != null) {
                    String name = byWire.getName();
                    if (name != null && !name.trim().isEmpty()) {
                        return name.trim();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        String fallback = wire;
        if (senderUid == null || senderUid.trim().isEmpty()) {
            return fallback;
        }
        try {
            Contact c = Contacts.getInstance().getContactByUuid(senderUid.trim());
            if (c != null) {
                String name = c.getName();
                if (name != null && !name.trim().isEmpty()) {
                    return name.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return fallback.isEmpty() ? senderUid.trim() : fallback;
    }

    /** True if the RF payload "chat room" equals this operator's callsign or its AX.25 form. */
    private boolean rfDestinationLooksLikeSelf(String room) {
        if (room == null || room.isEmpty()) {
            return false;
        }
        String r = room.trim().toUpperCase(Locale.US);
        if (r.isEmpty()) {
            return false;
        }

        Set<String> accepted = new HashSet<>();
        collectLocalStationDestinationVariants(accepted);
        if (accepted.isEmpty()) {
            return false;
        }

        if (matchesSelfCallsignVariants(r, accepted)) {
            return true;
        }
        String normalized = normalizeAprsDestination(r);
        if (!normalized.isEmpty() && accepted.contains(normalized)) {
            return true;
        }
        if (r.startsWith(ANDROID_UID_PREFIX)) {
            String bare = r.substring(ANDROID_UID_PREFIX.length());
            if (matchesSelfCallsignVariants(bare, accepted)) {
                return true;
            }
            normalized = normalizeAprsDestination(bare);
            return !normalized.isEmpty() && accepted.contains(normalized);
        }
        return false;
    }

    /** ATAK tactical callsign + configured APRS FCC identity for inbound DM routing. */
    private void collectLocalStationDestinationVariants(Set<String> out) {
        if (out == null) {
            return;
        }
        if (localCallsign() != null) {
            addSelfCallsignVariants(out, localCallsign());
        }
        try {
            if (mapView != null && mapView.getSelfMarker() != null) {
                addSelfCallsignVariants(out, mapView.getSelfMarker().getMetaString("callsign", null));
            }
        } catch (Exception ignored) {
        }
        addAprsConfiguredDestinationVariants(out);
    }

    private void addAprsConfiguredDestinationVariants(Set<String> out) {
        try {
            if (pluginContext == null) {
                return;
            }
            String aprsBase = SettingsFragment.getAprsCallsign(pluginContext);
            int aprsSsid = SettingsFragment.getAprsSsid(pluginContext);
            addAprsDestinationVariants(out, aprsBase);
            if (aprsBase != null && !aprsBase.trim().isEmpty()
                    && aprsSsid > 0 && aprsSsid <= 15) {
                addAprsDestinationVariants(out, aprsBase.trim() + "-" + aprsSsid);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean inboundRfDestinationLooksLikeSelf(String gatewayWireDest,
                                                      String gatewayDisplayCallsign,
                                                      String chatRoom,
                                                      String wireToCallsign) {
        if (gatewayWireDest != null && !gatewayWireDest.trim().isEmpty()
                && rfDestinationLooksLikeSelf(gatewayWireDest.trim())) {
            return true;
        }
        if (inboundDestinationMatchesLocalMeshPubKey(gatewayWireDest)) {
            return true;
        }
        if (gatewayDisplayCallsign != null && !gatewayDisplayCallsign.trim().isEmpty()
                && rfDestinationLooksLikeSelf(gatewayDisplayCallsign.trim())) {
            return true;
        }
        if (inboundDestinationMatchesLocalMeshPubKey(gatewayDisplayCallsign)) {
            return true;
        }
        if (chatRoom != null && !chatRoom.trim().isEmpty()
                && rfDestinationLooksLikeSelf(chatRoom.trim())) {
            return true;
        }
        if (inboundDestinationMatchesLocalMeshPubKey(chatRoom)) {
            return true;
        }
        if (wireToCallsign != null && !wireToCallsign.trim().isEmpty()
                && rfDestinationLooksLikeSelf(wireToCallsign.trim())) {
            return true;
        }
        if (inboundDestinationMatchesLocalMeshPubKey(wireToCallsign)) {
            return true;
        }
        return false;
    }

    private boolean inboundDestinationMatchesLocalMeshPubKey(String rawDestination) {
        String candidate = extractMeshPublicKeyCandidate(rawDestination);
        if (candidate.isEmpty()) {
            return false;
        }
        String local = getLocalMeshPublicKey();
        return !local.isEmpty() && candidate.equalsIgnoreCase(local);
    }

    private String getLocalMeshPublicKey() {
        if (!(btManager instanceof MeshBtConnectionManager)) {
            return "";
        }
        String key = ((MeshBtConnectionManager) btManager).getSelfPubKeyHex();
        if (key == null) {
            return "";
        }
        String trimmed = key.trim().toUpperCase(Locale.US);
        return isLikelyMeshPublicKey(trimmed) ? trimmed : "";
    }

    /**
     * Sender UID from {@code GeoChat.{senderUid}.{thread}.{suffix}} when present on RF gateway relay.
     */
    static String parseGeoChatSenderUid(String geoChatLineUid) {
        if (geoChatLineUid == null || geoChatLineUid.trim().isEmpty()) {
            return null;
        }
        String trimmed = geoChatLineUid.trim();
        if (!trimmed.startsWith("GeoChat.")) {
            return null;
        }
        String rest = trimmed.substring("GeoChat.".length());
        int lastDot = rest.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        rest = rest.substring(0, lastDot);
        int prevDot = rest.lastIndexOf('.');
        if (prevDot <= 0 || prevDot >= rest.length() - 1) {
            return null;
        }
        String senderUid = rest.substring(0, prevDot).trim();
        return senderUid.isEmpty() ? null : senderUid;
    }

    /** Match full/radio/alphanumeric callsign forms (SMOKEY_15 == SMOKEY15 == SMKY15). */
    private static boolean matchesSelfCallsignVariants(String candidate, Set<String> accepted) {
        if (candidate == null || candidate.isEmpty() || accepted == null || accepted.isEmpty()) {
            return false;
        }
        String upper = candidate.trim().toUpperCase(Locale.US);
        if (accepted.contains(upper)) {
            return true;
        }
        LinkedHashSet<String> variants = buildCallsignVariants(upper);
        for (String variant : variants) {
            if (accepted.contains(variant)) {
                return true;
            }
        }
        String candidateAlpha = callsignAlphanumericKey(upper);
        if (candidateAlpha.isEmpty()) {
            return false;
        }
        for (String selfVariant : accepted) {
            if (candidateAlpha.equals(callsignAlphanumericKey(selfVariant))) {
                return true;
            }
        }
        String candidateRadio = radioCallsignKey(upper);
        if (!candidateRadio.isEmpty()) {
            for (String selfVariant : accepted) {
                if (candidateRadio.equals(radioCallsignKey(selfVariant))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String callsignAlphanumericKey(String rawCallsign) {
        if (rawCallsign == null) {
            return "";
        }
        return rawCallsign.replaceAll("[^A-Z0-9]", "").toUpperCase(Locale.US);
    }

    private static void addSelfCallsignVariants(Set<String> out, String raw) {
        if (raw == null) {
            return;
        }
        String base = raw.trim().toUpperCase(Locale.US);
        if (base.isEmpty()) {
            return;
        }

        out.add(base);

        String alpha = CallsignUtil.alphanumericCallsignKey(base);
        if (!alpha.isEmpty()) {
            out.add(alpha);
            out.add(ANDROID_UID_PREFIX + alpha);
        }

        // Avoid ambiguous root-only matches (e.g. JESTER_15 vs JESTER_25).
        if (base.indexOf('-') < 0 && base.indexOf('_') < 0) {
            String noSuffix = base.replaceFirst("[-_].*$", "");
            if (!noSuffix.isEmpty()) {
                out.add(noSuffix);
            }
        }

        try {
            String radio = CallsignUtil.toRadioCallsign(base);
            if (radio != null && !radio.trim().isEmpty()) {
                out.add(radio.trim().toUpperCase(Locale.US));
                String radioUpper = radio.trim().toUpperCase(Locale.US);
                if (radioUpper.indexOf('-') < 0 && radioUpper.indexOf('_') < 0) {
                    String radioNoSuffix = radioUpper.replaceFirst("[-_].*$", "");
                    if (!radioNoSuffix.isEmpty()) {
                        out.add(radioNoSuffix);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isLikelyGroupConversationThread(String threadIdRaw) {
        if (threadIdRaw == null) {
            return false;
        }
        String threadId = threadIdRaw.trim();
        if (threadId.isEmpty() || "All Chat Rooms".equalsIgnoreCase(threadId)) {
            return false;
        }
        try {
            Contact c = Contacts.getInstance().getContactByUuid(threadId);
            if (c instanceof com.atakmap.android.contact.GroupContact) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return threadId.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    public static String syntheticAndroidUid(String callsign) {
        if (callsign == null) {
            return "";
        }
        String c = callsign.trim().toUpperCase(Locale.US);
        if (c.isEmpty()) {
            return "";
        }
        if (c.startsWith("ANDROID-")) {
            return c;
        }
        c = c.replaceAll("[^A-Z0-9_\\-]", "");
        if (c.isEmpty()) {
            return "";
        }
        return "ANDROID-" + c;
    }

    /**
     * Ensure an ATAK contact exists for APRS GeoChat routing, then return the contact UID.
     */
    /**
     * One ANDROID-* (or existing) UID per callsign — avoids duplicate JESTER/SMOKEY rows when
     * GeoChat uses device UID, link UID, and callsign forms interchangeably.
     */
    public static String resolveCanonicalPeerUid(String callsignRaw, String... candidateUids) {
        String callsign = callsignRaw != null ? callsignRaw.trim().toUpperCase(Locale.US) : "";
        try {
            Contacts contacts = Contacts.getInstance();
            if (!callsign.isEmpty()) {
                Contact byCallsign = findContactByCallsignVariants(contacts, callsign);
                if (byCallsign != null && !byCallsign.getUID().isEmpty()) {
                    return byCallsign.getUID();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "resolveCanonicalPeerUid callsign lookup failed", e);
        }
        if (candidateUids != null) {
            for (String raw : candidateUids) {
                if (raw == null || raw.trim().isEmpty()) {
                    continue;
                }
                String uid = raw.trim();
                try {
                    Contact c = Contacts.getInstance().getContactByUuid(uid);
                    if (c != null) {
                        return c.getUID();
                    }
                } catch (Exception ignored) {
                }
                if (uid.toUpperCase(Locale.US).startsWith(ANDROID_UID_PREFIX)) {
                    String bare = uid.substring(ANDROID_UID_PREFIX.length());
                    try {
                        Contacts contacts = Contacts.getInstance();
                        Contact byCall = findContactByCallsignVariants(contacts, bare);
                        if (byCall != null && !byCall.getUID().isEmpty()) {
                            return byCall.getUID();
                        }
                    } catch (Exception ignored) {
                    }
                    if (callsign.isEmpty()) {
                        return uid.toUpperCase(Locale.US);
                    }
                }
            }
        }
        if (!callsign.isEmpty()) {
            return syntheticAndroidUid(callsign);
        }
        return "";
    }

    public static String ensurePluginChatContact(String callsignRaw, String preferredUid) {
        String uid = preferredUid;
        if (uid == null || uid.trim().isEmpty()) {
            uid = syntheticAndroidUid(callsignRaw);
        } else {
            uid = uid.trim().toUpperCase(Locale.US);
        }
        if (uid.isEmpty()) {
            return "";
        }

        String callsign = callsignRaw != null ? callsignRaw.trim().toUpperCase(Locale.US) : "";
        if (callsign.isEmpty() && uid.startsWith(ANDROID_UID_PREFIX)) {
            callsign = uid.substring(ANDROID_UID_PREFIX.length());
        }
        if (callsign.isEmpty()) {
            callsign = uid;
        }

        try {
            Contacts contacts = Contacts.getInstance();
            if (!callsign.isEmpty()) {
                Contact byCallsign = findContactByCallsignVariants(contacts, callsign);
                if (byCallsign instanceof IndividualContact) {
                    IndividualContact ic = (IndividualContact) byCallsign;
                    // Preserve existing ATAK/Wi-Fi contact icon/action behavior.
                    // Reuse this contact as canonical, but do not mutate connector stack.
                    preferNativeContactAction(ic);
                    removeDuplicateUidContact(contacts, uid, ic.getUID());
                    collapseDuplicateContactsForCallsign(callsign, ic.getUID());
                    finishContactMerge(ic, callsign);
                    return ic.getUID();
                }
            }
            Contact existing = contacts.getContactByUuid(uid);
            if (existing instanceof IndividualContact) {
                return ((IndividualContact) existing).getUID();
            }
            if (!callsign.isEmpty()) {
                collapseDuplicateContactsForCallsign(callsign, null);
                Contact merged = findContactByCallsignVariants(contacts, callsign);
                if (merged instanceof IndividualContact) {
                    IndividualContact ic = (IndividualContact) merged;
                    preferNativeContactAction(ic);
                    removeDuplicateUidContact(contacts, uid, ic.getUID());
                    finishContactMerge(ic, callsign);
                    return ic.getUID();
                }
            }
            if (existing != null) {
                Log.w(TAG, "Cannot ensure plugin chat contact; non-individual UID exists: " + uid);
                return uid;
            }

            MapItem item = null;
            MapView mv = MapView.getMapView();
            if (mv != null && mv.getRootGroup() != null) {
                item = mv.getRootGroup().deepFindUID(uid);
            }

            IndividualContact c = new IndividualContact(callsign, uid, item,
                    buildNativeConnectorSeed(callsign));
            contacts.addContact(c);
            return uid;
        } catch (Exception e) {
            Log.e(TAG, "ensurePluginChatContact failed callsign=" + callsignRaw
                    + " uid=" + uid, e);
            return "";
        }
    }

    /**
     * Ensures the exact destination UID exists in Contacts (for ATAK send lookup by UID).
     * Unlike {@link #ensurePluginChatContact(String, String)}, this does not canonicalize by
     * callsign first, because group-send uses exact toUID matching.
     */
    public static String ensurePluginChatContactExactUid(String callsignRaw, String preferredUid) {
        String uid = preferredUid != null ? preferredUid.trim().toUpperCase(Locale.US) : "";
        if (uid.isEmpty()) {
            return "";
        }
        // Mesh nodes are addressed by pubkey; native GeoChat/IP routing (stcp) is unroutable and
        // makes ATAK throw "Send to unknown contact". Route these over the plugin RF DM path
        // (MeshSendMessageConnector) instead of falling through to preferNativeContactAction.
        if (uid.startsWith(MESH_NODE_UID_PREFIX) || uid.startsWith(MESH_RPTR_UID_PREFIX)) {
            com.uvpro.plugin.UVProContactHandler.ensureMeshChatContactByUid(uid, callsignRaw);
            return uid;
        }
        String callsign = callsignRaw != null ? callsignRaw.trim().toUpperCase(Locale.US) : "";
        if (callsign.isEmpty() && uid.startsWith(ANDROID_UID_PREFIX)) {
            callsign = uid.substring(ANDROID_UID_PREFIX.length());
        }
        if (callsign.isEmpty()) {
            callsign = uid;
        }
        try {
            Contacts contacts = Contacts.getInstance();
            if (!callsign.isEmpty()) {
                Contact byCallsign = findContactByCallsignVariants(contacts, callsign);
                if (byCallsign instanceof IndividualContact) {
                    IndividualContact ic = (IndividualContact) byCallsign;
                    // Preserve existing ATAK/Wi-Fi contact icon/action behavior.
                    // Reuse this contact as canonical, but do not mutate connector stack.
                    preferNativeContactAction(ic);
                    removeDuplicateUidContact(contacts, uid, ic.getUID());
                    collapseDuplicateContactsForCallsign(callsign, ic.getUID());
                    finishContactMerge(ic, callsign);
                    return ic.getUID();
                }
            }
            Contact existing = contacts.getContactByUuid(uid);
            if (existing instanceof IndividualContact) {
                return ((IndividualContact) existing).getUID();
            }
            if (existing != null) {
                return uid;
            }
            MapItem item = null;
            MapView mv = MapView.getMapView();
            if (mv != null && mv.getRootGroup() != null) {
                item = mv.getRootGroup().deepFindUID(uid);
            }
            IndividualContact c = new IndividualContact(callsign, uid, item,
                    buildNativeConnectorSeed(callsign));
            contacts.addContact(c);
            return uid;
        } catch (Exception e) {
            Log.e(TAG, "ensurePluginChatContactExactUid failed callsign=" + callsignRaw
                    + " uid=" + preferredUid, e);
            return "";
        }
    }

    /**
     * Wi‑Fi peer received an RF-uplinked SA (map marker without {@code contact@endpoint}).
     * ATAK does not always create a Contacts row — register one and apply reachability policy.
     */
    public static void ensureInboundNetworkSaContact(String callsignRaw, String uidRaw) {
        ensureInboundNetworkSaContact(callsignRaw, uidRaw, 0);
    }

    private static void ensureInboundNetworkSaContact(String callsignRaw, String uidRaw,
                                                        int attempt) {
        if (uidRaw == null || uidRaw.trim().isEmpty()) {
            return;
        }
        String uid = uidRaw.trim();
        try {
            String selfUid = MapView.getDeviceUid();
            if (selfUid != null && selfUid.equalsIgnoreCase(uid)) {
                return;
            }
        } catch (Exception ignored) {
        }
        MapView mv = MapView.getMapView();
        if (mv == null) {
            return;
        }
        String callsign = callsignRaw != null ? callsignRaw.trim().toUpperCase(Locale.US) : "";
        if (callsign.isEmpty() && uid.toUpperCase(Locale.US).startsWith(ANDROID_UID_PREFIX)) {
            callsign = uid.substring(ANDROID_UID_PREFIX.length());
        }
        if (callsign.isEmpty()) {
            return;
        }
        try {
            MapItem item = mv.getRootGroup() != null ? mv.getRootGroup().deepFindUID(uid) : null;
            Contacts contacts = Contacts.getInstance();
            IndividualContact ic = null;
            Contact byUid = contacts.getContactByUuid(uid);
            if (byUid instanceof IndividualContact) {
                ic = (IndividualContact) byUid;
            } else {
                Contact byCallsign = findContactByCallsignVariants(contacts, callsign);
                if (byCallsign instanceof IndividualContact) {
                    ic = (IndividualContact) byCallsign;
                }
            }
            if (ic == null) {
                if (item == null && attempt < 20) {
                    mv.postDelayed(() -> ensureInboundNetworkSaContact(
                            callsignRaw, uidRaw, attempt + 1), 100L);
                    return;
                }
                ic = new IndividualContact(callsign, uid, item, buildNativeConnectorSeed(callsign));
                contacts.addContact(ic);
                Log.i(TAG, "Registered inbound network SA contact uid=" + uid
                        + " callsign=" + callsign);
            } else if (item != null && ic.getMapItem() == null) {
                ic.setMapItem(item);
            }
            applyPostMergeCommsPolicy(ic);
            ic.dispatchChangeEvent();
            collapseDuplicateContactsForCallsign(callsign, ic.getUID());
        } catch (Exception e) {
            Log.w(TAG, "ensureInboundNetworkSaContact failed uid=" + uid, e);
        }
    }

    private static void removeDuplicateUidContact(Contacts contacts, String candidateUid, String keepUid) {
        if (contacts == null || candidateUid == null || candidateUid.trim().isEmpty()) {
            return;
        }
        String c = candidateUid.trim();
        if (keepUid != null && c.equalsIgnoreCase(keepUid.trim())) {
            return;
        }
        try {
            Contact dup = contacts.getContactByUuid(c);
            if (dup != null) {
                contacts.removeContact(dup);
            }
        } catch (Exception ignored) {
        }
    }

    public static Contact findContactByCallsignVariants(Contacts contacts, String rawCallsign) {
        if (contacts == null) {
            return null;
        }
        LinkedHashSet<String> variants = buildCallsignVariants(rawCallsign);
        String queryRadioKey = radioCallsignKey(rawCallsign);
        java.util.List<Contact> all = contacts.getAllContacts();
        if (all == null || all.isEmpty()) {
            return null;
        }

        IndividualContact best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Contact c : all) {
            if (!(c instanceof IndividualContact)) {
                continue;
            }
            IndividualContact ic = (IndividualContact) c;
            String name = ic.getName() != null ? ic.getName().trim().toUpperCase(Locale.US) : "";
            String uid = ic.getUID() != null ? ic.getUID().trim().toUpperCase(Locale.US) : "";
            if (!contactMatchesCallsignVariants(name, uid, variants, queryRadioKey)) {
                continue;
            }
            int score = scorePreferredNativeContact(ic);
            if (score > bestScore) {
                bestScore = score;
                best = ic;
            }
        }
        return best;
    }

    /** True when a contact name/uid matches literal variants or the same 6-byte radio callsign. */
    private static boolean contactMatchesCallsignVariants(String contactName, String contactUid,
                                                            LinkedHashSet<String> variants,
                                                            String queryRadioKey) {
        boolean match = variants.contains(contactName);
        String bareUid = "";
        if (contactUid != null && contactUid.startsWith(ANDROID_UID_PREFIX)) {
            bareUid = contactUid.substring(ANDROID_UID_PREFIX.length());
            if (!match) {
                match = variants.contains(bareUid);
            }
        }
        if (!match && queryRadioKey != null && !queryRadioKey.isEmpty()
                && queryRadioKey.length() >= 4) {
            match = queryRadioKey.equals(radioCallsignKey(contactName));
            if (!match && !bareUid.isEmpty()) {
                match = queryRadioKey.equals(radioCallsignKey(bareUid));
            }
        }
        return match;
    }

    static String radioCallsignKey(String rawCallsign) {
        if (rawCallsign == null) {
            return "";
        }
        String radio = CallsignUtil.toRadioCallsign(rawCallsign.trim());
        return radio != null ? radio.trim().toUpperCase(Locale.US) : "";
    }

    private static LinkedHashSet<String> buildCallsignVariants(String rawCallsign) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        if (rawCallsign == null) {
            return variants;
        }
        String base = rawCallsign.trim().toUpperCase(Locale.US);
        if (base.isEmpty()) {
            return variants;
        }
        variants.add(base);
        variants.add(base.replaceAll("[^A-Z0-9_\\-]", ""));
        variants.add(base.replace("_", ""));
        variants.add(base.replace("-", ""));
        String radio = CallsignUtil.toRadioCallsign(base);
        if (radio != null && !radio.trim().isEmpty()) {
            variants.add(radio.trim().toUpperCase(Locale.US));
        }
        return variants;
    }

    public static void preferNativeContactAction(IndividualContact contact) {
        if (contact == null) {
            return;
        }
        Context ctx = MapView.getMapView() != null
                ? MapView.getMapView().getContext() : null;
        if (com.uvpro.plugin.contacts.ContactReachability.isPolicyEnabled(ctx)) {
            com.uvpro.plugin.contacts.ContactReachability.applyContactCommsPolicy(
                    contact, mergeRoutingBridge);
            return;
        }
        preferNativeContactActionInternal(contact);
    }

    public static boolean preferPositionOnlyContactActionInternal(IndividualContact contact) {
        if (contact == null) {
            return false;
        }
        try {
            MapView mv = MapView.getMapView();
            if (mv == null) {
                return false;
            }
            AtakPreferences prefs = new AtakPreferences(mv.getContext());
            String uid = contact.getUID();
            clearDefaultConnectorPref(prefs, uid);

            contact.removeConnector(PluginConnector.CONNECTOR_TYPE);
            contact.removeConnector(GeoChatConnector.CONNECTOR_TYPE);
            contact.removeConnector(IpConnector.CONNECTOR_TYPE);

            if (contact.getConnector(
                    com.uvpro.plugin.contacts.PositionOnlyConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new com.uvpro.plugin.contacts.PositionOnlyConnector());
            }
            writeDefaultConnectorPref(prefs, uid,
                    com.uvpro.plugin.contacts.PositionOnlyConnector.CONNECTOR_TYPE);
            contact.dispatchChangeEvent();
            Log.i(TAG, "preferPositionOnlyContactAction uid=" + uid
                    + " callsign=" + contact.getName());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "preferPositionOnlyContactAction failed uid="
                    + (contact != null ? contact.getUID() : "?"), e);
            return false;
        }
    }

    public static boolean preferNativeContactActionInternal(IndividualContact contact) {
        if (contact == null) {
            return false;
        }
        try {
            MapView mv = MapView.getMapView();
            if (mv == null) {
                return false;
            }
            String uid = contact.getUID();
            // Never force native GeoChat/IP for mesh-node contacts: they are addressed by pubkey
            // and the stcp endpoint is unroutable (ATAK throws "Send to unknown contact"). Keep the
            // plugin RF DM connector as the default action instead.
            if (uid != null) {
                String uidUpper = uid.trim().toUpperCase(Locale.US);
                if (uidUpper.startsWith(MESH_NODE_UID_PREFIX)
                        || uidUpper.startsWith(MESH_RPTR_UID_PREFIX)) {
                    return com.uvpro.plugin.UVProContactHandler.ensureMeshChatContactByUid(
                            uid, contact.getName());
                }
            }
            AtakPreferences prefs = new AtakPreferences(mv.getContext());
            clearDefaultConnectorPref(prefs, uid);

            contact.removeConnector(PluginConnector.CONNECTOR_TYPE);
            contact.removeConnector(
                    com.uvpro.plugin.contacts.PositionOnlyConnector.CONNECTOR_TYPE);

            if (contact.getConnector(GeoChatConnector.CONNECTOR_TYPE) == null) {
                String callsign = contact.getName() != null ? contact.getName() : "";
                contact.addConnector(new GeoChatConnector(buildNativeConnectorSeed(callsign)));
            }
            com.uvpro.plugin.UVProContactHandler.ensurePingConnectorForContact(contact);
            writeDefaultConnectorPref(prefs, uid, GeoChatConnector.CONNECTOR_TYPE);
            contact.dispatchChangeEvent();
            Log.i(TAG, "preferNativeContactAction uid=" + uid
                    + " callsign=" + contact.getName());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "preferNativeContactAction failed uid="
                    + (contact != null ? contact.getUID() : "?"), e);
            return false;
        }
    }

    private static void writeDefaultConnectorPref(AtakPreferences prefs, String contactUid,
                                                  String connectorType) {
        if (prefs == null || contactUid == null || contactUid.trim().isEmpty()) {
            return;
        }
        String trimmed = contactUid.trim();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(trimmed);
        keys.add(trimmed.toUpperCase(Locale.US));
        keys.add(trimmed.toLowerCase(Locale.US));
        for (String key : keys) {
            prefs.set("contact.connector.default." + key, connectorType);
        }
    }

    private static void clearDefaultConnectorPref(AtakPreferences prefs, String contactUid) {
        writeDefaultConnectorPref(prefs, contactUid, "");
    }

    /**
     * Repair persisted connector prefs and in-memory connectors after plugin updates or
     * duplicate UID rows from prior RF/Wi-Fi sessions.
     */
    public static void repairAllNativeContactActions() {
        try {
            Contacts contacts = Contacts.getInstance();
            java.util.List<Contact> all = contacts.getAllContacts();
            if (all == null || all.isEmpty()) {
                return;
            }
            Context ctx = MapView.getMapView() != null
                    ? MapView.getMapView().getContext() : null;
            boolean reachabilityPolicy =
                    com.uvpro.plugin.contacts.ContactReachability.isPolicyEnabled(ctx);
            int repaired = 0;
            for (Contact c : all) {
                if (!(c instanceof IndividualContact)) {
                    continue;
                }
                IndividualContact ic = (IndividualContact) c;
                if (reachabilityPolicy) {
                    if (com.uvpro.plugin.contacts.ContactReachability.applyContactCommsPolicy(
                            ic, mergeRoutingBridge)) {
                        repaired++;
                    }
                } else if (needsNativeContactRepair(ic)) {
                    preferNativeContactAction(ic);
                    repaired++;
                }
                com.uvpro.plugin.UVProContactHandler.ensurePingConnectorForContact(ic);
            }
            Log.i(TAG, "repairAllNativeContactActions repaired=" + repaired);
        } catch (Exception e) {
            Log.w(TAG, "repairAllNativeContactActions failed", e);
        }
    }

    private static void applyPostMergeCommsPolicy(IndividualContact contact) {
        if (contact == null) {
            return;
        }
        Context ctx = MapView.getMapView() != null
                ? MapView.getMapView().getContext() : null;
        if (com.uvpro.plugin.contacts.ContactReachability.isPolicyEnabled(ctx)) {
            com.uvpro.plugin.contacts.ContactReachability.applyContactCommsPolicy(
                    contact, mergeRoutingBridge);
        } else if (needsNativeContactRepair(contact)) {
            preferNativeContactActionInternal(contact);
        }
    }

    private static boolean needsNativeContactRepair(IndividualContact ic) {
        if (ic == null) {
            return false;
        }
        if (ic.getConnector(
                com.uvpro.plugin.contacts.PositionOnlyConnector.CONNECTOR_TYPE) != null) {
            return true;
        }
        String uid = ic.getUID();
        if (isOpaqueWifiDeviceUid(uid)) {
            return ic.getConnector(PluginConnector.CONNECTOR_TYPE) != null
                    || hasWrongDefaultConnectorPref(ic);
        }
        if (ic.getConnector(PluginConnector.CONNECTOR_TYPE) != null
                && shouldPreferNativeContactAction(ic)) {
            return true;
        }
        return hasWrongDefaultConnectorPref(ic);
    }

    private static boolean hasWrongDefaultConnectorPref(IndividualContact ic) {
        if (ic == null) {
            return false;
        }
        try {
            MapView mv = MapView.getMapView();
            if (mv == null) {
                return false;
            }
            AtakPreferences prefs = new AtakPreferences(mv.getContext());
            android.content.SharedPreferences sp = prefs.getSharedPrefs();
            String uid = ic.getUID();
            if (uid == null || uid.trim().isEmpty()) {
                return false;
            }
            for (String key : connectorDefaultPrefKeys(uid)) {
                String type = sp.getString(key, null);
                if (PluginConnector.CONNECTOR_TYPE.equals(type)) {
                    return true;
                }
                if (isOpaqueWifiDeviceUid(uid)
                        && IpConnector.CONNECTOR_TYPE.equals(type)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static java.util.ArrayList<String> connectorDefaultPrefKeys(String contactUid) {
        java.util.ArrayList<String> keys = new java.util.ArrayList<>(3);
        String trimmed = contactUid.trim();
        keys.add("contact.connector.default." + trimmed);
        keys.add("contact.connector.default." + trimmed.toUpperCase(Locale.US));
        keys.add("contact.connector.default." + trimmed.toLowerCase(Locale.US));
        return keys;
    }

    public static void collapseDuplicateContactsForCallsign(String rawCallsign, String keepUidHint) {
        if (rawCallsign == null || rawCallsign.trim().isEmpty()) {
            return;
        }
        try {
            Contacts contacts = Contacts.getInstance();
            java.util.List<Contact> all = contacts.getAllContacts();
            if (all == null || all.isEmpty()) {
                return;
            }
            LinkedHashSet<String> variants = buildCallsignVariants(rawCallsign);
            String queryRadioKey = radioCallsignKey(rawCallsign);
            java.util.ArrayList<IndividualContact> candidates = new java.util.ArrayList<>();
            for (Contact c : all) {
                if (!(c instanceof IndividualContact)) {
                    continue;
                }
                IndividualContact ic = (IndividualContact) c;
                String name = ic.getName() != null ? ic.getName().trim().toUpperCase(Locale.US) : "";
                String uid = ic.getUID() != null ? ic.getUID().trim().toUpperCase(Locale.US) : "";
                if (contactMatchesCallsignVariants(name, uid, variants, queryRadioKey)) {
                    candidates.add(ic);
                }
            }
            if (candidates.size() <= 1) {
                if (candidates.size() == 1) {
                    IndividualContact only = candidates.get(0);
                    applyPostMergeCommsPolicy(only);
                    finishContactMerge(only, rawCallsign);
                }
                return;
            }

            IndividualContact keep = null;
            int bestScore = Integer.MIN_VALUE;
            for (IndividualContact ic : candidates) {
                int score = scorePreferredNativeContact(ic);
                String uid = ic.getUID() != null ? ic.getUID().trim().toUpperCase(Locale.US) : "";
                String contactName = ic.getName() != null ? ic.getName().trim() : rawCallsign;
                if (keepUidHint != null && !keepUidHint.trim().isEmpty()
                        && uid.equalsIgnoreCase(keepUidHint.trim())
                        && !isSyntheticCallsignUid(uid, contactName)) {
                    score += 200;
                }
                if (score > bestScore) {
                    bestScore = score;
                    keep = ic;
                }
            }
            if (keep == null) {
                return;
            }
            applyPostMergeCommsPolicy(keep);
            String keepUid = keep.getUID();
            for (IndividualContact ic : candidates) {
                if (ic.getUID().equalsIgnoreCase(keepUid)) {
                    continue;
                }
                Log.d(TAG, "collapseDuplicate removing uid=" + ic.getUID()
                        + " keeping uid=" + keepUid + " callsign=" + rawCallsign);
                propagateTransportMarksFromDuplicate(keep, ic, rawCallsign);
                contacts.removeContact(ic);
            }
            finishContactMerge(keep, rawCallsign);
        } catch (Exception ignored) {
        }
    }

    /**
     * When collapsing duplicate rows, carry RF transport marks and wire UID aliases onto the
     * keeper so chat/CoT routing still sees both Wi‑Fi and RF paths after merge.
     */
    private static void propagateTransportMarksFromDuplicate(IndividualContact keep,
                                                             IndividualContact removed,
                                                             String callsignRaw) {
        if (keep == null || removed == null) {
            return;
        }
        CotBridge bridge = mergeRoutingBridge;
        if (bridge == null) {
            return;
        }
        String keepUid = keep.getUID();
        String removedUid = removed.getUID();
        if (keepUid == null || removedUid == null) {
            return;
        }
        if (bridge.isRfNativeContact(removedUid)) {
            bridge.markRfNativeContact(keepUid);
        }
        String removedName = removed.getName();
        if (removedName != null && !removedName.trim().isEmpty()) {
            bridge.markRfHeardCallsign(removedName.trim());
        }
        if (callsignRaw != null && !callsignRaw.trim().isEmpty()) {
            bridge.markRfHeardCallsign(callsignRaw.trim());
        }
        if (!removedUid.equalsIgnoreCase(keepUid)) {
            bridge.registerBtechContactUidAlias(removedUid, keepUid);
        }
    }

    private static void finishContactMerge(IndividualContact keep, String callsignRaw) {
        if (keep == null) {
            return;
        }
        CotBridge bridge = mergeRoutingBridge;
        if (bridge == null) {
            return;
        }
        bridge.registerMergedContact(keep);
        if (callsignRaw != null && !callsignRaw.trim().isEmpty()) {
            bridge.removeOrphanRfMapMarkerForCallsign(callsignRaw, keep.getUID());
        } else if (keep.getName() != null && !keep.getName().trim().isEmpty()) {
            bridge.removeOrphanRfMapMarkerForCallsign(keep.getName(), keep.getUID());
        }
    }

    public static String displayCallsignForContact(String callsignFallback, String uid) {
        if (uid != null && !uid.trim().isEmpty()) {
            try {
                Contact c = Contacts.getInstance().getContactByUuid(uid.trim());
                if (c != null && c.getName() != null && !c.getName().trim().isEmpty()) {
                    return c.getName().trim();
                }
            } catch (Exception ignored) {
            }
        }
        String fallback = callsignFallback != null ? callsignFallback.trim() : "";
        if (!fallback.isEmpty()) {
            return fallback;
        }
        if (uid != null && uid.toUpperCase(Locale.US).startsWith(ANDROID_UID_PREFIX)) {
            String bare = uid.substring(ANDROID_UID_PREFIX.length());
            if (bare.matches("^[0-9A-F]{16}$")) {
                return uid;
            }
            return bare;
        }
        return uid != null ? uid : "";
    }

    public void scheduleContactMergeForNetworkContact(String contactUid) {
        if (contactUid == null || contactUid.trim().isEmpty()) {
            return;
        }
        try {
            Contact c = Contacts.getInstance().getContactByUuid(contactUid.trim());
            if (!(c instanceof IndividualContact)) {
                return;
            }
            IndividualContact ic = (IndividualContact) c;
            String name = ic.getName();
            if (name == null || name.trim().isEmpty()) {
                return;
            }
            String key = name.trim().toUpperCase(Locale.US);
            Runnable existing = pendingMergeByCallsign.remove(key);
            if (existing != null) {
                contactMergeHandler.removeCallbacks(existing);
            }
            Runnable task = () -> {
                pendingMergeByCallsign.remove(key);
                collapseDuplicateContactsForCallsign(name, null);
                try {
                    IndividualContact merged = resolveMergedContact(name, contactUid.trim());
                    if (merged != null) {
                        if (com.uvpro.plugin.contacts.ContactReachability.isPolicyEnabled(
                                MapView.getMapView() != null
                                        ? MapView.getMapView().getContext() : null)) {
                            com.uvpro.plugin.contacts.ContactReachability.applyContactCommsPolicy(
                                    merged, mergeRoutingBridge);
                        } else if (needsNativeContactRepair(merged)) {
                            preferNativeContactAction(merged);
                        }
                        finishContactMerge(merged, name);
                        merged.dispatchChangeEvent();
                    }
                } catch (Exception ignored) {
                }
            };
            pendingMergeByCallsign.put(key, task);
            contactMergeHandler.postDelayed(task, 150L);
        } catch (Exception e) {
            Log.w(TAG, "scheduleContactMergeForNetworkContact failed uid=" + contactUid, e);
        }
    }

    /** Debounced full-list merge when ATAK adds contacts (e.g. Wi‑Fi SA after RF row exists). */
    private void scheduleCollapseAllCallsignDuplicatesDebounced() {
        if (pendingCollapseAllTask != null) {
            contactMergeHandler.removeCallbacks(pendingCollapseAllTask);
        }
        pendingCollapseAllTask = () -> {
            pendingCollapseAllTask = null;
            collapseAllCallsignAliasDuplicates();
        };
        contactMergeHandler.postDelayed(pendingCollapseAllTask, 350L);
    }

    private static IndividualContact resolveMergedContact(String callsignRaw, String preferredUid) {
        try {
            Contacts contacts = Contacts.getInstance();
            Contact byCallsign = findContactByCallsignVariants(contacts, callsignRaw);
            if (byCallsign instanceof IndividualContact) {
                return (IndividualContact) byCallsign;
            }
            if (preferredUid != null && !preferredUid.trim().isEmpty()) {
                Contact byUid = contacts.getContactByUuid(preferredUid.trim());
                if (byUid instanceof IndividualContact) {
                    return (IndividualContact) byUid;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Restore native GeoChat action when TAK/Wi-Fi connectors are present.
     * Pure RF plugin contacts (plugin connector only) are left unchanged.
     */
    private static boolean shouldPreferNativeContactAction(IndividualContact ic) {
        if (ic == null) {
            return false;
        }
        if (ic.getConnector(GeoChatConnector.CONNECTOR_TYPE) != null
                || ic.getConnector(IpConnector.CONNECTOR_TYPE) != null) {
            return true;
        }
        return false;
    }

    private static boolean isOpaqueWifiDeviceUid(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return false;
        }
        String upper = uid.trim().toUpperCase(Locale.US);
        if (upper.startsWith(ANDROID_UID_PREFIX)) {
            upper = upper.substring(ANDROID_UID_PREFIX.length());
        }
        return OPAQUE_WIFI_DEVICE_UID.matcher(upper).matches();
    }

    private static boolean isSyntheticCallsignUid(String uid, String callsign) {
        if (uid == null || callsign == null) {
            return false;
        }
        String normalizedCall = callsign.trim().toUpperCase(Locale.US);
        if (normalizedCall.isEmpty()) {
            return false;
        }
        return uid.trim().equalsIgnoreCase(ANDROID_UID_PREFIX + normalizedCall);
    }

    private static int scorePreferredNativeContact(IndividualContact ic) {
        int score = 0;
        if (ic == null) {
            return score;
        }
        String uid = ic.getUID() != null ? ic.getUID().trim().toUpperCase(Locale.US) : "";
        String name = ic.getName() != null ? ic.getName().trim().toUpperCase(Locale.US) : "";
        if (ic.getConnector(GeoChatConnector.CONNECTOR_TYPE) != null) {
            score += 300;
        }
        if (ic.getConnector(IpConnector.CONNECTOR_TYPE) != null) {
            score += 100;
        }
        if (isOpaqueWifiDeviceUid(uid)) {
            score += 400;
        }
        if (isSyntheticCallsignUid(uid, name)) {
            score -= 250;
        }
        if (!uid.startsWith(ANDROID_UID_PREFIX)) {
            score += 50;
        }
        if (ic.getConnector(PluginConnector.CONNECTOR_TYPE) != null) {
            score -= 40;
        }
        if (name.contains("_")) {
            score += 25;
        }
        if (name.length() > 6) {
            score += 10;
        }
        return score;
    }

    /**
     * Collapse JESTER_25/JSTR25-style duplicates already present in the Contacts list.
     */
    public static void collapseAllCallsignAliasDuplicates() {
        try {
            Contacts contacts = Contacts.getInstance();
            java.util.List<Contact> all = contacts.getAllContacts();
            if (all == null || all.isEmpty()) {
                return;
            }
            LinkedHashSet<String> seenRadioKeys = new LinkedHashSet<>();
            for (Contact c : all) {
                if (!(c instanceof IndividualContact)) {
                    continue;
                }
                IndividualContact ic = (IndividualContact) c;
                String name = ic.getName() != null ? ic.getName().trim() : "";
                if (name.isEmpty()) {
                    continue;
                }
                String radioKey = radioCallsignKey(name);
                if (radioKey.isEmpty() || radioKey.length() < 4) {
                    continue;
                }
                if (!seenRadioKeys.add(radioKey)) {
                    continue;
                }
                collapseDuplicateContactsForCallsign(name, null);
                String compressed = CallsignUtil.toRadioCallsign(name);
                if (compressed != null && !compressed.equalsIgnoreCase(name)) {
                    collapseDuplicateContactsForCallsign(compressed, null);
                }
            }
            removeOrphanSyntheticRadioContacts();
            repairAllNativeContactActions();
            com.uvpro.plugin.contacts.ContactReachability.applyAllContactCommsPolicies(
                    mergeRoutingBridge);
        } catch (Exception e) {
            Log.w(TAG, "collapseAllCallsignAliasDuplicates failed", e);
        }
    }

    /**
     * Drop abbreviated synthetic rows (e.g. {@code SMKY15}/{@code ANDROID-SMKY15}) when a fuller
     * peer with the same radio key already exists (e.g. {@code SMOKEY_15}).
     */
    static void removeOrphanSyntheticRadioContacts() {
        try {
            Contacts contacts = Contacts.getInstance();
            java.util.List<Contact> all = contacts.getAllContacts();
            if (all == null || all.size() < 2) {
                return;
            }
            java.util.ArrayList<IndividualContact> orphans = new java.util.ArrayList<>();
            for (Contact c : all) {
                if (!(c instanceof IndividualContact)) {
                    continue;
                }
                IndividualContact ic = (IndividualContact) c;
                if (isOrphanSyntheticRadioContact(ic, all)) {
                    orphans.add(ic);
                }
            }
            for (IndividualContact orphan : orphans) {
                Log.d(TAG, "Removing orphan synthetic radio contact uid=" + orphan.getUID()
                        + " callsign=" + orphan.getName());
                contacts.removeContact(orphan);
            }
        } catch (Exception e) {
            Log.w(TAG, "removeOrphanSyntheticRadioContacts failed", e);
        }
    }

    private static boolean isOrphanSyntheticRadioContact(IndividualContact ic,
                                                         java.util.List<Contact> all) {
        if (ic == null || all == null) {
            return false;
        }
        String uid = ic.getUID();
        if (isAprsContactUid(uid)) {
            return false;
        }
        String name = ic.getName() != null ? ic.getName().trim().toUpperCase(Locale.US) : "";
        if (name.isEmpty() || !isSyntheticCallsignUid(uid, name)) {
            return false;
        }
        if (name.contains("_") || name.contains("-")) {
            return false;
        }
        String radioKey = radioCallsignKey(name);
        if (radioKey.isEmpty() || radioKey.length() < 4) {
            return false;
        }
        int selfScore = scorePreferredNativeContact(ic);
        for (Contact c : all) {
            if (c == ic || !(c instanceof IndividualContact)) {
                continue;
            }
            IndividualContact other = (IndividualContact) c;
            String otherName = other.getName() != null ? other.getName().trim().toUpperCase(Locale.US) : "";
            if (otherName.isEmpty()) {
                continue;
            }
            if (!radioKey.equals(radioCallsignKey(otherName))) {
                continue;
            }
            if (scorePreferredNativeContact(other) > selfScore) {
                return true;
            }
        }
        return false;
    }

    private static NetConnectString buildNativeConnectorSeed(String callsign) {
        NetConnectString ncs = new NetConnectString("stcp", "*", -1);
        if (callsign != null && !callsign.trim().isEmpty()) {
            ncs.setCallsign(callsign.trim().toUpperCase(Locale.US));
        }
        return ncs;
    }

    /**
     * Open ATAK's native GeoChat conversation for a known contact UID.
     */
    public static boolean openNativeChatConversation(String contactUid) {
        if (contactUid == null || contactUid.trim().isEmpty()) {
            return false;
        }
        try {
            Contact c = Contacts.getInstance().getContactByUuid(contactUid.trim());
            if (c instanceof IndividualContact) {
                ChatManagerMapComponent.getInstance().openConversation((IndividualContact) c, true);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "openNativeChatConversation failed uid=" + contactUid, e);
        }
        return false;
    }

    public static void markAprsContactUid(String contactUid) {
        if (contactUid == null) {
            return;
        }
        String uid = contactUid.trim().toUpperCase(Locale.US);
        if (uid.isEmpty()) {
            return;
        }
        if (!uid.startsWith(ANDROID_UID_PREFIX)) {
            uid = ANDROID_UID_PREFIX + uid;
        }
        aprsConversationUids.add(uid);
    }

    /**
     * Ensure a Contacts row for APRS chat using the FCC callsign from the RF packet.
     * Does not merge with ATAK tactical callsign or Wi‑Fi contacts.
     */
    public static String ensureAprsPluginChatContact(String fccCallsignRaw) {
        String callsign = AprsMessageTransmitter.normalizeAddressee(fccCallsignRaw);
        if (callsign.isEmpty()) {
            return "";
        }
        String uid = syntheticAndroidUid(callsign);
        if (uid.isEmpty()) {
            return "";
        }
        try {
            Contacts contacts = Contacts.getInstance();
            Contact existing = contacts.getContactByUuid(uid);
            if (existing instanceof IndividualContact) {
                markAprsContactUid(uid);
                return uid;
            }
            if (existing != null) {
                markAprsContactUid(uid);
                return uid;
            }

            MapItem item = null;
            MapView mv = MapView.getMapView();
            if (mv != null && mv.getRootGroup() != null) {
                item = mv.getRootGroup().deepFindUID(uid);
            }

            IndividualContact c = new IndividualContact(callsign, uid, item,
                    buildNativeConnectorSeed(callsign));
            contacts.addContact(c);
            markAprsContactUid(uid);
            Log.i(TAG, "Created APRS chat contact " + callsign + " uid=" + uid);
            return uid;
        } catch (Exception e) {
            Log.e(TAG, "ensureAprsPluginChatContact failed callsign=" + fccCallsignRaw, e);
            return "";
        }
    }

    private void registerAprsChatContactRouting(String fromCallsignRaw, String senderUid) {
        if (cotBridge == null || senderUid == null || senderUid.isEmpty()) {
            return;
        }
        cotBridge.registerBtechContactUid(senderUid);
        String aprsCall = AprsMessageTransmitter.normalizeAddressee(fromCallsignRaw);
        if (!aprsCall.isEmpty()) {
            cotBridge.registerBtechContactId(aprsCall, senderUid);
        }
        if (fromCallsignRaw != null && !fromCallsignRaw.trim().isEmpty()
                && !fromCallsignRaw.trim().equalsIgnoreCase(aprsCall)) {
            cotBridge.registerBtechContactId(fromCallsignRaw.trim(), senderUid);
        }
    }

    private static boolean isAprsContactUid(String contactUid) {
        if (contactUid == null) {
            return false;
        }
        String uid = contactUid.trim().toUpperCase(Locale.US);
        if (uid.isEmpty()) {
            return false;
        }
        if (!uid.startsWith(ANDROID_UID_PREFIX)) {
            uid = ANDROID_UID_PREFIX + uid;
        }
        return aprsConversationUids.contains(uid);
    }

    /** Public wrapper for CotBridge inbound GeoChat seeding. */
    public static boolean isAprsChatContactUid(String contactUid) {
        if (isAprsContactUid(contactUid)) {
            return true;
        }
        return looksLikeAprsContactUid(contactUid);
    }

    /** {@code ANDROID-N7JDI-9} style FCC contact UIDs (not tactical {@code SMOKEY_15}). */
    private static boolean looksLikeAprsContactUid(String contactUid) {
        if (contactUid == null) {
            return false;
        }
        String uid = contactUid.trim().toUpperCase(Locale.US);
        if (uid.startsWith(ANDROID_UID_PREFIX)) {
            uid = uid.substring(ANDROID_UID_PREFIX.length());
        }
        if (uid.isEmpty() || uid.contains("_")) {
            return false;
        }
        String base = uid;
        int dash = uid.indexOf('-');
        if (dash > 0) {
            base = uid.substring(0, dash);
        }
        return SettingsFragment.isValidAprsCallsign(base);
    }

    /**
     * True when an inbound APRS message addressee targets this device/operator.
     * Accepts direct local callsign forms and APRS-configured callsign+SSID forms.
     */
    public boolean shouldAcceptAprsDestination(String toCallsignRaw) {
        String to = normalizeAprsDestination(toCallsignRaw);
        if (to.isEmpty()) {
            return false;
        }
        if ("ALL".equals(to) || to.startsWith("BLN")) {
            return true;
        }

        Set<String> accepted = new HashSet<>();
        collectLocalStationDestinationVariants(accepted);
        return accepted.contains(to);
    }

    private static void addAprsDestinationVariants(Set<String> out, String raw) {
        String n = normalizeAprsDestination(raw);
        if (n.isEmpty()) {
            return;
        }
        out.add(n);
        try {
            String radio = com.uvpro.plugin.util.CallsignUtil.toRadioCallsign(n);
            String rn = normalizeAprsDestination(radio);
            if (!rn.isEmpty()) {
                out.add(rn);
            }
        } catch (Exception ignored) {
        }
        int dash = n.indexOf('-');
        if (dash > 0) {
            String base = normalizeAprsDestination(n.substring(0, dash));
            if (!base.isEmpty()) {
                out.add(base);
                try {
                    String radioBase = com.uvpro.plugin.util.CallsignUtil.toRadioCallsign(base);
                    String rb = normalizeAprsDestination(radioBase);
                    if (!rb.isEmpty()) {
                        out.add(rb);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String normalizeAprsDestination(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toUpperCase(Locale.US);
        if (s.isEmpty()) {
            return "";
        }
        if (s.startsWith(ANDROID_UID_PREFIX)) {
            s = s.substring(ANDROID_UID_PREFIX.length());
        }
        s = s.replaceAll("[^A-Z0-9\\-]", "");
        if (s.length() > 9) {
            s = s.substring(0, 9);
        }
        return s;
    }

    /**
     * Register broadcast receiver to intercept outgoing ATAK chat
     * and relay to radio.
     */
    public void startOutgoingRelay() {
        chatReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleOutgoingChat(intent);
            }
        };

        // Register for GeoChat send events.
        // Some ATAK builds emit chat sends via SEND_MESSAGE (intent extras),
        // and/or via COT_PLACED (with CoT XML).
        AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction("com.atakmap.android.maps.COT_PLACED");
        filter.addAction(ACTION_CHAT_SEND);
        filter.addAction(ACTION_PLUGIN_CONTACT_GEOCHAT_SEND);
        AtakBroadcast.getInstance().registerReceiver(chatReceiver, filter);

        // Track currently open GeoChat conversation to suppress unread badge increments when
        // the user is actively viewing that conversation.
        try {
            chatOpenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    if (!"com.atakmap.android.OPEN_GEOCHAT".equals(intent.getAction())) return;
                    // ATAK (ChatManagerMapComponent) puts conversationId inside the
                    // parcelable "message" bundle, not as top-level intent extras — without
                    // this, opening GeoChat from the main chat menu never set openConversationId
                    // and the Contacts badge stayed stuck until Contacts pane opened chat.
                    String convo = null;
                    android.os.Bundle msgBundle = getOpenGeoChatMessageBundle(intent);
                    if (msgBundle != null) {
                        convo = msgBundle.getString("conversationId");
                        if (convo == null || convo.isEmpty()) {
                            convo = msgBundle.getString("chatroom");
                        }
                    }
                    if (convo == null || convo.isEmpty()) {
                        convo = intent.getStringExtra("conversationId");
                    }
                    if (convo == null || convo.isEmpty()) {
                        convo = intent.getStringExtra("chatroom");
                    }
                    if (convo == null || convo.isEmpty()) {
                        convo = intent.getStringExtra("id");
                    }
                    if (convo != null && !convo.isEmpty()) {
                        openConversationId = convo;
                        if (convo.startsWith("ANDROID-")) {
                            clearUnreadLocal(convo);
                            scheduleClearUnreadWhenGeoChatFragmentVisible(convo);
                        }
                    }
                }
            };
            AtakBroadcast.DocumentedIntentFilter openF =
                    new AtakBroadcast.DocumentedIntentFilter();
            openF.addAction("com.atakmap.android.OPEN_GEOCHAT");
            AtakBroadcast.getInstance().registerReceiver(chatOpenReceiver, openF);
        } catch (Exception ignored) {
        }

        try {
            chatClosedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String a = intent.getAction();
                    if (!"com.atakmap.chat.chatroom_closed".equals(a)
                            && !"CHAT_ROOM_DROPDOWN_CLOSED".equals(a)) {
                        return;
                    }
                    openConversationId = null;
                }
            };
            AtakBroadcast.DocumentedIntentFilter closedF =
                    new AtakBroadcast.DocumentedIntentFilter();
            closedF.addAction("com.atakmap.chat.chatroom_closed");
            closedF.addAction("CHAT_ROOM_DROPDOWN_CLOSED");
            AtakBroadcast.getInstance().registerReceiver(chatClosedReceiver, closedF);
        } catch (Exception ignored) {
        }

        // Clear plugin badge when ATAK marks a message read (chat menu path, not only Contacts).
        try {
            chatMarkReadReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    if (!"com.atakmap.chat.markmessageread".equals(intent.getAction())) return;
                    android.os.Bundle b = intent.getBundleExtra("chat_bundle");
                    if (b == null) return;
                    String convo = b.getString("conversationId");
                    if (convo == null || convo.isEmpty()) return;
                    if (convo.startsWith("ANDROID-")) {
                        clearUnreadLocal(convo);
                    }
                    Integer wireMid = extractWireMidFromMarkReadBundle(b);
                    String uid = convo.trim();
                    if (wireMid != null && cotBridge != null && cotBridge.isBtechContactUid(uid)) {
                        sendRadioChatAck(wireMid, UVProPacket.ACK_KIND_READ);
                    }
                }
            };
            AtakBroadcast.DocumentedIntentFilter markRead =
                    new AtakBroadcast.DocumentedIntentFilter();
            markRead.addAction("com.atakmap.chat.markmessageread");
            AtakBroadcast.getInstance().registerReceiver(chatMarkReadReceiver, markRead);
        } catch (Exception ignored) {
        }

        try {
            contactsUnreadSyncListener = new Contacts.OnContactsChangedListener() {
                @Override
                public void onContactsSizeChange(Contacts contacts) {
                    scheduleCollapseAllCallsignDuplicatesDebounced();
                }

                @Override
                public void onContactChanged(String contactUid) {
                    if (contactUid == null || contactUid.trim().isEmpty()) {
                        return;
                    }
                    scheduleContactMergeForNetworkContact(contactUid);
                    if (!contactUid.startsWith("ANDROID-")) {
                        return;
                    }
                    try {
                        Contact c = Contacts.getInstance().getContactByUuid(contactUid);
                        if (c == null) {
                            return;
                        }
                        int now = c.getUnreadCount();
                        Integer prev = lastAtakUnreadByUid.put(contactUid, now);
                        if (prev != null && prev > 0 && now == 0) {
                            Log.d(TAG, "ATAK native unread cleared for " + contactUid
                                    + " — clearing plugin Contacts badge");
                            clearUnreadLocal(contactUid);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "contacts unread sync", e);
                    }
                }
            };
            Contacts.getInstance().addListener(contactsUnreadSyncListener);
        } catch (Exception e) {
            Log.w(TAG, "Could not register Contacts unread sync listener", e);
        }

        registerAtakChatMessageListenerWhenReady(0);

        if (mapView != null) {
            mapView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!disposed) {
                        GeoChatUnreadReconciler.reconcileAll();
                    }
                }
            }, 1500L);
        }

        Log.d(TAG, "Outgoing chat relay started");
    }

    private void registerAtakChatMessageListenerWhenReady(final int attempt) {
        if (disposed || atakChatMessageListener != null) {
            return;
        }
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (disposed || atakChatMessageListener != null) {
                    return;
                }
                try {
                    ChatManagerMapComponent cmmc = ChatManagerMapComponent.getInstance();
                    if (cmmc != null) {
                        atakChatMessageListener =
                                new ChatManagerMapComponent.ChatMessageListener() {
                                    @Override
                                    public void chatMessageReceived(android.os.Bundle bundle) {
                                        maybeClearPluginUnreadWhenGeoChatUiShows(bundle);
                                    }
                                };
                        cmmc.addChatMessageListener(atakChatMessageListener);
                        Log.d(TAG, "Registered ChatManagerMapComponent.ChatMessageListener");
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "ChatManagerMapComponent listener registration", e);
                }
                if (!disposed && attempt < 12 && mapView != null) {
                    mapView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            registerAtakChatMessageListenerWhenReady(attempt + 1);
                        }
                    }, 500L);
                }
            }
        };
        if (mapView != null) {
            mapView.post(r);
        } else {
            r.run();
        }
    }

    /**
     * When ATAK has finished routing a chat line, clear our Contacts badge if that
     * conversation's {@link com.atakmap.android.chat.ConversationFragment} is on-screen
     * (main GeoChat path — {@code Contact.getUnreadCount} often stays 0 for plugin UIDs).
     */
    private void maybeClearPluginUnreadWhenGeoChatUiShows(android.os.Bundle messageBundle) {
        if (disposed || messageBundle == null) return;
        String convo = messageBundle.getString("conversationId");
        if (convo == null || !convo.startsWith("ANDROID-")) {
            return;
        }
        postClearUnreadIfFragmentVisible(convo, 0);
        postClearUnreadIfFragmentVisible(convo, 120);
        postClearUnreadIfFragmentVisible(convo, 400);
    }

    private void postClearUnreadIfFragmentVisible(final String conversationId, long delayMs) {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (disposed) {
                    return;
                }
                try {
                    if (isGeoChatConversationFragmentVisible(conversationId)) {
                        clearUnreadLocal(conversationId);
                        Log.d(TAG, "Plugin unread cleared (GeoChat fragment visible) " + conversationId);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "clear unread fragment check", e);
                }
            }
        };
        if (mapView != null) {
            mapView.postDelayed(r, delayMs);
        }
    }

    /** After OPEN_GEOCHAT, fragment creation can lag; poll briefly until it is resumed. */
    private void scheduleClearUnreadWhenGeoChatFragmentVisible(String conversationId) {
        postClearUnreadIfFragmentVisible(conversationId, 0);
        postClearUnreadIfFragmentVisible(conversationId, 80);
        postClearUnreadIfFragmentVisible(conversationId, 250);
        postClearUnreadIfFragmentVisible(conversationId, 700);
    }

    /**
     * ATAK keeps {@code ChatManagerMapComponent.fragmentMap} (conversationId → fragment).
     * Not a public API — reflect once per check, catch failures across ATAK versions.
     */
    private boolean isGeoChatConversationFragmentVisible(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return false;
        }
        try {
            Field f = ChatManagerMapComponent.class.getDeclaredField("fragmentMap");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> fm = (Map<String, Object>) f.get(null);
            if (fm == null) {
                return false;
            }
            Object o = fm.get(conversationId);
            if (!(o instanceof Fragment)) {
                return false;
            }
            Fragment fr = (Fragment) o;
            return fr.isResumed() && fr.isVisible();
        } catch (Throwable t) {
            return false;
        }
    }

    private void clearUnreadLocal(String conversationId) {
        if (conversationId == null) return;
        pendingUnreadConversationUids.remove(conversationId);
        UVProContactHandler.clearUnread(conversationId);
        GeoChatUnreadReconciler.markConversationRead(conversationId);
        if (pendingUnreadConversationUids.isEmpty()) {
            unreadVisibilityPollRunning = false;
        }
        drainAndSendReadAcks(conversationId);
    }

    /** Record an inbound wire mid that should be READ-acked when the user opens this conversation. */
    private void addPendingReadAck(String conversationUid, int wireMid) {
        Set<Integer> existing = pendingReadAcksByConversation.get(conversationUid);
        if (existing == null) {
            Set<Integer> fresh = ConcurrentHashMap.newKeySet();
            existing = pendingReadAcksByConversation.putIfAbsent(conversationUid, fresh);
            if (existing == null) existing = fresh;
        }
        existing.add(wireMid);
    }

    /** Send READ ACKs over RF for all pending wire mids for this conversation, then clear them. */
    private void drainAndSendReadAcks(String conversationId) {
        if (conversationId == null || !conversationId.startsWith("ANDROID-")) return;
        if (cotBridge == null || !cotBridge.isBtechContactUid(conversationId)) return;
        Set<Integer> mids = pendingReadAcksByConversation.remove(conversationId);
        if (mids == null || mids.isEmpty()) return;
        for (Integer mid : mids) {
            Log.d(TAG, "Sending READ ACK mid=" + mid + " conversation=" + conversationId);
            sendRadioChatAck(mid, UVProPacket.ACK_KIND_READ);
        }
    }

    private void startUnreadVisibilityPollIfNeeded() {
        if (disposed || unreadVisibilityPollRunning || mapView == null) {
            return;
        }
        unreadVisibilityPollRunning = true;
        mapView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (disposed) {
                    unreadVisibilityPollRunning = false;
                    return;
                }
                if (pendingUnreadConversationUids.isEmpty()) {
                    unreadVisibilityPollRunning = false;
                    return;
                }
                try {
                    // Iterate snapshot to avoid concurrent modification churn.
                    for (String uid : pendingUnreadConversationUids.toArray(new String[0])) {
                        if (uid == null) continue;
                        if (isGeoChatConversationFragmentVisible(uid)) {
                            clearUnreadLocal(uid);
                            Log.d(TAG, "Plugin unread cleared (poll visible) " + uid);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "unread visibility poll", e);
                }
                // Keep polling while there are pending unread threads.
                if (!pendingUnreadConversationUids.isEmpty()) {
                    mapView.postDelayed(this, 500L);
                } else {
                    unreadVisibilityPollRunning = false;
                }
            }
        }, 200L);
    }

    private static android.os.Bundle getMessageBundleExtra(Intent intent) {
        if (intent == null) return null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                return intent.getParcelableExtra("MESSAGE",
                        android.os.Bundle.class);
            }
            return intent.getParcelableExtra("MESSAGE");
        } catch (Exception e) {
            return null;
        }
    }

    /** Bundle from {@code com.atakmap.android.OPEN_GEOCHAT} (key {@code "message"}). */
    private static android.os.Bundle getOpenGeoChatMessageBundle(Intent intent) {
        if (intent == null) return null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                return intent.getParcelableExtra("message", android.os.Bundle.class);
            }
            android.os.Parcelable p = intent.getParcelableExtra("message");
            return p instanceof android.os.Bundle ? (android.os.Bundle) p : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Outbound GeoChat for plugin-connector contacts: {@code MESSAGE} bundle from
     * ChatManager → GeoChatService (conversationId / message keys).
     *
     * @return true if handled (relay attempted for a matching BTECH destination)
     */
    boolean relayPluginGeoChatMessageBundle(android.os.Bundle b) {
        if (cotBridge == null || b == null) return false;

        String conversationId = b.getString("conversationId");
        String msg = b.getString("message");
        if (msg == null || msg.isEmpty()) {
            return false;
        }

        // ACTION_PLUGIN_CONTACT_GEOCHAT_SEND is emitted only for destinations carrying
        // our PluginConnector; do not gate on conversationId (group UUIDs are valid).
        if (GeoChatContactListHelper.bundleIsGroupContactSync(b)) {
            Log.i(TAG, "Plugin GeoChat bundle contains paths; relaying compact RF chat fallback");
        }

        if (isAprsChatContactUid(conversationId)) {
            if (!SettingsFragment.isValidAprsCallsign(SettingsFragment.getAprsCallsign(pluginContext))) {
                postAprsCallsignWarning();
                return true;
            }
            String to = AprsMessageTransmitter.normalizeAddressee(conversationId);
            if (to.isEmpty()) {
                Log.w(TAG, "APRS relay blocked (invalid chat destination): " + conversationId);
                return true;
            }
            boolean ok = AprsMessageTransmitter.sendMessage(pluginContext, btManager, to, msg);
            if (!ok) {
                Log.w(TAG, "APRS relay failed for chat destination: " + to);
            }
            return true;
        }

        String lineUid = extractGeoChatLineUidFromBundle(b);
        if (lineUid == null) {
            maybeLogPluginGeoChatBundleKeysMissingUid(b);
        }
        if (skipIfDuplicateOutboundGeoChatLine(lineUid)) {
            return true;
        }

        if (isLikelyGroupConversationThread(conversationId)) {
            String wrapped = wrapGatewayMessage("", conversationId, lineUid, msg);
            Log.i(TAG, "Plugin GeoChat group bundle → compact gateway relay lineUid=" + lineUid);
            sendChatOverRadio(localCallsign(), conversationId, wrapped, lineUid);
            return true;
        }

        String room = "All Chat Rooms";
        if (conversationId != null) {
            String cid = conversationId.trim();
            if (!cid.isEmpty() && !"All Chat Rooms".equalsIgnoreCase(cid)) {
                if (cid.startsWith("ANDROID-")) {
                    room = cid.substring("ANDROID-".length());
                } else {
                    room = cid;
                }
            }
        }

        Log.d(TAG, "Relay outgoing plugin-contact GeoChat to radio room=" + room
                + " lineUid=" + lineUid);
        String outbound = msg;
        String wireRoom = room;
        if (isLikelyGroupConversationThread(conversationId)) {
            // TYPE_CHAT room is 6 bytes on-wire; preserve full group conversationId in payload.
            outbound = wrapGatewayMessage("", conversationId, lineUid, msg);
        } else if (!ALL_CHAT_ROOMS.equalsIgnoreCase(room) && !outbound.startsWith(GW_PREFIX)) {
            // Mesh-node DM: send as a native MeshCore contact message (pubkey DM), not the
            // proprietary 0xFF01 AX.25 datagram that native clients reject.
            if (trySendNativeMeshDm(conversationId, room, msg)) {
                return true;
            }
            String dmWireDest = resolveRfWireDestination(conversationId, room);
            if (!dmWireDest.isEmpty()) {
                outbound = wrapGatewayMessage(dmWireDest, room, lineUid, msg);
                wireRoom = dmWireDest;
            }
        }
        sendChatOverRadio(localCallsign(), wireRoom, outbound, lineUid);
        return true;
    }

    private void postAprsCallsignWarning() {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    android.widget.Toast.makeText(pluginContext,
                            "Set a valid APRS callsign in Edit APRS Settings first.",
                            android.widget.Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            Log.w(TAG, "Could not show APRS callsign warning", e);
        }
    }

    /**
     * For inbound APRS messages with message IDs ({@code {...}}), send {@code ackNN}.
     */
    public boolean sendAprsAckIfRequested(String toCallsignRaw, String messageIdRaw) {
        String id = messageIdRaw != null ? messageIdRaw.trim() : "";
        if (id.isEmpty()) {
            return false;
        }
        if (id.length() > 5) {
            id = id.substring(0, 5);
        }
        boolean ok = AprsMessageTransmitter.sendAcknowledgement(
                pluginContext, btManager, toCallsignRaw, id);
        if (ok) {
            Log.d(TAG, "Auto-sent APRS ack id=" + id + " to " + toCallsignRaw);
        } else {
            Log.d(TAG, "APRS ack not sent id=" + id + " to " + toCallsignRaw);
        }
        return ok;
    }

    /**
     * Handle an outgoing chat intent from ATAK.
     */
    private void handleOutgoingChat(Intent intent) {
        if (!relayOutgoing) return;
        if (btManager == null || !btManager.isConnected()) {
            return;
        }
        if (intent == null) return;

        try {
            final String action = intent.getAction();

            // Path plugin-contact GeoChat (ChatManager sends Intent(action=connectionString, MESSAGE=bundle)).
            if (ACTION_PLUGIN_CONTACT_GEOCHAT_SEND.equals(action)) {
                android.os.Bundle messageBundle = getMessageBundleExtra(intent);
                if (messageBundle != null) {
                    if (relayPluginGeoChatMessageBundle(messageBundle)) {
                        return;
                    }
                }
            }

            // Path A: chat send intent with explicit extras (preferred if present).
            if (ACTION_CHAT_SEND.equals(action)) {
                String message = intent.getStringExtra("message");
                String chatRoom = intent.getStringExtra("chatroom");
                String toUid = intent.getStringExtra("toUID");
                if (toUid == null) toUid = intent.getStringExtra("toUid");
                if (toUid == null) toUid = intent.getStringExtra("uid");
                if (chatRoom == null) chatRoom = intent.getStringExtra("room");
                if (toUid != null && !toUid.trim().isEmpty()) {
                    String trimmed = toUid.trim();
                    if (trimmed.toUpperCase(Locale.US).startsWith(ANDROID_UID_PREFIX)) {
                        String cs = trimmed.substring(ANDROID_UID_PREFIX.length());
                        ensurePluginChatContactExactUid(cs, trimmed);
                    }
                }

                // Log intent shape for field discovery (keep it short).
                try {
                    android.os.Bundle extras = intent.getExtras();
                    if (extras != null) {
                        StringBuilder keys = new StringBuilder();
                        for (String k : extras.keySet()) {
                            if (keys.length() > 0) keys.append(",");
                            keys.append(k);
                        }
                        Log.d(TAG, "SEND_MESSAGE extras keys: " + keys);
                    }
                } catch (Exception ignored) {
                }

                if (message == null || message.isEmpty()) {
                    // Some builds use "text" instead of "message"
                    message = intent.getStringExtra("text");
                }
                if (chatRoom == null || chatRoom.isEmpty()) {
                    chatRoom = "All Chat Rooms";
                }
                boolean allChatRooms = "All Chat Rooms".equalsIgnoreCase(chatRoom.trim());

                // Only relay when the destination is a plugin-created contact.
                // SEND_MESSAGE extras vary by build; ANDROID-VETTE1 must match callsign VETTE1.
                boolean shouldRelay =
                        cotBridge != null && cotBridge.isBtechOutboundChatDestination(toUid,
                        chatRoom);
                if (!shouldRelay && cotBridge != null) {
                    String[] fallback =
                            {"destUID", "destinationUID", "recipientUID",
                                    "recipient", "destination", "to",
                                    "toCallsign"};
                    for (String k : fallback) {
                        String v = intent.getStringExtra(k);
                        if (cotBridge.isBtechOutboundChatDestination(v, null)) {
                            shouldRelay = true;
                            break;
                        }
                    }
                }
                if (!shouldRelay && allChatRooms) {
                    // User selected broadcast chat from ATAK UI.
                    shouldRelay = true;
                }
                boolean gatewayRelay = false;
                if (!shouldRelay) {
                    gatewayRelay = isGatewayRelayEnabled();
                    if (!gatewayRelay) return;
                }
                if (message == null || message.isEmpty()) return;

                String lineUid = extractGeoChatLineUidFromIntent(intent);
                Log.d(TAG, "Relaying outgoing chat (SEND_MESSAGE) to radio: " + message
                        + " lineUid=" + lineUid);
                if (gatewayRelay) {
                    if (isLikelyGroupConversationThread(chatRoom)) {
                        // Group messages are already relayed as full b-t-f by CotBridge paths.
                        // Gateway compact chat here causes duplicate/random individual threads.
                        Log.d(TAG, "Skipping gateway compact relay for group thread " + chatRoom);
                        return;
                    }
                    String wrapped = wrapGatewayMessage(toUid, chatRoom, lineUid, message);
                    String rfRoom = toUid != null && !toUid.isEmpty()
                            ? toUid
                            : chatRoom;
                    sendChatOverRadio(localCallsign(), rfRoom, wrapped, lineUid);
                } else {
                    String outbound = message;
                    String wireRoom = chatRoom;
                    if (!allChatRooms
                            && !isLikelyGroupConversationThread(chatRoom)
                            && !outbound.startsWith(GW_PREFIX)) {
                        String dmWireDest = resolveRfWireDestination(toUid, chatRoom);
                        if (!dmWireDest.isEmpty()) {
                            outbound = wrapGatewayMessage(dmWireDest, chatRoom, lineUid, message);
                            wireRoom = dmWireDest;
                        }
                    }
                    sendChatOverRadio(localCallsign(), wireRoom, outbound, lineUid);
                }
                return;
            }

            String cotXml = intent.getStringExtra("xml");
            if (cotXml == null) return;

            CotEvent event = CotEvent.parse(cotXml);
            if (event == null) return;

            // Only relay GeoChat CoT messages when the destination is a plugin-created
            // (radio) contact. This prevents relaying all chat over radio when the user
            // chats with network contacts.
            if (cotBridge != null && !cotBridge.shouldRelayGeoChatToRadio(event)) {
                return;
            }

            // Extract message from remarks
            CotDetail detail = event.getDetail();
            if (detail == null) return;

            String message = null;
            String chatRoom = "All Chat Rooms";

            // Find remarks element for the message text
            CotDetail remarks = detail.getFirstChildByName(0, "remarks");
            if (remarks != null) {
                message = remarks.getInnerText();
            }

            // Find __chat element for room info
            CotDetail chat = detail.getFirstChildByName(0, "__chat");
            chatRoom = chatRoomOf(chat, chatRoom);

            if (message == null || message.isEmpty()) {
                return;
            }

            String lineUid = resolveOutboundGeoChatLineUid(event);
            Log.d(TAG, "Relaying outgoing chat (COT intent) to radio: " + message
                    + " lineUid=" + lineUid);
            relayOutboundGeoChatCot(event);

        } catch (Exception e) {
            Log.e(TAG, "Error handling outgoing chat", e);
        }
    }

    /**
     * Outbound GeoChat to radio contacts: full slotted CoT for group/contact-list sync,
     * otherwise compact {@code TYPE_CHAT} for ACK correlation.
     */
    public void relayOutboundGeoChatCot(CotEvent event) {
        if (!relayOutgoing) {
            return;
        }
        if (btManager == null || !btManager.isConnected()) {
            return;
        }
        if (event == null || !"b-t-f".equals(event.getType())) {
            return;
        }
        if (cotBridge == null || !cotBridge.shouldRelayGeoChatToRadio(event)) {
            return;
        }

        String lineUid = resolveOutboundGeoChatLineUid(event);
        if (skipIfDuplicateOutboundGeoChatLine(lineUid)) {
            return;
        }

        if (GeoChatContactListHelper.requiresFullCotRelay(event)) {
            Log.i(TAG, "Group/contact-list GeoChat → full CoT (brief stagger) uid=" + lineUid);
            cotBridge.scheduleSlottedGroupContactCotRelay(event);
            return;
        }

        if (tryRelayOutboundGeoChatAsAprs(event, lineUid)) {
            return;
        }

        relayOutboundGeoChatCotAsCompact(event);
    }

    /**
     * When GeoChat targets an APRS FCC contact ({@code ANDROID-N7JDI-9}), send over APRS KISS
     * instead of UV-PRO TYPE_CHAT so the receiver gets the sender's ham call, not tactical ID.
     */
    private boolean tryRelayOutboundGeoChatAsAprs(CotEvent event, String lineUid) {
        if (event == null) {
            return false;
        }
        CotDetail detail = event.getDetail();
        if (detail == null) {
            return false;
        }

        String message = null;
        String chatRoom = ALL_CHAT_ROOMS;
        CotDetail remarks = detail.getFirstChildByName(0, "remarks");
        if (remarks != null) {
            message = remarks.getInnerText();
        }
        CotDetail chat = detail.getFirstChildByName(0, "__chat");
        if (chat == null) {
            chat = detail.getFirstChildByName(0, "chat");
        }
        chatRoom = chatRoomOf(chat, chatRoom);
        if (message == null || message.isEmpty()) {
            return false;
        }

        String destHint = extractGeoChatDestinationHint(event, chatRoom);
        if (!isAprsChatDestination(chatRoom, destHint, event)) {
            return false;
        }
        if (!SettingsFragment.isValidAprsCallsign(SettingsFragment.getAprsCallsign(pluginContext))) {
            postAprsCallsignWarning();
            return true;
        }
        String destUid = resolveAprsGeoChatDestinationUid(chatRoom, destHint, event);
        String to = AprsMessageTransmitter.normalizeAddressee(
                destUid != null && !destUid.isEmpty() ? destUid : destHint);
        if (to.isEmpty()) {
            Log.w(TAG, "APRS GeoChat relay blocked (invalid destination)");
            return true;
        }
        Log.i(TAG, "Outbound GeoChat → APRS TX to " + to + " lineUid=" + lineUid);
        boolean ok = AprsMessageTransmitter.sendMessage(pluginContext, btManager, to, message);
        if (!ok) {
            Log.w(TAG, "APRS TX failed for GeoChat destination " + to);
        }
        return true;
    }

    private static boolean isAprsChatDestination(String chatRoom, String destHint, CotEvent event) {
        if (isAprsChatContactUid(chatRoom)) {
            return true;
        }
        if (isAprsChatContactUid(destHint)) {
            return true;
        }
        if (event != null) {
            String conv = GeoChatContactListHelper.extractConversationId(event);
            if (isAprsChatContactUid(conv)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveAprsGeoChatDestinationUid(String chatRoom, String destHint,
                                                           CotEvent event) {
        if (isAprsChatContactUid(chatRoom)) {
            return chatRoom.trim();
        }
        if (destHint != null && isAprsChatContactUid(destHint)) {
            return destHint.trim();
        }
        if (event != null) {
            String conv = GeoChatContactListHelper.extractConversationId(event);
            if (isAprsChatContactUid(conv)) {
                return conv.trim();
            }
        }
        return destHint != null ? destHint.trim() : "";
    }

    /**
     * PreSend / CommsLogger path: one compact TYPE_CHAT per outbound b-t-f with a registered
     * wire id for RF ACK correlation (delivered / read ticks).
     */
    public void relayOutboundGeoChatCotAsCompact(CotEvent event) {
        if (!relayOutgoing) {
            return;
        }
        if (btManager == null || !btManager.isConnected()) {
            return;
        }
        if (event == null || !"b-t-f".equals(event.getType())) {
            return;
        }
        if (cotBridge == null || !cotBridge.shouldRelayGeoChatToRadio(event)) {
            return;
        }

        String lineUid = resolveOutboundGeoChatLineUid(event);
        CotDetail detail = event.getDetail();
        if (detail == null) {
            return;
        }

        String message = null;
        String chatRoom = "All Chat Rooms";

        CotDetail remarks = detail.getFirstChildByName(0, "remarks");
        if (remarks != null) {
            message = remarks.getInnerText();
        }

        CotDetail chat = detail.getFirstChildByName(0, "__chat");
        if (chat == null) {
            chat = detail.getFirstChildByName(0, "chat");
        }
        chatRoom = chatRoomOf(chat, chatRoom);

        if (message == null || message.isEmpty()) {
            return;
        }

        if (tryRelayOutboundGeoChatAsAprs(event, lineUid)) {
            return;
        }

        String outbound = message;
        String rfRoom = chatRoom;
        if (!ALL_CHAT_ROOMS.equalsIgnoreCase(chatRoom)
                && !isLikelyGroupConversationThread(chatRoom)
                && !outbound.startsWith(GW_PREFIX)) {
            String destHint = extractGeoChatDestinationHint(event, chatRoom);
            // Mesh-node DM: send as a native MeshCore contact message (pubkey DM), not the
            // proprietary 0xFF01 AX.25 datagram that native clients reject.
            if (trySendNativeMeshDm(destHint, chatRoom, message)) {
                return;
            }
            String dmWireDest = resolveRfWireDestination(destHint, chatRoom);
            if (!dmWireDest.isEmpty()) {
                String aprsSender = isAprsChatDestination(chatRoom, destHint, event)
                        ? SettingsFragment.getAprsCallsign(pluginContext) : null;
                outbound = wrapGatewayMessage(dmWireDest, chatRoom, lineUid, aprsSender, message);
                rfRoom = dmWireDest;
            }
        }
        Log.d(TAG, "Relay outbound GeoChat (compact PreSend/CommsLogger) room=" + chatRoom
                + " lineUid=" + lineUid);
        sendChatOverRadio(localCallsign(), rfRoom, outbound, lineUid);
    }

    /**
     * Send a chat message over the radio link.
     */
    public void sendChatOverRadio(String sender, String room, String message) {
        sendChatOverRadio(sender, room, message, (String) null);
    }

    /**
     * @param originatingGeoChatLine when non-null (e.g. from {@code COT_PLACED}), associates the
     *                                 wire {@code messageId} with this GeoChat line UID for RF receipts.
     */
    public void sendChatOverRadio(String sender, String room, String message,
                                  CotEvent originatingGeoChatLine) {
        sendChatOverRadio(sender, room, message,
                originatingGeoChatLine == null ? null
                        : resolveOutboundGeoChatLineUid(originatingGeoChatLine));
    }

    /**
     * @param geoChatLineUidOrNull GeoChat line UID ({@code GeoChat....}); when set, RF ACKs can
     *                             update delivered/read state for that line.
     */
    public void sendChatOverRadio(String sender, String room, String message,
                                  String geoChatLineUidOrNull) {
        if (btManager == null || !btManager.isConnected()) {
            Log.w(TAG, "Not connected — cannot send chat");
            return;
        }
        if (btManager.isRadioSilenceEnabled()) {
            holdChatForSilence(sender, room, message, geoChatLineUidOrNull);
            return;
        }

        try {
            int wireMid = UVProPacket.allocateChatWireMessageId();
            String mappedLineUid = null;
            if (isLikelyChatLineUid(geoChatLineUidOrNull)) {
                mappedLineUid = normalizeLineUidForAck(geoChatLineUidOrNull, room);
                outboundWireMidToLocalLineUid.put(wireMid, mappedLineUid);
                trimOutboundAckMap();
                Log.d(TAG, "ACK map put mid=" + wireMid + " -> " + mappedLineUid);
            } else {
                Log.d(TAG, "ACK map skipped mid=" + wireMid + " (line uid missing/unreliable)");
            }

            String wireRoom = isBroadcastRoom(room) ? "ALL" : room;
            UVProPacket packet = UVProPacket.createChatPacket(
                    com.uvpro.plugin.util.CallsignUtil.toRadioCallsign(sender),
                    wireRoom, wireMid, message);

            byte[] packetBytes = packet.encode();
            // Encrypt if enabled
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                packetBytes = encryptionManager.encrypt(packetBytes);
                if (packetBytes == null) {
                    Log.e(TAG, "Encryption failed — aborting chat send");
                    return;
                }
            }
            Ax25Frame frame = Ax25Frame.createUVProFrame(
                    localCallsign(), 0, packetBytes);
            byte[] ax25 = frame.encode();

            Log.d(TAG, "Sending chat over radio: " + ax25.length + " bytes");
            btManager.sendKissFrame(ax25);

            if (isBroadcastRoom(room)) {
                // Net-wide chat never gets a DELIVERED ACK, so there is nothing to wait for.
                Log.d(TAG, "Broadcast chat sent mid=" + wireMid + " — no ACK expected, no retry");
                return;
            }

            // Register for retry watchdog — cancelled when DELIVERED ACK arrives.
            PendingOutboundChat pending =
                    new PendingOutboundChat(wireMid, sender, wireRoom, message, mappedLineUid);
            pendingOutboundChats.put(wireMid, pending);
            Log.d(TAG, "Outbound pending registered mid=" + wireMid + " room=" + wireRoom);
            scheduleRetryCheck(wireMid);
        } catch (Exception e) {
            Log.e(TAG, "Error sending chat over radio", e);
        }
    }

    /**
     * Inbound RF chat DELIVERED acks are for directed DMs only — not net-wide
     * All Chat Rooms / {@code ALL} / bulletin traffic.
     */
    public boolean shouldSendInboundDeliveredAck(String toCallsign) {
        return !isBroadcastRoom(toCallsign);
    }

    /**
     * GeoChat room named by a {@code __chat} element. ATAK localizes {@code chatroom} (French UI:
     * "Toutes les salles de chat") but keeps {@code id} = "All Chat Rooms" for the general room,
     * so the id decides broadcast; otherwise the display name is used as before.
     */
    private static String chatRoomOf(CotDetail chat, String fallback) {
        if (chat == null) {
            return fallback;
        }
        String id = chat.getAttribute("id");
        if (id != null && ALL_CHAT_ROOMS.equalsIgnoreCase(id.trim())) {
            return ALL_CHAT_ROOMS;
        }
        String room = chat.getAttribute("chatroom");
        return (room != null && !room.isEmpty()) ? room : fallback;
    }

    private static boolean isBroadcastRoom(String roomRaw) {
        if (roomRaw == null) {
            return true;
        }
        String room = roomRaw.trim();
        if (room.isEmpty()) {
            return true;
        }
        String upper = room.toUpperCase(Locale.US);
        if ("ALL".equals(upper) || ALL_CHAT_ROOMS.toUpperCase(Locale.US).equals(upper)) {
            return true;
        }
        if (upper.startsWith("ALL ")) {
            return true;
        }
        return upper.startsWith("BLN");
    }

    private String normalizeLineUidForAck(String lineUidRaw, String roomHint) {
        if (lineUidRaw == null) {
            return null;
        }
        String lineUid = lineUidRaw.trim();
        if (lineUid.startsWith("GeoChat.")) {
            return lineUid;
        }
        String senderUid = null;
        try {
            senderUid = MapView.getDeviceUid();
        } catch (Exception ignored) {
        }
        if (senderUid == null || senderUid.trim().isEmpty()) {
            senderUid = syntheticAndroidUid(localCallsign());
        }
        if (senderUid == null || senderUid.trim().isEmpty()) {
            senderUid = "ANDROID-UNKNOWN";
        }
        String thread = roomHint != null ? roomHint.trim() : "";
        if (thread.isEmpty()) {
            thread = ALL_CHAT_ROOMS;
        } else if (!ALL_CHAT_ROOMS.equalsIgnoreCase(thread)
                && !thread.toUpperCase(Locale.US).startsWith(ANDROID_UID_PREFIX)) {
            thread = syntheticAndroidUid(thread);
        }
        return "GeoChat." + senderUid + "." + thread + "." + lineUid;
    }

    /**
     * Build RF gateway envelope.
     * Format: {@code __UVGW__|wireDest|displayCallsign|lineUid|[aprsSenderCall|]message}
     * <ul>
     *   <li>{@code wireDest} — 6-char AX.25 address only (never shown in UI)</li>
     *   <li>{@code displayCallsign} — full destination callsign ({@code N7JDI-9})</li>
     *   <li>{@code aprsSenderCall} — optional sender FCC call when UV-PRO carries APRS chat</li>
     * </ul>
     */
    private static String wrapGatewayMessage(String wireDest, String displayCallsign,
                                           String lineUid, String message) {
        return wrapGatewayMessage(wireDest, displayCallsign, lineUid, null, message);
    }

    private static String wrapGatewayMessage(String wireDest, String displayCallsign,
                                           String lineUid, String aprsSenderCall,
                                           String message) {
        String wire = wireDest != null ? wireDest.trim() : "";
        String display = displayCallsign != null ? displayCallsign.trim() : "";
        String line = lineUid != null ? lineUid.trim() : "";
        String aprs = aprsSenderCall != null ? aprsSenderCall.trim().toUpperCase(Locale.US) : "";
        if (!aprs.isEmpty()) {
            return GW_PREFIX + wire + "|" + display + "|" + line + "|" + aprs + "|" + message;
        }
        return GW_PREFIX + wire + "|" + display + "|" + line + "|" + message;
    }

    private static GatewayWrapped parseGatewayWrappedMessage(String message) {
        if (message == null || !message.startsWith(GW_PREFIX)) {
            return null;
        }
        String rest = message.substring(GW_PREFIX.length());
        int p1 = rest.indexOf('|');
        if (p1 < 0) return null;
        int p2 = rest.indexOf('|', p1 + 1);
        if (p2 < 0) return null;
        String toUid = rest.substring(0, p1).trim();
        String room = rest.substring(p1 + 1, p2).trim();
        String afterRoom = rest.substring(p2 + 1);
        int p3 = afterRoom.indexOf('|');
        String lineUid = "";
        String aprsSenderCall = "";
        String body;
        if (p3 >= 0) {
            lineUid = afterRoom.substring(0, p3).trim();
            String afterLine = afterRoom.substring(p3 + 1);
            int p4 = afterLine.indexOf('|');
            if (p4 >= 0) {
                String field = afterLine.substring(0, p4).trim();
                String tail = afterLine.substring(p4 + 1);
                if (SettingsFragment.isValidAprsCallsign(field)
                        || (field.contains("-") && SettingsFragment.isValidAprsCallsign(
                                field.substring(0, field.indexOf('-'))))) {
                    aprsSenderCall = field;
                    body = tail;
                } else {
                    body = afterLine;
                }
            } else {
                body = afterLine;
            }
        } else {
            body = afterRoom;
        }
        if (body.isEmpty()) return null;
        return new GatewayWrapped(toUid, room, lineUid, aprsSenderCall, body);
    }

    /**
     * Send an outbound ATAK GeoChat to a MeshCore node as a native pubkey-to-pubkey DM
     * ({@code CMD_SEND_TXT_MSG}) instead of the proprietary {@code 0xFF01} AX.25 channel datagram
     * (which native MeshCore clients reject as "Unhandled"). Returns true if handled here.
     */
    private boolean trySendNativeMeshDm(String destUidHint, String roomHint, String message) {
        if (!(btManager instanceof MeshBtConnectionManager)) {
            return false;
        }
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        String pubKey = extractMeshPublicKeyCandidate(destUidHint);
        if (pubKey.isEmpty()) {
            pubKey = extractMeshPublicKeyCandidate(roomHint);
        }
        if (pubKey.isEmpty()) {
            return false;
        }
        boolean ok = ((MeshBtConnectionManager) btManager)
                .sendContactTextMessage(pubKey, message.trim());
        if (ok) {
            Log.d(TAG, "Outbound GeoChat sent as native MeshCore DM pubkeyPrefix="
                    + pubKey.substring(0, Math.min(12, pubKey.length())) + " len=" + message.trim().length());
        }
        return ok;
    }

    /**
     * Inject an inbound native MeshCore DM ({@code RESP_CONTACT_MSG}) into ATAK GeoChat. The native
     * frame carries only the sender's 6-byte pubkey prefix, so resolve the full
     * {@code MESHCORE-NODE-<pubkey>} contact when known; otherwise thread under the prefix.
     */
    public boolean injectInboundMeshDm(String senderPubKeyPrefixHex, String text) {
        if (cotBridge == null || text == null || text.trim().isEmpty()) {
            return false;
        }
        String prefix = senderPubKeyPrefixHex == null
                ? "" : senderPubKeyPrefixHex.trim().toUpperCase(Locale.US);
        if (prefix.isEmpty()) {
            return false;
        }
        String senderUid = resolveExistingMeshContactUidByPubKeyPrefix(prefix);
        if (senderUid == null || senderUid.trim().isEmpty()) {
            // The node delivered a DM from one of its own contacts, so the sender is known to the
            // radio even when ATAK has no map item for it (no GPS position, Show Nodes off).
            String created = UVProContactHandler.ensureMeshInboundChatContact(prefix);
            if (created != null && !created.trim().isEmpty()) {
                Log.i(TAG, "Created mesh contact for inbound native MeshCore DM prefix=" + prefix
                        + " uid=" + created);
                senderUid = created;
            }
        }
        if (senderUid == null || senderUid.trim().isEmpty()) {
            Log.w(TAG, "Dropping inbound native MeshCore DM from unknown prefix=" + prefix
                    + " (not in mesh contacts)");
            return false;
        }
        String fromCallsign = meshNodeDisplayForInboundPrefix(prefix, senderUid);
        cotBridge.registerBtechContactUid(senderUid);
        cotBridge.registerBtechContactId(fromCallsign, senderUid);
        int mid = (prefix + "|" + text.trim()).hashCode() & 0x7fffffff;
        if (mid == 0) {
            mid = 1;
        }
        Log.d(TAG, "Inbound native MeshCore DM from " + fromCallsign
                + " (" + senderUid + ") len=" + text.trim().length());
        return injectRadioMessage(fromCallsign, localCallsign(), text.trim(), mid);
    }

    private String resolveExistingMeshContactUidByPubKeyPrefix(String prefixUpper) {
        try {
            java.util.List<Contact> all = Contacts.getInstance().getAllContacts();
            if (all != null) {
                for (Contact c : all) {
                    if (c == null) {
                        continue;
                    }
                    String uid = c.getUID();
                    if (uid == null) {
                        continue;
                    }
                    String u = uid.toUpperCase(Locale.US);
                    if ((u.startsWith(MESH_NODE_UID_PREFIX) || u.startsWith(MESH_RPTR_UID_PREFIX))
                            && extractMeshPublicKeyCandidate(u).startsWith(prefixUpper)) {
                        if (c instanceof IndividualContact) {
                            IndividualContact ic = (IndividualContact) c;
                            if (ic.getConnector(com.uvpro.plugin.contacts.MeshSendMessageConnector.CONNECTOR_TYPE) != null
                                    || ic.getConnector(com.uvpro.plugin.contacts.MeshFavoriteConnector.CONNECTOR_TYPE) != null) {
                                return uid;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String meshNodeDisplayForUid(String uid) {
        try {
            Contact c = Contacts.getInstance().getContactByUuid(uid);
            if (c != null && c.getName() != null && !c.getName().trim().isEmpty()) {
                return c.getName().trim();
            }
        } catch (Exception ignored) {
        }
        return uid;
    }

    private String meshNodeDisplayForInboundPrefix(String prefixUpper, String senderUid) {
        String byUid = meshNodeDisplayForUid(senderUid);
        if (byUid != null && !byUid.trim().isEmpty()) {
            String clean = byUid.trim();
            String upper = clean.toUpperCase(Locale.US);
            if (!upper.startsWith(MESH_NODE_UID_PREFIX) && !upper.startsWith(MESH_RPTR_UID_PREFIX)) {
                return clean;
            }
        }
        try {
            MapView mv = MapView.getMapView();
            if (mv != null && mv.getRootGroup() != null) {
                java.util.List<MapItem> items = mv.getRootGroup().deepFindItems("type", "a-f-G-U-C");
                if (items != null) {
                    for (MapItem item : items) {
                        if (item == null) {
                            continue;
                        }
                        String uid = item.getUID();
                        if (uid == null) {
                            continue;
                        }
                        String candidate = extractMeshPublicKeyCandidate(uid);
                        if (!candidate.isEmpty() && candidate.startsWith(prefixUpper)) {
                            String call = item.getMetaString("callsign", item.getTitle());
                            if (call != null) {
                                call = call.trim();
                                if (!call.isEmpty()) {
                                    return call;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return MESH_NODE_UID_PREFIX + prefixUpper;
    }

    /**
     * AX.25 wire destination: 6-char radio callsign derived from the full ATAK callsign.
     * Used only in TYPE_CHAT room bytes and gateway {@code wireDest}; never for UI labels.
     */
    private String resolveRfWireDestination(String toUidHint, String displayCallsignHint) {
        String meshKey = extractMeshPublicKeyCandidate(toUidHint);
        if (meshKey.isEmpty()) {
            meshKey = extractMeshPublicKeyCandidate(displayCallsignHint);
        }
        if (!meshKey.isEmpty()) {
            return meshKey;
        }
        String hint = displayCallsignHint != null ? displayCallsignHint.trim() : "";
        if (hint.isEmpty() && toUidHint != null) {
            hint = toUidHint.trim();
        }
        if (hint.isEmpty()) {
            return "";
        }
        String upper = hint.toUpperCase(Locale.US);
        if (upper.startsWith(ANDROID_UID_PREFIX)) {
            hint = upper.substring(ANDROID_UID_PREFIX.length());
        }
        String radio = CallsignUtil.toRadioCallsign(hint);
        if (radio != null && !radio.trim().isEmpty()) {
            return radio.trim().toUpperCase(Locale.US);
        }
        return upper.length() > 6 ? upper.substring(0, 6) : upper;
    }

    private static String extractMeshPublicKeyCandidate(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toUpperCase(Locale.US);
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith(MESH_NODE_UID_PREFIX)) {
            String suffix = value.substring(MESH_NODE_UID_PREFIX.length()).trim();
            return isLikelyMeshPublicKey(suffix) ? suffix : "";
        }
        if (value.startsWith(MESH_RPTR_UID_PREFIX)) {
            String suffix = value.substring(MESH_RPTR_UID_PREFIX.length()).trim();
            return isLikelyMeshPublicKey(suffix) ? suffix : "";
        }
        return isLikelyMeshPublicKey(value) ? value : "";
    }

    private static boolean isLikelyMeshPublicKey(String key) {
        if (key == null || key.length() != 64) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'A' && c <= 'F')
                    || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private String resolveDmDestinationUid(String toUidHint, String roomHint) {
        return resolveRfWireDestination(toUidHint, roomHint);
    }

    private static String extractGeoChatDestinationHint(CotEvent event, String chatRoomFallback) {
        if (event == null) {
            return chatRoomFallback != null ? chatRoomFallback : "";
        }
        try {
            CotDetail detail = event.getDetail();
            if (detail == null) {
                return chatRoomFallback != null ? chatRoomFallback : "";
            }
            CotDetail remarks = detail.getFirstChildByName(0, "remarks");
            if (remarks != null) {
                String to = remarks.getAttribute("to");
                if (to != null && !to.trim().isEmpty()) {
                    return to.trim();
                }
            }
            CotDetail chat = detail.getFirstChildByName(0, "__chat");
            if (chat == null) {
                chat = detail.getFirstChildByName(0, "chat");
            }
            if (chat != null) {
                String id = chat.getAttribute("id");
                if (id != null && !id.trim().isEmpty()) {
                    return id.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return chatRoomFallback != null ? chatRoomFallback : "";
    }

    private boolean isGatewayRelayEnabled() {
        try {
            return SettingsFragment.isSaRelayEnabled(pluginContext)
                    && SettingsFragment.isRfToTakUplinkEnabled(pluginContext);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final class GatewayWrapped {
        /** 6-char AX.25 wire destination (internal routing only). */
        final String wireDest;
        /** Full destination callsign for display/threading ({@code N7JDI-9}). */
        final String displayCallsign;
        final String lineUid;
        /** Sender FCC call when UV-PRO gateway carries APRS-context chat. */
        final String aprsSenderCall;
        final String message;

        GatewayWrapped(String wireDest, String displayCallsign, String lineUid,
                       String aprsSenderCall, String message) {
            this.wireDest = wireDest;
            this.displayCallsign = displayCallsign;
            this.lineUid = lineUid;
            this.aprsSenderCall = aprsSenderCall;
            this.message = message;
        }
    }

    /**
     * Wait for follow-up RF traffic (sender CoT relay, OPENRL guard, etc.) to clear
     * before keying a compact chat ACK — reduces mesh drops from on-channel collisions.
     */
    private static final long RF_CHAT_ACK_DELIVERED_DELAY_MS = 5000L;
    private static final long RF_CHAT_ACK_READ_DELAY_MS = 5000L;

    /**
     * Notify peer over RF that their chat frame was received (GeoChat delivered/read).
     */
    public void sendRadioChatAck(int wireMessageId, byte ackKind) {
        sendRadioChatAck(wireMessageId, ackKind, null);
    }

    /** Link manager for the radio an inbound line arrived on, set from the router. */
    private volatile BtConnectionManager uvproInboundManager;
    private volatile BtConnectionManager meshInboundManager;
    private final ConcurrentHashMap<Integer, com.uvpro.plugin.protocol.RfInboundTransport>
            inboundTransportByWireMid = new ConcurrentHashMap<>();

    public void setInboundTransports(BtConnectionManager uvpro, BtConnectionManager mesh) {
        this.uvproInboundManager = uvpro;
        this.meshInboundManager = mesh;
    }

    /**
     * ACKs go back over the radio the message arrived on; the active transmit radio is only the
     * fallback when that link is gone or unknown.
     */
    private BtConnectionManager resolveAckManager(int wireMessageId) {
        com.uvpro.plugin.protocol.RfInboundTransport inbound =
                inboundTransportByWireMid.get(wireMessageId);
        BtConnectionManager preferred = null;
        if (inbound == com.uvpro.plugin.protocol.RfInboundTransport.MESHCORE) {
            preferred = meshInboundManager;
        } else if (inbound == com.uvpro.plugin.protocol.RfInboundTransport.UVPRO) {
            preferred = uvproInboundManager;
        }
        if (preferred != null && preferred.isConnected()) {
            return preferred;
        }
        return btManager;
    }

    /**
     * @param inboundTransport radio the chat arrived on, or null to reuse what was recorded for
     *                         this wire id (READ receipts sent later)
     */
    public void sendRadioChatAck(int wireMessageId, byte ackKind,
                                 com.uvpro.plugin.protocol.RfInboundTransport inboundTransport) {
        if (inboundTransport != null) {
            if (inboundTransportByWireMid.size() > 256) {
                inboundTransportByWireMid.clear();
            }
            inboundTransportByWireMid.put(wireMessageId, inboundTransport);
        }
        BtConnectionManager ackTx = resolveAckManager(wireMessageId);
        if (!relayOutgoing || ackTx == null || !ackTx.isConnected()) {
            return;
        }
        long delayMs = ackKind == UVProPacket.ACK_KIND_READ
                ? RF_CHAT_ACK_READ_DELAY_MS
                : RF_CHAT_ACK_DELIVERED_DELAY_MS;
        Log.d(TAG, "Scheduling radio chat ACK kind=" + ackKind + " mid=" + wireMessageId
                + " in " + (delayMs / 1000) + "s");
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(() -> sendRadioChatAckWhenReady(wireMessageId, ackKind, 0), delayMs);
    }

    private void sendRadioChatAckWhenReady(int wireMessageId, byte ackKind, int deferAttempt) {
        BtConnectionManager ackTx = resolveAckManager(wireMessageId);
        if (disposed || !relayOutgoing || ackTx == null || !ackTx.isConnected()) {
            return;
        }
        if (com.uvpro.plugin.protocol.RfTxArbitrator.get().shouldDeferRfChatAck()) {
            if (deferAttempt < 24) {
                android.os.Handler h = new android.os.Handler(
                        android.os.Looper.getMainLooper());
                h.postDelayed(() -> sendRadioChatAckWhenReady(wireMessageId, ackKind,
                                deferAttempt + 1),
                        400L);
            } else {
                Log.w(TAG, "Chat ACK deferred too long; dropping mid=" + wireMessageId);
            }
            return;
        }
        transmitRadioChatAckNow(wireMessageId, ackKind);
    }

    private void transmitRadioChatAckNow(int wireMessageId, byte ackKind) {
        try {
            UVProPacket packet =
                    UVProPacket.createChatAckPacket(wireMessageId, ackKind);
            byte[] packetBytes = packet.encode();
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                packetBytes = encryptionManager.encrypt(packetBytes);
                if (packetBytes == null) {
                    Log.w(TAG, "Chat ACK encrypt failed mid=" + wireMessageId);
                    return;
                }
            }
            Ax25Frame frame = Ax25Frame.createUVProFrame(
                    localCallsign(), 0, packetBytes);
            BtConnectionManager ackTx = resolveAckManager(wireMessageId);
            if (ackTx == null) {
                return;
            }
            ackTx.sendKissFrame(frame.encode());
            Log.d(TAG, "Sent radio chat ACK kind=" + ackKind + " mid=" + wireMessageId
                    + " via " + (ackTx instanceof com.uvpro.plugin.bluetooth.MeshBtConnectionManager
                    ? "MeshCore" : "UV-PRO"));
        } catch (Exception e) {
            Log.e(TAG, "sendRadioChatAck failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Outbound retry helpers
    // -------------------------------------------------------------------------

    private void scheduleRetryCheck(int wireMid) {
        if (retryExecutor.isShutdown()) {
            Log.w(TAG, "Retry executor shut down — cannot schedule retry for mid=" + wireMid);
            return;
        }
        long intervalMs = SettingsFragment.getRetryIntervalMs(pluginContext);
        Log.d(TAG, "Retry watchdog scheduled mid=" + wireMid + " in " + (intervalMs / 1000) + "s");
        retryExecutor.schedule(() -> onRetryTimer(wireMid), intervalMs, TimeUnit.MILLISECONDS);
    }

    private void onRetryTimer(int wireMid) {
        try {
            if (disposed) return;
            PendingOutboundChat pending = pendingOutboundChats.get(wireMid);
            if (pending == null) {
                Log.d(TAG, "Retry timer fired mid=" + wireMid + " — already ACK'd, nothing to do");
                return;
            }
            if (btManager != null && btManager.isRadioSilenceEnabled()) {
                Log.d(TAG, "Retry deferred mid=" + wireMid + " — Radio Silence active");
                scheduleRetryCheck(wireMid);
                return;
            }
            int maxRetries = SettingsFragment.getMaxChatRetries(pluginContext);
            if (pending.retryCount < maxRetries) {
                pending.retryCount++;
                Log.d(TAG, "No DELIVERED ACK for mid=" + wireMid
                        + " — retransmitting (attempt " + pending.retryCount + "/" + maxRetries + ")");
                retransmitChat(wireMid, pending);
                scheduleRetryCheck(wireMid);
            } else {
                Log.w(TAG, "Retry limit reached mid=" + wireMid + " after " + pending.retryCount
                        + " attempts — declaring failure");
                pendingOutboundChats.remove(wireMid);
                outboundWireMidToLocalLineUid.remove(wireMid);
                notifyDeliveryFailed(wireMid, pending);
            }
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in retry timer for mid=" + wireMid, e);
        }
    }

    private void retransmitChat(int wireMid, PendingOutboundChat pending) {
        if (btManager == null || !btManager.isConnected()) {
            Log.w(TAG, "Retry skipped — not connected mid=" + wireMid);
            return;
        }
        try {
            UVProPacket packet = UVProPacket.createChatPacket(
                    CallsignUtil.toRadioCallsign(pending.sender),
                    pending.room, wireMid, pending.message);
            byte[] packetBytes = packet.encode();
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                packetBytes = encryptionManager.encrypt(packetBytes);
                if (packetBytes == null) {
                    Log.e(TAG, "Retry encrypt failed mid=" + wireMid);
                    return;
                }
            }
            Ax25Frame frame = Ax25Frame.createUVProFrame(localCallsign(), 0, packetBytes);
            btManager.sendKissFrame(frame.encode());
        } catch (Exception e) {
            Log.e(TAG, "Retry transmit failed mid=" + wireMid, e);
        }
    }

    private void notifyDeliveryFailed(int wireMid, PendingOutboundChat pending) {
        Log.w(TAG, "Delivery failed after " + pending.retryCount + " retries mid=" + wireMid
                + " room=" + pending.room);
        String peer = pending.room;
        if (peer.startsWith("ANDROID-")) {
            peer = peer.substring("ANDROID-".length());
        }
        final String peerKey = peer.trim().toUpperCase();
        // Stash so we can auto-resend when the peer comes back online.
        failedOutboundChatsByPeer
                .computeIfAbsent(peerKey, k -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                .add(pending);

        final String peerDisplay = peer;
        final int retriesMade = pending.retryCount;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                // AlertDialog — stays on screen until user taps OK.
                android.app.AlertDialog.Builder builder =
                        new android.app.AlertDialog.Builder(
                                com.atakmap.android.maps.MapView.getMapView().getContext());
                builder.setTitle("Message Not Delivered");
                builder.setMessage("Message to " + peerDisplay
                        + " will be delivered when user is rediscovered.");
                builder.setIcon(android.R.drawable.ic_dialog_alert);
                builder.setPositiveButton("OK", null);
                builder.setCancelable(false);
                builder.show();
            } catch (Exception e) {
                Log.e(TAG, "AlertDialog for delivery failure failed", e);
                // Fallback toast if dialog can't be shown.
                try {
                    android.widget.Toast.makeText(pluginContext,
                            "Message to " + peerDisplay + " will be delivered when user is rediscovered.",
                            android.widget.Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {}
            }
            try {
                // Persistent system notification in the shade as a record.
                int notifyId = ("uvpro_fail_" + wireMid).hashCode() & 0x7FFFFFFF;
                NotificationUtil.getInstance().postNotification(
                        notifyId,
                        NotificationUtil.RED,
                        "Message Not Delivered",
                        "UV-PRO",
                        "Message to " + peerDisplay + " will be delivered when user is rediscovered.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to post delivery failure notification", e);
            }
        });
    }

    /**
     * Apply RF GeoChat ACK on this device (updates sent-line ticks via ATAK receipts).
     */
    public void handleIncomingRadioChatAck(int wireMessageId, byte kind) {
        String lineUid = outboundWireMidToLocalLineUid.get(wireMessageId);
        if (lineUid == null) {
            Log.d(TAG, "RF chat ACK mid=" + wireMessageId + " kind=" + kind
                    + " — no outbound mapping");
            return;
        }
        Log.d(TAG, "RF chat ACK apply mid=" + wireMessageId + " kind=" + kind
                + " -> lineUid=" + lineUid);
        if (cotBridge == null) {
            return;
        }
        if (kind == UVProPacket.ACK_KIND_DELIVERED) {
            // Cancel retry watchdog — message reached the recipient.
            PendingOutboundChat removed = pendingOutboundChats.remove(wireMessageId);
            if (removed != null) {
                Log.d(TAG, "DELIVERED ACK cancelled retry watchdog mid=" + wireMessageId);
            }
            cotBridge.injectGeoChatReceipt(lineUid, false);
        } else if (kind == UVProPacket.ACK_KIND_READ) {
            cotBridge.injectGeoChatReceipt(lineUid, true);
        }
    }

    public static String resolveGeoChatLineUid(CotEvent event) {
        return resolveOutboundGeoChatLineUid(event);
    }

    public static boolean isLikelyGeoChatLineUid(String uidRaw) {
        return isLikelyChatLineUid(uidRaw);
    }

    private static String resolveOutboundGeoChatLineUid(CotEvent event) {
        if (event == null) {
            return null;
        }
        String u = event.getUID();
        if (isLikelyChatLineUid(u)) {
            return u.trim();
        }
        try {
            CotDetail d = event.getDetail();
            if (d == null) {
                return null;
            }
            CotDetail chat = d.getFirstChildByName(0, "__chat");
            if (chat == null) {
                chat = d.getFirstChildByName(0, "chat");
            }
            if (chat != null) {
                String mid = chat.getAttribute("messageId");
                if (isLikelyChatLineUid(mid)) {
                    return mid.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isLikelyChatLineUid(String uidRaw) {
        if (uidRaw == null) {
            return false;
        }
        String uid = uidRaw.trim();
        if (uid.isEmpty()) {
            return false;
        }
        if (uid.startsWith("GeoChat.")) {
            // ATAK line UIDs vary by path; treat any non-trivial GeoChat.* as valid.
            return uid.length() > "GeoChat.".length() + 8;
        }
        // Some ATAK paths carry only the bare line UUID.
        return uid.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    private static String extractGeoChatLineUidFromBundle(android.os.Bundle b) {
        if (b == null) {
            return null;
        }
        String[] keys = {"messageId", "MessageId", "chatMessageUid", "lineUid", "chatLineUid",
                "cotUid", "cotUID", "geoChatUid", "GeoChatUid", "cotEventUid", "eventUid", "uid"};
        for (String k : keys) {
            String v = b.getString(k);
            if (isLikelyChatLineUid(v)) {
                return v.trim();
            }
        }
        String xml = b.getString("xml");
        if (xml == null) {
            xml = b.getString("cotXml");
        }
        if (xml == null) {
            xml = b.getString("cot");
        }
        if (xml != null && !xml.isEmpty()) {
            try {
                CotEvent e = CotEvent.parse(xml);
                return resolveOutboundGeoChatLineUid(e);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String extractGeoChatLineUidFromIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        String[] extraKeys = {"messageId", "MessageId", "chatMessageUid", "lineUid", "chatLineUid",
                "cotUid", "cotUID", "geoChatUid", "cotEventUid", "eventUid", "uid"};
        for (String k : extraKeys) {
            String v = intent.getStringExtra(k);
            if (isLikelyChatLineUid(v)) {
                return v.trim();
            }
        }
        String xml = intent.getStringExtra("xml");
        if (xml != null && !xml.isEmpty()) {
            try {
                CotEvent e = CotEvent.parse(xml);
                String u = resolveOutboundGeoChatLineUid(e);
                if (u != null) {
                    return u;
                }
            } catch (Exception ignored) {
            }
        }
        return extractGeoChatLineUidFromBundle(getMessageBundleExtra(intent));
    }

    /** @return true if this relay should be skipped (already sent for the same line recently). */
    private boolean skipIfDuplicateOutboundGeoChatLine(String lineUid) {
        if (lineUid == null || !lineUid.startsWith("GeoChat.")) {
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (outboundGeoChatDedupeLock) {
            if (lineUid.equals(lastOutboundGeoChatDedupeUid)
                    && (now - lastOutboundGeoChatDedupeMs) < 3000L) {
                Log.d(TAG, "Skip duplicate outbound GeoChat relay " + lineUid);
                return true;
            }
            lastOutboundGeoChatDedupeUid = lineUid;
            lastOutboundGeoChatDedupeMs = now;
            return false;
        }
    }

    private void maybeLogPluginGeoChatBundleKeysMissingUid(android.os.Bundle b) {
        if (b == null || loggedPluginChatBundleKeysMissingUid) {
            return;
        }
        loggedPluginChatBundleKeysMissingUid = true;
        try {
            StringBuilder keys = new StringBuilder();
            for (String k : b.keySet()) {
                if (keys.length() > 0) {
                    keys.append(",");
                }
                keys.append(k);
            }
            Log.d(TAG, "PLUGIN MESSAGE bundle (no GeoChat.* uid) keys: " + keys);
        } catch (Exception ignored) {
        }
    }

    private void trimOutboundAckMap() {
        while (outboundWireMidToLocalLineUid.size() > MAX_OUTBOUND_ACK_ENTRIES) {
            Iterator<Integer> it = outboundWireMidToLocalLineUid.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            outboundWireMidToLocalLineUid.remove(it.next());
        }
    }

    /**
     * Recover wire chat {@code messageId} embedded in {@link CotBridge} inbound GeoChat UIDs.
     */
    static Integer recoverWireMidFromGeoChatUid(String geoChatUid) {
        if (geoChatUid == null || !geoChatUid.startsWith("GeoChat.")) {
            return null;
        }
        int lastDot = geoChatUid.lastIndexOf('.');
        if (lastDot < 0 || lastDot >= geoChatUid.length() - 1) {
            return null;
        }
        try {
            long uniq = Long.parseLong(geoChatUid.substring(lastDot + 1));
            long wireUnsig = (uniq >>> 32) & 0xffffffffL;
            if (wireUnsig == 0L) {
                return null;
            }
            return (int) wireUnsig;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer extractWireMidFromMarkReadBundle(android.os.Bundle b) {
        if (b == null) {
            return null;
        }
        String[] keys = {"messageId", "chatMessageUid", "cotUid", "chatUid", "uid"};
        for (String k : keys) {
            Integer mid = recoverWireMidFromGeoChatUid(b.getString(k));
            if (mid != null) {
                return mid;
            }
        }
        return null;
    }

    /**
     * Called whenever a beacon (GPS position) or ping is received from a radio peer.
     *
     * Two effects:
     *  1. Any pending (unacknowledged, in-retry) messages for that peer are sent immediately
     *     and their retry counter is reset, giving the message a fresh set of attempts now
     *     that the peer is known to be reachable.
     *  2. Any messages that previously exhausted all retries (and showed the "will be delivered
     *     when user is rediscovered" dialog) are re-queued and sent now.
     *
     * @param callsign  Bare radio callsign (no "ANDROID-" prefix), as received from the wire.
     */
    public void onPeerActivity(String callsign) {
        if (callsign == null || callsign.isEmpty()) return;
        final String key = callsign.trim().toUpperCase();

        // 1. Pending in-retry messages → send immediately and reset retry counter.
        for (Map.Entry<Integer, PendingOutboundChat> entry : pendingOutboundChats.entrySet()) {
            PendingOutboundChat pending = entry.getValue();
            String roomKey = pending.room;
            if (roomKey.startsWith("ANDROID-")) roomKey = roomKey.substring("ANDROID-".length());
            if (!key.equals(roomKey.trim().toUpperCase())) continue;

            Log.d(TAG, "Peer activity for " + key
                    + " — sending pending mid=" + entry.getKey() + " immediately");
            pending.retryCount = 0;
            retransmitChat(entry.getKey(), pending);
            // Leave in pendingOutboundChats — ACK will remove it; watchdog retries if needed.
        }

        // 2. Previously-failed messages → resend as fresh transmissions.
        java.util.Queue<PendingOutboundChat> failed = failedOutboundChatsByPeer.remove(key);
        if (failed != null && !failed.isEmpty()) {
            Log.d(TAG, "Peer " + key + " rediscovered — resending " + failed.size() + " failed message(s)");
            for (PendingOutboundChat f : failed) {
                sendChatOverRadio(f.sender, f.room, f.message, f.geoChatLineUid);
            }
        }
    }

    /**
     * Clean up resources.
     */
    public void dispose() {
        disposed = true;
        retryExecutor.shutdownNow();
        pendingOutboundChats.clear();
        failedOutboundChatsByPeer.clear();
        outboundWireMidToLocalLineUid.clear();
        pendingReadAcksByConversation.clear();
        pendingUnreadConversationUids.clear();
        unreadVisibilityPollRunning = false;
        if (chatReceiver != null) {
            try {
                AtakBroadcast.getInstance()
                        .unregisterReceiver(chatReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering chat receiver", e);
            }
            chatReceiver = null;
        }
        if (chatMarkReadReceiver != null) {
            try {
                AtakBroadcast.getInstance()
                        .unregisterReceiver(chatMarkReadReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering mark-read receiver", e);
            }
            chatMarkReadReceiver = null;
        }
        if (chatOpenReceiver != null) {
            try {
                AtakBroadcast.getInstance()
                        .unregisterReceiver(chatOpenReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering open-chat receiver", e);
            }
            chatOpenReceiver = null;
        }
        if (chatClosedReceiver != null) {
            try {
                AtakBroadcast.getInstance()
                        .unregisterReceiver(chatClosedReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering chat-closed receiver", e);
            }
            chatClosedReceiver = null;
        }
        if (contactsUnreadSyncListener != null) {
            try {
                Contacts.getInstance().removeListener(contactsUnreadSyncListener);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering Contacts listener", e);
            }
            contactsUnreadSyncListener = null;
        }
        if (atakChatMessageListener != null) {
            try {
                ChatManagerMapComponent cmmc = ChatManagerMapComponent.getInstance();
                if (cmmc != null) {
                    cmmc.removeChatMessageListener(atakChatMessageListener);
                }
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering ChatManager listener", e);
            }
            atakChatMessageListener = null;
        }
        lastAtakUnreadByUid.clear();
        Log.d(TAG, "ChatBridge disposed");
    }
}
