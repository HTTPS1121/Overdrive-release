package com.overdrive.app.navmap.nav

/**
 * Plain, immutable data model for the RoadSense navigation map.
 *
 * <p>These are deliberately framework-free (no Android imports) so the
 * routing client, polyline codec and the guidance state machine can be unit
 * tested on the JVM. All coordinates are WGS-84 decimal degrees; all
 * distances are meters; all durations are seconds.
 */

/**
 * A single WGS-84 geographic coordinate.
 *
 * @property lat latitude in decimal degrees (north positive)
 * @property lng longitude in decimal degrees (east positive)
 */
data class GeoPoint(
    val lat: Double,
    val lng: Double
)

/**
 * One turn-by-turn maneuver as returned by Valhalla's `/route` directions.
 *
 * @property instruction human-readable instruction text (e.g. "Turn left onto Main St")
 * @property type the Valhalla maneuver type integer (see Valhalla
 *   `TripDirections::Maneuver::Type`; e.g. 0=none, 1=start, 4=destination,
 *   9/10/11=slight/turn/sharp right, 16/15/14=slight/turn/sharp left). Kept as a raw int so the
 *   Activity/voice layer can map it to icons/phrasing without this layer
 *   committing to an enum.
 * @property beginShapeIndex index into [NavRoute.points] where this maneuver
 *   begins (the route vertex at which the maneuver's instruction applies)
 * @property lengthMeters length of this maneuver's road segment, in meters
 * @property timeSeconds estimated time to traverse this maneuver, in seconds
 * @property lat latitude (decimal degrees) of the maneuver point, taken from
 *   `points[beginShapeIndex]`
 * @property lng longitude (decimal degrees) of the maneuver point
 * @property roundaboutExitCount for a roundabout maneuver, which exit to take
 *   (Valhalla `roundabout_exit_count`); 0 when not a roundabout / absent.
 * @property bearingBefore heading (deg) approaching the maneuver point, or -1.
 * @property bearingAfter heading (deg) leaving the maneuver point, or -1. Together
 *   with [bearingBefore] these let the UI draw a true turn/exit angle.
 * @property verbalPre concise spoken pre-instruction ("Turn left onto Main Street")
 *   — better for TTS than the on-screen [instruction]; empty if absent.
 * @property verbalPost spoken post-instruction ("Continue for 2 miles"); empty if absent.
 * @property streetNames names of the road the maneuver leads onto (Valhalla `street_names`); empty if absent.
 * @property lanes lanes at the maneuver, left to right in the direction of travel; empty when the road has no lane data.
 * @property exitNumber signed exit number ("9"); empty if absent.
 * @property towards signed destinations ("Messedamm"), falling back to the exit branch; empty if absent.
 */
data class RouteManeuver(
    val instruction: String,
    val type: Int,
    val beginShapeIndex: Int,
    val lengthMeters: Double,
    val timeSeconds: Double,
    val lat: Double,
    val lng: Double,
    val roundaboutExitCount: Int = 0,
    val bearingBefore: Int = -1,
    val bearingAfter: Int = -1,
    val verbalPre: String = "",
    val verbalPost: String = "",
    val streetNames: List<String> = emptyList(),
    val lanes: List<RouteLane> = emptyList(),
    val exitNumber: String = "",
    val towards: List<String> = emptyList()
)

/**
 * One lane at a maneuver, as Valhalla bitmasks: 1 none, 2 through, 4 sharp left, 8 left, 16 slight left,
 * 32 slight right, 64 right, 128 sharp right, 256 reverse, 512 merge to left, 1024 merge to right.
 *
 * @property directions every arrow painted on the lane
 * @property active the arrow the route takes from its preferred lane; 0 when this lane is not preferred
 * @property valid arrows that also make the maneuver from this lane; 0 when none
 */
data class RouteLane(
    val directions: Int,
    val active: Int = 0,
    val valid: Int = 0
) {
    /** Lit when the route can use this lane. */
    val usable: Boolean get() = (active or valid) != 0
}

/**
 * A fully decoded route: the polyline geometry plus the maneuver list and
 * trip totals.
 *
 * @property points the decoded full route polyline, in order from origin to
 *   destination. [RouteManeuver.beginShapeIndex] indexes into this list.
 * @property maneuvers ordered turn-by-turn maneuvers
 * @property totalDistanceMeters total trip distance in meters
 * @property totalDurationSeconds total trip duration in seconds (traffic-aware for Google routes)
 * @property staticDurationSeconds duration without traffic, NaN when the provider has no traffic model
 * @property congestion slow/jammed stretches of [points]; empty when unknown or free-flowing
 * @property provider which routing backend produced this route
 */
data class NavRoute(
    val points: List<GeoPoint>,
    val maneuvers: List<RouteManeuver>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val staticDurationSeconds: Double = Double.NaN,
    val congestion: List<CongestionSpan> = emptyList(),
    val provider: RouteProvider = RouteProvider.VALHALLA
) {
    /** Extra time caused by traffic, 0 when unknown. */
    val trafficDelaySeconds: Double
        get() = if (staticDurationSeconds.isNaN()) 0.0
        else (totalDurationSeconds - staticDurationSeconds).coerceAtLeast(0.0)
}

enum class RouteProvider { VALHALLA, GOOGLE }

enum class CongestionLevel { SLOW, JAM }

/**
 * A congested stretch of a route: vertices [startIndex]..[endIndex] (inclusive)
 * of [NavRoute.points]. Free-flowing stretches are not listed.
 */
data class CongestionSpan(
    val startIndex: Int,
    val endIndex: Int,
    val level: CongestionLevel
)

/**
 * A single forward-geocoding (search) result.
 *
 * @property label readable display string (e.g. "Eiffel Tower, Paris, France")
 * @property lat latitude in decimal degrees; NaN for a not-yet-resolved place prediction
 * @property lng longitude in decimal degrees; NaN for a not-yet-resolved place prediction
 * @property placeId provider place id when the result came from Google Places
 * @property secondary second display line (address/locality), null when absent
 * @property source "google" for Google-derived results, null otherwise
 */
data class SearchResult(
    val label: String,
    val lat: Double,
    val lng: Double,
    val placeId: String? = null,
    val secondary: String? = null,
    val source: String? = null
) {
    /** A place prediction whose coordinates still need a details lookup. */
    val needsResolve: Boolean
        get() = placeId != null && (lat.isNaN() || lng.isNaN())
}
