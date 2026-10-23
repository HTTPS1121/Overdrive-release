package com.overdrive.app.camera.camera2;

import android.view.Surface;

import com.overdrive.app.camera.CameraRole;
import com.overdrive.app.logging.DaemonLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Camera2 360 source: opens the BYD AVM super-frame camera through the NDK
 * camera API and streams it into the pipeline's ImageReader surface.
 * Only the selected 360 camera is ever opened.
 */
public final class Camera2AvmBackend {

    private static final String TAG = "Camera2Avm";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String LIBRARY = "camera2_avm";

    static final String AVM_POSITION = "avm";
    static final int SUPER_FRAME_WIDTH = 5120;
    static final int SUPER_FRAME_HEIGHT = 960;

    static final int FLAG_PRIVATE_SUPER_FRAME = 1;
    static final int FLAG_SYSTEM_CAMERA = 2;

    static final int DEVICE_OK = 0;
    static final int DEVICE_DISCONNECTED = 1;
    static final int DEVICE_ERROR = 2;

    private static final long DESCRIBE_AT_LOAD_TIMEOUT_MS = 3_000L;

    /** camera.camera2SliceOrder: "F;R;L;B"-style override of the HAL order; empty or absent is Auto. */
    public static final String CONFIG_SLICE_ORDER_KEY = "camera2SliceOrder";

    private static final Object LOAD_LOCK = new Object();
    private static volatile boolean sLibraryLoaded;
    private static volatile String sLibraryLoadError;
    private static volatile String sNativeLibDir;
    // One tile order per daemon run: frozen at the first read after the load-time lookup
    // settles, so geometry built at different times can never disagree.
    private static final Object ROLES_LOCK = new Object();
    private static CameraRole[] sSliceRoles;           // guarded by ROLES_LOCK
    private static boolean sSliceRolesFrozen;          // guarded by ROLES_LOCK
    private static boolean sSliceOrderNoted;           // guarded by ROLES_LOCK
    private static volatile boolean sSliceLookupSettled;
    private static volatile String sConfiguredSliceOrder;
    private static volatile boolean sSuperFrameOrderMissingLogged;
    // Set once a session's start-up errors were tolerated; the NDK then refuses every
    // frame-rate change, so later sessions open at the highest rate this run has asked for.
    private static volatile boolean sStartupErrorsSeen;
    private static final java.util.concurrent.atomic.AtomicInteger sPeakTargetFps =
            new java.util.concurrent.atomic.AtomicInteger();

    private final String cameraId;
    private final int[] fpsRanges;
    // Device-state token: polled lock-free, so a wedged close never blocks the watchdog.
    private final long token;
    private final long openStartedAtMs;
    private long handle;
    private int appliedFps;
    private volatile boolean closed;
    private volatile boolean startupErrorsTolerated;
    private boolean closeClean = true;

    private Camera2AvmBackend(String cameraId, int[] fpsRanges, long handle, long token,
                              int appliedFps, long openStartedAtMs) {
        this.cameraId = cameraId;
        this.fpsRanges = fpsRanges;
        this.handle = handle;
        this.token = token;
        this.appliedFps = appliedFps;
        this.openStartedAtMs = openStartedAtMs;
    }

    /** Thrown when the 360 camera cannot be opened; {@code permanent} needs a daemon restart to change. */
    public static final class Camera2AvmException extends Exception {
        public final boolean permanent;

        Camera2AvmException(String message, boolean permanent) {
            super(message);
            this.permanent = permanent;
        }
    }

    static final class CameraInfo {
        final String id;
        final String position;
        final String superFrameOrder;
        final int flags;
        final int[] fpsRanges;

        CameraInfo(String id, String position, String superFrameOrder, int flags, int[] fpsRanges) {
            this.id = id;
            this.position = position;
            this.superFrameOrder = superFrameOrder;
            this.flags = flags;
            this.fpsRanges = fpsRanges;
        }

        boolean hasPrivateSuperFrame() {
            return (flags & FLAG_PRIVATE_SUPER_FRAME) != 0;
        }

        @Override
        public String toString() {
            return "id=" + id + " position=" + (position.isEmpty() ? "?" : position)
                    + " order=" + (superFrameOrder.isEmpty() ? "?" : superFrameOrder)
                    + " superFrame=" + hasPrivateSuperFrame()
                    + " system=" + ((flags & FLAG_SYSTEM_CAMERA) != 0);
        }
    }

