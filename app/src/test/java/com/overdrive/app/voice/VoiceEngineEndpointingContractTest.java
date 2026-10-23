package com.overdrive.app.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Pins command-window endpointing in VoiceEngine: the VAD decides speech when loaded and the RMS
 * path is kept as the fallback, the frozen wake check is untouched, the Vad.kt wrapper declares
 * only what the shipped native library exports, and the cloud deadline is provider-anchored.
 */
public class VoiceEngineEndpointingContractTest {

    private static final String ENGINE =
            "src/main/java/com/overdrive/app/voice/VoiceEngine.java";
    private static final String VAD_KT =
            "src/main/java/com/k2fsa/sherpa/onnx/Vad.kt";
    private static final String COMMAND_VAD =
            "src/main/java/com/overdrive/app/voice/local/CommandVad.java";
    private static final String CLIENT =
            "src/main/java/com/overdrive/app/voice/genai/VoiceSessionClient.java";
    private static final String SOCKET =
            "src/main/java/com/overdrive/app/server/GenAiVoiceWebSocket.java";

    @Test
    public void trackSpeechUsesTheVadWhenLoadedAndRmsOtherwise() throws Exception {
        String body = block(readProjectFile(ENGINE),
                "    private void trackSpeech(short[] pcm, int off, int n, float rms, long now) {");
        assertTrue(body.contains("vad != null"));
        assertTrue(body.contains("vad.speech(pcm, off, n)"));
        // The clean-hit gate's RMS anchors survive the VAD.
        assertTrue(body.contains("lastLoudSample = turnSamples + n"));
        assertTrue(body.contains("speechOnsetSample = Math.max(0, turnSamples - n)"));
    }

    @Test
    public void vadIsNeverFedInTheArmedPhase() throws Exception {
        String src = readProjectFile(ENGINE);
        // The only feed is inside trackSpeech, which onTurnAudio calls after the wake path returned.
        assertEquals(1, count(src, "vad.speech("));
        String onAudio = block(src, "    private void onAudio(short[] chunk, int n, long now) {");
        assertFalse(onAudio.contains("vad."));
        String checkWake = block(src, "    private void checkWake() {");
        assertFalse(checkWake.contains("vad."));
        assertEquals(2, count(checkWake, "englishSpeech()"));
    }

    @Test
    public void vadLifecycleFollowsTheSpotter() throws Exception {
        String src = readProjectFile(ENGINE);
        String load = block(src, "    private void loadVad() {");
        assertTrue(load.contains("CommandVad.isAvailable(app)"));
        assertTrue(load.contains("VAD model absent; RMS endpointing"));
        assertTrue(load.contains("catch (Throwable t)"));
        assertTrue(block(src, "    private void beginTurn(boolean manual, float gain) {")
                .contains("if (vad != null) vad.reset();"));
        assertTrue(block(src, "    private void releaseAll() {").contains("vad.close()"));
    }

    @Test
    public void vadWrapperDeclaresOnlyExportedExternals() throws Exception {
        String kt = readProjectFile(VAD_KT);
        assertFalse(kt.contains("compute("));
        assertFalse(kt.contains("bufferSizeInSeconds"));
        assertTrue(kt.contains("private external fun isSpeechDetected(ptr: Long): Boolean"));
        assertTrue(kt.contains("private external fun acceptWaveform(ptr: Long, samples: FloatArray)"));
        assertEquals(11, count(kt, "external fun"));
        assertTrue(kt.contains("System.loadLibrary(\"sherpa-onnx-jni\")"));
        assertTrue(kt.contains("require(ptr != 0L)"));
    }

    @Test
    public void commandVadFeedsFixedWindowsWithTheAgreedConfig() throws Exception {
        String src = readProjectFile(COMMAND_VAD);
        assertTrue(src.contains("\"voice/vad/silero_vad.onnx\""));
        assertTrue(src.contains("WINDOW = 512"));
        assertTrue(src.contains("THRESHOLD = 0.5f"));
        assertTrue(src.contains("MIN_SILENCE_S = 0.10f"));
        assertTrue(src.contains("MIN_SPEECH_S = 0.10f"));
        assertTrue(src.contains("MAX_SPEECH_S = 8.0f"));
        assertTrue(src.contains("if (pending == WINDOW)"));
    }

    @Test
    public void cloudDeadlineIsProviderAnchored() throws Exception {
        String src = readProjectFile(ENGINE);
        String tick = block(src, "    private void tickCloud(long now) {");
        assertTrue(tick.contains("deadline.localSpeechEnded(now)"));
        assertTrue(tick.contains("deadline.speechContinues(now, speechStartAt)"));
        assertTrue(tick.contains("deadline.timedOut(now)"));
        assertTrue(tick.contains("noteTurn(\"cloud_no_response\")"));
        assertTrue(tick.contains("CLOUD_STALL_MS"));
        assertTrue(tick.contains("CLOUD_MAX_MS"));
        assertTrue(tick.contains("CLOUD_NO_SPEECH_MS"));
        String events = src.substring(src.indexOf(
                "    private final class SessionEvents implements VoiceSessionClient.Listener {"));
        assertTrue(events.contains("deadline.setProviderEndpoints(\"openai\".equals(provider))"));
        assertTrue(events.contains("deadline.providerSpeechStopped(now)"));
        assertTrue(events.contains("deadline.providerResponding(now)"));
    }

    @Test
    public void speechStoppedIsRelayedWithAReasonAndNotAsResponding() throws Exception {
        String socket = readProjectFile(SOCKET);
        assertTrue(socket.contains("\"speech_stopped\" : \"response_created\""));
        String client = readProjectFile(CLIENT);
        assertTrue(client.contains("void onConnected(String provider);"));
        assertTrue(client.contains("void onUserSpeechStopped();"));
        String state = block(client,
                "    private void onStateLocked(String state, String reason, boolean afterClear) {");
        int stopped = state.indexOf("\"speech_stopped\".equals(reason)");
        int responding = state.indexOf("post(listener::onResponding)");
        assertTrue(stopped >= 0);
        assertTrue(responding > stopped);
        assertTrue(state.substring(stopped, responding).contains("post(listener::onUserSpeechStopped)"));
    }

    private static String block(String src, String openingLine) {
        int start = src.indexOf(openingLine);
        assertTrue("missing " + openingLine.trim(), start >= 0);
        int bodyStart = start + openingLine.length();
        int end = src.indexOf("\n    }\n", bodyStart);
        assertTrue(end > bodyStart);
        return src.substring(bodyStart, end);
    }

    private static int count(String text, String needle) {
        int n = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            n++;
            at = text.indexOf(needle, at + needle.length());
        }
        return n;
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
