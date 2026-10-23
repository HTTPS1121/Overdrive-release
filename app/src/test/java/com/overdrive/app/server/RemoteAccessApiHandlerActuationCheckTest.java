package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.DiLink5LockRail;
import com.overdrive.app.remoteaccess.DiLink5GateActuationPolicy;
import com.overdrive.app.remoteaccess.DiLink5ProximityCommandGuard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Case (C) daemon re-validation at the app's pre-write relay check: the current carsvc evidence must
 * be fresh (≤ 6 s), valid, opposite to the target and from the carsvc rail; the evidence is read
 * BEFORE {@code synchronized (STATE)}. Plus the actuation-rail preference and the scope builder.
 */
public class RemoteAccessApiHandlerActuationCheckTest {
    private static final String TOKEN = "00000000-0000-0000-0000-000000000001";
    private static final long NOW = 200_000L;

    private static DiLink5ProximityCommandGuard.Scope scope(String action, String rail) {
        return new DiLink5ProximityCommandGuard.Scope(TOKEN, action, "di5-generation", 7L, NOW + 3_000L,
                rail, "unlock".equals(action) ? 2 : 1, NOW - 1_000L, 0);
    }

    private static BydDataCollector.DiLink5LockEvidence evidence(DiLink5LockRail rail, int state, long ageMs) {
        return new BydDataCollector.DiLink5LockEvidence(state, NOW - ageMs, rail, state, 0L);
    }

    @Test
    public void freshOppositeCarsvcEvidenceAllowsTheWrite() {
        DiLink5ProximityCommandGuard.Scope unlock = scope("unlock", "carsvc");
        assertTrue(DiLink5GateActuationPolicy.carsvcEvidenceAllowsWrite(
                evidence(DiLink5LockRail.CARSVC, BydDataCollector.DOOR_STATE_LOCK, 0L), unlock, NOW));
        assertTrue(DiLink5GateActuationPolicy.carsvcEvidenceAllowsWrite(
                evidence(DiLink5LockRail.CARSVC, BydDataCollector.DOOR_STATE_LOCK, 6_000L), unlock, NOW));
        DiLink5ProximityCommandGuard.Scope lock = scope("lock", "carsvc");
        assertTrue(DiLink5GateActuationPolicy.carsvcEvidenceAllowsWrite(
                evidence(DiLink5LockRail.CARSVC, BydDataCollector.DOOR_STATE_UNLOCK, 5_999L), lock, NOW));
        assertEquals(6_000L, DiLink5GateActuationPolicy.CARSVC_EVIDENCE_WRITE_MAX_AGE_MS);
    }

    @Test
    public void staleAtTargetOtherRailInvalidAndMissingEvidenceDenyTheWrite() {
        DiLink5ProximityCommandGuard.Scope unlock = scope("unlock", "carsvc");
        // 6 001 ms old
        assertFalse(DiLink5GateActuationPolicy.carsvcEvidenceAllowsWrite(
                evidence(DiLink5LockRail.CARSVC, BydDataCollector.DOOR_STATE_LOCK, 6_001L), unlock, NOW));
        // already at the target (unlocked)
        assertFalse(DiLink5GateActuationPolicy.carsvcEvidenceAllowsWrite(
                evidence(DiLink5LockRail.CARSVC, BydDataCollector.DOOR_STATE_UNLOCK, 0L), unlock, NOW));
        // other rails cannot authorise a delegated write
        assertFalse(DiLink5GateActuationPolicy.carsvcEvidenceAllowsWrite(
                evidence(DiLink5LockRail.TYPED_CABIN, BydDataCollector.DOOR_STATE_LOCK, 0L), unlock, NOW));
        assertFalse(DiLink5GateActuationPolicy.carsvcEvidenceAllowsWrite(
                evidence(DiLink5LockRail.OTA, BydDataCollector.DOOR_STATE_LOCK, 0L), unlock, NOW));
        // INVALID / unknown / null / future-stamped
        assertFalse(DiLink5GateActuationPolicy.carsvcEvidenceAllowsWrite(
                evidence(DiLink5LockRail.CARSVC, BydDataCollector.DOOR_STATE_INVALID, 0L), unlock, NOW));
        assertFalse(DiLink5GateActuationPolicy.carsvcEvidenceAllowsWrite(
                BydDataCollector.DiLink5LockEvidence.unknown(), unlock, NOW));
        assertFalse(DiLink5GateActuationPolicy.carsvcEvidenceAllowsWrite(null, unlock, NOW));
        assertFalse(DiLink5GateActuationPolicy.carsvcEvidenceAllowsWrite(
                evidence(DiLink5LockRail.CARSVC, BydDataCollector.DOOR_STATE_LOCK, -1L), unlock, NOW));
        assertFalse(DiLink5GateActuationPolicy.carsvcEvidenceAllowsWrite(
                evidence(DiLink5LockRail.CARSVC, BydDataCollector.DOOR_STATE_LOCK, 0L), null, NOW));
        assertEquals(1, DiLink5GateActuationPolicy.targetOf("unlock"));
        assertEquals(2, DiLink5GateActuationPolicy.targetOf("lock"));
    }

