package com.overdrive.app.byd.assist;

import com.overdrive.app.byd.assist.DiLink5AssistOffGate.Control;

/**
 * Per-control write/readback specification of the DiLink 5 assist-off profile.
 *
 * <p>Routes are the SDK method names on {@code android.hardware.bydauto.dipilot.BYDAutoDiPilotDevice},
 * invoked by name through {@code BydDeviceHelper} — never raw Binder transaction codes. The codes
 * recorded during on-car validation are kept here as a cross-check only:
 * <pre>
 *   setCpdStatus                     tx 185   getCpdStatus                       tx 187
 *   setLaneAssistType                tx 12    getLaneAssistType                  tx 11
 *   setEmergencyLaneAssistanceState  tx 35    getEmergencyLaneAssistance32FState tx 79
 * </pre>
 * Value conventions (one car, one firmware). The readback proves "off", not "unchanged": every
 * write is readback-checked, but the write itself still executes, so on an untested trim a wrong
 * value can move the mode to a different non-off value and be reported {@code failed}:
 * <ul>
 *   <li>CPD: getter 1 = on, 2 = off, 0 = adapter not connected yet; write 2 = off.</li>
 *   <li>LDA/LKA mode: write 1 = off; getter reads 0 when off (3 = warning + prevent).</li>
 *   <li>ELKA: write 1 = off; getter reads 0 when off, non-zero when on.</li>
 *   <li>DMS: no DiPilot getter/setter tracks the real switch; it goes through the raw-Parcel
 *       settings client and is only ever "request sent".</li>
 * </ul>
 * The lane getters are ambiguous right after power-on: 0 means both "off" and "car service not
 * connected". A 0 is therefore trusted only once readiness (CPD getter 1 or 2) was observed in the
 * same ignition cycle. CPD's own 0 is never an off state.
 */
public final class DiLink5AssistOffControls {

    /** Same sentinel as {@code BydVehicleData.UNAVAILABLE}; kept local so this class stays pure. */
    public static final int READ_UNAVAILABLE = Integer.MIN_VALUE;

    /** Readback-driven route of one control. DMS has none (Binder client, no readback). */
    public static final class Spec {
        public final Control control;
        public final String setter;
        public final String getter;
        /** The value the setter takes to switch the feature off. */
        public final int offWriteValue;
        /** The getter value that means "off" (subject to {@link #zeroIsTrustworthy} for lanes). */
        public final int offReadback;

        Spec(Control control, String setter, String getter, int offWriteValue, int offReadback) {
            this.control = control;
            this.setter = setter;
            this.getter = getter;
            this.offWriteValue = offWriteValue;
            this.offReadback = offReadback;
        }
    }

    public static final Spec CPD =
            new Spec(Control.CPD, "setCpdStatus", "getCpdStatus", 2, 2);
    public static final Spec LDA =
            new Spec(Control.LDA, "setLaneAssistType", "getLaneAssistType", 1, 0);
    public static final Spec ELKA = new Spec(Control.ELKA,
            "setEmergencyLaneAssistanceState", "getEmergencyLaneAssistance32FState", 1, 0);

    private DiLink5AssistOffControls() {}

    /** The DiPilot route for a control, or {@code null} when it has none (DMS). */
    public static Spec spec(Control control) {
        if (control == null) return null;
        switch (control) {
            case CPD: return CPD;
            case LDA: return LDA;
            case ELKA: return ELKA;
            default: return null;
        }
    }

    /** CPD getter 1 or 2 means the assist adapter is connected to the car. */
    public static boolean isReadinessRead(int cpdRead) {
        return cpdRead == 1 || cpdRead == 2;
    }

    /**
     * Whether a lane-getter 0 may be read as "off". Only after readiness was observed this cycle,
     * and never for CPD (whose 0 is "not ready") or DMS (no getter).
     */
    public static boolean zeroIsTrustworthy(Control control, boolean readinessObserved) {
        if (control == null) return false;
        switch (control) {
            case LDA:
            case ELKA:
                return readinessObserved;
            default:
                return false;
        }
    }

    /** True when {@code read} proves the control is off under the rules above. */
    public static boolean isOff(Control control, int read, boolean readinessObserved) {
        Spec spec = spec(control);
        if (spec == null || read == READ_UNAVAILABLE) return false;
        if (read != spec.offReadback) return false;
        return read != 0 || zeroIsTrustworthy(control, readinessObserved);
    }
}
