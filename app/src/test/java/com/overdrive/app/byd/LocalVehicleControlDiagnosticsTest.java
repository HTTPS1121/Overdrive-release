package com.overdrive.app.byd;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class LocalVehicleControlDiagnosticsTest {
    public static final class Firmware {
        public static final int DOOR_LOCK_STATE_LOCK = 1;
        public static final int DOOR_LOCK_STATE_UNLOCK = 2;
        public static final String SECRET = "must-never-be-exported";
        int writes, reads;
        public int setAllDoorsLock(int state) { writes++; return 0; }
        public int getPowerLevel() { reads++; return 0; }
        public byte[] getBuffer(int device, int feature) { reads++; return new byte[]{1, 2}; }
        public String getAutoVIN() { reads++; return "private-vin"; }
    }

    @Test public void diagnosticsExposeMethodShapesAndConstantsWithoutInvokingControls() throws Exception {
        Firmware firmware = new Firmware();
        JSONObject result = LocalVehicleControlDiagnostics.describe(firmware);
        assertTrue(result.getBoolean("available"));
        assertEquals(Firmware.class.getName(), result.getString("class"));
        assertTrue(result.getJSONArray("methods").toString().contains("int setAllDoorsLock(int)"));
        assertEquals(2, result.getJSONObject("constants").getInt("DOOR_LOCK_STATE_UNLOCK"));
        assertEquals(0, firmware.writes);
        assertEquals(0, firmware.reads);
    }

    @Test public void diagnosticsDoNotExportVinSecretValuesOrKeyBufferContents() throws Exception {
        String result = LocalVehicleControlDiagnostics.describe(new Firmware()).toString();
        assertFalse(result.contains("private-vin"));
        assertFalse(result.contains("getAutoVIN"));
        assertFalse(result.contains("must-never-be-exported"));
        assertFalse(result.contains("[1,2]"));
    }

    @Test public void absentSdkDevicesAreReportedWithoutBindingOrGuessingMethods() throws Exception {
        JSONObject result = LocalVehicleControlDiagnostics.describe(null);
        assertFalse(result.getBoolean("available"));
        assertFalse(result.has("methods"));
    }
}
