package com.uvpro.plugin.modem;

import java.io.ByteArrayOutputStream;

/**
 * HDLC receive side for one bit stream: flag detection, zero-bit removal, abort on seven
 * ones, and FCS check. Takes NRZI-decoded bits LSB first; hands out frames without the FCS.
 */
final class HdlcDeframer {

    interface Sink {
        void onFrame(byte[] ax25NoFcs, int fcs);
    }

    /** Address fields (14) + control (1) + FCS (2). */
    private static final int MIN_FRAME_WITH_FCS = 17;
    private static final int MAX_FRAME_WITH_FCS = 1024;

    private final Sink sink;
    private final ByteArrayOutputStream frame = new ByteArrayOutputStream(MAX_FRAME_WITH_FCS);

    private int pattern;
    private int ones;
    private int acc;
    private int accBits;
    private boolean collecting;
    private int bitsSinceFlag;
    private int consecutiveFlags;
    private boolean carrier;

    HdlcDeframer(Sink sink) {
        this.sink = sink;
    }

    /** True between a flag and the next flag or abort — the PLL holds its lock harder here. */
    boolean isInFrame() {
        return collecting;
    }

    /**
     * Data carrier detect: set by three back-to-back flags (a real preamble; random noise almost
     * never lines three up), cleared by an abort, which noise produces within a few dozen bits.
     */
    boolean isCarrier() {
        return carrier;
    }

    void onBit(int bit) {
        pattern = ((pattern >>> 1) | (bit << 7)) & 0xFF;
        bitsSinceFlag++;
        if (pattern == 0x7E) {
            consecutiveFlags = bitsSinceFlag == 8 ? consecutiveFlags + 1 : 1;
            bitsSinceFlag = 0;
            if (consecutiveFlags >= 3) {
                carrier = true;
            }
            // The flag's first seven bits have gone into acc; a byte-aligned frame leaves exactly 7.
            if (collecting && accBits == 7 && frame.size() >= MIN_FRAME_WITH_FCS) {
                emit();
            }
            start();
            return;
        }
        if (bit == 1) {
            if (++ones >= 7) {
                collecting = false;
                carrier = false;
                return;
            }
        } else {
            boolean stuffed = ones == 5;
            ones = 0;
            if (stuffed) {
                return;
            }
        }
        if (!collecting) {
            return;
        }
        acc = (acc >>> 1) | (bit << 7);
        if (++accBits == 8) {
            frame.write(acc);
            acc = 0;
            accBits = 0;
            if (frame.size() > MAX_FRAME_WITH_FCS) {
                collecting = false;
            }
        }
    }

    private void start() {
        collecting = true;
        ones = 0;
        acc = 0;
        accBits = 0;
        frame.reset();
    }

    private void emit() {
        byte[] raw = frame.toByteArray();
        if (!HdlcFcs.check(raw, raw.length)) {
            return;
        }
        int fcs = (raw[raw.length - 2] & 0xFF) | ((raw[raw.length - 1] & 0xFF) << 8);
        sink.onFrame(java.util.Arrays.copyOf(raw, raw.length - 2), fcs);
    }
}
