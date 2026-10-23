package com.overdrive.app.surveillance;

import static com.overdrive.app.surveillance.ClusterAdasLifecycle.*;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.Test;

public class ClusterAdasLifecycleTest {
    private static Source source(long generation, boolean active, boolean busy) {
        return new Source(generation, active, busy);
    }
    private static Snapshot idle(long legacy, long di5) {
        return new Snapshot(source(legacy, false, false), source(di5, false, false));
    }
    private static Snapshot legacy(long generation) {
        return new Snapshot(source(generation, true, true), source(0, false, false));
    }
    private static Snapshot di5(long generation) {
        return new Snapshot(source(0, false, false), source(generation, true, true));
    }

    private static final class Fake implements Backend {
        Snapshot state = idle(0, 0);
        ClusterState cluster = ClusterState.UNKNOWN;
        Ownership initial = Ownership.NONE;
        boolean supported = true, powered = true, acquire = true, begin = true, complete = true;
        boolean clear = true, owned, pending;
        boolean throwCommand, throwProbe, throwSnapshot;
        long now, prepareDelay, releaseDelay, lastPreparedAt;
        int acquisitions, probes, clears;
        Runnable afterProbe, afterBegin, afterPacket;
        Result verdict = Result.COMPLETED;
        List<String> packets = new ArrayList<>(), events = new ArrayList<>(), logs = new ArrayList<>();

        @Override public Snapshot snapshot() {
            if (throwSnapshot) throw new IllegalStateException("snapshot");
            return state;
        }
        @Override public Ownership ownership() {
            owned = initial != Ownership.NONE;
            return initial;
        }
        @Override public boolean supported() {
            probes++;
            if (throwProbe) throw new IllegalStateException("probe");
            if (afterProbe != null) afterProbe.run();
            return supported;
        }
        @Override public boolean canHide() { return powered; }
        @Override public long nowMs() { return now; }
        @Override public ClusterState clusterState() { return cluster; }
        @Override public boolean acquireOwnership() {
            acquisitions++;
            if (acquire) owned = true;
            return acquire;
        }
        @Override public boolean beginCommand() {
            assertTrue("Mutation must have an owner", owned);
            if (!begin || pending) return false;
            pending = true;
            if (afterBegin != null) afterBegin.run();
            return true;
        }
        @Override public boolean completeCommand() {
            if (complete) pending = false;
            return complete;
        }
        @Override public boolean clearOwnership() {
            assertFalse("Cannot release a pending mutation", pending);
            clears++;
            if (clear) { owned = false; events.add("clear"); }
            return clear;
        }
        @Override public Result prepare(BooleanSupplier guard) {
            events.add("prepare");
            now += prepareDelay;
            lastPreparedAt = now;
            return verdict;
        }
        @Override public Result container(int opcode) {
            assertTrue("Only dedicated overlay commands", isOverlayOpcode(opcode));
            assertTrue("Write-ahead record required", pending);
            events.add("op:" + opcode);
            if (opcode == 12) now += releaseDelay;
            if (throwCommand) throw new IllegalStateException("transport");
            return verdict;
        }
        @Override public Result frames(String frame, int count, BooleanSupplier guard) {
            assertTrue("Bounded batch", count >= 1 && count <= 10);
            assertTrue("Write-ahead record required", pending);
            if (verdict != Result.COMPLETED) return verdict;
            for (int n = 0; n < count; n++) {
                if (!guard.getAsBoolean()) return Result.CANCELLED;
                packets.add(frame);
                events.add(frame);
                if (afterPacket != null) afterPacket.run();
            }
            return Result.COMPLETED;
        }
        @Override public void log(String message) { logs.add(message); }
        int count(String frame) { return (int) packets.stream().filter(frame::equals).count(); }
        int countEvent(String event) { return (int) events.stream().filter(event::equals).count(); }
    }

