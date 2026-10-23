package com.overdrive.app.safekeep;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * SafeKeep entry points. Master toggle OFF ⇒ no threads, no database, no network, no timers:
 * every hook below returns after one volatile read. The engine exists only while ON.
 */
public final class SafeKeep {

    private static final DaemonLogger logger = DaemonLogger.getInstance("SafeKeep");

    private static volatile Engine engine;
    /** Toggle is ON as last seen: locks hold even while the engine starts or failed to start. */
    private static volatile boolean armed;
    private static volatile boolean shuttingDown;
    private static volatile boolean starting;
    private static volatile long startFailedAt;
    private static final java.util.concurrent.atomic.AtomicBoolean convergeQueued =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile boolean listenerInstalled;
    private static final Object lifecycle = new Object();
    private static final Retention retention = new Retention();

    private SafeKeep() {}

    /** Daemon boot. Reads the toggle from the already-loaded config; starts nothing when OFF. */
    public static void init() {
        installListener();
        try {
            if (SafeKeepConfig.isEnabledIn(UnifiedConfigManager.loadConfig())) {
                arm();
                start();
            }
        } catch (Throwable t) {
            logger.error("SafeKeep init failed", t);
        }
    }

    /**
     * Daemon boot, before the startup reap: locks must already hold when rotation first runs.
     * With the toggle OFF this is a config read and nothing else.
     */
    public static void armLocksEarly() {
        try {
            if (SafeKeepConfig.isEnabledIn(UnifiedConfigManager.loadConfig())) arm();
        } catch (Throwable ignored) {
        }
    }

