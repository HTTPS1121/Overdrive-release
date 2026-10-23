package com.overdrive.app.launcher;

import java.util.function.BooleanSupplier;

/**
 * Settings-first escalation policy for DiLink 5 ADB recovery.
 *
 * <p>Ordinary settings re-enable always runs first and unchanged. This policy
 * decides when, after repeated closed-port observations and a settling period,
 * the gated OEM fallback may escalate once. It is pure in-process state with an
 * injected clock (callers pass {@code nowMs}); every method is synchronized so
 * the port-watch thread (observations/decisions) and the escalation thread
 * (finish/abort) can call it safely.
 */
public final class DiLink5AdbEscalationPolicy {

    /** Consecutive closed observations required before escalation is considered. */
    public static final int MIN_CLOSED_OBSERVATIONS = 5;

    /** Minimum age of the closed window and of the last effective settings write. */
    public static final long SETTLE_MS = 90_000L;

    /** Hard cap on escalations within one park cycle. */
    public static final int MAX_ESCALATIONS_PER_PARK_CYCLE = 3;

    /** Minimum spacing between two escalations in one park cycle. */
    public static final long MIN_ESCALATION_SPACING_MS = 15L * 60_000L;

    /** A connection seen within this window of an escalation end counts as confirmation. */
    public static final long CONFIRMATION_WINDOW_MS = 10L * 60_000L;

    private int consecutiveClosed;
    private long firstClosedAtMs = -1L;
    private int escalationsThisCycle;
    private long lastEscalationEndMs = -1L;
    private Boolean lastAccOn;

    /**
     * Reason {@link #decide} returned, in evaluation order. The cheap in-process
     * checks come first; {@link #NOT_PARKED} is last because the parked check is
     * the only one that costs more than a field read and is therefore evaluated
     * lazily, only when everything else already allows an escalation.
     */
    public enum Decision {
        CONNECTED,
        AUTH_PENDING,
        PORT_OPEN,
        IN_FLIGHT,
        WAIT_OBSERVATIONS,
        WAIT_SETTLE,
        BUDGET_EXHAUSTED,
        SPACING,
        NOT_PARKED,
        ESCALATE
    }

    /** Record a closed-port observation (auth not pending). */
    public synchronized void observeClosed(long nowMs) {
        consecutiveClosed++;
        if (firstClosedAtMs < 0L) {
            firstClosedAtMs = nowMs;
        }
    }

    /** Record an open-port observation: the closed window is over. */
    public synchronized void observeOpen() {
        consecutiveClosed = 0;
        firstClosedAtMs = -1L;
    }

    /** A shared connection was installed: reset the closed window and the budget. */
    public synchronized void onConnectionInstalled() {
        resetWindow();
        resetBudget();
    }

    /** An ACC power transition: reset the closed window and the budget (new park cycle). */
    public synchronized void onPowerTransition() {
        resetWindow();
        resetBudget();
    }

    /**
     * Feed an ACC power sample. Stores the first sample; on a flip, applies
     * {@link #onPowerTransition()} and returns {@code true}; otherwise returns
     * {@code false}.
     */
    public synchronized boolean notePowerSample(boolean accOn) {
        if (lastAccOn == null) {
            lastAccOn = accOn;
            return false;
        }
        if (lastAccOn != accOn) {
            lastAccOn = accOn;
            onPowerTransition();
            return true;
        }
        return false;
    }

    /**
     * Decide whether to escalate now. Conditions are evaluated in {@link Decision}
     * order, so a healthy connection, pending authorization, an open port, or an
     * in-flight escalation each short-circuits a counter that would otherwise be
     * ready. {@code lastEffectiveSettingsWriteMs <= 0} skips the settings-settle
     * clause. {@code parkedKeepAwake} is consulted last and at most once, only
     * when the counter, settle, budget and spacing clauses already allow an
     * escalation, so the per-observation cost stays at a few field reads.
     */
    public synchronized Decision decide(
            long nowMs,
            long lastEffectiveSettingsWriteMs,
            boolean connected,
            boolean authPending,
            boolean portOpen,
            BooleanSupplier parkedKeepAwake,
            boolean inFlight) {
        if (connected) {
            return Decision.CONNECTED;
        }
        if (authPending) {
            return Decision.AUTH_PENDING;
        }
        if (portOpen) {
            return Decision.PORT_OPEN;
        }
        if (inFlight) {
            return Decision.IN_FLIGHT;
        }
        if (consecutiveClosed < MIN_CLOSED_OBSERVATIONS) {
            return Decision.WAIT_OBSERVATIONS;
        }
        if (firstClosedAtMs < 0L || nowMs - firstClosedAtMs < SETTLE_MS) {
            return Decision.WAIT_SETTLE;
        }
        if (lastEffectiveSettingsWriteMs > 0L
                && nowMs - lastEffectiveSettingsWriteMs < SETTLE_MS) {
            return Decision.WAIT_SETTLE;
        }
        if (escalationsThisCycle >= MAX_ESCALATIONS_PER_PARK_CYCLE) {
            return Decision.BUDGET_EXHAUSTED;
        }
        if (lastEscalationEndMs >= 0L
                && nowMs - lastEscalationEndMs < MIN_ESCALATION_SPACING_MS) {
            return Decision.SPACING;
        }
        if (!parkedKeepAwake.getAsBoolean()) {
            return Decision.NOT_PARKED;
        }
        return Decision.ESCALATE;
    }

    /**
     * Mark an escalation finished after at least one OEM request was issued: spend
     * one budget slot, stamp the end (arms spacing and the confirmation window),
     * reset the window.
     */
    public synchronized void onEscalationFinished(long nowMs) {
        escalationsThisCycle++;
        lastEscalationEndMs = nowMs;
        resetWindow();
    }

    /**
     * Mark an escalation that ended before any OEM request was issued (a
     * precondition changed or the lease could not be claimed). Only the closed
     * window is reset: no budget slot is spent and neither the spacing nor the
     * confirmation window is armed, since nothing happened that could confirm.
     */
    public synchronized void onEscalationAborted() {
        resetWindow();
    }

    /** True when an escalation ended within {@link #CONFIRMATION_WINDOW_MS} of {@code nowMs}. */
    public synchronized boolean withinConfirmationWindow(long nowMs) {
        return lastEscalationEndMs >= 0L
                && nowMs - lastEscalationEndMs <= CONFIRMATION_WINDOW_MS;
    }

    private void resetWindow() {
        consecutiveClosed = 0;
        firstClosedAtMs = -1L;
    }

    private void resetBudget() {
        escalationsThisCycle = 0;
        lastEscalationEndMs = -1L;
    }
}
