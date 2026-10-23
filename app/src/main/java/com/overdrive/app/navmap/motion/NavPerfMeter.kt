package com.overdrive.app.navmap.motion

/** Rolling map render cost for the field log: frames per second and CPU render time percentiles. */
class NavPerfMeter(private val periodMs: Long = 30_000L) {

    private val costs = DoubleArray(4096)
    private var n = 0
    private var frames = 0
    private var startMs = 0L
    private val sorted = DoubleArray(costs.size)

    /** One rendered frame; [encodingTime]/[renderingTime] as MapLibre reports them (seconds or ms). */
    fun onFrame(encodingTime: Double, renderingTime: Double) {
        var ms = encodingTime + renderingTime
        if (ms > 0.0 && ms < 0.5) ms *= 1000.0
        synchronized(this) {
            if (n < costs.size) costs[n++] = ms
            frames++
        }
    }

    /** A summary line once per period while [active], else null; restarts the window. */
    fun summary(nowMs: Long, active: Boolean, divisor: Int, lowTier: Boolean): String? {
        if (!active) {
            synchronized(this) { n = 0; frames = 0 }
            startMs = 0L
            return null
        }
        if (startMs == 0L) {
            startMs = nowMs
            synchronized(this) { n = 0; frames = 0 }
            return null
        }
        val span = nowMs - startMs
        if (span < periodMs) return null
        val count: Int
        val frameCount: Int
        synchronized(this) {
            count = n
            frameCount = frames
            System.arraycopy(costs, 0, sorted, 0, count)
            n = 0
            frames = 0
        }
        startMs = nowMs
        if (count == 0) return null
        java.util.Arrays.sort(sorted, 0, count)
        val p50 = sorted[(count - 1) / 2]
        val p95 = sorted[((count - 1) * 95) / 100]
        val fps = frameCount * 1000.0 / span
        return String.format(
            java.util.Locale.ROOT, "fps=%.1f render p50=%.1fms p95=%.1fms target=%dfps tier=%s",
            fps, p50, p95, 60 / divisor.coerceAtLeast(1), if (lowTier) "low" else "full"
        )
    }
}
