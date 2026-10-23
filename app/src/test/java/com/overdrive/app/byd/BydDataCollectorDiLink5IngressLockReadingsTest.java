package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydDataCollector.DiLink5LockEvidence;
import com.overdrive.app.byd.BydDataCollector.DiLink5LockEvidenceLedger;
import com.overdrive.app.byd.BydDataCollector.DiLink5LockReading;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import org.junit.Test;

/**
 * Single-ingress {@code lockReadings} decode (M4): the two static pieces are composed exactly as
 * the 12-argument {@code acceptDiLink5Telemetry} does — one {@code isNewerDiLink5Message} check,
 * then {@code observeDiLink5BridgedLockReadings} under the ingress lock.
 */
public class BydDataCollectorDiLink5IngressLockReadingsTest {
    private static final int INVALID = BydDataCollector.DOOR_STATE_INVALID;
    private static final int UNLOCK = BydDataCollector.DOOR_STATE_UNLOCK;
    private static final int LOCK = BydDataCollector.DOOR_STATE_LOCK;
    private static final long NOW = 2_000_000L;
    private static final long SENT = NOW - 200L;

    private final List<String> lines = new ArrayList<>();

    /** Mirrors the ingress: sequence check first, then the pure observe helper. */
    private static final class Ingress {
        final DiLink5LockEvidenceLedger ledger;
        long epoch;
        long sequence;
        int typedObservations;

        Ingress(DiLink5LockEvidenceLedger ledger) { this.ledger = ledger; }

        boolean accept(long sourceEpoch, long seq, int doorLockState, long doorLockObservedAt,
                List<DiLink5LockReading> lockReadings) {
            if (!BydDataCollector.isNewerDiLink5Message(epoch, sequence, sourceEpoch, seq)) {
                return false;
            }
            epoch = sourceEpoch;
            sequence = seq;
            long before = countTyped(ledger);
            BydDataCollector.observeDiLink5BridgedLockReadings(ledger, lockReadings, doorLockState,
                    doorLockObservedAt, SENT, NOW);
            typedObservations += countTyped(ledger) - before;
            return true;
        }

        private static long countTyped(DiLink5LockEvidenceLedger ledger) {
            DiLink5LockEvidence typed = ledger.evidenceFor(DiLink5LockRail.TYPED_CABIN, NOW);
            return typed == null ? 0L : typed.observedAtElapsedMs;
        }
    }

    private DiLink5LockEvidenceLedger ledger(DiLink5LockRail... rails) {
        DiLink5LockEvidenceLedger ledger = new DiLink5LockEvidenceLedger(lines::add);
        ledger.setEnabledRails(EnumSet.of(rails[0], rails));
        lines.clear();
        return ledger;
    }

    private static DiLink5LockReading reading(DiLink5LockRail rail, int state, long observedAt) {
        return new DiLink5LockReading(rail, state, state, observedAt);
    }

