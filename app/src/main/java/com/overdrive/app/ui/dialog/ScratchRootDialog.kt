package com.overdrive.app.ui.dialog

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.overdrive.app.R
import com.overdrive.app.camera.dilink5.DiLink5CaptureStop
import com.overdrive.app.camera.dilink5.DiLink5Platform
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.launcher.DaemonLauncher
import com.overdrive.app.util.ScratchRoot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Daemon storage" dialog: the single opt-in for [ScratchRoot.ALT_DIR].
 *
 * Deliberately independent of every daemon and of the daemon-served web UI.
 * On a head unit whose shell cannot create under /data/local/tmp no daemon
 * runs, so this must work with nothing but the app process and the ADB
 * loopback. Flipping the switch:
 *
 *  1. kills every daemon and watchdog shell (no `.disabled` sentinels, so
 *     the normal startup brings them back on the new root),
 *  2. runs [ScratchRoot.enableCommand] / [ScratchRoot.disableCommand] over
 *     ADB and verifies the acknowledgement token,
 *  3. persists the selection ([ScratchRoot.setAlternateEnabled]),
 *  4. restarts the app process, because Java constants built from
 *     [ScratchRoot.path] are fixed at class initialisation.
 *
 * Nothing is migrated in either direction; see the dialog copy.
 */
object ScratchRootDialog {

    private const val TAG = "ScratchRootDialog"
    private const val SHELL_TIMEOUT_S = 25L
    private const val RESTART_DELAY_MS = 900L

