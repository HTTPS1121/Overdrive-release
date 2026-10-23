package com.overdrive.app.byd.assist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.assist.DiLink5AssistOffGate.Control;
import com.overdrive.app.byd.assist.DiLink5AssistOffPlan.Action;
import com.overdrive.app.byd.assist.DiLink5AssistOffPlan.Motion;

import org.junit.Test;

public class DiLink5AssistOffPlanTest {

    private static final int NA = DiLink5AssistOffControls.READ_UNAVAILABLE;
    private static final long WINDOW = DiLink5AssistOffPlan.VERIFY_WINDOW_MS;
    private static final long SPACING = DiLink5AssistOffPlan.MIN_ATTEMPT_SPACING_MS;

    @Test
    public void budgetIsThreeAttemptsThenGiveUp() {
        DiLink5AssistOffPlan plan = new DiLink5AssistOffPlan(Control.LDA);
        long t = 0L;
        // LDA reads 3 (both) throughout: each accepted write is polled for the verify window.
        for (int attempt = 1; attempt <= DiLink5AssistOffPlan.MAX_ATTEMPTS; attempt++) {
            assertEquals("attempt " + attempt, Action.WRITE, plan.nextAction(t, 3, true));
            plan.noteWrite(t, true);
            assertEquals(attempt, plan.attempts());
            for (long dt = 500L; dt < WINDOW; dt += 500L) {
                assertEquals(Action.WAIT, plan.nextAction(t + dt, 3, true));
            }
            t += WINDOW;
        }
        assertEquals(Action.GIVE_UP, plan.nextAction(t, 3, true));
        assertTrue(plan.isTerminal());
        assertEquals(Action.GIVE_UP, plan.terminal());
        // Terminal is sticky: no fourth write even when more time passes or a read changes.
        assertEquals(Action.GIVE_UP, plan.nextAction(t + 60_000L, 3, true));
        assertEquals(DiLink5AssistOffPlan.MAX_ATTEMPTS, plan.attempts());
        plan.noteWrite(t, true);
        assertEquals(DiLink5AssistOffPlan.MAX_ATTEMPTS, plan.attempts());
    }

    @Test
    public void refusedWriteIsRetriedAfterTheMinimumSpacing() {
        DiLink5AssistOffPlan plan = new DiLink5AssistOffPlan(Control.ELKA);
        assertEquals(Action.WRITE, plan.nextAction(0L, 1, true));
        plan.noteWrite(0L, false);
        // No verify window after a refusal, but never two writes closer than the spacing.
        assertEquals(Action.WAIT, plan.nextAction(500L, 1, true));
        assertEquals(Action.WAIT, plan.nextAction(SPACING - 1L, 1, true));
        assertEquals(Action.WRITE, plan.nextAction(SPACING, 1, true));
    }

    @Test
    public void acceptedWritesAreAlwaysAtLeastTheSpacingApart() {
        DiLink5AssistOffPlan plan = new DiLink5AssistOffPlan(Control.LDA);
        plan.noteWrite(0L, true);
        long firstRetry = -1L;
        for (long t = 0L; t <= WINDOW + SPACING; t += 100L) {
            if (plan.nextAction(t, 3, true) == Action.WRITE) {
                firstRetry = t;
                break;
            }
        }
        assertTrue(firstRetry >= SPACING);
        assertEquals(WINDOW, firstRetry);
    }

    @Test
    public void verifiedStopsFurtherAttempts() {
        DiLink5AssistOffPlan plan = new DiLink5AssistOffPlan(Control.CPD);
        assertEquals(Action.WRITE, plan.nextAction(0L, 1, true));
        plan.noteWrite(0L, true);
        assertEquals(Action.WAIT, plan.nextAction(500L, 1, true));
        assertEquals(Action.VERIFIED, plan.nextAction(1_000L, 2, true));
        assertTrue(plan.isTerminal());
        assertEquals(Action.VERIFIED, plan.nextAction(WINDOW + SPACING, 1, true));
        assertEquals(1, plan.attempts());
    }

    @Test
    public void alreadyOffNeedsNoWrite() {
        DiLink5AssistOffPlan cpd = new DiLink5AssistOffPlan(Control.CPD);
        assertEquals(Action.ALREADY_OFF, cpd.nextAction(0L, 2, false));
        assertEquals(0, cpd.attempts());

        // Lane zero is ambiguous before readiness: write; trusted after readiness: already off.
        DiLink5AssistOffPlan ldaCold = new DiLink5AssistOffPlan(Control.LDA);
        assertEquals(Action.WRITE, ldaCold.nextAction(0L, 0, false));
        DiLink5AssistOffPlan ldaReady = new DiLink5AssistOffPlan(Control.LDA);
        assertEquals(Action.ALREADY_OFF, ldaReady.nextAction(0L, 0, true));
    }

