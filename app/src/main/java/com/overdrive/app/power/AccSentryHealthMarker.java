package com.overdrive.app.power;

import android.os.SystemClock;

import com.overdrive.app.util.ScratchRoot;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Monotonic liveness marker for the DiLink 5 AccSentry heartbeat.
 *
 * <p>The shell watchdog can already restart a process that exits, but a vendor
 * Binder call can leave {@code app_process} alive while its ACC judge no longer
 * makes progress. The DiLink 5 heartbeat updates this tiny marker before every
 * power-state probe. The launcher only arms stale-marker enforcement after it
 * has read a valid marker whose PID matches the child it launched, leaving all
 * non-DiLink-5 modes byte-for-byte dormant.
 */
public final class AccSentryHealthMarker {

    public static final String FILE_NAME = "acc_sentry_health";

    /** Five-second heartbeat plus generous Binder/scheduler headroom. */
    public static final long STALE_AFTER_SECONDS = 45L;

    /** Shell supervisor cadence. */
    public static final int WATCHDOG_POLL_SECONDS = 10;

    /**
     * A larger supervisor scheduling gap indicates whole-system suspend or
     * severe scheduler starvation, not proof that this one process wedged.
     */
    public static final int SUSPEND_GAP_SECONDS = 30;

    private static final Object IO_LOCK = new Object();

    private AccSentryHealthMarker() {}

    public static final class Stamp {
        public final int pid;
        public final long elapsedSeconds;

        Stamp(int pid, long elapsedSeconds) {
            this.pid = pid;
            this.elapsedSeconds = elapsedSeconds;
        }
    }

    /** Publish the current process PID and the shared monotonic boot clock. */
    public static boolean publish() {
        return publish(
                ScratchRoot.file(FILE_NAME),
                android.os.Process.myPid(),
                SystemClock.elapsedRealtime() / 1_000L);
    }

    static boolean publish(File target, int pid, long elapsedSeconds) {
        if (target == null || pid <= 0 || elapsedSeconds < 0L) return false;
        synchronized (IO_LOCK) {
            File temporary = null;
            try {
                File parent = target.getParentFile();
                if (parent == null
                        || (!parent.isDirectory() && !parent.mkdirs())) {
                    return false;
                }
                temporary = new File(
                        parent,
                        target.getName() + ".tmp." + pid + "."
                                + Thread.currentThread().getId());
                String value = pid + " " + elapsedSeconds + "\n";
                try (FileOutputStream out =
                             new FileOutputStream(temporary, false)) {
                    out.write(value.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
                if (!replaceAtomically(temporary, target)) {
                    return false;
                }
                Stamp verified = readStamp(target);
                return verified != null
                        && verified.pid == pid
                        && verified.elapsedSeconds == elapsedSeconds;
            } catch (Throwable ignored) {
                return false;
            } finally {
                if (temporary != null && temporary.exists()) {
                    try {
                        temporary.delete();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    /**
     * Remove only this process's marker. A stale shutdown hook must never erase
     * a marker already published by a replacement process.
     */
    public static void clearIfOwned() {
        clearIfOwned(ScratchRoot.file(FILE_NAME), android.os.Process.myPid());
    }

    static boolean clearIfOwned(File target, int pid) {
        synchronized (IO_LOCK) {
            Stamp stamp = readStamp(target);
            if (stamp == null || stamp.pid != pid) return false;
            try {
                return Files.deleteIfExists(target.toPath());
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    static boolean isFreshForPid(
            Stamp stamp, int expectedPid, long nowElapsedSeconds) {
        if (stamp == null
                || expectedPid <= 0
                || stamp.pid != expectedPid
                || stamp.elapsedSeconds < 0L) {
            return false;
        }
        long age = nowElapsedSeconds - stamp.elapsedSeconds;
        return age >= 0L && age <= STALE_AFTER_SECONDS;
    }

    static Stamp readStamp(File candidate) {
        try {
            if (candidate == null || !candidate.isFile()) return null;
            byte[] raw = Files.readAllBytes(candidate.toPath());
            String value =
                    new String(raw, StandardCharsets.US_ASCII).trim();
            if (value.isEmpty() || value.length() > 64) return null;
            String[] parts = value.split("\\s+");
            if (parts.length != 2) return null;
            int pid = Integer.parseInt(parts[0]);
            long elapsedSeconds = Long.parseLong(parts[1]);
            if (pid <= 0 || elapsedSeconds < 0L) return null;
            return new Stamp(pid, elapsedSeconds);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean replaceAtomically(File temporary, File target) {
        try {
            Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (AtomicMoveNotSupportedException unsupported) {
            // Retry below without the optional atomic flag.
        } catch (Throwable atomicFailure) {
            // Some Android filesystems reject only ATOMIC_MOVE.
        }
        try {
            Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
