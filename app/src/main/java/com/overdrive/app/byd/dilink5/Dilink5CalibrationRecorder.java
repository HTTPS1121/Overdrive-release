package com.overdrive.app.byd.dilink5;

import android.content.Context;
import android.os.SystemClock;

import com.overdrive.app.byd.VehicleActuatorBridge;
import com.overdrive.app.logging.DaemonLogger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Daemon-side DI5 on-car calibration. For a few minutes it polls the app process for the raw
 * states of unverified controls and logs every change, so toggling them in the BYD menus maps
 * on/off to values without the tester keeping notes.
 */
public final class Dilink5CalibrationRecorder {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Dilink5Calibration");
    private static final long WINDOW_MS = 300_000L;
    // Each read starts the app's foreground service, so poll gently; testers wait ~5 s per change.
    private static final long POLL_MS = 3_000L;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final Object LOCK = new Object();
    private static long deadlineElapsedMs;
    private static Thread worker;

    private Dilink5CalibrationRecorder() {}

    /** Starts a recording window, or pushes a running one's end out to the full window again. */
    public static void start(Context context) {
        if (context == null) return;
        boolean extended;
        synchronized (LOCK) {
            deadlineElapsedMs = SystemClock.elapsedRealtime() + WINDOW_MS;
            extended = worker != null;
            if (!extended) {
                worker = new Thread(() -> record(context), "Dilink5Calibration");
                worker.setDaemon(true);
                worker.start();
            }
        }
        logger.warn(extended
                ? "DI5 calibration: recording extended to 5 minutes from now"
                : "DI5 calibration: recording raw-value changes for 5 minutes");
    }

    private static void record(Context context) {
        Map<String, String> previous = null;
        int failures = 0;
        while (true) {
            synchronized (LOCK) {
                // Decided under the lock so a concurrent start() either extends this run or
                // starts a new worker, never neither.
                if (failures >= MAX_CONSECUTIVE_FAILURES
                        || SystemClock.elapsedRealtime() >= deadlineElapsedMs) {
                    worker = null;
                    break;
                }
            }
            String snapshot = VehicleActuatorBridge.readDiLink5Calibration(context);
            if (snapshot == null) {
                failures++;
            } else {
                failures = 0;
                Map<String, String> current = parse(snapshot);
                if (previous == null) {
                    logger.warn("DI5 cal baseline: " + snapshot);
                } else {
                    String changes = changes(previous, current);
                    if (!changes.isEmpty()) logger.warn("DI5 cal change: " + changes);
                }
                previous = current;
            }
            SystemClock.sleep(POLL_MS);
        }
        logger.warn(failures >= MAX_CONSECUTIVE_FAILURES
                ? "DI5 calibration: stopped, the app process is not answering"
                : "DI5 calibration: recording ended");
    }

    static Map<String, String> parse(String snapshot) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String token : snapshot.trim().split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq > 0) values.put(token.substring(0, eq), token.substring(eq + 1));
        }
        return values;
    }

    static String changes(Map<String, String> previous, Map<String, String> current) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : current.entrySet()) {
            String before = previous.get(entry.getKey());
            if (before != null && !before.equals(entry.getValue())) {
                if (out.length() > 0) out.append(", ");
                out.append(entry.getKey()).append(' ').append(before)
                        .append("->").append(entry.getValue());
            }
        }
        return out.toString();
    }
}
