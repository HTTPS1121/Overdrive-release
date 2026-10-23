package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The daemon's media-session control: parsing {@code dumpsys media_session}, picking the session
 * that is actually playing, how a pause is delivered, and the rule that a resume only plays what
 * the caller paused while it is still paused. The route keeps every other media target as it was.
 */
public class MediaSessionControlTest {

    /** Android 10 layout, abridged: the media button session first, a paused BT session second. */
    private static final String ANDROID_10 = String.join("\n",
            "MEDIA SESSION SERVICE (dumpsys media_session)",
            "",
            "  Global priority session is null",
            "Record for full_user=0",
            "  Media key listener package: ",
            "  Media button session is com.byd.music/MusicService (userId=0)",
            "  Sessions Stack - have 2 sessions:",
            "    MusicService com.byd.music/MusicService (userId=0)",
            "      ownerPid=1234, ownerUid=10050, userId=0",
            "      package=com.byd.music",
            "      launchIntent=null",
            "      active=true",
            "      flags=3",
            "      rating type=0",
            "      controllers: 2",
            "      state=PlaybackState {state=3, position=81234, buffered position=0, speed=1.0, updated=55, actions=822, custom actions=[], active item id=-1, error=null}",
            "      audioAttrs=AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC flags=0x800 tags= bundle=null",
            "    BluetoothMediaBrowserService com.android.bluetooth/BluetoothMediaBrowserService (userId=0)",
            "      package=com.android.bluetooth",
            "      active=true",
            "      state=PlaybackState {state=2, position=0, buffered position=0, speed=0.0, updated=9, actions=0, custom actions=[], active item id=-1, error=null}",
            "  Audio playback (lastly played comes first)",
            "    uid=10050 packages=com.byd.music",
            "      state=this line is outside every session block");

    @Test
    public void dumpsysSessionsAreParsedInPriorityOrderWithStateAndActiveFlag() {
        List<MediaSessionControl.Session> sessions = MediaSessionControl.parseDumpsys(ANDROID_10);
        assertEquals(2, sessions.size());
        assertEquals("com.byd.music", sessions.get(0).pkg);
        assertEquals(MediaSessionControl.STATE_PLAYING, sessions.get(0).state);
        assertTrue(sessions.get(0).active);
        assertEquals("com.android.bluetooth", sessions.get(1).pkg);
        assertEquals(MediaSessionControl.STATE_PAUSED, sessions.get(1).state);
        // A shallower line closed the last block: the "Audio playback" section did not leak in.
        assertEquals("com.byd.music", MediaSessionControl.playing(sessions).pkg);
    }

    @Test
    public void namedStatesNullStatesAndBrokenBlocksAreHandled() {
        String dump = String.join("\n",
                "    A com.a/A (userId=0)",
                "      package=com.example.player",
                "      active=true",
                "      state=PlaybackState {state=PLAYING(3), position=0}",
                "    B com.b/B (userId=0)",
                "      package=com.example.paused",
                "      active=true",
                "      state=PlaybackState {state=PAUSED, position=0}",
                "    C x (userId=0)",
                "      package=not a package!",
                "      active=true",
                "      state=PlaybackState {state=3}",
                "    D com.d/D (userId=0)",
                "      package=com.example.idle",
                "      active=false",
                "      state=null");
        List<MediaSessionControl.Session> sessions = MediaSessionControl.parseDumpsys(dump);
        assertEquals(3, sessions.size());
        assertEquals(MediaSessionControl.STATE_PLAYING, sessions.get(0).state);
        assertEquals(MediaSessionControl.STATE_PAUSED, sessions.get(1).state);
        assertEquals("com.example.idle", sessions.get(2).pkg);
        assertFalse(sessions.get(2).active);
        assertEquals(MediaSessionControl.STATE_NONE, sessions.get(2).state);
        assertTrue(MediaSessionControl.parseDumpsys(null).isEmpty());
        assertTrue(MediaSessionControl.parseDumpsys("").isEmpty());
        assertEquals(MediaSessionControl.STATE_UNKNOWN, MediaSessionControl.parseStateValue("PlaybackState {position=1}"));
        assertEquals(MediaSessionControl.STATE_BUFFERING, MediaSessionControl.parseStateToken("STATE_BUFFERING"));
        assertEquals(MediaSessionControl.STATE_UNKNOWN, MediaSessionControl.parseStateToken("LOUD"));
        assertEquals(MediaSessionControl.STATE_UNKNOWN, MediaSessionControl.parseStateToken("1234567"));
    }

