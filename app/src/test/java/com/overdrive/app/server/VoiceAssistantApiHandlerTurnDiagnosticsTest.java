package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The daemon sanitises the app's listen-window diagnostics (cue, listen and speech measurements),
 * logs them in one fixed order, and the biggest report the app can send still fits under the cap
 * with headroom.
 */
public class VoiceAssistantApiHandlerTurnDiagnosticsTest {

    private static final String[] DIAGNOSTIC_KEYS = {
            "cueMs", "cueVol", "cueDb", "listenMs", "micDb", "vadMs", "loudMs", "tail"
    };

    @Test
    public void diagnosticsAreClampedToTheirPhysicalRanges() throws Exception {
        JSONObject turn = turn("no_speech")
                .put("cueMs", 9999)
                .put("cueVol", 250)
                .put("cueDb", 12)
                .put("listenMs", 99_999)
                .put("micDb", 5)
                .put("vadMs", -4)
                .put("loudMs", 70_000)
                .put("tail", 7);
        JSONObject clean = VoiceAssistantApiHandler.sanitizeRuntime(report(turn, null));
        JSONObject t = clean.getJSONObject("lastTurn");
        assertEquals(5000, t.getInt("cueMs"));
        assertEquals(100, t.getInt("cueVol"));
        assertEquals(0, t.getInt("cueDb"));
        assertEquals(60_000, t.getInt("listenMs"));
        assertEquals(0, t.getInt("micDb"));
        assertEquals(0, t.getInt("vadMs"));
        assertEquals(60_000, t.getInt("loudMs"));
        assertEquals(1, t.getInt("tail"));
    }

    @Test
    public void cueFallbackIsCopiedOnlyAsAShortTokenAndNamedInTheSummary() throws Exception {
        JSONObject turn = turn("no_speech").put("cueMs", 180).put("cueVol", 0).put("cueFallback", "voice");
        JSONObject clean = VoiceAssistantApiHandler.sanitizeRuntime(report(turn, turn));
        assertEquals("voice", clean.getJSONObject("lastTurn").getString("cueFallback"));
        String line = VoiceAssistantApiHandler.summarize(clean);
        assertTrue(line, line.endsWith(" | turns no_speech via wake [cue 180 ms, vol 0, cue on voice]"));

        for (Object bad : new Object[] {"", "Voice Stream", "a-b", 7, "x".repeat(17), JSONObject.NULL}) {
            JSONObject t = VoiceAssistantApiHandler.sanitizeRuntime(
                    report(turn("no_speech").put("cueFallback", bad), null)).getJSONObject("lastTurn");
            assertFalse(String.valueOf(bad), t.has("cueFallback"));
        }
        JSONObject absent = VoiceAssistantApiHandler.sanitizeRuntime(report(turn("done"), null))
                .getJSONObject("lastTurn");
        assertFalse(absent.has("cueFallback"));
    }

    @Test
    public void absentOrNonNumericDiagnosticsAreOmitted() throws Exception {
        JSONObject plain = VoiceAssistantApiHandler.sanitizeRuntime(report(turn("local_hit"), null))
                .getJSONObject("lastTurn");
        for (String key : DIAGNOSTIC_KEYS) assertFalse(key, plain.has(key));

        JSONObject typed = VoiceAssistantApiHandler.sanitizeRuntime(
                report(turn("no_speech").put("cueMs", "180").put("micDb", -54), null))
                .getJSONObject("lastTurn");
        assertFalse(typed.has("cueMs"));
        assertEquals(-54, typed.getInt("micDb"));
    }

    @Test
    public void summaryAppendsTheDiagnosticsInAFixedOrder() throws Exception {
        JSONObject turn = turn("no_speech")
                .put("cueMs", 180)
                .put("cueVol", 0)
                .put("cueDb", -55)
                .put("listenMs", 4870)
                .put("micDb", -54)
                .put("tail", 1);
        JSONObject clean = VoiceAssistantApiHandler.sanitizeRuntime(report(turn, turn));
        String line = VoiceAssistantApiHandler.summarize(clean);
        assertTrue(line, line.endsWith(
                " | turns no_speech via wake [cue 180 ms, vol 0, heard -55 dBFS, listened 4870 ms, mic -54 dBFS, tail 1]"));

        JSONObject bare = VoiceAssistantApiHandler.sanitizeRuntime(report(turn("local_hit"), turn("local_hit")));
        String bareLine = VoiceAssistantApiHandler.summarize(bare);
        assertTrue(bareLine, bareLine.endsWith(" | turns local_hit via wake"));
        assertFalse(bareLine.contains("["));
    }

