package com.overdrive.app.safekeep;

import com.overdrive.app.automation.Automation;
import com.overdrive.app.automation.ConditionGroup;
import com.overdrive.app.monitor.NetworkMonitor;
import com.overdrive.app.safekeep.util.NetRoute;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;

/**
 * "May this route upload now?" Level-evaluated against a fresh {@link UploadSignals.Snap}:
 * presets expand to simple rules, advanced rules reuse the automation condition catalogue.
 * Hard guards are separate and cannot be relaxed by any rule.
 */
public final class Policy {

    public static final class Verdict {
        public final boolean ok;
        public final String reason;

        Verdict(boolean ok, String reason) {
            this.ok = ok;
            this.reason = reason;
        }
    }

    static final Verdict OK = new Verdict(true, null);

    static Verdict no(String reason) {
        return new Verdict(false, reason);
    }

    private static final ConcurrentHashMap<String, ConditionGroup> advancedCache = new ConcurrentHashMap<>();

    private Policy() {}

    /** Preset expansion; explicit fields on the stored rule override the preset. */
    public static JSONObject effective(JSONObject rule) {
        JSONObject r = rule == null ? new JSONObject() : rule;
        String preset = r.optString("preset", "wifi_any");
        JSONObject net = new JSONObject();
        JSONObject out = new JSONObject();
        try {
            String wifi = "any", mobile = "off", vehicle = "any", charging = "any";
            JSONObject window = null;
            switch (preset) {
                case "wifi_home":
                    wifi = "home";
                    vehicle = "parked";
                    break;
                case "wifi_or_mobile":
                    mobile = "on";
                    break;
                case "home_charging":
                    wifi = "home";
                    vehicle = "parked";
                    charging = "only";
                    break;
                case "home_night":
                    wifi = "home";
                    vehicle = "parked";
                    window = new JSONObject().put("from", 0).put("to", 360);
                    break;
                case "manual":
                    wifi = "off";
                    break;
                default:
                    break;
            }
            JSONObject rn = r.optJSONObject("network");
            if ("custom".equals(preset)) {
                if (rn != null) {
                    wifi = rn.optString("wifi", wifi);
                    mobile = rn.optString("mobile", mobile);
                }
                vehicle = r.optString("vehicle", vehicle);
                charging = r.optString("charging", charging);
                window = r.optJSONObject("window");
            } else if (rn != null && rn.has("mobile") && !"manual".equals(preset)) {
                String m = rn.optString("mobile", mobile);
                // A preset can only be widened to "snapshots on mobile", never to full mobile.
                if ("snapshots".equals(m) && "off".equals(mobile)) mobile = m;
            }
            net.put("wifi", wifi).put("mobile", mobile).put("ethernet", rn == null || rn.optBoolean("ethernet", true));
            out.put("preset", preset).put("network", net).put("vehicle", vehicle).put("charging", charging);
            if (window != null) out.put("window", window);
            out.put("maxMbps", Math.max(0, r.optDouble("maxMbps", 0)));
            JSONObject adv = r.optJSONObject("advanced");
            if (adv != null && "custom".equals(preset)) out.put("advanced", adv);
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * Soft rule of a route.
     *
     * @param pclass     0 tiny (snapshots, trip rows, settings), 1 evidence media, 2 bulk
     * @param lanReachable the destination is a LAN host that currently answers
     * @param manualMobileOk user confirmed a one-off upload on mobile data
     */
    public static Verdict rule(JSONObject eff, SafeKeepConfig cfg, UploadSignals.Snap s, int pclass,
                               boolean lanReachable, boolean manual, boolean manualMobileOk) {
        if (s.net == null || s.net.transport == NetRoute.Transport.NONE) return no("offline");
        if (manual) {
            if (!s.unmetered() && !manualMobileOk) return no("waiting_wifi");
            return OK;
        }
        String preset = eff.optString("preset", "wifi_any");
        if ("manual".equals(preset)) return no("manual_only");
        JSONObject net = eff.optJSONObject("network");
        if (net == null) net = new JSONObject();
        if (s.unmetered()) {
            if (s.net.transport == NetRoute.Transport.ETHERNET) {
                if (!net.optBoolean("ethernet", true)) return no("waiting_wifi");
            } else {
                String wifi = net.optString("wifi", "any");
                if ("off".equals(wifi)) return no("waiting_mobile");
                if ("home".equals(wifi) && !atHome(cfg, s, lanReachable)) return no("waiting_home_wifi");
            }
        } else {
            String mobile = net.optString("mobile", "off");
            boolean ok = "on".equals(mobile) || ("snapshots".equals(mobile) && pclass == 0);
            if (!ok) return no(s.phoneHotspot ? "hotspot_is_mobile" : "waiting_wifi");
        }
        String vehicle = eff.optString("vehicle", "any");
        if ("parked".equals(vehicle) && !s.parked) return no("waiting_parked");
        if ("driving".equals(vehicle) && s.parked) return no("waiting_driving");
        if ("only".equals(eff.optString("charging", "any")) && !s.charging) return no("waiting_charging");
        JSONObject w = eff.optJSONObject("window");
        if (w != null && !inWindow(w, s)) return no("outside_window");
        JSONObject adv = eff.optJSONObject("advanced");
        if (adv != null) {
            ConditionGroup g = parseAdvanced(adv);
            if (g == null) return no("conditions_invalid");
            if (!g.evaluate(UploadSignals.stateFor(g, s))) return no("conditions_not_met");
        }
        return OK;
    }

    /**
     * Non-overridable guards, checked before dispatch and before every chunk. 12 V and heat use
     * hysteresis (release 0.2 V above the floor / below 75 °C, held 10 s) so load sag can't cycle.
     */
    public static Verdict guards(SafeKeepConfig cfg, UploadSignals.Snap s, UploadSignals sig) {
        if (!s.clockValid) return no("clock_not_set");
        if (s.onOnlyMode && !s.accOn) return no("on_only_mode");
        boolean onBattery = !s.accOn && !s.charging;
        if (onBattery && !Double.isNaN(s.v12) && s.v12 > 5) {
            long deci = Math.round(s.v12 * 10);
            if (sig.latch("low_12v", deci < cfg.limits.min12vDeci, deci >= cfg.limits.min12vDeci + 2)) {
                return no("low_12v");
            }
        } else {
            sig.unlatch("low_12v");
        }
        if (onBattery && cfg.limits.parkedMaxMinutes > 0
                && sig.parkedUploadMs() > cfg.limits.parkedMaxMinutes * 60_000L) {
            return no("parked_time_cap");
        }
        if (!Float.isNaN(s.tempC) && sig.latch("too_hot", s.tempC >= 80f, s.tempC < 75f)) return no("too_hot");
        return OK;
    }

    public static boolean atHome(SafeKeepConfig cfg, UploadSignals.Snap s, boolean lanReachable) {
        if (s.phoneHotspot) return false;
        if (cfg.limits.homeSsids.isEmpty()) return true;
        String cur = normalizeSsid(s.ssid);
        for (String h : cfg.limits.homeSsids) if (normalizeSsid(h).equals(cur)) return true;
        // Some head units can't read the network name: then a NAS on the LAN answering stands in.
        return lanReachable && !ssidKnown(cur);
    }

    /**
     * "" (off Wi-Fi), the shared hidden-name placeholder and anything containing "unknown"
     * all mean the head unit could not read the network name.
     */
    private static boolean ssidKnown(String ssid) {
        return !ssid.isEmpty()
                && !NetworkMonitor.SSID_UNKNOWN_PLACEHOLDER.equals(ssid)
                && !ssid.toLowerCase(java.util.Locale.ROOT).contains("unknown");
    }

    static String normalizeSsid(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        return t;
    }

    private static final String[] WEEK =
            {"sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"};

    static boolean inWindow(JSONObject w, UploadSignals.Snap s) {
        int from = clampMinute(w.optInt("from", 0));
        int to = clampMinute(w.optInt("to", 1440));
        int m = s.minuteOfDay;
        String day = s.day;
        boolean inTime;
        if (from == to) {
            inTime = true;
        } else if (from < to) {
            inTime = m >= from && m < to;
        } else {
            inTime = m >= from || m < to;
            // The part after midnight belongs to the day the window started (Fri 22:00–02:00 covers Sat 01:00).
            if (m < to) day = previousDay(day);
        }
        if (!inTime) return false;
        JSONArray days = w.optJSONArray("days");
        if (days == null || days.length() == 0) return true;
        for (int i = 0; i < days.length(); i++) if (day.equalsIgnoreCase(days.optString(i))) return true;
        return false;
    }

    private static String previousDay(String day) {
        for (int i = 0; i < WEEK.length; i++) {
            if (WEEK[i].equalsIgnoreCase(day)) return WEEK[(i + WEEK.length - 1) % WEEK.length];
        }
        return day;
    }

    private static int clampMinute(int m) {
        return Math.max(0, Math.min(1440, m));
    }

    static ConditionGroup parseAdvanced(JSONObject adv) {
        String key = adv.toString();
        // Right-hand sides must be literals: variables and live signals are not SafeKeep's to read.
        if (key.contains("${")) return null;
        ConditionGroup g = advancedCache.get(key);
        if (g == null) {
            try {
                g = ConditionGroup.fromJson(adv, Automation.MAX_GROUP_DEPTH);
            } catch (Throwable t) {
                g = null;
            }
            if (g != null) {
                if (advancedCache.size() > 64) advancedCache.clear();
                advancedCache.put(key, g);
            }
        }
        return g;
    }

    /** Upload rate in bytes/s for the current link; 0 = unlimited. */
    public static double rateBytesPerSec(SafeKeepConfig cfg, UploadSignals.Snap s, JSONObject eff) {
        double mbps;
        if (!s.unmetered()) mbps = cfg.limits.mobileMaxMbps;
        else if (!s.parked) mbps = cfg.limits.drivingMaxMbps;
        else mbps = cfg.limits.wifiMaxMbps;
        double routeCap = eff == null ? 0 : eff.optDouble("maxMbps", 0);
        if (routeCap > 0 && (mbps <= 0 || routeCap < mbps)) mbps = routeCap;
        if (!Float.isNaN(s.tempC) && s.tempC >= 70f) mbps = mbps <= 0 ? 4 : mbps / 2;
        return mbps <= 0 ? 0 : mbps * 125_000.0;
    }
}
