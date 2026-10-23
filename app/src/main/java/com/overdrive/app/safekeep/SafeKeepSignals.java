package com.overdrive.app.safekeep;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.safekeep.dest.DestError;

/**
 * Automation signals owned by SafeKeep (single publisher per key). Published only while the
 * engine runs; repeats of the same value are not re-published.
 */
public final class SafeKeepSignals {

    public static final EventData STATE = com.overdrive.app.automation.condition.BydEvent.SAFEKEEP_STATE;
    public static final EventData BACKLOG = com.overdrive.app.automation.condition.BydEvent.SAFEKEEP_BACKLOG;
    public static final EventData LAST_ERROR = com.overdrive.app.automation.condition.BydEvent.SAFEKEEP_LAST_ERROR;
    public static final EventData CLIP_SAVED = com.overdrive.app.automation.condition.BydEvent.SAFEKEEP_CLIP_SAVED;

    private static volatile String lastState;
    private static volatile int lastBacklog = -1;
    private static volatile long lastBacklogAt;
    private static volatile String lastErr;

    private SafeKeepSignals() {}

    /**
     * Engine start: store the resting values silently so the first real error or saved clip of
     * the session is a transition that fires (a first-ever value is otherwise only a seed).
     */
    static synchronized void seed() {
        try {
            Automations.runSilentSeed(() -> {
                Automations.update(LAST_ERROR, "none", true);
                Automations.update(CLIP_SAVED, "", true);
            });
            lastErr = "none";
        } catch (Throwable ignored) {
        }
    }

    static void publishState(String state, int backlog) {
        try {
            if (!state.equals(lastState)) {
                lastState = state;
                Automations.update(STATE, state, true);
            }
            long now = System.currentTimeMillis();
            if (backlog != lastBacklog && (now - lastBacklogAt > 30_000 || backlog == 0 || lastBacklog <= 0)) {
                lastBacklog = backlog;
                lastBacklogAt = now;
                Automations.update(BACKLOG, Math.max(0, backlog), true);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Engine stopped: tell automations once, if SafeKeep ever published this session. */
    static void publishOff() {
        try {
            if (lastState != null && !"off".equals(lastState)) {
                lastState = "off";
                Automations.update(STATE, "off", true);
            }
        } catch (Throwable ignored) {
        }
    }

    static synchronized void publishError(DestError.Kind kind) {
        String v;
        switch (kind) {
            case AUTH_EXPIRED:
                v = "auth";
                break;
            case QUOTA_FULL:
                v = "quota";
                break;
            case NETWORK:
            case RATE_LIMITED:
                v = "network";
                break;
            default:
                v = "other";
                break;
        }
        try {
            if (!v.equals(lastErr)) {
                lastErr = v;
                Automations.updateObservedEdge(LAST_ERROR, v);
            }
        } catch (Throwable ignored) {
        }
    }

    static synchronized void clearError() {
        try {
            if (!"none".equals(lastErr)) {
                lastErr = "none";
                Automations.updateObservedEdge(LAST_ERROR, "none");
            }
        } catch (Throwable ignored) {
        }
    }

    /** Each saved clip is an event of its own (its file name is the value). */
    static void publishClipSaved(String fileName) {
        try {
            Automations.updateObservedEdge(CLIP_SAVED, fileName);
        } catch (Throwable ignored) {
        }
    }
}
