package com.overdrive.app.byd.assist;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Source-level contract of the opt-in DiLink 5 assist-off profile: the new routes are isolated
 * from every existing lane/CPD/ADAS path, the DMS route can never claim verification, and the
 * settings surface stays explicit per control with confirmation.
 */
public class DiLink5AssistOffContractTest {

    private static final String COLLECTOR =
            "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java";
    private static final String APPLIER =
            "app/src/main/java/com/overdrive/app/byd/assist/DiLink5AssistOffApplier.java";
    private static final String DMS_CLIENT =
            "app/src/main/java/com/overdrive/app/byd/assist/DiLink5DmsSettingsClient.java";

    @Test
    public void existingDiLink5AdasRejectionsAreUntouched() throws IOException {
        String collector = read(COLLECTOR);
        // The legacy ADAS-device setters stay refused on DiLink 5 — nothing routes them to DiPilot.
        assertTrue(collector.contains(
                "public boolean setLaneAssistMode(int mode) {\n"
                        + "        if (refuseDiLink5AdasNoOp(\"setLKSMode\")) return false;"));
        int adas = collector.indexOf("private boolean setAdasFeature(int featureId, int value) {");
        assertTrue(adas > 0);
        String adasBody = collector.substring(adas, collector.indexOf("\n    }\n", adas));
        assertTrue(adasBody.contains("if (isDiLink5Vehicle()) {"));
        assertTrue(adasBody.contains("return false;"));
        assertTrue(collector.contains(
                "public boolean setEmergencyLaneKeeping(boolean enabled) {\n"
                        + "        return setAdasFeature(BydFeatureIds.ADAS_ELKA_SWITCH_SET, enabled ? 2 : 1);"));
        // The automation/MQTT CPD path keeps its driving-evidence gate.
        int cpd = collector.indexOf("public boolean setDiLink5ChildPresenceViaDiPilot(");
        assertTrue(cpd > 0);
        String cpdBody = collector.substring(cpd, cpd + 600);
        assertTrue(cpdBody.contains("if (!isDiLink5CabinCpdProvenAbsent()) {"));
    }

    @Test
    public void collectorHooksAreGenerationFencedAndProducerOnly() throws IOException {
        String collector = read(COLLECTOR);
        int accState = collector.indexOf("public synchronized void setAccState(boolean isOn) {");
        int next = collector.indexOf("private synchronized boolean collectAllFromScheduler(", accState);
        assertTrue(accState > 0 && next > accState);
        String body = collector.substring(accState, next);
        int restart = body.indexOf("pollSchedulerGeneration.incrementAndGet()");
        int hook = body.indexOf("DiLink5AssistOffApplier.onAccOn(");
        assertTrue(restart > 0);
        // Scheduled after the poll scheduler holds the new generation, on the edge only.
        assertTrue(hook > restart);
        assertTrue(body.contains("if (wasOn != isOn && isDiLink5ProducerActive()) {"));
        assertTrue(body.contains("DiLink5AssistOffApplier.onAccOff();"));
        assertTrue(body.contains("context, pollSchedulerGeneration.get());"));
        // Boot with ACC already on: the run is offered once from the producer start, ACC TRUE only.
        assertTrue(collector.contains(
                "if (Boolean.TRUE.equals(accOn) && collector.isDiLink5ProducerActive()) {\n"
                        + "                        com.overdrive.app.byd.assist.DiLink5AssistOffApplier\n"
                        + "                                .onProducerStarted(appContext);"));
        // Device access for the profile closes when the producer stops.
        assertTrue(collector.contains(
                "public Object diLink5DiPilotDeviceForAssistOff() {\n"
                        + "        return isDiLink5ProducerActive() ? ensureDiLink5DiPilotDevice() : null;"));
    }

