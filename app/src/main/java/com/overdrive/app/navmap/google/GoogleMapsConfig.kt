package com.overdrive.app.navmap.google

import android.os.Looper
import android.os.SystemClock
import com.overdrive.app.byd.cloud.crypto.CredentialCipher
import com.overdrive.app.byd.cloud.crypto.CredentialUpgrade
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.logging.DaemonLogger
import org.json.JSONObject
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class GoogleApi(val wire: String) {
    TILES("tiles"), ROUTES("routes"), PLACES("places"), GEOCODE("geocode")
}

enum class GoogleApiState(val wire: String) {
    OK("ok"), DISABLED("disabled"), DENIED("denied"), UNKNOWN("unknown")
}

enum class GooglePricing(val wire: String) {
    GLOBAL("global"), INDIA("india")
}

data class GoogleMapsSettings(
    val enabled: Boolean,
    val apiKey: String,
    val basemap: Boolean,
    val traffic: Boolean,
    val routing: Boolean,
    val search: Boolean,
    val pricing: GooglePricing,
    val quotaTilesPerDay: Int,
    val quotaRoutesPerDay: Int,
    val quotaPlacesPerDay: Int,
    val quotaGeocodePerDay: Int,
    val termsAcceptedMs: Long,
    val apiState: Map<GoogleApi, GoogleApiState>,
    val validatedMs: Long,
    val routeTraffic: Boolean = true,
    val paid: Boolean = false,
    val monthlyBudgetCents: Int = 0
) {
    val hasKey: Boolean
        get() = apiKey.isNotBlank()

    fun apiUsable(api: GoogleApi): Boolean {
        val state = apiState[api] ?: GoogleApiState.UNKNOWN
        return enabled && hasKey && termsAcceptedMs > 0 &&
            state != GoogleApiState.DISABLED && state != GoogleApiState.DENIED
    }

    val basemapUsable: Boolean
        get() = basemap && apiUsable(GoogleApi.TILES)
    val trafficUsable: Boolean
        get() = traffic && apiUsable(GoogleApi.TILES)
    val routingUsable: Boolean
        get() = routing && apiUsable(GoogleApi.ROUTES)
    val searchUsable: Boolean
        get() = search && apiUsable(GoogleApi.PLACES)
    val reverseUsable: Boolean
        get() = search && apiUsable(GoogleApi.GEOCODE)

    /** Route congestion bills a whole computeRoutes call at the Enterprise tier. */
    val routesSku: GoogleSku
        get() = if (routeTraffic) GoogleSku.ROUTES_TRAFFIC else GoogleSku.ROUTES

    // The generated data-class toString would print the key.
    override fun toString(): String =
        "GoogleMapsSettings(enabled=$enabled, hasKey=$hasKey, basemap=$basemap, traffic=$traffic, " +
            "routing=$routing, routeTraffic=$routeTraffic, search=$search, pricing=${pricing.wire}, paid=$paid/" +
            "$monthlyBudgetCents, quotas=$quotaTilesPerDay/" +
            "$quotaRoutesPerDay/$quotaPlacesPerDay/$quotaGeocodePerDay, termsAcceptedMs=$termsAcceptedMs, " +
            "apiState=$apiState, validatedMs=$validatedMs)"

    companion object {
        @JvmField
        val OFF = GoogleMapsSettings(
            enabled = false, apiKey = "",
            basemap = true, traffic = true, routing = true, search = true,
            pricing = GooglePricing.GLOBAL,
            quotaTilesPerDay = 0, quotaRoutesPerDay = 0, quotaPlacesPerDay = 0, quotaGeocodePerDay = 0,
            termsAcceptedMs = 0L,
            apiState = EnumMap<GoogleApi, GoogleApiState>(GoogleApi::class.java).apply {
                for (api in GoogleApi.entries) put(api, GoogleApiState.UNKNOWN)
            },
            validatedMs = 0L
        )
    }
}

/** Google BYOK settings: flat `google*` keys in the `navMap` unified-config section. */
object GoogleMapsConfig {

