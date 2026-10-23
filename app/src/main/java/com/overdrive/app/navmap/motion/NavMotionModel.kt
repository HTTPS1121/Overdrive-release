package com.overdrive.app.navmap.motion

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The pose the map shows, built from the dead-reckoned estimate. Each new GPS fix moves the estimate
 * in a step; the step is absorbed by a critically damped offset that keeps the shown position and its
 * velocity continuous, so corrections ease in instead of jumping. On the route the car advances by
 * along-route distance and steers by a chord of the route, so heading turns smoothly through bends.
 */
class NavMotionModel {

    /** Route position from the guidance engine's snapper: along-route metres and lateral offset. */
    class Snap(var alongM: Double = 0.0, var offsetM: Double = 0.0)

    // Output, valid after update() returns true.
    var lat = 0.0
        private set
    var lng = 0.0
        private set
    var puckBearing = 0.0
        private set
    var cameraBearing = 0.0
        private set
    var speedMps = 0.0
        private set
    /** Shown along-route distance while on the route, NaN otherwise. */
    var routeS = Double.NaN
        private set
    var onRoute = false
        private set

    private var track: RouteTrack? = null
    private var lastNowMs = 0L
    private var lastTruth = Long.MIN_VALUE
    private var seeded = false

    // On-route state.
    private var anchorS = 0.0
    private var anchorLat = 0.0
    private var anchorLng = 0.0
    private var baseS = 0.0
    private var baseV = 0.0
    private val along = DecaySpring(ALONG_OMEGA)
    private val lateral = DecaySpring(LATERAL_OMEGA)

    // Off-route state: shown = estimate + offset (metres east/north).
    private val offE = DecaySpring(FREE_OMEGA)
    private val offN = DecaySpring(FREE_OMEGA)

    // Last shown position and velocity (metres/s east, north) for continuity across steps and modes.
    private var shownVe = 0.0
    private var shownVn = 0.0

    private val puck = AngleSpring(PUCK_OMEGA)
    private val camera = AngleSpring(CAMERA_OMEGA)
    private val tmp = DoubleArray(2)
    private val proj = DoubleArray(2)

    fun setTrack(t: RouteTrack?) {
        if (t === track) return
        track = t
        // Leave route mode; the next on-route frame re-enters from the shown position.
        if (onRoute) leaveRoute()
    }

    fun reset() {
        seeded = false
        onRoute = false
        routeS = Double.NaN
        lastTruth = Long.MIN_VALUE
        along.clear(); lateral.clear(); offE.clear(); offN.clear()
        puck.clear(); camera.clear()
        shownVe = 0.0; shownVn = 0.0
    }

    /** Seeds both headings, e.g. down the first route segment before the car moves. */
    fun seedBearing(deg: Double) {
        puck.snap(deg)
        camera.snap(deg)
    }

    val settled: Boolean
        get() = seeded && speedMps < STOPPED_MPS &&
            along.settled(0.02) && lateral.settled(0.02) && offE.settled(0.02) && offN.settled(0.02) &&
            abs(puck.rateDegPerS) < 0.5 && abs(camera.rateDegPerS) < 0.5

