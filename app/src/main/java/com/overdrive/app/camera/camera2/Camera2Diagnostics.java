package com.overdrive.app.camera.camera2;

import com.overdrive.app.logging.DaemonLogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Copies the camera service's own explanation of a Camera2 failure into the
 * daemon log, so a log export alone shows why the 360 camera was refused.
 * Off-thread, time-bounded and rate-limited.
 */
public final class Camera2Diagnostics {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Camera2Avm");

    private static final long REPEAT_INTERVAL_MS = 10 * 60_000L;
    private static final long COMMAND_TIMEOUT_MS = 5_000L;
    private static final int LOGCAT_TAIL_LINES = 4_000;
    private static final int MAX_LOG_LINES = 40;
    private static final int MAX_DUMP_LINES = 40;
    private static final int MAX_LINE_CHARS = 300;

    // NDK, camera service, HAL device and provider tags; the NDK line carries the service's reason.
    private static final Pattern CAMERA_LOG = Pattern.compile(
            "ACameraManager|ACameraDevice|CameraService|Camera3-|CameraDeviceClient"
                    + "|CameraProviderManager|camera\\.provider|CamX|CHIUSECASE|QCamera"
                    + "|Camera2AvmBridge|avc: +denied.*camera",
            Pattern.CASE_INSENSITIVE);
    // Device list, HAL versions and current clients.
    private static final Pattern CAMERA_DUMP = Pattern.compile(
            "Number of camera devices|Camera HAL device|device@|== Camera device"
                    + "|Device version|API version|Allowed user|Device [0-9]+ is|Active Camera Clients",
            Pattern.CASE_INSENSITIVE);

    private static final Object LOCK = new Object();
    private static String lastKind;
    private static long lastCaptureMs;
    private static boolean dumpCaptured;
    private static boolean captureRunning;

    private Camera2Diagnostics() {}

    /** Captures once per new failure kind, then at most every 10 minutes per kind. */
    public static void captureFailure(String kind, String reason) {
        final boolean withDump;
        synchronized (LOCK) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (captureRunning) return;
            if (kind.equals(lastKind) && now - lastCaptureMs < REPEAT_INTERVAL_MS) return;
            lastKind = kind;
            lastCaptureMs = now;
            captureRunning = true;
            withDump = !dumpCaptured;
            dumpCaptured = true;
        }
        Thread worker = new Thread(() -> {
            try {
                capture(reason, withDump);
            } catch (Throwable t) {
                logger.warn("camera2 diag: capture failed: " + t);
            } finally {
                synchronized (LOCK) {
                    captureRunning = false;
                }
            }
        }, "Camera2Diag");
        worker.setDaemon(true);
        worker.start();
    }

    private static void capture(String reason, boolean withDump) {
        List<String> log = run(new String[] {"logcat", "-d", "-v", "threadtime", "-t",
                Integer.toString(LOGCAT_TAIL_LINES)}, CAMERA_LOG, MAX_LOG_LINES, true);
        logger.warn("camera2 diag: " + reason + " — camera service log ("
                + log.size() + " lines):");
        for (String line : log) logger.warn("camera2 diag | " + line);
        if (!withDump) return;
        List<String> dump = run(new String[] {"dumpsys", "media.camera"},
                CAMERA_DUMP, MAX_DUMP_LINES, false);
        logger.warn("camera2 diag: camera service state (" + dump.size() + " lines):");
        for (String line : dump) logger.warn("camera2 diag | " + line);
    }

    /** Matching lines of a command's output: the newest {@code max} when {@code keepLast}, else the first. */
    private static List<String> run(String[] command, Pattern filter, int max, boolean keepLast) {
        ArrayDeque<String> kept = new ArrayDeque<>();
        Process process = null;
        Thread killer = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final Process target = process;
            killer = new Thread(() -> {
                try {
                    Thread.sleep(COMMAND_TIMEOUT_MS);
                    target.destroyForcibly();
                } catch (InterruptedException ignored) {
                }
            }, "Camera2DiagTimeout");
            killer.setDaemon(true);
            killer.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!filter.matcher(line).find()) continue;
                    if (line.length() > MAX_LINE_CHARS) line = line.substring(0, MAX_LINE_CHARS);
                    if (kept.size() == max) {
                        if (!keepLast) break;
                        kept.removeFirst();
                    }
                    kept.addLast(line.trim());
                }
            }
        } catch (Throwable t) {
            kept.addLast("(" + command[0] + " unavailable: " + t.getMessage() + ")");
        } finally {
            if (killer != null) killer.interrupt();
            if (process != null) process.destroy();
        }
        return new ArrayList<>(kept);
    }
}
