package com.overdrive.app.byd;

import static org.junit.Assert.*;

import com.overdrive.app.byd.routing.VehicleCommandRouter;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class VehicleActuatorBridgeRemoteKeyCancellationTest {
    @Test public void manualAndLegacyCallsDoNotAcquireACancellationLease() {
        assertFalse(VehicleActuatorBridge.isRemoteKeyRequestCancelled());
        assertFalse(VehicleActuatorBridge.isRemoteKeyRequestExpired());
    }

    @Test public void nestedRequestRestoresItsOwningCancellationScope() throws Exception {
        LocalActuationCancellation outer = new LocalActuationCancellation();
        LocalActuationCancellation inner = new LocalActuationCancellation();
        outer.cancel();
        VehicleActuatorBridge.withRemoteKeyRequestCancellation(outer, () -> {
            assertTrue(VehicleActuatorBridge.isRemoteKeyRequestCancelled());
            assertTrue(VehicleActuatorBridge.isRemoteKeyRequestExpired());
            VehicleActuatorBridge.withRemoteKeyRequestCancellation(inner, () -> {
                assertFalse(VehicleActuatorBridge.isRemoteKeyRequestCancelled());
                inner.cancel();
                assertTrue(VehicleActuatorBridge.isRemoteKeyRequestCancelled());
                return null;
            });
            assertTrue(VehicleActuatorBridge.isRemoteKeyRequestCancelled());
            return null;
        });
        assertFalse(VehicleActuatorBridge.isRemoteKeyRequestCancelled());
    }

    @Test public void exceptionAndAnotherThreadCannotLeakCancellationScope() throws Exception {
        LocalActuationCancellation cancelled = new LocalActuationCancellation();
        cancelled.cancel();
        try {
            VehicleActuatorBridge.withRemoteKeyRequestCancellation(cancelled, () -> {
                FutureTask<Boolean> other = new FutureTask<>(
                        VehicleActuatorBridge::isRemoteKeyRequestCancelled);
                Thread worker = new Thread(other, "scope-isolation");
                worker.setDaemon(true);
                worker.start();
                assertFalse(other.get(2, TimeUnit.SECONDS));
                throw new IllegalStateException("test");
            });
            fail("expected test exception");
        } catch (IllegalStateException expected) {
            assertEquals("test", expected.getMessage());
        }
        assertFalse(VehicleActuatorBridge.isRemoteKeyRequestCancelled());
    }

    @Test public void typedDi5DoorsKeepTheirExistingAdmissionAndOutcomePath() {
        assertFalse(VehicleActuatorBridge.usesGenericRemoteKeyFrame(
                new VehicleCommandRouter.LockCommand()));
        assertFalse(VehicleActuatorBridge.usesGenericRemoteKeyFrame(
                new VehicleCommandRouter.UnlockCommand()));
        assertTrue(VehicleActuatorBridge.usesGenericRemoteKeyFrame(
                new VehicleCommandRouter.VehiclePowerOnCommand()));
        assertTrue(VehicleActuatorBridge.usesGenericRemoteKeyFrame(
                new VehicleCommandRouter.VehiclePowerOffCommand()));
        assertTrue(VehicleActuatorBridge.usesGenericRemoteKeyFrame(
                new VehicleCommandRouter.FindCarCommand()));
        assertTrue(VehicleActuatorBridge.usesGenericRemoteKeyFrame(
                new VehicleCommandRouter.FlashLightsCommand()));
    }
}
