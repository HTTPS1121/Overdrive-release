package com.overdrive.app.safekeep.util;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Locale;

/**
 * Egress classification from the kernel's own route choice: a connected UDP socket
 * (no packet is sent) reveals the source address, hence the interface uploads will
 * really use. Unlike NetworkMonitor it forks nothing and sees hotspot mode as cellular.
 */
public final class NetRoute {

    public enum Transport { WIFI, CELLULAR, ETHERNET, OTHER, NONE }

    public static final class Snapshot {
        public final Transport transport;
        public final String iface;
        public final String localIp;
        public final boolean likelyPhoneHotspot;

        Snapshot(Transport transport, String iface, String localIp, boolean likelyPhoneHotspot) {
            this.transport = transport;
            this.iface = iface;
            this.localIp = localIp;
            this.likelyPhoneHotspot = likelyPhoneHotspot;
        }

        /** Metered unless the egress is Wi-Fi or Ethernet (unknown interfaces count as metered). */
        public boolean metered() {
            return transport != Transport.WIFI && transport != Transport.ETHERNET;
        }

        public boolean sameRoute(Snapshot o) {
            return o != null && transport == o.transport
                    && eq(iface, o.iface) && eq(localIp, o.localIp);
        }

        private static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    private static final Snapshot NONE = new Snapshot(Transport.NONE, "", "", false);

    private NetRoute() {}

    public static Snapshot probe() {
        Snapshot s = probe(new byte[]{1, 1, 1, 1});
        if (s.transport != Transport.NONE) return s;
        return probe(new byte[]{0x26, 0x06, 0x47, 0x00, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11});
    }

    private static Snapshot probe(byte[] target) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByAddress(target), 53);
            InetAddress local = s.getLocalAddress();
            if (local == null || local.isAnyLocalAddress() || local.isLoopbackAddress()) return NONE;
            NetworkInterface ni = NetworkInterface.getByInetAddress(local);
            String name = ni != null ? ni.getName() : "";
            String ip = local.getHostAddress();
            return new Snapshot(classify(name), name, ip, isPhoneHotspotAddress(local));
        } catch (Throwable t) {
            return NONE;
        }
    }

    static Transport classify(String rawName) {
        if (rawName == null || rawName.isEmpty()) return Transport.OTHER;
        String n = rawName.toLowerCase(Locale.ROOT);
        if (n.startsWith("v4-")) n = n.substring(3); // 464XLAT clat interfaces
        if (n.startsWith("wlan") || n.startsWith("wifi")) return Transport.WIFI;
        if (n.startsWith("eth")) return Transport.ETHERNET;
        if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("wwan")
                || n.startsWith("pdp") || n.startsWith("ppp") || n.startsWith("seth")
                || n.startsWith("rev_rmnet") || n.startsWith("usb") || n.startsWith("rndis")
                || n.startsWith("bt-pan") || n.startsWith("bnep") || n.startsWith("clat")) {
            return Transport.CELLULAR;
        }
        return Transport.OTHER;
    }

    /** iPhone hotspots hand out 172.20.10.0/28; legacy Android hotspots 192.168.43.0/24. */
    static boolean isPhoneHotspotAddress(InetAddress local) {
        if (!(local instanceof Inet4Address)) return false;
        byte[] a = local.getAddress();
        int b0 = a[0] & 0xff, b1 = a[1] & 0xff, b2 = a[2] & 0xff, b3 = a[3] & 0xff;
        if (b0 == 172 && b1 == 20 && b2 == 10 && b3 < 16) return true;
        return b0 == 192 && b1 == 168 && b2 == 43;
    }

    /** SSID naming heuristics for phone hotspots; best effort only. */
    public static boolean looksLikePhoneHotspotSsid(String ssid) {
        if (ssid == null) return false;
        String s = ssid.toLowerCase(Locale.ROOT);
        return s.contains("iphone") || s.contains("androidap") || s.contains("android ap")
                || s.contains("galaxy") || s.contains("pixel") || s.contains("hotspot")
                || s.contains("redmi") || s.contains("oneplus");
    }
}