    @JvmStatic
    fun show(activity: Activity) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_scratch_root, null)
        val current = view.findViewById<TextView>(R.id.tvScratchRootCurrent)
        val paths = view.findViewById<TextView>(R.id.tvScratchRootPaths)
        val switch = view.findViewById<SwitchMaterial>(R.id.swScratchRootAlternate)

        val selected = ScratchRoot.isAlternateSelected(activity)
        current.text = activity.getString(R.string.scratch_root_current_folder, ScratchRoot.dir())
        paths.text = activity.getString(
            R.string.scratch_root_paths, ScratchRoot.LEGACY_DIR, ScratchRoot.ALT_DIR
        )
        switch.isChecked = selected

        val dialog = MaterialAlertDialogBuilder(activity, R.style.Theme_Overdrive_M3_Dialog)
            .setTitle(R.string.scratch_root_dialog_title)
            .setView(view)
            .setNegativeButton(R.string.dialog_close, null)
            .create()

        switch.setOnCheckedChangeListener { _, enabled ->
            if (enabled == selected) return@setOnCheckedChangeListener
            confirm(activity, enabled,
                onConfirmed = {
                    dialog.dismiss()
                    apply(activity, enabled)
                },
                onCancelled = { switch.isChecked = selected })
        }
        dialog.show()
    }

    private fun confirm(
        activity: Activity,
        enabled: Boolean,
        onConfirmed: () -> Unit,
        onCancelled: () -> Unit
    ) {
        MaterialAlertDialogBuilder(activity, R.style.Theme_Overdrive_M3_Dialog)
            .setTitle(R.string.scratch_root_confirm_title)
            .setMessage(
                if (enabled) R.string.scratch_root_confirm_enable
                else R.string.scratch_root_confirm_disable
            )
            .setPositiveButton(R.string.scratch_root_restart) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.action_cancel) { _, _ -> onCancelled() }
            .setOnCancelListener { onCancelled() }
            .show()
    }

    private fun apply(activity: Activity, enabled: Boolean) {
        val progress: AlertDialog = MaterialAlertDialogBuilder(activity, R.style.Theme_Overdrive_M3_Dialog)
            .setMessage(R.string.scratch_root_working)
            .setCancelable(false)
            .show()
        // Transient launcher: release ITS executor afterwards (never the
        // process-wide Dadb, which DaemonStartupManager's launcher owns).
        val launcher = AdbDaemonLauncher(activity.applicationContext)
        Thread({
            val failure = try {
                performSwitch(activity.applicationContext, launcher, enabled)
            } catch (t: Throwable) {
                Log.w(TAG, "switch failed", t)
                t.message ?: t.javaClass.simpleName
            } finally {
                try { launcher.releasePerInstanceResources() } catch (_: Throwable) {}
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                try { progress.dismiss() } catch (_: Throwable) {}
                if (failure == null) {
                    restartApp(activity)
                } else {
                    MaterialAlertDialogBuilder(activity, R.style.Theme_Overdrive_M3_Dialog)
                        .setTitle(R.string.scratch_root_dialog_title)
                        .setMessage(activity.getString(R.string.scratch_root_failed, failure))
                        .setPositiveButton(R.string.dialog_close, null)
                        .show()
                }
            }
        }, "ScratchRootSwitch").start()
    }

    /**
     * Runs the switch on a worker thread. Returns null on success or a
     * human-readable failure. The stored selection is only written once the
     * marker command has been acknowledged, so a dead ADB link leaves the
     * head unit exactly where it was.
     */
    internal fun performSwitch(
        context: Context,
        launcher: AdbDaemonLauncher,
        enabled: Boolean
    ): String? {
        // 1. Stop the whole daemon family on the CURRENT root. The paths in
        //    the sweep are remapped by the ADB lane against the root that is
        //    still live, which is exactly the one the old processes own.
        val sweep = runScript(launcher, killSweepScript())
        if (sweep.error != null) {
            Log.w(TAG, "kill sweep reported: ${sweep.error} (continuing)")
        }

        // 2. Publish (or retract) the marker; the ack token is the proof.
        val command = if (enabled) ScratchRoot.enableCommand() else ScratchRoot.disableCommand()
        val expected = if (enabled) ScratchRoot.ENABLE_OK else ScratchRoot.DISABLE_OK
        val marker = runCommand(launcher, command)
        if (marker.error != null) {
            return if (marker.error.contains("connect", ignoreCase = true)
                || marker.error.contains("refused", ignoreCase = true)
                || marker.error.contains("timeout", ignoreCase = true)
            ) context.getString(R.string.scratch_root_failed_adb) else marker.error
        }
        if (!marker.output.contains(expected)) {
            return marker.output.trim().ifEmpty { "no acknowledgement from shell" }
        }

        // 3. Persist. From here the next app process resolves the new root.
        if (!ScratchRoot.setAlternateEnabled(context, enabled)) {
            return "preference could not be saved"
        }
        Log.i(TAG, "scratch root switched: ${ScratchRoot.describe()}")
        return null
    }

    /**
     * ps+awk+kill every daemon, watchdog shell and native capture child. No
     * sentinel is planted: the point is a clean restart on the other root,
     * not a user stop. Patterns mirror the launchers' own cleanup sweeps.
     */
    internal fun killSweepScript(): String = buildString {
        val diLink5 = diLink5Selected()
        for (pattern in listOf(
            "start_cam_daemon", "cam_daemon",
            "start_acc_sentry", "acc_sentry",
            "start_telegram", "telegram_bot_daemon",
            "sentry_daemon", "sentry_proxy",
            "start_singbox", "sing-box",
            "start_zrok", "zrok",
            "cloudflared", "tailscaled", "wgproxy",
            "fast_cam_capture", "qcarcam_test"
        )) {
            // DiLink 5: fast_cam_capture stops its cameras on SIGTERM before the kill -9 below.
            if (diLink5 && pattern == "fast_cam_capture") {
                append(DiLink5CaptureStop.gracefulStopScript())
            }
            append(DaemonLauncher.psAwkKillLine(pattern))
        }
        // Ownership artifacts on the root that is still live (the one the
        // processes above were using); the new root starts clean by itself.
        append("rm -f ")
        for (name in listOf(
            "cam_watchdog.pid", "acc_sentry_watchdog.pid",
            "camera_daemon.lock", "acc_sentry_daemon.lock", "telegram_bot_daemon.lock"
        )) append(ScratchRoot.path(name)).append(' ')
        append("2>/dev/null\n")
        append("rm -rf ")
            .append(ScratchRoot.path("cam_watchdog.lock")).append(' ')
            .append(ScratchRoot.path("acc_sentry_watchdog.lock"))
            .append(" 2>/dev/null\n")
        append("echo swept\n")
    }

    private fun diLink5Selected(): Boolean = try {
        DiLink5Platform.refreshActiveMode()
        DiLink5Platform.isSelected()
    } catch (_: Throwable) {
        false
    }

    private class ShellOutcome(val output: String, val error: String?)

    private fun runScript(launcher: AdbDaemonLauncher, body: String): ShellOutcome {
        val latch = CountDownLatch(1)
        var out = ""
        var err: String? = null
        launcher.executeShellScript(body, object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) { out += message }
            override fun onLaunched() { latch.countDown() }
            override fun onError(error: String) { err = error; latch.countDown() }
        })
        if (!latch.await(SHELL_TIMEOUT_S, TimeUnit.SECONDS)) err = "timeout"
        return ShellOutcome(out, err)
    }

    private fun runCommand(launcher: AdbDaemonLauncher, command: String): ShellOutcome {
        val latch = CountDownLatch(1)
        var out = ""
        var err: String? = null
        launcher.executeShellCommand(command, object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) { out += message }
            override fun onLaunched() { latch.countDown() }
            override fun onError(error: String) { err = error; latch.countDown() }
        })
        if (!latch.await(SHELL_TIMEOUT_S, TimeUnit.SECONDS)) err = "timeout"
        return ShellOutcome(out, err)
    }

    /**
     * Full process restart: schedule the launcher Activity, then exit. Every
     * path constant resolved through [ScratchRoot.path] at class init is
     * dropped with the process; the next one boots on the new root.
     */
    internal fun restartApp(activity: Activity) {
        val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            ?: Intent(activity, activity.javaClass)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(
            activity, 0x5C, launch,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarms = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + RESTART_DELAY_MS
        try {
            // Same exact-alarm path ProcessRevivalReceiver relies on (the
            // manifest declares SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM); an
            // inexact alarm may be batched for minutes on Android 12.
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (denied: SecurityException) {
            Log.w(TAG, "exact alarm denied, falling back to inexact", denied)
            alarms.set(AlarmManager.RTC_WAKEUP, at, pending)
        }
        activity.finishAffinity()
        Runtime.getRuntime().exit(0)
    }
}
