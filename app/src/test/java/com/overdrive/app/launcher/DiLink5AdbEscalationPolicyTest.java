package com.overdrive.app.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.Test;

public class DiLink5AdbEscalationPolicyTest {

    private static final BooleanSupplier PARKED = () -> true;
    private static final BooleanSupplier NOT_PARKED = () -> false;

    /** decide() with "parked, nothing else wrong" so the counter/budget gates are what matter. */
    private static DiLink5AdbEscalationPolicy.Decision decideReady(
            DiLink5AdbEscalationPolicy policy, long nowMs, long lastWriteMs) {
        return policy.decide(nowMs, lastWriteMs, false, false, false, PARKED, false);
    }

    /** Five closed observations, firstClosedAtMs = 1000. */
    private static DiLink5AdbEscalationPolicy readyWindow() {
        DiLink5AdbEscalationPolicy policy = new DiLink5AdbEscalationPolicy();
        for (int i = 0; i < 5; i++) {
            policy.observeClosed(1_000L);
        }
        return policy;
    }

    /** A parked check that counts how often decide() consulted it. */
    private static final class CountingParkedCheck implements BooleanSupplier {
        final AtomicInteger calls = new AtomicInteger();
        private final boolean value;

        CountingParkedCheck(boolean value) {
            this.value = value;
        }

        @Override
        public boolean getAsBoolean() {
            calls.incrementAndGet();
            return value;
        }
    }

    @Test
    public void fourClosedWaitsForObservations() {
        DiLink5AdbEscalationPolicy policy = new DiLink5AdbEscalationPolicy();
        for (int i = 0; i < 4; i++) {
            policy.observeClosed(1_000L);
        }
        assertEquals(DiLink5AdbEscalationPolicy.Decision.WAIT_OBSERVATIONS,
                decideReady(policy, 1_000L, 0L));
    }

    @Test
    public void fiveClosedWithinSettleWaits() {
        DiLink5AdbEscalationPolicy policy = readyWindow();
        assertEquals(DiLink5AdbEscalationPolicy.Decision.WAIT_SETTLE,
                decideReady(policy, 1_000L + 8_000L, 0L));
    }

    @Test
    public void fiveClosedSpanningSettleEscalates() {
        DiLink5AdbEscalationPolicy policy = readyWindow();
        assertEquals(DiLink5AdbEscalationPolicy.Decision.ESCALATE,
                decideReady(policy, 1_000L + 90_000L, 0L));
    }

    @Test
    public void recentEffectiveSettingsWriteWaits() {
        DiLink5AdbEscalationPolicy policy = readyWindow();
        long now = 1_000L + 90_000L;
        assertEquals(DiLink5AdbEscalationPolicy.Decision.WAIT_SETTLE,
                decideReady(policy, now, now - 30_000L));
    }

    @Test
    public void openResetsTheCounter() {
        DiLink5AdbEscalationPolicy policy = readyWindow();
        policy.observeOpen();
        assertEquals(DiLink5AdbEscalationPolicy.Decision.WAIT_OBSERVATIONS,
                decideReady(policy, 1_000L + 90_000L, 0L));
    }

    @Test
    public void higherPriorityConditionsWinOverReadyCounter() {
        long now = 1_000L + 90_000L;
        assertEquals(DiLink5AdbEscalationPolicy.Decision.CONNECTED,
                readyWindow().decide(now, 0L, true, false, false, PARKED, false));
        assertEquals(DiLink5AdbEscalationPolicy.Decision.AUTH_PENDING,
                readyWindow().decide(now, 0L, false, true, false, PARKED, false));
        assertEquals(DiLink5AdbEscalationPolicy.Decision.PORT_OPEN,
                readyWindow().decide(now, 0L, false, false, true, PARKED, false));
        assertEquals(DiLink5AdbEscalationPolicy.Decision.NOT_PARKED,
                readyWindow().decide(now, 0L, false, false, false, NOT_PARKED, false));
        assertEquals(DiLink5AdbEscalationPolicy.Decision.IN_FLIGHT,
                readyWindow().decide(now, 0L, false, false, false, PARKED, true));
    }

    @Test
    public void parkedCheckIsConsultedOnlyWhenEverythingElseAllowsEscalation() {
        long ready = 1_000L + 90_000L;

        // Counter not ready: the parked check is never evaluated.
        DiLink5AdbEscalationPolicy fourClosed = new DiLink5AdbEscalationPolicy();
        for (int i = 0; i < 4; i++) {
            fourClosed.observeClosed(1_000L);
        }
        CountingParkedCheck check = new CountingParkedCheck(false);
        assertEquals(DiLink5AdbEscalationPolicy.Decision.WAIT_OBSERVATIONS,
                fourClosed.decide(ready, 0L, false, false, false, check, false));
        assertEquals(0, check.calls.get());

        // Settle not reached: never evaluated.
        check = new CountingParkedCheck(false);
        assertEquals(DiLink5AdbEscalationPolicy.Decision.WAIT_SETTLE,
                readyWindow().decide(1_000L + 8_000L, 0L, false, false, false, check, false));
        assertEquals(0, check.calls.get());

        // Higher-priority short-circuits: never evaluated.
        check = new CountingParkedCheck(false);
        assertEquals(DiLink5AdbEscalationPolicy.Decision.CONNECTED,
                readyWindow().decide(ready, 0L, true, false, false, check, false));
        assertEquals(DiLink5AdbEscalationPolicy.Decision.IN_FLIGHT,
                readyWindow().decide(ready, 0L, false, false, false, check, true));
        assertEquals(0, check.calls.get());

        // Budget exhausted: never evaluated.
        DiLink5AdbEscalationPolicy exhausted = new DiLink5AdbEscalationPolicy();
        for (int cycle = 0; cycle < 3; cycle++) {
            exhausted.onEscalationFinished(1_000L);
        }
        for (int i = 0; i < 5; i++) {
            exhausted.observeClosed(1_000L);
        }
        check = new CountingParkedCheck(false);
        assertEquals(DiLink5AdbEscalationPolicy.Decision.BUDGET_EXHAUSTED,
                exhausted.decide(ready, 0L, false, false, false, check, false));
        assertEquals(0, check.calls.get());

        // Everything else allows it: evaluated exactly once, and it decides.
        check = new CountingParkedCheck(false);
        assertEquals(DiLink5AdbEscalationPolicy.Decision.NOT_PARKED,
                readyWindow().decide(ready, 0L, false, false, false, check, false));
        assertEquals(1, check.calls.get());
        check = new CountingParkedCheck(true);
        assertEquals(DiLink5AdbEscalationPolicy.Decision.ESCALATE,
                readyWindow().decide(ready, 0L, false, false, false, check, false));
        assertEquals(1, check.calls.get());
    }

