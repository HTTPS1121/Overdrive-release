package com.overdrive.app.server;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;
/**
 * 18 Oct multi-phone fix, daemon wiring (M1 inputs, M2 retention/adoption, M4 texts). The policy and
 * evaluator behaviour is covered by MultiPhoneProximityPolicyAbsentPhoneTest,
 * RemoteAccessPhoneStateRetentionReplayTest and ProximityEvaluatorFirstConnectionDiagnosticTest;
 * {@code RemoteAccessApiHandler} cannot run on the JVM, so its wiring is pinned by source, in the
 * style of the other handler tests. The frozen regions of the file are pinned here too.
 */
public class RemoteAccessApiHandlerMultiPhoneTest {
    private static final String HANDLER = "app/src/main/java/com/overdrive/app/server/RemoteAccessApiHandler.java";
    private static final String RESET = "    private static void reset(JSONObject next) throws Exception {";
    private static final String RELAY = "    public static void relay(String body, OutputStream out) throws Exception {";
    private static final String STATES = "    private static java.util.List<MultiPhoneProximityPolicy.Observation> phoneDepartureStates() {";
    private static final String BLOCKED = "    private static String phoneLockBlockedText() {";

    private static String method(String handler, String signature) {
        int start = handler.indexOf(signature);
        assertTrue(signature, start > 0);
        return handler.substring(start, handler.indexOf("\n    }\n", start));
    }

    private static String phoneState(String handler) {
        int start = handler.indexOf("    private static final class PhoneState {");
        assertTrue(start > 0);
        return handler.substring(start, handler.indexOf("\n    }\n", start));
    }

