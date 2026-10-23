package com.overdrive.app.daemon;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Source-level guards for cross-process DiLink 5 ACC/sentry integration. */
public class DiLink5AccSentryRegressionContractTest {

    @Test
    public void ambiguousHeartbeatReplaysOnlyAnAdmittedState()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");
        int rule = source.indexOf(
                "private static boolean shouldRepublishHeldDiLink5AccState(");
        int ruleEnd = source.indexOf(
                "/**\n     * Notify CameraDaemon", rule);
        String body = source.substring(rule, ruleEnd);

        // A fresh AccSentry process has no ACC authority. An IVI Display-on
        // observation therefore cannot be converted into parked/OFF.
        assertTrue(body.contains("transitionGeneration > 0L"));

        // Replication is bounded to every two 5-second ambiguous ticks.
        assertTrue(source.contains(
                "DILINK5_FORCE_REPUBLISH_TICKS = 2"));
        assertTrue(body.contains(
                "undecidedTicks % DILINK5_FORCE_REPUBLISH_TICKS == 0"));
    }

    @Test
    public void ambiguousHeartbeatCannotSynthesizeATripStoppingTransition()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");
        int helper = source.indexOf(
                "private static boolean requestHeldDiLink5AccStateRepublish(");
        int helperEnd = source.indexOf(
                "private static boolean shouldRepublishHeldDiLink5AccState(",
                helper);
        String body = source.substring(helper, helperEnd);

        assertTrue(body.contains("observedState.generation"));
        assertTrue(body.contains("observedState.sentryMode"));
        assertTrue(body.contains("latest.generation != observedState.generation"));
        assertTrue(body.contains("latest.sentryMode != observedState.sentryMode"));
        assertTrue(body.contains("notifyAccState("));
        assertFalse(body.contains("enterSentryMode()"));
        assertFalse(body.contains("exitSentryMode()"));
        assertFalse(body.contains("lastPowerLevel ="));
    }

    @Test
    public void bothManifestAndDynamicReceiversPublishIgnitionHint()
            throws Exception {
        String boot = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/receiver/BootReceiver.kt");
        String keepalive = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/DaemonKeepaliveService.kt");

        int bootHint = boot.indexOf(
                "val pendingHintWrite = goAsync()");
        int bootGate = boot.lastIndexOf(
                "DiLink5Platform.isSelected()", bootHint);
        assertTrue(bootGate >= 0);
        assertTrue(bootHint > bootGate);
        assertTrue(boot.contains("val pendingHintWrite = goAsync()"));
        assertTrue(boot.contains(
                "IgnitionBroadcastHint.publishAsync("));
        assertTrue(boot.contains("pendingHintWrite.finish()"));
        int keepaliveHint = keepalive.indexOf(
                ".publishAsync(applicationContext)");
        int keepaliveGate = keepalive.lastIndexOf(
                ".isSelected()", keepaliveHint);
        assertTrue(keepaliveGate >= 0);
        assertTrue(keepaliveHint > keepaliveGate);
    }

    @Test
    public void recordingAccProbeUsesSelectedDiLink5Fence()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/recording/RecordingModeManager.java");
        int method = source.indexOf(
                "private boolean queryAccStateFromHardware()");
        int methodEnd = source.indexOf(
                "private void dispatchProbedAccEdgeIfMeaningful", method);
        String body = source.substring(method, methodEnd);

        assertTrue(body.contains("DiLink5Platform.isSelected()"));
        assertFalse(body.contains("DiLink5Platform.isEnabled()"));
    }

    @Test
    public void onlyDiLink5TrustsCurrentOffGenerationForDeferredEffects()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        int method = source.indexOf(
                "private static boolean validateAccOffForDeferredEffect(");
        int methodEnd = source.indexOf(
                "private static Integer readRawAccPowerLevelBounded()", method);
        String body = source.substring(method, methodEnd);

        int currentGeneration = body.indexOf(
                "isAccTransitionCurrent(transitionGeneration, true)");
        int diLink5Gate = body.indexOf(
                "DiLink5Platform.isSelected()", currentGeneration);
        int legacyProbe = body.indexOf(
                "DEFERRED_ACC_VALIDATION_IN_FLIGHT.compareAndSet", diLink5Gate);
        assertTrue(currentGeneration >= 0);
        assertTrue(diLink5Gate > currentGeneration);
        assertTrue(legacyProbe > diLink5Gate);
    }

    @Test
    public void cameraDaemonHasIndependentGenerationSafeDi5OnRecovery()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        int method = source.indexOf(
                "private static void startAccOnDisarmWatchdog(");
        int methodEnd = source.indexOf(
                "private static void stopAccOnDisarmWatchdog()", method);
        String body = source.substring(method, methodEnd);

        int di5Gate = body.indexOf(
                "DiLink5Platform.isSelected()");
        int offEntryFence = body.indexOf(
                "captureCurrentAccOffEnteredAtElapsedMs(", di5Gate);
        int hint = body.indexOf(
                "IgnitionBroadcastHint", offEntryFence);
        int transitionProbe = body.indexOf(
                "probeDiLink5AccOnForTransition(", hint);
        int currencyCheck = body.indexOf(
                "isAccObservationCurrent(", transitionProbe);
        int admission = body.indexOf(
                "onObservedAccStateChanged(", currencyCheck);
        int legacyProbe = body.indexOf(
                "probeAccStateWithBackoff(", admission);

        assertTrue(di5Gate >= 0);
        assertTrue(offEntryFence > di5Gate);
        assertTrue(hint > offEntryFence);
        assertTrue(transitionProbe > hint);
        assertTrue(currencyCheck > transitionProbe);
        assertTrue(admission > currencyCheck);
        assertTrue(legacyProbe > admission);
        assertTrue(body.contains("\"di5-disarm-watchdog\""));
        assertTrue(source.contains(
                "latestAccTransitionAdmittedAtElapsedMs"));
    }

    @Test
    public void di5OnRecoveryStartsBeforeEverySentrySuppressionReturn()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        int offRecovery = source.indexOf(
                "// DiLink 5 must retain an independent local ACC-ON recovery path");
        int start = source.indexOf(
                "startAccOnDisarmWatchdog(transitionGeneration)",
                offRecovery);
        int userGate = source.indexOf(
                "if (!userEnabled)", start);
        int safeZoneGate = source.indexOf(
                "if (safeMgr.isInSafeZone())", start);
        int scheduleGate = source.indexOf(
                "if (schedule != null && schedule.isEnabled()",
                start);

        assertTrue(offRecovery >= 0);
        assertTrue(start > offRecovery);
        assertTrue(userGate > start);
        assertTrue(safeZoneGate > start);
        assertTrue(scheduleGate > start);
    }

    @Test
    public void accAdmissionTimestampChangesOnlyWithANewGeneration()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        int ipcMethod = source.indexOf(
                "private static void onAccStateChanged(boolean accIsOff,");
        int observedMethod = source.indexOf(
                "private static void onObservedAccStateChanged(", ipcMethod);
        int runner = source.indexOf(
                "private static void runAdmittedAccTransition(", observedMethod);

        String ipcBody = source.substring(ipcMethod, observedMethod);
        String observedBody = source.substring(observedMethod, runner);
        assertTrue(ipcBody.indexOf(
                "latestAccTransitionAdmittedAtElapsedMs =")
                > ipcBody.indexOf("if (transitionChanged)"));
        assertTrue(observedBody.indexOf(
                "latestAccTransitionAdmittedAtElapsedMs =")
                > observedBody.indexOf("if (changed)"));
    }

    @Test
    public void di5HeartbeatPublishesProgressBeforePowerProbe()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");
        int method = source.indexOf(
                "private static synchronized void startDiLink5AccStateHeartbeat()");
        int methodEnd = source.indexOf(
                "private static boolean requestHeldDiLink5AccStateRepublish(",
                method);
        String body = source.substring(method, methodEnd);

        int loop = body.indexOf("while (running");
        int marker = body.indexOf(
                "AccSentryHealthMarker.publish()", loop);
        int probe = body.indexOf(
                "probeDiLink5AccOnForTransition(", marker);
        assertTrue(loop >= 0);
        assertTrue(marker > loop);
        assertTrue(probe > marker);
        assertTrue(source.contains(
                "AccSentryHealthMarker.clearIfOwned()"));
    }

    private static String readRepositoryFile(String relativePath)
            throws Exception {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path direct = current.resolve(relativePath);
            if (Files.isRegularFile(direct)) {
                return new String(
                        Files.readAllBytes(direct),
                        StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule),
                        StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
