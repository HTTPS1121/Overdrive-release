package com.overdrive.app.voice;

/**
 * Stage-2 wake verification rules while media plays through the cabin speakers. The field log
 * (podcast on, ~60 stage-1 triggers in 20 minutes) showed the Moonshine fallback transcribing
 * the media and burning 1-7 s per rejected check; under media only the keyword verifier decides,
 * and a rejected check is followed by a short refractory so the verifier is not re-entered every
 * 2-3 s. Without media every rule returns today's value. Pure Java so it is unit-testable.
 */
final class MediaWakeGate {

    /** Silence after a rejected check while media is active; none without media, as before. */
    static final long REFRACTORY_AFTER_REJECT_MEDIA_MS = 2000;

    private MediaWakeGate() {
    }

    /** Moonshine may confirm a wake the keyword model missed only when the mic is not hearing media. */
    static boolean sttFallbackAllowed(boolean mediaActive) {
        return !mediaActive;
    }

    /** Over budget without media keeps today's cheaper STT-only check; under media the KWS check always runs. */
    static boolean sttOnly(boolean mediaActive, boolean overBudget) {
        return !mediaActive && overBudget;
    }

    /** Milliseconds the stage-1 model stays unfed after a rejected check; 0 keeps today's immediate re-arm. */
    static long refractoryAfterRejectMs(boolean mediaActive) {
        return mediaActive ? REFRACTORY_AFTER_REJECT_MEDIA_MS : 0;
    }

    /**
     * The stage-1 cutoff to apply on top of the model's own while media plays, or NaN for none.
     * Only while media genuinely plays (stage 2 then stays KWS-only with its refractory, the
     * false-accept guard); {@code underMedia} at the top of its range (0.45, the model's own
     * cutoff) switches it off; anything else is kept inside 0.20-0.45. The detector only ever
     * lowers its cutoff with it (WakeWordDetector.withMediaCutoff), never raises it.
     */
    static float stage1MediaCutoff(boolean mediaActive, double underMedia) {
        if (!mediaActive || Double.isNaN(underMedia)) return Float.NaN;
        if (underMedia >= VoiceAssistantConfig.WAKE_CUTOFF_UNDER_MEDIA_MAX - 1e-6) return Float.NaN;
        double clamped = Math.max(VoiceAssistantConfig.WAKE_CUTOFF_UNDER_MEDIA_MIN, underMedia);
        return (float) clamped;
    }
}