    /**
     * Advances to [nowMs] from the estimate (rawLat, rawLng, rawSpeed, rawBearing). [truthStamp] changes
     * whenever the estimator accepted a new fix; [snap] maps a position to the route (null = off route).
     */
    fun update(
        nowMs: Long,
        rawLat: Double, rawLng: Double, rawSpeed: Double, rawBearing: Double,
        truthStamp: Long,
        snap: ((Double, Double, Snap) -> Boolean)?
    ) {
        val dt = if (seeded) ((nowMs - lastNowMs).coerceAtLeast(0L)) / 1000.0 else 0.0
        lastNowMs = nowMs
        val newTruth = truthStamp != lastTruth
        lastTruth = truthStamp
        speedMps = if (!seeded) rawSpeed else speedMps + (rawSpeed - speedMps) * (1.0 - exp(-dt / SPEED_TAU_S))
        val moving = rawSpeed >= MOVING_MPS

        val prevLat = lat
        val prevLng = lng
        // Predicted shown position at now with no new information: last shown + last velocity.
        RouteTrack.offset(prevLat, prevLng, shownVe * dt, shownVn * dt, tmp)
        val predLat = tmp[0]
        val predLng = tmp[1]

        if (!seeded || dt > RESEED_GAP_S) {
            hardSeed(rawLat, rawLng, rawSpeed, rawBearing)
            return
        }

        along.step(dt); lateral.step(dt); offE.step(dt); offN.step(dt)

        val t = track
        var routeNow = onRoute
        if (t != null && snap != null && newTruth) {
            val s = Snap()
            val ok = snap(rawLat, rawLng, s)
            val off = abs(s.offsetM)
            routeNow = ok && if (onRoute) off <= SNAP_EXIT_M else off <= SNAP_ENTER_M
            if (routeNow) {
                if (!onRoute) enterRoute(t, s.alongM, rawLat, rawLng, rawSpeed, predLat, predLng)
                else reanchor(s.alongM, rawLat, rawLng, rawSpeed, dt)
            }
        }
        if (t == null) routeNow = false
        if (onRoute && !routeNow) leaveRoute()

        if (onRoute && t != null) {
            val prevBase = baseS
            baseS = anchorS + RouteTrack.haversine(anchorLat, anchorLng, rawLat, rawLng)
            if (dt > 0.0) baseV = (baseS - prevBase) / dt
            var s = baseS + along.x
            val lastShown = routeS
            if (!lastShown.isNaN() && s < lastShown) {
                // Never roll backwards along the route: hold and let the offset catch up.
                along.x = lastShown - baseS
                if (along.v < -baseV) along.v = -baseV
                s = lastShown
            }
            s = s.coerceIn(0.0, t.length)
            routeS = s
            t.pointAt(s, tmp)
            var outLat = tmp[0]
            var outLng = tmp[1]
            val tangent = t.chordBearing(s, PUCK_CHORD_BACK_M, PUCK_CHORD_FWD_M)
            if (lateral.x != 0.0) {
                val b = Math.toRadians(tangent)
                RouteTrack.offset(outLat, outLng, lateral.x * cos(b), -lateral.x * sin(b), tmp)
                outLat = tmp[0]; outLng = tmp[1]
            }
            setShown(outLat, outLng, prevLat, prevLng, dt)
            if (moving) {
                puck.step(tangent, dt)
                val lead = (CAMERA_LEAD_BASE_M + CAMERA_LEAD_S * speedMps).coerceIn(CAMERA_LEAD_MIN_M, CAMERA_LEAD_MAX_M)
                camera.step(t.chordBearing(s, 0.0, lead), dt)
            } else {
                puck.hold(dt); camera.hold(dt)
            }
        } else {
            if (newTruth) {
                // Keep the shown point and velocity where they were; the offset decays toward the new estimate.
                setOffsetFrom(predLat, predLng, rawLat, rawLng)
                val b = Math.toRadians(rawBearing)
                val rawVe = rawSpeed * sin(b)
                val rawVn = rawSpeed * cos(b)
                offE.v = shownVe - rawVe
                offN.v = shownVn - rawVn
            }
            RouteTrack.offset(rawLat, rawLng, offE.x, offN.x, tmp)
            setShown(tmp[0], tmp[1], prevLat, prevLng, dt)
            routeS = Double.NaN
            if (moving) {
                puck.step(rawBearing, dt)
                camera.step(puck.degrees, dt)
            } else {
                puck.hold(dt); camera.hold(dt)
            }
        }
        puckBearing = puck.degrees
        cameraBearing = camera.degrees
    }

    private fun hardSeed(rawLat: Double, rawLng: Double, rawSpeed: Double, rawBearing: Double) {
        seeded = true
        onRoute = false
        routeS = Double.NaN
        along.clear(); lateral.clear(); offE.clear(); offN.clear()
        lat = rawLat; lng = rawLng
        shownVe = 0.0; shownVn = 0.0
        speedMps = rawSpeed
        if (!puck.isSet || rawSpeed >= MOVING_MPS) puck.snap(rawBearing)
        if (!camera.isSet || rawSpeed >= MOVING_MPS) camera.snap(puck.degrees)
        puckBearing = puck.degrees
        cameraBearing = camera.degrees
        // Force the next frame to re-test the route with a fresh snap.
        lastTruth = Long.MIN_VALUE
    }

