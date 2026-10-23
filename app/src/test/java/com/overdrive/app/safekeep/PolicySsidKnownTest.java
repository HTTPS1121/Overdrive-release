package com.overdrive.app.safekeep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.monitor.NetworkMonitor;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

/**
 * {@code Policy.atHome}: a network name the head unit cannot read (the shared placeholder,
 * "", or an "unknown" marker) defers to LAN reachability; a readable foreign name never does.
 * Pure JVM: {@code NetworkMonitor.SSID_UNKNOWN_PLACEHOLDER} is a compile-time constant.
 */
public class PolicySsidKnownTest {

    private static SafeKeepConfig config(String... homeSsids) throws Exception {
        JSONObject limits = new JSONObject().put("homeSsids", new JSONArray(Arrays.asList(homeSsids)));
        JSONObject root = new JSONObject()
                .put(SafeKeepConfig.SECTION, new JSONObject().put("limits", limits));
        return SafeKeepConfig.from(root);
    }

    private static UploadSignals.Snap snap(String ssid) {
        UploadSignals.Snap s = new UploadSignals.Snap();
        s.ssid = ssid;
        return s;
    }

    @Test
    public void emptyHomeListMeansEveryUnmeteredLinkIsHome() throws Exception {
        SafeKeepConfig cfg = config();
        assertTrue(Policy.atHome(cfg, snap("Anything"), false));
        assertTrue(Policy.atHome(cfg, snap(""), false));
        assertTrue(Policy.atHome(cfg, snap(NetworkMonitor.SSID_UNKNOWN_PLACEHOLDER), false));
    }

    @Test
    public void placeholderSsidDefersToLanReachability() throws Exception {
        SafeKeepConfig cfg = config("Home");
        assertTrue(Policy.atHome(cfg, snap(NetworkMonitor.SSID_UNKNOWN_PLACEHOLDER), true));
        assertFalse(Policy.atHome(cfg, snap(NetworkMonitor.SSID_UNKNOWN_PLACEHOLDER), false));
    }

    @Test
    public void thePlaceholderIsTheOneSharedConstant() throws Exception {
        // Policy must recognise exactly what NetworkMonitor publishes; the literal is pinned
        // so a change on either side shows up here, not as a silent upload-policy change.
        assertEquals("WiFi", NetworkMonitor.SSID_UNKNOWN_PLACEHOLDER);
        SafeKeepConfig cfg = config("Home");
        assertTrue(Policy.atHome(cfg, snap("WiFi"), true));
        assertFalse(Policy.atHome(cfg, snap("WiFi"), false));
    }

    @Test
    public void emptyAndUnknownMarkersAlsoDeferToLan() throws Exception {
        SafeKeepConfig cfg = config("Home");
        assertTrue(Policy.atHome(cfg, snap(""), true));
        assertFalse(Policy.atHome(cfg, snap(""), false));
        assertTrue(Policy.atHome(cfg, snap("<unknown ssid>"), true));
        assertTrue(Policy.atHome(cfg, snap("Unknown-Guest"), true));
        assertFalse(Policy.atHome(cfg, snap("<unknown ssid>"), false));
    }

    @Test
    public void readableForeignSsidIsNotHomeEvenWithLanAnswering() throws Exception {
        SafeKeepConfig cfg = config("Home");
        assertFalse(Policy.atHome(cfg, snap("Cafe"), true));
        assertFalse(Policy.atHome(cfg, snap("Cafe"), false));
    }

    @Test
    public void matchingHomeSsidIsHomeRegardlessOfLan() throws Exception {
        SafeKeepConfig cfg = config("Home", "Garage");
        assertTrue(Policy.atHome(cfg, snap("Home"), false));
        assertTrue(Policy.atHome(cfg, snap("\"Garage\""), false));
        assertTrue(Policy.atHome(cfg, snap(" Home "), false));
    }

    @Test
    public void phoneHotspotIsNeverHome() throws Exception {
        UploadSignals.Snap s = snap("Home");
        s.phoneHotspot = true;
        assertFalse(Policy.atHome(config("Home"), s, true));
        assertFalse(Policy.atHome(config(), s, true));
    }
}
