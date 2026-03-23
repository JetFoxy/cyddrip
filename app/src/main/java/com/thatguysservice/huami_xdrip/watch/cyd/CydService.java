package com.thatguysservice.huami_xdrip.watch.cyd;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleConnection;
import com.polidea.rxandroidble2.RxBleDevice;
import com.polidea.rxandroidble2.scan.ScanFilter;
import com.polidea.rxandroidble2.scan.ScanResult;
import com.polidea.rxandroidble2.scan.ScanSettings;
import com.thatguysservice.huami_xdrip.HuamiXdrip;
import com.thatguysservice.huami_xdrip.R;
import com.thatguysservice.huami_xdrip.UtilityModels.Notifications;
import com.thatguysservice.huami_xdrip.UtilityModels.RxBleProvider;
import com.thatguysservice.huami_xdrip.models.BgData;
import com.thatguysservice.huami_xdrip.models.database.UserError;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

import static com.thatguysservice.huami_xdrip.services.BroadcastService.CMD_ALERT;
import static com.thatguysservice.huami_xdrip.services.BroadcastService.CMD_LOCAL_REFRESH;
import static com.thatguysservice.huami_xdrip.services.BroadcastService.CMD_UPDATE_BG;
import static com.thatguysservice.huami_xdrip.services.BroadcastService.CMD_UPDATE_BG_FORCE;
import static com.thatguysservice.huami_xdrip.services.BroadcastService.INTENT_FUNCTION_KEY;

/**
 * CydService — BLE central service for ESP32-2432S028 (Cheap Yellow Display).
 *
 * Flow:
 *   1. Receive BG update intent from xDripReceiver
 *   2. Connect to CYD device (by MAC or scan by name "M5Stack")
 *   3. Subscribe to BLE notifications on the characteristic
 *   4. Send OP_AUTH_INIT (0x09)
 *   5. Handle auth response:
 *      - 0x0E (new password generated) → auto-authenticated, save password
 *      - 0x0D (enter password) → send stored password via OP_AUTH_PASS
 *      - 0x0B (auth ok) → send data
 *   6. Send OP_CYD_UPDATE (0x20) with all BG + insulin + CoB data
 *   7. Disconnect (disposables.clear() drops the BLE connection)
 *
 * Thread safety: connection is passed as a parameter through the call chain
 * instead of being stored in a volatile field, eliminating race conditions.
 */
public class CydService extends Service {

    private static final String TAG             = "CydService";
    private static final long   SCAN_TIMEOUT_MS = 8_000;
    private static final long   OP_TIMEOUT_MS   = 30_000;  // total operation timeout

    private static final Pattern RE_RESERVOIR = Pattern.compile("(?i)res[:\\s]+([0-9]+(?:[.,][0-9]+)?)");
    private static final Pattern RE_BATTERY   = Pattern.compile("(?i)bat[:\\s]+([0-9]+)\\s*%");
    private static final Pattern RE_COB       = Pattern.compile("\\b([0-9]+)\\s*g\\b");

    private final RxBleClient        rxBleClient = RxBleProvider.getSingleton();
    private final CompositeDisposable disposables = new CompositeDisposable();

