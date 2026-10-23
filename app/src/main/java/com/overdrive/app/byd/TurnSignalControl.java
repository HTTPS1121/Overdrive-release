package com.overdrive.app.byd;

import android.os.SystemClock;

import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Turn-indicator writes: one side at a time, confirmed by read-back, with a bounded lifetime.
 *
 * <p>Like the stalk, only one side can be active: switching sides turns the other off first.
 * The write's own return value is not trusted — success means {@link BydDataCollector#readTurnNow}
 * shows the requested side lit (or dark, for off) within {@link #CONFIRM_WINDOW_MS}. The stalk
 * self-cancels but this write does not, and callers are automations and key mappings where nobody
 * is watching, so every confirmed ON is paired with a scheduled OFF.
 */
public final class TurnSignalControl {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TurnSignalControl");

    /** How long an indicator may stay on before it is switched back off. */
    public static final long AUTO_OFF_MS = 30_000L;
    /** Covers the lamp's ~1.5 Hz blink off-phase plus HAL latency. */
    static final long CONFIRM_WINDOW_MS = 1_500L;
    private static final long CONFIRM_POLL_MS = 150L;

    private static final int BIT_LEFT = 0x1;
    private static final int BIT_RIGHT = 0x2;
    private static final int BITS_HAZARD = 0x3;

    private static final Object LOCK = new Object();
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "TurnSignalAutoOff");
                t.setDaemon(true);
                return t;
            });

    /** Side this controller last confirmed on (0 = none). Guarded by LOCK. */
    private static int activeSide;
    private static ScheduledFuture<?> pendingOff;
    /** Bumped on every arm/cancel so a superseded auto-off task never acts. */
    private static int generation;

    private TurnSignalControl() {}

    public static boolean set(BydDataCollector collector, int side, boolean on) {
        if (collector == null) return false;
        if (side != BydDataCollector.TURN_SIGNAL_LEFT
                && side != BydDataCollector.TURN_SIGNAL_RIGHT) return false;
        synchronized (LOCK) {
            if (!on) return switchOffLocked(collector, side);
            if (collector.readTurnNow() == BITS_HAZARD) {
                // Both lamps are already flashing, so a read-back could not tell the sides apart.
                logger.info("turn signal refused: hazards are on");
                return false;
            }
            int other = side == BydDataCollector.TURN_SIGNAL_LEFT
                    ? BydDataCollector.TURN_SIGNAL_RIGHT : BydDataCollector.TURN_SIGNAL_LEFT;
            if (activeSide == other) switchOffLocked(collector, other);
            cancelPendingLocked();
            collector.setTurnSignal(side, true);
            if (!confirm(collector, side, true)) {
                logger.warn("turn signal side=" + side + " not confirmed lit — reporting failure");
                // Leave nothing half-applied if the lamp comes on late.
                collector.setTurnSignal(side, false);
                return false;
            }
            activeSide = side;
            armLocked(side);
            return true;
        }
    }

    private static boolean switchOffLocked(BydDataCollector collector, int side) {
        if (activeSide == side) {
            cancelPendingLocked();
            activeSide = 0;
        }
        collector.setTurnSignal(side, false);
        boolean confirmed = confirm(collector, side, false);
        if (!confirmed) logger.warn("turn signal side=" + side + " still lit after off");
        return confirmed;
    }

    /**
     * Poll the lamp state until it matches. An unreadable state (-1) cannot confirm anything, so
     * it counts as a mismatch — reporting success there would echo a state nobody observed.
     */
    private static boolean confirm(BydDataCollector collector, int side, boolean wantLit) {
        int bit = side == BydDataCollector.TURN_SIGNAL_LEFT ? BIT_LEFT : BIT_RIGHT;
        long deadline = SystemClock.elapsedRealtime() + CONFIRM_WINDOW_MS;
        while (true) {
            int state = collector.readTurnNow();
            if (state >= 0) {
                boolean lit = (state & bit) != 0;
                if (lit == wantLit) return true;
            }
            if (SystemClock.elapsedRealtime() >= deadline) return false;
            try {
                Thread.sleep(CONFIRM_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static void armLocked(int side) {
        final int myGen = ++generation;
        try {
            pendingOff = SCHEDULER.schedule(() -> {
                synchronized (LOCK) {
                    if (myGen != generation) return;
                    pendingOff = null;
                    BydDataCollector live = BydDataCollector.getInstance();
                    if (live == null) return;
                    logger.info("auto-off: clearing indicator side=" + side);
                    switchOffLocked(live, side);
                }
            }, AUTO_OFF_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            // A refused schedule must not leave an unbounded indicator behind.
            logger.warn("could not arm auto-off (" + t.getClass().getSimpleName()
                    + ") — clearing indicator immediately");
            BydDataCollector live = BydDataCollector.getInstance();
            if (live != null) switchOffLocked(live, side);
        }
    }

    private static void cancelPendingLocked() {
        generation++;
        if (pendingOff != null) {
            pendingOff.cancel(false);
            pendingOff = null;
        }
    }
}
