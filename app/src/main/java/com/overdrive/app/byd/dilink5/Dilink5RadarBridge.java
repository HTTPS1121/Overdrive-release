package com.overdrive.app.byd.dilink5;

import android.app.Application;
import android.os.SystemClock;

import com.overdrive.app.byd.radar.RadarConstants;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.logging.DaemonLogger;

import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Relays radar probe states from the app-process producer to the DI5 daemon, which cannot bind
 * the vehicle radar service. Only states the HAL delivered through its listener are relayed, and
 * every message carries the complete observed set, so a lost message is superseded by the next.
 */
public final class Dilink5RadarBridge {

    /** Daemon-side consumer. Calls arrive in sequence order on one dispatch thread. */
    public interface Listener {
        /** Complete observed set; an area whose bit is clear in {@code mask} is unknown. */
        void onRadarProbeStates(int mask, int[] states);

        /** No radar message refreshed the lease; every previously delivered state is void. */
        void onRadarProbesExpired();
    }

    public static final String EVENT = "radarProbes";

    private static final DaemonLogger logger = DaemonLogger.getInstance("Dilink5RadarBridge");
    private static final String APP_PROCESS = "com.overdrive.app";
    private static final int AREAS = RadarConstants.SENSOR_COUNT;
    private static final int ALL_AREAS_MASK = (1 << AREAS) - 1;
    private static final int STATE_BITS = 3;
    private static final int STATE_FIELD_MASK = (1 << STATE_BITS) - 1;
    // The HAL only reports changes, so a close state this old may be a stalled listener.
    private static final long STALE_CLOSE_MS = 120_000L;

    private static final Object producerLock = new Object();
    private static final int[] producerStates = new int[AREAS];
    private static int producerObservedMask;
    private static long producerGeneration;
    private static long lastCallbackElapsedMs;

    private static final Object consumerLock = new Object();
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static ScheduledThreadPoolExecutor dispatcher;
    private static ScheduledFuture<?> leaseExpiry;
    private static long leaseGeneration;
    private static long lastSourceEpoch;
    private static long lastSequence;

    private Dilink5RadarBridge() {}

    /** Producer: read before the caller's lifecycle check so a reset in between voids the call. */
    public static long producerGeneration() {
        synchronized (producerLock) {
            return producerGeneration;
        }
    }

    /** Producer: record one HAL probe callback and relay the complete observed set. */
    public static void publishProbeState(long generation, int area, int state) {
        if (area < 0 || area >= AREAS) return;
        synchronized (producerLock) {
            if (generation != producerGeneration) return;
            producerStates[area] = normalizeState(state);
            producerObservedMask |= 1 << area;
            lastCallbackElapsedMs = SystemClock.elapsedRealtime();
            publishLocked();
        }
    }

    /** Producer: re-assert while any area is YELLOW/RED, so a lost message heals within a poll. */
    public static void publishObservedLevels() {
        synchronized (producerLock) {
            if (producerObservedMask == 0 || !anyCloseLocked()) return;
            if (SystemClock.elapsedRealtime() - lastCallbackElapsedMs > STALE_CLOSE_MS) {
                resetLocked();
                return;
            }
            publishLocked();
        }
    }

    /** Producer: forget observed states at a radar-session boundary. */
    public static void resetProducer() {
        synchronized (producerLock) {
            resetLocked();
        }
    }

    public static boolean isConsumerProcess() {
        return DiLink5Platform.isSelected()
                && !APP_PROCESS.equals(Application.getProcessName());
    }

    public static void addListener(Listener listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Consumer: accept one relayed set; its states stay valid for {@code leaseMs}. */
    public static boolean accept(
            int mask, int packed, long sourceEpoch, long sequence, long leaseMs) {
        if (leaseMs <= 0L || mask <= 0 || (mask & ~ALL_AREAS_MASK) != 0
                || (packed >>> (AREAS * STATE_BITS)) != 0) {
            return false;
        }
        int[] states = new int[AREAS];
        for (int i = 0; i < AREAS; i++) {
            if ((mask & (1 << i)) == 0) continue;
            states[i] = (packed >>> (i * STATE_BITS)) & STATE_FIELD_MASK;
            if (!isValidState(states[i])) return false;
        }
        synchronized (consumerLock) {
            if (sourceEpoch <= 0L || sequence <= 0L
                    || sourceEpoch < lastSourceEpoch
                    || (sourceEpoch == lastSourceEpoch && sequence <= lastSequence)) {
                return false;
            }
            lastSourceEpoch = sourceEpoch;
            lastSequence = sequence;
            ScheduledThreadPoolExecutor executor = dispatcherLocked();
            executor.execute(() -> deliver(mask, states));
            if (leaseExpiry != null) leaseExpiry.cancel(false);
            final long generation = ++leaseGeneration;
            leaseExpiry = executor.schedule(
                    () -> expire(generation), leaseMs, TimeUnit.MILLISECONDS);
        }
        return true;
    }

    // Trigger callbacks do storage and notification work, so they must not run on the ingress
    // thread that also carries DI5 telemetry.
    private static ScheduledThreadPoolExecutor dispatcherLocked() {
        if (dispatcher == null) {
            dispatcher = new ScheduledThreadPoolExecutor(1, r -> {
                Thread thread = new Thread(r, "Dilink5RadarDispatch");
                thread.setDaemon(true);
                return thread;
            });
            dispatcher.setRemoveOnCancelPolicy(true);
        }
        return dispatcher;
    }

    private static void deliver(int mask, int[] states) {
        for (Listener listener : listeners) {
            try {
                listener.onRadarProbeStates(mask, states.clone());
            } catch (Throwable t) {
                logger.debug("Radar listener failed: " + t.getMessage());
            }
        }
    }

    private static void expire(long generation) {
        synchronized (consumerLock) {
            if (generation != leaseGeneration) return;
            leaseExpiry = null;
        }
        for (Listener listener : listeners) {
            try {
                listener.onRadarProbesExpired();
            } catch (Throwable t) {
                logger.debug("Radar expiry listener failed: " + t.getMessage());
            }
        }
    }

    // Holding producerLock keeps radar message content in sequence order.
    private static void publishLocked() {
        Dilink5TelemetryBridge.publishEvent(EVENT, producerObservedMask, packStatesLocked());
    }

    private static void resetLocked() {
        producerGeneration++;
        producerObservedMask = 0;
        Arrays.fill(producerStates, RadarConstants.STATE_SAFE);
    }

    private static boolean anyCloseLocked() {
        for (int i = 0; i < AREAS; i++) {
            if ((producerObservedMask & (1 << i)) != 0
                    && (producerStates[i] == RadarConstants.STATE_YELLOW
                        || producerStates[i] == RadarConstants.STATE_RED)) {
                return true;
            }
        }
        return false;
    }

    private static int packStatesLocked() {
        int packed = 0;
        for (int i = 0; i < AREAS; i++) {
            if ((producerObservedMask & (1 << i)) != 0) {
                packed |= producerStates[i] << (i * STATE_BITS);
            }
        }
        return packed;
    }

    private static boolean isValidState(int state) {
        return state >= RadarConstants.STATE_SAFE && state <= RadarConstants.STATE_RED;
    }

    // Unknown states never trigger; carry them as ABNORMAL so they still clear a trigger.
    private static int normalizeState(int state) {
        return isValidState(state) ? state : RadarConstants.STATE_ABNORMAL;
    }
}
