package com.overdrive.app.navmap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import com.overdrive.app.overlay.glass.GlassColor
import kotlin.math.abs
import kotlin.math.max

/**
 * The Re-centre pill (SPEC §6): lit accent glass with a 6 dp halo, the location arrow ringed by the auto-return
 * countdown (track white α .26, progress white, from 12 o'clock clockwise), and the label.
 */
class RecenterPillView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : HudPanelView(context, attrs) {

    override val shell = HudShell(HudShadows.PILL).apply {
        body = HudShell.Body.CTA; halo = 6f; radiusTop = H / 2f; radiusBottom = H / 2f
    }

    private var icon: Drawable? = null
    private var label = ""
    private var labelW = 0f
    private var progress = 0f
    private val labelPaint = HudType.paint(context, 600, 23f, 0.005f).apply { color = HudColors.WHITE }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.2f; strokeCap = Paint.Cap.ROUND
        color = GlassColor.alpha(HudColors.WHITE, 0.26f)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.2f; strokeCap = Paint.Cap.ROUND; color = HudColors.WHITE
    }
    private val ringOval = RectF(PAD_L + 3f, (H - ICON) / 2f + 3f, PAD_L + ICON - 3f, (H + ICON) / 2f - 3f)

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        visibility = GONE
    }

    internal fun setup(label: String, icon: Drawable, description: String) {
        this.label = label
        labelW = labelPaint.measureText(label)
        this.icon = icon.also { it.setTint(HudColors.WHITE) }
        contentDescription = description
        requestLayout()
    }

    /** Idle time over the auto-return delay, 0…1; redraws in 1/720 steps. */
    internal fun setProgress(fraction: Float): Boolean {
        val f = (fraction.coerceIn(0f, 1f) * 720f).toInt() / 720f
        if (abs(f - progress) < 1e-6f) return false
        progress = f
        if (visibility == VISIBLE) invalidate()
        return true
    }

    override fun computePanel() {
        panelH = H
        panelW = max(W, PAD_L + ICON + GAP + labelW + PAD_R)
    }

    override fun drawContent(c: Canvas) {
        c.drawOval(ringOval, track)
        if (progress > 0f) c.drawArc(ringOval, -90f, 360f * progress, false, arc)
        icon?.let {
            val top = (H - ICON) / 2f
            it.setBounds(PAD_L.toInt(), top.toInt(), (PAD_L + ICON).toInt(), (top + ICON).toInt())
            it.draw(c)
        }
        val lh = HudType.normal(23f)
        c.drawText(label, PAD_L + ICON + GAP, HudType.baseline(labelPaint, (H - lh) / 2f, lh), labelPaint)
    }

    companion object {
        const val W = 186f
        const val H = 66f
        private const val ICON = 36f
        private const val PAD_L = 15f
        private const val PAD_R = 22f
        private const val GAP = 10f
    }
}
