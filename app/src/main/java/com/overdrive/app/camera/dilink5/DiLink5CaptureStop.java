package com.overdrive.app.camera.dilink5;

/** Shell step for the DiLink 5 kill sweeps, placed before their fast_cam_capture kill -9 lines. */
public final class DiLink5CaptureStop {

    private DiLink5CaptureStop() {}

    /**
     * SIGTERM every fast_cam_capture and wait up to 3 s: its handler stops and closes each camera
     * before the buffers are freed, which a SIGKILL mid-stream skips.
     */
    public static String gracefulStopScript() {
        return "FC_PIDS=$(ps -A -o PID,ARGS | grep -F 'fast_cam_capture' | grep -v grep "
                + "| awk -v self=$$ '$1 != self {print $1}')\n"
                + "if [ -n \"$FC_PIDS\" ]; then kill -TERM $FC_PIDS 2>/dev/null; FC_WAIT=0; "
                + "while [ $FC_WAIT -lt 30 ]; do FC_LIVE=; for FC_P in $FC_PIDS; do "
                + "[ -d /proc/$FC_P ] && FC_LIVE=1; done; [ -z \"$FC_LIVE\" ] && break; "
                + "sleep 0.1; FC_WAIT=$((FC_WAIT + 1)); done; fi\n";
    }
}
