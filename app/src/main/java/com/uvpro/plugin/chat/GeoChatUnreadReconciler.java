package com.uvpro.plugin.chat;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

import com.atakmap.android.chat.ChatDatabase;
import com.atakmap.android.chat.ChatLine;
import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.chat.ConversationFragment;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.maps.MapView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ATAK's contacts-icon badge is the sum of each contact's {@code unreadMessageCount}.
 * Opening a chat marks lines read in memory and writes {@code readTime} for only one
 * row, and only when the sender uid equals the conversation id. The next history load
 * counts every other row, including our own sent lines, so the badge climbs into the
 * dozens after a single new message.
 *
 * The first pass on a conversation keeps the newest inbound row and stamps the rest.
 * Later inbound rows stay unread until this conversation is actually on screen.
 */
public final class GeoChatUnreadReconciler {

    private static final String TAG = "UVPro.Unread";
    private static final String SEAL_PREFIX = "uvpro_geochat_seal_";

    private GeoChatUnreadReconciler() {
    }

    /** Plugin load: correct every saved conversation and drop stale contact counts. */
    public static void reconcileAll() {
        MapView mapView = MapView.getMapView();
        if (mapView == null) {
            return;
        }
        try {
            ChatDatabase db = ChatDatabase.getInstance(mapView.getContext());
            List<String> ids = db.getPersistedConversationIds();
            Set<String> keep = new HashSet<>();
            if (ids != null) {
                for (String id : ids) {
                    if (id == null || id.trim().isEmpty()) {
                        continue;
                    }
                    keep.add(id);
                    applyConversation(db, id, false);
                }
            }
            int total = publishCounts(keep);
            Log.i(TAG, "Contacts unread badge reconciled to " + total);
        } catch (Exception e) {
            Log.w(TAG, "reconcileAll", e);
        }
    }