    /** Daemon shutdown: stops the engine, waiting at most 3 s so the rest of shutdown keeps its budget. */
    public static void shutdown() {
        shuttingDown = true;
        if (engine == null && !starting) return;
        Thread t = new Thread(SafeKeep::stop, "safekeep-shutdown");
        t.setDaemon(true);
        t.start();
        try {
            t.join(3000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean isActive() {
        return engine != null;
    }

    static Engine engine() {
        return engine;
    }

    static Retention retention() {
        return retention;
    }

    private static synchronized void arm() {
        if (armed) return;
        retention.loadLocks();
        armed = true;
    }

    private static void installListener() {
        if (listenerInstalled) return;
        listenerInstalled = true;
        UnifiedConfigManager.addListener((section, config) -> {
            // Runs inside the config lock on the writer's thread: decide cheaply, act elsewhere.
            final boolean relevant = SafeKeepConfig.SECTION.equals(section) || "all".equals(section);
            if (!relevant) {
                Engine e = engine;
                if (e != null && !SafeKeepSecrets.SECTION.equals(section)) e.onSettingsChanged();
                return;
            }
            final boolean enabled = "all".equals(section)
                    ? SafeKeepConfig.isEnabledIn(config)
                    : config != null && config.optBoolean("enabled", false);
            if (!enabled) armed = false;
            Engine e = engine;
            if (enabled == (e != null)) {
                if (e == null) {
                    // OFF: a restore may still replace sign-ins read while off (config page).
                    if ("all".equals(section)) {
                        SafeKeepSecrets.invalidateIfChanged(config == null ? null
                                : config.optJSONObject(SafeKeepSecrets.SECTION));
                    }
                    return;
                }
                if (SafeKeepConfig.SECTION.equals(section)) {
                    e.reloadConfigAsync();
                } else {
                    // A whole-config write (a restore) may carry new SafeKeep settings or sign-ins.
                    JSONObject sk = config == null ? null : config.optJSONObject(SafeKeepConfig.SECTION);
                    if (sk != null && !sk.toString().equals(e.cfg.raw.toString())) e.reloadConfigAsync();
                    SafeKeepSecrets.invalidateIfChanged(config == null ? null
                            : config.optJSONObject(SafeKeepSecrets.SECTION));
                }
                return;
            }
            if (shuttingDown) return;
            // Coalesced: one thread at a time plus at most one waiting, however many writes arrive.
            if (!convergeQueued.compareAndSet(false, true)) return;
            Thread t = new Thread(() -> converge(true), "safekeep-toggle");
            t.setDaemon(true);
            t.start();
        });
    }

    /** Brings the engine in line with the stored toggle (every toggle event funnels here). */
    static void converge() {
        converge(false);
    }

    /** {@code auto}: a config event, not a user action; a start that just failed isn't retried for 60 s. */
    private static void converge(boolean auto) {
        synchronized (lifecycle) {
            if (auto) convergeQueued.set(false);
            boolean want = SafeKeepConfig.load().enabled && !shuttingDown;
            if (!want) {
                stopLocked();
            } else if (!(auto && engine == null && System.currentTimeMillis() - startFailedAt < 60_000L)) {
                startLocked();
            }
        }
    }

    static void start() {
        synchronized (lifecycle) {
            startLocked();
        }
    }

    static void stop() {
        synchronized (lifecycle) {
            stopLocked();
        }
    }

    private static void startLocked() {
        if (shuttingDown) return;
        SafeKeepConfig cfg = SafeKeepConfig.load();
        if (!cfg.enabled) return;
        arm();
        if (engine != null) return;
        // Sign-ins may have changed (restore, edits) while the engine was off.
        SafeKeepSecrets.invalidate();
        Engine e = new Engine(retention);
        starting = true;
        boolean ok;
        try {
            ok = e.start(cfg);
        } finally {
            starting = false;
        }
        if (!ok) {
            startFailedAt = System.currentTimeMillis();
            logger.warn("SafeKeep engine failed to start");
            return;
        }
        startFailedAt = 0;
        engine = e;
        // An OFF written while the ledger was opening found no engine to stop: re-check now it is visible.
        if (!SafeKeepConfig.load().enabled || shuttingDown) {
            stopLocked();
            return;
        }
        // An OFF+ON pair during the start cleared the lock flag without stopping anything.
        arm();
        logger.info("SafeKeep ON");
    }

    private static void stopLocked() {
        if (!shuttingDown && !SafeKeepConfig.load().enabled) armed = false;
        Engine e = engine;
        if (e == null) return;
        engine = null;
        retention.clearPins();
        e.stop();
        SafeKeepSecrets.invalidate();
        SafeKeepSignals.publishOff();
        logger.info("SafeKeep OFF");
    }

    // ── hooks from existing subsystems (each must stay a single volatile read when OFF) ──

    /** StorageManager.onFileSaved: a finalized clip. */
    public static void onFileSaved(File file) {
        Engine e = engine;
        if (e == null || file == null) return;
        e.onFileSaved(file);
    }

    /** EventTimelineCollector: an event's JSON/SRT sidecars are on disk. */
    public static void onEventSidecarsWritten(File mp4) {
        Engine e = engine;
        if (e == null || mp4 == null) return;
        e.onEventSealed(mp4);
    }

    /** TripAnalyticsManager: a finished trip row (and its telemetry file) is committed. */
    public static void onTripSaved(long tripId) {
        Engine e = engine;
        if (e == null || tripId <= 0) return;
        e.onTripSaved(tripId);
    }

    /**
     * StorageManager category rotation: the clips of one pass to keep (locks, then fresh pins,
     * each bounded by a share of {@code limitBytes}). Empty when SafeKeep is off.
     */
    public static Set<String> retainedAmong(List<File> oldestFirst, long limitBytes) {
        if (!armed || oldestFirst == null || oldestFirst.isEmpty()) return Collections.emptySet();
        return retention.retainedAmong(oldestFirst, limitBytes);
    }

    /** StorageManager physical-free emergency reap: user locks sort last, pins count for nothing. */
    public static boolean isLockedForEmergency(File f) {
        if (!armed) return false;
        return retention.isLocked(f);
    }

    /** Automation action entry (non-blocking). */
    public static boolean command(String cmd, int minutes) {
        Engine e = engine;
        if (e == null) return false;
        e.command(cmd, minutes);
        return true;
    }
}
