package com.overdrive.app.navmap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.overdrive.app.overlay.glass.GlassColor
import com.overdrive.app.overlay.glass.GlassTheme
import kotlin.math.ceil

/** The map data credit (required by the OSM licence) on a radius-8 plate, so it never prints over a map label. */
class AttributionPlateView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private var night = true
    private val textPaint = HudType.paint(context, 500, 10.5f, 0.01f)
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var text = ""
    private var textW = 0f

    init { applyColors() }

    fun setText(t: CharSequence?) {
        val s = t?.toString().orEmpty()
        if (s == text) return
        text = s
        textW = textPaint.measureText(s)
        contentDescription = s
        requestLayout(); invalidate()
    }

    val hasText get() = text.isNotEmpty()

    fun setNight(night: Boolean) {
        if (this.night == night) return
        this.night = night; applyColors(); invalidate()
    }

    private fun applyColors() {
        val t = GlassTheme.of(night)
        textPaint.color = t.text2
        plate.color = if (night) GlassColor.alpha(t.fogPlate, 0.72f) else GlassColor.alpha(HudColors.WHITE, 0.74f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(ceil((textW + PAD_X * 2f) * density).toInt(), ceil(H * density).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (text.isEmpty()) return
        c.save()
        c.scale(density, density)
        rect.set(0f, 0f, textW + PAD_X * 2f, H)
        c.drawRoundRect(rect, 8f, 8f, plate)
        c.drawText(text, PAD_X, HudType.baseline(textPaint, PAD_Y, HudType.normal(10.5f)), textPaint)
        c.restore()
    }

    companion object {
        private const val PAD_X = 8f
        private const val PAD_Y = 2f
        private val H = PAD_Y * 2f + 10.5f * 1.2f
    }
}