    /**
     * After ATAK has stored one inbound chat. History reloads count old rows; clamp
     * that contact back to messages the operator has not opened.
     */
    public static void reconcileConversation(String conversationId) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            return;
        }
        MapView mapView = MapView.getMapView();
        if (mapView == null) {
            return;
        }
        try {
            ChatDatabase db = ChatDatabase.getInstance(mapView.getContext());
            int n = applyConversation(db, conversationId.trim(), false);
            Contacts.getInstance().updateTotalUnreadCount();
            Log.i(TAG, "GeoChat unread " + conversationId.trim() + " -> " + n);
        } catch (Exception e) {
            Log.w(TAG, "reconcileConversation " + conversationId, e);
        }
    }

    /** The operator is looking at this thread. Nothing in it is unread. */
    public static void markConversationRead(String conversationId) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            return;
        }
        MapView mapView = MapView.getMapView();
        if (mapView == null) {
            return;
        }
        try {
            ChatDatabase db = ChatDatabase.getInstance(mapView.getContext());
            applyConversation(db, conversationId.trim(), true);
            Contacts.getInstance().updateTotalUnreadCount();
            Log.i(TAG, "GeoChat marked read " + conversationId.trim());
        } catch (Exception e) {
            Log.w(TAG, "markConversationRead " + conversationId, e);
        }
    }

    /**
     * @param forceRead stamp every row and clear the badge for this conversation
     * @return unread count left on the conversation contact
     */
    private static int applyConversation(ChatDatabase db, String conversationId, boolean forceRead) {
        List<Bundle> history;
        try {
            history = db.getHistory(conversationId);
        } catch (Exception e) {
            Log.w(TAG, "getHistory " + conversationId, e);
            history = Collections.emptyList();
        }
        if (history == null) {
            history = Collections.emptyList();
        }

        String selfUid = null;
        try {
            selfUid = MapView.getDeviceUid();
        } catch (Exception ignored) {
        }

        boolean visible = !forceRead && isFragmentVisible(conversationId);
        boolean treatAsRead = forceRead || visible;
        SharedPreferences prefs = prefs();
        boolean sealed = prefs != null && prefs.getBoolean(sealKey(conversationId), false);

        String keepId = null;
        if (!treatAsRead && !sealed) {
            keepId = newestInboundId(history, selfUid);
            if (prefs != null) {
                prefs.edit().putBoolean(sealKey(conversationId), true).apply();
            }
        }
        if (treatAsRead && prefs != null && !sealed) {
            prefs.edit().putBoolean(sealKey(conversationId), true).apply();
        }

        Set<String> stillUnread = new HashSet<>();
        for (Bundle row : history) {
            if (row == null) {
                continue;
            }
            String messageId = row.getString("messageId");
            boolean alreadyRead = row.getLong("readTime", -1L) >= 0L;
            boolean inbound = messageId != null && !isSelf(row.getString("senderUid"), selfUid);
            if (!inbound || alreadyRead || treatAsRead) {
                if (!alreadyRead) {
                    stampRead(db, messageId);
                }
                continue;
            }
            if (keepId != null && !keepId.equals(messageId)) {
                stampRead(db, messageId);
                continue;
            }
            stillUnread.add(messageId);
        }

        syncAdapter(conversationId, stillUnread, selfUid);
        int unread = adapterUnreadCount(conversationId);
        if (unread < 0) {
            unread = stillUnread.size();
        }
        setContactUnread(conversationId, unread);
        return unread;
    }

    private static String newestInboundId(List<Bundle> historyNewestFirst, String selfUid) {
        for (Bundle row : historyNewestFirst) {
            if (row == null) {
                continue;
            }
            String messageId = row.getString("messageId");
            if (messageId == null || messageId.isEmpty()) {
                continue;
            }
            if (isSelf(row.getString("senderUid"), selfUid)) {
                continue;
            }
            if (row.getLong("readTime", -1L) >= 0L) {
                continue;
            }
            return messageId;
        }
        return null;
    }

    private static void stampRead(ChatDatabase db, String messageId) {
        if (messageId == null || messageId.isEmpty()) {
            return;
        }
        try {
            if (db.getChatMessage(messageId) == null) {
                return;
            }
            Bundle update = new Bundle();
            update.putString("messageId", messageId);
            update.putLong("readTime", System.currentTimeMillis());
            db.addChat(update);
        } catch (Exception e) {
            Log.w(TAG, "stamp read " + messageId, e);
        }
    }

    private static void syncAdapter(String conversationId, Set<String> stillUnread, String selfUid) {
        Object adapter = adapterFor(conversationId);
        if (adapter == null) {
            return;
        }
        try {
            Field linesField = adapter.getClass().getDeclaredField("chatLines");
            linesField.setAccessible(true);
            Object raw = linesField.get(adapter);
            if (!(raw instanceof List)) {
                return;
            }
            for (Object item : (List<?>) raw) {
                if (!(item instanceof ChatLine)) {
                    continue;
                }
                ChatLine line = (ChatLine) item;
                if (isSelf(line.senderUid, selfUid) || line.messageId == null
                        || !stillUnread.contains(line.messageId)) {
                    line.read = true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "sync adapter " + conversationId, t);
        }
    }

    /** @return adapter unread count, or -1 when the conversation has no adapter yet */
    private static int adapterUnreadCount(String conversationId) {
        Object adapter = adapterFor(conversationId);
        if (adapter == null) {
            return -1;
        }
        try {
            Method unread = adapter.getClass().getMethod("getUnreadCount");
            Object n = unread.invoke(adapter);
            if (n instanceof Integer) {
                return (Integer) n;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** ATAK's list adapter is package-private, so the fragment field is read by reflection. */
    private static Object adapterFor(String conversationId) {
        ConversationFragment fragment = fragmentFor(conversationId);
        if (fragment == null) {
            return null;
        }
        try {
            Field f = ConversationFragment.class.getDeclaredField("_mChatLineAdapter");
            f.setAccessible(true);
            return f.get(fragment);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isFragmentVisible(String conversationId) {
        ConversationFragment fragment = fragmentFor(conversationId);
        if (fragment == null) {
            return false;
        }
        try {
            return fragment.isResumed() && fragment.isVisible();
        } catch (Throwable t) {
            return false;
        }
    }

    private static ConversationFragment fragmentFor(String conversationId) {
        if (conversationId == null) {
            return null;
        }
        try {
            Field f = ChatManagerMapComponent.class.getDeclaredField("fragmentMap");
            f.setAccessible(true);
            Object raw = f.get(null);
            if (!(raw instanceof java.util.Map)) {
                return null;
            }
            Object o = ((java.util.Map<?, ?>) raw).get(conversationId);
            if (o instanceof ConversationFragment) {
                return (ConversationFragment) o;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void setContactUnread(String conversationId, int unread) {
        try {
            Contact contact = Contacts.getInstance().getContactByUuid(conversationId);
            if (contact == null) {
                return;
            }
            int current = 0;
            if (contact.getExtras() != null) {
                current = contact.getExtras().getInt("unreadMessageCount", 0);
            }
            if (current != unread) {
                contact.setUnreadCount(unread);
            }
        } catch (Exception e) {
            Log.w(TAG, "set unread " + conversationId, e);
        }
    }

    /**
     * Write each reconciled conversation, then clear extras on every other contact.
     * @return sum of counts we kept
     */
    private static int publishCounts(Set<String> conversationIds) {
        int total = 0;
        try {
            List<Contact> all = Contacts.getInstance().getAllContacts();
            if (all == null) {
                Contacts.getInstance().updateTotalUnreadCount();
                return 0;
            }
            for (Contact contact : all) {
                if (contact == null || contact.getUID() == null) {
                    continue;
                }
                int want = 0;
                if (conversationIds.contains(contact.getUID())) {
                    if (contact.getExtras() != null) {
                        want = contact.getExtras().getInt("unreadMessageCount", 0);
                    }
                    total += Math.max(0, want);
                    continue;
                }
                if (contact.getExtras() != null
                        && contact.getExtras().getInt("unreadMessageCount", 0) != 0) {
                    contact.setUnreadCount(0);
                }
            }
            Contacts.getInstance().updateTotalUnreadCount();
        } catch (Exception e) {
            Log.w(TAG, "publishCounts", e);
        }
        return total;
    }

    private static boolean isSelf(String senderUid, String selfUid) {
        return senderUid != null && selfUid != null && senderUid.equals(selfUid);
    }

    private static String sealKey(String conversationId) {
        return SEAL_PREFIX + conversationId;
    }

    private static SharedPreferences prefs() {
        MapView mapView = MapView.getMapView();
        if (mapView == null || mapView.getContext() == null) {
            return null;
        }
        return PreferenceManager.getDefaultSharedPreferences(mapView.getContext());
    }
}
