package com.overdrive.app.config;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/** Checks ordering without writing configuration or starting an Android service. */
public class BluetoothKeepAliveMutationClockTest {
    @Test
    public void legacyAndCanonicalAliasesSeeTheSameAcceptedMutation() throws Exception {
        JSONObject config = new JSONObject();
        record(config, "remoteAccess", "keepBluetoothOn", stamp("page", 20L));
        assertEquals(20L, sequence(config, "remoteAccess", "keepBluetoothOn",
                stamp("page", 19L)));
        assertEquals(20L, sequence(config, "surveillance", "bluetoothKeepAlive",
                stamp("page", 19L)));
    }

    @Test
    public void newerSurveillanceOffDominatesDelayedProximityWrite() throws Exception {
        JSONObject config = new JSONObject();
        record(config, "remoteAccess", "keepBluetoothOn", stamp("page", 10L));
        record(config, "surveillance", "bluetoothKeepAlive", stamp("page", 11L));
        assertEquals(11L, sequence(config, "remoteAccess", "keepBluetoothOn",
                stamp("page", 10L)));
    }

    @Test
    public void unrelatedKeysAndOtherOriginsDoNotShareTheBluetoothClock() throws Exception {
        JSONObject config = new JSONObject();
        record(config, "remoteAccess", "deviceName", stamp("page", 50L));
        assertEquals(0L, sequence(config, "surveillance", "bluetoothKeepAlive",
                stamp("page", 1L)));
        record(config, "surveillance", "bluetoothKeepAlive", stamp("page", 51L));
        assertEquals(0L, sequence(config, "remoteAccess", "deviceName",
                stamp("page", 1L)));
        assertEquals(0L, sequence(config, "remoteAccess", "keepBluetoothOn",
                stamp("automation", 1L)));
    }

    private static Object stamp(String origin, long sequence) throws Exception {
        Constructor<?> constructor = stampType().getDeclaredConstructor(String.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(origin, sequence);
    }

    private static Class<?> stampType() throws Exception {
        return Class.forName("com.overdrive.app.config.UnifiedConfigManager$MutationStamp");
    }

    private static void record(JSONObject config, String section, String key, Object stamp)
            throws Exception {
        Method record = UnifiedConfigManager.class.getDeclaredMethod(
                "recordSharedBluetoothMutation", JSONObject.class, String.class,
                List.class, stampType());
        record.setAccessible(true);
        record.invoke(UnifiedConfigManager.INSTANCE, config, section,
                Collections.singletonList(key), stamp);
    }

    private static long sequence(JSONObject config, String section, String key, Object stamp)
            throws Exception {
        Method sequence = UnifiedConfigManager.class.getDeclaredMethod(
                "sharedBluetoothMutationSequence", JSONObject.class, String.class,
                String.class, stampType());
        sequence.setAccessible(true);
        return ((Number) sequence.invoke(UnifiedConfigManager.INSTANCE,
                config, section, key, stamp)).longValue();
    }
}
