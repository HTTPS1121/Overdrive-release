package com.overdrive.app.abrp;

import static org.junit.Assert.assertEquals;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/** Vehicle SOH remains available before the capacity estimator has been created. */
public class CanonicalSohAvailabilityTest {

    @Test
    public void vehicleReadingIsAvailableWithoutAnEstimatorOrNominalCapacity() throws Exception {
        for (int raw : new int[]{1, 97, 100}) {
            assertEquals(raw, readWithoutEstimator(
                    true, new BydVehicleData.Builder().sohPercent(raw).build()), 0.0);
        }
    }

    @Test
    public void uninitializedCollectorCannotSupplyAPreviousVehicleReading() throws Exception {
        assertEquals(-1.0, readWithoutEstimator(
                false, new BydVehicleData.Builder().sohPercent(97).build()), 0.0);
    }

    @Test
    public void unavailableOrInvalidVehicleReadingStaysUnavailableWithoutAnEstimator()
            throws Exception {
        assertEquals(-1.0, readWithoutEstimator(true, null), 0.0);
        for (double raw : new double[]{Double.NaN, 0, -999, 101}) {
            assertEquals(-1.0, readWithoutEstimator(
                    true, new BydVehicleData.Builder().sohPercent(raw).build()), 0.0);
        }
    }

    private static double readWithoutEstimator(
            boolean initialized, BydVehicleData snapshot) throws Exception {
        Field instance = BydDataCollector.class.getDeclaredField("instance");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        try {
            Constructor<BydDataCollector> constructor =
                    BydDataCollector.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            BydDataCollector collector = constructor.newInstance();
            Field initializedField = BydDataCollector.class.getDeclaredField("initialized");
            initializedField.setAccessible(true);
            initializedField.set(collector, initialized);
            Field snapshotField = BydDataCollector.class.getDeclaredField("snapshot");
            snapshotField.setAccessible(true);
            @SuppressWarnings("unchecked")
            AtomicReference<BydVehicleData> snapshots =
                    (AtomicReference<BydVehicleData>) snapshotField.get(collector);
            snapshots.set(snapshot);
            instance.set(null, collector);

            return SohEstimator.getDisplaySohOrOem(null);
        } finally {
            instance.set(null, previous);
        }
    }
}
