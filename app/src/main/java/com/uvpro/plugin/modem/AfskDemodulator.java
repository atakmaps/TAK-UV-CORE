package com.uvpro.plugin.modem;

/**
 * Bell 202 AFSK demodulator (1200 baud) for 16-bit mono PCM from {@code AudioRecord}.
 *
 * Signal path per sample:
 * <ol>
 *   <li>Mark and space quadrature correlators over a one-bit sliding window give a
 *       phase-independent amplitude for each tone.</li>
 *   <li>Each amplitude has its own AGC (fast attack, slow decay), so a level mismatch between
 *       the tones does not bias the decision.</li>
 *   <li>Several slicers compare {@code mark * gain - space} at different gains. FM pre-emphasis
 *       and de-emphasis rarely match on cheap handhelds, which leaves one tone louder; one of
 *       the slicers usually lands near the right balance. Each slicer has its own DPLL clock
 *       recovery, NRZI decode, and {@link HdlcDeframer}.</li>
 *   <li>A frame that more than one slicer decodes is delivered once.</li>
 * </ol>
 *
 * Not thread-safe: call {@link #process} from the single audio read thread. Plain Java, no
 * Android types, so {@code tools/AfskLoopback.java} can run it on a desktop JVM.
 */
public final class AfskDemodulator {

    public interface FrameListener {
        /** One AX.25 frame, FCS already checked and removed (same bytes a KISS TNC delivers). */
        void onFrame(byte[] ax25Frame);
    }

    private static final double[] SLICER_GAINS = {0.5, 0.71, 1.0, 1.41, 2.0};
    private static final double AGC_ATTACK = 0.70;
    private static final double AGC_DECAY = 0.00009;
    private static final double PLL_INERTIA_SEARCHING = 0.74;
    private static final double PLL_INERTIA_LOCKED = 0.89;

    private final FrameListener listener;
    private final int sampleRate;
    private final int window;
    private final double markStep;
    private final double spaceStep;
    private final int pllStep;
    private final long dedupeSamples;

    private final double[] markI;
    private final double[] markQ;
    private final double[] spaceI;
    private final double[] spaceQ;
    private double sumMarkI;
    private double sumMarkQ;
    private double sumSpaceI;
    private double sumSpaceQ;
    private int ringPos;
    private double markPhase;
    private double spacePhase;

    private double markPeak = 1e-9;
    private double spacePeak = 1e-9;
    private double inputPeak;

    private final Slicer[] slicers = new Slicer[SLICER_GAINS.length];

    private long sampleCount;
    private long framesDecoded;
    private volatile boolean carrierDetected;
    private int lastFcs = -1;
    private int lastLen = -1;
    private long lastFrameSample = Long.MIN_VALUE;

    public AfskDemodulator(int sampleRate, FrameListener listener) {
        this.sampleRate = sampleRate;
        this.listener = listener;
        this.window = Math.max(4, (int) Math.round((double) sampleRate / AfskModulator.BAUD));
        this.markStep = 2.0 * Math.PI * AfskModulator.MARK_HZ / sampleRate;
        this.spaceStep = 2.0 * Math.PI * AfskModulator.SPACE_HZ / sampleRate;
        this.pllStep = (int) Math.round(4294967296.0 * AfskModulator.BAUD / sampleRate);
        this.dedupeSamples = sampleRate / 4;
        markI = new double[window];
        markQ = new double[window];
        spaceI = new double[window];
        spaceQ = new double[window];
        for (int i = 0; i < slicers.length; i++) {
            slicers[i] = new Slicer(SLICER_GAINS[i]);
        }
    }

    public int getSampleRate() {
        return sampleRate;
    }

    /** Frames delivered so far (after de-duplication across slicers). */
    public long getFramesDecoded() {
        return framesDecoded;
    }

    /**
     * Recent input peak, 0..1 of full scale, decaying over roughly a second. For a level meter
     * when setting the radio volume: aim well under 1.0 so the codec does not clip.
     */
    public double getInputPeak() {
        return inputPeak;
    }

