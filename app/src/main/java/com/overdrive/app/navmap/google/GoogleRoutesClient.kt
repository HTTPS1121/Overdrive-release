package com.overdrive.app.navmap.google

import android.util.Log
import com.overdrive.app.navmap.nav.CongestionLevel
import com.overdrive.app.navmap.nav.CongestionSpan
import com.overdrive.app.navmap.nav.GeoPoint
import com.overdrive.app.navmap.nav.MapNetworking
import com.overdrive.app.navmap.nav.NavRoute
import com.overdrive.app.navmap.nav.PolylineCodec
import com.overdrive.app.navmap.nav.RouteManeuver
import com.overdrive.app.navmap.nav.RouteProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Google Routes API `computeRoutes` mapped onto [NavRoute]. Synchronous (never on the main thread); never throws. */
object GoogleRoutesClient {

    /** FAST = `TRAFFIC_AWARE` (interactive), BEST = `TRAFFIC_AWARE_OPTIMAL` (background refresh). */
    enum class Quality { FAST, BEST }

    enum class Error { NONE, BUDGET, QUOTA, AUTH, HTTP, TIMEOUT, NETWORK, EMPTY }

    @Volatile
    var lastError: Error = Error.NONE

    @Volatile
    var lastHttpCode: Int = 0

    /** Result of one call; concurrent callers read this instead of [lastError]. */
    internal class Outcome(val routes: List<NavRoute>, val error: Error, val httpCode: Int)

    private const val TAG = "GoogleRoutesClient"
    private const val ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"
    private const val CONGESTION_FIELD = "routes.travelAdvisory.speedReadingIntervals"
    private const val FIELD_MASK = "routes.distanceMeters,routes.duration,routes.staticDuration," +
        "routes.polyline.encodedPolyline,$CONGESTION_FIELD," +
        "routes.legs.duration,routes.legs.staticDuration,routes.legs.steps.distanceMeters," +
        "routes.legs.steps.staticDuration,routes.legs.steps.startLocation,routes.legs.steps.navigationInstruction"
    private val FIELD_MASK_PLAIN = FIELD_MASK.replace("$CONGESTION_FIELD,", "")
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val M_PER_DEG = EARTH_RADIUS_M * PI / 180.0
    // Step starts sit within ~1 m of their 1e-5-quantised overview vertex.
    private const val STEP_SNAP_M = 1.5

    @JvmStatic
    fun computeRoutes(
        origin: GeoPoint,
        destination: GeoPoint,
        intermediates: List<GeoPoint>,
        alternatives: Boolean,
        headingDeg: Double?,
        quality: Quality,
        arrivalInstruction: String
    ): List<NavRoute> =
        computeOutcome(origin, destination, intermediates, alternatives, headingDeg, quality, arrivalInstruction).routes

    internal fun computeOutcome(
        origin: GeoPoint,
        destination: GeoPoint,
        intermediates: List<GeoPoint>,
        alternatives: Boolean,
        headingDeg: Double?,
        quality: Quality,
        arrivalInstruction: String
    ): Outcome {
        val out = try {
            request(origin, destination, intermediates, alternatives, headingDeg, quality, arrivalInstruction)
        } catch (t: Throwable) {
            Log.w(TAG, "computeRoutes failed: ${t.javaClass.simpleName}")
            Outcome(emptyList(), classify(t), 0)
        }
        lastError = out.error
        lastHttpCode = out.httpCode
        return out
    }

