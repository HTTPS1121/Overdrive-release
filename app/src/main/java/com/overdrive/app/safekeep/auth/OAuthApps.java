package com.overdrive.app.safekeep.auth;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * OAuth app registrations for the consumer clouds. Resolution order: values the user entered
 * for this destination ("use my own app"), then /data/local/tmp/.safekeep/oauth_apps.json,
 * then the IDs an official build carries. Installed-app client secrets are not
 * confidential by the providers' own definition, so shipping them in the APK is expected.
 */
public final class OAuthApps {

    // Official builds fill these from safekeep-oauth.properties via BuildConfig (read by name,
    // so source builds whose BuildConfig lacks them compile and simply have no built-in app).
    private static final String GOOGLE_CLIENT_ID = built("SAFEKEEP_GOOGLE_CLIENT_ID");
    private static final String GOOGLE_CLIENT_SECRET = built("SAFEKEEP_GOOGLE_CLIENT_SECRET");
    private static final String MICROSOFT_CLIENT_ID = built("SAFEKEEP_MICROSOFT_CLIENT_ID");
    private static final String DROPBOX_APP_KEY = built("SAFEKEEP_DROPBOX_APP_KEY");

    private static final File OVERRIDE = new File(com.overdrive.app.util.ScratchRoot.path(".safekeep/oauth_apps.json"));

    private OAuthApps() {}

    public static final class App {
        public final String clientId;
        public final String clientSecret;

        App(String clientId, String clientSecret) {
            this.clientId = clientId == null ? "" : clientId.trim();
            this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        }

        public boolean configured() {
            return !clientId.isEmpty();
        }
    }

    /** @param userSupplied optional {clientId, clientSecret} from the add-destination form */
    public static App get(String provider, JSONObject userSupplied) {
        if (userSupplied != null && !userSupplied.optString("clientId", "").trim().isEmpty()) {
            return new App(userSupplied.optString("clientId"), userSupplied.optString("clientSecret"));
        }
        JSONObject o = override();
        JSONObject p = o == null ? null : o.optJSONObject(provider);
        if (p != null && !p.optString("clientId", "").isEmpty()) {
            return new App(p.optString("clientId"), p.optString("clientSecret"));
        }
        switch (provider) {
            case "gdrive":
                return new App(GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET);
            case "onedrive":
                return new App(MICROSOFT_CLIENT_ID, "");
            case "dropbox":
                return new App(DROPBOX_APP_KEY, "");
            default:
                return new App("", "");
        }
    }

    private static String built(String field) {
        try {
            Object v = Class.forName("com.overdrive.app.BuildConfig").getField(field).get(null);
            return v instanceof String ? ((String) v).trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static JSONObject override() {
        try {
            if (!OVERRIDE.isFile() || OVERRIDE.length() > 16 * 1024) return null;
            return new JSONObject(new String(Files.readAllBytes(OVERRIDE.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}
