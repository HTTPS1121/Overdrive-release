package com.overdrive.app.byd.dilink5;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.services.KeyMapDispatcher;
import com.overdrive.app.util.DaemonHttpClient;

import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DiLink 5 steering-wheel keys for Key Mapping. The wheel never reaches Android input there:
 * CarInputService hands it to Thundersoft's key service, so subscribe to that service and feed
 * {@link KeyMapDispatcher} synthetic keycodes. BYD's own action still runs; it cannot be consumed.
 */
public final class Dilink5KeySource {

    /** Synthetic keycodes sit above every Android keycode, so DI5 bindings never collide. */
    public static final int KEY_BASE = 0x10000;
    public static final int WHEEL_BASE = 0x20000;

    private static final String APP_PROCESS = "com.overdrive.app";
    private static final int CLICK_LONG = 2;
    private static final int REPORTED_EVENTS_MAX = 30;
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService REPORTS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Dilink5KeyReport");
        t.setDaemon(true);
        return t;
    });
    private static boolean started;
    private static int reportedEvents;
    // Kept so the subscription's identity stays stable for the life of the process.
    private static volatile Object listener;

    private Dilink5KeySource() {}

    /** Idempotent; subscribes off-thread because the Thundersoft adapter connects asynchronously. */
    public static void start(Context context) {
        if (context == null || !DiLink5Platform.isSelected()
                || !APP_PROCESS.equals(Application.getProcessName())) {
            return;
        }
        synchronized (LOCK) {
            if (started) return;
            started = true;
        }
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        Thread t = new Thread(() -> subscribe(app), "Dilink5KeySource");
        t.setDaemon(true);
        t.start();
    }

    private static void subscribe(Context context) {
        try {
            if (!Dilink5SdkInjector.ensure(context)) {
                fail("DiLink 5 SDK unavailable");
                return;
            }
            ClassLoader loader = Dilink5SdkInjector.class.getClassLoader();
            Class<?> settingsClass = Class.forName(
                    "android.hardware.bydauto.SettingsManagerImpl", true, loader);
            Object settings = settingsClass.getMethod("getInstance", Context.class).invoke(
                    null, com.overdrive.app.byd.BydDeviceHelper.withBydPermissionBypass(context));
            Class<?> listenerType = Class.forName(
                    "com.ts.lib.settings.key.OnKeyListener", true, loader);
            Object proxy = Proxy.newProxyInstance(
                    loader, new Class<?>[] {listenerType}, Dilink5KeySource::invoke);
            // An add despite the name; this list is re-subscribed on every key-service reconnect.
            settingsClass.getMethod("setOnKeyListener", listenerType).invoke(settings, proxy);
            listener = proxy;
            KeyMapDispatcher.INSTANCE.warmUp();
            Method getKeys = settingsClass.getMethod("getKeySettingsManager");
            Object keys = null;
            for (int attempt = 0; attempt < 60 && keys == null; attempt++) {
                keys = getKeys.invoke(settings);
                if (keys == null) SystemClock.sleep(500L);
            }
            report(keys != null ? "subscribed" : "listening; key service not connected yet", null);
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException && t.getCause() != null
                    ? t.getCause() : t;
            fail(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private static Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "onKeyClick":
                onKeyClick((Integer) args[0], (Integer) args[1]);
                return null;
            case "onIdlerWheel":
                onWheel((Integer) args[0]);
                return null;
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "Dilink5KeySource";
            default:
                return null;
        }
    }

    /** {@code click}: 1 short, 2 long. A long press is replayed as a held (repeating) DOWN. */
    private static void onKeyClick(int keyValue, int click) {
        int code = KEY_BASE + keyValue;
        boolean longPress = click == CLICK_LONG;
        MAIN.post(() -> {
            KeyMapDispatcher.INSTANCE.onKey(code, true, 0);
            if (longPress) KeyMapDispatcher.INSTANCE.onKey(code, true, 1);
            KeyMapDispatcher.INSTANCE.onKey(code, false, 0);
        });
        noteEvent("key=" + keyValue + " click=" + click + " code=" + code);
    }

    private static void onWheel(int value) {
        int code = WHEEL_BASE + (value & 0xFFFF);
        MAIN.post(() -> {
            KeyMapDispatcher.INSTANCE.onKey(code, true, 0);
            KeyMapDispatcher.INSTANCE.onKey(code, false, 0);
        });
        noteEvent("wheel=" + value + " code=" + code);
    }

    private static void noteEvent(String detail) {
        synchronized (LOCK) {
            if (reportedEvents >= REPORTED_EVENTS_MAX) return;
            reportedEvents++;
        }
        report("event", detail);
    }

    private static void fail(String reason) {
        synchronized (LOCK) {
            started = false;  // the next keep-alive start retries
        }
        report("failed", reason);
    }

    /** This process's log is not uploadable; the daemon records the status. */
    private static void report(String status, String detail) {
        REPORTS.execute(() -> {
            java.net.HttpURLConnection conn = null;
            try {
                conn = DaemonHttpClient.open("/api/keymap/di5-key-source", "POST", 2000, 3000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] body = new JSONObject()
                        .put("status", status)
                        .put("detail", detail != null ? detail : "")
                        .toString().getBytes(StandardCharsets.UTF_8);
                conn.getOutputStream().write(body);
                conn.getResponseCode();
            } catch (Throwable ignored) {
                // Diagnostics only; the key path never depends on the report.
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }
}
