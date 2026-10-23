package com.overdrive.app.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The explicit command language: automatic follows the UI locale into a KWS phrase set, an explicit
 * choice overrides it, and English speech-to-text only runs when English is explicit or the UI is English.
 */
public class VoiceAssistantConfigCommandLanguageTest {

    private static VoiceAssistantConfig withCommandLanguage(String value) throws Exception {
        JSONObject section = new JSONObject();
        if (value != null) section.put("commandLanguage", value);
        return VoiceAssistantConfig.from(section);
    }

    @Test
    public void automaticNonEnglishUiKeepsEnglishCommandsWithoutStt() throws Exception {
        VoiceAssistantConfig auto = withCommandLanguage(null);
        assertEquals("", auto.commandLanguage);
        assertEquals("en", auto.resolvedCommandLanguage("fr"));
        assertFalse(auto.commandSttAllowed("fr"));
        assertEquals("en", auto.resolvedCommandLanguage("de-DE"));
        assertFalse(auto.commandSttAllowed("de-DE"));
        assertEquals("en", auto.resolvedCommandLanguage(null));
        assertFalse(auto.commandSttAllowed(null));
    }

    @Test
    public void automaticEnglishUiAllowsStt() throws Exception {
        VoiceAssistantConfig auto = withCommandLanguage("");
        assertEquals("en", auto.resolvedCommandLanguage("en-US"));
        assertTrue(auto.commandSttAllowed("en-US"));
        assertTrue(auto.commandSttAllowed("en"));
    }

    @Test
    public void automaticChineseUiPicksTheMatchingPhraseSet() throws Exception {
        VoiceAssistantConfig auto = withCommandLanguage("");
        assertEquals("zh-TW", auto.resolvedCommandLanguage("zh-TW"));
        assertEquals("zh-TW", auto.resolvedCommandLanguage("zh-HK"));
        assertEquals("zh-TW", auto.resolvedCommandLanguage("zh-Hant-TW"));
        assertEquals("zh-CN", auto.resolvedCommandLanguage("zh-CN"));
        assertEquals("zh-CN", auto.resolvedCommandLanguage("zh"));
        assertFalse(auto.commandSttAllowed("zh-TW"));
        assertFalse(auto.commandSttAllowed("zh-CN"));
    }

    @Test
    public void explicitEnglishOverridesTheUiAndAllowsStt() throws Exception {
        VoiceAssistantConfig en = withCommandLanguage("en");
        assertEquals("en", en.resolvedCommandLanguage("fr"));
        assertTrue(en.commandSttAllowed("fr"));
        assertEquals("en", en.resolvedCommandLanguage("zh-CN"));
        assertTrue(en.commandSttAllowed("zh-CN"));
    }

    @Test
    public void explicitChineseOverridesAnEnglishUi() throws Exception {
        VoiceAssistantConfig zh = withCommandLanguage("zh-CN");
        assertEquals("zh-CN", zh.resolvedCommandLanguage("en"));
        assertFalse(zh.commandSttAllowed("en"));
        VoiceAssistantConfig tw = withCommandLanguage("zh-TW");
        assertEquals("zh-TW", tw.resolvedCommandLanguage("en-US"));
        assertFalse(tw.commandSttAllowed("en-US"));
    }

    @Test
    public void unknownStoredValueFallsBackToAutomatic() throws Exception {
        assertEquals("", withCommandLanguage("de").commandLanguage);
        assertEquals("", withCommandLanguage("EN").commandLanguage);
    }

    @Test
    public void validatedPatchAcceptsOnlyTheFourValues() throws Exception {
        for (String value : new String[] {"", "en", "zh-CN", "zh-TW"}) {
            JSONObject out = VoiceAssistantConfig.validatedPatch(
                    new JSONObject().put("commandLanguage", value));
            assertEquals(value, out.getString("commandLanguage"));
        }
        try {
            VoiceAssistantConfig.validatedPatch(new JSONObject().put("commandLanguage", "de"));
            fail("de is not a command language");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("commandLanguage"));
        }
    }

    @Test
    public void toJsonRoundTrips() throws Exception {
        VoiceAssistantConfig zh = withCommandLanguage("zh-TW");
        JSONObject json = zh.toJson();
        assertEquals("zh-TW", json.getString("commandLanguage"));
        assertEquals("zh-TW", VoiceAssistantConfig.from(json).commandLanguage);
        assertEquals("", withCommandLanguage(null).toJson().getString("commandLanguage"));
    }

    @Test
    public void sameRuntimeShapeDiffersOnCommandLanguage() throws Exception {
        VoiceAssistantConfig auto = withCommandLanguage("");
        VoiceAssistantConfig en = withCommandLanguage("en");
        assertTrue(auto.sameRuntimeShape(withCommandLanguage("")));
        assertFalse(auto.sameRuntimeShape(en));
    }
}
