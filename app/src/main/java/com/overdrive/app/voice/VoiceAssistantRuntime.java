package com.overdrive.app.voice;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.overdrive.app.audio.AppAudioCaptureController;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.util.DaemonHttpClient;
import com.overdrive.app.voice.local.LocalCommandSpotter;
import com.overdrive.app.voice.wake.WakeWordDetector;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-process entry point, reconciled from the status overlay's poll tick. Disabled costs a
 * single config lookup; ACC off or an unreachable daemon tears the engine down.
 */
public final class VoiceAssistantRuntime {

    private static final String TAG = "VoiceAssistant";
    private static final long ACTIVITY_REPORT_MIN_MS = 2000;
    private static final long HEARTBEAT_MS = 5 * 60_000L;
    // One poll can misread ACC (field missing, mid-transition); a real ACC off is still off next poll.
    private static final long STOP_DEBOUNCE_MS = 5000;
    private static long stopPendingSince;

    private static final Object LOCK = new Object();
    private static volatile boolean enabledSeen;
    // Written under LOCK; engine callbacks read it without the lock so they never wait on close().
    private static volatile VoiceEngine engine;
    private static Context appContext;
    private static volatile Boolean wakeModelPresent;
    private static volatile Boolean localEnginePresent;

    private static final Object REPORT_LOCK = new Object();
    private static ExecutorService reporter;
    private static String reportedState;
    private static String currentState = "off";
    private static String currentMessage;
    private static long lastActivityReportAt;
    private static boolean activityPending;
    private static long lastReportAt;

    private VoiceAssistantRuntime() {
    }

    /** Last enabled flag seen by the poll tick. */
    public static boolean isEnabled() {
        return enabledSeen;
    }

    public static void reconcile(Context context, boolean daemonReachable, boolean accOn) {
        JSONObject root;
        try {
            root = UnifiedConfigManager.loadConfig();
        } catch (Throwable t) {
            return;
        }
        boolean enabled = VoiceAssistantConfig.isEnabled(root);
        enabledSeen = enabled;
        if (!enabled && engine == null && reportedState == null) return;
        synchronized (LOCK) {
            if (!enabled) {
                stopLocked("off");
                return;
            }
            if (!daemonReachable || !accOn) {
                long now = SystemClock.elapsedRealtime();
                if (engine != null) {
                    if (stopPendingSince == 0) stopPendingSince = now;
                    if (now - stopPendingSince < STOP_DEBOUNCE_MS) return;
                }
                stopPendingSince = 0;
                stopLocked("waiting");
                return;
            }
            stopPendingSince = 0;
            VoiceAssistantConfig cfg = VoiceAssistantConfig.fromRoot(root);
            if (engine == null) {
                appContext = context.getApplicationContext();
                if (wakeModelPresent == null) {
                    wakeModelPresent = WakeWordDetector.isModelPresent(
                            appContext, WakeWordDetector.DEFAULT_ASSET_BASE);
                    localEnginePresent = LocalCommandSpotter.isAvailable(appContext);
                }
                engine = new VoiceEngine(appContext, cfg, HOST);
                synchronized (REPORT_LOCK) {
                    lastReportAt = SystemClock.elapsedRealtime();
                }
                engine.start();
                Log.i(TAG, "Voice assistant started (mode=" + cfg.mode + ")");
            } else {
                engine.updateConfig(cfg);
            }
            engine.ensureCapture();
            flushReports(engine);
        }
    }

    /** Poll-tick flush: a rate-limited activity report, or a heartbeat carrying the mic level. */
    private static void flushReports(VoiceEngine e) {
        long now = SystemClock.elapsedRealtime();
        synchronized (REPORT_LOCK) {
            boolean pending = activityPending && now - lastActivityReportAt >= ACTIVITY_REPORT_MIN_MS;
            if (!pending && now - lastReportAt < HEARTBEAT_MS) return;
            activityPending = false;
            lastActivityReportAt = now;
        }
        report(e, false);
    }

    /** Push-to-talk from a mapped key; ignored when the assistant is not running. */
    public static void pushToTalk() {
        VoiceEngine e;
        synchronized (LOCK) {
            e = engine;
        }
        if (e != null) e.pushToTalk();
    }

