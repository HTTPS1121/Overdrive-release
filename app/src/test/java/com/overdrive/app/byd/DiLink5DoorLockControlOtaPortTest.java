package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.LongToIntFunction;

import org.junit.Test;

/**
 * 1.6b case (B): typed cabin write, OTA agreeing-pair readback (250 ms apart), OTA → native
 * inversion, hybrid readiness and a 4 s confirmation budget — selected ONLY for gate rail {@code ota}.
 * The OTA getter is scripted in its own domain (LF_DOOR_UNLOCK = 1, LF_DOOR_LOCK = 2).
 */
public class DiLink5DoorLockControlOtaPortTest {
    private static final int OTA_UNLOCKED = 1;
    private static final int OTA_LOCKED = 2;

    private static final class FakeClock implements DiLink5DoorLockControl.Clock {
        long now;
        public long elapsedMs() { return now; }
        public void sleep(long millis) { now += millis; }
    }

    /** Typed cabin writer. Its own getter is INVALID on this car and must never be consulted here. */
    private static final class TypedWriter implements DiLink5DoorLockControl.NativePort {
        boolean ready = true;
        int rc;
        int writes;
        int writtenState;
        int typedReads;
        Runnable afterWrite;
        public boolean ready() { return ready; }
        public int readDriverLock() { typedReads++; return 0; }
        public int writeAllDoors(int state) {
            writes++;
            writtenState = state;
            if (afterWrite != null) afterWrite.run();
            return rc;
        }
    }

    /** OTA getter: queued raw values first, then the value of {@code steady} at the read time. */
    private static final class ScriptedOta implements DiLink5DoorLockControl.OtaLfReader {
        final FakeClock clock;
        final Queue<Integer> script = new ArrayDeque<>();
        final List<Long> readTimes = new ArrayList<>();
        LongToIntFunction steady;
        boolean ready = true;

        ScriptedOta(FakeClock clock, int steadyValue, int... queued) {
            this.clock = clock;
            this.steady = at -> steadyValue;
            for (int value : queued) script.add(value);
        }

        public boolean ready() { return ready; }

        public int readLf() {
            readTimes.add(clock.now);
            return script.isEmpty() ? steady.applyAsInt(clock.now) : script.remove();
        }
    }

    private static final class Run {
        final FakeClock clock = new FakeClock();
        final TypedWriter typed = new TypedWriter();
        final ScriptedOta ota;
        long writeAt = -1L;
        int readsAtWrite = -1;

        Run(int steadyValue, int... queued) {
            ota = new ScriptedOta(clock, steadyValue, queued);
            typed.afterWrite = () -> {
                writeAt = clock.now;
                readsAtWrite = ota.readTimes.size();
            };
        }

        DiLink5DoorLockControl.Result apply(boolean locked) {
            return DiLink5DoorLockControl.apply(
                    new DiLink5DoorLockControl.OtaReadPort(typed, ota, clock), locked, () -> true, () -> true, clock);
        }

        int readsAfterWrite() { return ota.readTimes.size() - readsAtWrite; }
    }

