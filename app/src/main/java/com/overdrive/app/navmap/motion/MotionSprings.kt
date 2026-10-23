package com.overdrive.app.navmap.motion

import kotlin.math.abs
import kotlin.math.exp

/** Critically damped offset that decays to zero; stepped with the exact solution, so any dt is stable. */
internal class DecaySpring(private val omega: Double) {
    var x = 0.0
    var v = 0.0

    fun step(dt: Double) {
        if (dt <= 0.0 || (x == 0.0 && v == 0.0)) return
        val e = exp(-omega * dt)
        val k = v + omega * x
        x = (x + k * dt) * e
        v = (v - omega * k * dt) * e
        if (abs(x) < 1e-7 && abs(v) < 1e-7) clear()
    }

    fun clear() {
        x = 0.0
        v = 0.0
    }

    fun settled(eps: Double): Boolean = abs(x) < eps && abs(v) < eps
}

/** Value that follows a moving target with critical damping (target held constant within a step). */
internal class FollowSpring(private val omega: Double) {
    var value = Double.NaN
        private set
    var velocity = 0.0
        private set

    fun step(target: Double, dt: Double): Double {
        if (value.isNaN() || dt <= 0.0) {
            if (value.isNaN()) {
                value = target
                velocity = 0.0
            }
            return value
        }
        val x0 = value - target
        val e = exp(-omega * dt)
        val k = velocity + omega * x0
        value = target + (x0 + k * dt) * e
        velocity = (velocity - omega * k * dt) * e
        return value
    }

    fun snap(v: Double) {
        value = v
        velocity = 0.0
    }

    fun clear() {
        value = Double.NaN
        velocity = 0.0
    }
}

/** [FollowSpring] over compass degrees: follows the shortest arc and reports 0..360. */
internal class AngleSpring(omega: Double) {
    private val s = FollowSpring(omega)

    val isSet: Boolean get() = !s.value.isNaN()
    val degrees: Double get() = norm(s.value)
    val rateDegPerS: Double get() = s.velocity

    fun step(targetDeg: Double, dt: Double): Double {
        val cur = s.value
        val t = if (cur.isNaN()) targetDeg else cur + shortestArc(cur, targetDeg)
        s.step(t, dt)
        return degrees
    }

    /** Advances toward the current value (no new target), letting residual velocity settle. */
    fun hold(dt: Double): Double {
        val cur = s.value
        if (cur.isNaN()) return 0.0
        s.step(cur, dt)
        return degrees
    }

    fun snap(deg: Double) = s.snap(deg)

    fun clear() = s.clear()

    companion object {
        fun shortestArc(from: Double, to: Double): Double = ((to - from) % 360.0 + 540.0) % 360.0 - 180.0
        fun norm(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
    }
}
