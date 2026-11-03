package com.uvpro.plugin.modem;

/**
 * Bell 202 AFSK modulator (1200 baud, mark 1200 Hz, space 2200 Hz) for a radio with no TNC,
 * such as a DM-32 behind a Digirig Lite. Output is 16-bit mono PCM for {@code AudioTrack}.
 *
 * One call turns one AX.25 frame (no FCS, same bytes as {@code Ax25Frame.encode()}) into a
 * complete transmission: TXDELAY flags, the HDLC frame with FCS and bit stuffing, then tail
 * flags. Bits are NRZI coded (0 = tone change, 1 = no change) and sent LSB first. Phase is
 * continuous across tone changes so the radio's audio filters see no clicks.
 *
 * Plain Java, no Android types, so {@code tools/AfskLoopback.java} can run it on a desktop JVM.
 */
public final class AfskModulator {

    public static final int BAUD = 1200;
    public static final double MARK_HZ = 1200.0;
    public static final double SPACE_HZ = 2200.0;

    private static final int FLAG = 0x7E;

    private final int sampleRate;
    private final double amplitude;
    private final int txDelayMs;
    private final int txTailMs;

    /**
     * @param sampleRate output rate, normally 48000 for a USB codec
     * @param amplitude  peak level 0..1 of full scale; set to suit the radio's mic input
     * @param txDelayMs  flags sent before the frame while the transmitter and the far
     *                   receiver settle (VOX keying needs more than GPIO PTT)
     * @param txTailMs   flags sent after the frame so the end is not clipped on unkey
     */
    public AfskModulator(int sampleRate, double amplitude, int txDelayMs, int txTailMs) {
        this.sampleRate = sampleRate;
        this.amplitude = Math.max(0.0, Math.min(1.0, amplitude));
        this.txDelayMs = Math.max(0, txDelayMs);
        this.txTailMs = Math.max(0, txTailMs);
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public short[] modulate(byte[] ax25Frame) {
        return modulate(new byte[][] {ax25Frame});
    }

    /** Several frames in one key-up, separated by a single shared flag. */
    public short[] modulate(byte[][] ax25Frames) {
        BitSink bits = new BitSink();
        int leadFlags = Math.max(1, flagsFor(txDelayMs));
        for (int i = 0; i < leadFlags; i++) {
            bits.flag();
        }
        for (byte[] frame : ax25Frames) {
            int fcs = HdlcFcs.compute(frame, 0, frame.length);
            for (byte b : frame) {
                bits.stuffedByte(b & 0xFF);
            }
            bits.stuffedByte(fcs & 0xFF);
            bits.stuffedByte((fcs >>> 8) & 0xFF);
            bits.flag();
        }
        int tailFlags = Math.max(1, flagsFor(txTailMs));
        for (int i = 1; i < tailFlags; i++) {
            bits.flag();
        }
        return render(bits);
    }

    private int flagsFor(int ms) {
        return (int) Math.ceil(ms * BAUD / 8000.0);
    }

    private short[] render(BitSink bits) {
        double samplesPerBit = (double) sampleRate / BAUD;
        int total = (int) Math.ceil(bits.size * samplesPerBit);
        short[] out = new short[total];
        double phase = 0.0;
        double markStep = 2.0 * Math.PI * MARK_HZ / sampleRate;
        double spaceStep = 2.0 * Math.PI * SPACE_HZ / sampleRate;
        boolean mark = true;
        double peak = amplitude * Short.MAX_VALUE;
        int sample = 0;
        for (int i = 0; i < bits.size; i++) {
            if (!bits.get(i)) {
                mark = !mark;
            }
            int end = (int) Math.round((i + 1) * samplesPerBit);
            double step = mark ? markStep : spaceStep;
            for (; sample < end && sample < total; sample++) {
                out[sample] = (short) Math.round(Math.sin(phase) * peak);
                phase += step;
                if (phase > 2.0 * Math.PI) {
                    phase -= 2.0 * Math.PI;
                }
            }
        }
        return out;
    }

    /** Growable on-air bit list (pre-NRZI) with HDLC zero-bit insertion. */
    private static final class BitSink {
        private boolean[] data = new boolean[4096];
        private int size;
        private int ones;

        void flag() {
            for (int i = 0; i < 8; i++) {
                add(((FLAG >>> i) & 1) != 0);
            }
            ones = 0;
        }

        void stuffedByte(int value) {
            for (int i = 0; i < 8; i++) {
                boolean bit = ((value >>> i) & 1) != 0;
                add(bit);
                if (bit) {
                    if (++ones == 5) {
                        add(false);
                        ones = 0;
                    }
                } else {
                    ones = 0;
                }
            }
        }

        private void add(boolean bit) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = bit;
        }

        boolean get(int i) {
            return data[i];
        }
    }
}
