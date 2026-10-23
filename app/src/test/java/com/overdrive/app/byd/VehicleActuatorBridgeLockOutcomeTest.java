package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * 1.6a outcome hand-off: {@code setDiLink5AllDoorsLocked} (app) → {@code DILINK5_RESULT_LOCK_OUTCOME}
 * Bundle key (VehicleActuatorService) → daemon thread-local (dispatchDiLink5Command) →
 * {@code performDecision}. The thread-locals are exercised directly; the Android-bound wiring is pinned
 * by source.
 */
public class VehicleActuatorBridgeLockOutcomeTest {

    @Test
    public void daemonOutcomeIsReturnedOnceThenNull() {
        VehicleActuatorBridge.noteDiLink5LockOutcome("WRITTEN_UNCONFIRMED");
        assertEquals("WRITTEN_UNCONFIRMED", VehicleActuatorBridge.takeLastDiLink5LockOutcome());
        assertNull(VehicleActuatorBridge.takeLastDiLink5LockOutcome());
        // A later dispatch without an outcome (older app, non-lock command) clears a stale value.
        VehicleActuatorBridge.noteDiLink5LockOutcome("CONFIRMED");
        VehicleActuatorBridge.noteDiLink5LockOutcome(null);
        assertNull(VehicleActuatorBridge.takeLastDiLink5LockOutcome());
    }

    @Test
    public void outcomeIsPerThread() throws Exception {
        VehicleActuatorBridge.noteDiLink5LockOutcome("FAILED");
        String[] other = new String[]{"unset"};
        Thread thread = new Thread(() -> other[0] = VehicleActuatorBridge.takeLastDiLink5LockOutcome());
        thread.start();
        thread.join();
        assertNull(other[0]);
        assertEquals("FAILED", VehicleActuatorBridge.takeLastDiLink5LockOutcome());
    }

    @Test
    public void noProximityScopeWithoutARequestScope() {
        assertNull(VehicleActuatorBridge.currentDiLink5ProximityScope());
        assertFalse(VehicleActuatorBridge.hasDiLink5RequestScope());
        assertEquals("lockOutcome", VehicleActuatorBridge.DILINK5_RESULT_LOCK_OUTCOME);
    }

    @Test
    public void bridgeRecordsTheOutcomeOfEveryDispatchResult() throws Exception {
        String bridge = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/VehicleActuatorBridge.java");
        int dispatch = bridge.indexOf("public static boolean dispatchDiLink5Command(");
        int next = bridge.indexOf("static void applyDiLink5CommandResult(", dispatch);
        String body = bridge.substring(dispatch, next);
        assertTrue(body.contains(
                "noteDiLink5LockOutcome(result == null ? null : result.getString(DILINK5_RESULT_LOCK_OUTCOME));"));
        // Recorded after the result is known and before the success/failure logging.
        assertTrue(body.indexOf("noteDiLink5LockOutcome(") > body.indexOf("boolean success = result != null"));
        assertTrue(body.indexOf("noteDiLink5LockOutcome(") < body.indexOf("if (success && result.getString(DILINK5_RESULT_DETAIL)"));
        assertTrue(bridge.contains("public static final String DILINK5_RESULT_LOCK_OUTCOME = \"lockOutcome\";"));
        assertTrue(bridge.contains("private static final ThreadLocal<String> LAST_DILINK5_LOCK_OUTCOME = new ThreadLocal<>();"));
        assertTrue(bridge.contains("return scope == null ? null : scope.proximity;"));
    }

