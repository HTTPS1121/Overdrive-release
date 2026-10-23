package com.overdrive.app.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Stage-2 wake verification under media: the Moonshine fallback is skipped (it transcribes the
 * podcast), the KWS check always runs even over budget, and a rejected check is followed by a
 * 2 s refractory. Without media every rule is today's behaviour.
 */
public class MediaWakeGateTest {

    @Test
    public void withoutMediaNothingChanges() {
        assertTrue(MediaWakeGate.sttFallbackAllowed(false));
        assertFalse(MediaWakeGate.sttOnly(false, false));
        assertTrue(MediaWakeGate.sttOnly(false, true));
        assertEquals(0, MediaWakeGate.refractoryAfterRejectMs(false));
    }

    @Test
    public void underMediaOnlyTheKeywordVerifierDecides() {
        assertFalse(MediaWakeGate.sttFallbackAllowed(true));
        // Over budget the STT-only shortcut would leave no check at all under media.
        assertFalse(MediaWakeGate.sttOnly(true, true));
        assertFalse(MediaWakeGate.sttOnly(true, false));
    }

    @Test
    public void underMediaARejectedCheckIsFollowedByATwoSecondRefractory() {
        assertEquals(2000, MediaWakeGate.REFRACTORY_AFTER_REJECT_MEDIA_MS);
        assertEquals(2000, MediaWakeGate.refractoryAfterRejectMs(true));
    }

    @Test
    public void theLowerStage1CutoffAppliesOnlyWhileMediaPlays() {
        // Without media: no media cutoff at all, whatever the setting.
        for (double setting : new double[] {0.20, 0.35, 0.45}) {
            assertTrue(Float.isNaN(MediaWakeGate.stage1MediaCutoff(false, setting)));
        }
        assertEquals(0.35f, MediaWakeGate.stage1MediaCutoff(true, 0.35), 1e-6f);
        assertEquals(0.20f, MediaWakeGate.stage1MediaCutoff(true, 0.20), 1e-6f);
        assertEquals(0.20f, MediaWakeGate.stage1MediaCutoff(true, 0.05), 1e-6f);
        // 0.45, the model's own cutoff, means off.
        assertTrue(Float.isNaN(MediaWakeGate.stage1MediaCutoff(true, 0.45)));
        assertTrue(Float.isNaN(MediaWakeGate.stage1MediaCutoff(true, 0.6)));
        assertTrue(Float.isNaN(MediaWakeGate.stage1MediaCutoff(true, Double.NaN)));
    }
}
