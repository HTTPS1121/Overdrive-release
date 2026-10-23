package com.ts.lib.caradapter.cabin;

import android.os.IInterface;

/** Test-only verified cabin field layout. */
public final class CarCabinAdapterManager {
    private IInterface mService;

    public CarCabinAdapterManager(IInterface service) { mService = service; }

    public void replaceService(IInterface replacement) { mService = replacement; }
}
