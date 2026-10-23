package com.overdrive.app.genai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/** Daemon-minted one-time proposal tokens: single use, short-lived, bound to session + action. */
public class VoiceActionTokensTest {

    private static final String SESSION = "session-a";
    private long now;

    @Before
    public void setUp() {
        now = 1_000_000L;
        VoiceActionTokens.clearForTest();
        VoiceActionTokens.setClockForTest(() -> now);
    }

    @After
    public void tearDown() {
        VoiceActionTokens.clearForTest();
        VoiceActionTokens.setClockForTest(null);
    }

    @Test
    public void mintThenConsumeOkThenConsumed() throws Exception {
        JSONObject action = climate(22, 0);
        String token = VoiceActionTokens.mint(SESSION, action, "call-1");
        assertNotNull(token);
        assertTrue(token.matches("[0-9a-f]{32}"));

        VoiceActionTokens.Result first = VoiceActionTokens.consume(token, action, SESSION);
        assertEquals(VoiceActionTokens.Code.OK, first.code);
        assertTrue(first.ok());
        assertEquals("call-1", first.entry.callId);
        assertEquals(SESSION, first.entry.sessionId);

        VoiceActionTokens.Result second = VoiceActionTokens.consume(token, action, SESSION);
        assertEquals(VoiceActionTokens.Code.CONSUMED, second.code);
        assertFalse(second.ok());
    }

    @Test
    public void expiresAfterTtl() throws Exception {
        JSONObject action = climate(22, 0);
        String token = VoiceActionTokens.mint(SESSION, action, "call-1");
        now += 61_000;
        assertEquals(VoiceActionTokens.Code.EXPIRED,
                VoiceActionTokens.consume(token, action, SESSION).code);
        // An expired token is forgotten, not retried.
        assertEquals(VoiceActionTokens.Code.UNKNOWN,
                VoiceActionTokens.consume(token, action, SESSION).code);
    }

    @Test
    public void missingAndUnknownTokens() throws Exception {
        JSONObject action = climate(22, 0);
        assertEquals(VoiceActionTokens.Code.MISSING,
                VoiceActionTokens.consume("", action, SESSION).code);
        assertEquals(VoiceActionTokens.Code.MISSING,
                VoiceActionTokens.consume(null, action, SESSION).code);
        assertEquals(VoiceActionTokens.Code.UNKNOWN,
                VoiceActionTokens.consume("0123456789abcdef0123456789abcdef", action,
                        SESSION).code);
    }

    @Test
    public void foreignSessionIsRefusedWithoutBurningTheToken() throws Exception {
        JSONObject action = climate(22, 0);
        String token = VoiceActionTokens.mint(SESSION, action, "call-1");
        assertEquals(VoiceActionTokens.Code.FOREIGN,
                VoiceActionTokens.consume(token, action, "session-b").code);
        assertEquals(VoiceActionTokens.Code.FOREIGN,
                VoiceActionTokens.consume(token, action, null).code);
        assertEquals(VoiceActionTokens.Code.OK,
                VoiceActionTokens.consume(token, action, SESSION).code);
    }

    @Test
    public void differentActionIsMismatch() throws Exception {
        String token = VoiceActionTokens.mint(SESSION, climate(22, 0), "call-1");
        assertEquals(VoiceActionTokens.Code.MISMATCH,
                VoiceActionTokens.consume(token, climate(24, 0), SESSION).code);
        assertEquals(VoiceActionTokens.Code.MISMATCH,
                VoiceActionTokens.consume(token, climate(22, 1), SESSION).code);
        assertEquals(VoiceActionTokens.Code.MISMATCH,
                VoiceActionTokens.consume(token, sunshade("open"), SESSION).code);
        assertEquals(VoiceActionTokens.Code.MISMATCH,
                VoiceActionTokens.consume(token, null, SESSION).code);
        // Mismatches do not burn the token.
        assertEquals(VoiceActionTokens.Code.OK,
                VoiceActionTokens.consume(token, climate(22, 0), SESSION).code);
    }

