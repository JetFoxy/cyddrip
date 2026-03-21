package com.thatguysservice.huami_xdrip.watch.cyd;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.thatguysservice.huami_xdrip.HuamiXdrip;
import com.thatguysservice.huami_xdrip.UtilityModels.Inevitable;
import com.thatguysservice.huami_xdrip.models.Helper;
import com.thatguysservice.huami_xdrip.models.Pref;
import com.thatguysservice.huami_xdrip.models.database.UserError;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.thatguysservice.huami_xdrip.services.BroadcastService.CMD_LOCAL_REFRESH;
import static com.thatguysservice.huami_xdrip.services.BroadcastService.INTENT_FUNCTION_KEY;

public class CydEntry {

    public static final String PREF_CYD_ENABLED       = "cyd_enabled";
    public static final String PREF_CYD_MAC            = "cyd_mac";
    public static final String PREF_CYD_BLE_PASSWORD   = "cyd_ble_password";
    public static final String PREF_CYD_SEND_READINGS  = "cyd_send_readings";
    public static final String PREF_CYD_BG_HISTORY     = "cyd_bg_history";
    private static final int   BG_HISTORY_MAX          = 180; // 180 × 1 min = 3 hours (matches firmware historySize)

    public static boolean isEnabled() {
        return Pref.getBooleanDefaultFalse(PREF_CYD_ENABLED);
    }

    public static String getMac() {
        return Pref.getString(PREF_CYD_MAC, "");
    }

    public static String getBlePassword() {
        return Pref.getString(PREF_CYD_BLE_PASSWORD, "");
    }

    public static void setBlePassword(String password) {
        Pref.setString(PREF_CYD_BLE_PASSWORD, password);
    }

    public static void setMac(String mac) {
        Pref.setString(PREF_CYD_MAC, mac);
    }

    /** Prepend a new BG reading (mg/dL) to the persistent history, keeping last 10. */
    public static void addBgToHistory(int bgMgdl) {
        List<Integer> history = getBgHistory();
        history.add(0, bgMgdl);
        while (history.size() > BG_HISTORY_MAX) history.remove(history.size() - 1);
        Pref.setString(PREF_CYD_BG_HISTORY, historyToString(history));
    }

    /** Returns stored BG history as a list (mg/dL), newest first. */
    public static List<Integer> getBgHistory() {
        String stored = Pref.getString(PREF_CYD_BG_HISTORY, "");
        List<Integer> history = new ArrayList<>();
        if (!stored.isEmpty()) {
            for (String s : stored.split(",")) {
                try { history.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) {}
            }
        }
        return history;
    }

    private static String historyToString(List<Integer> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    /**
     * Query xDrip+ content provider for the last {@code limit} BG readings (mg/dL),
     * returned newest-first. Falls back to empty list on any error.
     */
    // Firmware 0x21 handler assigns timestamps at exactly this interval
    private static final long PREFILL_INTERVAL_MS = 5 * 60 * 1000L;
    private static final long HISTORY_WINDOW_MS   = 3 * 60 * 60 * 1000L; // 3 hours

    /**
     * Query xDrip+ for BG readings within the last 3 hours, downsampled to ~5-min intervals.
     * This matches the firmware's assumption that each 0x21 entry is 5 minutes apart,
     * so 3 hours always maps to exactly 36 readings regardless of CGM frequency.
     * Returns newest-first.
     */
    public static List<Integer> queryXdripHistory(int limit) {
        List<Integer> readings = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            long windowStart = now - HISTORY_WINDOW_MS;

            Uri uri = Uri.parse("content://com.eveningoutpost.dexdrip.BgReadings/");
            Cursor c = HuamiXdrip.getAppContext().getContentResolver().query(
                    uri,
                    new String[]{"calculated_value", "timestamp"},
                    "timestamp > " + windowStart,
                    null,
                    "timestamp DESC");
            if (c != null) {
                int colVal = c.getColumnIndex("calculated_value");
                int colTs  = c.getColumnIndex("timestamp");
                long lastAcceptedTs = Long.MAX_VALUE;
                while (c.moveToNext() && readings.size() < limit) {
                    double val = colVal >= 0 ? c.getDouble(colVal) : 0;
                    long   ts  = colTs  >= 0 ? c.getLong(colTs)    : 0;
                    if (val <= 0 || ts <= 0) continue;
                    // Accept only if at least ~4.5 min gap from previous accepted reading
                    if (lastAcceptedTs - ts >= PREFILL_INTERVAL_MS * 9 / 10) {
                        readings.add((int) Math.round(val));
                        lastAcceptedTs = ts;
                    }
                }
                c.close();
            }
        } catch (Exception e) {
            UserError.Log.d("CydEntry", "xDrip history query failed: " + e.getMessage());
        }
        return readings;  // newest first
    }

    private static boolean backfillDone = false;

    /**
     * Backfill our stored history from xDrip if we have fewer than {@code minSize} entries.
     * Runs at most once per process lifetime — history only grows from there.
     */
    public static void backfillFromXdrip() {
        if (backfillDone) return;
        backfillDone = true;
        if (getBgHistory().size() >= BG_HISTORY_MAX) return;
        List<Integer> xdrip = queryXdripHistory(BG_HISTORY_MAX);
        if (xdrip.isEmpty()) return;
        // xdrip list is newest-first; add oldest-first so addBgToHistory builds correct order
        List<Integer> reversed = new ArrayList<>(xdrip);
        Collections.reverse(reversed);
        for (int bg : reversed) addBgToHistory(bg);
        UserError.Log.d("CydEntry", "Backfilled " + xdrip.size() + " readings from xDrip");
    }

    public static boolean isSendReadings() {
        return isEnabled() && Pref.getBooleanDefaultFalse(PREF_CYD_SEND_READINGS);
    }

    public static void refresh() {
        Inevitable.task("cyd-preference-changed", 1000,
                () -> Helper.startService(CydService.class, INTENT_FUNCTION_KEY, CMD_LOCAL_REFRESH));
    }

    public static void sendToService(String function, Bundle bundle) {
        if (!isEnabled()) return;
        Intent serviceIntent = new Intent(HuamiXdrip.getAppContext(), CydService.class);
        serviceIntent.putExtra(INTENT_FUNCTION_KEY, function);
        if (bundle != null) serviceIntent.putExtras(bundle);
        Helper.startService(serviceIntent);
    }
}
