package com.overdrive.app.network;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Daemon-side liveness check for the hotspot access point that needs no framework dump.
 *
 * <p>The app process publishes the framework's AP state ({@code hotspotState.apState};
 * 13 = ENABLED, 12 = ENABLING) and the tethered interface name. This class adds the
 * kernel's view of that interface, readable from the shell uid through
 * {@link NetworkInterface}: it must be up and carry an IPv4 address in {@code 192.168.*},
 * the same test {@code HotspotManager} applies to locate the AP subnet.
 *
 * <p>{@code apUp} is the conjunction "declared up-or-coming-up AND interface alive".
 * ENABLING counts as declared because an owner that is still bringing the AP up past the
 * caller's hold-off must not have its AP torn down while the interface already shows it;
 * an owner that died mid-enable never gets the interface up, so the stale marker still
 * clears. {@code probeWorked} is false only when the interface lookup itself threw, which
 * callers treat as "cannot tell" and keep the current state (fail-recoverable).
 */
public final class HotspotApLiveness {

    /** {@code WifiManager.WIFI_AP_STATE_ENABLING}. */
    public static final int AP_STATE_ENABLING = 12;
    /** {@code WifiManager.WIFI_AP_STATE_ENABLED}. */
    public static final int AP_STATE_ENABLED = 13;
    static final String AP_IPV4_PREFIX = "192.168.";

    /** Outcome of one probe. */
    public static final class Probe {
        public final boolean apUp;
        public final boolean probeWorked;
        /** Short reason for logs; never null. */
        public final String detail;

        Probe(boolean apUp, boolean probeWorked, String detail) {
            this.apUp = apUp;
            this.probeWorked = probeWorked;
            this.detail = detail == null ? "" : detail;
        }
    }

    /** Kernel facts about one interface. A lookup returning null means "no such interface". */
    public static final class Iface {
        public final boolean up;
        public final List<String> ipv4Addresses;

        public Iface(boolean up, List<String> ipv4Addresses) {
            this.up = up;
            this.ipv4Addresses = ipv4Addresses == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(ipv4Addresses));
        }
    }

    /** Lookup seam; production wraps {@link NetworkInterface#getByName(String)}. */
    @FunctionalInterface
    public interface IfaceLookup {
        Iface lookup(String name) throws Exception;
    }

    private HotspotApLiveness() {}

    public static Probe probe(int declaredApState, String iface) {
        return probe(declaredApState, iface, HotspotApLiveness::lookupInterface);
    }

    static Probe probe(int declaredApState, String iface, IfaceLookup lookup) {
        if (declaredApState != AP_STATE_ENABLED && declaredApState != AP_STATE_ENABLING) {
            return new Probe(false, true, "declared apState=" + declaredApState);
        }
        Iface facts;
        try {
            facts = lookup.lookup(iface);
        } catch (Throwable t) {
            return new Probe(false, false, "interface lookup failed: " + t);
        }
        if (facts == null) {
            return new Probe(false, true, iface + " absent");
        }
        if (!facts.up) {
            return new Probe(false, true, iface + " down");
        }
        for (String address : facts.ipv4Addresses) {
            if (address != null && address.startsWith(AP_IPV4_PREFIX)) {
                return new Probe(true, true, iface + " up with " + address);
            }
        }
        return new Probe(false, true, iface + " up without a " + AP_IPV4_PREFIX + "* address");
    }

    static Iface lookupInterface(String name) throws Exception {
        if (name == null || name.isEmpty()) {
            return null;
        }
        NetworkInterface nif = NetworkInterface.getByName(name);
        if (nif == null) {
            return null;
        }
        List<String> v4 = new ArrayList<>();
        Enumeration<InetAddress> addresses = nif.getInetAddresses();
        while (addresses.hasMoreElements()) {
            InetAddress address = addresses.nextElement();
            if (address instanceof Inet4Address) {
                v4.add(address.getHostAddress());
            }
        }
        return new Iface(nif.isUp(), v4);
    }
}
