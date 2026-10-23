package com.overdrive.app.camera.camera2;

import java.util.Locale;

/**
 * Opt-in boundary for the Camera2 360 camera path.
 *
 * <p>Camera-only: the active-mode marker is still owned by the shared vehicle-mode
 * state machine, and every non-camera subsystem treats this mode as
 * {@code default}. Nothing here changes DiLink 3/4/5 behaviour.
 */
public final class Camera2Platform {

    public static final String MODE = "camera2";

    private Camera2Platform() {}

    /** True when the camera mode committed to this process is Camera2. */
    public static boolean isSelected() {
        return isMode(com.overdrive.app.camera.dilink5.DiLink5Platform
                .currentActiveMode());
    }

    /** Pure check against a persisted/user-supplied mode string. */
    public static boolean isMode(String cameraMode) {
        return cameraMode != null
                && MODE.equals(cameraMode.trim().toLowerCase(Locale.US));
    }
}
