package com.overdrive.app.byd.assist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.assist.DiLink5AssistOffGate.Control;

import org.junit.Test;

public class DiLink5AssistOffGateTest {

    private static final DiLink5AssistOffProfile ALL_ON =
            new DiLink5AssistOffProfile(true, true, true, true);

    @Test
    public void offDiLink5NothingIsAllowed() {
        for (Control c : Control.values()) {
            assertFalse(c.name(), DiLink5AssistOffGate.allows(
                    false, "sealion7", "user", ALL_ON, c));
        }
        assertFalse(DiLink5AssistOffGate.modelConfirmedForProfile(false, "sealion7", "user"));
    }

    @Test
    public void unconfirmedModelSourceIsNotAnExplicitSelection() {
        // The DiLink 5 renderer default is modelId=sealion7 with modelSource=unset: it must never
        // enable a write. Missing/blank provenance on a stored model normalizes to legacy (a
        // migration stamp), which the profile rejects as well — see legacyAndAutoProvenanceAreRejected.
        assertFalse(DiLink5AssistOffGate.modelConfirmedForProfile(true, "sealion7", "unset"));
        assertFalse(DiLink5AssistOffGate.modelConfirmedForProfile(true, "sealion7", "UNSET"));
        assertFalse(DiLink5AssistOffGate.modelConfirmedForProfile(true, "sealion7", ""));
        assertFalse(DiLink5AssistOffGate.modelConfirmedForProfile(true, "sealion7", null));
        assertFalse(DiLink5AssistOffGate.modelConfirmedForProfile(true, "", "user"));
        assertFalse(DiLink5AssistOffGate.modelConfirmedForProfile(true, null, "user"));
        for (Control c : Control.values()) {
            assertFalse(c.name(), DiLink5AssistOffGate.allows(
                    true, "sealion7", "unset", ALL_ON, c));
        }
    }

    @Test
    public void legacyAndAutoProvenanceAreRejected() {
        // Only a user selection counts: `legacy` is stamped by config migrations (getVehicle
        // backfill, applyDefaults), `auto` would be a detector's guess. Neither is a user statement
        // that the car is a Sealion 7, so the model gate — and with it the server-side
        // assistOffAvailable that greys out the toggles — is closed for both.
        for (String source : new String[] {"legacy", "LEGACY", " legacy ", "auto", "AUTO"}) {
            assertFalse(source, DiLink5AssistOffGate.modelConfirmedForProfile(
                    true, "sealion7", source));
            for (Control c : Control.values()) {
                assertFalse(source + "/" + c, DiLink5AssistOffGate.allows(
                        true, "sealion7", source, ALL_ON, c));
            }
        }
        // An unknown source token normalizes to legacy when a model is present: rejected too.
        assertFalse(DiLink5AssistOffGate.modelConfirmedForProfile(true, "sealion7", "imported"));
    }

    @Test
    public void otherModelsAreRejectedEvenWhenExplicit() {
        assertFalse(DiLink5AssistOffGate.modelConfirmedForProfile(true, "seal", "user"));
        assertFalse(DiLink5AssistOffGate.modelConfirmedForProfile(true, "atto3", "user"));
        assertFalse(DiLink5AssistOffGate.modelConfirmedForProfile(true, "sealion6", "user"));
        for (Control c : Control.values()) {
            assertFalse(c.name(), DiLink5AssistOffGate.allows(
                    true, "seal", "user", ALL_ON, c));
        }
    }

    @Test
    public void optInOffBlocksThatControlOnly() {
        DiLink5AssistOffProfile cpdOnly = new DiLink5AssistOffProfile(true, false, false, false);
        assertTrue(DiLink5AssistOffGate.allows(true, "sealion7", "user", cpdOnly, Control.CPD));
        assertFalse(DiLink5AssistOffGate.allows(true, "sealion7", "user", cpdOnly, Control.LDA));
        assertFalse(DiLink5AssistOffGate.allows(true, "sealion7", "user", cpdOnly, Control.ELKA));
        assertFalse(DiLink5AssistOffGate.allows(true, "sealion7", "user", cpdOnly, Control.DMS));
        for (Control c : Control.values()) {
            assertFalse(c.name(), DiLink5AssistOffGate.allows(
                    true, "sealion7", "user", DiLink5AssistOffProfile.disabled(), c));
        }
    }

