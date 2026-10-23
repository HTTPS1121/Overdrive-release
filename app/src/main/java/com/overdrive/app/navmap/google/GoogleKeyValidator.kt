package com.overdrive.app.navmap.google

import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.navmap.nav.MapNetworking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Checks a Google Maps key against each API with requests Google does not bill (daemon, blocking).
 * The key rides only on the probe requests; logs and results carry per-API outcomes, never the key.
 */
object GoogleKeyValidator {

    const val REASON_OK = "ok"
    const val REASON_INVALID_KEY = "invalid_key"
    const val REASON_DISABLED = "disabled"
    const val REASON_BILLING = "billing"
    const val REASON_RESTRICTED = "restricted"
    const val REASON_QUOTA = "quota"
    const val REASON_NETWORK = "network"
    const val REASON_TIMEOUT = "timeout"
    const val REASON_UNEXPECTED = "unexpected"
    const val REASON_UNVERIFIED = "unverified"

    class ValidationResult(
        val states: Map<GoogleApi, GoogleApiState>,
        val reasons: Map<GoogleApi, String>
    ) {
        /** Google reports the key itself as invalid and no probe got through with it. */
        val keyRejected: Boolean
            get() {
                val apis = listOf(GoogleApi.TILES, GoogleApi.ROUTES, GoogleApi.PLACES)
                return apis.any { reasons[it] == REASON_INVALID_KEY } && apis.none { states[it] == GoogleApiState.OK }
            }
    }

    private class Outcome(val state: GoogleApiState, val reason: String)

    private const val PROBE_TIMEOUT_MS = 6_000L
    private const val TOTAL_TIMEOUT_MS = 7_000L
    private const val MAX_BODY_BYTES = 64L * 1024
    private val KEY_FORMAT = Regex("AIza[A-Za-z0-9_-]{35}")
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val logger = DaemonLogger.getInstance("GoogleKeyValidator")

