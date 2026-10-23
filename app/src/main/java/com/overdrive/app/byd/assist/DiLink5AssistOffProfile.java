package com.overdrive.app.byd.assist;

import com.overdrive.app.byd.assist.DiLink5AssistOffGate.Control;

import org.json.JSONObject;

/**
 * Immutable snapshot of the four per-control opt-ins of the DiLink 5 assist-off profile.
 *
 * <p>Persisted as flat booleans in the unified {@code vehicle} section next to
 * {@code modelId}/{@code modelSource} ({@code assistOffCpd}, {@code assistOffLda},
 * {@code assistOffElka}, {@code assistOffDms}), all default {@code false}. Pure Java: the
 * applier, the API handlers and the JVM tests share this one reader.
 */
public final class DiLink5AssistOffProfile {

    /** Flat {@code vehicle}-section keys, one per control, seeded {@code false} by the config. */
    public static final String KEY_CPD = Control.CPD.configKey();
    public static final String KEY_LDA = Control.LDA.configKey();
    public static final String KEY_ELKA = Control.ELKA.configKey();
    public static final String KEY_DMS = Control.DMS.configKey();

    private static final DiLink5AssistOffProfile DISABLED =
            new DiLink5AssistOffProfile(false, false, false, false);

    /** Every control off — the default and the value for a missing section. */
    public static DiLink5AssistOffProfile disabled() {
        return DISABLED;
    }

    public final boolean cpd;
    public final boolean lda;
    public final boolean elka;
    public final boolean dms;

    public DiLink5AssistOffProfile(boolean cpd, boolean lda, boolean elka, boolean dms) {
        this.cpd = cpd;
        this.lda = lda;
        this.elka = elka;
        this.dms = dms;
    }

    /** Reads the flat opt-in keys from a {@code vehicle} section; anything missing is off. */
    public static DiLink5AssistOffProfile fromVehicle(JSONObject vehicle) {
        if (vehicle == null) return DISABLED;
        return new DiLink5AssistOffProfile(
                vehicle.optBoolean(keyFor(Control.CPD), false),
                vehicle.optBoolean(keyFor(Control.LDA), false),
                vehicle.optBoolean(keyFor(Control.ELKA), false),
                vehicle.optBoolean(keyFor(Control.DMS), false));
    }

    /** The flat {@code vehicle}-section key that persists one control's opt-in. */
    public static String keyFor(Control control) {
        return control == null ? null : control.configKey();
    }

    public boolean isEnabled(Control control) {
        if (control == null) return false;
        switch (control) {
            case CPD: return cpd;
            case LDA: return lda;
            case ELKA: return elka;
            case DMS: return dms;
            default: return false;
        }
    }

    public boolean anyEnabled() {
        return cpd || lda || elka || dms;
    }

    /** {@code {cpd, lda, elka, dms}} keyed by {@link Control#key()}. */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            for (Control c : Control.values()) {
                json.put(c.key(), isEnabled(c));
            }
        } catch (Throwable ignored) {
            // Booleans under fixed keys cannot fail to serialize.
        }
        return json;
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}
