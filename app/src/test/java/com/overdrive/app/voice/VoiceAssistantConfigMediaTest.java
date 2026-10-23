package com.overdrive.app.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

/** The pause-media toggle (default on) and the experimental microphone input (default mic). */
public class VoiceAssistantConfigMediaTest {

    @Test
    public void pauseMediaDefaultsToOnAndMicSourceToMic() {
        VoiceAssistantConfig empty = VoiceAssistantConfig.from(new JSONObject());
        assertTrue(empty.pauseMediaWhileListening);
        assertEquals(VoiceAssistantConfig.MIC_SOURCE_MIC, empty.micSource);
        VoiceAssistantConfig absent = VoiceAssistantConfig.from(null);
        assertTrue(absent.pauseMediaWhileListening);
        assertEquals("mic", absent.micSource);
    }

    @Test
    public void bothFieldsRoundTripThroughToJson() throws Exception {
        VoiceAssistantConfig cfg = VoiceAssistantConfig.from(new JSONObject()
                .put("pauseMediaWhileListening", false)
                .put("micSource", "voice_communication"));
        assertFalse(cfg.pauseMediaWhileListening);
        assertEquals("voice_communication", cfg.micSource);
        JSONObject json = cfg.toJson();
        assertFalse(json.getBoolean("pauseMediaWhileListening"));
        assertEquals("voice_communication", json.getString("micSource"));
        VoiceAssistantConfig again = VoiceAssistantConfig.from(json);
        assertFalse(again.pauseMediaWhileListening);
        assertEquals("voice_communication", again.micSource);
    }

    @Test
    public void unknownStoredMicSourceFallsBackToMic() throws Exception {
        assertEquals("mic", VoiceAssistantConfig.from(new JSONObject().put("micSource", "camcorder")).micSource);
        assertEquals("mic", VoiceAssistantConfig.from(new JSONObject().put("micSource", 7)).micSource);
        assertEquals("voice_recognition",
                VoiceAssistantConfig.from(new JSONObject().put("micSource", "voice_recognition")).micSource);
    }

    @Test
    public void validatedPatchKeepsTheToggleAndRejectsNonBooleans() throws Exception {
        JSONObject kept = VoiceAssistantConfig.validatedPatch(
                new JSONObject().put("pauseMediaWhileListening", false));
        assertFalse(kept.getBoolean("pauseMediaWhileListening"));
        assertEquals(1, kept.length());
        try {
            VoiceAssistantConfig.validatedPatch(new JSONObject().put("pauseMediaWhileListening", "no"));
            fail("a string is not a flag");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("pauseMediaWhileListening"));
        }
    }

    @Test
    public void validatedPatchAcceptsOnlyTheThreeMicSources() throws Exception {
        for (String source : new String[] {"mic", "voice_recognition", "voice_communication"}) {
            JSONObject kept = VoiceAssistantConfig.validatedPatch(new JSONObject().put("micSource", source));
            assertEquals(source, kept.getString("micSource"));
            assertEquals(1, kept.length());
            assertTrue(VoiceAssistantConfig.isMicSource(source));
        }
        for (Object bad : new Object[] {"camcorder", "MIC", "", 1, true}) {
            try {
                VoiceAssistantConfig.validatedPatch(new JSONObject().put("micSource", bad));
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("micSource"));
            }
        }
    }

    @Test
    public void neitherFieldChangesTheRuntimeShape() throws Exception {
        VoiceAssistantConfig a = VoiceAssistantConfig.from(new JSONObject().put("enabled", true));
        VoiceAssistantConfig b = VoiceAssistantConfig.from(new JSONObject().put("enabled", true)
                .put("pauseMediaWhileListening", false).put("micSource", "voice_communication"));
        // The capture source is applied by the shared capture controller, not by reloading models.
        assertTrue(a.sameRuntimeShape(b));
        assertTrue(b.sameRuntimeShape(a));
    }
}
