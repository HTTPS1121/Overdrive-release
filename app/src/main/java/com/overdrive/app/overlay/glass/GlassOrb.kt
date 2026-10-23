package com.overdrive.app.overlay.glass

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The floating shortcuts button, collapsed: a small glass orb with a sparkle. */
@SuppressLint("ViewConstructor")
class GlassOrbView(context: Context, env: GlassEnv, ticker: GlassTicker) : GlassWidgetView(context, env, ticker, SIZE, SIZE) {

    private val glyph = GlassGlyphs.Slot()

    init { setShellRadius(SIZE / 2f, true) }

    override fun drawBack(c: Canvas) {
        val t = env.theme
        c.save()
        c.translate(SIZE / 2f - 16f * G, SIZE / 2f - 16f * G)
        c.scale(G, G)
        GlassGlyphs.draw(c, glyph.get("sparkle", t, d * G, GlassGlyphs.Tone.TINT, GlassIcons.hue("sparkle")) { GlassIcons.outline("sparkle") })
        c.restore()
    }

    override fun drawLive(c: Canvas, nowMs: Long) {}
    override fun drawFront(c: Canvas) {}

    companion object {
        const val SIZE = 60f
        private const val G = 1.05f
    }
}

/**
 * The floating shortcuts button's window, over every app. Collapsed it holds only the orb: a drag moves
 * it anywhere and it settles on the nearest side; a tap opens the automation shortcuts [panel] out of
 * it, which a tap beside it or a few idle seconds close again. Idle, the orb dims. Its place is kept
 * as a side and a height, so it survives a restart and a new resolution.
 */