    @Test
    public void onlyAnActiveSessionOnItsWayToSoundCountsAsPlaying() {
        for (int s : new int[] {3, 4, 5, 6, 8, 9, 10, 11}) assertTrue(String.valueOf(s), MediaSessionControl.isPlayingState(s));
        for (int s : new int[] {-1, 0, 1, 2, 7, 12}) assertFalse(String.valueOf(s), MediaSessionControl.isPlayingState(s));
        MediaSessionControl.Session inactivePlaying = new MediaSessionControl.Session("com.a", 3, false);
        MediaSessionControl.Session paused = new MediaSessionControl.Session("com.b", 2, true);
        MediaSessionControl.Session playing = new MediaSessionControl.Session("com.c", 3, true);
        assertNull(MediaSessionControl.playing(Arrays.asList(inactivePlaying, paused)));
        assertEquals("com.c", MediaSessionControl.playing(Arrays.asList(inactivePlaying, paused, playing)).pkg);
        assertNull(MediaSessionControl.playing(null));
    }

    @Test
    public void aPauseGoesToThePlayingSessionAndNothingIsPausedWhenNothingPlays() {
        MediaSessionControl.Session playing = new MediaSessionControl.Session("com.c", 3, true);
        assertEquals("session", MediaSessionControl.pauseAction("session", playing));
        assertEquals("dispatch", MediaSessionControl.pauseAction("dumpsys", playing));
        assertEquals("none", MediaSessionControl.pauseAction("session", null));
        assertEquals("none", MediaSessionControl.pauseAction("dumpsys", null));
        // Nothing can be seen at all: today's input-layer key, blind.
        assertEquals("key", MediaSessionControl.pauseAction("unknown", null));
    }

    @Test
    public void aResumePlaysOnlyWhatTheCallerPausedWhileItIsStillPaused() {
        MediaSessionControl.Session paused = new MediaSessionControl.Session("com.c", 2, true);
        MediaSessionControl.Session stopped = new MediaSessionControl.Session("com.c", 1, true);
        MediaSessionControl.Session playing = new MediaSessionControl.Session("com.c", 3, true);
        assertEquals("session", MediaSessionControl.resumeAction(true, "session", paused, "session"));
        assertEquals("dispatch", MediaSessionControl.resumeAction(true, "dumpsys", paused, "dispatch"));
        // The pause needed the input key: the PLAY goes the same way.
        assertEquals("key", MediaSessionControl.resumeAction(true, "dumpsys", paused, "key"));
        // The driver stopped it, or it already plays, or it is gone: nothing is played.
        assertEquals("not_paused", MediaSessionControl.resumeAction(true, "session", stopped, "session"));
        assertEquals("not_paused", MediaSessionControl.resumeAction(true, "session", playing, "session"));
        assertEquals("no_session", MediaSessionControl.resumeAction(true, "session", null, "session"));
        assertEquals("no_session", MediaSessionControl.resumeAction(true, "dumpsys", null, "dispatch"));
        // Never without the caller's own pause.
        assertEquals("not_paused_by_caller", MediaSessionControl.resumeAction(false, "session", paused, "session"));
        assertEquals("not_paused_by_caller", MediaSessionControl.resumeAction(false, "unknown", null, "key"));
        // Nothing can be seen: the PLAY mirrors the blind pause.
        assertEquals("key", MediaSessionControl.resumeAction(true, "unknown", null, "key"));
        assertEquals("dispatch", MediaSessionControl.resumeAction(true, "unknown", null, "session"));
    }

    @Test
    public void theResumeTargetIsTheNamedPackageOrWhereMediaKeysGo() {
        List<MediaSessionControl.Session> sessions = Arrays.asList(
                new MediaSessionControl.Session("com.a", 2, false),
                new MediaSessionControl.Session("com.b", 2, true),
                new MediaSessionControl.Session("com.c", 3, true));
        assertEquals("com.c", MediaSessionControl.target(sessions, "com.c").pkg);
        assertEquals("com.a", MediaSessionControl.target(sessions, "com.a").pkg);
        assertNull(MediaSessionControl.target(sessions, "com.z"));
        assertEquals("com.b", MediaSessionControl.target(sessions, "").pkg);
        assertNull(MediaSessionControl.target(Collections.<MediaSessionControl.Session>emptyList(), ""));
        // An app with two sessions: the resume is about its paused one.
        MediaSessionControl.Session other = new MediaSessionControl.Session("com.d", 3, true);
        MediaSessionControl.Session ours = new MediaSessionControl.Session("com.d", 2, true);
        assertEquals(ours, MediaSessionControl.target(Arrays.asList(other, ours), "com.d"));
    }

    @Test
    public void aDispatchedPauseIsVerifiedOnEverySessionOfThatApp() {
        List<MediaSessionControl.Session> sessions = Arrays.asList(
                new MediaSessionControl.Session("com.d", 2, true),
                new MediaSessionControl.Session("com.d", 3, true),
                new MediaSessionControl.Session("com.e", 3, false));
        assertTrue(MediaSessionControl.stillPlaying(sessions, "com.d"));
        assertFalse(MediaSessionControl.stillPlaying(sessions, "com.e"));
        assertFalse(MediaSessionControl.stillPlaying(sessions, "com.z"));
        assertFalse(MediaSessionControl.stillPlaying(null, "com.d"));
    }

