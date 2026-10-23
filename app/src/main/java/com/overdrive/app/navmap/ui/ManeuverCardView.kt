package com.overdrive.app.navmap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import com.overdrive.app.overlay.glass.GlassColor
import com.overdrive.app.overlay.glass.GlassFonts
import com.overdrive.app.overlay.glass.unitOval
import kotlin.math.min

/**
 * The maneuver card (SPEC §6): angle-true glass turn glyph, distance, verb, street, the Towards / exit line and the
 * recessed lane tray. Text is prepared on bind; a draw only blits the glyph sprite and draws prepared strings.
 */
class ManeuverCardView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : HudPanelView(context, attrs) {

    override val shell = HudShell(HudShadows.PANEL).apply { tint = 0.9f; radiusTop = 28f; radiusBottom = 28f }

    private var labels: HudLabels? = null
    private var towardsIcon: Drawable? = null

    private val distPaint = HudType.paint(context, 600, 78f, -0.01f)
    private val unitPaint = HudType.paint(context, 500, 36f)
    private val verbPaint = HudType.paint(context, 500, 21f, 0.005f)
    private val streetPaint = HudType.paint(context, 700, 40f, -0.005f)
    private val towPaint = HudType.paint(context, 500, 21f)
    private val towBoldPaint = HudType.paint(context, 600, 21f)
    private val labelFace = GlassFonts.get(context, 700)
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glyphDst = RectF()

    private var maneuver: HudManeuver? = null
    private var spec: GlyphSpec? = null
    private var sprite: GlyphSprite? = null

    private var meters = Double.NaN
    private var imperial = false
    private var distKey = Long.MIN_VALUE
    private var distNum = ""
    private var distUnit = ""
    private var distNumW = 0f

    private var verb: CharSequence = ""
    private var street: CharSequence = ""
    private var towLead = ""
    private var towWord = ""
    private var towNames: CharSequence = ""
    private var towLeadW = 0f
    private var towWordW = 0f

    // lanes, left to right: every arrow drawn, the lit arrows, and 0 dim / 1 usable / 2 best
    private var laneCount = 0
    private val laneDirs = IntArray(MAX_LANES)
    private val laneLit = IntArray(MAX_LANES)
    private val laneLevel = IntArray(MAX_LANES)
    private var lht = false

    // rows in dp from the glass top
    private var showStreet = false
    private var showTowards = false
    private var showLanes = false
    private var streetBase = 0f
    private var towardsTop = 0f
    private var trayTop = 0f

