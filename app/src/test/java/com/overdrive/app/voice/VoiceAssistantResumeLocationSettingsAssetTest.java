package com.overdrive.app.voice;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Pins the two settings added with the media-session work on the Assistant page: "Resume media
 * afterwards" (next to "Pause media while listening") and "Share the car's approximate location"
 * (next to the live-status toggle). Both default on when the field is absent, the script binds
 * them to the right config fields, and every web catalog carries all four strings.
 */
public class VoiceAssistantResumeLocationSettingsAssetTest {

    private static final String HTML = "src/main/assets/web/local/assistant.html";
    private static final String SCRIPT = "src/main/assets/web/shared/voice-assistant.js";
    private static final String I18N = "src/main/assets/web/i18n/";
    private static final String[] CATALOGS = {
            "ar.json", "cs.json", "de.json", "en.json", "es.json", "fr.json", "he.json",
            "hi.json", "it.json", "ja.json", "ko.json", "nb.json", "nl.json", "pt-BR.json",
            "ru.json", "th.json", "tr.json", "vi.json", "zh-CN.json", "zh-TW.json",
    };
    private static final String[] KEYS = {
            "resume_media_title", "resume_media_desc", "share_location_title", "share_location_desc",
    };

    @Test
    public void theRowsSitNextToTheirSiblings() throws Exception {
        String html = readProjectFile(HTML);
        assertTrue(html.contains("id=\"voiceResumeMediaAfterListening\""));
        assertTrue(html.contains("id=\"voiceShareLocationWithAi\""));
        for (String key : KEYS) assertTrue(key, html.contains("data-i18n=\"voice_assistant." + key + "\""));
        int pause = html.indexOf("id=\"voicePauseMediaWhileListening\"");
        int resume = html.indexOf("id=\"voiceResumeMediaAfterListening\"");
        int direct = html.indexOf("id=\"voiceRealtimeDirectFallback\"");
        assertTrue(pause >= 0 && resume > pause && direct > resume);
        int share = html.indexOf("id=\"voiceShareVehicleState\"");
        int location = html.indexOf("id=\"voiceShareLocationWithAi\"");
        int sensitivity = html.indexOf("id=\"voiceSensitivity\"");
        assertTrue(share >= 0 && location > share && sensitivity > location);
        // The hint says what leaves the car and to whom.
        assertTrue(html.contains("approximate location"));
        assertTrue(html.contains("is sent to your AI provider"));
    }

    @Test
    public void theScriptBindsAndRendersBothTogglesDefaultOn() throws Exception {
        String js = readProjectFile(SCRIPT);
        assertTrue(js.contains("$('voiceResumeMediaAfterListening')"));
        assertTrue(js.contains("resumeMediaAfterListening: !!this.checked"));
        assertTrue(js.contains("config.resumeMediaAfterListening !== false"));
        assertTrue(js.contains("config.pauseMediaWhileListening === false"));
        assertTrue(js.contains("$('voiceShareLocationWithAi')"));
        assertTrue(js.contains("shareLocationWithAi: !!this.checked"));
        assertTrue(js.contains("config.shareLocationWithAi !== false"));
        for (String key : KEYS) assertTrue(key, js.contains(key + ":"));
    }

    @Test
    public void everyCatalogCarriesAllFourStrings() throws Exception {
        assertTrue(CATALOGS.length == 20);
        for (String name : CATALOGS) {
            JSONObject voice = new JSONObject(readProjectFile(I18N + name)).getJSONObject("voice_assistant");
            for (String key : KEYS) {
                assertTrue(name + " " + key, voice.optString(key, "").trim().length() > 0);
            }
            // The location hint names the ~1 km rounding in every language.
            assertTrue(name, voice.getString("share_location_desc").contains("1"));
        }
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
