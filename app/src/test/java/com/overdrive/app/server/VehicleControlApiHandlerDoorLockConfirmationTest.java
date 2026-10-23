package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * P5 (17 Oct): the dashboard Lock/Unlock report what the car's own LOCAL readback says — never
 * SUCCESS on frame acceptance (rc=0 / router SUCCESS) alone — and fall back to ONE cloud command
 * when the local leg is refused or unconfirmed. Source pins in the style of
 * {@code VehicleControlApiHandlerContractTest}: the handler runs only on the head unit.
 */
public class VehicleControlApiHandlerDoorLockConfirmationTest {
    private static final String HANDLER = "app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java";

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    private static String region(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue("missing " + from, start >= 0);
        int end = source.indexOf(to, start);
        assertTrue("missing " + to, end > start);
        return source.substring(start, end);
    }

    private static String method(String source, String signatureStart) {
        int start = source.indexOf(signatureStart);
        assertTrue("missing " + signatureStart, start >= 0);
        int end = source.indexOf("\n    }\n", start);
        assertTrue(end > start);
        return source.substring(start, end);
    }

    @Test
    public void lockAndUnlockRouteThroughTheLocalConfirmationHelperInsteadOfTheRouterCascade() throws IOException {
        String source = readRepositoryFile(HANDLER);
        // The one-shot router cascade is gone from the dashboard door-lock endpoints …
        assertEquals(0, count(source, ".execute(new VehicleCommandRouter.LockCommand())"));
        assertEquals(0, count(source, ".execute(new VehicleCommandRouter.UnlockCommand())"));
        // … both call the confirming helper with today's command objects (UnlockCommand() keeps its
        // internal pre-read/readback/re-send, confirmLanded=true).
        String lock = method(source, "private static void handleLock(OutputStream out)");
        String unlock = method(source, "private static void handleUnlock(OutputStream out)");
        assertTrue(lock.contains("routeDoorLockWithLocalConfirmation(\n"
                + "                new VehicleCommandRouter.LockCommand(), \"lock\", true)"));
        assertTrue(unlock.contains("routeDoorLockWithLocalConfirmation(\n"
                + "                new VehicleCommandRouter.UnlockCommand(), \"unlock\", false)"));
        assertEquals(3, count(source, "routeDoorLockWithLocalConfirmation("));
        // The helpers live right after handleUnlock, before handleFindCar; routedResponse is unchanged.
        String helpers = region(source, "private static JSONObject routeDoorLockWithLocalConfirmation(",
                "private static void handleFindCar(OutputStream out)");
        assertTrue(helpers.contains("private static JSONObject doorLockResponse("));
        assertTrue(source.contains("static JSONObject routedResponse(CommandResult r, String action) {"));
        assertTrue(method(source, "static JSONObject routedResponse(CommandResult r, String action)")
                .contains("boolean success = r.outcome == VehicleCommandRouter.Outcome.SUCCESS;"));
        assertEquals(1, count(source, "static JSONObject routedResponse(CommandResult r, String action)"));
    }