    @Test
    public void budgetExhaustsAfterThreeEscalations() {
        DiLink5AdbEscalationPolicy policy = new DiLink5AdbEscalationPolicy();
        long t = 1_000L;
        for (int cycle = 0; cycle < 3; cycle++) {
            policy.onEscalationFinished(t);
            t += 20L * 60_000L;
        }
        for (int i = 0; i < 5; i++) {
            policy.observeClosed(t);
        }
        assertEquals(DiLink5AdbEscalationPolicy.Decision.BUDGET_EXHAUSTED,
                decideReady(policy, t + 90_000L, 0L));
    }

    @Test
    public void spacingThenEscalateAfterFifteenMinutes() {
        DiLink5AdbEscalationPolicy policy = new DiLink5AdbEscalationPolicy();
        long end = 1_000L;
        policy.onEscalationFinished(end);
        for (int i = 0; i < 5; i++) {
            policy.observeClosed(end);
        }
        assertEquals(DiLink5AdbEscalationPolicy.Decision.SPACING,
                decideReady(policy, end + 10L * 60_000L, 0L));
        assertEquals(DiLink5AdbEscalationPolicy.Decision.ESCALATE,
                decideReady(policy, end + 15L * 60_000L, 0L));
    }

    @Test
    public void abortedEscalationSpendsNoBudgetAndArmsNothing() {
        DiLink5AdbEscalationPolicy policy = readyWindow();
        long t = 1_000L + 90_000L;

        // The abort resets the closed window ...
        policy.onEscalationAborted();
        assertEquals(DiLink5AdbEscalationPolicy.Decision.WAIT_OBSERVATIONS,
                decideReady(policy, t, 0L));
        // ... but arms neither the confirmation window nor the spacing clause.
        assertFalse(policy.withinConfirmationWindow(t));
        for (int i = 0; i < 5; i++) {
            policy.observeClosed(t);
        }
        assertEquals(DiLink5AdbEscalationPolicy.Decision.ESCALATE,
                decideReady(policy, t + 90_000L, 0L));

        // Any number of aborts leaves the full budget intact.
        for (int n = 0; n < 10; n++) {
            policy.onEscalationAborted();
        }
        long later = t + 90_000L;
        for (int i = 0; i < 5; i++) {
            policy.observeClosed(later);
        }
        assertEquals(DiLink5AdbEscalationPolicy.Decision.ESCALATE,
                decideReady(policy, later + 90_000L, 0L));
    }

    @Test
    public void connectionInstallResetsBudget() {
        DiLink5AdbEscalationPolicy policy = new DiLink5AdbEscalationPolicy();
        for (int cycle = 0; cycle < 3; cycle++) {
            policy.onEscalationFinished(1_000L);
        }
        policy.onConnectionInstalled();
        long t = 100_000L;
        for (int i = 0; i < 5; i++) {
            policy.observeClosed(t);
        }
        assertEquals(DiLink5AdbEscalationPolicy.Decision.ESCALATE,
                decideReady(policy, t + 90_000L, 0L));
    }

    @Test
    public void powerFlipResetsBudgetAndReportsOnlyOnFlip() {
        DiLink5AdbEscalationPolicy policy = new DiLink5AdbEscalationPolicy();
        for (int cycle = 0; cycle < 3; cycle++) {
            policy.onEscalationFinished(1_000L);
        }
        assertFalse(policy.notePowerSample(false));  // first sample only stores
        assertFalse(policy.notePowerSample(false));  // same value, no flip
        assertTrue(policy.notePowerSample(true));     // flip resets budget
        long t = 100_000L;
        for (int i = 0; i < 5; i++) {
            policy.observeClosed(t);
        }
        assertEquals(DiLink5AdbEscalationPolicy.Decision.ESCALATE,
                decideReady(policy, t + 90_000L, 0L));
    }

    @Test
    public void confirmationWindowTracksLastEscalationEnd() {
        DiLink5AdbEscalationPolicy policy = new DiLink5AdbEscalationPolicy();
        assertFalse(policy.withinConfirmationWindow(1_000L));
        policy.onEscalationFinished(1_000L);
        assertTrue(policy.withinConfirmationWindow(1_000L + 10L * 60_000L));
        assertFalse(policy.withinConfirmationWindow(1_000L + 10L * 60_000L + 1L));
    }
}