    private val http: OkHttpClient by lazy {
        MapNetworking.builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** "AIza" followed by 35 characters of [A-Za-z0-9_-]. */
    @JvmStatic
    fun isValidFormat(apiKey: String?): Boolean = apiKey != null && KEY_FORMAT.matches(apiKey)

    /** Runs the four probes in parallel; returns within about 7 s, unanswered probes as UNKNOWN. */
    @JvmStatic
    fun validate(apiKey: String): ValidationResult {
        val states = EnumMap<GoogleApi, GoogleApiState>(GoogleApi::class.java)
        val reasons = EnumMap<GoogleApi, String>(GoogleApi::class.java)
        if (!isValidFormat(apiKey)) {
            for (api in GoogleApi.entries) {
                states[api] = GoogleApiState.DENIED
                reasons[api] = REASON_INVALID_KEY
            }
            return ValidationResult(states, reasons)
        }
        val pool = Executors.newFixedThreadPool(GoogleApi.entries.size) { task ->
            Thread(task, "GoogleKeyProbe").apply { isDaemon = true }
        }
        try {
            val futures = LinkedHashMap<GoogleApi, Future<Outcome>>()
            for (api in GoogleApi.entries) futures[api] = pool.submit(Callable { probe(api, apiKey) })
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_TIMEOUT_MS)
            for ((api, future) in futures) {
                val outcome = try {
                    future.get(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) {
                    future.cancel(true)
                    Outcome(GoogleApiState.UNKNOWN, REASON_TIMEOUT)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Outcome(GoogleApiState.UNKNOWN, REASON_TIMEOUT)
                } catch (_: Exception) {
                    Outcome(GoogleApiState.UNKNOWN, REASON_UNEXPECTED)
                }
                states[api] = outcome.state
                reasons[api] = outcome.reason
            }
        } finally {
            pool.shutdownNow()
        }
        logger.info("Key check: " + GoogleApi.entries.joinToString(" ") { "${wire(it)}=${reasons[it]}" })
        return ValidationResult(states, reasons)
    }

    private fun probe(api: GoogleApi, key: String): Outcome = try {
        when (api) {
            GoogleApi.TILES -> send(tilesRequest(key), ::classifyTiles)
            GoogleApi.ROUTES -> send(routesRequest(key), ::classifyRpc)
            GoogleApi.PLACES -> send(placesRequest(key), ::classifyRpc)
            GoogleApi.GEOCODE -> send(geocodeRequest(key), ::classifyGeocode)
        }
    } catch (_: InterruptedIOException) {
        Outcome(GoogleApiState.UNKNOWN, REASON_TIMEOUT)
    } catch (_: IOException) {
        Outcome(GoogleApiState.UNKNOWN, REASON_NETWORK)
    } catch (_: Exception) {
        Outcome(GoogleApiState.UNKNOWN, REASON_UNEXPECTED)
    }

    // createSession is free; a 200 with a session token proves the key, the API and billing.
    private fun tilesRequest(key: String): Request {
        val url = "https://tile.googleapis.com/v1/createSession".toHttpUrl().newBuilder()
            .addQueryParameter("key", key)
            .build()
        val body = JSONObject().put("mapType", "roadmap").put("language", "en-US").put("region", "US")
        return Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()
    }

    // An empty body fails request validation, so no route is computed or billed.
    private fun routesRequest(key: String): Request = Request.Builder()
        .url("https://routes.googleapis.com/directions/v2:computeRoutes")
        .header("X-Goog-Api-Key", key)
        .header("X-Goog-FieldMask", "routes.duration")
        .post("{}".toRequestBody(JSON))
        .build()

    // An empty body has no input, so Google rejects it before any autocomplete work.
    private fun placesRequest(key: String): Request = Request.Builder()
        .url("https://places.googleapis.com/v1/places:autocomplete")
        .header("X-Goog-Api-Key", key)
        .post("{}".toRequestBody(JSON))
        .build()

    // Without address or latlng Google answers INVALID_REQUEST and never geocodes (or bills).
    private fun geocodeRequest(key: String): Request {
        val url = "https://maps.googleapis.com/maps/api/geocode/json".toHttpUrl().newBuilder()
            .addQueryParameter("key", key)
            .build()
        return Request.Builder().url(url).get().build()
    }

    private fun send(request: Request, classify: (Int, String) -> Outcome): Outcome =
        http.newCall(request).execute().use { response ->
            classify(response.code, response.peekBody(MAX_BODY_BYTES).string())
        }

    private fun classifyTiles(code: Int, body: String): Outcome {
        if (code !in 200..299) return classifyRpc(code, body)
        val session = parseJson(body)?.optString("session", "").orEmpty()
        return if (session.isNotEmpty()) Outcome(GoogleApiState.OK, REASON_OK)
        else Outcome(GoogleApiState.UNKNOWN, REASON_UNEXPECTED)
    }

    // Google RPC errors: an invalid key arrives as a 400, so only a non-key 400 counts as OK.
    private fun classifyRpc(code: Int, body: String): Outcome {
        if (code in 200..299) return Outcome(GoogleApiState.OK, REASON_OK)
        val error = parseJson(body)?.optJSONObject("error")
        val status = error?.optString("status", "").orEmpty()
        val message = error?.optString("message", "").orEmpty().lowercase(Locale.ROOT)
        val reasons = HashSet<String>()
        val details = error?.optJSONArray("details")
        if (details != null) {
            for (i in 0 until details.length()) {
                val reason = details.optJSONObject(i)?.optString("reason", "").orEmpty()
                if (reason.isNotEmpty()) reasons.add(reason)
            }
        }
        return when {
            "API_KEY_INVALID" in reasons || "API_KEY_EXPIRED" in reasons ||
                "api key not valid" in message || "api key expired" in message -> denied(REASON_INVALID_KEY)
            "BILLING_DISABLED" in reasons -> denied(REASON_BILLING)
            "SERVICE_DISABLED" in reasons -> Outcome(GoogleApiState.DISABLED, REASON_DISABLED)
            // The app's own requests carry the package/cert headers this probe cannot send.
            "API_KEY_ANDROID_APP_BLOCKED" in reasons -> Outcome(GoogleApiState.UNKNOWN, REASON_UNVERIFIED)
            reasons.any { it.startsWith("API_KEY_") && it.endsWith("_BLOCKED") } -> denied(REASON_RESTRICTED)
            "billing" in message -> denied(REASON_BILLING)
            "has not been used" in message || "is disabled" in message ->
                Outcome(GoogleApiState.DISABLED, REASON_DISABLED)
            code == 429 || status == "RESOURCE_EXHAUSTED" -> Outcome(GoogleApiState.OK, REASON_QUOTA)
            code == 400 -> Outcome(GoogleApiState.OK, REASON_OK)
            code == 401 -> denied(REASON_INVALID_KEY)
            code == 403 -> denied(REASON_RESTRICTED)
            else -> Outcome(GoogleApiState.UNKNOWN, "http_$code")
        }
    }

    private fun classifyGeocode(code: Int, body: String): Outcome {
        val json = parseJson(body)
        val status = json?.optString("status", "").orEmpty()
        if (status.isEmpty()) {
            return if (code in 200..299) Outcome(GoogleApiState.UNKNOWN, REASON_UNEXPECTED)
            else classifyRpc(code, body)
        }
        val message = json?.optString("error_message", "").orEmpty().lowercase(Locale.ROOT)
        return when (status) {
            // Google rejects missing parameters before it looks at the key, so this proves reachability only.
            "INVALID_REQUEST" -> Outcome(GoogleApiState.OK, REASON_UNVERIFIED)
            "OK", "ZERO_RESULTS" -> Outcome(GoogleApiState.OK, REASON_OK)
            "REQUEST_DENIED" -> when {
                // "...not authorized to use this API key" is an app/IP restriction, not a disabled API.
                "not activated" in message ||
                    ("not authorized to use this api" in message && "this api key" !in message) ->
                    Outcome(GoogleApiState.DISABLED, REASON_DISABLED)
                "billing" in message -> denied(REASON_BILLING)
                "api key is invalid" in message || "api key not valid" in message ||
                    "api key expired" in message -> denied(REASON_INVALID_KEY)
                // An app/IP restriction looks the same here; the app's own requests settle it.
                "not authorized to use this api key" in message -> Outcome(GoogleApiState.UNKNOWN, REASON_UNVERIFIED)
                else -> denied(REASON_RESTRICTED)
            }
            "OVER_DAILY_LIMIT" -> when {
                "billing" in message -> denied(REASON_BILLING)
                "invalid" in message -> denied(REASON_INVALID_KEY)
                else -> Outcome(GoogleApiState.OK, REASON_QUOTA)
            }
            "OVER_QUERY_LIMIT" -> Outcome(GoogleApiState.OK, REASON_QUOTA)
            else -> Outcome(GoogleApiState.UNKNOWN, REASON_UNEXPECTED)
        }
    }

    private fun denied(reason: String) = Outcome(GoogleApiState.DENIED, reason)

    private fun parseJson(body: String): JSONObject? = try {
        JSONObject(body)
    } catch (_: Exception) {
        null
    } catch (_: StackOverflowError) {
        null
    }

    private fun wire(api: GoogleApi): String = api.name.lowercase(Locale.ROOT)
}