    @Test
    public void lockOnALockedCarWritesNothingAndConfirms() {
        Run run = new Run(OTA_LOCKED);
        DiLink5DoorLockControl.Result result = run.apply(true);
        assertTrue(result.confirmed);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.CONFIRMED, result.outcome);
        assertEquals("driver door already confirmed", result.reason);
        assertEquals(0, run.typed.writes);
        assertEquals(0, run.typed.typedReads);
    }

    @Test
    public void lockOnAnUnlockedCarWritesTypedLockAndConfirmsOnTwoAgreeingLockedPairs() {
        Run run = new Run(OTA_LOCKED, OTA_UNLOCKED, OTA_UNLOCKED);
        DiLink5DoorLockControl.Result result = run.apply(true);
        assertTrue(result.reason, result.confirmed);
        assertEquals("driver-door readback confirmed", result.reason);
        assertEquals(1, run.typed.writes);
        assertEquals(DiLink5DoorLockControl.LOCK, run.typed.writtenState);
        // Two agreeing pairs = four raw reads spanning at least 600 ms after the write.
        assertEquals(4, run.readsAfterWrite());
        assertTrue(run.clock.now - run.writeAt >= 600L);
        assertEquals(600L, run.clock.now - run.writeAt);
        assertEquals(0, run.typed.typedReads);
    }

    @Test
    public void unlockOnAnUnlockedCarWritesNothingAndConfirms() {
        Run run = new Run(OTA_UNLOCKED);
        DiLink5DoorLockControl.Result result = run.apply(false);
        assertTrue(result.confirmed);
        assertEquals("driver door already confirmed", result.reason);
        assertEquals(0, run.typed.writes);
    }

    @Test
    public void unlockOnALockedCarWritesTypedUnlockAndConfirmsOnTwoAgreeingUnlockedPairs() {
        Run run = new Run(OTA_UNLOCKED, OTA_LOCKED, OTA_LOCKED);
        DiLink5DoorLockControl.Result result = run.apply(false);
        assertTrue(result.reason, result.confirmed);
        assertEquals(1, run.typed.writes);
        assertEquals(DiLink5DoorLockControl.UNLOCK, run.typed.writtenState);
        assertEquals(4, run.readsAfterWrite());
        assertTrue(run.clock.now - run.writeAt >= 600L);
    }

    @Test
    public void theInversionIsLoadBearing() {
        // A port that passes the OTA value through WITHOUT inverting it: OTA 2 (locked) reads as
        // native 2 = UNLOCK, so "unlock on a locked car" is wrongly CONFIRMED without any write.
        FakeClock clock = new FakeClock();
        TypedWriter typed = new TypedWriter();
        ScriptedOta ota = new ScriptedOta(clock, OTA_LOCKED);
        DiLink5DoorLockControl.NativePort nonInverting = new DiLink5DoorLockControl.NativePort() {
            public boolean ready() throws Exception { return typed.ready() && ota.ready(); }
            public int readDriverLock() throws Exception {
                int first = ota.readLf();
                clock.sleep(DiLink5DoorLockControl.OtaReadPort.OTA_AGREEMENT_SPACING_MS);
                int second = ota.readLf();
                return first == second ? first : 0;
            }
            public int writeAllDoors(int state) { return typed.writeAllDoors(state); }
        };
        DiLink5DoorLockControl.Result wrong = DiLink5DoorLockControl.apply(
                nonInverting, false, () -> true, () -> true, clock);
        assertTrue(wrong.confirmed);
        assertEquals(0, typed.writes);
        // The real port on the same (still locked) car writes the unlock and does NOT confirm it.
        Run run = new Run(OTA_LOCKED);
        DiLink5DoorLockControl.Result right = run.apply(false);
        assertFalse(right.confirmed);
        assertEquals("driver-door confirmation timed out", right.reason);
        assertEquals(1, run.typed.writes);
        assertEquals(DiLink5DoorLockControl.UNLOCK, run.typed.writtenState);
    }

    @Test
    public void inversionMapsOnlyTheTwoOtaStates() {
        assertEquals(DiLink5DoorLockControl.LOCK, DiLink5DoorLockControl.OtaReadPort.nativeOfOta(OTA_LOCKED));
        assertEquals(DiLink5DoorLockControl.UNLOCK, DiLink5DoorLockControl.OtaReadPort.nativeOfOta(OTA_UNLOCKED));
        for (int other : new int[]{0, -1, 3, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertEquals(0, DiLink5DoorLockControl.OtaReadPort.nativeOfOta(other));
        }
    }

    @Test
    public void aDisagreeingPreReadPairIsUnknownAndBlocksTheWrite() {
        for (int[] pair : new int[][]{{OTA_UNLOCKED, OTA_LOCKED}, {OTA_LOCKED, OTA_UNLOCKED}}) {
            Run run = new Run(OTA_LOCKED, pair);
            DiLink5DoorLockControl.Result result = run.apply(true);
            assertFalse(result.confirmed);
            assertEquals(DiLink5DoorLockControl.Result.Outcome.FAILED, result.outcome);
            assertEquals("driver-door lock readback unavailable raw=0", result.reason);
            assertEquals(0, run.typed.writes);
        }
    }

    @Test
    public void aBluffDuringConfirmationNeedsTwoMoreAgreeingPairs() {
        // Unlocked car, lock: pre-read [1,1], then [2,2, 1,2, 2,2, 2,2].
        Run run = new Run(OTA_LOCKED, OTA_UNLOCKED, OTA_UNLOCKED,
                OTA_LOCKED, OTA_LOCKED, OTA_UNLOCKED, OTA_LOCKED, OTA_LOCKED, OTA_LOCKED, OTA_LOCKED, OTA_LOCKED);
        DiLink5DoorLockControl.Result result = run.apply(true);
        assertTrue(result.reason, result.confirmed);
        assertEquals(1, run.typed.writes);
        assertEquals(8, run.readsAfterWrite());
        assertTrue(run.clock.now - run.writeAt >= 600L);
        assertEquals(1_300L, run.clock.now - run.writeAt);
    }

    @Test
    public void aSingleUnlockedSampleNeverConfirmsAnUnlockOnALockedCar() {
        // The door never unlocks (OTA keeps reading 2); one bluff 1 at any position cannot confirm.
        for (int position = 0; position < 24; position++) {
            int[] queued = new int[2 + position + 1];
            queued[0] = OTA_LOCKED;
            queued[1] = OTA_LOCKED;
            for (int i = 2; i < queued.length; i++) queued[i] = OTA_LOCKED;
            queued[queued.length - 1] = OTA_UNLOCKED;
            Run run = new Run(OTA_LOCKED, queued);
            DiLink5DoorLockControl.Result result = run.apply(false);
            assertFalse("bluff at " + position, result.confirmed);
            assertEquals("driver-door confirmation timed out", result.reason);
            assertEquals(1, run.typed.writes);
        }
    }

    @Test
    public void otaBudgetIsFourSecondsWhileTheTypedPortKeepsTwo() {
        Run run = new Run(OTA_LOCKED);
        // Unlocked until 2 500 ms after the write, then locked.
        run.ota.steady = at -> run.writeAt >= 0L && at >= run.writeAt + 2_500L ? OTA_LOCKED : OTA_UNLOCKED;
        DiLink5DoorLockControl.Result result = run.apply(true);
        assertTrue(result.reason, result.confirmed);
        assertTrue(run.clock.now - run.writeAt > 2_000L);
        assertTrue(run.clock.now - run.writeAt < 4_000L);
        assertEquals(4_000L, new DiLink5DoorLockControl.OtaReadPort(
                new TypedWriter(), new ScriptedOta(new FakeClock(), OTA_LOCKED), new FakeClock())
                .confirmationTimeoutMs());
        assertEquals(4_000L, DiLink5DoorLockControl.OtaReadPort.OTA_CONFIRMATION_TIMEOUT_MS);
        assertEquals(250L, DiLink5DoorLockControl.OtaReadPort.OTA_AGREEMENT_SPACING_MS);

        // A ReflectivePort-shaped fake (default budget) with the same 2 500 ms latency times out at 2 000 ms.
        assertEquals(2_000L, new TypedWriter().confirmationTimeoutMs());
        FakeClock clock = new FakeClock();
        long[] typedWriteAt = {-1L};
        DiLink5DoorLockControl.NativePort typedOnly = new DiLink5DoorLockControl.NativePort() {
            public boolean ready() { return true; }
            public int readDriverLock() {
                return typedWriteAt[0] >= 0L && clock.now >= typedWriteAt[0] + 2_500L
                        ? DiLink5DoorLockControl.LOCK : DiLink5DoorLockControl.UNLOCK;
            }
            public int writeAllDoors(int state) { typedWriteAt[0] = clock.now; return 0; }
        };
        DiLink5DoorLockControl.Result typedResult = DiLink5DoorLockControl.apply(
                typedOnly, true, () -> true, () -> true, clock);
        assertFalse(typedResult.confirmed);
        assertEquals("driver-door confirmation timed out", typedResult.reason);
        assertEquals(2_000L, clock.now - typedWriteAt[0]);
    }

    @Test
    public void outOfDomainOtaPreReadsFailWithoutWrite() {
        for (int value : new int[]{-1, 0, 3}) {
            Run run = new Run(value);
            DiLink5DoorLockControl.Result result = run.apply(true);
            assertFalse(result.confirmed);
            assertEquals("driver-door lock readback unavailable raw=0", result.reason);
            assertEquals(0, run.typed.writes);
        }
    }

    @Test
    public void hybridReadinessNeedsBothRails() throws Exception {
        Run typedDown = new Run(OTA_UNLOCKED);
        typedDown.typed.ready = false;
        assertFalse(new DiLink5DoorLockControl.OtaReadPort(typedDown.typed, typedDown.ota, typedDown.clock).ready());
        assertEquals("cabin adapter unavailable", typedDown.apply(true).reason);
        assertEquals(0, typedDown.typed.writes);

        Run otaDown = new Run(OTA_UNLOCKED);
        otaDown.ota.ready = false;
        assertFalse(new DiLink5DoorLockControl.OtaReadPort(otaDown.typed, otaDown.ota, otaDown.clock).ready());
        assertEquals("cabin adapter unavailable", otaDown.apply(true).reason);
        assertEquals(0, otaDown.typed.writes);
        assertTrue(otaDown.ota.readTimes.isEmpty());

        Run both = new Run(OTA_UNLOCKED);
        assertTrue(new DiLink5DoorLockControl.OtaReadPort(both.typed, both.ota, both.clock).ready());

        // The OTA side going down after the write cannot report success.
        Run lost = new Run(OTA_LOCKED, OTA_UNLOCKED, OTA_UNLOCKED);
        lost.typed.afterWrite = () -> lost.ota.ready = false;
        DiLink5DoorLockControl.Result result = lost.apply(true);
        assertFalse(result.confirmed);
        assertEquals("cabin adapter lost during confirmation", result.reason);
        assertEquals(1, lost.typed.writes);
    }

    @Test
    public void refusedTypedWriteIsNotConfirmedByTheOtaReadback() {
        Run run = new Run(OTA_LOCKED, OTA_UNLOCKED, OTA_UNLOCKED);
        run.typed.rc = -10;
        DiLink5DoorLockControl.Result result = run.apply(true);
        assertFalse(result.confirmed);
        assertEquals("setAllDoorsLock refused rc=-10", result.reason);
        assertEquals(0, run.readsAfterWrite());
    }

    /** Correctly shaped OTA-like device for the D4 seam (its own name is the expected name). */
    public static final class ShapedOta {
        public static final int LF_DOOR_LOCK = 2;
        public static final int LF_DOOR_UNLOCK = 1;
        public static final int LF_DOOR_LOCK_INVALID = 0;
        public int getLFDoorLockState() { return LF_DOOR_LOCK; }
    }

    /** Inverted constants: the domain check must reject it. */
    public static final class InvertedOta {
        public static final int LF_DOOR_LOCK = 1;
        public static final int LF_DOOR_UNLOCK = 2;
        public static final int LF_DOOR_LOCK_INVALID = 0;
        public int getLFDoorLockState() { return LF_DOOR_LOCK; }
    }

    public static final class LongGetterOta {
        public static final int LF_DOOR_LOCK = 2;
        public static final int LF_DOOR_UNLOCK = 1;
        public static final int LF_DOOR_LOCK_INVALID = 0;
        public long getLFDoorLockState() { return LF_DOOR_LOCK; }
    }

    public static final class MissingGetterOta {
        public static final int LF_DOOR_LOCK = 2;
        public static final int LF_DOOR_UNLOCK = 1;
        public static final int LF_DOOR_LOCK_INVALID = 0;
    }

    public static final class MissingInvalidOta {
        public static final int LF_DOOR_LOCK = 2;
        public static final int LF_DOOR_UNLOCK = 1;
        public int getLFDoorLockState() { return LF_DOOR_LOCK; }
    }

    @Test
    public void verifyOtaDeviceShapeAcceptsOnlyTheExactNameAndDomain() {
        DiLink5DoorLockControl.verifyOtaDeviceShape(ShapedOta.class, ShapedOta.class.getName());
        expectShapeRejected(ShapedOta.class, DiLink5DoorLockControl.ReflectiveOtaLfReader.OTA_DEVICE_CLASS);
        expectShapeRejected(ShapedOta.class, null);
        expectShapeRejected(null, ShapedOta.class.getName());
        expectShapeRejected(InvertedOta.class, InvertedOta.class.getName());
        expectShapeRejected(LongGetterOta.class, LongGetterOta.class.getName());
        expectShapeRejected(MissingGetterOta.class, MissingGetterOta.class.getName());
        expectShapeRejected(MissingInvalidOta.class, MissingInvalidOta.class.getName());
        // The SDK stub on the test classpath lacks the LF lock members: it fails closed, never shadowed.
        expectShapeRejected(android.hardware.bydauto.ota.BYDAutoOtaDevice.class,
                DiLink5DoorLockControl.ReflectiveOtaLfReader.OTA_DEVICE_CLASS);
        assertEquals("android.hardware.bydauto.ota.BYDAutoOtaDevice",
                DiLink5DoorLockControl.ReflectiveOtaLfReader.OTA_DEVICE_CLASS);
    }

    @Test
    public void unverifiedDevicesFailClosedThroughSetLockedViaOta() throws Exception {
        try {
            new DiLink5DoorLockControl.ReflectiveOtaLfReader(new ShapedOta());
            fail("a device of another class was accepted");
        } catch (IllegalArgumentException expected) {
            // rejected by name
        }
        try {
            new DiLink5DoorLockControl.ReflectiveOtaLfReader(null);
            fail("a missing device was accepted");
        } catch (IllegalArgumentException expected) {
            // rejected
        }
        DiLink5DoorLockControl.Result missingOta = DiLink5DoorLockControl.setLockedViaOta(
                new Object(), null, true, () -> true, () -> true);
        assertFalse(missingOta.confirmed);
        assertEquals("typed door-lock rail unavailable (ota device missing)", missingOta.reason);
        DiLink5DoorLockControl.Result badDoorLock = DiLink5DoorLockControl.setLockedViaOta(
                new Object(), new ShapedOta(), true, () -> true, () -> true);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.FAILED, badDoorLock.outcome);
        assertEquals("typed door-lock rail unavailable (IllegalArgumentException)", badDoorLock.reason);
    }

    @Test
    public void otaPortIsSelectedOnlyForOtaGateRail() {
        assertEquals(DiLink5DoorLockControl.Mode.TYPED, DiLink5DoorLockControl.selectDiLink5LockMode(null, true));
        assertEquals(DiLink5DoorLockControl.Mode.TYPED, DiLink5DoorLockControl.selectDiLink5LockMode(null, false));
        assertEquals(DiLink5DoorLockControl.Mode.TYPED,
                DiLink5DoorLockControl.selectDiLink5LockMode(DiLink5LockRail.TYPED_CABIN, true));
        assertEquals(DiLink5DoorLockControl.Mode.TYPED,
                DiLink5DoorLockControl.selectDiLink5LockMode(DiLink5LockRail.TYPED_CABIN, false));
        assertEquals(DiLink5DoorLockControl.Mode.OTA,
                DiLink5DoorLockControl.selectDiLink5LockMode(DiLink5LockRail.OTA, true));
        assertEquals(DiLink5DoorLockControl.Mode.OTA_UNAVAILABLE,
                DiLink5DoorLockControl.selectDiLink5LockMode(DiLink5LockRail.OTA, false));
        assertEquals(DiLink5DoorLockControl.Mode.DELEGATED_CARSVC,
                DiLink5DoorLockControl.selectDiLink5LockMode(DiLink5LockRail.CARSVC, true));
        assertEquals(DiLink5DoorLockControl.Mode.DELEGATED_CARSVC,
                DiLink5DoorLockControl.selectDiLink5LockMode(DiLink5LockRail.CARSVC, false));
        // The OTA port is reachable from no other rail.
        for (DiLink5LockRail rail : new DiLink5LockRail[]{null, DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.CARSVC}) {
            for (boolean device : new boolean[]{true, false}) {
                DiLink5DoorLockControl.Mode mode = DiLink5DoorLockControl.selectDiLink5LockMode(rail, device);
                assertTrue(String.valueOf(rail), mode != DiLink5DoorLockControl.Mode.OTA
                        && mode != DiLink5DoorLockControl.Mode.OTA_UNAVAILABLE);
            }
        }
    }

    private static void expectShapeRejected(Class<?> type, String expectedName) {
        try {
            DiLink5DoorLockControl.verifyOtaDeviceShape(type, expectedName);
            fail("accepted " + type + " as " + expectedName);
        } catch (IllegalArgumentException expected) {
            // rejected
        }
    }
}
