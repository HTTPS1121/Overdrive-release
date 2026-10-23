package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Exercises the actual device read cascade, including its direct instrument channel. */
public class OemSohReadTest {

    @Test
    public void directInstrumentReadingWinsWithoutScalingOrCompatibilityReads() throws Exception {
        List<String> calls = new ArrayList<>();
        InstrumentDevice instrument = new InstrumentDevice(calls, 87);
        StatisticDevice statistic = new StatisticDevice(calls, 96, 95, 94);
        HealthDevice health = new HealthDevice(calls, 93);

        assertEquals(87.0, collect(instrument, statistic, health).sohPercent, 0.0);
        assertEquals(Arrays.asList("instrument:1000:44400028"), calls);
    }

    @Test
    public void inheritedInstrumentGetterWorksWithoutOtherDevices() throws Exception {
        List<String> calls = new ArrayList<>();

        assertEquals(91.0,
                collect(new InheritedInstrumentDevice(calls, 91), null, null).sohPercent, 0.0);
        assertEquals(Arrays.asList("instrument:1000:44400028"), calls);
    }

    @Test
    public void invalidInstrumentValuesAllowStatisticFallback() throws Exception {
        for (int raw : new int[]{0, -999, 101, 65_535}) {
            List<String> calls = new ArrayList<>();

            assertEquals(92.0, collect(
                    new InstrumentDevice(calls, raw),
                    new StatisticDevice(calls, 92, 95, 94), null).sohPercent, 0.0);
            assertEquals(Arrays.asList(
                    "instrument:1000:44400028", "statistic:named"), calls);
        }
    }

    @Test
    public void instrumentFailureAllowsStatisticFallback() throws Exception {
        List<String> calls = new ArrayList<>();

        assertEquals(92.0, collect(
                new ThrowingInstrumentDevice(calls),
                new StatisticDevice(calls, 92, 95, 94), null).sohPercent, 0.0);
        assertEquals(Arrays.asList(
                "instrument:1000:44400028", "statistic:named"), calls);
    }

    @Test
    public void nonIntegerInstrumentResultDoesNotBecomeAnInventedPercentage() throws Exception {
        List<String> calls = new ArrayList<>();

        assertEquals(92.0, collect(
                new InstrumentDevice(calls, 87.0),
                new StatisticDevice(calls, 92, 95, 94), null).sohPercent, 0.0);
        assertEquals(Arrays.asList(
                "instrument:1000:44400028", "statistic:named"), calls);
    }

    @Test
    public void statisticTypedChannelStillWorksAfterUnavailableNamedGetter() throws Exception {
        List<String> calls = new ArrayList<>();

        assertEquals(95.0, collect(
                new InstrumentDevice(calls, 0),
                new StatisticDevice(calls, 0, 95, 94), null).sohPercent, 0.0);
        assertEquals(Arrays.asList(
                "instrument:1000:44400028", "statistic:named",
                "statistic:1014"), calls);
    }

    @Test
    public void statisticGenericFallbackRequestsIntegerBeforeExtraction() throws Exception {
        List<String> calls = new ArrayList<>();

        assertEquals(94.0, collect(
                new InstrumentDevice(calls, 0),
                new StatisticDevice(calls, 0, 0, 94), null).sohPercent, 0.0);
        assertEquals(Arrays.asList(
                "instrument:1000:44400028", "statistic:named",
                "statistic:1014", "statistic:int"), calls);
    }

    @Test
    public void vehicleHealthRemainsTheFinalCompatibilityFallback() throws Exception {
        List<String> calls = new ArrayList<>();

        assertEquals(93.0, collect(
                new InstrumentDevice(calls, 0),
                new StatisticDevice(calls, 0, 0, 0),
                new HealthDevice(calls, 93)).sohPercent, 0.0);
        assertEquals(Arrays.asList(
                "instrument:1000:44400028", "statistic:named",
                "statistic:1014", "statistic:int", "health"), calls);
    }

