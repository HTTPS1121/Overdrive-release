package com.overdrive.app.byd;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Test;

/** Runs the production sequence engine with no Android or vehicle runtime. */
public class RemoteKeySequenceTest {
    @After public void clearInterrupt() { Thread.interrupted(); }

    private static final class Clock implements RemoteKeySequence.Clock {
        final AtomicLong now = new AtomicLong(1_000L);
        final List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());
        volatile java.util.function.LongConsumer afterSleep;
        long interruptGap = -1L;
        public long elapsedMs() { return now.get(); }
        public void sleep(long millis) throws InterruptedException {
            if (Thread.interrupted() || interruptGap == millis) {
                interruptGap = -1L;
                throw new InterruptedException("test cancellation");
            }
            sleeps.add(millis);
            now.addAndGet(millis);
            if (afterSleep != null) afterSleep.accept(millis);
        }
    }

    private static final class Port implements RemoteKeySequence.Port {
        final List<Integer> writes;
        final List<Integer> motionChecks = new ArrayList<>();
        final Queue<Integer> reads = new ArrayDeque<>();
        volatile boolean motionAllowed = true;
        volatile boolean requestCurrent = true;
        boolean off = true;
        int door = 2;
        int refusedCode = -1;
        int throwingCode = -1;
        boolean unavailable;
        long expiresAt = Long.MAX_VALUE;
        Clock clock;
        LocalActuationCancellation cancellation;
        Runnable afterPrepare;
        Runnable afterOffProof;
        Runnable duringAdmission;
        String failure;
        LocalActuationAbort abort;
        // MCU readiness gate fakes. Defaults mirror the interface defaults (status unavailable,
        // cannot wake, gate on), so every pre-existing test sees no gate activity at all.
        int mcuStatus = -1;
        final Queue<Integer> mcuStatuses = new ArrayDeque<>();
        boolean statusThrows;
        boolean wakeResult;
        boolean gateEnabled = true;
        int statusReads;
        int wakes;
        final List<String> logs = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        Port() { this(Collections.synchronizedList(new ArrayList<>())); }
        Port(List<Integer> writes) { this.writes = writes; }
        public void begin() { failure = null; abort = null; }
        public void clearFailure() { failure = null; }
        public RemoteKeySequence.PreparedFrame prepare(int code) {
            if (afterPrepare != null) afterPrepare.run();
            if (unavailable) { refuse("auto service unavailable"); return null; }
            return () -> {
                writes.add(code);
                if (throwingCode == code) throw new IllegalStateException("vendor rejected");
                if (refusedCode == code) { refuse("rc=-1"); return false; }
                return true;
            };
        }
        public void checkLease() {
            if (cancellation != null && cancellation.isCancelled()) {
                throw new LocalActuationAbort(LocalActuationAbort.Kind.CANCELLED, "cancelled");
            }
            if (!requestCurrent || (clock != null && clock.elapsedMs() >= expiresAt)) {
                throw new LocalActuationAbort(LocalActuationAbort.Kind.REQUEST_EXPIRED, "expired");
            }
        }
        public void checkMotion(int code) {
            motionChecks.add(code);
            if (duringAdmission != null) duringAdmission.run();
            if (!motionAllowed) {
                throw new LocalActuationAbort(LocalActuationAbort.Kind.BLOCKED_DRIVING, "moving");
            }
        }
        public boolean confirmedOff() {
            boolean answer = off;
            if (afterOffProof != null) afterOffProof.run();
            return answer;
        }
        public int readDoor() { return reads.isEmpty() ? door : reads.remove(); }
        public void refuse(String reason) { failure = reason; }
        public void abort(LocalActuationAbort reason) { abort = reason; failure = reason.getMessage(); }
        public void warn(String message) { warnings.add(message); }
        public void log(String message) { logs.add(message); }
        public int readControllerStatus() {
            statusReads++;
            if (statusThrows) throw new IllegalStateException("power device unavailable");
            return mcuStatuses.isEmpty() ? mcuStatus : mcuStatuses.remove();
        }
        public boolean wakeController() { wakes++; return wakeResult; }
        public boolean readinessGateEnabled() { return gateEnabled; }
    }

    @Test public void acceptedUnlockKeepsTwoFramesAndTheOriginalGap() {
        Clock clock = new Clock();
        Port port = new Port();
        assertTrue(new RemoteKeySequence(clock).unlock(port));
        assertEquals(Arrays.asList(5, 5), port.writes);
        assertEquals(Arrays.asList(5, 5), port.motionChecks);
        assertEquals(Collections.singletonList(500L), clock.sleeps);
        assertNull(port.abort);
    }

    @Test public void refusedFirstUnlockFrameDoesNotSendTheSecond() {
        Clock clock = new Clock();
        Port port = new Port();
        port.refusedCode = 5;
        assertFalse(new RemoteKeySequence(clock).unlock(port));
        assertEquals(Collections.singletonList(5), port.writes);
        assertTrue(clock.sleeps.isEmpty());
        assertNull(port.abort);
    }

    @Test public void motionChangingDuringManagerLookupBlocksTheActualWrite() {
        Port port = new Port();
        port.afterPrepare = () -> port.motionAllowed = false;
        assertFalse(new RemoteKeySequence(new Clock()).send(port, 7));
        assertTrue(port.writes.isEmpty());
        assertEquals(LocalActuationAbort.Kind.BLOCKED_DRIVING, port.abort.kind);
    }

    @Test public void leaseExpiringDuringUnavailableManagerLookupIsTerminal() {
        Clock clock = new Clock();
        Port port = new Port();
        port.clock = clock;
        port.expiresAt = 2_000L;
        port.unavailable = true;
        port.afterPrepare = () -> clock.now.set(2_000L);
        assertFalse(new RemoteKeySequence(clock).send(port, 7));
        assertTrue(port.writes.isEmpty());
        assertEquals(LocalActuationAbort.Kind.REQUEST_EXPIRED, port.abort.kind);
    }

    @Test public void requestRevokedDuringMotionReadIsCheckedAgainBeforeWrite() {
        Port port = new Port();
        port.duringAdmission = () -> port.requestCurrent = false;
        assertFalse(new RemoteKeySequence(new Clock()).send(port, 7));
        assertTrue(port.writes.isEmpty());
        assertEquals(LocalActuationAbort.Kind.REQUEST_EXPIRED, port.abort.kind);
    }

    @Test public void interruptDuringManagerLookupPreservesTheFlagAndBlocksWrite() {
        Port port = new Port();
        port.afterPrepare = () -> Thread.currentThread().interrupt();
        assertFalse(new RemoteKeySequence(new Clock()).send(port, 7));
        assertTrue(port.writes.isEmpty());
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(LocalActuationAbort.Kind.CANCELLED, port.abort.kind);
    }

    @Test public void preexistingInterruptNeverPreparesOrSendsAFrame() {
        Port port = new Port();
        AtomicBoolean prepared = new AtomicBoolean();
        port.afterPrepare = () -> prepared.set(true);
        Thread.currentThread().interrupt();
        assertFalse(new RemoteKeySequence(new Clock()).send(port, 7));
        assertFalse(prepared.get());
        assertTrue(port.writes.isEmpty());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test public void motionChangingDuringUnlockGapStopsTheSecondFrame() {
        Clock clock = new Clock();
        Port port = new Port();
        clock.afterSleep = ignored -> port.motionAllowed = false;
        assertFalse(new RemoteKeySequence(clock).unlock(port));
        assertEquals(Collections.singletonList(5), port.writes);
        assertEquals(LocalActuationAbort.Kind.BLOCKED_DRIVING, port.abort.kind);
    }

    @Test public void leaseExpiringDuringUnlockGapStopsTheSecondFrame() {
        Clock clock = new Clock();
        Port port = new Port();
        port.clock = clock;
        port.expiresAt = 1_500L;
        assertFalse(new RemoteKeySequence(clock).unlock(port));
        assertEquals(Collections.singletonList(5), port.writes);
        assertEquals(LocalActuationAbort.Kind.REQUEST_EXPIRED, port.abort.kind);
    }

    @Test public void interruptedUnlockGapCannotContinueTheSequence() {
        Clock clock = new Clock();
        clock.interruptGap = 500L;
        Port port = new Port();
        assertFalse(new RemoteKeySequence(clock).unlock(port));
        assertEquals(Collections.singletonList(5), port.writes);
        assertEquals(LocalActuationAbort.Kind.CANCELLED, port.abort.kind);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test public void remoteCancellationDuringUnlockGapCannotSendDelayedFrame() {
        Clock clock = new Clock();
        Port port = new Port();
        port.cancellation = new LocalActuationCancellation();
        clock.afterSleep = ignored -> port.cancellation.cancel();
        assertFalse(new RemoteKeySequence(clock).unlock(port));
        assertEquals(Collections.singletonList(5), port.writes);
        assertEquals(LocalActuationAbort.Kind.CANCELLED, port.abort.kind);
    }

    @Test public void confirmedOffPowerOnRetainsOffOnOrderAndPrimerGap() {
        Clock clock = new Clock();
        Port port = new Port();
        assertTrue(new RemoteKeySequence(clock).powerOnFromOff(port));
        assertEquals(Arrays.asList(9, 1), port.writes);
        assertEquals(Arrays.asList(9, 1), port.motionChecks);
        assertEquals(Collections.singletonList(2_000L), clock.sleeps);
    }

    @Test public void staleOffProofSendsOnAloneWithoutAnOffPrimer() {
        Clock clock = new Clock();
        Port port = new Port();
        port.afterPrepare = () -> port.off = false;
        assertTrue(new RemoteKeySequence(clock).powerOnFromOff(port));
        assertEquals(Collections.singletonList(1), port.writes);
        assertTrue(clock.sleeps.isEmpty());
    }

    @Test public void offProofChangingMotionCannotUseAnEarlierMotionVerdict() {
        Port port = new Port();
        port.afterOffProof = () -> port.motionAllowed = false;
        assertFalse(new RemoteKeySequence(new Clock()).powerOnFromOff(port));
        assertTrue(port.writes.isEmpty());
        assertEquals(LocalActuationAbort.Kind.BLOCKED_DRIVING, port.abort.kind);
    }

    @Test public void offProofRevokingRequestCannotWritePrimerOrOn() {
        Port port = new Port();
        port.afterOffProof = () -> port.requestCurrent = false;
        assertFalse(new RemoteKeySequence(new Clock()).powerOnFromOff(port));
        assertTrue(port.writes.isEmpty());
        assertEquals(LocalActuationAbort.Kind.REQUEST_EXPIRED, port.abort.kind);
    }

    @Test public void ordinaryPrimerRefusalStillPermitsOnWithoutSleeping() {
        Clock clock = new Clock();
        Port port = new Port();
        port.refusedCode = 9;
        assertTrue(new RemoteKeySequence(clock).powerOnFromOff(port));
        assertEquals(Arrays.asList(9, 1), port.writes);
        assertTrue(clock.sleeps.isEmpty());
        assertNull(port.abort);
        assertNull(port.failure);
    }

    @Test public void primerInterruptStopsOnAndPreservesInterrupt() {
        Clock clock = new Clock();
        clock.interruptGap = 2_000L;
        Port port = new Port();
        assertFalse(new RemoteKeySequence(clock).powerOnFromOff(port));
        assertEquals(Collections.singletonList(9), port.writes);
        assertEquals(LocalActuationAbort.Kind.CANCELLED, port.abort.kind);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test public void remoteCancellationAfterPrimerStopsTheDelayedOn() {
        Clock clock = new Clock();
        Port port = new Port();
        port.cancellation = new LocalActuationCancellation();
        clock.afterSleep = ignored -> port.cancellation.cancel();
        assertFalse(new RemoteKeySequence(clock).powerOnFromOff(port));
        assertEquals(Collections.singletonList(9), port.writes);
        assertEquals(LocalActuationAbort.Kind.CANCELLED, port.abort.kind);
    }

    @Test public void motionChangingAfterPrimerStopsTheDelayedOn() {
        Clock clock = new Clock();
        Port port = new Port();
        clock.afterSleep = ignored -> port.motionAllowed = false;
        assertFalse(new RemoteKeySequence(clock).powerOnFromOff(port));
        assertEquals(Collections.singletonList(9), port.writes);
        assertEquals(LocalActuationAbort.Kind.BLOCKED_DRIVING, port.abort.kind);
    }

    @Test public void holdoffDoesNotExtendOnRepeatAndExpiresAtSixtySeconds() {
        Clock clock = new Clock();
        Port port = new Port();
        RemoteKeySequence sequence = new RemoteKeySequence(clock);
        assertTrue(sequence.powerOnFromOff(port)); // accepted at 3,000
        clock.now.set(10_000L);
        assertTrue(sequence.powerOnFromOff(port));
        clock.now.set(62_999L);
        assertTrue(sequence.powerOnFromOff(port));
        clock.now.set(63_000L);
        assertTrue(sequence.powerOnFromOff(port));
        assertEquals(Arrays.asList(9, 1, 1, 1, 9, 1), port.writes);
        assertEquals(Arrays.asList(2_000L, 2_000L), clock.sleeps);
    }

    @Test public void refusedOnDoesNotStartPrimerHoldoff() {
        Clock clock = new Clock();
        Port port = new Port();
        port.refusedCode = 1;
        RemoteKeySequence sequence = new RemoteKeySequence(clock);
        assertFalse(sequence.powerOnFromOff(port));
        assertFalse(sequence.powerOnFromOff(port));
        assertEquals(Arrays.asList(9, 1, 9, 1), port.writes);
        assertEquals(Arrays.asList(2_000L, 2_000L), clock.sleeps);
        assertNull(port.abort);
    }

    @Test public void continuouslyLockedReadbackRetriesExactlyOnceThenRefuses() {
        Clock clock = new Clock();
        Port port = new Port();
        assertFalse(new RemoteKeySequence(clock).unlockConfirmed(port, true));
        assertEquals(Arrays.asList(5, 5, 5, 5), port.writes);
        assertEquals(10_000L, clock.elapsedMs());
        assertNull(port.abort);
        assertTrue(port.failure.contains("accepted twice"));
    }

    @Test public void unknownInitialReadbackKeepsPlainAcceptedUnlock() {
        Clock clock = new Clock();
        Port port = new Port();
        port.door = 0;
        assertTrue(new RemoteKeySequence(clock).unlockConfirmed(port, true));
        assertEquals(Arrays.asList(5, 5), port.writes);
        assertEquals(Collections.singletonList(500L), clock.sleeps);
    }

    @Test public void nullReadbackKeepsPlainAcceptedUnlock() {
        Port port = new Port();
        assertTrue(new RemoteKeySequence(new Clock()).unlockConfirmed(port, false));
        assertEquals(Arrays.asList(5, 5), port.writes);
    }

    @Test public void readbackBecomingUnknownKeepsAcceptanceWithoutRetry() {
        Port port = new Port();
        port.reads.add(2);
        port.door = 0;
        assertTrue(new RemoteKeySequence(new Clock()).unlockConfirmed(port, true));
        assertEquals(Arrays.asList(5, 5), port.writes);
        assertNull(port.abort);
    }

    @Test public void unlockedReadbackConfirmsWithoutRetry() {
        Port port = new Port();
        port.reads.add(2);
        port.door = 1;
        assertTrue(new RemoteKeySequence(new Clock()).unlockConfirmed(port, true));
        assertEquals(Arrays.asList(5, 5), port.writes);
    }

    @Test public void readbackCancellationKeepsAlreadyAcceptedUnknownContract() {
        Clock clock = new Clock();
        clock.interruptGap = 250L;
        Port port = new Port();
        assertTrue(new RemoteKeySequence(clock).unlockConfirmed(port, true));
        assertEquals(Arrays.asList(5, 5), port.writes);
        assertNull(port.abort);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test public void acceptedAndRefusedHornOrFlashDoNotSupersedeUnlockRetry() {
        for (int code : new int[] {10, 22}) {
            for (boolean accepted : new boolean[] {true, false}) {
                Clock clock = new Clock();
                Port unlock = new Port();
                Port other = new Port(unlock.writes);
                other.refusedCode = accepted ? -1 : code;
                RemoteKeySequence sequence = new RemoteKeySequence(clock);
                AtomicBoolean once = new AtomicBoolean();
                clock.afterSleep = gap -> {
                    if (gap == 250L && once.compareAndSet(false, true)) {
                        assertEquals(accepted, sequence.send(other, code));
                    }
                };
                assertFalse(sequence.unlockConfirmed(unlock, true));
                assertEquals(Arrays.asList(5, 5, code, 5, 5), unlock.writes);
                assertNull(unlock.abort);
            }
        }
    }

    @Test public void acceptedRefusedAndThrowingLockIntentsSuppressOldUnlockRetry() {
        for (int failure : new int[] {0, 1, 2, 3}) {
            Clock clock = new Clock();
            Port unlock = new Port();
            Port lock = new Port(unlock.writes);
            lock.refusedCode = failure == 1 ? 7 : -1;
            lock.throwingCode = failure == 2 ? 7 : -1;
            lock.unavailable = failure == 3;
            RemoteKeySequence sequence = new RemoteKeySequence(clock);
            AtomicBoolean once = new AtomicBoolean();
            clock.afterSleep = gap -> {
                if (gap == 250L && once.compareAndSet(false, true)) sequence.send(lock, 7);
            };
            assertTrue(sequence.unlockConfirmed(unlock, true));
            assertEquals(failure == 3 ? Arrays.asList(5, 5) : Arrays.asList(5, 5, 7),
                    unlock.writes);
            assertNull(unlock.abort);
        }
    }

    @Test public void powerIntentsConservativelyRetainUnlockSupersession() {
        for (int code : new int[] {1, 9}) {
            for (boolean accepted : new boolean[] {true, false}) {
                Clock clock = new Clock();
                Port unlock = new Port();
                Port power = new Port(unlock.writes);
                power.refusedCode = accepted ? -1 : code;
                RemoteKeySequence sequence = new RemoteKeySequence(clock);
                AtomicBoolean once = new AtomicBoolean();
                clock.afterSleep = gap -> {
                    if (gap == 250L && once.compareAndSet(false, true)) sequence.send(power, code);
                };
                assertTrue(sequence.unlockConfirmed(unlock, true));
                assertEquals(Arrays.asList(5, 5, code), unlock.writes);
            }
        }
    }

    @Test public void expiredConfirmationCannotStartARetrySequence() {
        Clock clock = new Clock();
        Port port = new Port();
        port.clock = clock;
        port.expiresAt = 3_000L;
        assertFalse(new RemoteKeySequence(clock).unlockConfirmed(port, true));
        assertEquals(Arrays.asList(5, 5), port.writes);
        assertEquals(LocalActuationAbort.Kind.REQUEST_EXPIRED, port.abort.kind);
    }

    @Test public void laterCallDoesNotInheritAnEarlierFrameAbort() {
        Port port = new Port();
        RemoteKeySequence sequence = new RemoteKeySequence(new Clock());
        port.motionAllowed = false;
        assertFalse(sequence.send(port, 7));
        assertNotNull(port.abort);
        port.motionAllowed = true;
        assertTrue(sequence.send(port, 7));
        assertNull(port.abort);
        assertNull(port.failure);
    }

    @Test public void queuedFrameRechecksAdmissionAfterAcquiringSequenceLock() throws Exception {
        runQueuedFrame(false);
    }

    @Test public void cancelledQueuedFrameCannotWriteAfterLockBecomesAvailable() throws Exception {
        runQueuedFrame(true);
    }

    // ── MCU readiness gate ─────────────────────────────────────────────────────────────────

    @Test public void readyControllerWritesImmediatelyWithoutSleepOrWake() {
        for (int status : new int[] {1, 10}) {
            Clock clock = new Clock();
            Port port = new Port();
            port.mcuStatus = status;
            port.wakeResult = true;
            assertTrue(new RemoteKeySequence(clock).send(port, 7));
            assertEquals(Collections.singletonList(7), port.writes);
            assertEquals(1, port.statusReads);
            assertEquals(0, port.wakes);
            assertTrue(clock.sleeps.isEmpty());
            assertEquals(Collections.singletonList("remote-key: mcuStatus=" + status
                    + " ready=true wake=false waitedMs=0 outcome=ready"), port.logs);
            assertTrue(port.warnings.isEmpty());
        }
    }

    @Test public void unavailableStatusFailsOpenWithoutSleepOrWake() {
        for (boolean throwing : new boolean[] {false, true}) {
            Clock clock = new Clock();
            Port port = new Port();
            port.statusThrows = throwing;
            port.wakeResult = true;
            assertTrue(new RemoteKeySequence(clock).send(port, 7));
            assertEquals(Collections.singletonList(7), port.writes);
            assertEquals(1, port.statusReads);
            assertEquals(0, port.wakes);
            assertTrue(clock.sleeps.isEmpty());
            assertEquals(Collections.singletonList(
                    "remote-key: mcuStatus=-1 ready=false wake=false waitedMs=0 outcome=unavailable"),
                    port.logs);
            assertNull(port.abort);
        }
    }

    @Test public void sleepingControllerIsWokenOnceAndPolledUntilReady() {
        Clock clock = new Clock();
        Port port = new Port();
        port.mcuStatuses.addAll(Arrays.asList(0, 0, 1));
        port.wakeResult = true;
        assertTrue(new RemoteKeySequence(clock).unlock(port));
        assertEquals(Arrays.asList(5, 5), port.writes);
        assertEquals(1, port.wakes);
        assertEquals(3, port.statusReads);
        // Two 250 ms readiness polls, then the unchanged 500 ms unlock gap.
        assertEquals(Arrays.asList(250L, 250L, 500L), clock.sleeps);
        assertEquals(Collections.singletonList(
                "remote-key: mcuStatus=1 ready=true wake=true waitedMs=500 outcome=ready"), port.logs);
        assertTrue(port.warnings.isEmpty());
        assertNull(port.abort);
    }

    @Test public void controllerStillAsleepAtDeadlineProceedsWithWarning() {
        Clock clock = new Clock();
        Port port = new Port();
        port.mcuStatus = 0;
        port.wakeResult = true;
        assertTrue(new RemoteKeySequence(clock).send(port, 7));
        assertEquals(Collections.singletonList(7), port.writes);
        assertEquals(1, port.wakes);
        assertEquals(Collections.nCopies(6, 250L), clock.sleeps);
        assertEquals(1_500L, clock.elapsedMs() - 1_000L);
        assertEquals(1, port.warnings.size());
        assertTrue(port.warnings.get(0).contains("not confirmed ready after 1500ms"));
        assertEquals(Collections.singletonList(
                "remote-key: mcuStatus=0 ready=false wake=true waitedMs=1500 outcome=timeout"),
                port.logs);
        assertNull(port.abort);
        assertNull(port.failure);
    }

    @Test public void refusedWakeProceedsWithoutWaiting() {
        Clock clock = new Clock();
        Port port = new Port();
        port.mcuStatus = 0;
        port.wakeResult = false;
        assertTrue(new RemoteKeySequence(clock).send(port, 7));
        assertEquals(Collections.singletonList(7), port.writes);
        assertEquals(1, port.wakes);
        assertEquals(1, port.statusReads);
        assertTrue(clock.sleeps.isEmpty());
        assertEquals(Collections.singletonList(
                "remote-key: mcuStatus=0 ready=false wake=false waitedMs=0 outcome=skipped"),
                port.logs);
    }

    @Test public void interruptDuringReadinessPollAbortsBeforeAnyFrame() {
        Clock clock = new Clock();
        clock.interruptGap = 250L;
        Port port = new Port();
        port.mcuStatus = 0;
        port.wakeResult = true;
        assertFalse(new RemoteKeySequence(clock).unlock(port));
        assertTrue(port.writes.isEmpty());
        assertEquals(LocalActuationAbort.Kind.CANCELLED, port.abort.kind);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test public void leaseRevokedDuringReadinessPollAbortsBeforeAnyFrame() {
        Clock clock = new Clock();
        Port port = new Port();
        port.mcuStatus = 0;
        port.wakeResult = true;
        clock.afterSleep = gap -> { if (gap == 250L) port.requestCurrent = false; };
        assertFalse(new RemoteKeySequence(clock).unlock(port));
        assertTrue(port.writes.isEmpty());
        assertEquals(LocalActuationAbort.Kind.REQUEST_EXPIRED, port.abort.kind);
        assertEquals(Collections.singletonList(250L), clock.sleeps);
    }

    @Test public void remoteCancellationDuringReadinessPollAbortsBeforeAnyFrame() {
        Clock clock = new Clock();
        Port port = new Port();
        port.mcuStatus = 0;
        port.wakeResult = true;
        port.cancellation = new LocalActuationCancellation();
        clock.afterSleep = gap -> { if (gap == 250L) port.cancellation.cancel(); };
        assertFalse(new RemoteKeySequence(clock).send(port, 7));
        assertTrue(port.writes.isEmpty());
        assertEquals(LocalActuationAbort.Kind.CANCELLED, port.abort.kind);
    }

    @Test public void leaseRevokedBeforeTheWakeNeverWakes() {
        Clock clock = new Clock();
        Port port = new Port();
        port.mcuStatus = 0;
        port.wakeResult = true;
        port.afterPrepare = () -> port.requestCurrent = false;
        assertFalse(new RemoteKeySequence(clock).send(port, 7));
        assertTrue(port.writes.isEmpty());
        assertEquals(0, port.wakes);
        assertEquals(0, port.statusReads);
        assertEquals(LocalActuationAbort.Kind.REQUEST_EXPIRED, port.abort.kind);
    }

    @Test public void motionBlockedBeforeTheWakeAbortsWithNoWakeOrFrame() {
        Clock clock = new Clock();
        Port port = new Port();
        port.mcuStatus = 0;          // asleep: the gate would wake
        port.wakeResult = true;
        port.motionAllowed = false;  // but the car is no longer confirmed parked
        assertFalse(new RemoteKeySequence(clock).send(port, 7));
        assertTrue(port.writes.isEmpty());
        assertEquals(0, port.wakes);                 // the pre-wake motion check aborts first
        assertEquals(1, port.statusReads);           // status read decides "asleep"; then motion blocks
        assertEquals(Collections.singletonList(7), port.motionChecks);
        assertTrue(clock.sleeps.isEmpty());
        assertEquals(LocalActuationAbort.Kind.BLOCKED_DRIVING, port.abort.kind);
    }

    @Test public void motionBlockedAfterTheWaitAbortsWithOneWakeAndNoFrame() {
        Clock clock = new Clock();
        Port port = new Port();
        port.mcuStatuses.addAll(Arrays.asList(0, 1)); // asleep, then ready after one poll
        port.wakeResult = true;
        clock.afterSleep = gap -> { if (gap == 250L) port.motionAllowed = false; };
        assertFalse(new RemoteKeySequence(clock).send(port, 7));
        assertTrue(port.writes.isEmpty());
        assertEquals(1, port.wakes);                 // motion was OK before the wake, so it woke once
        assertEquals(Arrays.asList(7, 7), port.motionChecks); // pre-wake OK, pre-write blocked
        assertEquals(LocalActuationAbort.Kind.BLOCKED_DRIVING, port.abort.kind);
    }

    @Test public void readyControllerAddsNoPreWakeMotionCheck() {
        Clock clock = new Clock();
        Port port = new Port();
        port.mcuStatus = 1;          // ready: no wake, so no pre-wake motion check
        assertTrue(new RemoteKeySequence(clock).send(port, 7));
        assertEquals(Collections.singletonList(7), port.writes);
        assertEquals(Collections.singletonList(7), port.motionChecks); // one per frame, as before
        assertEquals(0, port.wakes);
    }

    @Test public void confirmRetryReGatesButAReadyControllerNeverWakesOrSleeps() {
        Clock clock = new Clock();
        Port port = new Port();
        port.mcuStatus = 1; // ready on every read
        // Driver door keeps reading locked: four frames in two attempts. The gate runs once per
        // attempt's FIRST frame (first sequence + confirm retry) and never on the 500 ms repeat.
        assertFalse(new RemoteKeySequence(clock).unlockConfirmed(port, true));
        assertEquals(Arrays.asList(5, 5, 5, 5), port.writes);
        assertEquals(0, port.wakes);         // ready → never wakes, so the retry adds no sleep
        assertEquals(2, port.statusReads);   // one per attempt (not four) → repeat frame never re-gates
        assertEquals(2, port.logs.size());
        assertEquals(2, Collections.frequency(clock.sleeps, 500L));
    }

    @Test public void confirmRetryWakesIndependentlyWhenTheControllerSleepsAgain() {
        Clock clock = new Clock();
        Port port = new Port();
        port.mcuStatuses.addAll(Arrays.asList(0, 1, 0, 1));
        port.wakeResult = true;
        // Each attempt's first frame re-gates independently: asleep → wake → poll → ready.
        assertFalse(new RemoteKeySequence(clock).unlockConfirmed(port, true));
        assertEquals(Arrays.asList(5, 5, 5, 5), port.writes);
        assertEquals(2, port.wakes);         // first attempt + retry each wake once
        assertEquals(4, port.statusReads);   // two reads (0 then 1) per gated first frame, none on the repeat
        assertEquals(2, port.logs.size());
    }

    @Test public void hornAndFlashNeverReadOrWakeTheController() {
        for (int code : new int[] {10, 22}) {
            Clock clock = new Clock();
            Port port = new Port();
            port.mcuStatus = 0;
            port.wakeResult = true;
            assertTrue(new RemoteKeySequence(clock).send(port, code));
            assertEquals(Collections.singletonList(code), port.writes);
            assertEquals(0, port.statusReads);
            assertEquals(0, port.wakes);
            assertTrue(clock.sleeps.isEmpty());
            assertTrue(port.logs.isEmpty());
        }
    }

    @Test public void powerOnFromOffGatesOnceBeforeThePrimer() {
        Clock clock = new Clock();
        Port port = new Port();
        port.mcuStatuses.addAll(Arrays.asList(0, 1));
        port.wakeResult = true;
        assertTrue(new RemoteKeySequence(clock).powerOnFromOff(port));
        assertEquals(Arrays.asList(9, 1), port.writes);
        assertEquals(1, port.wakes);
        assertEquals(2, port.statusReads);
        assertEquals(Arrays.asList(250L, 2_000L), clock.sleeps);
        assertEquals(1, port.logs.size());
    }

    @Test public void staleOffProofGatesTheOnFrameInsteadOfTheSkippedPrimer() {
        Clock clock = new Clock();
        Port port = new Port();
        port.mcuStatuses.addAll(Arrays.asList(0, 1));
        port.wakeResult = true;
        port.afterPrepare = () -> port.off = false;
        assertTrue(new RemoteKeySequence(clock).powerOnFromOff(port));
        assertEquals(Collections.singletonList(1), port.writes);
        assertEquals(1, port.wakes);
        assertEquals(Collections.singletonList(250L), clock.sleeps);
    }

    @Test public void unavailableManagerNeverTriggersAWake() {
        Clock clock = new Clock();
        Port port = new Port();
        port.unavailable = true;
        port.mcuStatus = 0;
        port.wakeResult = true;
        assertFalse(new RemoteKeySequence(clock).send(port, 7));
        assertTrue(port.writes.isEmpty());
        assertEquals(0, port.statusReads);
        assertEquals(0, port.wakes);
        assertTrue(clock.sleeps.isEmpty());
    }

    @Test public void disabledGateNeverReadsStatus() {
        Clock clock = new Clock();
        Port port = new Port();
        port.gateEnabled = false;
        port.mcuStatus = 0;
        port.wakeResult = true;
        assertTrue(new RemoteKeySequence(clock).unlock(port));
        assertEquals(Arrays.asList(5, 5), port.writes);
        assertEquals(0, port.statusReads);
        assertEquals(0, port.wakes);
        assertEquals(Collections.singletonList(500L), clock.sleeps);
        assertEquals(Collections.singletonList(
                "remote-key: mcuStatus=n/a ready=false wake=false waitedMs=0 outcome=disabled"),
                port.logs);
    }

    /** Invariant: with a ready or unavailable MCU, call sequence and timing match the pre-gate engine. */
    @Test public void readyOrUnavailableControllerKeepsPreGateCallSequenceAndTiming() {
        for (int status : new int[] {1, 10, -1}) {
            Clock clock = new Clock();
            Port port = new Port();
            port.mcuStatus = status;
            RemoteKeySequence sequence = new RemoteKeySequence(clock);
            assertTrue(sequence.unlock(port));
            assertEquals(Arrays.asList(5, 5), port.writes);
            assertEquals(Arrays.asList(5, 5), port.motionChecks);
            assertEquals(Collections.singletonList(500L), clock.sleeps);
            Port power = new Port();
            power.mcuStatus = status;
            Clock powerClock = new Clock();
            assertTrue(new RemoteKeySequence(powerClock).powerOnFromOff(power));
            assertEquals(Arrays.asList(9, 1), power.writes);
            assertEquals(Collections.singletonList(2_000L), powerClock.sleeps);
            assertEquals(0, port.wakes + power.wakes);
        }
    }

    private static void runQueuedFrame(boolean cancel) throws Exception {
        Clock clock = new Clock();
        List<Integer> frames = Collections.synchronizedList(new ArrayList<>());
        Port first = new Port(frames);
        Port second = new Port(frames);
        RemoteKeySequence sequence = new RemoteKeySequence(clock);
        CountDownLatch holdingGap = new CountDownLatch(1);
        CountDownLatch releaseGap = new CountDownLatch(1);
        clock.afterSleep = gap -> {
            if (gap != 500L) return;
            holdingGap.countDown();
            try {
                if (!releaseGap.await(2, TimeUnit.SECONDS)) throw new AssertionError("gap not released");
            } catch (InterruptedException interrupted) {
                throw new AssertionError(interrupted);
            }
        };
        FutureTask<Boolean> a = new FutureTask<>(() -> sequence.unlock(first));
        AtomicBoolean flagPreserved = new AtomicBoolean();
        FutureTask<Boolean> b = new FutureTask<>(() -> {
            boolean accepted = sequence.send(second, 7);
            flagPreserved.set(Thread.currentThread().isInterrupted());
            return accepted;
        });
        Thread holder = new Thread(a, "sequence-holder");
        Thread queued = new Thread(b, "sequence-queued");
        holder.setDaemon(true);
        queued.setDaemon(true);
        try {
            holder.start();
            assertTrue(holdingGap.await(2, TimeUnit.SECONDS));
            queued.start();
            long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (queued.getState() != Thread.State.BLOCKED && System.nanoTime() < waitUntil) {
                Thread.yield();
            }
            assertEquals(Thread.State.BLOCKED, queued.getState());
            if (cancel) queued.interrupt();
            else second.motionAllowed = false;
            assertEquals(Collections.singletonList(5), frames);
            releaseGap.countDown();
            assertTrue(a.get(2, TimeUnit.SECONDS));
            assertFalse(b.get(2, TimeUnit.SECONDS));
            assertEquals(Arrays.asList(5, 5), frames);
            assertEquals(cancel ? LocalActuationAbort.Kind.CANCELLED
                    : LocalActuationAbort.Kind.BLOCKED_DRIVING, second.abort.kind);
            assertEquals(cancel, flagPreserved.get());
        } finally {
            releaseGap.countDown();
            holder.interrupt();
            queued.interrupt();
            holder.join(2_000L);
            queued.join(2_000L);
        }
    }
}
