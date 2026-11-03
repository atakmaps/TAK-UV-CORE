package com.uvpro.plugin.modem;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

/**
 * Push-to-talk through a CM108-family USB audio codec's GPIO pin, the way Direwolf's
 * {@code PTT CM108} does it: a 4-byte HID output report sets the pin's direction and level.
 *
 * Digirig Lite wires PTT to GPIO3 and has no serial port, so this is its only keying method
 * besides VOX. The AIOC cable emulates the same interface on GPIO3.
 *
 * Android's USB host API needs a one-time user grant per attach; {@link #open()} asks for it
 * (ATAK shows the system "allow access" dialog) and opens the device once granted. Only the HID
 * interface is claimed; the audio interfaces stay with Android's audio stack.
 */
final class Cm108Ptt {

    private static final String TAG = "UVProAudioModem";
    private static final String ACTION_USB_PERMISSION = "com.uvpro.plugin.modem.USB_PERMISSION";

    private static final int VENDOR_CMEDIA = 0x0D8C;
    private static final int VENDOR_AIOC = 0x1209;
    private static final int PRODUCT_AIOC = 0x7388;
    private static final int PTT_GPIO = 3;

    private static final int HID_SET_REPORT = 0x09;
    private static final int HID_REPORT_TYPE_OUTPUT = 0x02;
    private static final int REQUEST_TYPE_CLASS_INTERFACE_OUT = 0x21;
    private static final int TIMEOUT_MS = 500;

    private final Context context;
    private final UsbManager usbManager;
    private final Runnable onStateChanged;

    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface hidInterface;
    private boolean permissionRequested;
    private boolean receiverRegistered;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                return;
            }
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            Log.i(TAG, "USB PTT permission " + (granted ? "granted" : "denied"));
            if (granted) {
                open();
            }
            onStateChanged.run();
        }
    };

    Cm108Ptt(Context atakContext, Runnable onStateChanged) {
        this.context = atakContext.getApplicationContext();
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.onStateChanged = onStateChanged;
    }

    synchronized boolean isReady() {
        return connection != null && device != null && usbManager.getDeviceList().containsValue(device);
    }

    /** True when a CM108-family device is attached, whether or not it is open yet. */
    synchronized boolean isAttached() {
        return findDevice() != null;
    }

    /** Opens the attached interface, asking the user for USB access first if needed. */
    synchronized void open() {
        if (isReady()) {
            return;
        }
        close();
        UsbDevice d = findDevice();
        if (d == null) {
            permissionRequested = false;
            return;
        }
        if (!usbManager.hasPermission(d)) {
            requestPermission(d);
            return;
        }
        UsbInterface hid = null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
                hid = d.getInterface(i);
                break;
            }
        }
        if (hid == null) {
            Log.w(TAG, d.getProductName() + " has no HID interface; GPIO PTT unavailable");
            return;
        }
        UsbDeviceConnection c = usbManager.openDevice(d);
        if (c == null) {
            Log.w(TAG, "openDevice failed for " + d.getProductName());
            return;
        }
        if (!c.claimInterface(hid, true)) {
            Log.w(TAG, "claimInterface failed for " + d.getProductName());
            c.close();
            return;
        }
        device = d;
        connection = c;
        hidInterface = hid;
        permissionRequested = false;
        Log.i(TAG, "USB PTT ready on " + d.getProductName() + " (GPIO" + PTT_GPIO + ")");
        set(false);
    }

    /** Drives the PTT pin; returns false if the report did not go out. */
    synchronized boolean set(boolean keyed) {
        if (connection == null || hidInterface == null) {
            return false;
        }
        int mask = 1 << (PTT_GPIO - 1);
        byte[] report = {0, (byte) (keyed ? mask : 0), (byte) mask, 0};
        int n = connection.controlTransfer(REQUEST_TYPE_CLASS_INTERFACE_OUT, HID_SET_REPORT,
                HID_REPORT_TYPE_OUTPUT << 8, hidInterface.getId(), report, report.length, TIMEOUT_MS);
        if (n < 0) {
            Log.w(TAG, "PTT " + (keyed ? "key" : "unkey") + " report failed");
            return false;
        }
        return true;
    }

    synchronized void close() {
        if (connection != null) {
            set(false);
            try {
                if (hidInterface != null) {
                    connection.releaseInterface(hidInterface);
                }
            } catch (RuntimeException ignored) {
            }
            connection.close();
        }
        connection = null;
        hidInterface = null;
        device = null;
    }

    synchronized void shutdown() {
        close();
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(permissionReceiver);
            } catch (RuntimeException ignored) {
            }
            receiverRegistered = false;
        }
    }

    private UsbDevice findDevice() {
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (d.getVendorId() == VENDOR_CMEDIA
                    || (d.getVendorId() == VENDOR_AIOC && d.getProductId() == PRODUCT_AIOC)) {
                return d;
            }
        }
        return null;
    }

    private void requestPermission(UsbDevice d) {
        if (permissionRequested) {
            return;
        }
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(permissionReceiver, filter);
            }
            receiverRegistered = true;
        }
        // Explicit intent: Android 14 rejects a mutable PendingIntent wrapping an implicit one.
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName());
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
        usbManager.requestPermission(d, PendingIntent.getBroadcast(context, 0, intent, flags));
        permissionRequested = true;
        Log.i(TAG, "Requesting USB access to " + d.getProductName() + " for PTT");
    }
}
