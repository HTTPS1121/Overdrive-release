package com.overdrive.app.navmap.ui

import com.overdrive.app.navmap.nav.RouteLane
import com.overdrive.app.navmap.nav.RouteManeuver

/** One maneuver as the HUD shows it. Bearings are -1 when unknown; [roundaboutExit] is 0 when not a roundabout. */
data class HudManeuver(
    val type: Int,
    val bearingBefore: Int,
    val bearingAfter: Int,
    val roundaboutExit: Int,
    val instruction: String,
    val streetNames: List<String>,
    val towards: List<String>,
    val exitNumber: String,
    val lanes: List<RouteLane>,
    val leftHandTraffic: Boolean
) {
    companion object {
        fun of(m: RouteManeuver, leftHandTraffic: Boolean) = HudManeuver(
            m.type, m.bearingBefore, m.bearingAfter, m.roundaboutExitCount, m.instruction,
            m.streetNames, m.towards, m.exitNumber, m.lanes, leftHandTraffic
        )

        /**
         * maneuvers[index] for the HUD. A roundabout's enter (26) and exit (27) share one glyph: the turn runs from
         * the enter's bearing_before to the exit's bearing_after, and the enter shows the exit road's names and signs.
         */
        fun of(maneuvers: List<RouteManeuver>, index: Int, leftHandTraffic: Boolean): HudManeuver {
            val m = maneuvers[index]
            val base = of(m, leftHandTraffic)
            when (m.type) {
                ROUNDABOUT_ENTER -> {
                    val exit = (index + 1 until maneuvers.size).firstOrNull { maneuvers[it].type == ROUNDABOUT_EXIT }
                        ?.let { maneuvers[it] } ?: return base
                    return base.copy(
                        bearingAfter = if (exit.bearingAfter >= 0) exit.bearingAfter else m.bearingAfter,
                        streetNames = exit.streetNames.ifEmpty { m.streetNames },
                        towards = exit.towards.ifEmpty { m.towards },
                        exitNumber = exit.exitNumber.ifEmpty { m.exitNumber },
                        lanes = m.lanes.ifEmpty { exit.lanes }
                    )
                }
                ROUNDABOUT_EXIT -> {
                    val enter = (index - 1 downTo 0).firstOrNull { maneuvers[it].type == ROUNDABOUT_ENTER }
                        ?.let { maneuvers[it] } ?: return base
                    return base.copy(
                        bearingBefore = if (enter.bearingBefore >= 0) enter.bearingBefore else m.bearingBefore,
                        roundaboutExit = if (m.roundaboutExitCount > 0) m.roundaboutExitCount else enter.roundaboutExitCount
                    )
                }
            }
            return base
        }

        internal const val ROUNDABOUT_ENTER = 26
        internal const val ROUNDABOUT_EXIT = 27
    }
}
