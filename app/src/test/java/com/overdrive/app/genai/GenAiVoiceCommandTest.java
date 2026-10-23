package com.overdrive.app.genai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class GenAiVoiceCommandTest {

    /** The Assistant page text before Group G, copied verbatim; it must not drift. */
    private static final String PAGE_INSTRUCTIONS =
            "For an explicit request to set cabin temperature, open or close "
            + "the sunshade, or run one saved automation, call "
            + "propose_overdrive_action once. The tool only creates a confirmation card; "
            + "tell the user to confirm on screen and never claim it ran. "
            + "Ask a question instead of calling the tool when any detail is "
            + "missing or ambiguous. For an automation, pass the exact name "
            + "spoken by the user; OverDrive resolves it locally and rejects "
            + "missing or ambiguous matches.";

    @Test
    public void statusMappingIsTruthful() {
        assertEquals("completed", GenAiVoiceCommand.statusFor("done", false));
        assertEquals("duplicate", GenAiVoiceCommand.statusFor("duplicate", false));
        assertEquals("blocked", GenAiVoiceCommand.statusFor("blocked_moving", false));
        assertEquals("refused", GenAiVoiceCommand.statusFor("unsupported", false));
        assertEquals("refused", GenAiVoiceCommand.statusFor("refused", false));
        assertEquals("refused", GenAiVoiceCommand.statusFor("voice_policy", false));
        assertEquals("failed", GenAiVoiceCommand.statusFor("failed", false));
        assertEquals("unknown", GenAiVoiceCommand.statusFor("timeout", false));
        assertEquals("needs_confirmation", GenAiVoiceCommand.statusFor("needs_confirmation", false));
        assertEquals("unknown", GenAiVoiceCommand.statusFor(null, false));
        // A fire-and-forget automation was only started, never "completed".
        assertEquals("accepted", GenAiVoiceCommand.statusFor("done", true));
        assertEquals("accepted", GenAiVoiceCommand.statusFor("failed", true));
    }

    @Test
    public void reportedStatusFallsBackToSuccessWhenOutsideTheEnum() {
        assertEquals("accepted", GenAiVoiceCommand.resultStatus("accepted", true));
        assertEquals("refused", GenAiVoiceCommand.resultStatus("refused", false));
        assertEquals("completed", GenAiVoiceCommand.resultStatus("", true));
        assertEquals("failed", GenAiVoiceCommand.resultStatus(null, false));
        assertEquals("failed", GenAiVoiceCommand.resultStatus("confirmation_required", false));
    }

    @Test
    public void intentRequestDropsSentinelsAndTagsTheSource() throws Exception {
        JSONObject args = new JSONObject()
                .put("intent", "climate.set_temp")
                .put("on", "none")
                .put("open", "none")
                .put("seat", "none")
                .put("level", -1)
                .put("celsius", 21.5)
                .put("delta", 0)
                .put("goal", "none");
        JSONObject request = GenAiVoiceCommand.toIntentRequest(args, "s#call_1", "en");
        assertEquals("climate.set_temp", request.getString("intent"));
        assertEquals("s#call_1", request.getString("utteranceId"));
        assertEquals("genai", request.getString("source"));
        assertEquals("en", request.getString("locale"));
        JSONObject slots = request.getJSONObject("slots");
        assertEquals(1, slots.length());
        assertEquals(21.5, slots.getDouble("celsius"), 0.0001);
    }

    @Test
    public void onAndOpenArePassedAsTheStringsTheExecutorAccepts() throws Exception {
        JSONObject slots = GenAiVoiceCommand.toIntentRequest(sentinels()
                .put("intent", "climate.power").put("on", "off"), "u", "en")
                .getJSONObject("slots");
        assertEquals("off", slots.getString("on"));
        assertEquals(1, slots.length());
        slots = GenAiVoiceCommand.toIntentRequest(sentinels()
                .put("intent", "sunshade").put("open", "close"), "u", "en")
                .getJSONObject("slots");
        assertEquals("close", slots.getString("open"));
        slots = GenAiVoiceCommand.toIntentRequest(sentinels()
                .put("intent", "climate.step_temp").put("delta", -2), "u", "en")
                .getJSONObject("slots");
        assertEquals(-2, slots.getInt("delta"));
        slots = GenAiVoiceCommand.toIntentRequest(sentinels()
                .put("intent", "climate.goal").put("goal", "defog"), "u", "en")
                .getJSONObject("slots");
        assertEquals("defog", slots.getString("goal"));
    }

    @Test
    public void seatIntentsDefaultToTheDriverLikeTheLocalCatalog() throws Exception {
        JSONObject slots = GenAiVoiceCommand.toIntentRequest(sentinels()
                .put("intent", "seat.heat").put("level", 2), "u", "en")
                .getJSONObject("slots");
        assertEquals("driver", slots.getString("seat"));
        assertEquals(2, slots.getInt("level"));
        slots = GenAiVoiceCommand.toIntentRequest(sentinels()
                .put("intent", "seat.heat").put("seat", "passenger").put("level", 2), "u", "en")
                .getJSONObject("slots");
        assertEquals("passenger", slots.getString("seat"));
        // Non-seat intents never gain a seat slot from the sentinel.
        slots = GenAiVoiceCommand.toIntentRequest(sentinels()
                .put("intent", "windows.close_all"), "u", "en").getJSONObject("slots");
        assertEquals(0, slots.length());
    }

    @Test
    public void unknownIntentIsRejected() throws Exception {
        for (String bad : new String[] {"doors.unlock", "", "assistant.cancel"}) {
            try {
                GenAiVoiceCommand.toIntentRequest(sentinels().put("intent", bad), "u", "en");
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }
        try {
            GenAiVoiceCommand.toIntentRequest(null, "u", "en");
            fail("accepted null arguments");
        } catch (IllegalArgumentException expected) {
            // A missing payload is refused, not executed.
        }
    }

    @Test
    public void schemaIsStrictWithEverySlotRequiredAndNoTranscriptionWording()
            throws Exception {
        JSONObject openAi = GenAiVoiceCommand.openAiTool();
        assertEquals("function", openAi.getString("type"));
        assertEquals("run_overdrive_command", openAi.getString("name"));
        JSONObject parameters = openAi.getJSONObject("parameters");
        assertFalse(parameters.getBoolean("additionalProperties"));
        JSONArray required = parameters.getJSONArray("required");
        List<String> requiredNames = new ArrayList<>();
        for (int i = 0; i < required.length(); i++) requiredNames.add(required.getString(i));
        List<String> propertyNames = new ArrayList<>();
        for (java.util.Iterator<String> keys = parameters.getJSONObject("properties").keys();
                keys.hasNext(); ) {
            propertyNames.add(keys.next());
        }
        assertEquals(requiredNames.size(), propertyNames.size());
        assertTrue(requiredNames.containsAll(propertyNames));
        JSONArray intents = parameters.getJSONObject("properties")
                .getJSONObject("intent").getJSONArray("enum");
        assertEquals(65, intents.length());
        assertEquals("climate.power", intents.getString(0));
        assertEquals("recording.save_clip", intents.getString(17));
        assertFalse(openAi.toString().contains("transcription"));
        JSONObject gemini = GenAiVoiceCommand.geminiTool();
        assertEquals("run_overdrive_command", gemini.getString("name"));
        // The Gemini variant keeps the same strict shape (every slot required, no extra
        // properties, same enums and bounds) but carries the per-field guidance in the tool
        // description instead of property descriptions, so its schema keywords stay within
        // the set the two field-proven tools use (see GenAiVoiceWebSocketSetupTest).
        JSONObject geminiParameters = gemini.getJSONObject("parameters");
        assertFalse(geminiParameters.getBoolean("additionalProperties"));
        assertEquals(required.toString(), geminiParameters.getJSONArray("required").toString());
        JSONObject geminiProperties = geminiParameters.getJSONObject("properties");
        for (String name : propertyNames) {
            JSONObject openAiProperty = parameters.getJSONObject("properties").getJSONObject(name);
            JSONObject geminiProperty = geminiProperties.getJSONObject(name);
            assertFalse(name, geminiProperty.has("description"));
            assertEquals(name, openAiProperty.getString("type"), geminiProperty.getString("type"));
            assertEquals(name, openAiProperty.optString("enum"), geminiProperty.optString("enum"));
            assertEquals(name, openAiProperty.optString("minimum"), geminiProperty.optString("minimum"));
            assertEquals(name, openAiProperty.optString("maximum"), geminiProperty.optString("maximum"));
            assertTrue(name, gemini.getString("description").contains(
                    name + ": " + openAiProperty.getString("description")));
        }
        assertEquals(65, geminiProperties.getJSONObject("intent").getJSONArray("enum").length());
        assertTrue(gemini.getString("description").startsWith(openAi.getString("description")));
        assertFalse(gemini.toString().contains("transcription"));
    }

    @Test
    public void farFieldInstructionsNeverMentionTheScreen() {
        String farField = GenAiAction.realtimeInstructions(true);
        assertFalse(farField.contains("confirmation card"));
        assertFalse(farField.contains("confirm on screen"));
        assertFalse(farField.contains("screen"));
        assertFalse(farField.contains("transcription"));
        assertTrue(farField.contains("run_overdrive_command"));
        assertTrue(farField.contains("propose_overdrive_action"));
        assertTrue(farField.contains("accepted = started, not finished"));
    }

    @Test
    public void pageInstructionsAreUnchanged() {
        assertEquals(PAGE_INSTRUCTIONS, GenAiAction.realtimeInstructions(false));
        assertEquals(PAGE_INSTRUCTIONS, GenAiAction.realtimeInstructions());
    }

    private static JSONObject sentinels() throws Exception {
        return new JSONObject()
                .put("on", "none")
                .put("open", "none")
                .put("seat", "none")
                .put("level", -1)
                .put("celsius", -1)
                .put("delta", 0)
                .put("goal", "none");
    }
}
