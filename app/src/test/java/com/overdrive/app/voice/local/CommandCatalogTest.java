package com.overdrive.app.voice.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pins the shape of the spoken-command catalog: it feeds both the keyword spotter (tags) and the
 * transcript matcher (phrases), so every language must be complete and free of prefix conflicts.
 */
public class CommandCatalogTest {

    @Test
    public void everyLanguageHasEntries() {
        for (String lang : CommandCatalog.languages()) {
            assertFalse(lang, CommandCatalog.entries(lang).isEmpty());
        }
        assertTrue(CommandCatalog.entries("xx").isEmpty());
    }

    @Test
    public void tagsAreUniqueAndRoundTripThroughFind() {
        for (String lang : CommandCatalog.languages()) {
            Set<String> tags = new HashSet<>();
            for (CommandCatalog.Entry e : CommandCatalog.entries(lang)) {
                assertTrue(lang + " duplicate tag " + e.tag, tags.add(e.tag));
                assertFalse(e.tag, e.tag.matches(".*\\s.*"));
                assertSame(e.phrase, e, CommandCatalog.find(lang, e.tag));
            }
        }
        assertEquals(null, CommandCatalog.find(CommandCatalog.EN, "no_such_phrase"));
    }

    /** JVM twin of gen_kws_keywords.py: matches fire when a phrase ends, so no risky word prefixes. */
    @Test
    public void noEnglishPhraseIsAWordPrefixOfADifferentMeaning() {
        List<CommandCatalog.Entry> en = CommandCatalog.entries(CommandCatalog.EN);
        List<String[]> words = new ArrayList<>(en.size());
        for (CommandCatalog.Entry e : en) words.add(wordsOf(e.phrase));
        List<String> offenders = new ArrayList<>();
        for (int i = 0; i < en.size(); i++) {
            for (int j = 0; j < en.size(); j++) {
                if (i == j || en.get(i).sameMeaning(en.get(j))) continue;
                if (isPrefix(words.get(i), words.get(j))) {
                    offenders.add("\"" + en.get(i).phrase + "\" < \"" + en.get(j).phrase + "\"");
                }
            }
        }
        assertTrue(offenders.toString(), offenders.isEmpty());
    }

    @Test
    public void bareSeatHeatingDefaultsToDriverHigh() {
        CommandCatalog.Entry e = CommandCatalog.find(CommandCatalog.EN,
                CommandCatalog.tagOf("turn on seat heating"));
        assertNotNull(e);
        assertEquals("seat.heat", e.intent);
        assertEquals("seat", e.slots[0]);
        assertEquals("driver", e.slots[1]);
        assertEquals("level", e.slots[2]);
        assertEquals(2, e.slots[3]);
    }

    /** Rear seats have no controls here; the matcher refuses them instead of guessing a front seat. */
    @Test
    public void noEnglishPhraseNamesARearOrBackSeat() {
        for (CommandCatalog.Entry e : CommandCatalog.entries(CommandCatalog.EN)) {
            String p = e.phrase.toLowerCase(Locale.ROOT);
            assertFalse(e.phrase, p.contains("rear seat"));
            assertFalse(e.phrase, p.contains("back seat"));
        }
    }

    @Test
    public void cancelExistsInEveryLanguage() {
        for (String lang : CommandCatalog.languages()) {
            boolean found = false;
            for (CommandCatalog.Entry e : CommandCatalog.entries(lang)) {
                if (e.isCancel()) {
                    assertEquals(CommandCatalog.INTENT_CANCEL, e.intent);
                    assertEquals(0, e.slots.length);
                    found = true;
                }
            }
            assertTrue(lang, found);
        }
    }

    private static String[] wordsOf(String phrase) {
        return phrase.trim().toLowerCase(Locale.ROOT).split("\\s+");
    }

    private static boolean isPrefix(String[] shorter, String[] longer) {
        if (shorter.length >= longer.length) return false;
        for (int i = 0; i < shorter.length; i++) {
            if (!shorter[i].equals(longer[i])) return false;
        }
        return true;
    }
}
