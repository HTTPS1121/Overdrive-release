package com.overdrive.app.byd.assist;

import com.overdrive.app.byd.assist.DiLink5AssistOffGate.Control;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;

import org.json.JSONObject;

/**
 * Diagnostic status of the current assist-off cycle, written by the app process and read by the
 * daemon behind {@code GET /api/vehicle/di5-assist-off}. Additive surface: nothing else consumes it
 * and it feeds no vehicle-state vocabulary.
 *
 * <p>Shape: {@code {cycleGeneration, accGeneration, trigger, modelId, startedAt,
 * readinessObservedAt, finishedAt, updatedAt, skipReason, controls: {cpd: {state, attempts,
 * lastAttemptAt, before, after, detail}, lda: {...}, elka: {...}, dms: {state, attempts,
 * lastAttemptAt, detail}}}}. States: {@code disabled / pending / applied / verified / failed /
 * request_sent / skipped}. DMS has no readback and is never {@code verified} or {@code applied};
 * {@link #update} rejects both.
 *
 * <p>Location: the app UID cannot create files in the shell-owned scratch root, so — exactly like
 * {@code power.IgnitionBroadcastHint} — the file lives in the app's external files directory, which
 * the UID-2000 daemons already read and write (recordings). The daemon resolves it through the two
 * equivalent mount views in {@link #daemonReadCandidates()}. Pure Java apart from {@code org.json}.
 */
public final class DiLink5AssistOffStatus {

    /** File name inside the app's external files directory. */
    public static final String FILE_NAME = "overdrive_di5_assist_off.status";

    public enum State {
        DISABLED("disabled"),
        PENDING("pending"),
        APPLIED("applied"),
        VERIFIED("verified"),
        FAILED("failed"),
        REQUEST_SENT("request_sent"),
        SKIPPED("skipped");

        public final String json;

        State(String json) {
            this.json = json;
        }
    }

    /** One control's progress. Readback values use {@link DiLink5AssistOffControls#READ_UNAVAILABLE}. */
    public static final class ControlStatus {
        public State state = State.DISABLED;
        public int attempts;
        public long lastAttemptAtMs;
        public int before = DiLink5AssistOffControls.READ_UNAVAILABLE;
        public int after = DiLink5AssistOffControls.READ_UNAVAILABLE;
        public String detail = "";

        JSONObject toJson(boolean withReadback) {
            JSONObject json = new JSONObject();
            try {
                json.put("state", state.json);
                json.put("attempts", attempts);
                json.put("lastAttemptAt", lastAttemptAtMs);
                if (withReadback) {
                    json.put("before", readbackValue(before));
                    json.put("after", readbackValue(after));
                }
                json.put("detail", detail == null ? "" : detail);
            } catch (Throwable ignored) {
                // Fixed keys with primitive/String values cannot fail to serialize.
            }
            return json;
        }

        private static Object readbackValue(int value) {
            return value == DiLink5AssistOffControls.READ_UNAVAILABLE ? JSONObject.NULL : value;
        }
    }

    public final long cycleGeneration;
    public final long accGeneration;
    public final String trigger;
    public final String modelId;
    public final long startedAtMs;
    private long readinessObservedAtMs;
    private long finishedAtMs;
    private String skipReason = "";
    private final Map<Control, ControlStatus> controls = new EnumMap<>(Control.class);

    public DiLink5AssistOffStatus(
            long cycleGeneration,
            long accGeneration,
            String trigger,
            String modelId,
            long startedAtMs,
            DiLink5AssistOffProfile profile) {
        this.cycleGeneration = cycleGeneration;
        this.accGeneration = accGeneration;
        this.trigger = trigger == null ? "" : trigger;
        this.modelId = modelId == null ? "" : modelId;
        this.startedAtMs = startedAtMs;
        DiLink5AssistOffProfile p = profile == null ? DiLink5AssistOffProfile.disabled() : profile;
        for (Control c : Control.values()) {
            ControlStatus cs = new ControlStatus();
            cs.state = p.isEnabled(c) ? State.PENDING : State.DISABLED;
            controls.put(c, cs);
        }
    }

    public ControlStatus control(Control control) {
        return controls.get(control);
    }

    public State stateOf(Control control) {
        ControlStatus cs = controls.get(control);
        return cs == null ? null : cs.state;
    }

    public void readinessObserved(long nowMs) {
        readinessObservedAtMs = nowMs;
    }

    public boolean isReadinessObserved() {
        return readinessObservedAtMs > 0L;
    }

    public long readinessObservedAtMs() {
        return readinessObservedAtMs;
    }

    public void finished(long nowMs) {
        finishedAtMs = nowMs;
    }

    public String skipReason() {
        return skipReason;
    }

