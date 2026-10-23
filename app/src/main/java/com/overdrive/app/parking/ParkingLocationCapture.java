package com.overdrive.app.parking;

/**
 * Turns the daemon's last GPS reading into the session's location fields.
 *
 * <p>Unlike the recorder's geo tag ({@code GeoSnapshot}, which REJECTS a fix
 * older than five minutes so a parked clip never inherits yesterday's
 * address), a parking session WANTS the last fix even when it is old: after
 * a long underground descent the entrance fix from ten minutes ago is exactly
 * the "where did I park" answer, as long as it is labelled honestly. So this
 * stores the fix together with its age and a quality label, and only refuses a
 * fix that was restored from the on-disk cache (previous drive / boot — its age
 * is unknowable) or is (0,0).
 */
public final class ParkingLocationCapture {

    private ParkingLocationCapture() {}

    /**
     * @param nowElapsedMs monotonic clock now, same basis as {@code fix.fixElapsedMs}
     * @param nowMs        wall clock now; kept for symmetry with the callers, NOT
     *                     used for ageing (see {@link #fixAgeMs})
     */
    public static void apply(ParkingSession s, ParkingEnvironment.GpsFix fix,
                             long nowElapsedMs, long nowMs) {
        if (s == null) return;
        if (fix == null || !fix.hasLocation() || fix.loadedFromCache) {
            s.lat = Double.NaN;
            s.lng = Double.NaN;
            s.accuracyM = 0f;
            s.fixAgeMs = -1L;
            s.fixFromCache = fix != null && fix.loadedFromCache;
            s.gpsQuality = ParkingSession.GPS_UNKNOWN;
            return;
        }
        long age = fixAgeMs(fix, nowElapsedMs, nowMs);
        s.lat = fix.lat;
        s.lng = fix.lng;
        s.accuracyM = fix.accuracyM;
        s.fixAgeMs = age;
        s.fixFromCache = false;
        s.gpsQuality = ParkingSession.qualityFor(true, false, age);
    }

    /**
     * Age of a fix in ms; -1 when it cannot be known.
     *
     * <p>Only the MONOTONIC stamp ({@code fixElapsedMs}, the sidecar's
     * elapsedRealtime at the moment the GNSS fix was produced) is an age. It is
     * immune to a wrong RTC at cold boot and to the sidecar's 1 Hz keep-alive,
     * which re-sends the last fix with a fresh send time for as long as the car
     * sits without GPS.
     *
     * <p>{@code lastUpdateMs} is that SEND time, never a fix time, so it must not
     * be used as a fallback age: a fix without a monotonic stamp is exactly the
     * one the sidecar replayed from its disk cache after it (or the head unit)
     * restarted in a garage, and ageing it by send time read "0 s old" — a
     * yesterday's-parking-spot pin labelled "GPS fresh". Unknown stays unknown;
     * {@link ParkingSession#qualityFor} labels it stale and the caller keeps the
     * coordinates as the honest last-known answer.
     */
    public static long fixAgeMs(ParkingEnvironment.GpsFix fix, long nowElapsedMs, long nowMs) {
        if (fix == null) return -1L;
        if (fix.fixElapsedMs > 0L && fix.fixElapsedMs <= nowElapsedMs) return nowElapsedMs - fix.fixElapsedMs;
        return -1L;
    }

    /** Great-circle distance in metres (haversine). */
    public static double distanceM(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
