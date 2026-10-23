package com.overdrive.app.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

public class VoiceAssistantConfigDirectFallbackTest {

    @Test
    public void defaultsToOff() {
        assertFalse(VoiceAssistantConfig.from(new JSONObject()).realtimeDirectFallback);
        assertFalse(VoiceAssistantConfig.from(null).realtimeDirectFallback);
    }

    @Test
    public void toJsonRoundTrips() throws Exception {
        VoiceAssistantConfig on = VoiceAssistantConfig.from(
                new JSONObject().put("realtimeDirectFallback", true));
        assertTrue(on.realtimeDirectFallback);
        assertTrue(on.toJson().getBoolean("realtimeDirectFallback"));
        assertTrue(VoiceAssistantConfig.from(on.toJson()).realtimeDirectFallback);
        VoiceAssistantConfig off = VoiceAssistantConfig.from(
                new JSONObject().put("realtimeDirectFallback", false));
        assertFalse(off.toJson().getBoolean("realtimeDirectFallback"));
        assertFalse(VoiceAssistantConfig.from(off.toJson()).realtimeDirectFallback);
    }

    @Test
    public void validatedPatchKeepsABooleanAndRejectsAString() throws Exception {
        JSONObject kept = VoiceAssistantConfig.validatedPatch(
                new JSONObject().put("realtimeDirectFallback", true));
        assertTrue(kept.getBoolean("realtimeDirectFallback"));
        assertEquals(1, kept.length());
        try {
            VoiceAssistantConfig.validatedPatch(new JSONObject().put("realtimeDirectFallback", "yes"));
            fail("a string is not a flag");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("realtimeDirectFallback"));
        }
    }

    @Test
    public void flagDoesNotChangeTheRuntimeShape() throws Exception {
        VoiceAssistantConfig a = VoiceAssistantConfig.from(
                new JSONObject().put("enabled", true).put("realtimeDirectFallback", false));
        VoiceAssistantConfig b = VoiceAssistantConfig.from(
                new JSONObject().put("enabled", true).put("realtimeDirectFallback", true));
        assertTrue(a.sameRuntimeShape(b));
        assertTrue(b.sameRuntimeShape(a));
    }
}
