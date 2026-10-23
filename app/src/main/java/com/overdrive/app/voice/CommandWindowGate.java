package com.overdrive.app.voice;

/**
 * Decides whether a keyword-spotter hit is clean enough to run at once or must wait for the
 * command window to close so the whole utterance can be validated. A bare phrase right after the
 * wake word keeps today's instant path; a phrase inside a longer sentence ("I do not want you to
 * turn on the AC") is held, because only the full transcript can tell negation from a command.
 * Pure Java so the rules are unit-testable without Android.
 */
final class CommandWindowGate {

    enum Decision { DISPATCH_NOW, HOLD }

    /** English: speech this long before the phrase may carry a negation Moonshine can confirm. */
    static final long LEADING_SPEECH_FOR_HOLD_MS = 150;
    /**
     * Without speech-to-text to validate with (Chinese, a non-English UI on automatic, or
     * Moonshine still loading) a held hit can never be confirmed, so only a long preface, the
     * likely shape of a negation, is held; polite prefixes such as 请 / 帮我 / "please" stay instant.
     */
    static final long LEADING_SPEECH_FOR_HOLD_ZH_MS = 600;
    /** A loud RMS chunk this close after the phrase means the sentence went on. */
    static final long TRAILING_LOUD_FOR_HOLD_SAMPLES = 1024;

    private CommandWindowGate() {
    }

    /**
     * @param language            command language tag, e.g. "en" or "zh-CN"
     * @param leadingMs           speech heard before the phrase started
     * @param trailingLoud        a loud chunk arrived right after the phrase
     * @param heldAlready         something in this turn is already held; later hits join it to keep order
     * @param validationAvailable a transcript can confirm a held hit at window close; when it
     *                            cannot, holding on a short preface or trailing sound would only
     *                            turn a working command into "not understood"
     */
    static Decision decide(String language, long leadingMs, boolean trailingLoud, boolean heldAlready,
                           boolean validationAvailable) {
        if (heldAlready) return Decision.HOLD;
        boolean chinese = language != null
                && language.toLowerCase(java.util.Locale.ROOT).startsWith("zh");
        if (chinese || !validationAvailable) {
            return leadingMs > LEADING_SPEECH_FOR_HOLD_ZH_MS ? Decision.HOLD : Decision.DISPATCH_NOW;
        }
        if (leadingMs > LEADING_SPEECH_FOR_HOLD_MS || trailingLoud) return Decision.HOLD;
        return Decision.DISPATCH_NOW;
    }
}