    private static void ticks(Fake f, ClusterAdasLifecycle c, int steps) {
        for (int n = 0; n < steps; n++) { c.tick(f.now); f.now += 50L; }
    }
    private static void until(Fake f, ClusterAdasLifecycle c, BooleanSupplier done) {
        for (int n = 0; n < 2000 && !done.getAsBoolean(); n++) ticks(f, c, 1);
        assertTrue("State machine should finish bounded work", done.getAsBoolean());
    }
    private static void firstHide(Fake f, ClusterAdasLifecycle c) {
        until(f, c, () -> f.packets.size() >= 263);
    }
    private static void restore(Fake f, ClusterAdasLifecycle c) {
        until(f, c, () -> !f.owned);
    }

    @Test public void exactHideAndRestoreProtocolHasBoundedFramesAndOrdering() {
        Fake f = new Fake(); f.state = legacy(1);
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        firstHide(f, c);
        assertEquals(63, f.count("23,50,00,08,01,00"));
        assertEquals(100, f.count("26,F0,00,28,01,06"));
        assertEquals(50, f.count("43,20,00,00,01,01"));
        assertEquals(50, f.count("43,20,00,21,01,01"));
        assertEquals("op:13", f.events.get(0));
        assertEquals("prepare", f.events.get(1));
        f.state = idle(2, 0);
        restore(f, c);
        assertEquals(40, f.count("23,50,00,08,01,01"));
        assertEquals(1, f.countEvent("op:12"));
        assertEquals(1, f.countEvent("op:53"));
        assertTrue(f.events.lastIndexOf(RESTORE_FRAME) < f.events.indexOf("op:12"));
        assertTrue(f.events.indexOf("op:12") < f.events.indexOf("op:53"));
        assertEquals("clear", f.events.get(f.events.size() - 1));
    }

    @Test public void eachPhysicalOwnerHidesAndTheFinalOwnerRestores() {
        for (boolean useDi5 : new boolean[] {false, true}) {
            Fake f = new Fake(); f.state = useDi5 ? di5(1) : legacy(1);
            ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
            firstHide(f, c);
            f.state = new Snapshot(source(2, true, true), source(2, true, true));
            ticks(f, c, 30);
            f.state = new Snapshot(source(3, false, false), source(2, true, true));
            ticks(f, c, 30);
            assertEquals(0, f.count(RESTORE_FRAME));
            assertTrue(f.owned);
            f.state = idle(3, 3);
            restore(f, c);
            assertEquals(40, f.count(RESTORE_FRAME));
        }
    }

    @Test public void appMapHandoffAndPendingStartNeverRestoreBetweenSessions() {
        Fake f = new Fake(); f.state = di5(1);
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        firstHide(f, c);
        f.state = idle(0, 2);
        ticks(f, c, 3); // Less than the idle debounce.
        f.state = new Snapshot(source(0, false, false), source(3, false, true));
        ticks(f, c, 80); // PREPARING is not a projection-off state.
        assertEquals(0, f.count(RESTORE_FRAME));
        f.state = di5(3);
        until(f, c, () -> f.countEvent("op:13") == 2);
        assertEquals(0, f.count(RESTORE_FRAME));
        f.state = idle(0, 4);
        restore(f, c);
    }

    @Test public void stopDuringBurstCancelsAtTheNextPacketAndWaitsForCoreClose() {
        Fake f = new Fake(); f.state = legacy(1);
        f.afterPacket = () -> {
            if (f.packets.size() == 4) {
                f.state = new Snapshot(source(2, false, true), source(0, false, false));
            }
        };
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        until(f, c, () -> f.packets.size() == 4);
        ticks(f, c, 100);
        assertEquals(4, f.packets.size());
        assertEquals(0, f.countEvent("op:12"));
        assertFalse(f.pending);
        f.afterPacket = null;
        f.state = idle(2, 0);
        restore(f, c);
        assertEquals(44, f.packets.size());
    }

