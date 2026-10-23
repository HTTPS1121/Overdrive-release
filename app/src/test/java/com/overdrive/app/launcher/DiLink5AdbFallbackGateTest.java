package com.overdrive.app.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class DiLink5AdbFallbackGateTest {

    private static final String SIG = "fp=brand/car/di5:11/INC123;inc=INC123";

    @Test
    public void shippedAllowlistIsEmpty() {
        assertTrue(DiLink5AdbFallbackGate.VERIFIED_DILINK5_ADB_FALLBACK_SIGNATURES.isEmpty());
    }

    @Test
    public void switchOffForAbsentOrDisabledValues() {
        Set<String> empty = Collections.emptySet();
        assertEquals(DiLink5AdbFallbackGate.Verdict.SWITCH_OFF,
                DiLink5AdbFallbackGate.evaluate(SIG, null, empty));
        assertEquals(DiLink5AdbFallbackGate.Verdict.SWITCH_OFF,
                DiLink5AdbFallbackGate.evaluate(SIG, "", empty));
        assertEquals(DiLink5AdbFallbackGate.Verdict.SWITCH_OFF,
                DiLink5AdbFallbackGate.evaluate(SIG, "0", empty));
        assertEquals(DiLink5AdbFallbackGate.Verdict.SWITCH_OFF,
                DiLink5AdbFallbackGate.evaluate(SIG, "off", empty));
    }

    @Test
    public void enabledFormWithEmptyAllowlistIsUnverified() {
        assertEquals(DiLink5AdbFallbackGate.Verdict.FIRMWARE_UNVERIFIED,
                DiLink5AdbFallbackGate.evaluate(SIG, "1", Collections.emptySet()));
    }

    @Test
    public void enabledFormWithSeededAllowlistIsAllowed() {
        Set<String> seeded = new HashSet<>();
        seeded.add(SIG);
        assertEquals(DiLink5AdbFallbackGate.Verdict.ALLOWED,
                DiLink5AdbFallbackGate.evaluate(SIG, "1", seeded));
        assertEquals(DiLink5AdbFallbackGate.Verdict.ALLOWED,
                DiLink5AdbFallbackGate.evaluate(SIG, "true", seeded));
    }

    @Test
    public void signaturePrefixExactMatchIsAllowed() {
        assertEquals(DiLink5AdbFallbackGate.Verdict.ALLOWED,
                DiLink5AdbFallbackGate.evaluate(SIG, "sig:" + SIG, Collections.emptySet()));
    }

    @Test
    public void signaturePrefixMismatchIsUnverified() {
        assertEquals(DiLink5AdbFallbackGate.Verdict.FIRMWARE_UNVERIFIED,
                DiLink5AdbFallbackGate.evaluate(SIG, "sig:fp=other;inc=other", Collections.emptySet()));
    }

    @Test
    public void blankProbedSignatureIsUnverifiedEvenWhenSwitchOn() {
        assertEquals(DiLink5AdbFallbackGate.Verdict.FIRMWARE_UNVERIFIED,
                DiLink5AdbFallbackGate.evaluate("", "sig:anything", Collections.emptySet()));
        assertEquals(DiLink5AdbFallbackGate.Verdict.FIRMWARE_UNVERIFIED,
                DiLink5AdbFallbackGate.evaluate(
                        DiLink5AdbFallbackGate.signatureOf(null, null),
                        "sig:fp=?;inc=?",
                        Collections.emptySet()));
        assertEquals(DiLink5AdbFallbackGate.Verdict.FIRMWARE_UNVERIFIED,
                DiLink5AdbFallbackGate.evaluate(null, "1", Collections.emptySet()));
    }

    @Test
    public void signatureOfFormatsAndFallsBackToQuestionMarks() {
        assertEquals("fp=FP;inc=INC", DiLink5AdbFallbackGate.signatureOf("FP", "INC"));
        assertEquals("fp=?;inc=?", DiLink5AdbFallbackGate.signatureOf(null, null));
        assertEquals("fp=?;inc=X", DiLink5AdbFallbackGate.signatureOf("  ", "X"));
    }

    @Test
    public void parseStagesTable() {
        assertEquals(DiLink5AdbFallbackGate.Stages.OTA_THEN_TCP_PORT,
                DiLink5AdbFallbackGate.parseStages(null));
        assertEquals(DiLink5AdbFallbackGate.Stages.OTA_THEN_TCP_PORT,
                DiLink5AdbFallbackGate.parseStages("ota,tsport"));
        assertEquals(DiLink5AdbFallbackGate.Stages.OTA_ONLY,
                DiLink5AdbFallbackGate.parseStages("ota"));
        assertEquals(DiLink5AdbFallbackGate.Stages.OTA_ONLY,
                DiLink5AdbFallbackGate.parseStages(""));
        assertEquals(DiLink5AdbFallbackGate.Stages.OTA_ONLY,
                DiLink5AdbFallbackGate.parseStages("garbage"));
    }
}
