package com.overdrive.app.byd;

import java.util.function.BooleanSupplier;

/** When the four-door write backs up the regular local unlock, independent of Android and the vendor SDK. */
final class FourDoorUnlockPolicy {
    static final int DOOR_UNLOCK = 1;
    static final int DOOR_LOCK = 2;
    static final long CONFIRM_MS = 4_000L;
    static final long CONFIRM_POLL_MS = 250L;
    /** An unconfirmed unlock this soon after an accepted one, door still locked, is that unlock's re-send. */
    static final long RESEND_WINDOW_MS = 15_000L;
    private static final long NONE = Long.MIN_VALUE;

    interface Port {
        boolean aborted();
        boolean enabled();
        boolean diLink5();
        String blockReason();
        boolean requestExpired();
        int readDoor();
        /** The write ordered as the newest door intent; null when {@code expected} was superseded. */
        RemoteKeySequence.IntentWrite write(long expected);
        long doorIntent();
        long elapsedMs();
        void sleep(long millis) throws InterruptedException;
        void log(String message);
        void warn(String message);
    }

    private volatile long lastAcceptedUnlockMs = NONE;

    /**
     * {@code primary} is the regular local unlock and registers {@code primaryIntents} door intents of
     * its own. The four-door write follows it when it fails; for an unconfirmed ({@code confirm=false})
     * re-send of an accepted unlock the door still reads locked after, it goes first instead.
     */
    boolean unlock(Port port, boolean confirm, int primaryIntents, BooleanSupplier primary) {
        long intent = port.doorIntent();
        boolean resend = !confirm && followsUnlandedUnlock(port);
        boolean sentFirst = resend && send(port, intent, "before a re-sent unlock") != null;
        boolean done = primary.getAsBoolean() || sentFirst;
        if (!done && !resend) {
            RemoteKeySequence.IntentWrite write =
                    send(port, intent + primaryIntents, "after a failed unlock");
            done = write != null && (!confirm || awaitUnlocked(port, write.intent));
        }
        if (done) lastAcceptedUnlockMs = port.elapsedMs();
        return done;
    }

    private boolean followsUnlandedUnlock(Port port) {
        long last = lastAcceptedUnlockMs;
        return last != NONE && port.elapsedMs() - last <= RESEND_WINDOW_MS
                && readDoor(port) == DOOR_LOCK;
    }

    /** Null unless the write went out and returned 0. */
    private static RemoteKeySequence.IntentWrite send(Port port, long expected, String trigger) {
        if (Thread.currentThread().isInterrupted() || port.aborted() || port.diLink5()) return null;
        if (!port.enabled()) {
            port.log("four-door: skipped " + trigger + " (disabled by config)");
            return null;
        }
        int before = readDoor(port);
        if (before == DOOR_UNLOCK) {
            port.log("four-door: skipped " + trigger + " (driver door reads unlocked)");
            return null;
        }
        if (port.requestExpired()) {
            port.log("four-door: skipped " + trigger + " (request expired)");
            return null;
        }
        String block = blockReason(port);
        if (block != null) {
            port.log("four-door: skipped " + trigger + " (not confirmed parked: " + block + ")");
            return null;
        }
        RemoteKeySequence.IntentWrite write;
        try {
            write = port.write(expected);
        } catch (Throwable failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            port.warn("four-door: write failed " + trigger + ": " + cause.getClass().getSimpleName()
                    + (cause.getMessage() != null ? " " + cause.getMessage() : ""));
            return null;
        }
        if (write == null) {
            port.log("four-door: skipped " + trigger + " (superseded by a newer door command)");
            return null;
        }
        port.log("four-door: sent " + trigger + " door=" + before + " rc=" + write.rc);
        if (write.rc != 0) {
            port.warn("four-door: refused rc=" + write.rc);
            return null;
        }
        return write;
    }

    /** True on an unlocked read, a newer door intent, an interrupt, or a door that never read locked. */
    private static boolean awaitUnlocked(Port port, long intent) {
        long start = port.elapsedMs();
        boolean readLocked = false;
        while (true) {
            if (port.doorIntent() != intent) return true;
            int state = readDoor(port);
            if (state == DOOR_UNLOCK) {
                port.log("four-door: driver door unlocked after " + (port.elapsedMs() - start) + "ms");
                return true;
            }
            if (state == DOOR_LOCK) readLocked = true;
            if (port.elapsedMs() - start >= CONFIRM_MS) break;
            try {
                port.sleep(CONFIRM_POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        if (!readLocked) {
            port.log("four-door: accepted, driver door unreadable");
            return true;
        }
        port.warn("four-door: accepted, driver door still locked after " + CONFIRM_MS + "ms");
        return false;
    }

    private static int readDoor(Port port) {
        try {
            return port.readDoor();
        } catch (Throwable unavailable) {
            return 0;
        }
    }

    private static String blockReason(Port port) {
        try {
            return port.blockReason();
        } catch (Throwable unavailable) {
            return "state_unknown";
        }
    }
}