    @Test public void newProjectionDuringRestoreCancelsRemainingRestorePackets() {
        Fake f = new Fake(); f.state = legacy(1);
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        firstHide(f, c);
        f.state = idle(2, 0);
        f.afterPacket = () -> {
            if (f.count(RESTORE_FRAME) == 1) f.state = di5(1);
        };
        until(f, c, () -> f.count(RESTORE_FRAME) == 1);
        f.afterPacket = null;
        until(f, c, () -> f.countEvent("op:13") == 2);
        assertEquals(1, f.count(RESTORE_FRAME));
        assertEquals(0, f.countEvent("op:12"));
        assertEquals(0, f.countEvent("op:53"));
        assertEquals(0, f.clears);
        assertTrue(f.owned);
    }

    @Test public void newStartDuringRestoreDelayCancelsFinalRestoreOperation() {
        Fake f = new Fake(); f.initial = Ownership.OWNED;
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        until(f, c, () -> f.countEvent("op:12") == 1);
        f.state = new Snapshot(source(0, false, false), source(1, false, true));
        ticks(f, c, 100);
        assertEquals(0, f.countEvent("op:53"));
        assertEquals(0, f.clears);
        f.state = di5(1);
        until(f, c, () -> f.countEvent("op:13") == 1);
        assertEquals(0, f.countEvent("op:53"));
    }

    @Test public void unsupportedAndUnwritableOwnershipNeverDispatchAndAreBounded() {
        for (boolean unsupported : new boolean[] {true, false}) {
            Fake f = new Fake(); f.state = legacy(1);
            f.supported = !unsupported; f.acquire = unsupported;
            ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
            ticks(f, c, 1000);
            assertTrue(f.events.isEmpty());
            assertTrue(f.probes <= 2);
            assertTrue(f.acquisitions <= 2);
            f.state = idle(2, 0);
            ticks(f, c, 100);
            assertTrue(f.events.isEmpty());
        }
    }

    @Test public void stopDuringCapabilityProbeDoesNotAcquireOrMutate() {
        Fake f = new Fake(); f.state = legacy(1);
        f.afterProbe = () -> f.state = idle(2, 0);
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        ticks(f, c, 100);
        assertEquals(0, f.acquisitions);
        assertTrue(f.events.isEmpty());
    }

    @Test public void generationIsRecheckedAfterWriteAheadBeforeDispatch() {
        Fake f = new Fake(); f.state = legacy(1);
        f.afterBegin = () -> f.state = new Snapshot(source(2, false, true), source(0, false, false));
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        ticks(f, c, 100);
        assertEquals(0, f.countEvent("op:13"));
        assertTrue(f.events.isEmpty());
        assertFalse(f.pending);
        f.state = idle(2, 0);
        ticks(f, c, 100);
        assertFalse(f.owned);
        assertEquals(0, f.countEvent("op:12"));
        assertEquals(0, f.countEvent("op:53"));
    }

    @Test public void unknownCommandOrThrownTransportRetainsOwnershipAndStopsNewWrites() {
        for (boolean thrown : new boolean[] {false, true}) {
            Fake f = new Fake(); f.state = legacy(1);
            f.verdict = thrown ? Result.COMPLETED : Result.UNKNOWN;
            f.throwCommand = thrown;
            ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
            ticks(f, c, 100);
            assertEquals(1, f.events.size());
            assertTrue(f.owned);
            assertTrue(f.pending);
            f.state = idle(2, 0); ticks(f, c, 300);
            f.state = di5(3); ticks(f, c, 300);
            assertEquals(1, f.events.size());
            assertEquals(0, f.clears);
        }
    }

    @Test public void failedCommandCompletionPersistenceDoesNotSendRestoreAfterPossibleLateHide() {
        Fake f = new Fake(); f.state = legacy(1); f.complete = false;
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        ticks(f, c, 100);
        f.state = idle(2, 0);
        ticks(f, c, 400);
        assertEquals(1, f.countEvent("op:13"));
        assertEquals(0, f.count(RESTORE_FRAME));
        assertEquals(0, f.clears);
        assertTrue(f.owned && f.pending);
    }

