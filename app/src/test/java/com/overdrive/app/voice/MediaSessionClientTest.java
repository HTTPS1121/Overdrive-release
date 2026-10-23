package com.overdrive.app.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The engine's reading of the daemon's media-session replies: an unreadable or failed reply is
 * "unknown" (so today's isMusicActive() decides), a pause counts only when the daemon says it
 * paused, the resume request names what was paused and how, and the turn log gets a short token.
 */
public class MediaSessionClientTest {

    @Test
    public void stateRepliesAreTrustedOnlyFromAVisibleSource() throws Exception {
        MediaSessionClient.State playing = MediaSessionClient.parseState(new JSONObject()
                .put("success", true).put("playing", true).put("package", "com.byd.music")
                .put("source", "session"));
        assertTrue(playing.known());
        assertTrue(playing.playing);
        assertEquals("com.byd.music", playing.pkg);
        MediaSessionClient.State paused = MediaSessionClient.parseState(new JSONObject()
                .put("success", true).put("playing", false).put("source", "dumpsys"));
        assertTrue(paused.known());
        assertFalse(paused.playing);
        for (JSONObject unknown : new JSONObject[] {
                null,
                new JSONObject().put("success", false).put("source", "session"),
                new JSONObject().put("success", true).put("playing", true).put("source", "unknown"),
                new JSONObject().put("success", true).put("playing", true).put("source", "guess"),
                new JSONObject().put("success", true).put("playing", true)}) {
            MediaSessionClient.State s = MediaSessionClient.parseState(unknown);
            assertFalse(String.valueOf(unknown), s.known());
            assertFalse(String.valueOf(unknown), s.playing);
        }
    }

    @Test
    public void aPauseCountsOnlyWhenTheDaemonPausedSomething() throws Exception {
        MediaSessionClient.Paused paused = MediaSessionClient.parsePause(new JSONObject()
                .put("success", true).put("paused", true).put("package", "com.byd.music")
                .put("previousState", 3).put("method", "session").put("ms", 12));
        assertTrue(paused.paused);
        assertEquals("com.byd.music", paused.pkg);
        assertEquals(3, paused.previousState);
        assertEquals("session", paused.method);
        assertEquals(12, paused.ms);
        assertFalse(MediaSessionClient.parsePause(new JSONObject()
                .put("success", true).put("paused", false).put("method", "none")).paused);
        assertFalse(MediaSessionClient.parsePause(new JSONObject().put("success", false).put("paused", true)).paused);
        assertFalse(MediaSessionClient.parsePause(null).paused);
    }

    @Test
    public void theRequestsUseTheMediaSessionTargetAndTheResumeNamesWhatWasPaused() throws Exception {
        JSONObject state = MediaSessionClient.stateRequest(false);
        assertEquals(2, state.length());
        assertEquals("media_session", state.getString("target"));
        assertEquals("state", state.getString("action"));
        JSONObject fresh = MediaSessionClient.stateRequest(true);
        assertTrue(fresh.getBoolean("fresh"));
        assertEquals("state", fresh.getString("action"));
        JSONObject resume = MediaSessionClient.resumeRequest("com.byd.music", "dispatch");
        assertEquals("media_session", resume.getString("target"));
        assertEquals("resume", resume.getString("action"));
        assertEquals("com.byd.music", resume.getString("package"));
        assertTrue(resume.getBoolean("paused"));
        assertEquals("dispatch", resume.getString("method"));
        JSONObject blind = MediaSessionClient.resumeRequest(null, null);
        assertEquals("", blind.getString("package"));
        assertEquals("", blind.getString("method"));
    }

    @Test
    public void packageTokensAreShortAndKeepTheDistinctiveTail() {
        assertEquals("com.byd.music", MediaSessionClient.shortPackage("com.byd.music"));
        String yt = MediaSessionClient.shortPackage("com.google.android.apps.youtube.music");
        assertEquals("android.apps.youtube.music", yt);
        assertTrue(yt.length() <= MediaSessionClient.MAX_PACKAGE_TOKEN);
        assertEquals("comevil", MediaSessionClient.shortPackage("com;evil"));
        assertNull(MediaSessionClient.shortPackage(null));
        assertNull(MediaSessionClient.shortPackage(""));
        assertNull(MediaSessionClient.shortPackage("..."));
        String longest = MediaSessionClient.shortPackage("x".repeat(80));
        assertEquals(MediaSessionClient.MAX_PACKAGE_TOKEN, longest.length());
    }
}
