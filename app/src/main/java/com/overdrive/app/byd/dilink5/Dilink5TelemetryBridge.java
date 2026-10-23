package com.overdrive.app.byd.dilink5;

import android.app.Application;
import android.os.SystemClock;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.remote.RemoteDevViewBridgeAuth;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** App-process pump for DI5 vehicle telemetry and transient vehicle events. */
public final class Dilink5TelemetryBridge {
    public static final String TELEMETRY_COMMAND = "DILINK5_TELEMETRY";
    public static final String EVENT_COMMAND = "DILINK5_VEHICLE_EVENT";

    private static final DaemonLogger logger =
            DaemonLogger.getInstance("Dilink5TelemetryBridge");
    private static final String APP_PROCESS = "com.overdrive.app";
    private static final int PORT = 19877;
    private static final int MAX_MESSAGE_BYTES = 1024 * 1024;
    private static final String HELLO_COMMAND = "DILINK5_HELLO";
    private static final long SOURCE_EPOCH = SystemClock.elapsedRealtimeNanos();
    private static final AtomicLong telemetrySequence = new AtomicLong();
    private static final AtomicLong eventSequence = new AtomicLong();
    private static final AtomicLong lifecycleGeneration = new AtomicLong();
    private static final AtomicReference<JSONObject> pendingTelemetry =
            new AtomicReference<>();
    private static final LinkedBlockingDeque<JSONObject> pendingEvents =
            new LinkedBlockingDeque<>(128);
    private static final Object eventEnqueueLock = new Object();
    private static final AtomicBoolean drainScheduled = new AtomicBoolean();
    private static final ScheduledExecutorService io =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "Dilink5TelemetryIpc");
                thread.setDaemon(true);
                return thread;
            });

    private static volatile boolean active;
    private static volatile Socket socket;
    private static BufferedReader input;
    private static OutputStream output;
    private static String ingressSession;

    private Dilink5TelemetryBridge() {}

    public static void publish(
            BydVehicleData data,
            long dynamicsObservedAtElapsedMs,
            boolean charging,
            int energyFeedback,
            int doorLockState,
            long doorLockObservedAtElapsedMs,
            int[] doorStates) {
        publish(data, dynamicsObservedAtElapsedMs, charging, energyFeedback,
                doorLockState, doorLockObservedAtElapsedMs, doorStates, null);
    }

    public static void publish(
            BydVehicleData data,
            long dynamicsObservedAtElapsedMs,
            boolean charging,
            int energyFeedback,
            int doorLockState,
            long doorLockObservedAtElapsedMs,
            int[] doorStates,
            long[] doorStatesObservedAtElapsedMs) {
        publish(data, dynamicsObservedAtElapsedMs, charging, energyFeedback,
                doorLockState, doorLockObservedAtElapsedMs, doorStates,
                doorStatesObservedAtElapsedMs, null);
    }

    /** {@code lockDiag} is the producer's latest "DI5 lock rails:" line; no per-rail readings. */
    public static void publish(
            BydVehicleData data,
            long dynamicsObservedAtElapsedMs,
            boolean charging,
            int energyFeedback,
            int doorLockState,
            long doorLockObservedAtElapsedMs,
            int[] doorStates,
            long[] doorStatesObservedAtElapsedMs,
            String lockDiag) {
        publish(data, dynamicsObservedAtElapsedMs, charging, energyFeedback,
                doorLockState, doorLockObservedAtElapsedMs, doorStates,
                doorStatesObservedAtElapsedMs, lockDiag, null);
    }

    /**
     * Longest overload; {@code lockReadings} is the additive per-rail array
     * ({@code [{rail,state,raw,observedElapsedMs}]}, built by the collector's pure encoder). The
     * legacy {@code doorLockState}/{@code doorLockObservedElapsedMs} pair is still written unchanged,
     * so an older daemon keeps working; a newer daemon prefers the array when it is present.
     */
    public static void publish(
            BydVehicleData data,
            long dynamicsObservedAtElapsedMs,
            boolean charging,
            int energyFeedback,
            int doorLockState,
            long doorLockObservedAtElapsedMs,
            int[] doorStates,
            long[] doorStatesObservedAtElapsedMs,
            String lockDiag,
            JSONArray lockReadings) {
        if (data == null || !isActiveProcess()) return;
        try {
            JSONObject request = baseRequest(
                    TELEMETRY_COMMAND, telemetrySequence.incrementAndGet());
            if (dynamicsObservedAtElapsedMs > 0L) {
                request.put(
                        "dynamicsObservedElapsedMs",
                        dynamicsObservedAtElapsedMs);
            }
            request.put("charging", charging);
            if (energyFeedback >= 0 && energyFeedback <= 2) {
                request.put("energyFeedback", energyFeedback);
            }
            if (doorLockState >= 0 && doorLockState <= 2) {
                request.put("doorLockState", doorLockState);
                if (doorLockObservedAtElapsedMs > 0L) {
                    request.put("doorLockObservedElapsedMs", doorLockObservedAtElapsedMs);
                }
            }
            // Additive diagnostic only (protocol stays 1; an older daemon ignores the key).
            if (lockDiag != null) {
                request.put("lockDiag", sanitizeLockDiag(lockDiag));
            }
            // Additive per-rail lock readings (protocol stays 1; signed with the whole object).
            if (lockReadings != null && lockReadings.length() > 0) {
                request.put("lockReadings", lockReadings);
            }
            if (doorStates != null) {
                request.put("doorStates", new JSONArray(doorStates));
            }
            if (doorStatesObservedAtElapsedMs != null) {
                request.put("doorStatesObservedElapsedMs",
                        new JSONArray(doorStatesObservedAtElapsedMs));
            }
            request.put("data", data.toBridgeJson());
            active = true;
            pendingTelemetry.set(request);
            scheduleDrain(0L);
        } catch (Exception e) {
            logger.debug("Telemetry encode failed: " + e.getMessage());
        }
    }

    static String sanitizeLockDiag(String lockDiag) {
        return LockDiagSanitizer.sanitize(lockDiag);
    }

    /**
     * Pure helper (own class initialisation, so JVM tests can exercise it without touching the
     * bridge's {@code SystemClock}-backed statics): one line, at most
     * {@value #MAX_CHARS} chars, CR/LF stripped before signing.
     */
    static final class LockDiagSanitizer {
        static final int MAX_CHARS = 200;

        private LockDiagSanitizer() {}

        static String sanitize(String lockDiag) {
            if (lockDiag == null) return null;
            String flat = lockDiag.replace("\r", "").replace("\n", " ").trim();
            return flat.length() > MAX_CHARS ? flat.substring(0, MAX_CHARS) : flat;
        }
    }

    public static void publishEvent(String event, int area, int value) {
        if (event == null || event.isEmpty() || !isActiveProcess()) return;
        try {
            // The daemon rejects any event older than the last one it accepted, so numbering and
            // queueing must be one step or a concurrent publisher's event erases this one.
            synchronized (eventEnqueueLock) {
                JSONObject request = baseRequest(
                        EVENT_COMMAND, eventSequence.incrementAndGet());
                request.put("event", event);
                request.put("area", area);
                request.put("value", value);
                active = true;
                if (!pendingEvents.offerLast(request)) {
                    pendingEvents.pollFirst();
                    pendingEvents.offerLast(request);
                    logger.warn("Vehicle event queue full; dropped oldest event");
                }
            }
            scheduleDrain(0L);
        } catch (Exception e) {
            logger.debug("Vehicle event encode failed: " + e.getMessage());
        }
    }

    public static void stop() {
        active = false;
        lifecycleGeneration.incrementAndGet();
        closeSocket();
        pendingTelemetry.set(null);
        pendingEvents.clear();
    }

    private static JSONObject baseRequest(String command, long sequence)
            throws org.json.JSONException {
        JSONObject request = new JSONObject();
        request.put("command", command);
        request.put("protocol", 1);
        request.put("sourceEpoch", SOURCE_EPOCH);
        request.put("sequence", sequence);
        request.put("sentElapsedMs", SystemClock.elapsedRealtime());
        return request;
    }

    private static boolean isActiveProcess() {
        return DiLink5Platform.isSelected()
                && APP_PROCESS.equals(Application.getProcessName());
    }

    private static void scheduleDrain(long delayMs) {
        if (!drainScheduled.compareAndSet(false, true)) return;
        io.schedule(Dilink5TelemetryBridge::drain, delayMs, TimeUnit.MILLISECONDS);
    }

    private static void drain() {
        long generation = lifecycleGeneration.get();
        boolean retry = false;
        try {
            while (isLifecycleActive(generation)) {
                JSONObject event = pendingEvents.pollFirst();
                if (event != null) {
                    if (!send(event, generation)) {
                        if (isLifecycleActive(generation)) {
                            pendingEvents.offerFirst(event);
                            retry = true;
                        }
                        break;
                    }
                    continue;
                }

                JSONObject telemetry = pendingTelemetry.getAndSet(null);
                if (telemetry == null) break;
                if (!send(telemetry, generation)) {
                    if (isLifecycleActive(generation)) {
                        pendingTelemetry.accumulateAndGet(telemetry,
                                Dilink5TelemetryBridge::newerTelemetry);
                        retry = true;
                    }
                    break;
                }
            }
        } finally {
            drainScheduled.set(false);
            if (active && (!pendingEvents.isEmpty() || pendingTelemetry.get() != null)) {
                scheduleDrain(retry ? 1000L : 0L);
            }
        }
    }

    private static JSONObject newerTelemetry(JSONObject current, JSONObject failed) {
        if (current == null) return failed;
        return current.optLong("sequence") >= failed.optLong("sequence")
                ? current : failed;
    }

    private static boolean isLifecycleActive(long generation) {
        return active && lifecycleGeneration.get() == generation;
    }

    private static boolean send(JSONObject request, long generation) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!isLifecycleActive(generation)) return false;
            try {
                if (socket == null) openSocket(generation);
                if (!isLifecycleActive(generation)) {
                    closeSocket();
                    return false;
                }
                request.put("session", ingressSession);
                byte[] payload = (RemoteDevViewBridgeAuth.sign(request) + "\n")
                        .getBytes(StandardCharsets.UTF_8);
                if (payload.length > MAX_MESSAGE_BYTES) {
                    logger.warn("Dropping oversized telemetry message: " + payload.length);
                    return true;
                }
                output.write(payload);
                output.flush();
                return true;
            } catch (Throwable unavailable) {
                closeSocket();
            }
        }
        return false;
    }

    private static void openSocket(long generation) throws Exception {
        Socket connected = new Socket();
        socket = connected;
        if (!isLifecycleActive(generation)) {
            throw new IllegalStateException("bridge stopped");
        }
        connected.connect(new InetSocketAddress("127.0.0.1", PORT), 1000);
        connected.setTcpNoDelay(true);
        connected.setSoTimeout(2000);
        input = new BufferedReader(new InputStreamReader(
                connected.getInputStream(), StandardCharsets.UTF_8));
        output = connected.getOutputStream();

        JSONObject hello = new JSONObject()
                .put("command", HELLO_COMMAND)
                .put("protocol", 1);
        output.write((RemoteDevViewBridgeAuth.sign(hello) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        output.flush();
        String responseLine = input.readLine();
        JSONObject response = responseLine != null
                ? new JSONObject(responseLine) : null;
        ingressSession = response != null && response.optBoolean("success", false)
                ? response.optString("session", "") : "";
        if (ingressSession.isEmpty()) {
            throw new SecurityException("vehicle ingress handshake failed");
        }
        if (!isLifecycleActive(generation)) {
            throw new IllegalStateException("bridge stopped");
        }
        connected.setSoTimeout(0);
    }

    private static void closeSocket() {
        Socket connected = socket;
        socket = null;
        try {
            if (connected != null) connected.close();
        } catch (Throwable ignored) {}
        input = null;
        output = null;
        ingressSession = null;
    }
}
