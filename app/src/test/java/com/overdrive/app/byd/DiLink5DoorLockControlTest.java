package com.overdrive.app.byd;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class DiLink5DoorLockControlTest {
    @Test public void delayedApproachRevocationBlocksTheActualNativeWrite() {
        Port port = new Port();
        AtomicBoolean approachCurrent = new AtomicBoolean(true);
        port.afterRead = () -> approachCurrent.set(false);
        assertFalse(DiLink5DoorLockControl.apply(
                port, false, () -> true, approachCurrent::get, new FakeClock()).confirmed);
        assertEquals(0, port.writes);
    }

    @Test public void masterOffDuringBoundaryVerificationPreventsTheNativeWrite() {
        Port port = new Port();
        AtomicBoolean masterEnabled = new AtomicBoolean(true);
        assertFalse(DiLink5DoorLockControl.apply(
                port, false, masterEnabled::get,
                () -> { masterEnabled.set(false); return true; }, new FakeClock()).confirmed);
        assertEquals(0, port.writes);
    }

    @Test public void manualTypedLockRequestsKeepTheirExistingWritePath() {
        Port port = new Port();
        assertTrue(DiLink5DoorLockControl.apply(
                port, false, () -> true, new FakeClock()).confirmed);
        assertEquals(1, port.writes);
    }
    private static final class FakeClock implements DiLink5DoorLockControl.Clock {
        long now;
        Runnable afterSleep;
        public long elapsedMs() { return now; }
        public void sleep(long millis) {
            now += millis;
            if (afterSleep != null) afterSleep.run();
        }
    }

    private static final class Port implements DiLink5DoorLockControl.NativePort {
        boolean ready = true;
        int state = DiLink5DoorLockControl.LOCK;
        int rc;
        int writes;
        int writtenState;
        boolean updateOnWrite = true;
        Runnable afterRead;
        Runnable afterWrite;
        Runnable afterReady;
        int readinessChecks;
        final Queue<Integer> reads = new ArrayDeque<>();
        public boolean ready() {
            readinessChecks++;
            if (afterReady != null) afterReady.run();
            return ready;
        }
        public int readDriverLock() {
            int value = reads.isEmpty() ? state : reads.remove();
            if (afterRead != null) afterRead.run();
            return value;
        }
        public int writeAllDoors(int value) {
            writes++;
            writtenState = value;
            if (updateOnWrite) state = value;
            if (afterWrite != null) afterWrite.run();
            return rc;
        }
    }

    @Test public void unlockUsesTypedSdkDomainAndConfirmsReadback() {
        Port port = new Port();
        assertTrue(DiLink5DoorLockControl.apply(
                port, false, () -> true, new FakeClock()).confirmed);
        assertEquals(1, port.writes);
        assertEquals(2, port.writtenState);
    }

    @Test public void lockUsesTypedSdkDomainAndConfirmsReadback() {
        Port port = new Port();
        port.state = DiLink5DoorLockControl.UNLOCK;
        assertTrue(DiLink5DoorLockControl.apply(
                port, true, () -> true, new FakeClock()).confirmed);
        assertEquals(1, port.writes);
        assertEquals(1, port.writtenState);
    }

    @Test public void disconnectedZeroReturningSdkCannotReportSuccessOrWrite() {
        Port port = new Port();
        port.ready = false;
        assertFalse(DiLink5DoorLockControl.apply(
                port, false, () -> true, new FakeClock()).confirmed);
        assertEquals(0, port.writes);
    }

    @Test public void zeroWithoutPhysicalChangeTimesOutAndNeverRepeatsWrite() {
        Port port = new Port();
        port.updateOnWrite = false;
        FakeClock clock = new FakeClock();
        DiLink5DoorLockControl.Result result = DiLink5DoorLockControl.apply(
                port, false, () -> true, clock);
        assertFalse(result.confirmed);
        assertTrue(result.reason.contains("timed out"));
        assertEquals(2_000L, clock.now);
        assertEquals(1, port.writes);
    }

    @Test public void pendingReadbackCanConfirmWithinBoundedBudget() {
        Port port = new Port();
        port.updateOnWrite = false;
        port.reads.add(1);
        port.reads.add(1);
        port.reads.add(1);
        port.reads.add(2);
        port.reads.add(2);
        FakeClock clock = new FakeClock();
        assertTrue(DiLink5DoorLockControl.apply(
                port, false, () -> true, clock).confirmed);
        assertEquals(300L, clock.now);
        assertEquals(1, port.writes);
    }

    @Test public void refusedReturnCannotBecomeSuccessEvenIfReadbackChanged() {
        Port port = new Port();
        port.rc = -10;
        assertFalse(DiLink5DoorLockControl.apply(
                port, false, () -> true, new FakeClock()).confirmed);
        assertEquals(1, port.writes);
    }

    @Test public void singleTargetBlipDoesNotConfirmPhysicalChange() {
        Port port = new Port();
        port.updateOnWrite = false;
        port.reads.add(1);
        port.reads.add(2);
        port.reads.add(1);
        FakeClock clock = new FakeClock();
        assertFalse(DiLink5DoorLockControl.apply(
                port, false, () -> true, clock).confirmed);
        assertEquals(2_000L, clock.now);
        assertEquals(1, port.writes);
    }

    @Test public void unknownInitialReadbackBlocksWrite() {
        Port port = new Port();
        port.state = 0;
        assertFalse(DiLink5DoorLockControl.apply(
                port, false, () -> true, new FakeClock()).confirmed);
        assertEquals(0, port.writes);
    }

    @Test public void scopeInvalidatedDuringReadBlocksWrite() {
        Port port = new Port();
        AtomicBoolean current = new AtomicBoolean(true);
        port.afterRead = () -> current.set(false);
        assertFalse(DiLink5DoorLockControl.apply(
                port, false, current::get, new FakeClock()).confirmed);
        assertEquals(0, port.writes);
    }

    @Test public void adapterLostDuringReadBlocksWrite() {
        Port port = new Port();
        port.afterRead = () -> port.ready = false;
        assertFalse(DiLink5DoorLockControl.apply(
                port, false, () -> true, new FakeClock()).confirmed);
        assertEquals(0, port.writes);
    }

    @Test public void deadlineWhileReadinessBinderReturnsBlocksWrite() {
        Port port = new Port();
        AtomicBoolean current = new AtomicBoolean(true);
        port.afterReady = () -> {
            if (port.readinessChecks == 2) current.set(false);
        };
        assertFalse(DiLink5DoorLockControl.apply(
                port, false, current::get, new FakeClock()).confirmed);
        assertEquals(0, port.writes);
    }

    @Test public void adapterLostAfterAcceptedWriteCannotReportSuccess() {
        Port port = new Port();
        port.afterWrite = () -> port.ready = false;
        assertFalse(DiLink5DoorLockControl.apply(
                port, false, () -> true, new FakeClock()).confirmed);
        assertEquals(1, port.writes);
    }

    @Test public void deadlineDuringFinalReadinessCannotReportSuccess() {
        Port port = new Port();
        AtomicBoolean current = new AtomicBoolean(true);
        port.afterReady = () -> {
            if (port.readinessChecks == 5) current.set(false);
        };
        assertFalse(DiLink5DoorLockControl.apply(
                port, false, current::get, new FakeClock()).confirmed);
        assertEquals(1, port.writes);
    }

    @Test public void deadlineDuringConfirmationStopsWithoutSecondCommand() {
        Port port = new Port();
        port.updateOnWrite = false;
        AtomicBoolean current = new AtomicBoolean(true);
        FakeClock clock = new FakeClock();
        clock.afterSleep = () -> current.set(false);
        assertFalse(DiLink5DoorLockControl.apply(
                port, false, current::get, clock).confirmed);
        assertEquals(100L, clock.now);
        assertEquals(1, port.writes);
    }

    @Test public void alreadyConfirmedDriverStateDoesNotIssueDuplicateCommand() {
        Port port = new Port();
        assertTrue(DiLink5DoorLockControl.apply(
                port, true, () -> true, new FakeClock()).confirmed);
        assertEquals(0, port.writes);
    }

    @Test public void unknownDeviceLayoutFailsClosed() {
        assertFalse(DiLink5DoorLockControl.setLocked(
                new Object(), false, () -> true).confirmed);
    }
}
