package com.uvpro.plugin.protocol;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits large payloads into radio-sized fragments and reassembles them.
 *
 * AX.25 info field is limited to ~256 bytes. Large CoT XML (even gzipped)
 * may exceed this, requiring fragmentation.
 *
 * Fragment header (4 bytes):
 *   [FragmentID: 2 bytes] [SeqNum: 1 byte] [TotalFragments: 1 byte]
 *
 * When TotalFragments == 0, the packet is unfragmented (no header overhead).
 */
public class PacketFragmenter {

    private static final String TAG = "UVPro.Fragment";

    /**
     * Maximum payload size per AX.25 info field.
     * AX.25 max is 256, minus our UV-PRO TLV header (3 bytes) and
     * fragment header (4 bytes), we keep payload at 240 bytes max.
     */
    public static final int MAX_FRAGMENT_PAYLOAD = 240;

    /** Fragment header size */
    public static final int FRAGMENT_HEADER_SIZE = 4;

    /** Counter for generating fragment IDs */
    private static int nextFragmentId = 0;

    /**
     * Fragment a payload if it exceeds the maximum frame size.
     *
     * @param type    UV-PRO packet type
     * @param payload The full payload to fragment
     * @return List of UVProPacket instances ready for AX.25 framing.
     *         If payload fits in one frame, returns a single unfragmented packet.
     */
    public static List<UVProPacket> fragment(int type, byte[] payload) {
        return fragment(type, payload, MAX_FRAGMENT_PAYLOAD);
    }

    /**
     * @param maxPayload largest UV-PRO payload, including the 4-byte fragment header
     *                   when the payload must be split
     */
    public static List<UVProPacket> fragment(int type, byte[] payload, int maxPayload) {
        List<UVProPacket> results = new ArrayList<>();
        int limit = maxPayload < (FRAGMENT_HEADER_SIZE + 1)
                ? MAX_FRAGMENT_PAYLOAD : maxPayload;

        if (payload.length <= limit) {
            // No fragmentation needed
            results.add(new UVProPacket((byte) type, payload));
            return results;
        }

        // Must fragment — use TYPE_COT_FRAGMENT for CoT fragments
        int fragType = (type == UVProPacket.TYPE_COT)
                ? UVProPacket.TYPE_COT_FRAGMENT : type;

        int fragmentId = getNextFragmentId();
        int maxPayloadPerFrag = limit - FRAGMENT_HEADER_SIZE;
        int totalFragments = (payload.length + maxPayloadPerFrag - 1)
                / maxPayloadPerFrag;

        if (totalFragments > 255) {
            Log.e(TAG, "Payload too large to fragment: " + payload.length
                    + " bytes would require " + totalFragments + " fragments");
            return results; // Return empty — can't send
        }

        Log.d(TAG, "Fragmenting " + payload.length + " bytes into "
                + totalFragments + " fragments (id=" + fragmentId + ")");

        for (int i = 0; i < totalFragments; i++) {
            int offset = i * maxPayloadPerFrag;
            int len = Math.min(maxPayloadPerFrag, payload.length - offset);

            ByteBuffer buf = ByteBuffer.allocate(FRAGMENT_HEADER_SIZE + len);
            buf.putShort((short) fragmentId);
            buf.put((byte) i);
            buf.put((byte) totalFragments);
            buf.put(payload, offset, len);

            results.add(new UVProPacket((byte) fragType, buf.array()));
        }

        return results;
    }

    private static synchronized int getNextFragmentId() {
        int id = nextFragmentId;
        nextFragmentId = (nextFragmentId + 1) & 0xFFFF;
        return id;
    }

    /**
     * Reassembler: collects fragments and emits complete payloads.
     *
     * Thread-safe. Fragments are identified by their fragmentId.
     * Incomplete fragment sets are expired after a timeout.
     */
    public static class Reassembler {

        private static final long FRAGMENT_TIMEOUT_MS = 30_000; // 30 seconds

        private final Map<Integer, FragmentSet> pending = new HashMap<>();

        /**
         * Add a fragment. If this completes a set, returns the full payload.
         *
         * @param fragmentPayload The fragment data including the 4-byte header
         * @return Complete reassembled payload, or null if still waiting
         */
        public synchronized byte[] addFragment(byte[] fragmentPayload) {
            if (fragmentPayload.length < FRAGMENT_HEADER_SIZE) {
                Log.w(TAG, "Fragment too short: " + fragmentPayload.length);
                return null;
            }

            ByteBuffer buf = ByteBuffer.wrap(fragmentPayload);
            int fragmentId = buf.getShort() & 0xFFFF;
            int seqNum = buf.get() & 0xFF;
            int totalFragments = buf.get() & 0xFF;

            if (totalFragments == 0 || seqNum >= totalFragments) {
                Log.w(TAG, "Invalid fragment: seq=" + seqNum
                        + " total=" + totalFragments);
                return null;
            }

            // Expire old fragment sets
            expireOld();

            FragmentSet set = pending.get(fragmentId);
            if (set == null) {
                set = new FragmentSet(fragmentId, totalFragments);
                pending.put(fragmentId, set);
            }

            // Copy data (without header)
            byte[] data = new byte[fragmentPayload.length - FRAGMENT_HEADER_SIZE];
            System.arraycopy(fragmentPayload, FRAGMENT_HEADER_SIZE,
                    data, 0, data.length);

            set.addFragment(seqNum, data);

            if (set.isComplete()) {
                pending.remove(fragmentId);
                byte[] result = set.reassemble();
                Log.d(TAG, "Reassembled fragment set " + fragmentId
                        + ": " + result.length + " bytes");
                return result;
            }

            Log.d(TAG, "Fragment " + seqNum + "/" + totalFragments
                    + " of set " + fragmentId + " received ("
                    + set.getReceivedCount() + "/" + totalFragments + ")");
            return null;
        }

        private void expireOld() {
            long now = System.currentTimeMillis();
            pending.entrySet().removeIf(entry ->
                    now - entry.getValue().createdAt > FRAGMENT_TIMEOUT_MS);
        }

        /**
         * Internal: Holds fragments for a single payload.
         */
        private static class FragmentSet {
            final int fragmentId;
            final int totalFragments;
            final byte[][] fragments;
            int receivedCount;
            final long createdAt;

            FragmentSet(int id, int total) {
                this.fragmentId = id;
                this.totalFragments = total;
                this.fragments = new byte[total][];
                this.receivedCount = 0;
                this.createdAt = System.currentTimeMillis();
            }

            void addFragment(int seq, byte[] data) {
                if (seq >= 0 && seq < totalFragments
                        && fragments[seq] == null) {
                    fragments[seq] = data;
                    receivedCount++;
                }
            }

            boolean isComplete() {
                return receivedCount == totalFragments;
            }

            int getReceivedCount() {
                return receivedCount;
            }

            byte[] reassemble() {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                for (byte[] fragment : fragments) {
                    if (fragment != null) {
                        out.write(fragment, 0, fragment.length);
                    }
                }
                return out.toByteArray();
            }
        }
    }
}