    private fun enterRoute(
        t: RouteTrack, s: Double, rawLat: Double, rawLng: Double, rawSpeed: Double,
        predLat: Double, predLng: Double
    ) {
        onRoute = true
        anchorS = s; anchorLat = rawLat; anchorLng = rawLng
        baseS = s; baseV = rawSpeed
        // Express the predicted shown point in route terms so entering the route doesn't jump.
        t.project(predLat, predLng, s, ENTRY_WINDOW_M, proj)
        along.x = proj[0] - s
        lateral.x = proj[1]
        val tangent = Math.toRadians(t.chordBearing(proj[0], PUCK_CHORD_BACK_M, PUCK_CHORD_FWD_M))
        val shownAlongV = shownVe * sin(tangent) + shownVn * cos(tangent)
        val shownLateralV = shownVe * cos(tangent) - shownVn * sin(tangent)
        along.v = shownAlongV - rawSpeed
        lateral.v = shownLateralV
        offE.clear(); offN.clear()
        routeS = Double.NaN
        if (abs(along.x) > SNAP_LIMIT_M || abs(lateral.x) > SNAP_LIMIT_M) {
            along.clear(); lateral.clear()
        }
    }

    private fun reanchor(s: Double, rawLat: Double, rawLng: Double, rawSpeed: Double, dt: Double) {
        // Shown along-route distance and velocity at this frame had the fix not arrived, carried into the new anchor.
        val shownS = baseS + baseV * dt + along.x
        val shownV = baseV + along.v
        anchorS = s; anchorLat = rawLat; anchorLng = rawLng
        baseS = s
        along.x = shownS - s
        along.v = shownV - rawSpeed
        baseV = rawSpeed
        if (abs(along.x) > SNAP_LIMIT_M) along.clear()
    }

    private fun leaveRoute() {
        onRoute = false
        routeS = Double.NaN
        along.clear(); lateral.clear()
        // The off-route branch rebuilds its offset from the shown point on the next truth; until then
        // hold the shown point by expressing it against the current estimate on the next frame.
        lastTruth = Long.MIN_VALUE
    }

    private fun setOffsetFrom(shownLat: Double, shownLng: Double, rawLat: Double, rawLng: Double) {
        val kx = M_PER_DEG * cos(Math.toRadians(rawLat))
        offE.x = (shownLng - rawLng) * kx
        offN.x = (shownLat - rawLat) * M_PER_DEG
        if (sqrt(offE.x * offE.x + offN.x * offN.x) > SNAP_LIMIT_M) {
            offE.clear(); offN.clear()
        }
    }

    private fun setShown(newLat: Double, newLng: Double, prevLat: Double, prevLng: Double, dt: Double) {
        if (dt > 0.0) {
            val kx = M_PER_DEG * cos(Math.toRadians(newLat))
            shownVe = (newLng - prevLng) * kx / dt
            shownVn = (newLat - prevLat) * M_PER_DEG / dt
        }
        lat = newLat
        lng = newLng
    }

    companion object {
        private const val M_PER_DEG = 6_371_000.0 * Math.PI / 180.0
        // Correction ease: ω 3.2/s settles a fix's error in ~1 s with no overshoot.
        private const val ALONG_OMEGA = 3.2
        private const val LATERAL_OMEGA = 3.0
        private const val FREE_OMEGA = 3.0
        private const val PUCK_OMEGA = 12.0
        private const val CAMERA_OMEGA = 2.6
        private const val SPEED_TAU_S = 0.6
        private const val MOVING_MPS = 1.5
        private const val STOPPED_MPS = 0.3
        private const val RESEED_GAP_S = 2.5
        private const val SNAP_ENTER_M = 32.0
        private const val SNAP_EXIT_M = 48.0
        private const val SNAP_LIMIT_M = 80.0
        private const val ENTRY_WINDOW_M = 120.0
        private const val PUCK_CHORD_BACK_M = 4.0
        private const val PUCK_CHORD_FWD_M = 9.0
        // Camera aims at a point 22–140 m ahead (≈2.2 s of travel), so it starts turning before a bend.
        private const val CAMERA_LEAD_BASE_M = 12.0
        private const val CAMERA_LEAD_S = 2.2
        private const val CAMERA_LEAD_MIN_M = 22.0
        private const val CAMERA_LEAD_MAX_M = 140.0
    }
}
