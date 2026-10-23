package com.overdrive.app.surveillance;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Optional overlay work. Projection owners never wait for this worker or its commands. */
public final class ClusterAdasLifecycle {
    public static final String HIDE_FRAME = "23,50,00,08,01,00";
    public static final String RESTORE_FRAME = "23,50,00,08,01,01";
    private static final String[] PATTERNS = {
            "26,F0,00,28,01,06", "43,20,00,00,01,01",
            "43,20,00,21,01,01", "26,F0,00,28,01,06", HIDE_FRAME
    };
    private static final int[] REPEATS = {60, 50, 50, 40, 60};
    private static final long RETRY_MS = 12_000L;
    private static final long POWER_RETRY_MS = 4_000L;
    private static final long MAINTENANCE_MS = 3_000L;
    private static final long PLATFORM_138_MAINTENANCE_MS = 1_000L;
    private static final long PREPARATION_REFRESH_MS = 60_000L;
    private static final int MAX_ATTEMPTS = 2;

    /** Physical projection state, independent of the consumer that requested it. */
    public static final class Source {
        public final long generation;
        public final boolean active;
        public final boolean busy;

        public Source(long generation, boolean active, boolean busy) {
            this.generation = generation;
            this.active = active;
            this.busy = busy;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Source)) return false;
            Source s = (Source) other;
            return generation == s.generation && active == s.active && busy == s.busy;
        }

        @Override public int hashCode() { return Objects.hash(generation, active, busy); }
    }

    public static final class Snapshot {
        public final Source legacy;
        public final Source diLink5;

        public Snapshot(Source legacy, Source diLink5) {
            this.legacy = Objects.requireNonNull(legacy);
            this.diLink5 = Objects.requireNonNull(diLink5);
        }

        public boolean active() { return legacy.active || diLink5.active; }
        public boolean busy() { return legacy.busy || diLink5.busy; }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Snapshot)) return false;
            Snapshot s = (Snapshot) other;
            return legacy.equals(s.legacy) && diLink5.equals(s.diLink5);
        }

        @Override public int hashCode() { return Objects.hash(legacy, diLink5); }
    }

    public enum Result { COMPLETED, REJECTED, UNAVAILABLE, CANCELLED, UNKNOWN }
    public enum Ownership { NONE, OWNED, UNCERTAIN }

    public static final class ClusterState {
        public static final ClusterState UNKNOWN = new ClusterState(-1, -1);
        public final int power, platform;

        public ClusterState(int power, int platform) {
            this.power = power >= 0 && power <= 3 ? power : -1;
            this.platform = platform >= 0 && platform <= 255 ? platform : -1;
        }

        boolean ready() { return power == 3; }
        boolean maintain() {
            return maintenanceIntervalMs() > 0L;
        }

        long maintenanceIntervalMs() {
            // Observed on this platform: the initial sequence hides the box briefly
            // at power 2, then it returns while projection remains active.
            if (power == 2 && platform == 138) return PLATFORM_138_MAINTENANCE_MS;
            return ready() && (platform == 102 || platform == 115
                    || platform == 141 || platform == 181) ? MAINTENANCE_MS : 0L;
        }
    }

    public interface Backend {
        Snapshot snapshot();
        Ownership ownership();
        boolean supported();
        boolean canHide();
        long nowMs();
        boolean acquireOwnership();
        boolean beginCommand();
        boolean completeCommand();
        boolean clearOwnership();
        Result prepare(BooleanSupplier guard);
        Result container(int opcode);
        Result frames(String frame, int count, BooleanSupplier stillCurrent);
        default ClusterState clusterState() { return ClusterState.UNKNOWN; }
        void log(String message);
    }

    private enum Phase {
        IDLE, SUPPORT, HIDE_ENTER, HIDE_PREPARE, HIDE_WAIT, HIDE_INITIAL,
        HIDE_PATTERN, HIDDEN, MAINTENANCE_PREPARE, MAINTENANCE_FRAMES,
        WAIT_IDLE, RESTORE_SUPPORT, RESTORE_PREPARE,
        RESTORE_FRAMES, RESTORE_RELEASE, RESTORE_WAIT, RESTORE_FINAL,
        CLEAR, RETRY, QUIET, UNCERTAIN
    }

    private final Backend backend;
    private Snapshot expected;
    private Phase phase = Phase.IDLE;
    private Phase retryPhase;
    private boolean owned;
    private boolean changedHardware;
    private boolean bootRestore;
    private boolean restoring;
    private int attempts;
    private int pattern;
    private int remaining;
    private long due;
    private long maintenanceDue;
    private long maintenanceInterval;
    private long preparedAt = -1L;
    private long powerRetryAt = -1L;
    private boolean clusterReady;
    private boolean followup;

    public ClusterAdasLifecycle(Backend backend) {
        this.backend = Objects.requireNonNull(backend);
        try {
            Ownership ownership = backend.ownership();
            owned = ownership != Ownership.NONE;
            bootRestore = owned;
            changedHardware = owned;
            if (ownership == Ownership.UNCERTAIN) {
                phase = Phase.UNCERTAIN;
                log("Optional cluster overlay paused: recovery ownership is uncertain; "
                        + "projection remains available");
            }
        } catch (Throwable failure) {
            phase = Phase.UNCERTAIN;
            log("Optional cluster overlay paused: ownership could not be read");
        }
    }

    /** Advances at most one bounded command batch, on the feature's worker only. */
    public void tick(long nowMs) {
        try {
            tickInternal(nowMs);
        } catch (Throwable failure) {
            // Marker/snapshot/capability failures never escape to projection callers.
            phase = Phase.QUIET;
            log("Optional cluster overlay skipped: " + failure.getClass().getSimpleName());
        }
    }

    private void tickInternal(long nowMs) {
        if (phase == Phase.UNCERTAIN) return;
        Snapshot current = backend.snapshot();
        boolean sessionChanged = !current.equals(expected);
        if (sessionChanged) {
            expected = current;
            attempts = 0;
            clusterReady = false;
            powerRetryAt = -1L;
            preparedAt = -1L;
            maintenanceInterval = 0L;
            maintenanceDue = nowMs + MAINTENANCE_MS;
            followup = true;
            if (current.active()) {
                bootRestore = false;
                restoring = false;
                phase = Phase.SUPPORT;
            } else {
                restoring = true;
                phase = owned ? Phase.WAIT_IDLE : Phase.IDLE;
                // An idle gap during app/map handoff must not flash the overlay.
                due = nowMs + 250L;
            }
        }

        ClusterState cluster = ClusterState.UNKNOWN;
        if (current.active() && backend.canHide()) cluster = backend.clusterState();
        if (cluster == null) cluster = ClusterState.UNKNOWN;
        long interval = cluster.maintenanceIntervalMs();
        if (interval != maintenanceInterval) {
            boolean compatibilityChanged = interval == PLATFORM_138_MAINTENANCE_MS
                    || maintenanceInterval == PLATFORM_138_MAINTENANCE_MS;
            maintenanceInterval = interval;
            if (compatibilityChanged) {
                maintenanceDue = later(nowMs, interval > 0L ? interval : MAINTENANCE_MS);
                if (phase == Phase.MAINTENANCE_PREPARE || phase == Phase.MAINTENANCE_FRAMES) {
                    phase = Phase.HIDDEN;
                }
            }
            if (interval == PLATFORM_138_MAINTENANCE_MS) {
                log("Optional cluster overlay: projection maintenance enabled for "
                        + "power=2, platform=138, interval=1000 ms");
            }
        }
        if (sessionChanged) {
            clusterReady = cluster.ready();
        } else if (cluster.ready() != clusterReady) {
            clusterReady = cluster.ready();
            powerRetryAt = clusterReady ? later(nowMs, POWER_RETRY_MS) : -1L;
            if (clusterReady) log("Optional cluster overlay: cluster power ready; "
                    + "scheduling guarded hide retry in 4000 ms");
        }
        if (powerRetryAt >= 0L && nowMs >= powerRetryAt && hidePhase() && current(true)) {
            powerRetryAt = -1L;
            followup = true;
            phase = Phase.HIDE_ENTER;
            log("Optional cluster overlay: retrying hide after cluster power transition");
        }

        switch (phase) {
            case IDLE:
            case QUIET:
                return;
            case SUPPORT:
                if (!backend.canHide()) return;
                if (!backend.supported()) {
                    retry(Phase.SUPPORT, nowMs, "overlay transport unavailable");
                    return;
                }
                if (!current(true)) return;
                if (!owned && !backend.acquireOwnership()) {
                    retry(Phase.SUPPORT, nowMs, "overlay ownership could not be saved");
                    return;
                }
                owned = true;
                phase = Phase.HIDE_ENTER;
                return;
            case HIDE_ENTER:
                if (command(() -> backend.container(13), true, nowMs)) {
                    phase = Phase.HIDE_PREPARE;
                }
                return;
            case HIDE_PREPARE:
                if (command(() -> backend.prepare(() -> current(true)), true, nowMs)) {
                    preparedAt = Math.max(nowMs, backend.nowMs());
                    phase = Phase.HIDE_WAIT;
                    due = later(nowMs, 1_000L);
                }
                return;
            case HIDE_WAIT:
                if (nowMs >= due) phase = Phase.HIDE_INITIAL;
                return;
            case HIDE_INITIAL:
                if (command(() -> backend.frames(HIDE_FRAME, 3, () -> current(true)),
                        true, nowMs)) {
                    pattern = 0;
                    remaining = REPEATS[0];
                    phase = Phase.HIDE_PATTERN;
                }
                return;
            case HIDE_PATTERN:
                int count = Math.min(10, remaining);
                if (command(() -> backend.frames(PATTERNS[pattern], count,
                        () -> current(true)), true, nowMs)) {
                    remaining -= count;
                    if (remaining == 0 && ++pattern == PATTERNS.length) {
                        phase = Phase.HIDDEN;
                        due = later(nowMs, RETRY_MS);
                        log("Optional cluster overlay hide sequence completed "
                                + "(transport completion; display not acknowledged)");
                    } else if (remaining == 0) {
                        remaining = REPEATS[pattern];
                    }
                }
                return;
            case HIDDEN:
                // One delayed repeat accommodates slow OEM receiver startup.
                if (followup && nowMs >= due) {
                    followup = false;
                    phase = Phase.HIDE_ENTER;
                    return;
                }
                if (nowMs >= maintenanceDue) {
                    maintenanceDue = later(nowMs, maintenanceInterval > 0L
                            ? maintenanceInterval : MAINTENANCE_MS);
                    if (cluster.maintain() && current(true)) {
                        phase = preparedAt < 0L || nowMs - preparedAt >= PREPARATION_REFRESH_MS
                                ? Phase.MAINTENANCE_PREPARE : Phase.MAINTENANCE_FRAMES;
                    }
                }
                return;
            case MAINTENANCE_PREPARE:
                if (!maintenanceCurrent()) {
                    phase = Phase.HIDDEN;
                    return;
                }
                if (command(() -> backend.prepare(this::maintenanceCurrent), true, nowMs)) {
                    preparedAt = Math.max(nowMs, backend.nowMs());
                    phase = Phase.MAINTENANCE_FRAMES;
                    log("Optional cluster overlay: receiver refreshed during projection");
                }
                return;
            case MAINTENANCE_FRAMES:
                if (!maintenanceCurrent()) {
                    phase = Phase.HIDDEN;
                    return;
                }
                if (command(() -> backend.frames(HIDE_FRAME, 3, this::maintenanceCurrent),
                        true, nowMs)) {
                    phase = Phase.HIDDEN;
                    maintenanceDue = later(nowMs, maintenanceInterval);
                    log("Optional cluster overlay: three-frame maintenance completed "
                            + "(interval=" + maintenanceInterval
                            + " ms; transport completion; display not acknowledged)");
                }
                return;
            case WAIT_IDLE:
                if (!current.busy() && nowMs >= due) {
                    phase = changedHardware ? Phase.RESTORE_SUPPORT : Phase.CLEAR;
                }
                return;
            case RESTORE_SUPPORT:
                if (!backend.supported()) {
                    retry(Phase.RESTORE_SUPPORT, nowMs, "restore transport unavailable");
                    return;
                }
                phase = Phase.RESTORE_PREPARE;
                return;
            case RESTORE_PREPARE:
                if (command(() -> backend.prepare(() -> current(false)), false, nowMs)) {
                    remaining = 40;
                    phase = Phase.RESTORE_FRAMES;
                }
                return;
            case RESTORE_FRAMES:
                int restoreCount = Math.min(bootRestore ? 2 : 10, remaining);
                if (command(() -> backend.frames(RESTORE_FRAME, restoreCount,
                        () -> current(false)), false, nowMs)) {
                    remaining -= restoreCount;
                    if (remaining == 0) phase = Phase.RESTORE_RELEASE;
                }
                return;
            case RESTORE_RELEASE:
                if (command(() -> backend.container(12), false, nowMs)) {
                    due = later(nowMs, 1_200L);
                    phase = Phase.RESTORE_WAIT;
                }
                return;
            case RESTORE_WAIT:
                if (nowMs >= due) phase = Phase.RESTORE_FINAL;
                return;
            case RESTORE_FINAL:
                if (command(() -> backend.container(53), false, nowMs)) phase = Phase.CLEAR;
                return;
            case CLEAR:
                if (!current(false)) return;
                boolean restored = changedHardware;
                if (backend.clearOwnership()) {
                    owned = false;
                    changedHardware = false;
                    bootRestore = false;
                    phase = Phase.IDLE;
                    log(restored ? "Optional cluster overlay restore sequence completed "
                            + "(transport completion; display not acknowledged)"
                            : "Optional cluster overlay cancelled before dispatch; unused ownership cleared");
                } else {
                    retry(Phase.CLEAR, nowMs, "restore ownership could not be cleared");
                }
                return;
            case RETRY:
                if (nowMs >= due) phase = retryPhase;
                return;
            default:
                return;
        }
    }

    private boolean current(boolean hiding) {
        Snapshot live = backend.snapshot();
        return live.equals(expected)
                && (hiding ? live.active() && backend.canHide() : !live.active() && !live.busy());
    }

    private boolean maintenanceCurrent() {
        ClusterState state = backend.clusterState();
        return state != null && state.maintain()
                && state.maintenanceIntervalMs() == maintenanceInterval && current(true);
    }

    private boolean hidePhase() {
        return phase == Phase.HIDE_ENTER || phase == Phase.HIDE_PREPARE
                || phase == Phase.HIDE_WAIT || phase == Phase.HIDE_INITIAL
                || phase == Phase.HIDE_PATTERN || phase == Phase.HIDDEN
                || phase == Phase.MAINTENANCE_PREPARE || phase == Phase.MAINTENANCE_FRAMES;
    }

    private interface Command { Result run(); }

    private boolean command(Command action, boolean hiding, long nowMs) {
        if (!current(hiding)) return false;
        if (!backend.beginCommand()) {
            phase = Phase.UNCERTAIN;
            log("Optional cluster overlay paused: command ownership write failed");
            return false;
        }
        Result result;
        try {
            if (current(hiding)) {
                changedHardware = true;
                result = action.run();
            } else {
                result = Result.CANCELLED;
            }
            if (result == null) result = Result.UNKNOWN;
        } catch (Throwable failure) {
            result = Result.UNKNOWN;
        }
        try {
            if (result == Result.UNKNOWN || !backend.completeCommand()) {
                phase = Phase.UNCERTAIN;
                log("Optional cluster overlay paused: command outcome or ownership "
                        + "persistence is uncertain; projection remains available");
                return false;
            }
        } catch (Throwable failure) {
            phase = Phase.UNCERTAIN;
            log("Optional cluster overlay paused: command ownership could not be completed");
            return false;
        }
        if (result == Result.CANCELLED) return false;
        if (result == Result.COMPLETED) return true;
        retry(restoring ? Phase.RESTORE_SUPPORT : Phase.SUPPORT, nowMs,
                "overlay command at " + phase.name().toLowerCase(java.util.Locale.ROOT)
                        + " " + result.name().toLowerCase(java.util.Locale.ROOT));
        return false;
    }

    private void retry(Phase target, long nowMs, String reason) {
        log("Optional cluster overlay: " + reason + "; projection remains available");
        if (++attempts >= MAX_ATTEMPTS) {
            phase = Phase.QUIET;
        } else {
            retryPhase = target;
            phase = Phase.RETRY;
            due = later(nowMs, RETRY_MS);
        }
    }

    private long later(long tickTime, long delayMs) {
        return Math.max(tickTime, backend.nowMs()) + delayMs;
    }

    private void log(String message) {
        try { backend.log(message); } catch (Throwable ignored) { }
    }

    public static boolean isOverlayOpcode(int opcode) {
        return opcode == 12 || opcode == 13 || opcode == 53;
    }

    static boolean isFrame(String frame) {
        if (RESTORE_FRAME.equals(frame) || HIDE_FRAME.equals(frame)) return true;
        for (String pattern : PATTERNS) if (pattern.equals(frame)) return true;
        return false;
    }
}
