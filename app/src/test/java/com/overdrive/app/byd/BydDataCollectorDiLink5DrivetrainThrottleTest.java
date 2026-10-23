package com.overdrive.app.byd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BydDataCollectorDiLink5DrivetrainThrottleTest {

    @Test
    public void ambiguousProbeThrottleIsDiLink5OnlyAndBounded() {
        long last = 10_000L;

        assertTrue(BydDataCollector
                .shouldThrottleDiLink5AmbiguousDrivetrainProbe(
                        true, 10_001L, last));
        assertTrue(BydDataCollector
                .shouldThrottleDiLink5AmbiguousDrivetrainProbe(
                        true,
                        last + BydDataCollector
                                .DILINK5_AMBIGUOUS_DRIVETRAIN_REPROBE_MS - 1L,
                        last));
        assertFalse(BydDataCollector
                .shouldThrottleDiLink5AmbiguousDrivetrainProbe(
                        true,
                        last + BydDataCollector
                                .DILINK5_AMBIGUOUS_DRIVETRAIN_REPROBE_MS,
                        last));
        assertFalse(BydDataCollector
                .shouldThrottleDiLink5AmbiguousDrivetrainProbe(
                        false, 10_001L, last));
        assertFalse(BydDataCollector
                .shouldThrottleDiLink5AmbiguousDrivetrainProbe(
                        true, 10_001L, 0L));
        assertFalse(BydDataCollector
                .shouldThrottleDiLink5AmbiguousDrivetrainProbe(
                        true, 9_999L, last));
    }
}
