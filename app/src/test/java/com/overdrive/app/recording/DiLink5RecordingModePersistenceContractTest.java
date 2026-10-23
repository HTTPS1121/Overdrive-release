package com.overdrive.app.recording;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression guards for preserving the configured DiLink 5 recording mode. */
public class DiLink5RecordingModePersistenceContractTest {

    @Test
    public void diLink5AccOffFinalizesRuntimeWithoutSelectingNone()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/recording/"
                        + "RecordingModeManager.java");
        String accHandler = method(
                source,
                "private void onAccStateChangedLocked(boolean isOn)",
                "\n    /**\n     * Notify of gear state change.");

        int selectedFence = accHandler.indexOf(
                "DiLink5Platform.isSelected()");
        int diLink5Branch = accHandler.indexOf(
                "if (diLink5Selected)", selectedFence);
        int preserveSnapshot = accHandler.indexOf(
                "configuredMode = currentMode", diLink5Branch);
        int guardedFinalize = accHandler.indexOf(
                "runDiLink5AccOffFinalizeGuarded(configuredMode)",
                preserveSnapshot);
        int legacyDiLink4Reset = accHandler.indexOf(
                "currentMode = Mode.NONE", guardedFinalize);

        assertTrue(selectedFence >= 0);
        assertTrue(diLink5Branch > selectedFence);
        assertTrue(preserveSnapshot > diLink5Branch);
        assertTrue(guardedFinalize > preserveSnapshot);
        assertTrue(legacyDiLink4Reset > guardedFinalize);
        assertTrue(accHandler.contains("modeToActivate = currentMode"));
        assertTrue(accHandler.contains(
                "activateModeWithWarmup(mta, \"acc-on-reacquire-\" + mta)"));

        String diLink5Only = accHandler.substring(
                diLink5Branch, legacyDiLink4Reset);
        assertFalse(diLink5Only.contains("currentMode = Mode.NONE"));
        assertFalse(diLink5Only.contains("persistMode(Mode.NONE)"));
    }

    @Test
    public void diLink5AccOffUsesSerializedCompleteDeactivation()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/recording/"
                        + "RecordingModeManager.java");
        String helper = method(
                source,
                "private void runDiLink5AccOffFinalizeGuarded(",
                "\n    /**\n     * Run the AVC HAL warmup");

        assertTrue(helper.contains("synchronized (activationLock)"));
        assertTrue(helper.contains("configuredMode == Mode.NONE"));
        assertTrue(helper.contains("modeActive = false"));
        assertTrue(helper.contains("recordingWedged = false"));
        assertTrue(helper.contains("pipeline.stopRecording()"));
        assertTrue(helper.contains("reconcileCameraProfile()"));
        assertTrue(helper.contains("deactivateMode(configuredMode)"));
        assertFalse(helper.contains("currentMode ="));
        assertFalse(helper.contains("persistMode("));
    }

    private static String method(
            String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start);
        assertTrue("method start not found: " + startNeedle, start >= 0);
        assertTrue("method end not found: " + endNeedle, end > start);
        return source.substring(start, end);
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
