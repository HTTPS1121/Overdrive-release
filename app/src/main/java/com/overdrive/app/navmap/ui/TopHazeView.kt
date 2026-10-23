package com.overdrive.app.navmap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.overdrive.app.overlay.glass.GlassColor
import kotlin.math.max

/** SPEC §3 top haze: aerial perspective over the far city that also quiets the map behind the card (300 dp). */
class TopHazeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var night = true
    private val paint = Paint().apply { isDither = true }
    private var builtFor = -1
    private val rect = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setNight(night: Boolean) {
        if (this.night == night) return
        this.night = night; builtFor = -1; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val h = height
        if (h <= 0) return
        if (builtFor != h) {
            builtFor = h
            val base = if (night) 0xFF070C0F.toInt() else 0xFFF4F8F5.toInt()
            val a = if (night) floatArrayOf(0.86f, 0.55f, 0.18f) else floatArrayOf(0.82f, 0.5f, 0.15f)
            paint.shader = LinearGradient(0f, 0f, 0f, max(1f, h.toFloat()),
                intArrayOf(GlassColor.alpha(base, a[0]), GlassColor.alpha(base, a[1]), GlassColor.alpha(base, a[2]), GlassColor.clear(base)),
                floatArrayOf(0f, 0.3f, 0.65f, 1f), Shader.TileMode.CLAMP)
        }
        rect.set(0f, 0f, width.toFloat(), h.toFloat())
        c.drawRect(rect, paint)
    }

    companion object {
        const val HEIGHT = 300f
    }
}