    @Test public void failedWriteAheadNeverDispatches() {
        Fake f = new Fake(); f.state = di5(1); f.begin = false;
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        ticks(f, c, 300);
        assertTrue(f.events.isEmpty());
        assertTrue(f.owned);
    }

    @Test public void sameBootUncertainOwnerNeverMutatesButCleanBootOwnerRestores() {
        Fake uncertain = new Fake(); uncertain.initial = Ownership.UNCERTAIN;
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(uncertain);
        ticks(uncertain, c, 200);
        uncertain.state = legacy(1); ticks(uncertain, c, 200);
        assertTrue(uncertain.events.isEmpty());
        assertTrue(uncertain.owned);
        Fake clean = new Fake(); clean.initial = Ownership.OWNED;
        ClusterAdasLifecycle cleanController = new ClusterAdasLifecycle(clean);
        restore(clean, cleanController);
        assertEquals(40, clean.count(RESTORE_FRAME));
        assertEquals(0, clean.countEvent("op:13"));
    }

    @Test public void noOwnerMeansIdleAndBootRecoveryDoNotChangeHardware() {
        Fake f = new Fake();
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        ticks(f, c, 400);
        assertTrue(f.events.isEmpty());
        assertEquals(0, f.probes);
    }

    @Test public void bootRecoveryWaitsForBothCoreRecoveries() {
        Fake f = new Fake(); f.initial = Ownership.OWNED;
        f.state = new Snapshot(source(0, false, true), source(0, false, true));
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        ticks(f, c, 80);
        f.state = new Snapshot(source(0, false, false), source(0, false, true));
        ticks(f, c, 80);
        assertTrue(f.events.isEmpty());
        f.state = idle(0, 0);
        restore(f, c);
        assertEquals(40, f.count(RESTORE_FRAME));
    }

    @Test public void delayedRepeatIsBoundedToTwoHideBursts() {
        Fake f = new Fake(); f.state = di5(1);
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        ticks(f, c, 2000);
        assertEquals(526, f.packets.size());
        assertEquals(2, f.countEvent("op:13"));
    }

    @Test public void initiallyReadyClusterUsesOnlyTheNormalBoundedFollowup() {
        Fake f = new Fake(); f.state = legacy(1); f.cluster = new ClusterState(3, 17);
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        ticks(f, c, 2000);
        assertEquals(526, f.packets.size());
        assertEquals(2, f.countEvent("op:13"));
    }

    @Test public void powerTransitionRetriesAfterFourSecondsWithoutAPlatformGate() {
        Fake f = new Fake(); f.state = legacy(1);
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        firstHide(f, c);
        f.cluster = new ClusterState(3, 17); // Not a maintenance platform.
        long readyAt = f.now;
        c.tick(f.now);
        f.now = readyAt + 3999L; c.tick(f.now);
        assertEquals(1, f.countEvent("op:13"));
        f.now = readyAt + 4000L; c.tick(f.now);
        assertEquals(2, f.countEvent("op:13"));
        until(f, c, () -> f.packets.size() >= 526);
        ticks(f, c, 2000);
        assertEquals(789, f.packets.size()); // Transition attempt has one follow-up.
        assertEquals(3, f.countEvent("op:13"));
    }

    @Test public void lossOrUnknownPowerCancelsTheDelayedTransitionRetry() {
        for (ClusterState lost : new ClusterState[] {
                new ClusterState(0, 102), ClusterState.UNKNOWN
        }) {
            Fake f = new Fake(); f.state = legacy(1);
            ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
            firstHide(f, c);
            f.cluster = new ClusterState(3, 17);
            long readyAt = f.now;
            c.tick(f.now);
            f.cluster = lost;
            f.now = readyAt + 3999L; c.tick(f.now);
            ticks(f, c, 100);
            assertEquals(1, f.countEvent("op:13"));
            assertEquals(263, f.packets.size());
        }
    }

