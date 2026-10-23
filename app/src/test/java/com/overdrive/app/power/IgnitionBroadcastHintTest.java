package com.overdrive.app.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class IgnitionBroadcastHintTest {

    @Test
    public void freshnessRequiresCurrentBootRecentAndPostSentry() {
        IgnitionBroadcastHint.Stamp stamp =
                new IgnitionBroadcastHint.Stamp(10_000L, 100_000L);

        assertTrue(IgnitionBroadcastHint.isFresh(
                stamp, 9_000L, 20_000L, 105_000L));
        assertFalse(IgnitionBroadcastHint.isFresh(
                stamp, 10_000L, 20_000L, 105_000L));
        assertFalse(IgnitionBroadcastHint.isFresh(
                stamp, 11_000L, 20_000L, 105_000L));
        assertFalse(IgnitionBroadcastHint.isFresh(
                stamp, 9_000L,
                10_000L + IgnitionBroadcastHint.MAX_AGE_MS + 1L,
                105_000L));
        assertFalse(IgnitionBroadcastHint.isFresh(
                stamp, 9_000L, 9_999L, 105_000L));
    }

    @Test
    public void repeatedIgnitionCyclesReplaceExistingHint() throws Exception {
        Path dir = Files.createTempDirectory("ignition-hint-test");
        try {
            File target = dir.resolve(IgnitionBroadcastHint.FILE_NAME).toFile();
            Files.write(target.toPath(),
                    "100 200".getBytes(StandardCharsets.US_ASCII));

            File first = dir.resolve("first.tmp").toFile();
            Files.write(first.toPath(),
                    "300 400".getBytes(StandardCharsets.US_ASCII));
            assertTrue(IgnitionBroadcastHint.replaceAtomically(first, target));
            IgnitionBroadcastHint.Stamp firstStamp =
                    IgnitionBroadcastHint.readStamp(target);
            assertNotNull(firstStamp);
            assertEquals(300L, firstStamp.elapsedMs);
            assertEquals(400L, firstStamp.wallMs);

            File second = dir.resolve("second.tmp").toFile();
            Files.write(second.toPath(),
                    "500 600".getBytes(StandardCharsets.US_ASCII));
            assertTrue(IgnitionBroadcastHint.replaceAtomically(second, target));
            IgnitionBroadcastHint.Stamp secondStamp =
                    IgnitionBroadcastHint.readStamp(target);
            assertNotNull(secondStamp);
            assertEquals(500L, secondStamp.elapsedMs);
            assertEquals(600L, secondStamp.wallMs);
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
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
}