    // ==================== Library ====================

    /** Daemon-only: loads the bridge when the Camera2 mode is active. */
    public static boolean ensureNativeLibrariesLoaded(String nativeLibDir) {
        if (nativeLibDir != null && !nativeLibDir.trim().isEmpty()) {
            sNativeLibDir = nativeLibDir;
        }
        if (!Camera2Platform.isSelected()) return false;
        synchronized (LOAD_LOCK) {
            if (sLibraryLoaded) return true;
            try {
                String dir = sNativeLibDir;
                if (dir == null || dir.trim().isEmpty()) {
                    System.loadLibrary(LIBRARY);
                } else {
                    java.io.File library = new java.io.File(dir, System.mapLibraryName(LIBRARY));
                    if (!library.isFile()) {
                        throw new UnsatisfiedLinkError("Missing native library: " + library.getPath());
                    }
                    System.load(library.getAbsolutePath());
                }
                sLibraryLoaded = true;
                sLibraryLoadError = null;
            } catch (Throwable t) {
                sLibraryLoadError = t.getMessage();
                logger.error("Camera2 bridge library unavailable: " + t.getMessage());
                return false;
            }
        }
        CameraRole[] configured = configuredSliceRoles();
        if (configured != null) {
            synchronized (ROLES_LOCK) {
                sSliceRoles = configured;
                sSliceOrderNoted = true;
            }
            sSliceLookupSettled = true;
            logger.info("360 slice order from settings: " + sConfiguredSliceOrder);
            return true;
        }
        // Bounded: a slow camera service must not hold up daemon startup.
        Thread describe = new Thread(Camera2AvmBackend::cacheSliceRoles, "Camera2Describe");
        describe.setDaemon(true);
        describe.start();
        try {
            describe.join(DESCRIBE_AT_LOAD_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (describe.isAlive()) {
            logger.warn("Camera2 camera list not ready after " + DESCRIBE_AT_LOAD_TIMEOUT_MS
                    + "ms; this daemon run uses the F;R;L;B slice order");
        }
        sSliceLookupSettled = true;
        return true;
    }

    public static boolean isLibraryLoaded() {
        return sLibraryLoaded;
    }

    /**
     * Role per super-frame slice from the settings override, else as published by the HAL,
     * or null when unknown (app process, library not loaded, or no usable SuperFrameOrder).
     */
    public static CameraRole[] cachedSliceRoles() {
        synchronized (ROLES_LOCK) {
            if (sSliceLookupSettled) sSliceRolesFrozen = true;
            return sSliceRoles != null ? sSliceRoles.clone() : null;
        }
    }

    /** The settings override, or null for Auto. */
    private static CameraRole[] configuredSliceRoles() {
        try {
            String order = normalizeSliceOrder(com.overdrive.app.camera.CameraConfigResolver
                    .getCameraSection().optString(CONFIG_SLICE_ORDER_KEY, ""));
            if (order == null) {
                logger.warn("Ignoring invalid 360 slice order setting; using the camera's order");
                return null;
            }
            if (order.isEmpty()) return null;
            sConfiguredSliceOrder = order;
            return sliceRolesFromOrder(order);
        } catch (Throwable t) {
            logger.warn("360 slice order setting unreadable: " + t.getMessage());
            return null;
        }
    }

    private static void cacheSliceRoles() {
        try {
            List<CameraInfo> cameras = parseDescriptors(nativeDescribeCameras());
            CameraInfo avm = selectAvmCamera(cameras, -1);
            if (avm != null) noteHalSliceOrder(avm.superFrameOrder);
        } catch (Throwable t) {
            logger.warn("SuperFrameOrder lookup failed: " + t.getMessage());
        }
    }

    /** First HAL report wins unless this run's geometry was already built; then a mismatch is only logged. */
    private static void noteHalSliceOrder(String order) {
        synchronized (ROLES_LOCK) {
            if (sSliceOrderNoted) return;
            sSliceOrderNoted = true;
            CameraRole[] roles = sliceRolesFromOrder(order);
            if (roles == null) {
                if (order != null && !order.isEmpty()) {
                    logger.warn("Unrecognised SuperFrameOrder '" + order + "' — using F;R;L;B");
                }
                return;
            }
            if (!sSliceRolesFrozen) {
                sSliceRoles = roles;
            } else if (!java.util.Arrays.equals(roles, sSliceRoles != null ? sSliceRoles
                    : com.overdrive.app.camera.CameraProfiles.CAMERA2_DEFAULT_SLICE_ROLES)) {
                logger.warn("360 slice order " + order + " arrived after the camera layout was"
                        + " built; restart the OverDrive camera daemon to apply it");
            }
        }
    }

    // ==================== Open / control ====================

    /**
     * Opens the 360 camera into {@code surface}. {@code hintCameraId} is only
     * used when no camera identifies itself as the AVM super-frame.
     */
    public static Camera2AvmBackend open(Surface surface, int hintCameraId, int targetFps)
            throws Camera2AvmException {
        if (surface == null || !surface.isValid()) {
            throw new Camera2AvmException("camera surface is not available", false);
        }
        if (!ensureNativeLibrariesLoaded(null)) {
            throw new Camera2AvmException("Camera2 bridge library unavailable: "
                    + sLibraryLoadError, true);
        }
        long handle = 0;
        try {
            List<CameraInfo> cameras = parseDescriptors(nativeDescribeCameras());
            CameraInfo avm = selectAvmCamera(cameras, hintCameraId);
            if (avm == null) {
                String why = cameras.isEmpty() ? nativeLastError() : "";
                // Retryable: the camera provider can still be enumerating at boot.
                throw new Camera2AvmException("no Camera2 device offers the "
                        + SUPER_FRAME_WIDTH + "x" + SUPER_FRAME_HEIGHT
                        + " 360 super-frame (cameras: "
                        + (cameras.isEmpty()
                                ? "none visible" + (why.isEmpty() ? "" : ", " + why)
                                : cameras) + ")", false);
            }
            // Covers a boot lookup that missed the camera; never changes an order already in use.
            noteHalSliceOrder(avm.superFrameOrder);
            if (avm.superFrameOrder.isEmpty() && !sSuperFrameOrderMissingLogged) {
                sSuperFrameOrderMissingLogged = true;
                logger.info("Camera2 360 camera reports no SuperFrameOrder: " + avm);
            }
            notePeakTargetFps(targetFps);
            int rangeFps = sStartupErrorsSeen ? Math.max(targetFps, sPeakTargetFps.get()) : targetFps;
            int[] range = chooseFpsRange(avm.fpsRanges, rangeFps);
            long openStartedAtMs = android.os.SystemClock.elapsedRealtime();
            handle = nativeOpen(avm.id, surface,
                    range != null ? range[0] : 0, range != null ? range[1] : 0);
            if (handle == 0) {
                throw new Camera2AvmException("camera " + avm.id + " open failed: "
                        + nativeLastError(), false);
            }
            long token = nativeSessionToken(handle);
            logger.info("Camera2 360 camera " + avm.id + " streaming "
                    + SUPER_FRAME_WIDTH + "x" + SUPER_FRAME_HEIGHT
                    + " (order=" + (avm.superFrameOrder.isEmpty()
                            ? "default F;R;L;B" : avm.superFrameOrder)
                    + ", fpsRange=" + formatRange(range) + ", target=" + targetFps + ")"
                    + (sConfiguredSliceOrder != null
                            ? " settings order=" + sConfiguredSliceOrder : ""));
            return new Camera2AvmBackend(avm.id, avm.fpsRanges, handle, token, targetFps,
                    openStartedAtMs);
        } catch (LinkageError e) {
            if (handle != 0) {
                try {
                    nativeClose(handle);
                } catch (Throwable ignored) {
                }
            }
            throw new Camera2AvmException("Camera2 bridge library is incompatible: " + e, true);
        }
    }

    public String getCameraId() {
        return cameraId;
    }

    /** Re-applies the AE frame-rate range; exact pacing is done by the consumer. */
    public synchronized void setTargetFps(int fps) {
        notePeakTargetFps(fps);
        if (handle == 0 || fps == appliedFps) return;
        if (startupErrorsTolerated) {
            // The NDK refuses every request on a device that reported an error.
            appliedFps = fps;
            return;
        }
        int[] range = chooseFpsRange(fpsRanges, fps);
        if (range == null) {
            appliedFps = fps;
            return;
        }
        if (nativeSetFpsRange(handle, range[0], range[1])) {
            appliedFps = fps;
        } else {
            logger.warn("Camera2 fps range " + formatRange(range) + " rejected: "
                    + nativeLastError());
        }
    }

    /**
     * Reason for a device disconnect/error until the session is closed, else null.
     * Lock-free, so the watchdog never blocks on close().
     */
    public String pollDeviceFailure() {
        if (closed) return null;
        int state = nativeDeviceState(token);
        // close() retires the token before the native session is freed.
        if (closed) return null;
        int kind = state & 0xff;
        if (kind == DEVICE_OK) return null;
        return kind == DEVICE_DISCONNECTED
                ? "camera " + cameraId + " disconnected (another client took it or the service restarted)"
                : "camera " + cameraId + " device error " + deviceErrorName(state >> 8);
    }

    /**
     * True while every device callback is an error raised within {@code windowMs} of open.
     * BYD camera services send private start-up codes that the NDK reports as a device error.
     */
    public boolean hasOnlyStartupErrors(long windowMs) {
        if (closed) return false;
        int kind = nativeDeviceState(token) & 0xff;
        if (kind != DEVICE_ERROR || nativeDeviceDisconnected(token)) return false;
        long lastErrorAtMs = nativeLastDeviceErrorAtMs(token);
        return !closed && lastErrorAtMs > 0 && lastErrorAtMs - openStartedAtMs <= windowMs;
    }

    /** elapsedRealtime of the latest device error, 0 if none, -1 once closed. */
    public long lastDeviceErrorAtMs() {
        return closed ? -1L : nativeLastDeviceErrorAtMs(token);
    }

    /** True once the device reported a disconnect, including one after an earlier error. */
    public boolean wasDisconnected() {
        return !closed && nativeDeviceDisconnected(token);
    }

    /** Frames kept arriving after this session's start-up errors. */
    public synchronized void noteStartupErrorsTolerated() {
        if (startupErrorsTolerated) return;
        startupErrorsTolerated = true;
        sStartupErrorsSeen = true;
        logger.info("Camera2 360 camera " + cameraId + " keeps its open frame rate for this session;"
                + " later sessions open at " + sPeakTargetFps.get() + " fps and pace down");
    }

    private static void notePeakTargetFps(int fps) {
        int peak;
        while (fps > (peak = sPeakTargetFps.get()) && !sPeakTargetFps.compareAndSet(peak, fps)) {
            // retry
        }
    }

    /** Stops streaming and releases the device; true when the close completed. */
    public synchronized boolean close() {
        closed = true;
        long h = handle;
        handle = 0;
        if (h == 0) return closeClean;
        closeClean = nativeClose(h);
        if (closeClean) {
            logger.info("Camera2 360 camera " + cameraId + " closed");
        } else {
            logger.warn("Camera2 360 camera " + cameraId + " close incomplete: " + nativeLastError());
        }
        return closeClean;
    }

    // ==================== Pure helpers ====================

    /** Lines of {@code id|position|superFrameOrder|flags|min-max,min-max}. */
    static List<CameraInfo> parseDescriptors(String text) {
        List<CameraInfo> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String line : text.split("\n")) {
            String[] parts = line.split("\\|", -1);
            if (parts.length < 5 || parts[0].trim().isEmpty()) continue;
            int flags;
            try {
                flags = Integer.parseInt(parts[3].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            out.add(new CameraInfo(parts[0].trim(), parts[1].trim(), parts[2].trim(),
                    flags, parseRanges(parts[4])));
        }
        return out;
    }

    private static int[] parseRanges(String text) {
        List<Integer> values = new ArrayList<>();
        for (String pair : text.split(",")) {
            String[] bounds = pair.trim().split("-");
            if (bounds.length != 2) continue;
            try {
                int min = Integer.parseInt(bounds[0].trim());
                int max = Integer.parseInt(bounds[1].trim());
                if (min > 0 && max >= min) {
                    values.add(min);
                    values.add(max);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }

    /**
     * The camera that names itself "avm", else the one carrying a SuperFrameOrder,
     * else the hinted id; each must offer the PRIVATE 5120x960 output.
     */
    static CameraInfo selectAvmCamera(List<CameraInfo> cameras, int hintCameraId) {
        if (cameras == null) return null;
        for (CameraInfo camera : cameras) {
            if (camera.hasPrivateSuperFrame()
                    && AVM_POSITION.equalsIgnoreCase(camera.position)) {
                return camera;
            }
        }
        for (CameraInfo camera : cameras) {
            if (camera.hasPrivateSuperFrame() && !camera.superFrameOrder.isEmpty()) {
                return camera;
            }
        }
        if (hintCameraId >= 0) {
            String hint = Integer.toString(hintCameraId);
            for (CameraInfo camera : cameras) {
                if (camera.hasPrivateSuperFrame() && hint.equals(camera.id)) {
                    return camera;
                }
            }
        }
        return null;
    }

    /**
     * Fixed ranges (min == max) at or above the target first, then any range
     * whose max covers it, then the highest available. Null when none.
     */
    static int[] chooseFpsRange(int[] ranges, int targetFps) {
        if (ranges == null || ranges.length < 2) return null;
        int target = Math.max(1, targetFps);
        int[] best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            int min = ranges[i];
            int max = ranges[i + 1];
            int score;
            if (max >= target) {
                score = (max - target) * 4 + (min == max ? 0 : 2) + (min > target ? 1 : 0);
            } else {
                score = 100_000 + (target - max) * 4;
            }
            if (score < bestScore) {
                bestScore = score;
                best = new int[] {min, max};
            }
        }
        return best;
    }

    /** "F;R;L;B;" -> role per slice; null unless it names each 360 view exactly once. */
    static CameraRole[] sliceRolesFromOrder(String order) {
        if (order == null) return null;
        List<CameraRole> roles = new ArrayList<>();
        for (String token : order.split(";")) {
            String t = token.trim().toUpperCase(Locale.US);
            if (t.isEmpty()) continue;
            CameraRole role;
            switch (t) {
                case "F": role = CameraRole.PANO_FRONT; break;
                case "R": role = CameraRole.PANO_RIGHT; break;
                case "L": role = CameraRole.PANO_LEFT; break;
                case "B": role = CameraRole.PANO_REAR; break;
                default: return null;
            }
            if (roles.contains(role)) return null;
            roles.add(role);
        }
        return roles.size() == 4 ? roles.toArray(new CameraRole[0]) : null;
    }

    /** Canonical "F;R;L;B" form, "" for Auto, or null unless it names each 360 view exactly once. */
    public static String normalizeSliceOrder(String order) {
        if (order == null || order.trim().isEmpty()) return "";
        CameraRole[] roles = sliceRolesFromOrder(order);
        if (roles == null) return null;
        StringBuilder out = new StringBuilder();
        for (CameraRole role : roles) {
            if (out.length() > 0) out.append(';');
            switch (role) {
                case PANO_FRONT: out.append('F'); break;
                case PANO_RIGHT: out.append('R'); break;
                case PANO_LEFT: out.append('L'); break;
                default: out.append('B'); break;
            }
        }
        return out.toString();
    }

    private static String formatRange(int[] range) {
        return range == null ? "HAL default" : "[" + range[0] + "," + range[1] + "]";
    }

    private static String deviceErrorName(int code) {
        switch (code) {
            case 1: return "CAMERA_IN_USE";
            case 2: return "MAX_CAMERAS_IN_USE";
            case 3: return "CAMERA_DISABLED";
            case 4: return "CAMERA_DEVICE";
            case 5: return "CAMERA_SERVICE";
            default: return Integer.toString(code);
        }
    }

    private static native String nativeDescribeCameras();
    private static native long nativeOpen(String cameraId, Surface surface, int fpsMin, int fpsMax);
    private static native boolean nativeSetFpsRange(long handle, int fpsMin, int fpsMax);
    private static native long nativeSessionToken(long handle);
    private static native int nativeDeviceState(long token);
    private static native long nativeLastDeviceErrorAtMs(long token);
    private static native boolean nativeDeviceDisconnected(long token);
    private static native boolean nativeClose(long handle);
    private static native String nativeLastError();
}
