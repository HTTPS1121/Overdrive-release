package com.overdrive.app.voice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Per-provider rules for giving up on a cloud turn: OpenAI's wait is anchored on its own
 * speech_stopped report, Gemini's on the local end of speech (or 8 s of continuous speech).
 */
public class CloudDeadlineTest {

    private static final long T0 = 100_000;
    private static final long OVER = CloudDeadline.RESPONSE_TIMEOUT_MS + 1;

    private static CloudDeadline openAi() {
        CloudDeadline d = new CloudDeadline();
        d.setProviderEndpoints(true);
        d.reset();
        return d;
    }

    private static CloudDeadline gemini() {
        CloudDeadline d = new CloudDeadline();
        d.setProviderEndpoints(false);
        d.reset();
        return d;
    }

    @Test
    public void openAiContinuousSpeechNeverStartsTheWait() {
        CloudDeadline d = openAi();
        for (long t = T0; t <= T0 + 20_000; t += 64) {
            d.speechContinues(t, T0);
            assertFalse("timed out at +" + (t - T0), d.timedOut(t));
        }
        // Even the local end of speech does not start it: the provider reports its own.
        d.localSpeechEnded(T0 + 20_000);
        assertFalse(d.awaiting());
        assertFalse(d.timedOut(T0 + 40_000));
    }

    @Test
    public void openAiSpeechStoppedStartsTheWait() {
        CloudDeadline d = openAi();
        d.speechContinues(T0 + 5000, T0);
        d.providerSpeechStopped(T0 + 5000);
        assertTrue(d.awaiting());
        assertFalse(d.timedOut(T0 + 5000 + CloudDeadline.RESPONSE_TIMEOUT_MS));
        assertTrue(d.timedOut(T0 + 5000 + OVER));
    }

    @Test
    public void openAiSpeechStoppedKeepsItsFirstAnchor() {
        CloudDeadline d = openAi();
        d.providerSpeechStopped(T0);
        d.providerSpeechStopped(T0 + 7000);
        assertTrue(d.timedOut(T0 + OVER));
    }

    @Test
    public void openAiRespondingClearsTheWait() {
        CloudDeadline d = openAi();
        d.providerSpeechStopped(T0);
        d.providerResponding(T0 + 300);
        assertTrue(d.responded());
        assertFalse(d.timedOut(T0 + OVER));
        assertFalse(d.timedOut(T0 + 60_000));
    }

    @Test
    public void openAiRespondingBeforeSpeechStoppedNeverTimesOut() {
        CloudDeadline d = openAi();
        d.providerResponding(T0);
        d.providerSpeechStopped(T0 + 100);
        assertFalse(d.timedOut(T0 + 100 + OVER));
    }

    @Test
    public void geminiLocalEndOfSpeechStartsTheWait() {
        CloudDeadline d = gemini();
        d.localSpeechEnded(T0);
        assertTrue(d.awaiting());
        assertFalse(d.timedOut(T0 + CloudDeadline.RESPONSE_TIMEOUT_MS));
        assertTrue(d.timedOut(T0 + OVER));
    }

    @Test
    public void geminiContinuousSpeechStartsTheWaitAfterEightSeconds() {
        CloudDeadline d = gemini();
        d.speechContinues(T0 + CloudDeadline.CONTINUOUS_SPEECH_MS, T0);
        assertFalse(d.awaiting());
        long start = T0 + CloudDeadline.CONTINUOUS_SPEECH_MS + 1;
        d.speechContinues(start, T0);
        assertTrue(d.awaiting());
        assertTrue(d.timedOut(start + OVER));
    }

    @Test
    public void geminiIgnoresProviderSpeechStopped() {
        CloudDeadline d = gemini();
        d.providerSpeechStopped(T0);
        assertFalse(d.awaiting());
        assertFalse(d.timedOut(T0 + OVER));
    }

    @Test
    public void geminiRespondingClearsTheWait() {
        CloudDeadline d = gemini();
        d.localSpeechEnded(T0);
        d.providerResponding(T0 + 1000);
        assertFalse(d.timedOut(T0 + OVER));
    }

    @Test
    public void replayWaitsFromNowForEitherProvider() {
        CloudDeadline d = openAi();
        d.awaitFrom(T0);
        assertTrue(d.timedOut(T0 + OVER));
        d = gemini();
        d.awaitFrom(T0);
        assertTrue(d.timedOut(T0 + OVER));
    }

    @Test
    public void aWaitThatBeganBeforeLiveRestartsAtLive() {
        // Gemini: the user stopped talking while the session was still being set up.
        CloudDeadline d = gemini();
        d.localSpeechEnded(T0);
        d.providerLive(T0 + 5000);
        assertFalse(d.timedOut(T0 + OVER));
        assertFalse(d.timedOut(T0 + 5000 + CloudDeadline.RESPONSE_TIMEOUT_MS));
        assertTrue(d.timedOut(T0 + 5000 + OVER));
        // A replayed utterance (either provider) waits from live too.
        d = openAi();
        d.awaitFrom(T0);
        d.providerLive(T0 + 3000);
        assertFalse(d.timedOut(T0 + OVER));
        assertTrue(d.timedOut(T0 + 3000 + OVER));
    }

    @Test
    public void goingLiveNeverStartsNorExtendsAnAnsweredOrIdleWait() {
        CloudDeadline d = gemini();
        d.providerLive(T0);
        assertFalse(d.awaiting());
        d.localSpeechEnded(T0 + 100);
        d.providerResponding(T0 + 200);
        d.providerLive(T0 + 300);
        assertFalse(d.timedOut(T0 + 300 + OVER));
        // A session that never goes live still times out from the original anchor.
        d = gemini();
        d.localSpeechEnded(T0);
        assertTrue(d.timedOut(T0 + OVER));
    }

    @Test
    public void aFollowUpThatTheProviderNeverAnswersStillTimesOut() {
        // The field mechanism behind the 2026-10-11 cloud_no_response turns: the answer was
        // given, the follow-up window re-armed the wait (reset) on local speech, Gemini never
        // answered it, and the wait ran out. The engine now ends such an answered turn as "cloud".
        CloudDeadline d = gemini();
        d.awaitFrom(T0);
        d.providerResponding(T0 + 1500);
        assertFalse(d.timedOut(T0 + 20_000));
        d.reset();
        d.localSpeechEnded(T0 + 21_000);
        assertTrue(d.timedOut(T0 + 21_000 + OVER));
    }

    @Test
    public void resetClearsTheWaitButKeepsTheProvider() {
        CloudDeadline d = openAi();
        d.providerSpeechStopped(T0);
        d.providerResponding(T0 + 10);
        d.reset();
        assertFalse(d.awaiting());
        assertFalse(d.responded());
        assertTrue(d.providerEndpoints());
        // A follow-up turn on OpenAI still waits only for the provider's report.
        d.localSpeechEnded(T0 + 20_000);
        assertFalse(d.awaiting());
    }
}
