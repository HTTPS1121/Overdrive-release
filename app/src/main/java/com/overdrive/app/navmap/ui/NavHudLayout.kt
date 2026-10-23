package com.overdrive.app.navmap.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The HUD overlay (res/layout/nav_hud.xml): places each panel by its glass frame (SPEC §6, in dp inside the padding,
 * which carries the system-bar insets); shadows spill into the pads round each glass. In [cluster] mode only the card,
 * the Then tab and the compact trip summary show, scaled together to fit [clusterBounds].
 */
class NavHudLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private val dp = resources.displayMetrics.density

    internal var cluster = false
        set(v) { if (field != v) { field = v; requestLayout() } }
    /** Root-relative px; null means the padded area inset by 16 dp. */
    internal var clusterBounds: Rect? = null
        set(v) { if (field != v) { field = v; requestLayout() } }

    init {
        clipChildren = false
        clipToPadding = false
    }

    private fun <T : View> find(type: Class<T>): T? {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (type.isInstance(c)) return type.cast(c)
        }
        return null
    }

    private val area = Rect()

    private fun clusterArea(w: Int, h: Int): Rect {
        clusterBounds?.let { area.set(it); return area }
        val m = (16f * dp).roundToInt()
        area.set(paddingLeft + m, paddingTop + m, w - paddingRight - m, h - paddingBottom - m)
        return area
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val s = if (cluster) {
            val a = clusterArea(w, h)
            min(1f, min(a.height() / ((ManeuverCardView.MAX_H + ThenTabView.TAB_H - ThenTabView.TUCK) * dp), a.width() / (CLUSTER_W * dp)))
                .coerceAtLeast(0.3f)
        } else 1f
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            when (c) {
                is TopHazeView -> c.measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(paddingTop + (TopHazeView.HEIGHT * dp).roundToInt(), MeasureSpec.EXACTLY))
                is TripBarView -> {
                    c.applyScale(s)
                    if (cluster) c.measure(unspecified, unspecified)
                    else c.measure(MeasureSpec.makeMeasureSpec((tripGlassWidth(w) * dp + c.padsWidthPx).roundToInt(), MeasureSpec.EXACTLY), unspecified)
                }
                is HudPanelView -> {
                    c.applyScale(if (cluster && (c is ManeuverCardView || c is ThenTabView)) s else 1f)
                    c.measure(unspecified, unspecified)
                }
                else -> c.measure(unspecified, unspecified)
            }
        }
    }

    // the bar sits centred between the speed orb and the re-centre pill, at most 804 dp wide
    private fun tripGlassWidth(w: Int): Float {
        val inner = (w - paddingLeft - paddingRight) / dp
        val lo = M + SPEED + ORB_GAP
        val hi = inner - M - RECENTER_W - PILL_GAP
        return min(TripBarView.BAR_W, max(320f, hi - lo))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        if (cluster) layoutCluster(w, h) else layoutHead(w, h)
    }

    private fun place(v: View, glassX: Float, glassY: Float, padL: Float, padT: Float) {
        if (v.visibility == GONE) return
        val x = (glassX - padL).roundToInt(); val y = (glassY - padT).roundToInt()
        v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
    }

    private fun layoutHead(w: Int, h: Int) {
        val left = paddingLeft.toFloat(); val top = paddingTop.toFloat()
        val right = (w - paddingRight).toFloat(); val bottom = (h - paddingBottom).toFloat()
        find(TopHazeView::class.java)?.let { if (it.visibility != GONE) it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
        val card = find(ManeuverCardView::class.java)
        var cardBottom = top + M * dp
        card?.let {
            place(it, left + M * dp, top + M * dp, it.padLeftPx, it.padTopPx)
            if (it.visibility != GONE) cardBottom = top + M * dp + it.glassHeightPx
        }
        find(ThenTabView::class.java)?.let { place(it, left + M * dp, cardBottom - ThenTabView.TUCK * dp, it.padLeftPx, it.padTopPx) }
        val compass = find(CompassView::class.java)
        val compassX = right - (M + CompassView.SIZE) * dp
        compass?.let { place(it, compassX, top + M * dp, it.padLeftPx, it.padTopPx) }
        find(HudOrbKeyView::class.java)?.let {
            val x = if (compass != null && compass.visibility != GONE) compassX - (ORB_ROW_GAP + HudOrbKeyView.SIZE) * dp else compassX
            place(it, x, top + M * dp, it.padLeftPx, it.padTopPx)
        }
        find(SpeedOrbView::class.java)?.let { place(it, left + M * dp, bottom - (TRIP_BOTTOM + SpeedOrbView.SIZE) * dp, it.padLeftPx, it.padTopPx) }
        find(TripBarView::class.java)?.let {
            val gw = it.glassWidthPx
            val lo = left + (M + SPEED + ORB_GAP) * dp
            val hi = right - (M + RECENTER_W + PILL_GAP) * dp
            val x = ((left + right - gw) / 2f).coerceIn(lo, max(lo, hi - gw))
            place(it, x, bottom - (TRIP_BOTTOM + TripBarView.BAR_H) * dp, it.glassLeftPx, it.glassTopPx)
        }
        find(RecenterPillView::class.java)?.let {
            place(it, right - M * dp - it.glassWidthPx, bottom - (PILL_BOTTOM + RecenterPillView.H) * dp, it.padLeftPx, it.padTopPx)
        }
        find(AttributionPlateView::class.java)?.let {
            if (it.visibility != GONE) {
                val x = (right - ATTR_RIGHT * dp - it.measuredWidth).roundToInt()
                val y = (bottom - ATTR_BOTTOM * dp - it.measuredHeight).roundToInt()
                it.layout(x, y, x + it.measuredWidth, y + it.measuredHeight)
            }
        }
    }

    private fun layoutCluster(w: Int, h: Int) {
        val a = clusterArea(w, h)
        val card = find(ManeuverCardView::class.java) ?: return
        val s = card.uiScale
        place(card, a.left.toFloat(), a.top.toFloat(), card.padLeftPx, card.padTopPx)
        val cardBottom = a.top + (if (card.visibility != GONE) card.glassHeightPx else 0f)
        val then = find(ThenTabView::class.java)
        var rowX = a.left.toFloat()
        then?.let {
            place(it, a.left.toFloat(), cardBottom - ThenTabView.TUCK * dp * s, it.padLeftPx, it.padTopPx)
            if (it.visibility != GONE) rowX = a.left + it.glassWidthPx + 12f * dp * s
        }
        find(TripBarView::class.java)?.let { place(it, rowX, cardBottom + 8f * dp * s, it.glassLeftPx, it.glassTopPx) }
    }

    companion object {
        private const val M = 24f
        private const val SPEED = 96f
        private const val ORB_GAP = 22f
        private const val RECENTER_W = 186f
        private const val PILL_GAP = 28f
        private const val TRIP_BOTTOM = 20f
        private const val PILL_BOTTOM = 35f
        private const val ORB_ROW_GAP = 12f
        private const val ATTR_RIGHT = 10f
        private const val ATTR_BOTTOM = 5f
        /** Card plus a Then tab and trip summary side by side under it. */
        private const val CLUSTER_W = 664f
    }
}