    @Test
    public void laneZeroAfterAWriteIsVerifiedOnlyWithReadiness() {
        DiLink5AssistOffPlan plan = new DiLink5AssistOffPlan(Control.ELKA);
        assertEquals(Action.WRITE, plan.nextAction(0L, 1, false));
        plan.noteWrite(0L, true);
        // Readiness never observed this cycle: a 0 is not proof, so the plan keeps waiting.
        assertEquals(Action.WAIT, plan.nextAction(1_000L, 0, false));
        assertEquals(Action.VERIFIED, plan.nextAction(1_500L, 0, true));
    }

    @Test
    public void cpdZeroIsNeverVerified() {
        DiLink5AssistOffPlan plan = new DiLink5AssistOffPlan(Control.CPD);
        assertEquals(Action.WRITE, plan.nextAction(0L, 0, true));
        plan.noteWrite(0L, true);
        assertEquals(Action.WAIT, plan.nextAction(1_000L, 0, true));
        assertEquals(Action.WRITE, plan.nextAction(WINDOW, 0, true));
    }

    @Test
    public void unavailableReadIsNeverAConfirmation() {
        DiLink5AssistOffPlan plan = new DiLink5AssistOffPlan(Control.LDA);
        assertEquals(Action.WRITE, plan.nextAction(0L, NA, true));
        plan.noteWrite(0L, true);
        assertEquals(Action.WAIT, plan.nextAction(1_000L, NA, true));
        assertFalse(plan.isTerminal());
    }

    @Test
    public void dmsIsRequestSentAndNeverVerified() {
        DiLink5AssistOffPlan plan = new DiLink5AssistOffPlan(Control.DMS);
        // No readback: even a "0" never counts as already off.
        assertEquals(Action.WRITE, plan.nextAction(0L, 0, true));
        plan.noteRequestSent(0L);
        assertEquals(Action.REQUEST_SENT, plan.terminal());
        for (int read : new int[] {0, 1, 2, NA}) {
            assertEquals(Action.REQUEST_SENT, plan.nextAction(10_000L, read, true));
        }
        assertEquals(1, plan.attempts());

        // Failed Binder calls use the same budget and end in GIVE_UP, still never VERIFIED.
        DiLink5AssistOffPlan failing = new DiLink5AssistOffPlan(Control.DMS);
        long t = 0L;
        for (int attempt = 1; attempt <= DiLink5AssistOffPlan.MAX_ATTEMPTS; attempt++) {
            assertEquals(Action.WRITE, failing.nextAction(t, 0, true));
            failing.noteWrite(t, false);
            assertEquals(Action.WAIT, failing.nextAction(t + SPACING - 1L, 0, true));
            t += SPACING;
        }
        assertEquals(Action.GIVE_UP, failing.nextAction(t, 0, true));
        for (Action a : new Action[] {plan.terminal(), failing.terminal()}) {
            assertTrue(a != Action.VERIFIED && a != Action.ALREADY_OFF);
        }
    }

    @Test
    public void stationaryNeedsPositiveEvidence() {
        int unknownGear = Integer.MIN_VALUE;   // BydVehicleData.UNAVAILABLE
        int parked = DiLink5AssistOffPlan.GEAR_PARK;
        int drive = com.overdrive.app.monitor.GearMonitor.GEAR_D;   // compile-time constant, inlined
        assertEquals(1, parked);                                     // raw gearbox enum: P = 1

        // (a) A known speed at or below 3 km/h is stationary evidence on its own.
        assertEquals(Motion.STATIONARY, DiLink5AssistOffPlan.motion(0.0, unknownGear));
        assertEquals(Motion.STATIONARY, DiLink5AssistOffPlan.motion(2.0, unknownGear));
        assertEquals(Motion.STATIONARY, DiLink5AssistOffPlan.motion(3.0, drive));
        assertTrue(DiLink5AssistOffPlan.isStationary(2.0, unknownGear));
        // (b) A known gear P is stationary evidence when the speed is unknown.
        assertEquals(Motion.STATIONARY, DiLink5AssistOffPlan.motion(Double.NaN, parked));
        assertTrue(DiLink5AssistOffPlan.isStationary(Double.NaN, parked));
        // Unknown speed + unknown gear is NOT stationary (the review's safety finding).
        assertEquals(Motion.NO_EVIDENCE, DiLink5AssistOffPlan.motion(Double.NaN, unknownGear));
        assertFalse(DiLink5AssistOffPlan.isStationary(Double.NaN, unknownGear));
        // Unknown speed + a known gear other than P is no evidence either.
        assertEquals(Motion.NO_EVIDENCE, DiLink5AssistOffPlan.motion(Double.NaN, drive));
        assertEquals(Motion.NO_EVIDENCE, DiLink5AssistOffPlan.motion(Double.NaN, 0));
        assertEquals(Motion.NO_EVIDENCE, DiLink5AssistOffPlan.motion(Double.NaN, 7));
        // A known speed above 3 km/h is moving, whatever the gear says.
        assertEquals(Motion.MOVING, DiLink5AssistOffPlan.motion(5.0, unknownGear));
        assertEquals(Motion.MOVING, DiLink5AssistOffPlan.motion(3.1, parked));
        assertEquals(Motion.MOVING, DiLink5AssistOffPlan.motion(12.0, drive));
        assertFalse(DiLink5AssistOffPlan.isStationary(5.0, parked));
        // Non-finite speeds are "unknown", never "moving" nor "stationary" by themselves.
        assertEquals(Motion.NO_EVIDENCE, DiLink5AssistOffPlan.motion(
                Double.POSITIVE_INFINITY, unknownGear));
        assertEquals(Motion.STATIONARY, DiLink5AssistOffPlan.motion(
                Double.POSITIVE_INFINITY, parked));
        // Gear validity follows the raw enum range P..S (1..6).
        for (int g = 1; g <= 6; g++) assertTrue("gear " + g, DiLink5AssistOffPlan.isKnownGear(g));
        assertFalse(DiLink5AssistOffPlan.isKnownGear(0));
        assertFalse(DiLink5AssistOffPlan.isKnownGear(7));
        assertFalse(DiLink5AssistOffPlan.isKnownGear(unknownGear));
    }

