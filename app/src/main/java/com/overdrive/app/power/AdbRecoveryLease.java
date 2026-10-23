package com.overdrive.app.power;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Cross-process single-flight lease for DiLink 5 ADB recovery.
 *
 * <p>The app process (which can bind OEM services and write
 * {@code Settings.Global}) and the uid-2000 daemon (which re-applies the ADB
 * settings through {@code settings put}) both try to recover a closed ADB port
 * while parked. Only one should act at a time. They coordinate through a single
 * {@code Settings.Global} string ({@link #SETTING_KEY}) that both UIDs can read
 * and write: {@code "<owner>:<bootId>:<elapsedRealtimeMs>"}.
 *
 * <p>The clock is {@link android.os.SystemClock#elapsedRealtime()} — shared by
 * every process on one boot and immune to wall-clock jumps. The boot id
 * ({@code /proc/sys/kernel/random/boot_id}) invalidates a lease that survived a
 * reboot in the persistent settings table. There is no explicit clear: a lease
 * expires purely by {@link #TTL_MS}.
 *
 * <p>Pure logic, no Android imports, so it is unit-testable on a plain JVM. The
 * callers supply the current clock and boot id.
 */
public final class AdbRecoveryLease {

    /** {@code Settings.Global} key holding the lease, readable/writable by both UIDs. */
    public static final String SETTING_KEY = "overdrive_adb_recovery_lease";

    /** A lease is fresh for this long after it was stamped. */
    public static final long TTL_MS = 120_000L;

    private static volatile String cachedBootId;

    private AdbRecoveryLease() {}

    /** Which process published a lease. */
    public enum Owner {
        APP("app"),
        DAEMON("daemon");

        public final String wire;

        Owner(String wire) {
            this.wire = wire;
        }

        static Owner fromWire(String value) {
            if (value == null) {
                return null;
            }
            for (Owner owner : values()) {
                if (owner.wire.equals(value)) {
                    return owner;
                }
            }
            return null;
        }
    }

    /** Classification of a raw lease value against the current clock and boot. */
    public enum State {
        ABSENT,
        FRESH,
        EXPIRED,
        MALFORMED
    }

    /** Parsed, classified lease. */
    public static final class Parsed {
        public final State state;
        public final Owner owner;
        public final long ageMs;
        public final long stampMs;

        Parsed(State state, Owner owner, long ageMs, long stampMs) {
            this.state = state;
            this.owner = owner;
            this.ageMs = ageMs;
            this.stampMs = stampMs;
        }

        /** True only when this lease is FRESH and owned by {@code expected}. */
        public boolean isFresh(Owner expected) {
            return state == State.FRESH && owner == expected;
        }
    }

    /** Compose a wire value: {@code "<owner>:<bootId>:<elapsedMs>"}. */
    public static String format(Owner owner, String bootId, long elapsedMs) {
        return owner.wire + ":" + bootId + ":" + elapsedMs;
    }

    /**
     * Parse and classify a raw lease value.
     *
     * <p>{@code null}, empty, and the literal {@code "null"} (what
     * {@code settings get} prints for an absent key) classify as
     * {@link State#ABSENT}. A value that is not exactly three colon-separated
     * fields, carries an unknown owner, or whose stamp is not numeric is
     * {@link State#MALFORMED}. A value from another boot, or whose age is
     * negative or beyond {@link #TTL_MS}, is {@link State#EXPIRED}. Everything
     * else is {@link State#FRESH}.
     */
    public static Parsed parse(String raw, long nowElapsedMs, String currentBootId) {
        if (raw == null) {
            return new Parsed(State.ABSENT, null, 0L, 0L);
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.equals("null")) {
            return new Parsed(State.ABSENT, null, 0L, 0L);
        }
        String[] parts = trimmed.split(":", -1);
        if (parts.length != 3) {
            return new Parsed(State.MALFORMED, null, 0L, 0L);
        }
        Owner owner = Owner.fromWire(parts[0]);
        if (owner == null) {
            return new Parsed(State.MALFORMED, null, 0L, 0L);
        }
        long stampMs;
        try {
            stampMs = Long.parseLong(parts[2]);
        } catch (NumberFormatException notANumber) {
            return new Parsed(State.MALFORMED, null, 0L, 0L);
        }
        long ageMs = nowElapsedMs - stampMs;
        if (currentBootId == null || !currentBootId.equals(parts[1])) {
            return new Parsed(State.EXPIRED, owner, ageMs, stampMs);
        }
        if (ageMs < 0L || ageMs > TTL_MS) {
            return new Parsed(State.EXPIRED, owner, ageMs, stampMs);
        }
        return new Parsed(State.FRESH, owner, ageMs, stampMs);
    }

    /**
     * The sanitised boot id of the running kernel, cached for the process.
     * Falls back to {@code "unknown-boot"} when the kernel file is unreadable;
     * two processes on one boot still agree, and a reboot still invalidates a
     * persisted lease because every process re-reads the same file.
     */
    public static String currentBootId() {
        String cached = cachedBootId;
        if (cached != null) {
            return cached;
        }
        String resolved = readBootId();
        cachedBootId = resolved;
        return resolved;
    }

    private static String readBootId() {
        try {
            byte[] raw = Files.readAllBytes(
                    Paths.get("/proc/sys/kernel/random/boot_id"));
            String sanitized = sanitizeBootId(
                    new String(raw, StandardCharsets.US_ASCII));
            if (!sanitized.isEmpty()) {
                return sanitized;
            }
        } catch (Throwable ignored) {
            // Fall through to the stable fallback.
        }
        return "unknown-boot";
    }

    /** Trim and strip to {@code [A-Za-z0-9_-]} (matches the daemon's boot-id reader). */
    static String sanitizeBootId(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("[^A-Za-z0-9_-]", "");
    }
}
