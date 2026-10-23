package com.overdrive.app.voice.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Pins that the stage-2 wake rules live in WakePhraseMatcher only: CommandTranscriber delegates
 * and no longer carries its own spelling tables.
 */
public class CommandTranscriberContractTest {

    private static final String TRANSCRIBER =
            "src/main/java/com/overdrive/app/voice/local/CommandTranscriber.java";

    @Test
    public void saysWakeWordIsAOneLineDelegate() throws Exception {
        String src = readProjectFile(TRANSCRIBER);
        String body = block(src, "    static boolean saysWakeWord(String text) {");
        assertEquals("return WakePhraseMatcher.saysWakeWord(text);", body.trim());
    }

    @Test
    public void legacyWakeTablesAreGone() throws Exception {
        String src = readProjectFile(TRANSCRIBER);
        assertFalse(src.contains("WAKE_SPELLINGS"));
        assertFalse(src.contains("WAKE_FIRST"));
    }

    @Test
    public void hearsWakeWordStillReportsThroughSaysWakeWord() throws Exception {
        String src = readProjectFile(TRANSCRIBER);
        String body = block(src,
                "    public synchronized String hearsWakeWord(short[] pcm, int offset, int length, boolean[] heard) {");
        assertTrue(body.contains("heard[0] = saysWakeWord(text);"));
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
