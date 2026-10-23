package com.overdrive.app.voice.daemon;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Voice navigation in the daemon: a saved OverDrive place by name (Home, Work, …) or a free-text
 * destination, handed to the first installed navigation app. Google Maps and Waze take the text
 * as it is; Telenav needs coordinates, so the destination is geocoded first; anything else gets a
 * plain {@code geo:} intent.
 */
final class VoiceNavigator {

    private static final DaemonLogger logger = DaemonLogger.getInstance("VoiceNav");

    static final String GOOGLE_MAPS = "com.google.android.apps.maps";
    static final String WAZE = "com.waze";
    static final String TELENAV = "com.telenav.app.arp";
    static final int MAX_QUERY_CHARS = 200;

    /** Outcome for the executor: {@code code} is a VoiceIntentExecutor code. */
    static final class Result {
        final String code;
        final String message;

        Result(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    /** A resolved place: coordinates when known (saved place or geocoder), else only text. */
    static final class Place {
        final String label;
        final double lat;
        final double lng;

        Place(String label, double lat, double lng) {
            this.label = label;
            this.lat = lat;
            this.lng = lng;
        }

        boolean hasCoordinates() {
            return !Double.isNaN(lat) && !Double.isNaN(lng);
        }
    }

    private VoiceNavigator() {
    }

    // ---- Pure (JVM-testable) ----

    static String clean(String query) {
        String q = query == null ? "" : query.trim().replaceAll("\\s+", " ");
        return q.length() > MAX_QUERY_CHARS ? q.substring(0, MAX_QUERY_CHARS) : q;
    }

    /** URL-encoded with %20 for spaces; the result never contains a quote, so it is safe in '…'. */
    static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    /** The VIEW intent URI each app is given; {@code pkg} null is the generic geo: handler. */
    static String uriFor(String pkg, Place place) {
        String coords = place.hasCoordinates()
                ? String.format(Locale.US, "%.6f,%.6f", place.lat, place.lng) : null;
        if (GOOGLE_MAPS.equals(pkg)) {
            return "google.navigation:q=" + (coords != null ? coords : encode(place.label));
        }
        if (WAZE.equals(pkg)) {
            return coords != null
                    ? "https://waze.com/ul?ll=" + coords + "&navigate=yes"
                    : "https://waze.com/ul?q=" + encode(place.label) + "&navigate=yes";
        }
        return coords != null
                ? "geo:" + coords + "?q=" + coords + "(" + encode(place.label) + ")"
                : "geo:0,0?q=" + encode(place.label);
    }

    static String appName(String pkg) {
        if (GOOGLE_MAPS.equals(pkg)) return "Google Maps";
        if (WAZE.equals(pkg)) return "Waze";
        if (TELENAV.equals(pkg)) return "Telenav";
        return "the map app";
    }

    // ---- Daemon side ----

    static Result navigate(String query) {
        String q = clean(query);
        if (q.isEmpty()) return new Result(VoiceIntentExecutor.UNSUPPORTED, "Where would you like to go?");
        Place place = savedPlace(q);
        if (place == null) place = new Place(q, Double.NaN, Double.NaN);
        String pkg = installed(GOOGLE_MAPS) ? GOOGLE_MAPS : installed(WAZE) ? WAZE
                : installed(TELENAV) ? TELENAV : null;
        if (TELENAV.equals(pkg)) return viaTelenav(place);
        String uri = uriFor(pkg, place);
        String cmd = "am start -a android.intent.action.VIEW -f 0x10000000 -d '" + uri + "'"
                + (pkg != null ? " -p " + pkg : "");
        String output = runShell(cmd);
        // am exits 0 even when nothing resolves the intent; its output says so.
        boolean ok = output != null && !output.contains("Error");
        logger.info("Voice navigation via " + (pkg == null ? "geo" : pkg) + ": ok=" + ok);
        if (!ok) {
            return new Result(VoiceIntentExecutor.FAILED, pkg == null
                    ? "No navigation app is installed." : appName(pkg) + " did not start.");
        }
        return new Result(VoiceIntentExecutor.DONE, "Navigating to " + place.label + " in " + appName(pkg) + ".");
    }

    /** A safe-location zone whose name is the whole query ("home", "Work", …), else null. */
    private static Place savedPlace(String query) {
        try {
            for (com.overdrive.app.surveillance.SafeLocation zone
                    : com.overdrive.app.surveillance.SafeLocationManager.getInstance().getZones()) {
                String name = zone.getName();
                if (name != null && name.trim().equalsIgnoreCase(query)) {
                    return new Place(name.trim(), zone.getLatitude(), zone.getLongitude());
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Telenav routes only to coordinates: geocode near the car, then the existing navigate path. */
    private static Result viaTelenav(Place place) {
        Place target = place;
        if (!target.hasCoordinates()) {
            target = geocode(place.label);
            if (target == null) {
                return new Result(VoiceIntentExecutor.FAILED, "I could not find " + place.label + ".");
            }
        }
        try {
            JSONObject body = new JSONObject()
                    .put("name", target.label)
                    .put("lat", target.lat)
                    .put("lng", target.lng)
                    .put("formattedAddress", target.label);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            com.overdrive.app.server.TelenavDebugApiHandler.handle(
                    "POST", "/api/telenav/navigate", body.toString(), out);
            JSONObject reply = jsonBody(out.toString("UTF-8"));
            boolean ok = reply != null && reply.optBoolean("success", false);
            logger.info("Voice navigation via Telenav: ok=" + ok
                    + (reply != null && reply.optBoolean("queued", false) ? " (queued, car off)" : ""));
            if (!ok) return new Result(VoiceIntentExecutor.FAILED, "Telenav did not start the route.");
            if (reply.optBoolean("queued", false)) {
                return new Result(VoiceIntentExecutor.DONE,
                        "The route to " + target.label + " will be offered when the car starts.");
            }
            return new Result(VoiceIntentExecutor.DONE, "Navigating to " + target.label + " in Telenav.");
        } catch (Throwable t) {
            logger.warn("Voice navigation via Telenav failed: " + t.getMessage());
            return new Result(VoiceIntentExecutor.FAILED, "Telenav did not start the route.");
        }
    }

    private static Place geocode(String query) {
        try {
            double lat = Double.NaN;
            double lng = Double.NaN;
            com.overdrive.app.monitor.GpsMonitor.GpsFixSnapshot fix =
                    com.overdrive.app.monitor.GpsMonitor.getInstance().getFixSnapshot();
            if (fix != null && fix.hasLocation()) {
                lat = fix.latitude;
                lng = fix.longitude;
            }
            java.util.List<com.overdrive.app.navmap.nav.SearchResult> hits =
                    com.overdrive.app.navmap.nav.ForwardGeocoder.INSTANCE.search(query, 1,
                            Double.isNaN(lat) ? null : lat, Double.isNaN(lng) ? null : lng);
            if (hits == null || hits.isEmpty()) return null;
            com.overdrive.app.navmap.nav.SearchResult hit = hits.get(0);
            return new Place(hit.getLabel(), hit.getLat(), hit.getLng());
        } catch (Throwable t) {
            logger.warn("Voice navigation geocode failed: " + t.getMessage());
            return null;
        }
    }

    static boolean installed(String pkg) {
        try {
            android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            if (ctx == null) return false;
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static JSONObject jsonBody(String http) {
        if (http == null) return null;
        int split = http.indexOf("\r\n\r\n");
        String body = split >= 0 ? http.substring(split + 4) : http;
        try {
            return new JSONObject(body.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code sh -c} as the daemon (uid 2000, which may start activities); output, or null on failure. */
    static String runShell(String cmd) {
        String output = com.overdrive.app.server.VehicleControlApiHandler.runShellCapture(cmd, 5000, 16 * 1024);
        return output;
    }
}
