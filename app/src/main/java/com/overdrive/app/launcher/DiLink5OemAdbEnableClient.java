package com.overdrive.app.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Binds the OEM enablement services and asks them to re-open ADB, for the
 * DiLink 5 recovery fallback.
 *
 * <p><b>Call only from the dedicated escalation thread, never on the main
 * thread.</b> The descriptors and transaction codes below are UNVERIFIED on our
 * firmware — they come from field reports about a reference implementation on a
 * different DiLink 5 build — and are reachable only behind
 * {@link DiLink5AdbFallbackGate}. Each call binds a service, raw-transacts one
 * method, and unbinds; no connection is cached and no exception propagates.
 *
 * <p>Two stages: Stage A asks an OTA test-command Binder to set the
 * Settings-level ADB flag (no adbd restart); Stage B asks a vendor settings
 * property Binder to set the classic ADB TCP port, which restarts adbd. Neither
 * stage touches the wireless-debugging/TLS transport nor any reboot-surviving
 * system property.
 */
public final class DiLink5OemAdbEnableClient {

    // Stage A: OTA test-command Binder -> Settings-level ADB enable.
    private static final String OTA_PACKAGE = "com.byd.ota";
    private static final String OTA_SERVICE = "com.byd.ota.view.OtaUiService";
    private static final String OTA_ACTION = "com.intent.action.OTA_UPGRADE_TEST";
    private static final String OTA_DESCRIPTOR = "com.byd.sdkupdate.inner.IOtaTestCommand";
    private static final int OTA_TRANSACTION_TEST_COMMAND = 3;
    private static final int OTA_CMD_ADB = 31;
    // Enables the Settings-level ADB flag. The sibling value 99 would instead
    // flip the wireless-debugging/TLS transport (out of scope) and must never
    // be sent from here.
    private static final int OTA_VAL_ADB_ENABLED = 77;

    // Stage B: vendor settings service -> property adapter -> set ADB TCP port.
    private static final String TS_SETTINGS_PACKAGE = "com.ts.appservice.settings";
    private static final String TS_SETTINGS_SERVICE = "com.ts.appservice.settings.SettingsService";
    private static final String TS_ADAPTER_DESCRIPTOR = "com.ts.lib.settings.ISettingsAdapterService";
    private static final int TS_TRANSACTION_GET_ADAPTER = 2;
    private static final String TS_ADAPTER_PROPERTY = "property";
    private static final String TS_PROPERTY_DESCRIPTOR = "com.ts.lib.settings.property.IPropertySettingsService";
    private static final int TS_TRANSACTION_SET_PROPERTY = 10;
    // A volatile property (not stored across reboot); writing it restarts adbd
    // and re-opens the classic ADB TCP listener.
    private static final String ADB_TCP_PORT_PROPERTY = "service.adb.tcp.port";
    private static final String ADB_TCP_PORT_VALUE = "5555";

    // Camera daemon status IPC (read-only; no ADB involved).
    private static final int CAMERA_DAEMON_TCP_PORT = 19876;

    private DiLink5OemAdbEnableClient() {}

    /** Outcome of one stage. */
    public enum Outcome {
        SENT,
        UNAVAILABLE,
        DENIED,
        TIMEOUT,
        FAILED
    }

    /** Result of one stage: an outcome plus a short detail for diagnostics. */
    public static final class StageResult {
        public final Outcome outcome;
        public final String detail;

        StageResult(Outcome outcome, String detail) {
            this.outcome = outcome;
            if (detail == null) {
                this.detail = "";
            } else if (detail.length() > 120) {
                this.detail = detail.substring(0, 120);
            } else {
                this.detail = detail;
            }
        }
    }

    private interface BinderOp {
        StageResult run(IBinder binder) throws Exception;
    }

