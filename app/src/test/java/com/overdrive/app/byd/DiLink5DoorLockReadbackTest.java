package com.overdrive.app.byd;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class DiLink5DoorLockReadbackTest {
    private static final class FakeClock implements DiLink5DoorLockControl.Clock {
        long now = 100_000L;
        public long elapsedMs() { return now; }
        public void sleep(long millis) { now += millis; }
    }

    private static final class Port implements DiLink5DoorLockControl.NativePort {
        boolean ready = true;
        int state = DiLink5DoorLockControl.LOCK;
        int readinessChecks;
        int reads;
        int writes;
        Runnable afterReady;
        Runnable afterRead;
        Exception failure;

        public boolean ready() {
            readinessChecks++;
            if (afterReady != null) afterReady.run();
            return ready;
        }

        public int readDriverLock() throws Exception {
            reads++;
            if (failure != null) throw failure;
            int value = state;
            if (afterRead != null) afterRead.run();
            return value;
        }

        public int writeAllDoors(int value) {
            writes++;
            return 0;
        }
    }

    @Test public void lockedCabinReadingMapsToTheAppLockedDomain() {
        Port port = new Port();
        FakeClock clock = new FakeClock();
        DiLink5DoorLockControl.ReadResult result =
                DiLink5DoorLockControl.readDriverLock(port, () -> true, clock);
        assertEquals(1, result.nativeState);
        assertEquals(2, result.state);
        assertEquals(clock.now, result.observedAtElapsedMs);
        assertEquals(1, port.reads);
        assertEquals(2, port.readinessChecks);
        assertEquals(0, port.writes);
    }

    @Test public void unlockedCabinReadingMapsToTheAppUnlockedDomain() {
        Port port = new Port();
        port.state = DiLink5DoorLockControl.UNLOCK;
        DiLink5DoorLockControl.ReadResult result =
                DiLink5DoorLockControl.readDriverLock(port, () -> true, new FakeClock());
        assertEquals(2, result.nativeState);
        assertEquals(1, result.state);
        assertEquals(0, port.writes);
    }

    @Test public void invalidCabinValuesNeverBecomeAValidNormalizedState() {
        for (int raw : new int[]{0, -1, -10011, 3, 255, 65534, 65535, Integer.MIN_VALUE}) {
            Port port = new Port();
            port.state = raw;
            DiLink5DoorLockControl.ReadResult result =
                    DiLink5DoorLockControl.readDriverLock(port, () -> true, new FakeClock());
            assertEquals(raw, result.nativeState);
            assertEquals(0, result.state);
            assertTrue(result.reason.contains("unavailable"));
            assertEquals(0, port.writes);
        }
    }

    @Test public void unavailableAdapterDoesNotReadOrWriteTheSdk() {
        Port port = new Port();
        port.ready = false;
        DiLink5DoorLockControl.ReadResult result =
                DiLink5DoorLockControl.readDriverLock(port, () -> true, new FakeClock());
        assertEquals(0, result.state);
        assertEquals(0, port.reads);
        assertEquals(0, port.writes);
        assertTrue(result.reason.contains("adapter unavailable"));
    }

    @Test public void sourceRevocationDuringReadinessPreventsTheGetter() {
        Port port = new Port();
        AtomicBoolean current = new AtomicBoolean(true);
        port.afterReady = () -> current.set(false);
        assertEquals(0, DiLink5DoorLockControl.readDriverLock(
                port, current::get, new FakeClock()).state);
        assertEquals(0, port.reads);
    }

    @Test public void sourceRevocationDuringTheGetterRetiresItsValue() {
        Port port = new Port();
        AtomicBoolean current = new AtomicBoolean(true);
        port.afterRead = () -> current.set(false);
        DiLink5DoorLockControl.ReadResult result =
                DiLink5DoorLockControl.readDriverLock(port, current::get, new FakeClock());
        assertEquals(0, result.state);
        assertEquals(1, port.reads);
        assertTrue(result.reason.contains("source changed"));
    }

    @Test public void adapterLostDuringTheGetterRetiresItsValue() {
        Port port = new Port();
        port.afterRead = () -> port.ready = false;
        DiLink5DoorLockControl.ReadResult result =
                DiLink5DoorLockControl.readDriverLock(port, () -> true, new FakeClock());
        assertEquals(0, result.state);
        assertTrue(result.reason.contains("adapter lost"));
    }

    @Test public void slowPostReadReadinessCannotRestampTheNativeObservation() {
        Port port = new Port();
        FakeClock clock = new FakeClock();
        port.afterRead = () -> clock.now += 400L;
        port.afterReady = () -> {
            if (port.readinessChecks == 2) clock.now += 5_000L;
        };
        DiLink5DoorLockControl.ReadResult result =
                DiLink5DoorLockControl.readDriverLock(port, () -> true, clock);
        assertEquals(100_400L, result.observedAtElapsedMs);
        assertEquals(105_400L, clock.now);
        assertEquals(2, result.state);
    }

    @Test public void failedNativeGetterPublishesUnknownWithTheFailureTime() {
        Port port = new Port();
        port.failure = new IllegalStateException("native read unavailable");
        FakeClock clock = new FakeClock();
        DiLink5DoorLockControl.ReadResult result =
                DiLink5DoorLockControl.readDriverLock(port, () -> true, clock);
        assertEquals(0, result.state);
        assertEquals(clock.now, result.observedAtElapsedMs);
        assertTrue(result.reason.contains("IllegalStateException"));
        assertEquals(0, port.writes);
    }

    @Test public void anExpiredSourceNeverTouchesThePort() {
        Port port = new Port();
        assertEquals(0, DiLink5DoorLockControl.readDriverLock(
                port, () -> false, new FakeClock()).state);
        assertEquals(0, port.readinessChecks);
        assertEquals(0, port.reads);
    }

    @Test public void unknownSdkLayoutFailsClosedWithoutATypedFallback() {
        DiLink5DoorLockControl.ReadResult result = DiLink5DoorLockControl.readDriverLock(
                new Object(), () -> true, new FakeClock());
        assertEquals(0, result.state);
        assertTrue(result.reason.contains("rail unavailable"));
    }

    @Test public void interruptedGetterRetiresTheReadingAndPreservesInterruption() {
        Port port = new Port();
        port.failure = new InterruptedException();
        try {
            assertEquals(0, DiLink5DoorLockControl.readDriverLock(
                    port, () -> true, new FakeClock()).state);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
