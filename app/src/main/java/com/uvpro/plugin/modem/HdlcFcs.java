package com.uvpro.plugin.modem;

/**
 * AX.25 frame check sequence: CRC-16/X.25 (reflected poly 0x8408, init 0xFFFF, final xor 0xFFFF).
 *
 * KISS TNCs add and strip the FCS in hardware. The software modem has to do it itself, so
 * frames handed to and from {@link com.uvpro.plugin.protocol.PacketRouter} stay FCS-free.
 */
public final class HdlcFcs {

    private HdlcFcs() {
    }

    public static int compute(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= data[i] & 0xFF;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0x8408 : crc >>> 1;
            }
        }
        return crc ^ 0xFFFF;
    }

    /** True when the last two bytes are the FCS (low byte first) of everything before them. */
    public static boolean check(byte[] frameWithFcs, int length) {
        if (length < 3) {
            return false;
        }
        int expect = compute(frameWithFcs, 0, length - 2);
        int got = (frameWithFcs[length - 2] & 0xFF) | ((frameWithFcs[length - 1] & 0xFF) << 8);
        return expect == got;
    }
}
