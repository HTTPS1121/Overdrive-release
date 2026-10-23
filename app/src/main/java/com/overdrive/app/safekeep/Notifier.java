package com.overdrive.app.safekeep;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.safekeep.net.HttpFactory;
import com.overdrive.app.telegram.TelegramNotifier;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Telegram notices (rate-limited per kind) and outbound webhooks, all off the transfer threads. */
final class Notifier {

    private static final DaemonLogger logger = DaemonLogger.getInstance("SafeKeepNotify");
    private static final long SIX_HOURS = 6 * 3600_000L;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final Engine engine;
    private final ConcurrentHashMap<String, Long> lastSent = new ConcurrentHashMap<>();

    Notifier(Engine engine) {
        this.engine = engine;
    }

    private boolean due(String key, long gapMs) {
        long now = System.currentTimeMillis();
        Long prev = lastSent.get(key);
        if (prev != null && now - prev < gapMs) return false;
        lastSent.put(key, now);
        return true;
    }

    void needsAuth(String destId) {
        SafeKeepConfig c = engine.cfg;
        if (c == null || !c.notify.needsAuth || !due("auth:" + destId, SIX_HOURS)) return;
        SafeKeepConfig.Destination d = c.destination(destId);
        String name = d == null ? destId : d.name;
        send("SafeKeep: sign-in to " + name + " expired. Open SafeKeep on the car to reconnect — "
                + "clips stay queued until then.", "CRITICAL");
    }

    void quota(String destId, int pct) {
        SafeKeepConfig c = engine.cfg;
        if (c == null || !c.notify.quota || !due("quota:" + destId, 24 * 3600_000L)) return;
        SafeKeepConfig.Destination d = c.destination(destId);
        send("SafeKeep: " + (d == null ? destId : d.name) + " is " + pct + "% full. Oldest backups are pruned "
                + "per your retention settings.", "CRITICAL");
    }

    void mobileCap() {
        SafeKeepConfig c = engine.cfg;
        if (c == null || !c.notify.mobileCap) return;
        String period = Budgets.periodKey(c.limits.mobileResetDay, System.currentTimeMillis());
        if (!due("mobilecap:" + period, 31L * 24 * 3600_000L)) return;
        send("SafeKeep: this month's mobile-data allowance (" + c.limits.mobileCapMb
                + " MB) is used up. Uploads continue on Wi-Fi.", "CONNECTIVITY");
    }

    void clipSaved(String fileName, String destName, String link) {
        SafeKeepConfig c = engine.cfg;
        if (c == null || !c.notify.evidenceSaved) return;
        send("SafeKeep: " + fileName + " saved to " + destName + (link != null ? "\n" + link : ""), "VIDEO");
    }

    private void send(String text, String category) {
        engine.runAux(() -> {
            try {
                TelegramNotifier.sendMessage(text, category);
            } catch (Throwable t) {
                logger.warn("Telegram notice failed: " + t.getMessage());
            }
        });
    }

    /** Fire-and-forget webhook with an HMAC-SHA256 body signature when a secret is configured. */
    void webhook(String event, JSONObject data) {
        SafeKeepConfig c = engine.cfg;
        if (c == null || c.webhooks.isEmpty()) return;
        for (SafeKeepConfig.Webhook w : c.webhooks) {
            boolean wanted = ("trip.finished".equals(event) && w.tripFinished)
                    || ("evidence.saved".equals(event) && w.evidenceSaved);
            if (!wanted) continue;
            final String url = w.url;
            engine.runAux(() -> post(url, event, data, 0));
        }
    }

    /** One attempt; a retry is scheduled (5 s, 10 s), never slept, so the aux thread stays free. */
    private void post(String url, String event, JSONObject data, int attempt) {
        boolean retry = false;
        try {
            SafeKeepConfig c = engine.cfg;
            JSONObject body = new JSONObject().put("event", event).put("vehicle", c.vehicleName)
                    .put("vehicleId", c.vehicleId).put("at", System.currentTimeMillis()).put("data", data);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            Request.Builder rb = new Request.Builder().url(url).post(RequestBody.create(bytes, JSON))
                    .header("User-Agent", "OverDrive-SafeKeep").header("X-OverDrive-Event", event);
            String secret = SafeKeepSecrets.get("webhooks").optString(url, "");
            if (!secret.isEmpty()) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                rb.header("X-OverDrive-Signature", "sha256="
                        + com.overdrive.app.safekeep.util.StreamDigests.hex(mac.doFinal(bytes)));
            }
            OkHttpClient client = HttpFactory.client(HttpFactory.ProxyMode.AUTO, null).newBuilder()
                    .callTimeout(20, TimeUnit.SECONDS).build();
            try (Response r = client.newCall(rb.build()).execute()) {
                retry = !r.isSuccessful() && !(r.code() >= 400 && r.code() < 500);
            } catch (Exception e) {
                retry = true;
            }
        } catch (Throwable t) {
            logger.warn("Webhook failed: " + t.getMessage());
        }
        if (retry && attempt < 2 && engine.aux != null && !engine.stopping) {
            try {
                engine.aux.schedule(() -> post(url, event, data, attempt + 1), 5_000L << attempt, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
        }
    }
}