    @Test
    public void failedCollectionClearsThePreviousReading() throws Exception {
        List<String> calls = new ArrayList<>();
        BydVehicleData.Builder builder = new BydVehicleData.Builder().sohPercent(97);

        collect(new InstrumentDevice(calls, 0),
                new StatisticDevice(calls, 0, 0, 0),
                new HealthDevice(calls, 0), builder);

        assertTrue(Double.isNaN(builder.build().sohPercent));
    }

    @Test
    public void missingDevicesClearThePreviousReading() throws Exception {
        BydVehicleData.Builder builder = new BydVehicleData.Builder().sohPercent(97);

        collect(null, null, null, builder);

        assertTrue(Double.isNaN(builder.build().sohPercent));
    }

    public static class InstrumentDevice {
        private final List<String> calls;
        private final Object value;

        InstrumentDevice(List<String> calls, Object value) {
            this.calls = calls;
            this.value = value;
        }

        public Object get(int deviceType, int featureId) {
            assertEquals(1000, deviceType);
            assertEquals(0x44400028, featureId);
            calls.add("instrument:" + deviceType + ":" + Integer.toHexString(featureId));
            return value;
        }

        public Object get(int[] featureIds, Class<?> type) {
            throw new AssertionError("the instrument read must use get(int,int)");
        }
    }

    public static final class InheritedInstrumentDevice extends InstrumentDevice {
        InheritedInstrumentDevice(List<String> calls, Object value) {
            super(calls, value);
        }
    }

    public static final class ThrowingInstrumentDevice extends InstrumentDevice {
        ThrowingInstrumentDevice(List<String> calls) {
            super(calls, 0);
        }

        @Override
        public Object get(int deviceType, int featureId) {
            super.get(deviceType, featureId);
            throw new IllegalStateException("property unavailable");
        }
    }

    public static final class StatisticDevice {
        private final List<String> calls;
        private final int named;
        private final int typed;
        private final int generic;

        StatisticDevice(List<String> calls, int named, int typed, int generic) {
            this.calls = calls;
            this.named = named;
            this.typed = typed;
            this.generic = generic;
        }

        public int getStatisticBatteryHealthyIndex() {
            calls.add("statistic:named");
            return named;
        }

        public int get(int deviceType, int featureId) {
            assertEquals(1014, deviceType);
            assertEquals(BydFeatureIds.STAT_BATTERY_HEALTHY_INDEX, featureId);
            calls.add("statistic:" + deviceType);
            return typed;
        }

        public Object get(int[] featureIds, Class<?> type) {
            assertEquals(1, featureIds.length);
            assertEquals(BydFeatureIds.STAT_BATTERY_HEALTHY_INDEX, featureIds[0]);
            assertEquals(Integer.TYPE, type);
            calls.add("statistic:int");
            EventValue value = new EventValue();
            value.intValue = generic;
            return value;
        }
    }

    public static final class EventValue {
        public int intValue;
        public double doubleValue;
    }

    public static final class HealthDevice {
        private final List<String> calls;
        private final int value;

        HealthDevice(List<String> calls, int value) {
            this.calls = calls;
            this.value = value;
        }

        public int getBatteryHealthStatus() {
            calls.add("health");
            return value;
        }
    }

    private static BydVehicleData collect(
            Object instrument, Object statistic, Object health) throws Exception {
        BydVehicleData.Builder builder = new BydVehicleData.Builder();
        collect(instrument, statistic, health, builder);
        return builder.build();
    }

    private static void collect(
            Object instrument, Object statistic, Object health,
            BydVehicleData.Builder builder) throws Exception {
        Constructor<BydDataCollector> constructor =
                BydDataCollector.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        BydDataCollector collector = constructor.newInstance();
        setDevice(collector, "instrumentDevice", instrument);
        setDevice(collector, "statisticDevice", statistic);
        setDevice(collector, "vehicleHealthDevice", health);
        Method collect = BydDataCollector.class.getDeclaredMethod(
                "collectStatisticExtended", BydVehicleData.Builder.class);
        collect.setAccessible(true);
        collect.invoke(collector, builder);
    }

    private static void setDevice(
            BydDataCollector collector, String name, Object device) throws Exception {
        Field field = BydDataCollector.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(collector, device);
    }
}
