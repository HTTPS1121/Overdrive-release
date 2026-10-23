package com.overdrive.app.launcher;

import java.util.Collections;
import java.util.Set;

/**
 * Fail-closed authorization gate for the DiLink 5 OEM ADB-enable fallback.
 *
 * <p>Three verdicts guard every bind/transact in {@link DiLink5OemAdbEnableClient}:
 * the firmware must be on a verified allowlist (or named explicitly for one car),
 * and the field kill switch must be on. With the shipped defaults nothing is
 * reachable — the allowlist is empty and the switch is absent — so the gate
 * returns {@link Verdict#SWITCH_OFF} and no OEM service is ever bound.
 *
 * <p>Pure logic, no Android imports, so it is unit-testable on a plain JVM. The
 * caller supplies the probed firmware signature and the raw switch value read
 * from {@code Settings.Global}.
 */
public final class DiLink5AdbFallbackGate {

    /**
     * Firmware signatures on which the OEM ADB-enable fallback is authorized,
     * matched against {@link #signatureOf(String, String)}.
     *
     * <p>DELIBERATELY EMPTY: the OEM enablement interfaces this fallback drives
     * (an OTA test-command Binder and a vendor settings/property Binder) are
     * taken from field reports about a different DiLink 5 app on one firmware
     * build; none of their descriptors, transaction codes, or side effects are
     * verified on our firmware. Until a signature is proven to re-open ADB
     * safely on-device (before/after evidence, confirming no OTA UI/job fires
     * and no shell-spawned daemon is killed), unknown firmware fails closed.
     * Seed this set only from that evidence; a bare {@code "1"} switch stays
     * refused while it is empty.
     */
    static final Set<String> VERIFIED_DILINK5_ADB_FALLBACK_SIGNATURES = Collections.emptySet();

    /** Field kill switch (Settings.Global). Absent = off. */
    public static final String SWITCH_SETTING = "overdrive_adb_fallback";

    /** Optional stage selector (Settings.Global). Absent = both stages. */
    public static final String STAGES_SETTING = "overdrive_adb_fallback_stages";

    /** Switch prefix that enables THIS car by naming its exact probed signature. */
    public static final String SIGNATURE_PREFIX = "sig:";

    private static final String UNKNOWN_SIGNATURE = "fp=?;inc=?";

    private DiLink5AdbFallbackGate() {}

    /** Outcome of {@link #evaluate(String, String, Set)}. */
    public enum Verdict {
        SWITCH_OFF,
        FIRMWARE_UNVERIFIED,
        ALLOWED
    }

    /** Which stages of the fallback the operator permits. */
    public enum Stages {
        OTA_ONLY,
        OTA_THEN_TCP_PORT
    }

    /**
     * Stable identity of the running firmware: {@code "fp=<fingerprint>;inc=<incremental>"}.
     * Null or blank parts become {@code "?"}; each part is trimmed and capped so
     * the value stays loggable and comparable.
     */
    public static String signatureOf(String fingerprint, String incremental) {
        return "fp=" + sanitizePart(fingerprint) + ";inc=" + sanitizePart(incremental);
    }

    private static String sanitizePart(String part) {
        if (part == null) {
            return "?";
        }
        String trimmed = part.trim();
        if (trimmed.isEmpty()) {
            return "?";
        }
        if (trimmed.length() > 256) {
            return trimmed.substring(0, 256);
        }
        return trimmed;
    }

    /**
     * Decide whether the fallback is authorized for this firmware.
     *
     * <ul>
     *   <li>Switch absent/blank or any value other than the recognized ON forms
     *       → {@link Verdict#SWITCH_OFF}.</li>
     *   <li>{@code "sig:<signature>"} whose signature equals the probed one →
     *       {@link Verdict#ALLOWED}; a mismatch (or an unusable probed signature)
     *       → {@link Verdict#FIRMWARE_UNVERIFIED}.</li>
     *   <li>{@code "1"}/{@code "on"}/{@code "true"} → {@link Verdict#ALLOWED} only
     *       when {@code allowlist} contains the probed signature, else
     *       {@link Verdict#FIRMWARE_UNVERIFIED}.</li>
     * </ul>
     */
    public static Verdict evaluate(
            String probedSignature, String switchValue, Set<String> allowlist) {
        String sw = switchValue == null ? "" : switchValue.trim();
        if (sw.isEmpty()) {
            return Verdict.SWITCH_OFF;
        }
        boolean signatureForm = sw.startsWith(SIGNATURE_PREFIX);
        boolean enabledForm = sw.equalsIgnoreCase("1")
                || sw.equalsIgnoreCase("on")
                || sw.equalsIgnoreCase("true");
        if (!signatureForm && !enabledForm) {
            return Verdict.SWITCH_OFF;
        }
        if (!isUsableSignature(probedSignature)) {
            return Verdict.FIRMWARE_UNVERIFIED;
        }
        String probed = probedSignature.trim();
        if (signatureForm) {
            String named = sw.substring(SIGNATURE_PREFIX.length()).trim();
            return named.equals(probed) ? Verdict.ALLOWED : Verdict.FIRMWARE_UNVERIFIED;
        }
        return allowlist != null && allowlist.contains(probed)
                ? Verdict.ALLOWED
                : Verdict.FIRMWARE_UNVERIFIED;
    }

    private static boolean isUsableSignature(String probed) {
        if (probed == null) {
            return false;
        }
        String trimmed = probed.trim();
        return !trimmed.isEmpty() && !trimmed.equals(UNKNOWN_SIGNATURE);
    }

    /**
     * Parse the stage selector. Absent ({@code null}) or {@code "ota,tsport"}
     * permits both stages; everything else (including {@code "ota"}) restricts to
     * the Settings-only Stage A.
     */
    public static Stages parseStages(String value) {
        if (value == null) {
            return Stages.OTA_THEN_TCP_PORT;
        }
        if (value.trim().equalsIgnoreCase("ota,tsport")) {
            return Stages.OTA_THEN_TCP_PORT;
        }
        return Stages.OTA_ONLY;
    }
}
