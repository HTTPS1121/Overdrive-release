package com.overdrive.app.byd.dilink5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.DiLink5LockRail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Stage 0 bridge relay of the producer lock diagnostic ({@code lockDiag}) and its daemon echo. */
public class Dilink5TelemetryBridgeLockReadingsTest {

    @Test
    public void bridgeSanitizerStripsLineBreaksAndTruncates() throws Exception {
        // The bridge's own statics touch SystemClock (not mocked on the JVM), so the pure nested
        // sanitizer is exercised directly and the bridge's delegation is pinned by source.
        assertNull(Dilink5TelemetryBridge.LockDiagSanitizer.sanitize(null));
        assertEquals("DI5 lock rails: a b",
                Dilink5TelemetryBridge.LockDiagSanitizer.sanitize(" DI5 lock rails: a\r\nb \n"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 250; i++) sb.append('x');
        String truncated = Dilink5TelemetryBridge.LockDiagSanitizer.sanitize(sb.toString());
        assertEquals(200, truncated.length());
        assertEquals(200, Dilink5TelemetryBridge.LockDiagSanitizer.MAX_CHARS);
        String bridge = read(
                "app/src/main/java/com/overdrive/app/byd/dilink5/Dilink5TelemetryBridge.java");
        assertTrue(bridge.contains("    static String sanitizeLockDiag(String lockDiag) {\n"
                + "        return LockDiagSanitizer.sanitize(lockDiag);\n"
                + "    }"));
    }

    @Test
    public void collectorSanitizerAcceptsOnlyStrings() {
        assertNull(BydDataCollector.sanitizeDiLink5LockDiag(123));
        assertNull(BydDataCollector.sanitizeDiLink5LockDiag(null));
        assertNull(BydDataCollector.sanitizeDiLink5LockDiag("  \r\n "));
        assertEquals("a b", BydDataCollector.sanitizeDiLink5LockDiag(" a\r\nb "));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append('y');
        assertEquals(200, BydDataCollector.sanitizeDiLink5LockDiag(sb.toString()).length());
    }

    @Test
    public void eightArgOverloadDelegatesWithNullAndLockDiagIsAdditive() throws Exception {
        String bridge = read(
                "app/src/main/java/com/overdrive/app/byd/dilink5/Dilink5TelemetryBridge.java");
        int eightArg = bridge.indexOf(
                "            int[] doorStates,\n"
                        + "            long[] doorStatesObservedAtElapsedMs) {\n"
                        + "        publish(data, dynamicsObservedAtElapsedMs, charging, energyFeedback,\n"
                        + "                doorLockState, doorLockObservedAtElapsedMs, doorStates,\n"
                        + "                doorStatesObservedAtElapsedMs, null);");
        assertTrue(eightArg > 0);
        int nineArg = bridge.indexOf(
                "            long[] doorStatesObservedAtElapsedMs,\n"
                        + "            String lockDiag) {");
        assertTrue(nineArg > eightArg);
        // Legacy pair unchanged and written before the additive key.
        int doorLock = bridge.indexOf("request.put(\"doorLockState\", doorLockState);", nineArg);
        int observed = bridge.indexOf(
                "request.put(\"doorLockObservedElapsedMs\", doorLockObservedAtElapsedMs);", nineArg);
        int lockDiag = bridge.indexOf(
                "request.put(\"lockDiag\", sanitizeLockDiag(lockDiag));", nineArg);
        int doorStates = bridge.indexOf("request.put(\"doorStates\"", nineArg);
        assertTrue(doorLock > nineArg && observed > doorLock && lockDiag > observed
                && doorStates > lockDiag);
        assertTrue(bridge.contains("if (lockDiag != null) {"));

        String collector = read(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        int publish = collector.indexOf(
                "com.overdrive.app.byd.dilink5.Dilink5TelemetryBridge.publish(");
        assertTrue(publish > 0);
        String call = collector.substring(publish, collector.indexOf(");", publish));
        // Item 1.5b appended the additive lockReadings array after the relayed diagnostic.
        assertTrue(call, call.endsWith(
                "doorSafety.states,\n"
                        + "                    doorSafety.observedAt,\n"
                        + "                    lastDiLink5LockDiag,\n"
                        + "                    lockReadings"));
    }

    // ---- Stage 1, item 1.5b: lockReadings schema (encode in the producer, decode in the daemon)

    private static final int INVALID = BydDataCollector.DOOR_STATE_INVALID;
    private static final int UNLOCK = BydDataCollector.DOOR_STATE_UNLOCK;
    private static final int LOCK = BydDataCollector.DOOR_STATE_LOCK;

    private static JSONObject entry(Object rail, Object state, Object raw, Object observedElapsedMs)
            throws Exception {
        JSONObject entry = new JSONObject();
        if (rail != null) entry.put("rail", rail);
        if (state != null) entry.put("state", state);
        if (raw != null) entry.put("raw", raw);
        if (observedElapsedMs != null) entry.put("observedElapsedMs", observedElapsedMs);
        return entry;
    }

    @Test
    public void encodeWritesTheTypedEntryWheneverATypedReadExistsAndTheOtaEntryOnlyWhenEnabled()
            throws Exception {
        JSONArray typedOnly = BydDataCollector.encodeDiLink5LockReadings(
                LOCK, 1, 5_000L, false, UNLOCK, 1, 4_900L);
        assertEquals(1, typedOnly.length());
        JSONObject typed = typedOnly.getJSONObject(0);
        assertEquals("typed-cabin", typed.getString("rail"));
        assertEquals(LOCK, typed.getInt("state"));
        assertEquals(1, typed.getInt("raw"));
        assertEquals(5_000L, typed.getLong("observedElapsedMs"));
        assertEquals(4, typed.length());

        JSONArray both = BydDataCollector.encodeDiLink5LockReadings(
                INVALID, -10, 5_000L, true, LOCK, 2, 4_900L);
        assertEquals(2, both.length());
        assertEquals("typed-cabin", both.getJSONObject(0).getString("rail"));
        assertEquals(INVALID, both.getJSONObject(0).getInt("state"));
        assertEquals(-10, both.getJSONObject(0).getInt("raw"));
        JSONObject ota = both.getJSONObject(1);
        assertEquals("ota", ota.getString("rail"));
        assertEquals(LOCK, ota.getInt("state"));
        assertEquals(2, ota.getInt("raw"));
        assertEquals(4_900L, ota.getLong("observedElapsedMs"));

        // OTA enabled but never read → typed only; OTA read but disabled → typed only.
        assertEquals(1, BydDataCollector.encodeDiLink5LockReadings(
                LOCK, 1, 5_000L, true, INVALID, Integer.MIN_VALUE, 0L).length());
        assertEquals(1, BydDataCollector.encodeDiLink5LockReadings(
                LOCK, 1, 5_000L, false, LOCK, 2, 4_900L).length());
        // No typed read yet, OTA enabled and read → the OTA entry alone.
        JSONArray otaOnly = BydDataCollector.encodeDiLink5LockReadings(
                INVALID, Integer.MIN_VALUE, 0L, true, UNLOCK, 1, 4_900L);
        assertEquals(1, otaOnly.length());
        assertEquals("ota", otaOnly.getJSONObject(0).getString("rail"));
        // Nothing to say → null (the daemon then uses the legacy pair exactly as before).
        assertNull(BydDataCollector.encodeDiLink5LockReadings(
                INVALID, Integer.MIN_VALUE, 0L, false, INVALID, Integer.MIN_VALUE, 0L));
        assertNull(BydDataCollector.encodeDiLink5LockReadings(
                LOCK, 1, 0L, true, LOCK, 2, 0L));
    }

    @Test
    public void decodeAcceptsWellFormedEntriesOfEveryRail() throws Exception {
        JSONArray array = new JSONArray()
                .put(entry("typed-cabin", LOCK, 1, 5_000L))
                .put(entry("ota", UNLOCK, 1, 4_900L))
                .put(entry("carsvc", INVALID, 3, 4_800L));
        java.util.List<BydDataCollector.DiLink5LockReading> readings =
                BydDataCollector.decodeDiLink5LockReadings(array);
        assertEquals(3, readings.size());
        assertEquals(DiLink5LockRail.TYPED_CABIN, readings.get(0).rail);
        assertEquals(LOCK, readings.get(0).state);
        assertEquals(1, readings.get(0).raw);
        assertEquals(5_000L, readings.get(0).observedElapsedMs);
        assertEquals(DiLink5LockRail.OTA, readings.get(1).rail);
        assertEquals(UNLOCK, readings.get(1).state);
        assertEquals(DiLink5LockRail.CARSVC, readings.get(2).rail);
        assertEquals(INVALID, readings.get(2).state);
        assertEquals(3, readings.get(2).raw);
        // Integral doubles are exact integers too (JSON transports do that); fractions are not.
        JSONArray doubles = new JSONArray().put(entry("ota", 2.0, -1.0, 4_900.0));
        assertEquals(LOCK, BydDataCollector.decodeDiLink5LockReadings(doubles).get(0).state);
        assertEquals(-1, BydDataCollector.decodeDiLink5LockReadings(doubles).get(0).raw);
    }

    @Test
    public void decodeDropsOnlyTheMalformedEntry() throws Exception {
        JSONObject good = entry("typed-cabin", LOCK, 1, 5_000L);
        Object[][] malformed = {
                {"cloud", LOCK, 1, 5_000L},           // unknown rail (structural cloud exclusion)
                {null, LOCK, 1, 5_000L},              // missing rail
                {7, LOCK, 1, 5_000L},                 // non-string rail
                {"ota", 3, 1, 5_000L},                // state out of {0,1,2}
                {"ota", -1, 1, 5_000L},
                {"ota", "2", 1, 5_000L},              // state as a string
                {"ota", 1.5, 1, 5_000L},              // state not an exact integer
                {"ota", null, 1, 5_000L},             // missing state
                {"ota", LOCK, 1.5, 5_000L},           // non-integer raw
                {"ota", LOCK, "1", 5_000L},
                {"ota", LOCK, null, 5_000L},          // missing raw
                {"ota", LOCK, 1L << 40, 5_000L},      // raw outside int
                {"ota", LOCK, 1, null},               // missing observedElapsedMs
                {"ota", LOCK, 1, 0L},                 // non-positive observedElapsedMs
                {"ota", LOCK, 1, -5L},
                {"ota", LOCK, 1, 5_000.5},            // fractional time
                {"ota", LOCK, 1, "5000"},
        };
        for (Object[] row : malformed) {
            JSONArray array = new JSONArray()
                    .put(good)
                    .put(entry(row[0], row[1], row[2], row[3]));
            java.util.List<BydDataCollector.DiLink5LockReading> readings =
                    BydDataCollector.decodeDiLink5LockReadings(array);
            assertEquals(java.util.Arrays.toString(row), 1, readings.size());
            assertEquals(DiLink5LockRail.TYPED_CABIN, readings.get(0).rail);
            // Order independent: the malformed entry first is dropped the same way.
            JSONArray reversed = new JSONArray()
                    .put(entry(row[0], row[1], row[2], row[3]))
                    .put(good);
            assertEquals(1, BydDataCollector.decodeDiLink5LockReadings(reversed).size());
        }
        // Non-object entries are malformed entries too.
        JSONArray mixed = new JSONArray().put("typed-cabin").put(42).put(JSONObject.NULL).put(good);
        assertEquals(1, BydDataCollector.decodeDiLink5LockReadings(mixed).size());
    }

    @Test
    public void whollyUnusableArraysDecodeToNullSoTheLegacyPairIsUsed() throws Exception {
        assertNull(BydDataCollector.decodeDiLink5LockReadings(null));
        assertNull(BydDataCollector.decodeDiLink5LockReadings(new JSONArray()));
        assertNull(BydDataCollector.decodeDiLink5LockReadings(new JSONArray()
                .put(entry("cloud", LOCK, 1, 5_000L))
                .put(entry("ota", 9, 1, 5_000L))
                .put("garbage")));
    }

    @Test
    public void decodeConsidersAtMostTheFirstFourEntries() throws Exception {
        JSONArray five = new JSONArray();
        for (int i = 0; i < 5; i++) five.put(entry("ota", LOCK, 2, 1_000L + i));
        java.util.List<BydDataCollector.DiLink5LockReading> readings =
                BydDataCollector.decodeDiLink5LockReadings(five);
        assertEquals(4, readings.size());
        assertEquals(1_003L, readings.get(3).observedElapsedMs);
        // A malformed entry inside the first four is dropped; the fifth is still not considered.
        JSONArray fiveWithBad = new JSONArray()
                .put(entry("cloud", LOCK, 2, 1_000L))
                .put(entry("ota", LOCK, 2, 1_001L))
                .put(entry("ota", LOCK, 2, 1_002L))
                .put(entry("ota", LOCK, 2, 1_003L))
                .put(entry("typed-cabin", LOCK, 1, 1_004L));
        readings = BydDataCollector.decodeDiLink5LockReadings(fiveWithBad);
        assertEquals(3, readings.size());
        assertEquals(DiLink5LockRail.OTA, readings.get(2).rail);
    }

    @Test
    public void tenArgOverloadWritesLockReadingsAfterTheLegacyPairAndTheNineArgDelegatesNull()
            throws Exception {
        String bridge = read(
                "app/src/main/java/com/overdrive/app/byd/dilink5/Dilink5TelemetryBridge.java");
        int nineArg = bridge.indexOf(
                "            long[] doorStatesObservedAtElapsedMs,\n"
                        + "            String lockDiag) {\n"
                        + "        publish(data, dynamicsObservedAtElapsedMs, charging, energyFeedback,\n"
                        + "                doorLockState, doorLockObservedAtElapsedMs, doorStates,\n"
                        + "                doorStatesObservedAtElapsedMs, lockDiag, null);");
        assertTrue(nineArg > 0);
        int tenArg = bridge.indexOf(
                "            long[] doorStatesObservedAtElapsedMs,\n"
                        + "            String lockDiag,\n"
                        + "            JSONArray lockReadings) {");
        assertTrue(tenArg > nineArg);
        int doorLock = bridge.indexOf("request.put(\"doorLockState\", doorLockState);", tenArg);
        int observed = bridge.indexOf(
                "request.put(\"doorLockObservedElapsedMs\", doorLockObservedAtElapsedMs);", tenArg);
        int lockDiag = bridge.indexOf("request.put(\"lockDiag\", sanitizeLockDiag(lockDiag));", tenArg);
        int lockReadings = bridge.indexOf("request.put(\"lockReadings\", lockReadings);", tenArg);
        int doorStates = bridge.indexOf("request.put(\"doorStates\"", tenArg);
        assertTrue(doorLock > tenArg && observed > doorLock && lockDiag > observed
                && lockReadings > lockDiag && doorStates > lockReadings);
        assertTrue(bridge.contains("if (lockReadings != null && lockReadings.length() > 0) {"));
        // Protocol unchanged, signed as a whole.
        assertTrue(bridge.contains("request.put(\"protocol\", 1);"));
        assertTrue(bridge.contains("RemoteDevViewBridgeAuth.sign(request)"));
        assertEquals(1, count(bridge, "request.put(\"lockReadings\""));

        String collector = read(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        int publish = collector.indexOf(
                "    private void publishAutomationSnapshot(BydVehicleData published) {");
        String body = collector.substring(publish, collector.indexOf("\n    }\n", publish));
        assertTrue(body.contains(
                "lockReadings = encodeDiLink5LockReadings(lockState, lockRaw, lockReadAt,\n"
                        + "                        diLink5ProducerEnabledRails.contains(DiLink5LockRail.OTA),\n"
                        + "                        otaState, otaRaw, otaReadAt);"));
        // The legacy pair in the call is the SAME snapshot the array was built from.
        assertTrue(body.contains(
                "                    lockState,\n"
                        + "                    lockReadAt,\n"));
        assertTrue(body.contains("} catch (Throwable t) {"));
    }

    @Test
    public void serverDecodesLockReadingsIntoTheTwelveArgSingleIngress() throws Exception {
        String server = read(
                "app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java");
        int telemetry = server.indexOf("case \"DILINK5_TELEMETRY\": {");
        int accept = server.indexOf(".acceptDiLink5Telemetry(", telemetry);
        int doorReadTimes = server.indexOf("doorReadTimes,", accept);
        int decode = server.indexOf(
                ".decodeDiLink5LockReadings(\n"
                        + "                                                        request.optJSONArray(\"lockReadings\")));",
                accept);
        int caseEnd = server.indexOf("case \"DILINK5_VEHICLE_EVENT\": {", telemetry);
        assertTrue(telemetry > 0 && accept > telemetry && doorReadTimes > accept
                && decode > doorReadTimes && caseEnd > decode);
        // Exactly one ingress call in the case, and no separate lock-readings ingress anywhere.
        assertEquals(1, count(server.substring(telemetry, caseEnd), ".acceptDiLink5Telemetry("));
        assertFalse(server.contains("acceptDiLink5LockReadings"));
        String collector = read(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        assertFalse(collector.contains("acceptDiLink5LockReadings"));
        assertEquals(3, count(collector, "isNewerDiLink5Message("));
        // NIT-3: the producer's OTA read is enqueued from the fast loop every 8th tick, next to the
        // 4th-tick branch, and only the pure enablement predicate lets it through.
        int fourth = collector.indexOf("if ((currentTick & 0x3L) == 0L) {");
        int eighth = collector.indexOf("if ((currentTick & 0x7L) == 0L) submitDiLink5OtaLockRead();");
        assertTrue(fourth > 0 && eighth > fourth);
        assertEquals(1, count(collector, "(currentTick & 0x7L) == 0L"));
        // The decode never throws into the ingress: its body is fenced per entry.
        int decodeImpl = collector.indexOf(
                "    static java.util.List<DiLink5LockReading> decodeDiLink5LockReadings(\n"
                        + "            org.json.JSONArray array, int[] droppedOut) {");
        assertTrue(decodeImpl > 0);
        String decodeBody = collector.substring(decodeImpl, collector.indexOf("\n    }\n", decodeImpl));
        assertTrue(decodeBody.contains("} catch (Throwable malformed) {"));
        assertTrue(decodeBody.contains("DILINK5_LOCK_READINGS_MAX_ENTRIES"));
        assertTrue(collector.contains("static final int DILINK5_LOCK_READINGS_MAX_ENTRIES = 4;"));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    public void serverNotesTheProducerDiagBeforeAcceptingTelemetry() throws Exception {
        String server = read(
                "app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java");
        int telemetry = server.indexOf("case \"DILINK5_TELEMETRY\": {");
        assertTrue(telemetry > 0);
        int note = server.indexOf(
                ".noteDiLink5ProducerLockDiag(request.opt(\"lockDiag\"));", telemetry);
        int accept = server.indexOf(".acceptDiLink5Telemetry(", telemetry);
        assertTrue(note > telemetry && accept > note);
        String between = server.substring(telemetry, accept);
        assertTrue(between.contains("try {\n"
                + "                            com.overdrive.app.byd.BydDataCollector.getInstance()\n"
                + "                                    .noteDiLink5ProducerLockDiag("));
        assertTrue(between.contains("} catch (Throwable ignored) {"));

        String collector = read(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        int method = collector.indexOf("    public void noteDiLink5ProducerLockDiag(Object lockDiag) {");
        assertTrue(method > 0);
        String body = collector.substring(method, collector.indexOf("\n    }\n", method));
        assertTrue(body.contains("if (!isDiLink5DaemonProcess()) return;"));
        assertTrue(body.contains("sanitizeDiLink5LockDiag(lockDiag)"));
        assertTrue(body.contains("logger.info(\"DI5 producer lock diag: \" + text);"));
        assertTrue(body.contains("60_000L"));
    }

    private static String read(String relative) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path)) {
            path = Paths.get(System.getProperty("user.dir")).getParent()
                    .resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
