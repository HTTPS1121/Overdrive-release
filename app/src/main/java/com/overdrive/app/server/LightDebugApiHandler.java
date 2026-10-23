package com.overdrive.app.server;

import android.content.Context;

import com.overdrive.app.byd.HazardLightProbe;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.daemon.DaemonBootstrap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Debug endpoint for probing BYD hazard / turn-signal / fog actuation from the
 * daemon (uid 2000). Lets you test the {@link HazardLightProbe} candidates
 * over HTTP without launching a separate {@code app_process}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/debug/light/resolve} — read-only: which feature IDs
 *       resolved (named SDK const vs CANFD fallback int) + the candidate list.
 *       Safe, no writes.</li>
 *   <li>{@code GET /api/debug/light/fire?candidate=A|B|C&hold=4&confirm=YES} —
 *       fire one candidate (ON → hold seconds → OFF), returns each set()'s SDK
 *       result. Gated by {@code confirm=YES}.</li>
 *   <li>{@code GET /api/debug/light/fire-all?hold=3&confirm=YES} — fire A, B,
 *       then C in sequence. Gated by {@code confirm=YES}.</li>
 *   <li>{@code GET /api/debug/light/cabin?api=inside|lamps|rearLamps|insideDoor|doorLight&value=0..3&confirm=YES}
 *       — DI5 cabin-light calibration: one raw write, not self-resetting. {@code api=next}
 *       runs the next step of a fixed candidate list per reload; {@code api=record} writes
 *       nothing. All start the DI5 calibration recorder.</li>
 * </ul>
 *
 * <p>The hazard endpoints are light-only and self-resetting (OFF always issued).
 * Returns 503 if the daemon Context isn't available (run inside cam_daemon).
 */
public final class LightDebugApiHandler {

    private static final String TAG = "LightDebug";
    private static final long DEFAULT_HOLD_MS = 4000L;
    private static final long MAX_HOLD_MS = 15000L;
    private static final java.util.List<String> CABIN_PROBE_APIS =
            java.util.Arrays.asList("inside", "lamps", "rearLamps", "insideDoor", "doorLight");
    // One write per reload, so each step can be matched to what the cabin did. insideDoor is left
    // out: it is the cabin tile's own call. doorLight ends on 1, its idle reading.
    private static final String[][] CABIN_STEPS = {
            {"lamps", "2"}, {"lamps", "0"}, {"lamps", "3"}, {"inside", "1"}, {"inside", "2"},
            {"rearLamps", "1"}, {"rearLamps", "2"}, {"doorLight", "2"}, {"doorLight", "1"}};
    private static final java.util.concurrent.atomic.AtomicInteger cabinStep =
            new java.util.concurrent.atomic.AtomicInteger();

    private LightDebugApiHandler() {}

    /**
     * Resolve the daemon's BYD-capable Context. Prefer {@link CameraDaemon#getAppContext()}
     * (the shared context that successfully initialised the BYD device singletons),
     * fall back to {@link DaemonBootstrap#getContext()}.
     */
    private static Context resolveContext() {
        Context ctx = null;
        try { ctx = CameraDaemon.getAppContext(); } catch (Throwable ignore) {}
        if (ctx == null) {
            try { ctx = DaemonBootstrap.getContext(); } catch (Throwable ignore) {}
        }
        return ctx;
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (!"GET".equals(method)) {
            HttpResponse.sendError(out, 405, "Method Not Allowed");
            return true;
        }

        String pathOnly = path;
        Map<String, String> q = new LinkedHashMap<>();
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) {
            pathOnly = path.substring(0, qIdx);
            for (String pair : path.substring(qIdx + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) q.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }

        if (pathOnly.equals("/api/debug/light/resolve")) {
            return handleResolve(out);
        }
        if (pathOnly.equals("/api/debug/light/fire")) {
            return handleFire(out, q);
        }
        if (pathOnly.equals("/api/debug/light/fire-all")) {
            return handleFireAll(out, q);
        }
        if (pathOnly.equals("/api/debug/light/cabin")) {
            return handleCabin(out, q);
        }

        HttpResponse.sendError(out, 404, "Unknown light debug endpoint");
        return true;
    }

