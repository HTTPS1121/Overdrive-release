package com.overdrive.app.overlay.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.graphics.Region
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * The overlay's widget stack: a row or column of glass widgets whose shadow margins overlap so the
 * shells sit [GlassWidgetView.GAP] apart. Widgets are created once per id and kept while listed;
 * a dropped widget plays its exit before the stack closes up.
 */
@SuppressLint("ViewConstructor")
class GlassWidgetsHost(context: Context, val env: GlassEnv, private val ticker: GlassTicker, onArrange: () -> Unit) : LinearLayout(context), GlassSurface {

    private val views = LinkedHashMap<String, GlassWidgetView>()
    private val exiting = ArrayList<GlassWidgetView>()
    private val entering = HashSet<String>()
    private var shown: List<String> = emptyList()
    private var vertical = false
    private var hiddenNow = false
    private var staleNow = false
    private val rebuild = Runnable { rebuild() }
    // once the widgets have faded out the window itself goes invisible, so the compositor skips it entirely
    private val hideWindow = Runnable { if (hiddenNow) visibility = INVISIBLE }
    private val press = ArrangePress(this, onArrange)
    private val loc = IntArray(2)

    // only the glass takes touches: shadow margins and the gaps between widgets pass to the app beneath
    private val touchRegion = GlassTouchRegion(this) { r ->
        r.setEmpty()
        val k = resources.displayMetrics.density * env.scale
        for (i in 0 until childCount) {
            val v = getChildAt(i) as? GlassWidgetView ?: continue
            r.op((v.left + GlassWidgetView.PAD_L * k).toInt(), (v.top + GlassWidgetView.PAD_T * k).toInt(),
                (v.right - GlassWidgetView.PAD_R * k).toInt(), (v.bottom - GlassWidgetView.PAD_B * k).toInt(), Region.Op.UNION)
        }
    }

