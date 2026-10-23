package com.overdrive.app.navmap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import com.overdrive.app.overlay.glass.GlassFonts
import com.overdrive.app.overlay.glass.GlassTheme
import kotlin.math.ceil
import kotlin.math.hypot

/** Barlow text in design units (1 dp = 1 sp; the panels are fixed-size glass, so font scale does not apply). */
internal object HudType {
    fun paint(context: Context, weight: Int, size: Float, tracking: Float = 0f): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = GlassFonts.get(context, weight)
            textSize = size
            letterSpacing = tracking
            fontFeatureSettings = "'tnum'"
        }

    private val fm = Paint.FontMetrics()

    /** Baseline of [p]'s text in a CSS line box [lineH] tall starting at [top] (half-leading either side). */
    fun baseline(p: Paint, top: Float, lineH: Float): Float {
        p.getFontMetrics(fm)
        return top + (lineH - (fm.descent - fm.ascent)) / 2f - fm.ascent
    }

    /** CSS line-height: normal for Barlow (ascent 1.0 + descent 0.2 em). */
    fun normal(size: Float) = size * 1.2f
}

/**
 * A glass panel over the map: shell (shadows in the pads, body, front glass) baked by [HudShell], content drawn in
 * dp from the glass's top-left. Touches outside the glass fall through to the map; touches on it stay on it.
 */
abstract class HudPanelView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    protected val density = resources.displayMetrics.density

    internal var uiScale = 1f
        private set
    protected val u: Float get() = density * uiScale

    protected var theme: GlassTheme = GlassTheme.NIGHT
        private set

    internal abstract val shell: HudShell
    /** The glass in dp; set by [computePanel]. */
    protected var panelW = 0f
    protected var panelH = 0f
    /** Circular glass hit-tests as a circle. */
    protected open val round: Boolean get() = false

    internal val padLeftPx get() = shell.padL * u
    internal val padTopPx get() = shell.padT * u
    internal val glassWidthPx get() = panelW * u
    internal val glassHeightPx get() = panelH * u

    private val press = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Returns true if the scale changed; the caller lays out again. */
    internal fun applyScale(s: Float): Boolean {
        if (s == uiScale) return false
        uiScale = s
        shell.invalidate()
        onScaleChanged()
        invalidate()
        return true
    }

    fun setNight(night: Boolean) {
        if (theme.night == night) return
        theme = GlassTheme.of(night)
        shell.invalidate()
        onThemeChanged()
        invalidate()
    }

    protected abstract fun computePanel()
    protected abstract fun drawContent(c: Canvas)
    protected open fun onThemeChanged() {}
    protected open fun onScaleChanged() {}
    protected open fun drawOverlay(c: Canvas) {}

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        computePanel()
        setMeasuredDimension(
            ceil((panelW + shell.padL + shell.padR) * u).toInt(),
            ceil((panelH + shell.padT + shell.padB) * u).toInt()
        )
    }

    override fun onDraw(c: Canvas) {
        if (panelW <= 0f || panelH <= 0f) return
        shell.update(width, height, panelW, panelH, u, theme)
        shell.drawBack(c)
        c.save()
        c.clipPath(shell.path)
        c.translate(shell.panel.left, shell.panel.top)
        c.scale(u, u)
        drawContent(c)
        c.restore()
        shell.drawFront(c)
        drawOverlay(c)
    }

    internal fun inGlass(x: Float, y: Float): Boolean {
        shell.place(panelW, panelH, u)
        val r = shell.panel
        if (!round) return r.contains(x, y)
        return hypot(x - r.centerX(), y - r.centerY()) <= r.width() / 2f
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && !inGlass(e.x, e.y)) return false
        return super.dispatchTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = super.onTouchEvent(e) || isEnabled

    override fun setPressed(pressed: Boolean) {
        if (pressed == isPressed) return
        super.setPressed(pressed)
        if (!isClickable) return
        pivotX = shell.panel.centerX(); pivotY = shell.panel.centerY()
        animate().scaleX(if (pressed) 0.96f else 1f).scaleY(if (pressed) 0.96f else 1f)
            .setDuration(if (pressed) 90L else 160L).setInterpolator(press).start()
    }

    override fun performClick(): Boolean {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        return super.performClick()
    }

    private var shownTarget = false

    /** Fades and rises in (420 ms, 10 dp) or fades and drops out (280 ms), like the glass widgets. */
    internal fun showAnimated(show: Boolean) {
        if (show == shownTarget && (show == (visibility == VISIBLE))) return
        shownTarget = show
        animate().cancel()
        if (show) {
            visibility = VISIBLE
            alpha = 0f; translationY = 10f * density
            animate().alpha(1f).translationY(0f).setDuration(420L).setInterpolator(press).start()
        } else {
            if (visibility != VISIBLE) return
            animate().alpha(0f).translationY(6f * density).setDuration(280L).setInterpolator(press)
                .withEndAction { if (!shownTarget) { visibility = GONE; alpha = 1f; translationY = 0f } }.start()
        }
    }

    internal fun hideNow() {
        shownTarget = false
        animate().cancel()
        visibility = GONE; alpha = 1f; translationY = 0f
    }

    internal fun releaseLayers() = shell.release()
}
