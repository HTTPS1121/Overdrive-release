package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The media session that is actually playing on the head unit, and pausing / resuming exactly
 * that one, for the voice assistant's command turn: POST /api/vehicle/media
 * {"target":"media_session","action":"state|pause|resume"}.
 *
 * <p>Truth comes from the platform's media sessions, not AudioManager.isMusicActive(): the
 * 2026-10-11 field log shows a PLAY key after every turn, also when the driver had paused the
 * player, because isMusicActive() stays true on this head unit while the player is paused; and
 * the input-layer PAUSE key took 1.6-2.6 s, so music kept playing into the command window.
 * Sources, in order: MediaSessionManager.getActiveSessions(null) on the daemon context (the shell
 * UID normally holds MEDIA_CONTENT_CONTROL; a SecurityException is caught and remembered), then
 * {@code dumpsys media_session}, else "unknown". The parsing and every decision are pure Java so
 * they are JVM-tested; the Android calls are thin wrappers further down.
 */
final class MediaSessionControl {

    private static final DaemonLogger logger = DaemonLogger.getInstance("MediaSession");

    // android.media.session.PlaybackState values, duplicated so the parser stays pure Java.
    static final int STATE_UNKNOWN = -1;
    static final int STATE_NONE = 0;
    static final int STATE_STOPPED = 1;
    static final int STATE_PAUSED = 2;
    static final int STATE_PLAYING = 3;
    static final int STATE_FAST_FORWARDING = 4;
    static final int STATE_REWINDING = 5;
    static final int STATE_BUFFERING = 6;
    static final int STATE_ERROR = 7;
    static final int STATE_CONNECTING = 8;
    static final int STATE_SKIPPING_TO_PREVIOUS = 9;
    static final int STATE_SKIPPING_TO_NEXT = 10;
    static final int STATE_SKIPPING_TO_QUEUE_ITEM = 11;

    private static final String[] STATE_NAMES = {
            "NONE", "STOPPED", "PAUSED", "PLAYING", "FAST_FORWARDING", "REWINDING", "BUFFERING",
            "ERROR", "CONNECTING", "SKIPPING_TO_PREVIOUS", "SKIPPING_TO_NEXT",
            "SKIPPING_TO_QUEUE_ITEM"
    };

    /** Where the state came from. */
    static final String SOURCE_SESSION = "session";
    static final String SOURCE_DUMPSYS = "dumpsys";
    static final String SOURCE_UNKNOWN = "unknown";

    /** How a pause / play was delivered. */
    static final String METHOD_SESSION = "session";    // the session's own transport controls
    static final String METHOD_DISPATCH = "dispatch";  // AudioManager.dispatchMediaKeyEvent, in-process
    static final String METHOD_KEY = "key";            // input keyevent through the shell (slow)
    static final String METHOD_NONE = "none";

    static final String ACTION_STATE = "state";
    static final String ACTION_PAUSE = "pause";
    static final String ACTION_RESUME = "resume";
    /** Voice commands: transport on the session in front, whatever paused it. */
    static final String ACTION_PLAY = "play";
    static final String ACTION_NEXT = "next";
    static final String ACTION_PREVIOUS = "previous";
    /** "Play <song / artist / playlist>": the player's own search (playFromSearch). */
    static final String ACTION_SEARCH = "search";

    /** Why a resume did nothing. */
    static final String SKIP_NOT_PAUSED_BY_CALLER = "not_paused_by_caller";
    static final String SKIP_NO_SESSION = "no_session";
    static final String SKIP_NOT_PAUSED = "not_paused";

