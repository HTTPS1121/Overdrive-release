package android.hardware.bydauto;

import com.ts.lib.caradapter.cabin.CarCabinAdapterManager;

/** Test-only verified TS manager API with independently replaceable cabin binding. */
public final class TsManagerImpl {
    private CarCabinAdapterManager cabin;
    public boolean connected = true;
    public boolean carServiceConnected = true;
    public Runnable afterServiceReady;

    public TsManagerImpl(CarCabinAdapterManager cabin) {
        this.cabin = cabin;
    }

    public boolean isConnected() { return connected; }

    public boolean isCarServiceConnect() {
        boolean ready = carServiceConnected;
        if (afterServiceReady != null) afterServiceReady.run();
        return ready;
    }

    public CarCabinAdapterManager getCarCabinAdapterManager() { return cabin; }

    public void replaceCabin(CarCabinAdapterManager replacement) { cabin = replacement; }
}