    @Test
    public void actuationRailPrefersFreshOtaEvidenceElseTheSelectedRail() {
        BydDataCollector.DiLink5LockEvidence typed = evidence(DiLink5LockRail.TYPED_CABIN, 2, 100L);
        BydDataCollector.DiLink5LockEvidence carsvc = evidence(DiLink5LockRail.CARSVC, 2, 100L);
        BydDataCollector.DiLink5LockEvidence ota = evidence(DiLink5LockRail.OTA, 2, 100L);
        // Default setting: the OTA rail is disabled → evidenceFor(OTA) is null → typed.
        assertEquals(DiLink5LockRail.TYPED_CABIN, DiLink5GateActuationPolicy.preferredActuationRail(typed, null));
        assertEquals(DiLink5LockRail.CARSVC, DiLink5GateActuationPolicy.preferredActuationRail(carsvc, null));
        assertEquals(DiLink5LockRail.OTA, DiLink5GateActuationPolicy.preferredActuationRail(typed, ota));
        assertEquals(DiLink5LockRail.OTA, DiLink5GateActuationPolicy.preferredActuationRail(ota, ota));
        // Unknown / INVALID OTA evidence does not steer the actuation; a mislabelled one is ignored.
        assertEquals(DiLink5LockRail.CARSVC, DiLink5GateActuationPolicy.preferredActuationRail(
                carsvc, BydDataCollector.DiLink5LockEvidence.unknown()));
        assertEquals(DiLink5LockRail.CARSVC, DiLink5GateActuationPolicy.preferredActuationRail(
                carsvc, evidence(DiLink5LockRail.OTA, 0, 100L)));
        assertEquals(DiLink5LockRail.CARSVC, DiLink5GateActuationPolicy.preferredActuationRail(carsvc, typed));
        assertNull(DiLink5GateActuationPolicy.preferredActuationRail(null, null));
        assertNull(DiLink5GateActuationPolicy.preferredActuationRail(
                BydDataCollector.DiLink5LockEvidence.unknown(), null));
    }

    @Test
    public void gateScopeCarriesTheActuationRailsEvidenceOrNothing() throws Exception {
        BydDataCollector.DiLink5LockEvidence carsvc =
                new BydDataCollector.DiLink5LockEvidence(2, 123_456L, DiLink5LockRail.CARSVC, 2, 99L);
        DiLink5ProximityCommandGuard.Scope scope = DiLink5GateActuationPolicy.gateScope(
                TOKEN, "unlock", "gen", 7L, 9_000L, carsvc);
        assertEquals(DiLink5LockRail.CARSVC, scope.gateRailOrNull());
        assertEquals(2, scope.gateState);
        assertEquals(123_456L, scope.gateObservedElapsedMs);
        assertEquals(2, scope.gateRaw);
        assertEquals(7L, scope.revision);
        assertEquals(9_000L, scope.expiresAt);
        // Unknown, null, rail-less or time-less evidence → plain scope (today's typed path in the app).
        for (BydDataCollector.DiLink5LockEvidence absent : new BydDataCollector.DiLink5LockEvidence[]{
                null, BydDataCollector.DiLink5LockEvidence.unknown(),
                new BydDataCollector.DiLink5LockEvidence(2, 0L, DiLink5LockRail.CARSVC, 2, 0L),
                new BydDataCollector.DiLink5LockEvidence(2, 10L, null, 2, 0L),
                new BydDataCollector.DiLink5LockEvidence(0, 10L, DiLink5LockRail.CARSVC, 0, 0L)}) {
            DiLink5ProximityCommandGuard.Scope plain = DiLink5GateActuationPolicy.gateScope(
                    TOKEN, "lock", "gen", 7L, 9_000L, absent);
            assertNull(plain.gateRailOrNull());
            assertEquals(5, plain.toJson().length());
        }
    }

    @Test
    public void relayActuationCheckReadsTheEvidenceBeforeStateAndRevalidatesCarsvcScopes() throws Exception {
        String handler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/RemoteAccessApiHandler.java");
        int start = handler.indexOf("private static void relayActuationCheck(");
        int end = handler.indexOf("private static void relayKeyFob(", start);
        String body = handler.substring(start, end);
        int read = body.indexOf("BydDataCollector.getInstance().readDiLink5GateDoorLockEvidence() : null;");
        int state = body.indexOf("synchronized (STATE) {");
        assertTrue(read > 0 && state > read);
        assertTrue(body.contains("boolean carsvcScope = scope.gateRailOrNull() == DiLink5LockRail.CARSVC;"));
        assertTrue(body.contains("carsvcScope ? BydDataCollector.getInstance().readDiLink5GateDoorLockEvidence() : null;"));
        // The re-validation is one more conjunct of the existing allowed predicate, inside STATE.
        int allowed = body.indexOf("allowed = pending != null && pending.scope.token.equals(scope.token)", state);
        int revalidate = body.indexOf("&& (!carsvcScope || DiLink5GateActuationPolicy.carsvcEvidenceAllowsWrite(\n"
                + "                            carsvcEvidence, scope, SystemClock.elapsedRealtime()));", allowed);
        assertTrue(allowed > state && revalidate > allowed);
        assertTrue(body.contains("&& scope.locallyCurrent() && pending.decision.validForDelayedActuation()\n"));
        // No SDK read, no ACTION lock, no dump here (unchanged contract of the relay).
        assertFalse(body.contains("synchronized (ACTION)"));
        assertFalse(body.contains("dumpsys"));
        assertFalse(body.contains("readDoorLockState()"));
    }

    private static String readRepositoryFile(String relative) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path)) {
            path = Paths.get(System.getProperty("user.dir")).getParent().resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
