package com.overdrive.app.navmap.ui

import android.content.Context
import android.content.res.Configuration
import com.overdrive.app.R
import java.util.Locale

/** Loads [HudStrings] from strings_navmap_hud.xml and the app's unit strings. */
object HudRes {

    fun strings(c: Context): HudStrings {
        val r = c.resources
        val verbs = HudVerb.entries.size
        // A translation with a wrong item count falls back to English rather than misaligning verbs.
        val english by lazy {
            val cfg = Configuration(r.configuration).apply { setLocale(Locale.ENGLISH) }
            c.createConfigurationContext(cfg).resources
        }
        fun array(id: Int, size: Int): Array<String> {
            val a = r.getStringArray(id)
            return if (a.size == size) a else english.getStringArray(id)
        }
        return HudStrings(
            bare = array(R.array.nav_hud_verbs_bare, verbs),
            onto = array(R.array.nav_hud_verbs_onto, verbs),
            ordinals = r.getStringArray(R.array.nav_hud_ordinals),
            roundaboutExitBare = c.getString(R.string.nav_hud_roundabout_exit),
            roundaboutExitOnto = c.getString(R.string.nav_hud_roundabout_exit_onto),
            towards = c.getString(R.string.nav_hud_towards),
            exit = c.getString(R.string.nav_hud_exit),
            then = c.getString(R.string.nav_hud_then),
            arrive = c.getString(R.string.nav_hud_arrive),
            recenter = c.getString(R.string.nav_hud_recenter),
            end = c.getString(R.string.nav_hud_end),
            delay = c.getString(R.string.nav_hud_delay),
            unitM = c.getString(R.string.nav_hud_unit_m),
            unitKm = c.getString(R.string.nav_hud_unit_km),
            unitFt = c.getString(R.string.nav_hud_unit_ft),
            unitMi = c.getString(R.string.nav_hud_unit_mi),
            unitMin = c.getString(R.string.unit_minute),
            unitH = c.getString(R.string.unit_hour_compact),
            unitKmh = c.getString(R.string.nav_hud_unit_kmh),
            unitMph = c.getString(R.string.nav_hud_unit_mph),
            attribution = c.getString(R.string.nav_hud_attribution),
            cdMute = c.getString(R.string.nav_hud_cd_mute),
            cdUnmute = c.getString(R.string.nav_hud_cd_unmute),
            cdOverview = c.getString(R.string.nav_hud_cd_overview),
            cdOverviewExit = c.getString(R.string.nav_hud_cd_overview_exit),
            cdEnd = c.getString(R.string.nav_hud_cd_end),
            cdRecenter = c.getString(R.string.nav_hud_cd_recenter),
            cdCompassHeadingUp = c.getString(R.string.nav_hud_cd_compass_heading_up),
            cdCompassNorthUp = c.getString(R.string.nav_hud_cd_compass_north_up),
            cdBack = c.getString(R.string.nav_hud_cd_back),
            cdThen = c.getString(R.string.nav_hud_cd_then),
            cdTrip = c.getString(R.string.nav_hud_cd_trip),
            cdDelay = c.getString(R.string.nav_hud_cd_delay),
        )
    }
}
