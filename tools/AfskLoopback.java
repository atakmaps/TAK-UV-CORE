import com.uvpro.plugin.ax25.Ax25Frame;
import com.uvpro.plugin.modem.AfskDemodulator;
import com.uvpro.plugin.modem.AfskModulator;
import com.uvpro.plugin.modem.HdlcFcs;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Loopback check for the software AFSK modem in {@code com.uvpro.plugin.modem}.
 * Not a Gradle test — the plugin has no unit-test harness. The modem and Ax25Frame are plain
 * Java, so this runs on a desktop JVM with no ATAK jar:
 *
 * <pre>
 * S=app/src/main/java/com/uvpro/plugin
 * javac -d build/afsk $S/modem/*.java $S/ax25/Ax25Frame.java tools/AfskLoopback.java
 * java -cp build/afsk AfskLoopback
 * </pre>
 *
 * Each scenario modulates random frames, damages the audio the way a radio path can (noise,
 * level, clipping, pre/de-emphasis tilt, DC offset, rate), demodulates it in random-sized
 * chunks, and counts how many frames came back byte-for-byte.
 */
public final class AfskLoopback {

    private static final int FRAMES_PER_SCENARIO = 60;
    private static final Random RNG = new Random(47);
    private static int failures;

    public static void main(String[] args) {
        checkFcsVector();
        checkAx25RoundTrip();

        System.out.println();
        System.out.printf("%-38s %8s %6s%n", "scenario", "decoded", "false");
        for (int rate : new int[] {48000, 44100, 22050, 16000}) {
            run("clean @" + rate, rate, 0.5, Double.NaN, 0, 0, 0.0, 1.0, true);
        }
        run("quiet (-26 dBFS)", 48000, 0.05, Double.NaN, 0, 0, 0.0, 1.0, true);
        run("hard clipped (2x over)", 48000, 1.0, Double.NaN, 0, 0, 0.0, 2.0, true);
        run("DC offset +0.2", 48000, 0.5, Double.NaN, 0, 0, 0.2, 1.0, true);
        run("de-emphasis only (lp 1 kHz)", 48000, 0.5, Double.NaN, 1000, 0, 0.0, 1.0, true);
        run("pre-emphasis only (hp diff)", 48000, 0.5, Double.NaN, 0, 1, 0.0, 1.0, true);
        run("SNR 20 dB", 48000, 0.5, 20, 0, 0, 0.0, 1.0, true);
        run("SNR 12 dB", 48000, 0.5, 12, 0, 0, 0.0, 1.0, false);
        run("SNR 8 dB", 48000, 0.5, 8, 0, 0, 0.0, 1.0, false);
        run("SNR 6 dB", 48000, 0.5, 6, 0, 0, 0.0, 1.0, false);
        run("SNR 12 dB + de-emphasis", 48000, 0.5, 12, 1000, 0, 0.0, 1.0, false);
        // Radio audio noise sits in the voice band, so in-band SNR is what matters.
        for (int snr : new int[] {12, 9, 6, 3}) {
            run("voice-band SNR " + snr + " dB", 48000, 0.5, -snr, 0, 0, 0.0, 1.0, false);
        }
        checkBackToBack();
        checkCarrierDetect();
        reportSpeed();

        if (failures > 0) {
            System.out.println("LOOPBACK_FAIL (" + failures + ")");
            System.exit(1);
        }
        System.out.println("LOOPBACK_OK");
    }

    /** CRC-16/X.25 check value from the CRC catalogue. */
    private static void checkFcsVector() {
        byte[] v = "123456789".getBytes(StandardCharsets.US_ASCII);
        int fcs = HdlcFcs.compute(v, 0, v.length);
        expect(fcs == 0x906E, String.format("FCS of 123456789 = 0x%04X (want 0x906E)", fcs));
    }

    /** A real plugin frame survives modulate → demodulate and Ax25Frame.decode reads it back. */
    private static void checkAx25RoundTrip() {
        Ax25Frame out = Ax25Frame.createUVProFrame("N0CALL", 7,
                "uvpro test 39.0997,-94.5786".getBytes(StandardCharsets.US_ASCII));
        byte[] wire = out.encode();
        List<byte[]> got = demodulate(new AfskModulator(48000, 0.5, 300, 30).modulate(wire), 48000);
        expect(got.size() == 1 && Arrays.equals(got.get(0), wire), "Ax25Frame round trip");
        if (!got.isEmpty()) {
            Ax25Frame in = Ax25Frame.decode(got.get(0));
            expect(in != null && "N0CALL".equals(in.getSrcCallsign()) && in.getSrcSsid() == 7
                    && "OPENRL".equals(in.getDestCallsign()), "Ax25Frame.decode fields");
        }
    }

