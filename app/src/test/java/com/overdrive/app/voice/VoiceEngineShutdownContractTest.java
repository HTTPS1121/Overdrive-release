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
 * Pins the single shutdown path of VoiceEngine: close() and the fatal exit in run() must both go
 * through shutdown(reason) so a crashed engine releases the wake-only mic capture, invalidates
 * queued dispatches and stops the io executor exactly like a normal close does.
 */
public class VoiceEngineShutdownContractTest {

    private static final String ENGINE =
            "src/main/java/com/overdrive/app/voice/VoiceEngine.java";

    @Test
    public void runFinallyUsesSharedShutdown() throws Exception {
        String src = readProjectFile(ENGINE);
        String runBody = block(src, "    private void run() {");
        int finallyAt = runBody.indexOf("} finally {");
        assertTrue(finallyAt >= 0);
        String finallyBody = runBody.substring(finallyAt);
        assertTrue(finallyBody.contains("shutdown("));
        assertFalse(finallyBody.contains("releaseAll();"));
    }

    @Test
    public void closeDelegatesToShutdown() throws Exception {
        String src = readProjectFile(ENGINE);
        String closeBody = block(src, "    void close() {");
        assertEquals("shutdown(\"close\");", closeBody.trim());
    }

    @Test
    public void shutdownClearsCaptureBumpsTurnAndReleasesInOrder() throws Exception {
        String src = readProjectFile(ENGINE);
        String body = block(src, "    private void shutdown(String reason) {");
        int running = body.indexOf("running = false");
        int sink = body.indexOf("AppAudioCaptureController.setWakeSink(null)");
        int turn = body.indexOf("turn++");
        int self = body.indexOf("Thread.currentThread() == thread");
        int release = body.indexOf("releaseAll()");
        int io = body.indexOf("io.shutdownNow()");
        assertTrue(running >= 0);
        assertTrue(sink > running);
        assertTrue(turn > sink);
        assertTrue(self > turn);
        assertTrue(release > self);
        assertTrue(io > release);
    }

    @Test
    public void releaseAllIsOnlyCalledFromShutdown() throws Exception {
        String src = readProjectFile(ENGINE);
        assertEquals(1, count(src, "releaseAll();"));
        String body = block(src, "    private void shutdown(String reason) {");
        assertTrue(body.contains("releaseAll();"));
    }

    /** Returns the text between a method's opening line and its closing brace at the same indent. */
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
