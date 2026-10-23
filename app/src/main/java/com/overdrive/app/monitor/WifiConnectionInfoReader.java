package com.overdrive.app.monitor;

import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Wi-Fi connection details for the shell-uid daemon without the Wi-Fi service's dump: a direct
 * {@code IWifiManager.getConnectionInfo} call under the shell package, whose NETWORK_SETTINGS
 * grant returns the real SSID with no location gate.
 */
final class WifiConnectionInfoReader {

    static final String SHELL_PACKAGE = "com.android.shell";
    private static final int INVALID_RSSI = -127;

    /** One read. Null fields are unknown; {@code ssid} is also null when the name is withheld. */
    static final class Info {
        final String ssid;
        final Integer rssi;
        final Boolean meteredHint;

        Info(String ssid, Integer rssi, Boolean meteredHint) {
            this.ssid = ssid;
            this.rssi = rssi;
            this.meteredHint = meteredHint;
        }
    }

    static final Info NOT_CONNECTED = new Info(null, null, null);

    private static final Object LOCK = new Object();
    private static FutureTask<Info> inFlight;
    private static long inFlightStartedNanos;
    private static volatile String lastFailure = "";

    private WifiConnectionInfoReader() {}

    /** Null when the call failed or missed {@code deadlineMs}. */
    static Info read(long deadlineMs) {
        return read(deadlineMs, WifiConnectionInfoReader::readNow);
    }

    /**
     * One call in flight at a time: concurrent callers share it, and a call still blocked past
     * its deadline makes later callers return null at once instead of queueing behind it.
     */
    static Info read(long deadlineMs, Callable<Info> source) {
        FutureTask<Info> task;
        long startedNanos;
        boolean start = false;
        synchronized (LOCK) {
            if (inFlight == null || inFlight.isDone()) {
                inFlight = new FutureTask<>(source);
                inFlightStartedNanos = System.nanoTime();
                start = true;
            }
            task = inFlight;
            startedNanos = inFlightStartedNanos;
        }
        if (start) {
            Thread worker = new Thread(task, "NetworkMonitor-WifiInfo");
            worker.setDaemon(true);
            worker.start();
        }
        long remainingMs = deadlineMs
                - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        if (remainingMs <= 0) {
            lastFailure = "stalled";
            return null;
        }
        try {
            return task.get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (TimeoutException e) {
            lastFailure = "timeout";
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            lastFailure = cause == null ? "failed" : cause.getClass().getSimpleName();
            return null;
        } catch (CancellationException e) {
            lastFailure = "cancelled";
            return null;
        }
    }

    /** Why the last read returned null; "" before any failure. */
    static String lastFailure() {
        return lastFailure;
    }

    /** SDK 29: the connectivity service lists the Wi-Fi agent's SSID in its capabilities. */
    static String ssidFromConnectivity(long deadlineMs) {
        return parseConnectivitySsid(BoundedShell.run(
                "dumpsys connectivity networks 2>/dev/null | grep -F 'Transports: WIFI'",
                deadlineMs).output);
    }

    /** First SSID on a Wi-Fi {@code NetworkAgentInfo} line; null when absent or withheld. */
    static String parseConnectivitySsid(String output) {
        if (output == null) {
            return null;
        }
        for (String raw : output.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.startsWith("NetworkAgentInfo{") || !line.contains("Transports: WIFI")) {
                continue;
            }
            int key = line.indexOf(" SSID: \"");
            if (key < 0) {
                continue;
            }
            int start = key + " SSID: ".length();
            // The SSID is the last capabilities field: ... SSID: "name"]}
            int end = line.indexOf("\"]}", start + 1);
            if (end < 0) {
                continue;
            }
            String ssid = WifiStatusParser.normalizeSsid(line.substring(start, end + 1));
            if (ssid != null) {
                return ssid;
            }
        }
        return null;
    }

    private static Info readNow() throws Exception {
        try {
            com.overdrive.app.shell.HiddenApiBypass.INSTANCE.bypass();
        } catch (Throwable ignored) {
        }
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "wifi");
        if (binder == null) {
            throw new IllegalStateException("wifi service unavailable");
        }
        Object service = Class.forName("android.net.wifi.IWifiManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Class<?> api = Class.forName("android.net.wifi.IWifiManager");
        Object result;
        Method withFeature = connectionInfoMethod(api, String.class, String.class);
        if (withFeature != null) {
            result = withFeature.invoke(service, SHELL_PACKAGE, null);
        } else {
            Method legacy = connectionInfoMethod(api, String.class);
            if (legacy == null) {
                throw new NoSuchMethodException("getConnectionInfo");
            }
            result = legacy.invoke(service, SHELL_PACKAGE);
        }
        if (!(result instanceof WifiInfo)) {
            throw new IllegalStateException("no WifiInfo");
        }
        return fromWifiInfo((WifiInfo) result);
    }

    /** Android 11+ takes (package, featureId); Android 10 takes (package). */
    private static Method connectionInfoMethod(Class<?> api, Class<?>... params) {
        try {
            return api.getMethod("getConnectionInfo", params);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static Info fromWifiInfo(WifiInfo info) {
        if (info.getSupplicantState() != SupplicantState.COMPLETED) {
            return NOT_CONNECTED;
        }
        Boolean metered = null;
        try {
            Object hint = WifiInfo.class.getMethod("getMeteredHint").invoke(info);
            if (hint instanceof Boolean) {
                metered = (Boolean) hint;
            }
        } catch (Throwable ignored) {
        }
        int rssi = info.getRssi();
        return new Info(WifiStatusParser.normalizeSsid(info.getSSID()),
                rssi > INVALID_RSSI && rssi <= 0 ? rssi : null, metered);
    }
}
