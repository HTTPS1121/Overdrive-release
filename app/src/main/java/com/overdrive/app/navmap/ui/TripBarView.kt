package com.overdrive.app.navmap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import com.overdrive.app.overlay.glass.GlassColor
import com.overdrive.app.overlay.glass.GlassTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A trip-bar key (SPEC §6): neutral key glass, the End key's red glass, or lit accent glass while selected. */
class HudKeyView internal constructor(context: Context, private val endKey: Boolean) : HudPanelView(context) {

    override val shell = HudShell(HudShadows.KEY).apply {
        radiusTop = 22f; radiusBottom = 22f
        front = HudShell.Front.KEY
        body = if (endKey) HudShell.Body.END else HudShell.Body.KEY
    }

    private var icon: Drawable? = null
    private var label = ""
    private var labelW = 0f
    private val labelPaint = HudType.paint(context, 700, 25f, 0.01f).apply { color = HudColors.WHITE }

    /** Selected (Overview while the overview is up): lit accent glass with a white icon. */
    var lit = false
        internal set(v) {
            if (field == v || endKey) return
            field = v
            shell.body = if (v) HudShell.Body.CTA else HudShell.Body.KEY
            tintIcon(); invalidate()
        }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    internal fun setIcon(d: Drawable?) { icon = d; tintIcon(); invalidate() }

    internal fun setLabel(text: String) {
        label = text; labelW = labelPaint.measureText(text)
        requestLayout(); invalidate()
    }

    override fun onThemeChanged() = tintIcon()

    private fun tintIcon() { icon?.setTint(if (endKey || lit) HudColors.WHITE else theme.text) }

    override fun computePanel() {
        panelH = KEY
        panelW = if (endKey) max(END_W, END_PAD * 2f + END_ICON + END_GAP + labelW) else KEY
    }

    override fun drawContent(c: Canvas) {
        val ic = icon
        if (!endKey) {
            ic?.let { val o = (KEY - ICON) / 2f; it.setBounds(o.toInt(), o.toInt(), (o + ICON).toInt(), (o + ICON).toInt()); it.draw(c) }
            return
        }
        val x = (panelW - (END_ICON + END_GAP + labelW)) / 2f
        val iy = (KEY - END_ICON) / 2f
        ic?.let { it.setBounds(x.toInt(), iy.toInt(), (x + END_ICON).toInt(), (iy + END_ICON).toInt()); it.draw(c) }
        c.drawText(label, x + END_ICON + END_GAP, HudType.baseline(labelPaint, (KEY - HudType.normal(25f)) / 2f, HudType.normal(25f)), labelPaint)
    }

    companion object {
        const val KEY = 66f
        const val ICON = 30f
        const val END_W = 124f
        private const val END_ICON = 20f
        private const val END_GAP = 8f
        private const val END_PAD = 18f
    }
}

/**
 * The trip bar (SPEC §6): ARRIVE time, remaining time · distance, the traffic delay pill, and the Mute, Overview and
 * End keys. [compact] (the cluster) drops the label and the keys and sizes the glass to its text.
 */
class TripBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    private val density = resources.displayMetrics.density
    internal var uiScale = 1f
        private set
    private val u get() = density * uiScale
    private var theme: GlassTheme = GlassTheme.NIGHT

    internal val shell = HudShell(HudShadows.PANEL).apply { tint = 0.88f; radiusTop = 30f; radiusBottom = 30f }
    val muteKey = HudKeyView(context, false)
    val overviewKey = HudKeyView(context, false)
    val endKey = HudKeyView(context, true)

    internal var compact = false
        set(v) {
            if (field == v) return
            field = v
            val vis = if (v) GONE else VISIBLE
            muteKey.visibility = vis; overviewKey.visibility = vis; endKey.visibility = vis
            shell.radiusTop = if (v) COMPACT_R else 30f; shell.radiusBottom = shell.radiusTop
            applySizes(); shell.invalidate(); requestLayout(); invalidate()
        }

    private val arrivePaint = HudType.paint(context, 600, 15f, 0.1f)
    private val timePaint = HudType.paint(context, 600, 50f, -0.005f)
    private val ampmPaint = HudType.paint(context, 600, 22f)
    private val mainPaint = HudType.paint(context, 600, 28f)
    private val dotPaint = HudType.paint(context, 600, 28f)
    private val delayPaint = HudType.paint(context, 700, 19f)
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val pillDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillDotRing = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sepPaint = Paint()
    private val pillRect = RectF()

    private var labels: HudLabels? = null
    private var arriveLabel = ""
    private var dotText = ""

    // last bound values (re-formatted on a unit or clock-format change) and the keys of what is shown
    private var arriveAt = 0L
    private var remainS = 0.0
    private var remainM = 0.0
    private var delayS: Double? = null
    private var imperial = false
    private var bound = false
    private var arriveMinute = Long.MIN_VALUE
    private var remainMin = Long.MIN_VALUE
    private var distKey = Long.MIN_VALUE
    private var delayMin = Long.MIN_VALUE
    private var clock24: Boolean? = null

    private var timeText = ""
    private var ampm = ""
    private var ampmFirst = false
    private var durText = ""
    private var distText = ""
    private var delayText = ""

    // layout in dp, from the glass's top-left
    private var panelW = 804f
    private var panelH = 96f
    private var etaX = 30f
    private var etaW = 0f
    private var timeW = 0f
    private var ampmW = 0f
    private var sepX = 0f
    private var sepMargin = 26f
    private var remainX = 0f
    private var durW = 0f
    private var dotW = 0f
    private var distW = 0f
    private var mainScale = 1f
    private var pillW = 0f
    private var keysW = 0f

    init {
        setWillNotDraw(false)
        clipChildren = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(muteKey); addView(overviewKey); addView(endKey)
        applyColors()
    }

    internal fun setup(labels: HudLabels, mute: Drawable, overview: Drawable, end: Drawable) {
        this.labels = labels
        arriveLabel = labels.s.arrive.uppercase(Locale.getDefault())
        dotText = "·"
        muteKey.setIcon(mute)
        overviewKey.setIcon(overview)
        endKey.setIcon(end)
        endKey.setLabel(labels.s.end)
        endKey.contentDescription = labels.s.cdEnd
        overviewKey.contentDescription = labels.s.cdOverview
        muteKey.contentDescription = labels.s.cdMute
    }

    internal fun applyScale(s: Float): Boolean {
        if (s == uiScale) return false
        uiScale = s
        shell.invalidate()
        muteKey.applyScale(s); overviewKey.applyScale(s); endKey.applyScale(s)
        invalidate()
        return true
    }

    fun setNight(night: Boolean) {
        if (theme.night == night) return
        theme = GlassTheme.of(night)
        shell.invalidate()
        muteKey.setNight(night); overviewKey.setNight(night); endKey.setNight(night)
        applyColors(); invalidate()
    }

    internal fun setMuted(muted: Boolean, icon: Drawable) {
        muteKey.setIcon(icon)
        labels?.let { muteKey.contentDescription = if (muted) it.s.cdUnmute else it.s.cdMute }
    }

    internal fun setOverview(active: Boolean) {
        overviewKey.lit = active
        labels?.let { overviewKey.contentDescription = if (active) it.s.cdOverviewExit else it.s.cdOverview }
    }

    internal fun setImperial(imperial: Boolean) {
        if (imperial == this.imperial) return
        this.imperial = imperial
        if (bound) bind(arriveAt, remainS, remainM, delayS, imperial)
    }

    /** Returns false when every shown string is unchanged. */
    internal fun bind(arriveAtMs: Long, remainingS: Double, remainingM: Double, delay: Double?, imperial: Boolean): Boolean {
        val l = labels ?: return false
        arriveAt = arriveAtMs; remainS = remainingS; remainM = remainingM; delayS = delay; this.imperial = imperial; bound = true
        val minute = Math.floorDiv(arriveAtMs, 60_000L)
        val rm = HudFormat.minutes(remainingS)
        val dk = HudFormat.distanceKey(remainingM, imperial)
        val dm = if (delay == null || delay < 30.0) 0L else HudFormat.minutes(delay)
        val is24 = DateFormat.is24HourFormat(context)
        if (minute == arriveMinute && rm == remainMin && dk == distKey && dm == delayMin && is24 == clock24) return false
        if (minute != arriveMinute || is24 != clock24) formatClock(arriveAtMs, is24)
        arriveMinute = minute; remainMin = rm; distKey = dk; delayMin = dm; clock24 = is24
        durText = l.duration(rm)
        distText = l.distance(dk)
        delayText = if (dm > 0) String.format(l.s.delay, l.duration(dm)) else ""
        contentDescription = buildString {
            append(String.format(l.s.cdTrip, if (ampm.isEmpty()) timeText else if (ampmFirst) "$ampm $timeText" else "$timeText $ampm", durText, distText))
            if (dm > 0) append(", ").append(String.format(l.s.cdDelay, l.duration(dm)))
        }
        requestLayout()
        invalidate()
        return true
    }

    private fun formatClock(ms: Long, is24: Boolean) {
        val locale = Locale.getDefault()
        val date = Date(ms)
        try {
            val pattern = DateFormat.getBestDateTimePattern(locale, if (is24) "Hm" else "hm")
            val main = stripDayPeriod(pattern)
            timeText = SimpleDateFormat(main, locale).format(date)
            ampm = if (is24) "" else SimpleDateFormat("a", locale).format(date)
            ampmFirst = pattern.trim().let { it.startsWith("a") || it.startsWith("B") || it.startsWith("b") }
        } catch (_: RuntimeException) {
            timeText = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", locale).format(date)
            ampm = if (is24) "" else SimpleDateFormat("a", locale).format(date)
            ampmFirst = false
        }
    }

    // the day period is drawn smaller beside the time; quoted literals are kept as they are
    private fun stripDayPeriod(pattern: String): String {
        val out = StringBuilder()
        var quoted = false
        for (ch in pattern) {
            if (ch == '\'') quoted = !quoted
            if (!quoted && (ch == 'a' || ch == 'b' || ch == 'B')) continue
            out.append(ch)
        }
        return out.toString().trim()
    }

    private fun applyColors() {
        val t = theme
        arrivePaint.color = t.text2
        timePaint.color = t.text
        ampmPaint.color = t.text2
        mainPaint.color = t.text
        dotPaint.color = t.text3
        delayPaint.color = t.warn
        pillFill.color = GlassColor.alpha(t.glowWarn, 0.17f)
        pillRing.color = GlassColor.alpha(t.glowWarn, 0.42f)
        pillDot.color = t.glowWarn
        pillDotRing.color = GlassColor.alpha(t.glowWarn, 0.25f)
        sepPaint.shader = null
    }

    private fun applySizes() {
        timePaint.textSize = if (compact) 30f else 50f
        ampmPaint.textSize = if (compact) 15f else 22f
        mainPaint.textSize = if (compact) 22f else 28f
        dotPaint.textSize = mainPaint.textSize
        delayPaint.textSize = if (compact) 15f else 19f
        sepPaint.shader = null
    }

    // positions every part in dp; [width] is the glass width the parent gave, or 0 to size to the text
    private fun layoutContent(width: Float) {
        panelH = if (compact) COMPACT_H else BAR_H
        timeW = timePaint.measureText(timeText)
        ampmW = if (ampm.isEmpty()) 0f else ampmPaint.measureText(ampm) + 4f
        etaW = if (compact) timeW + ampmW else max(arrivePaint.measureText(arriveLabel), timeW + ampmW)
        mainPaint.textSize = if (compact) 22f else 28f
        dotPaint.textSize = mainPaint.textSize
        durW = mainPaint.measureText(durText)
        dotW = dotPaint.measureText(dotText) + 2f * 0.22f * mainPaint.textSize
        distW = mainPaint.measureText(distText)
        pillW = if (delayText.isEmpty()) 0f else pillWidth()
        keysW = if (compact) 0f else muteKey.glassWidthPx / u + overviewKey.glassWidthPx / u + endKey.glassWidthPx / u + 2f * KEY_GAP
        etaX = if (compact) 20f else 30f
        sepMargin = if (compact) 14f else 26f
        val padR = if (compact) 20f else 16f
        val remainW = if (compact) durW + dotW + distW + (if (pillW > 0f) 12f + pillW else 0f) else max(durW + dotW + distW, pillW)
        if (compact) {
            panelW = etaX + etaW + sepMargin * 2f + SEP_W + remainW + padR
            mainScale = 1f
        } else {
            panelW = width
            val fixed = etaX + etaW + SEP_W + remainW + MIN_SPACER + keysW + padR
            var over = fixed + sepMargin * 2f - panelW
            if (over > 0f) {
                val cut = min(over / 2f, sepMargin - 12f)
                sepMargin -= cut; over -= cut * 2f
            }
            mainScale = if (over > 0f) max(0.7f, 1f - over / max(1f, durW + dotW + distW)) else 1f
        }
        sepX = etaX + etaW + sepMargin
        remainX = sepX + SEP_W + sepMargin
    }

    private fun pillWidth(): Float {
        val dot = if (compact) 7f else 9f
        val padL = if (compact) 7f else 9f
        val padR = if (compact) 9f else 11f
        val gap = if (compact) 6f else 7f
        return padL + dot + gap + delayPaint.measureText(delayText) + padR
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val padW = (shell.padL + shell.padR) * u
        val specW = MeasureSpec.getSize(widthMeasureSpec)
        val exact = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
        val keySpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        muteKey.measure(keySpec, keySpec); overviewKey.measure(keySpec, keySpec); endKey.measure(keySpec, keySpec)
        layoutContent(if (!compact && exact) (specW - padW) / u else BAR_W)
        setMeasuredDimension(
            if (!compact && exact) specW else ceil(panelW * u + padW).toInt(),
            ceil((panelH + shell.padT + shell.padB) * u).toInt()
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (compact) return
        val gx = shell.padL * u; val gy = shell.padT * u
        var right = panelW - 16f
        for (k in arrayOf(endKey, overviewKey, muteKey)) {
            val kw = k.glassWidthPx / u
            val left = right - kw
            val top = (panelH - HudKeyView.KEY) / 2f
            val vx = (gx + left * u - k.padLeftPx).toInt()
            val vy = (gy + top * u - k.padTopPx).toInt()
            k.layout(vx, vy, vx + k.measuredWidth, vy + k.measuredHeight)
            right = left - KEY_GAP
        }
    }

    override fun onDraw(c: Canvas) {
        shell.update(width, height, panelW, panelH, u, theme)
        shell.drawBack(c)
        c.save()
        c.clipPath(shell.path)
        c.translate(shell.panel.left, shell.panel.top)
        c.scale(u, u)
        drawText(c)
        c.restore()
    }

    override fun dispatchDraw(c: Canvas) {
        c.save()
        c.clipPath(shell.path)
        super.dispatchDraw(c)
        c.restore()
        shell.drawFront(c)
    }

    private fun drawText(c: Canvas) {
        if (timeText.isEmpty()) return
        val h = panelH
        if (compact) {
            val lh = 30f * 0.95f
            val base = HudType.baseline(timePaint, (h - lh) / 2f, lh)
            drawClock(c, etaX, base)
        } else {
            val lblH = HudType.normal(15f)
            val timeH = 50f * 0.95f
            val top = (h - (lblH + 1f + timeH)) / 2f
            c.drawText(arriveLabel, etaX, HudType.baseline(arrivePaint, top, lblH), arrivePaint)
            drawClock(c, etaX, HudType.baseline(timePaint, top + lblH + 1f, timeH))
        }
        val sepH = if (compact) 28f else 52f
        if (sepPaint.shader == null) {
            val t3 = theme.text3
            sepPaint.shader = LinearGradient(0f, (h - sepH) / 2f, 0f, (h + sepH) / 2f,
                intArrayOf(GlassColor.clear(t3), t3, GlassColor.clear(t3)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(sepX, (h - sepH) / 2f, sepX + SEP_W, (h + sepH) / 2f, sepPaint)

        val mainH = mainPaint.textSize
        val pillH = if (compact) 3f + 15f * 1.1f + 3f else 4f + 19f * 1.1f + 4f
        val stacked = !compact && pillW > 0f
        val mainTop = if (stacked) (h - (mainH + 7f + pillH)) / 2f else (h - mainH) / 2f
        val base = HudType.baseline(mainPaint, mainTop, mainH)
        c.save()
        if (mainScale < 1f) { c.translate(remainX, base); c.scale(mainScale, mainScale); c.translate(-remainX, -base) }
        var x = remainX
        c.drawText(durText, x, base, mainPaint); x += durW
        c.drawText(dotText, x + 0.22f * mainPaint.textSize, base, dotPaint); x += dotW
        c.drawText(distText, x, base, mainPaint); x += distW
        c.restore()
        if (pillW > 0f) {
            val px = if (stacked) remainX else remainX + (durW + dotW + distW) * mainScale + 12f
            val py = if (stacked) mainTop + mainH + 7f else (h - pillH) / 2f
            drawPill(c, px, py, pillH)
        }
    }

    private fun drawClock(c: Canvas, x: Float, base: Float) {
        if (ampm.isEmpty()) { c.drawText(timeText, x, base, timePaint); return }
        if (ampmFirst) {
            c.drawText(ampm, x, base, ampmPaint)
            c.drawText(timeText, x + ampmW, base, timePaint)
        } else {
            c.drawText(timeText, x, base, timePaint)
            c.drawText(ampm, x + timeW + 4f, base, ampmPaint)
        }
    }

    private fun drawPill(c: Canvas, x: Float, y: Float, ph: Float) {
        pillRect.set(x, y, x + pillW, y + ph)
        c.drawRoundRect(pillRect, ph / 2f, ph / 2f, pillFill)
        pillRect.inset(0.5f, 0.5f)
        c.drawRoundRect(pillRect, ph / 2f, ph / 2f, pillRing)
        val dot = if (compact) 7f else 9f
        val dotX = x + (if (compact) 7f else 9f) + dot / 2f
        val cy = y + ph / 2f
        c.drawCircle(dotX, cy, dot / 2f + (if (compact) 2.5f else 3f), pillDotRing)
        c.drawCircle(dotX, cy, dot / 2f, pillDot)
        val tx = dotX + dot / 2f + (if (compact) 6f else 7f)
        val lh = delayPaint.textSize * 1.1f
        c.drawText(delayText, tx, HudType.baseline(delayPaint, cy - lh / 2f, lh), delayPaint)
    }

    private fun inGlass(x: Float, y: Float): Boolean {
        shell.place(panelW, panelH, u)
        return shell.panel.contains(x, y)
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && !inGlass(e.x, e.y)) return false
        return super.dispatchTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = true

    internal fun releaseLayers() {
        shell.release()
        muteKey.releaseLayers(); overviewKey.releaseLayers(); endKey.releaseLayers()
    }

    internal val glassLeftPx get() = shell.padL * u
    internal val glassTopPx get() = shell.padT * u
    internal val glassWidthPx get() = panelW * u
    internal val glassHeightPx get() = panelH * u
    internal val padsWidthPx get() = (shell.padL + shell.padR) * u

    companion object {
        const val BAR_W = 804f
        const val BAR_H = 96f
        const val COMPACT_H = 50f
        private const val COMPACT_R = 22f
        private const val SEP_W = 1.5f
        private const val KEY_GAP = 12f
        private const val MIN_SPACER = 16f
    }
}