    /**
     * Stage A: ask the OTA test-command Binder to set the Settings-level ADB
     * flag. Does not restart adbd.
     */
    public static StageResult requestAdbEnabledViaOta(Context ctx, long timeoutMs) {
        return withService(ctx, OTA_PACKAGE, OTA_SERVICE, OTA_ACTION, timeoutMs, binder -> {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(OTA_DESCRIPTOR);
                data.writeInt(OTA_CMD_ADB);
                data.writeInt(OTA_VAL_ADB_ENABLED);
                binder.transact(OTA_TRANSACTION_TEST_COMMAND, data, reply, 0);
                reply.readException();
                return new StageResult(Outcome.SENT, "ota enable requested");
            } finally {
                reply.recycle();
                data.recycle();
            }
        });
    }

    /**
     * Stage B: ask the vendor settings service for its property adapter, then
     * set the classic ADB TCP port, which restarts adbd. Only reached when the
     * stage selector allows it and no recording is active.
     */
    public static StageResult requestAdbTcpPortViaTsSettings(Context ctx, long timeoutMs) {
        return withService(ctx, TS_SETTINGS_PACKAGE, TS_SETTINGS_SERVICE, null, timeoutMs, binder -> {
            IBinder property;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(TS_ADAPTER_DESCRIPTOR);
                data.writeString(TS_ADAPTER_PROPERTY);
                binder.transact(TS_TRANSACTION_GET_ADAPTER, data, reply, 0);
                reply.readException();
                property = reply.readStrongBinder();
            } finally {
                reply.recycle();
                data.recycle();
            }
            if (property == null) {
                return new StageResult(Outcome.FAILED, "null property adapter");
            }
            Parcel propertyData = Parcel.obtain();
            Parcel propertyReply = Parcel.obtain();
            try {
                propertyData.writeInterfaceToken(TS_PROPERTY_DESCRIPTOR);
                propertyData.writeString(ADB_TCP_PORT_PROPERTY);
                propertyData.writeString(ADB_TCP_PORT_VALUE);
                property.transact(TS_TRANSACTION_SET_PROPERTY, propertyData, propertyReply, 0);
                propertyReply.readException();
                return new StageResult(Outcome.SENT, "port property set");
            } finally {
                propertyReply.recycle();
                propertyData.recycle();
            }
        });
    }

    /**
     * Read-only probe of the camera daemon's recording state over its localhost
     * status IPC. Returns {@code TRUE} when a camera is recording, {@code FALSE}
     * when none is, and {@code null} when the daemon is unreachable or the reply
     * cannot be parsed (so an unknown state never blocks or forces a decision).
     */
    public static Boolean isCameraDaemonRecording() {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", CAMERA_DAEMON_TCP_PORT), 500);
            socket.setSoTimeout(1500);
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            writer.println("{\"cmd\":\"status\"}");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            JSONArray recording = new JSONObject(line).optJSONArray("recording");
            return recording != null && recording.length() > 0;
        } catch (Exception unreachableOrUnparseable) {
            return null;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private static StageResult withService(
            Context ctx, String pkg, String service, String action,
            long timeoutMs, BinderOp op) {
        if (ctx == null) {
            return new StageResult(Outcome.UNAVAILABLE, "no context");
        }
        Intent intent = new Intent();
        if (action != null) {
            intent.setAction(action);
        }
        intent.setComponent(new ComponentName(pkg, service));
        if (ctx.getPackageManager().resolveService(intent, 0) == null) {
            return new StageResult(Outcome.UNAVAILABLE, "unresolved: " + service);
        }

        final CountDownLatch connected = new CountDownLatch(1);
        final AtomicReference<IBinder> binderRef = new AtomicReference<>();
        final ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                binderRef.set(binder);
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // Nothing cached; the finally below unbinds.
            }
        };

        boolean bindRequested;
        try {
            bindRequested = ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE);
        } catch (SecurityException denied) {
            safeUnbind(ctx, conn);
            return new StageResult(Outcome.DENIED, "bind denied: " + denied.getMessage());
        }
        if (!bindRequested) {
            safeUnbind(ctx, conn);
            return new StageResult(Outcome.UNAVAILABLE, "bindService returned false");
        }

        try {
            if (!connected.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return new StageResult(Outcome.TIMEOUT, "timed out binding " + service);
            }
            IBinder binder = binderRef.get();
            if (binder == null) {
                return new StageResult(Outcome.FAILED, "null service binder");
            }
            return op.run(binder);
        } catch (SecurityException denied) {
            return new StageResult(Outcome.DENIED, "transact denied: " + denied.getMessage());
        } catch (RemoteException remote) {
            return new StageResult(Outcome.FAILED, "remote: " + remote.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new StageResult(Outcome.FAILED, "interrupted");
        } catch (Exception other) {
            return new StageResult(Outcome.FAILED,
                    other.getClass().getSimpleName() + ": " + other.getMessage());
        } finally {
            safeUnbind(ctx, conn);
        }
    }

    private static void safeUnbind(Context ctx, ServiceConnection conn) {
        try {
            ctx.unbindService(conn);
        } catch (Throwable ignored) {
            // Unbind of a never-bound connection throws; ignore.
        }
    }
}
