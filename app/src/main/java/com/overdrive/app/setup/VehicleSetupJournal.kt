package com.overdrive.app.setup

import android.content.Context

/**
 * An accepted vehicle setup that has not finished applying. App-private prefs, written with
 * commit() because the storage switch ends the process right after it succeeds.
 */
data class VehicleSetupJournal(
    /** Null when the car is not listed: the configured model is left as it is. */
    val modelId: String?,
    val cameraMode: String,
    val storage: VehicleSetupPlan.Storage,
    val fromOnboarding: Boolean,
) {
    /** A new journal is a new attempt: attempt markers from an earlier run are dropped. */
    fun save(context: Context): Boolean {
        dismissedInProcess = false
        return prefs(context).edit()
            .clear()
            .putBoolean(KEY_PENDING, true)
            .putString(KEY_MODEL, modelId)
            .putString(KEY_MODE, cameraMode)
            .putString(KEY_STORAGE, storage.name)
            .putBoolean(KEY_ONBOARDING, fromOnboarding)
            .commit()
    }

    companion object {
        private const val PREFS = "overdrive_vehicle_setup"
        private const val KEY_PENDING = "pending"
        private const val KEY_MODEL = "model_id"
        private const val KEY_MODE = "camera_mode"
        private const val KEY_STORAGE = "storage"
        private const val KEY_ONBOARDING = "from_onboarding"
        private const val KEY_STORAGE_ATTEMPTED_AT = "storage_attempted_at"
        private const val KEY_DEFERRED = "deferred"
        // Durable state outside the journal: a Close or a new plan must not erase it.
        private const val STATE_PREFS = "overdrive_vehicle_setup_state"
        private const val KEY_RESTART_OWED = "camera_restart_owed"

        // A failed clear() commit must not reopen the assistant again in this process.
        @Volatile private var dismissedInProcess = false

        fun load(context: Context): VehicleSetupJournal? {
            if (dismissedInProcess) return null
            val p = prefs(context)
            if (!p.getBoolean(KEY_PENDING, false)) return null
            val model = p.getString(KEY_MODEL, null)
            val mode = p.getString(KEY_MODE, null) ?: return null
            val storage = VehicleSetupPlan.Storage.values()
                .firstOrNull { it.name == p.getString(KEY_STORAGE, null) }
                ?: VehicleSetupPlan.Storage.NONE
            return VehicleSetupJournal(model, mode, storage, p.getBoolean(KEY_ONBOARDING, false))
        }

        fun isPending(context: Context): Boolean = load(context) != null

        fun clear(context: Context) {
            dismissedInProcess = true
            prefs(context).edit().clear().commit()
        }

        /** Set right after a successful switch; a resumed run that still sees legacy stops. */
        fun markStorageAttempted(context: Context): Boolean =
            prefs(context).edit()
                .putLong(KEY_STORAGE_ATTEMPTED_AT, System.currentTimeMillis()).commit()

        fun storageAttempted(context: Context): Boolean =
            prefs(context).getLong(KEY_STORAGE_ATTEMPTED_AT, 0L) > 0L

        /** True only within [windowMs] after the switch, i.e. the relaunch it caused. */
        fun storageAttemptedWithin(context: Context, windowMs: Long): Boolean {
            val at = prefs(context).getLong(KEY_STORAGE_ATTEMPTED_AT, 0L)
            val age = System.currentTimeMillis() - at
            return at > 0L && age in 0..windowMs
        }

        /** The car switched on mid-run: the next start asks before continuing. */
        fun markDeferred(context: Context): Boolean =
            prefs(context).edit().putBoolean(KEY_DEFERRED, true).commit()

        fun deferred(context: Context): Boolean = prefs(context).getBoolean(KEY_DEFERRED, false)

        fun clearDeferred(context: Context): Boolean =
            prefs(context).edit().remove(KEY_DEFERRED).commit()

        /** A camera restart an applied model still needs; cleared only when one is confirmed. */
        fun setRestartOwed(context: Context, owed: Boolean): Boolean =
            statePrefs(context).edit().putBoolean(KEY_RESTART_OWED, owed).commit()

        fun restartOwed(context: Context): Boolean =
            statePrefs(context).getBoolean(KEY_RESTART_OWED, false)

        private fun statePrefs(context: Context) =
            context.applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
