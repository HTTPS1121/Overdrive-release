package com.overdrive.app.voice.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Format pins for the keyword-spotter wake phrases: the six base pronunciations are untouched,
 * every variant is a stricter "@overdrive" line, every phone is in the shipped token table and no
 * wake line collides with a command phrase.
 */
public class WakeKeywordsContractTest {

    private static final String TOKENS = "src/main/assets/voice/kws/tokens.txt";
    private static final String KEYWORDS_EN = "src/main/assets/voice/kws/keywords-en.txt";
    private static final String KEYWORDS_DEFAULT = "src/main/assets/voice/kws/keywords-default.txt";

    private static final String[] BASE = {
            "OW1 V ER0 D R AY2 V :2.0 #0.10 @overdrive",
            "OW1 V ER0 D R AY1 V :2.0 #0.10 @overdrive",
            "OW1 V AH0 D R AY1 V :2.0 #0.10 @overdrive",
            "OW1 V AH0 D R AY2 V :2.0 #0.10 @overdrive",
            "AO1 V ER0 D R AY1 V :2.0 #0.10 @overdrive",
            "OW1 V ER1 D R AY1 V :2.0 #0.10 @overdrive"};

    private static final Pattern LINE = Pattern.compile("^([A-Z]+[0-2]? )+:2\\.0 #0\\.1[05] @overdrive$");

    private static String[] wakeLines() {
        return LocalCommandSpotter.WAKE_KEYWORDS.split("/");
    }

    @Test
    public void thirteenLines() {
        assertEquals(13, wakeLines().length);
    }

    @Test
    public void baseSixLinesAreUnchanged() {
        String[] lines = wakeLines();
        for (int i = 0; i < BASE.length; i++) {
            assertEquals("line " + (i + 1), BASE[i], lines[i]);
        }
    }

    @Test
    public void everyLineIsOverdriveWithBoost2AndThreshold010or015() {
        String[] lines = wakeLines();
        for (int i = 0; i < lines.length; i++) {
            assertTrue(lines[i], LINE.matcher(lines[i]).matches());
            String threshold = i < 6 ? "#0.10" : "#0.15";
            assertTrue(lines[i], lines[i].contains(" " + threshold + " "));
        }
    }

    @Test
    public void everyWakeTokenIsInTheShippedTokenTable() throws Exception {
        Set<String> tokens = new HashSet<>();
        for (String line : readProjectFile(TOKENS).split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            tokens.add(t.split("\\s+")[0]);
        }
        for (String line : wakeLines()) {
            for (String phone : phones(line)) {
                assertTrue(phone + " in " + line, tokens.contains(phone));
            }
        }
    }

    @Test
    public void noWakeLineIsAPrefixOfACommandPhraseOrViceVersa() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        for (String file : new String[] {KEYWORDS_EN, KEYWORDS_DEFAULT}) {
            for (String line : readProjectFile(file).split("\n")) {
                if (line.trim().isEmpty()) continue;
                commands.add(phones(line));
            }
        }
        assertFalse(commands.isEmpty());
        for (String line : wakeLines()) {
            List<String> wake = phones(line);
            for (List<String> command : commands) {
                assertFalse(line + " vs " + command, isPrefix(wake, command));
                assertFalse(line + " vs " + command, isPrefix(command, wake));
            }
        }
    }

    /** Token sequence before the first ":"/"#"/"@" field of a keyword line. */
    private static List<String> phones(String line) {
        List<String> out = new ArrayList<>();
        for (String field : line.trim().split("\\s+")) {
            if (field.startsWith(":") || field.startsWith("#") || field.startsWith("@")) break;
            out.add(field);
        }
        assertFalse(line, out.isEmpty());
        return out;
    }

    private static boolean isPrefix(List<String> shorter, List<String> longer) {
        if (shorter.size() > longer.size()) return false;
        return shorter.equals(longer.subList(0, shorter.size()));
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