    @Test
    public void helperRunsLocalLegThenLocalReadbackThenOneCloudLegThenSecondReadback() throws IOException {
        String source = readRepositoryFile(HANDLER);
        String helper = method(source, "private static JSONObject routeDoorLockWithLocalConfirmation(");
        int sdkLeg = helper.indexOf("router.executeSdkOnly(cmd)");
        int blocked = helper.indexOf("VehicleCommandRouter.Outcome.BLOCKED_DRIVING");
        int firstWindow = helper.indexOf("confirmDoorLockLocally(");
        int setting = helper.indexOf("RemoteAccessSettings.cloudFallback(");
        int cloudLeg = helper.indexOf("router.executeCloudOnly(cmd)");
        int secondWindow = helper.indexOf("cloudFallbackSecondWindowMs(");
        int verdict = helper.indexOf("manualDoorLockVerdict(");
        assertTrue(sdkLeg >= 0 && blocked > sdkLeg && firstWindow > blocked && setting > firstWindow
                && cloudLeg > setting && secondWindow > cloudLeg && verdict > secondWindow);
        // BLOCKED_DRIVING from the local leg: today's routed response, before any readback.
        assertTrue(helper.indexOf("return routedResponse(sdk, action);") < firstWindow);
        // Exactly ONE cloud leg per request, through the router's additive cloud-only entry point.
        assertEquals(1, count(source, "executeCloudOnly("));
        assertEquals(1, count(helper, "executeSdkOnly("));
        assertEquals(2, count(helper, "confirmDoorLockLocally("));
        // The cloud leg is due after a refused local leg whatever the setting, and after an accepted
        // but unconfirmed one only with the switch on; never after a local confirmation.
        assertTrue(helper.contains("boolean cloudLegDue = !sdkAccepted || (cloudFallbackOn\n"
                + "                && first != com.overdrive.app.remoteaccess.DiLink5GateActuationPolicy.Confirmation.CONFIRMED);"));
        assertTrue(helper.contains("if (cloudLegDue) {\n            cloud = router.executeCloudOnly(cmd);"));
        // The second window only follows an acknowledged cloud command and is cut to the decision budget.
        assertTrue(helper.contains("if (cloud.outcome == VehicleCommandRouter.Outcome.SUCCESS) {\n"
                + "                second = confirmDoorLockLocally("));
        assertTrue(helper.contains("cloudFallbackSecondWindowMs(\n"
                + "                                rail, started, android.os.SystemClock.elapsedRealtime())"));
        // The first window is the rail's own (4 s typed / 8 s ota / 12 s carsvc).
        assertTrue(helper.contains("DiLink5GateActuationPolicy.confirmationWindowMs(rail)"));
        // The verdict gets the router outcome NAMES, both windows, the timeout flag and the cloud's
        // onlineState label (read only when the cloud leg failed).
        assertTrue(helper.contains("sdk.outcome.name(), first, cloudFallbackOn,"));
        assertTrue(helper.contains("cloud == null ? null : cloud.outcome.name(), second,"));
        assertTrue(helper.contains("cloud.error instanceof java.util.concurrent.TimeoutException"));
        assertTrue(helper.contains("? cloudOnlineStateLabel() : \"unknown\""));
        String online = method(source, "private static String cloudOnlineStateLabel()");
        assertTrue(online.contains(".optString(\"onlineState\", \"unknown\")"));
        assertEquals(1, count(source, "\"onlineState\""));
    }

    @Test
    public void readbacksAreLocalOnlyAndReuseTheProximityLoop() throws IOException {
        String source = readRepositoryFile(HANDLER);
        String window = method(source, "confirmDoorLockLocally(\n            BydDataCollector collector");
        // The proximity loop, reused: exactly one call in the file, inside the window helper.
        assertEquals(1, count(source, "confirmByDistinctReadbacks("));
        assertTrue(window.contains("DiLink5GateActuationPolicy.confirmByDistinctReadbacks("));
        // Suppliers: the Di5 gate ledger or the legacy getter stamped with its read time; nothing else.
        assertTrue(window.contains("diLink5 ? collector::readDiLink5GateDoorLockEvidence"));
        assertTrue(window.contains("DiLink5GateActuationPolicy.legacyReadback("));
        assertTrue(window.contains("collector.readDoorLockState(), android.os.SystemClock.elapsedRealtime())"));
        assertTrue(window.contains("target, windowMs, android.os.SystemClock::elapsedRealtime, Thread::sleep)"));
        // carsvc gets its dump burst for the window; a window of 0 or less reads nothing.
        assertTrue(window.contains("if (rail == com.overdrive.app.byd.DiLink5LockRail.CARSVC) collector.requestDiLink5CarSvcLockBurst(windowMs);"));
        assertTrue(window.contains("if (windowMs <= 0L) return com.overdrive.app.remoteaccess.DiLink5GateActuationPolicy.Confirmation.UNCONFIRMED;"));
        String body = window.substring(window.indexOf("{"));
        for (String forbidden : new String[] {"BydCloud", "getStatusJson", "remoteCommand", "LOCKDOOR", "OPENDOOR",
                "executeCloudOnly", "onlineState"}) {
            assertFalse(forbidden, body.contains(forbidden));
        }
        // Di5 rail choice: the proximity rule over the gate ledger, carsvc when the ledger holds nothing
        // fresh but that rail is enabled.
        String rail = method(source, "private static com.overdrive.app.byd.DiLink5LockRail dashboardActuationRail(");
        assertTrue(rail.contains("DiLink5GateActuationPolicy.preferredActuationRail("));
        assertTrue(rail.contains("collector.readDiLink5GateDoorLockEvidence(),"));
        assertTrue(rail.contains("collector.readDiLink5GateDoorLockEvidenceFor(com.overdrive.app.byd.DiLink5LockRail.OTA)"));
        assertTrue(rail.contains("RemoteAccessSettings.di5LockRails(remoteAccess)"));
        // The CAN remote-key frame stays inside the router's SDK leg: the door-lock code never touches it.
        String helpers = region(source, "private static void handleLock(OutputStream out)",
                "private static void handleFindCar(OutputStream out)");
        assertFalse(helpers.contains("RemoteKeyControl"));
        // No new imports: the new code is fully qualified (import block unchanged).
        assertFalse(source.contains("import com.overdrive.app.remoteaccess."));
        assertFalse(source.contains("import com.overdrive.app.byd.DiLink5LockRail"));
        assertFalse(source.contains("import android.os.SystemClock"));
    }

