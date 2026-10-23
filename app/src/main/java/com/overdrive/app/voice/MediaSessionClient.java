package com.overdrive.app.voice;

import android.util.Log;

import com.overdrive.app.util.DaemonHttpClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * The voice engine's side of the daemon's media-session control, POST /api/vehicle/media
 * {"target":"media_session","action":"state|pause|resume"}. Every call blocks, so the engine runs
 * them on its io executor, never on the audio thread. The polled state uses
 * {@code /api/vehicle/media?poll=1}, which the daemon does not log line by line.
 */
final class MediaSessionClient {

    private static final String TAG = "VoiceMedia";
    private static final String PATH = "/api/vehicle/media";
    private static final String POLL_PATH = PATH + "?poll=1";
    private static final int MAX_REPLY_BYTES = 4096;
    /** Package names in the turn log are cut to this many characters (keeping the distinctive tail). */
    static final int MAX_PACKAGE_TOKEN = 32;

    private MediaSessionClient() {
    }

    /** What the daemon sees: source session | dumpsys | unknown; unknown also when the call failed. */
    static final class State {
        final String source;
        final boolean playing;
        final String pkg;

        State(String source, boolean playing, String pkg) {
            this.source = source;
            this.playing = playing;
            this.pkg = pkg;
        }

        boolean known() {
            return !MediaTurnPolicy.SOURCE_UNKNOWN.equals(source);
        }
    }

    /** A pause reply; {@code paused} only when the daemon delivered a pause to playing media. */
    static final class Paused {
        final boolean paused;
        final String pkg;
        final int previousState;
        final String method;
        final int ms;

        Paused(boolean paused, String pkg, int previousState, String method, int ms) {
            this.paused = paused;
            this.pkg = pkg;
            this.previousState = previousState;
            this.method = method;
            this.ms = ms;
        }
    }

    static final State UNKNOWN = new State(MediaTurnPolicy.SOURCE_UNKNOWN, false, "");
    static final Paused NOT_PAUSED = new Paused(false, "", -1, "none", 0);

    /** The daemon's answer about the head unit's own assistant duck; null fields mean no answer. */
    static final class Duck {
        final Boolean supported;
        final boolean ducked;

        Duck(Boolean supported, boolean ducked) {
            this.supported = supported;
            this.ducked = ducked;
        }
    }

    static final Duck NO_DUCK_REPLY = new Duck(null, false);

    // ---- Pure (JVM-tested) ----

    static JSONObject request(String action) throws Exception {
        return new JSONObject().put("target", "media_session").put("action", action);
    }

    /** The resume request: the package the pause named ("" when unknown) and how it was paused. */
    static JSONObject resumeRequest(String pkg, String method) throws Exception {
        return request("resume")
                .put("package", pkg == null ? "" : pkg)
                .put("paused", true)
                .put("method", method == null ? "" : method);
    }

    static State parseState(JSONObject reply) {
        if (reply == null || !reply.optBoolean("success", false)) return UNKNOWN;
        String source = reply.optString("source", MediaTurnPolicy.SOURCE_UNKNOWN);
        if (!"session".equals(source) && !"dumpsys".equals(source)) return UNKNOWN;
        return new State(source, reply.optBoolean("playing", false),
                reply.optString("package", ""));
    }

    static JSONObject duckRequest(String action, long leaseMs) throws Exception {
        JSONObject request = new JSONObject().put("target", "media_duck").put("action", action);
        if (leaseMs > 0) request.put("leaseMs", leaseMs);
        return request;
    }

    /** A daemon without the duck target answers success=false: that is "not supported", not "no answer". */
    static Duck parseDuck(JSONObject reply) {
        if (reply == null) return NO_DUCK_REPLY;
        if (!reply.optBoolean("success", false)) return new Duck(Boolean.FALSE, false);
        return new Duck(reply.optBoolean("supported", false), reply.optBoolean("ducked", false));
    }

    static Paused parsePause(JSONObject reply) {
        if (reply == null || !reply.optBoolean("success", false)) return NOT_PAUSED;
        return new Paused(reply.optBoolean("paused", false), reply.optString("package", ""),
                reply.optInt("previousState", -1), reply.optString("method", "none"),
                Math.max(0, reply.optInt("ms", 0)));
    }

    /**
     * A short log-safe token for a package name: package characters only, at most
     * {@link #MAX_PACKAGE_TOKEN} long, keeping the tail ("…youtube.music"), null when nothing is left.
     */
    static String shortPackage(String pkg) {
        if (pkg == null) return null;
        String clean = pkg.replaceAll("[^A-Za-z0-9_.]", "");
        if (clean.length() > MAX_PACKAGE_TOKEN) {
            clean = clean.substring(clean.length() - MAX_PACKAGE_TOKEN);
            int dot = clean.indexOf('.');
            if (dot >= 0 && dot < clean.length() - 1) clean = clean.substring(dot + 1);
        }
        while (clean.startsWith(".")) clean = clean.substring(1);
        return clean.isEmpty() ? null : clean;
    }

    // ---- Daemon calls (blocking; io executor only) ----

    /** The state request; {@code fresh} (the pause confirmation) bypasses the daemon's poll reuse. */
    static JSONObject stateRequest(boolean fresh) throws Exception {
        JSONObject request = request("state");
        if (fresh) request.put("fresh", true);
        return request;
    }

    static State state(boolean fresh) {
        try {
            return parseState(post(POLL_PATH, stateRequest(fresh), 600, 1500));
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }

    static Paused pause() {
        try {
            return parsePause(post(PATH, request("pause"), 1000, 4500));
        } catch (Throwable t) {
            Log.w(TAG, "media pause failed: " + t.getMessage());
            return NOT_PAUSED;
        }
    }

    static boolean resume(String pkg, String method) {
        try {
            JSONObject reply = post(PATH, resumeRequest(pkg, method), 1000, 4500);
            boolean resumed = reply != null && reply.optBoolean("resumed", false);
            Log.i(TAG, "media resume " + (resumed ? "sent" : "skipped ("
                    + (reply == null ? "no reply" : reply.optString("reason", "not delivered")) + ")"));
            return resumed;
        } catch (Throwable t) {
            Log.w(TAG, "media resume failed: " + t.getMessage());
            return false;
        }
    }

    static Duck duckState() {
        try {
            return parseDuck(post(PATH, duckRequest("state", 0), 600, 1500));
        } catch (Throwable t) {
            return NO_DUCK_REPLY;
        }
    }

    /** Ducks media for {@code leaseMs}, or renews the duck; the daemon releases it when not renewed. */
    static Duck duck(long leaseMs) {
        try {
            return parseDuck(post(PATH, duckRequest("duck", leaseMs), 1000, 3000));
        } catch (Throwable t) {
            Log.w(TAG, "media duck failed: " + t.getMessage());
            return NO_DUCK_REPLY;
        }
    }

    static void releaseDuck() {
        try {
            post(PATH, duckRequest("release", 0), 1000, 3000);
        } catch (Throwable t) {
            Log.w(TAG, "media duck release failed: " + t.getMessage());
        }
    }

    private static JSONObject post(String path, JSONObject body, int connectMs, int readMs)
            throws Exception {
        HttpURLConnection conn = null;
        try {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn = DaemonHttpClient.open(path, "POST", connectMs, readMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream input = in) {
                byte[] tmp = new byte[512];
                int r;
                while ((r = input.read(tmp)) > 0) {
                    if (buf.size() + r > MAX_REPLY_BYTES) break;
                    buf.write(tmp, 0, r);
                }
            }
            return new JSONObject(buf.toString("UTF-8"));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