    @Test
    public void typedAndOtaReadingsOfOneMessageBothLand() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.OTA);
        Ingress ingress = new Ingress(ledger);
        assertTrue(ingress.accept(7L, 1L, INVALID, 0L, Arrays.asList(
                reading(DiLink5LockRail.TYPED_CABIN, LOCK, SENT - 900L),
                reading(DiLink5LockRail.OTA, LOCK, SENT - 400L))));
        DiLink5LockEvidence typed = ledger.evidenceFor(DiLink5LockRail.TYPED_CABIN, NOW);
        DiLink5LockEvidence ota = ledger.evidenceFor(DiLink5LockRail.OTA, NOW);
        assertNotNull(typed);
        assertNotNull(ota);
        assertEquals(SENT - 900L, typed.observedAtElapsedMs);
        assertEquals(SENT - 400L, ota.observedAtElapsedMs);
        assertEquals(LOCK, ledger.current(NOW));
        assertSame(DiLink5LockRail.OTA, ledger.evidence(NOW).rail);
    }

    @Test
    public void replayedSequenceIsRejectedBeforeAnyObservation() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.OTA);
        Ingress ingress = new Ingress(ledger);
        assertTrue(ingress.accept(7L, 3L, INVALID, 0L, Collections.singletonList(
                reading(DiLink5LockRail.TYPED_CABIN, LOCK, SENT - 500L))));
        assertEquals(LOCK, ledger.current(NOW));
        int linesBefore = lines.size();
        // Same (epoch, sequence) with a contradicting payload: nothing reaches the ledger.
        assertFalse(ingress.accept(7L, 3L, INVALID, 0L, Collections.singletonList(
                reading(DiLink5LockRail.TYPED_CABIN, UNLOCK, SENT - 100L))));
        assertFalse(ingress.accept(7L, 2L, INVALID, 0L, Collections.singletonList(
                reading(DiLink5LockRail.OTA, UNLOCK, SENT - 100L))));
        assertEquals(LOCK, ledger.current(NOW));
        assertEquals(SENT - 500L, ledger.evidence(NOW).observedAtElapsedMs);
        assertNull(ledger.evidenceFor(DiLink5LockRail.OTA, NOW));
        assertEquals(linesBefore, lines.size());
        // A newer epoch is accepted again.
        assertTrue(ingress.accept(8L, 1L, INVALID, 0L, Collections.singletonList(
                reading(DiLink5LockRail.TYPED_CABIN, UNLOCK, SENT - 100L))));
        assertEquals(UNLOCK, ledger.current(NOW));
    }

    @Test
    public void presentLockReadingsMakeTheLegacyPairIgnoredForTheGate() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.OTA);
        Ingress ingress = new Ingress(ledger);
        // Legacy pair says LOCK read at SENT-50; the list's typed entry says UNLOCK at SENT-300.
        assertTrue(ingress.accept(1L, 1L, LOCK, SENT - 50L, Collections.singletonList(
                reading(DiLink5LockRail.TYPED_CABIN, UNLOCK, SENT - 300L))));
        DiLink5LockEvidence typed = ledger.evidenceFor(DiLink5LockRail.TYPED_CABIN, NOW);
        assertNotNull(typed);
        assertEquals(UNLOCK, typed.state);
        assertEquals(SENT - 300L, typed.observedAtElapsedMs); // typed observed exactly once: the entry
        assertEquals(UNLOCK, ledger.current(NOW));
    }

    @Test
    public void absentLockReadingsFeedTheLegacyPairToTheTypedRail() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.TYPED_CABIN);
        Ingress ingress = new Ingress(ledger);
        assertTrue(ingress.accept(1L, 1L, LOCK, SENT - 50L, null));
        DiLink5LockEvidence evidence = ledger.evidence(NOW);
        assertEquals(LOCK, evidence.state);
        assertSame(DiLink5LockRail.TYPED_CABIN, evidence.rail);
        assertEquals(SENT - 50L, evidence.observedAtElapsedMs);
        assertEquals(Integer.MIN_VALUE, evidence.raw); // legacy pair carries no raw value
        // The legacy guard is unchanged: a read stamped after the send time is not observed.
        assertTrue(ingress.accept(1L, 2L, UNLOCK, SENT + 1L, null));
        assertEquals(LOCK, ledger.current(NOW));
        // INVALID through the legacy pair still revokes, as today.
        assertTrue(ingress.accept(1L, 3L, INVALID, SENT, null));
        assertEquals(INVALID, ledger.current(NOW));
    }

    @Test
    public void emptyLockReadingsBehaveLikeAbsent() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.TYPED_CABIN);
        Ingress ingress = new Ingress(ledger);
        assertTrue(ingress.accept(1L, 1L, LOCK, SENT - 50L, Collections.emptyList()));
        DiLink5LockEvidence evidence = ledger.evidence(NOW);
        assertEquals(LOCK, evidence.state);
        assertSame(DiLink5LockRail.TYPED_CABIN, evidence.rail);
        assertEquals(SENT - 50L, evidence.observedAtElapsedMs);
    }

    @Test
    public void entryReadAfterTheSendTimeIsDroppedWhileOthersLand() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.OTA);
        Ingress ingress = new Ingress(ledger);
        assertTrue(ingress.accept(1L, 1L, INVALID, 0L, Arrays.asList(
                reading(DiLink5LockRail.TYPED_CABIN, UNLOCK, SENT + 1L),
                null,
                reading(DiLink5LockRail.OTA, LOCK, SENT))));
        assertNull(ledger.evidenceFor(DiLink5LockRail.TYPED_CABIN, NOW));
        DiLink5LockEvidence ota = ledger.evidenceFor(DiLink5LockRail.OTA, NOW);
        assertNotNull(ota);
        assertEquals(LOCK, ota.state);
        assertEquals(SENT, ota.observedAtElapsedMs);
        assertEquals(0L, ledger.droppedFuture());
    }

    @Test
    public void readingForADisabledRailIsDroppedByTheLedger() {
        DiLink5LockEvidenceLedger ledger = ledger(DiLink5LockRail.TYPED_CABIN);
        Ingress ingress = new Ingress(ledger);
        assertTrue(ingress.accept(1L, 1L, INVALID, 0L, Arrays.asList(
                reading(DiLink5LockRail.OTA, LOCK, SENT - 10L),
                reading(DiLink5LockRail.CARSVC, LOCK, SENT - 10L))));
        assertEquals(INVALID, ledger.current(NOW));
        assertEquals(2L, ledger.droppedDisabled());
    }

    @Test
    public void collectorKeepsOneSequenceCheckAndNoSeparateLockReadingsIngress() throws Exception {
        String collector = read("app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        assertEquals(3, count(collector, "isNewerDiLink5Message("));
        assertFalse(collector.contains("acceptDiLink5LockReadings"));
        assertFalse(collector.contains("noteDiLink5GateLock"));
        // 11-arg overload delegates with a null lockReadings list.
        assertTrue(collector.contains(
                "            int[] doorStates,\n"
                        + "            long[] doorStatesObservedAtElapsedMs) {\n"
                        + "        return acceptDiLink5Telemetry(incoming, sourceEpoch, sequence, sentElapsedMs,\n"
                        + "                dynamicsObservedAtElapsedMs, charging, energyFeedback, doorLockState,\n"
                        + "                doorLockObservedAtElapsedMs, doorStates, doorStatesObservedAtElapsedMs, null);"));
        int twelveArg = collector.indexOf(
                "            long[] doorStatesObservedAtElapsedMs,\n"
                        + "            java.util.List<DiLink5LockReading> lockReadings) {");
        assertTrue(twelveArg > 0);
        int sequenceCheck = collector.indexOf("if (!isNewerDiLink5Message(", twelveArg);
        int observe = collector.indexOf(
                "observeDiLink5BridgedLockReadings(diLink5GateLock, lockReadings, doorLockState,\n"
                        + "                    doorLockObservedAtElapsedMs, sampleElapsed, nowElapsed);", twelveArg);
        int initialized = collector.indexOf("initialized = true;", twelveArg);
        assertTrue(sequenceCheck > twelveArg && observe > sequenceCheck && initialized > observe);
        // Display field update unchanged and before the gate observation.
        int display = collector.indexOf("diLink5DoorLockState = doorLockState;", twelveArg);
        assertTrue(display > sequenceCheck && display < observe);
        assertEquals(1, count(collector, "observeDiLink5BridgedLockReadings(diLink5GateLock"));
        // The gate field is the ledger; stop() still clears it; the state read is ledger.current.
        assertTrue(collector.contains(
                "    private final DiLink5LockEvidenceLedger diLink5GateLock =\n"
                        + "            new DiLink5LockEvidenceLedger(logger::info);"));
        assertTrue(collector.contains("            diLink5GateLock.clear();"));
        assertTrue(collector.contains(
                "                    ? diLink5GateLock.current(SystemClock.elapsedRealtime()) : DOOR_STATE_INVALID;"));
        assertTrue(collector.contains(
                "    public DiLink5LockEvidence readDiLink5GateDoorLockEvidence() {\n"
                        + "        if (!isDiLink5Vehicle() || isMainAppProcess()) return DiLink5LockEvidence.unknown();"));
        assertTrue(collector.contains(
                "    public void setDiLink5LockRails(java.util.Set<DiLink5LockRail> rails) {"));
        // Structural cloud exclusion: nothing under byd/cloud near the ledger or the gate reads.
        int ledgerStart = collector.indexOf("    static final class DiLink5LockEvidenceLedger {");
        int ledgerEnd = collector.indexOf("    /** An atomic per-area safety copy", ledgerStart);
        assertTrue(ledgerStart > 0 && ledgerEnd > ledgerStart);
        assertFalse(collector.substring(ledgerStart, ledgerEnd).contains("cloud"));
        int gateRead = collector.indexOf("    public int readDiLink5GateDoorLockState() {");
        int gateEnd = collector.indexOf("    public void setDiLink5CarSvcLockMaxEventAgeSec(", gateRead);
        assertTrue(gateRead > 0 && gateEnd > gateRead);
        assertFalse(collector.substring(gateRead, gateEnd).contains("cloud"));
        assertFalse(collector.substring(gateRead, gateEnd).contains("Cloud"));
    }

    /** Item 1.5b: the bridge schema round trip, composed with the 1.2 helper exactly as the ingress does. */
    @Test
    public void encodeDecodeRoundTripFeedsTheLedgerExactlyAsTheIngressDoes() throws Exception {
        long now = 2_000_000L;
        long sent = now - 200L;
        java.util.List<String> lines = new java.util.ArrayList<>();
        BydDataCollector.DiLink5LockEvidenceLedger ledger =
                new BydDataCollector.DiLink5LockEvidenceLedger(lines::add);
        ledger.setEnabledRails(java.util.EnumSet.of(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.OTA));
        // Producer: typed INVALID (raw -10) at sent-900, OTA debounced LOCK (raw 2) at sent-400,
        // legacy pair LOCK@sent-50 says something else on purpose.
        org.json.JSONArray encoded = BydDataCollector.encodeDiLink5LockReadings(
                INVALID, -10, sent - 900L, true, LOCK, 2, sent - 400L);
        java.util.List<BydDataCollector.DiLink5LockReading> decoded =
                BydDataCollector.decodeDiLink5LockReadings(encoded);
        BydDataCollector.observeDiLink5BridgedLockReadings(
                ledger, decoded, LOCK, sent - 50L, sent, now);
        // Array present → legacy pair ignored: typed is INVALID (revoked), OTA holds LOCK with raw.
        assertNull(ledger.evidenceFor(DiLink5LockRail.TYPED_CABIN, now));
        BydDataCollector.DiLink5LockEvidence ota = ledger.evidenceFor(DiLink5LockRail.OTA, now);
        assertEquals(LOCK, ota.state);
        assertEquals(2, ota.raw);
        assertEquals(sent - 400L, ota.observedAtElapsedMs);
        assertEquals(LOCK, ledger.current(now));
        assertEquals(DiLink5LockRail.OTA, ledger.evidence(now).rail);
        // Array absent (older producer) → legacy pair → TYPED_CABIN, as before this change.
        BydDataCollector.DiLink5LockEvidenceLedger legacy =
                new BydDataCollector.DiLink5LockEvidenceLedger(lines::add);
        BydDataCollector.observeDiLink5BridgedLockReadings(
                legacy, BydDataCollector.decodeDiLink5LockReadings(null), LOCK, sent - 50L, sent, now);
        BydDataCollector.DiLink5LockEvidence typed = legacy.evidence(now);
        assertEquals(LOCK, typed.state);
        assertEquals(DiLink5LockRail.TYPED_CABIN, typed.rail);
        assertEquals(sent - 50L, typed.observedAtElapsedMs);
        // Default setting on both sides: the array carries the typed entry only, and the gate
        // observes exactly what the legacy pair would have (same state, same read time).
        BydDataCollector.DiLink5LockEvidenceLedger defaults =
                new BydDataCollector.DiLink5LockEvidenceLedger(lines::add);
        org.json.JSONArray typedOnly = BydDataCollector.encodeDiLink5LockReadings(
                LOCK, 1, sent - 50L, false, LOCK, 2, sent - 400L);
        assertEquals(1, typedOnly.length());
        BydDataCollector.observeDiLink5BridgedLockReadings(defaults,
                BydDataCollector.decodeDiLink5LockReadings(typedOnly), LOCK, sent - 50L, sent, now);
        assertEquals(LOCK, defaults.current(now));
        assertEquals(sent - 50L, defaults.evidence(now).observedAtElapsedMs);
        assertEquals(DiLink5LockRail.TYPED_CABIN, defaults.evidence(now).rail);
        assertEquals(0L, defaults.droppedDisabled());
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static String read(String relative) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path)) {
            path = Paths.get(System.getProperty("user.dir")).getParent().resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
