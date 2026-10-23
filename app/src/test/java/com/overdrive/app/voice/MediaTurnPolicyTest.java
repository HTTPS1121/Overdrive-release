package com.overdrive.app.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The duck-vs-pause-vs-nothing decision a command turn makes about media on the cabin speakers,
 * where "media is playing" comes from (the daemon's media sessions, isMusicActive() only when the
 * daemon cannot see them), the rule that only media the engine paused is ever resumed, and when a
 * pause counts as confirmed.
 */
public class MediaTurnPolicyTest {

    @Test
    public void activeMediaIsPausedWhenTheUserAllowsIt() {
        assertEquals(MediaTurnPolicy.Focus.PAUSE, MediaTurnPolicy.forTurn(true, true, true));
    }

    @Test
    public void activeMediaIsOnlyDuckedWhenTheToggleIsOff() {
        assertEquals(MediaTurnPolicy.Focus.DUCK, MediaTurnPolicy.forTurn(true, true, false));
    }

    @Test
    public void silenceKeepsTodaysDuckRegardlessOfTheToggle() {
        assertEquals(MediaTurnPolicy.Focus.DUCK, MediaTurnPolicy.forTurn(true, false, true));
        assertEquals(MediaTurnPolicy.Focus.DUCK, MediaTurnPolicy.forTurn(true, false, false));
    }

    @Test
    public void withoutAnAudioManagerNothingIsRequestedOrPaused() {
        assertEquals(MediaTurnPolicy.Focus.NONE, MediaTurnPolicy.forTurn(false, true, true));
        assertEquals(MediaTurnPolicy.Focus.NONE, MediaTurnPolicy.forTurn(false, false, true));
    }

    @Test
    public void theDaemonsSessionStateDecidesAndIsMusicActiveOnlyWhenItCannotSee() {
        // Field log 2026-10-11: isMusicActive() stayed true while the player was paused.
        assertFalse(MediaTurnPolicy.mediaPlaying("session", false, true));
        assertFalse(MediaTurnPolicy.mediaPlaying("dumpsys", false, true));
        assertTrue(MediaTurnPolicy.mediaPlaying("session", true, false));
        assertTrue(MediaTurnPolicy.mediaPlaying("dumpsys", true, false));
        // The daemon cannot see the sessions: today's isMusicActive() decides.
        assertTrue(MediaTurnPolicy.mediaPlaying("unknown", false, true));
        assertFalse(MediaTurnPolicy.mediaPlaying("unknown", true, false));
        assertTrue(MediaTurnPolicy.mediaPlaying(null, false, true));
    }

    @Test
    public void onlyMediaTheEnginePausedIsResumedAndOnlyWhenTheUserWantsItBack() {
        assertTrue(MediaTurnPolicy.shouldResume(true, true));
        assertFalse(MediaTurnPolicy.shouldResume(true, false));
        assertFalse(MediaTurnPolicy.shouldResume(false, true));
        assertFalse(MediaTurnPolicy.shouldResume(false, false));
    }

    @Test
    public void aPauseIsConfirmedOnlyByAVisibleSessionThatStoppedPlaying() {
        assertTrue(MediaTurnPolicy.pauseConfirmed("session", false));
        assertTrue(MediaTurnPolicy.pauseConfirmed("dumpsys", false));
        assertFalse(MediaTurnPolicy.pauseConfirmed("session", true));
        assertFalse(MediaTurnPolicy.pauseConfirmed("unknown", false));
        assertFalse(MediaTurnPolicy.pauseConfirmed(null, false));
    }

    @Test
    public void theConfirmationIsBoundedAndTheDiagnosticCodesAreDistinct() {
        assertEquals(400, MediaTurnPolicy.CONFIRM_POLL_MS);
        assertEquals(150, MediaTurnPolicy.SPEAKER_DECAY_MS);
        assertTrue(MediaTurnPolicy.CONFIRM_POLL_INTERVAL_MS < MediaTurnPolicy.CONFIRM_POLL_MS);
        assertTrue(MediaTurnPolicy.PAUSE_REPLY_WAIT_MS <= 1000);
        assertEquals(-1, MediaTurnPolicy.PAUSE_UNCONFIRMED);
        assertTrue(MediaTurnPolicy.PAUSE_NONE < MediaTurnPolicy.PAUSE_UNCONFIRMED);
        assertEquals("unknown", MediaTurnPolicy.SOURCE_UNKNOWN);
    }
}