    @Test
    public void applierUsesSdkRoutesAndStaysOutOfLegacyVocabularies() throws IOException {
        String applier = read(APPLIER);
        assertTrue(applier.contains("isDiLink5AdapterReady("));
        assertTrue(applier.contains("\"getCarAssistAdapterManager\""));
        assertTrue(applier.contains("writeDiLink5DiPilotCpd("));
        assertTrue(applier.contains("BydDeviceHelper.callMethod(device, setter, value)"));
        assertTrue(applier.contains("BydDeviceHelper.callGetter(device, getter)"));
        // Budget, readiness window and stationary threshold live in the pure plan the JVM tests
        // exercise; the applier only consults them.
        String plan = read(
                "app/src/main/java/com/overdrive/app/byd/assist/DiLink5AssistOffPlan.java");
        assertTrue(plan.contains("MAX_ATTEMPTS = 3;"));
        assertTrue(plan.contains("MIN_ATTEMPT_SPACING_MS = 2_000L;"));
        assertTrue(plan.contains("VERIFY_WINDOW_MS = 6_000L;"));
        assertTrue(plan.contains("READINESS_DEADLINE_MS = 90_000L;"));
        assertTrue(plan.contains("STATIONARY_MAX_KMH = 3.0;"));
        // Stationary needs positive evidence (known speed <= 3 km/h or known gear P): the pure rule
        // is DiLink5AssistOffPlan.motion(speed, gear) and the applier consults nothing else. The
        // gear comes from the collector snapshot (BydVehicleData.gearMode, raw gearbox enum, P = 1),
        // never from GearMonitor.getCurrentGear() whose cold default is P.
        assertTrue(plan.contains("GEAR_PARK = com.overdrive.app.monitor.GearMonitor.GEAR_P;"));
        assertTrue(plan.contains("\"no stationary evidence (speed and gear unknown)\""));
        assertTrue(applier.contains("DiLink5AssistOffPlan.motion(speed, gear)"));
        assertTrue(applier.contains("DiLink5AssistOffPlan.describeMotion(speed, gear)"));
        assertTrue(applier.contains("data.gearMode"));
        assertFalse(applier.contains("import com.overdrive.app.monitor.GearMonitor"));
        assertFalse(applier.contains("GearMonitor.getInstance()"));
        assertFalse(applier.contains("isStationary(speed)"));
        assertTrue(applier.contains(
                "DiLink5AssistOffPlan.cycleAborted(cycle, cycleGeneration.get(), collector.isAccOn())"));
        // The gate is evaluated at most once per ignition cycle and the cycle is marked BEFORE the
        // gate runs, so an opt-in flipped mid-drive waits for the next ACC ON.
        int marked = applier.indexOf("evaluatedCycle = cycle;");
        int gated = applier.indexOf("Eligibility eligibility = eligibility();");
        assertTrue(marked > 0 && gated > marked);
        // Readiness also waits for stationary evidence inside the same 90 s deadline; a moving car
        // ends the run there, no evidence by the deadline skips the cycle.
        int readiness = applier.indexOf("private static Object awaitReadiness(");
        int sdk = applier.indexOf("private static boolean applySdkControl(");
        int dms = applier.indexOf("private static boolean applyDms(");
        assertTrue(readiness > 0 && sdk > readiness && dms > sdk);
        String readinessBody = applier.substring(readiness, sdk);
        assertTrue(readinessBody.contains("if (adapterReady && motion == Motion.STATIONARY) {"));
        assertTrue(readinessBody.contains("if (motion == Motion.MOVING) {"));
        assertTrue(readinessBody.contains("SystemClock.elapsedRealtime() >= deadline"));
        // The live config re-read (model, provenance, this control's opt-in) and the stationary
        // check run immediately before every write, in that order, in both control paths.
        String sdkBody = applier.substring(sdk, dms);
        int sdkDenial = sdkBody.indexOf("String denied = e.denial(control);");
        int sdkStationary = sdkBody.indexOf("if (!stationary(collector, status, statusFile,");
        assertTrue(sdkDenial > 0 && sdkStationary > sdkDenial);
        assertTrue(sdkBody.contains("wrote ? State.FAILED : State.DISABLED"));
        String dmsOnwards = applier.substring(dms);
        int dmsDenial = dmsOnwards.indexOf("String denied = e.denial(Control.DMS);");
        int dmsStationary = dmsOnwards.indexOf("if (!stationary(collector, status, statusFile,");
        assertTrue(dmsDenial > 0 && dmsStationary > dmsDenial);
        assertTrue(applier.contains("UnifiedConfigManager.getVehicle()"));
        assertTrue(applier.contains("DiLink5AssistOffGate.writeDenial("));
        // An opted-in control denied during the readiness wait is reported "disabled" with the reason.
        assertTrue(applier.contains("status.update(control, State.DISABLED, 0, System.currentTimeMillis(),"));
        // stillCurrent also requires the DI5 producer (device reachable), for SDK controls and DMS.
        int still = applier.indexOf("private static boolean stillCurrent(");
        assertTrue(still > 0);
        String stillBody = applier.substring(still, applier.indexOf("\n    }\n", still));
        assertTrue(stillBody.contains("&& collector.diLink5DiPilotDeviceForAssistOff() != null"));
        assertTrue(sdkBody.contains("if (!stillCurrent(collector, cycle)) {"));
        assertTrue(dmsOnwards.contains("if (!stillCurrent(collector, cycle)) {"));
        // Skip wording: an earlier control may already have written, so "no further writes".
        assertTrue(applier.contains("; no further writes this cycle"));
        assertFalse(applier.contains("no writes this cycle"));
        // Never the generic DiPilot switch helper, never the legacy lane/ADAS setters, never
        // MQTT/keymap/quick-panel vocabularies, never raw transaction codes.
        for (String forbidden : new String[] {
                "setDiLink5DiPilotSwitch", "setLaneAssistMode", "setEmergencyLaneKeeping",
                "setAdasFeature", "setDiLink5ChildPresenceViaDiPilot", "MqttPublisher",
                "lane_assist", "adas_elka", "KeymapApiHandler", "transact("}) {
            assertFalse(forbidden, applier.contains(forbidden));
        }
        // The DMS branch only ever reports request_sent / pending / failed / skipped / disabled, and
        // its final state comes from the per-transaction delivery record (partial → request_sent).
        int helpers = applier.indexOf("// ── Helpers", dms);
        assertTrue(helpers > dms);
        String dmsBody = applier.substring(dms, helpers);
        assertFalse(dmsBody.contains("State.VERIFIED"));
        assertFalse(dmsBody.contains("State.APPLIED"));
        assertTrue(dmsBody.contains("State.REQUEST_SENT"));
        assertTrue(dmsBody.contains("DiLink5DmsSettingsClient.requestOff("));
        assertTrue(dmsBody.contains("new DiLink5DmsSettingsClient.Delivery()"));
        assertTrue(dmsBody.contains("delivery.finalResult(failureDetail)"));
        assertTrue(dmsBody.contains("delivery.anyAcknowledged()"));
        // Every write attempt logs its before/after readback and verdict.
        assertTrue(applier.contains("\" write=\" + writeValue + \" before=\""));
        assertTrue(applier.contains("\" verified=\" + verified"));
    }