    @Test
    public void automationFingerprintUsesId() throws Exception {
        String token = VoiceActionTokens.mint(SESSION, automation("abc"), "call-1");
        assertEquals(VoiceActionTokens.Code.MISMATCH,
                VoiceActionTokens.consume(token, automation("xyz"), SESSION).code);
        assertEquals(VoiceActionTokens.Code.OK,
                VoiceActionTokens.consume(token, automation("abc"), SESSION).code);
    }

    @Test
    public void revokeSessionForgetsItsTokensOnly() throws Exception {
        JSONObject action = climate(22, 0);
        String mine = VoiceActionTokens.mint(SESSION, action, "call-1");
        String other = VoiceActionTokens.mint("session-b", action, "call-2");
        VoiceActionTokens.revokeSession(SESSION);
        assertEquals(VoiceActionTokens.Code.UNKNOWN,
                VoiceActionTokens.consume(mine, action, SESSION).code);
        assertEquals(VoiceActionTokens.Code.OK,
                VoiceActionTokens.consume(other, action, "session-b").code);
    }

    @Test
    public void callIdLookup() throws Exception {
        JSONObject action = climate(22, 0);
        String token = VoiceActionTokens.mint(SESSION, action, "call-9");
        assertEquals("call-9", VoiceActionTokens.callIdFor(token));
        VoiceActionTokens.consume(token, action, SESSION);
        // Still resolvable after consumption so the result can answer the model's call.
        assertEquals("call-9", VoiceActionTokens.callIdFor(token));
        assertEquals("", VoiceActionTokens.callIdFor("nope"));
        assertEquals("", VoiceActionTokens.callIdFor(null));
    }

    @Test
    public void perSessionCapEvictsOldest() throws Exception {
        JSONObject action = climate(22, 0);
        String oldest = VoiceActionTokens.mint(SESSION, action, "c0");
        for (int i = 1; i < VoiceActionTokens.PER_SESSION_CAP; i++) {
            VoiceActionTokens.mint(SESSION, action, "c" + i);
        }
        assertEquals(VoiceActionTokens.PER_SESSION_CAP, VoiceActionTokens.liveCount(SESSION));
        String newest = VoiceActionTokens.mint(SESSION, action, "newest");
        assertEquals(VoiceActionTokens.PER_SESSION_CAP, VoiceActionTokens.liveCount(SESSION));
        assertEquals(VoiceActionTokens.Code.UNKNOWN,
                VoiceActionTokens.consume(oldest, action, SESSION).code);
        assertEquals(VoiceActionTokens.Code.OK,
                VoiceActionTokens.consume(newest, action, SESSION).code);
    }

    @Test
    public void globalCapEvictsOldestAcrossSessions() throws Exception {
        JSONObject action = climate(22, 0);
        String oldest = VoiceActionTokens.mint("s0", action, "c");
        for (int i = 1; i < VoiceActionTokens.GLOBAL_CAP; i++) {
            VoiceActionTokens.mint("s" + i, action, "c");
        }
        List<String> before = VoiceActionTokens.liveTokens();
        assertEquals(VoiceActionTokens.GLOBAL_CAP, before.size());
        assertEquals(oldest, before.get(0));
        String newest = VoiceActionTokens.mint("s-new", action, "c");
        List<String> after = VoiceActionTokens.liveTokens();
        assertEquals(VoiceActionTokens.GLOBAL_CAP, after.size());
        assertFalse(after.contains(oldest));
        assertEquals(newest, after.get(after.size() - 1));
    }

    private static JSONObject climate(double celsius, int zone) throws Exception {
        return new JSONObject().put("type", "climate_temperature")
                .put("temperatureC", celsius).put("zone", zone);
    }

    private static JSONObject sunshade(String operation) throws Exception {
        return new JSONObject().put("type", "sunshade").put("operation", operation);
    }

    private static JSONObject automation(String id) throws Exception {
        return new JSONObject().put("type", "run_automation").put("automationId", id)
                .put("automationName", "Name " + id);
    }
}
