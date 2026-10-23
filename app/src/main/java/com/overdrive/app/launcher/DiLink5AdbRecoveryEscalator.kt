package com.overdrive.app.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.overdrive.app.byd.BydDataCollector
import com.overdrive.app.camera.dilink5.DiLink5Platform
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.logging.LogManager
import com.overdrive.app.power.AdbRecoveryLease
import com.overdrive.app.ui.model.ParkedShutdown
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

/**
 * DiLink 5 ADB-recovery escalation, APK process only.
 *
 * <p>Settings re-enable (the port watch's existing [requestDiLink5AdbReenable]) always runs
 * first. This object layers a last-resort, firmware-gated OEM fallback on top: after repeated
 * closed-port observations and a settling period, and only while parked and keep-awake, it binds
 * the OEM enablement services once to try to re-open ADB. With the shipped defaults (empty
 * allowlist, switch off) the gate never allows it, so no OEM service is ever bound.
 *
 * <p>All entry points are cheap (a few field reads and a synchronized counter update) and run on
 * the port-watch thread; they never block, never touch the shared ADB connection lock, and never
 * create an ADB connection. The parked check, the gate read and the lease read happen only once
 * the policy's counter, settle, budget and spacing clauses are all satisfied, and the gate/lease
 * reads at most once per [GATE_REEVALUATE_MS]. The only blocking work (binds, transacts, settle
 * sleeps) runs on the dedicated single daemon thread "Di5AdbRecoveryEscalation", created lazily
 * on the first allowed escalation. Off DiLink 5 every entry point returns immediately.
 */
internal object DiLink5AdbRecoveryEscalator {

    private const val TAG = "Di5AdbRecovery"
    private const val GATE_REEVALUATE_MS = 60_000L
    private const val OEM_BIND_TIMEOUT_MS = 10_000L
    private const val STAGE_SETTLE_MS = 30_000L
    private const val SETTLE_POLL_MS = 2_000L
    private const val DUMP_TAIL = 16
    private const val OUTCOME_LEASE_UNWRITABLE = "lease-unwritable"
    private const val MAX_LOGGED_LEASE_CHARS = 64

    private val logger = LogManager.getInstance()
    private val policy = DiLink5AdbEscalationPolicy()
    private val ring = DiLink5AdbRecoveryDiagnostics()
    private val inFlight = AtomicBoolean(false)

    /** Stable supplier so the lazily-evaluated parked check allocates nothing per observation. */
    private val parkedKeepAwakeSupplier = BooleanSupplier { parkedKeepAwake() }