    @Test public void projectionStopCancelsTheTransitionRetryAndStillRestores() {
        Fake f = new Fake(); f.state = di5(1);
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        firstHide(f, c);
        f.cluster = new ClusterState(3, 17);
        c.tick(f.now);
        f.state = idle(0, 2);
        f.now += 5000L;
        restore(f, c);
        ticks(f, c, 300);
        assertEquals(1, f.countEvent("op:13"));
        assertEquals(40, f.count(RESTORE_FRAME));
    }

    @Test public void sourceChangeCannotRunThePreviousTransitionTimer() {
        Fake f = new Fake(); f.state = legacy(1);
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        firstHide(f, c);
        f.cluster = new ClusterState(3, 17);
        long readyAt = f.now; c.tick(f.now);
        f.state = di5(2);
        until(f, c, () -> f.countEvent("op:13") == 2);
        f.now = readyAt + 5000L;
        ticks(f, c, 20);
        assertEquals(2, f.countEvent("op:13"));
    }

    @Test public void defaultMaintenanceRequiresReadyPowerAndTheVerifiedPlatformSet() {
        for (int platform : new int[] {102, 115, 141, 181}) {
            Fake f = new Fake(); f.state = legacy(1); f.cluster = new ClusterState(3, platform);
            ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
            firstHide(f, c);
            assertEquals(263, f.packets.size()); // No maintenance interleaves the burst.
            ticks(f, c, 80);
            int extra = f.packets.size() - 263;
            assertTrue(extra >= 3);
            assertEquals(0, extra % 3);
            assertEquals(extra + 63, f.count(HIDE_FRAME));
            assertEquals(1, f.countEvent("op:13"));
        }
        for (ClusterState invalid : new ClusterState[] {
                ClusterState.UNKNOWN, new ClusterState(0, 102), new ClusterState(2, 102),
                new ClusterState(3, -1), new ClusterState(3, 101),
                new ClusterState(3, 116), new ClusterState(3, 182),
                new ClusterState(-1, 138), new ClusterState(0, 138),
                new ClusterState(1, 138), new ClusterState(3, 138),
                new ClusterState(2, -1), new ClusterState(2, 137),
                new ClusterState(2, 139), new ClusterState(2, 141)
        }) {
            Fake f = new Fake(); f.state = legacy(1); f.cluster = invalid;
            ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
            firstHide(f, c);
            ticks(f, c, 80);
            assertEquals(263, f.packets.size());
            assertEquals(1, f.countEvent("op:13"));
        }
    }

    @Test public void observedPlatformMaintenanceRefreshesEachSecondForBothPhysicalOwners() {
        for (boolean useDi5 : new boolean[] {false, true}) {
            Fake f = new Fake(); f.state = useDi5 ? di5(1) : legacy(1);
            f.cluster = new ClusterState(2, 138);
            ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
            firstHide(f, c);
            until(f, c, () -> f.packets.size() == 266);
            long completedAt = f.now - 50L;
            f.now = completedAt + 999L; c.tick(f.now);
            assertEquals(266, f.packets.size());
            f.now = completedAt + 1000L;
            until(f, c, () -> f.packets.size() == 269);
            assertTrue(f.now <= completedAt + 1100L);
            assertEquals(1, f.countEvent("op:13"));
            assertEquals(0, f.count(RESTORE_FRAME));
        }
    }

    @Test public void observedPlatformPulsesNeverReplayTheOtherPatternsOrContainerOperations() {
        Fake f = new Fake(); f.state = legacy(1); f.cluster = new ClusterState(2, 138);
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        firstHide(f, c);
        ticks(f, c, 80);
        assertTrue(f.count(HIDE_FRAME) > 63);
        assertEquals(100, f.count("26,F0,00,28,01,06"));
        assertEquals(50, f.count("43,20,00,00,01,01"));
        assertEquals(50, f.count("43,20,00,21,01,01"));
        assertEquals(1, f.countEvent("op:13"));
        assertEquals(1, f.countEvent("prepare"));
        assertEquals(0, f.countEvent("op:12"));
        assertEquals(0, f.countEvent("op:53"));
    }

