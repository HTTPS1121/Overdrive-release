package com.overdrive.app.proximity;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Proximity Guard trigger for the car's blind-spot radar (a vehicle beside us).
 *
 * <p>Fed by {@code BlindSpotEvent}, which owns the only ADAS warning sampler: its counter reads
 * consume the alert, so a second reader would take alerts from blind-spot automations. Callbacks
 * run on the controller's executor because starting a clip can block on storage.
 */
public final class ProximityBlindSpotMonitor {
    private static final DaemonLogger logger = DaemonLogger.getInstance("ProximityBlindSpotMonitor");

    public interface Callback {
        void onBlindSpotTrigger(int sides);
        void onBlindSpotSafe();
    }

    /** The warnings arrive as pulses; same hold as the blind-spot automation. */
    static final long HOLD_MS = 4_000L;
    /** Longer than any real pass: a warning held this long is ignored until it goes quiet. */
    static final long MAX_ASSERT_MS = 120_000L;
    static final int SIDE_BITS = BydDataCollector.BS_LEFT_BIT | BydDataCollector.BS_RIGHT_BIT
            | BydDataCollector.BS_ANY_BIT;

    private static volatile ProximityBlindSpotMonitor armedInstance;

    private final ScheduledExecutorService executor;
    private final Callback callback;
    private final LongSupplier clock;

    // Guarded by this. The callback is never invoked while holding it.
    private boolean armed;
    private boolean pollUseful;
    private long generation;
    private boolean triggered;
    private boolean stuck;
    private boolean seenClear;
    private long armedAtMs;
    private long lastAlertMs;
    private long assertedSinceMs;
    private ScheduledFuture<?> check;

    ProximityBlindSpotMonitor(ScheduledExecutorService executor, Callback callback) {
        this(executor, callback, android.os.SystemClock::elapsedRealtime);
    }

    ProximityBlindSpotMonitor(ScheduledExecutorService executor, Callback callback,
                              LongSupplier clock) {
        this.executor = executor;
        this.callback = callback;
        this.clock = clock;
    }

    /** Whether BlindSpotEvent's poll has to run for this trigger (DiLink 5 alerts arrive by relay). */
    public static boolean wantsPoll() {
        ProximityBlindSpotMonitor m = armedInstance;
        return m != null && m.isPollUseful();
    }

    /** One poll sample of packed ADAS warning bits; negative means unreadable. */
    public static void onPollSample(int packed) {
        ProximityBlindSpotMonitor m = armedInstance;
        if (m == null || packed < 0) return;
        int sides = packed & SIDE_BITS;
        if (sides != 0) m.alert(sides); else m.observeClear();
    }

    /** One instant ADAS warning (HAL event or DiLink 5 relay); other warning families are ignored. */
    public static void onAlertEvent(int bits) {
        ProximityBlindSpotMonitor m = armedInstance;
        if (m == null) return;
        int sides = bits & SIDE_BITS;
        if (sides != 0) m.alert(sides);
    }

    static String describe(int sides) {
        boolean left = (sides & BydDataCollector.BS_LEFT_BIT) != 0;
        boolean right = (sides & BydDataCollector.BS_RIGHT_BIT) != 0;
        if (left && right) return "both";
        if (left) return "left";
        if (right) return "right";
        return "either";
    }

    /** @return true if this call armed it. */
    synchronized boolean arm(boolean pollUseful) {
        if (armed) return false;
        armed = true;
        this.pollUseful = pollUseful;
        generation++;
        triggered = false;
        stuck = false;
        seenClear = false;
        armedAtMs = clock.getAsLong();
        lastAlertMs = 0L;
        assertedSinceMs = 0L;
        armedInstance = this;
        return true;
    }

    /** @return true if a trigger was live; the caller then treats the blind spot as clear. */
    synchronized boolean disarm() {
        if (!armed) return false;
        boolean wasTriggered = triggered;
        armed = false;
        generation++;
        triggered = false;
        stuck = false;
        cancelCheck();
        if (armedInstance == this) armedInstance = null;
        return wasTriggered;
    }

    synchronized boolean isArmed() {
        return armed;
    }

    synchronized boolean isTriggered() {
        return triggered;
    }

    private synchronized boolean isPollUseful() {
        return armed && pollUseful;
    }

    private synchronized void observeClear() {
        if (armed) seenClear = true;
    }

    private synchronized void alert(int sides) {
        if (!armed) return;
        long now = clock.getAsLong();
        long previous = lastAlertMs != 0L ? lastAlertMs : armedAtMs;
        lastAlertMs = now;
        // Like the radar's unobserved sensors: a warning already asserted at arm time may be a
        // resting value, so it counts only after the signal has been seen clear.
        if (!seenClear) {
            if (now - previous < HOLD_MS) return;
            seenClear = true;
        }
        if (triggered || stuck) return;
        triggered = true;
        assertedSinceMs = now;
        final long gen = generation;
        post(() -> deliverTrigger(gen, sides));
        scheduleCheck(HOLD_MS);
    }

    private void deliverTrigger(long gen, int sides) {
        synchronized (this) {
            if (!armed || gen != generation || !triggered) return;
        }
        callback.onBlindSpotTrigger(sides);
    }

    private void runCheck(long gen) {
        boolean cleared = false;
        boolean rearmed = false;
        boolean nowStuck = false;
        long heldMs = 0L;
        synchronized (this) {
            if (!armed || gen != generation) return;
            check = null;
            long now = clock.getAsLong();
            long quietMs = now - lastAlertMs;
            if (stuck) {
                if (quietMs < HOLD_MS) {
                    scheduleCheck(HOLD_MS - quietMs);
                } else {
                    stuck = false;
                    rearmed = true;
                }
            } else if (triggered) {
                heldMs = now - assertedSinceMs;
                if (quietMs < HOLD_MS && heldMs < MAX_ASSERT_MS) {
                    scheduleCheck(Math.min(HOLD_MS - quietMs, MAX_ASSERT_MS - heldMs));
                } else {
                    triggered = false;
                    cleared = true;
                    if (quietMs < HOLD_MS) {
                        stuck = true;
                        nowStuck = true;
                        scheduleCheck(HOLD_MS);
                    }
                }
            }
        }
        if (rearmed) logger.info("Blind-spot warning went quiet; trigger re-armed");
        if (nowStuck) {
            logger.warn("Blind-spot warning held for " + (heldMs / 1000)
                    + " s; ignoring it until it goes quiet");
        }
        if (cleared) callback.onBlindSpotSafe();
    }

    private void post(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            logger.debug("Blind-spot callback dropped: executor shut down");
        }
    }

    // Caller holds the lock.
    private void scheduleCheck(long delayMs) {
        cancelCheck();
        final long gen = generation;
        try {
            check = executor.schedule(() -> runCheck(gen), Math.max(1L, delayMs),
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            check = null;
        }
    }

    // Caller holds the lock.
    private void cancelCheck() {
        if (check != null) {
            check.cancel(false);
            check = null;
        }
    }
}
