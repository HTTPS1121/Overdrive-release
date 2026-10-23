package android.hardware.bydauto.doorlock.tsimpl;

import android.hardware.bydauto.TsManagerImpl;

/** Test-only verified manager field layout. */
public final class DoorlockManagerImpl {
    private final TsManagerImpl mTsManagerImpl;
    private int mForTest;

    public DoorlockManagerImpl(TsManagerImpl manager) {
        mTsManagerImpl = manager;
    }
}
