package com.overdrive.app.byd.assist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.assist.DiLink5AssistOffGate.Control;

import org.junit.Test;

public class DiLink5AssistOffControlsTest {

    private static final int NA = DiLink5AssistOffControls.READ_UNAVAILABLE;

    @Test
    public void routesAreSdkMethodNamesWithTheValidatedOffValues() {
        DiLink5AssistOffControls.Spec cpd = DiLink5AssistOffControls.spec(Control.CPD);
        assertEquals("setCpdStatus", cpd.setter);
        assertEquals("getCpdStatus", cpd.getter);
        assertEquals(2, cpd.offWriteValue);
        assertEquals(2, cpd.offReadback);

        DiLink5AssistOffControls.Spec lda = DiLink5AssistOffControls.spec(Control.LDA);
        assertEquals("setLaneAssistType", lda.setter);
        assertEquals("getLaneAssistType", lda.getter);
        assertEquals(1, lda.offWriteValue);
        assertEquals(0, lda.offReadback);

        DiLink5AssistOffControls.Spec elka = DiLink5AssistOffControls.spec(Control.ELKA);
        assertEquals("setEmergencyLaneAssistanceState", elka.setter);
        assertEquals("getEmergencyLaneAssistance32FState", elka.getter);
        assertEquals(1, elka.offWriteValue);
        assertEquals(0, elka.offReadback);

        // DMS is a vendor-settings Binder route with no SDK getter.
        assertNull(DiLink5AssistOffControls.spec(Control.DMS));
        assertNull(DiLink5AssistOffControls.spec(null));
    }

    @Test
    public void readinessIsARealCpdState() {
        assertTrue(DiLink5AssistOffControls.isReadinessRead(1));
        assertTrue(DiLink5AssistOffControls.isReadinessRead(2));
        assertFalse(DiLink5AssistOffControls.isReadinessRead(0));
        assertFalse(DiLink5AssistOffControls.isReadinessRead(3));
        assertFalse(DiLink5AssistOffControls.isReadinessRead(NA));
    }

    @Test
    public void cpdZeroIsNeverVerifiedAndTwoIsOff() {
        assertFalse(DiLink5AssistOffControls.zeroIsTrustworthy(Control.CPD, true));
        assertFalse(DiLink5AssistOffControls.isOff(Control.CPD, 0, true));
        assertFalse(DiLink5AssistOffControls.isOff(Control.CPD, 1, true));
        assertTrue(DiLink5AssistOffControls.isOff(Control.CPD, 2, true));
        assertTrue(DiLink5AssistOffControls.isOff(Control.CPD, 2, false));
        assertFalse(DiLink5AssistOffControls.isOff(Control.CPD, NA, true));
    }

    @Test
    public void laneZeroIsTrustedOnlyAfterReadiness() {
        for (Control lane : new Control[] {Control.LDA, Control.ELKA}) {
            assertFalse(lane.name(), DiLink5AssistOffControls.zeroIsTrustworthy(lane, false));
            assertTrue(lane.name(), DiLink5AssistOffControls.zeroIsTrustworthy(lane, true));
            // Straight after power-on a 0 is ambiguous (not connected yet).
            assertFalse(lane.name(), DiLink5AssistOffControls.isOff(lane, 0, false));
            assertTrue(lane.name(), DiLink5AssistOffControls.isOff(lane, 0, true));
            // Any non-zero read is "on" regardless of readiness.
            assertFalse(lane.name(), DiLink5AssistOffControls.isOff(lane, 1, true));
            assertFalse(lane.name(), DiLink5AssistOffControls.isOff(lane, 3, true));
            assertFalse(lane.name(), DiLink5AssistOffControls.isOff(lane, NA, true));
        }
    }

    @Test
    public void dmsIsNeverOffByReadback() {
        assertFalse(DiLink5AssistOffControls.zeroIsTrustworthy(Control.DMS, true));
        assertFalse(DiLink5AssistOffControls.isOff(Control.DMS, 0, true));
        assertFalse(DiLink5AssistOffControls.isOff(Control.DMS, 2, true));
        assertFalse(DiLink5AssistOffControls.isOff(null, 0, true));
    }
}