    public static void shutdown() {
        synchronized (LOCK) {
            if (engine != null || reportedState != null) stopLocked("off");
        }
    }

    private static void stopLocked(String state) {
        VoiceEngine e = engine;
        engine = null;
        if (e != null) {
            e.close();
            Log.i(TAG, "Voice assistant stopped (" + state + ")");
        }
        setState(state, null, e);
    }

    private static final VoiceEngine.Host HOST = new VoiceEngine.Host() {
        @Override
        public void onStatus(String state, String message) {
            VoiceEngine e = engine;
            if (e != null) setState(state, message, e);
        }

        @Override
        public void onActivity() {
            VoiceEngine e = engine;
            if (e == null) return;
            long now = SystemClock.elapsedRealtime();
            synchronized (REPORT_LOCK) {
                if (now - lastActivityReportAt < ACTIVITY_REPORT_MIN_MS) {
                    activityPending = true;
                    return;
                }
                lastActivityReportAt = now;
                activityPending = false;
            }
            report(e, false);
        }
    };

    private static void setState(String state, String message, VoiceEngine source) {
        boolean finalState = "off".equals(state) || "waiting".equals(state);
        synchronized (REPORT_LOCK) {
            // A callback from an engine already torn down must not undo the final report.
            if (!finalState && source != engine) return;
            if (state.equals(currentState) && state.equals(reportedState)
                    && (message == null ? currentMessage == null : message.equals(currentMessage))) {
                return;
            }
            currentState = state;
            currentMessage = message;
        }
        report(source, finalState);
    }

    private static void report(VoiceEngine source, boolean finalReport) {
        final JSONObject body = new JSONObject();
        final String state;
        synchronized (REPORT_LOCK) {
            // Late activity from a stopped engine would start a reporter nobody shuts down.
            if (!finalReport && source != engine) return;
            state = currentState;
            try {
                body.put("state", state);
                if (currentMessage != null) body.put("message", currentMessage);
                if (wakeModelPresent != null) body.put("wakeModelPresent", wakeModelPresent);
                if (localEnginePresent != null) body.put("localEnginePresent", localEnginePresent);
                // What the shared capture actually opened with (plain mic after the head unit
                // refused the configured input, flagged), and the echo-canceller probe.
                body.put("micSource", AppAudioCaptureController.activeMicSource());
                if (AppAudioCaptureController.micSourceFailed()) body.put("micSourceFailed", true);
                Boolean aec = AppAudioCaptureController.echoCancelerAvailable();
                if (aec != null) body.put("aecAvailable", aec);
                Boolean aecOn = AppAudioCaptureController.echoCancelerEnabled();
                if (aecOn != null) body.put("aecEnabled", aecOn);
                int[] channels = AppAudioCaptureController.carArrayChannelsDb();
                if (channels != null) body.put("arrayChannelsDb", new org.json.JSONArray(channels));
                if (source != null) {
                    JSONObject snap = source.snapshot();
                    body.put("counters", snap.optJSONObject("counters"));
                    if (snap.has("lastWakeAt")) body.put("lastWakeAt", snap.optLong("lastWakeAt"));
                    for (String key : new String[] {"lastVerify", "lastTurn", "mic", "checks", "turns",
                            "checksDropped", "nearMisses", "stage1", "enroll"}) {
                        Object value = snap.opt(key);
                        if (value != null) body.put(key, value);
                    }
                }
            } catch (Throwable ignored) {
            }
            reportedState = state;
            lastReportAt = SystemClock.elapsedRealtime();
            if (reporter == null) {
                reporter = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "VoiceReport");
                    t.setDaemon(true);
                    return t;
                });
            }
            reporter.execute(() -> post(body));
            if (finalReport) {
                reporter.shutdown();
                reporter = null;
                if ("off".equals(state)) reportedState = null;
            }
        }
    }

    private static void post(JSONObject body) {
        HttpURLConnection conn = null;
        try {
            conn = DaemonHttpClient.open("/api/voice/runtime", "POST", 1500, 3000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            conn.getResponseCode();
        } catch (Throwable t) {
            Log.d(TAG, "runtime report failed: " + t.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
