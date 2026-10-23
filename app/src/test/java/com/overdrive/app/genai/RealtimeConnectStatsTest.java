package com.overdrive.app.genai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RealtimeConnectStatsTest {

    @Before
    public void clear() {
        RealtimeConnectStats.clearForTest();
    }

    @After
    public void clearAgain() {
        RealtimeConnectStats.clearForTest();
    }

    @Test
    public void emptyBeforeAnyAttempt() {
        JSONObject json = RealtimeConnectStats.toJson();
        assertEquals(0L, json.optLong("lastAttemptAt"));
        assertFalse(json.has("outcome"));
        assertFalse(json.has("route"));
    }

    @Test
    public void recordRoundTrips() {
        long before = System.currentTimeMillis();
        RealtimeConnectStats.record("gemini", "far", "http-proxy 127.0.0.1:8119", "connect_timeout",
                5_003, 0, "no answer\r\nfrom route");
        JSONObject json = RealtimeConnectStats.toJson();
        assertTrue(json.optLong("lastAttemptAt") >= before);
        assertEquals("gemini", json.optString("provider"));
        assertEquals("far", json.optString("mic"));
        assertEquals("http-proxy 127.0.0.1:8119", json.optString("route"));
        assertEquals("connect_timeout", json.optString("outcome"));
        assertEquals(5_003L, json.optLong("connectMs"));
        assertEquals(0L, json.optLong("setupMs"));
        assertEquals("no answer  from route", json.optString("error"));
    }

    @Test
    public void errorIsTruncatedTo240Chars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append('e');
        RealtimeConnectStats.record("openai", "near", "direct", "setup_failed", 120, 0, sb.toString());
        assertEquals(240, RealtimeConnectStats.toJson().optString("error").length());
        RealtimeConnectStats.record("openai", "near", "direct", "ok", 120, 400, null);
        assertEquals("", RealtimeConnectStats.toJson().optString("error"));
    }

    @Test
    public void clearForTestResetsEverything() {
        RealtimeConnectStats.record("openai", "far", "direct", "ok", 1, 2, "x");
        RealtimeConnectStats.clearForTest();
        JSONObject json = RealtimeConnectStats.toJson();
        assertEquals(0L, json.optLong("lastAttemptAt"));
        assertFalse(json.has("provider"));
        assertFalse(json.has("error"));
    }

    @Test
    public void outcomesAreStoredVerbatim() {
        for (String outcome : new String[] {"ok", "replaced", "connect_timeout", "busy", "client_closed", "ended"}) {
            RealtimeConnectStats.record("openai", "far", "direct", outcome, 0, 0, "");
            assertEquals(outcome, RealtimeConnectStats.toJson().optString("outcome"));
        }
    }
}