@SuppressLint("ViewConstructor")
internal class GlassOrbWindow(
    private val ctx: Context,
    private val wm: WindowManager,
    private val shared: GlassEnv,
    private val ticker: GlassTicker,
    private val prefs: SharedPreferences,
    private val onPanel: (GlassShortcutsView) -> Unit,
) : FrameLayout(ctx) {

    // the widgets' state but its own hidden flag: it floats over every app whether or not they show
    private val env = GlassEnv().also { it.copyFrom(shared) }
    val orb = GlassOrbView(ctx, env, ticker)
    var panel: GlassShortcutsView? = null
        private set
    /** Open, its glass in screen px; the service anchors the automation chooser to it. */
    val panelGlass = RectF()
    /** Kept open (the chooser is up): no idle close, and a touch on the chooser doesn't close it. */
    var holdOpen = false
        set(v) { field = v; if (!v) poke() }
    var isOpen = false
        private set

    private val dp = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var right = prefs.getBoolean(KEY_RIGHT, true)
    private var yFrac = prefs.getFloat(KEY_Y, 0.42f)
    private val orbGlass = RectF()
    private val win = Rect()
    private var attached = false
    private var hiddenNow = false
    private var staleNow = false
    private var released = false
    // the drag
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var onOrbDown = false
    // open: the press began beside the panel's glass, so letting go closes it
    private var downOutside = false
    private var closing = false
    private var snap: ValueAnimator? = null
    private val dim = Runnable { if (!isOpen && !hiddenNow) orb.animate().alpha(IDLE_ALPHA).setDuration(400L).start() }
    private val idleClose = Runnable { if (!holdOpen) close() }

    private val lp = WindowManager.LayoutParams(
        1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        title = "GlassShortcutsOrb"
        gravity = Gravity.TOP or Gravity.START
        // placed in whole-screen px: a status or navigation bar that comes and goes must not move it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
    }

    // only the glass takes touches; the shadow margins pass them to the app beneath
    private val touchRegion = GlassTouchRegion(this) { r ->
        r.set(glassIn(orb, orbGlass))
        if (isOpen || closing) panel?.let { r.op(glassIn(it, panelGlass), Region.Op.UNION) }
    }
    private val tmp = Rect()

    init {
        clipChildren = false; clipToPadding = false
        addView(orb, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    // ── window ───────────────────────────────────────────────────────────────

    fun show() {
        if (attached || released) return
        placeOrb()
        fit()
        try { wm.addView(this, lp); attached = true } catch (e: Exception) { Log.e(TAG, "addView: ${e.message}") }
        orb.enter(0L)
        poke()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); touchRegion.attach() }
    override fun onDetachedFromWindow() { touchRegion.detach(); super.onDetachedFromWindow() }

    private fun screen(): DisplayMetrics = DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }

    // the orb on its side, at its height, clear of a car's bars along the top and bottom
    private fun placeOrb() {
        val dm = screen()
        val size = orb.glassWPx
        val top = TOP_MIN * dp
        val bottom = dm.heightPixels - BOTTOM_MIN * dp - size
        val y = (yFrac * dm.heightPixels).coerceIn(top, max(top, bottom))
        val x = if (right) dm.widthPixels - EDGE * dp - size else EDGE * dp
        orbGlass.set(x, y, x + size, y + size)
    }

    // the panel opens over the orb toward the middle of the screen, centred on it and kept on screen
    private fun placePanel(p: GlassShortcutsView) {
        val dm = screen()
        val w = p.glassWPx; val h = p.glassHPx
        val m = EDGE * dp
        val x = (if (right) orbGlass.right - w else orbGlass.left).coerceIn(m, max(m, dm.widthPixels - m - w))
        val top = TOP_MIN * dp
        val y = (orbGlass.centerY() - h / 2f).coerceIn(top, max(top, dm.heightPixels - BOTTOM_MIN * dp - h))
        panelGlass.set(x, y, x + w, y + h)
    }

    // the window holds the orb and, open, the panel, each with its shadow margin
    private fun fit() {
        val ko = orb.unitPx
        var l = orbGlass.left - GlassWidgetView.PAD_L * ko
        var t = orbGlass.top - GlassWidgetView.PAD_T * ko
        var r = orbGlass.right + GlassWidgetView.PAD_R * ko
        var b = orbGlass.bottom + GlassWidgetView.PAD_B * ko
        val p = panel
        if (p != null && (isOpen || closing)) {
            val kp = p.unitPx
            l = min(l, panelGlass.left - GlassWidgetView.PAD_L * kp)
            t = min(t, panelGlass.top - GlassWidgetView.PAD_T * kp)
            r = max(r, panelGlass.right + GlassWidgetView.PAD_R * kp)
            b = max(b, panelGlass.bottom + GlassWidgetView.PAD_B * kp)
        }
        win.set(floor(l).toInt(), floor(t).toInt(), ceil(r).toInt(), ceil(b).toInt())
        lp.x = win.left; lp.y = win.top; lp.width = win.width(); lp.height = win.height()
        update()
        requestLayout()
    }

    private fun update() {
        if (!attached) return
        try { wm.updateViewLayout(this, lp) } catch (e: Exception) { Log.w(TAG, "update: ${e.message}") }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureChildren(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(win.width(), win.height())
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        place(orb, orbGlass)
        panel?.let { place(it, panelGlass) }
    }

    private fun place(v: GlassWidgetView, glass: RectF) {
        val k = v.unitPx
        val l = (glass.left - win.left - GlassWidgetView.PAD_L * k).roundToInt()
        val t = (glass.top - win.top - GlassWidgetView.PAD_T * k).roundToInt()
        v.layout(l, t, l + v.measuredWidth, t + v.measuredHeight)
    }

    // a glass rect in this window's px
    private fun glassIn(v: GlassWidgetView, glass: RectF): Rect {
        tmp.set((glass.left - win.left).toInt(), (glass.top - win.top).toInt(), (glass.right - win.left).toInt(), (glass.bottom - win.top).toInt())
        return tmp
    }

    /** A new screen shape or size: the orb keeps its side and height, and an open panel closes. */
    fun relayout() {
        if (isOpen) close(instant = true)
        placeOrb()
        fit()
    }

    fun setHidden(hidden: Boolean) {
        if (hidden == hiddenNow) return
        hiddenNow = hidden
        env.hidden = hidden
        if (hidden && isOpen) close(instant = true)
        visibility = if (hidden) INVISIBLE else VISIBLE
        lp.flags = if (hidden) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        update()
        if (!hidden) { orb.enter(0L); poke() }
    }

    fun setStale(stale: Boolean) { sync(); staleNow = stale; orb.stale = stale; panel?.stale = stale }
    fun themeChanged() { sync(); orb.themeChanged(); panel?.themeChanged() }

    /** The widgets' state changed (driving, motion, size, tint): this window follows, at a new size if need be. */
    fun envChanged() {
        val scale = env.scale
        sync()
        orb.envChanged(); panel?.envChanged()
        if (scale != env.scale) { orb.requestLayout(); panel?.requestLayout(); relayout() }
    }

    private fun sync() { env.copyFrom(shared); env.hidden = hiddenNow }

    fun release() {
        released = true
        removeCallbacks(dim); removeCallbacks(idleClose)
        snap?.cancel()
        orb.release(); panel?.release(); panel = null
        if (attached) try { wm.removeView(this) } catch (_: Exception) { }
        attached = false
    }

    // ── opening and closing ──────────────────────────────────────────────────

    // a touch: the orb wakes from its dim, and an open panel waits again before closing
    private fun poke() {
        removeCallbacks(dim); removeCallbacks(idleClose)
        if (released || hiddenNow) return
        if (isOpen) { if (!holdOpen) postDelayed(idleClose, IDLE_CLOSE_MS) }
        else {
            if (orb.alpha < 1f && !closing) orb.animate().alpha(1f).setDuration(150L).start()
            postDelayed(dim, DIM_MS)
        }
    }

    private fun open() {
        if (isOpen || hiddenNow || released) return
        val p = panel ?: GlassShortcutsView(ctx, env, ticker).also {
            it.sizeK = PANEL_K
            it.stale = staleNow
            panel = it
            addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            onPanel(it)
        }
        closing = false
        isOpen = true
        placePanel(p)
        fit()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        // the panel grows out of the orb, which fades under it
        val k = p.unitPx
        p.animate().cancel()
        p.pivotX = orbGlass.centerX() - (panelGlass.left - GlassWidgetView.PAD_L * k)
        p.pivotY = orbGlass.centerY() - (panelGlass.top - GlassWidgetView.PAD_T * k)
        val sc = orbGlass.width() / max(1f, panelGlass.width())
        p.scaleX = sc; p.scaleY = sc; p.alpha = 0f; p.translationX = 0f; p.translationY = 0f
        p.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(0L).setDuration(if (env.reduceMotion) 120L else 320L).setInterpolator(EASE).start()
        orb.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setStartDelay(0L).setDuration(180L).setInterpolator(EASE).start()
        poke()
    }

    /** Back into the orb; [instant] skips the motion (hiding, a new screen). */
    fun close(instant: Boolean = false) {
        if (!isOpen) return
        isOpen = false
        holdOpen = false
        val p = panel
        if (p == null || instant || env.reduceMotion) {
            p?.let { removeView(it); it.release() }
            panel = null
            closing = false
            orb.animate().cancel(); orb.alpha = 1f; orb.scaleX = 1f; orb.scaleY = 1f
            fit()
            poke()
            return
        }
        closing = true
        val sc = orbGlass.width() / max(1f, panelGlass.width())
        p.animate().scaleX(sc).scaleY(sc).alpha(0f).setStartDelay(0L).setDuration(220L).setInterpolator(EASE).withEndAction {
            if (isOpen || released) return@withEndAction
            removeView(p); p.release()
            if (panel === p) panel = null
            closing = false
            fit()
        }.start()
        orb.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60L).setDuration(220L).setInterpolator(EASE).start()
        poke()
    }

    // ── touch ────────────────────────────────────────────────────────────────

    private fun onGlass(glass: RectF, x: Float, y: Float) = glass.contains(x + win.left, y + win.top)

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (hiddenNow || released) return false
        // a touch anywhere else on screen closes the panel, unless the chooser over it is up
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { if (isOpen && !holdOpen) close(); return false }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) poke()
        return super.dispatchTouchEvent(e)
    }

    // collapsed every touch is the orb's; open, the panel's keys take theirs and a touch beside them closes it
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!isOpen) return true
        if (e.actionMasked == MotionEvent.ACTION_DOWN) downOutside = panel == null || !onGlass(panelGlass, e.x, e.y)
        return downOutside
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (isOpen) {
            // a press on the panel's glass between its keys does nothing; one beside it closes it
            if (e.actionMasked == MotionEvent.ACTION_UP && downOutside && !holdOpen) close()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onOrbDown = onGlass(orbGlass, e.x, e.y)
                if (!onOrbDown) return false
                snap?.cancel()
                downRawX = e.rawX; downRawY = e.rawY
                startX = lp.x; startY = lp.y
                dragging = false
                orb.animate().scaleX(1.08f).scaleY(1.08f).setStartDelay(0L).setDuration(140L).setInterpolator(EASE).start()
            }
            MotionEvent.ACTION_MOVE -> if (onOrbDown) {
                val dx = e.rawX - downRawX; val dy = e.rawY - downRawY
                if (!dragging && hypot(dx, dy) > slop) dragging = true
                if (dragging) moveTo(startX + dx.roundToInt(), startY + dy.roundToInt())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (onOrbDown) {
                onOrbDown = false
                orb.animate().scaleX(1f).scaleY(1f).setStartDelay(0L).setDuration(180L).setInterpolator(EASE).start()
                if (dragging) settle() else if (e.actionMasked == MotionEvent.ACTION_UP) { performClick(); open() }
                dragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    // the window follows the finger; the orb sits in it at a fixed spot while collapsed
    private fun moveTo(x: Int, y: Int) {
        val dx = x - lp.x; val dy = y - lp.y
        if (dx == 0 && dy == 0) return
        lp.x = x; lp.y = y
        win.offset(dx, dy)
        orbGlass.offset(dx.toFloat(), dy.toFloat())
        update()
    }

    // let go: it glides to the nearer side, and the place is kept
    private fun settle() {
        val dm = screen()
        right = orbGlass.centerX() > dm.widthPixels / 2f
        yFrac = (orbGlass.top / dm.heightPixels).coerceIn(0f, 1f)
        prefs.edit().putBoolean(KEY_RIGHT, right).putFloat(KEY_Y, yFrac).apply()
        val fromX = lp.x; val fromY = lp.y
        val was = RectF(orbGlass)
        placeOrb()
        val toX = fromX + (orbGlass.left - was.left).roundToInt()
        val toY = fromY + (orbGlass.top - was.top).roundToInt()
        orbGlass.set(was)
        snap?.cancel()
        snap = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (env.reduceMotion) 120L else 320L
            interpolator = SETTLE
            addUpdateListener {
                val f = it.animatedValue as Float
                moveTo((fromX + (toX - fromX) * f).roundToInt(), (fromY + (toY - fromY) * f).roundToInt())
            }
            start()
        }
        poke()
    }

    companion object {
        private const val TAG = "GlassOrb"
        private const val KEY_RIGHT = "orbRight"
        private const val KEY_Y = "orbY"
        /** The open panel is the shortcuts widget at this size. */
        private const val PANEL_K = 0.8f
        private const val EDGE = 14f
        // clear of a car's status bar along the top and its dock along the bottom
        private const val TOP_MIN = 72f
        private const val BOTTOM_MIN = 110f
        private const val IDLE_ALPHA = 0.55f
        private const val DIM_MS = 3000L
        private const val IDLE_CLOSE_MS = 6000L
        private val EASE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
        private val SETTLE = PathInterpolator(0.3f, 1.25f, 0.4f, 1f)
    }
}
