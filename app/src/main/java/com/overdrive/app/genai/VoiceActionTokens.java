package com.overdrive.app.genai;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * One-time proposal tokens minted by the daemon for far-field voice sessions.
 *
 * <p>The hands-free client has no screen to confirm on, so /api/genai/action/execute must be
 * able to tell "the daemon itself just proposed exactly this action to the live far-field
 * session" from any other caller. A token binds a proposal to its session and to an action
 * fingerprint; it is single-use and short-lived, and every token of a session dies with it.
 * Pure Java (no Android, injectable clock) so the store is JVM-testable.
 */
public final class VoiceActionTokens {

    public enum Code { OK, MISSING, UNKNOWN, EXPIRED, CONSUMED, FOREIGN, MISMATCH }

    /** What a token was minted for; exposed so the result path can answer the model's call. */
    public static final class Entry {
        public final String sessionId;
        public final String fingerprint;
        public final String callId;
        public final long expiresAt;
        boolean consumed;

        Entry(String sessionId, String fingerprint, String callId, long expiresAt) {
            this.sessionId = sessionId;
            this.fingerprint = fingerprint;
            this.callId = callId;
            this.expiresAt = expiresAt;
        }
    }

    public static final class Result {
        public final Code code;
        public final Entry entry;

        Result(Code code, Entry entry) {
            this.code = code;
            this.entry = entry;
        }

        public boolean ok() {
            return code == Code.OK;
        }
    }

    static final long TTL_MS = 60_000;
    static final int PER_SESSION_CAP = 16;
    static final int GLOBAL_CAP = 64;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object LOCK = new Object();
    // Insertion ordered so eviction always drops the oldest token first.
    private static final LinkedHashMap<String, Entry> TOKENS = new LinkedHashMap<>();
    private static LongSupplier clock = System::currentTimeMillis;

    private VoiceActionTokens() {}

    /** Test seam: replaces the wall clock. */
    static void setClockForTest(LongSupplier supplier) {
        synchronized (LOCK) {
            clock = supplier == null ? System::currentTimeMillis : supplier;
        }
    }

    /** Test seam: forgets every token. */
    static void clearForTest() {
        synchronized (LOCK) {
            TOKENS.clear();
        }
    }

    /** Mints a token for one proposal; the oldest tokens of a crowded session (or store) go first. */
    public static String mint(String sessionId, JSONObject action, String callId) {
        String session = sessionId == null ? "" : sessionId;
        String fingerprint = fingerprint(action);
        synchronized (LOCK) {
            long now = clock.getAsLong();
            purgeLocked(now);
            ArrayDeque<String> ownTokens = new ArrayDeque<>();
            for (Map.Entry<String, Entry> e : TOKENS.entrySet()) {
                if (session.equals(e.getValue().sessionId)) ownTokens.addLast(e.getKey());
            }
            while (ownTokens.size() >= PER_SESSION_CAP) TOKENS.remove(ownTokens.pollFirst());
            while (TOKENS.size() >= GLOBAL_CAP) {
                Iterator<String> oldest = TOKENS.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            String token;
            do {
                token = randomHex();
            } while (TOKENS.containsKey(token));
            TOKENS.put(token, new Entry(session, fingerprint,
                    callId == null ? "" : callId, now + TTL_MS));
            return token;
        }
    }

    /**
     * Validates and burns a token. Expired, consumed and foreign tokens are reported as such
     * rather than silently unknown so the client can speak a truthful reason.
     */
    public static Result consume(String token, JSONObject action, String activeSessionId) {
        if (token == null || token.isEmpty()) return new Result(Code.MISSING, null);
        synchronized (LOCK) {
            Entry entry = TOKENS.get(token);
            if (entry == null) return new Result(Code.UNKNOWN, null);
            if (clock.getAsLong() >= entry.expiresAt) {
                TOKENS.remove(token);
                return new Result(Code.EXPIRED, entry);
            }
            if (entry.consumed) return new Result(Code.CONSUMED, entry);
            if (activeSessionId == null || !entry.sessionId.equals(activeSessionId)) {
                return new Result(Code.FOREIGN, entry);
            }
            if (!entry.fingerprint.equals(fingerprint(action))) {
                return new Result(Code.MISMATCH, entry);
            }
            entry.consumed = true;
            return new Result(Code.OK, entry);
        }
    }

    /** The provider call id a (live or consumed) token was minted for, or "" when unknown. */
    public static String callIdFor(String token) {
        if (token == null || token.isEmpty()) return "";
        synchronized (LOCK) {
            Entry entry = TOKENS.get(token);
            return entry == null ? "" : entry.callId;
        }
    }

    /** Drops every token of a session; called when the realtime session closes. */
    public static void revokeSession(String sessionId) {
        if (sessionId == null) return;
        synchronized (LOCK) {
            TOKENS.values().removeIf(entry -> sessionId.equals(entry.sessionId));
        }
    }

    /** Stable identity of an action: the fields that decide what the vehicle actually does. */
    static String fingerprint(JSONObject action) {
        if (action == null) return "";
        double celsius = action.optDouble("temperatureC", Double.NaN);
        return action.optString("type", "")
                + "|" + action.optString("automationId", "")
                + "|" + action.optInt("zone", -1)
                + "|" + (Double.isNaN(celsius) ? "" : String.valueOf(celsius))
                + "|" + action.optString("operation", "");
    }

    /** Test helper: how many tokens are live for a session. */
    static int liveCount(String sessionId) {
        synchronized (LOCK) {
            int n = 0;
            for (Entry entry : TOKENS.values()) {
                if (sessionId.equals(entry.sessionId)) n++;
            }
            return n;
        }
    }

    /** Oldest-first order of the live tokens; tests use it to check eviction. */
    static List<String> liveTokens() {
        synchronized (LOCK) {
            return new ArrayList<>(TOKENS.keySet());
        }
    }

    private static void purgeLocked(long now) {
        TOKENS.values().removeIf(entry -> now >= entry.expiresAt);
    }

    private static String randomHex() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
