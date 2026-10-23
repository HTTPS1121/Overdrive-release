package com.overdrive.app.server;

import java.io.File;

/**
 * Resolves which process holds a listening TCP port. Used only from
 * bind-failure paths: a BindException that is not a transient TIME_WAIT means
 * another process owns the port, and the log has to name it.
 */
public final class PortOwner {

    /** Wall-clock cap for the /proc/<pid>/fd sweep. */
    private static final long SCAN_BUDGET_MS = 2000L;

    private PortOwner() {}

    /**
     * Human-readable owner of {@code port}, or the reason it could not be
     * resolved. Never throws.
     */
    public static String describe(int port) {
        try {
            java.util.Set<String> inodes = listenInodes(port);
            if (inodes.isEmpty()) {
                return "no LISTEN row for port " + port + " in /proc/net/tcp{,6}";
            }
            String owner = findFdOwner(inodes);
            if (owner != null) return owner;
            return "socket inode(s) " + inodes + " held outside any readable /proc/<pid>/fd";
        } catch (Throwable t) {
            return "owner lookup failed: " + t;
        }
    }

    /** Socket inodes of all LISTEN rows whose local port matches. */
    private static java.util.Set<String> listenInodes(int port) {
        java.util.Set<String> inodes = new java.util.LinkedHashSet<>();
        for (String table : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            File f = new File(table);
            if (!f.canRead()) continue;
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
                String line;
                boolean first = true;
                while ((line = r.readLine()) != null) {
                    if (first) { first = false; continue; }  // header
                    String[] col = line.trim().split("\\s+");
                    if (col.length < 10) continue;
                    if (!"0A".equals(col[3])) continue;      // TCP_LISTEN
                    int colon = col[1].lastIndexOf(':');
                    if (colon < 0) continue;
                    try {
                        if (Integer.parseInt(col[1].substring(colon + 1), 16) != port) continue;
                    } catch (NumberFormatException nfe) {
                        continue;
                    }
                    inodes.add(col[9]);
                }
            } catch (Exception ignored) {
            }
        }
        return inodes;
    }

    /** First process holding any of {@code inodes} as an open socket fd. */
    private static String findFdOwner(java.util.Set<String> inodes) {
        String[] entries = new File("/proc").list();
        if (entries == null) return null;
        java.util.Set<String> links = new java.util.HashSet<>();
        for (String inode : inodes) links.add("socket:[" + inode + "]");

        long deadline = System.currentTimeMillis() + SCAN_BUDGET_MS;
        for (String entry : entries) {
            if (System.currentTimeMillis() > deadline) return null;
            int pid;
            try {
                pid = Integer.parseInt(entry);
            } catch (NumberFormatException nfe) {
                continue;
            }
            String[] fds = new File("/proc/" + pid + "/fd").list();
            if (fds == null) continue;                       // other UID / exited
            for (String fd : fds) {
                try {
                    if (links.contains(android.system.Os.readlink("/proc/" + pid + "/fd/" + fd))) {
                        return "pid=" + pid + " cmdline=" + cmdline(pid);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** NUL-separated /proc/<pid>/cmdline flattened to spaces; "" when unreadable. */
    private static String cmdline(int pid) {
        File f = new File("/proc/" + pid + "/cmdline");
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while (total < buf.length && (n = fis.read(buf, total, buf.length - total)) > 0) {
                total += n;
            }
            StringBuilder sb = new StringBuilder(total);
            for (int i = 0; i < total; i++) {
                sb.append(buf[i] == 0 ? ' ' : (char) (buf[i] & 0xff));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }
}