    @Test public void observedPlatformPropertyLossStopsBetweenIndividualPacketsAndStillRestores() {
        for (ClusterState lost : new ClusterState[] {
                ClusterState.UNKNOWN, new ClusterState(-1, 138), new ClusterState(2, -1),
                new ClusterState(0, 138), new ClusterState(1, 138),
                new ClusterState(3, 138), new ClusterState(2, 137),
                new ClusterState(2, 139)
        }) {
            Fake f = new Fake(); f.state = legacy(1); f.cluster = new ClusterState(2, 138);
            ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
            firstHide(f, c);
            f.afterPacket = () -> f.cluster = lost;
            until(f, c, () -> f.packets.size() > 263);
            ticks(f, c, 80);
            assertEquals(264, f.packets.size());
            assertFalse(f.pending);
            f.afterPacket = null; f.state = idle(2, 0);
            restore(f, c);
            assertEquals(40, f.count(RESTORE_FRAME));
        }
    }

    @Test public void observedPlatformAccLossStopsBetweenIndividualPacketsAndStillRestores() {
        Fake f = new Fake(); f.state = di5(1); f.cluster = new ClusterState(2, 138);
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        firstHide(f, c);
        f.afterPacket = () -> f.powered = false;
        until(f, c, () -> f.packets.size() > 263);
        ticks(f, c, 80);
        assertEquals(264, f.packets.size());
        assertFalse(f.pending);
        f.afterPacket = null; f.state = idle(0, 2);
        restore(f, c);
        assertEquals(40, f.count(RESTORE_FRAME));
    }

    @Test public void observedPlatformProjectionStopCancelsRemainingPacketsAndRestores() {
        for (boolean useDi5 : new boolean[] {false, true}) {
            Fake f = new Fake(); f.state = useDi5 ? di5(1) : legacy(1);
            f.cluster = new ClusterState(2, 138);
            ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
            firstHide(f, c);
            f.afterPacket = () -> f.state = idle(2, 2);
            until(f, c, () -> f.packets.size() > 263);
            f.afterPacket = null;
            restore(f, c);
            assertEquals(64, f.count(HIDE_FRAME));
            assertEquals(40, f.count(RESTORE_FRAME));
            assertEquals(1, f.countEvent("op:12"));
            assertEquals(1, f.countEvent("op:53"));
        }
    }

    @Test public void observedPlatformStopAfterOwnershipWriteCancelsBeforeDispatch() {
        Fake f = new Fake(); f.state = legacy(1); f.cluster = new ClusterState(2, 138);
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        firstHide(f, c);
        f.afterBegin = () -> { f.state = idle(2, 0); f.afterBegin = null; };
        restore(f, c);
        assertEquals(63, f.count(HIDE_FRAME));
        assertEquals(40, f.count(RESTORE_FRAME));
        assertFalse(f.pending);
    }

    @Test public void observedPlatformHandoffCancelsOldPulseWithoutRestoringTheLiveSource() {
        Fake f = new Fake(); f.state = legacy(1); f.cluster = new ClusterState(2, 138);
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        firstHide(f, c);
        f.afterPacket = () -> f.state = di5(2);
        until(f, c, () -> f.packets.size() > 263);
        ticks(f, c, 2);
        assertEquals(264, f.packets.size());
        assertFalse(f.pending);
        f.afterPacket = null;
        until(f, c, () -> f.packets.size() >= 527);
        assertEquals(0, f.count(RESTORE_FRAME));
        assertEquals(2, f.countEvent("op:13"));
        f.state = idle(2, 3);
        restore(f, c);
        assertEquals(40, f.count(RESTORE_FRAME));
    }

    @Test public void observedPlatformPulseRejectionKeepsRetriesBoundedAndPermitsRestoration() {
        Fake f = new Fake(); f.state = legacy(1); f.cluster = new ClusterState(2, 138);
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        firstHide(f, c);
        f.verdict = Result.REJECTED;
        ticks(f, c, 2000);
        assertEquals(263, f.packets.size());
        assertEquals(2, f.countEvent("op:13"));
        assertTrue(f.owned);
        assertFalse(f.pending);
        f.verdict = Result.COMPLETED; f.state = idle(2, 0);
        restore(f, c);
        assertEquals(40, f.count(RESTORE_FRAME));
    }

