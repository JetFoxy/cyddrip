package com.thatguysservice.huami_xdrip.watch.cyd;

import java.util.List;
import java.util.UUID;

/**
 * CYDDrip BLE protocol constants and packet builders.
 *
 * The ESP32-2432S028 (Cheap Yellow Display) advertises as "M5Stack".
 * Uses compact OP_CYD_UPDATE packet (0x20) for all BG + insulin + CoB data.
 *
 * Packet layout (26 bytes):
 *   Byte 0:    0x20  opcode
 *   Byte 1:    0x01  packet index
 *   Byte 2:    0x01  total packets
 *   Byte 3-4:  BG value (uint16, mg/dL)
 *   Byte 5-6:  Delta   (int16,  mg/dL, signed)
 *   Byte 7-10: UTC timestamp (uint32, seconds)
 *   Byte 11:   Direction byte (see DIR_* constants)
 *   Byte 12:   Flags (bit0=isMgdl, bit1=isStale)
 *   Byte 13-14: UTC offset (int16, minutes)
 *   Byte 15-16: IoB (uint16, hundredths of a unit — e.g. 125 = 1.25 U)
 *   Byte 17-18: last bolus uint16 hundredths of unit (0 = none)
 *   Byte 19:    minutes since last bolus uint8 (0 = none/unknown, 255 = >4h ago)
 *   Byte 20-21: pump Bolus IoB uint16 hundredths of unit (0 = none)
 *   Byte 22-23: pump reservoir uint16 × 10 (e.g. 2003 = 200.3U; 0 = none)
 *   Byte 24:    pump battery uint8 percent (255 = no pump data)
 *   Byte 25:    CoB uint8 grams (0 = no data, 255 = >254g)
 */
public class CydProtocol {

    // BLE identifiers — must match ESP32 firmware
    public static final String BLE_DEVICE_NAME      = "CYDDrip";
    public static final UUID   SERVICE_UUID         = UUID.fromString("AF6E5F78-706A-43FB-B1F4-C27D7D5C762F");
    public static final UUID   CHARACTERISTIC_UUID  = UUID.fromString("6D810E9F-0983-4030-BDA7-C7C9A6A19C1C");

    // Opcodes: Android → ESP32
    public static final byte OP_AUTH_INIT    = 0x09;  // request auth challenge
    public static final byte OP_AUTH_PASS    = 0x0A;  // send stored password
    public static final byte OP_CYD_UPDATE   = 0x20;  // compact all-in-one BG + insulin + CoB packet
    public static final byte OP_BG_HISTORY   = 0x21;  // pre-fill graph history

    // Opcodes: ESP32 → Android (received via BLE notifications)
    public static final byte OP_AUTH_OK     = 0x0B;  // password matched
    public static final byte OP_AUTH_FAIL   = 0x0C;  // wrong password
    public static final byte OP_AUTH_ENTER  = 0x0D;  // enter stored password
    public static final byte OP_AUTH_NEW    = 0x0E;  // new auto-generated password (authenticated)
    public static final byte OP_AUTH_BYPASS = 0x0F;  // no password configured, proceed
    public static final byte OP_REQUEST_ALL  = 0x16;  // device requests all parameters
    public static final byte OP_REQUEST_TIME = 0x11;  // device requests timestamp

    // Direction byte values for OP_WATCHDRIP_UPDATE
    public static final byte DIR_NONE        = 0;
    public static final byte DIR_DOUBLE_UP   = 1;
    public static final byte DIR_SINGLE_UP   = 2;
    public static final byte DIR_FORTY_UP    = 3;
    public static final byte DIR_FLAT        = 4;
    public static final byte DIR_FORTY_DOWN  = 5;
    public static final byte DIR_SINGLE_DOWN = 6;
    public static final byte DIR_DOUBLE_DOWN = 7;

    // Flags for OP_WATCHDRIP_UPDATE byte 12
    public static final byte FLAG_MGDL  = 0x01;
    public static final byte FLAG_STALE = 0x02;