    /**
     * Records one control's progress. DMS goes through a Binder route without any readback, so it
     * can never be reported {@link State#APPLIED} or {@link State#VERIFIED}; only
     * {@link State#REQUEST_SENT} says the request went out.
     */
    public void update(
            Control control,
            State state,
            int attempts,
            long lastAttemptAtMs,
            int before,
            int after,
            String detail) {
        if (control == null || state == null) throw new IllegalArgumentException("control/state");
        if (control == Control.DMS && (state == State.VERIFIED || state == State.APPLIED)) {
            throw new IllegalArgumentException(
                    "DMS has no readback; it is never " + state.json);
        }
        ControlStatus cs = controls.get(control);
        cs.state = state;
        cs.attempts = attempts;
        cs.lastAttemptAtMs = lastAttemptAtMs;
        cs.before = before;
        cs.after = after;
        cs.detail = detail == null ? "" : detail;
    }

    /**
     * Ends the cycle early (never ready, moving, superseded, interrupted). A control that never got
     * a write becomes {@link State#SKIPPED}; one whose write went out but was never confirmed
     * ({@link State#APPLIED}) becomes {@link State#FAILED}, because the write did happen and no
     * readback proved it. Controls that already reached a terminal state keep it.
     */
    public void skip(String reason) {
        skipReason = reason == null ? "" : reason;
        for (ControlStatus cs : controls.values()) {
            if (cs.state == State.PENDING) {
                cs.state = State.SKIPPED;
                cs.detail = skipReason;
            } else if (cs.state == State.APPLIED) {
                cs.state = State.FAILED;
                cs.detail = "not confirmed; " + skipReason;
            }
        }
    }

    /** Compact one-line digest for the log: {@code cpd=verified lda=failed ... skip="..."}. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (Control c : Control.values()) {
            if (sb.length() > 0) sb.append(' ');
            ControlStatus cs = controls.get(c);
            sb.append(c.key()).append('=').append(cs.state.json);
            if (cs.attempts > 0) sb.append('(').append(cs.attempts).append(')');
        }
        if (skipReason != null && !skipReason.isEmpty()) {
            sb.append(" skip=\"").append(skipReason).append('"');
        }
        return sb.toString();
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("cycleGeneration", cycleGeneration);
            json.put("accGeneration", accGeneration);
            json.put("trigger", trigger);
            json.put("modelId", modelId);
            json.put("startedAt", startedAtMs);
            json.put("readinessObservedAt", readinessObservedAtMs);
            json.put("finishedAt", finishedAtMs);
            json.put("updatedAt", System.currentTimeMillis());
            json.put("skipReason", skipReason == null ? "" : skipReason);
            JSONObject perControl = new JSONObject();
            for (Control c : Control.values()) {
                perControl.put(c.key(), controls.get(c).toJson(c != Control.DMS));
            }
            json.put("controls", perControl);
        } catch (Throwable ignored) {
            // Fixed keys with primitive/String/JSONObject values cannot fail to serialize.
        }
        return json;
    }

    // ── Files ──────────────────────────────────────────────────────────────

    /** The status file inside the app's external files directory; null when there is none. */
    public static File statusFile(File externalFilesDir) {
        if (externalFilesDir == null) return null;
        if (!externalFilesDir.isDirectory() && !externalFilesDir.mkdirs()
                && !externalFilesDir.isDirectory()) {
            return null;
        }
        return new File(externalFilesDir, FILE_NAME);
    }

    /**
     * Atomic, world-readable write (temp file + rename, with the non-atomic fallback emulated
     * storage sometimes needs) so the shell-uid daemon can read what the app wrote. Diagnostic
     * only: never throws.
     */
    public static boolean write(File target, JSONObject json) {
        if (target == null || json == null) return false;
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try {
            File dir = target.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                return false;
            }
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            tmp.setReadable(true, false);
            if (!replaceAtomically(tmp, target)) {
                return false;
            }
            target.setReadable(true, false);
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (tmp.exists()) {
                try {
                    tmp.delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean replaceAtomically(File temporary, File target) {
        try {
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Throwable atomicFailure) {
            // Some emulated-storage layers reject only the atomic flag; retry plainly.
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Throwable replacementFailure) {
            return false;
        }
    }

    /**
     * The app's external files directory as seen from a UID-2000 daemon (a bare {@code app_process}
     * cannot resolve it through a Context), spelled as the two equivalent mount views.
     */
    public static File[] daemonReadCandidates() {
        String rel = "/Android/data/" + com.overdrive.app.BuildConfig.APPLICATION_ID
                + "/files/" + FILE_NAME;
        return new File[] {
            new File("/storage/emulated/0" + rel),
            new File("/sdcard" + rel),
        };
    }

    /** Newest readable status (first candidate that parses), or null. Never throws. */
    public static JSONObject readLatest(long maxBytes) {
        for (File candidate : daemonReadCandidates()) {
            JSONObject parsed = read(candidate, maxBytes);
            if (parsed != null) return parsed;
        }
        return null;
    }

    /** One candidate file as JSON; null when absent, empty, over {@code maxBytes} or unparsable. */
    public static JSONObject read(File candidate, long maxBytes) {
        try {
            if (candidate == null || !candidate.isFile()) return null;
            long length = candidate.length();
            if (length <= 0L || length > maxBytes) return null;
            return new JSONObject(new String(
                    Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
