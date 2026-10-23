package com.overdrive.app.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The resume-after-listening toggle (default on), the location-sharing toggle (default on, the
 * user's decision of 2026-10-11), and the stage-1 cutoff under media (default 0.35, accepted
 * 0.20-0.45, a measurement knob without UI).
 */
public class VoiceAssistantConfigMediaSessionTest {

    @Test
    public void defaultsAreResumeOnLocationOnAndCutoff035() {
        for (VoiceAssistantConfig cfg : new VoiceAssistantConfig[] {
                VoiceAssistantConfig.from(new JSONObject()), VoiceAssistantConfig.from(null)}) {
            assertTrue(cfg.resumeMediaAfterListening);
            assertTrue(cfg.shareLocationWithAi);
            assertEquals(0.35, cfg.wakeCutoffUnderMedia, 1e-9);
        }
        assertEquals(0.20, VoiceAssistantConfig.WAKE_CUTOFF_UNDER_MEDIA_MIN, 1e-9);
        assertEquals(0.45, VoiceAssistantConfig.WAKE_CUTOFF_UNDER_MEDIA_MAX, 1e-9);
    }

    @Test
    public void theFieldsRoundTripThroughToJson() throws Exception {
        VoiceAssistantConfig cfg = VoiceAssistantConfig.from(new JSONObject()
                .put("resumeMediaAfterListening", false)
                .put("shareLocationWithAi", false)
                .put("wakeCutoffUnderMedia", 0.3));
        JSONObject json = cfg.toJson();
        assertFalse(json.getBoolean("resumeMediaAfterListening"));
        assertFalse(json.getBoolean("shareLocationWithAi"));
        assertEquals(0.3, json.getDouble("wakeCutoffUnderMedia"), 1e-9);
        VoiceAssistantConfig again = VoiceAssistantConfig.from(json);
        assertFalse(again.resumeMediaAfterListening);
        assertFalse(again.shareLocationWithAi);
        assertEquals(0.3, again.wakeCutoffUnderMedia, 1e-9);
    }

    @Test
    public void storedCutoffsAreKeptInRangeAndRounded() throws Exception {
        assertEquals(0.20, VoiceAssistantConfig.from(new JSONObject().put("wakeCutoffUnderMedia", 0.05))
                .wakeCutoffUnderMedia, 1e-9);
        assertEquals(0.45, VoiceAssistantConfig.from(new JSONObject().put("wakeCutoffUnderMedia", 0.9))
                .wakeCutoffUnderMedia, 1e-9);
        assertEquals(0.33, VoiceAssistantConfig.from(new JSONObject().put("wakeCutoffUnderMedia", 0.3333))
                .wakeCutoffUnderMedia, 1e-9);
        assertEquals(0.35, VoiceAssistantConfig.from(new JSONObject().put("wakeCutoffUnderMedia", "low"))
                .wakeCutoffUnderMedia, 1e-9);
    }

    @Test
    public void validatedPatchAcceptsBooleansAndTheCutoffRangeOnly() throws Exception {
        JSONObject kept = VoiceAssistantConfig.validatedPatch(new JSONObject()
                .put("resumeMediaAfterListening", false)
                .put("shareLocationWithAi", true)
                .put("wakeCutoffUnderMedia", 0.25));
        assertFalse(kept.getBoolean("resumeMediaAfterListening"));
        assertTrue(kept.getBoolean("shareLocationWithAi"));
        assertEquals(0.25, kept.getDouble("wakeCutoffUnderMedia"), 1e-9);
        assertEquals(3, kept.length());
        for (double edge : new double[] {0.20, 0.45}) {
            assertEquals(edge, VoiceAssistantConfig.validatedPatch(
                    new JSONObject().put("wakeCutoffUnderMedia", edge)).getDouble("wakeCutoffUnderMedia"), 1e-9);
        }
        for (Object bad : new Object[] {0.19, 0.46, 1, -0.3, "0.3", true}) {
            try {
                VoiceAssistantConfig.validatedPatch(new JSONObject().put("wakeCutoffUnderMedia", bad));
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("wakeCutoffUnderMedia"));
            }
        }
        for (String key : new String[] {"resumeMediaAfterListening", "shareLocationWithAi"}) {
            try {
                VoiceAssistantConfig.validatedPatch(new JSONObject().put(key, "yes"));
                fail(key + " accepted a string");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains(key));
            }
        }
    }

    @Test
    public void theLocationModeFollowsTheLocationToggleAndEveryOtherModeTheLiveStatusOne() throws Exception {
        VoiceAssistantConfig both = VoiceAssistantConfig.from(new JSONObject());
        assertTrue(both.sharesContext("location"));
        assertTrue(both.sharesContext("current_vehicle"));
        VoiceAssistantConfig noLocation = VoiceAssistantConfig.from(new JSONObject().put("shareLocationWithAi", false));
        assertFalse(noLocation.sharesContext("location"));
        assertTrue(noLocation.sharesContext("current_vehicle"));
        VoiceAssistantConfig noStatus = VoiceAssistantConfig.from(new JSONObject().put("shareVehicleState", false));
        assertTrue(noStatus.sharesContext("location"));
        assertFalse(noStatus.sharesContext("overview"));
        assertFalse(noStatus.sharesContext(""));
    }

    @Test
    public void noneOfTheFieldsChangesTheRuntimeShape() throws Exception {
        VoiceAssistantConfig a = VoiceAssistantConfig.from(new JSONObject().put("enabled", true));
        VoiceAssistantConfig b = VoiceAssistantConfig.from(new JSONObject().put("enabled", true)
                .put("resumeMediaAfterListening", false).put("shareLocationWithAi", false)
                .put("wakeCutoffUnderMedia", 0.2));
        assertTrue(a.sameRuntimeShape(b));
    }
}
