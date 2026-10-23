package com.overdrive.app.navmap.google

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.overdrive.app.navmap.nav.CongestionSpan
import com.overdrive.app.navmap.nav.GeoPoint
import com.overdrive.app.navmap.nav.NavRoute
import com.overdrive.app.navmap.nav.NavRouting
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Periodic Google re-query of the remaining trip while navigating: projects live congestion onto the
 * current route and offers clearly faster routes. Public methods and [Host] callbacks run on the main thread.
 */
class TrafficRefreshController(private val host: Host) {

    interface Host {
        fun mainHandler(): Handler
        fun isNavigating(): Boolean
        fun currentRoute(): NavRoute?
        fun currentFix(): GeoPoint?
        fun currentHeading(): Double?
        fun remainingWaypoints(): List<GeoPoint>      // ahead stops then destination
        fun traveledMeters(): Double
        fun onTrafficUpdate(route: NavRoute, spans: List<CongestionSpan>, remainingEtaSeconds: Double)  // main thread
        fun onFasterRoute(candidate: NavRoute, savesSeconds: Double)                                     // main thread
        /** Current ground speed, null when unknown; drives the early refresh on an unexpected slowdown. */
        fun currentSpeedMps(): Double? = null
    }

    companion object {
        /** Global free pricing; India pricing and paid mode have room for fresher traffic. */
        const val INTERVAL_MS = 300_000L
        const val INDIA_INTERVAL_MS = 120_000L
        const val MIN_SAVING_S = 300.0
        const val MIN_SAVING_FRACTION = 0.10

        private const val TAG = "TrafficRefresh"
        private const val OFFER_COOLDOWN_MS = 600_000L
        private const val BUSY_FRACTION = 0.8
        private const val PAID_INTERVAL_MS = 60_000L
        private const val BUSY_MIN_INTERVAL_MS = 300_000L
        // Early refresh: crawling below 40 % of the route's expected speed for 90 s on a road expected
        // to flow at ≥ 8 m/s (≈ 29 km/h), outside any congestion Google already reported. At most one
        // refresh per slowdown, regular ones included; red lights, slow streets and known jams never trigger it.
        private const val WATCH_MS = 5_000L
        private const val SLOW_FRACTION = 0.4
        private const val MIN_EXPECTED_MPS = 8.0
        private const val SLOW_HOLD_MS = 90_000L
        private const val TRIGGER_MIN_GAP_MS = 120_000L
        private const val TRIGGER_SKIP_IF_DUE_MS = 45_000L
        private const val SAMPLE_STEP_M = 50.0
        private const val MATCH_RADIUS_M = 30.0
        private const val MATCH_FRACTION = 0.90
        private const val PROJECT_RADIUS_M = 40.0
        // Grid cell ≥ query radius + half the indexing step, so a 3×3 cell lookup is exhaustive.
        private const val CELL_M = 100.0
        private const val EARTH_RADIUS_M = 6_371_000.0
        private const val M_PER_DEG = EARTH_RADIUS_M * PI / 180.0
    }

    private class Request(
        val generation: Int,
        val handler: Handler,
        val route: NavRoute,
        val points: List<GeoPoint>,
        val heading: Double?,
        val traveledM: Double,
        val atMs: Long
    )

    private class Evaluation(
        val match: NavRoute?,
        val projected: List<CongestionSpan>,
        val fastest: NavRoute?,
        val primary: NavRoute
    )

