package com.overdrive.app.server;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

import static org.junit.Assert.*;

public class VehiclePowerConfirmationTest {
    private static VehiclePowerConfirmation.Result confirm(boolean on, IntSupplier read,
            AtomicLong clock) throws InterruptedException {
        return VehiclePowerConfirmation.await(on, 1000, read, () -> true,
                clock::get, clock::addAndGet);
    }

    private static IntSupplier sequence(int... values) {
        AtomicInteger reads = new AtomicInteger();
        return () -> values[Math.min(reads.getAndIncrement(), values.length - 1)];
    }

    @Test public void powerOnRequiresTwoLiveOnReadbacksAfterTheTransition() throws Exception {
        AtomicLong clock = new AtomicLong(100);
        VehiclePowerConfirmation.Result result = confirm(true, sequence(0, 0, 2, 3), clock);
        assertTrue(result.confirmed);
        assertEquals(3, result.readback);
        assertEquals(850, clock.get());
    }

    @Test public void powerOffRequiresTwoLiveOffReadbacks() throws Exception {
        VehiclePowerConfirmation.Result result = confirm(false, sequence(2, 0, 0),
                new AtomicLong());
        assertTrue(result.confirmed);
        assertEquals(0, result.readback);
    }

    @Test public void acceptedCommandWithNoPhysicalTransitionRemainsUnconfirmed() throws Exception {
        assertFalse(confirm(true, () -> 0, new AtomicLong()).confirmed);
        assertFalse(confirm(false, () -> 2, new AtomicLong()).confirmed);
    }

    @Test public void accessoryPowerAndUnknownNativeValuesNeverConfirmPowerOn() throws Exception {
        for (int value : new int[]{-1, 0, 1, 4, 255}) {
            assertFalse(confirm(true, () -> value, new AtomicLong()).confirmed);
        }
    }

    @Test public void anUnknownReadBetweenTargetReadsBreaksConfirmation() throws Exception {
        assertFalse(confirm(true, sequence(2, -1, 2, -1), new AtomicLong()).confirmed);
    }

    @Test public void modeChangeBeforeTheSecondReadRetiresTheConfirmation() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicLong clock = new AtomicLong();
        VehiclePowerConfirmation.Result result = VehiclePowerConfirmation.await(true, 1000,
                () -> 2, current::get, clock::get, millis -> {
                    clock.addAndGet(millis);
                    current.set(false);
                });
        assertFalse(result.confirmed);
    }

    @Test public void aGetterCompletingAfterTheDeadlineCannotConfirm() throws Exception {
        AtomicLong clock = new AtomicLong();
        assertFalse(confirm(true, () -> {
            clock.addAndGet(1000);
            return 2;
        }, clock).confirmed);
    }

    @Test public void nativeReadFailureRemainsUnconfirmed() throws Exception {
        assertFalse(confirm(true, () -> { throw new IllegalStateException("HAL unavailable"); },
                new AtomicLong()).confirmed);
    }

    @Test public void interruptionStopsWaitingWithoutAnActuatorRetry() {
        assertThrows(InterruptedException.class, () -> VehiclePowerConfirmation.await(true, 1000,
                () -> 0, () -> true, () -> 0, millis -> {
                    throw new InterruptedException("request cancelled");
                }));
    }
}