    /** M2: reset() keeps the PhoneState of every phone whose own entry and the shared phone rules are unchanged. */
    @Test
    public void resetRetainsUnchangedPhonesAcrossARosterChangeAndRebuildsTheRest() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String reset = method(handler, RESET);
        assertTrue(reset.contains(
                "boolean phoneRulesUnchanged = java.util.Objects.equals(presenceModeGeneration, nextModeGeneration)\n"
                + "                && MultiPhoneProximityPolicy.canRetainPhoneRules(effectiveConfig, nextEffective);"));
        assertTrue(reset.contains("if (phoneRulesUnchanged) for (PhoneState phone : PHONES.values()) {"));
        assertTrue(reset.contains(
                "JSONObject nextProfile = RemoteAccessSettings.phone(nextEffective, phone.profile.getString(\"address\"));\n"
                + "            if (MultiPhoneProximityPolicy.sameProfile(phone.profile, nextProfile))\n"
                + "                retainedPhones.put(phone.profile.getString(\"address\"), phone);"));
        // Retention is decided BEFORE config/effectiveConfig are replaced and the roster is rebuilt.
        int predicate = reset.indexOf("boolean phoneRulesUnchanged =");
        int replace = reset.indexOf("config = new JSONObject(next.toString());", predicate);
        int clear = reset.indexOf("PHONES.clear();", replace);
        int loop = reset.indexOf("PhoneState retained = retainedPhones.get(profile.getString(\"address\"));", clear);
        assertTrue(predicate > 0 && replace > predicate && clear > replace && loop > clear);
        // A retained phone opens the adoption window; a changed or new phone gets a fresh state, in roster order.
        assertTrue(reset.contains("if (retained != null) retained.adoptInstanceUntilElapsedMs = adoptUntil;"));
        assertTrue(reset.contains("retained != null ? retained : new PhoneState(profile, effectiveConfig));"));
        assertEquals(1, count(reset, "new PhoneState("));
        assertEquals(1, count(handler, "SOURCE_INSTANCE_ADOPTION_WINDOW_MS = 30_000;"));
        // The fob retention lines of 55.6/A1 are untouched.
        assertTrue(reset.contains("boolean retainKeyFob = keyFob != null && (radioOnlyChange || fobRulesUnchanged);"));
        assertTrue(reset.contains("else keyFob.adoptRevision(effectiveConfig.optLong(\"revision\"));"));
        assertTrue(reset.contains("runtime = new JSONObject().put(\"active\", false).put(\"readerRegistered\", false).put(\"radioOwned\", false);"));
    }

    /** M2: relay() adopts the restarted app instance onto a retained phone instead of resetting it. */
    @Test
    public void relayAdoptsANewSourceInstanceOntoRetainedStateOnlyInsideTheWindow() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String relay = method(handler, RELAY);
        assertTrue(relay.contains(
                "            if (!instance.equals(phone.sourceInstance)) {\n"
                + "                if (!adoptSourceInstance(phone, now)) {\n"
                + "                    phone.evaluator.reset();\n"
                + "                    phone.calibration.clear();\n"
                + "                    phone.satisfiedEpisodeId = 0;\n"
                + "                }\n"
                + "                phone.sourceInstance = instance;\n"
                + "                phone.sourceSequence = 0;\n"
                + "            }\n"
                + "            if (serial <= 0 || serial <= phone.sourceSequence)\n"
                + "                throw new IllegalArgumentException(\"Observation was already consumed\");"));
        assertEquals(1, count(handler, "adoptSourceInstance(phone, now)"));
        String adopt = method(handler, "    private static boolean adoptSourceInstance(PhoneState phone, long now) {");
        assertTrue(adopt.contains(
                "boolean adopt = phone.adoptInstanceUntilElapsedMs >= 0 && now <= phone.adoptInstanceUntilElapsedMs\n"
                + "                && !phone.sourceInstance.isEmpty();"));
        // One-shot window; the generation clock restarts and the old instance's proof is dropped either way;
        // only a non-adopted change forgets the first connection.
        assertTrue(adopt.contains("phone.adoptInstanceUntilElapsedMs = -1;"));
        assertTrue(adopt.contains("phone.generationStartedElapsedMs = now;"));
        assertTrue(adopt.contains("phone.lastProof = null;"));
        assertTrue(adopt.contains("if (!adopt) phone.firstConnectedElapsedMs = -1;"));
        assertFalse(adopt.contains("evaluator"));
        // The relay's revision check still rejects stale envelopes before any of this runs.
        int revoked = relay.indexOf("|| sample.optLong(\"revision\", -1) != settings.optLong(\"revision\")");
        int instance = relay.indexOf("if (!instance.equals(phone.sourceInstance)) {");
        assertTrue(revoked > 0 && instance > revoked);
    }

    /** M1: the daemon keeps what the evaluator loses and hands it to the policy as the new Observation fields. */
    @Test
    public void bookkeepingFeedsTheAbsentPhoneRulesFromTheRelayedNativeRead() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String state = phoneState(handler);
        for (String field : new String[] {"long generationStartedElapsedMs = -1;", "long firstConnectedElapsedMs = -1;",
                "long lastNearEvidenceElapsedMs = -1;", "long lastProofElapsedMs = -1;",
                "BluetoothConnectionEvidence lastProof;", "boolean lastProofHealthy;",
                "long adoptInstanceUntilElapsedMs = -1;"})
            assertTrue(field, state.contains(field));
        String relay = method(handler, RELAY);
        assertEquals(1, count(handler, "notePhoneObservation(phone, sample, observed, proof, healthyNativeProof, evaluated);"));
        int note = relay.indexOf("notePhoneObservation(phone, sample, observed, proof, healthyNativeProof, evaluated);");
        int log = relay.indexOf("logPhoneDiagnostic(phone, sample, evaluated, eligible, power, now);");
        int signal = relay.indexOf("phone.signal = snapshotJson(evaluated)");
        assertTrue(signal > 0 && note > signal && log > note);
        String note2 = method(handler, "    private static void notePhoneObservation(PhoneState phone, JSONObject sample, long observed,");
        // The first connection is taken from the sample's own claim (conservative for rule b), the near
        // evidence from any connected reading that is not a departure-eligible Away.
        assertTrue(note2.contains("boolean claimedConnected = sample.optBoolean(\"connected\");"));
        assertTrue(note2.contains("if (claimedConnected && phone.firstConnectedElapsedMs < 0) phone.firstConnectedElapsedMs = observed;"));
        assertTrue(note2.contains("if (claimedConnected && !(evaluated.state == ProximityEvaluator.State.AWAY && evaluated.departureEligible))\n"
                + "            phone.lastNearEvidenceElapsedMs = observed;"));
        assertTrue(note2.contains("phone.lastProofHealthy = healthyProof;"));
        // phoneDepartureStates: a positive read must be healthy, fresh, of this mode generation and agree
        // with the signal's connected flag; the radio flag may come from a connection-unknown read.
        String states = method(handler, STATES);
        assertTrue(states.contains(
                "boolean proofFresh = proof != null && generation != null && generation.equals(proof.modeGeneration)\n"
                + "                    && BluetoothProximityDecisionGuard.fresh(phone.lastProofElapsedMs, now, staleMs);"));
        assertTrue(states.contains(
                "boolean connectionKnown = proofFresh && phone.lastProofHealthy && proof.healthyAt(now, staleMs)\n"
                + "                    && (proof.state == BluetoothConnectionEvidence.State.CONNECTED) == signal.optBoolean(\"connected\");"));
        assertTrue(states.contains("boolean radioOn = proofFresh && proof.radioReadKnown && proof.radioOn;"));
        assertTrue(states.contains(
                "                    signal.optLong(\"observedAtElapsedMs\", -1), confirmedDisconnection,\n"
                + "                    connectionKnown, radioOn, phone.firstConnectedElapsedMs < 0,\n"
                + "                    phone.generationStartedElapsedMs, proofFresh ? phone.lastProofElapsedMs : -1,\n"
                + "                    phone.lastNearEvidenceElapsedMs));"));
        // The original conservative conversion of the departing phone's own evidence is still there.
        assertTrue(states.contains("state = ProximityEvaluator.State.TRANSITION;"));
        assertTrue(states.contains("boolean confirmedDisconnection = BluetoothProximityDecisionGuard.confirmedDisconnection("));
    }

    /** M4: the R4 diagnostic is one log line per flagged observation, by roster index, never by name. */
    @Test
    public void firstConnectionDiagnosticIsLoggedOncePerFlaggedObservationWithoutPhoneIdentity() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String note = method(handler, "    private static void notePhoneObservation(PhoneState phone, JSONObject sample, long observed,");
        assertTrue(note.contains("if (!evaluated.firstConnectionNoPriorAway) return;"));
        assertEquals(1, count(handler, "first_connection_no_prior_away"));
        assertTrue(note.contains("com.overdrive.app.daemon.CameraDaemon.log(\"Proximity: phone=\" + index\n"
                + "                + \" arrivalSuppressed reason=first_connection_no_prior_away state=\" + evaluated.state.name()"));
        for (String identity : new String[] {"getString(\"name\")", "optString(\"name\"", "deviceName", "getString(\"address\")", "deviceAddress"})
            assertFalse(identity, note.contains(identity));
        // The evaluator exposes the flag without touching its decisions: set only at the two Near
        // confirmations, from the arrival eligibility that already existed there.
        String evaluator = readRepositoryFile("app/src/main/java/com/overdrive/app/remoteaccess/ProximityEvaluator.java");
        assertEquals(1, count(evaluator, "return result(nowMs, event, reason, true, zone == State.NEAR && !arrivalEpisodeEligible);"));
        assertEquals(1, count(evaluator, "return result(nowMs, event, reason, true, !arrivalEpisodeEligible);"));
        assertEquals(2, count(evaluator, "arrivalEpisodeEligible = armed || config.unlockTrigger == UnlockTrigger.NEAR;"));
        assertFalse(evaluator.contains("first_connection_no_prior_away"));
    }

    /** M4: the block text names the blocking phone and why; the fob-blocking texts are unchanged. */
    @Test
    public void lockBlockTextNamesTheBlockingPhoneOnlyWhenAPhoneIsTheBlockingFactor() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String helper = method(handler, BLOCKED);
        // Same shape as A3: fob-paused text first, then the phone case, then the original generic text.
        int paused = helper.indexOf("return \"Driver door is already locked; the key fob search is paused while locked\";");
        int phone = helper.indexOf("if (phoneVetoesLock(SystemClock.elapsedRealtime()))\n"
                + "                    return blockingPhoneText(SystemClock.elapsedRealtime());", paused);
        int generic = helper.indexOf("return \"Every active Bluetooth phone must confirm Away; a nearby key prevents locking\";", phone);
        assertTrue(paused > 0 && phone > paused && generic > phone);
        assertTrue(helper.contains("catch (Throwable ignored)"));
        // The fob gate mirrors lockConsensus: a fresh fob approach/presence or a configured search that
        // does not allow locking keeps the generic text.
        String veto = method(handler, "    private static boolean phoneVetoesLock(long now) {");
        assertTrue(veto.contains("if (fobDetection && (hasFreshKeyFobApproach(now) || hasFreshKeyFobPresence(now))) return false;"));
        assertTrue(veto.contains("if (fobConfigured && !keyFobPresence.allowsLock(now)) return false;"));
        assertTrue(veto.contains("return blockingPhoneText(now) != null;"));
        String text = method(handler, "    private static String blockingPhoneText(long now) {");
        assertTrue(text.contains("MultiPhoneProximityPolicy.awayForLock(observation, now, staleMs)) continue;"));
        assertTrue(text.contains("return name + \" is near\";"));
        assertTrue(text.contains("return name + \" is connected but not confirmed away\";"));
        assertTrue(text.contains("return name + \" has not connected since \" + since + \"; bring it to the car once or disable it\";"));
        assertTrue(text.contains("return name + \" is disconnected but not confirmed away\";"));
        assertTrue(text.contains("return null;"));
        // Text selection only: no decision, record, vehicle read or state change in any of the three.
        for (String forbidden : new String[] {"recordAction(", "readDoorLockState", "refreshSettings", "invalidate(",
                "vetoFreshApproach", "VehicleCommandRouter", "evaluator.", ".signal.put(", "changed();"}) {
            assertFalse(forbidden, veto.contains(forbidden));
            assertFalse(forbidden, text.contains(forbidden));
        }
        // lockConsensus is unchanged except the 20 Oct requiresFobAway term (lockWaitsForKeyAbsence,
        // pinned in RemoteAccessSnappyLocksAssetTest).
        String consensus = method(handler, "    private static boolean lockConsensus(boolean phoneDeparture, long now) {");
        assertTrue(consensus.contains("return MultiPhoneProximityPolicy.canLock(phoneDeparture,\n"
                + "                !phoneDeparture || (fobConfigured && RemoteAccessSettings.lockWaitsForKeyAbsence(effectiveConfig)),\n"
                + "                fobConfigured && keyFobPresence.allowsLock(now), phoneDepartureStates(), now,\n"
                + "                effectiveConfig.optLong(\"staleMs\"));"));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    private static String readRepositoryFile(String relative) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path)) {
            path = Paths.get(System.getProperty("user.dir")).getParent().resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
