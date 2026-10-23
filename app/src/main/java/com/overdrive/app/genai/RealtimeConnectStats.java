package com.overdrive.app.genai;

import org.json.JSONObject;

/**
 * Outcome of the most recent provider-native realtime voice connect, recorded by the voice bridge
 * when a session ends and shown on the GenAI and voice status endpoints. One record is enough: the
 * settings page only needs to tell "the route is reachable" from "the last attempt failed and why".
 */
public final class RealtimeConnectStats {

    private static final int MAX_ERROR_CHARS = 240;

    private static long lastAttemptAt;
    private static String provider = "";
    private static String mic = "";
    private static String route = "";
    private static String outcome = "";
    private static long connectMs;
    private static long setupMs;
    private static String error = "";

    private RealtimeConnectStats() {}

    public static synchronized void record(String provider, String mic, String route,
            String outcome, long connectMs, long setupMs, String error) {
        RealtimeConnectStats.lastAttemptAt = System.currentTimeMillis();
        RealtimeConnectStats.provider = provider == null ? "" : provider;
        RealtimeConnectStats.mic = mic == null ? "" : mic;
        RealtimeConnectStats.route = route == null ? "" : route;
        RealtimeConnectStats.outcome = outcome == null ? "" : outcome;
        RealtimeConnectStats.connectMs = connectMs;
        RealtimeConnectStats.setupMs = setupMs;
        RealtimeConnectStats.error = boundedError(error);
    }

    /** {@code lastAttemptAt} is always present (0 before any attempt); the rest only afterwards. */
    public static synchronized JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("lastAttemptAt", lastAttemptAt);
            if (lastAttemptAt > 0) {
                json.put("provider", provider);
                json.put("mic", mic);
                json.put("route", route);
                json.put("outcome", outcome);
                json.put("connectMs", connectMs);
                json.put("setupMs", setupMs);
                json.put("error", error);
            }
        } catch (Exception ignored) {
        }
        return json;
    }

    public static synchronized void clearForTest() {
        lastAttemptAt = 0;
        provider = "";
        mic = "";
        route = "";
        outcome = "";
        connectMs = 0;
        setupMs = 0;
        error = "";
    }

    private static String boundedError(String error) {
        String value = error == null ? "" : error.replace('\r', ' ').replace('\n', ' ').trim();
        return value.length() > MAX_ERROR_CHARS ? value.substring(0, MAX_ERROR_CHARS) : value;
    }
}