    private volatile Bundle     pendingBundle = null;
    private final AtomicBoolean dataSent      = new AtomicBoolean(false);

    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundSafe();
    }

    // -------------------------------------------------------------------------

    private void startForegroundSafe() {
        try {
            Notifications.createNotificationChannels(this);
            startForeground(Notifications.cydNotificationId,
                    Notifications.createNotification(
                            HuamiXdrip.getAppContext().getString(R.string.huami_xdrip_running), this));
        } catch (Exception e) {
            UserError.Log.e(TAG, "startForeground failed (" + e.getClass().getSimpleName() + "): " + e.getMessage());
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Re-call startForeground on every onStartCommand to satisfy Android's
        // 5-second requirement in case onCreate() was skipped (service already running)
        startForegroundSafe();
        if (intent == null) return START_STICKY;
        String function = intent.getStringExtra(INTENT_FUNCTION_KEY);
        Bundle extras   = intent.getExtras();
        handleCommand(function, extras);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        disposables.clear();
        stopForeground(true);
        super.onDestroy();
    }

    // -------------------------------------------------------------------------

    private void handleCommand(String function, Bundle bundle) {
        if (function == null || !CydEntry.isEnabled()) return;
        UserError.Log.d(TAG, "handleCommand: " + function);

        switch (function) {
            case CMD_UPDATE_BG_FORCE:
            case CMD_UPDATE_BG:
            case CMD_LOCAL_REFRESH:
                triggerUpdate(bundle);
                break;
            case CMD_ALERT:
                // Alert support can be added here later
                break;
        }
    }

    private void triggerUpdate(Bundle bundle) {
        if (bundle == null) return;

        if (rxBleClient.getState() != com.polidea.rxandroidble2.RxBleClient.State.READY) {
            UserError.Log.w(TAG, "BLE not ready: " + rxBleClient.getState());
            stopSelf();
            return;
        }

        // Cancel any in-progress attempt before starting a new one
        disposables.clear();
        dataSent.set(false);
        pendingBundle = bundle;

        String mac = CydEntry.getMac();
        if (!mac.isEmpty()) {
            connectAndSend(mac.toUpperCase());
        } else {
            scanByNameAndSend();
        }
    }

    // -------------------------------------------------------------------------
    // Connection

    private void connectAndSend(String mac) {
        UserError.Log.d(TAG, "Connecting to " + mac);
        try {
            RxBleDevice device = rxBleClient.getBleDevice(mac);
            disposables.add(
                device.establishConnection(false)
                    .timeout(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .subscribeOn(Schedulers.io())
                    .subscribe(
                        this::onConnected,
                        err -> {
                            UserError.Log.e(TAG, "Connection failed: " + err);
                            stopSelf();
                        }
                    )
            );
        } catch (Exception e) {
            UserError.Log.e(TAG, "connectAndSend error: " + e);
            stopSelf();
        }
    }

    private void scanByNameAndSend() {
        UserError.Log.d(TAG, "Scanning for " + CydProtocol.BLE_DEVICE_NAME);
        try {
            ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(android.os.ParcelUuid.fromString(CydProtocol.SERVICE_UUID.toString()))
                    .build();

            disposables.add(
                rxBleClient.scanBleDevices(
                        new ScanSettings.Builder().build(),
                        filter)
                    .take(1)
                    .timeout(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .subscribeOn(Schedulers.io())
                    .subscribe(
                        (ScanResult result) -> {
                            String mac = result.getBleDevice().getMacAddress();
                            UserError.Log.d(TAG, "Found CYD at " + mac);
                            CydEntry.setMac(mac);  // persist so next update skips scan
                            connectAndSend(mac);
                        },
                        err -> {
                            UserError.Log.e(TAG, "Scan failed/timeout: " + err);
                            stopSelf();
                        }
                    )
            );
        } catch (Exception e) {
            UserError.Log.e(TAG, "scanByNameAndSend error: " + e);
            stopSelf();
        }
    }

    // -------------------------------------------------------------------------
    // Connection established

    private void onConnected(RxBleConnection connection) {
        UserError.Log.d(TAG, "Connected to CYD");

        // setupNotification must complete before we write OP_AUTH_INIT,
        // otherwise some devices ignore the write and the handshake never starts.
        disposables.add(
            connection.setupNotification(CydProtocol.CHARACTERISTIC_UUID)
                .subscribeOn(Schedulers.io())
                .flatMap(notifObservable -> {
                    // CCCD is now written — safe to kick off auth
                    writeBytes(connection, CydProtocol.buildSimplePacket(CydProtocol.OP_AUTH_INIT));
                    return notifObservable;
                })
                .subscribe(
                    bytes -> handleNotification(bytes, connection),
                    err -> {
                        UserError.Log.e(TAG, "Notification error: " + err);
                        stopSelf();
                    }
                )
        );
    }

    // -------------------------------------------------------------------------
    // Auth & notification handling
    // connection is passed as parameter — no shared mutable field

    private void handleNotification(byte[] bytes, RxBleConnection connection) {
        if (bytes == null || bytes.length == 0) return;
        byte opCode = bytes[0];
        UserError.Log.d(TAG, "RX opcode: 0x" + Integer.toHexString(opCode & 0xFF));

        switch (opCode) {
            case CydProtocol.OP_AUTH_NEW:
                // Device generated a new password — auto-authenticated
                if (bytes.length > 3) {
                    String newPass = new String(bytes, 3, bytes.length - 3).trim();
                    CydEntry.setBlePassword(newPass);
                    UserError.Log.d(TAG, "Saved new CYD password");
                }
                sendAllData(connection);
                break;

            case CydProtocol.OP_AUTH_OK:
            case CydProtocol.OP_AUTH_BYPASS:
                sendAllData(connection);
                break;

            case CydProtocol.OP_AUTH_FAIL:
                UserError.Log.w(TAG, "Auth failed — clearing stored password");
                CydEntry.setBlePassword("");
                disposables.clear();
                stopSelf();
                break;

            case CydProtocol.OP_AUTH_ENTER:
                sendStoredPassword(connection);
                break;

            case CydProtocol.OP_REQUEST_ALL:
            case CydProtocol.OP_REQUEST_TIME:
                sendAllData(connection);
                break;
        }
    }

    private void sendStoredPassword(RxBleConnection connection) {
        String pass = CydEntry.getBlePassword();
        if (pass.isEmpty()) {
            UserError.Log.w(TAG, "No password stored — device will reject");
            return;
        }
        byte[] passBytes = pass.getBytes();
        byte[] pkt = new byte[1 + passBytes.length];
        pkt[0] = CydProtocol.OP_AUTH_PASS;
        System.arraycopy(passBytes, 0, pkt, 1, passBytes.length);
        writeBytes(connection, pkt);
    }

    // -------------------------------------------------------------------------
    // Data sending

    private void sendAllData(RxBleConnection connection) {
        if (dataSent.getAndSet(true)) return;  // send once per connection attempt

        Bundle bundle = pendingBundle;
        if (bundle == null) { disposables.clear(); return; }

        BgData bgData;
        try {
            bgData = new BgData(bundle);
        } catch (Exception e) {
            UserError.Log.e(TAG, "Failed to parse BgData: " + e);
            disposables.clear();
            stopSelf();
            return;
        }

        if (bgData.isNoBgData()) {
            UserError.Log.d(TAG, "No BG data to send");
            disposables.clear();
            stopSelf();
            return;
        }

        int     bgMgdl     = (int) bgData.getValueMgdl();
        int     deltaMgdl  = (int) bgData.getDeltaMgdl();
        long    utcSeconds = bgData.getTimeStamp() / 1000L;
        byte    direction  = CydProtocol.directionFromName(bgData.getDeltaName());
        boolean isMgdl     = bgData.isDoMgdl();
        boolean isStale    = bgData.isStale();

        // Read IoB from xDrip broadcast bundle ("predict.IOB" is a string like "1.25u")
        double iobUnits = 0.0;
        String predictIob = bundle.getString("predict.IOB");
        if (predictIob != null && !predictIob.isEmpty()) {
            try {
                String cleaned = predictIob.replace(",", ".").replaceAll("[^0-9.]", "").trim();
                if (!cleaned.isEmpty()) iobUnits = Double.parseDouble(cleaned);
            } catch (NumberFormatException ignored) {}
        }

        // Last bolus from xDrip broadcast bundle
        double lastBolusUnits = 0.0;
        int    lastBolusAgeMins = 0;
        double treatInsul = bundle.getDouble("treatment.insulin", -1);
        long   treatTs    = bundle.getLong("treatment.timeStamp", -1);
        if (treatInsul > 0 && treatTs > 0) {
            lastBolusUnits = treatInsul;
            long ageMs = System.currentTimeMillis() - treatTs;
            int ageMins = (int)(ageMs / 60000L);
            lastBolusAgeMins = (ageMins < 0) ? 0 : Math.min(254, ageMins);
        }

        // Pump data — try pumpJSON first (xDrip direct pump), then AAPS-specific keys,
        // then parse external.statusLine as last resort.
        // xDrip/AAPS uses -1 as "not available" sentinel — clamp negatives to 0/255.
        double pumpIobUnits       = 0.0;
        double pumpReservoirUnits = 0.0;
        int    pumpBatteryPct     = 255;  // 255 = no pump data

        // 1) pumpJSON (xDrip direct pump driver)
        String pumpJson = bundle.getString("pumpJSON");
        if (pumpJson != null && !pumpJson.isEmpty()) {
            try {
                org.json.JSONObject json = new org.json.JSONObject(pumpJson);
                double piob = json.optDouble("bolusiob",  -1);
                double res  = json.optDouble("reservoir", -1);
                double bat  = json.optDouble("battery",   -1);
                if (piob >= 0) pumpIobUnits       = piob;
                if (res  >= 0) pumpReservoirUnits = res;
                if (bat  >= 0 && bat <= 254) pumpBatteryPct = (int) Math.round(bat);
            } catch (Exception ignored) {}
        }

        // 2) AAPS direct bundle keys (sent by AAPS XDripBroadcast plugin)
        if (pumpReservoirUnits <= 0) {
            double res = bundle.getDouble("reservoir", -1);
            if (res > 0) pumpReservoirUnits = res;
        }
        if (pumpBatteryPct == 255) {
            double bat = bundle.getDouble("battery", -1);
            if (bat >= 0 && bat <= 100) pumpBatteryPct = (int) Math.round(bat);
        }

        // 3) Parse external.statusLine — AAPS loop status string.
        //    Formats vary but CoB is always "Xg" (e.g. "29g"), reservoir "Res: 200.5" etc.
        int cobGrams = 0;
        String statusLine = bundle.getString("external.statusLine");
        if (statusLine != null && !statusLine.isEmpty()) {
            if (pumpReservoirUnits <= 0) {
                java.util.regex.Matcher m = RE_RESERVOIR.matcher(statusLine);
                if (m.find()) {
                    try {
                        pumpReservoirUnits = Double.parseDouble(
                                m.group(1).replace(",", "."));
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (pumpBatteryPct == 255) {
                java.util.regex.Matcher m = RE_BATTERY.matcher(statusLine);
                if (m.find()) {
                    try {
                        int b = Integer.parseInt(m.group(1));
                        if (b >= 0 && b <= 100) pumpBatteryPct = b;
                    } catch (NumberFormatException ignored) {}
                }
            }
            // CoB: match standalone number followed by 'g' (e.g. "29g")
            java.util.regex.Matcher cobM = RE_COB.matcher(statusLine);
            if (cobM.find()) {
                try { cobGrams = Integer.parseInt(cobM.group(1)); }
                catch (NumberFormatException ignored) {}
            }
            UserError.Log.d(TAG, "statusLine parse: res=" + pumpReservoirUnits
                    + " bat=" + pumpBatteryPct + " cob=" + cobGrams + " from: " + statusLine);
        }

        UserError.Log.d(TAG, String.format(
                "Sending: bg=%d delta=%d dir=%d mgdl=%b stale=%b iob=%.2f bolus=%.2f age=%d pumpIob=%.2f res=%.1f bat=%d cob=%d",
                bgMgdl, deltaMgdl, direction, isMgdl, isStale, iobUnits,
                lastBolusUnits, lastBolusAgeMins, pumpIobUnits, pumpReservoirUnits, pumpBatteryPct, cobGrams));

        // Backfill history from xDrip content provider if we have too few entries
        CydEntry.backfillFromXdrip();

        // Persist this reading
        if (!isStale) CydEntry.addBgToHistory(bgMgdl);

        final List<Integer> bgHistory = CydEntry.getBgHistory();
        final byte[] mainPkt = CydProtocol.buildUpdatePacket(
                bgMgdl, deltaMgdl, utcSeconds, direction, isMgdl, isStale, 0, iobUnits,
                lastBolusUnits, lastBolusAgeMins, pumpIobUnits, pumpReservoirUnits, pumpBatteryPct,
                cobGrams);

        // Request larger MTU so we can send up to 35 history readings in one packet
        disposables.add(
            connection.requestMtu(512)
                .onErrorReturn(e -> 23)  // fallback to default 20-byte payload
                .flatMap(mtu -> {
                    int payload = mtu - 3;  // ATT overhead
                    byte[] histPkt = CydProtocol.buildHistoryPacket(bgHistory, payload);
                    if (histPkt != null) {
                        final byte[] hp = histPkt;
                        return connection.writeCharacteristic(CydProtocol.CHARACTERISTIC_UUID, hp)
                                .flatMap(b -> connection.writeCharacteristic(CydProtocol.CHARACTERISTIC_UUID, mainPkt));
                    } else {
                        return connection.writeCharacteristic(CydProtocol.CHARACTERISTIC_UUID, mainPkt);
                    }
                })
                .subscribeOn(Schedulers.io())
                .subscribe(
                    b -> {
                        UserError.Log.d(TAG, "CYDDrip update sent OK");
                        disposables.clear();
                        stopSelf();
                    },
                    err -> {
                        UserError.Log.e(TAG, "Send failed: " + err);
                        disposables.clear();
                        stopSelf();
                    }
                )
        );
    }

    // -------------------------------------------------------------------------

    private void writeBytes(RxBleConnection connection, byte[] data) {
        disposables.add(
            connection.writeCharacteristic(CydProtocol.CHARACTERISTIC_UUID, data)
                .subscribeOn(Schedulers.io())
                .subscribe(
                    b -> {},
                    err -> UserError.Log.e(TAG, "Write error: " + err)
                )
        );
    }
}