    @Test
    public void dmsClientIsARawParcelRouteWithoutAnyVerifiedState() throws IOException {
        String client = read(DMS_CLIENT);
        assertTrue(client.contains("\"com.ts.appservice.settings\""));
        assertTrue(client.contains("\"com.ts.appservice.settings.SettingsService\""));
        assertTrue(client.contains("\"com.ts.lib.settings.ISettingsAdapterService\""));
        assertTrue(client.contains("\"com.ts.lib.settings.dms.IDmsSettingsService\""));
        assertTrue(client.contains("TX_GET_ADAPTER = 2;"));
        assertTrue(client.contains("data.writeString(ADAPTER_DMS);"));
        assertTrue(client.contains("\"dms\""));
        assertTrue(client.contains("reply.readStrongBinder()"));
        // The three IDmsSettingsService off-writes: switch (2), fatigue (4), attention (5), value 0,
        // each its own transaction whose outcome is recorded per setting (partial delivery).
        assertTrue(client.contains("DMS_TX_SWITCH = 2;"));
        assertTrue(client.contains("DMS_TX_FATIGUE = 4;"));
        assertTrue(client.contains("DMS_TX_ATTENTION = 5;"));
        assertTrue(client.contains("DMS_OFF = 0;"));
        assertTrue(client.contains("MONITORING(\"monitoring\", DMS_TX_SWITCH)"));
        assertTrue(client.contains("FATIGUE(\"fatigue\", DMS_TX_FATIGUE)"));
        assertTrue(client.contains("ATTENTION(\"attention\", DMS_TX_ATTENTION)"));
        assertTrue(client.contains("for (Setting setting : delivery.pending()) {"));
        assertTrue(client.contains("sendOff(dms, setting.transactionCode);"));
        assertTrue(client.contains("delivery.record(setting, true);"));
        assertTrue(client.contains("delivery.record(setting, false);"));
        // A not-fully-acknowledged attempt reports through the tested helper, so an all-failed
        // attempt reads "none acknowledged: ..." and never "partial: ...".
        assertTrue(client.contains(
                "return new Result(Outcome.FAILED, delivery.attemptDetail(firstError));"));
        assertFalse(client.contains("\"partial: \" + delivery.summary()"));
        assertTrue(client.contains("data.writeInt(DMS_OFF);"));
        assertTrue(client.contains("dms.transact(transactionCode, data, reply, 0);"));
        assertTrue(client.contains("Context.BIND_AUTO_CREATE"));
        assertTrue(client.contains("safeUnbind(context, connection);"));
        // No verified vocabulary anywhere on this route.
        assertFalse(client.contains("VERIFIED"));
        assertFalse(client.contains("verified"));
        assertTrue(client.contains("REQUEST_SENT"));
    }

