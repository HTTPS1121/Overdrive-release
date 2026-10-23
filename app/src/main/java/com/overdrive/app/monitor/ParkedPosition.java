package com.overdrive.app.monitor;

import android.os.SystemClock;

import com.overdrive.app.daemon.CameraDaemon;

/**
 * Where the parked car stands: the newest accurate GPS fix taken in P, held until the gearbox
 * leaves P, a fix shows movement, or the car is switched on while the gearbox is not watched.
 * In memory only, so a restarted daemon holds nothing until it sees P again. Fed from
 * {@link GpsMonitor}, {@link CameraDaemon#onGearChanged} and the ACC edge in {@link AccMonitor}.
 */
public final class ParkedPosition {

    private static final ParkedPosition INSTANCE = new ParkedPosition();

    public static ParkedPosition get() { return INSTANCE; }

    /** Same accuracy limit as the safe-zone gate. */
    static final double MAX_ACCURACY_M = 50.0;
    /** A gearbox first seen later than this after switch-on may have moved unseen. */
    static final long FIRST_GEAR_MAX_DELAY_MS = 10_000;
    /** The gear poller counts as watching while its last reading is at most this old. */
    static final long GEAR_WATCH_MAX_AGE_MS = 10_000;

    /** A held fix; fixElapsedMs is the provider's monotonic fix time. */
    public static final class Fix {
        public final double latitude;
        public final double longitude;
        public final float accuracyM;
        public final long fixElapsedMs;

        Fix(double latitude, double longitude, float accuracyM, long fixElapsedMs) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracyM = accuracyM;
            this.fixElapsedMs = fixElapsedMs;
        }
    }

    private boolean inPark;
    private long parkedSinceMs;
    // elapsedRealtime of the current switch-on edge; 0 while switched off
    private long onSinceMs;
    private boolean gearSeenSinceOn;
    private Fix held;

    ParkedPosition() {}

    /** The parked fix, or null when the car has not provably stood still since an accurate fix. */
    public synchronized Fix current() {
        return onSinceMs > 0L && !gearSeenSinceOn ? null : held;
    }

    /** Stamped live fixes only; a replayed or cache-loaded position is no evidence either way. */
    public void onFix(GpsMonitor.GpsFixSnapshot f) {
        if (f == null || f.loadedFromCache || f.fixElapsedMs <= 0L || !f.hasLocation()) return;
        log(fix(new Fix(f.latitude, f.longitude, f.accuracy, f.fixElapsedMs), f.isMoving()));
    }

    public void onGear(int gear) {
        log(gear(gear == GearMonitor.GEAR_P, GearMonitor.gearToString(gear),
                SystemClock.elapsedRealtime()));
    }

    public void onAccEdge(boolean accOn) {
        long now = SystemClock.elapsedRealtime();
        GearMonitor gearbox = GearMonitor.getInstance();
        boolean watching = gearbox.isRunning()
                && now - gearbox.getLastUpdateTime() <= GEAR_WATCH_MAX_AGE_MS;
        log(accEdge(accOn, watching, now));
    }

    synchronized String fix(Fix f, boolean moving) {
        if (moving) return release("moving fix");
        if (!inPark || f.fixElapsedMs < parkedSinceMs || (onSinceMs > 0L && !gearSeenSinceOn)
                || !(f.accuracyM > 0f && f.accuracyM <= MAX_ACCURACY_M)) return null;
        boolean first = held == null;
        held = f;
        return first ? "holding fix ±" + Math.round(f.accuracyM) + " m" : null;
    }

    synchronized String gear(boolean park, String name, long nowMs) {
        String late = null;
        if (onSinceMs > 0L && !gearSeenSinceOn) {
            gearSeenSinceOn = true;
            if (nowMs - onSinceMs > FIRST_GEAR_MAX_DELAY_MS) {
                inPark = false;
                late = release("gearbox first seen " + (nowMs - onSinceMs) / 1000 + " s after switch-on");
            }
        }
        if (!park) {
            inPark = false;
            String released = release("gear " + name);
            return released != null ? released : late;
        }
        if (!inPark) {
            inPark = true;
            parkedSinceMs = nowMs;
        }
        return late;
    }

    synchronized String accEdge(boolean on, boolean gearboxWatching, long nowMs) {
        if (on) {
            onSinceMs = nowMs;
            gearSeenSinceOn = gearboxWatching;
            return null;
        }
        boolean watched = onSinceMs <= 0L || (gearSeenSinceOn && gearboxWatching);
        onSinceMs = 0L;
        if (watched) return null;
        inPark = false;
        return release("gearbox not watched while switched on");
    }

    private String release(String reason) {
        if (held == null) return null;
        held = null;
        return "released (" + reason + ")";
    }

    private static void log(String line) {
        if (line != null) CameraDaemon.log("ParkedPosition: " + line);
    }
}