    @Test
    public void serviceHandsTheOutcomeToTheBundleAndKeepsScopedLockDetail() throws Exception {
        String service = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/VehicleActuatorService.java");
        assertTrue(service.contains(
                "private static boolean keepsDiLink5SuccessDetail(VehicleCommandRouter.VehicleCommand command,\n"
                        + "            com.overdrive.app.remoteaccess.DiLink5ProximityCommandGuard.Scope proximity) {"));
        assertTrue(service.contains("|| (proximity != null && (command instanceof VehicleCommandRouter.LockCommand\n"
                + "                        || command instanceof VehicleCommandRouter.UnlockCommand));"));
        assertTrue(service.contains("if (done && !keepsDiLink5SuccessDetail(command, scopedProximity)) detail[0] = null;"));
        int detail = service.indexOf("result.putString(VehicleActuatorBridge.DILINK5_RESULT_DETAIL, detail[0]);");
        int outcome = service.indexOf("String lockOutcome = collector.takeLastDiLink5LockOutcome();", detail);
        int put = service.indexOf(
                "result.putString(VehicleActuatorBridge.DILINK5_RESULT_LOCK_OUTCOME, lockOutcome);", outcome);
        assertTrue(detail > 0 && outcome > detail && put > outcome);
        // Cabin lights keep their detail exactly as before.
        assertTrue(service.contains("return VehicleActuatorBridge.isDiLink5CabinLightCommand(command)\n"));
    }

    @Test
    public void collectorSelectsTheWritePathFromTheScopeAndRecordsTheOutcome() throws Exception {
        String collector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        int start = collector.indexOf("public boolean setDiLink5AllDoorsLocked(boolean locked) {");
        int end = collector.indexOf("public String takeLastDiLink5LockOutcome() {", start);
        String body = collector.substring(start, end);
        assertTrue(body.contains("if (!isDiLink5ProducerActive() || !RemoteKeyControl.isEnabled()) return false;"));
        assertTrue(body.contains("VehicleActuatorBridge.currentDiLink5ProximityScope();"));
        assertTrue(body.contains("DiLink5LockRail gateRail = scope == null ? null : scope.gateRailOrNull();"));
        assertTrue(body.contains("switch (DiLink5DoorLockControl.selectDiLink5LockMode(gateRail, otaDevice != null)) {"));
        // Case (B) and (C) branches, and the default = today's typed call with the same guards.
        assertTrue(body.contains("case OTA:"));
        assertTrue(body.contains("DiLink5DoorLockControl.setLockedViaOta(doorLockDevice, otaDevice, locked,"));
        assertTrue(body.contains("case DELEGATED_CARSVC:"));
        assertTrue(body.contains("DiLink5DoorLockControl.applyWithDelegatedEvidence(doorLockDevice, locked,\n"
                + "                        scope.gateState, scope.gateObservedElapsedMs, requestCurrent,"));
        assertTrue(body.contains("default:\n"
                + "                result = DiLink5DoorLockControl.setLocked(\n"
                + "                        doorLockDevice, locked, requestCurrent,\n"
                + "                        VehicleActuatorBridge::confirmProximityBeforeDiLink5Write);"));
        assertTrue(body.contains("&& !VehicleActuatorBridge.isDiLink5RequestExpired()"));
        assertTrue(body.contains("DrivingSafetyGuard.GUARD_DOOR_LOCKS) == null;"));
        // Outcome log line, thread-local hand-off, and FAILED is the only false.
        assertTrue(body.contains("+ \" outcome=\" + result.outcome + \" confirmed=\" + result.confirmed);"));
        assertTrue(body.contains("lastDiLink5LockOutcome.set(result.outcome.name());"));
        assertTrue(body.contains("return result.outcome != DiLink5DoorLockControl.Result.Outcome.FAILED;"));
        assertTrue(collector.contains("private final ThreadLocal<String> lastDiLink5LockOutcome = new ThreadLocal<>();"));
        String take = collector.substring(end, collector.indexOf("}", collector.indexOf("lastDiLink5LockOutcome.remove();", end)));
        assertTrue(take.contains("lastDiLink5LockOutcome.get();"));
        assertTrue(take.contains("lastDiLink5LockOutcome.remove();"));
    }

    private static String readRepositoryFile(String relative) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path)) {
            path = Paths.get(System.getProperty("user.dir")).getParent().resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
