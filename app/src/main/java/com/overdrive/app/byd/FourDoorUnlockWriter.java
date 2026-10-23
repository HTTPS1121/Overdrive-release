package com.overdrive.app.byd;

import android.content.Context;

/**
 * Generic vendor device for the bodywork four-door write. Its type and permission declarations are
 * the ones the field-proven generic writer uses; legacy firmware only (DiLink 5 stubs {@code set}).
 */
final class FourDoorUnlockWriter extends android.hardware.bydauto.AbsBYDAutoDevice {
    private static final int DEVICE_BODYWORK = 1001;
    private static final int VALUE_UNLOCK = 1;

    FourDoorUnlockWriter(Context context) {
        super(context);
    }

    int unlock() {
        return set(DEVICE_BODYWORK, BydFeatureIds.BODYWORK_FOUR_DOOR_SET, VALUE_UNLOCK);
    }

    public int getDevicetype() { return 0; }

    public int getType() { return 1031; }

    public int[] getFeatureList() { return new int[0]; }

    public String getGetPermission() { return null; }

    public String getSetPermission() { return null; }
}
