package com.overdrive.app.navmap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import com.overdrive.app.overlay.glass.GlassColor
import com.overdrive.app.overlay.glass.GlassFonts
import kotlin.math.max

/** The "Then" tab hanging under the card (SPEC §6): label, the next turn's 44 dp glyph (glow × .55), its distance. */
class ThenTabView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : HudPanelView(context, attrs) {

    override val shell = HudShell(HudShadows.PANEL).apply { tint = 0.92f; radiusTop = 0f; radiusBottom = 24f }

    private var labels: HudLabels? = null
    private val labelPaint = HudType.paint(context, 500, 21f)
    private val valuePaint = HudType.paint(context, 600, 34f)
    private val unitPaint = HudType.paint(context, 500, 19f)
    private val labelFace = GlassFonts.get(context, 700)
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glyphDst = RectF()

    private var next: HudManeuver? = null
    private var spec: GlyphSpec? = null
    private var sprite: GlyphSprite? = null
    private var gap = Double.NaN
    private var imperial = false
    private var key = Long.MIN_VALUE
    private var label = ""
    private var labelW = 0f
    private var num = ""
    private var numW = 0f
    private var unit = ""
    private var unitW = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        applyColors()
    }

    internal fun setup(labels: HudLabels) {
        this.labels = labels
        label = labels.s.then
        labelW = labelPaint.measureText(label)
    }

    /** Returns false when nothing shown changed. */
    internal fun bind(m: HudManeuver?, gapMeters: Double, imperial: Boolean): Boolean {
        val l = labels ?: return false
        val k = HudFormat.distanceKey(gapMeters, imperial)
        if (m == next && k == key) return false
        val widthBefore = panelW
        next = m; gap = gapMeters; this.imperial = imperial; key = k
        if (m == null) { contentDescription = null; return true }
        val s = GlyphSpec.of(m)
        if (s != spec) { spec = s; sprite = null }
        num = HudFormat.number(k)
        unit = l.distanceUnit(HudFormat.unit(k))
        numW = valuePaint.measureText(num)
        unitW = unitPaint.measureText(unit)
        contentDescription = String.format(l.s.cdThen, l.verb(m, false), "$num $unit")
        computePanel()
        if (panelW != widthBefore) requestLayout()
        invalidate()
        return true
    }

    internal fun setImperial(imperial: Boolean) {
        if (imperial != this.imperial && next != null) bind(next, gap, imperial) else this.imperial = imperial
    }

    override fun computePanel() {
        panelH = TAB_H
        val valueX = PAD_X + labelW + GAP + GLYPH + GAP
        panelW = max(TAB_W, valueX + numW + UNIT_GAP + unitW + PAD_X)
    }

    override fun onThemeChanged() { sprite = null; applyColors() }
    override fun onScaleChanged() { sprite = null }

    private fun applyColors() {
        labelPaint.color = theme.text2
        valuePaint.color = theme.text
        unitPaint.color = GlassColor.alpha(theme.text, 0.5f)
    }

    override fun drawContent(c: Canvas) {
        val s = spec ?: return
        val bottom = TAB_H - PAD_B
        c.drawText(label, PAD_X, HudType.baseline(labelPaint, bottom - 6f - HudType.normal(21f), HudType.normal(21f)), labelPaint)
        val gx = PAD_X + labelW + GAP
        val sp = sprite ?: GlyphSprites.get(s, GLYPH, u, theme, 0.55f, labelFace).also { sprite = it }
        val gy = bottom - GLYPH
        glyphDst.set(gx - sp.padDp, gy - sp.padDp, gx - sp.padDp + sp.boxDp, gy - sp.padDp + sp.boxDp)
        c.drawBitmap(sp.bitmap, null, glyphDst, spritePaint)
        val vx = gx + GLYPH + GAP
        val base = HudType.baseline(valuePaint, bottom - 3f - 34f, 34f)
        c.drawText(num, vx, base, valuePaint)
        c.drawText(unit, vx + numW + UNIT_GAP, base, unitPaint)
    }

    companion object {
        const val TAB_W = 232f
        const val TAB_H = 78f
        /** How far the tab's top tucks under the card. */
        const val TUCK = 20f
        private const val PAD_X = 20f
        private const val PAD_B = 13f
        private const val GAP = 10f
        private const val GLYPH = 44f
        private const val UNIT_GAP = 19f * 0.14f
    }
}