    private fun request(
        origin: GeoPoint,
        destination: GeoPoint,
        intermediates: List<GeoPoint>,
        alternatives: Boolean,
        headingDeg: Double?,
        quality: Quality,
        arrivalInstruction: String
    ): Outcome {
        if (!finite(origin) || !finite(destination) || intermediates.any { !finite(it) }) {
            return Outcome(emptyList(), Error.EMPTY, 0)
        }
        val congestion = congestionWanted()
        val body = buildBody(
            origin, destination, intermediates, alternatives, headingDeg, quality, congestion,
            language(), region(), MapNetworking.useMiles
        )
        val req = Request.Builder()
            .url(ENDPOINT)
            .header("X-Goog-FieldMask", if (congestion) FIELD_MASK else FIELD_MASK_PLAIN)
            .apply { if (congestion) header(GoogleHttp.HEADER_SKU, GoogleSku.ROUTES_TRAFFIC.wire) }
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        return GoogleHttp.client().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use failure(resp)
            val text = resp.body?.string() ?: return@use Outcome(emptyList(), Error.EMPTY, resp.code)
            val routes = parse(text, arrivalInstruction)
            if (routes.isEmpty()) Log.w(TAG, "computeRoutes -> no usable route")
            Outcome(routes, if (routes.isEmpty()) Error.EMPTY else Error.NONE, resp.code)
        }
    }

    private fun failure(resp: Response): Outcome {
        val code = resp.code
        if (GoogleHttp.isLocalBudgetRejection(resp) && code != 401) {
            Log.i(TAG, "computeRoutes held back by the local budget")
            return Outcome(emptyList(), Error.BUDGET, 0)
        }
        val body = try { resp.body?.string() } catch (_: Throwable) { null }
        val err = when {
            code == 429 -> Error.QUOTA
            code == 401 || code == 403 -> Error.AUTH
            code == 400 && body?.contains("API_KEY_INVALID") == true -> Error.AUTH
            else -> Error.HTTP
        }
        Log.w(TAG, "computeRoutes -> HTTP $code ${err.name}${errorReason(body)}")
        return Outcome(emptyList(), err, code)
    }

    /** Google error status/reason only (never the message or request). */
    private fun errorReason(body: String?): String {
        if (body.isNullOrEmpty()) return ""
        return try {
            val err = JSONObject(body).optJSONObject("error") ?: return ""
            var reason = ""
            val details = err.optJSONArray("details")
            if (details != null) {
                for (i in 0 until details.length()) {
                    reason = details.optJSONObject(i)?.optString("reason", "").orEmpty()
                    if (reason.isNotEmpty()) break
                }
            }
            val parts = listOf(err.optString("status", ""), reason).filter { it.isNotEmpty() }
            if (parts.isEmpty()) "" else parts.joinToString("/", " (", ")")
        } catch (_: Throwable) {
            ""
        }
    }

    private fun classify(t: Throwable): Error =
        if (t is SocketTimeoutException || t.message?.contains("timeout", ignoreCase = true) == true) {
            Error.TIMEOUT
        } else {
            Error.NETWORK
        }

    // TRAFFIC_ON_POLYLINE bills the whole call as Compute Routes Enterprise, metered as ROUTES_TRAFFIC.
    private fun congestionWanted(): Boolean = try {
        GoogleMapsConfig.cached().routeTraffic
    } catch (_: Throwable) {
        false
    }

    private fun language(): String? =
        try { GoogleTileSessions.language().takeIf { it.isNotBlank() } } catch (_: Throwable) { null }

    /** Routes wants a ccTLD (`uk`, not ISO `GB`). */
    private fun region(): String? = try {
        GoogleMapsBudget.appContext()?.let { GoogleTileSessions.region(it) }
            ?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.length == 2 }
            ?.let { if (it == "gb") "uk" else it }
    } catch (_: Throwable) {
        null
    }

    private fun finite(p: GeoPoint): Boolean = p.lat.isFinite() && p.lng.isFinite()

    /** Request body JSON. Pure. */
    internal fun buildBody(
        origin: GeoPoint,
        destination: GeoPoint,
        intermediates: List<GeoPoint>,
        alternatives: Boolean,
        headingDeg: Double?,
        quality: Quality,
        congestion: Boolean,
        language: String?,
        region: String?,
        miles: Boolean
    ): String {
        val heading = headingDeg?.takeIf { it.isFinite() }?.let { (((Math.round(it) % 360) + 360) % 360).toInt() }
        val body = JSONObject()
            .put("origin", waypoint(origin, heading))
            .put("destination", waypoint(destination, null))
        if (intermediates.isNotEmpty()) {
            val arr = JSONArray()
            intermediates.forEach { arr.put(waypoint(it, null)) }
            body.put("intermediates", arr)
        }
        body.put("travelMode", "DRIVE")
        body.put("routingPreference", if (quality == Quality.BEST) "TRAFFIC_AWARE_OPTIMAL" else "TRAFFIC_AWARE")
        body.put("computeAlternativeRoutes", alternatives && intermediates.isEmpty())
        if (congestion) body.put("extraComputations", JSONArray().put("TRAFFIC_ON_POLYLINE"))
        body.put("polylineQuality", "HIGH_QUALITY")
        body.put("polylineEncoding", "ENCODED_POLYLINE")
        if (!language.isNullOrBlank()) body.put("languageCode", language)
        if (!region.isNullOrBlank()) body.put("regionCode", region)
        body.put("units", if (miles) "IMPERIAL" else "METRIC")
        return body.toString()
    }

    private fun waypoint(p: GeoPoint, heading: Int?): JSONObject {
        val location = JSONObject()
            .put("latLng", JSONObject().put("latitude", p.lat).put("longitude", p.lng))
        if (heading != null) location.put("heading", heading)
        return JSONObject().put("location", location)
    }

    /** `computeRoutes` response → routes in response order. Pure; malformed routes are skipped. */
    internal fun parse(json: String, arrivalInstruction: String): List<NavRoute> {
        val root = try { JSONObject(json) } catch (_: Throwable) { return emptyList() }
        val routes = root.optJSONArray("routes") ?: return emptyList()
        val out = ArrayList<NavRoute>(routes.length())
        for (i in 0 until routes.length()) {
            val r = routes.optJSONObject(i) ?: continue
            try {
                parseRoute(r, arrivalInstruction)?.let { out.add(it) }
            } catch (_: Throwable) {
            }
        }
        return out
    }

    private fun parseRoute(r: JSONObject, arrivalInstruction: String): NavRoute? {
        val encoded = r.optJSONObject("polyline")?.optString("encodedPolyline", "").orEmpty()
        val points = PolylineCodec.decode(encoded, 1e5)
        if (points.size < 2) return null
        val lastIndex = points.size - 1
        val maneuvers = ArrayList<RouteManeuver>()
        var cursor = 0
        var firstStep = true
        val legs = r.optJSONArray("legs") ?: JSONArray()
        for (li in 0 until legs.length()) {
            val leg = legs.optJSONObject(li) ?: continue
            val legDuration = seconds(leg, "duration")
            val legStatic = seconds(leg, "staticDuration")
            val trafficScale = if (legDuration > 0.0 && legStatic > 0.0) legDuration / legStatic else 1.0
            val steps = leg.optJSONArray("steps") ?: continue
            for (si in 0 until steps.length()) {
                val step = steps.optJSONObject(si) ?: continue
                val routeFirst = firstStep
                firstStep = false
                val stepStatic = seconds(step, "staticDuration").let { if (it > 0.0) it else 0.0 }
                val time = stepStatic * trafficScale
                val length = step.optDouble("distanceMeters", 0.0).let { if (it.isFinite() && it > 0.0) it else 0.0 }
                latLng(step.optJSONObject("startLocation"))?.let { cursor = nearestVertex(points, it, cursor) }
                val nav = step.optJSONObject("navigationInstruction")
                // Primary line only; later lines are hints ("Toll road") that break the one-line banner.
                val text = nav?.optString("instructions", "").orEmpty()
                    .lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
                if (text.isEmpty() && !routeFirst) {
                    // A silent step extends the previous maneuver's segment.
                    if (maneuvers.isNotEmpty()) {
                        val prev = maneuvers[maneuvers.size - 1]
                        maneuvers[maneuvers.size - 1] = prev.copy(
                            lengthMeters = prev.lengthMeters + length,
                            timeSeconds = prev.timeSeconds + time
                        )
                    }
                    continue
                }
                val at = points[cursor]
                maneuvers.add(
                    RouteManeuver(
                        instruction = text,
                        type = maneuverType(nav?.optString("maneuver", ""), routeFirst),
                        beginShapeIndex = cursor,
                        lengthMeters = length,
                        timeSeconds = time,
                        lat = at.lat,
                        lng = at.lng
                    )
                )
            }
        }
        val end = points[lastIndex]
        maneuvers.add(RouteManeuver(arrivalInstruction, 4, lastIndex, 0.0, 0.0, end.lat, end.lng))

        val distance = r.optDouble("distanceMeters", 0.0)
            .let { if (it.isFinite() && it > 0.0) it else polylineMeters(points) }
        val duration = seconds(r, "duration").let { if (it > 0.0) it else 0.0 }
        val staticDuration = seconds(r, "staticDuration").let { if (it > 0.0) it else Double.NaN }
        return NavRoute(
            points = points,
            maneuvers = maneuvers,
            totalDistanceMeters = distance,
            totalDurationSeconds = duration,
            staticDurationSeconds = staticDuration,
            congestion = congestion(r, lastIndex),
            provider = RouteProvider.GOOGLE
        )
    }

    /** Valhalla maneuver type (§3.1 of the BYOK design) for a Google `Maneuver`. */
    private fun maneuverType(name: String?, routeFirst: Boolean): Int = when (name) {
        "DEPART" -> 1
        "TURN_SLIGHT_LEFT" -> 16
        "TURN_SHARP_LEFT" -> 14
        "UTURN_LEFT" -> 13
        "TURN_LEFT" -> 15
        "TURN_SLIGHT_RIGHT" -> 9
        "TURN_SHARP_RIGHT" -> 11
        "UTURN_RIGHT" -> 12
        "TURN_RIGHT" -> 10
        "STRAIGHT" -> 8
        "RAMP_LEFT" -> 19
        "RAMP_RIGHT" -> 18
        "MERGE" -> 25
        "FORK_LEFT" -> 24
        "FORK_RIGHT" -> 23
        "FERRY", "FERRY_TRAIN" -> 28
        "ROUNDABOUT_LEFT", "ROUNDABOUT_RIGHT" -> 26
        "NAME_CHANGE" -> 7
        null, "", "MANEUVER_UNSPECIFIED" -> if (routeFirst) 1 else 8
        else -> 8
    }

    private fun congestion(r: JSONObject, lastIndex: Int): List<CongestionSpan> {
        val intervals = r.optJSONObject("travelAdvisory")?.optJSONArray("speedReadingIntervals")
            ?: return emptyList()
        val spans = ArrayList<CongestionSpan>()
        for (i in 0 until intervals.length()) {
            val o = intervals.optJSONObject(i) ?: continue
            val level = when (o.optString("speed", "")) {
                "SLOW" -> CongestionLevel.SLOW
                "TRAFFIC_JAM" -> CongestionLevel.JAM
                else -> null
            } ?: continue
            // proto3 JSON omits a zero start index.
            val start = o.optInt("startPolylinePointIndex", 0).coerceIn(0, lastIndex)
            val end = o.optInt("endPolylinePointIndex", 0).coerceIn(0, lastIndex)
            if (end > start) spans.add(CongestionSpan(start, end, level))
        }
        return mergeSpans(spans)
    }

    /** Merges touching/overlapping spans of the same level; result sorted by start. Pure. */
    internal fun mergeSpans(spans: List<CongestionSpan>): List<CongestionSpan> {
        if (spans.size < 2) return spans
        val out = ArrayList<CongestionSpan>(spans.size)
        for (level in CongestionLevel.values()) {
            var cur: CongestionSpan? = null
            for (s in spans.filter { it.level == level }.sortedBy { it.startIndex }) {
                val c = cur
                if (c != null && s.startIndex <= c.endIndex) {
                    if (s.endIndex > c.endIndex) cur = c.copy(endIndex = s.endIndex)
                } else {
                    if (c != null) out.add(c)
                    cur = s
                }
            }
            cur?.let { out.add(it) }
        }
        out.sortWith(compareBy<CongestionSpan>({ it.startIndex }, { it.endIndex }))
        return out
    }

    /** Duration string such as "1234s" / "12.5s" → seconds; NaN when absent or malformed. */
    private fun seconds(o: JSONObject, key: String): Double {
        val v = o.opt(key) ?: return Double.NaN
        val d = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().removeSuffix("s").toDoubleOrNull()
            else -> null
        }
        return if (d != null && d.isFinite()) d else Double.NaN
    }

    private fun latLng(location: JSONObject?): GeoPoint? {
        val ll = location?.optJSONObject("latLng") ?: return null
        val lat = ll.optDouble("latitude", 0.0)
        val lng = ll.optDouble("longitude", 0.0)
        return if (lat.isFinite() && lng.isFinite()) GeoPoint(lat, lng) else null
    }

    /** Nearest vertex at or after [from]; ties and near-exact hits resolve to the earliest. */
    private fun nearestVertex(points: List<GeoPoint>, target: GeoPoint, from: Int): Int {
        val kx = cos(Math.toRadians(target.lat))
        val snap = (STEP_SNAP_M / M_PER_DEG).let { it * it }
        var best = from
        var bestD = Double.MAX_VALUE
        for (i in from until points.size) {
            val p = points[i]
            val dx = (p.lng - target.lng) * kx
            val dy = p.lat - target.lat
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = i
                if (d <= snap) break
            }
        }
        return best
    }

    private fun polylineMeters(points: List<GeoPoint>): Double {
        var sum = 0.0
        for (i in 1 until points.size) sum += haversine(points[i - 1], points[i])
        return sum
    }

    private fun haversine(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(h), sqrt(1 - h))
    }
}
