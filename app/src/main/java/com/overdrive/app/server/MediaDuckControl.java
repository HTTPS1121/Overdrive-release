package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The head unit's own voice-assistant duck for a voice turn: POST /api/vehicle/media
 * {"target":"media_duck","action":"state|duck|release","leaseMs":N}. BYD's AudioService honours
 * setTrackVolumeDuck only for its allow-listed assistant packages, so the call names BYD's
 * assistant. A duck that is not renewed is released when its lease runs out, and the marker file
 * lets the next daemon release one that a dead daemon left in force.
 */
public final class MediaDuckControl {

    private static final DaemonLogger logger = DaemonLogger.getInstance("MediaDuck");

    static final String ACTION_STATE = "state";
    static final String ACTION_DUCK = "duck";
    static final String ACTION_RELEASE = "release";

    /** The allow-listed caller BYD's AudioService ducks media for. */
    static final String DUCK_PACKAGE = "com.byd.vrassistant.xf";
    /** While the duck is in force AudioService reports the voice stream to this caller. */
    private static final String SYSTEMUI = "com.android.systemui";
    static final int STREAM_VOICE_OEM = 16;

    static final long LEASE_DEFAULT_MS = 15_000;
    static final long LEASE_MIN_MS = 2_000;
    static final long LEASE_MAX_MS = 60_000;

    private static final String MARKER = com.overdrive.app.util.ScratchRoot.path("overdrive_media_duck.state");

    private static final Object lock = new Object();
    private static boolean resolved;
    private static Object audioService;
    private static Method setDuck;
    private static Method focusStream;
    /** The head unit refused a duck this daemon asked for (another allow-list): never retried. */
    private static boolean refused;
    /** This daemon ducked media and owes the release. */
    private static boolean owned;
    private static long leaseUntil;
    private static ScheduledExecutorService timer;
    private static ScheduledFuture<?> expiry;

    private MediaDuckControl() {
    }

    // ---- Pure decisions ----

    static long clampLease(long requested) {
        if (requested <= 0) return LEASE_DEFAULT_MS;
        return Math.max(LEASE_MIN_MS, Math.min(LEASE_MAX_MS, requested));
    }

    /** {@code getCurrentAudioFocusStream("com.android.systemui")} reads the voice stream only while ducked. */
    static boolean duckedStream(int stream) {
        return stream == STREAM_VOICE_OEM;
    }

    /** Daemon start: a duck the previous daemon left in force is released; nothing to do without its marker. */
    public static void releaseStaleDuck() {
        if (!new File(MARKER).exists()) return;
        Thread t = new Thread(() -> {
            synchronized (lock) {
                resolveLocked();
            }
        }, "MediaDuckRecover");
        t.setDaemon(true);
        t.start();
    }

    // ---- Route ----

    /** Body of POST /api/vehicle/media {"target":"media_duck"}; never throws. */
    static JSONObject handle(JSONObject req) {
        try {
            String action = req == null ? "" : req.optString("action", "");
            synchronized (lock) {
                resolveLocked();
                switch (action) {
                    case ACTION_STATE:
                        return reply(ACTION_STATE);
                    case ACTION_DUCK:
                        duckLocked(clampLease(req.optLong("leaseMs", 0)));
                        return reply(ACTION_DUCK);
                    case ACTION_RELEASE:
                        releaseLocked("turn end");
                        return reply(ACTION_RELEASE);
                    default:
                        return new JSONObject().put("success", false).put("target", "media_duck")
                                .put("error", "action must be state, duck or release");
                }
            }
        } catch (Throwable t) {
            logger.warn("Media duck request failed: " + t.getMessage());
            try {
                return new JSONObject().put("success", false).put("target", "media_duck")
                        .put("error", "Media duck failed");
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    private static JSONObject reply(String action) throws Exception {
        return new JSONObject()
                .put("success", true)
                .put("target", "media_duck")
                .put("action", action)
                .put("supported", supportedLocked())
                .put("ducked", owned);
    }

    // ---- Android-bound part ----

    private static boolean supportedLocked() {
        return setDuck != null && focusStream != null && !refused;
    }

    /** Looks the BYD calls up once; a daemon that died while ducked left the marker behind. */
    private static void resolveLocked() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Object binder = serviceManager.getMethod("getService", String.class).invoke(null, "audio");
            Class<?> stub = Class.forName("android.media.IAudioService$Stub");
            audioService = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            Class<?> service = Class.forName("android.media.IAudioService");
            setDuck = service.getMethod("setTrackVolumeDuck", boolean.class, String.class);
            focusStream = service.getMethod("getCurrentAudioFocusStream", String.class);
        } catch (Throwable t) {
            setDuck = null;
            focusStream = null;
            return;
        }
        if (new File(MARKER).exists()) {
            if (duckedNowLocked()) {
                invokeDuck(false);
                logger.info("Media duck: released one a previous daemon left in force");
            }
            deleteMarker();
        }
    }

    private static boolean duckedNowLocked() {
        try {
            Object stream = focusStream.invoke(audioService, SYSTEMUI);
            return stream instanceof Integer && duckedStream((Integer) stream);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean invokeDuck(boolean on) {
        try {
            setDuck.invoke(audioService, on, DUCK_PACKAGE);
            return true;
        } catch (Throwable t) {
            logger.warn("setTrackVolumeDuck(" + on + ") failed: " + t.getMessage());
            return false;
        }
    }

    /** Ducks (or renews this daemon's duck); a duck someone else holds is left to its owner. */
    private static void duckLocked(long leaseMs) {
        if (!supportedLocked()) return;
        if (!owned) {
            if (duckedNowLocked()) return;
            if (!invokeDuck(true)) return;
            if (!duckedNowLocked()) {
                refused = true;
                logger.warn("Media duck: the head unit did not accept it; voice turns keep pausing media");
                return;
            }
            owned = true;
            writeMarker();
            logger.info("Media duck: on (lease " + leaseMs + " ms)");
        }
        leaseUntil = android.os.SystemClock.elapsedRealtime() + leaseMs;
        scheduleExpiryLocked(leaseMs);
    }

    private static void releaseLocked(String why) {
        if (expiry != null) {
            expiry.cancel(false);
            expiry = null;
        }
        if (!owned) return;
        owned = false;
        invokeDuck(false);
        deleteMarker();
        logger.info("Media duck: released (" + why + ")");
    }

    private static void scheduleExpiryLocked(long leaseMs) {
        if (timer == null) {
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MediaDuckLease");
                t.setDaemon(true);
                return t;
            });
        }
        if (expiry != null) expiry.cancel(false);
        expiry = timer.schedule(() -> {
            synchronized (lock) {
                if (owned && android.os.SystemClock.elapsedRealtime() >= leaseUntil) {
                    releaseLocked("lease expired");
                }
            }
        }, leaseMs, TimeUnit.MILLISECONDS);
    }

    private static void writeMarker() {
        try (FileOutputStream out = new FileOutputStream(MARKER)) {
            out.write(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            logger.warn("Media duck marker not written: " + t.getMessage());
        }
    }

    private static void deleteMarker() {
        File marker = new File(MARKER);
        if (marker.exists() && !marker.delete()) logger.warn("Media duck marker not deleted");
    }
}
