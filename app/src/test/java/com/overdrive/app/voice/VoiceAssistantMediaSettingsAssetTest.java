package com.overdrive.app.voice;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Pins the two media-playback settings on the Assistant page: the "Pause media while listening"
 * toggle and the experimental "Microphone input" select exist, the script binds and renders both
 * against the right config fields, the HTML option values match the config vocabulary, and every
 * web catalog carries all seven strings.
 */
public class VoiceAssistantMediaSettingsAssetTest {

    private static final String HTML = "src/main/assets/web/local/assistant.html";
    private static final String SCRIPT = "src/main/assets/web/shared/voice-assistant.js";
    private static final String I18N = "src/main/assets/web/i18n/";
    private static final String[] CATALOGS = {
            "ar.json", "cs.json", "de.json", "en.json", "es.json", "fr.json", "he.json",
            "hi.json", "it.json", "ja.json", "ko.json", "nb.json", "nl.json", "pt-BR.json",
            "ru.json", "th.json", "tr.json", "vi.json", "zh-CN.json", "zh-TW.json",
    };
    private static final String[] KEYS = {
            "pause_media_title", "pause_media_desc", "mic_source", "mic_source_mic",
            "mic_source_voice_recognition", "mic_source_voice_communication", "mic_source_hint",
    };

    @Test
    public void settingsPageHasTheToggleAndTheSelectWithTheConfigVocabulary() throws Exception {
        String html = readProjectFile(HTML);
        assertTrue(html.contains("id=\"voicePauseMediaWhileListening\""));
        assertTrue(html.contains("voice_assistant.pause_media_title"));
        assertTrue(html.contains("voice_assistant.pause_media_desc"));
        assertTrue(html.contains("id=\"voiceMicSource\""));
        assertTrue(html.contains("voice_assistant.mic_source\""));
        assertTrue(html.contains("voice_assistant.mic_source_hint"));
        for (String source : new String[] {
                VoiceAssistantConfig.MIC_SOURCE_MIC, VoiceAssistantConfig.MIC_SOURCE_VOICE_RECOGNITION,
                VoiceAssistantConfig.MIC_SOURCE_VOICE_COMMUNICATION}) {
            assertTrue(source, html.contains("<option value=\"" + source + "\" data-i18n=\"voice_assistant.mic_source_" + source + "\""));
        }
        // The select lives in the form grid next to the reply channel; the toggle is its own row.
        assertTrue(html.indexOf("id=\"voiceAudioChannel\"") < html.indexOf("id=\"voiceMicSource\""));
        assertTrue(html.indexOf("id=\"voiceMicSource\"") < html.indexOf("id=\"voicePauseMediaWhileListening\""));
    }

    @Test
    public void scriptBindsAndRendersBothSettings() throws Exception {
        String js = readProjectFile(SCRIPT);
        assertTrue(js.contains("$('voicePauseMediaWhileListening')"));
        assertTrue(js.contains("pauseMediaWhileListening: !!this.checked"));
        // Default on: an absent field renders checked, like the config default.
        assertTrue(js.contains("config.pauseMediaWhileListening !== false"));
        assertTrue(js.contains("this.bindSelect('voiceMicSource', function (value) {"));
        assertTrue(js.contains("return { micSource: value };"));
        assertTrue(js.contains("this.setValue('voiceMicSource', config ? (config.micSource || 'mic') : 'mic', locked);"));
        for (String key : KEYS) assertTrue(key, js.contains(key + ":"));
    }

    @Test
    public void everyCatalogCarriesAllSevenStrings() throws Exception {
        assertTrue(CATALOGS.length == 20);
        for (String name : CATALOGS) {
            JSONObject root = new JSONObject(readProjectFile(I18N + name));
            JSONObject voice = root.getJSONObject("voice_assistant");
            for (String key : KEYS) {
                assertTrue(name + " " + key, voice.optString(key, "").trim().length() > 0);
            }
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