    private const val SECTION = "navMap"
    private const val KEY_API_KEY = "googleApiKey"
    private const val KEY_ENABLED = "googleEnabled"
    private const val KEY_BASEMAP = "googleBasemap"
    private const val KEY_TRAFFIC = "googleTraffic"
    private const val KEY_ROUTING = "googleRouting"
    private const val KEY_SEARCH = "googleSearch"
    private const val KEY_ROUTE_TRAFFIC = "googleRouteTraffic"
    private const val KEY_SPEND_MODE = "googleSpendMode"
    private const val KEY_BUDGET_CENTS = "googleMonthlyBudgetCents"
    private const val MAX_BUDGET_CENTS = 1_000_000
    private const val KEY_PRICING = "googlePricing"
    private const val KEY_QUOTA_TILES = "googleQuotaTilesPerDay"
    private const val KEY_QUOTA_ROUTES = "googleQuotaRoutesPerDay"
    private const val KEY_QUOTA_PLACES = "googleQuotaPlacesPerDay"
    private const val KEY_QUOTA_GEOCODE = "googleQuotaGeocodePerDay"
    private const val KEY_TERMS = "googleTermsAcceptedMs"
    private const val KEY_API_STATE = "googleApiState"
    private const val KEY_VALIDATED = "googleValidatedMs"
    private const val CACHE_TTL_MS = 15_000L

    private val BOOLEAN_KEYS = listOf(KEY_ENABLED, KEY_BASEMAP, KEY_TRAFFIC, KEY_ROUTING, KEY_SEARCH, KEY_ROUTE_TRAFFIC)
    private val QUOTA_KEYS = listOf(KEY_QUOTA_TILES, KEY_QUOTA_ROUTES, KEY_QUOTA_PLACES, KEY_QUOTA_GEOCODE)

    private val logger = DaemonLogger.getInstance("GoogleMapsConfig")

    private class Snapshot(val settings: GoogleMapsSettings, val atMs: Long)

    @Volatile private var cache: Snapshot? = null
    private val generation = AtomicInteger(0)
    private val reloading = AtomicBoolean(false)

    @JvmStatic
    fun load(): GoogleMapsSettings {
        val gen = generation.get()
        val settings = try {
            read()
        } catch (t: Throwable) {
            return cache?.settings ?: GoogleMapsSettings.OFF
        }
        // A load that raced a write + invalidate() must not republish its stale read.
        if (generation.get() == gen) cache = Snapshot(settings, SystemClock.elapsedRealtime())
        return settings
    }

