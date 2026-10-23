package com.overdrive.app.byd.assist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.assist.DiLink5AssistOffGate.Control;

import org.json.JSONObject;
import org.junit.Test;

public class DiLink5AssistOffProfileTest {

    @Test
    public void defaultsAreAllOff() {
        DiLink5AssistOffProfile fromNull = DiLink5AssistOffProfile.fromVehicle(null);
        DiLink5AssistOffProfile fromEmpty = DiLink5AssistOffProfile.fromVehicle(new JSONObject());
        for (DiLink5AssistOffProfile p : new DiLink5AssistOffProfile[] {
                fromNull, fromEmpty, DiLink5AssistOffProfile.disabled()}) {
            assertFalse(p.anyEnabled());
            for (Control c : Control.values()) assertFalse(c.name(), p.isEnabled(c));
        }
    }

    @Test
    public void readsTheFourVehicleKeysIndependently() throws Exception {
        JSONObject vehicle = new JSONObject()
                .put("modelId", "sealion7")
                .put(DiLink5AssistOffProfile.KEY_LDA, true)
                .put(DiLink5AssistOffProfile.KEY_DMS, true);
        DiLink5AssistOffProfile p = DiLink5AssistOffProfile.fromVehicle(vehicle);
        assertTrue(p.anyEnabled());
        assertFalse(p.isEnabled(Control.CPD));
        assertTrue(p.isEnabled(Control.LDA));
        assertFalse(p.isEnabled(Control.ELKA));
        assertTrue(p.isEnabled(Control.DMS));
        // Non-boolean junk never enables.
        JSONObject junk = new JSONObject().put(DiLink5AssistOffProfile.KEY_CPD, "yes");
        assertFalse(DiLink5AssistOffProfile.fromVehicle(junk).isEnabled(Control.CPD));
    }

    @Test
    public void keysMatchTheConfigNamesSeededByUnifiedConfig() {
        assertEquals("assistOffCpd", DiLink5AssistOffProfile.keyFor(Control.CPD));
        assertEquals("assistOffLda", DiLink5AssistOffProfile.keyFor(Control.LDA));
        assertEquals("assistOffElka", DiLink5AssistOffProfile.keyFor(Control.ELKA));
        assertEquals("assistOffDms", DiLink5AssistOffProfile.keyFor(Control.DMS));
    }

    @Test
    public void toJsonUsesTheShortControlKeys() throws Exception {
        JSONObject json = new DiLink5AssistOffProfile(true, false, true, false).toJson();
        assertEquals(4, json.length());
        assertTrue(json.getBoolean("cpd"));
        assertFalse(json.getBoolean("lda"));
        assertTrue(json.getBoolean("elka"));
        assertFalse(json.getBoolean("dms"));
    }
}
