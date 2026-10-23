package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The daemon copies the new media and wake diagnostics with the same discipline as the old ones:
 * the paused player as a short package token, how long the pause took to confirm (-1 = not in
 * time), stage-1 near misses under media as two-decimal probabilities, and the latest/peak stage-1
 * probability with the cutoff in force (kept for /api/voice/status). Each lands in the field log
 * line in a fixed place.
 */
public class VoiceAssistantApiHandlerMediaSessionDiagnosticsTest {

    @Test
    public void thePausedPlayerAndThePauseTimeAreCopiedAndNamedAfterMediaPaused() throws Exception {
        JSONObject turn = turn("cloud").put("cueMs", 180).put("media", true).put("mediaPaused", true)
                .put("mediaPkg", "com.byd.music").put("pauseMs", 140);
        JSONObject clean = VoiceAssistantApiHandler.sanitizeRuntime(
                report(turn).put("turns", new JSONArray().put(turn)));
        JSONObject t = clean.getJSONObject("lastTurn");
        assertEquals("com.byd.music", t.getString("mediaPkg"));
        assertEquals(140, t.getInt("pauseMs"));
        String line = VoiceAssistantApiHandler.summarize(clean);
        assertTrue(line, line.endsWith(" | turns cloud via wake [cue 180 ms, media, media paused in 140 ms (com.byd.music)]"));

        JSONObject late = turn("done").put("media", true).put("mediaPaused", true).put("pauseMs", -1);
        String lateLine = VoiceAssistantApiHandler.summarize(VoiceAssistantApiHandler.sanitizeRuntime(
                report(late).put("turns", new JSONArray().put(late))));
        assertTrue(lateLine, lateLine.endsWith(" [media, media paused unconfirmed]"));

        JSONObject clamped = VoiceAssistantApiHandler.sanitizeRuntime(report(turn("done")
                .put("pauseMs", 99_999))).getJSONObject("lastTurn");
        assertEquals(60_000, clamped.getInt("pauseMs"));
        for (Object bad : new Object[] {"com.a;rm", "a b", "", 5, "x".repeat(33), JSONObject.NULL}) {
            JSONObject out = VoiceAssistantApiHandler.sanitizeRuntime(report(turn("done")
                    .put("mediaPkg", bad))).getJSONObject("lastTurn");
            assertFalse(String.valueOf(bad), out.has("mediaPkg"));
        }
        JSONObject typed = VoiceAssistantApiHandler.sanitizeRuntime(report(turn("done")
                .put("pauseMs", "140"))).getJSONObject("lastTurn");
        assertFalse(typed.has("pauseMs"));
    }

    @Test
    public void nearMissesAreCopiedAsProbabilitiesAndLoggedEvenWithoutChecks() throws Exception {
        JSONArray misses = new JSONArray()
                .put(new JSONObject().put("p", 0.37))
                .put(new JSONObject().put("p", 0.2949))
                .put(new JSONObject().put("p", 1.5))
                .put(new JSONObject().put("p", "0.3"))
                .put(new JSONObject().put("p", 0.21))
                .put(new JSONObject().put("p", 0.22))
                .put(new JSONObject().put("p", 0.23));
        JSONObject clean = VoiceAssistantApiHandler.sanitizeRuntime(report(null).put("nearMisses", misses));
        JSONArray out = clean.getJSONArray("nearMisses");
        assertEquals(4, out.length());
        assertEquals(0.37, out.getJSONObject(0).getDouble("p"), 1e-9);
        assertEquals(0.29, out.getJSONObject(1).getDouble("p"), 1e-9);
        assertEquals(0.21, out.getJSONObject(2).getDouble("p"), 1e-9);
        String line = VoiceAssistantApiHandler.summarize(clean);
        assertNotNull(line);
        assertTrue(line, line.endsWith(" | wake near-miss p=0.37 media; wake near-miss p=0.29 media; "
                + "wake near-miss p=0.21 media; wake near-miss p=0.22 media"));
        assertNull(VoiceAssistantApiHandler.summarize(VoiceAssistantApiHandler.sanitizeRuntime(report(null))));
    }

    @Test
    public void stage1ValuesAreKeptOnlyWithAValidCutoff() throws Exception {
        JSONObject clean = VoiceAssistantApiHandler.sanitizeRuntime(report(null).put("stage1",
                new JSONObject().put("lastP", 0.123).put("peakP", 0.4).put("cutoff", 0.35).put("media", true)));
        JSONObject s = clean.getJSONObject("stage1");
        assertEquals(0.12, s.getDouble("lastP"), 1e-9);
        assertEquals(0.4, s.getDouble("peakP"), 1e-9);
        assertEquals(0.35, s.getDouble("cutoff"), 1e-9);
        assertTrue(s.getBoolean("media"));
        JSONObject noMedia = VoiceAssistantApiHandler.sanitizeRuntime(report(null).put("stage1",
                new JSONObject().put("lastP", -1).put("peakP", "x").put("cutoff", 0.45).put("media", "yes")))
                .getJSONObject("stage1");
        assertFalse(noMedia.has("lastP"));
        assertFalse(noMedia.has("peakP"));
        assertFalse(noMedia.has("media"));
        assertFalse(VoiceAssistantApiHandler.sanitizeRuntime(report(null).put("stage1",
                new JSONObject().put("lastP", 0.1))).has("stage1"));
        assertFalse(VoiceAssistantApiHandler.sanitizeRuntime(report(null).put("stage1", 3)).has("stage1"));
    }

    @Test
    public void theStoredRuntimeKeepsStage1ButNotTheNearMissQueue() throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(resolve(
                "src/main/java/com/overdrive/app/server/VoiceAssistantApiHandler.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        int stored = src.indexOf("JSONObject stored = new JSONObject(clean.toString());");
        String block = src.substring(stored, src.indexOf("runtime = stored;", stored));
        assertTrue(block.contains("stored.remove(\"nearMisses\");"));
        assertFalse(block.contains("stored.remove(\"stage1\");"));
    }

    private static java.nio.file.Path resolve(String relativePath) {
        java.nio.file.Path current = java.nio.file.Paths.get("").toAbsolutePath();
        java.nio.file.Path direct = current.resolve(relativePath);
        return java.nio.file.Files.exists(direct) ? direct : current.resolve("app").resolve(relativePath);
    }

    private static JSONObject turn(String outcome) throws Exception {
        return new JSONObject()
                .put("at", System.currentTimeMillis())
                .put("outcome", outcome)
                .put("via", "wake");
    }

    private static JSONObject report(JSONObject lastTurn) throws Exception {
        JSONObject counters = new JSONObject();
        for (String name : new String[] {"triggers", "wakes", "rejectedWakes", "localHits", "cloudTurns",
                "failures", "asrHits", "rewakes"}) {
            counters.put(name, 1);
        }
        JSONObject body = new JSONObject().put("state", "listening").put("counters", counters);
        if (lastTurn != null) body.put("lastTurn", lastTurn);
        return body;
    }
}