    /**
     * True while any slicer hears packet preamble or data. Updated once per {@link #process}
     * call, so another thread (the transmitter) can read it to hold off on a busy channel.
     */
    public boolean isCarrierDetected() {
        return carrierDetected;
    }

    public void process(short[] pcm, int offset, int length) {
        double levelDecay = 1.0 / sampleRate;
        for (int n = offset; n < offset + length; n++) {
            double x = pcm[n] / 32768.0;
            double ax = Math.abs(x);
            inputPeak = ax > inputPeak ? ax : inputPeak * (1.0 - levelDecay);
            step(x);
        }
        boolean carrier = false;
        for (Slicer slicer : slicers) {
            carrier |= slicer.deframer.isCarrier();
        }
        carrierDetected = carrier;
    }

    private void step(double x) {
        double mi = x * Math.cos(markPhase);
        double mq = x * Math.sin(markPhase);
        double si = x * Math.cos(spacePhase);
        double sq = x * Math.sin(spacePhase);
        markPhase += markStep;
        if (markPhase > 2.0 * Math.PI) {
            markPhase -= 2.0 * Math.PI;
        }
        spacePhase += spaceStep;
        if (spacePhase > 2.0 * Math.PI) {
            spacePhase -= 2.0 * Math.PI;
        }

        sumMarkI += mi - markI[ringPos];
        sumMarkQ += mq - markQ[ringPos];
        sumSpaceI += si - spaceI[ringPos];
        sumSpaceQ += sq - spaceQ[ringPos];
        markI[ringPos] = mi;
        markQ[ringPos] = mq;
        spaceI[ringPos] = si;
        spaceQ[ringPos] = sq;
        if (++ringPos == window) {
            ringPos = 0;
        }

        double markAmp = Math.sqrt(sumMarkI * sumMarkI + sumMarkQ * sumMarkQ);
        double spaceAmp = Math.sqrt(sumSpaceI * sumSpaceI + sumSpaceQ * sumSpaceQ);
        markPeak = agc(markPeak, markAmp);
        spacePeak = agc(spacePeak, spaceAmp);
        double m = markAmp / markPeak;
        double s = spaceAmp / spacePeak;

        for (Slicer slicer : slicers) {
            slicer.step(m * slicer.gain - s);
        }
        sampleCount++;
    }

    private static double agc(double peak, double value) {
        double next = value > peak
                ? peak + (value - peak) * AGC_ATTACK
                : peak - (peak - value) * AGC_DECAY;
        return Math.max(next, 1e-9);
    }

    private void deliver(byte[] frame, int fcs) {
        if (fcs == lastFcs && frame.length == lastLen
                && sampleCount - lastFrameSample < dedupeSamples) {
            return;
        }
        lastFcs = fcs;
        lastLen = frame.length;
        lastFrameSample = sampleCount;
        framesDecoded++;
        listener.onFrame(frame);
    }

    /** One decision threshold with its own bit clock and deframer. */
    private final class Slicer {
        final double gain;
        private final HdlcDeframer deframer =
                new HdlcDeframer(AfskDemodulator.this::deliver);
        private int pll;
        private boolean lastLevel;
        private boolean lastRawBit;

        Slicer(double gain) {
            this.gain = gain;
        }

        void step(double demod) {
            boolean level = demod > 0;
            int prev = pll;
            pll += pllStep;
            if (prev > 0 && pll < 0) {
                // Bit centre: NRZI — no tone change is a 1, a change is a 0.
                deframer.onBit(level == lastRawBit ? 1 : 0);
                lastRawBit = level;
            }
            if (level != lastLevel) {
                double inertia = deframer.isInFrame() ? PLL_INERTIA_LOCKED : PLL_INERTIA_SEARCHING;
                pll = (int) (pll * inertia);
                lastLevel = level;
            }
        }
    }
}
