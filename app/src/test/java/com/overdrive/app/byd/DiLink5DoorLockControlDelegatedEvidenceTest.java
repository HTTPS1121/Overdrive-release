package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

/**
 * 1.6c case (C): the typed rail is INVALID on this car, so the daemon's {@code carsvc} evidence carried
 * in the proximity scope authorises the typed write and confirmation is delegated to the daemon. Never
 * CONFIRMED from the app on this path; selected ONLY for gate rail {@code carsvc}.
 */
public class DiLink5DoorLockControlDelegatedEvidenceTest {
    private static final int APP_UNLOCKED = 1;
    private static final int APP_LOCKED = 2;
    private static final long NOW = 100_000L;

    private static final class FakeClock implements DiLink5DoorLockControl.Clock {
        long now = NOW;
        int sleeps;
        public long elapsedMs() { return now; }
        public void sleep(long millis) { sleeps++; now += millis; }
    }

    /** Typed cabin port; {@code state} is the native value its getter returns (0 = INVALID). */
    private static final class Port implements DiLink5DoorLockControl.NativePort {
        boolean ready = true;
        int state;
        int rc;
        int writes;
        int writtenState;
        int reads;
        int readsAtWrite = -1;
        boolean updateOnWrite = true;
        RuntimeException readFailure;
        public boolean ready() { return ready; }
        public int readDriverLock() {
            reads++;
            if (readFailure != null) throw readFailure;
            return state;
        }
        public int writeAllDoors(int value) {
            writes++;
            writtenState = value;
            readsAtWrite = reads;
            if (updateOnWrite) state = value;
            return rc;
        }
    }

    private static DiLink5DoorLockControl.Result delegated(Port port, FakeClock clock, boolean locked,
                                                           int gateStateApp, long gateAgeMs) {
        return DiLink5DoorLockControl.applyWithDelegatedEvidence(
                port, locked, gateStateApp, clock.now - gateAgeMs, () -> true, () -> true, clock);
    }

