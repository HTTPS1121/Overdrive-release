package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.DiLink5LockRail;
import com.overdrive.app.remoteaccess.BluetoothProximityDecisionGuard;
import com.overdrive.app.remoteaccess.DiLink5GateActuationPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Q14 (planner deviation): the pre-dispatch minimum-lease check applies ONLY when the actuation rail
 * is {@code ota} or {@code carsvc}; the typed rail (the default) and non-Di5 keep today's behaviour.
 */
public class RemoteAccessApiHandlerLeaseTest {
    private static final long OBSERVED = 50_000L;
    private static final long FRESHNESS = 5_000L;

    private static boolean blocked(DiLink5LockRail rail, long remainingMs) {
        long now = OBSERVED + FRESHNESS - remainingMs;
        return DiLink5GateActuationPolicy.insufficientLease(rail, OBSERVED, FRESHNESS, now);
    }

    @Test
    public void carsvcAndOtaNeedAtLeastFifteenHundredMillisecondsLeft() {
        assertEquals(1_500L, DiLink5GateActuationPolicy.MIN_ACTUATION_LEASE_MS);
        for (DiLink5LockRail rail : new DiLink5LockRail[]{DiLink5LockRail.CARSVC, DiLink5LockRail.OTA}) {
            assertTrue(rail.name(), blocked(rail, 1_499L));
            assertFalse(rail.name(), blocked(rail, 1_500L));
            assertFalse(rail.name(), blocked(rail, 4_999L));
            assertTrue(rail.name(), blocked(rail, 0L));
            assertTrue(rail.name(), blocked(rail, -10L));
        }
        assertEquals(1_499L, DiLink5GateActuationPolicy.remainingLeaseMs(OBSERVED, FRESHNESS, OBSERVED + FRESHNESS - 1_499L));
        assertEquals(-10L, DiLink5GateActuationPolicy.remainingLeaseMs(OBSERVED, FRESHNESS, OBSERVED + FRESHNESS + 10L));
    }

    @Test
    public void leaseCheckIsInactiveForTypedRail() {
        // Typed (the default) with 100 ms left is NOT blocked here — today's behaviour exactly.
        assertFalse(blocked(DiLink5LockRail.TYPED_CABIN, 100L));
        assertFalse(blocked(DiLink5LockRail.TYPED_CABIN, 0L));
        assertFalse(blocked(DiLink5LockRail.TYPED_CABIN, -1L));
        // No rail (non-Di5, or unknown evidence) is never blocked here either.
        assertFalse(blocked(null, 100L));
        assertFalse(blocked(null, -1L));
    }

    @Test
    public void minimumLeaseFitsInsideTheActionLease() {
        assertTrue(DiLink5GateActuationPolicy.MIN_ACTUATION_LEASE_MS
                < BluetoothProximityDecisionGuard.MAX_ACTION_LEASE_MS);
    }

    @Test
    public void performDecisionBlocksOnlyNonTypedRailsAndKeepsTheNumberOutOfTheEvent() throws Exception {
        String handler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/RemoteAccessApiHandler.java");
        int start = handler.indexOf("private static void performDecision(");
        int end = handler.indexOf("private static JSONObject bridge(String operation)", start);
        String body = handler.substring(start, end);
        assertEquals(1, count(body, "recordAction(action, \"blocked\", \"Insufficient lease left for local actuation\");"));
        assertTrue(body.contains("if (DiLink5GateActuationPolicy.insufficientLease(\n"
                + "                    actuationRail, decision.observed, decision.freshnessMs, now)) {"));
        // D1: the remaining ms goes to the daemon log, never into the recordAction message.
        assertTrue(body.contains("\"Proximity: DI5 insufficient lease rail=\""));
        assertTrue(body.contains("remainingMs=\" + DiLink5GateActuationPolicy"));
        for (int at = body.indexOf("recordAction(action,"); at >= 0; at = body.indexOf("recordAction(action,", at + 1)) {
            String call = body.substring(at, body.indexOf(");", at));
            assertFalse(call, call.contains("remainingMs"));
        }
        // Placement: after the second already-at-target check, before the second cooldown check,
        // and after the actuation rail is known.
        int secondAlready = body.indexOf("if (finalLock == target) {");
        int rail = body.indexOf("DiLink5GateActuationPolicy.preferredActuationRail(finalEvidence, otaEvidence);", secondAlready);
        int lease = body.indexOf("Insufficient lease left for local actuation", rail);
        int cooldown = body.indexOf("lastActionElapsed, settings.optLong(\"cooldownMs\"), lastCommandConfirmed, finalLock)", lease);
        assertTrue(secondAlready > 0 && rail > secondAlready && lease > rail && cooldown > lease);
        // The numeric gate and its text are still verbatim.
        assertTrue(body.contains("if (before != 1 && before != 2) {\n"
                + "                recordAction(action, \"blocked\", \"Local lock state is unknown\");"));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    private static String readRepositoryFile(String relative) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path)) {
            path = Paths.get(System.getProperty("user.dir")).getParent().resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
