package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydDataCollector.DiLink5LockEvidence;
import com.overdrive.app.byd.BydDataCollector.DiLink5LockEvidenceLedger;
import com.overdrive.app.byd.BydDataCollector.DiLink5LockSafetyCache;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

/**
 * Stage 1 ledger semantics (design §2 / §4.1 table): one unchanged {@link DiLink5LockSafetyCache}
 * per rail, strict any-gap cross-rail disagreement, future reads dropped, rails inactive unless
 * enabled, and DEFAULT-EQUIVALENCE with today's single cache.
 */
public class DiLink5LockEvidenceLedgerTest {
    private static final int INVALID = BydDataCollector.DOOR_STATE_INVALID;
    private static final int UNLOCK = BydDataCollector.DOOR_STATE_UNLOCK;
    private static final int LOCK = BydDataCollector.DOOR_STATE_LOCK;
    private static final long T0 = 1_000_000L;

    private final List<String> lines = new ArrayList<>();

    private DiLink5LockEvidenceLedger ledger(DiLink5LockRail... rails) {
        DiLink5LockEvidenceLedger ledger = new DiLink5LockEvidenceLedger(lines::add);
        if (rails.length > 0) ledger.setEnabledRails(EnumSet.of(rails[0], rails));
        lines.clear();
        return ledger;
    }

    private static void observe(DiLink5LockEvidenceLedger ledger, DiLink5LockRail rail, int state,
            long readAt, long now) {
        ledger.observe(rail, state, readAt, now, state, 0L);
    }

    // ---- DEFAULT-EQUIVALENCE -------------------------------------------------------------------

    @Test
    public void singleTypedRailIsEquivalentToDiLink5LockSafetyCacheOverRandomSequences() {
        Random random = new Random(20261015L);
        int[] values = {0, 1, 2, 3, -10, Integer.MIN_VALUE};
        DiLink5LockSafetyCache cache = new DiLink5LockSafetyCache();
        DiLink5LockEvidenceLedger ledger = new DiLink5LockEvidenceLedger(line -> { });
        assertEquals(EnumSet.of(DiLink5LockRail.TYPED_CABIN), ledger.enabledRails());
        long now = 50_000L;
        for (int step = 0; step < 10_000; step++) {
            now += random.nextInt(3_001); // 0..3000 ms, including same-ms ties
            long readAt = now - random.nextInt(20_001); // [now - 20 000, now], never > now
            int value = values[random.nextInt(values.length)];
            cache.observe(value, readAt);
            ledger.observe(DiLink5LockRail.TYPED_CABIN, value, readAt, now, value, 0L);
            assertEquals("step " + step, cache.current(now), ledger.current(now));
            assertEquals("step " + step, cache.current(now + 15_000L), ledger.current(now + 15_000L));
            assertEquals("step " + step, cache.current(now + 15_001L), ledger.current(now + 15_001L));
        }
        assertEquals(0L, ledger.droppedDisabled());
        assertEquals(0L, ledger.droppedFuture());
    }

    @Test
    public void singleRailEvidenceCarriesRailRawAndReadTime() {
        DiLink5LockEvidenceLedger ledger = ledger();
        ledger.observe(DiLink5LockRail.TYPED_CABIN, LOCK, T0, T0 + 10L, 1, 0L);
        DiLink5LockEvidence evidence = ledger.evidence(T0 + 500L);
        assertEquals(LOCK, evidence.state);
        assertEquals(T0, evidence.observedAtElapsedMs);
        assertSame(DiLink5LockRail.TYPED_CABIN, evidence.rail);
        assertEquals(1, evidence.raw);
        assertTrue(evidence.known());
        assertEquals(INVALID, DiLink5LockEvidence.unknown().state);
        assertNull(DiLink5LockEvidence.unknown().rail);
        assertFalse(DiLink5LockEvidence.unknown().known());
    }

    // ---- design §4.1 table rows ------------------------------------------------------------------

