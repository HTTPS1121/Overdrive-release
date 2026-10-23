package com.overdrive.app.byd.assist;

import android.content.Context;
import android.os.SystemClock;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.assist.DiLink5AssistOffGate.Control;
import com.overdrive.app.byd.assist.DiLink5AssistOffPlan.Action;
import com.overdrive.app.byd.assist.DiLink5AssistOffPlan.Motion;
import com.overdrive.app.byd.assist.DiLink5AssistOffStatus.State;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONObject;

/**
 * Opt-in DiLink 5 assist-off profile: turns explicitly selected driver-assistance features off
 * once per ignition cycle, from the app process (the DiLink 5 producer, where the DiPilot SDK
 * device is reachable).
 *
 * <p>Entry points are called by {@code BydDataCollector}: {@link #onAccOn} on the OFF→ON edge (after
 * the poll scheduler holds its new generation), {@link #onAccOff} on the ON→OFF edge, and
 * {@link #onProducerStarted} at the end of a successful producer start with ACC already on (boot
 * produces no edge). All three return immediately; the work runs on one daemon thread.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Gate first, allocation-free: off DiLink 5 the call is one volatile read; with an
 *       unconfirmed model or nothing opted in it returns before scheduling anything, and no
 *       status is ever written.</li>
 *   <li>One run per ignition cycle. Every ACC edge bumps {@link #cycleGeneration}; a worker aborts
 *       as soon as its generation is no longer current, ACC reads off, or this process stops being
 *       the DiLink 5 producer ({@code diLink5DiPilotDeviceForAssistOff() == null}, e.g. a mode
 *       change mid-run), so a rapid OFF→ON ends the old run and starts a fresh one that waits for
 *       readiness again.</li>
 *   <li>Readiness before any write: the TS assist adapter manager is connected AND the DiPilot CPD
 *       getter reports a real state (1 or 2) AND there is positive stationary evidence. Polled every
 *       {@link #READINESS_POLL_MS} for up to {@link #READINESS_DEADLINE_MS}; never ready, or no
 *       stationary evidence by then → the cycle is skipped and logged.</li>
 *   <li>Stationary only, with positive evidence ({@link DiLink5AssistOffPlan#motion}): a known
 *       snapshot speed of at most {@link #STATIONARY_MAX_KMH}, or a known snapshot gear of P. An
 *       unknown speed is NOT stationary. Checked immediately before every write: moving or no
 *       evidence ends the whole run for this cycle — assist behaviour is never changed under a
 *       moving driver, and there is no retry until the next ACC ON.</li>
 *   <li>Live opt-in: before every write attempt the vehicle config is re-read
 *       ({@link DiLink5AssistOffGate#writeDenial}); a control must have been opted in at the ACC
 *       edge AND still be opted in, on DiLink 5, with the model still confirmed. A toggle switched
 *       off during the readiness wait leaves that control {@code disabled} with the reason.</li>
 *   <li>Routes are the public DiPilot SDK setters/getters by name ({@link DiLink5AssistOffControls});
 *       CPD reuses {@code BydDataCollector.writeDiLink5DiPilotCpd}. The collector's generic DiPilot
 *       on/off switch helper and the automation/MQTT CPD path (with its driving-evidence gate) are
 *       not involved: the
 *       user's explicit CPD opt-in — offered only for the model without a cabin-camera CPD
 *       switch — stands in for that evidence here and nowhere else.</li>
 *   <li>Readback-verified per {@link DiLink5AssistOffPlan}: up to three attempts, two seconds
 *       apart, each polled for six seconds. DMS has no readback and is reported as
 *       {@code request_sent}, never {@code verified}; its three settings are tracked per
 *       transaction ({@link DiLink5DmsSettingsClient.Delivery}), so a partially acknowledged
 *       request is {@code request_sent} with a {@code partial: ...} detail, not {@code failed}.</li>
 *   <li>Nothing here feeds the legacy lane-assist vocabulary, MQTT, the keymap or the quick panel;
 *       the status surface is additive ({@link DiLink5AssistOffStatus}).</li>
 * </ul>
 */
public final class DiLink5AssistOffApplier {

    private static final DaemonLogger logger = DaemonLogger.getInstance("DiLink5AssistOff");

