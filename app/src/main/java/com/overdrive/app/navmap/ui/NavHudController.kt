package com.overdrive.app.navmap.ui

import android.graphics.drawable.Drawable
import android.view.View
import com.overdrive.app.R

/**
 * The glass navigation HUD (res/layout/nav_hud.xml): binds guidance data to the panels and reports key taps.
 * Main thread only. Binds are cheap no-ops when nothing shown changes.
 */
class NavHudController(private val root: NavHudLayout, private val listener: Listener) {

    interface Listener {
        fun onEnd()
        fun onOverview()
        fun onMute()
        fun onRecenter()
        fun onCompass()
        fun onBack()
    }

    private val context = root.context
    private val labels = HudLabels(HudRes.strings(context))

    private val haze: TopHazeView = root.findViewById(R.id.hudHaze)
    private val then: ThenTabView = root.findViewById(R.id.hudThen)
    private val card: ManeuverCardView = root.findViewById(R.id.hudCard)
    private val compass: CompassView = root.findViewById(R.id.hudCompass)
    private val back: HudOrbKeyView = root.findViewById(R.id.hudBack)
    private val speed: SpeedOrbView = root.findViewById(R.id.hudSpeed)
    private val trip: TripBarView = root.findViewById(R.id.hudTrip)
    private val recenter: RecenterPillView = root.findViewById(R.id.hudRecenter)
    private val attribution: AttributionPlateView = root.findViewById(R.id.hudAttribution)

    private val volumeIcon = icon(R.drawable.ic_hud_volume)
    private val mutedIcon = icon(R.drawable.ic_hud_muted)

    private var night = true
    private var cluster = false
    private var imperial = false
    private var guidance = false
    private var maneuver: HudManeuver? = null
    private var next: HudManeuver? = null
    private var northUp = false
    private var showAttribution = true

    init {
        card.setup(labels, icon(R.drawable.ic_hud_towards))
        then.setup(labels)
        speed.setup(labels)
        trip.setup(labels, volumeIcon, icon(R.drawable.ic_hud_overview), icon(R.drawable.ic_hud_end))
        recenter.setup(labels.s.recenter, icon(R.drawable.ic_hud_recenter), labels.s.cdRecenter)
        back.setup(icon(R.drawable.ic_hud_back), labels.s.cdBack)
        attribution.setText(labels.s.attribution)
        compass.contentDescription = labels.s.cdCompassHeadingUp
        trip.muteKey.setOnClickListener { listener.onMute() }
        trip.overviewKey.setOnClickListener { listener.onOverview() }
        trip.endKey.setOnClickListener { listener.onEnd() }
        recenter.setOnClickListener { listener.onRecenter() }
        compass.setOnClickListener { listener.onCompass() }
        back.setOnClickListener { listener.onBack() }
        applyNight()
    }

    fun setNight(night: Boolean) {
        if (this.night == night) return
        this.night = night
        applyNight()
    }

    /** The non-touch driver display: card, Then tab and a compact trip summary only. */
    fun setCluster(cluster: Boolean) {
        if (this.cluster == cluster) return
        this.cluster = cluster
        root.cluster = cluster
        trip.compact = cluster
        applyChrome()
    }

    fun setImperial(imperial: Boolean) {
        if (this.imperial == imperial) return
        this.imperial = imperial
        card.setImperial(imperial)
        then.setImperial(imperial)
        trip.setImperial(imperial)
        speed.setImperial(imperial)
    }

    /** Vector basemaps print the OSM attribution on the HUD; another basemap supplies its own. */
    fun setAttributionVisible(visible: Boolean) {
        if (showAttribution == visible) return
        showAttribution = visible
        applyChrome()
    }

    fun setGuidanceVisible(visible: Boolean) {
        if (guidance == visible) return
        guidance = visible
        if (visible) {
            root.visibility = View.VISIBLE
            applyChrome()
            if (maneuver != null) card.showAnimated(true)
            if (next != null) then.showAnimated(true)
        } else {
            card.hideNow(); then.hideNow(); recenter.hideNow()
            root.visibility = View.GONE
        }
    }

    fun bindManeuver(m: HudManeuver?) {
        maneuver = m
        card.bind(m)
        if (!guidance) return
        if (m == null) card.showAnimated(false) else card.showAnimated(true)
    }

    /** ≤ 4 Hz: distance to the maneuver on the card. */
    fun bindDistance(meters: Double) {
        card.bindDistance(meters, imperial)
    }

    /** The next maneuver after the card's, [gapMeters] beyond it; null hides the tab. */
    fun bindThen(next: HudManeuver?, gapMeters: Double) {
        this.next = next
        if (next != null) then.bind(next, gapMeters, imperial)
        if (guidance) then.showAnimated(next != null && maneuver != null)
    }

    fun bindTrip(arriveAtEpochMs: Long, remainingS: Double, remainingM: Double, delayS: Double?) {
        trip.bind(arriveAtEpochMs, remainingS, remainingM, delayS, imperial)
    }

    fun bindSpeed(mps: Double?) {
        speed.bind(mps, imperial)
    }

    fun bindMuted(muted: Boolean) {
        trip.setMuted(muted, if (muted) mutedIcon else volumeIcon)
    }

    /** The driver panned away during guidance: offer the re-centre pill. */
    fun setFree(free: Boolean) {
        if (cluster) return
        if (free) recenter.setProgress(0f)
        recenter.showAnimated(free && guidance)
    }

    /** Auto-recentre countdown, 0 → 1. */
    fun setFreeProgress(fraction: Float) {
        recenter.setProgress(fraction.coerceIn(0f, 1f))
    }

    fun setCompass(bearingDeg: Double, northUp: Boolean) {
        compass.set(bearingDeg, northUp)
        if (this.northUp != northUp) {
            this.northUp = northUp
            compass.contentDescription = if (northUp) labels.s.cdCompassNorthUp else labels.s.cdCompassHeadingUp
        }
    }

    fun setOverview(active: Boolean) {
        trip.setOverview(active)
    }

    private fun applyNight() {
        haze.setNight(night)
        then.setNight(night); card.setNight(night); compass.setNight(night); back.setNight(night)
        speed.setNight(night); trip.setNight(night); recenter.setNight(night); attribution.setNight(night)
    }

    private fun applyChrome() {
        val touch = if (cluster) View.GONE else View.VISIBLE
        haze.visibility = touch
        compass.visibility = touch
        back.visibility = touch
        speed.visibility = touch
        attribution.visibility = if (!cluster && showAttribution) View.VISIBLE else View.GONE
        if (cluster) recenter.hideNow()
    }

    private fun icon(id: Int): Drawable = context.getDrawable(id)!!.mutate()
}
