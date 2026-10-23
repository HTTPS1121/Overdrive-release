package com.overdrive.app.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

/** Shared Bluetooth preference migration and cross-page mutation behavior. */
public class BluetoothKeepAlivePolicyTest {
    @Test
    public void freshInstallationHasNoParkedBluetoothOptIn() throws Exception {
        JSONObject config = new JSONObject();
        BluetoothKeepAlivePolicy.ensureCanonical(config);
        assertFalse(BluetoothKeepAlivePolicy.enabled(config));
        assertFalse(config.getJSONObject("surveillance").getBoolean("bluetoothKeepAlive"));
        assertFalse(config.has("remoteAccess"));
    }

    @Test
    public void unusedLegacyDefaultDoesNotBecomeGlobalConsent() throws Exception {
        JSONObject config = legacy(false, true);
        BluetoothKeepAlivePolicy.ensureCanonical(config);
        assertFalse(BluetoothKeepAlivePolicy.enabled(config));
        assertFalse(config.getJSONObject("remoteAccess").getBoolean("keepBluetoothOn"));
    }

    @Test
    public void enabledLegacyProximityRetainsItsRadioOptIn() throws Exception {
        JSONObject config = legacy(true, true);
        BluetoothKeepAlivePolicy.ensureCanonical(config);
        assertTrue(BluetoothKeepAlivePolicy.enabled(config));
        assertTrue(config.getJSONObject("remoteAccess").getBoolean("keepBluetoothOn"));
    }

    @Test
    public void enabledLegacyProximityWithoutNewerFieldRetainsItsRadio() throws Exception {
        JSONObject config = new JSONObject().put("remoteAccess",
                new JSONObject().put("enabled", true));
        BluetoothKeepAlivePolicy.ensureCanonical(config);
        assertTrue(BluetoothKeepAlivePolicy.enabled(config));
    }

    @Test
    public void legacyExplicitOffRemainsOff() throws Exception {
        JSONObject config = legacy(true, false);
        BluetoothKeepAlivePolicy.ensureCanonical(config);
        assertFalse(BluetoothKeepAlivePolicy.enabled(config));
    }

    @Test
    public void canonicalOffWinsOverLegacyOptInAndStaysOffAcrossReloads() throws Exception {
        JSONObject config = legacy(true, true).put("surveillance",
                new JSONObject().put("bluetoothKeepAlive", false));
        BluetoothKeepAlivePolicy.ensureCanonical(config);
        assertFalse(BluetoothKeepAlivePolicy.enabled(config));
        config.getJSONObject("remoteAccess").put("keepBluetoothOn", true);
        BluetoothKeepAlivePolicy.ensureCanonical(config);
        assertFalse(config.getJSONObject("remoteAccess").getBoolean("keepBluetoothOn"));
    }

    @Test
    public void canonicalOnAlsoAppliesWhenProximityMasterIsOff() throws Exception {
        JSONObject config = legacy(false, false).put("surveillance",
                new JSONObject().put("bluetoothKeepAlive", true));
        BluetoothKeepAlivePolicy.ensureCanonical(config);
        assertTrue(BluetoothKeepAlivePolicy.enabled(config));
        assertTrue(config.getJSONObject("remoteAccess").getBoolean("keepBluetoothOn"));
        assertFalse(config.getJSONObject("remoteAccess").getBoolean("enabled"));
    }

    @Test
    public void malformedPersistedPreferenceFailsClosed() throws Exception {
        JSONObject config = legacy(true, true).put("surveillance",
                new JSONObject().put("bluetoothKeepAlive", "true"));
        BluetoothKeepAlivePolicy.ensureCanonical(config);
        assertFalse(BluetoothKeepAlivePolicy.enabled(config));
        JSONObject badLegacy = new JSONObject().put("remoteAccess",
                new JSONObject().put("enabled", "true").put("keepBluetoothOn", true));
        assertFalse(BluetoothKeepAlivePolicy.enabled(badLegacy));
    }

    @Test
    public void surveillanceWriteImmediatelyMirrorsExistingProximityAlias() throws Exception {
        JSONObject config = legacy(true, true);
        BluetoothKeepAlivePolicy.ensureCanonical(config);
        config.getJSONObject("surveillance").put("bluetoothKeepAlive", false);
        BluetoothKeepAlivePolicy.synchronizeSectionWrite(config, "surveillance",
                Collections.singleton("bluetoothKeepAlive"));
        assertFalse(BluetoothKeepAlivePolicy.enabled(config));
        assertFalse(config.getJSONObject("remoteAccess").getBoolean("keepBluetoothOn"));
        assertTrue(config.getJSONObject("remoteAccess").getBoolean("enabled"));
    }

    @Test
    public void explicitLegacyPageWriteUpdatesCanonicalPreference() throws Exception {
        JSONObject config = legacy(true, false);
        BluetoothKeepAlivePolicy.ensureCanonical(config);
        config.getJSONObject("remoteAccess").put("keepBluetoothOn", true);
        BluetoothKeepAlivePolicy.synchronizeSectionWrite(config, "remoteAccess",
                Collections.singleton("keepBluetoothOn"));
        assertTrue(config.getJSONObject("surveillance").getBoolean("bluetoothKeepAlive"));
    }

    @Test
    public void skippedOrUnrelatedLegacyMutationCannotRestoreDisabledRadio() throws Exception {
        JSONObject config = legacy(true, false);
        BluetoothKeepAlivePolicy.ensureCanonical(config);
        config.getJSONObject("remoteAccess").put("keepBluetoothOn", true);
        BluetoothKeepAlivePolicy.synchronizeSectionWrite(config, "remoteAccess",
                Collections.singleton("deviceName"));
        assertFalse(BluetoothKeepAlivePolicy.enabled(config));
        assertFalse(config.getJSONObject("remoteAccess").getBoolean("keepBluetoothOn"));
    }

    @Test
    public void radioKeysRequireActualBooleansAndUnrelatedSectionsAreUnaffected()
            throws Exception {
        for (String section : new String[] {"surveillance", "remoteAccess"}) {
            String key = section.equals("surveillance")
                    ? "bluetoothKeepAlive" : "keepBluetoothOn";
            assertTrue(BluetoothKeepAlivePolicy.validPatch(section,
                    new JSONObject().put(key, true)));
            assertTrue(BluetoothKeepAlivePolicy.validPatch(section,
                    new JSONObject().put(key, false)));
            for (Object value : new Object[] {"true", 1, JSONObject.NULL}) {
                assertFalse(BluetoothKeepAlivePolicy.validPatch(section,
                        new JSONObject().put(key, value)));
            }
        }
        assertTrue(BluetoothKeepAlivePolicy.validPatch("recording",
                new JSONObject().put("keepBluetoothOn", "other feature")));
    }

    private static JSONObject legacy(boolean enabled, boolean keepBluetoothOn) throws Exception {
        return new JSONObject().put("remoteAccess", new JSONObject()
                .put("enabled", enabled).put("keepBluetoothOn", keepBluetoothOn));
    }
}
