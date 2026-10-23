package com.overdrive.app.safekeep.auth;

import com.overdrive.app.safekeep.SafeKeepSecrets;
import com.overdrive.app.safekeep.dest.DestError;

import org.json.JSONObject;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Access-token cache with refresh-token rotation. Refreshed tokens are persisted (fail-closed)
 * before use so a kill never strands a rotated refresh token.
 */
public final class OAuthTokens {

    /** lastError code the page explains: the user's own Google Cloud project is still in Testing. */
    public static final String GOOGLE_TESTING_EXPIRED = "google_testing_expired";
    private static final long TESTING_TOKEN_LIFE_MS = 6L * 24 * 3600_000L;

    private final String destId;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String scope;

    public OAuthTokens(String destId, String tokenUrl, String clientId, String clientSecret, String scope) {
        this.destId = destId;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
    }

    public synchronized String accessToken(OkHttpClient http) throws DestError {
        JSONObject s = SafeKeepSecrets.get(destId);
        String access = s.optString("accessToken", "");
        long exp = s.optLong("expiresAt", 0);
        if (!access.isEmpty() && System.currentTimeMillis() < exp - 120_000) return access;
        return refresh(http, s);
    }

    /** Forces a refresh on the next call (after a 401). */
    public synchronized void invalidate() {
        try {
            SafeKeepSecrets.merge(destId, new JSONObject().put("expiresAt", 0));
        } catch (Exception ignored) {
        }
    }

    /** Keeps sliding refresh windows (Microsoft: 90 days) alive while the car is online. */
    public synchronized void keepAlive(OkHttpClient http, long maxAgeMs) throws DestError {
        JSONObject s = SafeKeepSecrets.get(destId);
        if (System.currentTimeMillis() - s.optLong("refreshedAt", 0) > maxAgeMs) refresh(http, s);
    }

    private String refresh(OkHttpClient http, JSONObject s) throws DestError {
        String refresh = s.optString("refreshToken", "");
        if (refresh.isEmpty()) throw new DestError(DestError.Kind.AUTH_EXPIRED, "not signed in");
        // A reconnect may switch OAuth apps: the stored pair wins over the one captured at build time.
        String cid = s.optString("clientId", clientId);
        String secret = s.has("clientId") ? s.optString("clientSecret", "") : clientSecret;
        FormBody.Builder fb = new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", cid);
        if (secret != null && !secret.isEmpty()) fb.add("client_secret", secret);
        if (scope != null && !scope.isEmpty()) fb.add("scope", scope);
        Request req = new Request.Builder().url(tokenUrl).post(fb.build()).build();
        try (Response r = http.newCall(req).execute()) {
            String body = r.body() == null ? "" : r.body().string();
            JSONObject j;
            try {
                j = new JSONObject(body);
            } catch (Exception e) {
                j = new JSONObject();
            }
            if (!r.isSuccessful()) {
                String err = j.optString("error", "");
                long linkedAt = s.optLong("linkedAt", 0);
                if ("invalid_grant".equals(err) && "gdrive".equals(s.optString("provider"))
                        && s.optBoolean("ownApp", false) && linkedAt > 0
                        && System.currentTimeMillis() - linkedAt >= TESTING_TOKEN_LIFE_MS) {
                    throw new DestError(DestError.Kind.AUTH_EXPIRED, GOOGLE_TESTING_EXPIRED, r.code(), 0);
                }
                if ("invalid_grant".equals(err) || "invalid_client".equals(err)
                        || "unauthorized_client".equals(err) || (r.code() == 401 && err.isEmpty())) {
                    throw new DestError(DestError.Kind.AUTH_EXPIRED,
                            "sign-in expired (" + (err.isEmpty() ? r.code() : err) + ")", r.code(), 0);
                }
                throw new DestError(DestError.Kind.NETWORK, "token refresh HTTP " + r.code(), r.code(), 0);
            }
            String access = j.optString("access_token", "");
            if (access.isEmpty()) throw new DestError(DestError.Kind.AUTH_EXPIRED, "no access token returned");
            JSONObject upd = new JSONObject()
                    .put("accessToken", access)
                    .put("expiresAt", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000L)
                    .put("refreshedAt", System.currentTimeMillis());
            String rotated = j.optString("refresh_token", "");
            if (!rotated.isEmpty()) upd.put("refreshToken", rotated);
            if (!SafeKeepSecrets.merge(destId, upd)) {
                throw new DestError(DestError.Kind.LOCAL_IO, "could not store refreshed token securely");
            }
            return access;
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }
}
