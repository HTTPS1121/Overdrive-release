package android.hardware.bydauto.doorlock;

import android.hardware.bydauto.doorlock.tsimpl.DoorlockManagerImpl;

/** Test-only shape of the verified Di5 SDK, used to exercise the real reflective port. */
public final class BYDAutoDoorLockDevice {
    public static final int DOOR_LOCK_STATE_LOCK = 1;
    public static final int DOOR_LOCK_STATE_UNLOCK = 2;
    public static final int DOOR_LOCK_COMMAND_SUCCESS = 0;
    public static final int DOOR_LOCK_AREA_LEFT_FRONT = 1;
    private final DoorlockManagerImpl mDoorlockManagerImpl;
    public int state = DOOR_LOCK_STATE_LOCK;
    public int reads;
    public int writes;
    public Runnable afterRead;
    public Runnable afterWrite;

    public BYDAutoDoorLockDevice(DoorlockManagerImpl manager) {
        mDoorlockManagerImpl = manager;
    }

    public int getDoorLockStatus(int area) {
        reads++;
        int value = area == DOOR_LOCK_AREA_LEFT_FRONT ? state : 0;
        if (afterRead != null) afterRead.run();
        return value;
    }

    public int setAllDoorsLock(int value) {
        writes++;
        state = value;
        if (afterWrite != null) afterWrite.run();
        return DOOR_LOCK_COMMAND_SUCCESS;
    }
}
