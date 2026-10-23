package com.overdrive.app.monitor;

import com.overdrive.app.util.ScratchRoot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Deadline-bounded {@code sh -c} runner for the network monitor's shell fallbacks.
 *
 * <p>Every call returns within roughly {@code deadlineMs} plus a short kill grace: the
 * child's stdout and stderr are merged into one pipe and drained in bounded passes while
 * the deadline is polled, captured output is capped at {@link #MAX_OUTPUT_BYTES} (the rest
 * is read and discarded so the child can finish), stdin is closed at once, and on timeout
 * the shell plus every descendant that can be enumerated through {@code /proc} is
 * destroyed. Nothing here blocks on EOF or on an unbounded {@link Process#waitFor()}, so a
 * wedged system service cannot wedge the calling thread (HTTP handlers, automation pollers,
 * SafeKeep's signal reads).
 *
 * <p>Pure Java apart from the monotonic clock, which is injectable so the class runs on a
 * plain JVM in unit tests. Callers decide what to log; this class never logs.
 */
public final class BoundedShell {

    /** Default per-command deadline; no network-monitor shell call may block longer. */
    public static final long DEFAULT_DEADLINE_MS = 3_000L;
    /** Captured-output cap in bytes; output past it is drained and dropped. */
    public static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final long WAIT_SLICE_MS = 100L;
    private static final long KILL_GRACE_MS = 250L;
    private static final int DRAIN_PASSES = 8;
    private static final int FINAL_DRAIN_ROUNDS = 4;

    /** Outcome of one bounded run. {@link #output} is never null. */
    public static final class Result {
        /** Exit status, or -1 when the command did not finish (timeout, interrupt, spawn failure). */
        public final int exitCode;
        /** Trimmed merged stdout/stderr; possibly partial when {@link #timedOut}. */
        public final String output;
        /** True when the deadline expired before the shell exited. */
        public final boolean timedOut;

        Result(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
            this.timedOut = timedOut;
        }

        /** The command finished inside the deadline and exited 0. */
        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    private BoundedShell() {}

    /**
     * Runs {@code sh -c cmd} (scratch-root remapped like every other spawn chokepoint) and
     * returns within about {@code deadlineMs}. Never throws; failures surface as
     * {@code exitCode == -1} and {@code ok() == false}.
     */
    public static Result run(String cmd, long deadlineMs) {
        return run(new String[]{"sh", "-c", ScratchRoot.remap(cmd)},
                deadlineMs, BoundedShell::elapsedRealtime, null);
    }

    /** Test seam: explicit argv and clock, no scratch-root remap. */
    static Result run(String[] argv, long deadlineMs, LongSupplier clock) {
        return run(argv, deadlineMs, clock, null);
    }

    /** Test seam: {@code onStart} observes the spawned process so a test can check it died. */
    static Result run(String[] argv, long deadlineMs, LongSupplier clock, Consumer<Process> onStart) {
        Process process = null;
        InputStream merged = null;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int exitCode = -1;
        boolean timedOut = false;
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.redirectErrorStream(true);
            process = builder.start();
            if (onStart != null) {
                onStart.accept(process);
            }
            // Nothing is ever written to the child: closing stdin now means a child that
            // reads its input sees EOF instead of waiting on this thread.
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {}
            merged = process.getInputStream();
            byte[] buffer = new byte[4096];
            long deadline = clock.getAsLong() + Math.max(1L, deadlineMs);
            while (true) {
                drain(merged, captured, buffer);
                long remaining = deadline - clock.getAsLong();
                if (remaining <= 0L) {
                    timedOut = true;
                    break;
                }
                if (process.waitFor(Math.min(WAIT_SLICE_MS, remaining), TimeUnit.MILLISECONDS)) {
                    // Exited: take what is in the pipe now, but never block for EOF — a
                    // detached descendant can inherit the pipe and keep it open.
                    for (int round = 0; round < FINAL_DRAIN_ROUNDS && merged.available() > 0; round++) {
                        drain(merged, captured, buffer);
                    }
                    exitCode = process.exitValue();
                    break;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            // Spawn or pipe failure: reported as exitCode -1 with whatever was captured.
        } finally {
            if (process != null && (timedOut || process.isAlive())) {
                destroyProcessTree(process);
            }
            if (merged != null) {
                try {
                    merged.close();
                } catch (IOException ignored) {}
            }
        }
        return new Result(exitCode, outputString(captured), timedOut);
    }

    /**
     * One bounded drain pass. A noisy child can keep {@code available()} positive forever,
     * so each pass reads at most {@link #DRAIN_PASSES} chunks and returns control to the
     * caller for the deadline check.
     */
    private static void drain(InputStream input, ByteArrayOutputStream output, byte[] buffer)
            throws IOException {
        for (int reads = 0; reads < DRAIN_PASSES; reads++) {
            int available = input.available();
            if (available <= 0) {
                return;
            }
            int read = input.read(buffer, 0, Math.min(buffer.length, available));
            if (read < 0) {
                return;
            }
            int remaining = MAX_OUTPUT_BYTES - output.size();
            if (remaining > 0) {
                output.write(buffer, 0, Math.min(read, remaining));
            }
            if (input.available() <= 0) {
                return;
            }
        }
    }

    private static String outputString(ByteArrayOutputStream output) {
        return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    private static long elapsedRealtime() {
        return android.os.SystemClock.elapsedRealtime();
    }

    /**
     * Kills the shell and, best effort, its descendants. Descendants are found through
     * {@code /proc/<pid>/task/<pid>/children}, which needs the shell's pid: that is only
     * exposed by reflection on newer runtimes, so on older ones this degrades to
     * {@code destroy()} / {@code destroyForcibly()} on the shell itself. Children that
     * belong to another uid cannot be signalled from here in any case.
     */
    private static void destroyProcessTree(Process process) {
        for (Integer pid : descendantsOf(process)) {
            try {
                android.os.Process.killProcess(pid);
            } catch (Throwable ignored) {}
        }
        try {
            process.destroy();
            if (!process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            try {
                process.destroyForcibly();
            } catch (Throwable ignored) {}
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            try {
                process.destroyForcibly();
            } catch (Throwable ignoredAgain) {}
        }
    }

    private static List<Integer> descendantsOf(Process process) {
        List<Integer> descendants = new ArrayList<>();
        int rootPid = pidOf(process);
        if (rootPid > 0) {
            collectDescendants(rootPid, descendants, new HashSet<Integer>());
        }
        return descendants;
    }

    private static int pidOf(Process process) {
        try {
            Method pidMethod = Process.class.getMethod("pid");
            Object value = pidMethod.invoke(process);
            if (value instanceof Number) {
                long pid = ((Number) value).longValue();
                if (pid > 0L && pid <= Integer.MAX_VALUE) {
                    return (int) pid;
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static void collectDescendants(int parentPid, List<Integer> output, Set<Integer> visited) {
        if (!visited.add(parentPid)) {
            return;
        }
        try {
            String children = new String(
                    Files.readAllBytes(Paths.get("/proc/" + parentPid + "/task/" + parentPid + "/children")),
                    StandardCharsets.US_ASCII).trim();
            if (children.isEmpty()) {
                return;
            }
            for (String token : children.split("\\s+")) {
                int childPid = Integer.parseInt(token);
                collectDescendants(childPid, output, visited);
                output.add(childPid);
            }
        } catch (Throwable ignored) {}
    }
}
