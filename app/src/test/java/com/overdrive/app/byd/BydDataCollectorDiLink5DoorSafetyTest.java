package com.overdrive.app.byd;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BydDataCollectorDiLink5DoorSafetyTest {
    private static final long READ_AT = 100_000L;
    private static final int UNKNOWN = BydVehicleData.UNAVAILABLE;

    @Test
    public void unrelatedTelemetryCannotRenewTheLockReadLease() {
        BydDataCollector.DiLink5LockSafetyCache lock =
                new BydDataCollector.DiLink5LockSafetyCache();
        lock.observe(BydDataCollector.DOOR_STATE_LOCK, READ_AT);
        assertEquals(BydDataCollector.DOOR_STATE_LOCK, lock.current(READ_AT + 15_000L));
        assertEquals(BydDataCollector.DOOR_STATE_INVALID, lock.current(READ_AT + 15_001L));
        // Reading the cache repeatedly never restamps the old SDK observation.
        assertEquals(BydDataCollector.DOOR_STATE_INVALID, lock.current(READ_AT + 20_000L));
    }

    @Test
    public void failedLockReadRevokesKnownStateAndRejectsLateOldRead() {
        BydDataCollector.DiLink5LockSafetyCache lock =
                new BydDataCollector.DiLink5LockSafetyCache();
        lock.observe(BydDataCollector.DOOR_STATE_LOCK, READ_AT);
        lock.observe(BydDataCollector.DOOR_STATE_INVALID, READ_AT + 100L);
        lock.observe(BydDataCollector.DOOR_STATE_UNLOCK, READ_AT + 50L);
        assertEquals(BydDataCollector.DOOR_STATE_INVALID, lock.current(READ_AT + 101L));
        lock.observe(BydDataCollector.DOOR_STATE_UNLOCK, READ_AT + 200L);
        assertEquals(BydDataCollector.DOOR_STATE_UNLOCK, lock.current(READ_AT + 201L));
    }

    @Test
    public void unknownWinsWhenTwoLockReadsShareTheClockMillisecond() {
        BydDataCollector.DiLink5LockSafetyCache lock =
                new BydDataCollector.DiLink5LockSafetyCache();
        lock.observe(BydDataCollector.DOOR_STATE_LOCK, READ_AT);
        lock.observe(BydDataCollector.DOOR_STATE_INVALID, READ_AT);
        lock.observe(BydDataCollector.DOOR_STATE_LOCK, READ_AT);
        assertEquals(BydDataCollector.DOOR_STATE_INVALID, lock.current(READ_AT));
    }

    @Test
    public void futureAndClearedLockObservationsAreUnknown() {
        BydDataCollector.DiLink5LockSafetyCache lock =
                new BydDataCollector.DiLink5LockSafetyCache();
        lock.observe(BydDataCollector.DOOR_STATE_LOCK, READ_AT);
        assertEquals(BydDataCollector.DOOR_STATE_INVALID, lock.current(READ_AT - 1L));
        lock.clear();
        assertEquals(BydDataCollector.DOOR_STATE_INVALID, lock.current(READ_AT));
    }

    @Test
    public void typedReadRetainsItsNativeTimeAcrossLaterTelemetryPublication() {
        LockReadClock clock = new LockReadClock();
        LockReadPort port = new LockReadPort();
        port.afterRead = () -> clock.now += 250L;
        port.afterReady = () -> {
            if (port.readinessChecks == 2) clock.now += 4_000L;
        };
        DiLink5DoorLockControl.ReadResult reading =
                DiLink5DoorLockControl.readDriverLock(port, () -> true, clock);
        BydDataCollector.DiLink5LockSafetyCache lock =
                new BydDataCollector.DiLink5LockSafetyCache();
        lock.observe(reading.state, reading.observedAtElapsedMs);
        assertEquals(READ_AT + 250L, reading.observedAtElapsedMs);
        assertEquals(BydDataCollector.DOOR_STATE_LOCK, lock.current(clock.now));
        assertEquals(BydDataCollector.DOOR_STATE_LOCK, lock.current(READ_AT + 15_250L));
        assertEquals(BydDataCollector.DOOR_STATE_INVALID, lock.current(READ_AT + 15_251L));
    }

    @Test
    public void failedTypedReadImmediatelyRevokesThePreviousLockedLease() {
        LockReadClock clock = new LockReadClock();
        LockReadPort port = new LockReadPort();
        BydDataCollector.DiLink5LockSafetyCache lock =
                new BydDataCollector.DiLink5LockSafetyCache();
        DiLink5DoorLockControl.ReadResult locked =
                DiLink5DoorLockControl.readDriverLock(port, () -> true, clock);
        lock.observe(locked.state, locked.observedAtElapsedMs);
        clock.now += 1_000L;
        port.ready = false;
        DiLink5DoorLockControl.ReadResult failed =
                DiLink5DoorLockControl.readDriverLock(port, () -> true, clock);
        lock.observe(failed.state, failed.observedAtElapsedMs);
        assertEquals(BydDataCollector.DOOR_STATE_INVALID, lock.current(clock.now));
        lock.observe(locked.state, locked.observedAtElapsedMs);
        assertEquals(BydDataCollector.DOOR_STATE_INVALID, lock.current(clock.now));
    }

    private static final class LockReadClock implements DiLink5DoorLockControl.Clock {
        long now = READ_AT;
        public long elapsedMs() { return now; }
        public void sleep(long millis) { now += millis; }
    }

    private static final class LockReadPort implements DiLink5DoorLockControl.NativePort {
        boolean ready = true;
        int readinessChecks;
        Runnable afterReady;
        Runnable afterRead;
        public boolean ready() {
            readinessChecks++;
            if (afterReady != null) afterReady.run();
            return ready;
        }
        public int readDriverLock() {
            if (afterRead != null) afterRead.run();
            return DiLink5DoorLockControl.LOCK;
        }
        public int writeAllDoors(int value) {
            throw new AssertionError("telemetry must never write the native rail");
        }
    }

    @Test
    public void closedDoorBecomesUnknownImmediatelyWhenItsGetterFails() {
        BydDataCollector.DiLink5DoorSafetyCache doors =
                new BydDataCollector.DiLink5DoorSafetyCache();
        doors.observe(1, 0, READ_AT);
        doors.observe(2, 0, READ_AT);
        doors.observe(1, 255, READ_AT + 100L);
        int[] states = doors.snapshot(READ_AT + 101L).current(READ_AT + 101L);
        assertEquals(UNKNOWN, states[0]);
        assertEquals(0, states[1]);
        // A late return from a getter started before the failure cannot restore Closed.
        doors.observe(1, 0, READ_AT + 50L);
        assertEquals(UNKNOWN, doors.snapshot(READ_AT + 102L).states[0]);
    }

    @Test
    public void unknownDoorWinsWhenReadsShareTheClockMillisecond() {
        BydDataCollector.DiLink5DoorSafetyCache doors =
                new BydDataCollector.DiLink5DoorSafetyCache();
        doors.observe(1, 0, READ_AT);
        doors.observe(1, UNKNOWN, READ_AT);
        doors.observe(1, 0, READ_AT);
        assertEquals(UNKNOWN, doors.snapshot(READ_AT).states[0]);
    }

    @Test
    public void perAreaLeaseExpiresFromActualReadTimeAcrossTransport() {
        BydDataCollector.DiLink5DoorSafetyCache producer =
                new BydDataCollector.DiLink5DoorSafetyCache();
        producer.observe(1, 0, READ_AT);
        producer.observe(2, 0, READ_AT + 4_000L);
        BydDataCollector.DiLink5DoorSafetySnapshot outgoing =
                producer.snapshot(READ_AT + 5_000L);
        BydDataCollector.DiLink5DoorSafetySnapshot consumer =
                BydDataCollector.DiLink5DoorSafetySnapshot.fromBridge(
                        outgoing.states, outgoing.observedAt,
                        READ_AT + 5_000L, READ_AT + 7_000L);
        assertEquals(READ_AT, consumer.observedAt[0]);
        assertEquals(READ_AT + 4_000L, consumer.observedAt[1]);
        assertEquals(0, consumer.current(READ_AT + 15_000L)[0]);
        assertEquals(UNKNOWN, consumer.current(READ_AT + 15_001L)[0]);
        assertEquals(0, consumer.current(READ_AT + 15_001L)[1]);
    }

    @Test
    public void repeatedTelemetryCannotRestampOldClosedDoorsAfterSuspend() {
        BydDataCollector.DiLink5DoorSafetyCache producer =
                new BydDataCollector.DiLink5DoorSafetyCache();
        producer.observe(1, 0, READ_AT);
        BydDataCollector.DiLink5DoorSafetySnapshot later =
                producer.snapshot(READ_AT + 60_000L);
        BydDataCollector.DiLink5DoorSafetySnapshot consumer =
                BydDataCollector.DiLink5DoorSafetySnapshot.fromBridge(
                        later.states, later.observedAt,
                        READ_AT + 60_000L, READ_AT + 60_001L);
        assertEquals(READ_AT, consumer.observedAt[0]);
        assertEquals(UNKNOWN, consumer.current(READ_AT + 60_001L)[0]);
    }

    @Test
    public void failedDoorReadSurvivesLaterUnrelatedSnapshotRoundtrip() {
        BydDataCollector.DiLink5DoorSafetyCache producer =
                new BydDataCollector.DiLink5DoorSafetyCache();
        producer.observe(5, 0, READ_AT);
        producer.observe(5, UNKNOWN, READ_AT + 100L);
        BydDataCollector.DiLink5DoorSafetySnapshot outgoing =
                producer.snapshot(READ_AT + 200L);
        BydDataCollector.DiLink5DoorSafetySnapshot consumer =
                BydDataCollector.DiLink5DoorSafetySnapshot.fromBridge(
                        outgoing.states, outgoing.observedAt,
                        READ_AT + 200L, READ_AT + 300L);
        assertEquals(UNKNOWN, consumer.current(READ_AT + 300L)[4]);
        producer.observe(5, 0, READ_AT + 400L);
        assertEquals(0, producer.snapshot(READ_AT + 401L).states[4]);
    }

    @Test
    public void missingMalformedOrFutureReadTimesNeverBecomeFreshClosedDoors() {
        int[] closed = new int[]{0, 0, 0, 0, 0, 0, 0};
        long[] times = new long[]{READ_AT, READ_AT, READ_AT, READ_AT,
                READ_AT, READ_AT, READ_AT};
        assertUnknown(BydDataCollector.DiLink5DoorSafetySnapshot.fromBridge(
                closed, null, READ_AT, READ_AT));
        assertUnknown(BydDataCollector.DiLink5DoorSafetySnapshot.fromBridge(
                new int[]{0}, times, READ_AT, READ_AT));
        assertUnknown(BydDataCollector.DiLink5DoorSafetySnapshot.fromBridge(
                closed, new long[]{READ_AT}, READ_AT, READ_AT));
        assertUnknown(BydDataCollector.DiLink5DoorSafetySnapshot.fromBridge(
                closed, times, READ_AT - 1L, READ_AT));
        assertUnknown(BydDataCollector.DiLink5DoorSafetySnapshot.fromBridge(
                closed, times, READ_AT, READ_AT - 1L));
    }

    @Test
    public void snapshotOwnsItsArraysAndClearRetiresEveryDoor() {
        BydDataCollector.DiLink5DoorSafetyCache producer =
                new BydDataCollector.DiLink5DoorSafetyCache();
        producer.observe(1, 0, READ_AT);
        BydDataCollector.DiLink5DoorSafetySnapshot snapshot = producer.snapshot(READ_AT);
        snapshot.states[0] = 1;
        snapshot.observedAt[0] = READ_AT + 1L;
        assertEquals(0, producer.snapshot(READ_AT).states[0]);
        assertEquals(READ_AT, producer.snapshot(READ_AT).observedAt[0]);
        producer.clear();
        assertUnknown(producer.snapshot(READ_AT + 2L));
    }

    private static void assertUnknown(BydDataCollector.DiLink5DoorSafetySnapshot snapshot) {
        assertArrayEquals(new int[]{UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN,
                UNKNOWN, UNKNOWN, UNKNOWN}, snapshot.states);
    }
}