    @Test
    public void motionDescriptionsMatchTheStatusVocabulary() {
        int unknownGear = Integer.MIN_VALUE;
        // The no-evidence skip reason the applier writes when the readiness deadline passes.
        assertEquals("no stationary evidence (speed and gear unknown)",
                DiLink5AssistOffPlan.describeMotion(Double.NaN, unknownGear));
        assertEquals("no stationary evidence (speed unknown, gear code 4 is not P)",
                DiLink5AssistOffPlan.describeMotion(Double.NaN, 4));
        assertEquals("moving (12.0 km/h)", DiLink5AssistOffPlan.describeMotion(12.0, unknownGear));
        assertEquals("moving (5.0 km/h)", DiLink5AssistOffPlan.describeMotion(5.0, 1));
        assertEquals("stationary (2.0 km/h)", DiLink5AssistOffPlan.describeMotion(2.0, unknownGear));
        assertEquals("stationary (gear P)",
                DiLink5AssistOffPlan.describeMotion(Double.NaN, DiLink5AssistOffPlan.GEAR_PARK));
    }

    @Test
    public void cycleGuardsAreExact() {
        // Moving-vehicle skip: writes only with positive stationary evidence (see
        // stationaryNeedsPositiveEvidence); an unknown speed alone is never stationary.
        assertTrue(DiLink5AssistOffPlan.isStationary(0.0, Integer.MIN_VALUE));
        assertTrue(DiLink5AssistOffPlan.isStationary(3.0, Integer.MIN_VALUE));
        assertFalse(DiLink5AssistOffPlan.isStationary(Double.NaN, Integer.MIN_VALUE));
        assertFalse(DiLink5AssistOffPlan.isStationary(3.1, Integer.MIN_VALUE));
        assertFalse(DiLink5AssistOffPlan.isStationary(12.0, Integer.MIN_VALUE));
        assertFalse(DiLink5AssistOffPlan.isStationary(Double.POSITIVE_INFINITY, Integer.MIN_VALUE));

        // Generation abort: a run belongs to one ignition cycle and only while ACC is on.
        assertFalse(DiLink5AssistOffPlan.cycleAborted(7L, 7L, true));
        assertTrue(DiLink5AssistOffPlan.cycleAborted(7L, 8L, true));    // ACC edge raced the run
        assertTrue(DiLink5AssistOffPlan.cycleAborted(7L, 7L, false));   // ACC read off
        assertTrue(DiLink5AssistOffPlan.cycleAborted(7L, 9L, false));

        // Readiness deadline: 90 s after the run started, inclusive.
        long started = 1_000L;
        assertFalse(DiLink5AssistOffPlan.readinessDeadlinePassed(started, started));
        assertFalse(DiLink5AssistOffPlan.readinessDeadlinePassed(
                started, started + DiLink5AssistOffPlan.READINESS_DEADLINE_MS - 1L));
        assertTrue(DiLink5AssistOffPlan.readinessDeadlinePassed(
                started, started + DiLink5AssistOffPlan.READINESS_DEADLINE_MS));
        assertEquals(90_000L, DiLink5AssistOffPlan.READINESS_DEADLINE_MS);
        assertEquals(3_000L, DiLink5AssistOffPlan.READINESS_POLL_MS);
    }

    @Test
    public void externalFailureIsTerminalAndKeepsItsReason() {
        DiLink5AssistOffPlan plan = new DiLink5AssistOffPlan(Control.LDA);
        assertNull(plan.terminal());
        plan.fail("superseded");
        assertEquals(Action.FAILED, plan.nextAction(0L, 0, true));
        assertEquals("superseded", plan.failure());
        plan.fail("other");
        assertEquals("superseded", plan.failure());
        assertTrue(Action.FAILED.isTerminal());
        assertFalse(Action.WRITE.isTerminal());
        assertFalse(Action.WAIT.isTerminal());
    }
}
