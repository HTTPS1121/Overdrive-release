package com.overdrive.app.byd.dilink5;

import android.content.Context;
import android.os.SystemClock;

import com.overdrive.app.util.DaemonHttpClient;

import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * DiLink 5 Standby: Android stays Awake while Thundersoft's power service keeps the IVI panel
 * dark and holds input focus with its own window, so a deterrent only shows if that service
 * lights the panel. App process only: the service binds from an app, like the other TS adapters.
 */
public final class Dilink5PanelPower {

    // CarPowerConst DISPLAYPANEL_CENTRAL_IVI and SET_DISPLAY_POWER_STATE_ON/OFF. The setter
    // refuses DISPLAY_POWER_OFF (0) — field log: setOff=false on every restore.
    private static final int PANEL_CENTRAL_IVI = 1;
    private static final int POWER_ON = 1;
    private static final int POWER_OFF = 2;
    // CarPowerSignalStatusEnum OFF(1) and CarPowerWorkModeStatusEnum IVI_PM_STANDBY(4).
    private static final int SIGNAL_OFF = 1;
    private static final int MODE_STANDBY = 4;
    private static final Object LOCK = new Object();
    private static Object manager;
    private static boolean litByUs;
    // Work mode read right after lighting, so a mode our own lighting causes is not read as the owner.
    private static int litMode = -1;

    private Dilink5PanelPower() {}

    /** Blocks up to ~2 s on the service bind; never call on the main thread. */
    public static String lightPanel(Context context) {
        synchronized (LOCK) {
            try {
                Object m = manager(context);
                String before = describe(m);
                Object rc = call(m, "setDisplayPowerState", PANEL_CENTRAL_IVI, POWER_ON);
                if (Boolean.TRUE.equals(rc) && !litByUs) {
                    litByUs = true;
                    litMode = workMode(m);
                }
                return "setOn=" + rc + " before[" + before + "] after[" + describe(m) + "]";
            } catch (Throwable t) {
                return "failed: " + rootMessage(t);
            }
        }
    }

    /**
     * Turns the panel back off only if this process lit it and nobody has since woken the car:
     * the ignition came on, or the work mode moved off what it was right after we lit it.
     */
    public static String restorePanel(Context context) {
        synchronized (LOCK) {
            if (!litByUs) return "skipped (not lit by us)";
            litByUs = false;
            try {
                Object m = manager(context);
                int signal = signal(m);
                int mode = workMode(m);
                if (signal > SIGNAL_OFF
                        || (mode >= 0 && mode != litMode && mode != MODE_STANDBY)
                        || com.overdrive.app.monitor.AccMonitor.isVehicleActive()) {
                    return "skipped (car woke: signal=" + signal + " mode=" + mode
                            + " litMode=" + litMode + ")";
                }
                Object rc = call(m, "setDisplayPowerState", PANEL_CENTRAL_IVI, POWER_OFF);
                return "setOff=" + rc + " after[" + describe(m) + "]";
            } catch (Throwable t) {
                return "failed: " + rootMessage(t);
            }
        }
    }

    /**
     * Null while BYD's power service reports the car off and the IVI in Standby; otherwise why
     * the car counts as in use (the owner is there), so the deterrent must not show.
     */
    public static String carAwakeReason(Context context) {
        synchronized (LOCK) {
            try {
                return awakeReason(manager(context));
            } catch (Throwable t) {
                return null;  // unreadable: the daemon's own gate still applies
            }
        }
    }

    private static String awakeReason(Object m) {
        int signal = signal(m);
        int mode = workMode(m);
        boolean signalOn = signal > SIGNAL_OFF;
        boolean modeAwake = mode >= 0 && mode != MODE_STANDBY;
        return signalOn || modeAwake ? "signal=" + signal + " mode=" + mode : null;
    }

    private static int signal(Object m) {
        return enumValue(safeCall(m, "getCurrentSignalState"), "getCarPowerSignalStatusEnum");
    }

    private static int workMode(Object m) {
        return enumValue(safeCall(m, "getCurrentWorkMode"), "getCarPowerWorkModeStatusEnum");
    }

    private static Object safeCall(Object target, String name) {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** -1 when the status object or its enum is unavailable. */
    private static int enumValue(Object status, String enumGetter) {
        if (status == null) return -1;
        Object e = safeCall(status, enumGetter);
        Object v = e == null ? null : safeCall(e, "getVal");
        return v instanceof Number ? ((Number) v).intValue() : -1;
    }

    /** The app process's log is not uploadable; the daemon records DI5 diagnostics. */
    public static void report(String tag, String detail) {
        Thread t = new Thread(() -> {
            java.net.HttpURLConnection conn = null;
            try {
                conn = DaemonHttpClient.open("/api/vehicle/di5-diag", "POST", 2000, 3000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] body = new JSONObject().put("tag", tag).put("detail", detail)
                        .toString().getBytes(StandardCharsets.UTF_8);
                conn.getOutputStream().write(body);
                conn.getResponseCode();
            } catch (Throwable ignored) {
                // Diagnostics only.
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "Di5DiagReport");
        t.setDaemon(true);
        t.start();
    }

    private static Object manager(Context context) throws Exception {
        if (manager != null) return manager;
        Dilink5SdkInjector.ensure(context);
        ClassLoader loader = Dilink5SdkInjector.class.getClassLoader();
        Class<?> type = Class.forName("com.ts.lib.power.manager.CarPowerManager", true, loader);
        Class<?> listener = Class.forName(
                "com.ts.lib.power.listener.CarPowerEventListener", true, loader);
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        Object m = type.getMethod("getInstance", Context.class, listener).invoke(null, app, null);
        type.getMethod("connect").invoke(m);
        Method connected = type.getMethod("isServiceConnected");
        for (int i = 0; i < 20 && !Boolean.TRUE.equals(connected.invoke(m)); i++) {
            SystemClock.sleep(100L);
        }
        manager = m;
        return m;
    }

    private static String describe(Object m) {
        return "connected=" + safe(m, "isServiceConnected")
                + " display=" + safe(m, "getDisplayInfo", PANEL_CENTRAL_IVI)
                + " backlight=" + safe(m, "getBackLightState")
                + " awake=" + awakeReason(m);
    }

    private static Object call(Object target, String name, int... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        Object[] values = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = int.class;
            values[i] = args[i];
        }
        return target.getClass().getMethod(name, types).invoke(target, values);
    }

    private static String safe(Object target, String name, int... args) {
        try {
            return String.valueOf(call(target, name, args));
        } catch (Throwable t) {
            return "E(" + rootMessage(t) + ")";
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t instanceof InvocationTargetException && t.getCause() != null
                ? t.getCause() : t;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