    private val worker = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) { r ->
        Thread(r, "NavTrafficRefresh").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    private val tick = Runnable { runCycle() }
    private val watch = Runnable { watchSpeed() }
    private var nextTickAtMs = 0L
    private var lastRequestMs = 0L
    private var slowSinceMs = 0L
    private var knownRoute: NavRoute? = null
    private var knownSpans: List<CongestionSpan> = emptyList()
    private var running = false
    private var generation = 0
    private var inFlight = false
    private var baselineEtaS = Double.NaN
    private var baselineAtMs = 0L
    private var lastOfferMs = -1L

    fun start() {
        if (running) return
        running = true
        generation++
        inFlight = false
        baselineEtaS = Double.NaN
        slowSinceMs = 0L
        knownRoute = null
        knownSpans = emptyList()
        schedule(nextInterval())
        try {
            host.mainHandler().removeCallbacks(watch)
            host.mainHandler().postDelayed(watch, WATCH_MS)
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        running = false
        generation++
        inFlight = false
        baselineEtaS = Double.NaN
        lastOfferMs = -1L
        slowSinceMs = 0L
        knownRoute = null
        knownSpans = emptyList()
        cancelTick()
        try { host.mainHandler().removeCallbacks(watch) } catch (_: Throwable) {}
    }

    fun onRouteReplaced() {
        generation++
        inFlight = false
        baselineEtaS = Double.NaN
        slowSinceMs = 0L
        knownRoute = null
        knownSpans = emptyList()
        cancelTick()
        if (running) schedule(nextInterval())
    }

    private fun schedule(delayMs: Long) {
        try {
            val h = host.mainHandler()
            h.removeCallbacks(tick)
            h.postDelayed(tick, delayMs)
            nextTickAtMs = SystemClock.elapsedRealtime() + delayMs
        } catch (_: Throwable) {
        }
    }

    private fun cancelTick() {
        try { host.mainHandler().removeCallbacks(tick) } catch (_: Throwable) {}
    }

    private fun runCycle() {
        if (!running || inFlight) return
        val req = try { snapshot() } catch (_: Throwable) { null }
        if (req == null) {
            schedule(nextInterval())
            return
        }
        inFlight = true
        lastRequestMs = SystemClock.elapsedRealtime()
        try {
            worker.execute { work(req) }
        } catch (_: RejectedExecutionException) {
            inFlight = false
            schedule(nextInterval())
        }
    }

    private fun snapshot(): Request? {
        if (!host.isNavigating()) return null
        val route = host.currentRoute() ?: return null
        val fix = host.currentFix() ?: return null
        if (route.points.size < 2) return null
        val ahead = host.remainingWaypoints()
        if (ahead.isEmpty()) return null
        val points = ArrayList<GeoPoint>(ahead.size + 1)
        points.add(fix)
        points.addAll(ahead)
        return Request(
            generation, host.mainHandler(), route, points, host.currentHeading(),
            host.traveledMeters(), SystemClock.elapsedRealtime()
        )
    }

    /** Worker thread: network + geometry, then hands the result back to the main thread. */
    private fun work(req: Request) {
        var eval: Evaluation? = null
        try {
            if (NavRouting.googleActive()) {
                val candidates = NavRouting.refreshForTraffic(req.points, req.heading)
                if (candidates.isNotEmpty()) eval = evaluate(req.route, req.traveledM, candidates)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "refresh failed: ${t.javaClass.simpleName}")
        }
        val nextMs = nextInterval()
        val result = eval
        try {
            req.handler.post { deliver(req, result, nextMs) }
        } catch (_: Throwable) {
        }
    }

    private fun nextInterval(): Long = try {
        val settings = GoogleMapsConfig.cached()
        when {
            settings.paid -> PAID_INTERVAL_MS
            else -> {
                val base = if (settings.pricing == GooglePricing.INDIA) INDIA_INTERVAL_MS else INTERVAL_MS
                if (nearDailyLimit(settings)) max(base * 2, BUSY_MIN_INTERVAL_MS) else base
            }
        }
    } catch (_: Throwable) {
        INTERVAL_MS
    }

    private fun nearDailyLimit(settings: GoogleMapsSettings): Boolean {
        if (settings.paid) return false
        val sku = settings.routesSku
        return GoogleMapsBudget.usedToday(sku) >= BUSY_FRACTION * GoogleMapsBudget.dailyAllowance(sku)
    }

    /** Main thread, every [WATCH_MS]: refreshes early when the car crawls well below the route's expected speed. */
    private fun watchSpeed() {
        if (!running) return
        try {
            val now = SystemClock.elapsedRealtime()
            val speed = host.currentSpeedMps()
            val route = host.currentRoute()
            val traveledM = host.traveledMeters()
            val expected = expectedSpeedMps(route, traveledM)
            val slow = host.isNavigating() && speed != null && expected != null &&
                expected >= MIN_EXPECTED_MPS && speed < SLOW_FRACTION * expected
            when {
                !slow -> slowSinceMs = 0L
                slowSinceMs == 0L -> slowSinceMs = now
                now - slowSinceMs >= SLOW_HOLD_MS && lastRequestMs < slowSinceMs && !inFlight &&
                    now - lastRequestMs >= TRIGGER_MIN_GAP_MS && nextTickAtMs - now > TRIGGER_SKIP_IF_DUE_MS &&
                    !nearDailyLimit(GoogleMapsConfig.cached()) && route != null && !congestionKnownAt(route, traveledM) -> {
                    // Restarting the hold here latches the slowdown once the request goes out.
                    slowSinceMs = now
                    cancelTick()
                    runCycle()
                }
            }
        } catch (_: Throwable) {
        }
        try { host.mainHandler().postDelayed(watch, WATCH_MS) } catch (_: Throwable) {}
    }

    /** True when the latest traffic data for [route] already reports congestion where the car is. */
    private fun congestionKnownAt(route: NavRoute, traveledM: Double): Boolean {
        val spans = if (knownRoute === route) knownSpans else route.congestion
        if (spans.isEmpty()) return false
        val i = startVertex(route.points, traveledM)
        return spans.any { i >= it.startIndex && i < it.endIndex }
    }

    /** Planned speed of the maneuver segment the car is on (its length over its traffic-aware time). */
    private fun expectedSpeedMps(route: NavRoute?, traveledM: Double): Double? {
        val ms = route?.maneuvers ?: return null
        var start = 0.0
        for (m in ms) {
            val end = start + m.lengthMeters
            if (traveledM < end && m.lengthMeters > 0.0 && m.timeSeconds > 0.0) return m.lengthMeters / m.timeSeconds
            start = end
        }
        return null
    }

    private fun deliver(req: Request, eval: Evaluation?, nextMs: Long) {
        if (req.generation != generation || !running) return
        inFlight = false
        schedule(nextMs)
        if (eval == null) return
        try {
            if (!host.isNavigating() || host.currentRoute() !== req.route) return
            applyResult(req, eval)
        } catch (t: Throwable) {
            Log.w(TAG, "deliver failed: ${t.javaClass.simpleName}")
        }
    }

    private fun applyResult(req: Request, eval: Evaluation) {
        val match = eval.match
        if (match != null) {
            val eta = match.totalDurationSeconds
            baselineEtaS = eta
            baselineAtMs = req.atMs
            knownRoute = req.route
            knownSpans = eval.projected
            host.onTrafficUpdate(req.route, eval.projected, eta)
            val fastest = eval.fastest
            if (fastest != null && fastest !== match) {
                val saves = eta - fastest.totalDurationSeconds
                if (saves >= threshold(eta)) offer(fastest, saves)
            }
            return
        }
        // No candidate follows the current route: compare against the last known remaining ETA.
        if (baselineEtaS.isNaN() || !usable(eval.primary)) return
        val expected = baselineEtaS - (req.atMs - baselineAtMs) / 1000.0
        if (expected <= 0.0) return
        val saves = expected - eval.primary.totalDurationSeconds
        if (saves >= threshold(expected)) offer(eval.primary, saves)
    }

    private fun threshold(durationS: Double): Double = max(MIN_SAVING_S, MIN_SAVING_FRACTION * durationS)

    private fun offer(candidate: NavRoute, savesSeconds: Double) {
        val now = SystemClock.elapsedRealtime()
        if (lastOfferMs >= 0L && now - lastOfferMs < OFFER_COOLDOWN_MS) return
        lastOfferMs = now
        host.onFasterRoute(candidate, savesSeconds)
    }

    private fun usable(r: NavRoute): Boolean =
        r.points.size >= 2 && r.totalDurationSeconds.isFinite() && r.totalDurationSeconds > 0.0

    private fun evaluate(route: NavRoute, traveledM: Double, candidates: List<NavRoute>): Evaluation {
        val ahead = RouteAhead(route.points, startVertex(route.points, traveledM))
        var match: NavRoute? = null
        var matchScore = 0.0
        for (c in candidates) {
            if (!usable(c)) continue
            val score = ahead.coverage(c.points)
            if (score >= MATCH_FRACTION && score > matchScore) {
                match = c
                matchScore = score
            }
        }
        val projected = match?.let { ahead.project(it) } ?: emptyList()
        val fastest = candidates.filter { usable(it) }.minByOrNull { it.totalDurationSeconds }
        return Evaluation(match, projected, fastest, candidates[0])
    }

    /** Vertex at or just behind [traveledM] along [points] (haversine arc, as the guidance engine measures it). */
    private fun startVertex(points: List<GeoPoint>, traveledM: Double): Int {
        if (!(traveledM > 0.0)) return 0
        var acc = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
            acc += EARTH_RADIUS_M * 2 * atan2(sqrt(h), sqrt(1 - h))
            if (acc > traveledM) return i - 1
        }
        return max(0, points.size - 2)
    }

    /** Segments of a route from [from] on, bucketed into ~[CELL_M] cells for nearest-segment queries. */
    private class RouteAhead(private val pts: List<GeoPoint>, from: Int) {
        private val cellLat = CELL_M / M_PER_DEG
        private val cellLng: Double
        private val cells = HashMap<Long, ArrayList<Int>>()
        private var hitSegment = -1
        private var hitT = 0.0

        init {
            var maxAbsLat = 0.0
            for (i in from until pts.size) maxAbsLat = max(maxAbsLat, abs(pts[i].lat))
            cellLng = CELL_M / (M_PER_DEG * max(cos(Math.toRadians(min(maxAbsLat + 0.01, 89.0))), 0.02))
            for (i in from until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                val steps = max(1, ceil(metres(a, b) / (CELL_M / 2)).toInt())
                var lastKey = Long.MIN_VALUE
                for (s in 0..steps) {
                    val f = s.toDouble() / steps
                    val key = cellKey(cellY(a.lat + (b.lat - a.lat) * f), cellX(a.lng + (b.lng - a.lng) * f))
                    if (key != lastKey) {
                        cells.getOrPut(key) { ArrayList(4) }.add(i)
                        lastKey = key
                    }
                }
            }
        }

        private fun cellY(lat: Double): Long = floor(lat / cellLat).toLong()
        private fun cellX(lng: Double): Long = floor(lng / cellLng).toLong()
        private fun cellKey(y: Long, x: Long): Long = (y shl 32) xor (x and 0xffffffffL)

        /** Nearest indexed segment within [radiusM]; on success [hitSegment]/[hitT] hold it (ties → earliest). */
        private fun nearest(lat: Double, lng: Double, radiusM: Double): Boolean {
            val y = cellY(lat)
            val x = cellX(lng)
            val kx = M_PER_DEG * cos(Math.toRadians(lat))
            var bestD = Double.MAX_VALUE
            var bestSeg = -1
            var bestT = 0.0
            for (dy in -1L..1L) {
                for (dx in -1L..1L) {
                    val list = cells[cellKey(y + dy, x + dx)] ?: continue
                    for (seg in list) {
                        val a = pts[seg]
                        val b = pts[seg + 1]
                        val ax = (a.lng - lng) * kx
                        val ay = (a.lat - lat) * M_PER_DEG
                        val vx = (b.lng - lng) * kx - ax
                        val vy = (b.lat - lat) * M_PER_DEG - ay
                        val len2 = vx * vx + vy * vy
                        val t = if (len2 > 0.0) (-(ax * vx + ay * vy) / len2).coerceIn(0.0, 1.0) else 0.0
                        val px = ax + vx * t
                        val py = ay + vy * t
                        val d = sqrt(px * px + py * py)
                        if (d < bestD || (d == bestD && seg < bestSeg)) {
                            bestD = d
                            bestSeg = seg
                            bestT = t
                        }
                    }
                }
            }
            if (bestSeg < 0 || bestD > radiusM) return false
            hitSegment = bestSeg
            hitT = bestT
            return true
        }

        /** Share of [SAMPLE_STEP_M]-spaced samples along [points] lying within [MATCH_RADIUS_M] of this route. */
        fun coverage(points: List<GeoPoint>): Double {
            if (points.size < 2) return 0.0
            var samples = 0
            var hits = 0
            fun probe(lat: Double, lng: Double) {
                samples++
                if (nearest(lat, lng, MATCH_RADIUS_M)) hits++
            }
            probe(points[0].lat, points[0].lng)
            var acc = 0.0
            var next = SAMPLE_STEP_M
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val len = metres(a, b)
                while (len > 0.0 && acc + len >= next) {
                    val f = (next - acc) / len
                    probe(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
                    next += SAMPLE_STEP_M
                }
                acc += len
            }
            val last = points[points.size - 1]
            probe(last.lat, last.lng)
            return hits.toDouble() / samples
        }

        /** [candidate]'s congestion re-indexed onto this route; ends farther than [PROJECT_RADIUS_M] drop the span. */
        fun project(candidate: NavRoute): List<CongestionSpan> {
            val cp = candidate.points
            val out = ArrayList<CongestionSpan>(candidate.congestion.size)
            for (span in candidate.congestion) {
                if (span.startIndex !in cp.indices || span.endIndex !in cp.indices) continue
                val from = positionNear(cp[span.startIndex]) ?: continue
                val to = positionNear(cp[span.endIndex]) ?: continue
                if (to <= from) continue
                var start = nearestVertex(from)
                var end = nearestVertex(to)
                // A span shorter than one segment of this route keeps that whole segment.
                if (end <= start) {
                    start = floor(from).toInt()
                    end = ceil(to).toInt()
                }
                out.add(CongestionSpan(start, end, span.level))
            }
            return GoogleRoutesClient.mergeSpans(out)
        }

        /** Segment index + fraction of the nearest point of this route within [PROJECT_RADIUS_M]. */
        private fun positionNear(p: GeoPoint): Double? =
            if (nearest(p.lat, p.lng, PROJECT_RADIUS_M)) hitSegment + hitT else null

        private fun nearestVertex(position: Double): Int {
            val i = floor(position).toInt()
            return if (position - i <= 0.5) i else i + 1
        }

        private fun metres(a: GeoPoint, b: GeoPoint): Double {
            val dx = (b.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2)) * M_PER_DEG
            val dy = (b.lat - a.lat) * M_PER_DEG
            return sqrt(dx * dx + dy * dy)
        }
    }
}
