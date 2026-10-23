package com.overdrive.app.byd.assist;

import com.overdrive.app.byd.assist.DiLink5AssistOffGate.Control;

import java.util.Locale;

/**
 * Pure per-control attempt budget of the DiLink 5 assist-off profile, one instance per control
 * per ignition cycle, plus the pure cycle-level guards the applier evaluates between steps.
 *
 * <p>Budget: at most {@value #MAX_ATTEMPTS} writes, at least {@value #MIN_ATTEMPT_SPACING_MS} ms
 * apart; after an accepted write the getter is polled every {@value #VERIFY_POLL_MS} ms for up to
 * {@value #VERIFY_WINDOW_MS} ms before the next attempt. A confirming readback ends the control;
 * exhausting the budget gives up. DMS has no readback: a sent request is terminal
 * ({@link Action#REQUEST_SENT}) and the plan never reports it {@link Action#VERIFIED}.
 *
 * <p>The applier drives it as: read getter → {@link #nextAction} → on {@link Action#WRITE} write
 * and call {@link #noteWrite} (or {@link #noteRequestSent} for DMS); on {@link Action#WAIT} sleep
 * one poll and read again; stop on any terminal action. {@link #fail} ends a control early when
 * the route is unusable or the cycle is superseded. Once terminal, every recorder is a no-op and
 * {@link #nextAction} keeps returning the same terminal action.
 */
public final class DiLink5AssistOffPlan {

    public enum Action {
        WRITE(false),
        WAIT(false),
        VERIFIED(true),
        ALREADY_OFF(true),
        FAILED(true),
        GIVE_UP(true),
        REQUEST_SENT(true);

        private final boolean terminal;

        Action(boolean terminal) {
            this.terminal = terminal;
        }

        /** True for the actions that end a control for the rest of the cycle. */
        public boolean isTerminal() {
            return terminal;
        }
    }

    public static final int MAX_ATTEMPTS = 3;
    public static final long MIN_ATTEMPT_SPACING_MS = 2_000L;
    public static final long VERIFY_WINDOW_MS = 6_000L;
    public static final long VERIFY_POLL_MS = 500L;

    /**
     * Readiness (adapter connected AND CPD getter 1/2, AND positive stationary evidence) must
     * appear within this after ACC ON.
     */
    public static final long READINESS_DEADLINE_MS = 90_000L;
    public static final long READINESS_POLL_MS = 3_000L;
    /** A known speed at or below this is stationary evidence; an unknown speed is no evidence. */
    public static final double STATIONARY_MAX_KMH = 3.0;
    /**
     * Gear encoding of the collector snapshot ({@code BydVehicleData.gearMode}): the raw gearbox
     * SDK enum the {@code GearMonitor} and the DI5 fast-dynamics poll already use, P = 1 … S = 6;
     * anything outside that range (including {@code BydVehicleData.UNAVAILABLE}) is "unknown".
     * Compile-time constants, inlined, so this class stays JVM-pure.
     */
    public static final int GEAR_PARK = com.overdrive.app.monitor.GearMonitor.GEAR_P;
    static final int GEAR_LAST_VALID = com.overdrive.app.monitor.GearMonitor.GEAR_S;

    /** Verdict of {@link #motion}: writes happen only on {@link #STATIONARY}. */
    public enum Motion {
        /** Positive evidence: known speed ≤ {@link #STATIONARY_MAX_KMH} or known gear P. */
        STATIONARY,
        /** Known speed above {@link #STATIONARY_MAX_KMH}, whatever the gear says. */
        MOVING,
        /** No positive evidence: speed unknown and gear unknown or not P. */
        NO_EVIDENCE
    }

    private final Control control;
    private int attempts;
    private long lastAttemptAtMs = Long.MIN_VALUE;
    private boolean lastWriteAccepted;
    private Action terminal;
    private String failure = "";

    public DiLink5AssistOffPlan(Control control) {
        if (control == null) throw new IllegalArgumentException("control");
        this.control = control;
    }

    public Control control() {
        return control;
    }

    public int attempts() {
        return attempts;
    }

    public long lastAttemptAtMs() {
        return lastAttemptAtMs;
    }

    /** Whether the newest write was accepted by the setter (false before any write). */
    public boolean lastWriteAccepted() {
        return attempts > 0 && lastWriteAccepted;
    }

    public boolean isTerminal() {
        return terminal != null;
    }

    /** The terminal action once {@link #isTerminal()}, else {@code null}. */
    public Action terminal() {
        return terminal;
    }

    /** Why the control failed or gave up ({@code ""} otherwise). */
    public String failure() {
        return failure;
    }