    private val trayRect = RectF()
    private val trayPath = Path()
    private val trayEdge = Path()
    private val trayMoved = Path()
    private val trayFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trayShade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trayLine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = Paint()
    private val pool = Paint(Paint.ANTI_ALIAS_FLAG)
    private var poolShader: RadialGradient? = null
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markRect = RectF()
    private val laneStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5.2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val laneHead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE; strokeWidth = 1.6f; strokeJoin = Paint.Join.ROUND
    }
    private var trayPrepared = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        applyColors()
    }

    internal fun setup(labels: HudLabels, towardsIcon: Drawable) {
        this.labels = labels
        this.towardsIcon = towardsIcon
    }

    /** Returns false when [m] equals what is already shown. */
    internal fun bind(m: HudManeuver?): Boolean {
        if (m == maneuver) return false
        maneuver = m
        val l = labels
        if (m == null || l == null) { contentDescription = null; invalidate(); return true }
        val newSpec = GlyphSpec.of(m)
        if (newSpec != spec) { spec = newSpec; sprite = null }
        verb = ellipsize(l.verb(m), verbPaint, TEXT_COL_W)
        val main = l.mainLine(m)
        street = if (main == null) "" else ellipsize(main, streetPaint, INNER_W)
        val exit = l.exitLabel(m)
        val names = l.towardsNames(m)
        towLead = if (exit.isNotEmpty() && names.isNotEmpty()) "$exit  ·  " else exit
        towWord = if (names.isNotEmpty()) l.s.towards + " " else ""
        towLeadW = towBoldPaint.measureText(towLead)
        towWordW = towPaint.measureText(towWord)
        val namesRoom = INNER_W - TOW_TEXT_X + PAD_X - towLeadW - towWordW
        towNames = if (names.isEmpty()) "" else ellipsize(names, towBoldPaint, namesRoom)
        lht = m.leftHandTraffic
        laneCount = min(m.lanes.size, MAX_LANES)
        var anyBest = false
        for (i in 0 until laneCount) if (m.lanes[i].active != 0) anyBest = true
        for (i in 0 until laneCount) {
            val lane = m.lanes[i]
            val lit = lane.active or lane.valid
            val dirs = (lane.directions or lit) and LaneGlyphs.ALL
            laneDirs[i] = if (dirs == 0) LaneGlyphs.NONE else dirs
            laneLit[i] = lit and LaneGlyphs.ALL
            laneLevel[i] = when {
                lit == 0 -> 0
                lane.active != 0 || !anyBest -> 2
                else -> 1
            }
        }
        contentDescription = buildString {
            append(l.verb(m))
            if (main != null) append(' ').append(main)
            if (exit.isNotEmpty()) append(". ").append(exit)
            if (names.isNotEmpty()) append(". ").append(l.s.towards).append(' ').append(names)
        }
        relayoutRows()
        invalidate()
        return true
    }

    /** ≤ 4 Hz from the frame loop; redraws only when the rounded text changes. */
    internal fun bindDistance(meters: Double, imperial: Boolean): Boolean {
        this.meters = meters; this.imperial = imperial
        val key = HudFormat.distanceKey(meters, imperial)
        if (key == distKey) return false
        distKey = key
        val l = labels ?: return false
        distNum = HudFormat.number(key)
        distUnit = l.distanceUnit(HudFormat.unit(key))
        distNumW = distPaint.measureText(distNum)
        invalidate()
        return true
    }

    internal fun setImperial(imperial: Boolean) {
        if (imperial != this.imperial && !meters.isNaN()) bindDistance(meters, imperial) else this.imperial = imperial
    }

    private fun relayoutRows() {
        val m = maneuver
        showStreet = street.isNotEmpty()
        showTowards = towLead.isNotEmpty() || towNames.isNotEmpty()
        showLanes = m != null && laneCount > 0
        var y = PAD_Y + ROW_H
        if (showStreet) {
            val top = y + STREET_GAP
            streetBase = HudType.baseline(streetPaint, top, STREET_LH)
            y = top + STREET_LH
        }
        if (showTowards) {
            towardsTop = y + TOW_GAP
            y = towardsTop + TOW_LH
        }
        if (showLanes) {
            trayTop = y + TRAY_GAP
            y = trayTop + TRAY_H
        }
        val h = y + PAD_Y
        trayPrepared = false
        if (h != panelH) requestLayout()
    }

    override fun computePanel() {
        panelW = CARD_W
        if (maneuver == null) { panelH = PAD_Y * 2f + ROW_H; return }
        var y = PAD_Y + ROW_H
        if (showStreet) y += STREET_GAP + STREET_LH
        if (showTowards) y += TOW_GAP + TOW_LH
        if (showLanes) y += TRAY_GAP + TRAY_H
        panelH = y + PAD_Y
    }

    override fun onThemeChanged() { sprite = null; trayPrepared = false; applyColors() }
    override fun onScaleChanged() { sprite = null }

    private fun applyColors() {
        val t = theme
        distPaint.color = t.text
        unitPaint.color = GlassColor.alpha(t.text, 0.5f)
        verbPaint.color = t.text2
        streetPaint.color = t.text
        towPaint.color = t.text2
        towBoldPaint.color = t.text
        towardsIcon?.setTint(t.text2)
        mark.color = t.glowAccent
        dash.color = GlassColor.alpha(t.text3, (t.text3 ushr 24) / 255f * 0.7f)
    }

    private fun prepareTray() {
        if (trayPrepared) return
        trayPrepared = true
        val t = theme
        trayRect.set(PAD_X, trayTop, PAD_X + INNER_W, trayTop + TRAY_H)
        trayPath.rewind()
        trayPath.addRoundRect(trayRect, TRAY_R, TRAY_R, Path.Direction.CW)
        trayFill.color = if (t.night) GlassColor.alpha(0xFF000000.toInt(), 0.32f) else GlassColor.alpha(t.ink, 0.07f)
        val shadeA = t.thickA * 0.9f
        trayShade.shader = LinearGradient(0f, trayTop, 0f, trayTop + 7f,
            intArrayOf(GlassColor.alpha(t.edgeDark, shadeA), GlassColor.alpha(t.edgeDark, shadeA * 0.55f), GlassColor.clear(t.edgeDark)),
            floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
        trayMoved.set(trayPath); trayMoved.offset(0f, -1f)
        trayEdge.set(trayPath); trayEdge.op(trayMoved, Path.Op.DIFFERENCE)
        trayLine.color = if (t.night) GlassColor.alpha(0xFFFFFFFF.toInt(), 0.07f) else GlassColor.alpha(0xFFFFFFFF.toInt(), 0.9f)
        poolShader = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.alpha(t.glowAccent, 0.34f), GlassColor.clear(t.glowAccent)),
            floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP)
    }

    private fun ensureSprite(): GlyphSprite? {
        val s = spec ?: return null
        sprite?.let { return it }
        return GlyphSprites.get(s, GLYPH, u, theme, 1f, labelFace).also { sprite = it }
    }

    override fun drawContent(c: Canvas) {
        if (maneuver == null) return
        val t = theme
        ensureSprite()?.let { s ->
            glyphDst.set(PAD_X - s.padDp, PAD_Y - s.padDp, PAD_X - s.padDp + s.boxDp, PAD_Y - s.padDp + s.boxDp)
            c.drawBitmap(s.bitmap, null, glyphDst, spritePaint)
        }
        val colTop = PAD_Y + (ROW_H - (DIST_LH + VERB_GAP + VERB_LH)) / 2f
        if (distNum.isNotEmpty()) {
            val base = HudType.baseline(distPaint, colTop, DIST_LH)
            c.drawText(distNum, TEXT_COL_X, base, distPaint)
            c.drawText(distUnit, TEXT_COL_X + distNumW + UNIT_GAP, base, unitPaint)
        }
        c.drawText(verb, 0, verb.length, TEXT_COL_X, HudType.baseline(verbPaint, colTop + DIST_LH + VERB_GAP, VERB_LH), verbPaint)
        if (showStreet) c.drawText(street, 0, street.length, PAD_X, streetBase, streetPaint)
        if (showTowards) {
            towardsIcon?.let { ic ->
                val iy = towardsTop + (TOW_LH - TOW_ICON) / 2f
                ic.setBounds(PAD_X.toInt(), iy.toInt(), (PAD_X + TOW_ICON).toInt(), (iy + TOW_ICON).toInt())
                ic.draw(c)
            }
            val base = HudType.baseline(towPaint, towardsTop, TOW_LH)
            var x = TOW_TEXT_X
            if (towLead.isNotEmpty()) { c.drawText(towLead, x, base, towBoldPaint); x += towLeadW }
            if (towWord.isNotEmpty()) { c.drawText(towWord, x, base, towPaint); x += towWordW }
            if (towNames.isNotEmpty()) c.drawText(towNames, 0, towNames.length, x, base, towBoldPaint)
        }
        if (showLanes) drawLanes(c, t.text, t.text3)
    }

    private fun drawLanes(c: Canvas, lit: Int, dim: Int) {
        prepareTray()
        c.drawPath(trayPath, trayFill)
        c.save()
        c.clipPath(trayPath)
        c.drawRect(trayRect.left, trayTop, trayRect.right, trayTop + 7f, trayShade)
        val n = laneCount
        val cellW = INNER_W / n
        val gs = min(LANE_GLYPH, cellW - 6f)
        val shader = poolShader
        for (i in 0 until n) {
            val x0 = PAD_X + cellW * i
            val cx = x0 + cellW / 2f
            val level = laneLevel[i]
            if (level > 0) {
                if (shader != null) {
                    val boxW = cellW * 0.8f
                    c.unitOval(pool, shader, cx, trayTop + TRAY_H, boxW * 0.6f, TRAY_H * 0.75f, if (level == 2) 255 else 128)
                }
                markRect.set(x0 + cellW * 0.26f, trayTop + TRAY_H - 10f, x0 + cellW * 0.74f, trayTop + TRAY_H - 6f)
                mark.alpha = if (level == 2) 255 else 140
                c.drawRoundRect(markRect, 2f, 2f, mark)
            }
            val dirs = laneDirs[i]
            val litMask = laneLit[i]
            val litColor = if (level == 2) lit else GlassColor.alpha(lit, 0.78f)
            c.save()
            c.translate(cx, trayTop + TRAY_H / 2f)
            c.scale(gs / 40f, gs / 40f)
            c.translate(-20f + LaneGlyphs.nudge(dirs, lht), -20f)
            drawArrows(c, dirs, if (level > 0) litMask else 0, dim)
            if (level > 0) drawArrows(c, dirs and litMask, 0, litColor)
            c.restore()
            if (i > 0) {
                var y = trayTop + 12f
                val end = trayTop + TRAY_H - 12f
                while (y < end) {
                    c.drawRect(x0 - 1f, y, x0 + 1f, min(y + 7f, end), dash)
                    y += 13f
                }
            }
        }
        c.restore()
        c.drawPath(trayEdge, trayLine)
    }

    // every bit of [bits] not in [skip], so dim arrows draw first and lit ones once, on top
    private fun drawArrows(c: Canvas, bits: Int, skip: Int, color: Int) {
        if (bits == 0) return
        laneStroke.color = color; laneHead.color = color
        for (bit in LaneGlyphs.BITS) {
            if (bits and bit == 0 || skip and bit != 0) continue
            val a = LaneGlyphs.arrow(bit, lht)
            c.drawPath(a.stem, laneStroke)
            a.head?.let { c.drawPath(it, laneHead) }
        }
    }

    private fun ellipsize(s: String, p: TextPaint, width: Float): CharSequence =
        TextUtils.ellipsize(s, p, width, TextUtils.TruncateAt.END)

    companion object {
        const val CARD_W = 452f
        const val PAD_X = 20f
        const val PAD_Y = 18f
        const val ROW_H = 108f
        const val GLYPH = 108f
        const val INNER_W = CARD_W - 2f * PAD_X
        const val TEXT_COL_X = PAD_X + GLYPH + 16f
        const val TEXT_COL_W = CARD_W - PAD_X - TEXT_COL_X
        const val DIST_LH = 78f * 0.86f
        const val UNIT_GAP = 36f * 0.14f
        const val VERB_GAP = 12f
        const val VERB_LH = 21f * 1.2f
        const val STREET_GAP = 10f
        const val STREET_LH = 40f * 1.04f
        const val TOW_GAP = 6f
        const val TOW_LH = 21f * 1.2f
        const val TOW_ICON = 20f
        const val TOW_TEXT_X = PAD_X + TOW_ICON + 9f
        const val TRAY_GAP = 16f
        const val TRAY_H = 70f
        const val TRAY_R = 18f
        const val LANE_GLYPH = 44f
        const val MAX_LANES = 16
        /** Tallest card (street, sign line and lanes), for fitting the cluster stack. */
        const val MAX_H = PAD_Y + ROW_H + STREET_GAP + STREET_LH + TOW_GAP + TOW_LH + TRAY_GAP + TRAY_H + PAD_Y
    }
}