    @Test
    public void successComesFromTheVerdictAloneAndTheResponseCarriesConfirmation() throws IOException {
        String source = readRepositoryFile(HANDLER);
        String helpers = region(source, "private static JSONObject routeDoorLockWithLocalConfirmation(",
                "private static void handleFindCar(OutputStream out)");
        String response = method(source, "private static JSONObject doorLockResponse(");
        // Every "success" written by the door-lock code comes from the verdict (a pure table whose
        // success is a LOCAL confirmation); no router-SUCCESS shortcut anywhere in the region.
        assertEquals(1, count(helpers, "put(\"success\","));
        assertTrue(response.contains("resp.put(\"success\", verdict.success);"));
        assertTrue(response.contains("resp.put(\"commandSuccess\", verdict.success);"));
        assertFalse(helpers.contains("put(\"success\", true)"));
        assertFalse(helpers.contains("put(\"success\", sdk"));
        assertFalse(helpers.contains("put(\"success\", cloud"));
        assertFalse(helpers.contains("boolean success ="));
        // Router SUCCESS is only ever used to decide whether a leg/window runs, never as the result.
        assertTrue(helpers.contains("boolean sdkAccepted = sdk.outcome == VehicleCommandRouter.Outcome.SUCCESS;"));
        // Same keys as routedResponse plus confirmation (local | none); error only when not successful;
        // the message is the i18n base text plus the fixed English suffix (J11).
        for (String key : new String[] {"\"path\"", "\"latencyMs\"", "\"message\"", "\"outcome\"", "\"action\"",
                "\"error\"", "\"confirmation\""}) {
            assertTrue(key, response.contains("resp.put(" + key + ","));
        }
        assertTrue(response.contains("resp.put(\"confirmation\", verdict.confirmation());"));
        assertTrue(response.contains("if (!verdict.success) resp.put(\"error\", message);"));
        assertTrue(response.contains("Messages.get(\"vehicle_control.\" + verdict.baseKey) + verdict.suffix"));
        assertTrue(response.contains("resp.put(\"path\", verdict.path);"));
        assertTrue(response.contains("resp.put(\"outcome\", verdict.outcome);"));
        // Pass-through rows (rate_limited, blocked_driving) report the producing leg exactly as today.
        assertTrue(response.contains("if (verdict.passThrough()) {\n            resp = routedResponse(lastLeg, action);"));
        // The field-report log prefixes stay, extended with confirmation= and outcome=.
        assertTrue(helpers.contains("lock ? \"Lock: routed result=\" : \"Unlock: routed result=\""));
        assertTrue(helpers.contains("+ \" confirmation=\" + verdict.confirmation() + \" outcome=\" + verdict.outcome"));
        assertTrue(helpers.contains("+ \" confirmation=none outcome=blocked_driving\""));
        assertEquals(1, count(source, "\"Lock: routed result=\""));
        assertEquals(1, count(source, "\"Unlock: routed result=\""));
    }

    @Test
    public void keymapAndVoiceDoorLockCallersAreUntouched() throws IOException {
        String keymap = readRepositoryFile("app/src/main/java/com/overdrive/app/server/KeymapApiHandler.java");
        String runVehicle = method(keymap, "private static JSONObject runVehicle(JSONObject req)");
        assertTrue(runVehicle.contains("case \"lock\":     cmd = new VehicleCommandRouter.LockCommand(); break;"));
        assertTrue(runVehicle.contains("case \"unlock\":   cmd = new VehicleCommandRouter.UnlockCommand(); break;"));
        assertTrue(runVehicle.contains(".execute(cmd)"));
        assertFalse(keymap.contains("routeDoorLockWithLocalConfirmation"));
        assertFalse(keymap.contains("executeCloudOnly("));
        String router = readRepositoryFile("app/src/main/java/com/overdrive/app/byd/routing/VehicleCommandRouter.java");
        // The router's SDK legs stay as they are (J9): the readback lives in the dashboard handler.
        assertTrue(router.contains("return com.overdrive.app.byd.RemoteKeyControl.lock(c.getRuntimeContext());"));
        assertTrue(router.contains("return com.overdrive.app.byd.RemoteKeyControl.unlockConfirmed(\n"
                + "                    c.getRuntimeContext(), confirmLanded ? c : null);"));
        assertTrue(router.contains("return remoteCommand(client, vin, \"LOCKDOOR\", null);"));
        assertTrue(router.contains("return remoteCommand(client, vin, \"OPENDOOR\", null);"));
        assertEquals(1, count(router, "public CommandResult executeCloudOnly(VehicleCommand cmd)"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