    init { clipChildren = false; clipToPadding = false }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        touchRegion.attach()
    }

    override fun onDetachedFromWindow() {
        touchRegion.detach()
        super.onDetachedFromWindow()
    }

    // a long press on any widget's glass starts arranging; a widget's own taps still reach it until then
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = press.onEvent(e, onGlass(e.x, e.y)) || super.onInterceptTouchEvent(e)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = press.onEvent(e, onGlass(e.x, e.y)) || e.actionMasked == MotionEvent.ACTION_DOWN && onGlass(e.x, e.y)

    override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        if (disallow) press.cancel()
        super.requestDisallowInterceptTouchEvent(disallow)
    }

    private fun onGlass(x: Float, y: Float): Boolean {
        val k = resources.displayMetrics.density * env.scale
        for (i in 0 until childCount) {
            val v = getChildAt(i) as? GlassWidgetView ?: continue
            val l = v.left + GlassWidgetView.PAD_L * k; val t = v.top + GlassWidgetView.PAD_T * k
            if (x >= l && x <= v.right - GlassWidgetView.PAD_R * k && y >= t && y <= v.bottom - GlassWidgetView.PAD_B * k) return true
        }
        return false
    }

    // While a widget morphs, keep room for both of its sizes: the window then resizes once, after the
    // morph, instead of every frame (a WRAP_CONTENT window shows its old frame clipped while it shrinks).
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        var morph = false
        for (i in 0 until childCount) if ((getChildAt(i) as? GlassWidgetView)?.morphing == true) { morph = true; break }
        if (!morph) return
        var w = 0f; var h = 0f
        for (i in 0 until childCount) {
            val v = getChildAt(i) as? GlassWidgetView ?: continue
            val lp = v.layoutParams as LayoutParams
            if (vertical) { h += v.reserveHeightPx() + lp.topMargin + lp.bottomMargin; w = maxOf(w, v.reserveWidthPx()) }
            else { w += v.reserveWidthPx() + lp.leftMargin + lp.rightMargin; h = maxOf(h, v.reserveHeightPx()) }
        }
        setMeasuredDimension(maxOf(measuredWidth, w.toInt()), maxOf(measuredHeight, h.toInt()))
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : GlassWidgetView> widget(id: String): T? = views[id] as T?

    override fun show(ids: List<String>) = show(ids, vertical, gravity)

    /**
     * Shows [ids] in order. [gravity] packs the widgets against the window's anchored edges (e.g.
     * BOTTOM|START), so when the window resizes after a widget changes shape, nothing on screen moves.
     */
    fun show(ids: List<String>, vertical: Boolean, gravity: Int) {
        val layoutChanged = vertical != this.vertical
        this.vertical = vertical
        orientation = if (vertical) VERTICAL else HORIZONTAL
        this.gravity = gravity
        if (ids == shown && !layoutChanged) return
        for (id in ids) if (id !in views) {
            // a widget that joins while the stack is hidden or fogged starts that way too
            views[id] = GlassWidgets.create(id, context, env, ticker).also { it.stale = staleNow; if (hiddenNow) it.alpha = 0f }
            entering += id
        }
        for (id in shown) if (id !in ids) views.remove(id)?.let { v -> exiting += v; if (!hiddenNow) v.exit() }
        shown = ids
        removeCallbacks(rebuild)
        if (exiting.isEmpty() || hiddenNow) rebuild() else postDelayed(rebuild, GlassWidgetView.EXIT_MS + 40)
    }

    // only the children that change are removed or added, so kept widgets keep their baked layers and place
    private fun rebuild() {
        for (v in exiting) { removeView(v); v.release() }
        exiting.clear()
        for (i in childCount - 1 downTo 0) {
            val v = getChildAt(i)
            if (views.values.none { it === v } || shown.none { views[it] === v }) removeViewAt(i)
        }
        for ((i, id) in shown.withIndex()) {
            val v = views.getValue(id)
            val at = indexOfChild(v)
            if (at == i) { v.layoutParams = params(i); continue }
            if (at >= 0) removeViewAt(at)
            addView(v, i, params(i))
        }
        if (!hiddenNow) for ((i, id) in shown.withIndex()) if (entering.remove(id)) views[id]?.enter(i * 70L)
        ticker.kick()
    }

    private fun params(i: Int): LayoutParams {
        val k = resources.displayMetrics.density * env.scale
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        if (i > 0) {
            if (vertical) lp.topMargin = -((GlassWidgetView.PAD_T + GlassWidgetView.PAD_B - GlassWidgetView.GAP) * k).toInt()
            else lp.leftMargin = -((GlassWidgetView.PAD_L + GlassWidgetView.PAD_R - GlassWidgetView.GAP) * k).toInt()
        }
        return lp
    }

    /** The design width (row) or height (column) of [ids]' shells and the gaps between them, at scale 1. */
    fun extent(ids: List<String>, vertical: Boolean): Float {
        var sum = 0f
        for (id in ids) {
            val v = views[id] ?: continue
            sum += if (vertical) v.baseH else v.baseW
        }
        return sum + GlassWidgetView.GAP * (ids.size - 1).coerceAtLeast(0)
    }

    // from the layout position, never the drawn one: an entry or exit animation's offset isn't where the widget lives
    override fun shells(): Map<String, RectF> {
        val k = resources.displayMetrics.density * env.scale
        val out = LinkedHashMap<String, RectF>()
        getLocationOnScreen(loc)
        for (id in shown) {
            val v = views[id] ?: continue
            if (v.parent !== this || v.width <= 0) continue
            val x = loc[0] + v.left; val y = loc[1] + v.top
            out[id] = RectF(x + GlassWidgetView.PAD_L * k, y + GlassWidgetView.PAD_T * k, x + v.width - GlassWidgetView.PAD_R * k, y + v.height - GlassWidgetView.PAD_B * k)
        }
        return out
    }

    /** Scale changed: re-measure and re-space every widget. */
    override fun rescale() {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            v.layoutParams = params(i)
            v.requestLayout()
        }
        envChanged()
    }

    override fun themeChanged() { for (v in views.values) v.themeChanged() }

    override fun envChanged() { for (v in views.values) v.envChanged() }

    override fun setStale(stale: Boolean) {
        staleNow = stale
        for (v in views.values) v.stale = stale
    }

    /** Redraw without re-baking, e.g. for the fogged glass's "last seen" label. */
    override fun invalidateWidgets() { for (v in views.values) v.invalidate() }

    /** Hide or show the whole stack with the widgets' own exit and entry motion. */
    override fun setHidden(hidden: Boolean) {
        if (hidden == hiddenNow) return
        hiddenNow = hidden
        env.hidden = hidden
        removeCallbacks(hideWindow)
        // a hold that began before hiding must not open arrange mode over whatever took the screen
        if (hidden) press.cancel()
        if (hidden) {
            for (v in views.values) v.exit()
            postDelayed(hideWindow, GlassWidgetView.EXIT_MS + 60)
        } else {
            visibility = VISIBLE
            for ((i, id) in shown.withIndex()) views[id]?.enter(i * 70L)
            entering.clear()
        }
        envChanged()
    }

    override fun release() {
        removeCallbacks(rebuild)
        removeCallbacks(hideWindow)
        press.cancel()
        for (v in views.values) v.release()
        for (v in exiting) v.release()
        views.clear(); exiting.clear()
        removeAllViews()
    }
}