    static final long READINESS_DEADLINE_MS = DiLink5AssistOffPlan.READINESS_DEADLINE_MS;
    static final long READINESS_POLL_MS = DiLink5AssistOffPlan.READINESS_POLL_MS;
    static final double STATIONARY_MAX_KMH = DiLink5AssistOffPlan.STATIONARY_MAX_KMH;
    static final long DMS_BIND_TIMEOUT_MS = 5_000L;
    private static final String ASSIST_ADAPTER_GETTER = "getCarAssistAdapterManager";
    private static final int UNAVAILABLE = DiLink5AssistOffControls.READ_UNAVAILABLE;

    private static final Object LOCK = new Object();
    /** Bumped on every ACC edge; a worker runs only while its captured value is current. */
    private static final AtomicLong cycleGeneration = new AtomicLong();
    /**
     * Generation whose gate was already evaluated (run scheduled or not); guarded by {@link #LOCK}.
     * Marked before the gate so an opt-in flipped mid-drive waits for the next ignition, exactly
     * as the UI promises, instead of writing to a moving car's assist settings.
     */
    private static long evaluatedCycle = Long.MIN_VALUE;
    private static ExecutorService executor;
    private static Future<?> pending;
    private static volatile boolean statusDirWarned;

    private DiLink5AssistOffApplier() {
    }

    /** ACC OFF→ON edge (producer process). {@code accGeneration} is informational for the status. */
    public static void onAccOn(Context context, long accGeneration) {
        long cycle = cycleGeneration.incrementAndGet();
        scheduleIfEligible(context, cycle, accGeneration, "ACC ON");
    }

    /** ACC ON→OFF edge: invalidates the cycle and interrupts any run still in flight. */
    public static void onAccOff() {
        cycleGeneration.incrementAndGet();
        synchronized (LOCK) {
            if (pending != null) {
                pending.cancel(true);
                pending = null;
            }
        }
    }

    /** Producer started with ACC already on (boot): same run, idempotent per cycle. */
    public static void onProducerStarted(Context context) {
        scheduleIfEligible(context, cycleGeneration.get(), -1L, "producer start");
    }

    /** Current cycle generation, for diagnostics and tests. */
    static long cycleGeneration() {
        return cycleGeneration.get();
    }

