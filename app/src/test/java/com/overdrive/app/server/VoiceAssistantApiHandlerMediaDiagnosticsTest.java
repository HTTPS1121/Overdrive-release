package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The daemon copies the app's media diagnostics as bare flags only: {@code media} on checks and
 * turns, {@code mediaPaused} on turns, the capture's microphone source and the echo-canceller
 * probe on the runtime; each shows up in the field log line in a fixed place.
 */
public class VoiceAssistantApiHandlerMediaDiagnosticsTest {

    @Test
    public void mediaFlagOnChecksIsCopiedOnlyWhenTrueAndNamedInTheSummary() throws Exception {
        JSONObject underMedia = check().put("media", true);
        JSONObject clean = VoiceAssistantApiHandler.sanitizeRuntime(
                report(null).put("lastVerify", underMedia).put("checks", new JSONArray().put(underMedia)));
        assertTrue(clean.getJSONObject("lastVerify").getBoolean("media"));
        assertTrue(clean.getJSONArray("checks").getJSONObject(0).getBoolean("media"));
        String line = VoiceAssistantApiHandler.summarize(clean);
        assertTrue(line, line.contains(" | checks rejected(none) -32 dBFS gain 15 dB 1010 ms media"));

        for (Object bad : new Object[] {false, "true", 1, JSONObject.NULL}) {
            JSONObject c = check().put("media", bad);
            JSONObject out = VoiceAssistantApiHandler.sanitizeRuntime(
                    report(null).put("lastVerify", c).put("checks", new JSONArray().put(c)));
            assertFalse(String.valueOf(bad), out.getJSONObject("lastVerify").has("media"));
            assertFalse(String.valueOf(bad), out.getJSONArray("checks").getJSONObject(0).has("media"));
            assertFalse(VoiceAssistantApiHandler.summarize(out).contains(" media"));
        }
    }

    @Test
    public void mediaAndMediaPausedOnTurnsAreCopiedAndAppendedAfterTheListenDiagnostics() throws Exception {
        JSONObject turn = turn("done").put("cueMs", 180).put("media", true).put("mediaPaused", true);
        JSONObject clean = VoiceAssistantApiHandler.sanitizeRuntime(
                report(turn).put("turns", new JSONArray().put(turn)));
        JSONObject t = clean.getJSONObject("lastTurn");
        assertTrue(t.getBoolean("media"));
        assertTrue(t.getBoolean("mediaPaused"));
        String line = VoiceAssistantApiHandler.summarize(clean);
        assertTrue(line, line.endsWith(" | turns done via wake [cue 180 ms, media, media paused]"));

        JSONObject plain = VoiceAssistantApiHandler.sanitizeRuntime(report(turn("done"))).getJSONObject("lastTurn");
        assertFalse(plain.has("media"));
        assertFalse(plain.has("mediaPaused"));
        JSONObject typed = VoiceAssistantApiHandler.sanitizeRuntime(
                report(turn("done").put("media", "yes").put("mediaPaused", 1))).getJSONObject("lastTurn");
        assertFalse(typed.has("media"));
        assertFalse(typed.has("mediaPaused"));
    }

    @Test
    public void runtimeCarriesTheCaptureSourceAndEchoCancellerProbe() throws Exception {
        JSONObject clean = VoiceAssistantApiHandler.sanitizeRuntime(report(null)
                .put("micSource", "voice_communication")
                .put("aecAvailable", true)
                .put("aecEnabled", false));
        assertEquals("voice_communication", clean.getString("micSource"));
        assertTrue(clean.getBoolean("aecAvailable"));
        assertFalse(clean.getBoolean("aecEnabled"));
        assertFalse(clean.has("micSourceFailed"));
        // A refused input: the app reports what actually opened plus the flag.
        JSONObject fellBack = VoiceAssistantApiHandler.sanitizeRuntime(report(null)
                .put("micSource", "mic")
                .put("micSourceFailed", true));
        assertEquals("mic", fellBack.getString("micSource"));
        assertTrue(fellBack.getBoolean("micSourceFailed"));

        JSONObject absent = VoiceAssistantApiHandler.sanitizeRuntime(report(null));
        assertFalse(absent.has("micSource"));
        assertFalse(absent.has("micSourceFailed"));
        assertFalse(absent.has("aecAvailable"));
        assertFalse(absent.has("aecEnabled"));
        for (Object bad : new Object[] {"Voice Comm", "a-b", "", 3, "x".repeat(25)}) {
            assertFalse(String.valueOf(bad), VoiceAssistantApiHandler.sanitizeRuntime(
                    report(null).put("micSource", bad)).has("micSource"));
        }
        try {
            VoiceAssistantApiHandler.sanitizeRuntime(report(null).put("aecAvailable", "yes"));
            throw new AssertionError("a string is not a flag");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("aecAvailable"));
        }
        try {
            VoiceAssistantApiHandler.sanitizeRuntime(report(null).put("micSourceFailed", 1));
            throw new AssertionError("a number is not a flag");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("micSourceFailed"));
        }
    }

    private static JSONObject check() throws Exception {
        return new JSONObject()
                .put("at", System.currentTimeMillis())
                .put("heard", false)
                .put("by", "none")
                .put("levelDb", -32)
                .put("gainDb", 15)
                .put("ms", 1010);
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
