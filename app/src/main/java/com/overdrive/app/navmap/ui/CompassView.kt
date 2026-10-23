package com.overdrive.app.navmap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import com.overdrive.app.overlay.glass.GlassColor
import kotlin.math.abs

/**
 * The compass orb (SPEC §6): the needle turns by −camera bearing round (32, 32). Heading-up shows a quiet outline
 * needle; north-up a filled, faceted one with an accent "N" and ring. Redraws only for a ≥ 0.4° change.
 */
class CompassView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : HudPanelView(context, attrs) {

    override val shell = HudShell(HudShadows.PANEL).apply { tint = 0.92f; radiusTop = SIZE / 2f; radiusBottom = SIZE / 2f }
    override val round get() = true

    private var rotationDeg = 0f
    private var northUp = false

    private val north = Path().apply { moveTo(32f, 18.5f); lineTo(38f, 32f); lineTo(26f, 32f); close() }
    private val south = Path().apply { moveTo(26f, 32f); lineTo(32f, 45.5f); lineTo(38f, 32f) }
    private val northL = Path().apply { moveTo(32f, 18.5f); lineTo(26f, 32f); lineTo(32f, 32f); close() }
    private val northR = Path().apply { moveTo(32f, 18.5f); lineTo(38f, 32f); lineTo(32f, 32f); close() }
    private val southL = Path().apply { moveTo(26f, 32f); lineTo(32f, 45.5f); lineTo(32f, 32f); close() }
    private val southR = Path().apply { moveTo(38f, 32f); lineTo(32f, 45.5f); lineTo(32f, 32f); close() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val crest = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; strokeCap = Paint.Cap.ROUND }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.4f }
    private val pivotFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pivotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f }
    private val nPaint = HudType.paint(context, 700, 11.5f).apply { textAlign = Paint.Align.CENTER }

    private var red = 0
    private var southColor = 0
    private var northFillOutline = 0
    private var northFacetL = 0
    private var northFacetR = 0
    private var southFacetL = 0
    private var southFacetR = 0
    private var accent = 0

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        applyColors()
    }

    /** Returns true when it will redraw. */
    internal fun set(bearingDeg: Double, northUp: Boolean): Boolean {
        val rot = (((-bearingDeg % 360.0) + 540.0) % 360.0 - 180.0).toFloat()
        var d = abs(rot - rotationDeg)
        if (d > 180f) d = 360f - d
        if (northUp == this.northUp && d < 0.4f) return false
        val modeChanged = northUp != this.northUp
        rotationDeg = rot
        this.northUp = northUp
        if (modeChanged) applyColors()
        invalidate()
        return true
    }

    override fun computePanel() { panelW = SIZE; panelH = SIZE }

    override fun onThemeChanged() = applyColors()

    private fun applyColors() {
        val t = theme
        red = t.glowDanger
        southColor = if (t.night) GlassColor.alpha(t.text, 0.62f) else GlassColor.alpha(t.ink, 0.5f)
        northFillOutline = GlassColor.alpha(red, 0.22f)
        northFacetL = GlassColor.lighten(red, 0.18f)
        northFacetR = GlassColor.darken(red, 0.2f)
        southFacetL = GlassColor.lighten(southColor, 0.1f)
        southFacetR = GlassColor.darken(southColor, 0.15f)
        accent = if (t.night) t.glowAccent else t.accent
        ring.color = accent
        crest.color = GlassColor.alpha(HudColors.WHITE, 0.7f)
        pivotFill.color = if (t.night) t.glassBot else HudColors.WHITE
        pivotRing.color = t.text2
        nPaint.color = if (northUp) accent else t.text2
    }

    override fun drawContent(c: Canvas) {
        if (northUp) c.drawCircle(32f, 32f, 29.4f, ring)
        c.save()
        c.rotate(rotationDeg, 32f, 32f)
        c.drawText("N", 32f, 15.2f, nPaint)
        if (northUp) {
            fill.color = northFacetL; c.drawPath(northL, fill)
            fill.color = northFacetR; c.drawPath(northR, fill)
            fill.color = southFacetL; c.drawPath(southL, fill)
            fill.color = southFacetR; c.drawPath(southR, fill)
            c.drawLine(32f, 19.5f, 32f, 31f, crest)
        } else {
            fill.color = northFillOutline; c.drawPath(north, fill)
            stroke.color = red; c.drawPath(north, stroke)
            stroke.color = southColor; c.drawPath(south, stroke)
        }
        c.drawCircle(32f, 32f, 2.3f, pivotFill)
        c.drawCircle(32f, 32f, 2.3f, pivotRing)
        c.restore()
    }

    companion object {
        const val SIZE = 64f
    }
}