    /** Read-only: show resolved feature IDs + candidate list. No writes. */
    private static boolean handleResolve(OutputStream out) throws Exception {
        JSONObject r = new JSONObject();
        r.put("uid", android.os.Process.myUid());
        r.put("contextAvailable", resolveContext() != null);
        r.put("resolvedFeatureIds", HazardLightProbe.resolvedIds());
        JSONArray cands = new JSONArray();
        for (HazardLightProbe.Candidate c : HazardLightProbe.candidates()) {
            JSONObject o = new JSONObject();
            o.put("id", c.id);
            o.put("label", c.label);
            o.put("deviceClass", c.deviceClass);
            JSONArray fids = new JSONArray();
            for (int f : c.featureIds) fids.put(f);
            o.put("featureIds", fids);
            cands.put(o);
        }
        r.put("candidates", cands);
        r.put("note", "GET /api/debug/light/fire?candidate=A&hold=4&confirm=YES to actuate");
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    /** Fire one candidate (confirm=YES required). */
    private static boolean handleFire(OutputStream out, Map<String, String> q) throws Exception {
        if (!"YES".equals(q.get("confirm"))) {
            HttpResponse.sendJsonError(out, "Refusing to actuate lights without confirm=YES query param");
            return true;
        }
        Context ctx = resolveContext();
        if (ctx == null) {
            HttpResponse.sendJson(out, 503,
                new JSONObject().put("error", "Daemon Context unavailable — run inside cam_daemon").toString());
            return true;
        }
        String id = q.getOrDefault("candidate", "A");
        HazardLightProbe.Candidate c = HazardLightProbe.candidateById(id);
        if (c == null) {
            HttpResponse.sendJsonError(out, "Unknown candidate '" + id + "' (expected A, B, or C)");
            return true;
        }
        long hold = parseHold(q);
        log("fire candidate=" + c.id + " (" + c.label + ") hold=" + hold + "ms");
        JSONObject result = HazardLightProbe.runCandidate(ctx, c, hold);
        log("fire candidate=" + c.id + " result=" + result);
        HttpResponse.sendJson(out, result.toString());
        return true;
    }

    /** Fire A, B, C in sequence (confirm=YES required). */
    private static boolean handleFireAll(OutputStream out, Map<String, String> q) throws Exception {
        if (!"YES".equals(q.get("confirm"))) {
            HttpResponse.sendJsonError(out, "Refusing to actuate lights without confirm=YES query param");
            return true;
        }
        Context ctx = resolveContext();
        if (ctx == null) {
            HttpResponse.sendJson(out, 503,
                new JSONObject().put("error", "Daemon Context unavailable — run inside cam_daemon").toString());
            return true;
        }
        long hold = parseHold(q);
        JSONArray results = new JSONArray();
        for (HazardLightProbe.Candidate c : HazardLightProbe.candidates()) {
            log("fire-all candidate=" + c.id + " hold=" + hold + "ms");
            results.put(HazardLightProbe.runCandidate(ctx, c, hold));
        }
        JSONObject r = new JSONObject();
        r.put("uid", android.os.Process.myUid());
        r.put("results", results);
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    /** One raw DI5 cabin-light write via the app process; its rc lands in the daemon log. */
    private static boolean handleCabin(OutputStream out, Map<String, String> q) throws Exception {
        String api = q.get("api");
        if ("record".equals(api)) {
            boolean diLink5 = startDiLink5CalibrationRecorder();
            HttpResponse.sendJson(out, new JSONObject()
                    .put("recording", diLink5)
                    .put("note", diLink5
                            ? "Toggle the cabin lights by hand within 5 minutes; changes are logged as DI5 cal change"
                            : "DiLink 5 mode is not selected, or the vehicle context is not ready")
                    .toString());
            return true;
        }
        if (!"YES".equals(q.get("confirm"))) {
            HttpResponse.sendJsonError(out, "Refusing to actuate lights without confirm=YES query param");
            return true;
        }
        int step = -1;
        int value = -1;
        if ("next".equals(api)) {
            step = Math.floorMod(cabinStep.getAndIncrement(), CABIN_STEPS.length);
            api = CABIN_STEPS[step][0];
            value = Integer.parseInt(CABIN_STEPS[step][1]);
        } else {
            try {
                value = Integer.parseInt(q.getOrDefault("value", "").trim());
            } catch (NumberFormatException ignore) {}
        }
        if (!CABIN_PROBE_APIS.contains(api) || value < 0 || value > 3) {
            HttpResponse.sendJsonError(out, "Expected api=next|record, or api="
                    + String.join("|", CABIN_PROBE_APIS) + " and value=0..3");
            return true;
        }
        log("cabin " + (step >= 0 ? "step " + (step + 1) + "/" + CABIN_STEPS.length + " " : "")
                + "api=" + api + " value=" + value);
        VehicleCommandRouter.CommandResult r = VehicleCommandRouter.getInstance()
                .execute(new VehicleCommandRouter.CabinLightProbeCommand(api, value));
        // After the write: the recorder's reads share the app bridge's single worker.
        startDiLink5CalibrationRecorder();
        JSONObject resp = new JSONObject();
        resp.put("api", api);
        resp.put("value", value);
        resp.put("outcome", r.outcome.name());
        resp.put("message", r.displayMessage);
        if (step >= 0) {
            resp.put("step", step + 1);
            resp.put("of", CABIN_STEPS.length);
            resp.put("next", step + 1 < CABIN_STEPS.length
                    ? CABIN_STEPS[step + 1][0] + "=" + CABIN_STEPS[step + 1][1]
                    : "none; the next reload starts again at step 1");
            resp.put("note", "Look at the cabin lights, then reload for the next step. "
                    + "Report every step number that changed a light.");
        } else {
            resp.put("note", "Note whether the cabin light changed; the rc is logged as "
                    + "DI5 app-process 'cabin-light-probe'");
        }
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    private static boolean startDiLink5CalibrationRecorder() {
        com.overdrive.app.camera.dilink5.DiLink5Platform.refreshActiveMode();
        Context ctx = com.overdrive.app.byd.BydDataCollector.getInstance().getRuntimeContext();
        if (ctx == null || !com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            return false;
        }
        com.overdrive.app.byd.dilink5.Dilink5CalibrationRecorder.start(ctx);
        return true;
    }

    private static long parseHold(Map<String, String> q) {
        String s = q.get("hold");
        if (s == null) return DEFAULT_HOLD_MS;
        try {
            long ms = Long.parseLong(s.trim()) * 1000L;
            if (ms < 0) return DEFAULT_HOLD_MS;
            return Math.min(ms, MAX_HOLD_MS);
        } catch (NumberFormatException e) {
            return DEFAULT_HOLD_MS;
        }
    }

    private static void log(String s) {
        try { CameraDaemon.log(TAG + ": " + s); } catch (Throwable ignore) {}
    }
}