    @Test
    public void allTermsHoldAllowsEachOptedInControl() {
        // The only accepted provenance is a user selection (normalizeSource → "user").
        for (String source : new String[] {"user", "USER", " user "}) {
            assertTrue(source, DiLink5AssistOffGate.modelConfirmedForProfile(
                    true, "sealion7", source));
            for (Control c : Control.values()) {
                assertTrue(source + "/" + c, DiLink5AssistOffGate.allows(
                        true, "sealion7", source, ALL_ON, c));
            }
        }
    }

    @Test
    public void writeDenialReReadsTheLiveConfigAndKeepsTheAccEdgeTerm() {
        DiLink5AssistOffProfile cpdLdaAtEdge = new DiLink5AssistOffProfile(true, true, false, false);
        // Nothing changed since the ACC edge: both opted-in controls may be written.
        assertNull(DiLink5AssistOffGate.writeDenial(
                true, cpdLdaAtEdge, "sealion7", "user", cpdLdaAtEdge, Control.CPD));
        assertNull(DiLink5AssistOffGate.writeDenial(
                true, cpdLdaAtEdge, "sealion7", "user", cpdLdaAtEdge, Control.LDA));
        // Opt-in cleared during the readiness wait: that control is denied, the other is not.
        DiLink5AssistOffProfile ldaOnly = new DiLink5AssistOffProfile(false, true, false, false);
        assertEquals(DiLink5AssistOffGate.DENIAL_OPT_IN_TURNED_OFF,
                DiLink5AssistOffGate.writeDenial(
                        true, cpdLdaAtEdge, "sealion7", "user", ldaOnly, Control.CPD));
        assertNull(DiLink5AssistOffGate.writeDenial(
                true, cpdLdaAtEdge, "sealion7", "user", ldaOnly, Control.LDA));
        assertEquals("opt-in turned off during start-up", DiLink5AssistOffGate.DENIAL_OPT_IN_TURNED_OFF);
        // An opt-in flipped ON mid-cycle still waits for the next ignition (ACC-edge term).
        assertEquals(DiLink5AssistOffGate.DENIAL_NOT_OPTED_IN_AT_ACC_ON,
                DiLink5AssistOffGate.writeDenial(
                        true, cpdLdaAtEdge, "sealion7", "user", ALL_ON, Control.ELKA));
        // Model or provenance changed underneath the run.
        assertEquals(DiLink5AssistOffGate.DENIAL_MODEL_CHANGED, DiLink5AssistOffGate.writeDenial(
                true, cpdLdaAtEdge, "seal", "user", cpdLdaAtEdge, Control.CPD));
        assertEquals(DiLink5AssistOffGate.DENIAL_MODEL_CHANGED, DiLink5AssistOffGate.writeDenial(
                true, cpdLdaAtEdge, "sealion7", "legacy", cpdLdaAtEdge, Control.CPD));
        // Platform no longer DiLink 5, unreadable (null) live profile, null inputs: all denied.
        assertEquals(DiLink5AssistOffGate.DENIAL_PLATFORM_CHANGED, DiLink5AssistOffGate.writeDenial(
                false, cpdLdaAtEdge, "sealion7", "user", cpdLdaAtEdge, Control.CPD));
        assertEquals(DiLink5AssistOffGate.DENIAL_OPT_IN_TURNED_OFF, DiLink5AssistOffGate.writeDenial(
                true, cpdLdaAtEdge, "sealion7", "user", null, Control.CPD));
        assertEquals(DiLink5AssistOffGate.DENIAL_NOT_OPTED_IN_AT_ACC_ON,
                DiLink5AssistOffGate.writeDenial(
                        true, null, "sealion7", "user", ALL_ON, Control.CPD));
        assertEquals(DiLink5AssistOffGate.DENIAL_NOT_OPTED_IN_AT_ACC_ON,
                DiLink5AssistOffGate.writeDenial(
                        true, ALL_ON, "sealion7", "user", ALL_ON, null));
    }

    @Test
    public void modelIdIsCaseInsensitiveAndTrimmed() {
        assertTrue(DiLink5AssistOffGate.modelConfirmedForProfile(true, "SeaLion7", "user"));
        assertTrue(DiLink5AssistOffGate.modelConfirmedForProfile(true, "  sealion7 ", "user"));
        assertTrue(DiLink5AssistOffGate.allows(true, "SEALION7", "user", ALL_ON, Control.ELKA));
    }

    @Test
    public void nullProfileOrControlNeverAllows() {
        assertFalse(DiLink5AssistOffGate.allows(true, "sealion7", "user", null, Control.CPD));
        assertFalse(DiLink5AssistOffGate.allows(true, "sealion7", "user", ALL_ON, null));
    }
}
