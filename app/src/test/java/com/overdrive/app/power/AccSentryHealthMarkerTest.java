package com.overdrive.app.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class AccSentryHealthMarkerTest {

    @Test
    public void publishReplacesAndVerifiesSamePidStamp() throws Exception {
        Path dir = Files.createTempDirectory("acc-sentry-health-");
        try {
            File target = dir.resolve("health").toFile();

            assertTrue(AccSentryHealthMarker.publish(
                    target, 1234, 100L));
            AccSentryHealthMarker.Stamp first =
                    AccSentryHealthMarker.readStamp(target);
            assertNotNull(first);
            assertEquals(1234, first.pid);
            assertEquals(100L, first.elapsedSeconds);

            assertTrue(AccSentryHealthMarker.publish(
                    target, 1234, 110L));
            AccSentryHealthMarker.Stamp replacement =
                    AccSentryHealthMarker.readStamp(target);
            assertNotNull(replacement);
            assertEquals(1234, replacement.pid);
            assertEquals(110L, replacement.elapsedSeconds);
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void freshnessRequiresMatchingPidAndBoundedMonotonicAge() {
        AccSentryHealthMarker.Stamp stamp =
                new AccSentryHealthMarker.Stamp(42, 100L);

        assertTrue(AccSentryHealthMarker.isFreshForPid(
                stamp, 42, 100L));
        assertTrue(AccSentryHealthMarker.isFreshForPid(
                stamp,
                42,
                100L + AccSentryHealthMarker.STALE_AFTER_SECONDS));
        assertFalse(AccSentryHealthMarker.isFreshForPid(
                stamp, 41, 101L));
        assertFalse(AccSentryHealthMarker.isFreshForPid(
                stamp,
                42,
                101L + AccSentryHealthMarker.STALE_AFTER_SECONDS));
        assertFalse(AccSentryHealthMarker.isFreshForPid(
                stamp, 42, 99L));
    }

    @Test
    public void clearNeverDeletesAnotherProcessMarker() throws Exception {
        Path dir = Files.createTempDirectory("acc-sentry-health-clear-");
        try {
            File target = dir.resolve("health").toFile();
            assertTrue(AccSentryHealthMarker.publish(
                    target, 1001, 50L));

            assertFalse(AccSentryHealthMarker.clearIfOwned(
                    target, 1002));
            assertTrue(target.isFile());
            assertTrue(AccSentryHealthMarker.clearIfOwned(
                    target, 1001));
            assertFalse(target.exists());
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void malformedOrNegativeRowsAreRejected() throws Exception {
        Path dir = Files.createTempDirectory("acc-sentry-health-invalid-");
        try {
            File target = dir.resolve("health").toFile();
            Files.write(target.toPath(),
                    "pid timestamp".getBytes(StandardCharsets.US_ASCII));
            assertNull(AccSentryHealthMarker.readStamp(target));

            Files.write(target.toPath(),
                    "123 -1".getBytes(StandardCharsets.US_ASCII));
            assertNull(AccSentryHealthMarker.readStamp(target));
        } finally {
            deleteTree(dir);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }
}
