package com.overdrive.app.setup

import android.os.Build
import androidx.annotation.WorkerThread
import com.overdrive.app.camera.dilink5.DiLink5Platform
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.config.VehicleModelSelection
import com.overdrive.app.util.DaemonHttpClient
import com.overdrive.app.util.ScratchRoot
import org.json.JSONObject
import java.io.File

/** Everything the vehicle setup card shows, captured in one pass. */
data class VehicleSetupSnapshot(
    val vehicleType: String?,
    val manufacturer: String,
    val model: String,
    val product: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val di5CameraLibrary: Boolean,
    val aisClientVisible: Boolean,
    val bmmCameraJar: Boolean,
    val storageDir: String,
    /** Shell-owned marker that sends env-less daemon launches to [ScratchRoot.ALT_DIR]. */
    val altMarkerPresent: Boolean,
    /** Normalized camera.cameraMode; null when unset or unrecognized. */
    val configuredCameraMode: String?,
    /** Mode fenced into this app process at class load; can lag the camera service. */
    val appActiveCameraMode: String,
    /** camera.dilink5CameraMapping; "" is Auto, null is absent. */
    val dilink5Mapping: String?,
    /** Resolved selection; null while the owner has not picked a model. */
    val modelId: String?,
    val modelSource: String,
    val nominalKwh: Double?,
    /** The camera service answered at all. */
    val serviceReachable: Boolean,
    /** Mode the camera service reports as active; null when it did not answer 200. */
    val runningCameraMode: String?,
    val detection: VehicleSetupDetector.Detection,
) {
    val alternateStorage: Boolean get() = storageDir != ScratchRoot.LEGACY_DIR
    /** App on the legacy root while env-less daemon launches resolve the alternate one. */
    val storageSplit: Boolean get() = !alternateStorage && altMarkerPresent
}

/**
 * No ADB, no daemon control, no setting of its own. Config is read through loadConfig()
 * like the Diagnostics camera tile; the one GET uses DaemonHttpClient like the battery tile.
 */
object VehicleSetupProbe {
    private const val VEHICLE_TYPE_PROPERTY = "ro.vehicle.type"
    private const val AIS_CLIENT_PATH = "/vendor/lib64/libais_client.so"
    private const val BMM_CAMERA_JAR_PATH = "/system/framework/bmmcamera.jar"
    private const val STATUS_PATH = "/api/vehicle/cluster-mirror-status"

    @WorkerThread
    fun collect(): VehicleSetupSnapshot {
        val vehicleType = readSystemProperty(VEHICLE_TYPE_PROPERTY)
        val di5Library = guard(false) { DiLink5Platform.hasDiLink5CameraHardware() }
        val detection = VehicleSetupDetector.detect(
            VehicleSetupDetector.Evidence(vehicleType, Build.MODEL, Build.PRODUCT, Build.DEVICE, di5Library)
        )

        val config = try { UnifiedConfigManager.loadConfig() } catch (_: Throwable) { null }
        val camera = config?.optJSONObject("camera")
        val vehicle = config?.optJSONObject("vehicle")
        val rawModelId = stringOrNull(vehicle, "modelId")
        val rawSource = stringOrNull(vehicle, "modelSource")
        val kwh = vehicle?.optDouble("nominalKwh", Double.NaN) ?: Double.NaN

        var reachable = false
        var running: String? = null
        try {
            val conn = DaemonHttpClient.open(STATUS_PATH, "GET", 1500, 2000)
            try {
                val code = conn.responseCode
                reachable = code > 0
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    running = guard(null) {
                        DiLink5Platform.normalizeConfiguredMode(
                            stringOrNull(JSONObject(body), "vehicleMode")
                        )
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Throwable) {
            // No answer (refused, timed out, bad body): mode stays unknown.
        }

        return VehicleSetupSnapshot(
            vehicleType = vehicleType,
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            product = Build.PRODUCT ?: "",
            device = Build.DEVICE ?: "",
            androidRelease = Build.VERSION.RELEASE ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            di5CameraLibrary = di5Library,
            aisClientVisible = isFile(AIS_CLIENT_PATH),
            bmmCameraJar = isFile(BMM_CAMERA_JAR_PATH),
            storageDir = guard(ScratchRoot.LEGACY_DIR) { ScratchRoot.dir() },
            altMarkerPresent = isFile(ScratchRoot.ALT_MARKER),
            configuredCameraMode = guard(null) {
                DiLink5Platform.normalizeConfiguredMode(stringOrNull(camera, "cameraMode"))
            },
            appActiveCameraMode = guard("default") { DiLink5Platform.currentActiveMode() },
            dilink5Mapping = stringOrNull(camera, "dilink5CameraMapping"),
            modelId = VehicleModelSelection.resolvedModelId(rawModelId, rawSource),
            modelSource = VehicleModelSelection.normalizeSource(rawSource, rawModelId),
            nominalKwh = kwh.takeIf { it.isFinite() && it > 0.0 },
            serviceReachable = reachable,
            runningCameraMode = running,
            detection = detection,
        )
    }

    private inline fun <T> guard(fallback: T, block: () -> T): T = try {
        block()
    } catch (_: Throwable) {
        fallback
    }

    private fun stringOrNull(json: JSONObject?, key: String): String? {
        if (json == null || !json.has(key) || json.isNull(key)) return null
        return json.opt(key) as? String
    }

    private fun isFile(path: String): Boolean = try {
        File(path).isFile
    } catch (_: Throwable) {
        false
    }

    private fun readSystemProperty(key: String): String? = try {
        val properties = Class.forName("android.os.SystemProperties")
        val value = properties.getMethod("get", String::class.java).invoke(null, key) as? String
        value?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }
}
