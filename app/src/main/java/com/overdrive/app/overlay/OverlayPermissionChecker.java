package com.overdrive.app.overlay;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Authoritative app-process check for Display-over-other-apps permission.
 */
public final class OverlayPermissionChecker {

    private static final String TAG = "OverlayPermission";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final AtomicBoolean mismatchLogged = new AtomicBoolean(false);

    private OverlayPermissionChecker() {}

    public static boolean isGranted(Context context) {
        Context appContext = context.getApplicationContext();
        Context probeContext = appContext != null ? appContext : context;
        boolean frameworkAllowed = frameworkAllows(probeContext);
        Boolean appOpsAllowed = appOpsAllows(probeContext);
        boolean granted =
                OverlayPermissionDecision.resolve(appOpsAllowed, frameworkAllowed);

        if (appOpsAllowed != null
                && appOpsAllowed.booleanValue() != frameworkAllowed
                && mismatchLogged.compareAndSet(false, true)) {
            logger.warn("Permission APIs disagree: appOps=" + appOpsAllowed
                    + ", framework=" + frameworkAllowed
                    + "; using explicit AppOps result");
        }
        return granted;
    }

    private static boolean frameworkAllows(Context context) {
        try {
            return Settings.canDrawOverlays(context);
        } catch (Throwable error) {
            logger.warn("Settings.canDrawOverlays failed: " + error.getMessage());
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static Boolean appOpsAllows(Context context) {
        try {
            AppOpsManager appOps =
                    (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return null;

            String opPackageName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? context.getOpPackageName()
                    : context.getPackageName();
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = appOps.unsafeCheckOpRawNoThrow(
                        AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                        context.getApplicationInfo().uid,
                        opPackageName);
            } else {
                mode = appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                        context.getApplicationInfo().uid,
                        opPackageName);
            }
            Boolean decision = appOpsDecision(
                    mode,
                    com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected(),
                    context.getApplicationInfo().targetSdkVersion);
            if (decision != null || mode == AppOpsManager.MODE_DEFAULT
                    || mode == AppOpsManager.MODE_FOREGROUND) {
                return decision;
            }
            logger.warn("Unknown SYSTEM_ALERT_WINDOW AppOps mode: " + mode);
        } catch (Throwable error) {
            logger.warn("SYSTEM_ALERT_WINDOW AppOps check failed: "
                    + error.getMessage());
        }
        return null;
    }

    /**
     * Translate the raw SYSTEM_ALERT_WINDOW AppOp mode into a decision, or {@code null} when
     * the mode does not settle the question and {@code Settings.canDrawOverlays()} must.
     *
     * <p>Only an EXPLICIT verdict wins over the framework: ALLOWED, or IGNORED/ERRORED (the
     * "some BYD builds report canDrawOverlays()=true while the op is explicitly denied" case
     * this checker exists for — see {@link OverlayPermissionDecision}).
     *
     * <p>{@code MODE_DEFAULT} is NOT a denial. It is what AppOps reports when nobody has ever
     * toggled the op — the state of every install whose grant came from {@code pm grant}
     * (the daemon's PermissionGranter) rather than the Settings toggle. The platform itself
     * resolves DEFAULT by falling back to the static permission grant, both in
     * {@code Settings.canDrawOverlays()} and in the window manager's own add-window check, so
     * a window we refused here would have been accepted by the OS. Treating DEFAULT as denied
     * (v40.2–49.7 on non-DiLink5 cars) is what silently killed every "Show toast" automation
     * on such installs: MessageOverlayService exited before addView and the daemon, dispatching
     * fire-and-forget, logged success (2026-09-23 field log).
     */
    static Boolean appOpsDecision(
            int mode, boolean diLink5, int targetSdkVersion) {
        if (mode == AppOpsManager.MODE_ALLOWED) return Boolean.TRUE;
        if (mode == AppOpsManager.MODE_IGNORED) return Boolean.FALSE;
        if (mode == AppOpsManager.MODE_ERRORED) return Boolean.FALSE;
        if (!diLink5 && mode == AppOpsManager.MODE_FOREGROUND) return Boolean.TRUE;
        // MODE_DEFAULT everywhere, and FOREGROUND on DiLink 5: the op does not settle this
        // special permission; the framework check evaluates the current static grant.
        return null;
    }
}
