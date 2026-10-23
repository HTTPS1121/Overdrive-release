package com.overdrive.app.safekeep;

import com.overdrive.app.config.UnifiedConfigManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Immutable snapshot of the {@code safeKeep} config section, defaults applied inline. */
public final class SafeKeepConfig {

    public static final String SECTION = "safeKeep";

    public enum Lane {
        EVIDENCE("evidence"), ARCHIVE("archive"), TRIPS("trips"),
        TRIP_EXPORTS("tripExports"), SETTINGS("settings");

        public final String id;

        Lane(String id) {
            this.id = id;
        }

        public static Lane parse(String s) {
            for (Lane l : values()) if (l.id.equalsIgnoreCase(s)) return l;
            return null;
        }

        public boolean media() {
            return this == EVIDENCE || this == ARCHIVE;
        }
    }

    public static final class Destination {
        public final String id;
        public final String type;
        public final String name;
        public final boolean enabled;
        public final boolean humanLayout;
        public final String proxyMode;
        public final boolean encrypt;
        public final List<String> recipients;
        public final int eventsDays;
        public final int archiveDays;
        public final int settingsKeep;
        public final int maxQuotaPct;
        public final JSONObject params;

        Destination(JSONObject j) {
            id = j.optString("id", "");
            type = j.optString("type", "").toLowerCase(Locale.ROOT);
            name = j.optString("name", type);
            enabled = j.optBoolean("enabled", true);
            humanLayout = !"machine".equals(j.optString("layout", "s3".equals(type) ? "machine" : "human"));
            proxyMode = j.optString("proxyMode", "auto");
            JSONObject enc = j.optJSONObject("encryption");
            List<String> r = new ArrayList<>();
            if (enc != null && "age".equals(enc.optString("mode", "off"))) {
                JSONArray a = enc.optJSONArray("recipients");
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    String s = a.optString(i, "").trim();
                    if (!s.isEmpty()) r.add(s);
                }
            }
            recipients = Collections.unmodifiableList(r);
            encrypt = !recipients.isEmpty();
            JSONObject ret = j.optJSONObject("retention");
            if (ret == null) ret = new JSONObject();
            eventsDays = Math.max(0, ret.optInt("eventsDays", 90));
            archiveDays = Math.max(0, ret.optInt("archiveDays", 14));
            settingsKeep = Math.max(1, ret.optInt("settingsKeep", 10));
            maxQuotaPct = Math.max(50, Math.min(99, ret.optInt("maxQuotaPct", 85)));
            JSONObject p = j.optJSONObject("params");
            params = p == null ? new JSONObject() : p;
        }
    }

    public static final class Route {
        public final Lane lane;
        public final List<String> destIds;
        public final JSONObject rule;
        public final int backfillDays;
        public final boolean includeSentry;
        public final boolean includeProximity;
        public final boolean includeReplay;
        public final boolean includeOemDashcam;
        /** -1 inherit from destination, 0 force off, 1 force on (only when recipients exist). */
        public final int encrypt;

        Route(JSONObject j) {
            lane = Lane.parse(j.optString("lane", ""));
            List<String> d = new ArrayList<>();
            JSONArray a = j.optJSONArray("dest");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "");
                if (!s.isEmpty()) d.add(s);
            }
            destIds = Collections.unmodifiableList(d);
            JSONObject r = j.optJSONObject("rule");
            rule = r == null ? new JSONObject() : r;
            backfillDays = Math.max(0, Math.min(365, j.optInt("backfillDays",
                    lane == Lane.EVIDENCE ? 7 : 0)));
            includeSentry = j.optBoolean("includeSentry", true);
            includeProximity = j.optBoolean("includeProximity", true);
            includeReplay = j.optBoolean("includeReplay", true);
            includeOemDashcam = j.optBoolean("includeOemDashcam", true);
            encrypt = j.has("encrypt") ? (j.optBoolean("encrypt") ? 1 : 0) : -1;
        }
    }

    public static final class Limits {
        public final int mobileCapMb;
        public final int mobileResetDay;
        public final double drivingMaxMbps;
        public final double mobileMaxMbps;
        public final double wifiMaxMbps;
        public final int parkedMaxMinutes;
        public final int min12vDeci;
        public final boolean pauseForLiveView;
        public final List<String> homeSsids;
        public final int maxConcurrent;
        public final double pinMaxGb;
        public final double lockMaxGb;

        Limits(JSONObject j) {
            if (j == null) j = new JSONObject();
            mobileCapMb = Math.max(0, j.optInt("mobileCapMb", 500));
            mobileResetDay = Math.max(1, Math.min(28, j.optInt("mobileResetDay", 1)));
            drivingMaxMbps = Math.max(0, j.optDouble("drivingMaxMbps", 2));
            mobileMaxMbps = Math.max(0, j.optDouble("mobileMaxMbps", 1));
            wifiMaxMbps = Math.max(0, j.optDouble("wifiMaxMbps", 0));
            parkedMaxMinutes = Math.max(0, j.optInt("parkedMaxMinutes", 60));
            min12vDeci = Math.max(118, Math.min(135, j.optInt("min12vDeci", 122)));
            pauseForLiveView = j.optBoolean("pauseForLiveView", true);
            List<String> ssids = new ArrayList<>();
            JSONArray a = j.optJSONArray("homeSsids");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "").trim();
                if (!s.isEmpty()) ssids.add(s);
            }
            homeSsids = Collections.unmodifiableList(ssids);
            maxConcurrent = Math.max(1, Math.min(2, j.optInt("maxConcurrent", 2)));
            pinMaxGb = Math.max(0.5, Math.min(64, j.optDouble("pinMaxGb", 4)));
            lockMaxGb = Math.max(0.5, Math.min(128, j.optDouble("lockMaxGb", 8)));
        }
    }

    public static final class Notify {
        public final boolean needsAuth;
        public final boolean quota;
        public final boolean mobileCap;
        public final boolean evidenceSaved;

        Notify(JSONObject j) {
            if (j == null) j = new JSONObject();
            needsAuth = j.optBoolean("needsAuth", true);
            quota = j.optBoolean("quota", true);
            mobileCap = j.optBoolean("mobileCap", true);
            evidenceSaved = j.optBoolean("evidenceSaved", false);
        }
    }

    public static final class Webhook {
        public final String url;
        public final boolean tripFinished;
        public final boolean evidenceSaved;

        Webhook(JSONObject j) {
            url = j.optString("url", "").trim();
            JSONArray ev = j.optJSONArray("events");
            boolean t = false, e = false;
            if (ev != null) for (int i = 0; i < ev.length(); i++) {
                String s = ev.optString(i, "");
                if ("trip.finished".equals(s)) t = true;
                if ("evidence.saved".equals(s)) e = true;
            }
            tripFinished = t;
            evidenceSaved = e;
        }
    }

    public final boolean enabled;
    public final String vehicleName;
    public final String vehicleId;
    public final List<Destination> destinations;
    public final List<Route> routes;
    public final Limits limits;
    public final Notify notify;
    public final List<Webhook> webhooks;
    public final JSONObject raw;

    private SafeKeepConfig(JSONObject s) {
        raw = s;
        enabled = s.optBoolean("enabled", false);
        vehicleName = sanitizeName(s.optString("vehicleName", "My Car"));
        vehicleId = s.optString("vehicleId", "");
        List<Destination> d = new ArrayList<>();
        JSONArray da = s.optJSONArray("destinations");
        if (da != null) for (int i = 0; i < da.length(); i++) {
            JSONObject o = da.optJSONObject(i);
            if (o != null && !o.optString("id", "").isEmpty()) d.add(new Destination(o));
        }
        destinations = Collections.unmodifiableList(d);
        List<Route> r = new ArrayList<>();
        JSONArray ra = s.optJSONArray("routes");
        if (ra != null) for (int i = 0; i < ra.length(); i++) {
            JSONObject o = ra.optJSONObject(i);
            if (o == null) continue;
            Route route = new Route(o);
            if (route.lane != null) r.add(route);
        }
        routes = Collections.unmodifiableList(r);
        limits = new Limits(s.optJSONObject("limits"));
        notify = new Notify(s.optJSONObject("notify"));
        List<Webhook> w = new ArrayList<>();
        JSONArray wa = s.optJSONArray("webhooks");
        if (wa != null) for (int i = 0; i < wa.length(); i++) {
            JSONObject o = wa.optJSONObject(i);
            if (o != null) {
                Webhook hook = new Webhook(o);
                if (hook.url.startsWith("https://") || hook.url.startsWith("http://")) w.add(hook);
            }
        }
        webhooks = Collections.unmodifiableList(w);
    }

    public static SafeKeepConfig load() {
        return from(UnifiedConfigManager.loadConfig());
    }

    public static SafeKeepConfig from(JSONObject root) {
        JSONObject s = root == null ? null : root.optJSONObject(SECTION);
        return new SafeKeepConfig(s == null ? new JSONObject() : s);
    }

    /** Cheap master-toggle read used at boot and by the config listener. */
    public static boolean isEnabledIn(JSONObject root) {
        JSONObject s = root == null ? null : root.optJSONObject(SECTION);
        return s != null && s.optBoolean("enabled", false);
    }

    public Destination destination(String id) {
        for (Destination d : destinations) if (d.id.equals(id)) return d;
        return null;
    }

    public List<Route> routesFor(Lane lane) {
        List<Route> out = new ArrayList<>();
        for (Route r : routes) if (r.lane == lane) out.add(r);
        return out;
    }

    public Route route(Lane lane, String destId) {
        for (Route r : routes) if (r.lane == lane && r.destIds.contains(destId)) return r;
        return null;
    }

    /** Folder-safe name: OneDrive/SMB/Windows reserved characters removed, no trailing dot/space. */
    public static String sanitizeName(String s) {
        if (s == null) return "";
        String out = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-").trim();
        while (out.endsWith(".") || out.endsWith(" ")) out = out.substring(0, out.length() - 1);
        if (out.length() > 64) out = out.substring(0, 64).trim();
        return out.isEmpty() ? "My Car" : out;
    }
}
