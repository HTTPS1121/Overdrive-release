package com.overdrive.app.safekeep;

import com.overdrive.app.config.UnifiedConfigManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Read-modify-write of the safeKeep section under the config lock (nested arrays are replaced
 * wholesale by updateSection, so every edit re-reads first). Lifecycle changes run after the
 * lock is released so a stopping engine never waits on the lock it would need.
 */
public final class SafeKeepAdmin {

    private static final Object writeLock = new Object();

    private SafeKeepAdmin() {}

    public interface Mutation {
        void apply(JSONObject section) throws Exception;
    }

    public static JSONObject section() {
        JSONObject s = UnifiedConfigManager.forceReload().optJSONObject(SafeKeepConfig.SECTION);
        try {
            return s == null ? new JSONObject() : new JSONObject(s.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static boolean mutate(Mutation m) {
        final JSONObject[] result = new JSONObject[1];
        boolean ok;
        synchronized (writeLock) {
            try {
                ok = UnifiedConfigManager.runUnderConfigLock(() -> {
                    try {
                        JSONObject s = section();
                        m.apply(s);
                        result[0] = s;
                        return UnifiedConfigManager.replaceSection(SafeKeepConfig.SECTION, s);
                    } catch (Exception e) {
                        return false;
                    }
                });
            } catch (Exception e) {
                ok = false;
            }
        }
        if (ok && result[0] != null) {
            boolean enabled = result[0].optBoolean("enabled", false);
            if (enabled != SafeKeep.isActive()) {
                SafeKeep.converge();
            } else {
                Engine e = SafeKeep.engine();
                if (e != null) e.reloadConfigAsync();
            }
        }
        return ok;
    }

    public static boolean setEnabled(boolean enabled) {
        return mutate(s -> s.put("enabled", enabled));
    }

    /** Merges allowed top-level keys; arrays/objects are replaced as a whole. */
    public static boolean patch(JSONObject patch) {
        return mutate(s -> {
            Iterator<String> it = patch.keys();
            while (it.hasNext()) {
                String k = it.next();
                switch (k) {
                    case "enabled":
                    case "vehicleName":
                    case "limits":
                    case "notify":
                    case "routes":
                    case "webhooks":
                        s.put(k, patch.get(k));
                        break;
                    default:
                        break;
                }
            }
        });
    }

    static boolean addDestination(String id, String type, String name, JSONObject params, String proxyMode) {
        return mutate(s -> {
            JSONArray dests = s.optJSONArray("destinations");
            if (dests == null) dests = new JSONArray();
            JSONObject d = new JSONObject()
                    .put("id", id).put("type", type).put("name", name).put("enabled", true)
                    .put("layout", "s3".equals(type) ? "machine" : "human")
                    .put("proxyMode", proxyMode == null ? "auto" : proxyMode)
                    .put("params", params == null ? new JSONObject() : params);
            dests.put(d);
            s.put("destinations", dests);
            JSONArray routes = s.optJSONArray("routes");
            if (routes == null || routes.length() == 0) {
                routes = new JSONArray()
                        .put(route("evidence", id, "wifi_any"))
                        .put(route("trips", id, "wifi_any"))
                        .put(route("settings", id, "wifi_any"));
                s.put("routes", routes);
            }
        });
    }

    private static JSONObject route(String lane, String destId, String preset) throws Exception {
        return new JSONObject().put("lane", lane).put("dest", new JSONArray().put(destId))
                .put("rule", new JSONObject().put("preset", preset));
    }

    static boolean updateDestination(String id, JSONObject patch) {
        return mutate(s -> {
            JSONArray dests = s.optJSONArray("destinations");
            if (dests == null) return;
            for (int i = 0; i < dests.length(); i++) {
                JSONObject d = dests.optJSONObject(i);
                if (d == null || !id.equals(d.optString("id"))) continue;
                Iterator<String> it = patch.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if ("id".equals(k) || "type".equals(k)) continue;
                    if ("params".equals(k)) {
                        JSONObject p = d.optJSONObject("params");
                        if (p == null) p = new JSONObject();
                        JSONObject np = patch.getJSONObject("params");
                        Iterator<String> pk = np.keys();
                        while (pk.hasNext()) {
                            String kk = pk.next();
                            p.put(kk, np.get(kk));
                        }
                        d.put("params", p);
                    } else {
                        d.put(k, patch.get(k));
                    }
                }
            }
        });
    }

    static boolean removeDestination(String id) {
        boolean ok = mutate(s -> {
            JSONArray dests = s.optJSONArray("destinations");
            JSONArray kept = new JSONArray();
            if (dests != null) for (int i = 0; i < dests.length(); i++) {
                JSONObject d = dests.optJSONObject(i);
                if (d != null && !id.equals(d.optString("id"))) kept.put(d);
            }
            s.put("destinations", kept);
            JSONArray routes = s.optJSONArray("routes");
            if (routes != null) {
                for (int i = 0; i < routes.length(); i++) {
                    JSONObject r = routes.optJSONObject(i);
                    JSONArray ds = r == null ? null : r.optJSONArray("dest");
                    if (ds == null) continue;
                    JSONArray nd = new JSONArray();
                    for (int j = 0; j < ds.length(); j++) if (!id.equals(ds.optString(j))) nd.put(ds.optString(j));
                    r.put("dest", nd);
                }
            }
        });
        if (ok) {
            SafeKeepSecrets.remove(id);
            Engine e = SafeKeep.engine();
            if (e != null) e.runCtl(() -> e.ledger.forgetDestination(id));
        }
        return ok;
    }
}