    @Test public void maintenanceProfileChangeCancelsSelectedWorkUntilTheNewDeadline() {
        for (boolean switchToObserved : new boolean[] {false, true}) {
            Fake f = new Fake(); f.state = legacy(1);
            f.cluster = switchToObserved ? new ClusterState(3, 102) : new ClusterState(2, 138);
            ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
            firstHide(f, c);
            f.now += 4000L;
            c.tick(f.now); // Select a pulse but do not dispatch it yet.
            assertEquals(263, f.packets.size());
            f.cluster = switchToObserved ? new ClusterState(2, 138) : new ClusterState(3, 102);
            long changedAt = f.now;
            long interval = switchToObserved ? 1000L : 3000L;
            c.tick(f.now);
            assertEquals(263, f.packets.size());
            f.now = changedAt + interval - 1L; c.tick(f.now);
            assertEquals(263, f.packets.size());
            f.now = changedAt + interval;
            until(f, c, () -> f.packets.size() == 266);
            assertTrue(f.now <= changedAt + interval + 100L);
            assertEquals(1, f.countEvent("op:13"));
        }
    }

    @Test public void maintenanceProfileChangeBetweenPacketsCancelsTheOldPulse() {
        for (boolean switchToObserved : new boolean[] {false, true}) {
            Fake f = new Fake(); f.state = legacy(1);
            f.cluster = switchToObserved ? new ClusterState(3, 102) : new ClusterState(2, 138);
            ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
            firstHide(f, c);
            f.afterPacket = () -> f.cluster = switchToObserved
                    ? new ClusterState(2, 138) : new ClusterState(3, 102);
            until(f, c, () -> f.packets.size() > 263);
            f.afterPacket = null;
            ticks(f, c, 10);
            assertEquals(264, f.packets.size());
            assertFalse(f.pending);
            f.state = idle(2, 0);
            restore(f, c);
            assertEquals(40, f.count(RESTORE_FRAME));
        }
    }

    @Test public void maintenanceChecksPowerBetweenIndividualPackets() {
        Fake f = new Fake(); f.state = legacy(1); f.cluster = new ClusterState(3, 102);
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        firstHide(f, c);
        f.afterPacket = () -> f.cluster = ClusterState.UNKNOWN;
        until(f, c, () -> f.packets.size() > 263);
        ticks(f, c, 80);
        assertEquals(264, f.packets.size());
        assertFalse(f.pending);
        f.afterPacket = null;
        f.state = idle(2, 0);
        restore(f, c);
        assertEquals(40, f.count(RESTORE_FRAME));
    }

    @Test public void receiverRefreshIsMeasuredFromSuccessfulPreparationCompletion() {
        for (ClusterState profile : new ClusterState[] {
                new ClusterState(3, 102), new ClusterState(2, 138)
        }) {
            Fake f = new Fake(); f.state = di5(1); f.cluster = profile;
            ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
            until(f, c, () -> f.countEvent("op:13") == 2);
            until(f, c, () -> f.logs.stream().filter(s -> s.contains(
                    "hide sequence completed")).count() == 2);
            assertEquals(2, f.countEvent("prepare"));
            long lastPreparedAt = f.lastPreparedAt;
            f.now = lastPreparedAt + 59_999L; c.tick(f.now);
            assertEquals(2, f.countEvent("prepare"));
            f.now = lastPreparedAt + 60_000L;
            until(f, c, () -> f.countEvent("prepare") == 3);
            assertTrue(f.lastPreparedAt >= lastPreparedAt + 60_000L);
            assertTrue(f.lastPreparedAt < lastPreparedAt + 64_000L);
            assertTrue(f.logs.stream().anyMatch(s -> s.contains("receiver refreshed")));
        }
    }