    @Test
    public void modelGateAcceptsOnlyAUserSelectionEverywhere() throws IOException {
        String gate = read("app/src/main/java/com/overdrive/app/byd/assist/DiLink5AssistOffGate.java");
        assertTrue(gate.contains("VehicleModelSelection.SOURCE_USER.equals("));
        assertTrue(gate.contains("VehicleModelSelection.normalizeSource(modelSource, modelId)"));
        assertFalse(gate.contains("isResolvedSelection("));
        // The daemon computes assistOffAvailable (UI enablement) with the very same function, so
        // legacy/auto provenance greys the toggles out exactly where the applier refuses to write.
        String api = read("app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");
        int available = api.indexOf("config.put(\"assistOffAvailable\",");
        assertTrue(available > 0);
        assertTrue(api.substring(available, available + 400).contains(
                "DiLink5AssistOffGate.modelConfirmedForProfile("));
        String vehicle = read("app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");
        assertTrue(vehicle.contains("DiLink5AssistOffGate.modelConfirmedForProfile("));
        // The value-convention note no longer claims a wrong value can never change state.
        String controls = read(
                "app/src/main/java/com/overdrive/app/byd/assist/DiLink5AssistOffControls.java");
        String flattened = controls.replaceAll("\\s*\\n\\s*\\*\\s*", " ");
        assertFalse(flattened.contains("never a wrong state"));
        assertTrue(flattened.contains("The readback proves \"off\", not \"unchanged\""));
    }