    @Test
    public void pollsReuseTheShellReadButPauseResumeAndConfirmationReadFresh() throws Exception {
        String control = readProjectFile("src/main/java/com/overdrive/app/server/MediaSessionControl.java");
        assertEquals(1500, MediaSessionControl.DUMPSYS_REUSE_MS);
        assertEquals(10_000, MediaSessionControl.EMPTY_REUSE_MS);
        assertTrue(control.contains("Read read = read(req.optBoolean(\"fresh\", false));"));
        assertTrue(control.contains("if (!fresh && cached != null"));
        // pause() and resume() use the fresh read; a paused session that went inactive is found in dumpsys.
        assertTrue(control.contains("static Read read() {\n        return read(true);\n    }"));
        assertTrue(control.contains("if (target == null && SOURCE_SESSION.equals(read.source)) {"));
        // No visible session at all is "unknown" (the engine then uses isMusicActive() as before).
        assertTrue(control.contains("if (!sessions.isEmpty()) return new Read(SOURCE_SESSION, sessions, kept);"));
    }

    @Test
    public void stateRepliesAndPackageNamesAreSafe() throws Exception {
        JSONObject state = MediaSessionControl.stateJson("session", Arrays.asList(
                new MediaSessionControl.Session("com.b", 2, true),
                new MediaSessionControl.Session("com.c", 3, true)));
        assertTrue(state.getBoolean("success"));
        assertTrue(state.getBoolean("playing"));
        assertEquals("com.c", state.getString("package"));
        assertEquals(3, state.getInt("state"));
        assertEquals("session", state.getString("source"));
        JSONObject quiet = MediaSessionControl.stateJson("dumpsys", Collections.singletonList(
                new MediaSessionControl.Session("com.b", 2, true)));
        assertFalse(quiet.getBoolean("playing"));
        assertEquals("com.b", quiet.getString("package"));
        JSONObject blind = MediaSessionControl.stateJson("unknown",
                Collections.<MediaSessionControl.Session>emptyList());
        assertFalse(blind.getBoolean("playing"));
        assertEquals("", blind.getString("package"));
        assertEquals(-1, blind.getInt("state"));
        assertEquals("com.spotify.music", MediaSessionControl.safePackage("com.spotify.music"));
        for (String bad : new String[] {null, "", "com.a;reboot", "a b", "x".repeat(129)}) {
            assertEquals(String.valueOf(bad), "", MediaSessionControl.safePackage(bad));
        }
    }

    @Test
    public void malformedRequestsAreRefusedBeforeAnyPlatformCall() throws Exception {
        JSONObject bad = MediaSessionControl.handle(new JSONObject().put("action", "stop"));
        assertFalse(bad.getBoolean("success"));
        assertTrue(bad.getString("error").contains("state, pause or resume"));
        JSONObject pkg = MediaSessionControl.handle(new JSONObject().put("action", "resume")
                .put("package", "com.a; rm -rf").put("paused", true));
        assertFalse(pkg.getBoolean("success"));
        assertTrue(pkg.getString("error").contains("package"));
        JSONObject flag = MediaSessionControl.handle(new JSONObject().put("action", "resume")
                .put("package", "com.a").put("paused", "yes"));
        assertFalse(flag.getBoolean("success"));
        assertTrue(flag.getString("error").contains("paused"));
        assertFalse(MediaSessionControl.handle(null).getBoolean("success"));
    }

    @Test
    public void theRouteKeepsTheOtherMediaTargetsAndLogsPollsQuietly() throws Exception {
        String handler = readProjectFile("src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");
        int branch = handler.indexOf("if (\"media_session\".equals(target)) {");
        int legacy = handler.indexOf("boolean isMediaKey = \"media_key\".equals(target);");
        assertTrue(branch >= 0 && legacy > branch);
        assertTrue(handler.contains("HttpResponse.sendJson(out, MediaSessionControl.handle(req).toString());"));
        assertTrue(handler.contains("ok = dispatchMediaKey(req.optString(\"key\", \"\"));"));
        assertTrue(handler.contains("static String runShellCapture(String cmd, long timeoutMs, int capBytes) {"));
        String server = readProjectFile("src/main/java/com/overdrive/app/server/HttpServer.java");
        assertTrue(server.contains("requestLine.startsWith(\"POST /api/vehicle/media?poll=\")"));
        // The session control never calls AudioManager.isMusicActive() (its javadoc explains why).
        String control = readProjectFile("src/main/java/com/overdrive/app/server/MediaSessionControl.java");
        assertFalse(control.contains("isMusicActive();"));
        assertFalse(control.contains("isMusicActive())"));
        assertTrue(control.contains("getActiveSessions(null)"));
        assertTrue(control.contains("catch (SecurityException denied)"));
        assertTrue(control.contains("\"dumpsys media_session\""));
    }

    private static String readProjectFile(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        Path direct = current.resolve(relativePath);
        Path file = Files.exists(direct)
                ? direct : current.resolve("app").resolve(relativePath);
        if (!Files.exists(file)) {
            throw new AssertionError("Could not locate project file: " + relativePath);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
