package com.overdrive.app.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure-function contract of the daemon's dump-free AP liveness probe. */
public class HotspotApLivenessTest {

    private static HotspotApLiveness.Iface iface(boolean up, String... v4) {
        return new HotspotApLiveness.Iface(up, Arrays.asList(v4));
    }

    @Test
    public void declaredEnabledAndInterfaceUpWithApAddressIsUp() {
        HotspotApLiveness.Probe p = HotspotApLiveness.probe(
                13, "wlan0", name -> iface(true, "fe80::1", "192.168.43.1"));
        assertTrue(p.apUp);
        assertTrue(p.probeWorked);
        assertTrue(p.detail, p.detail.contains("192.168.43.1"));
    }

    @Test
    public void declaredEnabledButInterfaceMissingIsDownAndProbeWorked() {
        HotspotApLiveness.Probe p = HotspotApLiveness.probe(13, "swlan0", name -> null);
        assertFalse(p.apUp);
        assertTrue(p.probeWorked);
        assertTrue(p.detail, p.detail.contains("absent"));
    }

    @Test
    public void declaredEnabledButInterfaceDownOrWithoutApAddressIsDown() {
        HotspotApLiveness.Probe down = HotspotApLiveness.probe(
                13, "wlan0", name -> iface(false, "192.168.43.1"));
        assertFalse(down.apUp);
        assertTrue(down.probeWorked);

        HotspotApLiveness.Probe noAddress = HotspotApLiveness.probe(
                13, "wlan0", name -> iface(true, "10.0.0.5"));
        assertFalse(noAddress.apUp);
        assertTrue(noAddress.probeWorked);

        HotspotApLiveness.Probe bare = HotspotApLiveness.probe(
                13, "wlan0", name -> new HotspotApLiveness.Iface(true, null));
        assertFalse(bare.apUp);
        assertTrue(bare.probeWorked);
    }

    @Test
    public void declaredDisabledIsDownWithoutConsultingTheInterface() {
        AtomicInteger lookups = new AtomicInteger();
        for (int state : new int[]{10, 11, 14, 0, -1}) {
            HotspotApLiveness.Probe p = HotspotApLiveness.probe(state, "wlan0", name -> {
                lookups.incrementAndGet();
                return iface(true, "192.168.43.1");
            });
            assertFalse("state " + state, p.apUp);
            assertTrue("state " + state, p.probeWorked);
        }
        assertEquals(0, lookups.get());
    }

    @Test
    public void declaredEnablingWithLiveInterfaceCountsAsUp() {
        // An owner still bringing the AP up past the caller's hold-off keeps its AP as long
        // as the interface already shows it; a dead owner never gets the interface up.
        assertTrue(HotspotApLiveness.probe(12, "wlan0", name -> iface(true, "192.168.43.1")).apUp);
        assertFalse(HotspotApLiveness.probe(12, "wlan0", name -> null).apUp);
        assertFalse(HotspotApLiveness.probe(12, "wlan0", name -> iface(false, "192.168.43.1")).apUp);
    }

    @Test
    public void lookupFailureMeansProbeDidNotWork() {
        HotspotApLiveness.Probe p = HotspotApLiveness.probe(13, "wlan0", name -> {
            throw new java.net.SocketException("netlink denied");
        });
        assertFalse(p.apUp);
        assertFalse(p.probeWorked);
        assertTrue(p.detail, p.detail.contains("netlink denied"));
    }

    @Test
    public void lookupIsAskedForTheGivenInterfaceName() {
        StringBuilder asked = new StringBuilder();
        HotspotApLiveness.probe(13, "ap0", name -> {
            asked.append(name);
            return null;
        });
        assertEquals("ap0", asked.toString());
    }

    @Test
    public void productionLookupHandlesEmptyNamesAndRealInterfaces() throws Exception {
        assertNull(HotspotApLiveness.lookupInterface(""));
        assertNull(HotspotApLiveness.lookupInterface(null));
        assertNull(HotspotApLiveness.lookupInterface("no-such-interface-for-this-test"));

        String loopback = null;
        Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
        while (all != null && all.hasMoreElements()) {
            NetworkInterface candidate = all.nextElement();
            if (candidate.isLoopback() && candidate.isUp()) {
                loopback = candidate.getName();
                break;
            }
        }
        assumeTrue("host has no loopback interface", loopback != null);
        HotspotApLiveness.Iface lo = HotspotApLiveness.lookupInterface(loopback);
        assertTrue(lo.up);
        assertTrue(lo.ipv4Addresses.toString(), lo.ipv4Addresses.contains("127.0.0.1"));
        // Loopback is up but is not an access point: declared ENABLED is not enough on its own.
        HotspotApLiveness.Probe p = HotspotApLiveness.probe(13, loopback);
        assertFalse(p.apUp);
        assertTrue(p.probeWorked);
        assertEquals(Collections.emptyList(),
                new HotspotApLiveness.Iface(true, null).ipv4Addresses);
    }
}
