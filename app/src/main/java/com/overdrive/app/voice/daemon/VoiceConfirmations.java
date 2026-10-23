package com.overdrive.app.voice.daemon;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * One-time spoken confirmations for risky voice intents (I21): the first request gets a token and a
 * question, and only a second request for the same intent, slots and session that carries that token
 * runs it, once, within {@link #TTL_MS}. Pure Java so the rules are JVM-testable.
 */
public final class VoiceConfirmations {

    static final long TTL_MS = 30_000;
    private static final int MAX_PENDING = 8;
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Pending> PENDING = new LinkedHashMap<>();

    private static final class Pending {
        final String key;
        final long expiresAtNs;

        Pending(String key, long expiresAtNs) {
            this.key = key;
            this.expiresAtNs = expiresAtNs;
        }
    }

    private VoiceConfirmations() {
    }

    /** The utterance id's session part: the hands-free bridge and the engine send "{session}#{n}". */
    static String sessionOf(String utteranceId) {
        if (utteranceId == null) return "";
        int hash = utteranceId.indexOf('#');
        return hash < 0 ? utteranceId : utteranceId.substring(0, hash);
    }

    /** Intent and slots in a stable order; the token itself is never part of the key. */
    static String keyOf(String session, String intent, JSONObject slots) {
        TreeMap<String, String> sorted = new TreeMap<>();
        if (slots != null) {
            Iterator<String> names = slots.keys();
            while (names.hasNext()) {
                String name = names.next();
                sorted.put(name, String.valueOf(slots.opt(name)));
            }
        }
        return session + "|" + intent + "|" + sorted;
    }

    /** Issues a fresh token for this request, replacing any earlier one for the same key. */
    static String issue(String session, String intent, JSONObject slots) {
        return issue(session, intent, slots, System.nanoTime());
    }

    static String issue(String session, String intent, JSONObject slots, long nowNs) {
        String key = keyOf(session, intent, slots);
        synchronized (PENDING) {
            prune(nowNs);
            PENDING.values().removeIf(p -> p.key.equals(key));
            while (PENDING.size() >= MAX_PENDING) {
                Iterator<String> oldest = PENDING.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            String token = newToken();
            PENDING.put(token, new Pending(key, nowNs + TTL_MS * 1_000_000L));
            return token;
        }
    }

    /** True once: the token exists, has not expired and was issued for exactly this request. */
    static boolean consume(String token, String session, String intent, JSONObject slots) {
        return consume(token, session, intent, slots, System.nanoTime());
    }

    static boolean consume(String token, String session, String intent, JSONObject slots, long nowNs) {
        if (token == null || token.isEmpty()) return false;
        String key = keyOf(session, intent, slots);
        synchronized (PENDING) {
            prune(nowNs);
            Pending pending = PENDING.get(token.trim().toUpperCase(java.util.Locale.ROOT));
            if (pending == null || !pending.key.equals(key)) return false;
            PENDING.remove(token.trim().toUpperCase(java.util.Locale.ROOT));
            return true;
        }
    }

    private static void prune(long nowNs) {
        PENDING.values().removeIf(p -> nowNs - p.expiresAtNs > 0);
    }

    private static String newToken() {
        StringBuilder out = new StringBuilder(6);
        for (int i = 0; i < 6; i++) out.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        return out.toString();
    }
}
