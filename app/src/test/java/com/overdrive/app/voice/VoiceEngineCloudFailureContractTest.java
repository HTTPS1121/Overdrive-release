package com.overdrive.app.voice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Pins how the engine reports a GenAI session that never went live: a dead hybrid pre-warm
 * fails the turn at speech end instead of re-dialling, a provider that never answered is
 * {@code cloud_connect_failed} rather than {@code cloud_no_response}, and both reuse the
 * existing offline/busy prompts.
 */
public class VoiceEngineCloudFailureContractTest {

    private static final String ENGINE =
            "src/main/java/com/overdrive/app/voice/VoiceEngine.java";

    @Test
    public void deadPreWarmIsRememberedButBusyIsNot() throws Exception {
        String src = readProjectFile(ENGINE);
        String ended = block(src, "    private void onSessionEnded(String reason) {");
        assertTrue(ended.contains("sessionLive = false;"));
        assertTrue(ended.contains("if (phase != CLOUD) {"));
        assertTrue(ended.contains("preWarmFailed = reason"));
        assertTrue(ended.contains("\"connect_failed\".equals(reason)"));
        assertTrue(ended.contains("\"unavailable\".equals(reason)"));
        assertTrue(ended.contains("\"failed\".equals(reason)"));
        // N5: a busy pre-warm still re-dials from startCloud(true).
        assertFalse(ended.contains("\"busy\".equals(reason)) preWarmFailed"));
        String remember = ended.substring(0, ended.indexOf("preWarmFailed = reason"));
        assertFalse(remember.contains("\"busy\".equals(reason)"));
        assertTrue(ended.contains("failCloudTurn(reason);"));
        assertTrue(ended.contains("noteTurn(\"cloud\");"));
    }

    @Test
    public void failCloudTurnReusesTheOfflineAndBusyPrompts() throws Exception {
        String src = readProjectFile(ENGINE);
        String fail = block(src, "    private void failCloudTurn(String reason) {");
        assertTrue(fail.contains("failures.incrementAndGet();"));
        assertTrue(fail.contains("noteTurn(\"busy\")"));
        assertTrue(fail.contains("noteTurn(\"cloud_connect_failed\")"));
        assertTrue(fail.contains("noteTurn(\"offline\")"));
        assertTrue(fail.contains("VoicePrompts.OFFLINE"));
        assertTrue(fail.contains("VoicePrompts.BUSY"));
        assertTrue(fail.contains("R.string.voice_overlay_busy"));
        assertTrue(fail.contains("R.string.voice_overlay_offline"));
    }

    @Test
    public void setupFailureIsReportedAsCloudSetupFailedWithTheOfflinePrompt() throws Exception {
        String src = readProjectFile(ENGINE);
        String fail = block(src, "    private void failCloudTurn(String reason) {");
        int connect = fail.indexOf("\"connect_failed\".equals(reason)");
        int setup = fail.indexOf("\"setup_failed\".equals(reason)");
        int offline = fail.indexOf("noteTurn(\"offline\")");
        assertTrue(connect >= 0);
        assertTrue(setup > connect);
        assertTrue(offline > setup);
        String setupBranch = fail.substring(setup, offline);
        assertTrue(setupBranch.contains("noteTurn(\"cloud_setup_failed\")"));
        assertTrue(setupBranch.contains("R.string.voice_overlay_offline"));
        assertTrue(setupBranch.contains("VoicePrompts.OFFLINE"));
        assertFalse(setupBranch.contains("cloud_connect_failed"));
        // Both the dead pre-warm and the live cloud turn hand a setup failure to failCloudTurn.
        String ended = block(src, "    private void onSessionEnded(String reason) {");
        int remember = ended.indexOf("preWarmFailed = reason");
        int fails = ended.indexOf("failCloudTurn(reason);");
        assertTrue(ended.substring(0, remember).contains("\"setup_failed\".equals(reason)"));
        assertTrue(ended.substring(remember, fails).contains("\"setup_failed\".equals(reason)"));
        // tickCloud keeps its own labels: a never-live session at the deadline is still a connect failure.
        String tick = block(src, "    private void tickCloud(long now) {");
        assertFalse(tick.contains("cloud_setup_failed"));
    }

    @Test
    public void providerThatNeverWentLiveIsAConnectFailure() throws Exception {
        String src = readProjectFile(ENGINE);
        String tick = block(src, "    private void tickCloud(long now) {");
        int notLive = tick.indexOf("if (!sessionLive)");
        int connect = tick.indexOf("noteTurn(\"cloud_connect_failed\")");
        int noResponse = tick.indexOf("noteTurn(\"cloud_no_response\")");
        assertTrue(notLive >= 0);
        assertTrue(connect > notLive);
        assertTrue(noResponse > connect);
    }

    @Test
    public void startCloudConsultsThePreWarmBeforeEnteringTheCloudPhase() throws Exception {
        String src = readProjectFile(ENGINE);
        String start = block(src, "    private void startCloud(boolean replayUtterance) {");
        int check = start.indexOf("preWarmFailed != null");
        int phase = start.indexOf("phase = CLOUD;");
        assertTrue(check >= 0);
        assertTrue(phase > check);
        assertTrue(start.contains("failCloudTurn(preWarmFailed);"));
        assertTrue(start.contains("session == null && preWarmFailed != null"));
    }

    @Test
    public void sessionLiveIsSessionScopedNotPerTurn() throws Exception {
        String src = readProjectFile(ENGINE);
        String events = src.substring(src.indexOf(
                "    private final class SessionEvents implements VoiceSessionClient.Listener {"));
        assertTrue(events.contains("sessionLive = true"));
        String begin = block(src, "    private void beginTurn(boolean manual, float gain) {");
        assertTrue(begin.contains("preWarmFailed = null;"));
        assertFalse(begin.contains("sessionLive"));
        String open = block(src, "    private void openSession() {");
        assertTrue(open.contains("sessionLive = false;"));
        String quiet = block(src, "    private void closeSessionQuietly() {");
        assertTrue(quiet.contains("sessionLive = false;"));
    }

    private static String block(String src, String openingLine) {
        int start = src.indexOf(openingLine);
        assertTrue("missing " + openingLine.trim(), start >= 0);
        int bodyStart = start + openingLine.length();
        int end = src.indexOf("\n    }\n", bodyStart);
        assertTrue(end > bodyStart);
        return src.substring(bodyStart, end);
    }

    private static String readProjectFile(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        Path direct = current.resolve(relativePath);
        Path file = Files.exists(direct)
                ? direct : current.resolve("app").resolve(relativePath);
        if (!Files.exists(file)) {
            throw new AssertionError("Could not locate project file: " + relativePath);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