    /**
     * The next step for this control. {@code lastRead} is the newest getter value
     * ({@link DiLink5AssistOffControls#READ_UNAVAILABLE} when none / DMS).
     */
    public Action nextAction(long nowMs, int lastRead, boolean readinessObserved) {
        if (terminal != null) return terminal;
        if (control == Control.DMS) {
            return nextDmsAction(nowMs);
        }
        if (DiLink5AssistOffControls.isOff(control, lastRead, readinessObserved)) {
            terminal = attempts == 0 ? Action.ALREADY_OFF : Action.VERIFIED;
            return terminal;
        }
        if (attempts == 0) return Action.WRITE;
        long elapsed = nowMs - lastAttemptAtMs;
        long wait = lastWriteAccepted ? VERIFY_WINDOW_MS : MIN_ATTEMPT_SPACING_MS;
        if (elapsed < wait) return Action.WAIT;
        if (attempts >= MAX_ATTEMPTS) {
            terminal = Action.GIVE_UP;
            failure = "no off readback after " + attempts + " attempts (last read "
                    + (lastRead == DiLink5AssistOffControls.READ_UNAVAILABLE ? "n/a" : lastRead)
                    + ")";
            return terminal;
        }
        return Action.WRITE;
    }

    private Action nextDmsAction(long nowMs) {
        if (attempts == 0) return Action.WRITE;
        if (nowMs - lastAttemptAtMs < MIN_ATTEMPT_SPACING_MS) return Action.WAIT;
        if (attempts >= MAX_ATTEMPTS) {
            terminal = Action.GIVE_UP;
            failure = "request not sent after " + attempts + " attempts";
            return terminal;
        }
        return Action.WRITE;
    }

    /**
     * Records one write. {@code accepted} is the setter's verdict (non-negative rc): an accepted
     * write waits the verify window for its readback, a refused one only the attempt spacing.
     * No-op once terminal.
     */
    public void noteWrite(long nowMs, boolean accepted) {
        if (terminal != null) return;
        attempts++;
        lastAttemptAtMs = nowMs;
        lastWriteAccepted = accepted;
    }

    /** DMS only: the settings service accepted the request. There is no readback to confirm. */
    public void noteRequestSent(long nowMs) {
        if (terminal != null) return;
        attempts++;
        lastAttemptAtMs = nowMs;
        lastWriteAccepted = true;
        terminal = Action.REQUEST_SENT;
    }

    /** Ends the control as {@link Action#FAILED} (route unusable, cycle superseded, ...). */
    public void fail(String why) {
        if (terminal != null) return;
        lastWriteAccepted = false;
        terminal = Action.FAILED;
        failure = why == null ? "failed" : why;
    }

    // ── Cycle-level guards (pure, shared with the applier) ────────────────────────

    /**
     * Stationary needs positive evidence: (a) a known (finite) speed at or below
     * {@link #STATIONARY_MAX_KMH}, or (b) a known gear reading equal to P. A known speed above the
     * limit is {@link Motion#MOVING} even when the gear reads P; an unknown speed (NaN) with an
     * unknown or non-P gear is {@link Motion#NO_EVIDENCE} and never allows a write.
     */
    public static Motion motion(double speedKmh, int gearMode) {
        if (Double.isFinite(speedKmh)) {
            return speedKmh <= STATIONARY_MAX_KMH ? Motion.STATIONARY : Motion.MOVING;
        }
        return gearMode == GEAR_PARK ? Motion.STATIONARY : Motion.NO_EVIDENCE;
    }

    /** {@code motion(speedKmh, gearMode) == STATIONARY}. */
    public static boolean isStationary(double speedKmh, int gearMode) {
        return motion(speedKmh, gearMode) == Motion.STATIONARY;
    }

    /** Whether the snapshot gear is a real reading (P … S), as opposed to UNAVAILABLE / garbage. */
    public static boolean isKnownGear(int gearMode) {
        return gearMode >= GEAR_PARK && gearMode <= GEAR_LAST_VALID;
    }

    /**
     * Human-readable evidence for the status file and the log, e.g. {@code "moving (12.0 km/h)"},
     * {@code "stationary (gear P)"} or {@code "no stationary evidence (speed and gear unknown)"}.
     */
    public static String describeMotion(double speedKmh, int gearMode) {
        switch (motion(speedKmh, gearMode)) {
            case MOVING:
                return String.format(Locale.US, "moving (%.1f km/h)", speedKmh);
            case STATIONARY:
                return Double.isFinite(speedKmh)
                        ? String.format(Locale.US, "stationary (%.1f km/h)", speedKmh)
                        : "stationary (gear P)";
            default:
                return isKnownGear(gearMode)
                        ? "no stationary evidence (speed unknown, gear code " + gearMode
                                + " is not P)"
                        : "no stationary evidence (speed and gear unknown)";
        }
    }

    /** A run belongs to exactly one ignition cycle and only while ACC is on. */
    public static boolean cycleAborted(long myGeneration, long currentGeneration, boolean accOn) {
        return myGeneration != currentGeneration || !accOn;
    }

    /** The readiness window has closed without the adapter coming up. */
    public static boolean readinessDeadlinePassed(long startedAtMs, long nowMs) {
        return nowMs - startedAtMs >= READINESS_DEADLINE_MS;
    }
}
