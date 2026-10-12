package com.uvpro.plugin.protocol;

/**
 * Prevents APRS and OPENRL (ATAK) frames from transmitting at the same time.
 * OPENRL has priority: inbound OPENRL, in-flight OPENRL TX, and pending ping replies
 * defer or skip APRS beacon slots.
 */
public final class RfTxArbitrator {

    private static final RfTxArbitrator INSTANCE = new RfTxArbitrator();

    /** Guard after inbound OPENRL or OPENRL TX completes. */
    private static final long OPENRL_GUARD_MS = 2500L;

    private volatile long openRlGuardUntilMs;
    private volatile boolean openRlTxInFlight;
    private volatile boolean pingReplyPending;
    private volatile boolean slottedTxPending;

    private RfTxArbitrator() {
    }

    public static RfTxArbitrator get() {
        return INSTANCE;
    }

    public void markInboundOpenRl() {
        openRlGuardUntilMs = System.currentTimeMillis() + OPENRL_GUARD_MS;
    }

    public void markOpenRlTxStart() {
        openRlTxInFlight = true;
    }

    public void markOpenRlTxEnd() {
        openRlTxInFlight = false;
        openRlGuardUntilMs = System.currentTimeMillis() + OPENRL_GUARD_MS;
    }

    public void setPingReplyPending(boolean pending) {
        pingReplyPending = pending;
    }

    public void setSlottedTxPending(boolean pending) {
        slottedTxPending = pending;
    }

    /** True when an APRS beacon should be skipped this cycle. */
    public boolean shouldDeferAprsBeacon() {
        return channelBusy();
    }

    /** Milliseconds until mesh OPENRL traffic should stay off the channel. 0 means clear. */
    public long millisUntilOpenRlClear() {
        if (openRlTxInFlight || pingReplyPending || slottedTxPending) {
            return 500L;
        }
        long remain = openRlGuardUntilMs - System.currentTimeMillis();
        return remain > 0 ? remain : 0L;
    }

    private boolean channelBusy() {
        if (openRlTxInFlight) {
            return true;
        }
        if (pingReplyPending) {
            return true;
        }
        if (slottedTxPending) {
            return true;
        }
        return System.currentTimeMillis() < openRlGuardUntilMs;
    }

    /**
     * Compact chat ACKs should not key during an in-flight group CoT or slotted OPENRL window.
     */
    public boolean shouldDeferRfChatAck() {
        return channelBusy();
    }

    /**
     * Manual APRS button: only block when OPENRL TX is active right now
     * (or a ping reply is pending), but do not apply passive guard timing.
     */
    public boolean shouldDeferManualAprsBeacon() {
        return openRlTxInFlight || pingReplyPending || slottedTxPending;
    }
}
