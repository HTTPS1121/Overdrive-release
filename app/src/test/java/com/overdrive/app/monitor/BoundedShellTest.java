package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Runs the host's {@code sh}. Every command either finishes well inside its deadline or is
 * killed by it; the suite must never wait for a sleeping child.
 */
public class BoundedShellTest {

    private static final LongSupplier CLOCK = () -> System.nanoTime() / 1_000_000L;

    @Test
    public void fastCommandReturnsOutput() {
        BoundedShell.Result r = BoundedShell.run(
                new String[]{"sh", "-c", "echo hello; echo world"}, 3_000L, CLOCK);
        assertTrue(r.ok());
        assertEquals(0, r.exitCode);
        assertFalse(r.timedOut);
        assertEquals("hello\nworld", r.output);
    }

    @Test
    public void stderrIsMergedAndNonZeroExitIsNotOk() {
        BoundedShell.Result r = BoundedShell.run(
                new String[]{"sh", "-c", "echo oops 1>&2; exit 3"}, 3_000L, CLOCK);
        assertFalse(r.ok());
        assertEquals(3, r.exitCode);
        assertFalse(r.timedOut);
        assertEquals("oops", r.output);
    }

    @Test
    public void slowCommandIsKilledAtDeadline() throws Exception {
        AtomicReference<Process> spawned = new AtomicReference<>();
        long started = System.nanoTime();
        BoundedShell.Result r = BoundedShell.run(
                new String[]{"sh", "-c", "echo started; sleep 5; echo done"},
                300L, CLOCK, spawned::set);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(r.timedOut);
        assertFalse(r.ok());
        assertEquals(-1, r.exitCode);
        // Partial output captured before the deadline; nothing after it.
        assertEquals("started", r.output);
        assertTrue("returned after " + elapsedMs + " ms", elapsedMs < 2_000L);

        Process shell = spawned.get();
        assertNotNull(shell);
        assertTrue("shell still alive after the deadline kill",
                shell.waitFor(1, TimeUnit.SECONDS));
        assertFalse(shell.isAlive());
    }

    @Test
    public void outputIsCapped() {
        BoundedShell.Result r = BoundedShell.run(
                new String[]{"sh", "-c", "head -c 200000 /dev/zero | tr '\\0' x"}, 5_000L, CLOCK);
        assertFalse(r.timedOut);
        assertEquals(0, r.exitCode);
        assertTrue("captured " + r.output.length(),
                r.output.length() <= BoundedShell.MAX_OUTPUT_BYTES);
        // The child wrote far more than the cap; the excess was drained so it could exit 0,
        // and what was kept is the real output, not a truncation artefact.
        assertTrue("captured only " + r.output.length(),
                r.output.length() > BoundedShell.MAX_OUTPUT_BYTES / 2);
        assertTrue(r.output.chars().allMatch(c -> c == 'x'));
    }

    @Test
    public void unspawnableCommandFailsWithoutThrowing() {
        BoundedShell.Result r = BoundedShell.run(
                new String[]{"/nonexistent/binary/for/this/test"}, 1_000L, CLOCK);
        assertFalse(r.ok());
        assertFalse(r.timedOut);
        assertEquals(-1, r.exitCode);
        assertEquals("", r.output);
    }
}