    private static void scheduleIfEligible(
            Context context, long cycle, long accGeneration, String trigger) {
        // Cheapest gates first: a volatile read off DiLink 5, one long compare when this cycle
        // was already evaluated. Neither allocates.
        if (!DiLink5Platform.isSelected() || context == null) return;
        synchronized (LOCK) {
            if (evaluatedCycle == cycle) return;
            evaluatedCycle = cycle;
        }
        Eligibility eligibility = eligibility();
        if (eligibility == null) return;
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            if (cycleGeneration.get() != cycle) return;   // an ACC edge raced the gate
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "DiLink5AssistOff");
                    t.setDaemon(true);
                    return t;
                });
            }
            if (pending != null) pending.cancel(true);
            pending = executor.submit(() -> run(app, cycle, accGeneration, eligibility, trigger));
        }
    }

    /**
     * Model + opt-in snapshot taken at scheduling time (the ACC edge); null when the gate is closed.
     * The snapshot names the controls this cycle may write and feeds the status file; the actual
     * permission for each write is {@link #denial}, which re-reads the config every time.
     */
    private static final class Eligibility {
        final String modelId;
        final String modelSource;
        final DiLink5AssistOffProfile profile;

        Eligibility(String modelId, String modelSource, DiLink5AssistOffProfile profile) {
            this.modelId = modelId;
            this.modelSource = modelSource;
            this.profile = profile;
        }

        /**
         * Live write check: re-reads {@code UnifiedConfigManager.getVehicle()} (model, provenance
         * and this control's opt-in) and the platform flag, and combines them with the ACC-edge
         * snapshot through {@link DiLink5AssistOffGate#writeDenial}. Null means the write may go
         * ahead; otherwise the reason. Fail-closed: an unreadable config denies.
         */
        String denial(Control control) {
            boolean selected = DiLink5Platform.isSelected();
            try {
                JSONObject vehicle = UnifiedConfigManager.getVehicle();
                return DiLink5AssistOffGate.writeDenial(
                        selected, profile,
                        vehicle.optString("modelId", ""),
                        vehicle.optString("modelSource", ""),
                        DiLink5AssistOffProfile.fromVehicle(vehicle),
                        control);
            } catch (Throwable failure) {
                logger.debug("DI5 assist-off: config unreadable before " + control.key()
                        + " write: " + failure.getMessage());
                return "config unreadable before write";
            }
        }
    }

    private static Eligibility eligibility() {
        try {
            JSONObject vehicle = UnifiedConfigManager.getVehicle();
            String modelId = vehicle.optString("modelId", "");
            String modelSource = vehicle.optString("modelSource", "");
            if (!DiLink5AssistOffGate.modelConfirmedForProfile(
                    DiLink5Platform.isSelected(), modelId, modelSource)) {
                return null;
            }
            DiLink5AssistOffProfile profile = DiLink5AssistOffProfile.fromVehicle(vehicle);
            if (!profile.anyEnabled()) return null;
            return new Eligibility(modelId, modelSource, profile);
        } catch (Throwable failure) {
            // Fail closed: an unreadable config never writes to the car.
            logger.debug("DI5 assist-off: config unreadable, not scheduling: " + failure.getMessage());
            return null;
        }
    }

    // ── Worker ─────────────────────────────────────────────────────────────

    private static void run(
            Context context, long cycle, long accGeneration, Eligibility e, String trigger) {
        BydDataCollector collector = BydDataCollector.getInstance();
        DiLink5AssistOffStatus status = new DiLink5AssistOffStatus(
                cycle, accGeneration, trigger, e.modelId, System.currentTimeMillis(), e.profile);
        File statusFile = resolveStatusFile(context);
        logger.info("DI5 assist-off: cycle " + cycle + " (" + trigger + ") model=" + e.modelId
                + " profile " + e.profile);
        publish(statusFile, status);
        try {
            Object dp = awaitReadiness(context, collector, cycle, status, statusFile);
            if (dp == null) return;
            for (Control control : Control.values()) {
                if (!e.profile.isEnabled(control)) continue;   // not opted in at the ACC edge
                if (!stillCurrent(collector, cycle)) {
                    endRun(status, statusFile, "superseded before " + control.key());
                    return;
                }
                String denied = e.denial(control);
                if (denied != null) {
                    // Opt-in cleared (or model / platform changed) during the readiness wait: this
                    // control is not written this cycle; the others are judged on their own.
                    status.update(control, State.DISABLED, 0, System.currentTimeMillis(),
                            UNAVAILABLE, UNAVAILABLE, denied);
                    logger.info("DI5 assist-off " + control.key() + ": not written: " + denied);
                    publish(statusFile, status);
                    continue;
                }
                boolean keepGoing = control == Control.DMS
                        ? applyDms(context, collector, e, cycle, status, statusFile)
                        : applySdkControl(collector, e, dp, control, cycle, status, statusFile);
                if (!keepGoing) return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            endRun(status, statusFile, "interrupted (ACC OFF)");
        } catch (Throwable failure) {
            logger.warn("DI5 assist-off: cycle " + cycle + " aborted: " + failure);
            endRun(status, statusFile, "error: " + failure.getClass().getSimpleName());
        } finally {
            status.finished(System.currentTimeMillis());
            publish(statusFile, status);
            logger.info("DI5 assist-off: cycle " + cycle + " done: " + status.summary());
        }
    }

    /**
     * Waits for the assist adapter manager, a real CPD state AND positive stationary evidence
     * (known speed ≤ {@link #STATIONARY_MAX_KMH} or known gear P), all on the same poll. Returns the
     * DiPilot device, or null after writing a skip: never ready, no stationary evidence within the
     * deadline ("no stationary evidence (speed and gear unknown)"), moving, or superseded.
     */
    private static Object awaitReadiness(
            Context context,
            BydDataCollector collector,
            long cycle,
            DiLink5AssistOffStatus status,
            File statusFile) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + READINESS_DEADLINE_MS;
        int lastCpd = UNAVAILABLE;
        boolean adapterLogged = false;
        while (true) {
            if (!cycleCurrent(collector, cycle)) {
                endRun(status, statusFile, "superseded before readiness");
                return null;
            }
            Object dp = collector.diLink5DiPilotDeviceForAssistOff();
            lastCpd = dp != null ? readInt(dp, DiLink5AssistOffControls.CPD.getter) : UNAVAILABLE;
            boolean adapterReady = dp != null
                    && DiLink5AssistOffControls.isReadinessRead(lastCpd)
                    && BydDeviceHelper.isDiLink5AdapterReady(context, dp, ASSIST_ADAPTER_GETTER);
            if (adapterReady && !status.isReadinessObserved()) {
                status.readinessObserved(System.currentTimeMillis());
                publish(statusFile, status);
            }
            double speed = currentSpeedKmh(collector);
            int gear = currentGearMode(collector);
            Motion motion = DiLink5AssistOffPlan.motion(speed, gear);
            String evidence = DiLink5AssistOffPlan.describeMotion(speed, gear);
            if (motion == Motion.MOVING) {
                endRun(status, statusFile, evidence
                        + " during the readiness wait; no further writes this cycle");
                return null;
            }
            if (adapterReady && motion == Motion.STATIONARY) {
                logger.info("DI5 assist-off: DiPilot assist adapter ready (getCpdStatus="
                        + lastCpd + "), " + evidence);
                return dp;
            }
            if (adapterReady && !adapterLogged) {
                adapterLogged = true;
                logger.info("DI5 assist-off: DiPilot assist adapter ready (getCpdStatus="
                        + lastCpd + "); waiting for stationary evidence: " + evidence);
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                // Adapter never came up → the classic "not ready" (plus the missing evidence when
                // that is also lacking); adapter up but still no speed/gear evidence → the
                // no-evidence skip. Both end the cycle with no write.
                String reason = adapterReady
                        ? evidence
                        : "not ready within " + (READINESS_DEADLINE_MS / 1000L)
                                + " s (getCpdStatus=" + describe(lastCpd) + ")"
                                + (motion == Motion.STATIONARY ? "" : "; " + evidence);
                endRun(status, statusFile, reason);
                return null;
            }
            Thread.sleep(READINESS_POLL_MS);
        }
    }

    /**
     * One SDK-routed control (CPD, LDA, ELKA). Returns false when the run must stop (moving, no
     * stationary evidence, or superseded); true when this control reached a terminal state (or was
     * denied by the live config re-read) and the next may proceed.
     */
    private static boolean applySdkControl(
            BydDataCollector collector,
            Eligibility e,
            Object dp,
            Control control,
            long cycle,
            DiLink5AssistOffStatus status,
            File statusFile) throws InterruptedException {
        DiLink5AssistOffControls.Spec spec = DiLink5AssistOffControls.spec(control);
        DiLink5AssistOffPlan plan = new DiLink5AssistOffPlan(control);
        int writeValue = spec.offWriteValue;
        int lastRead = readInt(dp, spec.getter);
        int before = lastRead;          // readback immediately before the latest write
        int unloggedAttempt = 0;        // attempt whose outcome line has not been logged yet
        while (true) {
            if (!stillCurrent(collector, cycle)) {
                plan.fail("superseded");
                // A write that already went out is "failed" (not confirmed), never "skipped".
                status.update(control, plan.attempts() > 0 ? State.FAILED : State.SKIPPED,
                        plan.attempts(), System.currentTimeMillis(), before, lastRead,
                        plan.attempts() > 0 ? "not confirmed; superseded" : "superseded");
                endRun(status, statusFile, "superseded during " + control.key());
                return false;
            }
            long now = SystemClock.elapsedRealtime();
            Action action = plan.nextAction(now, lastRead, status.isReadinessObserved());
            switch (action) {
                case WRITE: {
                    if (unloggedAttempt > 0) {
                        logAttempt(control, unloggedAttempt, writeValue, before, lastRead, false,
                                "no off readback within the verify window");
                    }
                    // Live re-read of model, provenance and this control's opt-in before EVERY
                    // write attempt. A denial ends this control only; a write that already went
                    // out and was not confirmed is "failed", one that never went out "disabled".
                    String denied = e.denial(control);
                    if (denied != null) {
                        boolean wrote = plan.attempts() > 0;
                        status.update(control, wrote ? State.FAILED : State.DISABLED,
                                plan.attempts(), System.currentTimeMillis(), before, lastRead,
                                wrote ? "not confirmed; " + denied : denied);
                        logger.info("DI5 assist-off " + control.key() + ": not written (attempt "
                                + (plan.attempts() + 1) + "): " + denied);
                        publish(statusFile, status);
                        return true;
                    }
                    if (!stationary(collector, status, statusFile,
                            control.key() + " attempt " + (plan.attempts() + 1))) {
                        return false;
                    }
                    before = lastRead;
                    boolean accepted = control == Control.CPD
                            ? collector.writeDiLink5DiPilotCpd(writeValue)
                            : writeInt(dp, spec.setter, writeValue);
                    plan.noteWrite(now, accepted);
                    unloggedAttempt = plan.attempts();
                    status.update(control, accepted ? State.APPLIED : State.PENDING,
                            plan.attempts(), System.currentTimeMillis(), before, UNAVAILABLE,
                            accepted ? "write accepted, awaiting readback" : "write refused");
                    if (!accepted) {
                        logAttempt(control, unloggedAttempt, writeValue, before, UNAVAILABLE, false,
                                "write refused by the SDK");
                        unloggedAttempt = 0;
                    }
                    publish(statusFile, status);
                    break;
                }
                case WAIT:
                    Thread.sleep(DiLink5AssistOffPlan.VERIFY_POLL_MS);
                    break;
                case VERIFIED:
                case ALREADY_OFF: {
                    String detail = action == Action.ALREADY_OFF
                            ? "already off; no write" : "readback confirmed off";
                    if (control == Control.CPD) {
                        int carSvc = collector.readDiLink5DiPilotCpdStatusForAssistOff();
                        if (carSvc != UNAVAILABLE) detail += "; car_service=" + carSvc;
                    }
                    status.update(control, State.VERIFIED, plan.attempts(),
                            System.currentTimeMillis(), before, lastRead, detail);
                    if (action == Action.ALREADY_OFF) {
                        logger.info("DI5 assist-off " + control.key() + ": already off (read="
                                + lastRead + "), no write");
                    } else {
                        logAttempt(control, plan.attempts(), writeValue, before, lastRead, true,
                                detail);
                    }
                    publish(statusFile, status);
                    return true;
                }
                case GIVE_UP:
                case FAILED:
                default: {
                    String detail = action == Action.FAILED
                            ? plan.failure()
                            : "no off readback after " + plan.attempts() + " attempt(s); last read="
                                    + describe(lastRead);
                    status.update(control, State.FAILED, plan.attempts(),
                            System.currentTimeMillis(), before, lastRead, detail);
                    if (unloggedAttempt > 0) {
                        logAttempt(control, unloggedAttempt, writeValue, before, lastRead, false,
                                detail + " (giving up)");
                    } else {
                        logger.warn("DI5 assist-off " + control.key() + ": giving up: " + detail);
                    }
                    publish(statusFile, status);
                    return true;
                }
            }
            lastRead = readInt(dp, spec.getter);
        }
    }

    /**
     * DMS via the vendor settings Binder. Same contract as {@link #applySdkControl}. The three
     * settings (monitoring, fatigue, attention) are tracked per transaction across the attempts:
     * all acknowledged → {@code request_sent}; some, once no further attempt will be made →
     * {@code request_sent} with {@code partial: monitoring=ok, fatigue=failed, attention=ok}; none
     * → {@code failed}. Never {@code verified} or {@code applied}.
     */
    private static boolean applyDms(
            Context context,
            BydDataCollector collector,
            Eligibility e,
            long cycle,
            DiLink5AssistOffStatus status,
            File statusFile) throws InterruptedException {
        DiLink5AssistOffPlan plan = new DiLink5AssistOffPlan(Control.DMS);
        DiLink5DmsSettingsClient.Delivery delivery = new DiLink5DmsSettingsClient.Delivery();
        String lastDetail = "";
        while (true) {
            if (!stillCurrent(collector, cycle)) {
                if (plan.isTerminal()) return true;   // request_sent already recorded; nothing left
                if (delivery.anyAcknowledged()) {
                    // Part of the request did go out; report what was acknowledged.
                    finishDms(status, statusFile, plan, delivery, "not sent; superseded");
                } else {
                    status.update(Control.DMS, plan.attempts() > 0 ? State.FAILED : State.SKIPPED,
                            plan.attempts(), System.currentTimeMillis(), UNAVAILABLE, UNAVAILABLE,
                            plan.attempts() > 0 ? "not sent; superseded" : "superseded");
                }
                endRun(status, statusFile, "superseded during dms");
                return false;
            }
            long now = SystemClock.elapsedRealtime();
            Action action = plan.nextAction(now, UNAVAILABLE, status.isReadinessObserved());
            switch (action) {
                case WRITE: {
                    // Live re-read of model, provenance and the DMS opt-in before EVERY attempt.
                    String denied = e.denial(Control.DMS);
                    if (denied != null) {
                        logger.info("DI5 assist-off dms: not written (attempt "
                                + (plan.attempts() + 1) + "): " + denied);
                        if (plan.attempts() == 0) {
                            status.update(Control.DMS, State.DISABLED, 0,
                                    System.currentTimeMillis(), UNAVAILABLE, UNAVAILABLE, denied);
                            publish(statusFile, status);
                        } else {
                            finishDms(status, statusFile, plan, delivery, "not sent; " + denied);
                        }
                        return true;
                    }
                    if (!stationary(collector, status, statusFile,
                            "dms attempt " + (plan.attempts() + 1))) {
                        return false;
                    }
                    DiLink5DmsSettingsClient.Result result = DiLink5DmsSettingsClient.requestOff(
                            context, DMS_BIND_TIMEOUT_MS, delivery);
                    lastDetail = result.toString();
                    if (result.outcome == DiLink5DmsSettingsClient.Outcome.REQUEST_SENT) {
                        // All three settings acknowledged (cumulatively): terminal.
                        plan.noteRequestSent(now);
                        status.update(Control.DMS, State.REQUEST_SENT, plan.attempts(),
                                System.currentTimeMillis(), UNAVAILABLE, UNAVAILABLE,
                                "request sent; no readback on this route: " + result.detail);
                        logger.info("DI5 assist-off dms: attempt " + plan.attempts()
                                + " request sent (no readback available): " + result.detail);
                    } else {
                        plan.noteWrite(now, false);
                        status.update(Control.DMS, State.PENDING, plan.attempts(),
                                System.currentTimeMillis(), UNAVAILABLE, UNAVAILABLE, lastDetail);
                        logger.warn("DI5 assist-off dms: attempt " + plan.attempts()
                                + " not fully sent: " + lastDetail);
                    }
                    publish(statusFile, status);
                    break;
                }
                case WAIT:
                    Thread.sleep(DiLink5AssistOffPlan.VERIFY_POLL_MS);
                    break;
                case REQUEST_SENT:
                    return true;
                default: {
                    // Attempt budget exhausted: partial → request_sent + "partial: ...", none → failed.
                    finishDms(status, statusFile, plan, delivery,
                            "not sent after " + plan.attempts() + " attempt(s): " + lastDetail);
                    return true;
                }
            }
        }
    }

    /**
     * Final DMS state once no further attempt will be made, from the per-transaction record:
     * {@code request_sent} (all or some acknowledged; the latter with the {@code partial: ...}
     * summary) or {@code failed} with {@code failureDetail}.
     */
    private static void finishDms(
            DiLink5AssistOffStatus status, File statusFile, DiLink5AssistOffPlan plan,
            DiLink5DmsSettingsClient.Delivery delivery, String failureDetail) {
        DiLink5DmsSettingsClient.Result result = delivery.finalResult(failureDetail);
        boolean sent = result.outcome == DiLink5DmsSettingsClient.Outcome.REQUEST_SENT;
        status.update(Control.DMS, sent ? State.REQUEST_SENT : State.FAILED, plan.attempts(),
                System.currentTimeMillis(), UNAVAILABLE, UNAVAILABLE, result.detail);
        if (sent) {
            logger.warn("DI5 assist-off dms: after " + plan.attempts() + " attempt(s): "
                    + result.detail + " (no readback available)");
        } else {
            logger.warn("DI5 assist-off dms: giving up after " + plan.attempts()
                    + " attempt(s): " + result.detail);
        }
        publish(statusFile, status);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** The run still belongs to the current ignition cycle: same generation, ACC on, not interrupted. */
    private static boolean cycleCurrent(BydDataCollector collector, long cycle) {
        return !DiLink5AssistOffPlan.cycleAborted(cycle, cycleGeneration.get(), collector.isAccOn())
                && !Thread.currentThread().isInterrupted();
    }

    /**
     * {@link #cycleCurrent} AND this process is still the active DiLink 5 producer with the DiPilot
     * device reachable ({@code diLink5DiPilotDeviceForAssistOff()} is null the moment the producer
     * stops, e.g. on a mode change), so a live mode change mid-run ends the remaining attempts of
     * the SDK controls and of DMS alike. Used after readiness; the readiness loop fetches the device
     * itself and tolerates its absence until the deadline.
     */
    private static boolean stillCurrent(BydDataCollector collector, long cycle) {
        return cycleCurrent(collector, cycle)
                && collector.diLink5DiPilotDeviceForAssistOff() != null;
    }

    /**
     * Stationary gate run immediately before every write, with the strict rule of
     * {@link DiLink5AssistOffPlan#motion}: moving OR no positive evidence ends the run (status
     * written), and nothing else is written this cycle.
     */
    private static boolean stationary(
            BydDataCollector collector, DiLink5AssistOffStatus status, File statusFile,
            String where) {
        double speed = currentSpeedKmh(collector);
        int gear = currentGearMode(collector);
        if (DiLink5AssistOffPlan.motion(speed, gear) != Motion.STATIONARY) {
            endRun(status, statusFile, DiLink5AssistOffPlan.describeMotion(speed, gear)
                    + " at " + where + "; no further writes this cycle");
            return false;
        }
        return true;
    }

    /** Snapshot speed; NaN when unknown (no snapshot, cleared driving fields, non-finite). */
    private static double currentSpeedKmh(BydDataCollector collector) {
        try {
            BydVehicleData data = collector.getData();
            if (data == null || !Double.isFinite(data.speedKmh)) return Double.NaN;
            return data.speedKmh;
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }

    /**
     * Snapshot gear ({@code BydVehicleData.gearMode}, the source-observed raw gearbox enum the DI5
     * fast-dynamics poll and {@code collectGearbox} fill; cleared to UNAVAILABLE at ACC OFF, so a
     * known value is from this ignition cycle). UNAVAILABLE when unknown. Deliberately NOT
     * {@code GearMonitor.getCurrentGear()}, whose cold default is P.
     */
    private static int currentGearMode(BydDataCollector collector) {
        try {
            BydVehicleData data = collector.getData();
            if (data == null || !DiLink5AssistOffPlan.isKnownGear(data.gearMode)) {
                return BydVehicleData.UNAVAILABLE;
            }
            return data.gearMode;
        } catch (Throwable ignored) {
            return BydVehicleData.UNAVAILABLE;
        }
    }

    private static void endRun(DiLink5AssistOffStatus status, File statusFile, String reason) {
        status.skip(reason);
        logger.warn("DI5 assist-off: skipped: " + reason);
        publish(statusFile, status);
    }

    private static void logAttempt(
            Control control, int attempt, int writeValue, int before, int after,
            boolean verified, String detail) {
        logger.info("DI5 assist-off " + control.key() + ": attempt " + attempt
                + " write=" + writeValue + " before=" + describe(before) + " after="
                + describe(after) + " verified=" + verified
                + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static String describe(int read) {
        return read == UNAVAILABLE ? "n/a" : Integer.toString(read);
    }

    private static int readInt(Object device, String getter) {
        Object value = BydDeviceHelper.callGetter(device, getter);
        return value instanceof Number ? ((Number) value).intValue() : UNAVAILABLE;
    }

    /** DiPilot setters answer a non-negative code when accepted; null/negative is a refusal. */
    private static boolean writeInt(Object device, String setter, int value) {
        Object rc = BydDeviceHelper.callMethod(device, setter, value);
        return rc instanceof Number && ((Number) rc).intValue() >= 0;
    }

    private static File resolveStatusFile(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            File file = DiLink5AssistOffStatus.statusFile(dir);
            if (file == null && !statusDirWarned) {
                statusDirWarned = true;
                logger.warn("DI5 assist-off: no external files dir; status not published");
            }
            return file;
        } catch (Throwable failure) {
            return null;
        }
    }

    private static void publish(File statusFile, DiLink5AssistOffStatus status) {
        if (statusFile == null) return;
        try {
            DiLink5AssistOffStatus.write(statusFile, status.toJson());
        } catch (Throwable ignored) {
            // Diagnostic only.
        }
    }
}
