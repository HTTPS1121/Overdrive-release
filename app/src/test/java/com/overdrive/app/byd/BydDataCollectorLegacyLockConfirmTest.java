package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

import org.junit.Test;

/**
 * Pins the non-Di5 legacy lock-confirmation block of {@code BydDataCollector} byte-for-byte and the
 * Di5-only decoupling of {@code collectLockState} from {@code anyReferenced(BydEvent.LOCK)}: the
 * non-Di5 term is preserved, so legacy firmware keeps exactly today's predicate and cost.
 */
public class BydDataCollectorLegacyLockConfirmTest {
    private static final String START_MARKER = "    private static final long LOCK_CONFIRM_POLL_MS";
    private static final String END_MARKER = "    // ---";
    static final String LEGACY_BLOCK_SHA256 =
            "dfe49da498fcc64851b8c5e1ed6c90c7a0bf075c13694adca9ae40aaf15cd089";

    @Test
    public void legacyLockConfirmationBlockIsByteForByteIntact() throws Exception {
        String collector = read("app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        int start = collector.indexOf(START_MARKER);
        assertTrue("legacy block start marker missing", start >= 0);
        int end = collector.indexOf(END_MARKER, start);
        assertTrue("legacy block end marker missing", end > start);
        String block = collector.substring(start, end);
        assertEquals(LEGACY_BLOCK_SHA256, sha256(block));
        assertEquals(1, count(collector, START_MARKER));
    }

    @Test
    public void legacyConfirmationReturnsBeforeSchedulingOnDi5OrWithoutALockRule() throws Exception {
        String collector = read("app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        int start = collector.indexOf(START_MARKER);
        String block = collector.substring(start, collector.indexOf(END_MARKER, start));
        assertTrue(block.contains(
                "    public void confirmLockStateAfterCommand(boolean locked) {\n"
                        + "        if (isDiLink5Vehicle()\n"
                        + "                || !anyReferenced(com.overdrive.app.automation.condition.BydEvent.LOCK)) {\n"
                        + "            return;\n"
                        + "        }"));
        assertTrue(block.indexOf("return;") < block.indexOf("executor.execute("));
        assertEquals(1, count(block, "new Thread(r, \"LockStateConfirm\")"));
        assertTrue(block.contains("private synchronized void confirmLockState("));
        assertTrue(block.contains("if (generation != lockConfirmGeneration.get()) return;"));
        assertTrue(block.contains("if (run >= 2) {"));
        assertEquals(1, count(block, "LOCK_CONFIRM_POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS"));
    }

    @Test
    public void collectAllPredicateKeepsTheNonDi5TermAndAddsOnlyTheDi5Producer() throws Exception {
        String collector = read("app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        String predicate =
                "        if (!diLink5FastRefresh\n"
                        + "                && (isDiLink5ProducerActive()\n"
                        + "                    || anyReferenced(com.overdrive.app.automation.condition.BydEvent.LOCK))) {\n"
                        + "            collectLockState();\n"
                        + "        }";
        assertEquals(1, count(collector, predicate));
        // The old predicate (anyReferenced alone) is gone; collectLockState() has exactly the fast-loop
        // call and this main-poll call.
        assertFalse(collector.contains(
                "        if (!diLink5FastRefresh\n"
                        + "                && anyReferenced(com.overdrive.app.automation.condition.BydEvent.LOCK)) {\n"
                        + "            collectLockState();"));
        assertEquals(2, count(collector, "collectLockState();"));
        // Both terms sit inside the same "if (!diLink5FastRefresh" condition.
        int at = collector.indexOf(predicate);
        String condition = collector.substring(at, collector.indexOf(") {", at));
        assertTrue(condition.contains("isDiLink5ProducerActive() ||")
                || condition.contains("isDiLink5ProducerActive()\n                    ||"));
        assertTrue(condition.contains("anyReferenced(com.overdrive.app.automation.condition.BydEvent.LOCK)"));
        // The comment no longer claims the Di5 read is the OTA rail.
        int commentStart = collector.lastIndexOf("// Central-lock state (locked/unlocked)", at);
        assertTrue(commentStart > 0);
        String comment = collector.substring(commentStart, at);
        assertFalse(comment, comment.contains("OTA rail (getLFDoorLockState). Published"));
        assertTrue(comment, comment.contains("typed cabin on DiLink 5"));
        assertTrue(comment, comment.contains("must not depend on a user automation existing"));
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static String read(String relative) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path)) {
            path = Paths.get(System.getProperty("user.dir")).getParent().resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