    @Test
    public void maximalReportFitsUnderTheCapWithHeadroom() throws Exception {
        String text40 = "forty characters of transcript text here";
        assertEquals(40, text40.length());
        JSONObject counters = new JSONObject();
        for (String name : new String[] {"triggers", "wakes", "rejectedWakes", "localHits", "cloudTurns",
                "failures", "asrHits", "rewakes"}) {
            counters.put(name, 123456);
        }
        JSONArray checks = new JSONArray();
        for (int i = 0; i < 8; i++) checks.put(check(text40));
        JSONArray turns = new JSONArray();
        for (int i = 0; i < 4; i++) turns.put(maximalTurn(text40));
        JSONObject body = new JSONObject()
                .put("state", "listening")
                .put("wakeModelPresent", true)
                .put("localEnginePresent", true)
                .put("micSource", "voice_communication")
                .put("micSourceFailed", true)
                .put("aecAvailable", true)
                .put("aecEnabled", true)
                .put("lastWakeAt", System.currentTimeMillis())
                .put("counters", counters)
                .put("lastVerify", check(text40))
                .put("checks", checks)
                .put("lastTurn", maximalTurn(text40))
                .put("turns", turns)
                .put("checksDropped", 10_000)
                .put("mic", new JSONObject().put("peakDb", -12).put("silentPct", 100).put("droppedMs", 600_000));
        JSONArray misses = new JSONArray();
        for (int i = 0; i < 4; i++) misses.put(new JSONObject().put("p", 0.44));
        body.put("nearMisses", misses)
                .put("stage1", new JSONObject().put("lastP", 0.44).put("peakP", 0.44)
                        .put("cutoff", 0.35).put("media", true));
        int size = body.toString().getBytes(StandardCharsets.UTF_8).length;
        assertTrue("maximal report is " + size + " bytes",
                size <= VoiceAssistantApiHandler.MAX_RUNTIME_BYTES * 9 / 10);
        // And the daemon still accepts every field of it.
        JSONObject clean = VoiceAssistantApiHandler.sanitizeRuntime(body);
        assertEquals(4, clean.getJSONArray("turns").length());
        assertEquals(8, clean.getJSONArray("checks").length());
        assertTrue(clean.getJSONArray("checks").getJSONObject(7).getBoolean("media"));
        assertTrue(clean.getJSONArray("turns").getJSONObject(3).getBoolean("mediaPaused"));
        assertEquals("voice_communication", clean.getString("micSource"));
        assertTrue(clean.getBoolean("micSourceFailed"));
        assertEquals(32, clean.getJSONArray("turns").getJSONObject(3).getString("mediaPkg").length());
        assertEquals(60_000, clean.getJSONObject("lastTurn").getInt("pauseMs"));
        assertEquals(4, clean.getJSONArray("nearMisses").length());
        assertEquals(0.35, clean.getJSONObject("stage1").getDouble("cutoff"), 1e-9);
    }

    private static JSONObject check(String text) throws Exception {
        return new JSONObject()
                .put("at", System.currentTimeMillis())
                .put("heard", false)
                .put("by", "none")
                .put("levelDb", -100)
                .put("gainDb", 40)
                .put("ms", 60_000)
                .put("text", text)
                .put("media", true);
    }

    private static JSONObject maximalTurn(String text) throws Exception {
        JSONArray detail = new JSONArray()
                .put(new JSONObject().put("intent", "climate.set_temperature")
                        .put("slots", new JSONObject().put("value", 22).put("unit", "celsius").put("seat", "driver")))
                .put(new JSONObject().put("intent", "media.volume")
                        .put("slots", new JSONObject().put("delta", -2).put("zone", "front")));
        return turn("held_unverified")
                .put("intents", "climate.set_temperature,media.volume,windows.open,assistant.cancel")
                .put("by", "kwsasr")
                .put("held", 16)
                .put("asrMs", 60_000)
                .put("text", text)
                .put("intentDetail", detail)
                .put("speechEndAt", System.currentTimeMillis())
                .put("firstDispatchAt", System.currentTimeMillis())
                .put("firstReplyAt", System.currentTimeMillis())
                .put("cueMs", 5000)
                .put("cueVol", 100)
                .put("cueFallback", "notification")
                .put("cueDb", -150)
                .put("listenMs", 60_000)
                .put("micDb", -150)
                .put("vadMs", 60_000)
                .put("loudMs", 60_000)
                .put("tail", 1)
                .put("media", true)
                .put("mediaPaused", true)
                .put("mediaPkg", "android.apps.youtube.music.extra")
                .put("pauseMs", 60_000);
    }

    private static JSONObject turn(String outcome) throws Exception {
        return new JSONObject()
                .put("at", System.currentTimeMillis())
                .put("outcome", outcome)
                .put("via", "wake");
    }

    private static JSONObject report(JSONObject lastTurn, JSONObject turnInLog) throws Exception {
        JSONObject counters = new JSONObject();
        for (String name : new String[] {"triggers", "wakes", "rejectedWakes", "localHits", "cloudTurns",
                "failures", "asrHits", "rewakes"}) {
            counters.put(name, 1);
        }
        JSONObject body = new JSONObject()
                .put("state", "listening")
                .put("counters", counters)
                .put("lastTurn", lastTurn);
        if (turnInLog != null) body.put("turns", new JSONArray().put(turnInLog));
        return body;
    }
}
