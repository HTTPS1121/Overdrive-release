package com.overdrive.app.voice;

import static org.junit.Assert.assertEquals;

import com.overdrive.app.voice.CommandWindowGate.Decision;
import org.junit.Test;

/** The clean-hit gate: instant for bare phrases, held when the sentence went on around them. */
public class CommandWindowGateTest {

    @Test
    public void englishCleanHitDispatchesNow() {
        assertEquals(Decision.DISPATCH_NOW, CommandWindowGate.decide("en", 0, false, false, true));
        assertEquals(Decision.DISPATCH_NOW, CommandWindowGate.decide("en",
                CommandWindowGate.LEADING_SPEECH_FOR_HOLD_MS, false, false, true));
    }

    @Test
    public void englishLeadingSpeechHolds() {
        assertEquals(Decision.HOLD, CommandWindowGate.decide("en", 400, false, false, true));
    }

    @Test
    public void englishTrailingLoudHolds() {
        assertEquals(Decision.HOLD, CommandWindowGate.decide("en", 0, true, false, true));
    }

    @Test
    public void chineseShortPrefaceDispatchesNow() {
        assertEquals(Decision.DISPATCH_NOW, CommandWindowGate.decide("zh-CN", 400, false, false, false));
        // No speech-to-text to validate with, so trailing speech cannot hold a Chinese hit.
        assertEquals(Decision.DISPATCH_NOW, CommandWindowGate.decide("zh-TW", 400, true, false, false));
    }

    @Test
    public void chineseLongPrefaceHolds() {
        assertEquals(Decision.HOLD, CommandWindowGate.decide("zh-CN", 700, false, false, false));
        // Chinese never has a transcript, so the flag cannot make it stricter.
        assertEquals(Decision.DISPATCH_NOW, CommandWindowGate.decide("zh-CN", 400, true, false, true));
    }

    @Test
    public void englishWithoutValidationUsesTheNoTranscriptRule() {
        // A non-English UI on automatic, or Moonshine still loading: a held hit could never be
        // confirmed, so "please turn on the AC" and a loud hit chunk must still run at once.
        assertEquals(Decision.DISPATCH_NOW, CommandWindowGate.decide("en", 400, false, false, false));
        assertEquals(Decision.DISPATCH_NOW, CommandWindowGate.decide("en", 0, true, false, false));
        assertEquals(Decision.DISPATCH_NOW, CommandWindowGate.decide(null, 400, true, false, false));
        // Only a long preface, the likely shape of a negation, is still held.
        assertEquals(Decision.HOLD, CommandWindowGate.decide("en", 700, false, false, false));
    }

    @Test
    public void heldAlreadyHoldsLaterHits() {
        assertEquals(Decision.HOLD, CommandWindowGate.decide("en", 0, false, true, true));
        assertEquals(Decision.HOLD, CommandWindowGate.decide("en", 0, false, true, false));
        assertEquals(Decision.HOLD, CommandWindowGate.decide("zh-CN", 0, false, true, false));
    }

    @Test
    public void unknownLanguageUsesEnglishRules() {
        assertEquals(Decision.HOLD, CommandWindowGate.decide(null, 400, false, false, true));
        assertEquals(Decision.DISPATCH_NOW, CommandWindowGate.decide(null, 100, false, false, true));
    }
}
