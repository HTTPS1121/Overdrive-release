package com.overdrive.app.voice.wake;

/**
 * Wake decision over a streaming model's uint8 outputs, clocked by frontend slices: sliding-window
 * mean against the cutoff, cool-off and refractory after a detection, and a second chance after a
 * near miss. In candidate mode a crossing opens a window that is reported once its mean stops
 * rising, with that peak, and nothing is cleared: a verifier decides. Not thread-safe.
 */
final class WakeDecider {

    /** Slices whose latest output must sit below the cutoff before a detection (reference firmware). */
    static final int MIN_SLICES_BEFORE_DETECTION = 100;
    static final float SECOND_CHANCE_BAND = 0.1f;
    static final float SECOND_CHANCE_DROP = 0.05f;
    /** Candidate mode: slices without a new peak that close the window, and its longest span. */
    static final int CANDIDATE_SETTLE_SLICES = 25;
    static final int CANDIDATE_MAX_SLICES = 150;
    static final float CANDIDATE_RISE = 0.10f;

    private final Window window;
    private final int refractorySlices;
    private final int secondChanceSlices;

    private float cutoff;
    private int cutoffU8;
    private int loweredU8;

    private int ignoreSlices;
    private int refractoryLeft;
    private int secondChanceLeft;
    private boolean inBand;
    private boolean bandTainted;
    private float smoothed;
    private float peak;
    private float recentPeak;

    private boolean candidates;
    private boolean inCandidate;
    private float candidatePeak;
    private int sinceCandidatePeak;
    private int candidateAge;
    // After a candidate the next one opens only once the mean falls below the cutoff or clearly outgrows it.
    private boolean rearmBelow;
    private float lastCandidatePeak;

    WakeDecider(int windowSize, int refractorySlices, int secondChanceSlices, float cutoff) {
        window = new Window(windowSize);
        this.refractorySlices = refractorySlices;
        this.secondChanceSlices = secondChanceSlices;
        this.cutoff = Float.NaN;
        setCutoff(cutoff);
        reset();
    }

    /** Cutoffs compare in the uint8 domain, truncated like the reference firmware's int(cutoff * 255). */
    void setCutoff(float c) {
        if (c == cutoff) return;
        cutoff = c;
        cutoffU8 = (int) (c * 255.0);
        loweredU8 = (int) ((c - SECOND_CHANCE_DROP) * 255.0);
    }

    /** Takes effect on the next slice; leaving it drops an open candidate. */
    void setCandidateMode(boolean on) {
        if (on == candidates) return;
        candidates = on;
        inCandidate = false;
        rearmBelow = false;
    }

    boolean candidateMode() {
        return candidates;
    }

    /** The peak mean of the candidate reported last (candidate mode). */
    float lastCandidatePeak() {
        return lastCandidatePeak;
    }

    /**
     * Advances one slice; {@code fresh} marks a new model output {@code u8}. Returns true when the
     * slice completes a detection that {@code vadOpen} allows, after which the window is cleared;
     * in candidate mode, when an open candidate closes (the window is kept).
     */
    boolean onSlice(boolean fresh, int u8, boolean vadOpen) {
        if (refractoryLeft > 0) refractoryLeft--;
        if (secondChanceLeft > 0) secondChanceLeft--;
        if (fresh) {
            window.add(u8);
            smoothed = window.mean();
            if (smoothed > recentPeak) recentPeak = smoothed;
        }
        if (ignoreSlices < 0 && window.latest() < cutoffU8) ignoreSlices++;
        if (inCandidate) return candidateSlice(fresh);
        if (!fresh) return false;

        // A clearly stronger rise is a new event (a weak bump just before the wake word).
        if (rearmBelow && (!window.above(cutoffU8) || smoothed > lastCandidatePeak + CANDIDATE_RISE)) {
            rearmBelow = false;
        }
        boolean blocked = ignoreSlices < 0 || refractoryLeft > 0 || rearmBelow;
        boolean above = window.above(secondChanceLeft > 0 ? loweredU8 : cutoffU8);
        if (!blocked) {
            if (smoothed > peak) peak = smoothed;
            if (above && vadOpen) {
                if (candidates) {
                    inCandidate = true;
                    candidatePeak = smoothed;
                    sinceCandidatePeak = 0;
                    candidateAge = 0;
                    return false;
                }
                clearAfterDetection();
                return true;
            }
        }
        if (smoothed >= cutoff - SECOND_CHANCE_BAND) {
            if (!inBand) {
                inBand = true;
                bandTainted = false;
            }
            if (blocked || above) bandTainted = true;
        } else if (inBand) {
            if (!bandTainted) secondChanceLeft = secondChanceSlices;
            inBand = false;
        }
        return false;
    }

    /** An open candidate: closes once the mean has not risen for the settle span, or at its longest. */
    private boolean candidateSlice(boolean fresh) {
        candidateAge++;
        if (fresh && smoothed > candidatePeak) {
            candidatePeak = smoothed;
            sinceCandidatePeak = 0;
        } else {
            sinceCandidatePeak++;
        }
        if (sinceCandidatePeak < CANDIDATE_SETTLE_SLICES && candidateAge < CANDIDATE_MAX_SLICES) return false;
        inCandidate = false;
        rearmBelow = true;
        lastCandidatePeak = candidatePeak;
        secondChanceLeft = 0;
        inBand = false;
        bandTainted = false;
        return true;
    }

    float smoothed() {
        return smoothed;
    }

    float peak() {
        return peak;
    }

    /** Highest sliding-window mean since the previous call, before any clear (diagnostics only). */
    float takeRecentPeak() {
        float p = recentPeak;
        recentPeak = 0f;
        return p;
    }

    void reset() {
        clearAfterDetection();
        refractoryLeft = 0;
        peak = 0f;
    }

    private void clearAfterDetection() {
        window.clear();
        smoothed = 0f;
        ignoreSlices = -MIN_SLICES_BEFORE_DETECTION;
        refractoryLeft = refractorySlices;
        secondChanceLeft = 0;
        inBand = false;
        bandTainted = false;
        inCandidate = false;
        rearmBelow = false;
    }

    /** Ring of the last N uint8 outputs with a running sum. */
    static final class Window {
        private final int[] values;
        private int index;
        private int sum;

        Window(int size) {
            if (size < 1) throw new IllegalArgumentException("window size " + size);
            values = new int[size];
        }

        void add(int v) {
            if (++index == values.length) index = 0;
            sum += v - values[index];
            values[index] = v;
        }

        int latest() {
            return values[index];
        }

        float mean() {
            return sum / (255f * values.length);
        }

        /** Mean above the cutoff, compared as the reference firmware does. */
        boolean above(int cutoffU8) {
            return sum > cutoffU8 * values.length;
        }

        void clear() {
            java.util.Arrays.fill(values, 0);
            sum = 0;
        }
    }
}
