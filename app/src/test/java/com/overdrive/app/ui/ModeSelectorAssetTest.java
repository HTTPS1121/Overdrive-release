package com.overdrive.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Pins the shared mode picker (voice assistant, recording, surveillance, SafeKeep) to CSS the
 * BYD head-unit WebView (Chrome 58) can render. The selected-dot pseudo-element used the
 * {@code inset} shorthand (Chromium 87+), which that engine ignores, so the dot collapsed to 0x0
 * and the voice mode picker showed no radio circle on the car.
 */
public class ModeSelectorAssetTest {

    private static final String STYLES = "app/src/main/assets/web/shared/styles.css";

    @Test
    public void modeRadioDotUsesExplicitEdgesNotInsetForChrome58() throws IOException {
        String css = stripComments(readRepositoryFile(STYLES));
        String dot = ruleFor(css, ".mode-radio::after");
        assertFalse("inset shorthand is Chromium 87+; the head unit is Chrome 58", dot.contains("inset:"));
        assertTrue(dot.contains("top: 4px"));
        assertTrue(dot.contains("right: 4px"));
        assertTrue(dot.contains("bottom: 4px"));
        assertTrue(dot.contains("left: 4px"));
        assertTrue(dot.contains("transform: scale(0)"));
    }

    @Test
    public void modeRadioRingIsVisibleOnDimmedCards() throws IOException {
        String css = stripComments(readRepositoryFile(STYLES));
        String ring = ruleFor(css, ".mode-radio {");
        assertTrue(ring.contains("border: 2px solid var(--text-muted)"));
        assertFalse("10%-alpha border is ~1.4:1 contrast and vanished on the head unit",
                ring.contains("var(--border-default)"));
    }

    @Test
    public void fixedBackdropsUseExplicitEdgesNotInset() throws IOException {
        String css = stripComments(readRepositoryFile(STYLES));
        assertFalse(ruleFor(css, ".modal-backdrop {").contains("inset:"));
        assertFalse(ruleFor(css, ".lang-sheet-backdrop {").contains("inset:"));
        assertTrue(ruleFor(css, ".lang-sheet-backdrop {").contains("top: 0"));
    }

    @Test
    public void modeSelectorGridKeepsChrome58GapFallback() throws IOException {
        String css = stripComments(readRepositoryFile(STYLES));
        String grid = ruleFor(css, ".mode-selector {");
        assertTrue(grid.contains("grid-gap: 12px"));
        assertTrue(grid.contains("gap: 12px"));
    }

    @Test
    public void voiceModePickerKeepsThreeNativeRadios() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/assistant.html");
        assertTrue(html.contains("id=\"voiceModeSelector\""));
        assertEquals(3, countOccurrences(html, "name=\"voiceMode\""));
        assertEquals(3, countOccurrences(html, "<span class=\"mode-radio\" aria-hidden=\"true\"></span>"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String stripComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static String ruleFor(String css, String selector) {
        int start = css.indexOf(selector);
        if (start < 0) return "";
        int end = css.indexOf('}', start);
        return end < 0 ? css.substring(start) : css.substring(start, end + 1);
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
