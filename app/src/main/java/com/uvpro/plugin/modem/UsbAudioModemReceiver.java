package com.uvpro.plugin.modem;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;

import com.uvpro.plugin.protocol.PacketRouter;
import com.uvpro.plugin.protocol.RfInboundTransport;
import com.uvpro.plugin.ui.SettingsFragment;

/**
 * Receive path for a radio with no TNC (for example a Baofeng DM-32 on an analog FM channel)
 * behind a USB audio interface such as a Digirig Lite.
 *
 * While {@link SettingsFragment#PREF_AUDIO_MODEM_RX_ENABLED} is on and a USB audio input is
 * attached, a thread records 16-bit mono PCM from that device, runs it through
 * {@link AfskDemodulator}, and hands each frame to
 * {@link PacketRouter#routeIncoming(byte[], int, RfInboundTransport)} tagged
 * {@link RfInboundTransport#AUDIO} — the same entry the UV-PRO KISS path uses.
 *
 * Plugging or unplugging the interface, or flipping the preference, starts and stops capture.
 * Recording uses ATAK's RECORD_AUDIO grant (the plugin runs in ATAK's process). Nothing here
 * keys the radio; {@link AudioModemTransport} transmits and asks {@link #isChannelBusy()} first.
 */
public final class UsbAudioModemReceiver {

    private static final String TAG = "UVProAudioModem";
    private static final int[] SAMPLE_RATES = {48000, 44100};
    private static final long LEVEL_LOG_INTERVAL_MS = 5000;

    private final Context context;
    private final PacketRouter packetRouter;
    private final AudioManager audioManager;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Object lock = new Object();
    private Thread captureThread;
    private volatile boolean running;
    private volatile String deviceName;
    private volatile double inputPeak;
    private volatile long framesDecoded;
    private volatile AfskDemodulator activeDemod;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (p, key) -> {
                if (SettingsFragment.PREF_AUDIO_MODEM_RX_ENABLED.equals(key)) {
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

    /** @param atakContext ATAK's context (preferences and audio belong to the ATAK process) */
    public UsbAudioModemReceiver(Context atakContext, PacketRouter packetRouter) {
        this.context = atakContext.getApplicationContext();
        this.packetRouter = packetRouter;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public void start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "USB audio device selection needs Android 6.0+; audio modem disabled");
            return;
        }
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler);
        refresh();
    }

    public void shutdown() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback);
        }
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        stopCapture();
    }

    public boolean isReceiving() {
        return running;
    }

    /** Product name of the interface being recorded, or null when idle. */
    public String getDeviceName() {
        return running ? deviceName : null;
    }

    /** Recent input peak 0..1; for setting the radio volume so the codec does not clip. */
    public double getInputPeak() {
        return inputPeak;
    }

    public long getFramesDecoded() {
        return framesDecoded;
    }

    /** True while packet preamble or data is being heard; false when not receiving at all. */
    public boolean isChannelBusy() {
        AfskDemodulator d = activeDemod;
        return running && d != null && d.isCarrierDetected();
    }

    private void refresh() {
        boolean wanted = SettingsFragment.isAudioModemRxEnabled(context);
        AudioDeviceInfo device = wanted ? findUsbInput() : null;
        synchronized (lock) {
            if (device == null) {
                if (captureThread != null) {
                    Log.i(TAG, wanted ? "USB audio input gone; stopping receive" : "Audio receive turned off");
                }
                stopCapture();
                return;
            }
            if (captureThread != null) {
                return;
            }
            running = true;
            deviceName = String.valueOf(device.getProductName());
            captureThread = new Thread(() -> captureLoop(device), "UVProAudioModemRx");
            captureThread.start();
        }
    }

    private void stopCapture() {
        Thread t;
        synchronized (lock) {
            running = false;
            t = captureThread;
            captureThread = null;
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private AudioDeviceInfo findUsbInput() {
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return d;
            }
        }
        return null;
    }

    private void captureLoop(AudioDeviceInfo device) {
        AudioRecord record = null;
        try {
            record = openRecord(device);
            if (record == null) {
                Log.e(TAG, "Could not open " + deviceName + " for recording");
                return;
            }
            int rate = record.getSampleRate();
            AfskDemodulator demod = new AfskDemodulator(rate, this::onFrame);
            activeDemod = demod;
            short[] buf = new short[rate / 20];
            record.startRecording();
            Log.i(TAG, "Audio receive started on " + deviceName + " @ " + rate + " Hz");
            long nextLevelLog = System.currentTimeMillis() + LEVEL_LOG_INTERVAL_MS;
            while (running) {
                int n = record.read(buf, 0, buf.length);
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read error " + n + "; stopping");
                    break;
                }
                demod.process(buf, 0, n);
                inputPeak = demod.getInputPeak();
                long now = System.currentTimeMillis();
                if (now >= nextLevelLog) {
                    Log.d(TAG, String.format(java.util.Locale.US,
                            "level peak %.2f, frames %d", inputPeak, framesDecoded));
                    nextLevelLog = now + LEVEL_LOG_INTERVAL_MS;
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "ATAK lacks RECORD_AUDIO; audio receive unavailable", e);
        } catch (Exception e) {
            Log.e(TAG, "Audio receive failed", e);
        } finally {
            activeDemod = null;
            if (record != null) {
                try {
                    record.stop();
                } catch (IllegalStateException ignored) {
                }
                record.release();
            }
            synchronized (lock) {
                if (captureThread == Thread.currentThread()) {
                    captureThread = null;
                    running = false;
                }
            }
            Log.i(TAG, "Audio receive stopped");
        }
    }

    /**
     * VOICE_RECOGNITION (or UNPROCESSED where offered) skips the AGC and noise suppression that
     * the MIC source applies; both smear AFSK tones.
     */
    private AudioRecord openRecord(AudioDeviceInfo device) {
        int source = MediaRecorder.AudioSource.VOICE_RECOGNITION;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && "true".equals(audioManager.getProperty(
                        AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))) {
            source = MediaRecorder.AudioSource.UNPROCESSED;
        }
        for (int rate : SAMPLE_RATES) {
            int min = AudioRecord.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                continue;
            }
            AudioRecord r = new AudioRecord(source, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, rate / 2 * 2));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                continue;
            }
            if (!r.setPreferredDevice(device)) {
                Log.w(TAG, "setPreferredDevice refused for " + deviceName + "; Android routing decides");
            }
            return r;
        }
        return null;
    }

    private void onFrame(byte[] ax25) {
        framesDecoded++;
        Log.i(TAG, "AFSK frame " + ax25.length + " bytes (#" + framesDecoded + ")");
        try {
            packetRouter.routeIncoming(ax25, 0, RfInboundTransport.AUDIO);
        } catch (Exception e) {
            Log.w(TAG, "routeIncoming failed: " + e.getMessage());
        }
    }
}