    @JvmStatic
    fun cached(): GoogleMapsSettings {
        val c = cache ?: return load()
        if (SystemClock.elapsedRealtime() - c.atMs < CACHE_TTL_MS) return c.settings
        // One thread refreshes; concurrent callers keep the stale copy meanwhile.
        if (!reloading.compareAndSet(false, true)) return c.settings
        // The UI thread never waits on the config file: it keeps the stale copy and refreshes in the background.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                refresher.execute {
                    try { load() } finally { reloading.set(false) }
                }
            } catch (t: Throwable) {
                reloading.set(false)
            }
            return c.settings
        }
        return try {
            load()
        } finally {
            reloading.set(false)
        }
    }

    private val refresher: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "GoogleMapsConfig").apply { isDaemon = true }
        }
    }

    @JvmStatic
    fun invalidate() {
        generation.incrementAndGet()
        cache = null
    }

    @JvmStatic
    fun saveKey(apiKey: String): Boolean {
        val key = apiKey.trim()
        // encrypt() passes an "ENC:"-prefixed input through unchanged, which would store it in clear.
        if (!isUsableKey(key) || CredentialCipher.isEncrypted(key)) return false
        return try {
            val enc = CredentialCipher.encrypt(key)
            if (!CredentialCipher.isEncrypted(enc)) {
                logger.warn("Credential encryption failed; Google key not saved")
                return false
            }
            UnifiedConfigManager.updateSection(
                SECTION, JSONObject().put(KEY_API_KEY, enc).put(KEY_ENABLED, true)
            )
        } catch (t: Throwable) {
            false
        } finally {
            invalidate()
        }
    }

    @JvmStatic
    fun saveSettings(delta: JSONObject): Boolean {
        if (delta.has(KEY_API_KEY)) return false
        val out = JSONObject()
        try {
            for (k in BOOLEAN_KEYS) boolOf(delta.opt(k))?.let { out.put(k, it) }
            pricingOf(delta.opt(KEY_PRICING))?.let { out.put(KEY_PRICING, it.wire) }
            for (k in QUOTA_KEYS) intOf(delta.opt(k))?.let { out.put(k, it) }
            longOf(delta.opt(KEY_TERMS))?.let { out.put(KEY_TERMS, it) }
            when (delta.opt(KEY_SPEND_MODE)?.toString()?.trim()?.lowercase(Locale.ROOT)) {
                "free" -> out.put(KEY_SPEND_MODE, "free")
                "paid" -> out.put(KEY_SPEND_MODE, "paid")
            }
            intOf(delta.opt(KEY_BUDGET_CENTS))?.let { out.put(KEY_BUDGET_CENTS, it.coerceIn(0, MAX_BUDGET_CENTS)) }
        } catch (t: Throwable) {
            return false
        }
        if (out.length() == 0) return false
        return try {
            UnifiedConfigManager.updateSection(SECTION, out)
        } catch (t: Throwable) {
            false
        } finally {
            invalidate()
        }
    }

    @JvmStatic
    fun saveApiState(state: Map<GoogleApi, GoogleApiState>, validatedMs: Long): Boolean {
        return try {
            val current = parseApiState(
                UnifiedConfigManager.loadConfig().optJSONObject(SECTION)?.optJSONObject(KEY_API_STATE)
            )
            val obj = JSONObject()
            for (api in GoogleApi.entries) {
                obj.put(api.wire, (state[api] ?: current[api] ?: GoogleApiState.UNKNOWN).wire)
            }
            UnifiedConfigManager.updateSection(
                SECTION,
                JSONObject().put(KEY_API_STATE, obj).put(KEY_VALIDATED, validatedMs.coerceAtLeast(0L))
            )
        } catch (t: Throwable) {
            false
        } finally {
            invalidate()
        }
    }

    @JvmStatic
    fun clear(): Boolean {
        return try {
            val unknown = JSONObject()
            for (api in GoogleApi.entries) unknown.put(api.wire, GoogleApiState.UNKNOWN.wire)
            UnifiedConfigManager.updateSection(
                SECTION,
                JSONObject()
                    .put(KEY_API_KEY, "")
                    .put(KEY_ENABLED, false)
                    .put(KEY_API_STATE, unknown)
                    .put(KEY_VALIDATED, 0L)
            )
        } catch (t: Throwable) {
            false
        } finally {
            invalidate()
        }
    }

    /** Non-empty and safe as an HTTP header value (visible ASCII only). */
    internal fun isUsableKey(key: String): Boolean =
        key.isNotEmpty() && key.all { it in '!'..'~' }

    private fun read(): GoogleMapsSettings {
        val nav = UnifiedConfigManager.loadConfig().optJSONObject(SECTION) ?: return GoogleMapsSettings.OFF
        val stored = nav.optString(KEY_API_KEY, "")
        val apiKey = (CredentialCipher.decrypt(stored) ?: "").trim()
        // Config writes come only from the daemon (I7); the app process just decrypts.
        if (stored.isNotEmpty() && isDaemonProcess()) {
            if (!CredentialCipher.isEncrypted(stored)) {
                migrateKey(apiKey)
            } else {
                CredentialUpgrade.reEncryptKeyIfLegacy(SECTION, KEY_API_KEY)
            }
        }
        return GoogleMapsSettings(
            enabled = boolOf(nav.opt(KEY_ENABLED)) ?: false,
            apiKey = apiKey,
            basemap = boolOf(nav.opt(KEY_BASEMAP)) ?: true,
            traffic = boolOf(nav.opt(KEY_TRAFFIC)) ?: true,
            routing = boolOf(nav.opt(KEY_ROUTING)) ?: true,
            search = boolOf(nav.opt(KEY_SEARCH)) ?: true,
            pricing = pricingOf(nav.opt(KEY_PRICING)) ?: GooglePricing.GLOBAL,
            quotaTilesPerDay = intOf(nav.opt(KEY_QUOTA_TILES)) ?: 0,
            quotaRoutesPerDay = intOf(nav.opt(KEY_QUOTA_ROUTES)) ?: 0,
            quotaPlacesPerDay = intOf(nav.opt(KEY_QUOTA_PLACES)) ?: 0,
            quotaGeocodePerDay = intOf(nav.opt(KEY_QUOTA_GEOCODE)) ?: 0,
            termsAcceptedMs = longOf(nav.opt(KEY_TERMS)) ?: 0L,
            apiState = parseApiState(nav.optJSONObject(KEY_API_STATE)),
            validatedMs = longOf(nav.opt(KEY_VALIDATED)) ?: 0L,
            routeTraffic = boolOf(nav.opt(KEY_ROUTE_TRAFFIC)) ?: true,
            paid = nav.optString(KEY_SPEND_MODE, "free").equals("paid", ignoreCase = true) &&
                (intOf(nav.opt(KEY_BUDGET_CENTS)) ?: 0) > 0,
            monthlyBudgetCents = (intOf(nav.opt(KEY_BUDGET_CENTS)) ?: 0).coerceIn(0, MAX_BUDGET_CENTS)
        )
    }

    private fun migrateKey(plainKey: String) {
        try {
            if (plainKey.isEmpty()) return
            val enc = CredentialCipher.encrypt(plainKey)
            if (!CredentialCipher.isEncrypted(enc)) return
            UnifiedConfigManager.updateSection(SECTION, JSONObject().put(KEY_API_KEY, enc))
        } catch (t: Throwable) {
            // Best-effort; the plaintext still decrypts as-is and migrates on the next read.
        }
    }

    private fun isDaemonProcess(): Boolean =
        android.os.Process.myUid() < android.os.Process.FIRST_APPLICATION_UID

    private fun parseApiState(obj: JSONObject?): Map<GoogleApi, GoogleApiState> {
        val out = EnumMap<GoogleApi, GoogleApiState>(GoogleApi::class.java)
        for (api in GoogleApi.entries) {
            val wire = (obj?.opt(api.wire) as? String)?.trim()?.lowercase(Locale.ROOT)
            out[api] = GoogleApiState.entries.firstOrNull { it.wire == wire } ?: GoogleApiState.UNKNOWN
        }
        return out
    }

    private fun boolOf(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is String -> when (v.trim().lowercase(Locale.ROOT)) {
            "true" -> true
            "false" -> false
            else -> null
        }
        is Number -> v.toDouble().let { if (it.isNaN()) null else it != 0.0 }
        else -> null
    }

    private fun pricingOf(v: Any?): GooglePricing? {
        val wire = (v as? String)?.trim()?.lowercase(Locale.ROOT) ?: return null
        return GooglePricing.entries.firstOrNull { it.wire == wire }
    }

    private fun intOf(v: Any?): Int? = longOf(v)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()

    /** Non-negative whole number from a JSON number or numeric string; null when absent/garbage. */
    private fun longOf(v: Any?): Long? {
        val n: Long = when (v) {
            is Int -> v.toLong()
            is Long -> v
            is Number -> v.toDouble().takeUnless { it.isNaN() }?.toLong() ?: return null
            is String -> v.trim().let { s ->
                s.toLongOrNull() ?: s.toDoubleOrNull()?.takeUnless { it.isNaN() }?.toLong()
            } ?: return null
            else -> return null
        }
        return n.coerceAtLeast(0L)
    }
}
