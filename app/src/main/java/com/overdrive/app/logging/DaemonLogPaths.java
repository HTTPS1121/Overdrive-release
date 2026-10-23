package com.overdrive.app.logging;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth mapping a stable daemon KEY (used in API/IPC/Telegram
 * requests) to its on-disk log file path. Mirrors the native
 * DaemonAdapter.getLogFilePath table, but lives in the logging package so it's
 * reachable from the daemon process (UID 2000, no Android UI classes) too.
 *
 * Keys are short, lowercase, stable identifiers — safe to type into Telegram
 * (`/sendlog camera`) and to pass as a query param (`?daemon=camera`).
 */
public final class DaemonLogPaths {

    private DaemonLogPaths() {}

    // LinkedHashMap so the order is stable for "list available daemons" UIs.
    private static final Map<String, String> PATHS = new LinkedHashMap<>();
    static {
        PATHS.put("camera",     com.overdrive.app.util.ScratchRoot.path("cam_daemon.log"));
        PATHS.put("accsentry",  com.overdrive.app.util.ScratchRoot.path("acc_sentry_daemon.log"));
        PATHS.put("sentry",     com.overdrive.app.util.ScratchRoot.path("sentry_daemon.log"));
        PATHS.put("telegram",   com.overdrive.app.util.ScratchRoot.path("telegrambotdaemon.log"));
        PATHS.put("cloudflared",com.overdrive.app.util.ScratchRoot.path("cloudflared.log"));
        PATHS.put("zrok",       com.overdrive.app.util.ScratchRoot.path("zrok.log"));
        PATHS.put("tailscale",  com.overdrive.app.util.ScratchRoot.path(".tailscale/tailscale.log"));
        PATHS.put("wireguard",  com.overdrive.app.wireguard.WireGuardPaths.LOG);
        PATHS.put("singbox",    com.overdrive.app.util.ScratchRoot.path("singbox.log"));
    }

    /** @return the log path for a daemon key, or null if unknown. */
    public static String pathFor(String key) {
        if (key == null) return null;
        return PATHS.get(key.trim().toLowerCase());
    }

    /** Stable, ordered set of known daemon keys. */
    public static java.util.Set<String> keys() {
        return PATHS.keySet();
    }

    /** Comma-joined key list for help text / error messages. */
    public static String keyList() {
        return String.join(", ", PATHS.keySet());
    }
}