    /** Two frames in one key-up sharing a flag, as AfskModulator.modulate(byte[][]) sends. */
    private static void checkBackToBack() {
        byte[] a = randomFrame();
        byte[] b = randomFrame();
        List<byte[]> got = demodulate(
                new AfskModulator(48000, 0.5, 300, 30).modulate(new byte[][] {a, b}), 48000);
        boolean ok = got.size() == 2 && Arrays.equals(got.get(0), a) && Arrays.equals(got.get(1), b);
        System.out.printf("%-38s %8s %6s%n", "two frames, one key-up", ok ? "2/2" : got.size() + "/2", "-");
        expect(ok, "back-to-back frames");
    }

    private static void run(String label, int rate, double amplitude, double snrDb,
                            double lowpassHz, int highpass, double dc, double overdrive,
                            boolean mustBePerfect) {
        AfskModulator mod = new AfskModulator(rate, amplitude, 300, 30);
        List<byte[]> sent = new ArrayList<>();
        List<Double> audio = new ArrayList<>();
        for (int i = 0; i < FRAMES_PER_SCENARIO; i++) {
            byte[] frame = randomFrame();
            sent.add(frame);
            appendSilence(audio, rate, 50 + RNG.nextInt(400));
            for (short s : mod.modulate(frame)) {
                audio.add(s / 32768.0);
            }
        }
        appendSilence(audio, rate, 200);

        double[] x = new double[audio.size()];
        for (int i = 0; i < x.length; i++) {
            x[i] = audio.get(i);
        }
        if (lowpassHz > 0) {
            lowpass(x, rate, lowpassHz);
            normalise(x, amplitude);
        }
        if (highpass > 0) {
            for (int i = x.length - 1; i > 0; i--) {
                x[i] = x[i] - 0.9 * x[i - 1];
            }
            normalise(x, amplitude);
        }
        if (!Double.isNaN(snrDb)) {
            // Negative snrDb means the same SNR with noise band-limited to ~300-3000 Hz.
            boolean voiceBand = snrDb < 0;
            double signalRms = amplitude / Math.sqrt(2.0);
            double noiseRms = signalRms / Math.pow(10.0, Math.abs(snrDb) / 20.0);
            double[] noise = new double[x.length];
            for (int i = 0; i < noise.length; i++) {
                noise[i] = RNG.nextGaussian();
            }
            if (voiceBand) {
                lowpass(noise, rate, 3000);
                lowpass(noise, rate, 3000);
                for (int i = noise.length - 1; i > 0; i--) {
                    noise[i] = noise[i] - 0.96 * noise[i - 1];
                }
            }
            double scale = noiseRms / rms(noise);
            for (int i = 0; i < x.length; i++) {
                x[i] += noise[i] * scale;
            }
        }
        short[] pcm = new short[x.length];
        for (int i = 0; i < x.length; i++) {
            double v = (x[i] * overdrive + dc) * 32768.0;
            pcm[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
        }

        List<byte[]> got = demodulate(pcm, rate);
        int matched = 0;
        int bogus = 0;
        List<byte[]> pending = new ArrayList<>(sent);
        for (byte[] g : got) {
            boolean hit = false;
            for (int i = 0; i < pending.size(); i++) {
                if (Arrays.equals(pending.get(i), g)) {
                    pending.remove(i);
                    hit = true;
                    break;
                }
            }
            if (hit) {
                matched++;
            } else {
                bogus++;
            }
        }
        System.out.printf("%-38s %5d/%-2d %6d%n", label, matched, sent.size(), bogus);
        expect(bogus == 0, label + ": false frames");
        if (mustBePerfect) {
            expect(matched == sent.size(), label + ": lost frames");
        }
    }

    private static List<byte[]> demodulate(short[] pcm, int rate) {
        List<byte[]> got = new ArrayList<>();
        AfskDemodulator demod = new AfskDemodulator(rate, got::add);
        int pos = 0;
        while (pos < pcm.length) {
            int n = Math.min(pcm.length - pos, 64 + RNG.nextInt(4096));
            demod.process(pcm, pos, n);
            pos += n;
        }
        return got;
    }

    /** Valid AX.25 UI address/control/PID header, then a random payload heavy in 0xFF runs. */
    private static byte[] randomFrame() {
        byte[] info = new byte[8 + RNG.nextInt(240)];
        for (int i = 0; i < info.length; i++) {
            info[i] = RNG.nextInt(4) == 0 ? (byte) 0xFF : (byte) RNG.nextInt(256);
        }
        return new Ax25Frame("N0CALL", RNG.nextInt(16), "OPENRL", 0, info).encode();
    }

    private static void appendSilence(List<Double> audio, int rate, int ms) {
        for (int i = 0; i < rate * ms / 1000; i++) {
            audio.add(0.0);
        }
    }

    private static void lowpass(double[] x, int rate, double hz) {
        double a = 1.0 - Math.exp(-2.0 * Math.PI * hz / rate);
        double y = 0.0;
        for (int i = 0; i < x.length; i++) {
            y += a * (x[i] - y);
            x[i] = y;
        }
    }

    /**
     * The transmitter holds off while {@link AfskDemodulator#isCarrierDetected()} is true, so it
     * must be true during a packet and almost never on noise alone (else we never transmit).
     */
    private static void checkCarrierDetect() {
        int rate = 48000;
        int chunk = rate / 50;
        double[] noise = new double[rate * 30];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = RNG.nextGaussian();
        }
        lowpass(noise, rate, 3000);
        lowpass(noise, rate, 3000);
        normalise(noise, 0.5);
        short[] noisePcm = new short[noise.length];
        for (int i = 0; i < noise.length; i++) {
            noisePcm[i] = (short) Math.round(noise[i] * 32767);
        }
        AfskDemodulator demod = new AfskDemodulator(rate, f -> { });
        int busy = 0;
        int chunks = 0;
        for (int pos = 0; pos + chunk <= noisePcm.length; pos += chunk) {
            demod.process(noisePcm, pos, chunk);
            chunks++;
            if (demod.isCarrierDetected()) {
                busy++;
            }
        }
        double falseBusy = 100.0 * busy / chunks;

        short[] packet = new AfskModulator(rate, 0.5, 300, 30).modulate(randomFrame());
        AfskDemodulator demod2 = new AfskDemodulator(rate, f -> { });
        int heard = 0;
        int packetChunks = 0;
        // Skip the first 100 ms of lead-in while the PLL and AGC settle.
        for (int pos = 0; pos + chunk <= packet.length; pos += chunk) {
            demod2.process(packet, pos, chunk);
            if (pos >= rate / 10) {
                packetChunks++;
                if (demod2.isCarrierDetected()) {
                    heard++;
                }
            }
        }
        double hit = 100.0 * heard / Math.max(1, packetChunks);
        System.out.printf("%-38s %7.1f%% %6s%n", "carrier detect during packet", hit, "-");
        System.out.printf("%-38s %7.1f%% %6s%n", "carrier detect on noise (false busy)", falseBusy, "-");
        expect(hit >= 90.0, "carrier detect missed the packet");
        expect(falseBusy <= 5.0, "carrier detect fires on noise");
    }

    /** Demodulator throughput on this JVM; a phone is several times slower than a desktop. */
    private static void reportSpeed() {
        int rate = 48000;
        short[] pcm = new short[rate * 60];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (RNG.nextGaussian() * 3000);
        }
        AfskDemodulator demod = new AfskDemodulator(rate, f -> { });
        long t0 = System.nanoTime();
        demod.process(pcm, 0, pcm.length);
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("%nspeed: 60 s of 48 kHz audio in %.2f s (%.0fx real time)%n", secs, 60 / secs);
    }

    private static double rms(double[] x) {
        double sum = 0.0;
        for (double v : x) {
            sum += v * v;
        }
        return Math.sqrt(sum / Math.max(1, x.length));
    }

    private static void normalise(double[] x, double peak) {
        double max = 1e-12;
        for (double v : x) {
            max = Math.max(max, Math.abs(v));
        }
        for (int i = 0; i < x.length; i++) {
            x[i] = x[i] / max * peak;
        }
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
