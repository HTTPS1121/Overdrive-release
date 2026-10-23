package com.overdrive.app.byd.assist;

import com.overdrive.app.config.VehicleModelSelection;

import java.util.Locale;

/**
 * Write-enable gate for the opt-in DiLink 5 assist-off profile.
 *
 * <p>Pure function of four inputs so the JVM tests can enumerate every false case:
 * <ol>
 *   <li>the DiLink 5 platform is the fenced runtime mode of this process
 *       ({@code DiLink5Platform.isSelected()}; {@code BydDataCollector.isDiLink5Vehicle()} is the
 *       same call, so the two platform checks of the brief collapse into one input),</li>
 *   <li>the vehicle model was selected by the user ({@code modelSource} normalizes to
 *       {@code user}; {@code unset} is the renderer's cosmetic fallback, {@code legacy} is a
 *       migration stamp and {@code auto} a detector's guess — none is a user statement about the
 *       car),</li>
 *   <li>that model is {@value #PROFILE_MODEL_ID}, and</li>
 *   <li>the control's own opt-in is on.</li>
 * </ol>
 * Off DiLink 5 or with an unconfirmed model every caller must return before allocating anything.
 * {@link #writeDenial} is the live re-check the applier runs before every write attempt.
 */
public final class DiLink5AssistOffGate {

    /** The only model this profile's write values were observed on. */
    public static final String PROFILE_MODEL_ID = "sealion7";

    /** {@link #writeDenial} reasons (also the status {@code detail} of a control that was not written). */
    public static final String DENIAL_NOT_OPTED_IN_AT_ACC_ON = "not opted in at ACC ON";
    public static final String DENIAL_PLATFORM_CHANGED = "DiLink 5 no longer selected";
    public static final String DENIAL_MODEL_CHANGED = "model no longer confirmed during start-up";
    public static final String DENIAL_OPT_IN_TURNED_OFF = "opt-in turned off during start-up";

    /** The four independently opted-in controls, in apply order. */
    public enum Control {
        CPD("cpd", "assistOffCpd"),
        LDA("lda", "assistOffLda"),
        ELKA("elka", "assistOffElka"),
        DMS("dms", "assistOffDms");

        private final String key;
        private final String configKey;

        Control(String key, String configKey) {
            this.key = key;
            this.configKey = configKey;
        }

        /** JSON / UI key ({@code cpd}, {@code lda}, {@code elka}, {@code dms}). */
        public String key() {
            return key;
        }

        /** Flat key inside the unified {@code vehicle} section ({@code assistOffCpd}, ...). */
        public String configKey() {
            return configKey;
        }

        /** The control for a JSON/UI key, or {@code null} when unknown. */
        public static Control fromKey(String key) {
            if (key == null) return null;
            String k = key.trim().toLowerCase(Locale.US);
            for (Control c : values()) {
                if (c.key.equals(k)) return c;
            }
            return null;
        }
    }

    private DiLink5AssistOffGate() {}

    /**
     * True only when DiLink 5 is selected AND the user selected {@value #PROFILE_MODEL_ID} as the
     * model: {@code VehicleModelSelection.normalizeSource(modelSource, modelId)} must be exactly
     * {@code user}. {@code unset}, {@code legacy} (stamped by config migrations) and {@code auto}
     * are rejected. This is the "model confirmed" term the API and UI expose ({@code
     * assistOffAvailable}, so the toggles grey out for those sources too); it deliberately excludes
     * the per-control opt-in.
     */
    public static boolean modelConfirmedForProfile(
            boolean di5PlatformSelected, String modelId, String modelSource) {
        if (!di5PlatformSelected) return false;
        if (!VehicleModelSelection.SOURCE_USER.equals(
                VehicleModelSelection.normalizeSource(modelSource, modelId))) {
            return false;
        }
        String resolved = VehicleModelSelection.resolvedModelId(modelId, modelSource);
        return resolved != null
                && PROFILE_MODEL_ID.equals(resolved.trim().toLowerCase(Locale.US));
    }

    /** The full write-enable gate for one control. */
    public static boolean allows(
            boolean di5PlatformSelected,
            String modelId,
            String modelSource,
            DiLink5AssistOffProfile profile,
            Control control) {
        if (profile == null || control == null) return false;
        return modelConfirmedForProfile(di5PlatformSelected, modelId, modelSource)
                && profile.isEnabled(control);
    }

    /**
     * Live re-check immediately before a write attempt. The control must have been opted in at the
     * ACC edge ({@code edgeProfile}, so an opt-in flipped ON mid-cycle still waits for the next
     * ignition) AND the config re-read now ({@code liveModelId}, {@code liveModelSource},
     * {@code liveProfile}) must still select DiLink 5, confirm the model and keep that control's
     * opt-in. Returns {@code null} when the write may proceed, otherwise the reason (one of the
     * {@code DENIAL_*} constants). A {@code null} live profile is treated as "opt-in off".
     */
    public static String writeDenial(
            boolean di5PlatformSelected,
            DiLink5AssistOffProfile edgeProfile,
            String liveModelId,
            String liveModelSource,
            DiLink5AssistOffProfile liveProfile,
            Control control) {
        if (control == null || edgeProfile == null || !edgeProfile.isEnabled(control)) {
            return DENIAL_NOT_OPTED_IN_AT_ACC_ON;
        }
        if (!di5PlatformSelected) return DENIAL_PLATFORM_CHANGED;
        if (!modelConfirmedForProfile(true, liveModelId, liveModelSource)) {
            return DENIAL_MODEL_CHANGED;
        }
        if (liveProfile == null || !liveProfile.isEnabled(control)) {
            return DENIAL_OPT_IN_TURNED_OFF;
        }
        return null;
    }
}