    @Test
    public void configSeedsFourIndependentDefaultOffKeys() throws IOException {
        String config = read("app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt");
        for (String key : new String[] {"assistOffCpd", "assistOffLda", "assistOffElka", "assistOffDms"}) {
            assertTrue(key, config.contains(
                    "if (!vehicle.has(\"" + key + "\")) vehicle.put(\"" + key + "\", false)"));
        }
        String api = read("app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");
        assertTrue(api.contains("config.put(\"assistOff\","));
        assertTrue(api.contains("config.put(\"assistOffAvailable\","));
        assertTrue(api.contains("DiLink5AssistOffGate.modelConfirmedForProfile("));
        // POST persists per control into the vehicle section, after the keep-alive/SoC blocks.
        int keepAlive = api.indexOf("if (configJson.has(\"di5ParkedKeepAlive\"))");
        int soc = api.indexOf("if (configJson.has(\"lowSocCutoffPercent\"))");
        int assistOff = api.indexOf("if (configJson.has(\"assistOff\"))");
        assertTrue(keepAlive > 0 && soc > keepAlive && assistOff > soc);
        assertTrue(api.contains(".updateValues(\"vehicle\", assistOffValues)"));
        String vehicle = read("app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");
        assertTrue(vehicle.contains(
                "cleanPath.equals(\"/api/vehicle/di5-assist-off\") && method.equals(\"GET\")"));
        assertTrue(vehicle.contains("DiLink5AssistOffStatus\n                .readLatest("));
        assertTrue(vehicle.contains("response.put(\"modelConfirmed\","));
    }

    @Test
    public void settingsUiOffersFourExplicitTogglesBehindConfirmation() throws IOException {
        String html = read("app/src/main/assets/web/local/surveillance.html");
        String script = read("app/src/main/assets/web/shared/surveillance.js");
        for (String control : new String[] {"Cpd", "Lda", "Elka", "Dms"}) {
            assertTrue(control, html.contains("id=\"survAssistOff" + control + "Row\""));
            assertTrue(control, html.contains(
                    "id=\"survAssistOff" + control + "\" disabled "
                            + "onchange=\"SurvSettings.toggleAssistOff('"
                            + control.toLowerCase() + "')\""));
        }
        // Rows sit right after the keep-alive row and are NOT part of the onOnly dimming.
        assertTrue(html.indexOf("id=\"survAssistOffCpdRow\"")
                > html.indexOf("id=\"survDi5ParkedKeepAliveRow\""));
        assertFalse(script.contains("'survAssistOffCpd',"));
        int toggle = script.indexOf("toggleAssistOff(control) {");
        int persist = script.indexOf("_persistAssistOff(control, on, el, previous) {", toggle);
        assertTrue(toggle > 0 && persist > toggle);
        String toggleBody = script.substring(toggle, persist);
        assertTrue(toggleBody.contains("BYD.utils.confirmDialog({"));
        assertTrue(toggleBody.contains("surveillance.assist_off_confirm_title"));
        assertTrue(toggleBody.contains("surveillance.assist_off_confirm_body"));
        assertTrue(toggleBody.contains("surveillance.assist_off_confirm_unavailable"));
        // CPD's consent text carries the cabin-switch-absent statement.
        assertTrue(toggleBody.contains("if (control === 'cpd') {"));
        assertTrue(toggleBody.contains("surveillance.assist_off_cpd_desc"));
        assertTrue(script.contains("_nextImmediateWrite(writeKey)"));
        assertTrue(script.contains("this._writeJson('/api/surveillance/config', body)"));
        assertTrue(script.contains("applyAssistOffUI() {"));
        assertTrue(script.contains("this.config.assistOffAvailable === true"));
        assertTrue(script.contains("assistOff: { cpd: false, lda: false, elka: false, dms: false }"));
        String english = read("app/src/main/assets/web/i18n/en.json");
        String hebrew = read("app/src/main/assets/web/i18n/he.json");
        assertTrue(english.contains("\"assist_off_cpd_desc\": \"For cars with no cabin-camera CPD switch, such as the Sealion 7 model:"));
        for (String key : new String[] {
                "\"assist_off_cpd\"", "\"assist_off_lda\"", "\"assist_off_elka\"",
                "\"assist_off_dms\"", "\"assist_off_confirm_title\"",
                "\"assist_off_confirm_body\"", "\"assist_off_confirm_unavailable\"",
                "\"assist_off_saved_on\"", "\"assist_off_saved_off\"",
                "\"assist_off_save_failed\"", "\"assist_off_unavailable_hint\""}) {
            assertTrue("en.json missing " + key, english.contains(key));
            assertTrue("he.json missing " + key, hebrew.contains(key));
        }
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path direct = current.resolve(relativePath);
            if (Files.isRegularFile(direct)) {
                return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
