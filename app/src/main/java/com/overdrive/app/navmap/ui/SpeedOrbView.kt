package com.overdrive.app.navmap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import kotlin.math.roundToInt

/** The current-speed orb (SPEC §6): opaque glass, so no map label ghosts behind the digits. */
class SpeedOrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : HudPanelView(context, attrs) {

    override val shell = HudShell(HudShadows.PANEL).apply { tint = 1f; radiusTop = SIZE / 2f; radiusBottom = SIZE / 2f }
    override val round get() = true

    private val valuePaint = HudType.paint(context, 700, 44f).apply { textAlign = Paint.Align.CENTER }
    private val unitPaint = HudType.paint(context, 600, 15f, 0.02f).apply { textAlign = Paint.Align.CENTER }
    private var labels: HudLabels? = null
    private var mps: Double? = null
    private var imperial = false
    private var shown = Int.MIN_VALUE
    private var value = ""
    private var unit = ""

    init { applyColors() }

    internal fun setup(labels: HudLabels) { this.labels = labels; unit = labels.s.unitKmh }

    /** Whole km/h or mph; null shows a dash. */
    internal fun bind(mps: Double?, imperial: Boolean): Boolean {
        val l = labels ?: return false
        this.mps = mps; this.imperial = imperial
        val v = if (mps == null || mps.isNaN()) -1 else (mps.coerceAtLeast(0.0) * (if (imperial) MPH else KMH)).roundToInt()
        val key = if (imperial) v or (1 shl 30) else v
        if (key == shown) return false
        shown = key
        value = if (v < 0) "–" else v.toString()
        unit = if (imperial) l.s.unitMph else l.s.unitKmh
        invalidate()
        return true
    }

    internal fun setImperial(imperial: Boolean) { if (imperial != this.imperial) bind(mps, imperial) }

    override fun computePanel() { panelW = SIZE; panelH = SIZE }

    override fun onThemeChanged() = applyColors()

    private fun applyColors() {
        valuePaint.color = theme.text
        unitPaint.color = theme.text2
    }

    override fun drawContent(c: Canvas) {
        val vh = 44f * 0.9f
        val uh = HudType.normal(15f)
        val top = (SIZE - (vh + 3f + uh)) / 2f
        c.drawText(value, SIZE / 2f, HudType.baseline(valuePaint, top, vh), valuePaint)
        c.drawText(unit, SIZE / 2f, HudType.baseline(unitPaint, top + vh + 3f, uh), unitPaint)
    }

    companion object {
        const val SIZE = 96f
        private const val KMH = 3.6
        private const val MPH = 2.2369362920544
    }
}