    @Test public void uncertainMaintenanceOutcomePausesFurtherWritesAndRetainsOwnership() {
        for (ClusterState profile : new ClusterState[] {
                new ClusterState(3, 102), new ClusterState(2, 138)
        }) {
            Fake f = new Fake(); f.state = legacy(1); f.cluster = profile;
            ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
            firstHide(f, c);
            f.verdict = Result.UNKNOWN;
            ticks(f, c, 100);
            assertTrue(f.owned && f.pending);
            int eventCount = f.events.size();
            f.state = idle(2, 0); ticks(f, c, 300);
            assertEquals(eventCount, f.events.size());
            assertEquals(0, f.clears);
        }
    }

    @Test public void definiteRejectionHasBoundedRetriesAndRetainsRecoveryOwnership() {
        Fake f = new Fake(); f.state = legacy(1); f.verdict = Result.REJECTED;
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        ticks(f, c, 2000);
        assertEquals(2, f.countEvent("op:13"));
        assertTrue(f.owned);
        assertFalse(f.pending);
        f.verdict = Result.COMPLETED; f.state = idle(2, 0);
        restore(f, c);
    }

    @Test public void markerClearRetryDoesNotReplaySuccessfulRestoration() {
        Fake f = new Fake(); f.initial = Ownership.OWNED; f.clear = false;
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        until(f, c, () -> f.clears == 1);
        f.clear = true;
        restore(f, c);
        assertEquals(40, f.count(RESTORE_FRAME));
        assertEquals(1, f.countEvent("op:12"));
        assertEquals(1, f.countEvent("op:53"));
    }

    @Test public void powerUnknownDefersHideAndPowerLossCancelsFollowingPackets() {
        Fake f = new Fake(); f.state = legacy(1); f.powered = false;
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        ticks(f, c, 100);
        assertEquals(0, f.probes);
        f.powered = true;
        f.afterPacket = () -> { if (f.packets.size() == 4) f.powered = false; };
        until(f, c, () -> f.packets.size() == 4);
        ticks(f, c, 100);
        assertEquals(4, f.packets.size());
        f.state = idle(2, 0); f.afterPacket = null;
        restore(f, c); // Restoration is still attempted after power-off.
    }

    @Test public void protocolDelaysAreMeasuredFromCommandCompletion() {
        Fake f = new Fake(); f.state = legacy(1); f.prepareDelay = 2000L;
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        until(f, c, () -> f.countEvent("prepare") == 1);
        long preparedAt = f.now - 50L;
        f.now = preparedAt + 999L; c.tick(f.now);
        assertTrue(f.packets.isEmpty());
        f.now = preparedAt + 1000L; ticks(f, c, 2);
        assertEquals(3, f.packets.size());
        firstHide(f, c);
        f.releaseDelay = 2000L; f.state = idle(2, 0);
        until(f, c, () -> f.countEvent("op:12") == 1);
        long releasedAt = f.now - 50L;
        f.now = releasedAt + 1199L; c.tick(f.now);
        assertEquals(0, f.countEvent("op:53"));
        f.now = releasedAt + 1200L; ticks(f, c, 2);
        assertEquals(1, f.countEvent("op:53"));
    }

    @Test public void snapshotAndProbeExceptionsStayInsideOptionalWorker() {
        Fake f = new Fake(); f.state = legacy(1); f.throwProbe = true;
        ClusterAdasLifecycle c = new ClusterAdasLifecycle(f);
        ticks(f, c, 100);
        assertTrue(f.events.isEmpty());
        f.throwProbe = false; f.throwSnapshot = true;
        ticks(f, c, 100);
        assertTrue(f.events.isEmpty());
    }

    @Test public void overlayOpcodesCannotAcquireNormalProjectionAuthority() {
        for (int op : new int[] {12, 13, 53}) assertTrue(isOverlayOpcode(op));
        for (int op : new int[] {-1, 0, 16, 18, 35}) assertFalse(isOverlayOpcode(op));
    }
}
