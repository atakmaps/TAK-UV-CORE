package com.uvpro.plugin.modem;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.protocol.PacketRouter;
import com.uvpro.plugin.ui.SettingsFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Transmit side of the software modem: a radio with no TNC (DM-32 on an analog FM channel)
 * behind a USB audio interface (Digirig Lite).
 *
 * Extends {@link BtConnectionManager} the way {@code MeshBtConnectionManager} does, so every
 * existing {@code sendKissFrame} caller — beacons, chat, CoT, ACKs, pings — can use it unchanged.
 * The Bluetooth half of the base class is detached and its connect/scan entry points are no-ops.
 *
 * {@link #sendKissFrame} queues the frame and returns. A TX thread batches frames queued close
 * together into one key-up, waits while {@link UsbAudioModemReceiver#isChannelBusy()} hears
 * another station, keys PTT (CM108 GPIO, or nothing for VOX), plays the AFSK audio to the USB
 * output, waits for playback to drain, and unkeys.
 *
 * "Connected" means the user turned on Digirig transmit, a USB audio output is attached, and
 * (for GPIO PTT) the HID interface is open. Radio Silence on either Bluetooth radio also blocks
 * this transport, matching the one panel button.
 */
public final class AudioModemTransport extends BtConnectionManager {

    private static final String TAG = "UVProAudioModem";
    private static final int SAMPLE_RATE = 48000;
    private static final int TX_TAIL_MS = 50;
    private static final long BATCH_WINDOW_MS = 40;
    private static final int BATCH_MAX_FRAMES = 6;
    private static final int BATCH_MAX_BYTES = 1200;
    private static final long BUSY_WAIT_MAX_MS = 5000;
    private static final int MAX_KEYUP_SECONDS = 12;
    /** USB output sits behind the reported playback head; keep PTT up a little longer. */
    private static final long DRAIN_MARGIN_MS = 60;

    private final Context context;
    private final PacketRouter packetRouter;
    private final UsbAudioModemReceiver receiver;
    private final BtConnectionManager uvproManager;
    private final BtConnectionManager meshManager;
    private final AudioManager audioManager;
    private final SharedPreferences prefs;
    private final Cm108Ptt ptt;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(64);
    private final Random random = new Random();

    private volatile boolean txEnabled;
    private volatile boolean running;
    private volatile boolean lastReady;
    private volatile Runnable stateListener;
    private Thread txThread;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (p, key) -> {
        if (SettingsFragment.PREF_AUDIO_MODEM_TX_ENABLED.equals(key)
                || SettingsFragment.PREF_AUDIO_MODEM_PTT.equals(key)) {
            refresh();
        }
    };

    private final AudioDeviceCallback deviceCallback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                    refresh();
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    refresh();
                }
            }
            : null;

    /** @param atakContext ATAK's context (USB grants, audio and prefs belong to ATAK's process) */
    public AudioModemTransport(Context atakContext, PacketRouter packetRouter,
                               UsbAudioModemReceiver receiver,
                               BtConnectionManager uvproManager,
                               BtConnectionManager meshManager) {
        super(atakContext, packetRouter);
        detachClassicBtAutoConnect();
        this.context = atakContext.getApplicationContext();
        this.packetRouter = packetRouter;
        this.receiver = receiver;
        this.uvproManager = uvproManager;
        this.meshManager = meshManager;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
        this.ptt = new Cm108Ptt(context, this::refreshReadiness);
    }

    /** Called on the main thread whenever {@link #isConnected()} changes. */
    public void setStateListener(Runnable listener) {
        this.stateListener = listener;
    }

    public void start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "USB audio device selection needs Android 6.0+; audio transmit disabled");
            return;
        }
        running = true;
        txThread = new Thread(this::txLoop, "UVProAudioModemTx");
        txThread.start();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler);
        refresh();
    }

    @Override
    public void shutdown() {
        running = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback);
        }
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        queue.clear();
        if (txThread != null) {
            txThread.interrupt();
            try {
                txThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            txThread = null;
        }
        ptt.shutdown();
        super.shutdown();
    }

    /** The user turned on Digirig transmit (it may still be unplugged). */
    public boolean isTransmitSelected() {
        return txEnabled;
    }

    @Override
    public boolean isConnected() {
        if (!txEnabled || findUsbOutput() == null) {
            return false;
        }
        return SettingsFragment.isAudioModemVoxPtt(context) || ptt.isReady();
    }

    @Override
    public boolean isConnecting() {
        return false;
    }

    @Override
    public String getConnectedDeviceName() {
        AudioDeviceInfo out = findUsbOutput();
        return out != null ? "Digirig (" + out.getProductName() + ")" : null;
    }

    @Override
    public boolean isRadioSilenceEnabled() {
        return super.isRadioSilenceEnabled()
                || (uvproManager != null && uvproManager.isRadioSilenceEnabled())
                || (meshManager != null && meshManager.isRadioSilenceEnabled());
    }

    @Override
    public boolean sendKissFrame(byte[] ax25Frame) {
        if (ax25Frame == null || ax25Frame.length == 0) {
            return false;
        }
        if (!isConnected()) {
            Log.w(TAG, "Cannot send: Digirig transmit not ready");
            return false;
        }
        if (isRadioSilenceEnabled()) {
            Log.w(TAG, "TX blocked by Radio Silence");
            return false;
        }
        if (!queue.offer(ax25Frame.clone())) {
            Log.w(TAG, "TX queue full; dropping frame");
            return false;
        }
        return true;
    }

    /** No KISS link to command; TXDELAY comes from the Digirig TX Delay preference. */
    @Override
    public boolean primeKissTxTiming() {
        return false;
    }

    /** No radio-control channel on an audio interface. */
    @Override
    public boolean sendRawBytes(byte[] data) {
        return false;
    }

    // Bluetooth entry points of the base class do not apply to a USB audio interface.

    @Override
    public void connect(BluetoothDevice device) {
        Log.d(TAG, "connect() ignored: Digirig is USB, not Bluetooth");
    }

    @Override
    public void connectToLastDevice() {
    }

    @Override
    public void disconnect() {
        queue.clear();
    }

    @Override
    public void cancelConnectionAttempts() {
    }

    @Override
    public void startScan() {
    }

    @Override
    public void scheduleBootAutoConnect() {
    }

    @Override
    public void armPassiveReconnect() {
    }

    @Override
    public void resumeRadioAutoConnect() {
    }

    private void refresh() {
        txEnabled = SettingsFragment.isAudioModemTxEnabled(context);
        if (txEnabled && !SettingsFragment.isAudioModemVoxPtt(context) && findUsbOutput() != null) {
            ptt.open();
        } else {
            ptt.close();
        }
        refreshReadiness();
    }

    private void refreshReadiness() {
        boolean ready = isConnected();
        if (ready == lastReady) {
            return;
        }
        lastReady = ready;
        Log.i(TAG, ready ? "Digirig transmit ready" : "Digirig transmit not ready");
        Runnable l = stateListener;
        if (l != null) {
            mainHandler.post(l);
        }
    }

    private AudioDeviceInfo findUsbOutput() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return d;
            }
        }
        return null;
    }

    private void txLoop() {
        while (running) {
            try {
                List<byte[]> batch = new ArrayList<>();
                batch.add(queue.take());
                int bytes = batch.get(0).length;
                while (batch.size() < BATCH_MAX_FRAMES && bytes < BATCH_MAX_BYTES) {
                    byte[] next = queue.poll(BATCH_WINDOW_MS, TimeUnit.MILLISECONDS);
                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                    bytes += next.length;
                }
                waitForClearChannel();
                if (isRadioSilenceEnabled() || !isConnected()) {
                    Log.w(TAG, "Dropping " + batch.size() + " queued frame(s): "
                            + (isRadioSilenceEnabled() ? "Radio Silence" : "transmit not ready"));
                    continue;
                }
                transmit(batch);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                Log.e(TAG, "Audio transmit failed", e);
            }
        }
    }

    /** Hold off while another station's packet is on the air, then a short random backoff. */
    private void waitForClearChannel() throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + BUSY_WAIT_MAX_MS;
        boolean waited = false;
        while (receiver != null && receiver.isChannelBusy()
                && SystemClock.elapsedRealtime() < deadline) {
            waited = true;
            Thread.sleep(20);
        }
        if (waited) {
            Thread.sleep(50 + random.nextInt(200));
        }
    }

    private void transmit(List<byte[]> frames) throws InterruptedException {
        double level = SettingsFragment.getAudioModemTxLevelPercent(context) / 100.0;
        int txDelay = SettingsFragment.getAudioModemTxDelayMs(context);
        boolean vox = SettingsFragment.isAudioModemVoxPtt(context);
        short[] pcm = new AfskModulator(SAMPLE_RATE, level, txDelay, TX_TAIL_MS)
                .modulate(frames.toArray(new byte[0][]));
        if (pcm.length > SAMPLE_RATE * MAX_KEYUP_SECONDS) {
            Log.w(TAG, "Refusing " + (pcm.length / SAMPLE_RATE) + " s key-up");
            return;
        }
        AudioDeviceInfo out = findUsbOutput();
        if (out == null) {
            return;
        }
        AudioTrack track = buildTrack(out);
        if (track == null) {
            return;
        }
        boolean keyed = false;
        try {
            if (!vox) {
                keyed = ptt.set(true);
                if (!keyed) {
                    Log.w(TAG, "PTT did not key; not transmitting");
                    return;
                }
            }
            track.play();
            int written = 0;
            while (written < pcm.length) {
                int n = track.write(pcm, written, pcm.length - written);
                if (n < 0) {
                    Log.w(TAG, "AudioTrack.write error " + n);
                    return;
                }
                written += n;
            }
            long deadline = SystemClock.elapsedRealtime() + pcm.length * 1000L / SAMPLE_RATE + 1000;
            while (track.getPlaybackHeadPosition() < pcm.length
                    && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(10);
            }
            Thread.sleep(DRAIN_MARGIN_MS);
            Log.i(TAG, "Sent " + frames.size() + " frame(s), "
                    + (pcm.length * 1000L / SAMPLE_RATE) + " ms on air");
            for (int i = 0; i < frames.size(); i++) {
                packetRouter.notifyPacketTransmitted();
            }
        } finally {
            if (keyed) {
                ptt.set(false);
            }
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
            }
            track.release();
        }
    }

    private AudioTrack buildTrack(AudioDeviceInfo out) {
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, SAMPLE_RATE / 5 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (!track.setPreferredDevice(out)) {
                Log.w(TAG, "setPreferredDevice refused for " + out.getProductName());
            }
            return track;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not open USB audio output", e);
            return null;
        }
    }
}
