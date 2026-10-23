package com.overdrive.app.monitor;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BluetoothRadioStateTest {
    @Test public void aSuccessfulNativeOffReplyIsDifferentFromAnIpcFailure() throws Exception {
        JSONObject off = BluetoothRadioState.readFrom(() -> 10, true, 1000, 1000);
        assertEquals(10, off.getInt("state"));
        JSONObject failed = BluetoothRadioState.readFrom(
                () -> { throw new IllegalStateException("RemoteException"); }, true, 1000, 1000);
        assertEquals(-1, failed.getInt("state"));
        assertTrue(failed.getBoolean("available"));
    }

    @Test public void invalidNativeRepliesRemainUnknown() throws Exception {
        Object[] invalid = {null, true, "10", -1, 0, 9, 17, 1000, Double.NaN,
                Double.POSITIVE_INFINITY, 10.5, 4_294_967_306L};
        for (Object value : invalid)
            assertEquals(-1, BluetoothRadioState.readFrom(() -> value, true, 1000, 1000).getInt("state"));
    }

    @Test public void missingHardwareDoesNotReadTheManager() throws Exception {
        JSONObject unavailable = BluetoothRadioState.readFrom(
                () -> { throw new AssertionError("Missing adapter must not read"); }, false, 1000, 1000);
        assertFalse(unavailable.getBoolean("available"));
        assertEquals(-1, unavailable.getInt("state"));
    }

    @Test public void successfulLowEnergyOnlyStatesMatchClassicAdapterOff() throws Exception {
        for (int value : new int[] {14, 15, 16})
            assertEquals(10, BluetoothRadioState.readFrom(() -> value, true, 1000, 1000).getInt("state"));
    }

    @Test public void offIsObservedEvenWhenNoPhoneIsConnected() throws Exception {
        BluetoothRadioState.Store store = new BluetoothRadioState.Store();
        assertTrue(store.accept(BluetoothRadioState.observation(10, true, 8000, 1000), 1000));
        JSONObject state = store.snapshot(1000);
        assertEquals("off", state.getString("status"));
        assertTrue(state.getBoolean("available"));
        assertTrue(state.getBoolean("fresh"));
        assertFalse(state.getBoolean("enabled"));
        assertEquals(10, state.getInt("state"));
    }

    @Test public void nativeTransitionsRetainTheirOwnState() throws Exception {
        String[] expected = {"off", "turning_on", "on", "turning_off"};
        for (int nativeState = 10; nativeState <= 13; nativeState++) {
            BluetoothRadioState.Store store = new BluetoothRadioState.Store();
            assertTrue(store.accept(BluetoothRadioState.observation(nativeState, true, 100, 100), 100));
            JSONObject state = store.snapshot(100);
            assertEquals(expected[nativeState - 10], state.getString("status"));
            assertEquals(nativeState == 12, state.getBoolean("enabled"));
        }
    }

    @Test public void unknownAndMissingAdaptersDoNotPretendToBeOff() throws Exception {
        for (boolean available : new boolean[] {false, true}) {
            BluetoothRadioState.Store store = new BluetoothRadioState.Store();
            assertTrue(store.accept(BluetoothRadioState.observation(-1, available, 100, 100), 100));
            JSONObject state = store.snapshot(100);
            assertEquals(available ? "unknown" : "unavailable", state.getString("status"));
            assertTrue(state.isNull("enabled"));
            assertEquals(-1, state.getInt("state"));
        }
    }

    @Test public void neverObservedStateIsUnknown() throws Exception {
        JSONObject state = new BluetoothRadioState.Store().snapshot(500);
        assertEquals("unknown", state.getString("status"));
        assertFalse(state.getBoolean("fresh"));
        assertTrue(state.isNull("updatedAtMs"));
        assertTrue(state.isNull("ageMs"));
        assertTrue(state.isNull("enabled"));
    }

    @Test public void anOldOnObservationBecomesStaleAndEnabledUnknown() throws Exception {
        BluetoothRadioState.Store store = new BluetoothRadioState.Store();
        assertTrue(store.accept(BluetoothRadioState.observation(12, true, 1000, 1000), 1000));
        assertTrue(store.snapshot(1000 + BluetoothRadioState.MAX_AGE_MS).getBoolean("fresh"));
        JSONObject stale = store.snapshot(1001 + BluetoothRadioState.MAX_AGE_MS);
        assertEquals("stale", stale.getString("status"));
        assertEquals("on", stale.getString("lastStatus"));
        assertFalse(stale.getBoolean("fresh"));
        assertTrue(stale.isNull("enabled"));
        assertEquals(1000, stale.getLong("updatedAtMs"));
    }

    @Test public void ageUsesMonotonicObservationTime() throws Exception {
        BluetoothRadioState.Store store = new BluetoothRadioState.Store();
        assertTrue(store.accept(BluetoothRadioState.observation(10, true, 9_000_000, 1000), 1000));
        assertEquals(2400, store.snapshot(3400).getLong("ageMs"));
    }

    @Test public void invalidAndReplayedObservationsCannotReplaceCurrentTruth() throws Exception {
        BluetoothRadioState.Store store = new BluetoothRadioState.Store();
        assertTrue(store.accept(BluetoothRadioState.observation(10, true, 100, 1000), 1000));
        assertFalse(store.accept(BluetoothRadioState.observation(12, true, 100, 999), 1000));
        assertFalse(store.accept(BluetoothRadioState.observation(12, true, 100, 1001), 1000));
        assertFalse(store.accept(BluetoothRadioState.observation(12, true, 100, 0), 20_000));
        assertFalse(store.accept(new JSONObject().put("state", 10).put("available", "true")
                .put("updatedAtMs", 100).put("observedAtElapsedMs", 1000), 1000));
        assertFalse(store.accept(new JSONObject().put("state", 4_294_967_306L).put("available", true)
                .put("updatedAtMs", 100).put("observedAtElapsedMs", 1000), 1000));
        assertFalse(store.accept(new JSONObject().put("state", 10).put("available", false)
                .put("updatedAtMs", 100).put("observedAtElapsedMs", 1000), 1000));
        assertEquals("off", store.snapshot(1000).getString("status"));
    }
}
