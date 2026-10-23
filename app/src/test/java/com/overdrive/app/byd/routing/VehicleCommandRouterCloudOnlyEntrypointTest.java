package com.overdrive.app.byd.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * 17 Oct cloud fallback actuator: the router gains exactly one public cloud-only entry point for the
 * proximity path and nothing else changes — the SDK legs of lock/unlock, the SDK-only entry point
 * (MQTT / Home Assistant) and the manual SDK_FIRST cascade are untouched. Style of
 * {@link CloudFallbackContractTest}.
 */
public class VehicleCommandRouterCloudOnlyEntrypointTest {
    private static final String SIGNATURE = "    public CommandResult executeCloudOnly(VehicleCommand cmd) {";

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(signature, start > 0);
        return source.substring(start, source.indexOf("\n    }\n", start));
    }

    @Test
    public void executeCloudOnlyExistsOnceAndRunsOnlyTheGuardedCloudLeg() throws Exception {
        String source = readRouterSource();
        assertEquals(1, count(source, SIGNATURE));
        assertEquals(1, count(source, "executeCloudOnly("));
        String body = method(source, SIGNATURE);
        // Order: driving-safety gate → explicit sdk_only policy → the existing cloud-only leg → finish hook.
        int safety = body.indexOf("CommandResult blocked = checkDrivingSafety(cmd);");
        int blockedReturn = body.indexOf("if (blocked != null) return blocked;", safety);
        int policy = body.indexOf("if (resolveEffectivePreference(cmd) == RoutePreference.SDK_ONLY) {", blockedReturn);
        int notSupported = body.indexOf("return CommandResult.notSupported(msg(\"not_supported\"));", policy);
        int cloud = body.indexOf("return finishCommand(cmd, runCloudOnly(cmd));", notSupported);
        assertTrue(safety > 0 && blockedReturn > safety && policy > blockedReturn
                && notSupported > policy && cloud > notSupported);
        assertEquals(1, count(body, "runCloudOnly(cmd)"));
        // No SDK leg, no wake, no cascade, no second attempt.
        for (String forbidden : new String[] {"invokeSdk(", "runSdkFirst(", "runWithVehicleWake(",
                "executeViaSdk(", "runSdkOnly(", "runCloudFirst(", "runLocalOnlyWhileAwake(",
                "for (", "while (", "retry"}) {
            assertFalse(forbidden, body.contains(forbidden));
        }
        // The leg sits between runCloudOnly and runCloudFirst (the existing cloud-only helper is reused).
        int runCloudOnly = source.indexOf("    private CommandResult runCloudOnly(VehicleCommand cmd) {");
        int entry = source.indexOf(SIGNATURE);
        int runCloudFirst = source.indexOf("    private CommandResult runCloudFirst(VehicleCommand cmd) {");
        assertTrue(runCloudOnly > 0 && entry > runCloudOnly && runCloudFirst > entry);
        // runCloudOnly itself is unchanged: handshake first (AUTH_REQUIRED before any network call).
        String cloudOnly = method(source, "    private CommandResult runCloudOnly(VehicleCommand cmd) {");
        assertTrue(cloudOnly.contains("if (!cmd.hasCloudPath()) return CommandResult.notSupported(msg(\"not_supported\"));"));
        assertTrue(cloudOnly.contains("if (!cloudHandshakeSatisfied(cmd)) {\n"
                + "            return CommandResult.authRequired(msg(\"cloud_required\"));"));
        assertTrue(cloudOnly.contains("CloudCallResult cr = runCloudCall(cmd);"));
        assertTrue(cloudOnly.contains("return mapCloudOnlyResult(cr, elapsed);"));
    }

    @Test
    public void lockAndUnlockDeclarationsAndTheSdkOnlyEntrypointAreUnchanged() throws Exception {
        String source = readRouterSource();
        // Cloud legs: LOCKDOOR / OPENDOOR (the brief's "UNLOCKDOOR" does not exist); SDK legs as before.
        assertEquals(1, count(source, "return remoteCommand(client, vin, \"LOCKDOOR\", null);"));
        assertEquals(1, count(source, "return remoteCommand(client, vin, \"OPENDOOR\", null);"));
        assertTrue(source.contains("return com.overdrive.app.byd.RemoteKeyControl.lock(c.getRuntimeContext());"));
        assertTrue(source.contains("return com.overdrive.app.byd.RemoteKeyControl.unlockConfirmed(\n"
                + "                    c.getRuntimeContext(), confirmLanded ? c : null);"));
        assertTrue(source.contains("return c.setDiLink5AllDoorsLocked(true);"));
        assertTrue(source.contains("return c.setDiLink5AllDoorsLocked(false);"));
        assertEquals(1, count(source, "public static final class LockCommand extends VehicleCommand {"));
        assertEquals(1, count(source, "public static final class UnlockCommand extends VehicleCommand {"));
        // No readback or cloud fallback was added to the SDK legs themselves.
        String lock = source.substring(source.indexOf("public static final class LockCommand extends VehicleCommand {"),
                source.indexOf("public static final class UnlockCommand extends VehicleCommand {"));
        String unlock = source.substring(source.indexOf("public static final class UnlockCommand extends VehicleCommand {"),
                source.indexOf("/** Horn + lights. */"));
        for (String forbidden : new String[] {"confirmByDistinctReadbacks", "executeCloudOnly", "cloudFallback",
                "readDoorLockState", "RemoteAccessSettings"}) {
            assertFalse(forbidden, lock.contains(forbidden));
            assertFalse(forbidden, unlock.contains(forbidden));
        }
        // The MQTT / Home Assistant SDK-only entry point still never reaches a cloud leg.
        int executeSdkOnly = source.indexOf("public CommandResult executeSdkOnly(VehicleCommand cmd)");
        int finishCommand = source.indexOf("private CommandResult finishCommand(", executeSdkOnly);
        String sdkOnly = source.substring(executeSdkOnly, finishCommand);
        assertFalse(sdkOnly.contains("runCloudOnly("));
        assertFalse(sdkOnly.contains("executeCloudOnly("));
        assertTrue(sdkOnly.contains("CommandResult result = isSdkOnlyTrunkOpen(cmd)\n"
                + "                ? runSdkOnlyTrunkOpen(cmd, tailgateOpenStopGeneration) : runSdkOnly(cmd);"));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    private static String readRouterSource() throws Exception {
        Path fromModule = Paths.get("src/main/java/com/overdrive/app/byd/routing/VehicleCommandRouter.java");
        if (Files.exists(fromModule)) {
            return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
        }
        return new String(Files.readAllBytes(Paths.get(
                "app/src/main/java/com/overdrive/app/byd/routing/VehicleCommandRouter.java")),
                StandardCharsets.UTF_8);
    }
}
