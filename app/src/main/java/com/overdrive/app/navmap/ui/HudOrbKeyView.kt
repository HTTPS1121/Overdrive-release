package com.overdrive.app.navmap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet

/** A round glass key in the compass's glass (64 dp, tint .92) with an icon in the text colour: the HUD's Back. */
class HudOrbKeyView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : HudPanelView(context, attrs) {

    override val shell = HudShell(HudShadows.PANEL).apply { tint = 0.92f; radiusTop = SIZE / 2f; radiusBottom = SIZE / 2f }
    override val round get() = true

    private var icon: Drawable? = null

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    internal fun setup(icon: Drawable, description: String) {
        this.icon = icon.also { it.setTint(theme.text) }
        contentDescription = description
        invalidate()
    }

    override fun computePanel() { panelW = SIZE; panelH = SIZE }

    override fun onThemeChanged() { icon?.setTint(theme.text) }

    override fun drawContent(c: Canvas) {
        icon?.let {
            val o = (SIZE - ICON) / 2f
            it.setBounds(o.toInt(), o.toInt(), (o + ICON).toInt(), (o + ICON).toInt())
            it.draw(c)
        }
    }

    companion object {
        const val SIZE = 64f
        private const val ICON = 28f
    }
}
