package com.overdrive.app.power;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/** Small framework adapters for the optional, app-process parked transport. */
public final class ParkedLink {
    private ParkedLink() {}

    private static boolean loaded;
    private static int compatibility = ParkedLinkContract.RESULT_LIBRARY_UNAVAILABLE;

    /** Supports the normal app loader and an explicit installed-library path. */
    public static synchronized int tryLoadLibrary(String nativeLibDir) {
        if (!loaded) {
            try {
                System.loadLibrary("odpark");
                loaded = true;
            } catch (Throwable ignored) {
                if (nativeLibDir != null && !nativeLibDir.isEmpty()) {
                    try {
                        System.load(nativeLibDir + "/libodpark.so");
                        loaded = true;
                    } catch (Throwable unavailable) {
                        return ParkedLinkContract.RESULT_LIBRARY_UNAVAILABLE;
                    }
                } else {
                    return ParkedLinkContract.RESULT_LIBRARY_UNAVAILABLE;
                }
            }
            try {
                compatibility = nativeAbiVersion() == ParkedLinkContract.ABI_VERSION
                        ? ParkedLinkContract.RESULT_CONFIRMED
                        : ParkedLinkContract.RESULT_LIBRARY_INCOMPATIBLE;
            } catch (Throwable incompatible) {
                compatibility = ParkedLinkContract.RESULT_LIBRARY_INCOMPATIBLE;
            }
        }
        return compatibility;
    }

    static int execute(Context context, int operation) {
        if (context == null || operation < ParkedLinkContract.OP_ASSERT
                || operation > ParkedLinkContract.OP_DETACH) {
            return ParkedLinkContract.RESULT_BAD_REQUEST;
        }
        try {
            int available = tryLoadLibrary(context.getApplicationInfo().nativeLibraryDir);
            if (available != ParkedLinkContract.RESULT_CONFIRMED) return available;
            return nativeExecute(context, operation);
        } catch (Throwable failure) {
            // No exception text: framework errors can contain private endpoints.
            return ParkedLinkContract.RESULT_INTERNAL_FAILED;
        }
    }

    private static native int nativeAbiVersion();
    private static native int nativeExecute(Context context, int operation);

    /** One adapter per binding attempt; native code fences every event by token. */
    static final class Connection implements ServiceConnection, IBinder.DeathRecipient {
        private final long token;

        Connection(long token) {
            this.token = token;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            nativeEvent(token, 1, service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            nativeEvent(token, 2, null);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            nativeEvent(token, 3, null);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            nativeEvent(token, 4, null);
        }

        @Override
        public void binderDied() {
            nativeEvent(token, 5, null);
        }

        private static native void nativeEvent(long token, int event, IBinder service);
    }

    /** Binder mechanics only; descriptor, transaction parsing and waits are native. */
    static final class Callback extends Binder implements IInterface {
        Callback() {
            nativeAttach();
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            return nativeDispatch(code, data, reply, flags)
                    || super.onTransact(code, data, reply, flags);
        }

        private native void nativeAttach();
        private native boolean nativeDispatch(int code, Parcel data, Parcel reply, int flags);
    }
}
