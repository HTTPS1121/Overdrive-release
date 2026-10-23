package com.overdrive.app.navmap.motion

import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Display-synced frame driver: [frame] runs on vsync (every [divisor]-th one) with the vsync instant
 * in the elapsedRealtime clock, and returns false once there is nothing left to animate. Frames
 * never come from a timer, so updates land evenly on the display's refresh.
 *
 * Rate: 60 fps only where [allowFull] and the GPU keeps up; otherwise an even 30 fps. If rendered
 * frames fall short of what was asked for, it drops to 30 and retries full rate later.
 */
class NavFrameLoop(
    private val allowFull: Boolean,
    private val frame: (nowMs: Long) -> Boolean
) : Choreographer.FrameCallback {

    private var running = false
    private var tick = 0L
    private var divisorLimit = 0
    var divisor = if (allowFull) 1 else 2
        private set

    private val rendered = AtomicInteger(0)
    private var windowStartMs = 0L
    private var windowTicks = 0
    private var shortWindows = 0
    private var fullRetryAtMs = 0L

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        tick = 0L
        windowStartMs = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    /** Caps the rate at 60/[d] fps (e.g. 12 → 5 fps); 0 removes the cap. */
    fun limitDivisor(d: Int) {
        divisorLimit = d
    }

    /** Called for every frame the map renderer finished, from any thread. */
    fun onFrameRendered() {
        rendered.incrementAndGet()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val effective = if (divisorLimit > 0) maxOf(divisor, divisorLimit) else divisor
        var keep = true
        if (tick++ % effective == 0L) {
            val nowMs = (frameTimeNanos + (SystemClock.elapsedRealtimeNanos() - System.nanoTime())) / 1_000_000L
            keep = try {
                frame(nowMs)
            } catch (t: Throwable) {
                Log.w(TAG, "frame failed: ${t.javaClass.simpleName}")
                true
            }
            if (divisorLimit == 0) adaptRate(nowMs, effective)
        }
        if (keep && running) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            running = false
        }
    }

    private fun adaptRate(nowMs: Long, effective: Int) {
        if (windowStartMs == 0L) {
            windowStartMs = nowMs
            windowTicks = 0
            rendered.set(0)
            return
        }
        windowTicks++
        if (nowMs - windowStartMs < WINDOW_MS) return
        val asked = windowTicks
        val got = rendered.getAndSet(0)
        windowStartMs = nowMs
        windowTicks = 0
        if (effective == 1) {
            shortWindows = if (got < asked * SHORTFALL) shortWindows + 1 else 0
            if (shortWindows >= 2) {
                divisor = 2
                shortWindows = 0
                fullRetryAtMs = nowMs + FULL_RETRY_MS
                Log.i(TAG, "rate 30 fps (rendered $got of $asked)")
            }
        } else if (allowFull && nowMs >= fullRetryAtMs && got >= asked) {
            divisor = 1
            Log.i(TAG, "rate 60 fps retry")
        }
    }

    companion object {
        private const val TAG = "NavFrameLoop"
        private const val WINDOW_MS = 1500L
        private const val SHORTFALL = 0.85
        private const val FULL_RETRY_MS = 120_000L

        /** High-end Qualcomm automotive SoCs (8155 class and up) can hold 60 fps beside the camera pipeline. */
        fun fullRateCapable(): Boolean {
            if (Build.VERSION.SDK_INT < 31) return false
            val soc = try { Build.SOC_MODEL } catch (_: Throwable) { "" }.uppercase()
            return FULL_RATE_SOCS.any { soc.contains(it) }
        }

        private val FULL_RATE_SOCS = listOf("8155", "8195", "8255", "8295", "8775", "SM8150", "SM8250", "SM8350")
    }
}
