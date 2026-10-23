package com.overdrive.app.surveillance.impact

import kotlin.math.sqrt

/**
 * Parked bump detector over raw accelerometer samples (m/s², device frame).
 *
 * Gravity is tracked with a slow low-pass; the residual |a − ĝ| is the shock channel, which
 * absorbs the head unit's mount tilt. A hit needs two consecutive samples over the threshold so
 * a single-sample glitch cannot fire, and nothing fires during the initial settle window while ĝ
 * is still converging. Pure logic — no Android types — so it can be replayed off-device.
 */
class ImpactDetector(
    private val thresholdMs2: Float,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val settleMs: Long = DEFAULT_SETTLE_MS,
) {
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var seeded = false
    private var firstSampleMs = 0L
    private var consecutiveOver = 0
    private var runPeak = 0f
    private var lastFireMs = Long.MIN_VALUE / 2

    /** Feed one sample. Returns the peak residual when a hit is confirmed, otherwise NaN. */
    fun onSample(ax: Float, ay: Float, az: Float, nowMs: Long): Float {
        if (!seeded) {
            gx = ax; gy = ay; gz = az
            seeded = true
            firstSampleMs = nowMs
            return Float.NaN
        }
        val rx = ax - gx
        val ry = ay - gy
        val rz = az - gz
        val residual = sqrt(rx * rx + ry * ry + rz * rz)
        gx += GRAVITY_ALPHA * rx
        gy += GRAVITY_ALPHA * ry
        gz += GRAVITY_ALPHA * rz

        if (nowMs - firstSampleMs < settleMs) {
            consecutiveOver = 0
            runPeak = 0f
            return Float.NaN
        }
        if (residual >= thresholdMs2) {
            consecutiveOver++
            if (residual > runPeak) runPeak = residual
        } else {
            consecutiveOver = 0
            runPeak = 0f
            return Float.NaN
        }
        if (consecutiveOver >= CONFIRM_SAMPLES && nowMs - lastFireMs >= cooldownMs) {
            val peak = runPeak
            lastFireMs = nowMs
            consecutiveOver = 0
            runPeak = 0f
            return peak
        }
        return Float.NaN
    }

    companion object {
        /** ~1 s time constant at the 50 Hz sampling rate the service requests. */
        const val GRAVITY_ALPHA = 0.02f
        const val CONFIRM_SAMPLES = 2
        const val DEFAULT_COOLDOWN_MS = 30_000L
        const val DEFAULT_SETTLE_MS = 3_000L

        /**
         * Residual thresholds per sensitivity. The measured driving noise floor on this sensor is
         * ±0.18 m/s² and a speed bump peaks near 3.5 m/s², so even "high" sits well above noise.
         */
        fun thresholdFor(sensitivity: String?): Float = when (sensitivity) {
            "high" -> 1.0f
            "low" -> 3.0f
            else -> 1.8f
        }
    }
}
