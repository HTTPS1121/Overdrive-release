package com.overdrive.app.byd.assist;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Raw-Parcel client for the DiLink 5 vendor settings service's driver-monitoring (DMS, "Cabin
 * Perception") adapter: the DMS switch (monitoring), the fatigue alert and the attention alert are
 * each requested off.
 *
 * <p>The DiPilot device's own DMS getter/setter do not track the real Settings toggle on the target
 * car; this settings-service route does. It offers no readback, so the only successful outcome is
 * {@link Outcome#REQUEST_SENT}: the status vocabulary for DMS has no "off" state and the applier
 * never reports one. The transaction codes come from one firmware build and are reachable only
 * behind {@link DiLink5AssistOffGate}.
 *
 * <p>Delivery is tracked per transaction across the attempts of one cycle ({@link Delivery}): each
 * attempt sends the settings not yet acknowledged (the writes are idempotent {@code writeInt(0)}),
 * records which ones the service acknowledged, and the applier reports the cumulative outcome —
 * all three acknowledged → {@code request_sent}; some → {@code request_sent} with a
 * {@code partial: monitoring=ok, fatigue=failed, attention=ok} detail; none → {@code failed}.
 *
 * <p>Call only from the applier's worker thread. Binds, transacts, unbinds; nothing is cached and
 * no exception propagates.
 */
public final class DiLink5DmsSettingsClient {

    private static final String SETTINGS_PACKAGE = "com.ts.appservice.settings";
    private static final String SETTINGS_SERVICE = "com.ts.appservice.settings.SettingsService";
    /** Interface token of the root adapter service; also the bind action the service filters on. */
    private static final String ADAPTER_DESCRIPTOR = "com.ts.lib.settings.ISettingsAdapterService";
    /** {@code ISettingsAdapterService}: {@code IBinder getAdapter(String name)}. */
    private static final int TX_GET_ADAPTER = 2;
    private static final String ADAPTER_DMS = "dms";

    private static final String DMS_DESCRIPTOR = "com.ts.lib.settings.dms.IDmsSettingsService";
    /** {@code IDmsSettingsService} setters, each {@code void set*(int)}; no return value. */
    private static final int DMS_TX_SWITCH = 2;
    private static final int DMS_TX_FATIGUE = 4;
    private static final int DMS_TX_ATTENTION = 5;
    private static final int DMS_OFF = 0;

    private DiLink5DmsSettingsClient() {}

    /** The three {@code IDmsSettingsService} off-writes, in send order. */
    public enum Setting {
        MONITORING("monitoring", DMS_TX_SWITCH),
        FATIGUE("fatigue", DMS_TX_FATIGUE),
        ATTENTION("attention", DMS_TX_ATTENTION);

        /** Name used in the {@code partial: ...} detail. */
        public final String label;
        final int transactionCode;

        Setting(String label, int transactionCode) {
            this.label = label;
            this.transactionCode = transactionCode;
        }
    }

    /**
     * Pure per-cycle delivery record: the best outcome of every {@link Setting} across attempts.
     * A setting once acknowledged stays acknowledged (a later failed retry cannot demote it);
     * one never attempted or always failed reads {@code failed}. Shared by the client (records)
     * and the applier (final state after the attempt budget).
     */
    public static final class Delivery {
        private final Map<Setting, Boolean> outcomes = new EnumMap<>(Setting.class);

        /** Records one transaction's outcome; {@code true} is sticky. */
        public void record(Setting setting, boolean acknowledged) {
            if (setting == null) return;
            if (acknowledged || !Boolean.TRUE.equals(outcomes.get(setting))) {
                outcomes.put(setting, acknowledged);
            }
        }

        public boolean isAcknowledged(Setting setting) {
            return Boolean.TRUE.equals(outcomes.get(setting));
        }

        /** Settings still to send: everything not yet acknowledged, in send order. */
        public Set<Setting> pending() {
            Set<Setting> pending = EnumSet.noneOf(Setting.class);
            for (Setting s : Setting.values()) {
                if (!isAcknowledged(s)) pending.add(s);
            }
            return pending;
        }

        public boolean allAcknowledged() {
            return pending().isEmpty();
        }

        public boolean anyAcknowledged() {
            for (Setting s : Setting.values()) {
                if (isAcknowledged(s)) return true;
            }
            return false;
        }

        /** {@code "monitoring=ok, fatigue=failed, attention=ok"}, always all three in send order. */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (Setting s : Setting.values()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(s.label).append('=').append(isAcknowledged(s) ? "ok" : "failed");
            }
            return sb.toString();
        }

        /**
         * Detail of one attempt that did not acknowledge everything: {@code "partial: <summary>"}
         * once at least one setting is acknowledged (cumulatively, across attempts), {@code "none
         * acknowledged: <summary>"} while nothing is, followed by {@code "; <firstError>"} when an
         * error is known. The applier records it as the {@code pending} detail and, after the last
         * attempt, as the {@code failed} detail.
         */
        public String attemptDetail(String firstError) {
            return (anyAcknowledged() ? "partial: " : "none acknowledged: ") + summary()
                    + (firstError == null || firstError.isEmpty() ? "" : "; " + firstError);
        }

        /**
         * Final state for the status file once no further attempt will be made: all three
         * acknowledged → {@link Outcome#REQUEST_SENT} with the summary; some → {@link
         * Outcome#REQUEST_SENT} with {@code "partial: <summary>"}; none → {@link Outcome#FAILED}
         * with {@code lastDetail}. Never anything stronger than {@code REQUEST_SENT}.
         */
        public Result finalResult(String lastDetail) {
            if (allAcknowledged()) return new Result(Outcome.REQUEST_SENT, summary());
            if (anyAcknowledged()) return new Result(Outcome.REQUEST_SENT, "partial: " + summary());
            return new Result(Outcome.FAILED, lastDetail);
        }
    }

    public enum Outcome {
        REQUEST_SENT,
        UNAVAILABLE,
        TIMEOUT,
        FAILED
    }

    /** An outcome plus a short (≤120 chars) detail for the status surface and the log. */
    public static final class Result {
        public final Outcome outcome;
        public final String detail;

        Result(Outcome outcome, String detail) {
            this.outcome = outcome;
            if (detail == null) {
                this.detail = "";
            } else if (detail.length() > 120) {
                this.detail = detail.substring(0, 120);
            } else {
                this.detail = detail;
            }
        }

        @Override
        public String toString() {
            return detail.isEmpty() ? outcome.name() : outcome.name() + ": " + detail;
        }
    }

    /**
     * One attempt: requests every {@link Setting} not yet acknowledged in {@code delivery} off
     * through the {@code dms} adapter obtained from the root settings service, recording each
     * transaction's outcome. Returns {@link Outcome#REQUEST_SENT} only when, after this attempt,
     * all three settings are acknowledged (cumulatively); otherwise {@link Outcome#FAILED} with the
     * {@link Delivery#attemptDetail} ({@code partial: ...} once something is acknowledged,
     * {@code none acknowledged: ...} before that) so the applier retries the rest.
     */
    public static Result requestOff(Context context, long timeoutMs, Delivery delivery) {
        if (delivery == null) return new Result(Outcome.FAILED, "no delivery record");
        if (context == null) return new Result(Outcome.UNAVAILABLE, "no context");
        Intent intent = new Intent(ADAPTER_DESCRIPTOR)
                .setComponent(new ComponentName(SETTINGS_PACKAGE, SETTINGS_SERVICE));
        try {
            if (context.getPackageManager().resolveService(intent, 0) == null) {
                return new Result(Outcome.UNAVAILABLE, "unresolved: " + SETTINGS_SERVICE);
            }
        } catch (Throwable t) {
            return new Result(Outcome.UNAVAILABLE, "resolve failed: " + t.getMessage());
        }

        final CountDownLatch connected = new CountDownLatch(1);
        final AtomicReference<IBinder> binderRef = new AtomicReference<>();
        final ServiceConnection connection = new ServiceConnection() {
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
            bindRequested = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException denied) {
            safeUnbind(context, connection);
            return new Result(Outcome.FAILED, "bind denied: " + denied.getMessage());
        }
        if (!bindRequested) {
            safeUnbind(context, connection);
            return new Result(Outcome.UNAVAILABLE, "bindService returned false");
        }

        try {
            if (!connected.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return new Result(Outcome.TIMEOUT, "timed out binding " + SETTINGS_SERVICE);
            }
            IBinder root = binderRef.get();
            if (root == null) {
                return new Result(Outcome.FAILED, "null service binder");
            }
            IBinder dms = adapter(root);
            if (dms == null) {
                return new Result(Outcome.FAILED, "null dms adapter");
            }
            // Each pending setting is its own transaction: one failure must not stop the others,
            // and the per-setting verdict is what the status reports.
            String firstError = null;
            for (Setting setting : delivery.pending()) {
                try {
                    sendOff(dms, setting.transactionCode);
                    delivery.record(setting, true);
                } catch (RemoteException | RuntimeException failure) {
                    delivery.record(setting, false);
                    if (firstError == null) {
                        firstError = setting.label + ": " + failure.getClass().getSimpleName()
                                + (failure.getMessage() == null ? "" : " " + failure.getMessage());
                    }
                }
            }
            if (delivery.allAcknowledged()) {
                return new Result(Outcome.REQUEST_SENT, delivery.summary());
            }
            // "partial: ..." once something is acknowledged, "none acknowledged: ..." before that.
            return new Result(Outcome.FAILED, delivery.attemptDetail(firstError));
        } catch (SecurityException denied) {
            return new Result(Outcome.FAILED, "transact denied: " + denied.getMessage());
        } catch (RemoteException remote) {
            return new Result(Outcome.FAILED, "remote: " + remote.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.FAILED, "interrupted");
        } catch (Exception other) {
            return new Result(Outcome.FAILED,
                    other.getClass().getSimpleName() + ": " + other.getMessage());
        } finally {
            safeUnbind(context, connection);
        }
    }

    /** Root adapter service: {@code getAdapter("dms")} returns the DMS sub-binder. */
    private static IBinder adapter(IBinder root) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ADAPTER_DESCRIPTOR);
            data.writeString(ADAPTER_DMS);
            root.transact(TX_GET_ADAPTER, data, reply, 0);
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** One {@code IDmsSettingsService} setter with {@link #DMS_OFF}. */
    private static void sendOff(IBinder dms, int transactionCode) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DMS_DESCRIPTOR);
            data.writeInt(DMS_OFF);
            dms.transact(transactionCode, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void safeUnbind(Context context, ServiceConnection connection) {
        try {
            context.unbindService(connection);
        } catch (Throwable ignored) {
            // Unbind of a never-bound connection throws; ignore.
        }
    }
}
