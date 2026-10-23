package com.overdrive.app.navmap.google

import android.os.SystemClock
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Pushes the app-process budget snapshot to the daemon so the web settings card can show usage, and on
 * startup folds the daemon's saved copy back in: it outlives app data, so a reinstall keeps its counts.
 */
object GoogleUsageReporter {

    private const val TAG = "GoogleUsageReporter"
    private const val USAGE_URL = "http://127.0.0.1:8080/api/navmap/google/usage"
    private const val STATUS_URL = "http://127.0.0.1:8080/api/navmap/google/status"
    private const val MIN_INTERVAL_MS = 30_000L
    private const val RESTORE_FIRST_RETRY_MS = 5_000L
    private const val RESTORE_MAX_RETRY_MS = 60_000L

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Daemon loopback: never proxied.
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(3000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "GoogleUsageReport").apply { isDaemon = true } }
    }

    private val queued = AtomicBoolean(false)
    private val forceRequested = AtomicBoolean(false)

    // Executor thread only.
    private var lastPostMs = 0L
    private var lastPostedFingerprint: String? = null
    private var restored = false
    private var retryDelayMs = 0L
    private var retry: ScheduledFuture<*>? = null

    /** Call after [GoogleMapsBudget.init]; retries with backoff until the daemon answers. */
    @JvmStatic
    fun restoreSoon() {
        try {
            executor.execute { restoreOrRetry() }
        } catch (t: Throwable) {
            Log.w(TAG, "usage restore not queued: ${t.javaClass.simpleName}")
        }
    }

    @JvmStatic
    fun maybeReport(force: Boolean) {
        try {
            if (force) forceRequested.set(true)
            if (!queued.compareAndSet(false, true)) return
            executor.execute {
                queued.set(false)
                try {
                    report(forceRequested.getAndSet(false))
                } catch (t: Throwable) {
                    Log.w(TAG, "usage report failed: ${t.javaClass.simpleName}")
                }
            }
        } catch (t: Throwable) {
            queued.set(false)
        }
    }

    private fun report(force: Boolean) {
        // Before init() the counters are not loaded; posting them would wipe the daemon's copy.
        if (GoogleMapsBudget.appContext() == null) return
        // Without a key nothing is metered, so there is nothing to mirror (and no extra loopback traffic).
        if (!GoogleMapsConfig.cached().hasKey) return
        // Same until the daemon's copy is folded in: after a reinstall it is the only record of this month.
        if (!ensureRestored()) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastPostMs != 0L && now - lastPostMs < MIN_INTERVAL_MS) return
        val snapshot = GoogleMapsBudget.snapshotJson()
        val fingerprint = fingerprint(snapshot)
        if (fingerprint == lastPostedFingerprint) return
        lastPostMs = now
        val req = Request.Builder()
            .url(USAGE_URL)
            .post(snapshot.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                lastPostedFingerprint = fingerprint
            } else {
                Log.w(TAG, "POST usage -> HTTP ${resp.code}")
            }
        }
    }

    private fun restoreOrRetry() {
        retry?.cancel(false)
        retry = null
        val done = try {
            ensureRestored()
        } catch (t: Throwable) {
            Log.w(TAG, "usage restore failed: ${t.javaClass.simpleName}")
            false
        }
        if (done || GoogleMapsBudget.appContext() == null) return
        retryDelayMs = if (retryDelayMs == 0L) RESTORE_FIRST_RETRY_MS else min(retryDelayMs * 2, RESTORE_MAX_RETRY_MS)
        try {
            retry = executor.schedule({ restoreOrRetry() }, retryDelayMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "usage restore retry not scheduled: ${t.javaClass.simpleName}")
        }
    }

    /**
     * True once the daemon's copy is merged (or it has none). Only an unreachable or failing daemon returns
     * false; any other answer is final, so a broken status route can't block reporting for good.
     */
    private fun ensureRestored(): Boolean {
        if (restored) return true
        if (GoogleMapsBudget.appContext() == null) return false
        val req = Request.Builder().url(STATUS_URL).get().build()
        try {
            http.newCall(req).execute().use { resp ->
                if (resp.code >= 500) {
                    Log.w(TAG, "GET status -> HTTP ${resp.code}")
                    return false
                }
                if (resp.isSuccessful) {
                    val usage = try {
                        JSONObject(resp.body?.string().orEmpty()).optJSONObject("usage")
                    } catch (_: Exception) {
                        null
                    } catch (_: StackOverflowError) {
                        null
                    }
                    if (usage != null) GoogleMapsBudget.mergeSavedCopy(usage)
                } else {
                    Log.w(TAG, "GET status -> HTTP ${resp.code}; usage not restored")
                }
            }
        } catch (e: IOException) {
            return false
        }
        restored = true
        return true
    }

    private fun fingerprint(s: JSONObject): String =
        "${s.optString("dayKey")}|${s.optString("monthKey")}|${s.optJSONObject("day")}|" +
            "${s.optJSONObject("month")}|${s.optJSONObject("latches")}"
}