    /** Build the CYDDrip update packet (OP_CYD_UPDATE, 0x20), 26 bytes. See class javadoc for layout. */
    public static byte[] buildUpdatePacket(int bgMgdl, int deltaMgdl, long utcSeconds,
                                           byte direction, boolean isMgdl, boolean isStale,
                                           int utcOffsetMinutes, double iobUnits,
                                           double lastBolusUnits, int lastBolusAgeMins,
                                           double pumpIobUnits, double pumpReservoirUnits,
                                           int pumpBatteryPct, int cobGrams) {
        byte[] pkt = new byte[26];
        pkt[0]  = OP_CYD_UPDATE;
        pkt[1]  = 0x01;
        pkt[2]  = 0x01;
        pkt[3]  = (byte) ((bgMgdl >> 8) & 0xFF);
        pkt[4]  = (byte)  (bgMgdl & 0xFF);
        pkt[5]  = (byte) ((deltaMgdl >> 8) & 0xFF);
        pkt[6]  = (byte)  (deltaMgdl & 0xFF);
        pkt[7]  = (byte) ((utcSeconds >> 24) & 0xFF);
        pkt[8]  = (byte) ((utcSeconds >> 16) & 0xFF);
        pkt[9]  = (byte) ((utcSeconds >> 8)  & 0xFF);
        pkt[10] = (byte)  (utcSeconds & 0xFF);
        pkt[11] = direction;
        pkt[12] = (byte) ((isMgdl ? FLAG_MGDL : 0) | (isStale ? FLAG_STALE : 0));
        pkt[13] = (byte) ((utcOffsetMinutes >> 8) & 0xFF);
        pkt[14] = (byte)  (utcOffsetMinutes & 0xFF);
        int iobHundredths = (int) Math.round(Math.max(0, iobUnits) * 100.0);
        if (iobHundredths > 0xFFFF) iobHundredths = 0xFFFF;
        pkt[15] = (byte) ((iobHundredths >> 8) & 0xFF);
        pkt[16] = (byte)  (iobHundredths & 0xFF);
        int bolusHundredths = (int) Math.round(Math.max(0, lastBolusUnits) * 100.0);
        if (bolusHundredths > 0xFFFF) bolusHundredths = 0xFFFF;
        pkt[17] = (byte) ((bolusHundredths >> 8) & 0xFF);
        pkt[18] = (byte)  (bolusHundredths & 0xFF);
        pkt[19] = (byte) Math.min(255, Math.max(0, lastBolusAgeMins));
        int pumpIobH = (int) Math.round(Math.max(0, pumpIobUnits) * 100.0);
        if (pumpIobH > 0xFFFF) pumpIobH = 0xFFFF;
        pkt[20] = (byte) ((pumpIobH >> 8) & 0xFF);
        pkt[21] = (byte)  (pumpIobH & 0xFF);
        int resX10 = (int) Math.round(Math.max(0, pumpReservoirUnits) * 10.0);
        if (resX10 > 0xFFFF) resX10 = 0xFFFF;
        pkt[22] = (byte) ((resX10 >> 8) & 0xFF);
        pkt[23] = (byte)  (resX10 & 0xFF);
        pkt[24] = (byte) Math.min(255, Math.max(0, pumpBatteryPct));
        pkt[25] = (byte) Math.min(255, Math.max(0, cobGrams));
        return pkt;
    }

    /**
     * Map xDrip slope/direction name to CYD direction byte.
     */
    public static byte directionFromName(String name) {
        if (name == null) return DIR_NONE;
        switch (name) {
            case "DoubleUp":      return DIR_DOUBLE_UP;
            case "SingleUp":      return DIR_SINGLE_UP;
            case "FortyFiveUp":   return DIR_FORTY_UP;
            case "Flat":          return DIR_FLAT;
            case "FortyFiveDown": return DIR_FORTY_DOWN;
            case "SingleDown":    return DIR_SINGLE_DOWN;
            case "DoubleDown":    return DIR_DOUBLE_DOWN;
            default:              return DIR_NONE;
        }
    }

    /**
     * Build a history packet (OP_BG_HISTORY, 0x21).
     *
     * Sends previous readings (skipping index 0 = current, which arrives via 0x20).
     * Count is limited by maxPayloadBytes (MTU - 3) so the packet fits in one BLE write.
     *
     * Firmware assigns timestamps at 5-min intervals backward from the latest 0x20 reading,
     * so up to 179 readings cover 3 hours at 5-min resolution (firmware historySize = 180).
     *
     * Packet layout:
     *   Byte 0:   0x21
     *   Byte 1:   count (number of BG values, max 179)
     *   Byte 2:   0x01
     *   Byte 3+:  BG values as uint16 big-endian, mg/dL, newest first
     *
     * @param maxPayloadBytes  BLE payload capacity (MTU - 3). Use 17 for default 20-byte MTU.
     * @return packet bytes, or null if history has fewer than 2 entries
     */
    public static byte[] buildHistoryPacket(List<Integer> history, int maxPayloadBytes) {
        // index 0 is the current reading — skip it
        int maxByCount = (maxPayloadBytes - 3) / 2;  // how many readings fit in payload
        int count = Math.min(history.size() - 1, Math.min(maxByCount, 179)); // firmware historySize=180, -1 for current reading
        if (count <= 0) return null;
        byte[] pkt = new byte[3 + count * 2];
        pkt[0] = OP_BG_HISTORY;
        pkt[1] = (byte) count;
        pkt[2] = 0x01;
        for (int i = 0; i < count; i++) {
            int bg = history.get(i + 1);  // i+1: skip current
            pkt[3 + i * 2] = (byte) ((bg >> 8) & 0xFF);
            pkt[4 + i * 2] = (byte)  (bg & 0xFF);
        }
        return pkt;
    }

    /** Build a simple 3-byte opcode-only packet (no payload). */
    public static byte[] buildSimplePacket(byte opCode) {
        return new byte[]{opCode, 0x01, 0x01};
    }
}
