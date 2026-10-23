package com.overdrive.app.power;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Permission-protected app-process adapter for the shell daemon. */
public final class ParkedLinkReceiver extends BroadcastReceiver {
    private static final String TAG = "ParkedLink";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ParkedLinkContract.ACTION.equals(intent.getAction())) {
            setResultCode(ParkedLinkContract.RESULT_BAD_REQUEST);
            return;
        }
        final int operation = ParkedLinkContract.operationFor(
                intent.getStringExtra(ParkedLinkContract.EXTRA_COMMAND));
        final long generation = intent.getLongExtra(
                ParkedLinkContract.EXTRA_GENERATION, -1L);
        if (operation == 0 || generation < 0L) {
            setResultCode(ParkedLinkContract.RESULT_BAD_REQUEST);
            return;
        }

        final PendingResult pending = goAsync();
        Thread worker = new Thread(() -> {
            int result = ParkedLinkContract.RESULT_INTERNAL_FAILED;
            try {
                result = ParkedLink.execute(context, operation);
                Log.i(TAG, "op=" + operation + " generation=" + generation
                        + " result=" + result);
            } catch (Throwable failure) {
                Log.w(TAG, "result=" + ParkedLinkContract.RESULT_INTERNAL_FAILED);
            } finally {
                pending.setResultCode(result);
                pending.finish();
            }
        }, "parked-link-command");
        try {
            worker.start();
        } catch (Throwable failure) {
            Log.w(TAG, "result=" + ParkedLinkContract.RESULT_WORKER_START_FAILED);
            pending.setResultCode(ParkedLinkContract.RESULT_WORKER_START_FAILED);
            pending.finish();
        }
    }
}
