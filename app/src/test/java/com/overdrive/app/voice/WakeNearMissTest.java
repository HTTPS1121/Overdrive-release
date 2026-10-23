package com.overdrive.app.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Stage-1 near misses under media: one bump that rose to at least 0.20 and fell back below the
 * cutoff is reported once, at most one per 2 s (the highest waiting), never without media and
 * never for a bump that fired.
 */
public class WakeNearMissTest {

    private static final float CUTOFF = 0.35f;

    @Test
    public void aBumpBelowTheCutoffIsReportedOnceWhenItEnds() {
        WakeNearMiss w = new WakeNearMiss();
        long t = 10_000;
        assertNaN(w.onChunk(0.10f, 0.10f, CUTOFF, true, false, t));
        assertNaN(w.onChunk(0.25f, 0.25f, CUTOFF, true, false, t += 64));
        assertNaN(w.onChunk(0.31f, 0.28f, CUTOFF, true, false, t += 64));
        // Falls back below the floor: the bump's peak is the near miss.
        assertEquals(0.31f, w.onChunk(0.18f, 0.12f, CUTOFF, true, false, t += 64), 1e-6f);
        assertNaN(w.onChunk(0.05f, 0.05f, CUTOFF, true, false, t += 64));
    }

    @Test
    public void bumpsUnderTheFloorAboveTheCutoffOrThatFiredAreNotNearMisses() {
        WakeNearMiss w = new WakeNearMiss();
        long t = 10_000;
        assertNaN(w.onChunk(0.19f, 0.19f, CUTOFF, true, false, t));
        assertNaN(w.onChunk(0.05f, 0.05f, CUTOFF, true, false, t += 64));
        // Reached the cutoff without firing (warm-up, refractory or VAD gate): not a miss.
        assertNaN(w.onChunk(0.40f, 0.40f, CUTOFF, true, false, t += 64));
        assertNaN(w.onChunk(0.10f, 0.10f, CUTOFF, true, false, t += 64));
        // A detection is a hit.
        assertNaN(w.onChunk(0.30f, 0.30f, CUTOFF, true, false, t += 64));
        assertNaN(w.onChunk(0.36f, 0.00f, CUTOFF, true, true, t += 64));
        assertNaN(w.onChunk(0.05f, 0.05f, CUTOFF, true, false, t += 64));
    }

    @Test
    public void withoutMediaNothingIsTrackedOrKept() {
        WakeNearMiss w = new WakeNearMiss();
        long t = 10_000;
        assertNaN(w.onChunk(0.30f, 0.30f, CUTOFF, false, false, t));
        assertNaN(w.onChunk(0.05f, 0.05f, CUTOFF, false, false, t += 64));
        // A bump that started under media but ended after it stopped is dropped too.
        assertNaN(w.onChunk(0.30f, 0.30f, CUTOFF, true, false, t += 64));
        assertNaN(w.onChunk(0.05f, 0.05f, CUTOFF, false, false, t += 64));
        assertNaN(w.onChunk(0.05f, 0.05f, CUTOFF, true, false, t += 64));
    }

    @Test
    public void atMostOneEveryTwoSecondsAndTheHighestWaitingWins() {
        WakeNearMiss w = new WakeNearMiss();
        long t = 10_000;
        w.onChunk(0.22f, 0.22f, CUTOFF, true, false, t);
        assertEquals(0.22f, w.onChunk(0.05f, 0.05f, CUTOFF, true, false, t += 64), 1e-6f);
        // Two more bumps within 2 s: held back, the higher one kept.
        w.onChunk(0.33f, 0.33f, CUTOFF, true, false, t += 300);
        assertNaN(w.onChunk(0.05f, 0.05f, CUTOFF, true, false, t += 64));
        w.onChunk(0.26f, 0.26f, CUTOFF, true, false, t += 300);
        assertNaN(w.onChunk(0.05f, 0.05f, CUTOFF, true, false, t += 64));
        long last = 10_064;
        assertNaN(w.onChunk(0.01f, 0.01f, CUTOFF, true, false, last + WakeNearMiss.MIN_INTERVAL_MS - 1));
        assertEquals(0.33f, w.onChunk(0.01f, 0.01f, CUTOFF, true, false, last + WakeNearMiss.MIN_INTERVAL_MS), 1e-6f);
        assertNaN(w.onChunk(0.01f, 0.01f, CUTOFF, true, false, last + 3 * WakeNearMiss.MIN_INTERVAL_MS));
    }

    @Test
    public void theFloorAndIntervalAreTheAgreedValues() {
        assertEquals(0.20f, WakeNearMiss.FLOOR, 0f);
        assertEquals(2000, WakeNearMiss.MIN_INTERVAL_MS);
    }

    private static void assertNaN(float value) {
        assertTrue("expected NaN, got " + value, Float.isNaN(value));
    }
}