    @Test
    public void validAgreeingTypedReadTakesThePlainApplyPath() {
        Port port = new Port();
        port.state = DiLink5DoorLockControl.LOCK;
        FakeClock clock = new FakeClock();
        // Gate (carsvc) says locked, typed agrees → today's apply(): write unlock, typed confirmation.
        DiLink5DoorLockControl.Result result = delegated(port, clock, false, APP_LOCKED, 1_000L);
        assertTrue(result.reason, result.confirmed);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.CONFIRMED, result.outcome);
        assertEquals("driver-door readback confirmed", result.reason);
        assertEquals(1, port.writes);
        assertEquals(DiLink5DoorLockControl.UNLOCK, port.writtenState);
        // One extra typed read (the delegated pre-read) before apply's own pre-read and confirmation.
        assertEquals(4, port.reads);
    }

    @Test
    public void validDisagreeingTypedReadFailsClosedWithoutWrite() {
        Port port = new Port();
        port.state = DiLink5DoorLockControl.UNLOCK;
        DiLink5DoorLockControl.Result result = delegated(port, new FakeClock(), false, APP_LOCKED, 1_000L);
        assertFalse(result.confirmed);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.FAILED, result.outcome);
        assertTrue(result.reason, result.reason.startsWith("rail disagreement "));
        assertEquals("rail disagreement typed=2 gate=1 rail=carsvc", result.reason);
        assertEquals(0, port.writes);
    }

    @Test
    public void tooOldGateEvidenceFailsClosedWithoutWrite() {
        Port port = new Port();
        DiLink5DoorLockControl.Result result = delegated(port, new FakeClock(), false, APP_LOCKED, 6_001L);
        assertFalse(result.confirmed);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.FAILED, result.outcome);
        assertEquals("gate evidence too old for write ageMs=6001", result.reason);
        assertTrue(result.reason.contains("gate evidence too old"));
        assertEquals(0, port.writes);
        assertEquals(6_000L, DiLink5DoorLockControl.DILINK5_GATE_EVIDENCE_WRITE_MAX_AGE_MS);
        // The bound is inclusive: exactly 6 000 ms still writes.
        Port boundary = new Port();
        DiLink5DoorLockControl.Result atBound = delegated(boundary, new FakeClock(), false, APP_LOCKED, 6_000L);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.WRITTEN_UNCONFIRMED, atBound.outcome);
        assertEquals(1, boundary.writes);
    }

    @Test
    public void alreadyAtTargetPerTheGateIsWrittenUnconfirmedWithoutWrite() {
        Port port = new Port();
        FakeClock clock = new FakeClock();
        DiLink5DoorLockControl.Result result = delegated(port, clock, false, APP_UNLOCKED, 500L);
        assertFalse(result.confirmed);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.WRITTEN_UNCONFIRMED, result.outcome);
        assertEquals("nothing written; already at target per gate rail carsvc; confirmation delegated",
                result.reason);
        assertEquals(0, port.writes);
        DiLink5DoorLockControl.Result locked = delegated(new Port(), clock, true, APP_LOCKED, 500L);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.WRITTEN_UNCONFIRMED, locked.outcome);
        assertFalse(locked.confirmed);
    }

    @Test
    public void refusedWriteFails() {
        Port port = new Port();
        port.rc = -10;
        DiLink5DoorLockControl.Result result = delegated(port, new FakeClock(), false, APP_LOCKED, 500L);
        assertFalse(result.confirmed);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.FAILED, result.outcome);
        assertEquals("setAllDoorsLock refused rc=-10", result.reason);
        assertEquals(1, port.writes);
    }

    @Test
    public void happyPathWritesOnceAndNeverReadsBack() {
        Port port = new Port();
        port.updateOnWrite = false;
        FakeClock clock = new FakeClock();
        DiLink5DoorLockControl.Result unlock = delegated(port, clock, false, APP_LOCKED, 2_000L);
        assertFalse(unlock.confirmed);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.WRITTEN_UNCONFIRMED, unlock.outcome);
        assertEquals("setAllDoorsLock accepted rc=0; confirmation delegated to daemon rail carsvc", unlock.reason);
        assertEquals(1, port.writes);
        assertEquals(DiLink5DoorLockControl.UNLOCK, port.writtenState);
        assertEquals(port.readsAtWrite, port.reads); // zero confirmation reads after the write
        assertEquals(0, clock.sleeps);
        assertEquals(NOW, clock.now);

        Port lockPort = new Port();
        DiLink5DoorLockControl.Result lock = delegated(lockPort, clock, true, APP_UNLOCKED, 2_000L);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.WRITTEN_UNCONFIRMED, lock.outcome);
        assertFalse(lock.confirmed);
        assertEquals(DiLink5DoorLockControl.LOCK, lockPort.writtenState);
        assertEquals(lockPort.readsAtWrite, lockPort.reads);
    }

    @Test
    public void revocationBeforeTheWriteFailsWithoutWrite() {
        Port port = new Port();
        FakeClock clock = new FakeClock();
        DiLink5DoorLockControl.Result result = DiLink5DoorLockControl.applyWithDelegatedEvidence(
                port, false, APP_LOCKED, NOW - 500L, () -> true, () -> false, clock);
        assertEquals("proximity command revoked before write", result.reason);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.FAILED, result.outcome);
        assertEquals(0, port.writes);
        DiLink5DoorLockControl.Result nullCheck = DiLink5DoorLockControl.applyWithDelegatedEvidence(
                port, false, APP_LOCKED, NOW - 500L, () -> true, null, clock);
        assertEquals("proximity command revoked before write", nullCheck.reason);
        // The request expiring during the daemon check also revokes the write.
        AtomicBoolean current = new AtomicBoolean(true);
        DiLink5DoorLockControl.Result expired = DiLink5DoorLockControl.applyWithDelegatedEvidence(
                port, false, APP_LOCKED, NOW - 500L, current::get,
                () -> { current.set(false); return true; }, clock);
        assertEquals("proximity command revoked before write", expired.reason);
        assertEquals(0, port.writes);
    }

    @Test
    public void requestAdapterAndEvidenceChecksComeFirst() {
        Port port = new Port();
        FakeClock clock = new FakeClock();
        DiLink5DoorLockControl.Result notCurrent = DiLink5DoorLockControl.applyWithDelegatedEvidence(
                port, false, APP_LOCKED, NOW - 500L, () -> false, () -> true, clock);
        assertEquals("request expired or mode changed", notCurrent.reason);
        assertEquals(0, port.reads);
        port.ready = false;
        DiLink5DoorLockControl.Result notReady = delegated(port, clock, false, APP_LOCKED, 500L);
        assertEquals("cabin adapter unavailable", notReady.reason);
        assertEquals(0, port.reads);
        assertEquals(0, port.writes);
        // Evidence without a valid state can never authorise a write.
        Port invalid = new Port();
        DiLink5DoorLockControl.Result noState = delegated(invalid, clock, false, 0, 500L);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.FAILED, noState.outcome);
        assertEquals(0, invalid.writes);
        // A throwing typed getter fails closed.
        Port throwing = new Port();
        throwing.readFailure = new IllegalStateException("binder");
        DiLink5DoorLockControl.Result failed = delegated(throwing, clock, false, APP_LOCKED, 500L);
        assertEquals("typed door-lock request failed (IllegalStateException)", failed.reason);
        assertEquals(0, throwing.writes);
        // An unverified device layout fails closed like setLocked.
        DiLink5DoorLockControl.Result layout = DiLink5DoorLockControl.applyWithDelegatedEvidence(
                new Object(), false, APP_LOCKED, NOW - 500L, () -> true, () -> true);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.FAILED, layout.outcome);
        assertEquals("typed door-lock rail unavailable (IllegalArgumentException)", layout.reason);
    }

    @Test
    public void nativeOfMapsTheAppDomainToTheCabinDomain() {
        assertEquals(DiLink5DoorLockControl.LOCK, DiLink5DoorLockControl.nativeOf(APP_LOCKED));
        assertEquals(DiLink5DoorLockControl.UNLOCK, DiLink5DoorLockControl.nativeOf(APP_UNLOCKED));
        for (int other : new int[]{0, -1, 3, Integer.MIN_VALUE}) {
            assertEquals(0, DiLink5DoorLockControl.nativeOf(other));
        }
    }

    @Test
    public void writtenUnconfirmedIsNeverReportedAsConfirmed() {
        for (boolean locked : new boolean[]{true, false}) {
            for (int gate : new int[]{APP_UNLOCKED, APP_LOCKED}) {
                Port port = new Port();
                DiLink5DoorLockControl.Result result = delegated(port, new FakeClock(), locked, gate, 100L);
                assertEquals(DiLink5DoorLockControl.Result.Outcome.WRITTEN_UNCONFIRMED, result.outcome);
                assertFalse(result.confirmed);
            }
        }
    }

    @Test
    public void delegatedPathIsSelectedOnlyForCarsvcGateRail() {
        assertEquals(DiLink5DoorLockControl.Mode.DELEGATED_CARSVC,
                DiLink5DoorLockControl.selectDiLink5LockMode(DiLink5LockRail.CARSVC, true));
        assertEquals(DiLink5DoorLockControl.Mode.DELEGATED_CARSVC,
                DiLink5DoorLockControl.selectDiLink5LockMode(DiLink5LockRail.CARSVC, false));
        for (DiLink5LockRail rail : new DiLink5LockRail[]{null, DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.OTA}) {
            for (boolean device : new boolean[]{true, false}) {
                assertTrue(String.valueOf(rail), DiLink5DoorLockControl.selectDiLink5LockMode(rail, device)
                        != DiLink5DoorLockControl.Mode.DELEGATED_CARSVC);
            }
        }
        assertEquals(DiLink5DoorLockControl.Mode.TYPED, DiLink5DoorLockControl.selectDiLink5LockMode(null, true));
    }
}