    private val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "Di5AdbRecoveryEscalation").apply { isDaemon = true }
        }
    }

    @Volatile
    private var cachedSignature: String? = null
    @Volatile
    private var nextGateEvalMs = 0L
    /** Last gate outcome recorded/logged (a verdict name or [OUTCOME_LEASE_UNWRITABLE]); changes only. */
    @Volatile
    private var lastNotedGateOutcome: String? = null
    /** Last raw lease value we deferred to; recorded/logged once per distinct value. */
    @Volatile
    private var lastDeferredLease: String? = null
    @Volatile
    private var lastGateVerdict = "unevaluated"

    private enum class SettleOutcome { OPENED, ABORTED, STILL_CLOSED }

    /**
     * Port seen closed (auth not pending). Accounts the observation and, at most once per
     * [GATE_REEVALUATE_MS], evaluates the gate and may dispatch one escalation. Runs on the
     * port-watch thread; all work here is cheap reads. Gate and lease outcomes are written to
     * the ring only when they change, so a refusing gate cannot churn the ring.
     */
    fun onPortClosedObservation(
        appContext: Context,
        lastEffectiveSettingsWriteMs: Long,
        authPending: Boolean,
        connected: Boolean
    ) {
        if (!DiLink5Platform.isSelected()) return
        val now = SystemClock.elapsedRealtime()
        policy.notePowerSample(readAccOn())
        policy.observeClosed(now)
        val decision = policy.decide(
            now, lastEffectiveSettingsWriteMs, connected, authPending,
            /* portOpen = */ false, parkedKeepAwakeSupplier, inFlight.get()
        )
        if (decision != DiLink5AdbEscalationPolicy.Decision.ESCALATE) return
        if (now < nextGateEvalMs) return
        nextGateEvalMs = now + GATE_REEVALUATE_MS

        val signature = probedSignature()
        val resolver = appContext.contentResolver
        val switchValue = try {
            Settings.Global.getString(resolver, DiLink5AdbFallbackGate.SWITCH_SETTING)
        } catch (t: Throwable) {
            null
        }
        val verdict = DiLink5AdbFallbackGate.evaluate(
            signature, switchValue,
            DiLink5AdbFallbackGate.VERIFIED_DILINK5_ADB_FALLBACK_SIGNATURES
        )
        lastGateVerdict = verdict.name
        val allowed = verdict == DiLink5AdbFallbackGate.Verdict.ALLOWED
        val outcome = when {
            !allowed -> verdict.name
            !hasWriteSecureSettings(appContext) -> OUTCOME_LEASE_UNWRITABLE
            else -> verdict.name
        }
        if (lastNotedGateOutcome != outcome) {
            lastNotedGateOutcome = outcome
            record("gate", outcome)
            when {
                !allowed -> logger.warn(
                    TAG,
                    "ADB fallback gate ${verdict.name}; probed signature [$signature] " +
                        "(set ${DiLink5AdbFallbackGate.SWITCH_SETTING} to \"sig:$signature\" " +
                        "on a validated car to enable)"
                )
                outcome == OUTCOME_LEASE_UNWRITABLE -> logger.warn(
                    TAG,
                    "ADB fallback allowed but WRITE_SECURE_SETTINGS not granted; " +
                        "leaving recovery to the daemon"
                )
                else -> logger.info(TAG, "ADB fallback gate ALLOWED for probed signature [$signature]")
            }
        }
        if (outcome != DiLink5AdbFallbackGate.Verdict.ALLOWED.name) return

        val leaseRaw = try {
            Settings.Global.getString(resolver, AdbRecoveryLease.SETTING_KEY)
        } catch (t: Throwable) {
            null
        }
        val lease = AdbRecoveryLease.parse(leaseRaw, now, AdbRecoveryLease.currentBootId())
        if (lease.isFresh(AdbRecoveryLease.Owner.DAEMON)
            || lease.state == AdbRecoveryLease.State.MALFORMED
        ) {
            if (lastDeferredLease != leaseRaw) {
                lastDeferredLease = leaseRaw
                record("lease", "lease-busy ${lease.state}")
                if (lease.state == AdbRecoveryLease.State.MALFORMED) {
                    // Fail closed with no TTL: a malformed value defers the APK path until it is
                    // overwritten (the daemon rewrites it on its next attempt) or deleted by hand.
                    logger.warn(
                        TAG,
                        "ADB fallback deferring to MALFORMED lease [${leaseRaw.orEmpty().take(MAX_LOGGED_LEASE_CHARS)}]; " +
                            "clear it with: settings delete global ${AdbRecoveryLease.SETTING_KEY}"
                    )
                } else {
                    logger.info(TAG, "ADB fallback deferring to ${lease.state} lease (owner ${lease.owner})")
                }
            }
            return
        }
        val stages = DiLink5AdbFallbackGate.parseStages(
            try {
                Settings.Global.getString(resolver, DiLink5AdbFallbackGate.STAGES_SETTING)
            } catch (t: Throwable) {
                null
            }
        )
        if (inFlight.compareAndSet(false, true)) {
            try {
                executor.execute { runEscalation(appContext, stages) }
            } catch (t: Throwable) {
                inFlight.set(false)
                logger.warn(TAG, "ADB fallback could not start: ${t.message}")
            }
        }
    }

    /** Port seen open: the closed window is over. Runs on the port-watch thread. */
    fun onPortOpenObservation() {
        if (!DiLink5Platform.isSelected()) return
        policy.notePowerSample(readAccOn())
        policy.observeOpen()
    }

    /**
     * A shared connection is installed: recovery confirmed through the existing path. Called from
     * the port watch both when it finds a connection another caller installed and right after its
     * own [AdbShellExecutor.acquireConnection] succeeds (outside the connection lock).
     */
    fun onConnectionObserved(generation: Long) {
        if (!DiLink5Platform.isSelected()) return
        val now = SystemClock.elapsedRealtime()
        val confirmed = policy.withinConfirmationWindow(now)
        policy.onConnectionInstalled()
        record("connection", if (confirmed) "CONFIRMED gen=$generation" else "connected gen=$generation")
    }

    /** Snapshot of the diagnostics ring, oldest first, for any future surface. */
    fun diagnosticsSnapshot(): List<String> = ring.snapshot().map { it.format() }

    private fun runEscalation(ctx: Context, stages: DiLink5AdbFallbackGate.Stages) {
        // Budget, spacing and the confirmation window are armed only once an OEM request was
        // actually issued; an abort before Stage A hands the slot back and resets the window.
        var oemRequestIssued = false
        try {
            if (!precheck()) {
                record("precheck", "aborted-precondition")
                return
            }
            if (!claimLease(ctx)) {
                record("lease", "lease-claim-failed")
                return
            }
            logger.warn(TAG, "ADB recovery escalation starting (stages=$stages)")

            oemRequestIssued = true
            val stageA = DiLink5OemAdbEnableClient.requestAdbEnabledViaOta(ctx, OEM_BIND_TIMEOUT_MS)
            record("ota", "${stageA.outcome} ${stageA.detail}")
            logger.warn(TAG, "ADB recovery Stage A (ota enable): ${stageA.outcome} ${stageA.detail}")

            when (settlePoll()) {
                SettleOutcome.OPENED -> {
                    record("ota", "port-open-after-ota")
                    return
                }
                SettleOutcome.ABORTED -> {
                    record("ota", "aborted-between-stages")
                    return
                }
                SettleOutcome.STILL_CLOSED -> { /* fall through to Stage B gating */ }
            }

            if (stages != DiLink5AdbFallbackGate.Stages.OTA_THEN_TCP_PORT) {
                record("tsport", "tcpport-stage-disabled")
                return
            }
            if (DiLink5OemAdbEnableClient.isCameraDaemonRecording() == true) {
                record("tsport", "tcpport-deferred-recording")
                return
            }
            if (!precheck()) {
                record("tsport", "aborted-before-tcpport")
                return
            }
            claimLease(ctx)

            val stageB = DiLink5OemAdbEnableClient.requestAdbTcpPortViaTsSettings(ctx, OEM_BIND_TIMEOUT_MS)
            record("tsport", "${stageB.outcome} ${stageB.detail}")
            logger.warn(TAG, "ADB recovery Stage B (tcp port): ${stageB.outcome} ${stageB.detail}")

            when (settlePoll()) {
                SettleOutcome.ABORTED -> record("tsport", "aborted-after-tcpport")
                else -> record(
                    "result",
                    if (AdbShellExecutor.isAdbPortClosedNow()) "port-closed" else "port-open"
                )
            }
        } catch (t: Throwable) {
            record("result", "error ${t.javaClass.simpleName}")
        } finally {
            if (oemRequestIssued) {
                policy.onEscalationFinished(SystemClock.elapsedRealtime())
            } else {
                policy.onEscalationAborted()
            }
            inFlight.set(false)
            dumpTail()
        }
    }

    /** Up to [STAGE_SETTLE_MS] in [SETTLE_POLL_MS] steps; stops early on open port or lost preconditions. */
    private fun settlePoll(): SettleOutcome {
        val deadline = SystemClock.elapsedRealtime() + STAGE_SETTLE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(SETTLE_POLL_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return SettleOutcome.ABORTED
            }
            if (!AdbShellExecutor.isAdbPortClosedNow()) return SettleOutcome.OPENED
            if (!precheck()) return SettleOutcome.ABORTED
        }
        return SettleOutcome.STILL_CLOSED
    }

    private fun precheck(): Boolean =
        parkedKeepAwake() &&
            !AdbShellExecutor.isConnected() &&
            !AdbShellExecutor.isAuthPending() &&
            AdbShellExecutor.isAdbPortClosedNow()

    private fun claimLease(ctx: Context): Boolean = try {
        Settings.Global.putString(
            ctx.contentResolver,
            AdbRecoveryLease.SETTING_KEY,
            AdbRecoveryLease.format(
                AdbRecoveryLease.Owner.APP,
                AdbRecoveryLease.currentBootId(),
                SystemClock.elapsedRealtime()
            )
        )
        true
    } catch (t: Throwable) {
        false
    }

    private fun parkedKeepAwake(): Boolean = try {
        DiLink5Platform.isSelected() &&
            !BydDataCollector.getInstance().isAccOn() &&
            !UnifiedConfigManager.isVehicleOnOnlyMode() &&
            !java.io.File(ParkedShutdown.MARKER_PATH).isFile
    } catch (t: Throwable) {
        false
    }

    private fun readAccOn(): Boolean = try {
        BydDataCollector.getInstance().isAccOn()
    } catch (t: Throwable) {
        true
    }

    private fun hasWriteSecureSettings(ctx: Context): Boolean = try {
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    private fun probedSignature(): String {
        cachedSignature?.let { return it }
        val resolved = try {
            DiLink5AdbFallbackGate.signatureOf(Build.FINGERPRINT, Build.VERSION.INCREMENTAL)
        } catch (t: Throwable) {
            DiLink5AdbFallbackGate.signatureOf(null, null)
        }
        cachedSignature = resolved
        return resolved
    }

    private fun record(stage: String, result: String) {
        ring.record(
            SystemClock.elapsedRealtime(), System.currentTimeMillis(),
            stage, result, lastGateVerdict
        )
    }

    private fun dumpTail() {
        val lines = ring.tail(DUMP_TAIL).joinToString("\n") { it.format() }
        if (lines.isNotEmpty()) {
            logger.warn(TAG, "ADB recovery escalation summary:\n$lines")
        }
    }
}