    /** A dispatched PAUSE must show up in the session state this fast, else the input key is used. */
    static final long DISPATCH_VERIFY_MS = 300;
    /** A spoken play must show up as a playing state this fast, else the next delivery is tried. */
    static final long PLAY_SESSION_VERIFY_MS = 1200;
    static final long PLAY_KEY_VERIFY_MS = 1000;
    /** A spoken stop must show the player itself stopped this fast, else the next delivery is tried. */
    static final long STOP_VERIFY_MS = 1200;
    /** A search may need the network: a changed, playing track is waited for this long. */
    static final long SEARCH_VERIFY_MS = 6000;
    static final int MAX_QUERY_CHARS = 200;
    static final int MAX_ARTIST_CHARS = 100;
    /** media_kind values of the voice tool; anything else is a plain search. */
    static final String KIND_SONG = "song";
    static final String KIND_ARTIST = "artist";
    static final String KIND_ALBUM = "album";
    static final String KIND_PLAYLIST = "playlist";
    static final String KIND_LIKED = "liked";
    // MediaStore's playFromSearch extras (EXTRA_MEDIA_FOCUS and friends), as Google Assistant sends them.
    static final String EXTRA_FOCUS = "android.intent.extra.focus";
    static final String EXTRA_TITLE = "android.intent.extra.title";
    static final String EXTRA_ARTIST = "android.intent.extra.artist";
    static final String EXTRA_ALBUM = "android.intent.extra.album";
    static final String EXTRA_PLAYLIST = "android.intent.extra.playlist";
    /** Opened when no session takes searches: the car's Spotify front-end, then Spotify itself. */
    static final String[] SEARCH_APPS = {"com.vivid.music.byd", "com.spotify.music"};
    static final long LAUNCH_WAIT_MS = 3500;
    /** Inside the hands-free tool's 10 s wait, launch included. */
    static final long SEARCH_TOTAL_MS = 9000;
    private static final Pattern APP_SUFFIX = Pattern.compile("(?i)\\s+(on|in|from|using|via)\\s+(spotify|youtube music"
            + "|youtube|jio ?saavn|saavn|gaana|wynk( music)?|amazon music|apple music)\\s*$");
    private static final Pattern WORK_SUFFIX = Pattern.compile("(?i)('s)?\\s+(songs|song|music|tracks|hits)\\s*$");
    private static final long PLAYER_POLL_MS = 250;
    private static final long VERIFY_POLL_MS = 100;
    private static final long DUMPSYS_TIMEOUT_MS = 2000;
    private static final int DUMPSYS_CAP_BYTES = 256 * 1024;
    /** Polls reuse a dumpsys read this long; "no session anywhere" much longer. */
    static final long DUMPSYS_REUSE_MS = 1500;
    static final long EMPTY_REUSE_MS = 10_000;

    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_.]{1,128}");

    private static final int SHELL_UID = 2000;

    private static volatile boolean sessionApiDenied;
    private static volatile android.content.Context callerContext;
    private static volatile Read lastDump;
    private static volatile long lastDumpAt;
    /** Package of the last session any read saw playing, for a spoken play ("" until one played). */
    private static volatile String lastPlayingPkg = "";

    private MediaSessionControl() {
    }

    /** One media session as the sources report it. */
    static final class Session {
        final String pkg;
        final int state;
        final boolean active;

        Session(String pkg, int state, boolean active) {
            this.pkg = pkg;
            this.state = state;
            this.active = active;
        }
    }

    // ---- Pure parsing and decisions (JVM-tested) ----

    /** PLAYING and every state on its way to sound (buffering, seeking, skipping). */
    static boolean isPlayingState(int state) {
        switch (state) {
            case STATE_PLAYING:
            case STATE_FAST_FORWARDING:
            case STATE_REWINDING:
            case STATE_BUFFERING:
            case STATE_CONNECTING:
            case STATE_SKIPPING_TO_PREVIOUS:
            case STATE_SKIPPING_TO_NEXT:
            case STATE_SKIPPING_TO_QUEUE_ITEM:
                return true;
            default:
                return false;
        }
    }

    /**
     * Sessions from {@code dumpsys media_session}, in the service's priority order. A block starts
     * at its {@code package=} line; {@code active=} and {@code state=PlaybackState {state=N} lines
     * at the same indent belong to it, and a shallower line ends it. Android 10 prints the state as
     * a number; newer releases print a name, optionally with the number in parentheses.
     */
    static List<Session> parseDumpsys(String dump) {
        List<Session> out = new ArrayList<>();
        if (dump == null || dump.isEmpty()) return out;
        String pkg = null;
        int indent = -1;
        boolean active = false;
        int state = STATE_UNKNOWN;
        for (String raw : dump.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int lead = leadingSpaces(raw);
            if (line.startsWith("package=")) {
                if (pkg != null) out.add(new Session(pkg, state, active));
                String candidate = line.substring("package=".length()).trim();
                pkg = PACKAGE.matcher(candidate).matches() ? candidate : null;
                indent = lead;
                active = false;
                state = STATE_UNKNOWN;
                continue;
            }
            if (pkg == null) continue;
            if (lead < indent) {
                out.add(new Session(pkg, state, active));
                pkg = null;
                continue;
            }
            if (lead != indent) continue;
            if (line.startsWith("active=")) {
                active = "true".equals(line.substring("active=".length()).trim());
            } else if (line.startsWith("state=")) {
                state = parseStateValue(line.substring("state=".length()));
            }
        }
        if (pkg != null) out.add(new Session(pkg, state, active));
        return out;
    }

    /** {@code PlaybackState {state=3, …}} / {@code {state=PLAYING(3), …}} / {@code null}. */
    static int parseStateValue(String value) {
        String v = value == null ? "" : value.trim();
        if (v.startsWith("null")) return STATE_NONE;
        String prefix = "PlaybackState {";
        if (!v.startsWith(prefix)) return STATE_UNKNOWN;
        String inner = v.substring(prefix.length()).trim();
        if (!inner.startsWith("state=")) return STATE_UNKNOWN;
        String token = inner.substring("state=".length());
        int end = 0;
        while (end < token.length() && token.charAt(end) != ',' && token.charAt(end) != '}') end++;
        return parseStateToken(token.substring(0, end).trim());
    }

    static int parseStateToken(String token) {
        if (token == null || token.isEmpty()) return STATE_UNKNOWN;
        int open = token.indexOf('(');
        int close = token.indexOf(')');
        if (open >= 0 && close > open) {
            int number = parseInt(token.substring(open + 1, close).trim());
            if (number != Integer.MIN_VALUE) return number;
            token = token.substring(0, open).trim();
        }
        int number = parseInt(token);
        if (number != Integer.MIN_VALUE) return number;
        String name = token.toUpperCase(Locale.ROOT);
        if (name.startsWith("STATE_")) name = name.substring("STATE_".length());
        for (int i = 0; i < STATE_NAMES.length; i++) {
            if (STATE_NAMES[i].equals(name)) return i;
        }
        return STATE_UNKNOWN;
    }

    /** The first active session, in priority order, whose state is playing; null when none plays. */
    static Session playing(List<Session> sessions) {
        if (sessions == null) return null;
        for (Session s : sessions) {
            if (s.active && isPlayingState(s.state)) return s;
        }
        return null;
    }

    /**
     * The session a resume is about: the named package's (its paused session when it has several),
     * or, when the caller's pause was a blind key (no package known), the first active session,
     * which is where media keys are delivered.
     */
    static Session target(List<Session> sessions, String pkg) {
        if (sessions == null) return null;
        if (pkg == null || pkg.isEmpty()) {
            for (Session s : sessions) {
                if (s.active) return s;
            }
            return null;
        }
        Session first = null;
        for (Session s : sessions) {
            if (!s.pkg.equals(pkg)) continue;
            if (s.state == STATE_PAUSED) return s;
            if (first == null) first = s;
        }
        return first;
    }

    /**
     * The session a spoken play resumes: the app that last played (its paused session first), else
     * any paused session, else the first active one, which is where media keys are delivered.
     */
    static Session playTarget(List<Session> sessions, String lastPlaying) {
        if (sessions == null) return null;
        if (lastPlaying != null && !lastPlaying.isEmpty()) {
            Session first = null;
            for (Session s : sessions) {
                if (!s.active || !s.pkg.equals(lastPlaying)) continue;
                if (s.state == STATE_PAUSED) return s;
                if (first == null) first = s;
            }
            if (first != null) return first;
        }
        for (Session s : sessions) {
            if (s.active && s.state == STATE_PAUSED) return s;
        }
        return target(sessions, "");
    }

    /**
     * How a pause is delivered: to the playing session's controller, as an in-process media key
     * when only {@code dumpsys} can see the sessions, as today's input-layer key when nothing can
     * be seen at all, or not at all when the sessions are visible and none of them plays.
     */
    static String pauseAction(String source, Session playing) {
        if (SOURCE_UNKNOWN.equals(source)) return METHOD_KEY;
        if (playing == null) return METHOD_NONE;
        return SOURCE_SESSION.equals(source) ? METHOD_SESSION : METHOD_DISPATCH;
    }

    /**
     * Resume only what the caller paused and only while it is still paused: never a session the
     * driver stopped or started meanwhile, and never without the caller's own pause. Returns a
     * {@code METHOD_*} to deliver PLAY with, or a {@code SKIP_*} reason. Without the session API
     * the PLAY key goes the way the pause did (a pause that needed the input key resumes with it).
     */
    static String resumeAction(boolean callerPaused, String source, Session target,
                               String pauseMethod) {
        if (!callerPaused) return SKIP_NOT_PAUSED_BY_CALLER;
        if (SOURCE_UNKNOWN.equals(source)) return deliveryFor(pauseMethod);
        if (target == null) return SKIP_NO_SESSION;
        if (target.state != STATE_PAUSED) return SKIP_NOT_PAUSED;
        return SOURCE_SESSION.equals(source) ? METHOD_SESSION : deliveryFor(pauseMethod);
    }

    static String deliveryFor(String pauseMethod) {
        return METHOD_KEY.equals(pauseMethod) ? METHOD_KEY : METHOD_DISPATCH;
    }

    /** An Android package name, else the empty string. */
    static String safePackage(String raw) {
        return raw != null && PACKAGE.matcher(raw).matches() ? raw : "";
    }

    /** {@code state} reply body. */
    static JSONObject stateJson(String source, List<Session> sessions) throws Exception {
        Session playing = playing(sessions);
        Session top = playing != null ? playing : target(sessions, "");
        return new JSONObject()
                .put("success", true)
                .put("target", "media_session")
                .put("action", ACTION_STATE)
                .put("playing", playing != null)
                .put("package", top == null ? "" : top.pkg)
                .put("state", top == null ? STATE_UNKNOWN : top.state)
                .put("source", source);
    }

    private static int leadingSpaces(String raw) {
        int n = 0;
        while (n < raw.length() && (raw.charAt(n) == ' ' || raw.charAt(n) == '\t')) n++;
        return n;
    }

    private static int parseInt(String s) {
        if (s == null || s.isEmpty() || s.length() > 6) return Integer.MIN_VALUE;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < '0' || c > '9') && !(i == 0 && c == '-' && s.length() > 1)) {
                return Integer.MIN_VALUE;
            }
        }
        return Integer.parseInt(s);
    }

    /**
     * Whether {@code dumpsys audio}'s "players:" list shows a started player for {@code uid}: the
     * player state the platform tracks, which some players (the in-car Spotify) keep at "started"
     * after a pause their media session reports as done. Null when the list is absent.
     */
    static Boolean parsePlayerStarted(String dump, int uid) {
        if (dump == null || uid < 0) return null;
        int at = dump.indexOf("\n  players:");
        if (at < 0) return null;
        String owner = "u/pid:" + uid + "/";
        String[] lines = dump.substring(at + 1).split("\r?\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith("  ID:")) break;
            if (line.contains(owner) && line.contains("state:started")) return true;
        }
        return false;
    }

    /** {@code pkg}'s player per the platform, or null when its uid or the player list is unknown. */
    static Boolean playerStarted(String pkg) {
        int uid = uidOf(pkg);
        if (uid < 0) return null;
        return parsePlayerStarted(VehicleControlApiHandler.runShellCapture(
                "dumpsys audio", DUMPSYS_TIMEOUT_MS, 512 * 1024), uid);
    }

    private static int uidOf(String pkg) {
        if (pkg == null || pkg.isEmpty()) return -1;
        try {
            android.content.Context ctx = CameraDaemon.getAppContext();
            return ctx == null ? -1 : ctx.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Re-reads the player list until {@code pkg}'s player is {@code started}, for at most {@code budgetMs}. */
    private static Boolean playerWithin(String pkg, boolean started, long budgetMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + budgetMs;
        Boolean now;
        while (true) {
            now = playerStarted(pkg);
            if (now == null || now == started) return now;
            if (android.os.SystemClock.elapsedRealtime() + PLAYER_POLL_MS > deadline) return now;
            try {
                Thread.sleep(PLAYER_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return now;
            }
        }
    }

    // ---- Route ----

    /** Body of POST /api/vehicle/media {"target":"media_session"}; never throws. */
    static JSONObject handle(JSONObject req) {
        try {
            String action = req == null ? "" : req.optString("action", "");
            switch (action) {
                case ACTION_STATE:
                    Read read = read(req.optBoolean("fresh", false));
                    return stateJson(read.source, read.sessions);
                case ACTION_PAUSE:
                    return req.optBoolean("verify", false) ? stop() : pause();
                case ACTION_PLAY:
                    return play();
                case ACTION_NEXT:
                case ACTION_PREVIOUS:
                    return transportCommand(action);
                case ACTION_SEARCH:
                    return playFromSearch(req.optString("query", ""), req.optString("kind", ""),
                            req.optString("artist", ""));
                case ACTION_RESUME: {
                    Object rawPackage = req.opt("package");
                    String pkg = rawPackage instanceof String ? ((String) rawPackage).trim() : "";
                    if (!pkg.isEmpty() && safePackage(pkg).isEmpty()) {
                        return error("package must be an Android package name");
                    }
                    Object paused = req.opt("paused");
                    if (paused != null && !(paused instanceof Boolean)) {
                        return error("paused must be true or false");
                    }
                    String method = req.optString("method", "");
                    return resume(pkg, Boolean.TRUE.equals(paused), method);
                }
                default:
                    return error("action must be state, pause or resume (or play, next, previous, search)");
            }
        } catch (Throwable t) {
            logger.warn("Media session request failed: " + t.getMessage());
            try {
                return error("Media session control failed");
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    private static JSONObject error(String message) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("target", "media_session")
                .put("error", message);
    }

    /** The spoken name without a trailing "on Spotify", and for an artist without "songs" or "music". */
    static String cleanSearchText(String raw, String kind) {
        String out = raw == null ? "" : raw.trim();
        out = APP_SUFFIX.matcher(out).replaceFirst("").trim();
        if (KIND_ARTIST.equals(kind)) out = WORK_SUFFIX.matcher(out).replaceFirst("").trim();
        return out;
    }

    /**
     * Extras that make a player search one category. Without them the in-car Spotify searches the
     * whole quoted phrase across every type and plays the first track, so a playlist or "song by
     * artist" request played the wrong thing; with them it searches title plus artist:, artist:,
     * album: or playlists only.
     */
    static Map<String, String> searchExtras(String kind, String name, String artist) {
        Map<String, String> out = new LinkedHashMap<>();
        String q = name == null ? "" : name;
        String a = artist == null ? "" : artist;
        if (KIND_SONG.equals(kind) && !q.isEmpty()) {
            out.put(EXTRA_FOCUS, "vnd.android.cursor.item/audio");
            out.put(EXTRA_TITLE, q);
            if (!a.isEmpty()) out.put(EXTRA_ARTIST, a);
        } else if (KIND_ARTIST.equals(kind) && !(q.isEmpty() && a.isEmpty())) {
            out.put(EXTRA_FOCUS, "vnd.android.cursor.item/artist");
            out.put(EXTRA_ARTIST, a.isEmpty() ? q : a);
        } else if (KIND_ALBUM.equals(kind) && !q.isEmpty()) {
            out.put(EXTRA_FOCUS, "vnd.android.cursor.item/album");
            out.put(EXTRA_ALBUM, q);
            if (!a.isEmpty()) out.put(EXTRA_ARTIST, a);
        } else if (KIND_PLAYLIST.equals(kind) && !q.isEmpty()) {
            out.put(EXTRA_FOCUS, "vnd.android.cursor.item/playlist");
            out.put(EXTRA_PLAYLIST, q);
        }
        return out;
    }

    /** The query itself, all a player that ignores the extras reads; empty asks for liked songs. */
    static String searchQuery(String kind, String name, String artist) {
        if (KIND_LIKED.equals(kind)) return "";
        String q = name == null ? "" : name;
        String a = artist == null ? "" : artist;
        if (KIND_ARTIST.equals(kind)) return a.isEmpty() ? q : a;
        return a.isEmpty() ? q : (q + " " + a).trim();
    }

    static String searchKind(String raw) {
        String kind = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return KIND_SONG.equals(kind) || KIND_ARTIST.equals(kind) || KIND_ALBUM.equals(kind)
                || KIND_PLAYLIST.equals(kind) || KIND_LIKED.equals(kind) ? kind : "";
    }

    private static String clip(String text, int max) {
        return text.length() > max ? text.substring(0, max).trim() : text;
    }

    // ---- Android-bound part ----

    /** One look at the sessions; {@code controllers} is parallel to {@code sessions} for the session API. */
    static final class Read {
        final String source;
        final List<Session> sessions;
        final List<android.media.session.MediaController> controllers;

        Read(String source, List<Session> sessions,
             List<android.media.session.MediaController> controllers) {
            this.source = source;
            this.sessions = sessions;
            this.controllers = controllers;
        }

        /** The controller of exactly that session (an app may own several). */
        android.media.session.MediaController controllerFor(Session session) {
            if (controllers == null || session == null) return null;
            for (int i = 0; i < sessions.size() && i < controllers.size(); i++) {
                if (sessions.get(i) == session) return controllers.get(i);
            }
            return null;
        }
    }

    /**
     * What plays, from the best source available. No visible session at all is "unknown", not
     * "nothing plays": a player without a media session must keep today's behaviour, so the
     * caller then falls back to isMusicActive() as before. An empty active-session list is checked
     * against dumpsys, which also lists inactive sessions (some players deactivate on pause).
     */
    static Read read() {
        return read(true);
    }

    /**
     * {@code fresh} false is the engine's once-a-second poll: a dumpsys result is reused for
     * {@link #DUMPSYS_REUSE_MS} (and "no session anywhere" for {@link #EMPTY_REUSE_MS}), so the
     * fallback costs at most one shell spawn per 1.5 s. Pause, resume and confirmation read fresh.
     */
    static Read read(boolean fresh) {
        Read read = readSources(fresh);
        Session playing = playing(read.sessions);
        if (playing != null) lastPlayingPkg = playing.pkg;
        return read;
    }

    /**
     * The context a controller names as its caller. The daemon runs as the shell uid but its
     * context says com.overdrive.app (another uid, without MEDIA_CONTENT_CONTROL), and the in-car
     * Spotify drops every command from that name, so commands carry the uid's own package.
     */
    private static android.content.Context callerContext(android.content.Context ctx) {
        android.content.Context cached = callerContext;
        if (cached != null) return cached;
        android.content.Context out = ctx;
        if (android.os.Process.myUid() == SHELL_UID) {
            String owner = null;
            try {
                String[] owners = ctx.getPackageManager().getPackagesForUid(SHELL_UID);
                if (owners != null && owners.length > 0) owner = owners[0];
            } catch (Throwable ignored) {
            }
            final String name = owner != null ? owner : "com.android.shell";
            out = new android.content.ContextWrapper(ctx) {
                @Override
                public String getPackageName() {
                    return name;
                }
            };
            logger.info("Media session commands go out as " + name);
        }
        callerContext = out;
        return out;
    }

    private static Read readSources(boolean fresh) {
        android.content.Context ctx = CameraDaemon.getAppContext();
        if (ctx != null && !sessionApiDenied) {
            try {
                android.media.session.MediaSessionManager manager =
                        (android.media.session.MediaSessionManager) ctx.getSystemService(
                                android.content.Context.MEDIA_SESSION_SERVICE);
                if (manager != null) {
                    List<android.media.session.MediaController> controllers =
                            manager.getActiveSessions(null);
                    android.content.Context caller = callerContext(ctx);
                    List<Session> sessions = new ArrayList<>();
                    List<android.media.session.MediaController> kept = new ArrayList<>();
                    for (android.media.session.MediaController c : controllers == null
                            ? Collections.<android.media.session.MediaController>emptyList()
                            : controllers) {
                        String pkg = safePackage(c.getPackageName());
                        if (pkg.isEmpty()) continue;
                        android.media.session.PlaybackState ps = c.getPlaybackState();
                        sessions.add(new Session(pkg, ps == null ? STATE_NONE : ps.getState(), true));
                        kept.add(caller == ctx ? c
                                : new android.media.session.MediaController(caller, c.getSessionToken()));
                    }
                    if (!sessions.isEmpty()) return new Read(SOURCE_SESSION, sessions, kept);
                }
            } catch (SecurityException denied) {
                sessionApiDenied = true;
                logger.warn("Media session API refused for the daemon (" + denied.getMessage()
                        + "); reading dumpsys media_session instead");
            } catch (Throwable t) {
                // Not a permission problem but no usable API either: do not retry it every poll.
                sessionApiDenied = true;
                logger.warn("Media session API unavailable for the daemon (" + t.getMessage()
                        + "); reading dumpsys media_session instead");
            }
        }
        long now = android.os.SystemClock.elapsedRealtime();
        Read cached = lastDump;
        if (!fresh && cached != null
                && now - lastDumpAt < (cached.sessions.isEmpty() ? EMPTY_REUSE_MS : DUMPSYS_REUSE_MS)) {
            return cached;
        }
        Read read = readDumpsys();
        lastDump = read;
        lastDumpAt = now;
        return read;
    }

    private static Read readDumpsys() {
        String dump = VehicleControlApiHandler.runShellCapture(
                "dumpsys media_session", DUMPSYS_TIMEOUT_MS, DUMPSYS_CAP_BYTES);
        List<Session> sessions = parseDumpsys(dump);
        if (!sessions.isEmpty()) return new Read(SOURCE_DUMPSYS, sessions, null);
        return new Read(SOURCE_UNKNOWN, Collections.<Session>emptyList(), null);
    }

    static JSONObject pause() throws Exception {
        long started = android.os.SystemClock.elapsedRealtime();
        Read read = read();
        Session playing = playing(read.sessions);
        String action = pauseAction(read.source, playing);
        String method = METHOD_NONE;
        boolean paused = false;
        if (METHOD_SESSION.equals(action)) {
            if (transport(read.controllerFor(playing), true)) {
                method = METHOD_SESSION;
                paused = true;
            } else {
                action = METHOD_DISPATCH;
            }
        }
        if (METHOD_DISPATCH.equals(action)) {
            // Only dumpsys (or a refused controller) sees the session: the in-process key is
            // instant, but it is not delivered to every source, so the state must confirm it.
            if (dispatchKey(read.controllerFor(playing), android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
                    && stoppedWithin(playing.pkg, DISPATCH_VERIFY_MS)) {
                method = METHOD_DISPATCH;
                paused = true;
            } else {
                action = METHOD_KEY;
            }
        }
        if (METHOD_KEY.equals(action)) {
            paused = VehicleControlApiHandler.dispatchMediaKey("pause");
            method = METHOD_KEY;
        }
        long ms = android.os.SystemClock.elapsedRealtime() - started;
        String pkg = playing == null ? "" : playing.pkg;
        logger.info("Media session pause: paused=" + paused + " package=" + (pkg.isEmpty() ? "-" : pkg)
                + " state=" + (playing == null ? STATE_UNKNOWN : playing.state) + " method=" + method
                + " source=" + read.source + " " + ms + " ms");
        return new JSONObject()
                .put("success", true)
                .put("target", "media_session")
                .put("action", ACTION_PAUSE)
                .put("paused", paused)
                .put("package", pkg)
                .put("previousState", playing == null ? STATE_UNKNOWN : playing.state)
                .put("method", method)
                .put("source", read.source)
                .put("ms", ms);
    }

    static JSONObject resume(String pkg, boolean callerPaused, String pauseMethod) throws Exception {
        long started = android.os.SystemClock.elapsedRealtime();
        Read read = read();
        Session target = target(read.sessions, pkg);
        if (target == null && SOURCE_SESSION.equals(read.source)) {
            // Some players deactivate their session while paused; dumpsys still lists it.
            Read all = readDumpsys();
            Session inactive = target(all.sessions, pkg);
            if (inactive != null) {
                read = all;
                target = inactive;
            }
        }
        String action = resumeAction(callerPaused, read.source, target, pauseMethod);
        boolean resumed = false;
        String method = METHOD_NONE;
        String reason = null;
        if (METHOD_SESSION.equals(action)) {
            if (transport(read.controllerFor(target), false)) {
                resumed = true;
                method = METHOD_SESSION;
            } else {
                action = deliveryFor(pauseMethod);
            }
        }
        if (METHOD_DISPATCH.equals(action)) {
            resumed = dispatchKey(read.controllerFor(target), android.view.KeyEvent.KEYCODE_MEDIA_PLAY);
            method = METHOD_DISPATCH;
        } else if (METHOD_KEY.equals(action)) {
            resumed = VehicleControlApiHandler.dispatchMediaKey("play");
            method = METHOD_KEY;
        } else if (!METHOD_SESSION.equals(action)) {
            reason = action;
        }
        long ms = android.os.SystemClock.elapsedRealtime() - started;
        String named = target != null ? target.pkg : safePackage(pkg);
        logger.info("Media session resume: resumed=" + resumed + " package="
                + (named.isEmpty() ? "-" : named)
                + " state=" + (target == null ? STATE_UNKNOWN : target.state) + " method=" + method
                + (reason != null ? " reason=" + reason : "") + " source=" + read.source
                + " " + ms + " ms");
        JSONObject out = new JSONObject()
                .put("success", true)
                .put("target", "media_session")
                .put("action", ACTION_RESUME)
                .put("resumed", resumed)
                .put("package", named)
                .put("state", target == null ? STATE_UNKNOWN : target.state)
                .put("method", method)
                .put("source", read.source)
                .put("ms", ms);
        if (reason != null) out.put("reason", reason);
        return out;
    }

    /**
     * A spoken stop, confirmed by the player itself: the session's own pause, then the in-process
     * PAUSE key, then the input-layer key, each kept only until the player has stopped. Without a
     * readable player list it is today's pause, confirmed by the session alone.
     */
    static JSONObject stop() throws Exception {
        long started = android.os.SystemClock.elapsedRealtime();
        Read read = read();
        Session playing = playing(read.sessions);
        String pkg = playing != null ? playing.pkg : lastPlayingPkg;
        Boolean before = playerStarted(pkg);
        // Without a readable player list, or with nothing playing at all, today's pause decides.
        if (before == null || (playing == null && !before)) return pause();
        StringBuilder tried = new StringBuilder();
        Boolean after = before;
        if (playing != null && transport(read.controllerFor(playing), true)) {
            tried.append(METHOD_SESSION);
            after = playerWithin(pkg, false, STOP_VERIFY_MS);
        }
        if (Boolean.TRUE.equals(after)
                && dispatchKey(read.controllerFor(playing), android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)) {
            tried.append(tried.length() > 0 ? "," : "").append(METHOD_DISPATCH);
            after = playerWithin(pkg, false, STOP_VERIFY_MS);
        }
        if (Boolean.TRUE.equals(after) && VehicleControlApiHandler.dispatchMediaKey(ACTION_PAUSE)) {
            tried.append(tried.length() > 0 ? "," : "").append(METHOD_KEY);
            after = playerWithin(pkg, false, STOP_VERIFY_MS);
        }
        boolean paused = tried.length() > 0 && !Boolean.TRUE.equals(after);
        long ms = android.os.SystemClock.elapsedRealtime() - started;
        logger.info("Media session stop: paused=" + paused + " package=" + (pkg == null || pkg.isEmpty() ? "-" : pkg)
                + " state=" + (playing == null ? STATE_UNKNOWN : playing.state)
                + " player=" + (before == null ? "unknown" : before ? "started" : "idle")
                + "->" + (after == null ? "unknown" : after ? "started" : "idle")
                + " method=" + (tried.length() == 0 ? METHOD_NONE : tried.toString())
                + " source=" + read.source + " " + ms + " ms");
        return new JSONObject()
                .put("success", true)
                .put("target", "media_session")
                .put("action", ACTION_PAUSE)
                .put("paused", paused)
                .put("package", pkg == null ? "" : pkg)
                .put("previousState", playing == null ? STATE_UNKNOWN : playing.state)
                .put("method", tried.length() == 0 ? METHOD_NONE : tried.toString())
                .put("source", read.source)
                .put("ms", ms);
    }

    /**
     * A spoken play, confirmed by the session state: the target's own controls, then the in-process
     * PLAY key, then the input-layer key, each kept only until something is seen playing. PLAY is
     * idempotent, so a slow player that starts late is not toggled back off. {@code playing} is
     * meaningful only when {@code verified}; without any visible session it stays a blind key.
     */
    static JSONObject play() throws Exception {
        long started = android.os.SystemClock.elapsedRealtime();
        Read read = read();
        Session already = playing(read.sessions);
        // A session can stay "playing" over a stopped player (the in-car Spotify after a pause).
        if (already != null && Boolean.FALSE.equals(playerStarted(already.pkg))) already = null;
        boolean visible = !SOURCE_UNKNOWN.equals(read.source);
        Session target = already != null ? already : playTarget(read.sessions, lastPlayingPkg);
        StringBuilder tried = new StringBuilder();
        boolean sent = already != null;
        boolean nowPlaying = already != null;
        if (!nowPlaying && visible && transport(read.controllerFor(target), false)) {
            sent = true;
            tried.append(METHOD_SESSION);
            nowPlaying = playingWithin(PLAY_SESSION_VERIFY_MS);
        }
        if (!nowPlaying && visible
                && dispatchKey(read.controllerFor(target), android.view.KeyEvent.KEYCODE_MEDIA_PLAY)) {
            sent = true;
            tried.append(tried.length() > 0 ? "," : "").append(METHOD_DISPATCH);
            nowPlaying = playingWithin(PLAY_KEY_VERIFY_MS);
        }
        if (!nowPlaying && VehicleControlApiHandler.dispatchMediaKey(ACTION_PLAY)) {
            sent = true;
            tried.append(tried.length() > 0 ? "," : "").append(METHOD_KEY);
            if (visible) nowPlaying = playingWithin(PLAY_KEY_VERIFY_MS);
        }
        long ms = android.os.SystemClock.elapsedRealtime() - started;
        String pkg = target == null ? "" : target.pkg;
        String method = already != null ? METHOD_NONE : tried.length() == 0 ? METHOD_NONE : tried.toString();
        logger.info("Media session play: playing=" + (visible ? String.valueOf(nowPlaying) : "unknown")
                + (already != null ? " (already)" : "") + " package=" + (pkg.isEmpty() ? "-" : pkg)
                + " state=" + (target == null ? STATE_UNKNOWN : target.state) + " method=" + method
                + " source=" + read.source + " " + ms + " ms");
        return new JSONObject()
                .put("success", true)
                .put("target", "media_session")
                .put("action", ACTION_PLAY)
                .put("sent", sent)
                .put("verified", visible)
                .put("playing", nowPlaying)
                .put("package", pkg)
                .put("previousState", target == null ? STATE_UNKNOWN : target.state)
                .put("method", method)
                .put("source", read.source)
                .put("ms", ms);
    }

    /**
     * Re-reads the sessions until one plays (and, when the player list is readable, its player
     * has started), for at most {@code budgetMs}.
     */
    private static boolean playingWithin(long budgetMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + budgetMs;
        while (true) {
            Session now = playing(read().sessions);
            if (now != null && !Boolean.FALSE.equals(playerStarted(now.pkg))) return true;
            if (android.os.SystemClock.elapsedRealtime() + VERIFY_POLL_MS > deadline) return false;
            try {
                Thread.sleep(VERIFY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * The player's own search, as voice assistants ask for it (TransportControls.playFromSearch),
     * on the playing session or the one a spoken play would resume. Confirmed when that app plays
     * a different track (or plays at all when it was silent); the query is never logged.
     */
    static JSONObject playFromSearch(String rawQuery, String rawKind, String rawArtist) throws Exception {
        long started = android.os.SystemClock.elapsedRealtime();
        String kind = searchKind(rawKind);
        String name = clip(cleanSearchText(rawQuery, kind), MAX_QUERY_CHARS);
        String artist = clip(cleanSearchText(rawArtist, KIND_ARTIST), MAX_ARTIST_CHARS);
        String query = searchQuery(kind, name, artist);
        Read read = read();
        // BYD's media centre is often the only (and paused) session and takes no searches.
        Session target = searchTarget(read, lastPlayingPkg);
        boolean launched = false;
        if (target == null && (!query.isEmpty() || KIND_LIKED.equals(kind)) && launchSearchApp()) {
            launched = true;
            long until = started + LAUNCH_WAIT_MS;
            while (target == null && android.os.SystemClock.elapsedRealtime() < until) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                read = read();
                target = searchTarget(read, lastPlayingPkg);
            }
        }
        if (target == null) {
            // Nothing takes searches: today's choice, reported as unsupported below.
            target = playing(read.sessions);
            if (target == null) target = playTarget(read.sessions, lastPlayingPkg);
        }
        boolean wasPlaying = target != null && isPlayingState(target.state)
                && !Boolean.FALSE.equals(playerStarted(target.pkg));
        android.media.session.MediaController controller = read.controllerFor(target);
        String pkg = target == null ? "" : target.pkg;
        String reason = null;
        boolean sent = false;
        boolean advertised = false;
        String title = "";
        String by = "";
        boolean nowPlaying = false;
        if (query.isEmpty() && !KIND_LIKED.equals(kind)) {
            reason = "no_query";
        } else if (controller == null) {
            reason = "no_player";
        } else {
            android.media.session.PlaybackState ps = controller.getPlaybackState();
            advertised = ps != null
                    && (ps.getActions() & android.media.session.PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0;
            String before = titleOf(controller);
            try {
                android.os.Bundle extras = new android.os.Bundle();
                for (Map.Entry<String, String> e : searchExtras(kind, name, artist).entrySet()) {
                    extras.putString(e.getKey(), e.getValue());
                }
                controller.getTransportControls().playFromSearch(query, extras);
                sent = true;
            } catch (Throwable t) {
                logger.warn("Media session search failed: " + t.getMessage());
            }
            long deadline = Math.min(started + SEARCH_TOTAL_MS,
                    android.os.SystemClock.elapsedRealtime() + SEARCH_VERIFY_MS);
            while (sent && android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                Read again = read();
                for (int i = 0; i < again.sessions.size(); i++) {
                    Session s = again.sessions.get(i);
                    if (!s.pkg.equals(pkg) || !isPlayingState(s.state)) continue;
                    String now = titleOf(again.controllerFor(s));
                    if (!wasPlaying || (!now.isEmpty() && !now.equals(before))) {
                        nowPlaying = !Boolean.FALSE.equals(playerStarted(pkg));
                        title = now;
                        by = artistOf(again.controllerFor(s));
                    }
                }
                if (nowPlaying) break;
            }
            if (!nowPlaying) reason = advertised ? "not_played" : "unsupported";
        }
        long ms = android.os.SystemClock.elapsedRealtime() - started;
        logger.info("Media session search: playing=" + nowPlaying + " package=" + (pkg.isEmpty() ? "-" : pkg)
                + " advertised=" + advertised + (launched ? " launched=true" : "")
                + " kind=" + (kind.isEmpty() ? "any" : kind)
                + " chars=" + query.length() + " artist=" + !artist.isEmpty()
                + (reason != null ? " reason=" + reason : "") + " " + ms + " ms");
        JSONObject out = new JSONObject()
                .put("success", true)
                .put("target", "media_session")
                .put("action", ACTION_SEARCH)
                .put("sent", sent)
                .put("playing", nowPlaying)
                .put("package", pkg)
                .put("title", title)
                .put("artist", by)
                .put("ms", ms);
        if (reason != null) out.put("reason", reason);
        return out;
    }

    static boolean advertisesSearch(android.media.session.MediaController c) {
        try {
            android.media.session.PlaybackState ps = c == null ? null : c.getPlaybackState();
            return ps != null && (ps.getActions() & android.media.session.PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Where a search goes: the playing session, the app that last played, then any, each only if it takes searches. */
    private static Session searchTarget(Read read, String lastPlaying) {
        Session playing = playing(read.sessions);
        if (playing != null && advertisesSearch(read.controllerFor(playing))) return playing;
        Session any = null;
        for (Session s : read.sessions) {
            if (!advertisesSearch(read.controllerFor(s))) continue;
            if (s.pkg.equals(lastPlaying)) return s;
            if (any == null) any = s;
        }
        return any;
    }

    /** Opens the first installed {@link #SEARCH_APPS} entry, as the car's own assistant does; true when one started. */
    private static boolean launchSearchApp() {
        android.content.Context ctx = CameraDaemon.getAppContext();
        if (ctx == null) return false;
        for (String pkg : SEARCH_APPS) {
            try {
                android.content.Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                android.content.ComponentName c = launch == null ? null : launch.getComponent();
                if (c == null) continue;
                String out = VehicleControlApiHandler.runShellCapture(
                        "am start -n " + c.flattenToShortString(), 4000, 4096);
                if (out != null && !out.contains("Error")) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static String titleOf(android.media.session.MediaController controller) {
        try {
            android.media.MediaMetadata m = controller == null ? null : controller.getMetadata();
            String t = m == null ? null : m.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
            return t == null ? "" : t.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String artistOf(android.media.session.MediaController controller) {
        try {
            android.media.MediaMetadata m = controller == null ? null : controller.getMetadata();
            String a = m == null ? null : m.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
            return a == null ? "" : a.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Play / next / previous for a spoken command: the playing session (else the first active one)
     * through its own controls, else an in-process media key, else the input-layer key.
     */
    static JSONObject transportCommand(String action) throws Exception {
        long started = android.os.SystemClock.elapsedRealtime();
        Read read = read();
        Session playing = playing(read.sessions);
        Session top = playing != null ? playing : target(read.sessions, "");
        android.media.session.MediaController controller = read.controllerFor(top);
        String method = METHOD_NONE;
        boolean sent = false;
        if (controller != null) {
            try {
                android.media.session.MediaController.TransportControls controls =
                        controller.getTransportControls();
                if (ACTION_NEXT.equals(action)) controls.skipToNext();
                else if (ACTION_PREVIOUS.equals(action)) controls.skipToPrevious();
                else controls.play();
                sent = true;
                method = METHOD_SESSION;
            } catch (Throwable t) {
                logger.warn("Media session " + action + " via controller failed: " + t.getMessage());
            }
        }
        if (!sent) {
            int keyCode = ACTION_NEXT.equals(action) ? android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                    : ACTION_PREVIOUS.equals(action) ? android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    : android.view.KeyEvent.KEYCODE_MEDIA_PLAY;
            if (top != null && dispatchKey(controller, keyCode)) {
                sent = true;
                method = METHOD_DISPATCH;
            } else {
                sent = VehicleControlApiHandler.dispatchMediaKey(action);
                method = METHOD_KEY;
            }
        }
        long ms = android.os.SystemClock.elapsedRealtime() - started;
        String pkg = top == null ? "" : top.pkg;
        logger.info("Media session " + action + ": sent=" + sent + " package=" + (pkg.isEmpty() ? "-" : pkg)
                + " method=" + method + " source=" + read.source + " " + ms + " ms");
        return new JSONObject()
                .put("success", true)
                .put("target", "media_session")
                .put("action", action)
                .put("sent", sent)
                .put("package", pkg)
                .put("method", method)
                .put("source", read.source)
                .put("ms", ms);
    }

    /** pause() / play() on the session's own transport controls; false when it cannot be asked. */
    private static boolean transport(android.media.session.MediaController controller, boolean pause) {
        if (controller == null) return false;
        try {
            android.media.session.MediaController.TransportControls controls =
                    controller.getTransportControls();
            if (pause) {
                controls.pause();
            } else {
                controls.play();
            }
            return true;
        } catch (Throwable t) {
            logger.warn("Media session " + (pause ? "pause" : "play") + " via controller failed: "
                    + t.getMessage());
            return false;
        }
    }

    /**
     * A complete DOWN+UP media key in this process (instant, no shell): to that session's own
     * controller when there is one, else through AudioManager, which needs an app context the
     * daemon does not have (it threw a NullPointerException on every call in the 2026-10-13 log).
     */
    private static boolean dispatchKey(android.media.session.MediaController controller, int keyCode) {
        long at = android.os.SystemClock.uptimeMillis();
        if (controller != null) {
            try {
                boolean down = controller.dispatchMediaButtonEvent(new android.view.KeyEvent(at, at,
                        android.view.KeyEvent.ACTION_DOWN, keyCode, 0));
                boolean up = controller.dispatchMediaButtonEvent(new android.view.KeyEvent(at, at,
                        android.view.KeyEvent.ACTION_UP, keyCode, 0));
                if (down && up) return true;
            } catch (Throwable t) {
                logger.warn("Media button " + keyCode + " via controller failed: " + t.getMessage());
            }
        }
        try {
            android.content.Context ctx = CameraDaemon.getAppContext();
            android.media.AudioManager am = ctx == null ? null
                    : (android.media.AudioManager) ctx.getSystemService(
                            android.content.Context.AUDIO_SERVICE);
            if (am == null) return false;
            long now = android.os.SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(now, now,
                    android.view.KeyEvent.ACTION_DOWN, keyCode, 0));
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(now, now,
                    android.view.KeyEvent.ACTION_UP, keyCode, 0));
            return true;
        } catch (Throwable t) {
            logger.warn("dispatchMediaKeyEvent(" + keyCode + ") failed: " + t.getMessage());
            return false;
        }
    }

    /** Whether any active session of {@code pkg} still plays. */
    static boolean stillPlaying(List<Session> sessions, String pkg) {
        if (sessions == null || pkg == null) return false;
        for (Session s : sessions) {
            if (s.active && s.pkg.equals(pkg) && isPlayingState(s.state)) return true;
        }
        return false;
    }

    /** Re-reads the sessions until {@code pkg} no longer plays, for at most {@code budgetMs}. */
    private static boolean stoppedWithin(String pkg, long budgetMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + budgetMs;
        while (true) {
            Read again = read();
            if (!stillPlaying(again.sessions, pkg)) return true;
            if (android.os.SystemClock.elapsedRealtime() + VERIFY_POLL_MS > deadline) return false;
            try {
                Thread.sleep(VERIFY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