    @Test
    public void typedInvalidRevokesTypedLock() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.TYPED_CABIN);
        observe(ledger, DiLink5LockRail.TYPED_CABIN, LOCK, T0, T0);
        observe(ledger, DiLink5LockRail.TYPED_CABIN, INVALID, T0 + 1_000L, T0 + 1_000L);
        assertEquals(INVALID, ledger.current(T0 + 1_001L));
    }

    @Test
    public void typedInvalidDoesNotOverrideAnotherRailsValidEvidence() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.OTA);
        observe(ledger, DiLink5LockRail.OTA, LOCK, T0, T0);
        observe(ledger, DiLink5LockRail.TYPED_CABIN, INVALID, T0 + 1_000L, T0 + 1_000L);
        DiLink5LockEvidence evidence = ledger.evidence(T0 + 1_001L);
        assertEquals(LOCK, evidence.state);
        assertSame(DiLink5LockRail.OTA, evidence.rail);
        assertEquals(T0, evidence.observedAtElapsedMs);
        assertNull(ledger.evidenceFor(DiLink5LockRail.TYPED_CABIN, T0 + 1_001L));
        assertNotNull(ledger.evidenceFor(DiLink5LockRail.OTA, T0 + 1_001L));
    }

    @Test
    public void otaInvalidRevokesOtaLock() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.OTA);
        observe(ledger, DiLink5LockRail.OTA, LOCK, T0, T0);
        observe(ledger, DiLink5LockRail.OTA, INVALID, T0 + 1_000L, T0 + 1_000L);
        assertEquals(INVALID, ledger.current(T0 + 1_001L));
    }

    @Test
    public void disagreementAtThreeSecondsIsUnknown() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.OTA, DiLink5LockRail.CARSVC);
        observe(ledger, DiLink5LockRail.OTA, LOCK, T0, T0);
        observe(ledger, DiLink5LockRail.CARSVC, UNLOCK, T0 + 3_000L, T0 + 3_000L);
        assertEquals(INVALID, ledger.current(T0 + 3_000L));
        assertNull(ledger.evidence(T0 + 3_000L).rail);
    }

    @Test
    public void disagreementAtEightSecondsIsStillUnknownNoGapException() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.OTA, DiLink5LockRail.CARSVC);
        observe(ledger, DiLink5LockRail.OTA, LOCK, T0, T0);
        observe(ledger, DiLink5LockRail.CARSVC, UNLOCK, T0 + 8_000L, T0 + 8_000L);
        for (long now = T0 + 8_000L; now <= T0 + 15_000L; now += 250L) {
            assertEquals("now=" + now, INVALID, ledger.current(now));
        }
        // Only once the OTA LOCK ages out does the lone carsvc reading count.
        assertEquals(UNLOCK, ledger.current(T0 + 15_001L));
    }

    @Test
    public void agreeingRailsSelectTheFreshestRead() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.OTA, DiLink5LockRail.CARSVC);
        observe(ledger, DiLink5LockRail.OTA, LOCK, T0, T0);
        ledger.observe(DiLink5LockRail.CARSVC, LOCK, T0 + 8_000L, T0 + 8_000L, 2, 123_456L);
        DiLink5LockEvidence evidence = ledger.evidence(T0 + 8_000L);
        assertEquals(LOCK, evidence.state);
        assertSame(DiLink5LockRail.CARSVC, evidence.rail);
        assertEquals(T0 + 8_000L, evidence.observedAtElapsedMs);
        assertEquals(2, evidence.raw);
        assertEquals(123_456L, evidence.halTimestampNs);
    }

    @Test
    public void realTransitionIsUnknownUntilTheSlowerRailAgrees() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.OTA, DiLink5LockRail.CARSVC);
        observe(ledger, DiLink5LockRail.CARSVC, LOCK, T0, T0);
        assertEquals(LOCK, ledger.current(T0 + 2_999L));
        observe(ledger, DiLink5LockRail.OTA, UNLOCK, T0 + 3_000L, T0 + 3_000L);
        for (long now = T0 + 3_000L; now < T0 + 8_000L; now += 100L) {
            assertEquals("now=" + now, INVALID, ledger.current(now));
        }
        observe(ledger, DiLink5LockRail.CARSVC, UNLOCK, T0 + 8_000L, T0 + 8_000L);
        DiLink5LockEvidence evidence = ledger.evidence(T0 + 8_000L);
        assertEquals(UNLOCK, evidence.state);
        assertSame(DiLink5LockRail.CARSVC, evidence.rail);
    }

    @Test
    public void persistentDisagreementBetweenFastAndSlowRailNeverYieldsKnownState() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.OTA, DiLink5LockRail.CARSVC);
        long end = T0 + 60_000L;
        for (long now = T0; now <= end; now++) {
            if ((now - T0) % 1_000L == 0L) observe(ledger, DiLink5LockRail.OTA, LOCK, now, now);
            if ((now - T0) % 8_000L == 0L) observe(ledger, DiLink5LockRail.CARSVC, UNLOCK, now, now);
            assertEquals("now=" + now, INVALID, ledger.current(now));
            assertNull(ledger.evidence(now).rail);
        }
        // Logged once per disagreement episode (plus the 30 s unknown cadence), naming both rails.
        int disagreementLines = 0;
        for (String line : lines) {
            if (line.contains("disagree=ota:2@")) {
                disagreementLines++;
                assertTrue(line, line.contains(",carsvc:1@"));
            }
        }
        assertTrue(lines.toString(), disagreementLines >= 1 && disagreementLines <= 3);
    }

    @Test
    public void staleButFreshEvidenceCountsUntilItsWindowCloses() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.OTA, DiLink5LockRail.CARSVC);
        observe(ledger, DiLink5LockRail.CARSVC, UNLOCK, T0 - 14_000L, T0 - 14_000L);
        observe(ledger, DiLink5LockRail.OTA, LOCK, T0, T0);
        assertEquals(INVALID, ledger.current(T0 + 1_000L));
        assertEquals(LOCK, ledger.current(T0 + 1_001L));
        assertSame(DiLink5LockRail.OTA, ledger.evidence(T0 + 1_001L).rail);
    }

    @Test
    public void fifteenSecondWindowIsUnchangedPerRail() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.OTA);
        observe(ledger, DiLink5LockRail.OTA, LOCK, T0, T0);
        assertEquals(LOCK, ledger.current(T0 + 15_000L));
        assertEquals(INVALID, ledger.current(T0 + 15_001L));
        assertNull(ledger.evidenceFor(DiLink5LockRail.OTA, T0 + 15_001L));
    }

    @Test
    public void futureReadIsRejectedByTheLedgerItself() {
        DiLink5LockSafetyCache cache = new DiLink5LockSafetyCache();
        cache.observe(LOCK, T0 + 5_000L);
        // Today's cache accepts a future read and then serves it once the clock catches up.
        assertEquals(LOCK, cache.current(T0 + 5_000L));
        DiLink5LockEvidenceLedger ledger = ledger();
        assertFalse(ledger.observe(DiLink5LockRail.TYPED_CABIN, LOCK, T0 + 1L, T0, 1, 0L));
        assertEquals(1L, ledger.droppedFuture());
        assertEquals(INVALID, ledger.current(T0 + 1L));
        assertEquals(INVALID, ledger.current(T0 + 5_000L));
        assertTrue(lines.isEmpty()); // a dropped reading never produces an evidence line
        assertTrue(ledger.observe(DiLink5LockRail.TYPED_CABIN, LOCK, T0, T0, 1, 0L));
        assertEquals(LOCK, ledger.current(T0));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0), lines.get(0).startsWith("DI5 gate evidence: state=2 rail=typed-cabin"));
    }

    @Test
    public void outOfDomainValuesAreRejectedPerRail() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.OTA);
        observe(ledger, DiLink5LockRail.OTA, LOCK, T0, T0);
        observe(ledger, DiLink5LockRail.OTA, 3, T0 + 100L, T0 + 100L);
        observe(ledger, DiLink5LockRail.OTA, -10, T0 + 200L, T0 + 200L);
        observe(ledger, DiLink5LockRail.OTA, Integer.MIN_VALUE, T0 + 300L, T0 + 300L);
        DiLink5LockEvidence evidence = ledger.evidence(T0 + 300L);
        assertEquals(LOCK, evidence.state);
        assertEquals(T0, evidence.observedAtElapsedMs);
        // A zero or negative read time is rejected by the per-rail cache, as today.
        observe(ledger, DiLink5LockRail.OTA, UNLOCK, 0L, T0 + 400L);
        assertEquals(LOCK, ledger.current(T0 + 400L));
    }

    // ---- enablement ------------------------------------------------------------------------------

    @Test
    public void disabledRailsAreInactive() {
        DiLink5LockEvidenceLedger ledger = ledger();
        assertEquals(EnumSet.of(DiLink5LockRail.TYPED_CABIN), ledger.enabledRails());
        assertFalse(ledger.observe(DiLink5LockRail.OTA, LOCK, T0, T0, 2, 0L));
        assertFalse(ledger.observe(DiLink5LockRail.CARSVC, LOCK, T0, T0, 2, 0L));
        assertEquals(2L, ledger.droppedDisabled());
        assertEquals(INVALID, ledger.current(T0));
        assertNull(ledger.evidenceFor(DiLink5LockRail.OTA, T0));
        assertTrue(lines.isEmpty());
        // A typed reading still goes through; a disabled rail cannot disagree with it.
        assertTrue(ledger.observe(DiLink5LockRail.TYPED_CABIN, UNLOCK, T0 + 1L, T0 + 1L, 2, 0L));
        assertFalse(ledger.observe(DiLink5LockRail.CARSVC, LOCK, T0 + 1L, T0 + 1L, 2, 0L));
        assertEquals(UNLOCK, ledger.current(T0 + 1L));
        assertEquals(3L, ledger.droppedDisabled());
    }

    @Test
    public void disablingARailClearsItsHeldEvidence() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.OTA);
        observe(ledger, DiLink5LockRail.OTA, LOCK, T0, T0);
        assertEquals(LOCK, ledger.current(T0 + 1L));
        ledger.setEnabledRails(EnumSet.of(DiLink5LockRail.TYPED_CABIN));
        assertEquals(INVALID, ledger.current(T0 + 1L));
        assertEquals(EnumSet.of(DiLink5LockRail.TYPED_CABIN), ledger.enabledRails());
        // Re-enabling does not resurrect the cleared reading.
        ledger.setEnabledRails(EnumSet.of(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.OTA));
        assertEquals(INVALID, ledger.current(T0 + 1L));
        assertNull(ledger.evidenceFor(DiLink5LockRail.OTA, T0 + 1L));
        assertTrue(lines.toString(), lines.contains("DI5 gate rails: typed-cabin"));
        assertTrue(lines.toString(), lines.contains("DI5 gate rails: typed-cabin,ota"));
    }

    @Test
    public void enabledSetIsAnUnmodifiableSnapshotAndNullFailsClosed() {
        DiLink5LockEvidenceLedger ledger = ledger();
        Set<DiLink5LockRail> snapshot = ledger.enabledRails();
        try {
            snapshot.add(DiLink5LockRail.OTA);
            throw new AssertionError("enabled set must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        EnumSet<DiLink5LockRail> mutable = EnumSet.of(DiLink5LockRail.OTA);
        ledger.setEnabledRails(mutable);
        mutable.add(DiLink5LockRail.CARSVC);
        assertEquals(EnumSet.of(DiLink5LockRail.OTA), ledger.enabledRails());
        ledger.setEnabledRails(null);
        assertTrue(ledger.enabledRails().isEmpty());
        assertFalse(ledger.observe(DiLink5LockRail.TYPED_CABIN, LOCK, T0, T0, 1, 0L));
        assertEquals(INVALID, ledger.current(T0));
    }

    @Test
    public void clearRetiresEveryRail() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.OTA,
                DiLink5LockRail.CARSVC);
        observe(ledger, DiLink5LockRail.TYPED_CABIN, LOCK, T0, T0);
        observe(ledger, DiLink5LockRail.OTA, LOCK, T0, T0);
        observe(ledger, DiLink5LockRail.CARSVC, LOCK, T0, T0);
        assertEquals(LOCK, ledger.current(T0));
        ledger.clear();
        assertEquals(INVALID, ledger.current(T0));
        for (DiLink5LockRail rail : DiLink5LockRail.values()) assertNull(ledger.evidenceFor(rail, T0));
        assertEquals(3, ledger.enabledRails().size());
    }

    // ---- enum ------------------------------------------------------------------------------------

    @Test
    public void enumHasExactlyThreeRailsAndRejectsOtherTags() {
        assertEquals(3, DiLink5LockRail.values().length);
        assertSame(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.fromTag("typed-cabin"));
        assertSame(DiLink5LockRail.OTA, DiLink5LockRail.fromTag("ota"));
        assertSame(DiLink5LockRail.CARSVC, DiLink5LockRail.fromTag("carsvc"));
        assertNull(DiLink5LockRail.fromTag("cloud"));
        assertNull(DiLink5LockRail.fromTag("OTA"));
        assertNull(DiLink5LockRail.fromTag(" ota"));
        assertNull(DiLink5LockRail.fromTag(null));
        assertEquals(EnumSet.of(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.OTA),
                DiLink5LockRail.parseTags("typed-cabin,ota"));
        assertEquals(EnumSet.of(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.OTA),
                DiLink5LockRail.parseTags("ota, typed-cabin"));
        assertEquals(EnumSet.allOf(DiLink5LockRail.class),
                DiLink5LockRail.parseTags("carsvc,ota,typed-cabin"));
        assertNull(DiLink5LockRail.parseTags("ota,ota"));
        assertNull(DiLink5LockRail.parseTags(""));
        assertNull(DiLink5LockRail.parseTags(null));
        assertNull(DiLink5LockRail.parseTags("typed-cabin,cloud"));
        assertNull(DiLink5LockRail.parseTags("typed-cabin,ota,carsvc,ota"));
        assertNull(DiLink5LockRail.parseTags("typed-cabin,"));
        assertEquals("typed-cabin,ota", DiLink5LockRail.toTags(
                EnumSet.of(DiLink5LockRail.OTA, DiLink5LockRail.TYPED_CABIN)));
        assertEquals("", DiLink5LockRail.toTags(EnumSet.noneOf(DiLink5LockRail.class)));
        assertEquals("typed-cabin", DiLink5LockRail.TYPED_CABIN.tag());
        assertEquals("ota", DiLink5LockRail.OTA.tag());
        assertEquals("carsvc", DiLink5LockRail.CARSVC.tag());
    }

    // ---- diagnostic cadence ------------------------------------------------------------------------

    @Test
    public void gateEvidenceLineIsLoggedOnChangeAndAtMostEveryTenSecondsWhileKnown() {
        DiLink5LockEvidenceLedger ledger = ledger();
        observe(ledger, DiLink5LockRail.TYPED_CABIN, LOCK, T0, T0);
        assertEquals(1, lines.size());
        assertEquals("DI5 gate evidence: state=2 rail=typed-cabin ageMs=0 raw=2 typed=2@0 ota=off"
                + " carsvc=off disagree=none", lines.get(0));
        // Same state every second: silent until 10 s have passed since the last line.
        for (long now = T0 + 1_000L; now < T0 + 10_000L; now += 1_000L) {
            observe(ledger, DiLink5LockRail.TYPED_CABIN, LOCK, now, now);
        }
        assertEquals(1, lines.size());
        observe(ledger, DiLink5LockRail.TYPED_CABIN, LOCK, T0 + 10_000L, T0 + 10_000L);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1), lines.get(1).startsWith("DI5 gate evidence: state=2 rail=typed-cabin ageMs=0"));
        // A change logs at once.
        observe(ledger, DiLink5LockRail.TYPED_CABIN, UNLOCK, T0 + 10_500L, T0 + 10_500L);
        assertEquals(3, lines.size());
        assertTrue(lines.get(2), lines.get(2).startsWith("DI5 gate evidence: state=1 rail=typed-cabin"));
        observe(ledger, DiLink5LockRail.TYPED_CABIN, INVALID, T0 + 11_000L, T0 + 11_000L);
        assertEquals(4, lines.size());
        assertEquals("DI5 gate evidence: state=0 rail=none ageMs=-1 raw=n/a typed=none ota=off"
                + " carsvc=off disagree=none", lines.get(3));
    }

    @Test
    public void gateEvidenceLineIsLoggedAtMostEveryThirtySecondsWhileUnknown() {
        DiLink5LockEvidenceLedger ledger = ledger();
        observe(ledger, DiLink5LockRail.TYPED_CABIN, INVALID, T0, T0);
        assertEquals(1, lines.size());
        for (long now = T0 + 1_000L; now < T0 + 30_000L; now += 1_000L) {
            observe(ledger, DiLink5LockRail.TYPED_CABIN, INVALID, now, now);
        }
        assertEquals(1, lines.size());
        observe(ledger, DiLink5LockRail.TYPED_CABIN, INVALID, T0 + 30_000L, T0 + 30_000L);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1), lines.get(1).startsWith("DI5 gate evidence: state=0 rail=none"));
    }

    @Test
    public void disagreementLineNamesBothRailsAndIsLoggedOncePerEpisode() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.OTA, DiLink5LockRail.CARSVC);
        observe(ledger, DiLink5LockRail.OTA, LOCK, T0, T0);
        assertEquals(1, lines.size());
        assertEquals("DI5 gate evidence: state=2 rail=ota ageMs=0 raw=2 typed=off ota=2@0 carsvc=none"
                + " disagree=none", lines.get(0));
        observe(ledger, DiLink5LockRail.CARSVC, UNLOCK, T0 + 3_000L, T0 + 3_000L);
        assertEquals(2, lines.size());
        assertEquals("DI5 gate evidence: state=0 rail=none ageMs=-1 raw=n/a typed=off ota=2@3000"
                + " carsvc=1@0 disagree=ota:2@3000,carsvc:1@0", lines.get(1));
        // The episode continues: ageing alone is not a change, and the unknown cadence is 30 s.
        observe(ledger, DiLink5LockRail.OTA, LOCK, T0 + 4_000L, T0 + 4_000L);
        observe(ledger, DiLink5LockRail.CARSVC, UNLOCK, T0 + 9_000L, T0 + 9_000L);
        assertEquals(2, lines.size());
        // Agreement ends the episode and logs the new known state at once.
        observe(ledger, DiLink5LockRail.CARSVC, LOCK, T0 + 10_000L, T0 + 10_000L);
        assertEquals(3, lines.size());
        assertEquals("DI5 gate evidence: state=2 rail=carsvc ageMs=0 raw=2 typed=off ota=2@6000"
                + " carsvc=2@0 disagree=none", lines.get(2));
    }

    @Test
    public void legacyPairRawIsReportedAsUnavailable() {
        DiLink5LockEvidenceLedger ledger = ledger();
        ledger.observe(DiLink5LockRail.TYPED_CABIN, LOCK, T0, T0, Integer.MIN_VALUE, 0L);
        assertTrue(lines.get(0), lines.get(0).contains(" raw=n/a "));
        assertEquals(Integer.MIN_VALUE, ledger.evidence(T0).raw);
    }
}
